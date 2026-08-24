package com.metallum.client.metal.render;

import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.MetallumMaterialState;
import com.metallum.client.renderer.PlanarReflectionLayout;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;

import java.util.Objects;
import java.util.OptionalDouble;

/**
 * GPU resources for the Planar Reflection pass.
 * Manages the offscreen render target (Color + Depth), projection buffer, and linear sampler.
 */
public final class PlanarReflectionGpuResources implements AutoCloseable {
    private final MetalDevice device;
    private TextureTarget target;
    private ProjectionMatrixBuffer projectionBuffer;
    private final MetalGpuSampler sampler;
    private MetalGpuTexture fallbackTexture;
    private int width;
    private int height;
    private boolean closed;

    public PlanarReflectionGpuResources(final MetalDevice device) {
        this.device = Objects.requireNonNull(device, "device");
        this.sampler = new MetalGpuSampler(
                device,
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty()
        );
        this.fallbackTexture = (MetalGpuTexture) device.createTexture(
                "Metallum planar reflection fallback",
                com.mojang.blaze3d.textures.GpuTexture.USAGE_COPY_DST | com.mojang.blaze3d.textures.GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.RGBA8_UNORM,
                1,
                1,
                1,
                1
        );
        java.nio.ByteBuffer staging = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder());
        staging.putInt(0);
        staging.flip();
        this.device.commandEncoder.writeToTexture(
                this.fallbackTexture,
                staging,
                0, 0, 0, 0,
                1, 1
        );
    }

    public TextureTarget getOrCreateTarget(final int renderWidth, final int renderHeight, final float resolutionScale) {
        ensureOpen();
        int targetWidth = Math.max((int) (renderWidth * resolutionScale), 16);
        int targetHeight = Math.max((int) (renderHeight * resolutionScale), 16);

        if (this.target == null || this.width != targetWidth || this.height != targetHeight) {
            if (this.target != null) {
                this.target.destroyBuffers();
            }
            GpuFormat format = (HdrSceneState.isRequested() || MetallumMaterialState.requiresFp16Scene())
                    ? GpuFormat.RGBA16_FLOAT
                    : GpuFormat.RGBA8_UNORM;

            this.target = this.device.createTrackedTextureTarget(
                    "Metallum planar reflection target",
                    targetWidth,
                    targetHeight,
                    true,
                    format
            );
            this.width = targetWidth;
            this.height = targetHeight;
        }
        return this.target;
    }

    public TextureTarget target() {
        return this.target;
    }

    public ProjectionMatrixBuffer projectionBuffer() {
        if (this.projectionBuffer == null) {
            this.projectionBuffer = new ProjectionMatrixBuffer("Metallum planar reflection projection");
        }
        return this.projectionBuffer;
    }

    public MetalGpuSampler sampler() {
        return this.sampler;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public void bind(final com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder encoder) {
        bind(encoder, false);
    }

    /**
     * Binds the reflection texture to Advanced terrain shaders.  The reflection pass itself must
     * bind the transparent fallback: sampling a color attachment while it is being written is a
     * Metal feedback loop and is invalid even though opaque terrain does not use the sample.
     */
    public void bind(
            final com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder encoder,
            final boolean forceFallback
    ) {
        if (this.closed) {
            return;
        }
        MetalGpuTexture textureToBind = !forceFallback && this.target != null && this.target.getColorTexture() != null
                ? (MetalGpuTexture) this.target.getColorTexture()
                : this.fallbackTexture;

        if (textureToBind != null && !textureToBind.isClosed()) {
            encoder.setTextureAndSampler(
                    textureToBind.nativeHandle(),
                    this.sampler.nativeHandle(),
                    PlanarReflectionLayout.TEXTURE_SLOT,
                    MetalCompiledRenderPipeline.STAGE_FRAGMENT
            );
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        if (this.projectionBuffer != null) {
            this.projectionBuffer.close();
            this.projectionBuffer = null;
        }
        this.sampler.close();
        if (this.target != null) {
            this.target.destroyBuffers();
            this.target = null;
        }
        if (this.fallbackTexture != null) {
            this.fallbackTexture.close();
            this.fallbackTexture = null;
        }
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("PlanarReflectionGpuResources is closed");
        }
    }
}
