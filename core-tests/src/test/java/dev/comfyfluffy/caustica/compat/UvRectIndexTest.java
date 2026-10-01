package dev.comfyfluffy.caustica.compat;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class UvRectIndexTest {
    @Test void uncoveredAtlasPaddingReturnsNull() {
        var index = new UvRectIndex<>(List.of(new UvRectIndex.Entry<>(.1f, .1f, .2f, .2f, "tile")));
        assertEquals("tile", index.find(.15f, .15f));
        assertNull(index.find(.4f, .4f)); assertNull(index.find(.2f, .15f));
    }
    @Test void sharedEdgesAreHalfOpen() {
        var index = new UvRectIndex<>(List.of(new UvRectIndex.Entry<>(0, 0, .5f, 1, "left"), new UvRectIndex.Entry<>(.5f, 0, 1, 1, "right")));
        assertEquals("right", index.find(.5f, .5f));
        assertEquals("left", index.find(Math.nextDown(.5f), .5f));
        assertNull(index.find(1, .5f)); assertNull(index.find(.5f, 1));
    }
    @Test void invalidInputsAndBoundsAreRejected() {
        var entries = new ArrayList<UvRectIndex.Entry<String>>();
        entries.add(new UvRectIndex.Entry<>(0, 0, 1, 1, "valid"));
        entries.add(new UvRectIndex.Entry<>(-1, 0, 1, 1, "invalid"));
        entries.add(new UvRectIndex.Entry<>(0, 0, Float.NaN, 1, "invalid"));
        entries.add(new UvRectIndex.Entry<>(0, 0, Float.POSITIVE_INFINITY, 1, "invalid"));
        entries.add(new UvRectIndex.Entry<>(.5f, 0, .5f, 1, "invalid"));
        var index = new UvRectIndex<>(entries); assertEquals(1, index.size());
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -1, 1}) {
            assertNull(index.find(value, .5f)); assertNull(index.find(.5f, value));
        }
    }
    @Test void duplicateBoundsNeverRecursivelyExplode() {
        var entries = new ArrayList<UvRectIndex.Entry<Integer>>();
        for (int i = 0; i < 10000; i++) entries.add(new UvRectIndex.Entry<>(0, 0, 1, 1, i));
        var index = new UvRectIndex<>(entries);
        assertEquals(0, index.find(.25f, .25f)); assertEquals(1, index.nodeCount());
    }
    @Test void tinySpritesHaveBoundedSubdivision() {
        float tiny = Float.MIN_NORMAL;
        var index = new UvRectIndex<>(List.of(new UvRectIndex.Entry<>(0, 0, tiny, tiny, "a"), new UvRectIndex.Entry<>(tiny, tiny, tiny * 2, tiny * 2, "b")));
        assertEquals("a", index.find(0, 0)); assertEquals("b", index.find(tiny, tiny));
        assertTrue(index.nodeCount() <= 21);
    }
    @Test void randomizedGridMatchesLinearReference() {
        var entries = new ArrayList<UvRectIndex.Entry<Integer>>();
        int width = 32;
        for (int y = 0; y < width; y++) for (int x = 0; x < width; x++) {
            entries.add(new UvRectIndex.Entry<>((x + .05f) / width, (y + .05f) / width, (x + .95f) / width, (y + .95f) / width, y * width + x));
        }
        var index = new UvRectIndex<>(entries); var random = new Random(263);
        for (int i = 0; i < 20000; i++) {
            float u = random.nextFloat(), v = random.nextFloat(); Integer expected = null;
            for (var entry : entries) {
                if (u >= entry.minU() && u < entry.maxU() && v >= entry.minV() && v < entry.maxV()) { expected = entry.value(); break; }
            }
            assertEquals(expected, index.find(u, v));
        }
        assertTrue(index.nodeCount() <= width * width * 8);
    }
    @Test void emptyIndexReturnsNull() { assertNull(new UvRectIndex<String>(List.of()).find(.5f, .5f)); }
}
