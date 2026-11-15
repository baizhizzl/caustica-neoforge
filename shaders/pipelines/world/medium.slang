// Participating media: the tint-to-extinction mappings, atmospheric/underwater volumetric scattering
// coefficients, and the depth-2 medium stack the dielectric interface pushes and pops. Depends on core.

// Per-channel Beer–Lambert extinction from a water body's biome tint (carried in the payload's albedo view
// for water hits, or worldPush.waterParams.xyz for the camera's own biome when starting submerged). A blue
// ocean tint (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue. The floor
// keeps even a white-tinted body very slightly absorbing so deep water never reads as clear vacuum.

import world_common;
import world_core;

// One shared cap for the air-haze column. Both the primary pass (pre-interface throughput) and the
// indirect pass (volumetric shafts and escaped-sky attenuation) must use the same distance or the two
// halves of a split path disagree on how much haze sits between the camera and the far plane.
public static const float MAX_AIR_VOLUMETRIC_DIST = 96.0;

public static const float WATER_DENSITY = 0.1;
public static const float3 WATER_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);
public float3 waterExtinction(float3 tint) {
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

// Single-scattering coefficient for sunlit water columns: clean ocean water scatters cyan-blue
// preferentially while biome tint shapes rivers and swamps. The coefficient is strictly clamped below
// 0.85 * extinction, so the single-scattering albedo omega_0 stays <= 0.85 < 1 and a long path can never
// gain energy. `strength` is worldPush.visualParams1.w * volumetric intensity.
public float3 waterScatteringCoeff(float3 tint, float strength) {
    float3 ext = waterExtinction(tint);
    float3 clampedTint = clamp(tint, float3(0.05, 0.05, 0.05), float3(1.0, 1.0, 1.0));
    return min(clampedTint * (0.012 * max(strength, 0.0)), ext * 0.85);
}

// Height-attenuated low-lying atmospheric mist density (1/blocks) at rebased world height y.
// Denser near valleys/water surfaces and exponentially thinner on high mountain peaks.
public float airHazeScattering(float rebasedY, float baseSigma, float heightFalloff, float seaLevelRebasedY) {
    float aboveSea = max(rebasedY - seaLevelRebasedY, -16.0);
    float altitudeFactor = exp(-max(aboveSea, 0.0) * max(heightFalloff, 0.0));
    // Subtle valley/cave mist boost below sea level, bounded to 1.35x so caves stay crisp.
    float lowBoost = aboveSea < 0.0 ? lerp(1.0, 1.35, clamp(-aboveSea / 32.0, 0.0, 1.0)) : 1.0;
    return max(baseSigma, 0.0) * altitudeFactor * lowBoost;
}

// Optical depth of one ray piece that does not cross the sea-level plane. Above the sea the density is
// exactly baseSigma * exp(-k * (y - seaY)), so its integral has a closed form with no singularity. The
// (exp(-k*h0) - exp(-k*h1)) / (k*dy) form is the average of the exponential over the piece; when the
// exponent change is tiny (k*dy -> 0) it degrades to 0/0, so that branch uses the midpoint value instead.
// Below the sea the altitude factor is 1 (aboveSea <= 0) and lowBoost is a bounded piecewise-linear
// profile; splitting at the kink y = seaY - 16 makes every below-sea piece exactly linear/constant, so
// 3-point Simpson is exact there.
float airHazeSegmentOpticalDepth(float3 ro, float3 rd, float s0, float s1,
                                 float baseSigma, float k, float seaLevelRebasedY) {
    float segLen = s1 - s0;
    if (segLen <= 0.0) {
        return 0.0;
    }
    float y0 = ro.y + rd.y * s0;
    float y1 = ro.y + rd.y * s1;
    if (0.5 * (y0 + y1) >= seaLevelRebasedY) {
        float h0 = max(y0 - seaLevelRebasedY, 0.0);
        float h1 = max(y1 - seaLevelRebasedY, 0.0);
        float dy = y1 - y0;
        float sigmaY = max(baseSigma, 0.0);
        if (abs(k * dy) < 1.0e-4) {
            // Linear (midpoint) expansion of the exponential; exact for k == 0 and O((k*dy)^2) otherwise.
            return airHazeScattering(0.5 * (y0 + y1), sigmaY, k, seaLevelRebasedY) * segLen;
        }
        return sigmaY * (exp(-k * h0) - exp(-k * h1)) / (k * dy) * segLen;
    }
    float f0 = airHazeScattering(y0, baseSigma, k, seaLevelRebasedY);
    float fm = airHazeScattering(0.5 * (y0 + y1), baseSigma, k, seaLevelRebasedY);
    float f1 = airHazeScattering(y1, baseSigma, k, seaLevelRebasedY);
    return (f0 + 4.0 * fm + f1) * (segLen / 6.0);
}

// Integral of the height-dependent haze density along [0, dist].
//
// The 3-point Simpson rule used here previously is exact for only the cubic term of the profile; at the
// largest configured falloff (0.25) over a 96-block vertical column its error reached ~300%. The
// density is piecewise analytic, so the integral is now evaluated exactly:
//   * horizontal rays (|rd.y| < 1e-7) have a constant-height density: sigma(y) * dist, exact to
//     O((k*|rd.y|*dist)^2) < 1e-12, so even a grazing 96-block column stays within 0.003%;
//   * rays that cross the sea-level plane are split at s = (seaY - ro.y) / rd.y, because the profile
//     changes form there (pure exponential above, bounded linear lowBoost below);
//   * the above-sea piece uses the exact exponential antiderivative (0.00% error, no singularity);
//   * the below-sea piece uses 3-point Simpson on the bounded linear lowBoost profile (exact, since
//     the split at y = seaY - 16 leaves only linear/constant pieces).
public float airHazeOpticalDepth(float3 ro, float3 rd, float dist,
                                 float baseSigma, float heightFalloff, float seaLevelRebasedY) {
    if (dist <= 0.0 || baseSigma <= 0.0) {
        return 0.0;
    }
    if (abs(rd.y) < 1.0e-7) {
        return airHazeScattering(ro.y, baseSigma, heightFalloff, seaLevelRebasedY) * dist;
    }
    float k = max(heightFalloff, 0.0);
    // Breakpoints: the sea-level plane changes the profile form (exponential above, lowBoost below), and
    // y = seaY - 16 is where the clamped aboveSea saturates lowBoost at its 1.175 constant. Splitting at
    // both makes every below-sea piece exactly linear/constant, so 3-point Simpson is exact there.
    float sSea = (seaLevelRebasedY - ro.y) / rd.y;
    float sLow = (seaLevelRebasedY - 16.0 - ro.y) / rd.y;
    float sA = -1.0;
    float sB = -1.0;
    if (sSea > 0.0 && sSea < dist) {
        sA = sSea;
    }
    if (sLow > 0.0 && sLow < dist) {
        if (sA < 0.0) {
            sA = sLow;
        } else if (sLow < sA) {
            sB = sA;
            sA = sLow;
        } else {
            sB = sLow;
        }
    }
    if (sA < 0.0) {
        return airHazeSegmentOpticalDepth(ro, rd, 0.0, dist, baseSigma, k, seaLevelRebasedY);
    }
    float optical = airHazeSegmentOpticalDepth(ro, rd, 0.0, sA, baseSigma, k, seaLevelRebasedY);
    if (sB < 0.0) {
        return optical + airHazeSegmentOpticalDepth(ro, rd, sA, dist, baseSigma, k, seaLevelRebasedY);
    }
    optical += airHazeSegmentOpticalDepth(ro, rd, sA, sB, baseSigma, k, seaLevelRebasedY);
    return optical + airHazeSegmentOpticalDepth(ro, rd, sB, dist, baseSigma, k, seaLevelRebasedY);
}

// A non-water dielectric's tint is a filter over one block of travel rather than a biome water colour, so
// it maps to extinction by inverting Beer-Lambert over that reference distance: transmittance t after one
// block means sigma = -ln(t). `transmission` scales that transmittance, so it keeps the sense the
// material's authored factor has everywhere else — 1 passes the tint through unchanged, 0 is opaque.
// Clamped away from zero so a fully saturated texel stays finite instead of producing an infinite sigma.
public static const float VOLUME_TINT_REFERENCE_DISTANCE = 1.0; // blocks
public float3 volumeExtinction(float3 tint, float transmission) {
    float3 blockTransmittance = clamp(clamp(tint, 0.0, 1.0) * clamp(transmission, 0.0, 1.0),
            1.0e-3, 1.0);
    return -log(blockTransmittance) / VOLUME_TINT_REFERENCE_DISTANCE;
}

// ---- Participating medium the path is currently inside.
//
// Refraction needs the RELATIVE index across an interface, so the renderer has to know what it is
// travelling through, not just what it is hitting. `ior` drives Snell/Fresnel; `extinction` drives the
// per-segment Beer-Lambert attenuation.
//
// The stack is depth 2 (current + the one it will return to) held in named fields, NOT an array. A
// dynamically indexed local array lands in scratch memory, and this raygen is already register-bound —
// paying an occupancy hit for nesting that Minecraft does not produce would be a bad trade. Depth 2
// covers air->water->glass and air->glass->water, which is the realistic worst case; anything deeper
// degrades to air on the way out, and because `entering` is re-derived per face from geometry rather
// than toggled, the path re-synchronises at the next crossing instead of staying corrupted.
public struct Medium {
    public float ior;
    public float3 extinction;
    public bool water; // drives the wave-refraction caustic on submerged receivers; nothing else is water-specific
};

// True only for a genuinely empty atmospheric volume. A non-water dielectric (tinted glass, ice, amber)
// also has extinction > 0 along its interior, so it is NOT air: its Beer-Lambert absorption already
// accounts for the segment, and adding the air-haze profile inside it would scatter light in a vacuum
// and attenuate the surface twice.
public bool isAirMedium(Medium m) {
    return !m.water && all(m.extinction <= 0.0);
}

public struct MediumStack {
    public Medium current;
    public Medium outer;
};

public Medium airMedium() {
    Medium m;
    m.ior = 1.0;
    m.extinction = float3(0.0, 0.0, 0.0);
    m.water = false;
    return m;
}

public MediumStack makeMediumStack(Medium start) {
    MediumStack s;
    s.current = start;
    s.outer = airMedium();
    return s;
}

public void mediumPush(inout MediumStack stack, Medium entered) {
    stack.outer = stack.current;
    stack.current = entered;
}

public void mediumPop(inout MediumStack stack) {
    stack.current = stack.outer;
    stack.outer = airMedium();
}

// Water's tint is a biome colour whose absorption is calibrated per block of depth; any other volume
// dielectric's tint is a filter over a reference block of travel. Both end up as per-channel extinction.
public Medium makeDielectricMedium(bool isWater, float ior, float3 tint, float transmission) {
    Medium m;
    m.ior = ior;
    m.extinction = isWater ? waterExtinction(tint) : volumeExtinction(tint, transmission);
    m.water = isWater;
    return m;
}
