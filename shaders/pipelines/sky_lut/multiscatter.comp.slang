// Multiple-scattering LUT bake (Hillaire 2020, §5.2). For each (altitude, cos sun zenith) texel, fire 64
// directions over the sphere, raymarch each one for SECOND-order scattered luminance with an isotropic
// phase, and store the geometric series that closes the remaining orders:
//
//     psi_ms = L_2nd / (1 - f_ms)
//
// where f_ms is the fraction of light that gets scattered once more before leaving. Storing the closed
// series rather than one extra order is what makes twilight and the night sky come out at the right level
// without an authored fill term — high orders dominate exactly when the sun is below the horizon, which
// is the case the previous single-scattering sky could not represent at all.
//
// Static (per look package: only the ground albedo enters from outside), so this runs once behind the
// transmittance LUT and is never rebuilt per frame. The 64x20 inner work is why: ~1.3M medium samples for
// the whole 32x32 table, trivial once and far too much per frame.
import world_common;
import sky;
import bindings;

[[vk::push_constant]] PushAddr pcAddr;

static const int SQRT_DIRS = 8; // SQRT_DIRS^2 == MULTISCATTER_DIRS

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= uint(MULTISCATTER_LUT_W) || tid.y >= uint(MULTISCATTER_LUT_H)) {
        return;
    }
    WorldPush push = ConstPtr<WorldPush>(pcAddr.worldPushAddr)[0];
    float groundAlbedo = push.skyLook3.x;

    float2 uv = float2(float(tid.x), float(tid.y))
              / float2(float(MULTISCATTER_LUT_W - 1), float(MULTISCATTER_LUT_H - 1));
    float radiusKm;
    float cosSunZenith;
    multiScatterLutParams(uv, radiusKm, cosSunZenith);

    float3 origin = float3(0.0, radiusKm, 0.0);
    float3 sunDir = float3(sqrt(max(1.0 - cosSunZenith * cosSunZenith, 0.0)), cosSunZenith, 0.0);

    float3 luminanceSum = float3(0.0);
    float3 multiScatSum = float3(0.0);
    for (int d = 0; d < MULTISCATTER_DIRS; d++) {
        // Uniform sphere directions on a regular 8x8 grid of the (azimuth, cos polar) square. A fixed
        // pattern, not a random one: the LUT is baked once and any stochastic noise in it would be frozen
        // into every frame that samples it.
        float randA = (float(d / SQRT_DIRS) + 0.5) / float(SQRT_DIRS);
        float randB = (float(d % SQRT_DIRS) + 0.5) / float(SQRT_DIRS);
        float theta = 2.0 * SKY_PI * randA;
        float phi = acos(clamp(1.0 - 2.0 * randB, -1.0, 1.0));
        float sinPhi = sin(phi);
        float3 dir = float3(cos(theta) * sinPhi, cos(phi), sin(theta) * sinPhi);

        // Unit illuminance, isotropic phase, no multiple-scattering lookup: this IS the term that lookup
        // will later provide, so reading it here would be circular.
        ScatteringResult r = integrateScatteredLuminance(
                origin, dir, sunDir, float3(1.0), 0.0,
                MULTISCATTER_STEPS, false, false,
                transmittanceLut, multiScatterLut, groundAlbedo, true);
        luminanceSum += r.luminance;
        multiScatSum += r.multiScatAs1;
    }
    float inverse = 1.0 / float(MULTISCATTER_DIRS);
    float3 secondOrder = luminanceSum * inverse;
    float3 scatteredFraction = multiScatSum * inverse;
    // Sum of the infinite series of further scattering events. The fraction is physically below 1 (some
    // light is always absorbed or escapes), but clamp anyway: an fp32 round-trip landing on exactly 1
    // would divide by zero and poison the whole LUT with infinities.
    float3 series = 1.0 / max(1.0 - scatteredFraction, float3(1.0e-4));
    multiScatterImage[tid.xy] = float4(secondOrder * series, 1.0);
}
