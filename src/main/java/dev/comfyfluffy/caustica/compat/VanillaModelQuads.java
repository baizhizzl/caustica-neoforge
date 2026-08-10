package dev.comfyfluffy.caustica.compat;

import java.util.ArrayList;
import java.util.List;
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

/**
 * Vanilla-equivalent of FRAPI's {@code FabricBlockStateModel.emitQuads} default implementation:
 * collects the model's parts and delivers each baked quad with its effective chunk layer, honoring
 * the cull predicate (true = that face's quads are discarded) and the fast-graphics force-opaque
 * leaves rule (layer overridden to SOLID). Replaces Fabric's QuadEmitter pipeline for capturing
 * pre-raster-lighting quads on NeoForge, where FRAPI does not exist.
 */
public final class VanillaModelQuads {
    private static final Direction[] DIRECTIONS = Direction.values();

    @FunctionalInterface
    public interface QuadSink {
        void accept(BakedQuad quad, ChunkSectionLayer layer);
    }

    private VanillaModelQuads() {
    }

    public static void emit(BlockStateModel model, BlockAndTintGetter level, BlockPos pos,
                            BlockState state, RandomSource random,
                            Predicate<Direction> cullTest, QuadSink out) {
        // Mirrors FabricBlockStateModel.emitQuads's forceOpaque transform (fast graphics → leaves SOLID).
        boolean forceOpaque = ModelBlockRenderer.forceOpaque(
                Minecraft.getInstance().options.cutoutLeaves().get(), state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        // NeoForge's extended collectParts(level, pos, state, random, parts) (BlockStateModelExtension)
        // is the hook NeoForge model mods — NeoContinuity's connected-texture models among them — emit
        // their custom (UV-remapped) quads through. The default implementation falls back to the vanilla
        // collectParts(random, parts), so vanilla models behave exactly as before; passing the world
        // context is what lets those mods resolve per-block appearance (neighbour blocks for CTM).
        model.collectParts(level, pos, state, random, parts);
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) {
                out.accept(quad, forceOpaque ? ChunkSectionLayer.SOLID : quad.materialInfo().layer());
            }
            for (Direction direction : DIRECTIONS) {
                if (cullTest.test(direction)) {
                    continue; // predicate true = this face is discarded
                }
                for (BakedQuad quad : part.getQuads(direction)) {
                    out.accept(quad, forceOpaque ? ChunkSectionLayer.SOLID : quad.materialInfo().layer());
                }
            }
        }
    }
}
