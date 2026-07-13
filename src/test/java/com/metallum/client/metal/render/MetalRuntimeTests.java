package com.metallum.client.metal.render;

import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

public final class MetalRuntimeTests {
    private MetalRuntimeTests() {
    }

    public static void main(final String[] args) {
        testDestructionQueueDefersReentrantAdds();
        testDestructionQueueToleratesReentrantRotation();
        testDestructionQueueSpreadsBurst();
        testDestructionQueueClose();
        testTexelViewCacheReuseAndInvalidation();
        testTextureBindingHolderUpdatesInPlace();
        testDynamicBackingPoolBoundsAndReuse();
        testPartialDynamicWritePreservation();
        testFenceTimeoutRounding();
        testEdrRefreshThrottle();
        testGpuTimingDetailGate();
        testGpuTimingStageAbi();
        testPendingUiSeedConsumeOnceLifecycle();
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

    private static void testDestructionQueueSpreadsBurst() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3, 2);
        int[] executions = new int[1];
        for (int index = 0; index < 5; index++) {
            queue.add(() -> executions[0]++);
        }

        queue.rotate();
        queue.rotate();
        require(executions[0] == 0, "destruction burst ran before the GPU-safe delay");
        queue.rotate();
        require(executions[0] == 2, "destruction burst ignored the per-frame drain budget");
        queue.rotate();
        require(executions[0] == 4, "destruction backlog did not continue on the next frame");
        queue.close();
        require(executions[0] == 5, "close did not drain the remaining destruction backlog");
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

    private static void testEdrRefreshThrottle() {
        long interval = MetalSurface.EDR_REFRESH_INTERVAL_NANOS;
        require(MetalSurface.shouldRefreshEdrCapabilities(Long.MIN_VALUE, 5L),
                "initial EDR capability query was throttled");
        require(!MetalSurface.shouldRefreshEdrCapabilities(1_000L, 1_000L + interval - 1L),
                "EDR capability query ignored the refresh interval");
        require(MetalSurface.shouldRefreshEdrCapabilities(1_000L, 1_000L + interval),
                "EDR capability query did not run at the refresh boundary");
        require(MetalSurface.shouldRefreshEdrCapabilities(10_000L, 9_000L),
                "EDR capability query did not recover from a backwards clock sample");
    }

    private static void testGpuTimingStageAbi() {
        MetalGpuTimingStage[] stages = {
                MetalGpuTimingStage.WORLD_OPAQUE,
                MetalGpuTimingStage.TRANSLUCENT,
                MetalGpuTimingStage.ENTITIES,
                MetalGpuTimingStage.HDR_EXTRACT,
                MetalGpuTimingStage.HISTOGRAM_EXPOSURE,
                MetalGpuTimingStage.BLOOM_HORIZONTAL,
                MetalGpuTimingStage.BLOOM_VERTICAL,
                MetalGpuTimingStage.HDR_RECONSTRUCTION,
                MetalGpuTimingStage.METAL_FX,
                MetalGpuTimingStage.UI_SEED,
                MetalGpuTimingStage.UI,
                MetalGpuTimingStage.PRESENT
        };
        require(stages.length == MetalGpuTimingStage.PROFILED_STAGE_COUNT,
                "GPU timing stage count does not match the native ABI");
        for (int index = 0; index < stages.length; index++) {
            require(stages[index].nativeId() == index,
                    "GPU timing stage native ID mismatch at index " + index);
        }
        require(MetalGpuTimingStage.NONE.nativeId() == -1,
                "GPU timing NONE sentinel does not match the native ABI");
    }

    private static void testGpuTimingDetailGate() {
        require(!MetalGpuTiming.detailEnabled(null, null),
                "GPU timing detail was enabled without timing flags");
        require(!MetalGpuTiming.detailEnabled("1", null),
                "production-equivalent GPU timing unexpectedly enabled stage markers");
        require(!MetalGpuTiming.detailEnabled(null, "1"),
                "GPU timing detail flag bypassed the primary timing gate");
        require(!MetalGpuTiming.detailEnabled("0", "1"),
                "disabled GPU timing unexpectedly enabled stage markers");
        require(MetalGpuTiming.detailEnabled("1", "1"),
                "explicit GPU timing detail did not enable stage markers");
    }

    private static void testPendingUiSeedConsumeOnceLifecycle() {
        MetalCommandEncoder.PendingUiSeedState<Object> pending =
                new MetalCommandEncoder.PendingUiSeedState<>();
        Object exactDestination = new Object();
        Object mismatchedDestination = new Object();

        pending.arm(exactDestination);
        require(pending.isPending() && pending.peek() == exactDestination,
                "deferred UI seed did not arm");
        require(!pending.consume(mismatchedDestination) && pending.isPending(),
                "mismatched render target consumed the deferred UI seed");
        require(pending.consume(exactDestination) && !pending.isPending(),
                "exact render target did not consume the deferred UI seed");
        require(!pending.consume(exactDestination),
                "deferred UI seed was consumed more than once");

        pending.arm(exactDestination);
        boolean rejectedRearm = false;
        try {
            pending.arm(mismatchedDestination);
        } catch (IllegalStateException expected) {
            rejectedRearm = true;
        }
        require(rejectedRearm && pending.peek() == exactDestination,
                "pending UI seed re-arm replaced unresolved state");
        require(pending.consume(exactDestination) && !pending.isPending(),
                "submit/read materialization did not resolve pending state exactly once");

        require(MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        false, false, 7L, 7L
                ), "exact pending UI destination was not eligible for fusion");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        false, true, 3024, 1964, 3024, 1964,
                        false, false, 7L, 7L
                ), "mismatched UI destination was eligible for fusion");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        false, false, 7L, 8L
                ), "stale-submit UI seed was eligible for fusion");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        true, false, 7L, 7L
                ), "explicit color clear was incorrectly fused after the UI seed");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        false, true, 7L, 7L
                ), "semantic MRT pass was incorrectly fused with the UI seed");
    }

    private static void testTextureBindingHolderUpdatesInPlace() {
        FakeTextureView firstView = new FakeTextureView();
        FakeTextureView secondView = new FakeTextureView();
        FakeSampler firstSampler = new FakeSampler();
        FakeSampler secondSampler = new FakeSampler();
        Map<String, MetalRenderPass.TextureViewAndSampler> bindings = new HashMap<>();
        MetalRenderPass.TextureViewAndSampler originalBinding = MetalRenderPass.updateTextureBinding(
                bindings, "Sampler0", firstView, firstSampler
        );

        MetalRenderPass.TextureViewAndSampler rebound = MetalRenderPass.updateTextureBinding(
                bindings, "Sampler0", secondView, secondSampler
        );

        require(rebound == originalBinding, "texture binding holder was replaced instead of updated");
        require(bindings.size() == 1 && bindings.get("Sampler0") == originalBinding,
                "texture binding map did not retain the original holder");
        require(rebound.textureView() == secondView, "texture binding holder retained the previous texture view");
        require(rebound.sampler() == secondSampler, "texture binding holder retained the previous sampler");
    }

    private static void testDynamicBackingPoolBoundsAndReuse() {
        List<String> released = new ArrayList<>();
        DynamicBackingPool<String> pool = new DynamicBackingPool<>(16L, 2, released::add);

        pool.offer("four-a", 4L);
        pool.offer("four-b", 4L);
        pool.offer("four-overflow", 4L);
        require(released.equals(List.of("four-overflow")), "per-size backing limit did not release overflow");
        require(pool.pooledEntries() == 2 && pool.pooledBytes() == 8L, "pooled backing accounting mismatch");

        require("four-b".equals(pool.take(4L)), "dynamic backing pool did not reuse the newest exact-size entry");
        require(pool.pooledEntries() == 1 && pool.pooledBytes() == 4L, "take did not update pool accounting");

        pool.offer("sixteen", 16L);
        require(released.contains("four-a"), "byte budget did not evict the least-recently-used bucket");
        require(pool.pooledEntries() == 1 && pool.pooledBytes() == 16L, "byte-bounded pool retained excess entries");

        pool.offer("oversized", 32L);
        require(released.contains("oversized"), "oversized backing was retained");
        pool.drain();
        require(released.contains("sixteen"), "drain did not release retained backing");
        require(pool.pooledEntries() == 0 && pool.pooledBytes() == 0L, "drain did not reset pool accounting");
    }

    private static void testPartialDynamicWritePreservation() {
        java.nio.ByteBuffer previous = java.nio.ByteBuffer.allocate(16);
        java.nio.ByteBuffer fresh = java.nio.ByteBuffer.allocate(16);
        for (int index = 0; index < 16; index++) {
            previous.put(index, (byte) index);
            fresh.put(index, (byte) -1);
        }

        MetalCommandEncoder.copyPreservedDynamicRanges(previous, fresh, 4L, 4, 16L);
        for (int index = 0; index < 16; index++) {
            byte expected = index >= 4 && index < 8 ? (byte) -1 : (byte) index;
            require(fresh.get(index) == expected, "partial dynamic write preserved the wrong byte at " + index);
        }

        java.nio.ByteBuffer fullWrite = java.nio.ByteBuffer.allocate(16);
        for (int index = 0; index < 16; index++) {
            fullWrite.put(index, (byte) -1);
        }
        MetalCommandEncoder.copyPreservedDynamicRanges(previous, fullWrite, 0L, 16, 16L);
        for (int index = 0; index < 16; index++) {
            require(fullWrite.get(index) == (byte) -1, "full dynamic write copied obsolete contents");
        }
    }

    private static final class FakeTextureView extends GpuTextureView {
        private FakeTextureView() {
            super(null, 0, 1);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }

    private static final class FakeSampler extends GpuSampler {
        @Override
        public AddressMode getAddressModeU() {
            return AddressMode.CLAMP_TO_EDGE;
        }

        @Override
        public AddressMode getAddressModeV() {
            return AddressMode.CLAMP_TO_EDGE;
        }

        @Override
        public FilterMode getMinFilter() {
            return FilterMode.NEAREST;
        }

        @Override
        public FilterMode getMagFilter() {
            return FilterMode.NEAREST;
        }

        @Override
        public int getMaxAnisotropy() {
            return 1;
        }

        @Override
        public OptionalDouble getMaxLod() {
            return OptionalDouble.empty();
        }

        @Override
        public void close() {
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
