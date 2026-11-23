package dev.comfyfluffy.caustica.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import dev.comfyfluffy.caustica.rt.RtGuiBlend;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain vanilla GUI draw order while compositing each kind of blend onto the world. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererMixin {
	@Shadow @Final private List<?> draws;

	@WrapMethod(method = "executeDrawRange")
	private void caustica$compositeGuiLayers(Supplier<String> label, RenderTarget target,
			GpuBufferSlice transforms, int start, int end, Operation<Void> original) {
		if (!RtUiOverlay.owns(target)) {
			original.call(label, target, transforms, start, end);
			return;
		}
		RtUiOverlay.beginGuiRange();
		int first = start;
		while (first < end) {
			RtGuiBlend.Mode mode = caustica$mode(first);
			int next = first + 1;
			while (next < end && caustica$mode(next) == mode) {
				next++;
			}
			if (mode == RtGuiBlend.Mode.OVER) {
				original.call(label, target, transforms, first, next);
				RtUiOverlay.noteGuiDraws();
			} else if (RtUiOverlay.beginGuiEffect(mode)) {
				original.call(label, target, transforms, first, next);
				RtUiOverlay.finishGuiEffect(mode);
			} else {
				original.call(label, target, transforms, first, next);
				RtUiOverlay.noteGuiDraws();
			}
			first = next;
		}
	}

	private RtGuiBlend.Mode caustica$mode(int index) {
		return RtGuiBlend.mode(((GuiDrawAccessor) draws.get(index)).caustica$getPipeline());
	}

	@Inject(method = "draw", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/GameRenderer;processBlurEffect()V"))
	private void caustica$finishOrderedGuiBeforeBlur(CallbackInfo ci) {
		RtUiOverlay.flushSegmentedGuiBeforeBlur();
	}

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
