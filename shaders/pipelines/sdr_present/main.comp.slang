// Converts Minecraft's complete sRGB/BT.709 SDR frame to a PQ/BT.2020 image at paper white.
import bindings;

struct PresentPush {
    float uiNits;
};
[[vk::push_constant]] PresentPush pc;

static const float PQ_M1 = 0.1593017578125;
static const float PQ_M2 = 78.84375;
static const float PQ_C1 = 0.8359375;
static const float PQ_C2 = 18.8515625;
static const float PQ_C3 = 18.6875;

float pqEncode(float nits) {
    float y = pow(max(nits, 0.0) / 10000.0, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), PQ_M2);
}

float srgbChannelToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float3 srgbToLinear(float3 c) {
    return float3(srgbChannelToLinear(c.x), srgbChannelToLinear(c.y), srgbChannelToLinear(c.z));
}

// Row-major mathematical notation; the build targets column-major storage, while mul(M, v) preserves
// the intended BT.709 -> BT.2020 transform.
static const float3x3 BT709_TO_BT2020 = float3x3(
    0.6274039, 0.3292830, 0.0433131,
    0.0690973, 0.9195406, 0.0113612,
    0.0163916, 0.0880132, 0.8955953
);

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (tid.x >= width || tid.y >= height) return;

    int2 pix = int2(tid.xy);
    float3 nits709 = srgbToLinear(sourceImage.Load(int3(pix, 0)).rgb) * pc.uiNits;
    float3 nits2020 = mul(BT709_TO_BT2020, nits709);
    float3 pq = float3(pqEncode(nits2020.x), pqEncode(nits2020.y), pqEncode(nits2020.z));
    outputImage[pix] = float4(pq, 1.0);
}
