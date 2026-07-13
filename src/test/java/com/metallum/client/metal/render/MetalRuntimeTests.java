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
        testDestructionQueueClose();
        testTexelViewCacheReuseAndInvalidation();
        testTextureBindingHolderUpdatesInPlace();
        testDynamicBackingPoolBoundsAndReuse();
        testPartialDynamicWritePreservation();
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
