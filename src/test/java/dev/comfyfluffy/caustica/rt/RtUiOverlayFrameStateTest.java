package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtUiOverlayFrameStateTest {
    @Test
    void sdrCompositeRetainsUiForEveryGeneratedFrame() {
        var state = new RtUiOverlay.FrameState();
        state.beginFrame();
        state.populate();

        state.compositeSdr(false);

        assertFalse(state.compositePending());
        for (int generatedFrame = 0; generatedFrame < 5; generatedFrame++) {
            assertTrue(state.populated(), "generated frames must still receive the UI texture");
        }

        state.beginFrame();
        assertFalse(state.populated(), "a frame without GUI must not reuse old UI");
        assertFalse(state.compositePending());
    }

    @Test
    void hdrCompositeConsumesDisplayWorkButRetainsUiForFrameGeneration() {
        var state = new RtUiOverlay.FrameState();
        state.populate();
        state.compositeSdr(true);
        assertTrue(state.compositePending(), "HDR present must still composite after the SDR screenshot");

        state.consume();

        assertFalse(state.compositePending(), "a repeated present must not blend the UI twice");
        assertTrue(state.populated(), "HDR frame generation runs after display consumption");

        state.beginFrame();
        assertFalse(state.populated());
        assertFalse(state.compositePending());
    }

    @Test
    void emptyFramesNeverAdvertiseUiContent() {
        var state = new RtUiOverlay.FrameState();
        state.beginFrame();
        state.compositeSdr(true);
        state.consume();

        assertFalse(state.populated());
        assertFalse(state.compositePending());
    }
}
