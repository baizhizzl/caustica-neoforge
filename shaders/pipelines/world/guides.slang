// DLSS-RR guide state and everything that resolves it: the gv_* captures, the foreground specular
// surface and its reflection motion vector, and the image writes.
// PRIMARY PASS ONLY. Depends on trace, water and segment.

// Primary-visibility guide attributes. Pass A traces exactly one sample per pixel; a split reflection
// leaf supplies the spec endpoint and its transmission leaf may replace the ordinary tuple.

import world_common;
import world_core;
import bindings;
import math;
import medium;
import water;
import trace;

public static float3 gv_normal = float3(0.0, 0.0, 0.0);
public static float3 gv_albedo = float3(0.0, 0.0, 0.0);
// The diffuse-albedo guide's alpha lane is not consumed by DLSS-RR. Preserve whether the visible
// guide endpoint emits so downstream exposure metering can cap large lava/glowstone populations
// without another material-ID image.
public static bool gv_emissive = false;
public static float gv_rough = 0.0;
public static float3 gv_hitCamRel = float3(0.0, 0.0, 0.0);       // primary-surface hit position relative to the current camera
public static bool gv_motionUseRefracted = false; // true when the MV tracks the refracted hit (its own reprojection delta)
// World-space displacement of the motion-guide surface since the previous frame (0 for static
// terrain/sky, an entity's per-vertex/object motion for a dynamic hit).
public static float3 gv_motionObjDisp = float3(0.0, 0.0, 0.0);
// Transmission replaces the ordinary guide tuple with the visible destination, so RR's specular inputs
// (specular albedo + reflection MV) need the foreground glass/water interface kept separately. This is
// the surface the reflection physically lives on; for an opaque primary hit it is that hit itself.
public struct SpecSurface {
    public float3 camRel;         // interface position relative to the current camera
    public float3 normal;         // FP32: reflection reprojection amplifies normal quantization at distance
    public float3 previousNormal; // the same normal one frame ago; equal to `normal` unless the surface animates
    public float3 biasNormal;     // geometric normal, used only to offset a ray origin off the surface
    public float3 motionPrev;     // current-minus-previous displacement of the reflecting surface
    public float roughness;
    public float3 albedo;         // specular albedo fed to DLSS-RR for demodulation (0 = pure diffuse)
};
public static SpecSurface gv_spec = {};

// Static surfaces reuse one normal for all three roles. Water overrides them (wave-displaced shading
// normal now and last frame, flat geometric normal for the bias) via the six-argument form.
public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 previousNormal,
                            float3 biasNormal, float3 motionPrev, float roughness, float3 albedo) {
    SpecSurface s;
    s.camRel = camRel;
    s.normal = normal;
    s.previousNormal = previousNormal;
    s.biasNormal = biasNormal;
    s.motionPrev = motionPrev;
    s.roughness = roughness;
    s.albedo = albedo;
    return s;
}

public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal,
            float3(0.0, 0.0, 0.0), roughness, albedo);
}

public SpecSurface makeSpecSurface(float3 camRel, float3 normal, float3 motionPrev,
                            float roughness, float3 albedo) {
    return makeSpecSurface(camRel, normal, normal, normal, motionPrev, roughness, albedo);
}
// angle can land behind the eye even though the reflector itself is comfortably in view.
public float2 projectPrevNdc(float3 worldPos, out bool valid) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    float4 clip = mul(wp[0].prevViewProj, float4(worldPos - wp[0].camOffset + wp[0].camDelta, 1.0));
    valid = clip.w > 0.0;
    return valid ? clip.xy / clip.w : float2(0.0, 0.0);
}

// Previous-frame screen position of a planar reflection. The reflected image of a world point P seen
// in a planar reflector is the MIRROR image V = mirror(P) across the surface plane: the eye sees V
// along a straight line, so the reflection appears at proj(V). Project the mirror image directly.
// Gotcha: intersecting a reflection ray with the surface plane divides by dot(mirrorDir, n), which
// explodes at grazing angles and produces non-zero MV in a static scene. Mirroring is division-free, so
// static MV is bit-exact zero at any incidence. The reflector is assumed static (terrain / water);
// reflectedMotionPrev carries the reflected content's own displacement.
public float2 previousReflectionNdc(float3 surfacePos, float3 surfaceNormal, float3 surfaceMotionPrev,
                             float3 reflectedWorldPos, float3 reflectedMotionPrev, out bool valid) {
    float3 n = normalize(surfaceNormal); // mirror(P) is orientation-independent, so no viewer flip needed
    float3 previousSurfacePos = surfacePos - surfaceMotionPrev;
    float3 prevHit = reflectedWorldPos - reflectedMotionPrev;
    float3 mirroredHit = prevHit - 2.0 * n * dot(prevHit - previousSurfacePos, n);
    return projectPrevNdc(mirroredHit, valid);
}

// Reflection guides remain owned by the physical foreground interface even when transmission later
// replaces the ordinary guide tuple. The same non-SER probe resolves both motion and, for exact
// mirrors, the reflected terminal's diffuse content. Destinations without a stable diffuse albedo keep
// the foreground material reflectance: sky, emissive surfaces, metals, and another dielectric/mirror
// would otherwise give RR a zero or recursively defined demodulation signal.
public float2 resolveSpecularGuides(SpecSurface surface, float3 primaryDir,
                             float2 currentNdc, float2 size, float primaryConeSpread,
                             out float3 specAlbedo) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    specAlbedo = surface.albedo;
    if ((wp[0].flags & WORLD_FLAG_SPEC_GUIDES) == 0u
            || length(surface.normal) < 0.5
            || max(surface.albedo.r, max(surface.albedo.g, surface.albedo.b)) <= 0.001
            || surface.roughness > SPEC_MOTION_ALPHA_MAX) {
        return float2(0.0, 0.0);
    }

    float3 surfacePos = surface.camRel + wp[0].camOffset;
    float3 n = normalize(surface.normal);
    float3 specDir = reflect(primaryDir, n);

    // Pass A stops radiance traversal at the first split, so reflection motion keeps one dedicated,
    // deterministic guide probe.
    traceGuide(CULL_SECONDARY,
            offsetSurfaceOrigin(surfacePos, surface.biasNormal, specDir, SURF_BIAS),
            RAY_TMIN, specDir, 10000.0,
            max(length(surface.camRel) * primaryConeSpread, RAY_CONE_MIN_WIDTH),
            max(primaryConeSpread, RAY_CONE_MIN_SPREAD));
    float3 reflectedHit;
    float3 reflectedMotionPrev;
    if (payload.hitT > 0.0) {
        reflectedHit = surfacePos + specDir * payload.hitT;
        uint reflectedMaterial = payloadMaterial();
        // Closest-hit writes motionPrev for every material. Terrain/water are zero; dynamic opaque and
        // dielectric entities carry their per-vertex/object displacement.
        reflectedMotionPrev = payload.motionPrev;
        if (surface.roughness <= MIRROR_ALPHA_MAX
                && (reflectedMaterial == MATERIAL_OPAQUE
                    || reflectedMaterial == MATERIAL_PARTICLE)
                && payloadEmission() <= 0.0) {
            float3 reflectedAlbedo = payloadAlbedo();
            float3 reflectedDiffuse = reflectedMaterial == MATERIAL_PARTICLE
                    ? reflectedAlbedo
                    : reflectedAlbedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0));
            if (max(reflectedDiffuse.r, max(reflectedDiffuse.g, reflectedDiffuse.b)) > 0.001) {
                specAlbedo = surface.albedo * reflectedDiffuse;
            }
        }
    } else {
        reflectedHit = surfacePos + specDir * 1.0e6;
        reflectedMotionPrev = float3(0.0, 0.0, 0.0);
    }
    float3 previousN = length(surface.previousNormal) >= 0.5
            ? normalize(surface.previousNormal) : n;
    bool prevValid;
    float2 prevNdc = previousReflectionNdc(
            surfacePos, previousN, surface.motionPrev,
            reflectedHit, reflectedMotionPrev, prevValid);
    return prevValid ? (prevNdc - currentNdc) * 0.5 * size : float2(0.0, 0.0);
}

public void setTransmissionGuide(float3 hitCamRel, float3 motionPrev, float3 normal,
                          float roughness, float3 diffuseAlbedo, bool emissive) {
    gv_hitCamRel = hitCamRel;
    gv_motionObjDisp = motionPrev;
    gv_motionUseRefracted = true;
    gv_normal = normal;
    gv_rough = roughness;
    gv_albedo = diffuseAlbedo;
    gv_emissive = emissive;
}

// Deterministic ordinary guide behind the first transmitted interface. This is guide-only work:
// radiance reflection/transmission continuations are queued at the first split and traced by Pass B.
public void resolveTransmissionGuide(float3 surfacePos, float3 transmittedDir,
                              float3 surfaceBiasNormal, MediumStack medium, float rayBias,
                              float rayConeWidth, float rayConeSpread, float3 guideFilter) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    uint flags = wp[0].flags;
    if ((flags & WORLD_FLAG_TRANSMISSION_GUIDES) == 0u || dot(transmittedDir, transmittedDir) <= 0.0) {
        return;
    }

    float3 direction = normalize(transmittedDir);
    float3 ro = offsetSurfaceOrigin(surfacePos, surfaceBiasNormal, direction, rayBias);
    float3 camOffset = wp[0].camOffset;
    // Full bounce-depth crossing walk is only required when DLSS-RR or guide debug views are active;
    // FG and auto-exposure metering need at most the first transmitted layer.
    uint maxCrossings = (flags & WORLD_FLAG_SPEC_GUIDES) != 0u ? wp[0].maxBounces : min(wp[0].maxBounces, 1u);

    for (uint crossing = 0u; crossing < maxCrossings; ++crossing) {
        traceGuide(CULL_SECONDARY, ro, RAY_TMIN, direction, 10000.0,
                rayConeWidth, rayConeSpread);
        if (payload.hitT <= 0.0) {
            setTransmissionGuide((ro + direction * 1.0e6) - camOffset,
                    float3(0.0, 0.0, 0.0), float3(0.0, 0.0, 0.0), 1.0,
                    guideFilter * SKY_DIFF_ALBEDO, false);
            return;
        }

        uint material = payloadMaterial();
        float3 interfacePos = ro + direction * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * payload.hitT, RAY_CONE_MIN_WIDTH);
        if (material == MATERIAL_OPAQUE || material == MATERIAL_PARTICLE) {
            float endpointRoughness = material == MATERIAL_PARTICLE
                    ? 1.0 : clamp(payloadRoughness(), 0.0, 1.0);
            float3 hitAlbedo = payloadAlbedo();
            float3 endpointAlbedo = material == MATERIAL_PARTICLE
                    ? hitAlbedo
                    : hitAlbedo * (1.0 - clamp(payloadMetalness(), 0.0, 1.0));
            setTransmissionGuide(interfacePos - camOffset, payload.motionPrev,
                    payloadNormal(), endpointRoughness, guideFilter * endpointAlbedo,
                    payloadEmission() > 0.0);
            return;
        }
        if (material != MATERIAL_WATER && material != MATERIAL_DIELECTRIC) return;

        bool isWater = material == MATERIAL_WATER;
        bool entering = payloadDielectricEntering();
        float3 geometricNormal = normalize(payloadNormal());
        float3 interfaceNormal = geometricNormal;
        if (isWater && (flags & WORLD_FLAG_WATER_WAVES) != 0u) {
            float waterFootprint = rayConeWidth
                    / max(abs(dot(-direction, geometricNormal)), 0.2);
            interfaceNormal = applyWaterWaves(geometricNormal,
                    interfacePos.xz + wp[0].waterAnchor.xy,
                    wp[0].waterParams.w, waterFootprint);
        }

        float transmission = clamp(payloadTransmission(), 0.0, 1.0);
        Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0),
                                              payloadAlbedo(), transmission);
        float etaT = entering ? entered.ior : medium.outer.ior;
        float3 nextDirection = refract(direction, interfaceNormal, medium.current.ior / etaT);
        if (dot(nextDirection, nextDirection) <= 0.0) {
            // Never let a TIR reflection become ordinary diffuse/depth.
            float tirRough = isWater ? 0.0 : clamp(payloadRoughness(), 0.0, 1.0);
            tirRough = isDeltaAlpha(tirRough) ? 0.0 : tirRough;
            setTransmissionGuide(interfacePos - camOffset,
                    isWater ? float3(0.0, 0.0, 0.0) : float3(payload.motionPrev),
                    interfaceNormal, tirRough, float3(0.0, 0.0, 0.0), false);
            return;
        }
        if (!isWater && entering) {
            guideFilter *= payloadAlbedo();
        }
        if (entering) {
            mediumPush(medium, entered);
        } else {
            mediumPop(medium);
        }
        direction = normalize(nextDirection);
        ro = offsetSurfaceOrigin(interfacePos, geometricNormal, direction,
                isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS);
    }
}

// Resolve the captured gv_* guide state into the DLSS-RR guide images after Pass A queues continuations.
public void writeGuides(int2 pix, float3 primaryDir, float2 jndc, float2 size, float primaryConeSpread) {
    ConstPtr<WorldPush> wp = worldPushPtr();
    // Write ordinary primary/transmitted guides BEFORE launching the reflection probe so the gv_* registers
    // do not stay live across resolveSpecularGuides's TraceRay call.
    float4 curClip = mul(wp[0].curViewProj, float4(gv_hitCamRel, 1.0));
    float depth = curClip.w > 0.0 ? curClip.z / curClip.w : 0.0;

    float4 prevClip = mul(wp[0].prevViewProj,
            float4(gv_hitCamRel + wp[0].camDelta - gv_motionObjDisp, 1.0));
    float2 curNdc = jndc;
    float2 motion;
    if (prevClip.w > 0.0 && (!gv_motionUseRefracted || curClip.w > 0.0)) {
        if (gv_motionUseRefracted) curNdc = curClip.xy / curClip.w;
        motion = (prevClip.xy / prevClip.w - curNdc) * 0.5 * size;
    } else {
        motion = float2(0.0, 0.0);
    }

    gNormal[pix] = float4(gv_normal, gv_rough);
    gAlbedo[pix] = float4(gv_albedo, gv_emissive ? 1.0 : 0.0);
    gDepth[pix] = depth;
    gMotion[pix] = motion;

    float3 specAlbedo;
    float2 specMotion = resolveSpecularGuides(
            gv_spec, primaryDir, jndc, size, primaryConeSpread, specAlbedo);
    gSpecAlbedo[pix] = float4(specAlbedo, 1.0);
    gSpecMotion[pix] = specMotion;
}
