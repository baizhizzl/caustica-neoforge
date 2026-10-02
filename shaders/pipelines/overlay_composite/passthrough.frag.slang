import bindings;

[shader("fragment")]
float4 main(float4 position : SV_Position) : SV_Target0 {
    return sourceImage[int2(position.xy)];
}
