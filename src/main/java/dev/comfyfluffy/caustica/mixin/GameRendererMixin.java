package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.client.VanillaRenderController;
import dev.comfyfluffy.caustica.client.WorldRenderScaler;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtReflex;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.overlay.RtWorldOverlay;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep world scaling, RT frame ownership and screen-fixed overlays at the renderer's frame seams. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Shadow public abstract GameRenderState gameRenderState();

    @Inject(method = "render()V", at = @At("HEAD"))
    private void caustica$beginFrame(CallbackInfo ci) {
        RtUiOverlay.beginFrame();
        // Menu frames must not present stale HDR world images.
        RtComposite.INSTANCE.beginFrame();
        if (RtReflex.enabled()) {
            long swapchain = RtReflex.INSTANCE.appliedSwapchain();
            if (swapchain != 0L
                    && ((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
                RtReflex.INSTANCE.marker(device.vkDevice(), swapchain, RtReflex.MARKER_RENDERSUBMIT_START,
                        RtReflex.INSTANCE.currentSimFrameId());
            }
        }
    }

    @Inject(method = "render()V", at = @At("TAIL"))
    private void caustica$endFrame(CallbackInfo ci) {
        RtComposite.INSTANCE.endFrame();
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;renderLevel()V"))
    private void caustica$beginWorldScale(CallbackInfo ci) {
        WorldRenderScaler.INSTANCE.begin(this.mainRenderTarget);
    }

    @ModifyArg(method = "renderLevel()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f caustica$captureLevelProjection(Matrix4f projection) {
        if (VanillaRenderController.rtRuntimeWorkRequested()) {
            var camera = this.gameRenderState().levelRenderState.cameraRenderState;
            RtComposite.INSTANCE.captureFrame(projection, camera.viewRotationMatrix,
                    camera.pos.x, camera.pos.y, camera.pos.z);
            VanillaRenderController.INSTANCE.markProjectionCaptured();
        }
        return projection;
    }

    @Inject(method = "render3dHud", at = @At("HEAD"))
    private void caustica$finishWorldBeforeHud(CameraRenderState camera, PlayerRenderState player,
            OptionsRenderState options, boolean consistentDepthRequired, CallbackInfo ci) {
        WorldRenderScaler.INSTANCE.end(this.mainRenderTarget);
        try {
            RtWorldOverlay.INSTANCE.compositeIntoUiOverlay(this.mainRenderTarget, RtComposite.INSTANCE.currentGraphicsUse());
        } finally {
            // The overlay's TLAS read must enter the graphics submission before this token is signalled.
            RtComposite.INSTANCE.finishGraphicsUse();
        }
    }

    @ModifyExpressionValue(method = {"renderItemInHand", "render3dHud"}, at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget caustica$redirectScreenFixedOverlays(RenderTarget original) {
        return RtUiOverlay.enabled() ? RtUiOverlay.beginAndRedirect(original) : original;
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V", shift = At.Shift.AFTER))
    private void caustica$restoreWorldScale(CallbackInfo ci) {
        WorldRenderScaler.INSTANCE.endSafetyNet(this.mainRenderTarget);
    }

    @Inject(method = "render()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V", shift = At.Shift.AFTER))
    private void caustica$compositeUiOverlay(CallbackInfo ci) {
        RtComposite.INSTANCE.captureFgHudless(this.mainRenderTarget);
        RtUiOverlay.compositeIfUsed();
    }
}
