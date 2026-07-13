package com.metallum.client.metal.render;

import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Render-thread-confined, byte-bounded reuse pool for dynamic native buffers. */
final class DynamicBackingPool<T> {
    private final long maxBytes;
    private final int maxEntriesPerSize;
    private final Consumer<T> release;
    private final LinkedHashMap<Long, ArrayDeque<T>> buckets = new LinkedHashMap<>(16, 0.75f, true);
    private long pooledBytes;

    DynamicBackingPool(final long maxBytes, final int maxEntriesPerSize, final Consumer<T> release) {
        if (maxBytes < 0L) {
            throw new IllegalArgumentException("Dynamic backing pool byte limit cannot be negative");
        }
        if (maxEntriesPerSize <= 0) {
            throw new IllegalArgumentException("Dynamic backing pool bucket limit must be positive");
        }
        this.maxBytes = maxBytes;
        this.maxEntriesPerSize = maxEntriesPerSize;
        this.release = release;
    }

    @Nullable
    T take(final long size) {
        validateSize(size);
        ArrayDeque<T> bucket = this.buckets.get(size);
        if (bucket == null) {
            return null;
        }
        T value = bucket.pollFirst();
        if (value != null) {
            this.pooledBytes -= size;
        }
        if (bucket.isEmpty()) {
            this.buckets.remove(size);
        }
        return value;
    }

    void offer(final T value, final long size) {
        if (value == null) {
            return;
        }
        validateSize(size);
        if (size > this.maxBytes) {
            this.release.accept(value);
            return;
        }

        ArrayDeque<T> bucket = this.buckets.get(size);
        if (bucket != null && bucket.size() >= this.maxEntriesPerSize) {
            this.release.accept(value);
            return;
        }

        while (this.pooledBytes > this.maxBytes - size) {
            this.evictLeastRecentlyUsed();
        }
        if (bucket == null) {
            bucket = new ArrayDeque<>();
            this.buckets.put(size, bucket);
        }
        bucket.addFirst(value);
        this.pooledBytes += size;
    }

    void drain() {
        for (ArrayDeque<T> bucket : this.buckets.values()) {
            T value;
            while ((value = bucket.pollFirst()) != null) {
                this.release.accept(value);
            }
        }
        this.buckets.clear();
        this.pooledBytes = 0L;
    }

    long pooledBytes() {
        return this.pooledBytes;
    }

    int pooledEntries() {
        int entries = 0;
        for (ArrayDeque<T> bucket : this.buckets.values()) {
            entries += bucket.size();
        }
        return entries;
    }

    private void evictLeastRecentlyUsed() {
        var iterator = this.buckets.entrySet().iterator();
        if (!iterator.hasNext()) {
            throw new IllegalStateException("Dynamic backing pool accounting is inconsistent");
        }
        Map.Entry<Long, ArrayDeque<T>> entry = iterator.next();
        T evicted = entry.getValue().pollLast();
        if (evicted == null) {
            throw new IllegalStateException("Dynamic backing pool contains an empty bucket");
        }
        this.pooledBytes -= entry.getKey();
        if (entry.getValue().isEmpty()) {
            iterator.remove();
        }
        this.release.accept(evicted);
    }

    private static void validateSize(final long size) {
        if (size <= 0L) {
            throw new IllegalArgumentException("Dynamic backing size must be positive");
        }
    }
}
