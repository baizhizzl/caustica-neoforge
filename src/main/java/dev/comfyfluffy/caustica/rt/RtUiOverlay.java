package dev.comfyfluffy.caustica.rt;

import org.joml.Vector4f;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;

/**
 * World overlays, hands and ordinary GUI draws accumulate as premultiplied UI in a shared RGBA8 target.
 * GUI blends that depend on the destination resolve in draw order against the actual display images;
 * their temporary masks reuse the same color attachment without disturbing GUI depth.
 */
public final class RtUiOverlay {
    private static final Vector4f TRANSPARENT = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
    private static final Vector4f WHITE_MASK = new Vector4f(1.0f, 1.0f, 1.0f, 0.0f);

    private static TextureTarget overlay;
    private static final FrameState frameState = new FrameState();
    private static boolean compositeFailed;
    // The overlay is cleared once per frame, before the first thing that renders into it (RT world overlays,
    // the hand/screen-effects redirects in HDR mode, or the GUI). Reset at the start of GameRenderer.render
    // via beginFrame().
    private static boolean overlayClearedThisFrame;
    private static boolean guiDepthCleared;

    private RtUiOverlay() {
    }

    /** Content remains valid for frame generation after the display composite consumes it. */
    static final class FrameState {
        private boolean populated;
        private boolean compositePending;

        void beginFrame() {
            populated = false;
            compositePending = false;
        }

        void populate() {
            populated = true;
            compositePending = true;
        }

        boolean populated() {
            return populated;
        }

        boolean compositePending() {
            return compositePending;
        }

        void compositeSdr(boolean hdrPresentActive) {
            compositePending = populated && hdrPresentActive;
        }

        void consume() {
            compositePending = false;
        }
    }

    /**
     * Active when the Vulkan RT device is ready, the RT renderer is enabled, the composite pipeline has not
     * faulted, and the game has finished loading (the composite pipeline lazily compiles its shaders, which
     * are not available during the loading screen).
     */
    public static boolean enabled() {
        return RtDeviceBringup.isRtReady()
                && RtComposite.enabled()
                && !compositeFailed
                && Minecraft.getInstance().isGameLoadFinished();
    }

    public static void resetFailureLatch() {
        compositeFailed = false;
    }

    /** Whether the overlay holds this frame's UI, including after display compositing. */
    public static boolean populatedThisFrame() {
        return frameState.populated() && overlay != null;
    }

    /** Whether the HDR display composite still needs to consume this frame's overlay. */
    public static boolean pendingHdrComposite() {
        return frameState.compositePending() && overlay != null;
    }

    /** Mark the overlay consumed by the HDR present composite (so it isn't reused next frame). */
    public static void markConsumed() {
        frameState.consume();
    }

    public static int overlayWidth() {
        return overlay != null ? overlay.width : 0;
    }

    public static int overlayHeight() {
        return overlay != null ? overlay.height : 0;
    }

    /** The overlay color image view, for the HDR composite compute pass (0 if not available). */
    public static long overlayColorView() {
        if (overlay == null || overlay.getColorTextureView() == null) {
            return 0L;
        }
        if (overlay.getColorTextureView() instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView v) {
            return v.vkImageView();
        }
        return 0L;
    }

    /** The overlay color image (0 if not available) — pairs with {@link #overlayColorView()} for callers
     * (e.g. the DLSSG "ui" optional resource) that need both the view and the raw image. */
    public static long overlayColorImage() {
        if (overlay == null || overlay.getColorTexture() == null) {
            return 0L;
        }
        if (overlay.getColorTexture() instanceof com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture t) {
            return t.vkImage();
        }
        return 0L;
    }

    /**
     * Prepare the overlay (sized to {@code main}, cleared transparent with depth cleared to 0.0) and return
     * it so {@code GuiRenderer.draw} renders the GUI into it instead of the main target. Called from the
     * {@code GuiRendererMixin} redirect on the render thread.
     */
    public static RenderTarget beginAndRedirect(RenderTarget main) {
        return prepare(main);
    }

    /**
     * Prepare the shared transparent overlay for non-GUI contributors such as RT world overlays. Call before
     * the GUI redirect so the GUI draws over those contributors in the same image.
     */
    public static RenderTarget beginCompositeLayer(RenderTarget main) {
        return prepare(main);
    }

    /** Reset the per-frame clear and population latches. Called at the start of {@code GameRenderer.render}. */
    public static void beginFrame() {
        overlayClearedThisFrame = false;
        guiDepthCleared = false;
        frameState.beginFrame();
        RtGuiBlend.beginFrame();
    }

    /**
     * Ensure the overlay exists, sized to {@code main}, and cleared (transparent + depth 0.0) exactly once
     * this frame, then mark it used. Both the hand redirect and the GUI redirect funnel through here so the
     * overlay is cleared before the hand (which renders first) and not wiped before the GUI.
     */
    private static TextureTarget prepare(RenderTarget main) {
        TextureTarget target = ensureSized(main);
        if (!overlayClearedThisFrame) {
            CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
            if (target.hasDepth() && target.getDepthTexture() != null) {
                enc.clearColorAndDepthTextures(target.getColorTexture(), TRANSPARENT, target.getDepthTexture(), 0.0);
            } else {
                enc.clearColorTexture(target.getColorTexture(), TRANSPARENT);
            }
            overlayClearedThisFrame = true;
        }
        frameState.populate();
        return target;
    }

    public static boolean owns(RenderTarget target) {
        return target != null && target == overlay;
    }

    public static void beginGuiRange() {
        if (!guiDepthCleared) {
            // GameRenderer has cleared main's depth for GUI. The overlay may still hold hand depth.
            RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(overlay.getDepthTexture(), 0.0);
            guiDepthCleared = true;
        }
    }

    public static void noteGuiDraws() {
        frameState.populate();
    }

    public static void beginGuiEffect(RtGuiBlend.Mode mode) {
        RenderTarget main = mainTarget();
        RtComposite.INSTANCE.captureFgHudless(main);
        RtGuiBlend.beginSegments(overlay);
        flushOrdinaryLayer(main);
        clearGuiColor(mode == RtGuiBlend.Mode.MULTIPLY ? WHITE_MASK : TRANSPARENT);
    }

    public static void finishGuiEffect(RtGuiBlend.Mode mode) {
        commitLayer(mainTarget(), mode, true);
        // The next ordinary layer and the final FG alpha hint both assume a transparent seed.
        clearGuiColor(TRANSPARENT);
    }

    public static void flushSegmentedGuiBeforeBlur() {
        if (RtGuiBlend.segmented() && flushOrdinaryLayer(mainTarget())) {
            clearGuiColor(TRANSPARENT);
        }
    }

    private static boolean flushOrdinaryLayer(RenderTarget main) {
        if (!frameState.compositePending()) {
            return false;
        }
        commitLayer(main, RtGuiBlend.Mode.OVER, true);
        return true;
    }

    private static void clearGuiColor(Vector4f color) {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(overlay.getColorTexture(), color);
    }

    private static RenderTarget mainTarget() {
        return Minecraft.getInstance().gameRenderer.mainRenderTarget();
    }

    private static void commitLayer(RenderTarget main, RtGuiBlend.Mode mode, boolean early) {
        if (RtGuiBlend.segmented()) {
            // A layer can span several draw ranges; accumulate its completed coverage only once.
            RtGuiBlend.mergeCoverage(overlay, mode);
        }
        if (main != null && main.getColorTextureView() != null) {
            RtGuiBlend.compositeSdr(main, overlay, mode);
        }
        if (early) {
            RtComposite.INSTANCE.compositeHdrGuiLayer(mode);
            frameState.consume();
        } else {
            frameState.compositeSdr(RtComposite.INSTANCE.isHdrPresentActive());
        }
    }

    /**
     * Composite the overlay over the real main target. Called once per frame from {@code GameRendererMixin}
     * after {@code GuiRenderer.render} returns. A compile/render failure latches the overlay off rather than crashing the frame.
     */
    public static void compositeIfUsed() {
        if (overlay == null || (!frameState.compositePending() && !RtGuiBlend.segmented())) {
            frameState.consume();
            return;
        }
        RenderTarget main = mainTarget();
        if (main == null || main.getColorTextureView() == null) {
            return;
        }
        try {
            if (frameState.compositePending()) {
                commitLayer(main, RtGuiBlend.Mode.OVER, false);
            }
            if (RtGuiBlend.segmented()) {
                RtGuiBlend.resolveSdrUi(main, overlay);
            }
        } catch (Throwable t) {
            compositeFailed = true;
            org.slf4j.LoggerFactory.getLogger("Caustica")
                    .error("UI overlay composite failed; disabling overlay", t);
        }
    }

    private static TextureTarget ensureSized(RenderTarget main) {
        if (overlay == null) {
            overlay = new TextureTarget("caustica UI overlay", main.width, main.height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        } else if (overlay.width != main.width || overlay.height != main.height) {
            overlay.resize(main.width, main.height);
        }
        return overlay;
    }

    public static void destroy() {
        beginFrame();
        RtGuiBlend.destroy();
        if (overlay != null) {
            overlay.destroyBuffers();
            overlay = null;
        }
    }
}
