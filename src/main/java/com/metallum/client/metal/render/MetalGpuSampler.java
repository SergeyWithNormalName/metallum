package com.metallum.client.metal.render;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.metal.render.mtl.MTLSamplerAddressMode;
import com.metallum.client.metal.render.mtl.MTLSamplerMinMagFilter;
import com.metallum.client.metal.render.mtl.MTLSamplerMipFilter;
import com.metallum.client.metalfx.TemporalScalingMode;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.NonNull;

import java.lang.foreign.MemorySegment;
import java.util.OptionalDouble;

@Environment(EnvType.CLIENT)
final class MetalGpuSampler extends GpuSampler {
    private final MetalDevice device;
    private final MemorySegment nativeHandle;
    private final MemorySegment temporalQualityHandle;
    private final MemorySegment temporalPerformanceHandle;
    private final MemorySegment temporalUltraPerformanceHandle;
    private final boolean temporalMipBiasEligible;
    private final AddressMode addressModeU;
    private final AddressMode addressModeV;
    private final FilterMode minFilter;
    private final FilterMode magFilter;
    private final int maxAnisotropy;
    private final OptionalDouble maxLod;
    private boolean closed;

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod
    ) {
        this(
                device,
                addressModeU,
                addressModeV,
                minFilter,
                magFilter,
                maxAnisotropy,
                maxLod,
                MTLCompareFunction.Never
        );
    }

    MetalGpuSampler(
            final MetalDevice device,
            final AddressMode addressModeU,
            final AddressMode addressModeV,
            final FilterMode minFilter,
            final FilterMode magFilter,
            final int maxAnisotropy,
            final OptionalDouble maxLod,
            final MTLCompareFunction compareFunction
    ) {
        this.device = device;
        MTLSamplerAddressMode nativeAddressModeU = MTLSamplerAddressMode.from(addressModeU);
        MTLSamplerAddressMode nativeAddressModeV = MTLSamplerAddressMode.from(addressModeV);
        MTLSamplerMinMagFilter nativeMinFilter = MTLSamplerMinMagFilter.from(minFilter);
        MTLSamplerMinMagFilter nativeMagFilter = MTLSamplerMinMagFilter.from(magFilter);
        MTLSamplerMipFilter nativeMipFilter = toMtlMipFilter(maxLod);
        int nativeAnisotropy = Math.max(1, maxAnisotropy);
        double nativeMaxLod = toMtlMaxLodClamp(maxLod);
        this.nativeHandle = createNativeHandle(
                device,
                nativeAddressModeU,
                nativeAddressModeV,
                nativeMinFilter,
                nativeMagFilter,
                nativeMipFilter,
                compareFunction,
                nativeAnisotropy,
                nativeMaxLod,
                0.0
        );
        this.temporalMipBiasEligible = compareFunction == MTLCompareFunction.Never
                && nativeMipFilter == MTLSamplerMipFilter.Linear;
        if (this.temporalMipBiasEligible) {
            this.temporalQualityHandle = tryCreateTemporalHandle(
                    device, nativeAddressModeU, nativeAddressModeV, nativeMinFilter, nativeMagFilter,
                    nativeMipFilter, compareFunction, nativeAnisotropy, nativeMaxLod,
                    TemporalScalingMode.QUALITY.textureMipBias()
            );
            this.temporalPerformanceHandle = tryCreateTemporalHandle(
                    device, nativeAddressModeU, nativeAddressModeV, nativeMinFilter, nativeMagFilter,
                    nativeMipFilter, compareFunction, nativeAnisotropy, nativeMaxLod,
                    TemporalScalingMode.PERFORMANCE.textureMipBias()
            );
            this.temporalUltraPerformanceHandle = tryCreateTemporalHandle(
                    device, nativeAddressModeU, nativeAddressModeV, nativeMinFilter, nativeMagFilter,
                    nativeMipFilter, compareFunction, nativeAnisotropy, nativeMaxLod,
                    TemporalScalingMode.ULTRA_PERFORMANCE.textureMipBias()
            );
        } else {
            this.temporalQualityHandle = MemorySegment.NULL;
            this.temporalPerformanceHandle = MemorySegment.NULL;
            this.temporalUltraPerformanceHandle = MemorySegment.NULL;
        }
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.maxAnisotropy = maxAnisotropy;
        this.maxLod = maxLod;
    }

    @Override
    public @NonNull AddressMode getAddressModeU() {
        return this.addressModeU;
    }

    @Override
    public @NonNull AddressMode getAddressModeV() {
        return this.addressModeV;
    }

    @Override
    public @NonNull FilterMode getMinFilter() {
        return this.minFilter;
    }

    @Override
    public @NonNull FilterMode getMagFilter() {
        return this.magFilter;
    }

    @Override
    public int getMaxAnisotropy() {
        return this.maxAnisotropy;
    }

    @Override
    public @NonNull OptionalDouble getMaxLod() {
        return this.maxLod;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.device.queueResourceRelease(this.nativeHandle);
        queueResourceRelease(this.temporalQualityHandle);
        queueResourceRelease(this.temporalPerformanceHandle);
        queueResourceRelease(this.temporalUltraPerformanceHandle);
    }

    boolean isClosed() {
        return this.closed;
    }

    MemorySegment nativeHandle() {
        if (!this.temporalMipBiasEligible) {
            return this.nativeHandle;
        }
        MemorySegment selected = switch (this.device.temporalMipBiasMode()) {
            case QUALITY -> this.temporalQualityHandle;
            case PERFORMANCE -> this.temporalPerformanceHandle;
            case ULTRA_PERFORMANCE -> this.temporalUltraPerformanceHandle;
            // Dynamic Temporal is deliberately constrained to 50-60%. The
            // existing Performance sampler has the correct 50% mip bias and
            // is a free handle switch, so it restores more source detail than
            // Quality without another render or compute pass.
            case TEMPORAL -> this.temporalPerformanceHandle;
            case OFF -> this.nativeHandle;
        };
        return MetalNativeBridge.isNullHandle(selected) ? this.nativeHandle : selected;
    }

    private static MemorySegment createNativeHandle(
            final MetalDevice device,
            final MTLSamplerAddressMode addressModeU,
            final MTLSamplerAddressMode addressModeV,
            final MTLSamplerMinMagFilter minFilter,
            final MTLSamplerMinMagFilter magFilter,
            final MTLSamplerMipFilter mipFilter,
            final MTLCompareFunction compareFunction,
            final int maxAnisotropy,
            final double maxLod,
            final double lodBias
    ) {
        MemorySegment handle = MetalNativeBridge.metallum_create_sampler(
                device.metalDeviceHandle(),
                addressModeU,
                addressModeV,
                minFilter,
                magFilter,
                mipFilter,
                compareFunction,
                maxAnisotropy,
                maxLod,
                lodBias
        );
        if (MetalNativeBridge.isNullHandle(handle)) {
            throw new IllegalStateException("Metal failed to create a sampler");
        }
        return handle;
    }

    private static MemorySegment tryCreateTemporalHandle(
            final MetalDevice device,
            final MTLSamplerAddressMode addressModeU,
            final MTLSamplerAddressMode addressModeV,
            final MTLSamplerMinMagFilter minFilter,
            final MTLSamplerMinMagFilter magFilter,
            final MTLSamplerMipFilter mipFilter,
            final MTLCompareFunction compareFunction,
            final int maxAnisotropy,
            final double maxLod,
            final double lodBias
    ) {
        try {
            return createNativeHandle(
                    device, addressModeU, addressModeV, minFilter, magFilter, mipFilter,
                    compareFunction, maxAnisotropy, maxLod, lodBias
            );
        } catch (RuntimeException ignored) {
            // This is an optional visual-quality variant. The unbiased sampler
            // remains valid and is selected if a device cannot allocate it.
            return MemorySegment.NULL;
        }
    }

    private void queueResourceRelease(final MemorySegment handle) {
        if (!MetalNativeBridge.isNullHandle(handle)) {
            this.device.queueResourceRelease(handle);
        }
    }

    private static MTLSamplerMipFilter toMtlMipFilter(final OptionalDouble maxLod) {
        return maxLod.orElse(1000.0) > 0.25 ? MTLSamplerMipFilter.Linear : MTLSamplerMipFilter.Nearest;
    }

    private static double toMtlMaxLodClamp(final OptionalDouble maxLod) {
        return Math.max(0.25, maxLod.orElse(1000.0));
    }
}
