package dev.comfyfluffy.caustica.compat;

import dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * NeoForge equivalent of FRAPI's {@code SpriteFinder}: reverse-lookup the {@link TextureAtlasSprite}
 * whose atlas UV rectangle contains a given (u, v) point. This is the fallback that recovers the
 * correct sprite for quads whose UVs were <em>remapped</em> by a connected-texture mod (Continuity on
 * Fabric, NeoContinuity on NeoForge): such mods emit quads whose declared
 * {@code BakedQuad.MaterialInfo.sprite()} is the base tile, but whose vertex UVs point into a
 * different sprite's atlas region (the connected variant). {@code quad.materialInfo().sprite()} alone
 * then resolves the wrong material, so the capture paths consult this finder on the quad's UV
 * centroid — the same strategy as Fabric's {@code spriteFinder.find(quad)}.
 *
 * <p>The index is a quad tree over the unit UV square, mirroring FRAPI's {@code SpriteFinderImpl}
 * (Apache-2.0, FabricMC/fabric): each cell stores either a single sprite, a child node, or nothing;
 * a sprite spanning a cell boundary is inserted into every quadrant it overlaps, so a lookup descends
 * at most one path and the leaf it lands in is the sprite containing the point (or null when the
 * point falls in an uncovered gap — e.g. the padding between atlas regions). Sprites with bounds
 * outside [0, 1] are skipped (broken mod sprites; FRAPI logs and skips them too).
 *
 * <p>Instances are cached per atlas in {@link #CACHE}, keyed by the <em>identity</em> of the sprite
 * list instance. {@link TextureAtlas#upload} replaces the {@code sprites} list wholesale on every
 * resource reload (see {@link TextureAtlasAccessor#caustica$sprites()}), so a reload automatically
 * misses the cache and rebuilds the index — no explicit invalidation hook is needed, and the old
 * index (and its sprite references) becomes garbage.
 */
public final class AtlasSpriteFinder {
    private static final float EPS = 0.00001f;
    private static final Map<List<TextureAtlasSprite>, AtlasSpriteFinder> CACHE = new IdentityHashMap<>();

    private final Node root;

    private AtlasSpriteFinder(List<TextureAtlasSprite> sprites) {
        this.root = new Node(0.5f, 0.5f, 0.25f);
        for (TextureAtlasSprite sprite : sprites) {
            root.add(sprite);
        }
    }

    /**
     * The finder for an atlas, rebuilt automatically when the atlas's sprite list instance changes
     * (resource reload). Returns null when the atlas has no sprites yet (pre-first-upload).
     */
    @Nullable
    public static AtlasSpriteFinder of(TextureAtlas atlas) {
        List<TextureAtlasSprite> sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        if (sprites.isEmpty()) {
            return null;
        }
        AtlasSpriteFinder finder = CACHE.get(sprites);
        if (finder == null) {
            finder = new AtlasSpriteFinder(sprites);
            CACHE.put(sprites, finder);
        }
        return finder;
    }

    /**
     * The sprite whose atlas rectangle contains (u, v), or null when the point falls in a gap between
     * sprites (atlas padding / a UV outside every sprite's region). Mirrors FRAPI's
     * {@code SpriteFinder.find(float, float)}.
     */
    @Nullable
    public TextureAtlasSprite find(float u, float v) {
        return root.find(u, v);
    }

    /** One quad-tree cell over the unit UV square; see the class javadoc for the scheme. */
    private static final class Node {
        final float midU;
        final float midV;
        final float cellRadius;

        @Nullable
        Object lowLow;
        @Nullable
        Object lowHigh;
        @Nullable
        Object highLow;
        @Nullable
        Object highHigh;

        Node(float midU, float midV, float radius) {
            this.midU = midU;
            this.midV = midV;
            this.cellRadius = radius;
        }

        void add(TextureAtlasSprite sprite) {
            if (sprite.getU0() < -EPS || sprite.getU1() > 1f + EPS
                    || sprite.getV0() < -EPS || sprite.getV1() > 1f + EPS) {
                // Broken bounds (some mods have shipped these). Skipping keeps the tree finite — a
                // sprite outside [0,1] could otherwise recurse forever. FRAPI logs and skips as well.
                return;
            }
            boolean lowU = sprite.getU0() < midU - EPS;
            boolean highU = sprite.getU1() > midU + EPS;
            boolean lowV = sprite.getV0() < midV - EPS;
            boolean highV = sprite.getV1() > midV + EPS;
            if (lowU && lowV) {
                lowLow = addInner(sprite, lowLow, -1, -1);
            }
            if (lowU && highV) {
                lowHigh = addInner(sprite, lowHigh, -1, 1);
            }
            if (highU && lowV) {
                highLow = addInner(sprite, highLow, 1, -1);
            }
            if (highU && highV) {
                highHigh = addInner(sprite, highHigh, 1, 1);
            }
        }

        /** Insert into a quadrant slot, subdividing when the slot already holds a sprite. */
        private Object addInner(TextureAtlasSprite sprite, @Nullable Object quadrant, int uStep, int vStep) {
            if (quadrant == null) {
                return sprite;
            } else if (quadrant instanceof Node node) {
                node.add(sprite);
                return quadrant;
            } else {
                Node n = new Node(midU + cellRadius * uStep, midV + cellRadius * vStep, cellRadius * 0.5f);
                if (quadrant instanceof TextureAtlasSprite prevSprite) {
                    n.add(prevSprite);
                }
                n.add(sprite);
                return n;
            }
        }

        @Nullable
        TextureAtlasSprite find(float u, float v) {
            if (u < midU) {
                return v < midV ? findInner(lowLow, u, v) : findInner(lowHigh, u, v);
            } else {
                return v < midV ? findInner(highLow, u, v) : findInner(highHigh, u, v);
            }
        }

        @Nullable
        private TextureAtlasSprite findInner(@Nullable Object quadrant, float u, float v) {
            if (quadrant instanceof Node node) {
                return node.find(u, v);
            } else if (quadrant instanceof TextureAtlasSprite sprite) {
                return sprite;
            } else {
                return null; // empty quadrant — the point is in a gap between sprites
            }
        }
    }
}
