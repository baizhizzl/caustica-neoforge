package dev.comfyfluffy.caustica.compat;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Measure escaped list storage; the real renderer also spends time resolving and emitting geometry. */
public final class ModelPartsAllocationBenchmark {
    private static final int OPERATIONS = 1_000_000;
    private static final Object PART = new Object();
    private static volatile Object consumed;

    private ModelPartsAllocationBenchmark() {}

    public static void main(String[] args) {
        if (!(ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) {
            throw new IllegalStateException("This JVM does not support per-thread allocation measurement");
        }
        bean.setThreadAllocatedMemoryEnabled(true);
        ScratchLists<Object> scratch = new ScratchLists<>(4, 256);
        run(false, scratch, OPERATIONS);
        run(true, scratch, OPERATIONS);
        System.out.println("Mode, blocks, allocated bytes/block, elapsed ns/block");
        for (int i = 0; i < 3; i++) {
            measure(bean, false, scratch);
            measure(bean, true, scratch);
        }
    }

    private static void measure(ThreadMXBean bean, boolean reuse, ScratchLists<Object> scratch) {
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        long start = System.nanoTime();
        run(reuse, scratch, OPERATIONS);
        long elapsed = System.nanoTime() - start;
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        System.out.printf(Locale.ROOT, "%s, %d, %.2f, %.2f%n",
                reuse ? "worker-owned scratch" : "fresh ArrayList", OPERATIONS,
                (double) allocated / OPERATIONS, (double) elapsed / OPERATIONS);
    }

    private static void run(boolean reuse, ScratchLists<Object> scratch, int count) {
        for (int i = 0; i < count; i++) {
            List<Object> parts = reuse ? scratch.acquire() : new ArrayList<>();
            try {
                parts.add(PART);
                parts.add(PART);
                parts.add(PART);
                parts.add(PART);
                // Escape both variants equally so the JIT cannot eliminate the old storage allocation.
                consumed = parts;
            } finally {
                if (reuse) {
                    scratch.release();
                } else {
                    parts.clear();
                }
            }
        }
    }
}
