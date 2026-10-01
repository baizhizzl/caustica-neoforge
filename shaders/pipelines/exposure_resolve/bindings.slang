import display_common;

[[vk::binding(0, 0)]] public StructuredBuffer<uint, Std430DataLayout> histBins;
[[vk::binding(1, 0)]] [format("r32f")] public RWTexture2D<float> exposureImage;
[[vk::binding(2, 0)]] public RWStructuredBuffer<ExposureState, Std430DataLayout> stateBuf;
