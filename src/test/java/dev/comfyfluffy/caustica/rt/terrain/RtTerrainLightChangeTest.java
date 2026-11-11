package dev.comfyfluffy.caustica.rt.terrain;

import dev.comfyfluffy.caustica.rt.accel.RtAccel;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionTable.SectionGeom;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.junit.jupiter.api.Test;

import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtTerrainLightChangeTest {
    @Test
    void treatsMissingAndEmptyLightListsAsEquivalent() {
        assertTrue(RtTerrain.sameLightRecords(null, null));
        assertTrue(RtTerrain.sameLightRecords(null, new float[0]));
        assertTrue(RtTerrain.sameLightRecords(new float[0], null));
    }

    @Test
    void acceptsBitIdenticalReextractionAndRejectsActualChanges() {
        float[] original = {1f, 2f, 3f, 4f};
        assertTrue(RtTerrain.sameLightRecords(original, original.clone()));
        assertFalse(RtTerrain.sameLightRecords(original, new float[]{1f, 2f, 3f, 5f}));
        assertFalse(RtTerrain.sameLightRecords(original, null));
        assertFalse(RtTerrain.sameLightRecords(null, original));
    }

    @Test
    void preEvictedLitSectionStillReleasesSlotAndRemovesPublishedLights() {
        RtSectionTable table = new RtSectionTable();
        Long2ObjectOpenHashMap<SectionGeom> resident = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet published = new LongOpenHashSet();
        TreeMap<Integer, RtLightHierarchy.SectionInput> lightSections = new TreeMap<>();

        long litKey = RtTerrain.sectionKey(3, 4, -2);
        long trailingKey = RtTerrain.sectionKey(4, 4, -2);
        SectionGeom lit = publishStub(table, resident, published, lightSections, litKey, 48, 64, -32,
                new float[RtLightCollector.FLOATS_PER_LIGHT]);
        SectionGeom trailing = publishStub(table, resident, published, lightSections, trailingKey, 64, 64, -32,
                new float[0]);
        int originalLitSlot = lit.slot;
        assertTrue(lightSections.containsKey(originalLitSlot));

        // Window eviction and ungrouped empty re-extracts remove from `resident` before queueing `removed`.
        resident.remove(litKey);

        int removedSlot = table.removePublished(resident, published, lit);
        boolean lightsChanged = removedSlot >= 0 && lightSections.remove(removedSlot) != null;

        assertEquals(originalLitSlot, removedSlot);
        assertTrue(lightsChanged);
        assertFalse(lightSections.containsKey(originalLitSlot));
        assertFalse(published.contains(litKey));
        assertNull(table.slots.get(originalLitSlot));
        assertEquals(1, table.instanceList.size());
        assertEquals(0, trailing.instanceIndex);
        assertEquals(-1, lit.slot);
        assertEquals(-1, lit.instanceIndex);
    }

    @Test
    void supersededGeometryDoesNotClobberReplacementSlotOrPublishedState() {
        RtSectionTable table = new RtSectionTable();
        Long2ObjectOpenHashMap<SectionGeom> resident = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet published = new LongOpenHashSet();
        TreeMap<Integer, RtLightHierarchy.SectionInput> lightSections = new TreeMap<>();

        long key = RtTerrain.sectionKey(0, 4, 0);
        SectionGeom oldGeom = publishStub(table, resident, published, lightSections, key, 0, 64, 0,
                new float[RtLightCollector.FLOATS_PER_LIGHT]);
        int sharedSlot = oldGeom.slot;

        // Simulate in-place replacement before a stale reference reaches removePublished.
        SectionGeom replacement = new SectionGeom(key, null, null, null, new int[4], 0, 64, 0,
                new float[RtLightCollector.FLOATS_PER_LIGHT]);
        replacement.slot = sharedSlot;
        replacement.instanceIndex = oldGeom.instanceIndex;
        table.slots.set(sharedSlot, replacement);
        resident.put(key, replacement);

        int removedSlot = table.removePublished(resident, published, oldGeom);

        assertEquals(-1, removedSlot);
        assertTrue(published.contains(key));
        assertSame(replacement, resident.get(key));
        assertSame(replacement, table.slots.get(sharedSlot));
        assertTrue(lightSections.containsKey(sharedSlot));
        assertEquals(1, table.instanceList.size());
    }

    private static SectionGeom publishStub(RtSectionTable table,
                                           Long2ObjectOpenHashMap<SectionGeom> resident,
                                           LongOpenHashSet published,
                                           TreeMap<Integer, RtLightHierarchy.SectionInput> lightSections,
                                           long key, int sx, int sy, int sz, float[] lights) {
        SectionGeom geom = new SectionGeom(key, null, null, null, new int[4], sx, sy, sz, lights);
        geom.slot = table.allocateSlot();
        geom.instanceIndex = table.instanceList.size();
        table.slots.set(geom.slot, geom);
        table.instanceList.add(new RtAccel.Instance(new float[]{1, 0, 0, sx, 0, 1, 0, sy, 0, 0, 1, sz}, 0L, geom.slot));
        resident.put(key, geom);
        published.add(key);
        if (lights != null && lights.length > 0) {
            lightSections.put(geom.slot, new RtLightHierarchy.SectionInput(
                    geom.slot, sx >> 4, sy >> 4, sz >> 4, lights));
        }
        return geom;
    }
}
