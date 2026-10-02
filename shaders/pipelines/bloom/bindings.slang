[[vk::binding(0, 0)]] [format("rgba16f")] public RWTexture2D<float4> dstImage;
[[vk::binding(1, 0)]] public Sampler2D srcImage;
[[vk::binding(2, 0)]] [format("r32f")] public RWTexture2D<float> exposureImage;
