package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Route GUI draws to the shared premultiplied overlay; world blur still uses the main target. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@ModifyExpressionValue(
			method = "draw",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget caustica$redirectGuiToOverlay(RenderTarget original) {
		if (original != null && RtUiOverlay.enabled()) {
			return RtUiOverlay.beginAndRedirect(original);
		}
		return original;
	}
}
