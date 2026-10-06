package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;
import com.electronwill.nightconfig.core.CommentedConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaConfigTest {
    @Test
    void rrModelAcceptsFAndCanReturnToAuto() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.DlssRr.PRESET;
        int previous = setting.value();
        try {
            for (int preset : CausticaConfig.Rt.DlssRr.PRESET_STEPS) {
                setting.set(preset);
                assertEquals(preset, setting.value());
            }
            setting.set(0);
            assertEquals(0, setting.value());
            for (int invalid : new int[] {-1, 1, 7, 12, 13, Integer.MAX_VALUE}) {
                setting.set(invalid);
                assertEquals(0, setting.value());
            }
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void frameGenerationCountIsBoundedBeforeGpuAllocation() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
        int previous = setting.value();
        try {
            for (int count = 1; count <= 5; count++) {
                setting.set(count);
                assertEquals(count, setting.value());
            }
            setting.set(Integer.MIN_VALUE);
            assertEquals(1, setting.value());
            setting.set(Integer.MAX_VALUE);
            assertEquals(5, setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void invalidPeakNitsFallsBackToDefault() {
        CausticaConfig.IntSetting setting = CausticaConfig.Rt.Hdr.PEAK_NITS;
        int previous = setting.value();
        try {
            setting.set(2000);
            assertEquals(2000, setting.value());

            setting.set(900);
            assertEquals(1000, setting.value());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void performanceSwitchesRegisterAndRoundTripWithoutChangingCandidateCount() {
        CausticaConfig.ensureRegistered();
        var config = CommentedConfig.inMemory();
        int count = CausticaConfig.Rt.Lights.RIS_CANDIDATES.value();
        for (var setting : new CausticaConfig.BooleanSetting[] {
                CausticaConfig.Rt.Composite.TLAS_CACHE, CausticaConfig.Rt.Lights.PRESAMPLING}) {
            boolean previous = setting.value();
            try {
                assertTrue(CausticaConfig.settings().contains(setting));
                assertEquals(setting == CausticaConfig.Rt.Composite.TLAS_CACHE, setting.defaultValue());
                for (boolean enabled : new boolean[] {false, true}) {
                    setting.set(enabled);
                    setting.writeToFile(config);
                    assertEquals(enabled, config.<Boolean>get(setting.tomlPath()));
                    assertEquals(count, CausticaConfig.Rt.Lights.RIS_CANDIDATES.value());
                }
            } finally {
                setting.set(previous);
            }
        }
    }
}
