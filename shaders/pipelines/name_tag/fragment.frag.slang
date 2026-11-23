import bindings;

struct FragmentInput {
    [[vk::location(0)]] float2 uv;
    [[vk::location(1)]] float4 color;
};

[shader("fragment")]
float4 main(FragmentInput input) : SV_Target0 {
    float4 texel = fontAtlas.Sample(input.uv);
    return input.color * texel;
}
