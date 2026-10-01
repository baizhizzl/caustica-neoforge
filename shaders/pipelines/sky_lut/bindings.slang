[[vk::binding(0, 0)]] [format("rgba16f")] public RWTexture2D<float4> transmittanceImage;
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> multiScatterImage;
[[vk::binding(2, 0)]] [format("rgba16f")] public RWTexture2D<float4> skyViewImage;
[[vk::binding(3, 0)]] public Sampler2D transmittanceLut;
[[vk::binding(4, 0)]] public Sampler2D multiScatterLut;
