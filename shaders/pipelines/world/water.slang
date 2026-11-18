// Water surface: the wave spectrum (with its analytic time derivative), the normal perturbation it
// drives, and the chromatic wave-refraction caustic term that differentiates the same field. Depends on core.


// Animated surface waves — a directional wave spectrum, gradient-only (the surface stays geometrically
// flat; only the normal tilts). Three things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=14 m → ~4.7 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// The domain is world-anchored (see applyWaterWaves) so the pattern is pinned in world space, and the
// caustics differentiate this same field, so they stay in sync with the visible ripples.

import world_common;
import world_core;

public static const float WATER_WAVE_STRENGTH = 0.3;  // slope scale (higher = choppier glints / more broken reflections)
public static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
public static const int   WAVE_COUNT = 10;            // λ = 14 m … 0.38 m (each octave 1.5× shorter)
public static const float WAVE_G = 9.81;
public static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction
public static const float WAVE_MEANDER = 1.1;         // crest phase-wobble amplitude (rad) on the long components
public float waterWaveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0) / max(wavelength, 1.0e-4);
    return 1.0 - smoothstep(0.25, 0.5, cyclesPerPixel);
}

// One walk of the wave spectrum, shared by every consumer so the spectrum constants exist once. WITH_DT
// is a generic so the derivative terms are compiled out entirely for callers that only want the
// gradient — the trigonometry is shared, but the chain-rule work is not free.
public void waterWaveSpectrum<let WITH_DT : int>(float2 p, float t, float footprint,
                                          out float2 grad, out float2 gradDt) {
    // Per-wave steepness a·k, wavelength λ, wavenumber k = 2π/λ, deep-water ω = √(g·k), and wind-spread
    // unit headings precomputed across all 10 octaves so no per-octave trig/sqrt runs on constant indices.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    const float LAMBDA[WAVE_COUNT] = {
        14.00000000, 9.33333333, 6.22222222, 4.14814815, 2.76543210,
        1.84362140, 1.22908093, 0.81938729, 0.54625819, 0.36417213
    };
    const float K[WAVE_COUNT] = {
        0.44879895, 0.67319843, 1.00979764, 1.51469646, 2.27204469,
        3.40806703, 5.11210055, 7.66815083, 11.50222624, 17.25333936
    };
    const float W[WAVE_COUNT] = {
        2.09826526, 2.56983956, 3.14739773, 3.85475916, 4.72109638,
        5.78213808, 7.08164385, 8.67320703, 10.62246585, 13.00981140
    };
    const float DX[WAVE_COUNT] = {
        0.88643689, 0.98028219, 0.96396434, 0.75184189, 0.96875772,
        0.69285884, 0.94170789, 0.95829132, 0.20589894, 0.84580863
    };
    const float DY[WAVE_COUNT] = {
        0.46284951, 0.19760319, 0.26603034, 0.65934487, -0.24800902,
        0.72107325, 0.33643312, -0.28579285, 0.97857326, -0.53348642
    };
    t *= WAVE_SPEED;
    grad = float2(0.0, 0.0);
    gradDt = float2(0.0, 0.0);
    for (int i = 0; i < WAVE_COUNT; i++) {
        float lodWeight = waterWaveLodWeight(LAMBDA[i], footprint);
        if (lodWeight <= 0.0) break;
        float k = K[i];
        float w = W[i];
        float2 d = float2(DX[i], DY[i]);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        float2 dph = k * d;
        float phDt = -w;                      // ∂φ/∂t
        float2 dphDt = float2(0.0, 0.0);      // ∂(∇φ)/∂t
        if (i < 4) { // meander only the components whose fronts are long enough to read as lines
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
            if (WITH_DT != 0) {
                float wphDt = 0.5 * w;
                phDt += WAVE_MEANDER * cos(wph) * wphDt;
                dphDt = (-WAVE_MEANDER * sin(wph) * wphDt * 0.4 * k) * pd;
            }
        }
        float S = 1.2 + 0.12 * float(i); // crest sharpness: the short chop is choppier
        float sinPh = sin(ph);
        float cosPh = cos(ph);
        float e = exp(S * (sinPh - 1.0));
        // a = Q/k; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        float amplitude = lodWeight * (Q[i] / k) * S;
        grad += amplitude * cosPh * e * dph;
        if (WITH_DT != 0) {
            // ∂/∂t of the term above, chain-ruled through φ and ∇φ.
            gradDt += amplitude * e
                    * (phDt * (-sinPh + S * cosPh * cosPh) * dph + cosPh * dphDt);
        }
    }
    grad *= WATER_WAVE_STRENGTH;
    gradDt *= WATER_WAVE_STRENGTH;
}

public float2 waterWaveGrad(float2 p, float t, float footprint) {
    float2 grad, gradDt;
    waterWaveSpectrum<0>(p, t, footprint, grad, gradDt);
    return grad;
}

public float2 waterWaveGrad(float2 p, float t) {
    return waterWaveGrad(p, t, 0.0);
}

// Evaluate the current gradient and linearly rewind it to the previous phase in one spectrum walk.
// Frame deltas are bounded on the CPU, so this avoids a second set of trigonometric/exponential work
// while remaining accurate over the sub-frame interval used by reflection reprojection.
public void waterWaveGradTemporal(float2 p, float currentT, float previousT, float footprint,
                           out float2 currentGrad, out float2 previousGrad) {
    float2 gradDt;
    waterWaveSpectrum<1>(p, currentT, footprint, currentGrad, gradDt);
    previousGrad = currentGrad - (currentT - previousT) * WAVE_SPEED * gradDt;
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
public float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t, float footprint) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t, footprint);
    float3 up = normalize(float3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
}

// ---- Water caustics. Light refracting through the waved surface converges/diverges before reaching an
// underwater receiver. Evaluating the wave normal once at each finite-difference corner and refracting
// with Cauchy's dispersion indices for water (n_R = 1.330, n_G = 1.334, n_B = 1.340) produces spectral
// RGB caustics with subtle prismatic fringes along bright fold lines at no extra wave-spectrum cost.
public static const float CAUSTIC_EPS = 0.22; // finite-difference step (m); resolves crisp wave ribbons
public static const float CAUSTIC_MAX = 6.0;  // focusing clamp: keeps fold-line singularities from becoming fireflies
public static const float3 WATER_CAUCHY_INV_IOR = float3(1.0 / 1.330, 1.0 / 1.334, 1.0 / 1.340);

float2 causticLandingNormal(float2 xz, float3 nw, float3 inc, float h, float invIor) {
    float3 rdw = refract(inc, nw, invIor); // air→water: never TIR
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

float causticChannelFocus(float2 base, float2 baseX, float2 baseZ,
                          float3 nw0, float3 nwx, float3 nwz,
                          float3 inc, float h, float invIor) {
    float2 p0 = causticLandingNormal(base, nw0, inc, h, invIor);
    float2 px = causticLandingNormal(baseX, nwx, inc, h, invIor);
    float2 pz = causticLandingNormal(baseZ, nwz, inc, h, invIor);
    float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
    float focus = (CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5);
    return min(focus, CAUSTIC_MAX);
}

public float3 waterCausticSpectral(float3 exitPos, float3 lightDir, float waterDist, float intensity,
                                   float footprint) {
    if (intensity <= 0.0) {
        return float3(1.0, 1.0, 1.0);
    }
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) {
        return float3(1.0, 1.0, 1.0);
    }
    ConstPtr<WorldPush> wp = worldPushPtr();
    float h = max(waterDist * lightDir.y, 0.05);
    float t = wp[0].waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + wp[0].waterAnchor.xy;
    float2 baseX = base + float2(CAUSTIC_EPS, 0.0);
    float2 baseZ = base + float2(0.0, CAUSTIC_EPS);
    // Wavelengths finer than the finite-difference step alias across [base, base + eps] anyway; combine
    // with the caller's ray-cone footprint so distant/deep receivers prune high-frequency chop octaves.
    float causticFootprint = max(CAUSTIC_EPS, footprint);

    float2 g0 = waterWaveGrad(base, t, causticFootprint);
    float2 gx = waterWaveGrad(baseX, t, causticFootprint);
    float2 gz = waterWaveGrad(baseZ, t, causticFootprint);
    float3 nw0 = normalize(float3(-g0.x, 1.0, -g0.y));
    float3 nwx = normalize(float3(-gx.x, 1.0, -gx.y));
    float3 nwz = normalize(float3(-gz.x, 1.0, -gz.y));

    // fG (the central 1/1.333 refraction focus) is the neutral caustic magnitude for every channel.
    // Only the red/blue dispersion offsets RELATIVE to it are kept, softened by 0.75 so the fringe lives
    // in ACEScg AP1 without cross-talking neutral luminance. When dispersion vanishes (fR == fB == fG),
    // all three channels remain strictly equal and neutral fold lines stay achromatic.
    float fG = causticChannelFocus(base, baseX, baseZ, nw0, nwx, nwz, inc, h, 1.0 / WATER_IOR);
    float fR = causticChannelFocus(base, baseX, baseZ, nw0, nwx, nwz, inc, h, WATER_CAUCHY_INV_IOR.x);
    float fB = causticChannelFocus(base, baseX, baseZ, nw0, nwx, nwz, inc, h, WATER_CAUCHY_INV_IOR.z);
    float3 focus = float3(fG, fG, fG) + float3(fR - fG, 0.0, fB - fG) * 0.75;
    // No artificial dark floor; the final clamp is the only firefly ceiling so a high user intensity
    // cannot bypass CAUSTIC_MAX.
    float3 shaped = max(float3(1.0) + (focus - float3(1.0)) * intensity, float3(0.0));
    shaped = min(shaped, float3(CAUSTIC_MAX));
    return lerp(float3(1.0), shaped, fade);
}

public float3 waterCausticSpectral(float3 exitPos, float3 lightDir, float waterDist, float intensity) {
    return waterCausticSpectral(exitPos, lightDir, waterDist, intensity, CAUSTIC_EPS);
}
