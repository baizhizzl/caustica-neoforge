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
    ConstPtr<WorldPush> wp = worldPushPtr();
    float4 vp0 = wp[0].visualParams0;
    float volStrength = max(vp0.x, 0.0);
    if (volStrength <= 0.0 || segDist <= 0.05) {
        return float3(0.0, 0.0, 0.0);
    }

    float3 Lvol = float3(0.0, 0.0, 0.0);
    float causticsIntensity = vp0.z;
    float4 vp1 = wp[0].visualParams1;
    // A solid non-water dielectric (tinted glass, ice) is not air: its interior already carries its own
    // Beer-Lambert absorption, so it must not also receive air-haze scattering or a second air extinction.
    bool isAir = isAirMedium(currentMedium);

    if (currentMedium.water) {
        float marchDist = min(segDist, MAX_WATER_VOLUMETRIC_DIST);
        float3 sigmaS = waterScatteringCoeff(wp[0].waterParams.xyz,
                vp1.w * volStrength);
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
        float baseSigma = max(vp1.x, 0.0) * volStrength;
        float heightFalloff = vp1.y;
        float seaLevelY = vp1.z;
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
    uint dynCount = min(wp[0].dynamicLightCount, 16u);
    if (dynCount > 0u && (currentMedium.water || isAir)) {
        float baseSigma = max(vp1.x, 0.0) * volStrength;
        float3 waterSigma = currentMedium.water
                ? waterScatteringCoeff(wp[0].waterParams.xyz, vp1.w * volStrength)
                : float3(0.0, 0.0, 0.0);
        bool hasMediumDensity = currentMedium.water
                ? max(waterSigma.r, max(waterSigma.g, waterSigma.b)) > 1.0e-7
                : baseSigma > 1.0e-7;
        if (hasMediumDensity) {
            float maxRange = max(vp0.y, 4.0);
            float maxVolDist = min(segDist,
                    currentMedium.water ? MAX_WATER_VOLUMETRIC_DIST : MAX_AIR_VOLUMETRIC_DIST);
            float heightFalloff = vp1.y;
            float seaLevelY = vp1.z;
            float3 chosenHalo = float3(0.0, 0.0, 0.0);
            float3 chosenSamplePos = ro;
            float3 chosenLightPos = ro;
            uint chosenMask = CULL_SECONDARY;
            float chosenPhat = 0.0;
            float haloWSum = 0.0;

            for (uint i = 0u; i < dynCount; i++) {
                DynamicLight dl = wp[0].dynamicLights[i];
                float3 center = dl.posRadius.xyz;
                // Same minimum radius as risDynamic so the halo footprint and the surface NEE sphere agree.
                float radius = max(dl.posRadius.w, DYNAMIC_LIGHT_MIN_RADIUS);
                float3 toC = center - ro;
                float centerDist2 = dot(toC, toC);
                float tc = dot(toC, rd);
                float dPerp2 = max(centerDist2 - tc * tc, 0.0);
                float maxRange2 = maxRange * maxRange;
                if (dPerp2 >= maxRange2) {
                    continue;
                }
                float halfSpan = sqrt(maxRange2 - dPerp2);
                float tMin = max(0.0, tc - halfSpan);
                float tMax = min(maxVolDist, tc + halfSpan);
                if (tMax <= tMin) {
                    continue;
                }
                float perp2 = dPerp2 + radius * radius;
                float perp = sqrt(perp2);
                float thetaA = atan((tMin - tc) / perp);
                float thetaB = atan((tMax - tc) / perp);
                float deltaTheta = max(thetaB - thetaA, 0.0);
                if (deltaTheta <= 1.0e-5) {
                    continue;
                }
                float integral = deltaTheta / perp;
                float tSample = clamp(tc + perp * tan(lerp(thetaA, thetaB, rndf(seed))), tMin, tMax);
                float3 samplePos = ro + rd * tSample;
                float d2 = dot(center - samplePos, center - samplePos);
                float rangeFade = clamp(1.0 - d2 / maxRange2, 0.0, 1.0);
                rangeFade *= rangeFade;
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
                float3 toLightDir = d2 > 1.0e-12 ? (center - samplePos) * rsqrt(d2) : rd;
                float phase = 0.65 * (1.0 / (4.0 * PI)) + 0.35 * hg(dot(rd, toLightDir), 0.35);
                float3 contrib = trans * localSigma * dl.intensityMask.xyz * (phase * integral * rangeFade);
                float phat = luminance(contrib);
                if (!(phat > 0.0)) {
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
                float traceDist = dist - 1.0e-3;
                VisibilityResult vShadow = makeUnoccludedVisibility();
                if (dist > 1.0e-4 && traceDist > RAY_TMIN) {
                    vShadow = visibilityMasked(chosenSamplePos, toL / dist, traceDist, chosenMask);
                }
                float3 vis = vShadow.transmittance * waterShadowTransmittance(
                        currentMedium, vShadow, dist);
                Lvol += chosenHalo * vis * (haloWSum / chosenPhat);
            }
        }
    }

    // volStrength is already baked into the coefficients above; never scale radiance at the end.
    return Lvol;
}

// Atmospheric transmittance LUT (RtSkyLut), also bound to sky.rmiss at the same binding: raygen reads it
// to colour the NEE sun/moonlight, the miss shader reads it to tint the visible discs and stars.
public float3 tracePath(PathSegment seg, uint2 pix, uint sampleIndex, uint cachedPrimaryRecord,
                        float3 prefixCamOrigin, float3 prefixCamDir, Medium prefixCameraMedium) {
    ConstPtr<WorldPush> wp = worldPushPtr();
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
    int maxBounces = int(wp[0].maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    uint primaryRisCandidates = wp[0].risCandidates;
    uint indirectRisCandidates = wp[0].risCandidatesIndirect;
    // RIS emitter NEE: direct lighting from block emitters is active when lights are published and the
    // candidate count is non-zero. Otherwise emitters contribute only when a path hits them.
    bool risOn = primaryRisCandidates > 0u && wp[0].lightCount > 0u && pc.lightBufAddr != 0;
    bool dynLightsOn = wp[0].dynamicLightCount > 0u;
    bool hasCelestialNee = max(celestialLight.illuminance.r,
            max(celestialLight.illuminance.g, celestialLight.illuminance.b)) > 1.0e-5;
    // Independent light-proposal RNG.
    uint proposalSeed = pix.x * 1973u + pix.y * 9277u + 26699u
            ^ wp[0].frameIndex * 2654435761u ^ sampleIndex * 2246822519u;
    proposalSeed = pcg(proposalSeed);
    MediumStack medium = seg.medium;
    bool waterWaves = (wp[0].flags & WORLD_FLAG_WATER_WAVES) != 0u;
    bool showCelestial = seg.showCelestial;
    bool prevSampledDynamic = false;
    float prevBsdfPdf = 0.0;
    bool evalVolumetrics = seg.primaryVisual;
    float4 vp0 = wp[0].visualParams0;
    float4 vp1 = wp[0].visualParams1;
    float causticsIntensity = vp0.z;
    float msGgxStrength = vp0.w;
    float airBaseSigma = max(vp1.x, 0.0) * max(vp0.x, 0.0);
    float airHeightFalloff = vp1.y;
    float airSeaLevelY = vp1.z;
    int indirectDepth = 0;

    if (seg.preInterfaceDist > 0.0) {
        L += evaluatePrimaryVolumetrics(prefixCamOrigin, prefixCamDir, seg.preInterfaceDist,
                prefixCameraMedium, waterWaves, seed);
    }

    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        if (bounce == seg.bounce && cachedPrimaryRecord != PATH_NO_NEXT) {
            ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
            payload = unpackCachedPrimaryHit(queue[cachedPrimaryRecord], rd);
        } else {
#ifdef CAUSTICA_ENABLE_EXT_SER
            uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
            traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                                   showCelestial, rayConeWidth, rayConeSpread, pathPhaseHint);
#else
            traceRadiance(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, 10000.0,
                          showCelestial, rayConeWidth, rayConeSpread);
#endif
        }

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
            // Non-delta BSDF bounces suppress the huge 33-degree drawn sprite in sky.rmiss and instead
            // evaluate the physical celestial square weighted by power-heuristic MIS against prevBsdfPdf.
            if (!showCelestial && hasCelestialNee && prevBsdfPdf > 0.0 && celestialLight.halfAngle > 0.0) {
                float2 sunLocal;
                if (celestialSquareLocal(rd, celestialLight.dir, celestialLight.halfAngle, sunLocal)) {
                    float pSun = celestialSquarePdf(rd, celestialLight.dir, celestialLight.halfAngle);
                    float wBsdf = powerHeuristic(prevBsdfPdf, pSun);
                    sky += celestialLight.illuminance * (pSun * wBsdf);
                }
            }
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
                float2 waterDomain = hitPos.xz + wp[0].waterAnchor.xy;
                float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
                n = clampWaterWaveNormal(
                        applyWaterWaves(geometricNormal, waterDomain,
                                wp[0].waterParams.w, waterFootprint),
                        geometricNormal, rd);
            }

            Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0), tint, transmission);
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.outer.ior;
            float dielectricRough = isWater ? 0.0 : clamp(payloadRoughness(), 0.0, 1.0);
            bool frostedDielectric = dielectricRough >= FROSTED_DIELECTRIC_ALPHA_MIN;
            float3 facetNormal = frostedDielectric ? sampleGGXVNDF(n, -rd, dielectricRough, seed) : n;

            float cosI = clamp(dot(-rd, facetNormal), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, facetNormal, etaI / etaT);
            float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

            bool hasTransmitted = dot(transmittedDir, transmittedDir) > 0.0
                    && dot(transmittedDir, geometricNormal) < 0.0;
            bool chooseReflection = !hasTransmitted || (rndf(seed) < F);
            if (chooseReflection) {
                float3 reflDir = reflect(rd, facetNormal);
                if (frostedDielectric) {
                    float ndl = dot(n, reflDir);
                    if (ndl <= 0.0) break;
                    throughput *= ggxG1(ndl, dielectricRough);
                }
                if (dot(reflDir, geometricNormal) <= 0.0) break;
                rd = reflDir;
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, SURF_BIAS);
            } else {
                float3 refrDir = normalize(transmittedDir);
                if (frostedDielectric) {
                    float ndt = -dot(n, refrDir);
                    if (ndt <= 0.0) break;
                    throughput *= ggxG1(ndt, dielectricRough);
                }
                rd = refrDir;
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, transmitBias);
                if (entering) {
                    mediumPush(medium, entered);
                } else {
                    mediumPop(medium);
                }
            }
            if (frostedDielectric) {
                rayConeSpread = max(rayConeSpread, dielectricRough * RAY_CONE_GLOSSY_SPREAD_SCALE);
            }
            if (chooseReflection || frostedDielectric) {
                // Reflection and rough refraction leave the straight-line NEE visibility domain of the
                // previous shaded vertex, so emitters/celestials along this new direction were never
                // sampled there and must be evaluated at full weight.
                showCelestial = true;
                prevBsdfPdf = 0.0;
                prevSampledDynamic = false;
            }
            // Smooth transmission preserves showCelestial, prevBsdfPdf, and prevSampledDynamic from
            // the previous vertex because straight-line shadow rays already pass through water/glass.
            if (max(throughput.r, max(throughput.g, throughput.b)) <= 1.0e-5) {
                break;
            }
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
        uint risCandidates = hitDepth == 0 ? primaryRisCandidates : indirectRisCandidates;
        bool hasBsdfContinuation = bounce < maxBounces;

        // Particles (material == 2): camera-facing receiver billboard.
        if (material == MATERIAL_PARTICLE) {
            float3 albedo = payloadAlbedo();

            if (hasCelestialNee) {
                float3 lightDir = celestialLight.dir;
                float lightHalfAngle = celestialLight.halfAngle;
                if (lightHalfAngle > 0.0) {
                    lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
                }
                float signedNdl = dot(n, lightDir);
                float ndl = abs(signedNdl);
                if (ndl > 0.0) {
                    float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                    VisibilityResult shadow = visibility(shadowOrigin, lightDir, 10000.0);
                    float3 vis = shadow.transmittance
                            * waterShadowTransmittance(medium.current, shadow, 10000.0);
                    if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                        L += throughput * albedo * INV_PI * celestialLight.illuminance * ndl * vis;
                    }
                }
            }

            if (risOn) {
                float3 v = -rd;
                Reservoir r = risInitial(hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                         float3(1.0, 1.0, 1.0), true, hasBsdfContinuation, 0.0, risCandidates, seed,
                                         proposalSeed);
                L += throughput * shadeReservoir(r, hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                                 float3(1.0, 1.0, 1.0), true, hasBsdfContinuation, 0.0,
                                                 medium.current);
            }

            if (dynLightsOn) {
                float3 v = -rd;
                DynamicReservoir dr = risDynamic(hitPos, n, v, rd, albedo, float3(0.0, 0.0, 0.0), 1.0,
                                                 float3(1.0, 1.0, 1.0), true, 0.0, proposalSeed);
                L += throughput * shadeDynamicReservoir(dr, hitPos, n, v, rd, albedo,
                                                        float3(0.0, 0.0, 0.0), 1.0,
                                                        float3(1.0, 1.0, 1.0), true, 0.0, medium.current);
            }

            if (!hasBsdfContinuation) {
                break;
            }
            throughput *= albedo;
            if (max(throughput.r, max(throughput.g, throughput.b)) <= 1.0e-5) {
                break;
            }
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false;
            prevSampledDynamic = dynLightsOn;
            prevBsdfPdf = 0.0;
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
        float ps = surfaceSpecularProb(diffAlb, F0, exactSpecular);
        // Multiple-scattering GGX compensation is a vertex invariant: F0, the view cosine, the roughness
        // and the global strength are fixed for every NEE/RIS/BSDF estimate at this shading point, so the
        // Turquin scale is evaluated once here instead of once per candidate inside the lighting loops.
        float3 msScale = ggxMultiScatterScale(F0, max(1.0e-4, dot(n, v)), rough, msGgxStrength);

        float emission = payloadEmission();
        // Direct camera/mirror/dielectric chains (showCelestial == true) see full textured emission.
        // Non-delta BSDF bounces suppress dynamic emitters already sampled at the previous vertex and
        // weight listed static RIS emitters by the complementary power-heuristic MIS weight against
        // evalSampleContrib so glossy/near-field emitter hits neither double-count nor firefly.
        bool gateStaticEmitter = risOn && payloadEmitterInList();
        bool gateDynamicEmitter = prevSampledDynamic && payloadDynamicEmitter();
        if (emission > 0.0) {
            float emitWeight = 1.0;
            if (!showCelestial) {
                if (gateDynamicEmitter) {
                    emitWeight = 0.0;
                } else if (gateStaticEmitter) {
                    if (prevBsdfPdf > 0.0) {
                        float hitDist = max(payload.hitT, 1.0e-3);
                        float hitCosL = max(dot(n, v), 1.0e-4);
                        emitWeight = powerHeuristic(prevBsdfPdf,
                                staticEmitterMisPdf(hitDist * hitDist, hitCosL));
                    } else {
                        emitWeight = 0.0;
                    }
                }
            }
            if (emitWeight > 0.0) {
                L += throughput * albedo * (emission * emitWeight);
            }
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night).
        float3 lightDir = celestialLight.dir;
        if (hasCelestialNee) {
            float lightHalfAngle = celestialLight.halfAngle;
            float wSunNee = 1.0;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
                float pSun = celestialSquarePdf(lightDir, celestialLight.dir, lightHalfAngle);
                wSunNee = surfaceNeeMisWeight(pSun, n, v, lightDir, rough, ps, exactSpecular,
                        hasBsdfContinuation);
            }
            float ndl = max(0.0, dot(n, lightDir));
            if (ndl > 0.0 && wSunNee > 0.0) {
                VisibilityResult shadow = visibility(p, lightDir, 10000.0);
                float3 vis = shadow.transmittance
                        * waterShadowTransmittance(medium.current, shadow, 10000.0);
                if (medium.current.water && waterWaves && shadow.waterHitT > 0.0) {
                    vis *= waterCausticSpectral(p + lightDir * shadow.waterHitT, lightDir, shadow.waterHitT,
                            causticsIntensity, rayConeWidth);
                }
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    float3 brdf = diffAlb * INV_PI;
                    float3 h = safeHalfVector(lightDir, v, n);
                    float ndh = max(0.0, dot(n, h));
                    float ndv = max(1.0e-4, dot(n, v));
                    float vdh = max(0.0, dot(v, h));
                    float D = ggxD(ndh, rough);
                    float G = ggxG1(ndv, rough) * ggxG1(ndl, rough);
                    float3 F = fresnelSchlick(vdh, F0);
                    brdf += (D * G) * (F * msScale) / (4.0 * ndv * ndl);
                    L += throughput * brdf * celestialLight.illuminance * (ndl * wSunNee) * vis;
                }
            }
        }

        // RIS direct lighting from static block emitters.
        if (risOn) {
            float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;
            Reservoir r = risInitial(hitPos, n, v, rd, diffAlb, F0, rough, msScale, false,
                                     hasBsdfContinuation, activeSss, risCandidates, seed, proposalSeed);
            L += throughput * shadeReservoir(r, hitPos, n, v, rd, diffAlb, F0, rough, msScale, false,
                                             hasBsdfContinuation, activeSss, medium.current);
        }

        // RIS direct + first-bounce indirect lighting from dynamic spherical emitters (handheld torches,
        // lanterns, dropped glowing items, burning or bioluminescent entities). Running at hitDepth <= 1
        // gives caves and rooms warm colour-bleed bounce illumination from handheld torches in addition to
        // direct shadows and PBR specular highlights.
        if (dynLightsOn && hitDepth <= 1) {
            float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;
            DynamicReservoir dr = risDynamic(hitPos, n, v, rd, diffAlb, F0, rough, msScale, false,
                                             activeSss, proposalSeed);
            L += throughput * shadeDynamicReservoir(dr, hitPos, n, v, rd, diffAlb, F0, rough, msScale,
                                                    false, activeSss, medium.current);
        }

        // Thin-surface SSS transmission from celestial light.
        if (hasCelestialNee && sss > 0.0 && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                VisibilityResult shadowBack = visibility(hitPos - n * SURF_BIAS, lightDir, 10000.0);
                float3 visB = shadowBack.transmittance
                        * waterShadowTransmittance(medium.current, shadowBack, 10000.0);
                if (medium.current.water && waterWaves && shadowBack.waterHitT > 0.0) {
                    visB *= waterCausticSpectral(hitPos + lightDir * shadowBack.waterHitT,
                                                 lightDir, shadowBack.waterHitT, causticsIntensity, rayConeWidth);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * celestialLight.illuminance * visB;
                }
            }
        }

        if (!hasBsdfContinuation) {
            break;
        }

        bool sampledDynamicHere = dynLightsOn && hitDepth <= 1;

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe.
        if (rndf(seed) < ps) {
            float3 l;
            if (exactSpecular) {
                l = reflect(rd, n);
                throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0) / ps;
                showCelestial = true;
                prevSampledDynamic = false;
                prevBsdfPdf = 0.0;
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
                showCelestial = false;
                prevSampledDynamic = sampledDynamicHere;
                prevBsdfPdf = surfaceBsdfPdf(n, v, l, rough, ps, false);
            }
            ro = p;
            rd = l;
        } else {
            float3 l = cosineDir(n, seed);
            throughput *= diffAlb / (1.0 - ps);
            ro = p;
            rd = l;
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false;
            prevSampledDynamic = sampledDynamicHere;
            prevBsdfPdf = surfaceBsdfPdf(n, v, l, rough, ps, exactSpecular);
        }

        float maxTp = max(throughput.r, max(throughput.g, throughput.b));
        if (maxTp <= 1.0e-5) {
            break;
        }
        if (bounce >= rrStart) {
            float q = clamp(maxTp, 0.02, 1.0);
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
    ConstPtr<WorldPush> wp = worldPushPtr();
    celestialLight = dominantCelestialLight(skyState(wp), transmittanceLut);
    celestialLight.illuminance = bt709ToAcesCg(celestialLight.illuminance);
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    uint spp = max(wp[0].spp, 1u);
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    uint splitRecord = dimensions.x * dimensions.y + pixelIndex;
    ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    float3 prefixCamOrigin = float3(0.0, 0.0, 0.0);
    float3 prefixCamDir = float3(0.0, 0.0, 1.0);
    Medium prefixCameraMedium = airMedium();
    bool prefixReady = false;
    uint recordIndex = pixelIndex;
    for (uint leaf = 0u; leaf < MAX_PATH_SEGMENTS; ++leaf) {
        PackedPathSegment packed = queue[recordIndex];
        PathSegment segment = unpackPathSegment(packed);
        uint cachedPrimaryRecord = (packed.pathFlags & PATH_FLAG_CACHED_PRIMARY) != 0u
                ? splitRecord : PATH_NO_NEXT;
        if (segment.preInterfaceDist > 0.0 && !prefixReady) {
            float2 dims = float2(dimensions);
            float2 uv = (float2(pix) + 0.5) / dims;
            float2 jndc = (uv + wp[0].jitter / dims) * 2.0 - 1.0;
            float4 nearH = mul(wp[0].invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
            float4 farH = mul(wp[0].invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
            float3 nearP = nearH.xyz / nearH.w;
            float3 farP = farH.xyz / farH.w;
            prefixCamOrigin = nearP + wp[0].camOffset;
            prefixCamDir = normalize(farP - nearP);
            prefixCameraMedium = (wp[0].flags & WORLD_FLAG_SUBMERGED) != 0u
                    ? makeDielectricMedium(true, WATER_IOR, wp[0].waterParams.xyz, 1.0)
                    : airMedium();
            prefixReady = true;
        }
        for (uint s = 0u; s < spp; ++s) {
            frameRadiance += tracePath(segment, uint2(pix), s + leaf * spp, cachedPrimaryRecord,
                    prefixCamOrigin, prefixCamDir, prefixCameraMedium);
        }
        if (packed.nextRecord == PATH_NO_NEXT) {
            break;
        }
        recordIndex = packed.nextRecord;
    }
    float3 stored = frameRadiance * (wp[0].preExposure / float(spp));
    outImage[pix] = float4(clamp(stored, float3(0.0), float3(HALF_MAX)), 1.0);
}
