package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.compat.AtlasSpriteFinder;
import dev.comfyfluffy.caustica.mixin.GpuDeviceAccessor;
import dev.comfyfluffy.caustica.ngx.NgxRuntime;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFramePresenter;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtReflex;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
import dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg;
import dev.comfyfluffy.caustica.rt.terrain.RtTerrain;
import dev.comfyfluffy.caustica.rt.terrain.RtWorkerPool;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterDebugEntriesEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;

@Mod(value = CausticaMod.MOD_ID, dist = Dist.CLIENT)
public final class CausticaClient {
    private static boolean rtInitDone;

    public CausticaClient(IEventBus modBus) {
        CausticaMod.LOGGER.info("Caustica client initialized");
        modBus.addListener((RegisterDebugEntriesEvent event) ->
                event.register(RtExposureDebugEntry.ID, new RtExposureDebugEntry()));
        NeoForge.EVENT_BUS.addListener(CausticaClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(CausticaClient::onGameShuttingDown);
    }

    private static void onClientTick(ClientTickEvent.Pre event) {
		if (!VanillaRenderController.rtRuntimeWorkRequested()) {
			if (rtInitDone || RtContext.currentOrNull() != null || NgxRuntime.INSTANCE.isInitialized()) {
				shutdownRt();
			}
			return;
		}

		// Bring up the RT device/context once; terrain residency + the composite follow below.
		if (!rtInitDone && RtDeviceBringup.rtRequested()) {
			RtContext ctx = RtContext.get();
			if (ctx != null) {
				rtInitDone = true;
			}
		}

		// Once RT is up, keep section residency synced to vanilla's loaded chunks around
		// the player — builds newly-in-range sections, frees out-of-range ones, per tick.
		if (rtInitDone) {
			RtContext ctx = RtContext.currentOrNull();
			if (ctx != null) {
				RtFrameStats.FRAME.beginIfInactive();
				// Bring the world pipeline + LabPBR atlases up before terrain tessellates, so per-prim
				// material flags resolve from the first section (PBR on join, no re-extract). No-op
				// until we're in a world with the block atlas loaded, or once already created.
				RtComposite.INSTANCE.ensureResourcesReady(ctx);
				RtTerrain.update(ctx);
				// Log DLSS-FG availability once when frame generation is enabled (capability query only;
				// the present-loop integration that consumes it is built separately).
				if (RtDlssFg.enabled()) {
					RtDlssFg.INSTANCE.probeAvailabilityOnce();
				}
			}
		}
    }

    private static void onGameShuttingDown(GameShuttingDownEvent event) {
        shutdownRt();
    }

	private static void shutdownRt() {
		WorldRenderScaler.INSTANCE.destroy();
		RtUiOverlay.destroy(); // GUI redirect is not gated by rtInitDone; always release its TextureTarget
		RtContext ctx = RtContext.currentOrNull();
		if (!rtInitDone && ctx == null && !NgxRuntime.INSTANCE.isInitialized()) {
			RtWorkerPool.INSTANCE.shutdown();
			AtlasSpriteFinder.clearCache();
			return;
		}

		if (ctx != null) {
			// Let the terrain epoch cancel queued work and release every active-task token before
			// shutdownNow(): discarded worker Runnables cannot deliver their terminal callbacks.
			RtTerrain.shutdown(ctx);
		}
		RtWorkerPool.INSTANCE.shutdown();
		AtlasSpriteFinder.clearCache();
		if (ctx != null) {
			RtEntities.INSTANCE.shutdown();
		}
		RtComposite.INSTANCE.destroy();
		RtEntityTextures.INSTANCE.reset();
		RtBlockMaterials.INSTANCE.destroy();
		RtDlssFg.INSTANCE.destroy();
		VulkanDevice device = ctx != null ? ctx.device() : currentVulkanDevice();
		if (device != null) {
			RtFramePresenter.INSTANCE.destroy(device);
			RtReflex.INSTANCE.destroy(device.vkDevice());
		}
		// Shut NGX down once, after every feature (RR + FG) has been released above.
		NgxRuntime.INSTANCE.shutdown();
		if (ctx != null) {
			ctx.destroy();
		}
		rtInitDone = false;
	}

	private static VulkanDevice currentVulkanDevice() {
		try {
			if (!RenderSystem.isOnRenderThread()) {
				return null;
			}
			var gpuDevice = RenderSystem.getDevice();
			if (gpuDevice != null && ((GpuDeviceAccessor) gpuDevice).caustica$getBackend() instanceof VulkanDevice vulkanDevice) {
				return vulkanDevice;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}
}
