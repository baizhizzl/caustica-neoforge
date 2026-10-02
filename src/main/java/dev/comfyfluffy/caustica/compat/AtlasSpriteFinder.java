package dev.comfyfluffy.caustica.compat;

import dev.comfyfluffy.caustica.mixin.TextureAtlasAccessor;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Immutable reverse UV lookup for quads whose declared sprite differs from their atlas UVs. */
public final class AtlasSpriteFinder {
    private record Cached(List<TextureAtlasSprite> sprites, AtlasSpriteFinder finder) {}
    private static final Map<TextureAtlas, Cached> CACHE = new IdentityHashMap<>();
    private final UvRectIndex<TextureAtlasSprite> index;

    private AtlasSpriteFinder(List<TextureAtlasSprite> sprites) {
        var entries = new ArrayList<UvRectIndex.Entry<TextureAtlasSprite>>(sprites.size());
        for (TextureAtlasSprite sprite : sprites) {
            entries.add(new UvRectIndex.Entry<>(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1(), sprite));
        }
        index = new UvRectIndex<>(entries);
    }

    /** Cache access is serialized; published indices are read-only and safe for meshing workers. */
    @Nullable
    public static synchronized AtlasSpriteFinder of(TextureAtlas atlas) {
        List<TextureAtlasSprite> sprites = ((TextureAtlasAccessor) atlas).caustica$sprites();
        if (sprites.isEmpty()) { CACHE.remove(atlas); return null; }
        Cached cached = CACHE.get(atlas);
        if (cached == null || cached.sprites() != sprites) {
            cached = new Cached(sprites, new AtlasSpriteFinder(sprites));
            CACHE.put(atlas, cached);
        }
        return cached.finder();
    }

    /** Called after in-flight meshing is drained, before resource replacement or renderer shutdown. */
    public static synchronized void clearCache() { CACHE.clear(); }

    @Nullable
    public TextureAtlasSprite find(float u, float v) { return index.find(u, v); }
}
