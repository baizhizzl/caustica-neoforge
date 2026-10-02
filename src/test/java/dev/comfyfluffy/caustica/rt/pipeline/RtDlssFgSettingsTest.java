package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.CausticaConfig;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class RtDlssFgSettingsTest {
    @Test
    void requestedMfgWaitsForEnoughSwapchainImages() throws ReflectiveOperationException {
        RtDlssFg fg = backend(5);
        var setting = CausticaConfig.Rt.Fg.MULTI_FRAME_COUNT;
        int previous = setting.value();
        try {
            setting.set(5);
            fg.setSurfaceFrameLimits(2, 0);
            fg.setSwapchainFrameLimit(3, 2);
            assertEquals(5, fg.plannedMultiFrameCount());
            assertEquals(1, fg.effectiveMultiFrameCount());
            fg.setSwapchainFrameLimit(7, 2);
            assertEquals(5, fg.effectiveMultiFrameCount());
        } finally {
            setting.set(previous);
        }
    }

    @Test
    void windowSystemLimitsBothChoicesAndActualGeneration() throws ReflectiveOperationException {
        RtDlssFg fg = backend(5);
        fg.setSurfaceFrameLimits(2, 3);
        fg.setSwapchainFrameLimit(3, 2);
        assertEquals(1, fg.multiFrameCountMax());
        assertEquals(1, fg.effectiveMultiFrameCount());
        assertTrue(fg.isAvailable());
        fg.setSurfaceFrameLimits(2, 2);
        fg.setSwapchainFrameLimit(2, 2);
        assertFalse(fg.isAvailable());
        assertEquals(0, fg.plannedMultiFrameCount());
        assertEquals(0, fg.effectiveMultiFrameCount());
    }

    private static RtDlssFg backend(int driverMaximum) throws ReflectiveOperationException {
        Constructor<RtDlssFg> constructor = RtDlssFg.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        RtDlssFg fg = constructor.newInstance();
        Field max = RtDlssFg.class.getDeclaredField("multiFrameCountMax");
        max.setAccessible(true);
        max.setInt(fg, driverMaximum);
        Field available = RtDlssFg.class.getDeclaredField("available");
        available.setAccessible(true);
        available.setBoolean(fg, true);
        return fg;
    }
}
