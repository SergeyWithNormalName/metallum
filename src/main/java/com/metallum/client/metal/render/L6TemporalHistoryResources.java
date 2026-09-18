package com.metallum.client.metal.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4f;

import java.util.OptionalDouble;

/** Two-frame private history for the experimental in-fragment L6 reconstruction. */
final class L6TemporalHistoryResources implements AutoCloseable {
    record Pair(MetalGpuTexture read, MetalGpuTexture write) {
    }

    private final MetalGpuTexture[] history = new MetalGpuTexture[2];
    private final MetalGpuSampler sampler;
    private final int width;
    private final int height;

    L6TemporalHistoryResources(final MetalDevice device, final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("L6 temporal history extent must be positive");
        }
        this.width = width;
        this.height = height;
        int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;
        MetalGpuTexture first = null;
        MetalGpuTexture second = null;
        MetalGpuSampler createdSampler = null;
        try {
            first = new MetalGpuTexture(
                    device, usage, "Metallum L6 temporal history 0",
                    GpuFormat.RGBA16_FLOAT, width, height, 1, 1
            );
            second = new MetalGpuTexture(
                    device, usage, "Metallum L6 temporal history 1",
                    GpuFormat.RGBA16_FLOAT, width, height, 1, 1
            );
            createdSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST,
                    FilterMode.NEAREST,
                    1,
                    OptionalDouble.empty()
            );
            this.history[0] = first;
            this.history[1] = second;
            this.sampler = createdSampler;
            device.commandEncoder.clearColorTexture(first, new Vector4f(0.0f));
            device.commandEncoder.clearColorTexture(second, new Vector4f(0.0f));
        } catch (RuntimeException exception) {
            if (createdSampler != null) {
                createdSampler.close();
            }
            if (second != null) {
                second.close();
            }
            if (first != null) {
                first.close();
            }
            throw exception;
        }
    }

    boolean matches(final int expectedWidth, final int expectedHeight) {
        return this.width == expectedWidth && this.height == expectedHeight;
    }

    void materializeInitialClears(final MetalCommandEncoder encoder) {
        encoder.prepareTextureForRead(this.history[0]);
        encoder.prepareTextureForRead(this.history[1]);
    }

    Pair pair(final long submitIndex) {
        int writeIndex = (int) (submitIndex & 1L);
        return new Pair(this.history[writeIndex ^ 1], this.history[writeIndex]);
    }

    MetalGpuSampler sampler() {
        return this.sampler;
    }

    @Override
    public void close() {
        this.sampler.close();
        for (int index = 0; index < this.history.length; index++) {
            MetalGpuTexture texture = this.history[index];
            if (texture != null) {
                texture.close();
                this.history[index] = null;
            }
        }
    }
}
