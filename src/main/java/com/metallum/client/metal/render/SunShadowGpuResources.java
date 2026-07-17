package com.metallum.client.metal.render;

import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.lighting.SunShadowFrame;
import com.metallum.client.lighting.SunShadowStabilizer;
import com.metallum.client.lighting.shader.EnvironmentShadowBindingAbi;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.renderer.temporal.FrameState;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.OptionalDouble;

/** Owns one Advanced generation's ordinary, non-cached L4 cascades and params ring. */
final class SunShadowGpuResources implements AutoCloseable {
    private final long generation;
    private final SunShadowLayout.Budget budget;
    private final MetalGpuBuffer paramsRing;
    private final TextureTarget[] cascades;
    private final ProjectionMatrixBuffer projectionBuffer;
    private final MetalGpuSampler comparisonSampler;
    private final SunShadowStabilizer stabilizer;
    private SunShadowFrame frame;
    private long renderedSubmitIndex = Long.MIN_VALUE;
    private boolean closed;

    private SunShadowGpuResources(
            final long generation,
            final SunShadowLayout.Budget budget,
            final MetalGpuBuffer paramsRing,
            final TextureTarget[] cascades,
            final ProjectionMatrixBuffer projectionBuffer,
            final MetalGpuSampler comparisonSampler
    ) {
        this.generation = generation;
        this.budget = budget;
        this.paramsRing = paramsRing;
        this.cascades = cascades;
        this.projectionBuffer = projectionBuffer;
        this.comparisonSampler = comparisonSampler;
        this.stabilizer = new SunShadowStabilizer();
    }

    static SunShadowGpuResources create(
            final MetalDevice device,
            final long generation,
            final SunShadowLayout.Budget budget
    ) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(budget, "budget");
        if (generation <= 0L) {
            throw new IllegalArgumentException("Shadow generation must be positive");
        }
        MetalGpuBuffer params = null;
        TextureTarget[] cascades = new TextureTarget[budget.cascadeCount()];
        ProjectionMatrixBuffer projection = null;
        MetalGpuSampler comparisonSampler = null;
        try {
            params = new MetalGpuBuffer(
                    device,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    budget.paramsRingBytes()
            );
            for (int cascade = 0; cascade < cascades.length; cascade++) {
                cascades[cascade] = new TextureTarget(
                        "Metallum sun shadow cascade " + cascade,
                        budget.resolution(),
                        budget.resolution(),
                        true,
                        GpuFormat.R8_UNORM
                );
                if (cascades[cascade].getDepthTexture().getFormat() != GpuFormat.D32_FLOAT) {
                    throw new IllegalStateException("Sun shadow target is not D32Float");
                }
            }
            projection = new ProjectionMatrixBuffer("Metallum sun shadow projection");
            comparisonSampler = new MetalGpuSampler(
                    device,
                    AddressMode.CLAMP_TO_EDGE,
                    AddressMode.CLAMP_TO_EDGE,
                    FilterMode.LINEAR,
                    FilterMode.LINEAR,
                    1,
                    OptionalDouble.of(0.0),
                    MTLCompareFunction.GreaterEqual
            );
            return new SunShadowGpuResources(
                    generation,
                    budget,
                    params,
                    cascades,
                    projection,
                    comparisonSampler
            );
        } catch (RuntimeException | Error failure) {
            if (comparisonSampler != null) {
                comparisonSampler.close();
            }
            if (projection != null) {
                projection.close();
            }
            for (TextureTarget target : cascades) {
                if (target != null) {
                    target.destroyBuffers();
                }
            }
            if (params != null) {
                params.close();
            }
            throw failure;
        }
    }

    long generation() {
        return this.generation;
    }

    SunShadowLayout.Budget budget() {
        return this.budget;
    }

    SunShadowFrame encode(
            final EnvironmentDescriptor environment,
            final FrameState frameState
    ) {
        ensureOpen();
        Objects.requireNonNull(environment, "environment");
        Objects.requireNonNull(frameState, "frameState");
        if (frameState.lightingGenerationId() != this.generation) {
            throw new IllegalArgumentException("Environment does not match the shadow generation");
        }
        SunShadowFrame planned = SunShadowFrame.plan(
                environment,
                this.budget,
                frameState,
                this.stabilizer
        );
        int slot = frameState.inFlightSlot();
        long offset = (long) slot * SunShadowLayout.PARAMS_BYTES;
        ByteBuffer packet = this.paramsRing.sliceStorage(offset, SunShadowLayout.PARAMS_BYTES)
                .order(ByteOrder.nativeOrder());
        packet.clear();
        while (packet.remaining() > 0) {
            packet.put((byte) 0);
        }
        packet.clear();
        for (int cascade = 0; cascade < SunShadowLayout.MAX_CASCADES; cascade++) {
            putMatrix(packet, cascade * 64, planned.shadowFromView(cascade));
        }
        Vector3f toLight = planned.toLightView();
        putVec4(packet, EnvironmentShadowBindingAbi.DIRECTION_AND_FLAGS_OFFSET,
                toLight.x, toLight.y, toLight.z, planned.needsShadowPass() ? 1.0f : 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.DIRECTIONAL_RADIANCE_OFFSET,
                environment.directionalRed(), environment.directionalGreen(),
                environment.directionalBlue(), environment.moon() ? 1.0f : 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.SKY_IRRADIANCE_OFFSET,
                environment.skyRed(), environment.skyGreen(), environment.skyBlue(), 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.AMBIENT_RADIANCE_OFFSET,
                environment.ambientRed(), environment.ambientGreen(), environment.ambientBlue(), 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.CASCADE_SPLITS_OFFSET,
                planned.cascadeSplit(0), planned.cascadeSplit(1), planned.cascadeSplit(2),
                planned.cascadeSplit(planned.cascadeCount() - 1));
        putVec4(packet, EnvironmentShadowBindingAbi.TEXEL_AND_BIAS_OFFSET,
                1.0f / this.budget.resolution(),
                this.budget.receiverDepthBias(),
                this.budget.receiverNormalBias(),
                this.budget.pcfRadiusTexels());
        putVec4(packet, EnvironmentShadowBindingAbi.CASCADE_BLEND_OFFSET,
                this.budget.blendFraction(),
                this.budget.blendFraction(),
                this.budget.blendFraction(),
                0.0f);
        int flags = (planned.needsShadowPass() ? 1 : 0)
                | (environment.moon() ? 2 : 0)
                | (environment.profile().ordinal() << 4)
                | (environment.medium().ordinal() << 8);
        putInt4(packet, EnvironmentShadowBindingAbi.CONTRACT_OFFSET,
                EnvironmentShadowBindingAbi.VERSION,
                planned.cascadeCount(),
                (int) planned.lightingGeneration(),
                flags);
        Vector3f worldUp = planned.worldUpView();
        putVec4(packet, EnvironmentShadowBindingAbi.WORLD_UP_AND_MEDIUM_OFFSET,
                worldUp.x, worldUp.y, worldUp.z, environment.medium().ordinal());
        putVec4(packet, EnvironmentShadowBindingAbi.CASCADE_NORMAL_BIAS_OFFSET,
                planned.cascadeReceiverNormalBias(0),
                planned.cascadeReceiverNormalBias(1),
                planned.cascadeReceiverNormalBias(2),
                this.budget.receiverNormalBiasTexels());
        this.frame = planned;
        this.renderedSubmitIndex = planned.needsShadowPass()
                ? Long.MIN_VALUE
                : planned.submitIndex();
        return planned;
    }

    SunShadowFrame frameForSubmit(final long submitIndex) {
        ensureOpen();
        return this.frame != null && this.frame.submitIndex() == submitIndex ? this.frame : null;
    }

    RenderTarget target(final int cascade) {
        ensureOpen();
        if (cascade < 0 || cascade >= this.cascades.length) {
            throw new IllegalArgumentException("Invalid shadow cascade " + cascade);
        }
        return this.cascades[cascade];
    }

    ProjectionMatrixBuffer projectionBuffer() {
        ensureOpen();
        return this.projectionBuffer;
    }

    void markRendered(final long submitIndex) {
        ensureOpen();
        if (this.frame == null || !this.frame.needsShadowPass()
                || this.frame.submitIndex() != submitIndex) {
            throw new IllegalStateException("Rendered shadow pass does not match the prepared frame");
        }
        this.renderedSubmitIndex = submitIndex;
    }

    boolean isReady(final long submitIndex) {
        return !this.closed
                && this.frame != null
                && this.frame.submitIndex() == submitIndex
                && this.renderedSubmitIndex == submitIndex;
    }

    void bind(final MTLRenderCommandEncoder encoder, final int inFlightSlot) {
        ensureOpen();
        if (this.frame == null || !isReady(this.frame.submitIndex())) {
            throw new IllegalStateException("Sun-shadow bindings are not ready");
        }
        long paramsOffset = (long) inFlightSlot * SunShadowLayout.PARAMS_BYTES;
        encoder.setBuffer(
                this.paramsRing.nativeHandle(),
                paramsOffset,
                EnvironmentShadowBindingAbi.PARAMS_SLOT,
                MetalCompiledRenderPipeline.STAGE_FRAGMENT
        );
        int[] slots = EnvironmentShadowBindingAbi.shadowTextureSlots();
        for (int cascade = 0; cascade < SunShadowLayout.MAX_CASCADES; cascade++) {
            TextureTarget target = this.cascades[Math.min(cascade, this.cascades.length - 1)];
            MetalGpuTexture depth = (MetalGpuTexture) target.getDepthTexture();
            encoder.setTextureAndSampler(
                    depth.nativeHandle(),
                    this.comparisonSampler.nativeHandle(),
                    slots[cascade],
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
        this.frame = null;
        this.comparisonSampler.close();
        this.projectionBuffer.close();
        for (TextureTarget target : this.cascades) {
            target.destroyBuffers();
        }
        this.paramsRing.close();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Sun-shadow resources are closed");
        }
    }

    private static void putMatrix(
            final ByteBuffer packet,
            final int offset,
            final Matrix4f matrix
    ) {
        float[] values = new float[16];
        matrix.get(values);
        for (int index = 0; index < values.length; index++) {
            packet.putFloat(offset + index * Float.BYTES, values[index]);
        }
    }

    private static void putVec4(
            final ByteBuffer packet,
            final int offset,
            final float x,
            final float y,
            final float z,
            final float w
    ) {
        packet.putFloat(offset, x);
        packet.putFloat(offset + 4, y);
        packet.putFloat(offset + 8, z);
        packet.putFloat(offset + 12, w);
    }

    private static void putInt4(
            final ByteBuffer packet,
            final int offset,
            final int x,
            final int y,
            final int z,
            final int w
    ) {
        packet.putInt(offset, x);
        packet.putInt(offset + 4, y);
        packet.putInt(offset + 8, z);
        packet.putInt(offset + 12, w);
    }
}
