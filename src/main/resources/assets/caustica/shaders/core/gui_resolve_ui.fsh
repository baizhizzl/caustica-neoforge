#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D WorldSampler;
uniform sampler2D FinalSampler;
uniform sampler2D CoverageSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

float requiredAlpha(float background, float result) {
    float alpha = 0.0;
    if (background > 0.0) alpha = max(alpha, 1.0 - result / background);
    if (background < 1.0) alpha = max(alpha, (result - background) / (1.0 - background));
    return alpha;
}

void main() {
    vec3 background = texture(WorldSampler, texCoord).rgb;
    vec3 result = texture(FinalSampler, texCoord).rgb;
    float coverage = texture(CoverageSampler, texCoord).r;
    float alpha = clamp(max(coverage, max(requiredAlpha(background.r, result.r),
            max(requiredAlpha(background.g, result.g), requiredAlpha(background.b, result.b)))), 0.0, 1.0);
    // Round coverage up to UNORM8 so the representable premultiplied color still has enough range.
    alpha = ceil(alpha * 255.0) / 255.0;
    fragColor = vec4(clamp(result - (1.0 - alpha) * background, 0.0, alpha), alpha);
}
