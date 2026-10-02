[[vk::binding(0, 0)]] [format("rgba16f")] public RWTexture2D<float4> colorImage;
[[vk::binding(1, 0)]] public RWStructuredBuffer<uint, Std430DataLayout> histBins;
[[vk::binding(2, 0)]] [format("r32f")] public RWTexture2D<float> depthImage;
[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> albedoImage;
