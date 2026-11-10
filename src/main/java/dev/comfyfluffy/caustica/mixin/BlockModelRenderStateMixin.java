package dev.comfyfluffy.caustica.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.comfyfluffy.caustica.rt.entity.ContainedBlockSource;
import dev.comfyfluffy.caustica.rt.entity.RtEntityCollector;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.BlockModelRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes contained block-model displays (block display entities, enderman carried blocks, item frames, etc.)
 * into {@link RtEntityCollector} with their resolved {@link BlockState} context so state-aware material
 * profiles, tints, multi-layer parts, and special block-entity renderers are all preserved during RT capture.
 */
@Mixin(BlockModelRenderState.class)
public abstract class BlockModelRenderStateMixin implements ContainedBlockSource {
    @Shadow @Nullable private List<BlockStateModelPart> modelParts;
    @Shadow @Nullable private Matrix4fc transformation;
    @Shadow @Nullable private SpecialModelRenderer<?> specialRenderer;
    @Shadow @Nullable private Matrix4fc specialRendererTransformation;
    @Shadow @Nullable private IntList tintLayers;

    @Shadow
    private void submitSpecialRenderer(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                       int externalLightCoords, int overlayCoords, int outlineColor) {
        throw new AssertionError("mixin stub");
    }

    @Unique private BlockState caustica$containedState;

    @Override
    public @Nullable BlockState caustica$containedBlock() {
        return this.caustica$containedState;
    }

    @Override
    public void caustica$setContainedBlock(@Nullable BlockState state) {
        this.caustica$containedState = state;
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void caustica$clearContained(CallbackInfo ci) {
        this.caustica$containedState = null;
    }

    @Inject(method = {"submit", "submitMultiLayer"}, at = @At("HEAD"), cancellable = true)
    private void caustica$captureForRt(PoseStack poseStack, SubmitNodeCollector submitNodeCollector,
                                       int externalLightCoords, int overlayCoords, int outlineColor, CallbackInfo ci) {
        if (this.caustica$containedState == null || !(submitNodeCollector instanceof RtEntityCollector rt)) {
            return;
        }
        int[] tints = this.tintLayers != null
                ? this.tintLayers.toArray(BlockModelRenderState.EMPTY_TINTS)
                : BlockModelRenderState.EMPTY_TINTS;
        boolean capturedWorldModel = false;
        if (!this.caustica$containedState.isAir()) {
            Matrix4fc modelTransform = this.transformation != null
                    ? this.transformation
                    : (this.specialRenderer == null ? this.specialRendererTransformation : null);
            capturedWorldModel = rt.captureBlockState(
                    this.caustica$containedState, modelTransform, tints, poseStack, outlineColor);
        }
        if (!capturedWorldModel && this.modelParts != null && !this.modelParts.isEmpty()) {
            rt.captureBlockModelParts(this.modelParts, this.transformation, tints, poseStack, outlineColor);
        }
        if (this.specialRenderer != null) {
            this.submitSpecialRenderer(poseStack, submitNodeCollector, externalLightCoords, overlayCoords, outlineColor);
        }
        ci.cancel();
    }
}
