import bindings;

static const float EDGE_THRESHOLD = 0.02;

float coverage(int2 pixel, int2 size) {
    return sourceImage[clamp(pixel, int2(0), size - int2(1))].a;
}

[shader("fragment")]
float4 main(float4 position : SV_Position) : SV_Target0 {
    int2 pixel = int2(position.xy);
    uint width, height;
    sourceImage.GetDimensions(width, height);
    int2 size = int2(width, height);

    if (coverage(pixel, size) > 0.5) return float4(0.0);

    float topLeft = coverage(pixel + int2(-1, -1), size);
    float top = coverage(pixel + int2(0, -1), size);
    float topRight = coverage(pixel + int2(1, -1), size);
    float left = coverage(pixel + int2(-1, 0), size);
    float right = coverage(pixel + int2(1, 0), size);
    float bottomLeft = coverage(pixel + int2(-1, 1), size);
    float bottom = coverage(pixel + int2(0, 1), size);
    float bottomRight = coverage(pixel + int2(1, 1), size);

    float gx = -topLeft - 2.0 * left - bottomLeft + topRight + 2.0 * right + bottomRight;
    float gy = -topLeft - 2.0 * top - topRight + bottomLeft + 2.0 * bottom + bottomRight;
    float edge = clamp(length(float2(gx, gy)), 0.0, 1.0);
    if (edge <= EDGE_THRESHOLD) return float4(0.0);

    float3 color = float3(0.0);
    float count = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            int2 samplePixel = clamp(pixel + int2(dx, dy), int2(0), size - int2(1));
            float4 sampleValue = sourceImage[samplePixel];
            if (sampleValue.a > 0.5) {
                color += sampleValue.rgb;
                count += 1.0;
            }
        }
    }
    if (count <= 0.0) return float4(0.0);
    return float4(color / count, edge);
}
