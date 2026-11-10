package dev.comfyfluffy.caustica.mixin;

import dev.comfyfluffy.caustica.compat.AtlasSpriteFinder;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.minecraft.client.GameLoadCookie;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * Coordinates RT pipeline teardown and re-enablement across Minecraft resource reloads (manual F3+T,
 * pack-screen changes, and resource-pack recovery). Drains in-flight RT work right before pack reloading
 * starts — after deferred/queued reload checks have passed — so descriptors referencing old atlas/entity
 * textures are destroyed before Minecraft frees those GPU images, then clears the reload gate once the new
 * reload finishes or recovery aborts.
 */
@Mixin(Minecraft.class)
public class MinecraftReloadMixin {
    @Inject(
            method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/repository/PackRepository;reload()V"))
    private void caustica$rtReloadStart(boolean isRecovery, GameLoadCookie loadCookie,
            CallbackInfoReturnable<CompletableFuture<Void>> cir) {
        RtComposite.INSTANCE.onResourceReloadStart();
        AtlasSpriteFinder.clearCache();
    }

    @Inject(method = "onResourceLoadFinished(Lnet/minecraft/client/GameLoadCookie;)V", at = @At("RETURN"))
    private void caustica$rtReloadFinished(GameLoadCookie loadCookie, CallbackInfo ci) {
        RtComposite.INSTANCE.onResourceReloadFinished();
    }

    @Inject(method = "abortResourcePackRecovery()V", at = @At("RETURN"))
    private void caustica$rtReloadRecoveryAborted(CallbackInfo ci) {
        RtComposite.INSTANCE.onResourceReloadFinished();
    }
}
