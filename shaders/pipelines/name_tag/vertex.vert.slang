import overlay_common;

[[vk::push_constant]] NameTagPush pc;

struct VertexInput {
    [[vk::location(0)]] float3 position;
    [[vk::location(1)]] float2 uv;
    [[vk::location(2)]] float4 color;
};

struct VertexOutput {
    float4 position : SV_Position;
    [[vk::location(0)]] float2 uv;
    [[vk::location(1)]] float4 color;
};

[shader("vertex")]
VertexOutput main(VertexInput input) {
    VertexOutput output;
    output.position = mul(pc.curViewProj, float4(input.position - pc.camOffset, 1.0));
    output.uv = input.uv;
    output.color = input.color;
    return output;
}
