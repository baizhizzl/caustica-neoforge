// Transmittance LUT bake (Hillaire 2020, §5.1 / Bruneton 2008). For each (altitude, cos zenith) texel,
// integrate the optical depth from that point to the top of the atmosphere and store exp(-depth).
//
// Static: the medium never changes, so this runs once and every later pass reads it. Deliberately marched
// WITHOUT a ground test — a direction pointing below the horizon then accumulates the optical depth of a
// full chord through sea-level air, which comes out as transmittance zero. That is exactly the answer
// wanted for "the sun has set", so downstream passes need no separate planet-occlusion branch.
import world_common;
import sky;
import bindings;

[[vk::push_constant]] PushAddr pcAddr;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    if (tid.x >= uint(TRANSMITTANCE_LUT_W) || tid.y >= uint(TRANSMITTANCE_LUT_H)) {
        return;
    }
    // The parameterisation is defined on the closed [0,1] range, so the outermost texels must land on 0
    // and 1 exactly — a plain (i+0.5)/N would shrink the LUT's domain and bias every sample taken from it.
    float2 uv = float2(float(tid.x), float(tid.y))
              / float2(float(TRANSMITTANCE_LUT_W - 1), float(TRANSMITTANCE_LUT_H - 1));
    float radiusKm;
    float cosZenith;
    transmittanceLutParams(uv, radiusKm, cosZenith);

    float3 origin = float3(0.0, radiusKm, 0.0);
    float3 dir = float3(sqrt(max(1.0 - cosZenith * cosZenith, 0.0)), cosZenith, 0.0);
    float tMax = raySphereFarthest(origin, dir, ATMOS_TOP_KM);

    float3 opticalDepth = float3(0.0);
    if (tMax > 0.0) {
        float dt = tMax / float(TRANSMITTANCE_STEPS);
        for (int i = 0; i < TRANSMITTANCE_STEPS; i++) {
            float3 p = origin + dir * ((float(i) + 0.5) * dt);
            MediumSample medium = sampleMedium(length(p) - ATMOS_BOTTOM_KM);
            opticalDepth += medium.extinction * dt;
        }
    }
    transmittanceImage[tid.xy] = float4(exp(-opticalDepth), 1.0);
}
