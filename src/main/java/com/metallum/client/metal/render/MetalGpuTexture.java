package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLStorageMode;
import com.metallum.client.metal.render.mtl.MTLTextureUsage;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

@Environment(EnvType.CLIENT)
final class MetalGpuTexture extends GpuTexture {
    private final MetalDevice device;
    private final MTLPixelFormat mtlPixelFormat;
    private boolean closed;
    @Nullable
    private Vector4fc materializedColorClear;
    private boolean materializedColorClearDecoded;
    @Nullable
    private Double materializedDepthClear;
    private boolean sceneColorClearRole;
    private boolean displaySdrColorRole;
    private int views = 1;
    @Nullable
    private MemorySegment nativeHandle;

    MetalGpuTexture(
            final MetalDevice device,
            @GpuTexture.Usage final int usage,
            final String label,
            final GpuFormat format,
            final int width,
            final int height,
            final int depthOrLayers,
            final int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.device = device;
        this.mtlPixelFormat = MTLPixelFormat.from(format);

        MemorySegment createdTexture = MetalNativeBridge.metallum_create_texture_2d(
                device.metalDeviceHandle(),
                this.mtlPixelFormat,
                width,
                height,
                depthOrLayers,
                mipLevels,
                (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 ? 1L : 0L,
                toMtlTextureUsage(usage),
                MTLStorageMode.Private,
                label
        );
        if (MetalNativeBridge.isNullHandle(createdTexture)) {
            throw new IllegalStateException("Metal failed to allocate texture '" + label + "' (" + width + "x" + height + ")");
        }
        this.nativeHandle = createdTexture;
    }

    int pixelSize() {
        return this.getFormat().blockSize();
    }

    void recordMaterializedClear(
            @Nullable final Vector4fc color,
            @Nullable final Double depth,
            final boolean colorDecoded
    ) {
        if (color != null) {
            this.materializedColorClear = color;
            this.materializedColorClearDecoded = colorDecoded;
        }
        if (depth != null) {
            this.materializedDepthClear = depth;
        }
    }

    boolean clearIsRedundant(
            @Nullable final Vector4fc color,
            @Nullable final Double depth,
            final boolean colorDecoded
    ) {
        return (color == null || (color.equals(this.materializedColorClear)
                && colorDecoded == this.materializedColorClearDecoded))
                && (depth == null || depth.equals(this.materializedDepthClear));
    }

    void markContentsDirty() {
        this.materializedColorClear = null;
        this.materializedDepthClear = null;
    }

    void markSceneColorClearRole() {
        if (!this.displaySdrColorRole && !this.sceneColorClearRole) {
            // A cached logical clear is only redundant while its color-space
            // contract is unchanged. The declaration is attachment-owned and
            // intentionally sticky: an unknown pipeline must not turn a
            // scene-color target back into numerical/gamma data.
            this.materializedColorClear = null;
            this.sceneColorClearRole = true;
        }
    }

    boolean hasSceneColorClearRole() {
        return this.sceneColorClearRole;
    }

    void markDisplaySdrColorRole() {
        if (!this.displaySdrColorRole || this.sceneColorClearRole) {
            this.materializedColorClear = null;
            this.sceneColorClearRole = false;
            this.displaySdrColorRole = true;
        }
    }

    boolean hasDisplaySdrColorRole() {
        return this.displaySdrColorRole;
    }

    MemorySegment nativeHandle() {
        if (this.nativeHandle == null) {
            throw new IllegalStateException("Native Metal texture is closed");
        }
        return this.nativeHandle;
    }

    MetalDevice device() {
        return this.device;
    }

    void queueNativeRelease(final MemorySegment handle) {
        this.device.queueResourceRelease(handle);
    }

    void addView() {
        this.views++;
    }

    void removeView() {
        this.views--;
        if (this.views < 0) {
            throw new IllegalStateException("Too many views removed from texture");
        }
        if (this.closed && this.views == 0 && this.nativeHandle != null) {
            MemorySegment handle = this.nativeHandle;
            this.nativeHandle = null;
            this.device.queueResourceRelease(handle);
        }
    }

    MTLPixelFormat mtlPixelFormat() {
        return this.mtlPixelFormat;
    }

    MTLPixelFormat mtlStencilPixelFormat() {
        return this.mtlPixelFormat.hasStencil() ? this.mtlPixelFormat : MTLPixelFormat.Invalid;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.device.resolvePendingUiSeedForTexture(this);
        this.closed = true;
        this.removeView();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    private static long toMtlTextureUsage(@GpuTexture.Usage final int usage) {
        long result = 0L;
        if ((usage & GpuTexture.USAGE_TEXTURE_BINDING) != 0 || (usage & GpuTexture.USAGE_COPY_DST) != 0 || (usage & GpuTexture.USAGE_COPY_SRC) != 0) {
            result |= MTLTextureUsage.ShaderRead.value;
        }
        if ((usage & GpuTexture.USAGE_RENDER_ATTACHMENT) != 0) {
            result |= MTLTextureUsage.RenderTarget.value;
            result |= MTLTextureUsage.ShaderRead.value;
        }
        return result == 0L ? MTLTextureUsage.ShaderRead.value : result;
    }
}
