package dev.comfyfluffy.caustica.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class CausticaJitterTest {
    @Test
    void unsignedPhaseModuloStaysInHalfPixelRangeAcrossSignedIntWraparound() {
        CausticaJitter jitter = CausticaJitter.INSTANCE;
        jitter.setFrameIndexForTest(Integer.MAX_VALUE - 2);
        for (int step = 0; step < 8; step++) {
            jitter.prepare(1920, 1080, 3840);
            float x = jitter.jitterPixelsX();
            float y = jitter.jitterPixelsY();
            assertTrue(Float.isFinite(x) && x > -0.5f && x < 0.5f, "jitterX out of (-0.5, 0.5): " + x);
            assertTrue(Float.isFinite(y) && y > -0.5f && y < 0.5f, "jitterY out of (-0.5, 0.5): " + y);
        }
        jitter.setFrameIndexForTest(0);
    }
}
