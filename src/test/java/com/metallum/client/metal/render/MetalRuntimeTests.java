package com.metallum.client.metal.render;

import java.util.ArrayList;
import java.util.List;

public final class MetalRuntimeTests {
    private MetalRuntimeTests() {
    }

    public static void main(final String[] args) {
        testDestructionQueueDefersReentrantAdds();
        testDestructionQueueToleratesReentrantRotation();
        testDestructionQueueClose();
        testTexelViewCacheReuseAndInvalidation();
        testFenceTimeoutRounding();
    }

    private static void testDestructionQueueDefersReentrantAdds() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[2];
        queue.add(() -> {
            executions[0]++;
            queue.add(() -> executions[1]++);
        });

        queue.rotate();
        queue.rotate();
        require(executions[0] == 0, "destroy action ran before three rotations");
        queue.rotate();
        require(executions[0] == 1, "destroy action did not run after three rotations");
        require(executions[1] == 0, "reentrant destroy action ran in the same rotation");

        queue.rotate();
        queue.rotate();
        require(executions[1] == 0, "reentrant destroy action ran before its own delay");
        queue.rotate();
        require(executions[1] == 1, "reentrant destroy action did not preserve the queue delay");
        queue.close();
        require(executions[0] == 1 && executions[1] == 1, "destroy actions ran more than once");
    }

    private static void testDestructionQueueToleratesReentrantRotation() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[2];
        queue.add(() -> {
            executions[0]++;
            queue.rotate();
            queue.add(() -> executions[1]++);
        });

        queue.rotate();
        queue.rotate();
        queue.rotate();
        require(executions[0] == 1 && executions[1] == 0, "reentrant rotation callback mismatch");
        queue.rotate();
        queue.rotate();
        require(executions[1] == 0, "reentrant rotation aliased two queue slots");
        queue.rotate();
        require(executions[1] == 1, "reentrant rotation lost the deferred action");
        queue.close();
    }

    private static void testDestructionQueueClose() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[1];
        queue.add(() -> executions[0]++);
        queue.add(null);
        queue.close();
        require(executions[0] == 1, "close did not drain queued destruction exactly once");
    }

    private static void testTexelViewCacheReuseAndInvalidation() {
        List<String> released = new ArrayList<>();
        MetalGpuBuffer.TexelViewCache<String> cache = new MetalGpuBuffer.TexelViewCache<>(2, released::add);
        MetalGpuBuffer.TexelViewKey firstKey = new MetalGpuBuffer.TexelViewKey(70L, 0L, 16L, 64L);
        int[] creations = new int[1];

        require(!cache.isInitialized(), "texel view cache allocated storage eagerly");
        cache.drain();
        require(!cache.isInitialized(), "draining an unused texel view cache allocated storage");
        MetalGpuBuffer.TexelViewKey failedKey = new MetalGpuBuffer.TexelViewKey(0L, 0L, 1L, 4L);
        require(cache.getOrCreate(failedKey, ignored -> null) == null, "failed texel view creation returned a value");
        require(!cache.isInitialized(), "failed texel view creation initialized cache storage");

        String first = cache.getOrCreate(firstKey, ignored -> "view-" + ++creations[0]);
        require(cache.isInitialized(), "successful texel view creation did not initialize cache storage");
        String reused = cache.getOrCreate(firstKey, ignored -> "view-" + ++creations[0]);
        String differentRange = cache.getOrCreate(
                new MetalGpuBuffer.TexelViewKey(70L, 64L, 16L, 64L),
                ignored -> "view-" + ++creations[0]
        );
        require(first == reused, "identical texel view keys did not reuse the cached view");
        require(!first.equals(differentRange), "different texel ranges reused the same cached view");
        require(creations[0] == 2 && cache.size() == 2, "texel view cache creation count mismatch");

        cache.drain();
        require(cache.size() == 0, "texel view cache did not clear after backing invalidation");
        require(!cache.isInitialized(), "backing invalidation retained texel view cache storage");
        require(released.size() == 2 && released.contains(first) && released.contains(differentRange),
                "backing invalidation did not release every cached texel view");

        String afterInvalidation = cache.getOrCreate(firstKey, ignored -> "view-" + ++creations[0]);
        require(!first.equals(afterInvalidation), "backing invalidation reused a stale texel view");
        require(creations[0] == 3, "texel view was not recreated for the new backing");

        require(cache.getOrCreate(failedKey, ignored -> null) == null, "failed texel view creation returned a value");
        require(cache.getOrCreate(failedKey, ignored -> "retry") != null,
                "failed texel view creation was cached instead of allowing a retry");

        MetalGpuBuffer.TexelViewKey overflowKey = new MetalGpuBuffer.TexelViewKey(70L, 128L, 16L, 64L);
        cache.getOrCreate(overflowKey, ignored -> "overflow");
        require(cache.size() == 2, "texel view cache exceeded its configured bound");
        require(released.contains(afterInvalidation), "least-recently-used texel view was not released on eviction");
    }

    private static void testFenceTimeoutRounding() {
        require(MetalFence.timeoutMillis(-1L) == 0L, "negative timeout must remain non-blocking");
        require(MetalFence.timeoutMillis(0L) == 0L, "zero timeout must remain non-blocking");
        require(MetalFence.timeoutMillis(1L) == 1L, "positive sub-millisecond timeout rounded down");
        require(MetalFence.timeoutMillis(999_999L) == 1L, "sub-millisecond timeout rounded incorrectly");
        require(MetalFence.timeoutMillis(1_000_000L) == 1L, "whole millisecond changed");
        require(MetalFence.timeoutMillis(1_000_001L) == 2L, "fractional millisecond did not round up");
        require(
                MetalFence.timeoutMillis(Long.MAX_VALUE) == 9_223_372_036_855L,
                "maximum timeout overflowed"
        );
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
