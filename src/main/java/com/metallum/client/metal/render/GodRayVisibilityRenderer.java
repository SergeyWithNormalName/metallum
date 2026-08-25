package com.metallum.client.metal.render;

import com.metallum.client.lighting.GodRayVisibilityDiagnostic;
import com.metallum.client.lighting.GodRayVisibilityDiagnostic.ExecutionStatus;
import com.metallum.client.lighting.SunShadowFrame;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metal.render.mtl.MTLColorWriteMask;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.metallum.client.metal.render.mtl.MTLPrimitiveType;
import com.metallum.client.metal.render.mtl.MTLRenderCommandEncoder;
import com.metallum.client.metal.render.mtl.MTLRenderPipelineDescriptor;
import com.metallum.client.renderer.temporal.FrameState;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.GpuFormat;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Executes the low-resolution Directional World-Space God-Ray Visibility Volume
 * render pass and diagnostic visualizer (Stages GOD-RAYS-1 and GOD-RAYS-1.1).
 *
 * <p>Integrates Sun/Moon CSM point visibility along camera view rays using stratified
 * midpoint sampling ($u_i = (i + 0.5) / N$), ray endpoints derived from the scene
 * depth snapshot ($[z_{near}, t_{end}]$), and a dedicated POINT+GreaterEqual sampler.
 * Supports multi-mode false-color visualization and telemetry reporting.</p>
 */
public final class GodRayVisibilityRenderer implements AutoCloseable {
    public static final int UNIFORMS_BYTES = 320;
    public static final int BALANCED_SAMPLE_COUNT = GodRayVisibilityDiagnostic.DEFAULT_SAMPLE_COUNT;

    private final MetalDevice device;
    private final MetalGpuBuffer uniformsRing;
    private MemorySegment visibilityPipeline = MemorySegment.NULL;
    private MemorySegment froxelBuildPipeline = MemorySegment.NULL;
    private MemorySegment visualizePipelineFp16 = MemorySegment.NULL;
    private MemorySegment visualizePipelineRgba8 = MemorySegment.NULL;
    private MemorySegment visualizePipelineBgra8 = MemorySegment.NULL;
    private MemorySegment visualizeAdditivePipelineFp16 = MemorySegment.NULL;
    private MemorySegment visualizeAdditivePipelineRgba8 = MemorySegment.NULL;
    private MemorySegment visualizeAdditivePipelineBgra8 = MemorySegment.NULL;
    private boolean closed;

    public GodRayVisibilityRenderer(final MetalDevice device) {
        this.device = Objects.requireNonNull(device, "device");
        this.uniformsRing = new MetalGpuBuffer(
                device,
                GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                (long) UNIFORMS_BYTES * MetalCommandEncoder.MAX_SUBMITS_IN_FLIGHT
        );
    }

    private MemorySegment ensureVisibilityPipeline() {
        if (!MetalNativeBridge.isNullHandle(this.visibilityPipeline)) {
            return this.visibilityPipeline;
        }
        MemorySegment vertexFunc = this.device.getOrCompileFunction("", "metallum_hdr_vs");
        MemorySegment fragmentFunc = this.device.getOrCompileFunction("", "metallum_god_ray_visibility_fs");
        if (MetalNativeBridge.isNullHandle(vertexFunc) || MetalNativeBridge.isNullHandle(fragmentFunc)) {
            throw new IllegalStateException("Failed to resolve God-Ray visibility shader functions");
        }
        try (MTLRenderPipelineDescriptor desc = new MTLRenderPipelineDescriptor()) {
            desc.setCompiledFunctions(vertexFunc, fragmentFunc);
            desc.setAttachmentFormats(
                    MTLPixelFormat.R16Float,
                    MTLPixelFormat.Invalid,
                    MTLPixelFormat.Invalid,
                    MTLPixelFormat.Invalid
            );
            desc.disableBlending(0, MTLColorWriteMask.All.value);
            this.visibilityPipeline = MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    this.device.metalDeviceHandle(),
                    desc.handle()
            );
            if (MetalNativeBridge.isNullHandle(this.visibilityPipeline)) {
                throw new IllegalStateException("Failed to create God-Ray visibility pipeline state");
            }
            return this.visibilityPipeline;
        }
    }

    private MemorySegment ensureFroxelBuildPipeline() {
        if (!MetalNativeBridge.isNullHandle(this.froxelBuildPipeline)) {
            return this.froxelBuildPipeline;
        }
        MemorySegment vertexFunc = this.device.getOrCompileFunction("", "metallum_hdr_vs");
        MemorySegment fragmentFunc = this.device.getOrCompileFunction("", "metallum_god_ray_froxel_build_fs");
        if (MetalNativeBridge.isNullHandle(vertexFunc) || MetalNativeBridge.isNullHandle(fragmentFunc)) {
            throw new IllegalStateException("Failed to resolve God-Ray froxel build shader functions");
        }
        try (MTLRenderPipelineDescriptor desc = new MTLRenderPipelineDescriptor()) {
            desc.setCompiledFunctions(vertexFunc, fragmentFunc);
            desc.setAttachmentFormats(
                    MTLPixelFormat.R16Float,
                    MTLPixelFormat.Invalid,
                    MTLPixelFormat.Invalid,
                    MTLPixelFormat.Invalid
            );
            desc.disableBlending(0, MTLColorWriteMask.All.value);
            this.froxelBuildPipeline = MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    this.device.metalDeviceHandle(),
                    desc.handle()
            );
            if (MetalNativeBridge.isNullHandle(this.froxelBuildPipeline)) {
                throw new IllegalStateException("Failed to create God-Ray froxel build pipeline state");
            }
            return this.froxelBuildPipeline;
        }
    }

    private MTLPixelFormat toPixelFormat(final GpuFormat format) {
        if (format == GpuFormat.RGBA16_FLOAT) {
            return MTLPixelFormat.RGBA16Float;
        } else {
            return MTLPixelFormat.RGBA8Unorm;
        }
    }

    private MemorySegment ensureVisualizePipeline(final MTLPixelFormat format, final boolean additive) {
        if (additive) {
            if (format == MTLPixelFormat.RGBA16Float && !MetalNativeBridge.isNullHandle(this.visualizeAdditivePipelineFp16)) {
                return this.visualizeAdditivePipelineFp16;
            }
            if (format != MTLPixelFormat.RGBA16Float && !MetalNativeBridge.isNullHandle(this.visualizeAdditivePipelineRgba8)) {
                return this.visualizeAdditivePipelineRgba8;
            }
        } else {
            if (format == MTLPixelFormat.RGBA16Float && !MetalNativeBridge.isNullHandle(this.visualizePipelineFp16)) {
                return this.visualizePipelineFp16;
            }
            if (format != MTLPixelFormat.RGBA16Float && !MetalNativeBridge.isNullHandle(this.visualizePipelineRgba8)) {
                return this.visualizePipelineRgba8;
            }
        }

        MemorySegment vertexFunc = this.device.getOrCompileFunction("", "metallum_hdr_vs");
        MemorySegment fragmentFunc = this.device.getOrCompileFunction("", "metallum_god_ray_visualize_fs");
        if (MetalNativeBridge.isNullHandle(vertexFunc) || MetalNativeBridge.isNullHandle(fragmentFunc)) {
            throw new IllegalStateException("Failed to resolve God-Ray visualize shader functions");
        }
        try (MTLRenderPipelineDescriptor desc = new MTLRenderPipelineDescriptor()) {
            desc.setCompiledFunctions(vertexFunc, fragmentFunc);
            desc.setAttachmentFormats(
                    format,
                    MTLPixelFormat.Invalid,
                    MTLPixelFormat.Invalid,
                    MTLPixelFormat.Invalid
            );
            if (additive) {
                desc.setBlendState(
                        0,
                        com.metallum.client.metal.render.mtl.MTLBlendFactor.One,
                        com.metallum.client.metal.render.mtl.MTLBlendFactor.One,
                        com.metallum.client.metal.render.mtl.MTLBlendOperation.Add,
                        com.metallum.client.metal.render.mtl.MTLBlendFactor.One,
                        com.metallum.client.metal.render.mtl.MTLBlendFactor.One,
                        com.metallum.client.metal.render.mtl.MTLBlendOperation.Add,
                        MTLColorWriteMask.All.value
                );
            } else {
                desc.disableBlending(0, MTLColorWriteMask.All.value);
            }
            MemorySegment pso = MetalNativeBridge.metallum_MTLDevice_makeRenderPipelineState(
                    this.device.metalDeviceHandle(),
                    desc.handle()
            );
            if (MetalNativeBridge.isNullHandle(pso)) {
                throw new IllegalStateException("Failed to create God-Ray visualize pipeline state for format " + format);
            }
            if (additive) {
                if (format == MTLPixelFormat.RGBA16Float) {
                    this.visualizeAdditivePipelineFp16 = pso;
                } else if (format == MTLPixelFormat.BGRA8Unorm) {
                    this.visualizeAdditivePipelineBgra8 = pso;
                } else {
                    this.visualizeAdditivePipelineRgba8 = pso;
                }
            } else {
                if (format == MTLPixelFormat.RGBA16Float) {
                    this.visualizePipelineFp16 = pso;
                } else if (format == MTLPixelFormat.BGRA8Unorm) {
                    this.visualizePipelineBgra8 = pso;
                } else {
                    this.visualizePipelineRgba8 = pso;
                }
            }
            return pso;
        }
    }

    public void render(
            @Nullable final TextureTarget lowResTarget,
            @Nullable final MetalGpuTexture sceneDepthSnapshot,
            @Nullable final SunShadowGpuResources sunShadowResources,
            @Nullable final FrameState frameState,
            final int inFlightSlot,
            @Nullable final MetalGpuTexture mainColorToVisualize
    ) {
        if (this.closed || !GodRayVisibilityDiagnostic.isActive()) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.INACTIVE);
            return;
        }
        GodRayVisibilityDiagnostic.logStartupIfNeeded();

        if (sceneDepthSnapshot == null || sceneDepthSnapshot.isClosed()) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_BUT_NO_DEPTH);
            return;
        }
        if (sunShadowResources == null) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_BUT_NO_CSM);
            return;
        }
        SunShadowFrame shadowFrame = sunShadowResources.frameForSubmit(this.device.currentSubmitIndex());
        if (shadowFrame == null || !shadowFrame.needsShadowPass()) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_BUT_CSM_NOT_READY);
            return;
        }
        if (lowResTarget == null) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_BUT_NO_TARGET);
            return;
        }
        if (frameState == null) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_BUT_NO_DEPTH);
            return;
        }

        int lowWidth = lowResTarget.width;
        int lowHeight = lowResTarget.height;
        if (lowWidth <= 0 || lowHeight <= 0) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_BUT_NO_TARGET);
            return;
        }

        double[] projElements = frameState.currentTransforms().unjitteredProjection().elements();
        float[] projFloats = new float[16];
        for (int i = 0; i < 16; i++) {
            projFloats[i] = (float) projElements[i];
        }
        Matrix4f projJoml = new Matrix4f().set(projFloats);
        Matrix4f invProj = new Matrix4f(projJoml).invert();

        double[] viewElements = frameState.currentTransforms().view().elements();
        float[] viewFloats = new float[16];
        for (int i = 0; i < 16; i++) {
            viewFloats[i] = (float) viewElements[i];
        }
        Matrix4f viewJoml = new Matrix4f().set(viewFloats);
        Matrix4f viewToWorld = new Matrix4f(viewJoml).invert();

        float camX = (float) frameState.currentCameraPosition().x();
        float camY = (float) frameState.currentCameraPosition().y();
        float camZ = (float) frameState.currentCameraPosition().z();

        float froxelCellSize = GodRayVisibilityDiagnostic.froxelCellSize();
        int froxelDimX = 64;
        int froxelDimY = 36;
        int froxelDimZ = 32;
        float gridExtentX = froxelDimX * froxelCellSize;
        float gridExtentY = froxelDimY * froxelCellSize;
        float gridExtentZ = froxelDimZ * froxelCellSize;

        float originX = (float) Math.floor((camX - 0.5f * gridExtentX) / froxelCellSize) * froxelCellSize;
        float originY = (float) Math.floor((camY - 0.5f * gridExtentY) / froxelCellSize) * froxelCellSize;
        float originZ = (float) Math.floor((camZ - 0.5f * gridExtentZ) / froxelCellSize) * froxelCellSize;

        float nearPlane = (float) frameState.nearPlane();
        float farPlane = (float) frameState.farPlane();
        float maxVolumetricDistance = farPlane;
        float csmMaxDistance = sunShadowResources.budget().maximumDistance();
        int sampleCount = GodRayVisibilityDiagnostic.sampleCount();
        int debugMode = GodRayVisibilityDiagnostic.mode().id;
        int metricMode = GodRayVisibilityDiagnostic.metric().id;
        int strategy = GodRayVisibilityDiagnostic.strategy().id;
        float stepLength = GodRayVisibilityDiagnostic.stepLength();

        long uniformOffset = (long) inFlightSlot * UNIFORMS_BYTES;
        ByteBuffer packet = this.uniformsRing.sliceStorage(uniformOffset, UNIFORMS_BYTES);
        packet.clear();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                packet.putFloat(invProj.get(col, row));
            }
        }
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                packet.putFloat(viewToWorld.get(col, row));
            }
        }
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                packet.putFloat(viewJoml.get(col, row));
            }
        }
        packet.putFloat(camX);
        packet.putFloat(camY);
        packet.putFloat(camZ);
        packet.putFloat(1.0f);

        packet.putFloat(originX);
        packet.putFloat(originY);
        packet.putFloat(originZ);
        packet.putFloat(0.0f);

        packet.putFloat(froxelCellSize);
        packet.putFloat(froxelCellSize);
        packet.putFloat(froxelCellSize);
        packet.putFloat(0.0f);

        packet.putInt(froxelDimX);
        packet.putInt(froxelDimY);
        packet.putInt(froxelDimZ);
        packet.putInt(0);

        float intensity = GodRayVisibilityDiagnostic.intensity();
        float sigmaScattering = GodRayVisibilityDiagnostic.scatteringCoefficient();
        float sigmaExtinction = GodRayVisibilityDiagnostic.extinctionCoefficient();
        float anisotropyG = GodRayVisibilityDiagnostic.anisotropyG();

        packet.putFloat(nearPlane);
        packet.putFloat(farPlane);
        packet.putFloat(maxVolumetricDistance);
        packet.putFloat(csmMaxDistance);
        packet.putInt(sampleCount);
        packet.putInt(lowWidth);
        packet.putInt(lowHeight);
        packet.putInt(debugMode);
        packet.putInt(metricMode);
        packet.putInt(strategy);
        packet.putFloat(stepLength);
        packet.putFloat(intensity);
        packet.putFloat(sigmaScattering);
        packet.putFloat(sigmaExtinction);
        packet.putFloat(anisotropyG);
        packet.putFloat(0.0f); // 16-byte alignment padding (total 320 bytes)

        boolean froxelMode = (debugMode == GodRayVisibilityDiagnostic.Mode.FROXEL.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.WORLD_GRID_DEBUG.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.FREEZE_PHYSICAL_CAMERA.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.CAMERA_DELTA_DEBUG.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.HDR_PREVIEW.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.COMPONENT_RAW_VISIBILITY.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.COMPONENT_SCATTERING_ONLY.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.COMPONENT_PHASE_FUNCTION_ONLY.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.COMPONENT_EXTINCTION_ONLY.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.COMPONENT_FINAL_RADIANCE.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.FROXEL_CELL_ID_DEBUG.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.SUN_VISIBILITY_ONLY.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.FROXEL_WORLD_POSITION_DEBUG.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.SUN_SHAFT_ONLY.id);
        boolean additive = (debugMode == GodRayVisibilityDiagnostic.Mode.HDR_PREVIEW.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.COMPONENT_FINAL_RADIANCE.id
                || debugMode == GodRayVisibilityDiagnostic.Mode.SUN_SHAFT_ONLY.id);
        MemorySegment pso;
        MemorySegment vizPso = MemorySegment.NULL;
        try {
            pso = froxelMode ? ensureFroxelBuildPipeline() : ensureVisibilityPipeline();
            if (mainColorToVisualize != null && !mainColorToVisualize.isClosed()) {
                MTLPixelFormat targetFormat = toPixelFormat(mainColorToVisualize.getFormat());
                vizPso = ensureVisualizePipeline(targetFormat, additive);
            }
        } catch (RuntimeException ex) {
            GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_BUT_PIPELINE_FAILURE);
            throw ex;
        }

        MetalGpuTexture visibilityColor = (MetalGpuTexture) lowResTarget.getColorTexture();

        this.device.commandEncoder.endEncoder();
        MTLRenderCommandEncoder encoder = this.device.commandEncoder.commandBuffer().makeRenderCommandEncoder(
                visibilityColor.nativeHandle(),
                null,
                null,
                (double) lowWidth,
                (double) lowHeight,
                1, 0.0F, 0.0F, 0.0F, 1.0F,
                0,
                0, 1.0,
                MetalGpuTimingStage.NONE.nativeId()
        );

        encoder.setRenderPipelineState(pso);
        encoder.setBuffer(this.uniformsRing.nativeHandle(), uniformOffset, 0, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
        if (!froxelMode) {
            encoder.setTexture(sceneDepthSnapshot.nativeHandle(), 0, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
        }
        sunShadowResources.bindGodRayVisibility(encoder, inFlightSlot);

        encoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
        encoder.endEncoding();

        if (mainColorToVisualize != null && !mainColorToVisualize.isClosed() && !MetalNativeBridge.isNullHandle(vizPso)) {
            int mainWidth = mainColorToVisualize.getWidth(0);
            int mainHeight = mainColorToVisualize.getHeight(0);
            MTLRenderCommandEncoder vizEncoder = this.device.commandEncoder.commandBuffer().makeRenderCommandEncoder(
                    mainColorToVisualize.nativeHandle(),
                    null,
                    null,
                    (double) mainWidth,
                    (double) mainHeight,
                    0, 0.0F, 0.0F, 0.0F, 1.0F,
                    0,
                    0, 1.0,
                    MetalGpuTimingStage.NONE.nativeId()
            );
            vizEncoder.setRenderPipelineState(vizPso);
            vizEncoder.setTexture(visibilityColor.nativeHandle(), 0, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            vizEncoder.setTexture(sceneDepthSnapshot.nativeHandle(), 1, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            vizEncoder.setBuffer(this.uniformsRing.nativeHandle(), uniformOffset, 0, MetalCompiledRenderPipeline.STAGE_FRAGMENT);
            sunShadowResources.bindGodRayVisibility(vizEncoder, inFlightSlot);
            vizEncoder.drawPrimitives(MTLPrimitiveType.Triangle, 0, 3, 1, 0);
            vizEncoder.endEncoding();
        }

        GodRayVisibilityDiagnostic.reportStatus(ExecutionStatus.ACTIVE_AND_RENDERED);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        this.uniformsRing.close();
        if (!MetalNativeBridge.isNullHandle(this.visibilityPipeline)) {
            MetalNativeBridge.metallum_release_object(this.visibilityPipeline);
            this.visibilityPipeline = MemorySegment.NULL;
        }
        if (!MetalNativeBridge.isNullHandle(this.froxelBuildPipeline)) {
            MetalNativeBridge.metallum_release_object(this.froxelBuildPipeline);
            this.froxelBuildPipeline = MemorySegment.NULL;
        }
        if (!MetalNativeBridge.isNullHandle(this.visualizePipelineFp16)) {
            MetalNativeBridge.metallum_release_object(this.visualizePipelineFp16);
            this.visualizePipelineFp16 = MemorySegment.NULL;
        }
        if (!MetalNativeBridge.isNullHandle(this.visualizePipelineRgba8)) {
            MetalNativeBridge.metallum_release_object(this.visualizePipelineRgba8);
            this.visualizePipelineRgba8 = MemorySegment.NULL;
        }
        if (!MetalNativeBridge.isNullHandle(this.visualizePipelineBgra8)) {
            MetalNativeBridge.metallum_release_object(this.visualizePipelineBgra8);
            this.visualizePipelineBgra8 = MemorySegment.NULL;
        }
    }
}
