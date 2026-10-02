import overlay_common;

[[vk::push_constant]] OverlayPush pc;

[shader("fragment")]
float4 main() : SV_Target0 {
    return pc.color;
}
