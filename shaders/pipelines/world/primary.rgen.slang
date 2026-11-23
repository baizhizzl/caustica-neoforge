// Primary/guide pass of the wavefront renderer.
//
// Traces one camera radiance ray, captures DLSS-RR guides, and writes one or two resumable
// continuations per pixel. At the first eligible dielectric it queues both post-interface branches and
// stops; every later radiance trace belongs to Pass B. Deterministic guide probes may continue through
// refraction, but Pass A does no NEE, RIS, SSS, or BSDF sampling.
//
// Perspective setup: invViewProj = inverse(proj * viewRot) maps clip to camera-relative world. Primary
// rays are reconstructed from two unprojected points (near and far plane) rather than assuming the eye
// is at the origin, because Minecraft bakes view bobbing into the projection, so the effective eye comes
// from the matrix. camOffset shifts camera-relative space into the terrain's rebased coordinates. Depth
// is reversed-Z (near=1, far=0).
//
// Pass A deliberately uses ordinary TraceRay. Its short, visually coherent direct/interface walk is
// latency-sensitive and does not amortize an SER reorder barrier.
import world_common;
import world_core;
import bindings;
import math;
import medium;
import segment;
import water;
import trace;
import guides;

public PathSegment tracePrimary(PathSegment seg,
                                DevicePtr<PackedPathSegment> queue,
                                uint splitRecord,
                                out uint nextRecord,
                                out bool cachedPrimary) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    MediumStack medium = seg.medium;
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    uint seed = seg.seed;
    bool showCelestial = seg.showCelestial;
    bool waterWaves = (wp[0].flags & WORLD_FLAG_WATER_WAVES) != 0u;
    nextRecord = PATH_NO_NEXT;
    cachedPrimary = false;

    {
        int bounce = seg.bounce;
        PathSegment terminal = makePathSegment(ro, rd, throughput, medium,
                                               rayConeWidth, rayConeSpread, seed, bounce,
                                               showCelestial, true, 0.0);
        traceRadiance(CULL_PRIMARY, ro, 0.0, rd, 10000.0,
                      showCelestial, rayConeWidth, rayConeSpread);

        if (payload.hitT < 0.0) {
            if (bounce == 0) {
                gv_normal = float3(0.0, 0.0, 0.0);
                gv_albedo = SKY_DIFF_ALBEDO;
                gv_emissive = false;
                gv_rough = 1.0;
                gv_hitCamRel = rd * 1.0e6;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0);
                gv_spec = makeSpecSurface(gv_hitCamRel, gv_normal, 1.0, SKY_SPEC_ALBEDO);
                queue[splitRecord] = packCachedPrimaryHit(rd, payload);
                cachedPrimary = true;
            }
            return terminal;
        }

        float3 n = payloadNormal();
        float3 hitPos = ro + rd * payload.hitT;
        uint material = payloadMaterial();
        float3 camOffset = wp[0].camOffset;
        if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) {
            float rough = material == MATERIAL_PARTICLE
                    ? 1.0 : clamp(payloadRoughness(), 0.0, 1.0);
            bool exactSpecular = material == MATERIAL_OPAQUE && isDeltaAlpha(rough);
            rough = exactSpecular ? 0.0 : rough;
            float metal = material == MATERIAL_PARTICLE
                    ? 0.0 : clamp(payloadMetalness(), 0.0, 1.0);
            float3 hitAlbedo = payloadAlbedo();
            float3 diffAlb = material == MATERIAL_PARTICLE
                    ? hitAlbedo : hitAlbedo * (1.0 - metal);
            if (bounce == 0) {
                gv_normal = n;
                gv_hitCamRel = hitPos - camOffset;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = payload.motionPrev;
                gv_emissive = payloadEmission() > 0.0;
                if (material == MATERIAL_PARTICLE) {
                    gv_albedo = hitAlbedo;
                    gv_rough = 1.0;
                    gv_spec = makeSpecSurface(gv_hitCamRel, n, 1.0,
                                              float3(0.0, 0.0, 0.0));
                } else {
                    float3 v = -rd;
                    gv_albedo = diffAlb;
                    gv_rough = rough;
                    // Keep the DLSS-RR specular guide identical to what the image actually renders: the
                    // multiple-scattering compensation is part of the shaded specular response, so the
                    // guide must carry the same scale (Turquin factor) inside the same [0,1] clamp.
                    float specNoV = max(1.0e-4, dot(n, v));
                    float3 specGuide = rrSpecularAlbedo(payload.f0, rough, specNoV)
                            * ggxMultiScatterScale(payload.f0, specNoV, rough,
                                    wp[0].visualParams0.w);
                    gv_spec = makeSpecSurface(gv_hitCamRel, n, float3(payload.motionPrev), rough,
                                              clamp(specGuide, float3(0.0), float3(1.0)));
                }
                queue[splitRecord] = packCachedPrimaryHit(rd, payload);
                cachedPrimary = true;
            }

            return terminal;
        }

        // This hit is consumed in pass A, so carry its travelled-medium absorption and cone growth into
        // the record for the next trace. The terminal hit above is deliberately not consumed here.
        if (any(medium.current.extinction > 0.0)) {
            throughput *= exp(-medium.current.extinction * payload.hitT);
        }
        float preDist = max(payload.hitT, 0.0);
        // Fold the pre-interface AIR haze extinction into the shared throughput before the Fresnel split.
        // Both the reflected and deferred leaves then carry the exact same prefix attenuation, and
        // indirect.rgen adds only the prefix's in-scattered radiance (once, with pixel weight 1.0). This is
        // what makes the two leaves agree at the interface without any branch-specific bookkeeping.
        // Only true air (not water, and not a non-water dielectric volume such as tinted glass or ice)
        // contributes: such a solid already carries its own Beer-Lambert absorption along the segment, so
        // adding air haze inside it would scatter light in a vacuum and attenuate it twice.
        bool isAir = isAirMedium(medium.current);
        if (isAir) {
            float4 vp1 = wp[0].visualParams1;
            float airBaseSigma = max(vp1.x, 0.0)
                    * max(wp[0].visualParams0.x, 0.0);
            throughput *= exp(-airHazeOpticalDepth(ro, rd,
                    min(preDist, MAX_AIR_VOLUMETRIC_DIST), airBaseSigma,
                    vp1.y, vp1.z));
        }
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0),
                           RAY_CONE_MIN_WIDTH);

        bool isWater = material == MATERIAL_WATER;
        float dielectricRough = isWater ? 0.0 : clamp(payloadRoughness(), 0.0, 1.0);
        dielectricRough = isDeltaAlpha(dielectricRough) ? 0.0 : dielectricRough;
        bool frostedDielectric = dielectricRough >= FROSTED_DIELECTRIC_ALPHA_MIN;
        bool entering = payloadDielectricEntering();
        float3 geometricNormal = n;
        float3 previousNormal = n;
        if (isWater && waterWaves) {
            float4 wAnchor = wp[0].waterAnchor;
            float waveTime = wp[0].waterParams.w;
            float2 waterDomain = hitPos.xz + wAnchor.xy;
            float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
            if (bounce == 0 && abs(geometricNormal.y) >= 0.5) {
                float2 currentGrad;
                float2 previousGrad;
                waterWaveGradTemporal(waterDomain, waveTime,
                                      wAnchor.z, waterFootprint, currentGrad, previousGrad);
                float orientation = geometricNormal.y >= 0.0 ? 1.0 : -1.0;
                n = clampWaterWaveNormal(
                        orientation * normalize(float3(-currentGrad.x, 1.0, -currentGrad.y)),
                        geometricNormal, rd);
                previousNormal = clampWaterWaveNormal(
                        orientation * normalize(float3(-previousGrad.x, 1.0, -previousGrad.y)),
                        geometricNormal, rd);
            } else {
                n = clampWaterWaveNormal(
                        applyWaterWaves(geometricNormal, waterDomain, waveTime, waterFootprint),
                        geometricNormal, rd);
            }
        }

        float transmission = clamp(payloadTransmission(), 0.0, 1.0);
        Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0),
                                              payloadAlbedo(), transmission);
        float etaI = medium.current.ior;
        float etaT = entering ? entered.ior : medium.outer.ior;
        float F = fresnelDielectric(clamp(dot(-rd, n), 0.0, 1.0), etaI, etaT);
        float3 transmittedDir = refract(rd, n, etaI / etaT);
        bool validTransmission = dot(transmittedDir, transmittedDir) > 0.0
                && dot(transmittedDir, geometricNormal) < 0.0;
        float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

        if (bounce == 0) {
            gv_normal = n;
            gv_rough = dielectricRough;
            gv_albedo = float3(0.0, 0.0, 0.0);
            gv_emissive = false;
            gv_hitCamRel = hitPos - camOffset;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = isWater ? float3(0.0, 0.0, 0.0) : payload.motionPrev;
            gv_spec = makeSpecSurface(gv_hitCamRel, n, previousNormal, geometricNormal,
                                      isWater ? float3(0.0, 0.0, 0.0) : float3(payload.motionPrev),
                                      dielectricRough, float3(F, F, F));
            float3 glassFilter = !isWater && entering
                    ? payloadAlbedo() : float3(1.0, 1.0, 1.0);
            // Snapshot the primary hit BEFORE resolveTransmissionGuide's traceGuide() overwrites the
            // module-level payload with the transmitted background surface.
            if (frostedDielectric) {
                queue[splitRecord] = packCachedPrimaryHit(rd, payload);
                cachedPrimary = true;
            }
            // Heavily frosted dielectrics (> SPEC_MOTION_ALPHA_MAX) keep their own surface depth/normal so
            // DLSS-RR does not reproject crisp background silhouettes across blurred refraction.
            if (dielectricRough <= SPEC_MOTION_ALPHA_MAX && validTransmission) {
                MediumStack guideMedium = medium;
                if (entering) {
                    mediumPush(guideMedium, entered);
                } else {
                    mediumPop(guideMedium);
                }
                resolveTransmissionGuide(hitPos, transmittedDir, geometricNormal,
                        guideMedium, transmitBias, rayConeWidth, rayConeSpread,
                        glassFilter);
                gv_rough = max(gv_rough, dielectricRough);
            }
            if (frostedDielectric) {
                return terminal;
            }
        }

        // Split once, write both post-interface continuations, and stop Pass A radiance traversal.
        // Pass B owns every radiance trace after this point.
        bool splitEligible = validTransmission && F > 0.0 && F < 1.0;
        if (splitEligible) {
            MediumStack transmittedMedium = medium;
            if (entering) {
                mediumPush(transmittedMedium, entered);
            } else {
                mediumPop(transmittedMedium);
            }
            float3 deferredDir = normalize(transmittedDir);
            // The split record must NOT repeat the prefix volume: the main record below is the one that
            // carries preInterfaceDist and integrates [camera, interface] exactly once for the pixel.
            PathSegment deferred = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, deferredDir, transmitBias),
                    deferredDir, throughput * (1.0 - F), transmittedMedium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, true, 0.0);
            queue[splitRecord] = packPathSegment(deferred, PATH_NO_NEXT);
            nextRecord = splitRecord;
            float3 reflectedDir = reflect(rd, n);
            PathSegment reflected = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, reflectedDir, SURF_BIAS),
                    reflectedDir, throughput * F, medium,
                    rayConeWidth, rayConeSpread, seed ^ 0xa511e9b3u, bounce + 1,
                    true, true, preDist);
            return reflected;
        }

        bool hasTransmission = validTransmission;
        PathSegment continuation;
        if (!hasTransmission || F >= 1.0) {
            float3 reflectedDir = reflect(rd, n);
            continuation = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, reflectedDir, SURF_BIAS),
                    reflectedDir, throughput * F, medium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, true, preDist);
        } else {
            float3 deferredDir = normalize(transmittedDir);
            if (entering) {
                mediumPush(medium, entered);
            } else {
                mediumPop(medium);
            }
            continuation = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, deferredDir, transmitBias),
                    deferredDir, throughput * (1.0 - F), medium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true, true, preDist);
        }
        return continuation;
    }
}

[shader("raygeneration")]
void main() {
    ConstPtr<WorldPush> wp = worldPushPtr();
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    float2 size = float2(dimensions);
    float2 uv = (float2(pix) + 0.5) / size;
    float2 jndc = (uv + wp[0].jitter / size) * 2.0 - 1.0;
    float4 nearH = mul(wp[0].invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
    float4 farH = mul(wp[0].invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    float3 origin = nearP + wp[0].camOffset;
    float3 dir = normalize(farP - nearP);
    float rayConeSpread = primaryRayConeSpreadFromClip(nearH, farH, size, dir);
    uint seed = (dispatchIndex.x * 1973u + dispatchIndex.y * 9277u + 26699u)
            ^ (wp[0].frameIndex * 2654435761u);
    MediumStack cameraMedium = makeMediumStack((wp[0].flags & WORLD_FLAG_SUBMERGED) != 0u
            ? makeDielectricMedium(true, WATER_IOR, wp[0].waterParams.xyz, 1.0)
            : airMedium());
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    uint baseRecordCount = dimensions.x * dimensions.y;
    uint splitRecord = baseRecordCount + pixelIndex;
    DevicePtr<PackedPathSegment> queue = DevicePtr<PackedPathSegment>(pc.pathQueueAddr);

    seed = pcg(seed);
    PathSegment current = makePathSegment(origin, dir, float3(1.0, 1.0, 1.0),
            cameraMedium, 0.0, rayConeSpread, seed, 0, true, true, 0.0);
    uint nextRecord;
    bool cachedPrimary;
    PathSegment terminal = tracePrimary(
            current, queue, splitRecord, nextRecord, cachedPrimary);
    PackedPathSegment mainPacked = packPathSegment(terminal, nextRecord);
    if (cachedPrimary) {
        mainPacked.pathFlags |= PATH_FLAG_CACHED_PRIMARY;
    }
    queue[pixelIndex] = mainPacked;

    writeGuides(pix, dir, jndc, size, rayConeSpread);
}
