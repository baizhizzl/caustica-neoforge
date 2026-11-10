package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtVideoOptions;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Surfaces the runtime-tunable RT settings inside the vanilla Video Settings screen when the RT renderer is
 * active on an RT-ready Vulkan device:
 *
 * <ul>
 *   <li>The Quality section drops the vanilla options the path tracer supersedes (Ambient Occlusion and
 *       Entity Shadows are computed by RT global illumination / RT shadows).</li>
 *   <li>A trailing "Ray Tracing" section adds the {@link RtVideoOptions} controls.</li>
 * </ul>
 *
 * When RT is disabled or unavailable on the active graphics backend/device, the screen is left untouched.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Shadow
    private static OptionInstance<?>[] qualityOptions(Options options) {
        throw new AssertionError("mixin stub");
    }

    private static final Component CAUSTICA$RT_HEADER = Component.translatable("caustica.options.rt.header");

    @Unique
    private static boolean caustica$showRtOptions() {
        return RtDeviceBringup.isRtReady() && CausticaConfig.Rt.ENABLED.value();
    }

    @Redirect(
        method = "addOptions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"))
    private OptionInstance<?>[] caustica$filterQualityOptions(Options options) {
        OptionInstance<?>[] base = qualityOptions(options);
        if (!caustica$showRtOptions()) {
            return base;
        }
        List<OptionInstance<?>> kept = new ArrayList<>(base.length);
        for (OptionInstance<?> option : base) {
            // Path-traced GI + RT shadows make these vanilla raster controls inert under RT.
            if (option == options.ambientOcclusion() || option == options.entityShadows()) {
                continue;
            }
            kept.add(option);
        }
        return kept.toArray(OptionInstance<?>[]::new);
    }

    @Inject(method = "addOptions", at = @At("TAIL"))
    private void caustica$addRtOptions(CallbackInfo ci) {
        if (!caustica$showRtOptions()) {
            return;
        }
        OptionsList list = ((OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }
        list.addHeader(CAUSTICA$RT_HEADER);
        RtVideoOptions.addTo(list);
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void caustica$saveConfig(CallbackInfo ci) {
        if (caustica$showRtOptions()) {
            // Persist any RT settings the player changed in this screen to the TOML config.
            CausticaConfig.save();
        }
    }
}
