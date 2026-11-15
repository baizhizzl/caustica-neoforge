package dev.comfyfluffy.caustica;

import org.junit.jupiter.api.Test;
import com.electronwill.nightconfig.core.CommentedConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                CausticaConfig.Rt.Composite.TLAS_CACHE, CausticaConfig.Rt.Composite.TLAS_UPDATE,
                CausticaConfig.Rt.Lights.PRESAMPLING}) {
            boolean previous = setting.value();
            try {
                assertTrue(CausticaConfig.settings().contains(setting));
                assertEquals(setting != CausticaConfig.Rt.Lights.PRESAMPLING, setting.defaultValue());
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

    @Test
    void indirectRisCandidatesNeverExceedThePrimaryCount() {
        CausticaConfig.IntSetting primary = CausticaConfig.Rt.Lights.RIS_CANDIDATES;
        CausticaConfig.IntSetting indirect = CausticaConfig.Rt.Lights.RIS_CANDIDATES_INDIRECT;
        int previousPrimary = primary.value();
        int previousIndirect = indirect.value();
        try {
            assertEquals(4, indirect.defaultValue());
            primary.set(8);
            indirect.set(4);
            assertEquals(4, CausticaConfig.Rt.Lights.indirectRisCandidates());
            primary.set(2);
            assertEquals(2, CausticaConfig.Rt.Lights.indirectRisCandidates());
            primary.set(0);
            assertEquals(0, CausticaConfig.Rt.Lights.indirectRisCandidates());
        } finally {
            primary.set(previousPrimary);
            indirect.set(previousIndirect);
        }
    }

    @Test
    void visualEnhancementsDefaultToStrictlyNeutralAcesCalibration() {
        assertTrue(CausticaConfig.Rt.DynamicLights.ENABLED.defaultValue());
        assertFalse(CausticaConfig.Rt.Visuals.VOLUMETRIC_FOG.defaultValue());
        assertFalse(CausticaConfig.Rt.Visuals.MULTISCATTER_GGX.defaultValue());
        assertEquals(1.0f, CausticaConfig.Rt.Visuals.CAUSTICS_INTENSITY.defaultValue(), 1.0e-6f);
        assertEquals(1.0f, CausticaConfig.Rt.Visuals.BLOOM_SCALE.defaultValue(), 1.0e-6f);
        assertEquals(1.0f, CausticaConfig.Rt.Visuals.CONTRAST.defaultValue(), 1.0e-6f);
        assertEquals(1.0f, CausticaConfig.Rt.Visuals.VIBRANCE.defaultValue(), 1.0e-6f);
        assertEquals(0.0f, CausticaConfig.Rt.Visuals.SHARPNESS.defaultValue(), 1.0e-6f);
    }

    @Test
    void visualSettingsClampToTheirDeclaredRangesAndPersist() {
        var config = CommentedConfig.inMemory();
        var contrast = CausticaConfig.Rt.Visuals.CONTRAST;
        var vibrance = CausticaConfig.Rt.Visuals.VIBRANCE;
        var bloom = CausticaConfig.Rt.Visuals.BLOOM_SCALE;
        float prevContrast = contrast.value();
        float prevVibrance = vibrance.value();
        float prevBloom = bloom.value();
        try {
            contrast.set(9.0f);
            assertEquals(1.5f, contrast.value(), 1.0e-6f);
            contrast.set(0.1f);
            assertEquals(0.75f, contrast.value(), 1.0e-6f);

            vibrance.set(9.0f);
            assertEquals(2.0f, vibrance.value(), 1.0e-6f);
            vibrance.set(-1.0f);
            assertEquals(0.5f, vibrance.value(), 1.0e-6f);

            bloom.set(1.15f);
            bloom.writeToFile(config);
            assertEquals(1.15, config.<Number>get("visuals.bloom-scale").doubleValue(), 1.0e-5);
        } finally {
            contrast.set(prevContrast);
            vibrance.set(prevVibrance);
            bloom.set(prevBloom);
        }
    }

    @Test
    void dynamicLightAndVisualEnhancementSettingsRegisterClampAndRoundTrip() {
        CausticaConfig.ensureRegistered();
        var config = CommentedConfig.inMemory();
        assertTrue(CausticaConfig.settings().contains(CausticaConfig.Rt.DynamicLights.ENABLED));
        assertTrue(CausticaConfig.settings().contains(CausticaConfig.Rt.Visuals.VOLUMETRIC_FOG));
        assertTrue(CausticaConfig.settings().contains(CausticaConfig.Rt.Visuals.CAUSTICS_INTENSITY));
        assertTrue(CausticaConfig.settings().contains(CausticaConfig.Rt.Visuals.SHARPNESS));

        float prevIntensity = CausticaConfig.Rt.DynamicLights.INTENSITY.value();
        float prevSharpness = CausticaConfig.Rt.Visuals.SHARPNESS.value();
        try {
            CausticaConfig.Rt.DynamicLights.INTENSITY.set(99.0f);
            assertEquals(4.0f, CausticaConfig.Rt.DynamicLights.INTENSITY.value());
            CausticaConfig.Rt.DynamicLights.INTENSITY.set(-5.0f);
            assertEquals(0.0f, CausticaConfig.Rt.DynamicLights.INTENSITY.value());

            CausticaConfig.Rt.Visuals.SHARPNESS.set(0.45f);
            CausticaConfig.Rt.Visuals.SHARPNESS.writeToFile(config);
            assertEquals(0.45, config.<Number>get("visuals.sharpness").doubleValue(), 1.0e-5);
        } finally {
            CausticaConfig.Rt.DynamicLights.INTENSITY.set(prevIntensity);
            CausticaConfig.Rt.Visuals.SHARPNESS.set(prevSharpness);
        }
    }
}
