package dev.comfyfluffy.caustica.compat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScratchListsTest {
    @Test void reuseAcrossAFullSection() {
        var scratch = new ScratchLists<Object>(4, 256);
        var first = scratch.acquire(); scratch.release();
        for (int block = 0; block < 4096; block++) {
            var list = scratch.acquire();
            try { assertSame(first, list); assertTrue(list.isEmpty()); list.add(this); }
            finally { scratch.release(); }
        }
        assertTrue(first.isEmpty());
    }
    @Test void nestedCallbacksHaveSeparateStorage() {
        var scratch = new ScratchLists<String>(2, 4);
        var outer = scratch.acquire(); outer.add("outer");
        var inner = scratch.acquire(); inner.add("inner");
        assertNotSame(outer, inner);
        scratch.release(); assertEquals("outer", outer.getFirst());
        scratch.release(); assertTrue(outer.isEmpty()); assertTrue(inner.isEmpty());
    }
    @Test void exceptionsDoNotRetainParts() {
        var scratch = new ScratchLists<String>(2, 4);
        assertThrows(IllegalArgumentException.class, () -> {
            var list = scratch.acquire();
            try { list.add("part"); throw new IllegalArgumentException(); }
            finally { scratch.release(); }
        });
        assertTrue(scratch.acquire().isEmpty()); scratch.release();
    }
    @Test void oversizedStorageIsNotRetained() {
        var scratch = new ScratchLists<Integer>(2, 4);
        var large = scratch.acquire(); for (int i = 0; i < 1000; i++) large.add(i);
        scratch.release(); assertTrue(large.isEmpty());
        var next = scratch.acquire(); assertNotSame(large, next); scratch.release();
    }
    @Test void releaseRequiresAcquisition() {
        assertThrows(IllegalStateException.class, () -> new ScratchLists<>(0, 0).release());
        assertThrows(IllegalArgumentException.class, () -> new ScratchLists<>(4, 2));
    }
}
