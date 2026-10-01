// Minimal miss record for reflection/refraction guide probes and ordinary TraceRay shadow visibility.
// Guide rays consume only hit-vs-miss state; fixed sky guide values are supplied by raygen. Shadow rays
// seed flags=1 and use this clear as their miss marker, avoiding a hit-object dependency.
import world_common;

[shader("miss")]
void main(inout Payload payload) {
    // Preserve hitT: guide payloads already seed the -1 miss sentinel, while shadow any-hit may have
    // recorded the nearest water crossing here for underwater caustics. Preserve the surface words too:
    // this record serves shadow rays, whose accumulated transmittance lives in their albedo view.
    packNormal(payload, float3(0.0, 0.0, 0.0));
    payload.flags = 0u;
}
