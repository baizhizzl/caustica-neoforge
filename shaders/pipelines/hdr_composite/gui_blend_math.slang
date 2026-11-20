public static const uint GUI_BLEND_OVER = 0u;
public static const uint GUI_BLEND_MULTIPLY = 1u;
public static const uint GUI_BLEND_INVERT = 2u;
public static const uint GUI_BLEND_ADD = 3u;
public static const uint GUI_BLEND_MAX = 4u;
public static const uint GUI_BLEND_RESOLVE = 5u;

// Multiply masks hold the transmittance accumulated over white; invert masks hold the
// constant term accumulated over black. Both are dimensionless even in the HDR path.
// Add and max masks are expressed in the same units and color space as background.
public float3 compositeGuiEffect(float3 background, float3 mask, float referenceWhite, uint mode) {
    switch (mode) {
        case GUI_BLEND_MULTIPLY:
            return background * mask;
        case GUI_BLEND_INVERT:
            return (1.0 - 2.0 * mask) * background + mask * referenceWhite;
        case GUI_BLEND_ADD:
            return background + mask;
        case GUI_BLEND_MAX:
            return max(background, mask);
    }
    return background;
}

float requiredGuiAlpha(float background, float result) {
    float alpha = 0.0;
    if (background > 0.0) alpha = max(alpha, 1.0 - result / background);
    if (background < 1.0) alpha = max(alpha, (result - background) / (1.0 - background));
    return alpha;
}

// DLSS takes a premultiplied UI whose over-composite reconstructs the encoded backbuffer.
// One alpha must keep every encoded channel between zero and that alpha.
public float4 resolveGuiUi(float3 background, float3 result, float priorAlpha, float coverage) {
    background = saturate(background);
    result = saturate(result);
    float alpha = saturate(max(max(priorAlpha, coverage), max(requiredGuiAlpha(background.r, result.r),
            max(requiredGuiAlpha(background.g, result.g), requiredGuiAlpha(background.b, result.b)))));
    return float4(clamp(result - (1.0 - alpha) * background, 0.0, alpha), alpha);
}
