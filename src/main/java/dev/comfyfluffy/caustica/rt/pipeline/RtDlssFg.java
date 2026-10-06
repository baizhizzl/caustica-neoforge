package dev.comfyfluffy.caustica.rt.pipeline;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.DlssSettings;
import dev.comfyfluffy.caustica.ngx.NgxLibrary;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4fc;
import org.lwjgl.vulkan.VK10;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * DLSS Frame Generation (DLSSG) backend. Shares the NGX instance with DLSS-RR via {@link NgxRuntime};
 * owns the DLSSG feature and records interpolated frames for the multi-present loop. Runtime changes
 * reset temporal history on the next evaluation. Gated by the FG setting and hardware/driver support.
 */
public final class RtDlssFg {
    public static final RtDlssFg INSTANCE = new RtDlssFg();

    public static boolean enabled() {
        return CausticaConfig.Rt.Fg.ENABLED.value();
    }

    private NgxLibrary lib;
    private MemorySegment feature = MemorySegment.NULL;
    private boolean initialized;
    private boolean failed;
    private boolean probed;
    private boolean available;
    private int multiFrameCountMax;
    private boolean resetHistory = true;
    private int surfaceFrameLimit = DlssSettings.MAX_GENERATED_FRAMES;
    private int swapchainFrameLimit;

    private int featureWidth = -1;
    private int featureHeight = -1;
    private int featureRenderWidth = -1;
    private int featureRenderHeight = -1;
    private int featureBackbufferFormat = Integer.MIN_VALUE;

    private RtDlssFg() {
    }

    public boolean isAvailable() {
        return available && !failed && surfaceFrameLimit > 0;
    }

    /** Selectable generated-frame count, limited by both the driver and the surface's maximum image count. */
    public int multiFrameCountMax() {
        return Math.min(DlssSettings.generatedFrameLimit(multiFrameCountMax), surfaceFrameLimit);
    }

    /** Requested count for the next swapchain; an absent driver MFG capability means standard 2x. */
    public int plannedMultiFrameCount() {
        return surfaceFrameLimit == 0 ? 0
                : DlssSettings.generatedFrameCount(CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT.value(), multiFrameCountMax());
    }

    /** Only acquire as many extra images as the current swapchain can supply before Minecraft submits. */
    public int effectiveMultiFrameCount() {
        return Math.min(plannedMultiFrameCount(), swapchainFrameLimit);
    }

    public void setSurfaceFrameLimits(int minimumImages, int maximumImages) {
        surfaceFrameLimit = DlssSettings.surfaceGeneratedFrameLimit(minimumImages, maximumImages);
        swapchainFrameLimit = 0;
    }

    public void setSwapchainFrameLimit(int imageCount, int surfaceMinimum) {
        swapchainFrameLimit = DlssSettings.swapchainGeneratedFrameLimit(imageCount, surfaceMinimum);
        requestHistoryReset();
    }

    /** Discard history across enable/multiplier changes without releasing in-flight GPU resources. */
    public void requestHistoryReset() {
        resetHistory = true;
    }

    /**
     * Consume a pending history reset. Call once per real frame; the result applies to every generated frame
     * interpolated toward that real frame, since all of them would otherwise blend across the cut.
     */
    public boolean takeHistoryReset() {
        boolean reset = resetHistory;
        resetHistory = false;
        return reset;
    }

    public boolean isReady() {
        return initialized && !failed && !isNull(feature);
    }

    /** Whether a live feature already matches these dimensions/format (no recreate needed). */
    public boolean featureReadyFor(int width, int height, int renderWidth, int renderHeight, int backbufferFormat) {
        return isReady() && featureWidth == width && featureHeight == height
                && featureRenderWidth == renderWidth && featureRenderHeight == renderHeight
                && featureBackbufferFormat == backbufferFormat;
    }

    /**
     * Probe DLSSG availability once after NGX is up. Used by both the render tick and options screen;
     * needs no command buffer and does not create an FG feature.
     */
    public void probeAvailabilityOnce() {
        if (probed || failed) {
            return;
        }
        try {
            if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
                return;
            }
            NgxLibrary l = NgxRuntime.INSTANCE.acquire(device);
            if (l == null) {
                return;
            }
            probed = true;
            lib = l;
            if (!l.hasDlssg()) {
                CausticaMod.LOGGER.warn("DLSS-FG: loaded NGX shim has no DLSSG ABI");
                return;
            }
            available = l.dlssgAvailable();
            multiFrameCountMax = l.dlssgMultiFrameCountMax();
            CausticaMod.LOGGER.info("DLSS Frame Generation available: {} (multi-frame max {})", available, multiFrameCountMax);
            if (enabled() && isAvailable() && plannedMultiFrameCount() > swapchainFrameLimit) {
                // Startup may create the surface before the driver capability is known.
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft != null) {
                    minecraft.invalidateSurfaceConfiguration();
                }
            }
        } catch (Throwable t) {
            failed = true;
            available = false;
            CausticaMod.LOGGER.error("DLSS-FG capability query failed; frame generation unavailable", t);
        }
    }

    /**
     * Ensure a DLSSG feature exists for the given backbuffer/render size + native backbuffer format, creating
     * it into the supplied recording command buffer. Returns false (and disables itself) on failure.
     */
    public boolean ensureFeature(long cmd, int width, int height, int renderWidth, int renderHeight, int backbufferFormat) {
        if (!enabled() || failed) {
            return false;
        }
        if (!(((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device)) {
            return false;
        }
        try {
            if (lib == null) {
                lib = NgxRuntime.INSTANCE.acquire(device);
            }
            if (lib == null || !lib.hasDlssg()) {
                throw new IllegalStateException("NGX/DLSSG unavailable; cannot create FG feature");
            }
            if (!probed) {
                probeAvailabilityOnce();
            }
            if (!available) {
                throw new IllegalStateException("DLSS Frame Generation is not available on this system");
            }
            if (featureWidth != width || featureHeight != height
                    || featureRenderWidth != renderWidth || featureRenderHeight != renderHeight
                    || featureBackbufferFormat != backbufferFormat || isNull(feature)) {
                releaseFeature(device);
                feature = lib.createDlssg(cmd, width, height, renderWidth, renderHeight, backbufferFormat);
                if (isNull(feature)) {
                    throw new IllegalStateException("ngxshim_create_dlssg failed: last=0x"
                            + Integer.toHexString(lib.lastResult()));
                }
                featureWidth = width;
                featureHeight = height;
                featureRenderWidth = renderWidth;
                featureRenderHeight = renderHeight;
                featureBackbufferFormat = backbufferFormat;
                initialized = true;
                CausticaMod.LOGGER.info("DLSS-FG feature created: {}x{} (render {}x{}, backbuffer format {})",
                        width, height, renderWidth, renderHeight, backbufferFormat);
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS-FG setup failed; frame generation disabled", t);
            return false;
        }
    }

    /**
     * Record one DLSSG evaluation: generate interpolated frame {@code multiFrameIndex} of
     * {@code multiFrameCount} from the final {@code backbuffer} + HW {@code depth} + {@code mvec} into
     * {@code outputInterp}. {@code hudless} (the main scene before the combined UI overlay) and {@code ui}
     * (premultiplied combined overlay: RT world overlays, hand/screen effects and GUI) help the driver avoid
     * ghosting/smearing screen-fixed content in the generated frame; both are optional — pass 0 handles
     * (view/image/format) to skip, same as {@code outputReal}. Matrices are jitter-free (NGX left-multiply
     * layout); pass {@code null} to leave one out. {@code reset} must be the same for every
     * {@code multiFrameIndex} of one real frame (see {@link #takeHistoryReset()}). Returns false on failure.
     */
    public boolean evaluate(long cmd,
            long backbufferView, long backbufferImage, int backbufferFormat,
            long depthView, long depthImage, int depthFormat,
            long mvecView, long mvecImage, int mvecFormat,
            long hudlessView, long hudlessImage, int hudlessFormat,
            long uiView, long uiImage, int uiFormat,
            long outputInterpView, long outputInterpImage, int outputInterpFormat,
            int width, int height, int mvecDepthWidth, int mvecDepthHeight,
            int multiFrameCount, int multiFrameIndex, float mvScaleX, float mvScaleY,
            boolean depthInverted, boolean colorBuffersHDR, boolean cameraMotionIncluded, boolean reset,
            Matrix4fc cameraViewToClip, Matrix4fc clipToCameraView,
            Matrix4fc clipToPrevClip, Matrix4fc prevClipToClip) {
        if (!isReady()) {
            return false;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment viewToClip = matrixSegment(arena, cameraViewToClip);
            MemorySegment clipToView = matrixSegment(arena, clipToCameraView);
            MemorySegment clipToPrev = matrixSegment(arena, clipToPrevClip);
            MemorySegment prevToClip = matrixSegment(arena, prevClipToClip);
            int rc = lib.evaluateDlssg(cmd, feature,
                    backbufferView, backbufferImage, backbufferFormat,
                    depthView, depthImage, depthFormat,
                    mvecView, mvecImage, mvecFormat,
                    hudlessView, hudlessImage, hudlessFormat, // 0/0/0 = skip (no hudless capture this frame)
                    uiView, uiImage, uiFormat, // 0/0/0 = skip (UI overlay not active this frame)
                    outputInterpView, outputInterpImage, outputInterpFormat,
                    0L, 0L, 0, // outputReal (skip; MC presents the real frame itself)
                    width, height, mvecDepthWidth, mvecDepthHeight,
                    multiFrameCount, multiFrameIndex, mvScaleX, mvScaleY,
                    depthInverted ? 1 : 0, colorBuffersHDR ? 1 : 0, cameraMotionIncluded ? 1 : 0, reset ? 1 : 0,
                    viewToClip, clipToView, clipToPrev, prevToClip);
            if (NgxRuntime.ngxFailed(rc)) {
                throw new IllegalStateException("ngxshim_evaluate_dlssg failed: 0x" + Integer.toHexString(rc)
                        + " last=0x" + Integer.toHexString(lib.lastResult()));
            }
            return true;
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("DLSS-FG evaluate failed; frame generation disabled", t);
            return false;
        }
    }

    /** Format a JOML matrix into NGX left-multiply (row-major) layout, or NULL for a null matrix. */
    private static MemorySegment matrixSegment(Arena arena, Matrix4fc m) {
        if (m == null) {
            return MemorySegment.NULL;
        }
        MemorySegment seg = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 0, m.m00());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 1, m.m01());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 2, m.m02());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 3, m.m03());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 4, m.m10());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 5, m.m11());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 6, m.m12());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 7, m.m13());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 8, m.m20());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 9, m.m21());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 10, m.m22());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 11, m.m23());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 12, m.m30());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 13, m.m31());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 14, m.m32());
        seg.setAtIndex(ValueLayout.JAVA_FLOAT, 15, m.m33());
        return seg;
    }

    /** Release the FG feature. NGX itself is shut down by {@link NgxRuntime} at device teardown. */
    public void destroy() {
        if (((GpuDeviceAccessor) RenderSystem.getDevice()).caustica$getBackend() instanceof VulkanDevice device) {
            releaseFeature(device);
        }
        initialized = false;
        lib = null;
        failed = false;
        probed = false;
        available = false;
        multiFrameCountMax = 0;
        resetHistory = true;
    }

    private void releaseFeature(VulkanDevice device) {
        if (lib != null && !isNull(feature)) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null && ctx.device() == device) {
                ctx.waitIdle();
            } else {
                VK10.vkDeviceWaitIdle(device.vkDevice());
            }
            lib.release(feature);
        }
        feature = MemorySegment.NULL;
        featureWidth = -1;
        featureHeight = -1;
        featureRenderWidth = -1;
        featureRenderHeight = -1;
        featureBackbufferFormat = Integer.MIN_VALUE;
    }

    private static boolean isNull(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL);
    }
}
