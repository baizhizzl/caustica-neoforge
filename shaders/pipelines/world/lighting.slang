// Direct lighting: the emitter light grid, RIS reservoirs, dynamic spherical area lights, and shading.
// INDIRECT PASS ONLY — the primary pass shades nothing. Depends on trace and math.

// contribution weight, M the effective sample count. wSum/phat are streaming scratch (not persisted).

import world_common;
import world_core;
import bindings;
import math;
import medium;
import light_proposals;
import trace;

public struct Reservoir {
    public float3 pos;  // light sample point (rebased world space)
    public float3 lnrm; // emitter outward normal
    public float3 le;   // emitter radiance
    public float area;  // emitter rectangle area (the area-light pdf factor)
    public float M;
    public float W;
    public float wSum;
    public float phat;
};

public Reservoir resEmpty() {
    Reservoir r;
    r.pos = float3(0.0, 0.0, 0.0);
    r.lnrm = float3(0.0, 0.0, 0.0);
    r.le = float3(0.0, 0.0, 0.0);
    r.area = 0.0;
    r.M = 0.0;
    r.W = 0.0;
    r.wSum = 0.0;
    r.phat = 0.0;
    return r;
}

public struct DynamicReservoir {
    public float3 pos;       // sampled point on the spherical emitter disk (rebased world space)
    public float3 intensity; // linear ACEScg radiant intensity (cd = lm/sr)
    public float  radius;    // spherical emitter radius (blocks)
    public uint   shadowMask; // TLAS instance cull mask excluding this emitter's own geometry
    public float  W;
    public float  wSum;
    public float  phat;
};

// Smallest emitter radius used by both the surface NEE reservoir and the volumetric halo integrator, so
// their near-field falloff and equi-angular footprint agree instead of sampling different spheres.
public static const float DYNAMIC_LIGHT_MIN_RADIUS = 0.02;

public DynamicReservoir dynamicResEmpty() {
    DynamicReservoir r;
    r.pos = float3(0.0, 0.0, 0.0);
    r.intensity = float3(0.0, 0.0, 0.0);
    r.radius = 0.0;
    r.shadowMask = CULL_SECONDARY;
    r.W = 0.0;
    r.wSum = 0.0;
    r.phat = 0.0;
    return r;
}

// Unshadowed contribution of a fixed light sample point at surface (hitPos,n,v); out p-hat = its
// luminance (the resampling target). Evaluated from the UNBIASED hitPos — SURF_BIAS is applied only to
// the shadow-ray origin in shadeReservoir, on whichever side the survivor sample lands. Three receiver
// modes share one target function because they are mutually exclusive per candidate:
//   - front hemisphere (ndl>0): the same Lambert(+GGX) BRDF split the sun NEE uses;
//   - twoSided (particles): billboards have no back face, ndl=abs(ndl) folds both sides into the front;
//   - back hemisphere with sss>0 (LabPBR leaves/grass): HG transmission phase — cosT = dot(wi,rd) peaks
//     when the ray points from the light through the slab toward the camera (same as the sun SSS term).
public float3 evalSampleContrib(float3 sp, float3 lnrm, float3 le, float area, float3 hitPos, float3 n,
                         float3 v, float3 rd, float3 diffAlb, float3 F0, float rough, float3 msScale,
                         bool twoSided, bool hasBsdfContinuation, float sss, out float phat) {
    phat = 0.0;
    float3 toL = sp - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6) {
        return float3(0.0, 0.0, 0.0);
    }
    float rawCosL = dot(lnrm, -toL);
    if (rawCosL <= 0.0) {
        return float3(0.0, 0.0, 0.0); // receiver behind the emitter face
    }
    float rawNdl = dot(n, toL);
    if (!twoSided && sss <= 0.0 && rawNdl <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float invDist = rsqrt(dist2);
    float3 wi = toL * invDist;
    float cosL = rawCosL * invDist;
    float ndl = rawNdl * invDist;
    if (twoSided) {
        ndl = abs(ndl);
    }
    float3 contrib = float3(0.0, 0.0, 0.0);
    if (ndl > 0.0) {
        float G = ndl * cosL / dist2; // area-light geometry term (x area)
        float3 brdf = diffAlb * INV_PI;
        // twoSided callers are particle billboards, which pass rough=1/F0=0 and want diffuse-only
        // shading: fresnelSchlick still returns up to full white at grazing angles even with F0=0 (that
        // IS the Fresnel effect), so skipping the term here — not just zeroing F0 — is what keeps
        // billboards from picking up a rim highlight they were never meant to have.
        if (!twoSided) {
            float3 h = safeHalfVector(wi, v, n);
            float ndh = max(0.0, dot(n, h));
            float ndv = max(1.0e-4, dot(n, v));
            float vdh = max(0.0, dot(v, h));
            float D = ggxD(ndh, rough);
            float Gs = ggxG1(ndv, rough) * ggxG1(ndl, rough);
            float3 Fs = fresnelSchlick(vdh, F0);
            // msScale is a loop invariant at this shading point (F0, ndv, rough and the global strength
            // do not change across RIS candidates), so it is computed once by the caller and passed in.
            brdf += (D * Gs) * (Fs * msScale) / (4.0 * ndv * ndl);
            bool exactSpec = rough <= 0.0;
            float ps = surfaceSpecularProb(diffAlb, F0, exactSpec);
            float wNee = surfaceNeeMisWeight(staticEmitterMisPdf(dist2, cosL), n, v, wi,
                    rough, ps, exactSpec, hasBsdfContinuation);
            contrib = brdf * le * (G * area * wNee);
        } else {
            contrib = brdf * le * (G * area);
        }
    } else if (sss > 0.0) {
        float backNdl = -ndl;
        float G = backNdl * cosL / dist2;
        float cosT = dot(wi, rd);
        contrib = diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * le * (G * area);
    }
    phat = luminance(contrib);
    return contrib;
}

// Unshadowed contribution of a spherical dynamic emitter (handheld torch/lantern, dropped glowing item,
// burning or bioluminescent entity). Non-singular (dist2 + r^2) falloff matches the irradiance of a
// diffuse sphere of radius r while eliminating near-field 1/r^2 fireflies when a handheld light sits
// close to cave walls.
public float3 evalDynamicContrib(float3 sp, float3 intensity, float radius, float maxRange2,
                                 float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                                 float3 F0, float rough, float3 msScale, bool twoSided, float sss,
                                 out float phat) {
    phat = 0.0;
    float3 toL = sp - hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6 || (maxRange2 > 0.0 && dist2 >= maxRange2)) {
        return float3(0.0, 0.0, 0.0);
    }
    float window = maxRange2 > 0.0 ? clamp(1.0 - dist2 / maxRange2, 0.0, 1.0) : 1.0;
    window *= window;
    float atten = window / (dist2 + radius * radius);
    float rawNdl = dot(n, toL);
    if (!twoSided && sss <= 0.0 && rawNdl <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float invDist = rsqrt(dist2);
    float3 wi = toL * invDist;
    float ndl = rawNdl * invDist;
    if (twoSided) {
        ndl = abs(ndl);
    }
    float3 contrib = float3(0.0, 0.0, 0.0);
    if (ndl > 0.0) {
        float3 brdf = diffAlb * INV_PI;
        if (!twoSided) {
            float3 h = safeHalfVector(wi, v, n);
            float ndh = max(0.0, dot(n, h));
            float ndv = max(1.0e-4, dot(n, v));
            float vdh = max(0.0, dot(v, h));
            // Analytical spherical lights have no BVH sphere mesh for BSDF rays to MIS against (e.g. a
            // first-person handheld torch). Bound single-sample lobe tightness to the emitter's subtended
            // half-angle when !exactSpecular so smooth metals do not spike when one disk sample lands on h.
            float dynSpecRough = rough > 0.0 ? max(rough, min(0.35 * radius * invDist, 0.1)) : 0.0;
            float D = ggxD(ndh, dynSpecRough);
            float Gs = ggxG1(ndv, dynSpecRough) * ggxG1(ndl, dynSpecRough);
            float3 Fs = fresnelSchlick(vdh, F0);
            // Same loop-invariant msScale as evalSampleContrib, computed once per shading point.
            brdf += (D * Gs) * (Fs * msScale) / (4.0 * ndv * ndl);
        }
        contrib = brdf * intensity * (ndl * atten);
    } else if (sss > 0.0) {
        float backNdl = -ndl;
        float cosT = dot(wi, rd);
        contrib = diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * intensity * (backNdl * atten);
    }
    phat = luminance(contrib);
    return contrib;
}

// Exact target-proportional streaming reservoir over all active dynamic lights in WorldPush.
// Selecting one survivor with probability phat_i / sum(phat) and weighting by W = sum(phat) / phat
// yields an unbiased single-shadow-ray estimator across up to 16 simultaneous dynamic lights, with zero
// selection variance when a single handheld light is active.
public DynamicReservoir risDynamic(float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                                   float3 F0, float rough, float3 msScale, bool twoSided, float sss,
                                   inout uint seed) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    DynamicReservoir r = dynamicResEmpty();
    uint count = min(wp[0].dynamicLightCount, 16u);
    if (count == 0u) {
        return r;
    }
    float maxRange = max(wp[0].visualParams0.y, 0.0);
    float maxRange2 = maxRange * maxRange;
    // msScale is a loop invariant at this shading point (F0, ndv, rough and the global strength are
    // constant across candidates), so the caller computes it once and this loop only reuses it.
    for (uint i = 0u; i < count; i++) {
        DynamicLight dl = wp[0].dynamicLights[i];
        float3 center = dl.posRadius.xyz;
        float radius = max(dl.posRadius.w, DYNAMIC_LIGHT_MIN_RADIUS);
        float3 toCenter = center - hitPos;
        float centerDist2 = dot(toCenter, toCenter);
        if (maxRange2 > 0.0 && centerDist2 >= maxRange2) {
            continue;
        }
        float signedPlaneDist = dot(toCenter, n);
        if (!twoSided && sss <= 0.0 && signedPlaneDist <= 0.0) {
            continue;
        }
        // Decode the shadow cull mask once per candidate. It is the hardware instance mask that removes
        // the emitter's own mesh from its shadow rays; no distance trim is applied anywhere.
        uint shadowMask = clamp(uint(dl.intensityMask.w + 0.5), 1u, 255u);
        float3 sp = center;
        if (centerDist2 > 1.0e-4 && radius > 0.0) {
            float invCenterDist = rsqrt(centerDist2);
            float centerDist = centerDist2 * invCenterDist;
            float3 axis = toCenter * invCenterDist;
            float3 tangent;
            float3 bitangent;
            orthonormalBasis(axis, tangent, bitangent);
            float effRadius = min(radius, centerDist * 0.65);
            // Keep the sampled disk entirely on the receiver's side of the tangent plane. Without this
            // clamp a sphere centre in front of the surface lets the disk edge cross behind the
            // surface, flipping ndl in mid-sample and popping the near-field energy. SSS receivers use
            // the absolute plane distance because their back hemisphere is legitimately lit.
            float planeDist = (!twoSided && sss <= 0.0) ? signedPlaneDist : abs(signedPlaneDist);
            effRadius = min(effRadius, max(planeDist * 0.85, 0.0));
            float2 disk = sampleConcentricDisk(rndf(seed), rndf(seed)) * effRadius;
            sp = center + disk.x * tangent + disk.y * bitangent;
        }
        float phat;
        evalDynamicContrib(sp, dl.intensityMask.xyz, radius, maxRange2, hitPos, n, v, rd,
                diffAlb, F0, rough, msScale, twoSided, sss, phat);
        if (!(phat > 0.0)) {
            continue;
        }
        r.wSum += phat;
        if (rndf(seed) * r.wSum < phat) {
            r.pos = sp;
            r.intensity = dl.intensityMask.xyz;
            r.radius = radius;
            r.shadowMask = shadowMask;
            r.phat = phat;
        }
    }
    r.W = r.phat > 0.0 ? (r.wSum / r.phat) : 0.0;
    return r;
}

public float3 shadeDynamicReservoir(DynamicReservoir s, float3 hitPos, float3 n, float3 v, float3 rd,
                                    float3 diffAlb, float3 F0, float rough, float3 msScale,
                                    bool twoSided, float sss, Medium currentMedium) {
    if (s.W <= 0.0 || s.phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float maxRange = max(worldPushPtr()[0].visualParams0.y, 0.0);
    float phat;
    float3 contrib = evalDynamicContrib(s.pos, s.intensity, s.radius, maxRange * maxRange,
            hitPos, n, v, rd, diffAlb, F0, rough, msScale, twoSided, sss, phat);
    if (phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float side = dot(n, s.pos - hitPos) >= 0.0 ? 1.0 : -1.0;
    float3 origin = hitPos + side * n * SURF_BIAS;
    float3 toL = s.pos - origin;
    float dist = length(toL);
    if (dist <= 1.0e-5) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 dir = toL / dist;
    // Only 1 mm of numerical tolerance: the ray must reach every wall behind the emitter. The emitter's
    // own mesh is removed by s.shadowMask (hardware instance culling), never by shortening the ray, so a
    // torch cannot leak light through a wall simply because the wall sits beyond a "trim" distance.
    // Close-range points (inside the 1 mm tolerance) are unoccluded by construction: walls are kept at
    // least 0.05 m away by the collision volume and the emitter itself is culled by the mask.
    float traceDist = dist - 1.0e-3;
    VisibilityResult vShadow = makeUnoccludedVisibility();
    if (traceDist > RAY_TMIN) {
        // The water caustic is a directional plane-wave Jacobian and is meaningless for a near-field point
        // light; shadow transmittance (including the water any-hit tint) is the physically consistent term.
        vShadow = visibilityMasked(origin, dir, traceDist, s.shadowMask);
    }
    float3 vis = vShadow.transmittance * waterShadowTransmittance(currentMedium, vShadow, dist);
    return contrib * vis * s.W;
}

// Resolve the current light-grid cell, including empty and out-of-bounds fallback.
public bool findLightGridCell(float3 p, out LightGridCell cell, out int3 cellCoord) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    cell.spanOffset = 0u;
    cell.spanCount = 0u;
    cell.invWeightSum = 0.0;
    cellCoord = int3(0, 0, 0);
    float4 gridOrigin = wp[0].lightGridOrigin;
    int4 gridDims = wp[0].lightGridDims;
    if (pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0 || pc.lightLocalAliasAddr == 0
            || gridOrigin.w <= 0.0) return false;
    int3 coord = int3(floor((p - gridOrigin.xyz) / gridOrigin.w));
    if (coord.x < 0 || coord.y < 0 || coord.z < 0
            || coord.x >= gridDims.x
            || coord.y >= gridDims.y
            || coord.z >= gridDims.z) return false;
    uint linear = (uint(coord.z) * uint(gridDims.y) + uint(coord.y))
            * uint(gridDims.x) + uint(coord.x);
    cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[linear];
    cellCoord = coord;
    return cell.spanCount > 0u;
}

public float proposalPdf(LightCandidate light, LightGridCell cell, int3 cellCoord,
                  float localProbability, uint lightCount) {
    float globalPdf = pc.lightAliasAddr != 0
            ? light.globalPdf : 1.0 / float(lightCount);
    float localPdf = 0.0;
    if (localProbability > 0.0 && light.power > 0.0) {
        int3 delta = int3(int(light.section & 0x3ffu), int((light.section >> 10u) & 0x3ffu),
                    int((light.section >> 20u) & 0x3ffu)) - cellCoord;
        if (all(abs(delta) <= int3(2, 2, 2))) {
            float3 deltaF = float3(delta);
            localPdf = light.power * cell.invWeightSum / max(1.0, dot(deltaF, deltaF));
        }
    }
    return localProbability * localPdf + (1.0 - localProbability) * globalPdf;
}

public void selectLightGridLight(LightGridCell cell, bool useLocal, uint lightCount, inout uint proposalSeed,
                          out uint lightIndex) {
    if (useLocal) {
        selectLightGridSpanLight(pc.lightGridSpanAddr, pc.lightLocalAliasAddr, cell, proposalSeed, lightIndex);
    } else {
        selectGlobalLight(pc.lightAliasAddr, lightCount, proposalSeed, lightIndex);
    }
}

public LightCandidate selectCandidate(LightGridCell cell, int3 cellCoord, bool useLocal,
                                     uint lightCount, float inversePowerSum,
                                     int4 poolOrigin, int4 poolDims,
                                     inout uint proposalSeed) {
    uint first = useLocal ? lightPoolCellOffset(cellCoord, poolOrigin, poolDims) : 0u;
    uint count = useLocal ? uint(poolDims.w) : uint(poolOrigin.w);
    if (pc.lightPoolAddr != 0 && first != 0xffffffffu && count > 0u) {
        uint entry = min(uint(lightProposalRandom(proposalSeed) * float(count)), count - 1u);
        return ConstPtr<LightCandidate>(pc.lightPoolAddr)[first + entry];
    }
    uint index;
    selectLightGridLight(cell, useLocal, lightCount, proposalSeed, index);
    return decodeLightCandidate(ConstPtr<Light>(pc.lightBufAddr)[index],
            inversePowerSum, pc.lightAliasAddr != 0);
}

// Direct alias proposals resolved together. Candidates are independent until the reservoir update, so
// their span -> section alias -> Light chains are issued as three load waves for the whole batch rather
// than one full chain after another. Each wave only issues loads; results are consumed by the next wave.
static const uint RIS_LOAD_BATCH = 4u;

struct LightBatch {
    Light lights[RIS_LOAD_BATCH];
};

// Proposal draws are taken in candidate order before any load, so the selected lights, and therefore the
// estimator, match resolving each candidate with selectLightGridLight one at a time.
LightBatch loadLightBatch(uint first, uint candidateCount, uint globalCandidateCount, bool hasGridCell,
                          LightGridCell cell, uint lightCount, inout uint proposalSeed) {
    bool active[RIS_LOAD_BATCH];
    bool local[RIS_LOAD_BATCH];
    float outerSample[RIS_LOAD_BATCH];
    float innerSample[RIS_LOAD_BATCH];
    uint column[RIS_LOAD_BATCH];
    [unroll]
    for (uint k = 0u; k < RIS_LOAD_BATCH; k++) {
        uint c = first + k;
        active[k] = c < candidateCount;
        uint globalsBefore = (c * globalCandidateCount) / candidateCount;
        uint globalsAfter = ((c + 1u) * globalCandidateCount) / candidateCount;
        local[k] = hasGridCell && globalsAfter == globalsBefore;
        outerSample[k] = 0.0;
        innerSample[k] = 0.0;
        if (active[k]) {
            outerSample[k] = lightProposalRandom(proposalSeed);
            if (local[k]) innerSample[k] = lightProposalRandom(proposalSeed);
        }
        float columns = float(local[k] ? cell.spanCount : lightCount);
        outerSample[k] *= columns;
        column[k] = min(uint(outerSample[k]), uint(columns) - 1u);
    }

    // Wave 1: the cell's section-span column, or the global power alias column.
    LightGridSpan span[RIS_LOAD_BATCH];
    LightAlias globalAlias[RIS_LOAD_BATCH];
    [unroll]
    for (uint k = 0u; k < RIS_LOAD_BATCH; k++) {
        span[k] = {};
        globalAlias[k] = {};
        if (active[k] && local[k]) {
            span[k] = ConstPtr<LightGridSpan>(pc.lightGridSpanAddr)[cell.spanOffset + column[k]];
        } else if (active[k] && pc.lightAliasAddr != 0) {
            globalAlias[k] = ConstPtr<LightAlias>(pc.lightAliasAddr)[column[k]];
        }
    }

    // Wave 2: the section-local power alias column of each selected section.
    uint lightIndex[RIS_LOAD_BATCH];
    uint sectionFirst[RIS_LOAD_BATCH];
    LightAlias sectionAlias[RIS_LOAD_BATCH];
    [unroll]
    for (uint k = 0u; k < RIS_LOAD_BATCH; k++) {
        sectionAlias[k] = {};
        sectionFirst[k] = 0u;
        lightIndex[k] = column[k];
        if (!active[k]) continue;
        if (local[k]) {
            bool spanSelf = outerSample[k] - float(column[k]) < span[k].accept;
            sectionFirst[k] = spanSelf ? span[k].firstLight : span[k].aliasFirstLight;
            uint sectionCount = spanSelf ? (span[k].packedLightCounts & 0xffffu)
                    : (span[k].packedLightCounts >> 16u);
            if (sectionCount == 0u || pc.lightLocalAliasAddr == 0) {
                active[k] = false;
                continue;
            }
            innerSample[k] *= float(sectionCount);
            lightIndex[k] = min(uint(innerSample[k]), sectionCount - 1u);
            sectionAlias[k] = ConstPtr<LightAlias>(pc.lightLocalAliasAddr)[sectionFirst[k] + lightIndex[k]];
        } else if (pc.lightAliasAddr != 0) {
            bool self = outerSample[k] - float(column[k]) < globalAlias[k].accept;
            lightIndex[k] = self ? column[k] : globalAlias[k].aliasIndex;
        }
    }

    // Wave 3: the selected Light records.
    LightBatch batch;
    [unroll]
    for (uint k = 0u; k < RIS_LOAD_BATCH; k++) {
        batch.lights[k] = {};
        if (!active[k]) continue;
        if (local[k]) {
            bool self = innerSample[k] - float(lightIndex[k]) < sectionAlias[k].accept;
            lightIndex[k] = sectionFirst[k] + (self ? lightIndex[k] : sectionAlias[k].aliasIndex);
        }
        batch.lights[k] = ConstPtr<Light>(pc.lightBufAddr)[lightIndex[k]];
    }
    return batch;
}

// Fold one decoded proposal into the streaming reservoir. The emitter point and the replacement test use
// the per-vertex seed; the proposal PDF is the published mixture, whichever path produced the record.
void risAccumulate(inout Reservoir r, LightCandidate lg, LightGridCell gridCell, int3 gridCellCoord,
                   float localProbability, float3 lightRebase, uint lightCount,
                   float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                   float3 F0, float rough, float3 msScale, bool twoSided, bool hasBsdfContinuation,
                   float sss, inout uint seed) {
    // Soft shadows: uniform point on the emitter rectangle ((s,t) in [-1,1]^2, pdf 1/area).
    float s = rndf(seed) * 2.0 - 1.0;
    float t = rndf(seed) * 2.0 - 1.0;
    float3 sp = lg.pos + lightRebase
            + s * lg.halfU + t * lg.halfV;
    float phat;
    evalSampleContrib(sp, lg.normal, lg.radiance, lg.area, hitPos, n, v, rd,
            diffAlb, F0, rough, msScale, twoSided, hasBsdfContinuation, sss, phat);
    if (!(phat > 0.0)) {
        return;
    }
    float sourcePdf = proposalPdf(lg, gridCell, gridCellCoord, localProbability, lightCount);
    float w = phat / max(sourcePdf, 1.0e-20);
    r.wSum += w;
    if (rndf(seed) * r.wSum < w) { // weighted reservoir update
        r.pos = sp;
        r.lnrm = lg.normal;
        r.le = lg.radiance;
        r.area = lg.area;
        r.phat = phat;
    }
}

// Initial RIS over M power-weighted candidates -> one resampled sample, no shadow ray yet. The chosen
// sample's W = wSum / (M * p-hat); M counts every candidate, including zero-weight ones. The caller picks
// M per vertex: the primary-terminal hit and later indirect hits use separately configured counts.
// hasBsdfContinuation must match shadeReservoir so its target and reconstructed contribution agree.
public Reservoir risInitial(float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb, float3 F0,
                     float rough, float3 msScale, bool twoSided, bool hasBsdfContinuation,
                     float sss, uint candidateCount,
                     inout uint seed, inout uint proposalSeed) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    Reservoir r = resEmpty();
    uint lightCount = wp[0].lightCount;
    if (candidateCount == 0u || lightCount == 0u || pc.lightBufAddr == 0) {
        return r;
    }
    float4 lightRebase = wp[0].lightRebase;
    // msScale is computed once per shading vertex by the caller: F0, ndv, rough and the global
    // compensation strength are constant for every candidate in this loop.
    LightGridCell gridCell;
    int3 gridCellCoord;
    float3 gridLookup = hitPos;
    if (pc.lightGridCellAddr != 0) {
        // Stochastically blend across hard section boundaries. Every cell proposal retains full global
        // support, so conditioning on this independently jittered lookup remains unbiased.
        gridLookup += (float3(rndf(proposalSeed), rndf(proposalSeed), rndf(proposalSeed)) - 0.5)
                * wp[0].lightGridOrigin.w;
    }
    bool hasGridCell = findLightGridCell(gridLookup, gridCell, gridCellCoord);
    r.M = float(candidateCount);
    // Deterministically stratify the proposal mixture. At M=8 this schedules exactly six local and two
    // global candidates, with every lane taking the same branch for a given candidate. Counts that are
    // not divisible by four use the nearest practical split with at least one global candidate; M=1 is
    // global-only so the estimator retains full support.
    uint globalCandidateCount = hasGridCell
            ? max(1u, (candidateCount + 2u) / 4u) : candidateCount;
    uint localCandidateCount = candidateCount - globalCandidateCount;
    float localProbability = float(localCandidateCount) / float(candidateCount);
    if (pc.lightPoolAddr != 0) {
        int4 poolOrigin = wp[0].lightPoolOrigin;
        int4 poolDims = wp[0].lightPoolDims;
        for (uint c = 0u; c < candidateCount; c++) {
            uint globalsBefore = (c * globalCandidateCount) / candidateCount;
            uint globalsAfter = ((c + 1u) * globalCandidateCount) / candidateCount;
            bool useLocal = hasGridCell && globalsAfter == globalsBefore;
            LightCandidate lg = selectCandidate(gridCell, gridCellCoord, useLocal,
                    lightCount, lightRebase.w, poolOrigin, poolDims, proposalSeed);
            risAccumulate(r, lg, gridCell, gridCellCoord, localProbability, lightRebase.xyz, lightCount,
                    hitPos, n, v, rd, diffAlb, F0, rough, msScale, twoSided, hasBsdfContinuation, sss, seed);
        }
    } else {
        for (uint first = 0u; first < candidateCount; first += RIS_LOAD_BATCH) {
            LightBatch batch = loadLightBatch(first, candidateCount, globalCandidateCount, hasGridCell,
                    gridCell, lightCount, proposalSeed);
            [unroll]
            for (uint k = 0u; k < RIS_LOAD_BATCH; k++) {
                if (first + k >= candidateCount) break;
                LightCandidate lg = decodeLightCandidate(batch.lights[k], lightRebase.w,
                        pc.lightAliasAddr != 0);
                risAccumulate(r, lg, gridCell, gridCellCoord, localProbability, lightRebase.xyz, lightCount,
                        hitPos, n, v, rd, diffAlb, F0, rough, msScale, twoSided, hasBsdfContinuation,
                        sss, seed);
            }
        }
    }
    r.W = r.phat > 0.0 ? (r.wSum / (r.M * r.phat)) : 0.0;
    return r;
}

// Shade a finalized reservoir: recompute the survivor's contribution at (hitPos,n,v), cast ONE shadow
// ray, and return throughput-free radiance contrib*vis*W. The one ray serves whichever term fired for
// the survivor (front BRDF, twoSided billboard, or SSS backscatter) — the origin is biased toward the
// sample's side of the surface.
public float3 shadeReservoir(Reservoir s, float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                      float3 F0, float rough, float3 msScale, bool twoSided,
                      bool hasBsdfContinuation, float sss, Medium currentMedium) {
    if (s.W <= 0.0 || s.phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float phat;
    float3 contrib = evalSampleContrib(s.pos, s.lnrm, s.le, s.area, hitPos, n, v, rd,
            diffAlb, F0, rough, msScale, twoSided, hasBsdfContinuation, sss, phat);
    if (phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    // Pick the shadow-ray origin on the sample's side of the surface FIRST, then measure the ray from
    // that origin — measuring dist from hitPos while tracing from the biased origin shifts the ray tip
    // up to SURF_BIAS*ndl toward the emitter plane, beating the 0.001*dist stop-short margin for lights
    // closer than ~SURF_BIAS/0.001 blocks -> shadow ray hits the emitter's own face -> per-texel black.
    float side = dot(n, s.pos - hitPos) >= 0.0 ? 1.0 : -1.0;
    float3 origin = hitPos + side * n * SURF_BIAS;
    float3 toL = s.pos - origin;
    float dist = length(toL);
    if (dist <= 1.0e-5) {
        return float3(0.0, 0.0, 0.0);
    }
    // Stop 1 mm (or ~160 fp32 ULPs at long range) short of the sample point so the ray never
    // self-occludes on the emitter's own face, without the old relative 0.999*dist margin that stopped
    // 6-12 cm short at distance and leaked light past thin occluders (carpets, snow layers, trapdoors).
    float traceDist = dist - max(1.0e-3, dist * 1.0e-5);
    VisibilityResult shadow = traceDist > RAY_TMIN
            ? visibility(origin, toL / dist, traceDist)
            : makeUnoccludedVisibility();
    float3 vis = shadow.transmittance * waterShadowTransmittance(currentMedium, shadow, dist);
    return contrib * vis * s.W;
}
