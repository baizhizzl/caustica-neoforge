package dev.comfyfluffy.caustica.rt.accel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class RtTlasCacheTest {
    private static RtAccel.Instance instance(long address, int index, int mask, int sbt) {
        return new RtAccel.Instance(new float[] {1, 0, 0, 2, 0, 1, 0, 3, 0, 0, 1, 4}, address, index, mask, sbt);
    }

    @Test
    void coldSlotsNeedAnUploadAndABuildEvenForAnEmptyScene() {
        RtTlasCache cache = new RtTlasCache();
        Object source = new Object();
        assertTrue(cache.staticRangeChanged(source, 0, 0));
        assertTrue(cache.needsBuild(source, 0, 0, List.of(), 0));
        cache.staticRangeWritten(source, 0, 0);
        assertFalse(cache.staticRangeChanged(source, 0, 0));
        assertTrue(cache.needsBuild(source, 0, 0, List.of(), 0));
        cache.built(source, 0, 0, List.of(), 0);
        assertFalse(cache.needsBuild(source, 0, 0, List.of(), 0));
    }

    @Test
    void everyRingSlotCachesItsOwnTerrainPublication() {
        Object source = new Object();
        for (int slot = 0; slot < 4; slot++) {
            RtTlasCache cache = new RtTlasCache();
            cache.staticRangeWritten(source, 12, 42);
            cache.built(source, 12, 42, List.of(), 0);
            assertFalse(cache.staticRangeChanged(source, 12, 42));
            assertFalse(cache.needsBuild(source, 12, 42, List.of(), 0));
            assertTrue(cache.staticRangeChanged(source, 13, 42));
            assertTrue(cache.needsBuild(source, 13, 42, List.of(), 0));
        }
    }

    @Test
    void residencyCountAndWorldIdentityInvalidateTheStaticRange() {
        Object source = new Object();
        RtTlasCache cache = new RtTlasCache();
        cache.staticRangeWritten(source, 9, 2);
        cache.built(source, 9, 2, List.of(), 0);
        assertTrue(cache.staticRangeChanged(source, 9, 1));
        assertTrue(cache.needsBuild(source, 9, 1, List.of(), 0));
        assertTrue(cache.staticRangeChanged(new Object(), 9, 2));
        assertTrue(cache.needsBuild(new Object(), 9, 2, List.of(), 0));
    }

    @Test
    void freshDynamicObjectsWithIdenticalNativeInputsCanReuseTheTlas() {
        RtTlasCache cache = new RtTlasCache();
        Object source = new Object();
        cache.built(source, 7, 4, List.of(instance(100, 2, 255, 4)), 0);
        assertFalse(cache.needsBuild(source, 7, 4, List.of(instance(100, 2, 255, 4)), 0));
    }

    @Test
    void dynamicAddressMetadataOrderAndCountsInvalidate() {
        RtTlasCache cache = new RtTlasCache();
        Object source = new Object();
        var a = instance(100, 2, 255, 4);
        var b = instance(200, 3, 255, 4);
        cache.built(source, 7, 4, List.of(a, b), 0);
        assertTrue(cache.needsBuild(source, 7, 4, List.of(b, a), 0));
        assertTrue(cache.needsBuild(source, 7, 4, List.of(a), 0));
        for (var changed : List.of(instance(101, 2, 255, 4), instance(100, 9, 255, 4),
                instance(100, 2, 127, 4), instance(100, 2, 255, 8))) {
            assertTrue(cache.needsBuild(source, 7, 4, List.of(changed, b), 0));
        }
    }

    @Test
    void snapshotsDoNotAliasMutableTransformArrays() {
        RtTlasCache cache = new RtTlasCache();
        Object source = new Object();
        var a = instance(100, 2, 255, 4);
        cache.built(source, 7, 4, List.of(a), 0);
        a.transform3x4()[3] = 8;
        assertTrue(cache.needsBuild(source, 7, 4, List.of(a), 0));
        a.transform3x4()[3] = 2;
        assertFalse(cache.needsBuild(source, 7, 4, List.of(a), 0));
        a.transform3x4()[1] = -0.0f;
        assertTrue(cache.needsBuild(source, 7, 4, List.of(a), 0));
    }

    @Test
    void aBlasRefitInAnInterveningFrameInvalidatesEveryCachedSlot() {
        Object source = new Object();
        var dynamic = List.of(instance(100, 2, 255, 4));
        RtTlasCache[] slots = {new RtTlasCache(), new RtTlasCache(), new RtTlasCache(), new RtTlasCache()};
        for (var slot : slots) slot.built(source, 1, 10, dynamic, 50);
        // The address and instance transform are unchanged, but the referenced AS bounds were refitted.
        for (var slot : slots) assertTrue(slot.needsBuild(source, 1, 10, dynamic, 51));
        slots[0].built(source, 1, 10, dynamic, 51);
        assertFalse(slots[0].needsBuild(source, 1, 10, dynamic, 51));
        assertTrue(slots[1].needsBuild(source, 1, 10, dynamic, 51));
    }

    @Test
    void instanceStorageGrowthRetainsExactEqualityAndHandlesRemoval() {
        Object source = new Object();
        RtTlasCache cache = new RtTlasCache();
        var instances = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> instance(100 + i, i, 255, 4)).toList();
        cache.built(source, 1, 0, instances, 0);
        assertFalse(cache.needsBuild(source, 1, 0, instances, 0));
        cache.built(source, 1, 0, List.of(), 0);
        assertFalse(cache.needsBuild(source, 1, 0, List.of(), 0));
        assertTrue(cache.needsBuild(source, 1, 0, instances, 0));
    }
}
