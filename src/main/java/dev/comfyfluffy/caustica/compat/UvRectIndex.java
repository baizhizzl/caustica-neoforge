package dev.comfyfluffy.caustica.compat;

import java.util.ArrayList;
import java.util.List;

/** Immutable UV index with bounded subdivision and allocation-free, half-open rectangle queries. */
public final class UvRectIndex<T> {
    public record Entry<T>(float minU, float minV, float maxU, float maxV, T value) {
        private boolean valid() {
            return value != null && Float.isFinite(minU) && Float.isFinite(minV)
                    && Float.isFinite(maxU) && Float.isFinite(maxV)
                    && minU >= 0 && minV >= 0 && maxU <= 1 && maxV <= 1
                    && minU < maxU && minV < maxV;
        }
        private boolean contains(float u, float v) {
            return u >= minU && u < maxU && v >= minV && v < maxV;
        }
        private boolean covers(float loU, float loV, float hiU, float hiV) {
            return minU <= loU && minV <= loV && maxU >= hiU && maxV >= hiV;
        }
        private boolean sameBounds(Entry<?> other) {
            return minU == other.minU && minV == other.minV && maxU == other.maxU && maxV == other.maxV;
        }
    }

    private static final int MAX_DEPTH = 20;
    private final Node root;
    private final int size;
    private final int nodeCount;

    public UvRectIndex(List<Entry<T>> entries) {
        List<Entry<T>> valid = new ArrayList<>(entries.size());
        for (Entry<T> entry : entries) {
            if (entry != null && entry.valid()) valid.add(entry);
        }
        size = valid.size();
        Builder builder = new Builder((int) Math.min(1_000_000L, Math.max(64L, 8L * size)));
        root = new Node(0, 0, 1, 1, 0, builder);
        for (Entry<T> entry : valid) root.add(entry, builder);
        nodeCount = builder.count;
    }

    public int size() { return size; }
    public int nodeCount() { return nodeCount; }

    @SuppressWarnings("unchecked")
    public T find(float u, float v) {
        if (!Float.isFinite(u) || !Float.isFinite(v) || u < 0 || u >= 1 || v < 0 || v >= 1) return null;
        Object child = root;
        while (child instanceof Node node) {
            child = node.children[(u < node.midU ? 0 : 2) | (v < node.midV ? 0 : 1)];
        }
        if (child instanceof Entry<?> entry) {
            return entry.contains(u, v) ? (T) entry.value() : null;
        }
        if (child instanceof Bucket bucket) {
            for (Entry<?> entry : bucket.entries) {
                if (entry.contains(u, v)) return (T) entry.value();
            }
        }
        return null;
    }

    private static final class Builder {
        final int limit;
        int count;
        Builder(int limit) { this.limit = limit; }
    }

    private static final class Bucket {
        final ArrayList<Entry<?>> entries = new ArrayList<>(2);
        Bucket(Entry<?> first, Entry<?> second) { entries.add(first); entries.add(second); }
    }

    private static final class Node {
        final float minU, minV, maxU, maxV, midU, midV;
        final int depth;
        final Object[] children = new Object[4];

        Node(float minU, float minV, float maxU, float maxV, int depth, Builder builder) {
            this.minU = minU; this.minV = minV; this.maxU = maxU; this.maxV = maxV;
            this.midU = (minU + maxU) * .5f; this.midV = (minV + maxV) * .5f;
            this.depth = depth;
            builder.count++;
        }

        void add(Entry<?> entry, Builder builder) {
            boolean lowU = entry.minU() < midU, highU = entry.maxU() > midU;
            boolean lowV = entry.minV() < midV, highV = entry.maxV() > midV;
            if (lowU && lowV) addChild(0, entry, builder);
            if (lowU && highV) addChild(1, entry, builder);
            if (highU && lowV) addChild(2, entry, builder);
            if (highU && highV) addChild(3, entry, builder);
        }

        void addChild(int index, Entry<?> entry, Builder builder) {
            Object child = children[index];
            if (child == null) { children[index] = entry; return; }
            if (child instanceof Node node) { node.add(entry, builder); return; }
            if (child instanceof Bucket bucket) { bucket.entries.add(entry); return; }
            Entry<?> first = (Entry<?>) child;
            float loU = index < 2 ? minU : midU, hiU = index < 2 ? midU : maxU;
            float loV = (index & 1) == 0 ? minV : midV, hiV = (index & 1) == 0 ? midV : maxV;
            if (depth >= MAX_DEPTH || builder.count >= builder.limit || first.sameBounds(entry)
                    || (first.covers(loU, loV, hiU, hiV) && entry.covers(loU, loV, hiU, hiV))) {
                children[index] = new Bucket(first, entry);
                return;
            }
            Node node = new Node(loU, loV, hiU, hiV, depth + 1, builder);
            node.add(first, builder);
            node.add(entry, builder);
            children[index] = node;
        }
    }
}
