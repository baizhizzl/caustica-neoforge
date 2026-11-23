package dev.comfyfluffy.caustica.rt;

import dev.comfyfluffy.caustica.CausticaConfig;
import dev.comfyfluffy.caustica.CausticaMod;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.NVLowLatency2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkLatencySleepInfoNV;
import org.lwjgl.vulkan.VkLatencySleepModeInfoNV;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreWaitInfo;
import org.lwjgl.vulkan.VkSetLatencyMarkerInfoNV;

import java.nio.LongBuffer;

/**
 * NVIDIA Reflex per-frame sleep/pacing and latency markers, built on the timeline semaphore signaled
 * by {@code vkLatencySleepNV}. {@link RtDeviceBringup} enables the device support and
 * {@code VulkanGpuSurfaceMixin} configures each swapchain for latency mode.
 *
 * <p>{@code vkLatencySleepNV} does not itself block — per the extension, it schedules the driver to signal
 * a semaphore value once the paced frame-start time is reached, and the caller is expected to wait on that
 * value itself. That host-side {@code vkWaitSemaphores} in {@link #sleep} IS the actual pacing mechanism;
 * it must run once near the top of the frame, before input sampling/simulation (see
 * {@code MinecraftMixin#caustica$reflexSleep}).
 *
 * <p>Sleep mode + the timeline semaphore are scoped to a specific swapchain object ({@link #applySleepMode}
 * must be re-called whenever the swapchain is (re)created, e.g. on resize) — tracked here by comparing the
 * swapchain handle, so a stale call after a swapchain recreation is a safe no-op until re-applied.
 *
 * <p>{@code presentID} correlates markers with a specific present call. A single strictly-increasing sequence
 * feeds both the per-frame markers and {@code VkPresentIdKHR}: {@link #sleep} opens a simulation frame id
 * ({@link #currentSimFrameId()}), {@link #beginRenderSubmitId()} reuses that unclaimed id (or allocates a fresh
 * one if an out-of-tick present already claimed it), and {@link #consumePresentId()} claims that id once for
 * {@code vkQueuePresentKHR} + PRESENT_START/END markers (or allocates a fresh one for presents outside the
 * normal tick loop, such as {@code Minecraft.setScreenAndShow}). This keeps every normal frame's
 * SIMULATION/RENDERSUBMIT/PRESENT markers correlated while guaranteeing {@code VUID-VkPresentIdKHR-presentIds-04999}
 * ("each presentIds entry must be greater than all previously submitted present ids").
 */
public final class RtReflex {
    public static final RtReflex INSTANCE = new RtReflex();

    /** {@code VkLatencyMarkerNV} values (VK_NV_low_latency2); mirrored here to avoid importing the enum at call sites. */
    public static final int MARKER_SIMULATION_START = NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_START_NV;
    public static final int MARKER_SIMULATION_END = NVLowLatency2.VK_LATENCY_MARKER_SIMULATION_END_NV;
    public static final int MARKER_RENDERSUBMIT_START = NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_START_NV;
    public static final int MARKER_RENDERSUBMIT_END = NVLowLatency2.VK_LATENCY_MARKER_RENDERSUBMIT_END_NV;
    public static final int MARKER_PRESENT_START = NVLowLatency2.VK_LATENCY_MARKER_PRESENT_START_NV;
    public static final int MARKER_PRESENT_END = NVLowLatency2.VK_LATENCY_MARKER_PRESENT_END_NV;

    private static final long SLEEP_WAIT_TIMEOUT_NS = 200_000_000L; // 200ms: generous, never expected to hit

    private long timelineSemaphore;
    private long sleepCounter;
    private long nextPresentId;
    private long activeFrameId;
    private boolean activeFrameUnclaimed;
    private long sleepModeSwapchain; // swapchain applySleepMode was last successfully called for; 0 = none yet
    private boolean lastBoost;
    private int lastMinIntervalUs;
    private boolean failed;

    private RtReflex() {
    }

    public static boolean enabled() {
        return CausticaConfig.Rt.Reflex.ENABLED.value() && RtDeviceBringup.reflexEnabled();
    }

    /** Current simulation frame's marker id (opened by {@link #sleep}) — tags SIMULATION markers. */
    public long currentSimFrameId() {
        return activeFrameId;
    }

    /**
     * Return the active unclaimed frame id for {@code RENDERSUBMIT_START}, or allocate a fresh strictly-increasing
     * id when no unclaimed simulation frame id is open (e.g., an out-of-tick render).
     */
    public long beginRenderSubmitId() {
        if (!activeFrameUnclaimed) {
            activeFrameId = ++nextPresentId;
            activeFrameUnclaimed = true;
        }
        return activeFrameId;
    }

    /**
     * Claim the active frame id for the {@code vkQueuePresentKHR} about to execute, or allocate a fresh
     * strictly-increasing id if no unclaimed frame is pending. Call exactly once per real {@code vkQueuePresentKHR}.
     */
    public long consumePresentId() {
        if (activeFrameUnclaimed) {
            activeFrameUnclaimed = false;
            return activeFrameId;
        }
        activeFrameId = ++nextPresentId;
        return activeFrameId;
    }

    /** The swapchain {@link #applySleepMode} last successfully applied to, or 0 if none (not yet ready). */
    public long appliedSwapchain() {
        return sleepModeSwapchain;
    }

    /** Clear swapchain-scoped state before the underlying swapchain is destroyed or recreated. */
    public void invalidateSwapchain() {
        sleepModeSwapchain = 0L;
    }

    /**
     * Apply (or refresh) the sleep-mode config on {@code swapchain}. Cheap no-op when already applied for
     * this exact swapchain handle with unchanged boost/interval config; safe to call every frame from the
     * swapchain-configure path. Must be called at least once per swapchain (creation, and again whenever the
     * swapchain is recreated, e.g. resize) before {@link #sleep}/{@link #marker} do anything.
     */
    public void applySleepMode(VkDevice device, long swapchain) {
        if (swapchain == 0L) {
            sleepModeSwapchain = 0L;
            return;
        }
        if (!enabled() || failed) {
            return;
        }
        boolean boost = CausticaConfig.Rt.Reflex.LOW_LATENCY_BOOST.value();
        int minIntervalUs = CausticaConfig.Rt.Reflex.MINIMUM_INTERVAL_US.value();
        if (swapchain == sleepModeSwapchain && boost == lastBoost && minIntervalUs == lastMinIntervalUs) {
            return;
        }
        try {
            ensureSemaphore(device);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkLatencySleepModeInfoNV info = VkLatencySleepModeInfoNV.calloc(stack).sType$Default()
                        .lowLatencyMode(true).lowLatencyBoost(boost).minimumIntervalUs(minIntervalUs);
                int r = NVLowLatency2.vkSetLatencySleepModeNV(device, swapchain, info);
                if (r != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkSetLatencySleepModeNV failed: " + r);
                }
            }
            sleepModeSwapchain = swapchain;
            lastBoost = boost;
            lastMinIntervalUs = minIntervalUs;
            CausticaMod.LOGGER.info("Reflex: sleep mode applied (boost={}, minIntervalUs={})", boost, minIntervalUs);
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("Reflex: applySleepMode failed; Reflex disabled for session", t);
        }
    }

    /**
     * Per-frame pacing: open a fresh frame id, ask the driver to signal the timeline semaphore at the
     * paced frame-start time, then block this (the game) thread until it does. Call once, near the top of
     * the frame, before input sampling/simulation. No-op (returns immediately) unless {@link #applySleepMode}
     * has already been applied for this exact {@code swapchain}.
     */
    public void sleep(VkDevice device, long swapchain) {
        if (!enabled() || failed || swapchain == 0L || swapchain != sleepModeSwapchain || timelineSemaphore == 0L) {
            return;
        }
        sleepCounter++;
        activeFrameId = ++nextPresentId;
        activeFrameUnclaimed = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkLatencySleepInfoNV sleepInfo = VkLatencySleepInfoNV.calloc(stack).sType$Default()
                    .signalSemaphore(timelineSemaphore).value(sleepCounter);
            int r = NVLowLatency2.vkLatencySleepNV(device, swapchain, sleepInfo);
            if (r != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkLatencySleepNV failed: " + r);
            }
            VkSemaphoreWaitInfo waitInfo = VkSemaphoreWaitInfo.calloc(stack).sType$Default()
                    .semaphoreCount(1)
                    .pSemaphores(stack.longs(timelineSemaphore)).pValues(stack.longs(sleepCounter));
            r = VK12.vkWaitSemaphores(device, waitInfo, SLEEP_WAIT_TIMEOUT_NS);
            if (r == VK10.VK_ERROR_DEVICE_LOST) {
                throw new IllegalStateException("vkWaitSemaphores returned VK_ERROR_DEVICE_LOST");
            }
            if (r != VK10.VK_SUCCESS) {
                // A single missed wait just skips pacing for this frame — not fatal, worth knowing about if
                // it happens constantly (would mean the driver never signals, which needs investigating).
                CausticaMod.LOGGER.warn("Reflex: vkWaitSemaphores({}) returned {}; pacing skipped this frame",
                        sleepCounter, r);
            }
        } catch (Throwable t) {
            failed = true;
            CausticaMod.LOGGER.error("Reflex: sleep failed; Reflex disabled for session", t);
        }
    }

    /**
     * Set a latency marker tagged with {@code id} (pass {@link #currentSimFrameId()} for SIMULATION markers,
     * {@link #beginRenderSubmitId()} for RENDERSUBMIT_START, and {@link #consumePresentId()} for
     * RENDERSUBMIT_END / PRESENT markers). Markers are pure diagnostics for the driver's latency analysis
     * (incl. NVIDIA overlays) — a failure here never disables pacing.
     */
    public void marker(VkDevice device, long swapchain, int marker, long id) {
        if (!enabled() || failed || swapchain == 0L || swapchain != sleepModeSwapchain || id <= 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSetLatencyMarkerInfoNV info = VkSetLatencyMarkerInfoNV.calloc(stack).sType$Default()
                    .presentID(id).marker(marker);
            NVLowLatency2.vkSetLatencyMarkerNV(device, swapchain, info);
        } catch (Throwable t) {
            CausticaMod.LOGGER.warn("Reflex: vkSetLatencyMarkerNV({}) threw; ignoring", marker, t);
        }
    }

    private void ensureSemaphore(VkDevice device) {
        if (timelineSemaphore != 0L) {
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreTypeCreateInfo typeInfo = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK12.VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0L);
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(typeInfo);
            LongBuffer p = stack.mallocLong(1);
            if (VK10.vkCreateSemaphore(device, sci, null, p) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSemaphore(reflex timeline) failed");
            }
            timelineSemaphore = p.get(0);
        }
    }

    /** Device teardown: wait idle before destroying the timeline semaphore and reset all session state. */
    public void destroy(VkDevice device) {
        if (timelineSemaphore != 0L && device != null) {
            VK10.vkDeviceWaitIdle(device);
            VK10.vkDestroySemaphore(device, timelineSemaphore, null);
            timelineSemaphore = 0L;
        }
        sleepModeSwapchain = 0L;
        sleepCounter = 0L;
        nextPresentId = 0L;
        activeFrameId = 0L;
        activeFrameUnclaimed = false;
        failed = false;
    }
}
