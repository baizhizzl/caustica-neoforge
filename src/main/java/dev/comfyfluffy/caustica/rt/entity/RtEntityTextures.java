package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.mixin.RenderSetupAccessor;
import dev.comfyfluffy.caustica.mixin.RenderTypeAccessor;
import dev.comfyfluffy.caustica.rt.material.RtMaterialRegistry;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Resolves the texture backing an entity {@link RenderType} to a Vulkan image-view handle, for the
 * bindless entity-texture array. Entities use per-type texture files (zombie.png, …), not the block
 * atlas, so each distinct texture maps to its own bindless slot. Slots are keyed by the live
 * image-view handle paired with its owning {@link GpuTextureView}: some render types are rebuilt every
 * frame with a fresh identity (the charged-creeper energy swirl scrolls its texture transform), while
 * dynamic textures (skins, maps, mod overlays) can close and recreate views whose Vulkan handle may be
 * reused by the driver. Tracking the {@link GpuTextureView} detects closed views and recycles their
 * slots after in-flight frames finish.
 *
 * <p>The view is obtained through the <b>public</b> {@code RenderType.prepare()} → {@link
 * PreparedRenderType#textures()} API (a list of {@code Texture(name, GpuTextureView, sampler)}): the
 * primary sampler is {@code "Sampler0"} ({@code "Sampler1"}/{@code "Sampler2"} are the overlay/lightmap
 * the prepared list prepends). Resolution is cached per {@code RenderType} (they are stable singletons),
 * so the prepare() cost is paid once per distinct texture.
 */
public final class RtEntityTextures {
    /** Bindless array capacity (slot 0 reserved as a fallback texture). {@code -Dcaustica.rt.maxEntityTextures}. */
    public static int maxTextures() {
        return CausticaConfig.Rt.EntityTextures.MAX_TEXTURES.value();
    }

    /** Enable pack-compiled entity materials; disabled entities use the canonical neutral header. */
    public static boolean entityPbr() {
        return CausticaConfig.Rt.EntityTextures.PBR.value();
    }

    public static final RtEntityTextures INSTANCE = new RtEntityTextures();

    // Wait more than KEEP_FRAMES (3) before reusing a slot freed by a closed dynamic texture so
    // update-after-bind descriptor writes never overwrite a slot referenced by an in-flight frame.
    private static final long RECYCLE_DELAY_FRAMES = 4L;

    // RenderType identity → resolved primary GpuTextureView. WEAK: some render types are rebuilt every
    // frame with a fresh identity (e.g. the charged-creeper energy-swirl layer, whose scrolling texture
    // transform makes RenderTypes.energySwirl() allocate a new RenderType each frame). Caching the
    // GpuTextureView rather than its raw VkImageView address lets us detect closed dynamic views (skins,
    // maps, dynamic overlays) via GpuTextureView.isClosed() and re-resolve instead of sampling destroyed handles.
    private final Map<RenderType, GpuTextureView> viewCache = new WeakHashMap<>();
    private final Set<RenderType> untexturedCache = Collections.newSetFromMap(new WeakHashMap<>());
    private final Map<RenderType, Identifier> locationCache = new WeakHashMap<>();
    // Live VkImageView handle → owning slot entry. Checking owner.isClosed() detects destroyed views even
    // when the driver reuses the same VkImageView handle address for a newly created dynamic texture.
    private final Map<Long, SlotEntry> handleSlots = new HashMap<>();
    private final ArrayDeque<RetiredSlot> retiredSlots = new ArrayDeque<>();
    private final IntArrayList freeSlots = new IntArrayList();
    // Atlas-location → resolved GpuTextureView, checked for isClosed() so recreated dynamic textures
    // obtain a fresh slot instead of keeping a stale VkImageView. Block atlas always maps to slot 0.
    private final Map<Identifier, GpuTextureView> atlasViewCache = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>(); // albedo slots awaiting descriptor upload
    // Descriptor array capacity of the currently alive world pipeline. A higher config value applies after
    // reset/recreate; a lower value stops allocating new slots immediately without invalidating old ones.
    private int capacity = maxTextures();
    private int nextSlot = 1;
    private long frameTick;
    private boolean loggedFailure;
    private boolean loggedMaterialFailure;
    // 1x1 solid-white DynamicTexture for untextured geometry (leash/line ribbons); see whiteSlot().
    private static final Identifier WHITE_LOCATION = Identifier.fromNamespaceAndPath("caustica", "rt_white");
    private boolean whiteRegistered;

    // Cached RenderSetup.TextureBinding#location() (the class is package-private, the method public).
    private Method locationMethod;

    private record SlotEntry(int slot, GpuTextureView owner, long handle) {
    }

    private record RetiredSlot(int slot, long readyFrame) {
    }

    private record Pending(int slot, GpuTextureView owner, long view) {
    }

    private RtEntityTextures() {
    }

    /**
     * The bindless slot for {@code renderType}'s texture. Keyed by the live image view, not the
     * RenderType, so per-frame-allocated render types sharing one texture reuse a single slot. Returns 0
     * (the fallback slot = block atlas) if the texture can't be resolved or the array is full.
     */
    public int slotFor(RenderType renderType) {
        if (renderType == null) {
            return 0;
        }
        return slotForTextureView(resolveTextureView(renderType));
    }

    /** Canonical material ID for a full entity texture, or the neutral runtime-texture fallback. */
    public int materialIdFor(RenderType renderType, boolean stochasticAlpha) {
        if (!entityPbr()) return RtMaterialRegistry.INSTANCE.entityFallbackId(stochasticAlpha);
        return RtMaterialRegistry.INSTANCE.resolveEntityTexture(textureLocation(renderType), stochasticAlpha);
    }

    /**
     * The bindless slot for a texture atlas (block/item atlas used by item + block-model quads), cached
     * per atlas location while the underlying {@link GpuTextureView} remains open. The block atlas maps to
     * slot 0; other atlases (the item atlas) get their own slot. Returns 0 (fallback) if unresolvable or full.
     */
    public int slotForAtlas(Identifier atlasLocation) {
        if (atlasLocation == null || TextureAtlas.LOCATION_BLOCKS.equals(atlasLocation)) {
            return 0;
        }
        GpuTextureView cached = atlasViewCache.get(atlasLocation);
        if (cached == null || cached.isClosed()) {
            cached = null;
            try {
                GpuTextureView v = Minecraft.getInstance().getTextureManager().getTexture(atlasLocation).getTextureView();
                if (v != null && !v.isClosed()) {
                    atlasViewCache.put(atlasLocation, v);
                    cached = v;
                } else {
                    atlasViewCache.remove(atlasLocation);
                }
            } catch (Throwable t) {
                atlasViewCache.remove(atlasLocation);
                if (!loggedFailure) {
                    loggedFailure = true;
                    CausticaMod.LOGGER.warn("RT atlas texture resolution failed for {}", atlasLocation, t);
                }
            }
        }
        return slotForTextureView(cached);
    }

    /**
     * Bindless slot of a solid-white 1x1 texture, for untextured geometry (leashes, custom line
     * ribbons) whose colour comes entirely from the per-prim tint. Slot 0 (the block atlas) is NOT a
     * substitute: the hit shader multiplies albedo by the sampled texel, and UV (0,0) lands on
     * whatever sprite is stitched at the atlas origin. The texture is registered with the
     * TextureManager on first use and then resolved/cached by the ordinary atlas-slot path, so it
     * survives {@link #reset} like any other atlas. Falls back to slot 0 if registration fails.
     */
    public int whiteSlot() {
        GpuTextureView existing = atlasViewCache.get(WHITE_LOCATION);
        if (!whiteRegistered || (existing != null && existing.isClosed())) {
            whiteRegistered = true;
            atlasViewCache.remove(WHITE_LOCATION);
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixel(0, 0, 0xFFFFFFFF);
            Minecraft.getInstance().getTextureManager()
                    .register(WHITE_LOCATION, new DynamicTexture(() -> "caustica RT white", image));
        }
        return slotForAtlas(WHITE_LOCATION);
    }

    /**
     * Map a live {@link GpuTextureView} to a bindless slot, allocating or reusing one on first sight
     * (queued for upload via {@link #uploadPending}). Returns 0 (fallback) when the view is unresolved,
     * closed, or the array is full.
     */
    private int slotForTextureView(GpuTextureView textureView) {
        if (textureView == null || textureView.isClosed()) {
            return 0;
        }
        long view = vkImageView(textureView);
        if (view == 0L) {
            return 0;
        }
        SlotEntry existing = handleSlots.get(view);
        if (existing != null) {
            if (!existing.owner().isClosed()) {
                return existing.slot();
            }
            // The previous GpuTextureView closed and the driver reused its VkImageView handle address.
            // Retire the stale slot and bind the new view freshly so Vulkan updates the descriptor.
            handleSlots.remove(view);
            retireSlot(existing.slot());
        }
        int slot = allocateSlot();
        if (slot <= 0) {
            return 0;
        }
        handleSlots.put(view, new SlotEntry(slot, textureView, view));
        pending.add(new Pending(slot, textureView, view));
        return slot;
    }

    private int allocateSlot() {
        int limit = slotLimit();
        while (!freeSlots.isEmpty()) {
            int recycled = freeSlots.removeInt(freeSlots.size() - 1);
            if (recycled > 0 && recycled < limit) {
                return recycled;
            }
        }
        if (nextSlot >= limit) {
            return 0;
        }
        return nextSlot++;
    }

    private void retireSlot(int slot) {
        if (slot > 0) {
            retiredSlots.addLast(new RetiredSlot(slot, frameTick + RECYCLE_DELAY_FRAMES));
        }
    }

    private void sweepClosedSlots() {
        if (!handleSlots.isEmpty()) {
            for (Iterator<Map.Entry<Long, SlotEntry>> it = handleSlots.entrySet().iterator(); it.hasNext(); ) {
                SlotEntry entry = it.next().getValue();
                if (entry.owner().isClosed()) {
                    it.remove();
                    retireSlot(entry.slot());
                }
            }
        }
        while (!retiredSlots.isEmpty() && retiredSlots.peekFirst().readyFrame() <= frameTick) {
            freeSlots.add(retiredSlots.removeFirst().slot());
        }
    }

    /** Sweep closed views, recycle matured slots, and upload pending bindless descriptors before tracing. */
    public void uploadPending(RtPipeline pipeline, long sampler) {
        frameTick++;
        sweepClosedSlots();
        if (pending.isEmpty()) {
            return;
        }
        for (Pending p : pending) {
            if (p.owner().isClosed()) {
                // Closed before upload: never write a destroyed VkImageView into the descriptor set.
                continue;
            }
            SlotEntry current = handleSlots.get(p.view());
            if (current != null && current.slot() == p.slot() && current.owner() == p.owner()) {
                pipeline.setEntityAlbedoTexture(p.slot(), p.view(), sampler);
            }
        }
        pending.clear();
    }

    /** Drop the registry (call when the world pipeline / bindless set is recreated, or textures reload). */
    public void reset() {
        reset(maxTextures());
    }

    /** Drop the registry for a pipeline whose bindless descriptor arrays have this capacity. */
    public void reset(int descriptorCapacity) {
        capacity = Math.max(1, descriptorCapacity);
        viewCache.clear();
        untexturedCache.clear();
        locationCache.clear();
        handleSlots.clear();
        retiredSlots.clear();
        freeSlots.clear();
        atlasViewCache.clear();
        pending.clear();
        nextSlot = 1;
        frameTick = 0L;
    }

    private int slotLimit() {
        return Math.min(capacity, maxTextures());
    }

    /** Recover the resource Identifier of {@code renderType}'s primary texture (Sampler0), or null. The
     *  {@code RenderSetup.TextureBinding} class is package-private, so {@code location()} is reflective. */
    private Identifier textureLocation(RenderType renderType) {
        if (renderType == null) return null;
        if (locationCache.containsKey(renderType)) return locationCache.get(renderType);
        Identifier result = null;
        try {
            // RenderSetup is final, so the accessor cast must go through Object (the interface is only
            // mixed in at runtime); RenderType is non-final so its cast is fine directly.
            Object setup = ((RenderTypeAccessor) renderType).caustica$state();
            Map<String, ?> textures = ((RenderSetupAccessor) setup).caustica$textures();
            Object binding = textures.get("Sampler0");
            if (binding == null) {
                locationCache.put(renderType, null);
                return null;
            }
            if (locationMethod == null) {
                locationMethod = binding.getClass().getMethod("location");
                locationMethod.setAccessible(true);
            }
            result = (Identifier) locationMethod.invoke(binding);
        } catch (Throwable t) {
            warnMaterialOnce("RT entity texture Identifier resolution failed for " + renderType, t);
        }
        locationCache.put(renderType, result);
        return result;
    }

    private void warnMaterialOnce(String msg, Throwable t) {
        if (!loggedMaterialFailure) {
            loggedMaterialFailure = true;
            CausticaMod.LOGGER.warn(msg, t);
        }
    }

    /** The Vulkan image-view handle of {@code renderType}'s primary texture, or 0 if it can't be resolved. */
    public long resolveView(RenderType renderType) {
        return vkImageView(resolveTextureView(renderType));
    }

    private GpuTextureView resolveTextureView(RenderType renderType) {
        if (renderType == null) {
            return null;
        }
        GpuTextureView cached = viewCache.get(renderType);
        if (cached != null) {
            if (!cached.isClosed()) {
                return cached;
            }
            viewCache.remove(renderType);
        } else if (untexturedCache.contains(renderType)) {
            return null;
        }
        try {
            PreparedRenderType prepared = renderType.prepare();
            GpuTextureView chosen = null;
            GpuTextureView firstNonAux = null;
            for (PreparedRenderType.Texture t : prepared.textures()) {
                String name = t.name();
                if ("Sampler0".equals(name)) {
                    chosen = t.textureView();
                    break;
                }
                if (firstNonAux == null && !"Sampler1".equals(name) && !"Sampler2".equals(name)) {
                    firstNonAux = t.textureView();
                }
            }
            if (chosen == null) {
                chosen = firstNonAux;
            }
            if (chosen != null && !chosen.isClosed()) {
                viewCache.put(renderType, chosen);
                return chosen;
            }
            if (chosen == null) {
                untexturedCache.add(renderType);
            }
        } catch (Throwable t) {
            if (!loggedFailure) {
                loggedFailure = true;
                CausticaMod.LOGGER.warn("RT entity texture resolution failed for {}", renderType, t);
            }
        }
        return null;
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        return 0L;
    }
}
