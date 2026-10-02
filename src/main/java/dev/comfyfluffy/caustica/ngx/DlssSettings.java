package dev.comfyfluffy.caustica.ngx;

import java.util.List;
import java.util.stream.IntStream;

/** Pure configuration bounds shared by the options screen and NGX evaluation. */
public final class DlssSettings {
    /** RR-specific SDK presets: Auto, D, E and F (RR2); SR presets L/M are not valid RR models. */
    public static final List<Integer> RR_PRESETS = List.of(0, 4, 5, 6);
    /** Bound image allocations to at most five generated frames (6x including the rendered frame). */
    public static final int MAX_GENERATED_FRAMES = 5;

    private DlssSettings() {
    }

    /** An absent MFG capability means standard 2x FG, never an unbounded user-supplied count. */
    public static int generatedFrameLimit(int driverMaximum) {
        return Math.clamp(driverMaximum, 1, MAX_GENERATED_FRAMES);
    }

    public static int generatedFrameCount(int requested, int driverMaximum) {
        return Math.clamp(requested, 1, generatedFrameLimit(driverMaximum));
    }

    /** Zero maxImageCount means unbounded; a surface with no spare images cannot batch any FG presents. */
    public static int surfaceGeneratedFrameLimit(int surfaceMinimum, int surfaceMaximum) {
        return surfaceMaximum == 0 ? MAX_GENERATED_FRAMES
                : Math.clamp(surfaceMaximum - Math.max(1, surfaceMinimum), 0, MAX_GENERATED_FRAMES);
    }

    /** Reserve the surface minimum plus every generated frame to guarantee forward progress before submit. */
    public static int requiredSwapchainImages(int vanillaCount, int surfaceMinimum, int surfaceMaximum,
            int generatedCount) {
        int count = Math.clamp(generatedCount, 0, surfaceGeneratedFrameLimit(surfaceMinimum, surfaceMaximum));
        return Math.max(vanillaCount, Math.max(1, surfaceMinimum) + count);
    }

    /** Minecraft already holds the real frame; the remaining guaranteed acquisitions are images - minimum. */
    public static int swapchainGeneratedFrameLimit(int imageCount, int surfaceMinimum) {
        return Math.clamp(imageCount - Math.max(1, surfaceMinimum), 0, MAX_GENERATED_FRAMES);
    }

    /** Values stored in TOML/NGX count generated frames, not the multiplier shown in the UI. */
    public static List<Integer> generatedFrameChoices(int driverMaximum) {
        return IntStream.rangeClosed(1, generatedFrameLimit(driverMaximum)).boxed().toList();
    }
}
