import overlay_common;

[[vk::push_constant]] OverlayPush pc;

struct VertexInput {
    [[vk::location(0)]] float3 position;
};

struct VertexOutput {
    float4 position : SV_Position;
    [[vk::location(0)]] float3 cameraRelative;
};

[shader("vertex")]
VertexOutput main(VertexInput input) {
    VertexOutput output;
    output.cameraRelative = input.position - pc.camOffset;
    output.position = mul(pc.curViewProj, float4(output.cameraRelative, 1.0));
    return output;
}
