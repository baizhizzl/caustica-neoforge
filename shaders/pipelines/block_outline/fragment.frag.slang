import overlay_common;
import bindings;

[[vk::push_constant]] OverlayPush pc;

static const uint CULL_PRIMARY = 0x02u;

struct FragmentInput {
    [[vk::location(0)]] float3 cameraRelative;
};

[shader("fragment")]
float4 main(FragmentInput input) : SV_Target0 {
    float distanceToEdge = length(input.cameraRelative);
    if (distanceToEdge < 1.0e-4) return pc.color;

    RayDesc ray;
    ray.Origin = pc.camOffset;
    ray.TMin = 0.001;
    ray.Direction = input.cameraRelative / distanceToEdge;
    ray.TMax = max(distanceToEdge - 0.01, 0.001);

    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(tlas, RAY_FLAG_NONE, CULL_PRIMARY, ray);
    while (query.Proceed()) {
    }
    if (query.CommittedStatus() != COMMITTED_NOTHING) discard;
    return pc.color;
}
