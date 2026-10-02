[shader("vertex")]
float4 main(uint vertexIndex : SV_VertexID) : SV_Position {
    float2 position = float2((vertexIndex << 1u) & 2u, vertexIndex & 2u);
    return float4(position * 2.0 - 1.0, 0.0, 1.0);
}
