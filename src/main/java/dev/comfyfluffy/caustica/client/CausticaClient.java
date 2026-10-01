package dev.comfyfluffy.caustica.client;

import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtContext;
import dev.comfyfluffy.caustica.rt.RtDeviceBringup;
import dev.comfyfluffy.caustica.rt.RtComposite;
import dev.comfyfluffy.caustica.rt.RtFrameStats;
import dev.comfyfluffy.caustica.rt.RtUiOverlay;
import dev.comfyfluffy.caustica.rt.entity.RtEntities;
import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.material.RtBlockMaterials;
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
			if (rtInitDone) {
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
				if (dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.enabled()) {
					dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.probeAvailabilityOnce();
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
		if (!rtInitDone) {
			RtWorkerPool.INSTANCE.shutdown();
        dev.comfyfluffy.caustica.compat.AtlasSpriteFinder.clearCache();
			return;
		}

		RtContext ctx = RtContext.currentOrNull();
		if (ctx != null) {
			// Let the terrain epoch cancel queued work and release every active-task token before
			// shutdownNow(): discarded worker Runnables cannot deliver their terminal callbacks.
			RtTerrain.shutdown(ctx);
		}
		RtWorkerPool.INSTANCE.shutdown();
        dev.comfyfluffy.caustica.compat.AtlasSpriteFinder.clearCache();
		if (ctx != null) {
			RtEntities.INSTANCE.shutdown();
		}
		RtComposite.INSTANCE.destroy();
		RtEntityTextures.INSTANCE.reset();
		RtBlockMaterials.INSTANCE.destroy();
		dev.comfyfluffy.caustica.rt.pipeline.RtDlssFg.INSTANCE.destroy();
		if (ctx != null) {
			dev.comfyfluffy.caustica.rt.RtFramePresenter.INSTANCE.destroy(ctx.device());
			dev.comfyfluffy.caustica.rt.RtReflex.INSTANCE.destroy(ctx.device().vkDevice());
		}
		// Shut NGX down once, after every feature (RR + FG) has been released above.
		dev.comfyfluffy.caustica.ngx.NgxRuntime.INSTANCE.shutdown();
		if (ctx != null) {
			ctx.destroy();
		}
		rtInitDone = false;
	}
}
