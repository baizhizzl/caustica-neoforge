// Composites the sRGB-authored, premultiplied UI over the PQ/BT.2020 HDR world in linear nits.
import bindings;
import gui_blend_math;

struct PresentPush {
    float uiNits;
    uint mode;
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

float pqDecode(float e) {
    float ep = pow(max(e, 0.0), 1.0 / PQ_M2);
    float num = max(ep - PQ_C1, 0.0);
    float den = max(PQ_C2 - PQ_C3 * ep, 1.0e-6);
    return 10000.0 * pow(num / den, 1.0 / PQ_M1);
}

float srgbChannelToLinear(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float3 srgbToLinear(float3 c) {
    return float3(srgbChannelToLinear(c.x), srgbChannelToLinear(c.y), srgbChannelToLinear(c.z));
}

static const float3x3 BT709_TO_BT2020 = float3x3(
    0.6274039, 0.3292830, 0.0433131,
    0.0690973, 0.9195406, 0.0113612,
    0.0163916, 0.0880132, 0.8955953
);

static const float3x3 BT2020_TO_BT709 = float3x3(
     1.6604910, -0.5876411, -0.0728499,
    -0.1245505,  1.1328999, -0.0083494,
    -0.0181508, -0.1005789,  1.1187297
);

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint width, height;
    outputImage.GetDimensions(width, height);
    if (tid.x >= width || tid.y >= height) return;

    int2 pix = int2(tid.xy);
    float3 worldPq = outputImage[pix].rgb;
    float4 ui = sourceImage.Load(int3(pix, 0));
    float coverage = coverageImage.Load(int3(pix, 0)).r;
    if (pc.mode == GUI_BLEND_RESOLVE) {
        resolvedUiImage[pix] = resolveGuiUi(ui.rgb, worldPq, 0.0, coverage);
        return;
    }
    if ((pc.mode == GUI_BLEND_OVER && ui.a <= 0.0)
            || (pc.mode == GUI_BLEND_MULTIPLY && all(ui.rgb == float3(1.0)))
            || (pc.mode == GUI_BLEND_INVERT && all(ui.rgb == float3(0.0)))
            || ((pc.mode == GUI_BLEND_ADD || pc.mode == GUI_BLEND_MAX) && all(ui.rgb == float3(0.0)))) {
        return;
    }
    float3 worldNits2020 = float3(pqDecode(worldPq.x), pqDecode(worldPq.y), pqDecode(worldPq.z));
    float3 blended;
    if (pc.mode == GUI_BLEND_OVER) {
        float3 straight = ui.a > 0.0 ? saturate(ui.rgb / ui.a) : float3(0.0);
        float3 uiNits2020 = mul(BT709_TO_BT2020, srgbToLinear(straight) * pc.uiNits);
        blended = worldNits2020 * (1.0 - ui.a) + uiNits2020 * ui.a;
    } else {
        float3 background709 = mul(BT2020_TO_BT709, worldNits2020);
        // Multiply and inversion retain dimensionless coefficients; additive/max masks carry HDR nits.
        float3 mask = (pc.mode == GUI_BLEND_ADD || pc.mode == GUI_BLEND_MAX)
                ? srgbToLinear(saturate(ui.rgb)) * pc.uiNits : ui.rgb;
        float3 result709 = max(compositeGuiEffect(background709, mask, pc.uiNits, pc.mode), 0.0);
        blended = mul(BT709_TO_BT2020, result709);
    }
    float3 resultPq = saturate(float3(pqEncode(blended.x), pqEncode(blended.y), pqEncode(blended.z)));
    resolvedUiImage[pix] = resolveGuiUi(worldPq, resultPq, ui.a, coverage);
    outputImage[pix] = float4(resultPq, 1.0);
}
