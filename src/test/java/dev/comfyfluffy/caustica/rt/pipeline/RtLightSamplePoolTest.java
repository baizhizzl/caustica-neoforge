package dev.comfyfluffy.caustica.rt.pipeline;

import dev.comfyfluffy.caustica.rt.gen.LightCandidateData;
import dev.comfyfluffy.caustica.rt.gen.LightPresamplePushData;
import dev.comfyfluffy.caustica.rt.gen.WorldPushConstantsData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

final class RtLightSamplePoolTest {
    private static RtLightSamplePool.Plan plan(boolean enabled, int lights, int candidates, int x, int y, int z,
                                               double cx, double cy, double cz, double ox, double oy, double oz) {
        return RtLightSamplePool.plan(enabled, lights, candidates, x, y, z, cx, cy, cz, ox, oy, oz, 16.0);
    }

    @Test
    void disabledOrEmptyLightingNeedsNeitherAllocationNorDispatch() {
        assertEquals(0, plan(false, 5, 8, 10, 10, 10, 0, 0, 0, 0, 0, 0).entryCount());
        assertEquals(0, plan(true, 0, 8, 10, 10, 10, 0, 0, 0, 0, 0, 0).entryCount());
        assertEquals(0, plan(true, 5, 0, 10, 10, 10, 0, 0, 0, 0, 0, 0).entryCount());
    }

    @Test
    void absentGridUsesOnlyGlobalProposals() {
        var p = plan(true, 5, 8, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        assertTrue(p.active());
        assertEquals(RtLightSamplePool.GLOBAL_SAMPLES, p.entryCount());
        assertEquals(-1, p.cellOffset(0, 0, 0));
    }

    @Test
    void singleCandidateDoesNotPresampleUnusedLocalDistributions() {
        var p = plan(true, 5, 1, 100, 100, 100, 256, 128, 384, 0, 0, 0);
        assertEquals(RtLightSamplePool.GLOBAL_SAMPLES, p.entryCount());
        assertEquals(-1, p.cellOffset(16, 8, 24));
    }

    @Test
    void invalidCoordinatesAndCellSizeCannotProduceLocalBufferIndices() {
        var p = plan(true, 5, 8, 100, 100, 100, Double.NaN, 0, 0, 0, 0, 0);
        assertEquals(RtLightSamplePool.GLOBAL_SAMPLES, p.entryCount());
        p = plan(true, 5, 8, 100, 100, 100, 0, 0, 0, Double.POSITIVE_INFINITY, 0, 0);
        assertEquals(RtLightSamplePool.GLOBAL_SAMPLES, p.entryCount());
        p = RtLightSamplePool.plan(true, 5, 8, 100, 100, 100, 0, 0, 0, 0, 0, 0, 0.0);
        assertEquals(RtLightSamplePool.GLOBAL_SAMPLES, p.entryCount());
    }

    @Test
    void regionIsCenteredOnCameraAndClampedToActualGrid() {
        var p = plan(true, 5, 8, 100, 100, 100, 50 * 16, 60 * 16, 70 * 16, 0, 0, 0);
        assertEquals(46, p.originX());
        assertEquals(56, p.originY());
        assertEquals(66, p.originZ());
        assertEquals(RtLightSamplePool.MAX_ENTRIES, p.entryCount());
        var low = plan(true, 5, 8, 100, 100, 100, -1000, -1000, -1000, 0, 0, 0);
        assertEquals(0, low.originX());
        var high = plan(true, 5, 8, 100, 100, 100, 1e8, 1e8, 1e8, 0, 0, 0);
        assertEquals(92, high.originX());
        assertEquals(92, high.originY());
        assertEquals(92, high.originZ());
    }

    @Test
    void coordinateRebaseDoesNotChangeTheGridCellRegion() {
        var before = plan(true, 5, 8, 100, 100, 100, 256, 128, 384, -32, -32, -32);
        var after = plan(true, 5, 8, 100, 100, 100, 256 - 1024, 128 - 1024, 384 - 1024,
                -32 - 1024, -32 - 1024, -32 - 1024);
        assertEquals(before, after);
    }

    @Test
    void smallGridsHaveCompactDisjointCellRangesAndSafeOutsideFallback() {
        var p = plan(true, 5, 8, 3, 2, 4, 0, 0, 0, 0, 0, 0);
        assertEquals(1024 + 24 * 64, p.entryCount());
        var offsets = new HashSet<Integer>();
        for (int z = 0; z < p.dimZ(); z++) for (int y = 0; y < p.dimY(); y++) for (int x = 0; x < p.dimX(); x++) {
            int first = p.cellOffset(x, y, z);
            assertTrue(first >= 1024);
            assertTrue(first + 64 <= p.entryCount());
            assertTrue(offsets.add(first));
        }
        assertEquals(24, offsets.size());
        assertEquals(-1, p.cellOffset(-1, 0, 0));
        assertEquals(-1, p.cellOffset(3, 0, 0));
        assertEquals(-1, p.cellOffset(0, 2, 0));
        assertEquals(-1, p.cellOffset(0, 0, 4));
    }

    @Test
    void maximumPoolIsBoundedEvenForHugeDenseGrids() {
        var p = plan(true, 1, 8, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                100, 100, 100, 0, 0, 0);
        assertEquals(RtLightSamplePool.MAX_ENTRIES, p.entryCount());
        assertTrue((long) p.entryCount() * LightCandidateData.BYTE_SIZE < 3L * 1024 * 1024);
    }

    @Test
    void reflectedGpuLayoutsMatchAllocationAndMinimumPushConstantLimit() {
        assertEquals(80, LightCandidateData.BYTE_SIZE);
        assertEquals(64, LightPresamplePushData.BYTE_SIZE);
        assertTrue(WorldPushConstantsData.BYTE_SIZE <= 128);
    }

    @Test
    void reflectedCandidateSerializesEveryPackedLaneAtTheShaderOffset() {
        var data = ByteBuffer.allocateDirect(LightCandidateData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        new LightCandidateData(new LightCandidateData.Float3(1, 2, 3), 4,
                new LightCandidateData.Float3(5, 6, 7), 0.25f,
                new LightCandidateData.Float3(8, 9, 10), 0x40200801,
                new LightCandidateData.Float3(0, 1, 0), 2.5f,
                new LightCandidateData.Float3(11, 12, 13), 0).write(data);
        assertEquals(1f, data.getFloat(0));
        assertEquals(4f, data.getFloat(12));
        assertEquals(5f, data.getFloat(16));
        assertEquals(0.25f, data.getFloat(28));
        assertEquals(8f, data.getFloat(32));
        assertEquals(0x40200801, data.getInt(44));
        assertEquals(1f, data.getFloat(52));
        assertEquals(2.5f, data.getFloat(60));
        assertEquals(13f, data.getFloat(72));
        assertEquals(0, data.getInt(76));
    }

    @Test
    void reflectedPrepassPushHasSevenAddressesAndIndependentFrameBounds() {
        var data = ByteBuffer.allocateDirect(LightPresamplePushData.BYTE_SIZE).order(ByteOrder.nativeOrder());
        new LightPresamplePushData(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8, 9).write(data);
        for (int i = 0; i < 7; i++) assertEquals(i + 1L, data.getLong(i * Long.BYTES));
        assertEquals(8, data.getInt(56));
        assertEquals(9, data.getInt(60));
    }
}
