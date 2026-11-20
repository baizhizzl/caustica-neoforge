// CPU-callable probes of the helpers used by the actual HDR compositor shader.
import gui_blend_math;

bool rgbNear(float3 actual, float3 expected, float tolerance = 1.0e-5) {
    return all(abs(actual - expected) <= tolerance);
}

bool validUi(float4 ui, float3 background, float3 result) {
    return ui.a >= 0.0 && ui.a <= 1.0
            && all(ui.rgb >= 0.0) && all(ui.rgb <= ui.a)
            && rgbNear(ui.rgb + (1.0 - ui.a) * background, result);
}

export __extern_cpp uint testVignetteAndCrosshair() {
    float3 scene = float3(0.8, 0.4, 0.7);
    float3 vignette = compositeGuiEffect(scene, float3(0.2, 0.8, 0.45), 1.0, GUI_BLEND_MULTIPLY);
    if (!rgbNear(vignette, float3(0.16, 0.32, 0.315))) return 1u;

    // White and partially colored crosshair texels must read the post-vignette scene.
    float3 inverted = compositeGuiEffect(vignette, float3(1.0, 0.0, 0.75), 1.0, GUI_BLEND_INVERT);
    if (!rgbNear(inverted, float3(0.84, 0.32, 0.5925))) return 2u;

    float3 hdr = compositeGuiEffect(float3(40.0, 160.0, 200.0), float3(1.0), 200.0, GUI_BLEND_INVERT);
    return rgbNear(hdr, float3(160.0, 40.0, 0.0)) ? 0u : 4u;
}

export __extern_cpp uint testOrderedClampAndMax() {
    float3 scene = float3(0.3, 0.8, 0.0);
    float3 added = saturate(compositeGuiEffect(scene, float3(0.6, 0.6, 0.3), 1.0, GUI_BLEND_ADD));
    if (!rgbNear(added, float3(0.9, 1.0, 0.3))) return 1u;
    float3 inverted = compositeGuiEffect(added, float3(1.0), 1.0, GUI_BLEND_INVERT);
    if (!rgbNear(inverted, float3(0.1, 0.0, 0.7))) return 2u;
    float3 finalColor = compositeGuiEffect(inverted, float3(0.0, 0.4, 0.6), 1.0, GUI_BLEND_MAX);
    return rgbNear(finalColor, float3(0.1, 0.4, 0.7)) ? 0u : 4u;
}

export __extern_cpp uint testUiReconstructsTranslucencyAndExtremes() {
    float3 scene = float3(0.0, 0.6, 1.0);
    float3 premultiplied = float3(0.315, 0.07, 0.21);
    float3 result = float3(0.315, 0.46, 0.86);
    float4 ui = resolveGuiUi(scene, result, 0.35, 0.0);
    if (!validUi(ui, scene, result)) return 1u;
    if (abs(ui.a - 0.35) > 1.0e-5) return 8u;
    if (!rgbNear(ui.rgb, premultiplied)) return 16u;
    if (any(resolveGuiUi(scene, scene, 0.0, 0.0) != 0.0)) return 2u;
    float4 covered = resolveGuiUi(scene, scene, 0.0, 0.6);
    if (abs(covered.a - 0.6) > 1.0e-5 || !rgbNear(covered.rgb, float3(0.6) * scene)) return 4u;

    scene = float3(0.0, 1.0, 0.5);
    result = float3(0.75, 0.25, 0.5);
    ui = resolveGuiUi(scene, result, 0.0, 0.0);
    return validUi(ui, scene, result) && abs(ui.a - 0.75) < 1.0e-5 ? 0u : 4u;
}

export __extern_cpp uint testUiReconstructsPqAfterLinearHdrCompositing() {
    // ST.2084 code values for world nits (0, 80, 1200) and an authored 40%-opaque
    // overlay of (200, 20, 0) nits. Reconstruction is in PQ, not linear nits.
    float3 scene = float3(0.000000731, 0.4858568, 0.7717115);
    float3 result = float3(0.4858568, 0.4511087, 0.7160714);
    float4 ui = resolveGuiUi(scene, result, 0.4, 0.0);
    if (!validUi(ui, scene, result)) return 1u;
    return ui.a > 0.48 && ui.a < 0.49 ? 0u : 2u;
}
