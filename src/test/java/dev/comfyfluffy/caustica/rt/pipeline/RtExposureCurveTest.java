package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RtExposureCurveTest {
    private static final float EPSILON = 1.0e-5f;

    @Test
    void fullPresetReproducesLegacyFullAdaptation() {
        RtExposure.ExposureCurve curve = RtExposure.parseCurve("full");

        assertEquals(0.0f, curve.compensationAt(-20.0f), EPSILON);
        assertEquals(0.0f, curve.compensationAt(1.5f), EPSILON);
        assertEquals(1.0f, curve.effectiveSlopeAt(1.5f), EPSILON);
    }

    @Test
    void pointsAreSortedAndInterpolatedPiecewise() {
        RtExposure.ExposureCurve curve =
                RtExposure.parseCurve("4:0.4, 0:0, -6:-2.0, -3:-0.8");

        assertEquals(-6.0f, curve.scene0(), EPSILON);
        assertEquals(4.0f, curve.scene3(), EPSILON);
        assertEquals(-0.4f, curve.compensationAt(-1.5f), EPSILON);
        assertEquals(1.0f - (0.8f / 3.0f), curve.effectiveSlopeAt(-1.5f), EPSILON);
    }

    @Test
    void endpointCompensationIsConstantOutsideAuthoredDomain() {
        RtExposure.ExposureCurve curve =
                RtExposure.parseCurve("-6:-2.0, -3:-0.8, 0:0, 4:0.4");

        assertEquals(-2.0f, curve.compensationAt(-10.0f), EPSILON);
        assertEquals(0.4f, curve.compensationAt(8.0f), EPSILON);
        assertEquals(1.0f, curve.effectiveSlopeAt(-10.0f), EPSILON);
        assertEquals(1.0f, curve.effectiveSlopeAt(8.0f), EPSILON);
    }

    @Test
    void malformedOrDuplicatePointsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> RtExposure.parseCurve("-6:-2, -3:-0.8, 0:0"));
        assertThrows(IllegalArgumentException.class,
                () -> RtExposure.parseCurve("-6:-2, -3:-0.8, 0:0, 0:0.4"));
        assertThrows(IllegalArgumentException.class,
                () -> RtExposure.parseCurve("-6:-2, -3:nope, 0:0, 4:0.4"));
    }
}
