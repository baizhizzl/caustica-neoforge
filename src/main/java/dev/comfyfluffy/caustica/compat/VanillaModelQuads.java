package dev.comfyfluffy.caustica.compat;

import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/** Emit NeoForge model quads with their effective chunk layer and the caller's face-culling policy. */
public final class VanillaModelQuads {
    private static final Direction[] DIRECTIONS = Direction.values();

    @FunctionalInterface
    public interface QuadSink {
        void accept(BakedQuad quad, ChunkSectionLayer layer);
    }

    private VanillaModelQuads() {}

    /** Worker/capture-owned scratch storage; nested model callbacks cannot overwrite the outer list. */
    public static final class Emitter {
        private final ScratchLists<BlockStateModelPart> scratch = new ScratchLists<>(4, 256);

        public void emit(BlockStateModel model, BlockAndTintGetter level, BlockPos pos,
                BlockState state, RandomSource random, Predicate<Direction> cullTest, QuadSink out) {
            boolean forceOpaque = ModelBlockRenderer.forceOpaque(
                    Minecraft.getInstance().options.cutoutLeaves().get(), state);
            var parts = scratch.acquire();
            try {
                // World context is required for connected textures and other position-dependent NeoForge models.
                model.collectParts(level, pos, state, random, parts);
                for (BlockStateModelPart part : parts) {
                    emitQuads(part, null, forceOpaque, out);
                    for (Direction direction : DIRECTIONS) {
                        if (!cullTest.test(direction)) {
                            emitQuads(part, direction, forceOpaque, out);
                        }
                    }
                }
            } finally {
                scratch.release();
            }
        }

        private static void emitQuads(BlockStateModelPart part, Direction face, boolean forceOpaque, QuadSink out) {
            for (BakedQuad quad : part.getQuads(face)) {
                out.accept(quad, forceOpaque ? ChunkSectionLayer.SOLID : quad.materialInfo().layer());
            }
        }
    }
}
