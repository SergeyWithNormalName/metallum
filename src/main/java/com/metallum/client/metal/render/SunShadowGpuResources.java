package com.metallum.client.metal.render;

import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import com.metallum.client.lighting.SunShadowCache;
import com.metallum.client.lighting.SunShadowFrame;
import com.metallum.client.lighting.SunShadowStabilizer;
import com.metallum.client.lighting.shader.EnvironmentShadowBindingAbi;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.voxel.VoxelUploadBatch;
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

/** Owns one Advanced generation's cached terrain and per-frame dynamic L6 cascades. */
final class SunShadowGpuResources implements AutoCloseable {
    static final int PRODUCTION_PASS_COUNT = 3;
    static final int MAX_ENCODERS_PER_CASCADE = 3;
    static final int RESIDENT_PSO_COUNT = 2;
    private final long generation;
    private final SunShadowLayout.Budget budget;
    private final MetalGpuBuffer paramsRing;
    private final TextureTarget[] staticCascades;
    private final TextureTarget[] workingCascades;
    private final ProjectionMatrixBuffer projectionBuffer;
    private final MetalGpuSampler comparisonSampler;
    private final SunShadowStabilizer stabilizer;
    private final SunShadowCache cache;
    private SunShadowFrame frame;
    private double materialTimeSeconds;
    private float materialRainWetness;
    private SunShadowCache.Decision cacheDecision;
    private long renderedSubmitIndex = Long.MIN_VALUE;
    private boolean closed;

    /**
     * Advances the material clock without a modulo reset. The water shader combines this time
     * with several non-commensurate wave and noise frequencies, so wrapping the value makes the
     * surface visibly jump rather than return to an identical phase.
     */
    static double advanceMaterialTimeSeconds(
            final double materialTimeSeconds,
            final double frameDeltaSeconds
    ) {
        return materialTimeSeconds + Math.clamp(frameDeltaSeconds, 0.0, 0.25);
    }

    private SunShadowGpuResources(
            final long generation,
            final SunShadowLayout.Budget budget,
            final MetalGpuBuffer paramsRing,
            final TextureTarget[] staticCascades,
            final TextureTarget[] workingCascades,
            final ProjectionMatrixBuffer projectionBuffer,
            final MetalGpuSampler comparisonSampler
    ) {
        this.generation = generation;
        this.budget = budget;
        this.paramsRing = paramsRing;
        this.staticCascades = staticCascades;
        this.workingCascades = workingCascades;
        this.projectionBuffer = projectionBuffer;
        this.comparisonSampler = comparisonSampler;
        this.stabilizer = new SunShadowStabilizer();
        this.cache = new SunShadowCache(budget.totalBytes());
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
        TextureTarget[] staticCascades = new TextureTarget[budget.cascadeCount()];
        TextureTarget[] workingCascades = new TextureTarget[budget.cascadeCount()];
        ProjectionMatrixBuffer projection = null;
        MetalGpuSampler comparisonSampler = null;
        try {
            params = new MetalGpuBuffer(
                    device,
                    GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    budget.paramsRingBytes()
            );
            for (int cascade = 0; cascade < staticCascades.length; cascade++) {
                staticCascades[cascade] = device.createTrackedTextureTarget(
                        "Metallum static sun shadow cascade " + cascade,
                        budget.resolution(),
                        budget.resolution(),
                        true,
                        GpuFormat.R8_UNORM
                );
                workingCascades[cascade] = device.createTrackedTextureTarget(
                        "Metallum working sun shadow cascade " + cascade,
                        budget.resolution(),
                        budget.resolution(),
                        true,
                        GpuFormat.R8_UNORM
                );
                if (staticCascades[cascade].getDepthTexture().getFormat() != GpuFormat.D32_FLOAT
                        || workingCascades[cascade].getDepthTexture().getFormat()
                        != GpuFormat.D32_FLOAT) {
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
                    staticCascades,
                    workingCascades,
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
            for (TextureTarget target : staticCascades) {
                if (target != null) {
                    target.destroyBuffers();
                }
            }
            for (TextureTarget target : workingCascades) {
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
        this.cacheDecision = this.cache.prepare(planned);
        SunShadowFrame selected = this.cacheDecision.workingFrame();
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
            putMatrix(packet, cascade * 64, selected.shadowFromView(cascade));
        }
        Vector3f toLight = selected.toLightView();
        putVec4(packet, EnvironmentShadowBindingAbi.DIRECTION_AND_FLAGS_OFFSET,
                toLight.x, toLight.y, toLight.z, selected.needsShadowPass() ? 1.0f : 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.DIRECTIONAL_RADIANCE_OFFSET,
                environment.directionalRed(), environment.directionalGreen(),
                environment.directionalBlue(), environment.moon() ? 1.0f : 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.SKY_IRRADIANCE_OFFSET,
                environment.skyRed(), environment.skyGreen(), environment.skyBlue(), 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.AMBIENT_RADIANCE_OFFSET,
                environment.ambientRed(), environment.ambientGreen(), environment.ambientBlue(), 0.0f);
        putVec4(packet, EnvironmentShadowBindingAbi.CASCADE_SPLITS_OFFSET,
                selected.cascadeSplit(0), selected.cascadeSplit(1), selected.cascadeSplit(2),
                selected.cascadeSplit(selected.cascadeCount() - 1));
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
                selected.cascadeCount(),
                (int) selected.lightingGeneration(),
                flags);
        Vector3f worldUp = selected.worldUpView();
        putVec4(packet, EnvironmentShadowBindingAbi.WORLD_UP_AND_MEDIUM_OFFSET,
                worldUp.x, worldUp.y, worldUp.z, environment.medium().ordinal());
        putVec4(packet, EnvironmentShadowBindingAbi.CASCADE_NORMAL_BIAS_OFFSET,
                selected.cascadeReceiverNormalBias(0),
                selected.cascadeReceiverNormalBias(1),
                selected.cascadeReceiverNormalBias(2),
                this.budget.receiverNormalBiasTexels());
        this.materialTimeSeconds = advanceMaterialTimeSeconds(
                this.materialTimeSeconds, frameState.deltaSeconds());
        this.materialRainWetness = SurfaceMaterialPolicy.smoothRainWetness(
                this.materialRainWetness,
                environment.rain(),
                (float) Math.clamp(frameState.deltaSeconds(), 0.0, 0.25)
        );
        putVec4(packet, EnvironmentShadowBindingAbi.MATERIAL_WEATHER_AND_TIME_OFFSET,
                this.materialRainWetness, environment.thunder(),
                (float) this.materialTimeSeconds, 0.0f);
        putInt4(packet, EnvironmentShadowBindingAbi.MATERIAL_CONTRACT_OFFSET,
                EnvironmentShadowBindingAbi.MATERIAL_CONTRACT_VERSION,
                environment.profile().ordinal(),
                environment.medium().ordinal(),
                0);
        this.frame = selected;
        this.renderedSubmitIndex = selected.needsShadowPass()
                ? Long.MIN_VALUE
                : selected.submitIndex();
        return selected;
    }

    SunShadowFrame frameForSubmit(final long submitIndex) {
        ensureOpen();
        return this.frame != null && this.frame.submitIndex() == submitIndex ? this.frame : null;
    }

    RenderTarget target(final int cascade) {
        ensureOpen();
        if (cascade < 0 || cascade >= this.workingCascades.length) {
            throw new IllegalArgumentException("Invalid shadow cascade " + cascade);
        }
        return this.workingCascades[cascade];
    }

    RenderTarget staticTarget(final int cascade) {
        ensureOpen();
        if (cascade < 0 || cascade >= this.staticCascades.length) {
            throw new IllegalArgumentException("Invalid static shadow cascade " + cascade);
        }
        return this.staticCascades[cascade];
    }

    SunShadowFrame staticFrameForSubmit(final long submitIndex) {
        ensureOpen();
        return this.cacheDecision != null && this.frame != null && this.frame.submitIndex() == submitIndex
                ? this.cacheDecision.staticFrame() : null;
    }

    boolean requiresStaticRefresh(final long submitIndex) {
        ensureOpen();
        return this.cacheDecision != null && this.frame != null && this.frame.submitIndex() == submitIndex
                && this.cacheDecision.staticRefresh();
    }

    void invalidateVoxelBatch(final VoxelUploadBatch batch) {
        ensureOpen();
        this.cache.invalidateVoxelBatch(batch);
    }

    void invalidate() {
        ensureOpen();
        this.cache.invalidate();
    }

    SunShadowCache.Telemetry cacheTelemetry() {
        ensureOpen();
        return this.cache.telemetry();
    }

    long staticUpdates() {
        ensureOpen();
        return this.cache.staticUpdates();
    }

    long staticReuses() {
        ensureOpen();
        return this.cache.staticReuses();
    }

    long dynamicUpdates() {
        ensureOpen();
        return this.cache.dynamicUpdates();
    }

    long blockInvalidations() {
        ensureOpen();
        return this.cache.blockInvalidations();
    }

    long resourceBytes() {
        ensureOpen();
        return this.cache.resourceBytes();
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
        if (this.cacheDecision == null) {
            throw new IllegalStateException("Rendered shadow pass has no cache decision");
        }
        this.cache.complete(this.cacheDecision);
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
            TextureTarget target = this.workingCascades[Math.min(
                    cascade, this.workingCascades.length - 1
            )];
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
        this.cacheDecision = null;
        this.comparisonSampler.close();
        this.projectionBuffer.close();
        for (TextureTarget target : this.staticCascades) {
            target.destroyBuffers();
        }
        for (TextureTarget target : this.workingCascades) {
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
