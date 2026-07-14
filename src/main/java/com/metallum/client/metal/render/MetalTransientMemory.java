package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBuffer.Usage;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBufferSlice.MappedView;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.util.TransientBlockAllocator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.util.Mth;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Environment(EnvType.CLIENT)
final class MetalTransientMemory implements TransientMemory {
    private static final long BLOCK_SIZE = 524288L;
    private static final long MAX_CPU_ALIGNMENT = 16L;
    private static final long MAX_GPU_ALIGNMENT = Long.highestOneBit(Long.MAX_VALUE);
    private static final int BLOCK_USAGE = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE;
    private static final int MAX_RETIRED_GPU_BLOCKS_PER_SIZE = 1;

    private final MetalDevice device;
    private final MetalCommandEncoder encoder;
    private final TransientBlockAllocator<Long> cpuBlockAllocator;
    private final TransientBlockAllocator<MetalGpuBuffer> gpuBlockAllocator;
    @Nullable
    private final MetalJavaWorkloadTelemetry workloadTelemetry;
    @Nullable
    private final Map<Long, Long> timedCpuBlockSizes;
    // freeGpuBlock runs only after the allocator's retirement action reaches
    // the GPU-safe destruction queue; retain one exact-size block at that point.
    private final DynamicBackingPool<MetalGpuBuffer> retiredGpuBlocks = new DynamicBackingPool<>(
            BLOCK_SIZE,
            MAX_RETIRED_GPU_BLOCKS_PER_SIZE,
            MetalGpuBuffer::close
    );
    private long submitIndex = 0L;
    private boolean closed;

    MetalTransientMemory(
            final MetalDevice device,
            final MetalCommandEncoder encoder,
            final @Nullable MetalJavaWorkloadTelemetry workloadTelemetry
    ) {
        this.device = device;
        this.encoder = encoder;
        this.workloadTelemetry = workloadTelemetry;
        if (workloadTelemetry == null) {
            this.timedCpuBlockSizes = null;
            this.cpuBlockAllocator = new TransientBlockAllocator<>(
                    BLOCK_SIZE,
                    MAX_CPU_ALIGNMENT,
                    TransientBlockAllocator.Allocator.create(MemoryUtil::nmemAlloc, MemoryUtil::nmemFree)
            );
            this.gpuBlockAllocator = new TransientBlockAllocator<>(
                    BLOCK_SIZE,
                    MAX_GPU_ALIGNMENT,
                    TransientBlockAllocator.Allocator.create(this::allocateGpuBlock, this::freeGpuBlock)
            );
        } else {
            this.timedCpuBlockSizes = new HashMap<>();
            this.cpuBlockAllocator = new TransientBlockAllocator<>(
                    BLOCK_SIZE,
                    MAX_CPU_ALIGNMENT,
                    TransientBlockAllocator.Allocator.create(this::allocateTimedCpuBlock, this::freeTimedCpuBlock),
                    this::recordTimedCpuBlockUse
            );
            this.gpuBlockAllocator = new TransientBlockAllocator<>(
                    BLOCK_SIZE,
                    MAX_GPU_ALIGNMENT,
                    TransientBlockAllocator.Allocator.create(this::allocateGpuBlock, this::freeGpuBlock),
                    block -> workloadTelemetry.recordGpuTransientReserved(block.allocationSize())
            );
        }
    }

    void rotate() {
        cpuBlockAllocator.rotate().run();
        encoder.queueForDestroy(gpuBlockAllocator.rotate());
        submitIndex++;
    }

    void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        cpuBlockAllocator.close();
        gpuBlockAllocator.close();
        retiredGpuBlocks.drain();
    }

    private MetalGpuBuffer allocateGpuBlock(final long size) {
        MetalGpuBuffer retired = retiredGpuBlocks.take(size);
        if (retired != null) {
            return retired;
        }
        return new MetalGpuBuffer(device, BLOCK_USAGE, size);
    }

    private long allocateTimedCpuBlock(final long size) {
        long address = MemoryUtil.nmemAlloc(size);
        Long previous = this.timedCpuBlockSizes.put(address, size);
        if (previous != null) {
            MemoryUtil.nmemFree(address);
            throw new IllegalStateException("CPU transient allocator reused a live backing address");
        }
        return address;
    }

    private void freeTimedCpuBlock(final long address) {
        Long removed = this.timedCpuBlockSizes.remove(address);
        MemoryUtil.nmemFree(address);
        if (removed == null) {
            throw new IllegalStateException("CPU transient allocator freed an unknown backing address");
        }
    }

    private void recordTimedCpuBlockUse(final long address) {
        Long reserved = this.timedCpuBlockSizes.get(address);
        if (reserved == null) {
            throw new IllegalStateException("CPU transient allocator used an unknown backing address");
        }
        this.workloadTelemetry.recordCpuTransientReserved(reserved);
    }

    private void freeGpuBlock(final MetalGpuBuffer block) {
        if (this.closed) {
            block.close();
            return;
        }
        retiredGpuBlocks.offer(block, block.size());
    }

    @Override
    public @NonNull ByteBuffer allocateCpu(final long size, final long alignment, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<Long> alloc = cpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        if (this.workloadTelemetry != null) {
            this.workloadTelemetry.recordCpuTransientRequested(size);
        }
        return MemoryUtil.memByteBuffer(alloc.block() + alloc.offset(), (int) alloc.size());
    }

    @Override
    public @NonNull MappedView allocateStaging(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice allocateGpu(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        if (this.workloadTelemetry != null) {
            this.workloadTelemetry.recordGpuTransientRequested(size);
        }
        return new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
    }

    @Override
    public @NonNull MappedView allocateGpuMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return allocateMapped(size, alignment, usage, minimumAllocation, elementSize);
    }

    private MappedView allocateMapped(final long size, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        TransientBlockAllocator.Allocation<MetalGpuBuffer> alloc = gpuBlockAllocator.allocate(size, alignment, minimumAllocation, elementSize);
        if (this.workloadTelemetry != null) {
            // The reservation is exact, but public mapped views do not reveal
            // how many bytes the external caller actually writes.
            this.workloadTelemetry.recordGpuTransientRequested(size);
        }
        GpuBufferSlice slice = new GpuBufferSlice(wrap(alloc.block(), usage), alloc.offset(), alloc.size());
        ByteBuffer hostView = alloc.block().sliceStorage(alloc.offset(), alloc.size());
        return new MappedView(slice, hostView, () -> {
        });
    }

    private MetalGpuBuffer wrap(final MetalGpuBuffer block, @Usage final int usage) {
        return new TransientGpuBuffer(device, block.nativeHandle(), usage, block.size(), this, submitIndex);
    }

    @Override
    public @NonNull GpuBufferSlice uploadStaging(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    @Override
    public @NonNull GpuBufferSlice uploadGpu(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        return upload(data, alignment, usage, minimumAllocation, elementSize);
    }

    private GpuBufferSlice upload(final List<ByteBuffer> data, final long alignment, @Usage final int usage, final long minimumAllocation, final long elementSize) {
        long totalSize = 0L;
        for (ByteBuffer buffer : data) {
            totalSize += buffer.remaining();
            totalSize = Mth.roundToward(totalSize, alignment);
        }

        GpuBufferSlice result;
        try (MappedView mapped = allocateMapped(totalSize, alignment, usage, minimumAllocation, elementSize)) {
            long mappedPtr = MemoryUtil.memAddress(mapped.data());
            long offset = 0L;
            for (ByteBuffer buffer : data) {
                long copyLength = Math.min(mapped.slice().length() - offset, buffer.remaining());
                MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), mappedPtr + offset, copyLength);
                if (this.workloadTelemetry != null) {
                    this.workloadTelemetry.recordCpuToShared(copyLength);
                }
                offset += buffer.remaining();
                offset = Mth.roundToward(offset, alignment);
                if (offset >= mapped.slice().length()) {
                    break;
                }
            }
            result = mapped.slice();
        }
        return result;
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadStaging(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        return multiUpload(data, alignment, usage);
    }

    @Override
    public @NonNull List<GpuBufferSlice> multiUploadGpu(final @NonNull List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        return multiUpload(data, alignment, usage);
    }

    private List<GpuBufferSlice> multiUpload(final List<ByteBuffer> data, final long alignment, @Usage final int usage) {
        ReferenceArrayList<GpuBufferSlice> uploaded = new ReferenceArrayList<>();
        uploaded.size(data.size());
        IntArrayList sortedIndices = IntArrayList.toList(IntStream.range(0, data.size()));
        sortedIndices.sort(IntComparator.comparingInt(index -> data.get(index).remaining()));

        while (!sortedIndices.isEmpty()) {
            boolean allocatedAnything = false;

            for (int i = sortedIndices.size() - 1; i >= 0; i--) {
                int bufferIndex = sortedIndices.getInt(i);
                ByteBuffer currentBuffer = data.get(bufferIndex);
                if (gpuBlockAllocator.canAllocateInCurrentBlock(currentBuffer.remaining(), alignment)) {
                    sortedIndices.removeInt(i);
                    try (MappedView view = allocateGpuMapped(currentBuffer.remaining(), alignment, usage)) {
                        int copyLength = currentBuffer.remaining();
                        MemoryUtil.memCopy(currentBuffer, view.data());
                        if (this.workloadTelemetry != null) {
                            this.workloadTelemetry.recordCpuToShared(copyLength);
                        }
                        uploaded.set(bufferIndex, view.slice());
                    }
                    allocatedAnything = true;
                    break;
                }
            }

            if (!allocatedAnything) {
                int bufferIndex = sortedIndices.popInt();
                ByteBuffer currentBuffer = data.get(bufferIndex);
                try (MappedView view = allocateGpuMapped(currentBuffer.remaining(), alignment, usage)) {
                    int copyLength = currentBuffer.remaining();
                    MemoryUtil.memCopy(currentBuffer, view.data());
                    if (this.workloadTelemetry != null) {
                        this.workloadTelemetry.recordCpuToShared(copyLength);
                    }
                    uploaded.set(bufferIndex, view.slice());
                }
            }
        }

        return uploaded;
    }

    private static final class TransientGpuBuffer extends MetalGpuBuffer {
        private final MetalTransientMemory owner;
        private final long bufferSubmitIndex;
        private boolean closed;

        TransientGpuBuffer(
                final MetalDevice device,
                final MemorySegment handle,
                @Usage final int usage,
                final long size,
                final MetalTransientMemory owner,
                final long submitIndex
        ) {
            super(device, usage, size, handle);
            this.owner = owner;
            this.bufferSubmitIndex = submitIndex;
        }

        @Override
        public boolean isClosed() {
            if (closed) {
                return true;
            }
            closed = bufferSubmitIndex < owner.submitIndex;
            return closed;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
            throw new IllegalStateException("Cannot map transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice(final long offset, final long length) {
            throw new IllegalStateException("Cannot slice transient buffer");
        }

        @Override
        public @NonNull GpuBufferSlice slice() {
            throw new IllegalStateException("Cannot slice transient buffer");
        }
    }
}
