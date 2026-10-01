import bindings;

struct FragmentInput {
    [[vk::location(0)]] float2 uv;
    [[vk::location(1)]] float4 color;
};

[shader("fragment")]
float4 main(FragmentInput input) : SV_Target0 {
    float alpha = fontAtlas.Sample(input.uv).a;
    return float4(input.color.rgb, input.color.a * alpha);
}
