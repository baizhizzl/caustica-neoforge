// Indirect pass of the wavefront renderer.
//
// Resumes the continuations the primary pass queued and runs the bounce loop: volumetric light shafts
// and dynamic halos on primary segments, NEE toward the sun, static & dynamic RIS emitter lighting,
// thin-surface SSS, energy-compensated BSDF continuation and Russian roulette. It captures no guides and
// owns no motion vectors — guides and primary are deliberately absent from the include list
// below, so guide state cannot leak into this pass by accident.
//
// The build emits an ordinary TraceRay fallback and an optional EXT SER variant from this source.
import world_common;
import world_core;
import math;
import medium;
import segment;
import water;
import trace;
#ifdef CAUSTICA_ENABLE_EXT_SER
import trace_ser;
#endif
import lighting;
import sky;
import bindings;

static const float MAX_WATER_VOLUMETRIC_DIST = 40.0;
static const float VOLUMETRIC_SHADOW_DIST = 192.0;
static const float VOLUMETRIC_MIE_G = 0.72;
static const float WATER_VOLUMETRIC_MIE_G = 0.76;

// Single-scattering volumetric transport along the primary segment [0, segDist]:
//  1. Sun/moon crepuscular shafts (stained-glass tinted in air, wave-caustic modulated underwater) via
//     one near-weighted importance sample per SPP;
//  2. Analytic equi-angular scattering across active dynamic lights, with a single resampled shadow test
//     for volumetric occlusion cones around doorways, pillars, and foliage.
float3 evaluatePrimaryVolumetrics(float3 ro, float3 rd, float segDist, Medium currentMedium,
                                  bool waterWaves, inout uint seed) {
    // volStrength scales the medium coefficients directly (air extinction/scattering and the water
    // scattering strength), NOT the final radiance. This keeps the extinction used for throughput and
    // the in-scattering used for the shaft sample derived from one physical density.
    float volStrength = max(worldPush.visualParams0.x, 0.0);
    if (volStrength <= 0.0 || segDist <= 0.05) {
        return float3(0.0, 0.0, 0.0);
    }

    float3 Lvol = float3(0.0, 0.0, 0.0);
    float causticsIntensity = worldPush.visualParams0.z;
    // A solid non-water dielectric (tinted glass, ice) is not air: its interior already carries its own
    // Beer-Lambert absorption, so it must not also receive air-haze scattering or a second air extinction.
    bool isAir = isAirMedium(currentMedium);

    if (currentMedium.water) {
        float marchDist = min(segDist, MAX_WATER_VOLUMETRIC_DIST);
        float3 sigmaS = waterScatteringCoeff(worldPush.waterParams.xyz,
                worldPush.visualParams1.w * volStrength);
        if (marchDist > 0.1 && max(sigmaS.r, max(sigmaS.g, sigmaS.b)) > 1.0e-6
                && luminance(celestialLight.illuminance) > 1.0e-4) {
            // Importance sample the density along the segment: t(u) = D * (0.4u + 0.6u^2). The PDF in t
            // is 1 / (dt/du) with dt/du = D * (0.4 + 1.2u), so the unbiased Monte Carlo weight is dtDu.
            float u = rndf(seed);
            float tVol = marchDist * (0.4 * u + 0.6 * u * u);
            float dtDu = marchDist * (0.4 + 1.2 * u);
            float3 volPos = ro + rd * tVol;
            // Deep-water shafts stay crisp: give the shadow ray enough reach to find the real water top,
            // and only shade when it actually crossed one. A ray that never exits (unloaded water top or
            // a fully enclosed abyss) is not sunlight, so it contributes nothing instead of leaking a
            // bright fog wall at the chunk boundary.
            VisibilityResult vShadow = visibility(volPos, celestialLight.dir, 256.0);
            float3 vis = vShadow.transmittance;
            if (vShadow.waterHitT > 0.0 && max(vis.r, max(vis.g, vis.b)) > 0.0) {
                vis *= exp(-currentMedium.extinction * vShadow.waterHitT);
                if (waterWaves) {
                    vis *= waterCausticSpectral(volPos + celestialLight.dir * vShadow.waterHitT,
                            celestialLight.dir, vShadow.waterHitT, causticsIntensity);
                }
                float cosTheta = dot(rd, celestialLight.dir);
                float phase = hg(cosTheta, WATER_VOLUMETRIC_MIE_G);
                float3 transToEye = exp(-currentMedium.extinction * tVol);
                Lvol += transToEye * sigmaS * vis * celestialLight.illuminance * (phase * dtDu);
            }
        }
    } else if (isAir) {
        float marchDist = min(segDist, MAX_AIR_VOLUMETRIC_DIST);
        float baseSigma = max(worldPush.visualParams1.x, 0.0) * volStrength;
        float heightFalloff = worldPush.visualParams1.y;
        float seaLevelY = worldPush.visualParams1.z;
        if (marchDist > 0.25 && baseSigma > 1.0e-7 && luminance(celestialLight.illuminance) > 1.0e-3) {
            // t(u) = D * (0.35u + 0.65u^2), dt/du = D * (0.35 + 1.30u); the unbiased Monte Carlo weight
            // for importance sampling this density is dtDu.
            float u = rndf(seed);
            float tVol = marchDist * (0.35 * u + 0.65 * u * u);
            float dtDu = marchDist * (0.35 + 1.30 * u);
            float3 volPos = ro + rd * tVol;
            float sigmaS = airHazeScattering(volPos.y, baseSigma, heightFalloff, seaLevelY);
            if (sigmaS > 1.0e-7) {
                VisibilityResult vShadow = visibility(volPos, celestialLight.dir, VOLUMETRIC_SHADOW_DIST);
                float3 vis = vShadow.transmittance;
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    float cosTheta = dot(rd, celestialLight.dir);
                    float phaseMie = hg(cosTheta, VOLUMETRIC_MIE_G);
                    float phaseRay = (3.0 / (16.0 * PI)) * (1.0 + cosTheta * cosTheta);
                    float phase = 0.78 * phaseMie + 0.22 * phaseRay;
                    // Exact piecewise analytic optical depth, identical to the one applied to the main
                    // path throughput, so the shaft and the background it covers agree under thick haze.
                    float transToEye = exp(-airHazeOpticalDepth(ro, rd, tVol, baseSigma,
                            heightFalloff, seaLevelY));
                    Lvol += vis * celestialLight.illuminance * (sigmaS * phase * transToEye * dtDu);
                }
            }
        }
    }

    // Analytic equi-angular scattering for dynamic lights (handheld torches, lanterns, burning entities),
    // resampled into a single volumetric visibility query so pillars and doorways cast light cones.
    uint dynCount = min(worldPush.dynamicLightCount, 16u);
    if (dynCount > 0u && (currentMedium.water || isAir)) {
        float maxRange = max(worldPush.visualParams0.y, 4.0);
        float haloReach = min(segDist, maxRange);
        float baseSigma = max(worldPush.visualParams1.x, 0.0) * volStrength;
        float heightFalloff = worldPush.visualParams1.y;
        float seaLevelY = worldPush.visualParams1.z;
        float3 waterSigma = waterScatteringCoeff(worldPush.waterParams.xyz,
                worldPush.visualParams1.w * volStrength);
        float3 chosenHalo = float3(0.0, 0.0, 0.0);
        float3 chosenSamplePos = ro;
        float3 chosenLightPos = ro;
        uint chosenMask = CULL_SECONDARY;
        float chosenPhat = 0.0;
        float haloWSum = 0.0;

        for (uint i = 0u; i < dynCount; i++) {
            DynamicLight dl = worldPush.dynamicLights[i];
            float3 center = dl.posRadius.xyz;
            // Same minimum radius as risDynamic so the halo footprint and the surface NEE sphere agree.
            float radius = max(dl.posRadius.w, DYNAMIC_LIGHT_MIN_RADIUS);
            float3 toC = center - ro;
            float centerDist2 = dot(toC, toC);
            if (centerDist2 > (maxRange + haloReach) * (maxRange + haloReach)) {
                continue;
            }
            float tc = dot(toC, rd);
            float perp2 = max(centerDist2 - tc * tc, 0.0) + radius * radius;
            float perp = sqrt(perp2);
            float thetaA = atan((-tc) / perp);
            float thetaB = atan((haloReach - tc) / perp);
            float deltaTheta = max(thetaB - thetaA, 0.0);
            if (deltaTheta <= 1.0e-5) {
                continue;
            }
            float integral = deltaTheta / perp;
            float tSample = clamp(tc + perp * tan(lerp(thetaA, thetaB, rndf(seed))), 0.0, haloReach);
            float3 samplePos = ro + rd * tSample;
            float d2 = dot(center - samplePos, center - samplePos);
            float rangeFade = clamp(1.0 - d2 / (maxRange * maxRange), 0.0, 1.0);
            rangeFade *= rangeFade;
            // The halo density follows the local sample height exactly like the sun-shaft sampler; there
            // is no artificial floor, so haze-density == 0 (or volumetric-intensity == 0) means no glow.
            float3 trans = float3(1.0, 1.0, 1.0);
            float3 localSigma = float3(0.0, 0.0, 0.0);
            if (currentMedium.water) {
                trans = exp(-currentMedium.extinction * tSample);
                localSigma = waterSigma;
            } else {
                float airSigma = airHazeScattering(samplePos.y, baseSigma, heightFalloff, seaLevelY) * 2.0;
                localSigma = float3(airSigma, airSigma, airSigma);
                float airTrans = exp(-airHazeOpticalDepth(ro, rd, tSample, baseSigma,
                        heightFalloff, seaLevelY));
                trans = float3(airTrans, airTrans, airTrans);
            }
            // Mild forward/isotropic phase blend keeps handheld mist soft rather than blinding near the eye.
            float3 toLightDir = normalize(center - samplePos);
            float phase = 0.65 * (1.0 / (4.0 * PI)) + 0.35 * hg(dot(rd, toLightDir), 0.35);
            float3 contrib = trans * localSigma * dl.intensityMask.xyz * (phase * integral * rangeFade);
            float phat = luminance(contrib);
            if (phat <= 0.0) {
                continue;
            }
            haloWSum += phat;
            if (rndf(seed) * haloWSum < phat) {
                chosenHalo = contrib;
                chosenSamplePos = samplePos;
                chosenLightPos = center;
                chosenMask = clamp(uint(dl.intensityMask.w + 0.5), 1u, 255u);
                chosenPhat = phat;
            }
        }

        if (chosenPhat > 0.0) {
            float3 toL = chosenLightPos - chosenSamplePos;
            float dist = length(toL);
            // 1 mm tolerance only; the emitter's own geometry is hardware-culled by chosenMask, so the
            // volume shadow ray still reaches walls behind the light and cannot leak a halo through them.
            float traceDist = dist - 1.0e-3;
            VisibilityResult vShadow;
            vShadow.transmittance = float3(1.0, 1.0, 1.0);
            vShadow.waterHitT = -1.0;
            if (dist > 1.0e-4 && traceDist > RAY_TMIN) {
                vShadow = visibilityMasked(chosenSamplePos, toL / dist, traceDist, chosenMask);
            }
            float3 vis = vShadow.transmittance * waterShadowTransmittance(
                    currentMedium, vShadow.waterHitT, dist);
            Lvol += chosenHalo * vis * (haloWSum / chosenPhat);
        }
    }

    // volStrength is already baked into the coefficients above; never scale radiance at the end.
    return Lvol;
}

// Atmospheric transmittance LUT (RtSkyLut), also bound to sky.rmiss at the same binding: raygen reads it
// to colour the NEE sun/moonlight, the miss shader reads it to tint the visible discs and stars.
public float3 tracePath(PathSegment seg, uint2 pix, uint sampleIndex) {
    float3 L = float3(0.0, 0.0, 0.0);
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    // Pass A is fixed at one sample per pixel. Decorrelate each Pass B resample here so configured SPP
    // produces independent lighting/BSDF paths from the shared terminal continuation.
    uint seed = seg.seed ^ sampleIndex * 2246822519u;
    seed = pcg(seed);
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    // RIS emitter NEE: direct lighting from block emitters is active when lights are published and the
    // candidate count is non-zero. Otherwise emitters contribute only when a path hits them.
    bool risOn = worldPush.risCandidates > 0u && worldPush.lightCount > 0u && pc.lightBufAddr != 0;
    bool dynLightsOn = worldPush.dynamicLightCount > 0u;
    // Independent light-proposal RNG.
    uint proposalSeed = pix.x * 1973u + pix.y * 9277u + 26699u
            ^ worldPush.frameIndex * 2654435761u ^ sampleIndex * 2246822519u;
    proposalSeed = pcg(proposalSeed);
    MediumStack medium = seg.medium;
    bool waterWaves = (worldPush.flags & 16u) != 0u; // animated wave-normal perturbation
    bool showCelestial = seg.showCelestial;
    // True when the vertex that produced the current continuation ran risDynamic. Paired with
    // payloadDynamicEmitter() so a torch entity's own mesh is emitted exactly once (see the emission gate
    // at the opaque surface below).
    bool prevSampledDynamic = false;
    // Primary visual segments (camera prefix, plus everything before the first non-dielectric hit) own
    // the volumetric pass. The flag survives a dielectric crossing, so light shafts are continuous
    // through water/glass and a room seen through a window still gets its sun shafts.
    bool evalVolumetrics = seg.primaryVisual;
    float causticsIntensity = worldPush.visualParams0.z;
    float msGgxStrength = worldPush.visualParams0.w;
    // Air density used by the analytic optical depth. volStrength scales the coefficient, matching
    // evaluatePrimaryVolumetrics, so absorption and in-scattering come from one physical density.
    float airBaseSigma = max(worldPush.visualParams1.x, 0.0) * max(worldPush.visualParams0.x, 0.0);
    float airHeightFalloff = worldPush.visualParams1.y;
    float airSeaLevelY = worldPush.visualParams1.z;
    int indirectDepth = 0;

    // The primary pass split at a dielectric and wrote the camera→interface distance on the branch that
    // carries queue[pixelIndex]. Reconstruct that exact camera ray (same jitter, same invViewProj, same
    // camera medium as primary.rgen) and add its volumetric scattering with weight 1.0. The interface
    // Fresnel split is already folded into each continuation's throughput, but the prefix belongs to the
    // whole pixel, not to either leaf. Its air/water optical depth was also multiplied into the shared
    // throughput in primary.rgen, so both leaves inherit it exactly once.
    if (seg.preInterfaceDist > 0.0) {
        float2 dims = float2(DispatchRaysDimensions().xy);
        float2 uv = (float2(pix) + 0.5) / dims;
        float2 jndc = (uv + worldPush.jitter / dims) * 2.0 - 1.0;
        float4 nearH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
        float4 farH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
        float3 nearP = nearH.xyz / nearH.w;
        float3 farP = farH.xyz / farH.w;
        float3 camOrigin = nearP + worldPush.camOffset;
        float3 camDir = normalize(farP - nearP);
        MediumStack cameraMedium = makeMediumStack((worldPush.flags & 1u) != 0u
                ? makeDielectricMedium(true, WATER_IOR, worldPush.waterParams.xyz, 1.0)
                : airMedium());
        L += evaluatePrimaryVolumetrics(camOrigin, camDir, seg.preInterfaceDist,
                cameraMedium.current, waterWaves, seed);
    }

    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
#ifdef CAUSTICA_ENABLE_EXT_SER
        uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
        traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                               showCelestial, rayConeWidth, rayConeSpread, pathPhaseHint);
#else
        traceRadiance(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                      showCelestial, rayConeWidth, rayConeSpread);
#endif

        if (payload.hitT < 0.0) {
            // An underwater ray that misses is NOT air and must not reveal sky or air shafts: the water
            // volume has no loaded exit surface along this direction, so terminate without sky contribution.
            if (medium.current.water) {
                break;
            }
            if (evalVolumetrics && bounce <= 2) {
                L += throughput * evaluatePrimaryVolumetrics(ro, rd, MAX_AIR_VOLUMETRIC_DIST,
                        medium.current, waterWaves, seed);
                // Same analytic extinction the in-scattered sample uses. The sky behind the haze is thus
                // attenuated once by the whole capped air path and never over-brightens under thick fog.
                // Only true air gets this fog: a glass/ice interior already paid its own Beer-Lambert
                // extinction below, so applying the air column there would attenuate it twice.
                bool isAir = isAirMedium(medium.current);
                if (isAir) {
                    throughput *= exp(-airHazeOpticalDepth(ro, rd, MAX_AIR_VOLUMETRIC_DIST,
                            airBaseSigma, airHeightFalloff, airSeaLevelY));
                }
            }
            float3 sky = payloadSky();
            L += throughput * sky; // escaped to sky
            break;
        }

        if (evalVolumetrics && bounce <= 2) {
            L += throughput * evaluatePrimaryVolumetrics(ro, rd, payload.hitT,
                    medium.current, waterWaves, seed);
            // Same air-only guard as the miss branch: water and solid dielectrics are not air.
            bool isAir = isAirMedium(medium.current);
            if (isAir) {
                throughput *= exp(-airHazeOpticalDepth(ro, rd,
                        min(payload.hitT, MAX_AIR_VOLUMETRIC_DIST),
                        airBaseSigma, airHeightFalloff, airSeaLevelY));
            }
        }

        // Beer–Lambert: attenuate along the segment just travelled by the medium it lay inside.
        if (any(medium.current.extinction > 0.0)) {
            throughput *= exp(-medium.current.extinction * payload.hitT);
        }

        float3 n = payloadNormal(); // oriented toward the incoming ray by the closest-hit
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();

        if (material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC) {
            if (bounce >= maxBounces) {
                break;
            }
            bool isWater = material == MATERIAL_WATER;
            bool entering = payloadDielectricEntering();
            float3 tint = payloadAlbedo();
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);

            float3 geometricNormal = n; // unrippled; ray origins offset along this
            if (isWater && waterWaves) {
                float2 waterDomain = hitPos.xz + worldPush.waterAnchor.xy;
                float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
                n = applyWaterWaves(geometricNormal, waterDomain,
                                    worldPush.waterParams.w, waterFootprint);
            }

            Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0), tint, transmission);
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.outer.ior;

            float cosI = clamp(dot(-rd, n), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, n, etaI / etaT);
            float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

            bool chooseReflection = rndf(seed) < F;
            if (chooseReflection) {
                rd = reflect(rd, n);
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, SURF_BIAS);
            } else {
                if (dot(transmittedDir, transmittedDir) <= 0.0) break; // TIR with F < 1 cannot happen
                rd = normalize(transmittedDir);
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, transmitBias);
                if (entering) {
                    mediumPush(medium, entered);
                } else {
                    mediumPop(medium);
                }
            }
            showCelestial = true; // specular interface: the continuation ray may see the sun/moon disc
            prevSampledDynamic = false; // a dielectric does no dynamic NEE; emission may be seen again
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // Past this point the hit is a shaded (non-dielectric) surface: the primary visual chain ends.
        // The segment just traced was still evaluated above, so the surface is covered; any continuation
        // from here is indirect and must not re-integrate a primary volume.
        evalVolumetrics = false;

        int hitDepth = indirectDepth++;
        uint risCandidates = hitDepth == 0 ? worldPush.risCandidates : worldPush.risCandidatesIndirect;

        // Particles (material == 2): camera-facing receiver billboard.
        if (material == MATERIAL_PARTICLE) {
            float3 albedo = payloadAlbedo();

            float3 lightDir = celestialLight.dir;
            float lightHalfAngle = celestialLight.halfAngle;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            float signedNdl = dot(n, lightDir);
            float ndl = abs(signedNdl);
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibility(shadowOrigin, lightDir, 10000.0).transmittance;
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * celestialLight.illuminance * ndl * vis;
                }
            }

            if (risOn) {
                float3 v = -rd;
                Reservoir r = risInitial(hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                         float3(1.0, 1.0, 1.0), true, 0.0, risCandidates, seed,
                                         proposalSeed);
                L += throughput * shadeReservoir(r, hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                                 float3(1.0, 1.0, 1.0), true, 0.0);
            }

            if (dynLightsOn) {
                float3 v = -rd;
                // Independent proposal stream (same role as risInitial's proposalSeed): sampling the
                // spherical emitter must not consume the main BSDF path's RNG.
                DynamicReservoir dr = risDynamic(hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                                 float3(1.0, 1.0, 1.0), true, 0.0, proposalSeed);
                L += throughput * shadeDynamicReservoir(dr, hitPos, n, v, rd, albedo,
                                                        float3(0.0, 0.0, 0.0), 1.0,
                                                        float3(1.0, 1.0, 1.0), true, 0.0, medium.current);
            }

            if (bounce >= maxBounces) {
                break;
            }
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false;
            // The billboard ran dynamic NEE above when lights exist; the next diffuse hit is exactly the
            // bounce whose emitter-mesh emission must be suppressed.
            prevSampledDynamic = dynLightsOn;
            continue;
        }

        float3 albedo = payloadAlbedo();
        float sss = payloadSss(); // LabPBR SSS strength (0 when absent)
        float3 p = hitPos + n * SURF_BIAS;
        float3 v = -rd; // view direction (toward the camera / incoming ray)

        float rough = clamp(payloadRoughness(), 0.0, 1.0);
        float metal = clamp(payloadMetalness(), 0.0, 1.0);
        bool exactSpecular = isDeltaAlpha(rough);
        rough = exactSpecular ? 0.0 : rough;
        float3 diffAlb = albedo * (1.0 - metal);
        float3 F0 = payload.f0;
        // Multiple-scattering GGX compensation is a vertex invariant: F0, the view cosine, the roughness
        // and the global strength are fixed for every NEE/RIS/BSDF estimate at this shading point, so the
        // Turquin scale is evaluated once here instead of once per candidate inside the lighting loops.
        float3 msScale = ggxMultiScatterScale(F0, max(1.0e-4, dot(n, v)), rough, msGgxStrength);

        float emission = payloadEmission();
        // Static emitters in the RIS list are covered by risInitial NEE and must not emit again on diffuse
        // continuation rays. Dynamic emitter entity meshes follow the same rule, but their NEE ran only on
        // the previous bounce (hitDepth <= 1), so the previous vertex records that in prevSampledDynamic.
        // showCelestial (primary/specular/refraction) always keeps the mesh visible.
        bool gateStaticEmitter = risOn && payloadEmitterInList();
        bool gateDynamicEmitter = prevSampledDynamic && payloadDynamicEmitter();
        if (emission > 0.0 && ((!gateStaticEmitter && !gateDynamicEmitter) || showCelestial)) {
            L += throughput * albedo * emission;
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night).
        float3 lightDir = celestialLight.dir;
        float lightHalfAngle = celestialLight.halfAngle;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
        }
        float ndl = max(0.0, dot(n, lightDir));
        if (ndl > 0.0) {
            VisibilityResult shadow = visibility(p, lightDir, 10000.0);
            float3 vis = shadow.transmittance;
            if (medium.current.water && waterWaves && shadow.waterHitT > 0.0) {
                vis *= waterCausticSpectral(p + lightDir * shadow.waterHitT, lightDir, shadow.waterHitT,
                        causticsIntensity);
            }
            if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                float3 brdf = diffAlb * INV_PI;
                float3 h = normalize(lightDir + v);
                float ndh = max(0.0, dot(n, h));
                float ndv = max(1.0e-4, dot(n, v));
                float vdh = max(0.0, dot(v, h));
                float D = ggxD(ndh, rough);
                float G = ggxG1(ndv, rough) * ggxG1(ndl, rough);
                float3 F = fresnelSchlick(vdh, F0);
                brdf += (D * G) * (F * msScale) / (4.0 * ndv * ndl);
                L += throughput * brdf * celestialLight.illuminance * ndl * vis;
            }
        }

        // RIS direct lighting from static block emitters.
        if (risOn) {
            float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;
            Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, msScale, false, activeSss,
                                     risCandidates, seed, proposalSeed);
            L += throughput * shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough, msScale, false,
                                             activeSss);
        }

        // RIS direct + first-bounce indirect lighting from dynamic spherical emitters (handheld torches,
        // lanterns, dropped glowing items, burning or bioluminescent entities). Running at hitDepth <= 1
        // gives caves and rooms warm colour-bleed bounce illumination from handheld torches in addition to
        // direct shadows and PBR specular highlights.
        if (dynLightsOn && hitDepth <= 1) {
            float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;
            // Independent proposal stream, exactly like risInitial's proposalSeed; the BSDF path's seed
            // stays untouched so emitter sampling cannot perturb the continuation direction.
            DynamicReservoir dr = risDynamic(hitPos, n, v, rd, diffAlb, F0, rough, msScale, false,
                                             activeSss, proposalSeed);
            L += throughput * shadeDynamicReservoir(dr, hitPos, n, v, rd, diffAlb, F0, rough, msScale,
                                                    false, activeSss, medium.current);
        }

        // Thin-surface SSS transmission from celestial light.
        if (sss > 0.0 && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                VisibilityResult shadowBack = visibility(hitPos - n * SURF_BIAS, lightDir, 10000.0);
                float3 visB = shadowBack.transmittance;
                if (medium.current.water && waterWaves && shadowBack.waterHitT > 0.0) {
                    visB *= waterCausticSpectral(hitPos + lightDir * shadowBack.waterHitT,
                                                 lightDir, shadowBack.waterHitT, causticsIntensity);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * celestialLight.illuminance * visB;
                }
            }
        }

        if (bounce >= maxBounces) {
            break;
        }

        // Did this vertex run risDynamic? Only hitDepth 0 and 1 do. Recorded on a diffuse continuation so
        // the next hit can tell whether the torch it sees was already sampled as a light by NEE.
        bool sampledDynamicHere = dynLightsOn && hitDepth <= 1;

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe.
        float ps = exactSpecular && luminance(diffAlb) <= 1.0e-6
                       ? 1.0
                       : clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
        if (rndf(seed) < ps) {
            float3 l;
            if (exactSpecular) {
                l = reflect(rd, n);
                throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0) / ps;
            } else {
                float3 h = sampleGGXVNDF(n, v, rough, seed);
                l = reflect(rd, h);
                float ndl2 = dot(n, l);
                if (ndl2 <= 0.0) {
                    break;
                }
                float3 F = fresnelSchlick(max(0.0, dot(v, h)), F0);
                throughput *= (F * msScale) * ggxG1(ndl2, rough) / ps;
                rayConeSpread = max(rayConeSpread, rough * RAY_CONE_GLOSSY_SPREAD_SCALE);
            }
            ro = p;
            rd = l;
            showCelestial = true;
            // A specular continuation is a delta path: it may legitimately see the emitter mesh, and the
            // next vertex must not inherit this vertex's diffuse-NEE bookkeeping.
            prevSampledDynamic = false;
        } else {
            throughput *= diffAlb / (1.0 - ps);
            ro = p;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false;
            prevSampledDynamic = sampledDynamicHere;
        }

        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    return L;
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    celestialLight = dominantCelestialLight(skyState(worldPush), transmittanceLut);
    celestialLight.illuminance = bt709ToAcesCg(celestialLight.illuminance);
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    uint spp = max(worldPush.spp, 1u);
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    uint recordIndex = pixelIndex;
    for (uint leaf = 0u; leaf < MAX_PATH_SEGMENTS; ++leaf) {
        PackedPathSegment packed = queue[recordIndex];
        PathSegment segment = unpackPathSegment(packed);
        for (uint s = 0u; s < spp; ++s) {
            frameRadiance += tracePath(segment, uint2(pix), s + leaf * spp);
        }
        if (packed.nextRecord == PATH_NO_NEXT) {
            break;
        }
        recordIndex = packed.nextRecord;
    }
    float3 stored = frameRadiance * (worldPush.preExposure / float(spp));
    outImage[pix] = float4(clamp(stored, float3(0.0), float3(HALF_MAX)), 1.0);
}
