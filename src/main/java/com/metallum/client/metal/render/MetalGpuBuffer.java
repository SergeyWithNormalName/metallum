package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLHazardTrackingMode;
import com.metallum.client.metal.render.mtl.MTLResourceOptions;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@Environment(EnvType.CLIENT)
class MetalGpuBuffer extends GpuBuffer {
    // Keep hot descriptor ranges without retaining an unbounded number of
    // native views when callers bind many transient slices of one buffer.
    private static final int MAX_CACHED_TEXEL_VIEWS = 64;
    private static final int PRIVATE_GEOMETRY_HEAP_USAGE = GpuBuffer.USAGE_COPY_DST
            | GpuBuffer.USAGE_COPY_SRC
            | GpuBuffer.USAGE_VERTEX
            | GpuBuffer.USAGE_INDEX;
    private static final boolean PRIVATE_GEOMETRY_HEAPS_ENABLED = staticGeometryHeapsEnabled(
            System.getenv("METALLUM_STATIC_GEOMETRY_HEAPS")
    );
    private final MetalDevice device;
    private final boolean cpuAccessible;
    private final boolean dynamic;
    private final long resourceOptions;
    private final long allocationSize;
    private final boolean staticGeometryAllocation;
    private final NativeHandleState nativeHandleState;
    @Nullable
    private ByteBuffer storage;
    private final TexelViewCache<MemorySegment> texelViews;
    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size) {
        this(device, usage, size, false);
    }

    MetalGpuBuffer(
            final MetalDevice device,
            @GpuBuffer.Usage final int usage,
            final long size,
            final boolean usePrivateGeometryHeap
    ) {
        super(usage, size);
        this.device = device;
        this.texelViews = new TexelViewCache<>(MAX_CACHED_TEXEL_VIEWS, device::queueResourceRelease);

        this.dynamic = isDynamic(usage);
        this.cpuAccessible = isCpuAccessible(usage) || this.dynamic;
        this.resourceOptions = toMtlResourceOptions(usage);
        this.allocationSize = (size + 15L) & ~15L;
        this.staticGeometryAllocation = usePrivateGeometryHeap;
        MemorySegment nativeHandle = usePrivateGeometryHeap
                ? MetalNativeBridge.metallum_create_static_geometry_buffer(
                        device.metalDeviceHandle(),
                        this.allocationSize
                )
                : MetalNativeBridge.metallum_create_buffer(
                        device.metalDeviceHandle(),
                        this.allocationSize,
                        this.resourceOptions
                );
        if (MetalNativeBridge.isNullHandle(nativeHandle)) {
            throw new IllegalStateException("Failed to create Metal buffer");
        }
        this.nativeHandleState = new NativeHandleState(nativeHandle);

        if (this.cpuAccessible) {
            MemorySegment contents = MetalNativeBridge.metallum_get_buffer_contents(nativeHandle);
            if (MetalNativeBridge.isNullHandle(contents)) {
                MetalNativeBridge.metallum_release_object(nativeHandle);
                this.nativeHandleState.markReleased(nativeHandle);
                throw new IllegalStateException("MTLBuffer.contents returned null");
            }

            this.storage = MetalNativeBridge.nativeByteBufferView(contents, this.allocationSize).order(ByteOrder.nativeOrder());
        } else {
            this.storage = null;
        }
    }

    MetalGpuBuffer(final MetalDevice device, @GpuBuffer.Usage final int usage, final long size, final @Nullable MemorySegment wrappedHandle) {
        super(usage, size);
        this.device = device;
        this.texelViews = new TexelViewCache<>(MAX_CACHED_TEXEL_VIEWS, device::queueResourceRelease);
        this.cpuAccessible = false;
        this.dynamic = false;
        this.resourceOptions = 0L;
        this.allocationSize = size;
        this.staticGeometryAllocation = false;
        this.nativeHandleState = new NativeHandleState(wrappedHandle);
        this.storage = null;
    }

    ByteBuffer sliceStorage(final long offset, final long length) {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }

        ByteBuffer duplicate = this.storage.duplicate().order(this.storage.order());
        duplicate.position(Math.toIntExact(offset));
        duplicate.limit(Math.toIntExact(offset + length));
        return duplicate.slice().order(this.storage.order());
    }

    MemorySegment nativeHandle() {
        return this.nativeHandleState.requireForEncoding();
    }

    boolean isDynamic() {
        return this.dynamic;
    }

    long allocationSize() {
        return this.allocationSize;
    }

    long resourceOptions() {
        return this.resourceOptions;
    }

    ByteBuffer currentStorage() {
        if (this.storage == null) {
            throw new IllegalStateException("Buffer is not CPU-accessible");
        }
        return this.storage.duplicate().order(this.storage.order());
    }

    @Nullable
    MemorySegment cpuVisibleSliceForEncoding(final long offset, final long length) {
        ByteBuffer current = this.storage;
        if (current == null) {
            return null;
        }
        return cpuVisibleSlice(current, offset, length);
    }

    static MemorySegment cpuVisibleSlice(final ByteBuffer storage, final long offset, final long length) {
        return MemorySegment.ofBuffer(storage).asSlice(offset, length);
    }

    void swapBacking(final MemorySegment handle, final ByteBuffer storage) {
        // A texture view retains its backing. Queue all old views before the
        // caller queues that backing for reuse by the dynamic buffer pool.
        this.releaseTexelViews();
        this.nativeHandleState.replace(handle);
        this.storage = storage;
    }

    MemorySegment texelTextureView(
            final long pixelFormat,
            final long offset,
            final long texelCount,
            final long byteLength
    ) {
        if (this.nativeHandleState.isClosed()) {
            throw new IllegalStateException("Cannot create a texel view for a closed Metal buffer");
        }
        TexelViewKey key = new TexelViewKey(pixelFormat, offset, texelCount, byteLength);
        MemorySegment view = this.texelViews.getOrCreate(key, ignored -> {
            MemorySegment created = MetalNativeBridge.metallum_create_buffer_texture_view(
                    this.nativeHandle(),
                    pixelFormat,
                    offset,
                    texelCount,
                    1L,
                    byteLength
            );
            return MetalNativeBridge.isNullHandle(created) ? null : created;
        });
        if (view == null) {
            throw new IllegalStateException("Failed to create Metal texel buffer texture");
        }
        return view;
    }

    @Override
    public boolean isClosed() {
        return this.nativeHandleState.isClosed();
    }

    @Override
    public void close() {
        MemorySegment handle = this.nativeHandleState.beginClose();
        if (handle == null) {
            return;
        }
        this.storage = null;
        this.releaseTexelViews();
        Runnable markReleased = () -> this.nativeHandleState.markReleased(handle);
        if (this.staticGeometryAllocation) {
            this.device.queueStaticGeometryBufferRelease(handle, markReleased);
        } else {
            this.device.queueResourceRelease(handle, markReleased);
        }
    }

    @Override
    public GpuBufferSlice.@NonNull MappedView map(final long offset, final long length, final boolean read, final boolean write) {
        if (this.isClosed()) {
            throw new IllegalStateException("Buffer already closed");
        }
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (this.usage() & GpuBuffer.USAGE_MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (this.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        ByteBuffer mapped = this.sliceStorage(offset, length);
        return new GpuBufferSlice.MappedView(this.slice(offset, length), mapped, () -> {
        });
    }

    public int getUsage() {
        return this.usage();
    }

    private static boolean isCpuAccessible(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_MAP_READ) != 0
                || (usage & GpuBuffer.USAGE_MAP_WRITE) != 0
                || (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;
    }

    private static boolean isDynamic(@GpuBuffer.Usage final int usage) {
        return (usage & GpuBuffer.USAGE_UNIFORM) != 0 && (usage & GpuBuffer.USAGE_COPY_DST) != 0;
    }

    static boolean shouldUsePrivateGeometryHeap(
            @GpuBuffer.Usage final int originalUsage,
            final boolean initialData
    ) {
        return shouldUsePrivateGeometryHeap(
                originalUsage,
                initialData,
                PRIVATE_GEOMETRY_HEAPS_ENABLED
        );
    }

    static boolean shouldUsePrivateGeometryHeap(
            @GpuBuffer.Usage final int originalUsage,
            final boolean initialData,
            final boolean heapsEnabled
    ) {
        return heapsEnabled && !initialData && originalUsage == PRIVATE_GEOMETRY_HEAP_USAGE;
    }

    static boolean staticGeometryHeapsEnabled(final @Nullable String environmentValue) {
        return "1".equals(environmentValue);
    }

    private static long toMtlResourceOptions(@GpuBuffer.Usage final int usage) {
        MTLStorageMode storageMode = isCpuAccessible(usage) || isDynamic(usage) ? MTLStorageMode.Shared : MTLStorageMode.Private;
        return MTLResourceOptions.of(storageMode, MTLHazardTrackingMode.Untracked);
    }

    private void releaseTexelViews() {
        this.texelViews.drain();
    }

    /**
     * Mirrors Blaze3D's Vulkan two-phase buffer lifetime: close rejects new public use, while an
     * already-recorded render pass may still resolve the native handle until deferred destruction.
     */
    static final class NativeHandleState {
        @Nullable
        private MemorySegment handle;
        private boolean closed;

        NativeHandleState(final @Nullable MemorySegment handle) {
            this.handle = handle;
        }

        MemorySegment requireForEncoding() {
            if (this.handle == null) {
                throw new IllegalStateException("Native Metal buffer is closed");
            }
            return this.handle;
        }

        boolean isClosed() {
            return this.closed || this.handle == null;
        }

        @Nullable
        MemorySegment beginClose() {
            if (this.closed) {
                return null;
            }
            this.closed = true;
            return this.handle;
        }

        void replace(final MemorySegment replacement) {
            if (this.closed) {
                throw new IllegalStateException("Cannot replace the backing of a closed Metal buffer");
            }
            this.handle = replacement;
        }

        void markReleased(final MemorySegment released) {
            if (this.handle == released) {
                this.handle = null;
            }
        }
    }

    record TexelViewKey(long pixelFormat, long offset, long texelCount, long byteLength) {
    }

    static final class TexelViewCache<T> {
        private final int maxEntries;
        private final Consumer<T> release;
        @Nullable
        private Map<TexelViewKey, T> views;

        TexelViewCache(final int maxEntries, final Consumer<T> release) {
            if (maxEntries <= 0) {
                throw new IllegalArgumentException("Texel view cache must hold at least one entry");
            }
            this.maxEntries = maxEntries;
            this.release = release;
        }

        @Nullable
        T getOrCreate(final TexelViewKey key, final Function<TexelViewKey, @Nullable T> factory) {
            Map<TexelViewKey, T> views = this.views;
            if (views != null) {
                T cached = views.get(key);
                if (cached != null) {
                    return cached;
                }
            }

            T created = factory.apply(key);
            if (created == null) {
                return null;
            }

            if (views == null) {
                views = new LinkedHashMap<>(16, 0.75f, true);
                this.views = views;
            }
            views.put(key, created);
            if (views.size() > this.maxEntries) {
                var eldest = views.entrySet().iterator();
                T evicted = eldest.next().getValue();
                eldest.remove();
                this.release.accept(evicted);
            }
            return created;
        }

        void drain() {
            Map<TexelViewKey, T> views = this.views;
            if (views == null) {
                return;
            }

            this.views = null;
            views.values().forEach(this.release);
            views.clear();
        }

        int size() {
            return this.views == null ? 0 : this.views.size();
        }

        boolean isInitialized() {
            return this.views != null;
        }
    }
}
