#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 layer = texture(InSampler, texCoord);
#if defined(COVERAGE_MULTIPLY)
    float coverage = 1.0 - min(layer.r, min(layer.g, layer.b));
#elif defined(COVERAGE_INVERT)
    float coverage = max(layer.r, max(layer.g, layer.b));
#elif defined(COVERAGE_ADD) || defined(COVERAGE_MAX)
    float coverage = max(layer.r, max(layer.g, layer.b));
#else
    float coverage = layer.a;
#endif
    // Premultiplied-over blends both red and alpha into the union of successive UI layers.
    fragColor = vec4(coverage, 0.0, 0.0, coverage);
}
