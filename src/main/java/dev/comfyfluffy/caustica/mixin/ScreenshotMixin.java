package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.client.RtScreenshotExporter;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;
import java.util.function.Consumer;

/** Hooks only vanilla's auto-named F2 capture; named panorama/debug captures remain PNG-only. */
@Mixin(Screenshot.class)
public abstract class ScreenshotMixin {
    @Inject(
            method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void caustica$exportResidualExposureExr(
            File workDir,
            @Nullable String forceName,
            RenderTarget target,
            int downscaleFactor,
            Consumer<Component> callback,
            CallbackInfo ci
    ) {
        if (forceName == null && downscaleFactor == 1
                && CausticaConfig.Rt.Screenshots.EXR_ENABLED.value()) {
            String pairedPngName = RtScreenshotExporter.exportPaired(workDir, callback);
            if (pairedPngName != null) {
                // Re-enter vanilla's named path with our reserved PNG name. The non-null name bypasses
                // this hook on the nested call and makes both outputs use exactly one basename.
                Screenshot.grab(workDir, pairedPngName, target, downscaleFactor, callback);
                ci.cancel();
            }
        }
    }
}
