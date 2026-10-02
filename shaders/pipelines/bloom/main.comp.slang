// Scene-referred bloom: a downsample/upsample mip pyramid (Jimenez, "Next Generation Post Processing in
// Call of Duty: Advanced Warfare", SIGGRAPH 2014). One dispatch per pyramid step; the step's source and
// destination are bound as a dedicated descriptor set by RtBloomPipeline, so nothing here indexes a
// descriptor array.
//
// Why a pyramid and not the previous single wide Gaussian. That pass took nine taps per axis at a spacing
// of `radius` HALF-RES pixels (2.0 at 1080p, and it scaled with resolution, so ~2.7 at 1440p). Nine
// bilinear taps spaced two or more texels apart is a comb, not a filter: for a small bright source the
// output is nine shifted copies per axis, i.e. a visible 9x9 lattice of replicas — the reported "grid".
// A pyramid only ever samples one level below itself, so every tap is within a texel or two of its
// neighbours at that level and the kernel is dense at every scale it covers.
//
// The same structure fixes the "solid block" at high intensity. One Gaussian has one width and a hard
// support: a source far above the threshold saturates its entire footprint uniformly and clips to a flat
// slab with a visible boundary. The pyramid's response is the sum of levels, each twice as wide and
// (after the threshold) carrying comparable energy, which gives the wide, monotonically decaying skirt a
// real lens produces instead of a plateau with an edge. The Karis average on the first downsample
// (below) keeps a single blown-out pixel from dominating that skirt.
import display_common;
import bindings;

[[vk::push_constant]] BloomPush pc;

static const float3 ACESCG_LUMA = float3(0.2722287168, 0.6740817658, 0.0536895174);
static const float HALF_MAX = 65504.0;
static const float BLOOM_ROLLOFF_START = 25.0;
static const float BLOOM_CONTRIBUTION_CEILING = 50.0;

static const int MODE_PREFILTER = 0;
static const int MODE_DOWNSAMPLE = 1;
static const int MODE_UPSAMPLE = 2;

float3 tap(float2 uv, float2 offset) {
    return srcImage.SampleLevel(uv + offset, 0.0).rgb;
}

float rollOffBloomContribution(float contribution) {
    if (contribution <= BLOOM_ROLLOFF_START) {
        return contribution;
    }

    // Match the linear response and slope at the shoulder, then approach the
    // ceiling asymptotically so exceptionally bright sources cannot dominate.
    float shoulder = BLOOM_CONTRIBUTION_CEILING - BLOOM_ROLLOFF_START;
    return BLOOM_ROLLOFF_START
        + shoulder * (1.0 - exp(-(contribution - BLOOM_ROLLOFF_START) / shoulder));
}

// Quadratic soft-knee threshold (Jimenez): below `threshold - knee` nothing passes, above
// `threshold + knee` the full excess passes, and the transition is C1 so a highlight sliding across the
// threshold does not pop. The result scales the ORIGINAL colour, so hue is untouched.
float3 prefilter(float3 color) {
    color = clamp(color, float3(0.0), float3(HALF_MAX));
    float luminance = dot(color, ACESCG_LUMA);
    float knee = max(pc.softKnee, 1.0e-6);
    float soft = clamp(luminance - pc.threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee);
    float contribution = max(luminance - pc.threshold, soft);
    contribution = rollOffBloomContribution(contribution);
    return color * (contribution / max(luminance, 1.0e-6));
}

// Karis weight: average in a domain where a single very bright sample cannot outvote its neighbours.
// Applied per 2x2 group (not per tap) so it stays a partial average and does not flatten real highlights.
float karisWeight(float3 color) {
    return 1.0 / (1.0 + dot(color, ACESCG_LUMA));
}

// 13-tap downsample. The five overlapping 2x2 groups (one centred, four at the corners) reconstruct a
// smooth kernel with no sample landing on the destination lattice, which is what keeps the chain free of
// the aliasing pattern a plain 2x2 box downsample produces.
float3 downsample(float2 uv, float2 srcTexel, bool karis) {
    float3 a = tap(uv, float2(-2.0, -2.0) * srcTexel);
    float3 b = tap(uv, float2( 0.0, -2.0) * srcTexel);
    float3 c = tap(uv, float2( 2.0, -2.0) * srcTexel);
    float3 d = tap(uv, float2(-1.0, -1.0) * srcTexel);
    float3 e = tap(uv, float2( 1.0, -1.0) * srcTexel);
    float3 f = tap(uv, float2(-2.0,  0.0) * srcTexel);
    float3 g = tap(uv, float2( 0.0,  0.0) * srcTexel);
    float3 h = tap(uv, float2( 2.0,  0.0) * srcTexel);
    float3 i = tap(uv, float2(-1.0,  1.0) * srcTexel);
    float3 j = tap(uv, float2( 1.0,  1.0) * srcTexel);
    float3 k = tap(uv, float2(-2.0,  2.0) * srcTexel);
    float3 l = tap(uv, float2( 0.0,  2.0) * srcTexel);
    float3 m = tap(uv, float2( 2.0,  2.0) * srcTexel);

    float3 group0 = (d + e + i + j) * 0.25; // centre 2x2
    float3 group1 = (a + b + g + f) * 0.25;
    float3 group2 = (b + c + h + g) * 0.25;
    float3 group3 = (f + g + l + k) * 0.25;
    float3 group4 = (g + h + m + l) * 0.25;

    if (!karis) {
        return group0 * 0.5 + (group1 + group2 + group3 + group4) * 0.125;
    }
    float w0 = 0.5   * karisWeight(group0);
    float w1 = 0.125 * karisWeight(group1);
    float w2 = 0.125 * karisWeight(group2);
    float w3 = 0.125 * karisWeight(group3);
    float w4 = 0.125 * karisWeight(group4);
    float sum = w0 + w1 + w2 + w3 + w4;
    return (group0 * w0 + group1 * w1 + group2 * w2 + group3 * w3 + group4 * w4) / max(sum, 1.0e-6);
}

// 3x3 tent upsample, radius in SOURCE texels. Radius 1 is the standard filter; larger values widen every
// level of the skirt at once, which is the only knob the pyramid needs (the level count sets its reach).
float3 upsample(float2 uv, float2 srcTexel, float radius) {
    float2 o = srcTexel * radius;
    float3 sum = tap(uv, float2(-o.x,  o.y)) + tap(uv, float2(0.0,  o.y)) * 2.0 + tap(uv, float2(o.x,  o.y));
    sum += tap(uv, float2(-o.x, 0.0)) * 2.0 + tap(uv, float2(0.0, 0.0)) * 4.0 + tap(uv, float2(o.x, 0.0)) * 2.0;
    sum += tap(uv, float2(-o.x, -o.y)) + tap(uv, float2(0.0, -o.y)) * 2.0 + tap(uv, float2(o.x, -o.y));
    return sum * (1.0 / 16.0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    int2 pixel = int2(dispatchId.xy);
    uint width, height;
    dstImage.GetDimensions(width, height);
    if (pixel.x >= int(width) || pixel.y >= int(height)) {
        return;
    }
    uint srcWidth, srcHeight;
    srcImage.GetDimensions(srcWidth, srcHeight);
    float2 srcTexel = 1.0 / float2(float(srcWidth), float(srcHeight));
    float2 uv = (float2(pixel) + 0.5) / float2(float(width), float(height));

    if (pc.mode == MODE_PREFILTER) {
        // The pyramid works on EXPOSED values so the threshold is a display-referred decision (how far
        // above mid-grey a highlight has to be), not one that drifts with the scene's absolute level.
        float exposure = max(exposureImage[int2(0, 0)], 0.0);
        float3 color = downsample(uv, srcTexel, true) * exposure;
        dstImage[pixel] = float4(prefilter(color), 1.0);
    } else if (pc.mode == MODE_DOWNSAMPLE) {
        dstImage[pixel] = float4(downsample(uv, srcTexel, false), 1.0);
    } else {
        // In-place accumulate: this level already holds its own downsampled band, and the coarser level
        // tented up onto it adds the next-wider band. Summing on the way down the pyramid is what turns
        // the per-level bands into one continuous falloff.
        float3 coarse = upsample(uv, srcTexel, max(pc.radius, 0.0));
        dstImage[pixel] = float4(dstImage[pixel].rgb + coarse, 1.0);
    }
}
