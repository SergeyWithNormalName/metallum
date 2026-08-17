package com.metallum.client.metal.render;

import com.metallum.client.lighting.cloud.CloudShadowFrameState;
import com.metallum.client.lighting.cloud.CloudShadowMode;
import com.metallum.client.lighting.cloud.CloudShadowPolicy;
import com.metallum.client.lighting.cloud.CloudShadowSource;
import com.metallum.client.lighting.shader.CloudShadowBindingAbi;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.OptionalDouble;

/**
 * Owns GPU resources and preintegrated periodic transmittance for Metallum Cloud Shadows.
 */
final class CloudShadowGpuResources implements AutoCloseable {
    private final MetalDevice device;
    private MetalGpuTexture transmittanceTexture;
    private final MetalGpuSampler transmittanceSampler;
    private ByteBuffer stagingBuffer;

    private CloudShadowMode cachedMode = CloudShadowMode.NONE;
    private long cachedPatternGeneration = Long.MIN_VALUE;
    private float cachedOpacity = -1.0f;
    private float cachedToLightX = Float.NaN;
    private float cachedToLightY = Float.NaN;
    private float cachedToLightZ = Float.NaN;
    private int currentWidth = 256;
    private int currentHeight = 256;
    private boolean closed;

    CloudShadowGpuResources(final MetalDevice device) {
        this.device = Objects.requireNonNull(device, "device");
        this.currentWidth = 256;
        this.currentHeight = 256;
        this.transmittanceTexture = (MetalGpuTexture) device.createTexture(
                "Metallum cloud transmittance",
                GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.R8_UNORM,
                this.currentWidth,
                this.currentHeight,
                1,
                1
        );
        this.transmittanceSampler = new MetalGpuSampler(
                device,
                AddressMode.REPEAT,
                AddressMode.REPEAT,
                FilterMode.LINEAR,
                FilterMode.LINEAR,
                1,
                OptionalDouble.empty()
        );
        this.stagingBuffer = ByteBuffer.allocateDirect(this.currentWidth * this.currentHeight)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < this.currentWidth * this.currentHeight; i++) {
            this.stagingBuffer.put((byte) 0xFF);
        }
        this.stagingBuffer.flip();
        this.device.commandEncoder.writeToTexture(
                this.transmittanceTexture,
                this.stagingBuffer,
                0,
                0,
                0,
                0,
                this.currentWidth,
                this.currentHeight
        );
    }

    void update(
            final CloudShadowFrameState frameState,
            final CloudShadowSource source
    ) {
        if (this.closed) {
            return;
        }
        Objects.requireNonNull(frameState, "frameState");
        CloudShadowMode mode = frameState.mode();

        if (mode == CloudShadowMode.NONE || !frameState.enabled() || source == null || !source.isAvailable()) {
            if (this.cachedMode != CloudShadowMode.NONE) {
                this.cachedMode = CloudShadowMode.NONE;
                int totalPixels = this.currentWidth * this.currentHeight;
                this.stagingBuffer.clear();
                for (int i = 0; i < totalPixels; i++) {
                    this.stagingBuffer.put((byte) 0xFF);
                }
                this.stagingBuffer.flip();
                this.device.commandEncoder.writeToTexture(
                        this.transmittanceTexture,
                        this.stagingBuffer,
                        0,
                        0,
                        0,
                        0,
                        this.currentWidth,
                        this.currentHeight
                );
            }
            return;
        }

        int srcWidth = source.width();
        int srcHeight = source.height();
        if (this.currentWidth != srcWidth || this.currentHeight != srcHeight) {
            this.transmittanceTexture.close();
            this.currentWidth = srcWidth;
            this.currentHeight = srcHeight;
            this.transmittanceTexture = (MetalGpuTexture) this.device.createTexture(
                    "Metallum cloud transmittance",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
                    GpuFormat.R8_UNORM,
                    this.currentWidth,
                    this.currentHeight,
                    1,
                    1
            );
            this.stagingBuffer = ByteBuffer.allocateDirect(this.currentWidth * this.currentHeight)
                    .order(ByteOrder.nativeOrder());
            this.cachedMode = CloudShadowMode.NONE;
            this.cachedPatternGeneration = Long.MIN_VALUE;
        }

        boolean rebuildNeeded = false;
        if (this.cachedMode != mode) {
            rebuildNeeded = true;
        } else if (this.cachedPatternGeneration != source.generation()) {
            rebuildNeeded = true;
        } else if (Math.abs(this.cachedOpacity - frameState.cloudOpacity()) > 0.01f) {
            rebuildNeeded = true;
        } else if (mode == CloudShadowMode.VOLUMETRIC) {
            float dx = Math.abs(this.cachedToLightX - frameState.toLightX());
            float dy = Math.abs(this.cachedToLightY - frameState.toLightY());
            float dz = Math.abs(this.cachedToLightZ - frameState.toLightZ());
            if (dx > CloudShadowPolicy.VOLUMETRIC_DIRECTION_UPDATE_THRESHOLD
                    || dy > CloudShadowPolicy.VOLUMETRIC_DIRECTION_UPDATE_THRESHOLD
                    || dz > CloudShadowPolicy.VOLUMETRIC_DIRECTION_UPDATE_THRESHOLD) {
                rebuildNeeded = true;
            }
        }

        if (rebuildNeeded) {
            this.stagingBuffer.clear();
            source.generateTransmittanceBytes(
                    mode,
                    frameState.toLightX(),
                    frameState.toLightY(),
                    frameState.toLightZ(),
                    frameState.cloudOpacity(),
                    this.stagingBuffer
            );
            this.stagingBuffer.flip();
            this.device.commandEncoder.writeToTexture(
                    this.transmittanceTexture,
                    this.stagingBuffer,
                    0,
                    0,
                    0,
                    0,
                    this.currentWidth,
                    this.currentHeight
            );
            this.cachedMode = mode;
            this.cachedPatternGeneration = source.generation();
            this.cachedOpacity = frameState.cloudOpacity();
            this.cachedToLightX = frameState.toLightX();
            this.cachedToLightY = frameState.toLightY();
            this.cachedToLightZ = frameState.toLightZ();
        }
    }

    void bind(final MTLRenderCommandEncoder encoder) {
        if (this.closed || this.transmittanceTexture == null) {
            return;
        }
        encoder.setTextureAndSampler(
                this.transmittanceTexture.nativeHandle(),
                this.transmittanceSampler.nativeHandle(),
                CloudShadowBindingAbi.TEXTURE_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
    }

    MetalGpuTexture transmittanceTexture() {
        return this.transmittanceTexture;
    }

    MetalGpuSampler transmittanceSampler() {
        return this.transmittanceSampler;
    }

    CloudShadowMode cachedMode() {
        return this.cachedMode;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.transmittanceSampler.close();
        if (this.transmittanceTexture != null) {
            this.transmittanceTexture.close();
        }
    }
}
