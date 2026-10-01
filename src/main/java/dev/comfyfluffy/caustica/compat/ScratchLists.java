package dev.comfyfluffy.caustica.compat;

import java.util.ArrayList;

/** Caller-owned, reentrant list storage. Every acquisition must be released in a finally block. */
public final class ScratchLists<T> {
    private final ArrayList<ArrayList<T>> slots = new ArrayList<>(2);
    private final int initialCapacity;
    private final int retentionLimit;
    private int depth;

    public ScratchLists(int initialCapacity, int retentionLimit) {
        if (initialCapacity < 0 || retentionLimit < initialCapacity) {
            throw new IllegalArgumentException("Invalid scratch-list capacity");
        }
        this.initialCapacity = initialCapacity;
        this.retentionLimit = retentionLimit;
    }

    public ArrayList<T> acquire() {
        if (depth == slots.size()) {
            slots.add(new ArrayList<>(initialCapacity));
        }
        return slots.get(depth++);
    }

    public void release() {
        if (depth == 0) {
            throw new IllegalStateException("No scratch list is acquired");
        }
        ArrayList<T> list = slots.get(--depth);
        int size = list.size();
        list.clear();
        if (size > retentionLimit) {
            slots.set(depth, new ArrayList<>(initialCapacity));
        }
    }
}
