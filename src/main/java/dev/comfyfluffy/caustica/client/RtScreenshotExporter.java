package dev.comfyfluffy.caustica.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.comfyfluffy.caustica.CausticaMod;
import dev.comfyfluffy.caustica.rt.RtComposite;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.ScreenshotEvent;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/** Adds a residual-exposed scene-linear ACEScg EXR beside vanilla's ordinary F2 PNG. */
public final class RtScreenshotExporter {
    private static final Object NAME_LOCK = new Object();
    private static String lastBaseName = "";
    private static int nextIndex = 1;

    private RtScreenshotExporter() {
    }

    /**
     * Captures the paired RT EXR + SDR PNG screenshot, respecting NeoForge's {@link ScreenshotEvent}
     * cancellation and destination-file redirection while offloading both PNG and EXR encoding/disk writes
     * to {@link Util#ioPool()}.
     *
     * @return {@code true} if this exporter handled the screenshot request, or {@code false} when no RT
     *         frame is active (or EXR GPU readback failed) so the caller should run vanilla's unmodified path
     */
    public static boolean grabWithExr(
            File workDir,
            RenderTarget target,
            int downscaleFactor,
            Consumer<Component> callback
    ) {
        final RtComposite.CapturedExr capturedExr;
        try {
            capturedExr = RtComposite.INSTANCE.captureLatestResidualExposureExr();
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Couldn't capture residual-exposure ACEScg screenshot", e);
            callback.accept(Component.literal("Couldn't capture Caustica EXR: " + e.getMessage())
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (capturedExr == null) {
            return false;
        }

        Path screenshotDirectory = workDir.toPath().resolve(Screenshot.SCREENSHOT_DIR);
        final File reservedPng;
        try {
            Files.createDirectories(screenshotDirectory);
            reservedPng = reservePairedPng(screenshotDirectory);
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Couldn't reserve paired screenshot filename", e);
            return false;
        }

        Screenshot.takeScreenshot(target, downscaleFactor, image -> {
            ScreenshotEvent event = ClientHooks.onScreenshot(image, reservedPng);
            if (event.isCanceled()) {
                image.close();
                callback.accept(event.getCancelMessage());
                return;
            }
            File targetPng = event.getScreenshotFile();
            Util.ioPool().execute(() -> writePngAndExr(image, targetPng, event, capturedExr, callback));
        });
        return true;
    }

    private static void writePngAndExr(
            NativeImage image,
            File targetPng,
            ScreenshotEvent event,
            RtComposite.CapturedExr capturedExr,
            Consumer<Component> callback
    ) {
        try (NativeImage img = image) {
            Path pngPath = targetPng.toPath();
            Path parent = pngPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            img.writeToFile(targetPng);
            if (event.getResultMessage() != null) {
                callback.accept(event.getResultMessage());
            } else {
                File absPng = targetPng.getAbsoluteFile();
                Component pngLink = Component.literal(targetPng.getName())
                        .withStyle(ChatFormatting.UNDERLINE)
                        .withStyle(style -> style.withClickEvent(new ClickEvent.OpenFile(absPng)));
                callback.accept(Component.translatable("screenshot.success", pngLink));
            }
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Couldn't save screenshot", e);
            callback.accept(Component.translatable("screenshot.failure", e.getMessage()));
            return;
        }

        Path exrPath = pairedExrPath(targetPng.toPath());
        try {
            Path parent = exrPath.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            capturedExr.write(exrPath);
            File absExr = exrPath.toFile().getAbsoluteFile();
            Component exrLink = Component.literal(absExr.getName())
                    .withStyle(ChatFormatting.UNDERLINE)
                    .withStyle(style -> style.withClickEvent(new ClickEvent.OpenFile(absExr)));
            callback.accept(Component.literal("Saved residual-exposure ACEScg EXR: ").append(exrLink));
            CausticaMod.LOGGER.info("Saved residual-exposure ACEScg screenshot to {}", absExr);
        } catch (Exception e) {
            CausticaMod.LOGGER.warn("Couldn't save residual-exposure ACEScg screenshot", e);
            callback.accept(Component.literal("Couldn't save Caustica EXR: " + e.getMessage())
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static File reservePairedPng(Path directory) {
        synchronized (NAME_LOCK) {
            String base = Util.getFilenameFormattedDateTime();
            int count = base.equals(lastBaseName) ? nextIndex : 1;
            while (true) {
                String suffix = count == 1 ? "" : "_" + count;
                Path exrCandidate = directory.resolve(base + suffix + ".exr");
                Path pngCandidate = directory.resolve(base + suffix + ".png");
                if (!Files.exists(exrCandidate) && !Files.exists(pngCandidate)) {
                    lastBaseName = base;
                    nextIndex = count + 1;
                    return pngCandidate.toFile();
                }
                count++;
            }
        }
    }

    private static Path pairedExrPath(Path pngPath) {
        String name = pngPath.getFileName().toString();
        String exrName = name.toLowerCase(Locale.ROOT).endsWith(".png")
                ? name.substring(0, name.length() - ".png".length()) + ".exr"
                : name + ".exr";
        Path parent = pngPath.getParent();
        return parent != null ? parent.resolve(exrName) : Path.of(exrName);
    }
}
