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

    /** Values stored in TOML/NGX count generated frames, not the multiplier shown in the UI. */
    public static List<Integer> generatedFrameChoices(int driverMaximum) {
        return IntStream.rangeClosed(1, generatedFrameLimit(driverMaximum)).boxed().toList();
    }
}
