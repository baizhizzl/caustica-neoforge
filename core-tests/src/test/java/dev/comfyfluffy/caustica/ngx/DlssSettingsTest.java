package dev.comfyfluffy.caustica.ngx;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

final class DlssSettingsTest {
    @ParameterizedTest
    @CsvSource({"100, 0, 1", "3, -1, 1", "3, 1, 1", "5, 2, 2", "5, 3, 3",
            "5, 5, 5", "2147483647, 2147483647, 5", "0, 5, 1", "-1, 5, 1", "2, 5, 2"})
    void generatedCountNeverExceedsDriverOrAllocationLimit(int requested, int driverMax, int expected) {
        assertEquals(expected, DlssSettings.generatedFrameCount(requested, driverMax));
    }

    @Test
    void unknownMfgSupportOffersOnlyStandard2x() {
        assertEquals(List.of(1), DlssSettings.generatedFrameChoices(0));
        assertEquals(List.of(1), DlssSettings.generatedFrameChoices(-1));
        assertEquals(List.of(1), DlssSettings.generatedFrameChoices(1));
    }

    @Test
    void multiplierChoicesMatchNgxGeneratedCounts() {
        assertEquals(List.of(1, 2, 3), DlssSettings.generatedFrameChoices(3));
        assertEquals(List.of(2, 3, 4, 5, 6), DlssSettings.generatedFrameChoices(5).stream()
                .map(count -> count + 1).toList());
        assertEquals(5, DlssSettings.generatedFrameChoices(Integer.MAX_VALUE).size());
    }

    @Test
    void swapchainImageBudgetReservesTheSurfaceMinimum() {
        assertEquals(1, DlssSettings.swapchainGeneratedFrameLimit(3, 2));
        assertEquals(0, DlssSettings.swapchainGeneratedFrameLimit(3, 3));
        assertEquals(5, DlssSettings.swapchainGeneratedFrameLimit(7, 2));
    }

    @Test
    void highMultipliersGrowTheSwapchainBeforeTheBatchIsAcquired() {
        assertEquals(5, DlssSettings.requiredSwapchainImages(3, 2, 0, 3));
        assertEquals(7, DlssSettings.requiredSwapchainImages(3, 2, 0, 5));
        assertEquals(4, DlssSettings.requiredSwapchainImages(4, 2, 0, 1));
        assertEquals(3, DlssSettings.requiredSwapchainImages(3, 2, 3, 5));
    }

    @Test
    void surfaceMaximumLimitsSelectableMultipliers() {
        assertEquals(1, DlssSettings.surfaceGeneratedFrameLimit(2, 3));
        assertEquals(0, DlssSettings.surfaceGeneratedFrameLimit(2, 2));
        assertEquals(5, DlssSettings.surfaceGeneratedFrameLimit(2, 0));
    }

    @Test
    void rayReconstructionUsesItsOwnModelEnum() {
        assertEquals(List.of(0, 4, 5, 6), DlssSettings.RR_PRESETS);
        assertFalse(DlssSettings.RR_PRESETS.contains(12));
        assertFalse(DlssSettings.RR_PRESETS.contains(13));
    }
}
