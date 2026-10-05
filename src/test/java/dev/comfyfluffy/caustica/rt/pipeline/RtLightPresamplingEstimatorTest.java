package dev.comfyfluffy.caustica.rt.pipeline;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exact finite-state CPU reference for RIS weights; this does not execute the GPU shader. */
final class RtLightPresamplingEstimatorTest {
    private static final double[] GLOBAL = {0.2, 0.3, 0.5};
    private static final double[] LOCAL = {0.8, 0.2, 0.0};
    private static final double[] TARGET = {1.0, 3.0, 7.0};

    @Test
    void correlatedPoolDrawsRetainTheIntegralWithEightStratifiedCandidates() {
        assertEquals(8.0, expectation(GLOBAL, LOCAL, TARGET, new double[] {1, 0, 1}, 8, false), 1e-10);
        assertEquals(11.0, expectation(GLOBAL, LOCAL, TARGET, new double[] {1, 1, 1}, 8, false), 1e-10);
    }

    @Test
    void visibilityIsEvaluatedForTheCurrentSceneNotCachedInProposals() {
        assertEquals(10.0, expectation(GLOBAL, LOCAL, TARGET, new double[] {0, 1, 1}, 8, false), 1e-10);
        assertEquals(0.0, expectation(GLOBAL, LOCAL, TARGET, new double[] {0, 0, 0}, 8, false), 1e-10);
    }

    @Test
    void singleCandidateAndMissingGridRetainGlobalSupport() {
        assertEquals(8.0, expectation(GLOBAL, LOCAL, TARGET, new double[] {1, 0, 1}, 1, false), 1e-10);
        assertEquals(8.0, expectation(GLOBAL, GLOBAL, TARGET, new double[] {1, 0, 1}, 8, false), 1e-10);
    }

    @Test
    void freshPublishedDistributionsHandleChangedPowerCountAndZeroWeightCandidates() {
        double[] global = {0.1, 0.2, 0.3, 0.4};
        double[] local = {0.6, 0.4, 0.0, 0.0};
        assertEquals(15.0, expectation(global, local, new double[] {0, 2, 5, 10},
                new double[] {1, 0, 1, 1}, 8, false), 1e-10);
    }

    @Test
    void empiricalPoolFrequenciesWouldLoseAbsentLightsAndBiasTheEstimate() {
        double wrong = expectation(GLOBAL, LOCAL, TARGET, new double[] {1, 0, 1}, 8, true);
        assertTrue(Math.abs(wrong - 8.0) > 0.5);
    }

    private record Pool(int[] lights, double probability) {}

    // Two entries deliberately exaggerate correlation. Enumerating every pool and query choice
    // checks the expectation exactly instead of making a noisy assertion from a Monte Carlo run.
    private static List<Pool> pools(double[] pdf) {
        List<Pool> pools = new ArrayList<>();
        for (int a = 0; a < pdf.length; a++) for (int b = 0; b < pdf.length; b++) {
            double probability = pdf[a] * pdf[b];
            if (probability > 0.0) pools.add(new Pool(new int[] {a, b}, probability));
        }
        return pools;
    }

    private static double expectation(double[] global, double[] local, double[] target,
                                      double[] visibility, int count, boolean empiricalPdf) {
        int globals = Math.max(1, (count + 2) / 4);
        double alpha = (double) (count - globals) / count;
        double result = 0.0;
        for (Pool globalPool : pools(global)) for (Pool localPool : pools(local)) {
            double[] pdf = new double[target.length];
            for (int l = 0; l < pdf.length; l++) {
                double qg = empiricalPdf ? frequency(globalPool, l) : global[l];
                double ql = empiricalPdf ? frequency(localPool, l) : local[l];
                pdf[l] = alpha * ql + (1.0 - alpha) * qg;
            }
            int choices = 1 << count;
            for (int mask = 0; mask < choices; mask++) {
                double sum = 0.0;
                int[] candidates = new int[count];
                double[] weights = new double[count];
                for (int c = 0; c < count; c++) {
                    boolean useLocal = c * globals / count == (c + 1) * globals / count;
                    Pool pool = useLocal ? localPool : globalPool;
                    int l = pool.lights[(mask >>> c) & 1];
                    candidates[c] = l;
                    weights[c] = target[l] > 0.0 ? target[l] / pdf[l] : 0.0;
                    sum += weights[c];
                }
                double conditional = 0.0;
                if (sum > 0.0) for (int c = 0; c < count; c++) {
                    int l = candidates[c];
                    if (target[l] <= 0.0) continue;
                    double reservoirProbability = weights[c] / sum;
                    double w = sum / (count * target[l]);
                    conditional += reservoirProbability * target[l] * visibility[l] * w;
                }
                result += conditional * globalPool.probability * localPool.probability / choices;
            }
        }
        return result;
    }

    private static double frequency(Pool pool, int light) {
        return ((pool.lights[0] == light ? 1 : 0) + (pool.lights[1] == light ? 1 : 0)) / 2.0;
    }
}
