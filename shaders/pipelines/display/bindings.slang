[[vk::binding(0, 0)]] [format("rgba8")] public RWTexture2D<float4> outputImage;
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> rtImage;
[[vk::binding(2, 0)]] [format("r32f")] public RWTexture2D<float> exposureImage;
[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> hdrImage;
[[vk::binding(4, 0)]] public Sampler3D toneLut;
[[vk::binding(5, 0)]] public Sampler3D hdrToneLut;
[[vk::binding(6, 0)]] public Sampler3D lookLut;
[[vk::binding(7, 0)]] public Sampler2D bloomImage;
