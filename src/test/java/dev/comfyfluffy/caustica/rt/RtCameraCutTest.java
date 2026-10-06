package dev.comfyfluffy.caustica.rt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtCameraCutTest {
    private static final Object OVERWORLD = new Object();
    private static final Object NETHER = new Object();
    private static final Object FIRST_PERSON = new Object();
    private static final Object THIRD_PERSON = new Object();

    @Test
    void theFirstFrameInAWorldIsAWorldCut() {
        RtCameraCut cut = new RtCameraCut();
        assertEquals(RtCameraCut.Kind.WORLD, cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0));
        assertEquals(RtCameraCut.Kind.NONE, cut.update(OVERWORLD, FIRST_PERSON, 0.5, 64, 0));
    }

    @Test
    void continuousMovementUpToTheLimitKeepsHistory() {
        RtCameraCut cut = new RtCameraCut();
        cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0);
        assertEquals(RtCameraCut.Kind.NONE,
                cut.update(OVERWORLD, FIRST_PERSON, RtCameraCut.MAX_CONTINUOUS_MOVE, 64, 0));
        assertEquals(RtCameraCut.Kind.JUMP,
                cut.update(OVERWORLD, FIRST_PERSON, 2 * RtCameraCut.MAX_CONTINUOUS_MOVE + 0.01, 64, 0));
        assertEquals(RtCameraCut.Kind.NONE,
                cut.update(OVERWORLD, FIRST_PERSON, 2 * RtCameraCut.MAX_CONTINUOUS_MOVE + 1, 64, 0));
    }

    @Test
    void aNonFinitePositionIsNeverContinuous() {
        RtCameraCut cut = new RtCameraCut();
        cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0);
        assertEquals(RtCameraCut.Kind.JUMP, cut.update(OVERWORLD, FIRST_PERSON, Double.NaN, 64, 0));
    }

    @Test
    void cameraModeAndWorldChangesAreCuts() {
        RtCameraCut cut = new RtCameraCut();
        cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0);
        assertEquals(RtCameraCut.Kind.CAMERA_MODE, cut.update(OVERWORLD, THIRD_PERSON, 0, 64, 0));
        assertEquals(RtCameraCut.Kind.WORLD, cut.update(NETHER, THIRD_PERSON, 0, 64, 0));
        assertEquals(RtCameraCut.Kind.NONE, cut.update(NETHER, THIRD_PERSON, 0, 64, 0));
    }

    @Test
    void aForcedCutAppliesToExactlyOneFrame() {
        RtCameraCut cut = new RtCameraCut();
        cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0);
        cut.force();
        assertEquals(RtCameraCut.Kind.RESTART, cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0));
        assertEquals(RtCameraCut.Kind.NONE, cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0));
    }

    @Test
    void aDimensionChangeStaysAWorldCutDespiteTheInvalidation() {
        RtCameraCut cut = new RtCameraCut();
        cut.update(OVERWORLD, FIRST_PERSON, 0, 64, 0);
        cut.force();
        assertEquals(RtCameraCut.Kind.WORLD, cut.update(NETHER, FIRST_PERSON, 0, 64, 0));
        assertEquals(RtCameraCut.Kind.NONE, cut.update(NETHER, FIRST_PERSON, 0, 64, 0));
    }
}
