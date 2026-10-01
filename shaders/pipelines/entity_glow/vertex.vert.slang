import overlay_common;

[[vk::push_constant]] OverlayPush pc;

struct VertexInput {
    [[vk::location(0)]] float3 position;
};

struct VertexOutput {
    float4 position : SV_Position;
};

[shader("vertex")]
VertexOutput main(VertexInput input) {
    VertexOutput output;
    output.position = mul(pc.curViewProj, float4(input.position - pc.camOffset, 1.0));
    return output;
}
