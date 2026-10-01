package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.comfyfluffy.caustica.CausticaMod;
import java.util.Locale;
import net.minecraft.util.TimeSource;
import org.lwjgl.sdl.SDLHints;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Select the native Wayland driver before SDL creates windows so HDR remains available at runtime. */
@Mixin(RenderSystem.class)
public abstract class SdlBootstrapMixin {
    @Inject(method = "initBackendSystem", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/sdl/SDLInit;SDL_Init(I)Z"))
    private static void caustica$preferWayland(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (!caustica$isLinux()) {
            return;
        }
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        boolean waylandSession = waylandDisplay != null && !waylandDisplay.isBlank()
                || "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"));
        if (waylandSession) {
            // Respect an explicit driver selection; allow X11 fallback when Wayland initialization fails.
            SDLHints.SDL_SetHint("SDL_VIDEO_DRIVER", "wayland,x11");
        }
    }

    @Inject(method = "initBackendSystem", at = @At("RETURN"))
    private static void caustica$logVideoDriver(CallbackInfoReturnable<TimeSource.NanoTimeSource> cir) {
        if (!caustica$isLinux()) {
            return;
        }
        String driver = SDLVideo.SDL_GetCurrentVideoDriver();
        if ("wayland".equals(driver)) {
            CausticaMod.LOGGER.info("HDR: SDL initialized with the native Wayland driver");
        } else {
            CausticaMod.LOGGER.warn("HDR: SDL initialized with {}; Linux HDR requires native Wayland", driver);
        }
    }

    @Unique
    private static boolean caustica$isLinux() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }
}
