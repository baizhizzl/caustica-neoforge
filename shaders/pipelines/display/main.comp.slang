// Maps the display-res scene-linear ACEScg RT image to sRGB SDR and, when enabled, PQ/BT.2020 HDR,
// via baked ACES 2.0 display-transform LUTs (see tools/bake_display_lut.py). Tonemap seam: the path tracer and DLSS-RR use scene-linear
// ACEScg; exposure is applied from the compositor-owned 1x1 image right here, followed by contrast-adaptive
// luminance sharpening, bloom, the scene-referred ACES look (LMT), perceptual contrast/vibrance grading,
// and the shared ACES 2.0 output transform for each display.

import display_common;
import bindings;

[[vk::push_constant]] DisplayPush pc;

// MUST exactly match SHAPER_LO_STOPS/SHAPER_HI_STOPS in tools/bake_display_lut.py -- these are the two
// halves of one shaper function, one run at bake time (Python), one at sample time (here); if they
// drift the LUT fetch reads the wrong shelf of the LUT everywhere, silently.
static const float LUT_SHAPER_LO_STOPS = -12.0;
static const float LUT_SHAPER_HI_STOPS = 12.0;
static const float3 ACESCG_LUMA = float3(0.27222872, 0.67408177, 0.05368952);
static const float MID_GREY_ACESCG = 0.18;

float3 shaperEncode(float3 linearColor) {
    float3 stops = log2(max(linearColor, float3(1.0e-6)));
    return clamp((stops - LUT_SHAPER_LO_STOPS) / (LUT_SHAPER_HI_STOPS - LUT_SHAPER_LO_STOPS), 0.0, 1.0);
}

// Edge-aligned LUT: texel 0's centre is uvw=0.0 and texel (lutSize-1)'s centre is uvw=1.0, but a
// sampler's normalized [0,1] domain spans texel *centres* at 0.5/N .. 1-0.5/N by default. Remap into
// that convention; RtToneLut's CLAMP_TO_EDGE then holds the boundary texel for any exposed value
// outside the shaper's +/-12 EV range instead of wrapping. toneLut/hdrToneLut share pc.lutSize;
// the independently sized lookLut passes pc.lookLutSize.
float3 lutTexCoord(float3 uvw, float size) {
    return uvw * ((size - 1.0) / size) + float3(0.5 / size);
}

float3 shaperDecode(float3 shaped) {
    return exp2(lerp(
            float3(LUT_SHAPER_LO_STOPS),
            float3(LUT_SHAPER_HI_STOPS),
            clamp(shaped, 0.0, 1.0)));
}

float3 applyLook(float3 exposedAcesCg) {
    if (pc.lookEnabled == 0) {
        return exposedAcesCg;
    }
    float3 uvw = shaperEncode(exposedAcesCg);
    float3 shaped = lookLut.SampleLevel(lutTexCoord(uvw, pc.lookLutSize), 0.0).rgb;
    return shaperDecode(shaped);
}

float3 sampleBloom(int2 outputPixel, uint outputWidth, uint outputHeight) {
    float2 uv = (float2(outputPixel) + 0.5) / float2(outputWidth, outputHeight);
    return bloomImage.SampleLevel(uv, 0.0).rgb;
}

float perceptualLuma(float3 exposedColor) {
    return sqrt(max(dot(exposedColor, ACESCG_LUMA), 0.0));
}

// False for +/-Inf AND for NaN: an ordered float comparison against NaN is false, and the magnitude
// bound rejects infinities. (Do not use max() here, which may return the finite operand for a NaN pair.)
bool finiteColor(float3 v) {
    return abs(v.x) < 1.0e30 && abs(v.y) < 1.0e30 && abs(v.z) < 1.0e30;
}

// 16x16 workgroup tile plus a 1-pixel border for 5-tap cross sharpening. Stores perceptual sqrt-luma
// directly (-1.0 sentinel for NaN/Inf) so a 256-thread group issues 324 HDR reads instead of 1,280.
groupshared float tileLuma[18][18];

float sampleTileLuma(int2 coord, int maxX, int maxY, float exposure) {
    int2 clamped = clamp(coord, int2(0, 0), int2(maxX, maxY));
    float3 rawExposed = rtImage[clamped].rgb * exposure;
    if (!finiteColor(rawExposed)) {
        return -1.0;
    }
    return perceptualLuma(max(rawExposed, float3(0.0)));
}

// Contrast-adaptive 5-tap cross sharpening in perceptual sqrt-luminance space. Strictly clamped to the
// 5-tap neighbourhood min/max so DLSS-RR upscaled block textures gain crisp micro-detail with zero
// specular ringing or dark halos, while scaling RGB uniformly to preserve exact ACEScg chromaticity.
float3 sharpenExposedFromTile(uint2 localId, float3 centerExposed, float lC, float strength) {
    if (lC <= 1.0e-5) {
        return centerExposed;
    }
    uint lx = localId.x + 1u;
    uint ly = localId.y + 1u;
    float lN = tileLuma[ly - 1u][lx];
    float lS = tileLuma[ly + 1u][lx];
    float lW = tileLuma[ly][lx - 1u];
    float lE = tileLuma[ly][lx + 1u];
    if (lN < 0.0 || lS < 0.0 || lW < 0.0 || lE < 0.0) {
        return centerExposed;
    }

    float minL = min(lC, min(min(lN, lS), min(lW, lE)));
    float maxL = max(lC, max(max(lN, lS), max(lW, lE)));
    // Compress into [0,1] shoulder so HDR highlights taper the negative lobe automatically.
    float normMax = maxL / (1.0 + maxL);
    float normMin = minL / (1.0 + maxL);
    float amp = sqrt(clamp(min(normMin, max(1.0 - normMax, 0.0)) / max(normMax, 1.0e-4), 0.0, 1.0));
    float peak = -1.0 / lerp(8.0, 4.5, strength);
    float wTap = amp * peak;
    float denom = max(1.0 + 4.0 * wTap, 1.0e-4);
    float sharpL = clamp((lC + wTap * (lN + lS + lW + lE)) / denom, minL, maxL);
    float ratio = (sharpL * sharpL) / max(lC * lC, 1.0e-8);
    return centerExposed * ratio;
}

// Scene-referred filmic log-contrast (pivoted at 18% mid-grey with soft highlight shoulder) and
// luminance-preserving perceptual vibrance, shared identically by SDR and HDR10 PQ output transforms.
float3 gradeSceneAcesCg(float3 color) {
    if (!finiteColor(color)) {
        return float3(0.0);
    }
    bool neutralContrast = abs(pc.contrast - 1.0) <= 1.0e-4;
    bool neutralVibrance = abs(pc.vibrance - 1.0) <= 1.0e-4;
    // Strict identity pass-through at neutral defaults so the look-LUT output reaches the display
    // transform untouched with zero numerical drift.
    if (neutralContrast && neutralVibrance) {
        return color;
    }
    float3 graded = max(color, float3(0.0));
    if (!neutralContrast) {
        float y = dot(graded, ACESCG_LUMA);
        if (y > 1.0e-6) {
            float stops = log2(y / MID_GREY_ACESCG);
            float delta = (pc.contrast - 1.0) / (1.0 + 0.06 * stops * stops);
            float yAdjusted = MID_GREY_ACESCG * exp2(stops * (1.0 + delta));
            graded *= yAdjusted / y;
            if (!finiteColor(graded)) {
                return float3(0.0);
            }
        }
    }
    if (!neutralVibrance) {
        float luma = dot(graded, ACESCG_LUMA);
        float maxC = max(graded.r, max(graded.g, graded.b));
        float minC = min(graded.r, min(graded.g, graded.b));
        float sat = (maxC - minC) / max(maxC, 1.0e-4);
        float boost = pc.vibrance >= 1.0
                ? 1.0 + (pc.vibrance - 1.0) * (1.0 - sat * 0.65)
                : max(pc.vibrance, 0.0);
        float3 vibr = float3(luma) + (graded - float3(luma)) * boost;
        // Clamping a negative low channel would move ACEScg luma. When that happens, clamp to zero and
        // uniformly rescale the remaining non-negative channels so the authored luminance is restored.
        if (any(vibr < float3(0.0))) {
            vibr = max(vibr, float3(0.0));
            float luma2 = dot(vibr, ACESCG_LUMA);
            if (luma2 > 1.0e-6 && luma > 0.0) {
                vibr *= luma / luma2;
            }
        }
        graded = vibr;
        if (!finiteColor(graded)) {
            return float3(0.0);
        }
    }
    return graded;
}

// Artistic gamma after the view/display transform. Apply the curve to display luminance and scale
// RGB uniformly instead of exponentiating each channel: this lifts shadows/midtones without pulling
// chromaticities toward white. The uniform scale is limited at the display boundary so saturated
// colors preserve their channel ratios instead of clipping individual channels.
static const float3 BT709_LUMA = float3(0.2126, 0.7152, 0.0722);
static const float3 BT2020_LUMA = float3(0.2627, 0.6780, 0.0593);

float3 luminanceGamma(float3 linearDisplayColor, float3 lumaCoefficients) {
    float3 color = clamp(linearDisplayColor, 0.0, 1.0);
    float luminance = dot(color, lumaCoefficients);
    if (luminance <= 0.0) {
        return color;
    }

    float adjustedLuminance = pow(luminance, clamp(pc.gamma, 0.1, 5.0));
    float scale = adjustedLuminance / luminance;
    float maxChannel = max(color.r, max(color.g, color.b));
    float gamutLimitedScale = 1.0 / max(maxChannel, 1.0e-8);
    return color * min(scale, gamutLimitedScale);
}

float srgbDecodeChannel(float code) {
    return code <= 0.04045 ? code / 12.92 : pow((code + 0.055) / 1.055, 2.4);
}

float srgbEncodeChannel(float linearValue) {
    return linearValue <= 0.0031308
            ? 12.92 * linearValue
            : 1.055 * pow(linearValue, 1.0 / 2.4) - 0.055;
}

float3 srgbDecode(float3 code) {
    return float3(srgbDecodeChannel(code.r), srgbDecodeChannel(code.g), srgbDecodeChannel(code.b));
}

float3 srgbEncode(float3 linearColor) {
    return float3(
            srgbEncodeChannel(linearColor.r),
            srgbEncodeChannel(linearColor.g),
            srgbEncodeChannel(linearColor.b));
}

float3 displayGammaSdr(float3 displayColor) {
    float3 code = clamp(displayColor, 0.0, 1.0);
    if (abs(pc.gamma - 1.0) < 1.0e-6) {
        return code;
    }
    return clamp(srgbEncode(luminanceGamma(srgbDecode(code), BT709_LUMA)), 0.0, 1.0);
}

static const float PQ_M1 = 0.1593017578125;
static const float PQ_M2 = 78.84375;
static const float PQ_C1 = 0.8359375;
static const float PQ_C2 = 18.8515625;
static const float PQ_C3 = 18.6875;

float3 pqDecode(float3 code) {
    float3 p = pow(clamp(code, 0.0, 1.0), float3(1.0 / PQ_M2));
    float3 y = max((p - PQ_C1) / max(PQ_C2 - PQ_C3 * p, float3(1.0e-6)), float3(0.0));
    return pow(y, float3(1.0 / PQ_M1)) * 10000.0;
}

float3 pqEncode(float3 nits) {
    float3 y = pow(max(nits, float3(0.0)) / 10000.0, float3(PQ_M1));
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), float3(PQ_M2));
}

float3 displayGammaHdr(float3 pqColor) {
    if (abs(pc.gamma - 1.0) < 1.0e-6) {
        return clamp(pqColor, 0.0, 1.0);
    }
    float peak = max(pc.hdrPeakNits, 1.0);
    float3 normalizedNits = clamp(pqDecode(pqColor) / peak, 0.0, 1.0);
    float3 adjustedNits = peak * luminanceGamma(normalizedNits, BT2020_LUMA);
    return clamp(pqEncode(adjustedNits), 0.0, 1.0);
}

// Spatial Interleaved Gradient Noise mapped to a zero-mean symmetric triangular PDF on [-1, 1] (1 LSB
// span in 8-bit UNORM). Kept spatially stationary so post-RR 8-bit SDR quantization eliminates sky,
// volumetric-haze, and underwater gradient banding without introducing static shimmer into DLSS-FG.
float3 ditherSdr8Bit(float3 sdrColor, int2 pix) {
    if (all(sdrColor <= float3(0.0))) {
        return sdrColor;
    }
    float u = frac(52.9829189 * frac(dot(float2(pix) + 0.5, float2(0.06711056, 0.00583715))));
    float tri = u < 0.5 ? (sqrt(2.0 * u) - 1.0) : (1.0 - sqrt(2.0 * (1.0 - u)));
    return clamp(sdrColor + tri * (1.0 / 255.0), 0.0, 1.0);
}

// Exposure -> log2 shaper -> trilinear LUT fetch. The LUT already bakes in ACES 2.0's gamut mapping to
// BT.709 and the sRGB OETF, so its output goes straight to outputImage. Single mip (RtToneLut), so an
// explicit level-0 SampleLevel is exact, not an approximation.
float3 tonemap(float3 lookedAcesCg) {
    float3 uvw = shaperEncode(lookedAcesCg);
    return displayGammaSdr(toneLut.SampleLevel(lutTexCoord(uvw, pc.lutSize), 0.0).rgb);
}

// Same shaper as tonemap() but sampling the HDR LUT, whose range is already PQ-encoded BT.2020 -- no
// separate encode or UI-brightness mapping needed.
float3 tonemapHdr(float3 lookedAcesCg) {
    float3 uvw = shaperEncode(lookedAcesCg);
    return displayGammaHdr(hdrToneLut.SampleLevel(lutTexCoord(uvw, pc.lutSize), 0.0).rgb);
}

[shader("compute")]
[numthreads(16, 16, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID, uint3 groupId : SV_GroupID,
          uint3 localId : SV_GroupThreadID, uint groupIndex : SV_GroupIndex) {
    uint w, h;
    outputImage.GetDimensions(w, h);
    if (w == 0u || h == 0u) {
        return;
    }

    int2 pix = int2(dispatchId.xy);
    int maxX = int(w) - 1;
    int maxY = int(h) - 1;
    bool inBounds = pix.x <= maxX && pix.y <= maxY;
    float rawExposure = exposureImage[int2(0, 0)];
    float exposure = (rawExposure > 0.0 && rawExposure < 1.0e30) ? rawExposure : 0.0;
    float strength = clamp(pc.sharpness, 0.0, 1.0);

    float3 baseExposed = float3(0.0);
    float centerLuma = -1.0;
    if (inBounds) {
        float3 rawExposed = rtImage[pix].rgb * exposure;
        if (finiteColor(rawExposed)) {
            baseExposed = max(rawExposed, float3(0.0));
            centerLuma = perceptualLuma(baseExposed);
        }
    }

    float3 exposedAcesCg = baseExposed;
    // Uniform push-constant branch: all 256 threads participate in the haloed tile load + barrier together.
    if (strength > 1.0e-4) {
        int2 tileOrigin = int2(groupId.xy) * 16 - int2(1, 1);
        tileLuma[localId.y + 1u][localId.x + 1u] = inBounds
                ? centerLuma
                : sampleTileLuma(pix, maxX, maxY, exposure);
        if (groupIndex < 68u) {
            uint tx;
            uint ty;
            if (groupIndex < 18u) {
                tx = groupIndex;
                ty = 0u;
            } else if (groupIndex < 36u) {
                tx = groupIndex - 18u;
                ty = 17u;
            } else if (groupIndex < 52u) {
                tx = 0u;
                ty = (groupIndex - 36u) + 1u;
            } else {
                tx = 17u;
                ty = (groupIndex - 52u) + 1u;
            }
            tileLuma[ty][tx] = sampleTileLuma(tileOrigin + int2(int(tx), int(ty)), maxX, maxY, exposure);
        }
        GroupMemoryBarrierWithGroupSync();
        if (inBounds && centerLuma >= 0.0) {
            exposedAcesCg = sharpenExposedFromTile(localId.xy, baseExposed, centerLuma, strength);
        }
    }

    if (!inBounds) {
        return;
    }

    if (pc.bloomStrength > 0.0) {
        float3 bloom = sampleBloom(pix, w, h);
        if (finiteColor(bloom)) {
            exposedAcesCg += max(bloom, float3(0.0)) * pc.bloomStrength;
        }
    }
    float3 lookedAcesCg = gradeSceneAcesCg(applyLook(exposedAcesCg));
    outputImage[pix] = float4(ditherSdr8Bit(tonemap(lookedAcesCg), pix), 1.0);

    if (pc.hdrEnabled != 0) {
        hdrImage[pix] = float4(tonemapHdr(lookedAcesCg), 1.0);
    }
}
