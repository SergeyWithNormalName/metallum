package com.metallum.client.metal.render.bridge;

import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.metal.render.mtl.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Environment(EnvType.CLIENT)
public final class MetalNativeBridge {
    private static final String RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
    private static final String BUILTIN_LIBRARY_RESOURCE_PATH = "/natives/macos/metallum.metallib";
    private static final String[] BUILTIN_SHADER_RESOURCE_PATHS = {
            "/natives/macos/shaders/MetallumPresent.metal",
            "/natives/macos/shaders/MetallumHdrEffects.metal",
            "/natives/macos/shaders/MetallumClear.metal",
            "/natives/macos/shaders/MetallumSodiumLightPatch.metal",
            "/natives/macos/shaders/MetallumClusterBuild.metal",
            "/natives/macos/shaders/MetallumVoxelOccupancy.metal",
            "/natives/macos/shaders/MetallumDynamicVoxelShadow.metal"
    };
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final Linker LINKER = Linker.nativeLinker();

    static {
        try {
            Path tempDirectory = Files.createTempDirectory("metallum-native-");
            tempDirectory.toFile().deleteOnExit();
            Path shaderDirectory = Files.createDirectories(tempDirectory.resolve("shaders"));
            shaderDirectory.toFile().deleteOnExit();

            Path tempLib = tempDirectory.resolve("libmetallum.dylib");
            tempLib.toFile().deleteOnExit();
            copyRequiredResource(RESOURCE_PATH, tempLib);

            Path tempMetallib = tempDirectory.resolve("metallum.metallib");
            if (copyOptionalResource(BUILTIN_LIBRARY_RESOURCE_PATH, tempMetallib)) {
                tempMetallib.toFile().deleteOnExit();
            }
            for (String shaderResourcePath : BUILTIN_SHADER_RESOURCE_PATHS) {
                Path shaderPath = shaderDirectory.resolve(Path.of(shaderResourcePath).getFileName());
                copyRequiredResource(shaderResourcePath, shaderPath);
                shaderPath.toFile().deleteOnExit();
            }

            SymbolLookup lookup = SymbolLookup.libraryLookup(tempLib, Arena.global());


            createSystemDefaultDevice = downcall(lookup, "metallum_create_system_default_device", FunctionDescriptor.of(ValueLayout.ADDRESS));
            copyDeviceName = downcall(lookup, "metallum_copy_device_name", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            NSWindowBackingScaleFactor = downcall(lookup, "metallum_NSWindow_backingScaleFactor", FunctionDescriptor.of(DOUBLE, ValueLayout.ADDRESS));
            createEdrMonitor = downcallWithoutCritical(lookup, "metallum_create_edr_monitor", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            EDRMonitorQuery = downcallWithoutCritical(
                    lookup,
                    "metallum_EDRMonitor_query",
                    FunctionDescriptor.of(LONG, ValueLayout.ADDRESS)
            );
            rendererCapabilitiesV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_renderer_capabilities_v1",
                    FunctionDescriptor.of(LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            createMetalLayer = downcall(lookup, "metallum_create_metal_layer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, DOUBLE));
            fullscreenCreate = downcall(lookup, "metallum_fullscreen_create", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            fullscreenSet = downcall(lookup, "metallum_fullscreen_set", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, INT));
            fullscreenToggle = downcall(lookup, "metallum_fullscreen_toggle", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            fullscreenQuery = downcallWithoutCritical(lookup, "metallum_fullscreen_query", FunctionDescriptor.of(LONG, ValueLayout.ADDRESS));
            fullscreenRelease = downcall(lookup, "metallum_fullscreen_release", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            NSViewSetMetalLayer = downcall(lookup, "metallum_NSView_setMetalLayer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            NSViewClearLayer = downcall(lookup, "metallum_NSView_clearLayer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            setDebugLabelsEnabled = downcall(lookup, "metallum_set_debug_labels_enabled", FunctionDescriptor.ofVoid(INT));
            consumeDrsCompletedFrameTimeSeconds = downcallWithoutCritical(
                    lookup,
                    "metallum_drs_consume_completed_frame_time_seconds",
                    FunctionDescriptor.of(DOUBLE)
            );
            setGpuTimingBenchmarkState = downcall(
                    lookup,
                    "metallum_gpu_timing_set_benchmark_state",
                    FunctionDescriptor.ofVoid(INT, INT, ValueLayout.ADDRESS)
            );
            recordJavaWorkload = downcall(
                    lookup,
                    "metallum_gpu_timing_record_java_workload",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            LONG,
                            LONG,
                            LONG,
                            LONG,
                            LONG,
                            LONG,
                            LONG
                    )
            );
            validateFrameGraphV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_validate_frame_graph_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG)
            );
            validateFrameStateV3 = downcallWithoutCritical(
                    lookup,
                    "metallum_validate_frame_state_v3",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG)
            );
            setFrameStateV3 = downcallWithoutCritical(
                    lookup,
                    "metallum_set_frame_state_v3",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG)
            );
            lightingBatchAbiVersionV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_batch_abi_version_v1",
                    FunctionDescriptor.of(INT)
            );
            lightingLayoutV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_layout_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG)
            );
            lightingCreateContextV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_create_context_v1",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG,
                            INT,
                            INT,
                            INT,
                            INT,
                            INT
                    )
            );
            lightingReleaseContextV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_release_context_v1",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );
            lightingContextBufferV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_context_buffer_v1",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT)
            );
            lightingContextBufferBytesV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_context_buffer_bytes_v1",
                    FunctionDescriptor.of(LONG, ValueLayout.ADDRESS, INT)
            );
            lightingUploadAndBuildV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_upload_and_build_v1",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG
                    )
            );
            lightingLastCompletedStatsV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_lighting_last_completed_stats_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG)
            );
            voxelAbiVersionV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_abi_version_v1",
                    FunctionDescriptor.of(INT)
            );
            voxelLayoutV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_layout_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG)
            );
            voxelCreateContextV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_create_context_v1",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG,
                            LONG,
                            LONG,
                            ValueLayout.ADDRESS,
                            LONG,
                            INT,
                            INT,
                            LONG
                    )
            );
            voxelReleaseContextV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_release_context_v1",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );
            voxelContextBufferV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_context_buffer_v1",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT
                    )
            );
            voxelContextBufferBytesV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_context_buffer_bytes_v1",
                    FunctionDescriptor.of(LONG, ValueLayout.ADDRESS, INT, INT)
            );
            voxelUploadApplyV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_upload_apply_v1",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG
                    )
            );
            voxelLastCompletedStatsV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_last_completed_stats_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG)
            );
            voxelDebugChecksumV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_debug_checksum_v1",
                    FunctionDescriptor.of(
                            INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT
                    )
            );
            voxelDebugReadbackV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_voxel_debug_readback_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG)
            );
            dynamicShadowAbiVersionV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_dynamic_shadow_abi_version_v1",
                    FunctionDescriptor.of(INT)
            );
            dynamicShadowLayoutV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_dynamic_shadow_layout_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG)
            );
            dynamicShadowCreateContextV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_dynamic_shadow_create_context_v1",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG
                    )
            );
            dynamicShadowReleaseContextV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_dynamic_shadow_release_context_v1",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );
            dynamicShadowEncodeV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_dynamic_shadow_encode_v1",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG
                    )
            );
            encodeTemporalDiagnosticsV1 = downcallWithoutCritical(
                    lookup,
                    "metallum_encode_temporal_diagnostics_v1",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS
                    )
            );
            commitEntityVelocityReplay = downcallWithoutCritical(
                    lookup,
                    "metallum_commit_entity_velocity_replay",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT
                    )
            );
            initPipelines = downcallWithoutCritical(
                    lookup,
                    "metallum_init_pipelines",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS)
            );
            releaseDeviceCaches = downcall(lookup, "metallum_release_device_caches", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

            MTLDeviceMaxMemoryAllocationSize = downcall(lookup, "metallum_MTLDevice_maxMemoryAllocationSize", FunctionDescriptor.of(LONG, ValueLayout.ADDRESS));
            MTLFXSpatialScalerSupportsDevice = downcall(
                    lookup,
                    "metallum_MTLFXSpatialScaler_supportsDevice",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS)
            );
            MTLDeviceMakeCommandQueue = downcall(
                    lookup,
                    "metallum_MTLDevice_makeCommandQueue",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLCommandQueueMakeCommandBuffer = downcall(lookup, "metallum_MTLCommandQueue_makeCommandBuffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandBufferCommit = downcall(lookup, "metallum_MTLCommandBuffer_commit", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            createSemaphore = downcall(lookup, "metallum_create_semaphore", FunctionDescriptor.of(ValueLayout.ADDRESS));
            MTLCommandBufferCommitWithSignal = downcall(lookup, "metallum_MTLCommandBuffer_commitWithSignal", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            semaphoreWait = downcallWithoutCritical(lookup, "metallum_semaphore_wait", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
            MTLCommandBufferIsCompleted = downcall(lookup, "metallum_MTLCommandBuffer_isCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
            MTLCommandBufferWaitUntilCompleted = downcallWithoutCritical(lookup, "metallum_MTLCommandBuffer_waitUntilCompleted", FunctionDescriptor.of(INT, ValueLayout.ADDRESS, LONG));
            MTLCommandBufferPushDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_pushDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandBufferPopDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_popDebugGroup", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MTLCommandBufferEncodeSodiumLightLegacyPatchBatch = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodeSodiumLightLegacyPatchBatch_v1",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG,
                            LONG
                    )
            );
            MTLCommandBufferMakeBlitCommandEncoder = downcall(lookup, "metallum_MTLCommandBuffer_makeBlitCommandEncoder", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLCommandEncoderEndEncoding = downcall(lookup, "metallum_MTLCommandEncoder_endEncoding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MTLBlitCommandEncoderCopyFromBufferToBuffer = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromBufferToTexture = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromBufferToTexture",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromTextureToTexture = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromTextureToTexture",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLBlitCommandEncoderCopyFromTextureToBuffer = downcall(
                    lookup,
                    "metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLDeviceMakeDepthStencilState = downcall(lookup, "metallum_MTLDevice_makeDepthStencilState", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLCommandBufferMakeRenderCommandEncoder = downcall(
                    lookup,
                    "metallum_MTLCommandBuffer_makeRenderCommandEncoder",
                    FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            DOUBLE,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            INT,
                            INT,
                            DOUBLE,
                            INT
                    )
            );
            MTLRenderCommandEncoderSetRenderPipelineState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setRenderPipelineState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderSetDepthStencilState = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthStencilState", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderSetDepthBias = downcall(lookup, "metallum_MTLRenderCommandEncoder_setDepthBias", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT));
            MTLRenderCommandEncoderSetFrontFacingWinding = downcall(lookup, "metallum_MTLRenderCommandEncoder_setFrontFacingWinding", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            MTLRenderCommandEncoderSetCullMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setCullMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG));
            MTLRenderCommandEncoderSetTriangleFillMode = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTriangleFillMode", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
            MTLRenderCommandEncoderSetBuffer = downcall(lookup, "metallum_MTLRenderCommandEncoder_setBuffer", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, INT));
            MTLRenderCommandEncoderSetBufferOffset = downcall(lookup, "metallum_MTLRenderCommandEncoder_setBufferOffset", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, INT));
            MTLRenderCommandEncoderSetTexture = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTexture", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLRenderCommandEncoderSetTextureAndSampler = downcall(lookup, "metallum_MTLRenderCommandEncoder_setTextureAndSampler", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
            MTLRenderCommandEncoderApplyResourceBindings = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_applyResourceBindings_v1",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG)
            );
            MTLRenderCommandEncoderSetScissorRect = downcall(lookup, "metallum_MTLRenderCommandEncoder_setScissorRect", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
            MTLRenderCommandEncoderClearDraw = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_clearDraw",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            DOUBLE,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            INT,
                            DOUBLE
                    )
            );
            MTLRenderCommandEncoderDrawPrimitives = downcall(lookup, "metallum_MTLRenderCommandEncoder_drawPrimitives", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG));
            MTLRenderCommandEncoderDrawIndexedPrimitives = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitives",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderMultiDrawIndexed = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_multiDrawIndexed",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawIndexedPrimitivesCpuCommands = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesCpuCommands",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawPrimitivesIndirect = downcall(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG)
            );
            MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLCommandBufferClearColorDepthTexturesRegion = downcall(
                    lookup,
                    "metallum_MTLCommandBuffer_clearColorDepthTexturesRegion",
                    FunctionDescriptor.ofVoid(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            FLOAT,
                            ValueLayout.ADDRESS,
                            DOUBLE,
                            INT,
                            INT,
                            INT,
                            INT,
                            ValueLayout.ADDRESS
                    )
            );
            MTLCommandBufferEncodePresentTextureToDrawable = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodePresentTextureToDrawable",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            INT,
                            INT,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT
                    )
            );
            MTLCommandBufferEncodeHdrUiBackdrop = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodeHdrUiBackdrop",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            INT,
                            INT,
                            INT,
                            INT,
                            INT,
                            FLOAT,
                            FLOAT,
                            FLOAT
                    )
            );
            MTLRenderCommandEncoderEncodePreparedHdrUiBackdrop = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLRenderCommandEncoder_encodePreparedHdrUiBackdrop",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            LONG,
                            LONG
                    )
            );
            MTLCommandBufferMaterializePreparedHdrUiBackdrop = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_materializePreparedHdrUiBackdrop",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS
                    )
            );
            MTLCommandBufferEncodeCoherentMenuBlur = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodeCoherentMenuBlur",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            FLOAT,
                            FLOAT
                    )
            );
            MTLCommandBufferEncodeSpatialScreenshot = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLCommandBuffer_encodeSpatialScreenshot",
                    FunctionDescriptor.of(
                            INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            INT,
                            FLOAT
                    )
            );
            createBuffer = downcall(lookup, "metallum_create_buffer", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
            createStaticGeometryBuffer = downcall(
                    lookup,
                    "metallum_create_static_geometry_buffer",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG)
            );
            createTexture2d = downcall(
                    lookup,
                    "metallum_create_texture_2d",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, INT, ValueLayout.ADDRESS)
            );
            createTextureView = downcall(lookup, "metallum_create_texture_view", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG));
            createBufferTextureView = downcall(
                    lookup,
                    "metallum_create_buffer_texture_view",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG)
            );
            createSampler = downcall(
                    lookup,
                    "metallum_create_sampler",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, INT, DOUBLE, DOUBLE)
            );
            MTLVertexDescriptorCreate = downcall(
                    lookup,
                    "metallum_MTLVertexDescriptor_create",
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            MTLVertexDescriptorSetAttribute = downcall(
                    lookup,
                    "metallum_MTLVertexDescriptor_setAttribute",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLVertexDescriptorSetLayout = downcall(
                    lookup,
                    "metallum_MTLVertexDescriptor_setLayout",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLRenderPipelineDescriptorCreate = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_create",
                    FunctionDescriptor.of(ValueLayout.ADDRESS)
            );
            createShaderFunction = downcallWithoutCritical(
                    lookup,
                    "metallum_create_shader_function",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetCompiledFunctions = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setCompiledFunctions",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetVertexDescriptor = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setVertexDescriptor",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            MTLRenderPipelineDescriptorSetAttachmentFormats = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setAttachmentFormats",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG)
            );
            MTLRenderPipelineDescriptorSetBlendState = downcall(
                    lookup,
                    "metallum_MTLRenderPipelineDescriptor_setBlendState",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT, LONG, LONG, LONG, LONG, LONG, LONG, LONG)
            );
            MTLDeviceMakeRenderPipelineState = downcallWithoutCritical(
                    lookup,
                    "metallum_MTLDevice_makeRenderPipelineState",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS)
            );
            configureLayer = downcallWithoutCritical(
                    lookup,
                    "metallum_configure_layer",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, DOUBLE, DOUBLE, INT, INT, FLOAT)
            );
            updateLayerContentsHeadroom = downcallWithoutCritical(
                    lookup,
                    "metallum_update_layer_contents_headroom",
                    FunctionDescriptor.of(INT, ValueLayout.ADDRESS, FLOAT)
            );
            releaseObject = downcall(lookup, "metallum_release_object", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            releaseStaticGeometryBuffer = downcall(
                    lookup,
                    "metallum_release_static_geometry_buffer",
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)
            );
            getBufferContents = downcall(lookup, "metallum_get_buffer_contents", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            createFence = downcall(lookup, "metallum_create_fence", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLRenderCommandEncoderUpdateFence = downcall(lookup, "MTLRenderCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            MTLRenderCommandEncoderWaitForFence = downcallWithoutCritical(lookup, "MTLRenderCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
            MTLBlitCommandEncoderUpdateFence = downcall(lookup, "MTLBlitCommandEncoder_updateFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MTLBlitCommandEncoderWaitForFence = downcallWithoutCritical(lookup, "MTLBlitCommandEncoder_waitForFence", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Metal native bridge", e);
        }
    }

    private static void copyRequiredResource(final String resourcePath, final Path destination) throws IOException {
        if (!copyOptionalResource(resourcePath, destination)) {
            throw new IllegalStateException("Missing native resource: " + resourcePath);
        }
    }

    private static boolean copyOptionalResource(final String resourcePath, final Path destination) throws IOException {
        try (InputStream stream = MetalNativeBridge.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                return false;
            }
            Files.copy(stream, destination, StandardCopyOption.REPLACE_EXISTING);
            return true;
        }
    }


    private static final MethodHandle createSystemDefaultDevice;
    private static final MethodHandle copyDeviceName;
    private static final MethodHandle NSWindowBackingScaleFactor;
    private static final MethodHandle createEdrMonitor;
    private static final MethodHandle EDRMonitorQuery;
    private static final MethodHandle rendererCapabilitiesV1;
    private static final MethodHandle createMetalLayer;
    private static final MethodHandle fullscreenCreate;
    private static final MethodHandle fullscreenSet;
    private static final MethodHandle fullscreenToggle;
    private static final MethodHandle fullscreenQuery;
    private static final MethodHandle fullscreenRelease;
    private static final MethodHandle NSViewSetMetalLayer;
    private static final MethodHandle NSViewClearLayer;
    private static final MethodHandle setDebugLabelsEnabled;
    private static final MethodHandle consumeDrsCompletedFrameTimeSeconds;
    private static final MethodHandle setGpuTimingBenchmarkState;
    private static final MethodHandle recordJavaWorkload;
    private static final MethodHandle validateFrameGraphV1;
    private static final MethodHandle validateFrameStateV3;
    private static final MethodHandle setFrameStateV3;
    private static final MethodHandle lightingBatchAbiVersionV1;
    private static final MethodHandle lightingLayoutV1;
    private static final MethodHandle lightingCreateContextV1;
    private static final MethodHandle lightingReleaseContextV1;
    private static final MethodHandle lightingContextBufferV1;
    private static final MethodHandle lightingContextBufferBytesV1;
    private static final MethodHandle lightingUploadAndBuildV1;
    private static final MethodHandle lightingLastCompletedStatsV1;
    private static final MethodHandle voxelAbiVersionV1;
    private static final MethodHandle voxelLayoutV1;
    private static final MethodHandle voxelCreateContextV1;
    private static final MethodHandle voxelReleaseContextV1;
    private static final MethodHandle voxelContextBufferV1;
    private static final MethodHandle voxelContextBufferBytesV1;
    private static final MethodHandle voxelUploadApplyV1;
    private static final MethodHandle voxelLastCompletedStatsV1;
    private static final MethodHandle voxelDebugChecksumV1;
    private static final MethodHandle voxelDebugReadbackV1;
    private static final MethodHandle dynamicShadowAbiVersionV1;
    private static final MethodHandle dynamicShadowLayoutV1;
    private static final MethodHandle dynamicShadowCreateContextV1;
    private static final MethodHandle dynamicShadowReleaseContextV1;
    private static final MethodHandle dynamicShadowEncodeV1;
    private static final MethodHandle encodeTemporalDiagnosticsV1;
    private static final MethodHandle commitEntityVelocityReplay;
    private static final MethodHandle MTLDeviceMaxMemoryAllocationSize;
    private static final MethodHandle MTLFXSpatialScalerSupportsDevice;
    private static final MethodHandle MTLDeviceMakeCommandQueue;
    private static final MethodHandle MTLCommandQueueMakeCommandBuffer;
    private static final MethodHandle MTLCommandBufferCommit;
    private static final MethodHandle createSemaphore;
    private static final MethodHandle MTLCommandBufferCommitWithSignal;
    private static final MethodHandle semaphoreWait;
    private static final MethodHandle MTLCommandBufferIsCompleted;
    private static final MethodHandle MTLCommandBufferWaitUntilCompleted;
    private static final MethodHandle MTLCommandBufferPushDebugGroup;
    private static final MethodHandle MTLCommandBufferPopDebugGroup;
    private static final MethodHandle MTLCommandBufferEncodeSodiumLightLegacyPatchBatch;
    private static final MethodHandle MTLCommandBufferMakeBlitCommandEncoder;
    private static final MethodHandle MTLCommandEncoderEndEncoding;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromBufferToBuffer;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromBufferToTexture;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromTextureToTexture;
    private static final MethodHandle MTLBlitCommandEncoderCopyFromTextureToBuffer;
    private static final MethodHandle MTLDeviceMakeDepthStencilState;
    private static final MethodHandle MTLCommandBufferMakeRenderCommandEncoder;
    private static final MethodHandle MTLRenderCommandEncoderSetRenderPipelineState;
    private static final MethodHandle MTLRenderCommandEncoderSetDepthStencilState;
    private static final MethodHandle MTLRenderCommandEncoderSetDepthBias;
    private static final MethodHandle MTLRenderCommandEncoderSetFrontFacingWinding;
    private static final MethodHandle MTLRenderCommandEncoderSetCullMode;
    private static final MethodHandle MTLRenderCommandEncoderSetTriangleFillMode;
    private static final MethodHandle MTLRenderCommandEncoderSetBuffer;
    private static final MethodHandle MTLRenderCommandEncoderSetBufferOffset;
    private static final MethodHandle MTLRenderCommandEncoderSetTexture;
    private static final MethodHandle MTLRenderCommandEncoderSetTextureAndSampler;
    private static final MethodHandle MTLRenderCommandEncoderApplyResourceBindings;
    private static final MethodHandle MTLRenderCommandEncoderSetScissorRect;
    private static final MethodHandle MTLRenderCommandEncoderClearDraw;
    private static final MethodHandle MTLRenderCommandEncoderDrawPrimitives;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitives;
    private static final MethodHandle MTLRenderCommandEncoderMultiDrawIndexed;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect;
    private static final MethodHandle MTLRenderCommandEncoderDrawIndexedPrimitivesCpuCommands;
    private static final MethodHandle MTLRenderCommandEncoderDrawPrimitivesIndirect;
    private static final MethodHandle MTLCommandBufferClearColorDepthTexturesRegion;
    private static final MethodHandle MTLCommandBufferEncodePresentTextureToDrawable;
    private static final MethodHandle MTLCommandBufferEncodeHdrUiBackdrop;
    private static final MethodHandle MTLRenderCommandEncoderEncodePreparedHdrUiBackdrop;
    private static final MethodHandle MTLCommandBufferMaterializePreparedHdrUiBackdrop;
    private static final MethodHandle MTLCommandBufferEncodeCoherentMenuBlur;
    private static final MethodHandle MTLCommandBufferEncodeSpatialScreenshot;
    private static final MethodHandle createBuffer;
    private static final MethodHandle createStaticGeometryBuffer;
    private static final MethodHandle createTexture2d;
    private static final MethodHandle createTextureView;
    private static final MethodHandle createBufferTextureView;
    private static final MethodHandle createSampler;
    private static final MethodHandle MTLVertexDescriptorCreate;
    private static final MethodHandle MTLVertexDescriptorSetAttribute;
    private static final MethodHandle MTLVertexDescriptorSetLayout;
    private static final MethodHandle MTLRenderPipelineDescriptorCreate;
    private static final MethodHandle createShaderFunction;
    private static final MethodHandle MTLRenderPipelineDescriptorSetCompiledFunctions;
    private static final MethodHandle MTLRenderPipelineDescriptorSetVertexDescriptor;
    private static final MethodHandle MTLRenderPipelineDescriptorSetAttachmentFormats;
    private static final MethodHandle MTLRenderPipelineDescriptorSetBlendState;
    private static final MethodHandle MTLDeviceMakeRenderPipelineState;
    private static final MethodHandle configureLayer;
    private static final MethodHandle updateLayerContentsHeadroom;
    private static final MethodHandle releaseObject;
    private static final MethodHandle releaseStaticGeometryBuffer;
    private static final MethodHandle getBufferContents;
    private static final MethodHandle createFence;
    private static final MethodHandle MTLRenderCommandEncoderUpdateFence;
    private static final MethodHandle MTLRenderCommandEncoderWaitForFence;
    private static final MethodHandle MTLBlitCommandEncoderUpdateFence;
    private static final MethodHandle MTLBlitCommandEncoderWaitForFence;
    private static final MethodHandle initPipelines;
    private static final MethodHandle releaseDeviceCaches;


    private static MethodHandle downcall(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor, Linker.Option.critical(false));
    }

    private static MethodHandle downcallWithoutCritical(final SymbolLookup lookup, final String symbol, final FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.findOrThrow(symbol), descriptor);
    }

    public static MemorySegment metallum_create_system_default_device() {
        try {
            return (MemorySegment) createSystemDefaultDevice.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_system_default_device", throwable);
        }
    }

    public static String metallum_copy_device_name(final MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocate(256L);
            int result = (int) copyDeviceName.invokeExact(segment(device), buffer, 256L);
            return result == 0 ? buffer.getString(0L) : "";
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_copy_device_name", throwable);
        }
    }

    public static double metallum_NSWindow_backingScaleFactor(final MemorySegment window) {
        try {
            return (double) NSWindowBackingScaleFactor.invokeExact(segment(window));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSWindow_backingScaleFactor", throwable);
        }
    }

    public static MemorySegment metallum_create_edr_monitor(final MemorySegment window) {
        try {
            return (MemorySegment) createEdrMonitor.invokeExact(segment(window));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_edr_monitor", throwable);
        }
    }

    public static EdrCapabilities metallum_EDRMonitor_query(final MemorySegment monitor) {
        if (isNullHandle(monitor)) {
            return EdrCapabilities.SDR;
        }
        try {
            long packed = (long) EDRMonitorQuery.invokeExact(segment(monitor));
            return new EdrCapabilities(
                    Float.intBitsToFloat((int) packed),
                    Float.intBitsToFloat((int) (packed >>> 32))
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_EDRMonitor_query", throwable);
        }
    }

    public static long metallum_renderer_capabilities_v1(
            final MemorySegment device,
            final MemorySegment monitor
    ) {
        try {
            return (long) rendererCapabilitiesV1.invokeExact(segment(device), segment(monitor));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_renderer_capabilities_v1", throwable);
        }
    }

    public static MemorySegment metallum_create_metal_layer(final MemorySegment device, final double contentsScale) {
        try {
            return (MemorySegment) createMetalLayer.invokeExact(segment(device), contentsScale);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_metal_layer", throwable);
        }
    }

    public static MemorySegment metallum_fullscreen_create(final MemorySegment nsWindow, final MemorySegment metalLayer) {
        try {
            return (MemorySegment) fullscreenCreate.invokeExact(segment(nsWindow), segment(metalLayer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_fullscreen_create", throwable);
        }
    }

    public static int metallum_fullscreen_set(final MemorySegment coordinator, final int enabled) {
        try {
            return (int) fullscreenSet.invokeExact(segment(coordinator), enabled);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_fullscreen_set", throwable);
        }
    }

    public static int metallum_fullscreen_toggle(final MemorySegment coordinator) {
        try {
            return (int) fullscreenToggle.invokeExact(segment(coordinator));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_fullscreen_toggle", throwable);
        }
    }

    public static long metallum_fullscreen_query(final MemorySegment coordinator) {
        try {
            return (long) fullscreenQuery.invokeExact(segment(coordinator));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_fullscreen_query", throwable);
        }
    }

    public static void metallum_fullscreen_release(final MemorySegment coordinator) {
        try {
            fullscreenRelease.invokeExact(segment(coordinator));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_fullscreen_release", throwable);
        }
    }

    public static void metallum_NSView_setMetalLayer(final MemorySegment view, final MemorySegment layer) {
        try {
            NSViewSetMetalLayer.invokeExact(segment(view), segment(layer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_setMetalLayer", throwable);
        }
    }

    public static void metallum_NSView_clearLayer(final MemorySegment view) {
        try {
            NSViewClearLayer.invokeExact(segment(view));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_NSView_clearLayer", throwable);
        }
    }

    public static void metallum_set_debug_labels_enabled(final boolean enabled) {
        try {
            setDebugLabelsEnabled.invokeExact(enabled ? 1 : 0);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_set_debug_labels_enabled", throwable);
        }
    }

    /** Returns and clears the newest completed presented-frame GPU duration, or zero when unavailable. */
    public static double metallum_drs_consume_completed_frame_time_seconds() {
        try {
            return (double) consumeDrsCompletedFrameTimeSeconds.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_drs_consume_completed_frame_time_seconds", throwable);
        }
    }

    public static void metallum_gpu_timing_set_benchmark_state(
            final int segmentIndex,
            final int phase,
            final String scalerMode
    ) {
        try (Arena arena = Arena.ofConfined()) {
            setGpuTimingBenchmarkState.invokeExact(segmentIndex, phase, toCString(arena, scalerMode));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_gpu_timing_set_benchmark_state", throwable);
        }
    }

    public static void metallum_gpu_timing_record_java_workload(
            final MemorySegment commandBuffer,
            final long cpuToSharedBytes,
            final long cpuToSharedOperations,
            final long cpuTransientRequestedBytes,
            final long cpuTransientReservedBytes,
            final long gpuTransientRequestedBytes,
            final long gpuTransientReservedBytes,
            final long cpuRenderSubmissionNanos
    ) {
        try {
            recordJavaWorkload.invokeExact(
                    segment(commandBuffer),
                    cpuToSharedBytes,
                    cpuToSharedOperations,
                    cpuTransientRequestedBytes,
                    cpuTransientReservedBytes,
                    gpuTransientRequestedBytes,
                    gpuTransientReservedBytes,
                    cpuRenderSubmissionNanos
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_gpu_timing_record_java_workload", throwable);
        }
    }

    public static int metallum_validate_frame_graph_v1(final MemorySegment packet) {
        if (packet == null || packet.byteSize() == 0L) {
            throw new IllegalArgumentException("Frame graph ABI packet must not be empty");
        }
        try {
            return (int) validateFrameGraphV1.invokeExact(segment(packet), packet.byteSize());
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_validate_frame_graph_v1", throwable);
        }
    }

    public static int metallum_validate_frame_state_v3(final MemorySegment packet) {
        return invokeFrameState("metallum_validate_frame_state_v3", validateFrameStateV3, packet);
    }

    public static int metallum_set_frame_state_v3(final MemorySegment packet) {
        return invokeFrameState("metallum_set_frame_state_v3", setFrameStateV3, packet);
    }

    public static int metallum_lighting_batch_abi_version_v1() {
        try {
            return (int) lightingBatchAbiVersionV1.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_batch_abi_version_v1", throwable);
        }
    }

    public static int metallum_lighting_layout_v1(final MemorySegment output) {
        if (output == null || output.byteSize() == 0L) {
            throw new IllegalArgumentException("Lighting layout packet must not be empty");
        }
        try {
            return (int) lightingLayoutV1.invokeExact(segment(output), output.byteSize());
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_layout_v1", throwable);
        }
    }

    public static MemorySegment metallum_lighting_create_context_v1(
            final MemorySegment device,
            final long generation,
            final int maxLights,
            final int indexCapacity,
            final int clustersX,
            final int clustersY,
            final int clustersZ
    ) {
        try {
            return (MemorySegment) lightingCreateContextV1.invokeExact(
                    segment(device),
                    generation,
                    maxLights,
                    indexCapacity,
                    clustersX,
                    clustersY,
                    clustersZ
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_create_context_v1", throwable);
        }
    }

    public static void metallum_lighting_release_context_v1(final MemorySegment context) {
        try {
            lightingReleaseContextV1.invokeExact(segment(context));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_release_context_v1", throwable);
        }
    }

    public static MemorySegment metallum_lighting_context_buffer_v1(
            final MemorySegment context,
            final int kind
    ) {
        try {
            return (MemorySegment) lightingContextBufferV1.invokeExact(segment(context), kind);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_context_buffer_v1", throwable);
        }
    }

    public static long metallum_lighting_context_buffer_bytes_v1(
            final MemorySegment context,
            final int kind
    ) {
        try {
            return (long) lightingContextBufferBytesV1.invokeExact(segment(context), kind);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_context_buffer_bytes_v1", throwable);
        }
    }

    public static int metallum_lighting_upload_and_build_v1(
            final MemorySegment context,
            final MemorySegment commandBuffer,
            final MemorySegment packet
    ) {
        if (packet == null || packet.byteSize() == 0L) {
            throw new IllegalArgumentException("Lighting upload packet must not be empty");
        }
        try {
            return (int) lightingUploadAndBuildV1.invokeExact(
                    segment(context),
                    segment(commandBuffer),
                    segment(packet),
                    packet.byteSize()
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_upload_and_build_v1", throwable);
        }
    }

    public static int metallum_lighting_last_completed_stats_v1(
            final MemorySegment context,
            final MemorySegment output
    ) {
        if (output == null || output.byteSize() == 0L) {
            throw new IllegalArgumentException("Lighting statistics packet must not be empty");
        }
        try {
            return (int) lightingLastCompletedStatsV1.invokeExact(
                    segment(context),
                    segment(output),
                    output.byteSize()
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_lighting_last_completed_stats_v1", throwable);
        }
    }

    public static int metallum_voxel_abi_version_v1() {
        try {
            return (int) voxelAbiVersionV1.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_abi_version_v1", throwable);
        }
    }

    public static int metallum_voxel_layout_v1(final MemorySegment output) {
        if (output == null || output.byteSize() == 0L) {
            throw new IllegalArgumentException("Voxel layout packet must not be empty");
        }
        try {
            return (int) voxelLayoutV1.invokeExact(segment(output), output.byteSize());
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_layout_v1", throwable);
        }
    }

    public static MemorySegment metallum_voxel_create_context_v1(
            final MemorySegment device,
            final long lightingGeneration,
            final long clipmapGeneration,
            final long worldGeneration,
            final MemorySegment layouts,
            final int levelCount,
            final int maxPatchCount,
            final long stagingBytes
    ) {
        if (layouts == null || layouts.byteSize() == 0L) {
            throw new IllegalArgumentException("Voxel level layouts must not be empty");
        }
        try {
            return (MemorySegment) voxelCreateContextV1.invokeExact(
                    segment(device),
                    lightingGeneration,
                    clipmapGeneration,
                    worldGeneration,
                    segment(layouts),
                    layouts.byteSize(),
                    levelCount,
                    maxPatchCount,
                    stagingBytes
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_create_context_v1", throwable);
        }
    }

    public static void metallum_voxel_release_context_v1(final MemorySegment context) {
        try {
            voxelReleaseContextV1.invokeExact(segment(context));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_release_context_v1", throwable);
        }
    }

    public static MemorySegment metallum_voxel_context_buffer_v1(
            final MemorySegment context,
            final int kind,
            final int index
    ) {
        try {
            return (MemorySegment) voxelContextBufferV1.invokeExact(
                    segment(context), kind, index
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_context_buffer_v1", throwable);
        }
    }

    public static long metallum_voxel_context_buffer_bytes_v1(
            final MemorySegment context,
            final int kind,
            final int index
    ) {
        try {
            return (long) voxelContextBufferBytesV1.invokeExact(
                    segment(context), kind, index
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_context_buffer_bytes_v1", throwable);
        }
    }

    public static int metallum_voxel_upload_apply_v1(
            final MemorySegment context,
            final MemorySegment commandBuffer,
            final MemorySegment packet
    ) {
        if (packet == null || packet.byteSize() == 0L) {
            throw new IllegalArgumentException("Voxel upload packet must not be empty");
        }
        try {
            return (int) voxelUploadApplyV1.invokeExact(
                    segment(context), segment(commandBuffer), segment(packet), packet.byteSize()
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_upload_apply_v1", throwable);
        }
    }

    public static int metallum_voxel_last_completed_stats_v1(
            final MemorySegment context,
            final MemorySegment output
    ) {
        if (output == null || output.byteSize() == 0L) {
            throw new IllegalArgumentException("Voxel statistics packet must not be empty");
        }
        try {
            return (int) voxelLastCompletedStatsV1.invokeExact(
                    segment(context), segment(output), output.byteSize()
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_last_completed_stats_v1", throwable);
        }
    }

    public static int metallum_voxel_debug_checksum_v1(
            final MemorySegment context,
            final MemorySegment commandBuffer,
            final int level,
            final int slot
    ) {
        try {
            return (int) voxelDebugChecksumV1.invokeExact(
                    segment(context), segment(commandBuffer), level, slot
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_debug_checksum_v1", throwable);
        }
    }

    public static int metallum_voxel_debug_readback_v1(
            final MemorySegment context,
            final MemorySegment output
    ) {
        if (output == null || output.byteSize() == 0L) {
            throw new IllegalArgumentException("Voxel debug readback packet must not be empty");
        }
        try {
            return (int) voxelDebugReadbackV1.invokeExact(
                    segment(context), segment(output), output.byteSize()
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_voxel_debug_readback_v1", throwable);
        }
    }

    public static int metallum_dynamic_shadow_abi_version_v1() {
        try {
            return (int) dynamicShadowAbiVersionV1.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_dynamic_shadow_abi_version_v1", throwable);
        }
    }

    public static int metallum_dynamic_shadow_layout_v1(final MemorySegment output) {
        if (output == null || output.byteSize() != 32L) {
            throw new IllegalArgumentException("Dynamic shadow layout packet must be 32 bytes");
        }
        try {
            return (int) dynamicShadowLayoutV1.invokeExact(segment(output), output.byteSize());
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_dynamic_shadow_layout_v1", throwable);
        }
    }

    public static MemorySegment metallum_dynamic_shadow_create_context_v1(
            final MemorySegment device,
            final long atlasSuffixOffset,
            final long atlasSuffixBytes
    ) {
        try {
            return (MemorySegment) dynamicShadowCreateContextV1.invokeExact(
                    segment(device), atlasSuffixOffset, atlasSuffixBytes
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_dynamic_shadow_create_context_v1", throwable);
        }
    }

    public static void metallum_dynamic_shadow_release_context_v1(final MemorySegment context) {
        try {
            dynamicShadowReleaseContextV1.invokeExact(segment(context));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_dynamic_shadow_release_context_v1", throwable);
        }
    }

    public static int metallum_dynamic_shadow_encode_v1(
            final MemorySegment dynamicContext,
            final MemorySegment voxelContext,
            final MemorySegment commandBuffer,
            final MemorySegment atlas,
            final MemorySegment globalFence,
            final MemorySegment packet
    ) {
        if (packet == null || packet.byteSize() == 0L) {
            throw new IllegalArgumentException("Dynamic shadow packet must not be empty");
        }
        try {
            return (int) dynamicShadowEncodeV1.invokeExact(
                    segment(dynamicContext), segment(voxelContext), segment(commandBuffer),
                    segment(atlas), segment(globalFence), segment(packet), packet.byteSize()
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_dynamic_shadow_encode_v1", throwable);
        }
    }

    public static int metallum_encode_temporal_diagnostics_v1(
            final MemorySegment commandBuffer,
            final MemorySegment depthTexture,
            final MemorySegment motionTexture,
            final MemorySegment reactiveTexture,
            final MemorySegment classificationTexture,
            final MemorySegment globalFence
    ) {
        try {
            return (int) encodeTemporalDiagnosticsV1.invokeExact(
                    segment(commandBuffer),
                    segment(depthTexture),
                    segment(motionTexture),
                    segment(reactiveTexture),
                    segment(classificationTexture),
                    segment(globalFence)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_encode_temporal_diagnostics_v1", throwable);
        }
    }

    public static int metallum_commit_entity_velocity_replay(
            final MemorySegment commandBuffer,
            final MemorySegment depthTexture,
            final MemorySegment motionTexture,
            final MemorySegment reactiveTexture,
            final MemorySegment classificationTexture,
            final MemorySegment packetsBuffer,
            final int packetCount
    ) {
        try {
            return (int) commitEntityVelocityReplay.invokeExact(
                    segment(commandBuffer),
                    segment(depthTexture),
                    segment(motionTexture),
                    segment(reactiveTexture),
                    segment(classificationTexture),
                    segment(packetsBuffer),
                    packetCount
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_commit_entity_velocity_replay", throwable);
        }
    }

    private static int invokeFrameState(
            final String symbol,
            final MethodHandle handle,
            final MemorySegment packet
    ) {
        if (packet == null || packet.byteSize() == 0L) {
            throw new IllegalArgumentException("FrameState ABI packet must not be empty");
        }
        try {
            return (int) handle.invokeExact(segment(packet), packet.byteSize());
        } catch (Throwable throwable) {
            throw bridgeFailure(symbol, throwable);
        }
    }

    public static int metallum_init_pipelines(final MemorySegment device) {
        try {
            return (int) initPipelines.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_init_pipelines", throwable);
        }
    }

    public static void metallum_release_device_caches(final MemorySegment device) {
        try {
            releaseDeviceCaches.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_release_device_caches", throwable);
        }
    }


    public static long MTLDevice_maxMemoryAllocationSize(final MemorySegment device) {
        try {
            return (long) MTLDeviceMaxMemoryAllocationSize.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_maxMemoryAllocationSize", throwable);
        }
    }

    public static boolean MTLFXSpatialScaler_supportsDevice(final MemorySegment device) {
        try {
            return (int) MTLFXSpatialScalerSupportsDevice.invokeExact(segment(device)) == 1;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLFXSpatialScaler_supportsDevice", throwable);
        }
    }

    public static MemorySegment MTLDevice_makeCommandQueue(final MemorySegment device, final MemorySegment layer) {
        try {
            return (MemorySegment) MTLDeviceMakeCommandQueue.invokeExact(segment(device), segment(layer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeCommandQueue", throwable);
        }
    }

    public static MemorySegment MTLCommandQueue_makeCommandBuffer(final MemorySegment commandQueue, final String label) {
        try {
            if (label == null) {
                return (MemorySegment) MTLCommandQueueMakeCommandBuffer.invokeExact(
                        segment(commandQueue),
                        MemorySegment.NULL
                );
            }
            try (Arena arena = Arena.ofConfined()) {
                return (MemorySegment) MTLCommandQueueMakeCommandBuffer.invokeExact(
                        segment(commandQueue),
                        toCString(arena, label)
                );
            }
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandQueue_makeCommandBuffer", throwable);
        }
    }

    public static void MTLCommandBuffer_commit(final MemorySegment commandBuffer) {
        try {
            MTLCommandBufferCommit.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_commit", throwable);
        }
    }

    public static MemorySegment metallum_create_semaphore() {
        try {
            return (MemorySegment) createSemaphore.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_semaphore", throwable);
        }
    }

    public static void MTLCommandBuffer_commitWithSignal(final MemorySegment commandBuffer, final MemorySegment semaphore) {
        try {
            MTLCommandBufferCommitWithSignal.invokeExact(segment(commandBuffer), segment(semaphore));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_commitWithSignal", throwable);
        }
    }

    public static int metallum_semaphore_wait(final MemorySegment semaphore, final long timeoutMs) {
        try {
            return (int) semaphoreWait.invokeExact(segment(semaphore), timeoutMs);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_semaphore_wait", throwable);
        }
    }

    public static int MTLCommandBuffer_isCompleted(final MemorySegment commandBuffer) {
        try {
            return (int) MTLCommandBufferIsCompleted.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_isCompleted", throwable);
        }
    }

    public static int MTLCommandBuffer_waitUntilCompleted(final MemorySegment commandBuffer, final long timeoutMs) {
        try {
            return (int) MTLCommandBufferWaitUntilCompleted.invokeExact(segment(commandBuffer), timeoutMs);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_waitUntilCompleted", throwable);
        }
    }

    public static void MTLCommandBuffer_pushDebugGroup(final MemorySegment commandBuffer, final String label) {
        try (Arena arena = Arena.ofConfined()) {
            MTLCommandBufferPushDebugGroup.invokeExact(segment(commandBuffer), toCString(arena, label));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_pushDebugGroup", throwable);
        }
    }

    public static void MTLCommandBuffer_popDebugGroup(final MemorySegment commandBuffer) {
        try {
            MTLCommandBufferPopDebugGroup.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_popDebugGroup", throwable);
        }
    }

    public static int MTLCommandBuffer_encodeSodiumLightLegacyPatchBatch(
            final MemorySegment commandBuffer,
            final MemorySegment globalFence,
            final MemorySegment packet,
            final long commandCount
    ) {
        try {
            return (int) MTLCommandBufferEncodeSodiumLightLegacyPatchBatch.invokeExact(
                    segment(commandBuffer),
                    segment(globalFence),
                    segment(packet),
                    packet.byteSize(),
                    commandCount
            );
        } catch (Throwable throwable) {
            throw bridgeFailure(
                    "metallum_MTLCommandBuffer_encodeSodiumLightLegacyPatchBatch_v1",
                    throwable
            );
        }
    }

    public static MemorySegment MTLCommandBuffer_makeBlitCommandEncoder(final MemorySegment commandBuffer) {
        try {
            return (MemorySegment) MTLCommandBufferMakeBlitCommandEncoder.invokeExact(segment(commandBuffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeBlitCommandEncoder", throwable);
        }
    }

    public static void MTLCommandEncoder_endEncoding(final MemorySegment encoder) {
        try {
            MTLCommandEncoderEndEncoding.invokeExact(segment(encoder));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandEncoder_endEncoding", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromBufferToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long length
    ) {
        try {
            MTLBlitCommandEncoderCopyFromBufferToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(destinationBuffer),
                    destinationOffset,
                    length
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromBufferToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceBuffer,
            final long sourceOffset,
            final MemorySegment texture,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromBufferToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceBuffer),
                    sourceOffset,
                    segment(texture),
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromBufferToTexture", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromTextureToTexture(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final long mipLevel,
            final long sourceX,
            final long sourceY,
            final long destX,
            final long destY,
            final long width,
            final long height
    ) {
        try {
            MTLBlitCommandEncoderCopyFromTextureToTexture.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationTexture),
                    mipLevel,
                    sourceX,
                    sourceY,
                    destX,
                    destY,
                    width,
                    height
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToTexture", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_copyFromTextureToBuffer(
            final MemorySegment blitEncoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationBuffer,
            final long destinationOffset,
            final long mipLevel,
            final long slice,
            final long x,
            final long y,
            final long width,
            final long height,
            final long bytesPerRow,
            final long bytesPerImage
    ) {
        try {
            MTLBlitCommandEncoderCopyFromTextureToBuffer.invokeExact(
                    segment(blitEncoder),
                    segment(sourceTexture),
                    segment(destinationBuffer),
                    destinationOffset,
                    mipLevel,
                    slice,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    bytesPerImage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer", throwable);
        }
    }

    public static MemorySegment metallum_create_buffer(final MemorySegment device, final long length, final long options) {
        try {
            return (MemorySegment) createBuffer.invokeExact(segment(device), length, options);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer", throwable);
        }
    }

    public static MemorySegment metallum_create_static_geometry_buffer(
            final MemorySegment device,
            final long length
    ) {
        try {
            return (MemorySegment) createStaticGeometryBuffer.invokeExact(segment(device), length);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_static_geometry_buffer", throwable);
        }
    }

    public static MemorySegment metallum_create_texture_2d(
            final MemorySegment device,
            final MTLPixelFormat pixelFormat,
            final long width,
            final long height,
            final long depthOrLayers,
            final long mipLevels,
            final long cubeCompatible,
            final long usage,
            final MTLStorageMode storageMode,
            final boolean trackedHazards,
            final String label
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) createTexture2d.invokeExact(
                    segment(device),
                    pixelFormat.value,
                    width,
                    height,
                    depthOrLayers,
                    mipLevels,
                    cubeCompatible,
                    usage,
                    storageMode.value,
                    trackedHazards ? 1 : 0,
                    toCString(arena, label)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_2d", throwable);
        }
    }

    public static MemorySegment metallum_create_texture_view(final MemorySegment texture, final long baseMipLevel, final long mipLevelCount) {
        try {
            return (MemorySegment) createTextureView.invokeExact(segment(texture), baseMipLevel, mipLevelCount);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_texture_view", throwable);
        }
    }

    public static MemorySegment metallum_create_buffer_texture_view(
            final MemorySegment buffer,
            final long pixelFormat,
            final long offset,
            final long width,
            final long height,
            final long bytesPerRow
    ) {
        try {
            return (MemorySegment) createBufferTextureView.invokeExact(segment(buffer), pixelFormat, offset, width, height, bytesPerRow);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_buffer_texture_view", throwable);
        }
    }

    public static MemorySegment metallum_create_sampler(
            final MemorySegment device,
            final MTLSamplerAddressMode addressModeU,
            final MTLSamplerAddressMode addressModeV,
            final MTLSamplerMinMagFilter minFilter,
            final MTLSamplerMinMagFilter magFilter,
            final MTLSamplerMipFilter mipFilter,
            final MTLCompareFunction compareFunction,
            final int maxAnisotropy,
            final double lodMaxClamp,
            final double lodBias
    ) {
        try {
            return (MemorySegment) createSampler.invokeExact(
                    segment(device),
                    addressModeU.value,
                    addressModeV.value,
                    minFilter.value,
                    magFilter.value,
                    mipFilter.value,
                    compareFunction.value,
                    maxAnisotropy,
                    lodMaxClamp,
                    lodBias
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_sampler", throwable);
        }
    }

    public static MemorySegment MTLDevice_makeDepthStencilState(final MemorySegment device, final MTLCompareFunction depthCompareOp, final int writeDepth) {
        try {
            return (MemorySegment) MTLDeviceMakeDepthStencilState.invokeExact(segment(device), depthCompareOp.value, writeDepth);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeDepthStencilState", throwable);
        }
    }

    public static MemorySegment MTLCommandBuffer_makeRenderCommandEncoder(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final MemorySegment semanticTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int colorLoadAction,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearSemanticEnabled,
            final int clearDepthEnabled,
            final double clearDepth,
            final int gpuTimingStage
    ) {
        try {
            return (MemorySegment) MTLCommandBufferMakeRenderCommandEncoder.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    segment(semanticTexture),
                    segment(depthTexture),
                    viewportWidth,
                    viewportHeight,
                    colorLoadAction,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearSemanticEnabled,
                    clearDepthEnabled,
                    clearDepth,
                    gpuTimingStage
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_makeRenderCommandEncoder", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_clearDraw(
            final MemorySegment encoder,
            final MemorySegment colorTexture,
            final MemorySegment depthTexture,
            final double viewportWidth,
            final double viewportHeight,
            final int clearColorEnabled,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final int clearDepthEnabled,
            final double clearDepth
    ) {
        try {
            MTLRenderCommandEncoderClearDraw.invokeExact(
                    segment(encoder),
                    segment(colorTexture),
                    segment(depthTexture),
                    viewportWidth,
                    viewportHeight,
                    clearColorEnabled,
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    clearDepthEnabled,
                    clearDepth
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_clearDraw", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setRenderPipelineState(final MemorySegment encoder, final MemorySegment pipeline) {
        try {
            MTLRenderCommandEncoderSetRenderPipelineState.invokeExact(segment(encoder), segment(pipeline));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setRenderPipelineState", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setDepthStencilState(final MemorySegment encoder, final MemorySegment depthStencilState) {
        try {
            MTLRenderCommandEncoderSetDepthStencilState.invokeExact(segment(encoder), segment(depthStencilState));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthStencilState", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setDepthBias(final MemorySegment encoder, final float depthBias, final float slopeScale, final float clamp) {
        try {
            MTLRenderCommandEncoderSetDepthBias.invokeExact(segment(encoder), depthBias, slopeScale, clamp);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setDepthBias", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setFrontFacingWinding(final MemorySegment encoder, final int clockwise) {
        try {
            MTLRenderCommandEncoderSetFrontFacingWinding.invokeExact(segment(encoder), clockwise);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setFrontFacingWinding", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setCullMode(final MemorySegment encoder, final long cullMode) {
        try {
            MTLRenderCommandEncoderSetCullMode.invokeExact(segment(encoder), cullMode);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setCullMode", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTriangleFillMode(final MemorySegment encoder, final int lines) {
        try {
            MTLRenderCommandEncoderSetTriangleFillMode.invokeExact(segment(encoder), lines);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTriangleFillMode", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setBuffer(final MemorySegment encoder, final MemorySegment buffer, final long offset, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetBuffer.invokeExact(segment(encoder), segment(buffer), offset, index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setBuffer", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setBufferOffset(final MemorySegment encoder, final long offset, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetBufferOffset.invokeExact(segment(encoder), offset, index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setBufferOffset", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTexture(final MemorySegment encoder, final MemorySegment texture, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetTexture.invokeExact(segment(encoder), segment(texture), index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTexture", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setTextureAndSampler(final MemorySegment encoder, final MemorySegment texture, final MemorySegment sampler, final long index, final int stageMask) {
        try {
            MTLRenderCommandEncoderSetTextureAndSampler.invokeExact(segment(encoder), segment(texture), segment(sampler), index, stageMask);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setTextureAndSampler", throwable);
        }
    }

    public static int MTLRenderCommandEncoder_applyResourceBindings(
            final MemorySegment encoder,
            final MemorySegment packet,
            final long packetCapacityBytes
    ) {
        try {
            return (int) MTLRenderCommandEncoderApplyResourceBindings.invokeExact(
                    segment(encoder),
                    segment(packet),
                    packetCapacityBytes
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_applyResourceBindings_v1", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_setScissorRect(final MemorySegment encoder, final long x, final long y, final long width, final long height) {
        try {
            MTLRenderCommandEncoderSetScissorRect.invokeExact(segment(encoder), x, y, width, height);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_setScissorRect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long firstVertex,
            final long vertexCount,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawPrimitives.invokeExact(segment(encoder), primitiveType, firstVertex, vertexCount, instanceCount, baseInstance);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitives", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitives(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexCount,
            final long indexType,
            final MemorySegment indexBuffer,
            final long indexBufferOffset,
            final long instanceCount,
            final long baseVertex,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitives.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexCount,
                    indexType,
                    segment(indexBuffer),
                    indexBufferOffset,
                    instanceCount,
                    baseVertex,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitives", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_multiDrawIndexed(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment firstIndexOffsets,
            final MemorySegment indexCounts,
            final MemorySegment vertexOffsets,
            final long drawCount,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderMultiDrawIndexed.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(firstIndexOffsets),
                    segment(indexCounts),
                    segment(vertexOffsets),
                    drawCount,
                    instanceCount,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_multiDrawIndexed", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final long drawCount,
            final long stride
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitivesIndirect.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(indirectBuffer),
                    indirectBufferOffset,
                    drawCount,
                    stride
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesIndirect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitivesCpuCommands(
            final MemorySegment encoder,
            final long primitiveType,
            final long indexType,
            final MemorySegment indexBuffer,
            final MemorySegment commands,
            final long drawCount,
            final long stride
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitivesCpuCommands.invokeExact(
                    segment(encoder),
                    primitiveType,
                    indexType,
                    segment(indexBuffer),
                    segment(commands),
                    drawCount,
                    stride
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesCpuCommands", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawPrimitivesIndirect(
            final MemorySegment encoder,
            final long primitiveType,
            final MemorySegment indirectBuffer,
            final long indirectBufferOffset,
            final long drawCount,
            final long stride
    ) {
        try {
            MTLRenderCommandEncoderDrawPrimitivesIndirect.invokeExact(
                    segment(encoder),
                    primitiveType,
                    segment(indirectBuffer),
                    indirectBufferOffset,
                    drawCount,
                    stride
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawPrimitivesIndirect", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan(
            final MemorySegment encoder,
            final MemorySegment indexBuffer,
            final MemorySegment fanIndexBuffer,
            final long fanIndexBufferOffset,
            final long indexType,
            final long indexBufferOffset,
            final long indexCount,
            final long baseVertex,
            final long instanceCount,
            final long baseInstance
    ) {
        try {
            MTLRenderCommandEncoderDrawIndexedPrimitivesTriangleFan.invokeExact(
                    segment(encoder),
                    segment(indexBuffer),
                    segment(fanIndexBuffer),
                    fanIndexBufferOffset,
                    indexType,
                    indexBufferOffset,
                    indexCount,
                    baseVertex,
                    instanceCount,
                    baseInstance
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_drawIndexedPrimitivesTriangleFan", throwable);
        }
    }

    public static void MTLCommandBuffer_clearColorDepthTexturesRegion(
            final MemorySegment commandBuffer,
            final MemorySegment colorTexture,
            final float clearColorRed,
            final float clearColorGreen,
            final float clearColorBlue,
            final float clearColorAlpha,
            final MemorySegment depthTexture,
            final double clearDepth,
            final int x,
            final int y,
            final int width,
            final int height,
            final MemorySegment globalFence
    ) {
        try {
            MTLCommandBufferClearColorDepthTexturesRegion.invokeExact(
                    segment(commandBuffer),
                    segment(colorTexture),
                    clearColorRed,
                    clearColorGreen,
                    clearColorBlue,
                    clearColorAlpha,
                    segment(depthTexture),
                    clearDepth,
                    x,
                    y,
                    width,
                    height,
                    segment(globalFence)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_clearColorDepthTexturesRegion", throwable);
        }
    }

    public static MemorySegment metallum_MTLVertexDescriptor_create() {
        try {
            return (MemorySegment) MTLVertexDescriptorCreate.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_create", throwable);
        }
    }

    public static void metallum_MTLVertexDescriptor_setAttribute(
            final MemorySegment desc,
            final long index,
            final long format,
            final long offset,
            final long bufferIndex
    ) {
        try {
            MTLVertexDescriptorSetAttribute.invokeExact(segment(desc), index, format, offset, bufferIndex);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_setAttribute", throwable);
        }
    }

    public static void metallum_MTLVertexDescriptor_setLayout(
            final MemorySegment desc,
            final long bufferIndex,
            final long stride,
            final long stepFunction,
            final long stepRate
    ) {
        try {
            MTLVertexDescriptorSetLayout.invokeExact(segment(desc), bufferIndex, stride, stepFunction, stepRate);
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLVertexDescriptor_setLayout", throwable);
        }
    }

    public static MemorySegment metallum_MTLRenderPipelineDescriptor_create() {
        try {
            return (MemorySegment) MTLRenderPipelineDescriptorCreate.invokeExact();
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_create", throwable);
        }
    }

    public static MemorySegment metallum_create_shader_function(
            final MemorySegment device,
            final String source,
            final String entryPoint
    ) {
        try (Arena arena = Arena.ofConfined()) {
            return (MemorySegment) createShaderFunction.invokeExact(
                    segment(device),
                    toCString(arena, source),
                    toCString(arena, entryPoint)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_shader_function", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setCompiledFunctions(
            final MemorySegment desc,
            final MemorySegment vertexFunction,
            final MemorySegment fragmentFunction
    ) {
        try {
            MTLRenderPipelineDescriptorSetCompiledFunctions.invokeExact(
                    segment(desc),
                    segment(vertexFunction),
                    segment(fragmentFunction)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setCompiledFunctions", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setVertexDescriptor(
            final MemorySegment desc,
            final MemorySegment vertexDesc
    ) {
        try {
            MTLRenderPipelineDescriptorSetVertexDescriptor.invokeExact(segment(desc), segment(vertexDesc));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setVertexDescriptor", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setAttachmentFormats(
            final MemorySegment desc,
            final MTLPixelFormat colorFormat,
            final MTLPixelFormat semanticFormat,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        try {
            MTLRenderPipelineDescriptorSetAttachmentFormats.invokeExact(
                    segment(desc),
                    colorFormat.value,
                    semanticFormat.value,
                    depthFormat.value,
                    stencilFormat.value
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setAttachmentFormats", throwable);
        }
    }

    public static void metallum_MTLRenderPipelineDescriptor_setBlendState(
            final MemorySegment desc,
            final int attachmentIndex,
            final int enabled,
            final long srcRgb,
            final long dstRgb,
            final long opRgb,
            final long srcAlpha,
            final long dstAlpha,
            final long opAlpha,
            final long writeMask
    ) {
        try {
            MTLRenderPipelineDescriptorSetBlendState.invokeExact(
                    segment(desc),
                    attachmentIndex,
                    enabled,
                    srcRgb,
                    dstRgb,
                    opRgb,
                    srcAlpha,
                    dstAlpha,
                    opAlpha,
                    writeMask
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderPipelineDescriptor_setBlendState", throwable);
        }
    }

    public static MemorySegment metallum_MTLDevice_makeRenderPipelineState(
            final MemorySegment device,
            final MemorySegment descriptor
    ) {
        try {
            return (MemorySegment) MTLDeviceMakeRenderPipelineState.invokeExact(segment(device), segment(descriptor));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLDevice_makeRenderPipelineState", throwable);
        }
    }

    public static boolean metallum_configure_layer(
            final MemorySegment layer,
            final double width,
            final double height,
            final int immediatePresentMode,
            final int outputMode,
            final float contentHeadroom
    ) {
        try {
            return (int) configureLayer.invokeExact(
                    segment(layer),
                    width,
                    height,
                    immediatePresentMode,
                    outputMode,
                    contentHeadroom
            ) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_configure_layer", throwable);
        }
    }

    public static boolean metallum_update_layer_contents_headroom(
            final MemorySegment layer,
            final float contentHeadroom
    ) {
        try {
            return (int) updateLayerContentsHeadroom.invokeExact(segment(layer), contentHeadroom) != 0;
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_update_layer_contents_headroom", throwable);
        }
    }

    public static int MTLCommandBuffer_encodePresentTextureToDrawable(
            final MemorySegment commandBuffer,
            final MemorySegment layer,
            final MemorySegment sourceTexture,
            final MemorySegment sceneTexture,
            final MemorySegment sceneDepthTexture,
            final MemorySegment semanticTexture,
            final MemorySegment uiTexture,
            final MemorySegment globalFence,
            final int spatialHdrPrecomposed,
            final int outputMode,
            final int sourceEncoding,
            final int materialGenerationActive,
            final int diagnosticPattern,
            final float currentHeadroom,
            final float hdrStrength,
            final float bloomStrength
    ) {
        try {
            return (int) MTLCommandBufferEncodePresentTextureToDrawable.invokeExact(
                    segment(commandBuffer),
                    segment(layer),
                    segment(sourceTexture),
                    segment(sceneTexture),
                    segment(sceneDepthTexture),
                    segment(semanticTexture),
                    segment(uiTexture),
                    segment(globalFence),
                    spatialHdrPrecomposed,
                    outputMode,
                    sourceEncoding,
                    materialGenerationActive,
                    diagnosticPattern,
                    currentHeadroom,
                    hdrStrength,
                    bloomStrength
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_encodePresentTextureToDrawable", throwable);
        }
    }

    public static int MTLCommandBuffer_encodeHdrUiBackdrop(
            final MemorySegment commandBuffer,
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final MemorySegment sceneDepthTexture,
            final MemorySegment semanticTexture,
            final MemorySegment globalFence,
            final int sourceEncoding,
            final boolean materialGenerationActive,
            final boolean spatialScalingEnabled,
            final boolean hdrPrecomposeEnabled,
            final boolean perceptualScalingEnabled,
            final boolean deferSpatialHdrUiSeed,
            final float currentHeadroom,
            final float hdrStrength,
            final float bloomStrength
    ) {
        try {
            return (int) MTLCommandBufferEncodeHdrUiBackdrop.invokeExact(
                    segment(commandBuffer),
                    segment(sourceTexture),
                    segment(destinationTexture),
                    segment(sceneDepthTexture),
                    segment(semanticTexture),
                    segment(globalFence),
                    sourceEncoding,
                    materialGenerationActive ? 1 : 0,
                    spatialScalingEnabled ? 1 : 0,
                    hdrPrecomposeEnabled ? 1 : 0,
                    perceptualScalingEnabled ? 1 : 0,
                    deferSpatialHdrUiSeed ? 1 : 0,
                    currentHeadroom,
                    hdrStrength,
                    bloomStrength
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_encodeHdrUiBackdrop", throwable);
        }
    }

    public static int MTLRenderCommandEncoder_encodePreparedHdrUiBackdrop(
            final MemorySegment commandBuffer,
            final MemorySegment encoder,
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final MTLPixelFormat depthFormat,
            final MTLPixelFormat stencilFormat
    ) {
        try {
            return (int) MTLRenderCommandEncoderEncodePreparedHdrUiBackdrop.invokeExact(
                    segment(commandBuffer),
                    segment(encoder),
                    segment(sourceTexture),
                    segment(destinationTexture),
                    depthFormat.value,
                    stencilFormat.value
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLRenderCommandEncoder_encodePreparedHdrUiBackdrop", throwable);
        }
    }

    public static int MTLCommandBuffer_materializePreparedHdrUiBackdrop(
            final MemorySegment commandBuffer,
            final MemorySegment sourceTexture,
            final MemorySegment destinationTexture,
            final MemorySegment globalFence
    ) {
        try {
            return (int) MTLCommandBufferMaterializePreparedHdrUiBackdrop.invokeExact(
                    segment(commandBuffer),
                    segment(sourceTexture),
                    segment(destinationTexture),
                    segment(globalFence)
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_materializePreparedHdrUiBackdrop", throwable);
        }
    }

    public static int MTLCommandBuffer_encodeCoherentMenuBlur(
            final MemorySegment commandBuffer,
            final MemorySegment sourceTexture,
            final MemorySegment uiTexture,
            final MemorySegment globalFence,
            final float radius,
            final float currentHeadroom
    ) {
        try {
            return (int) MTLCommandBufferEncodeCoherentMenuBlur.invokeExact(
                    segment(commandBuffer),
                    segment(sourceTexture),
                    segment(uiTexture),
                    segment(globalFence),
                    radius,
                    currentHeadroom
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_encodeCoherentMenuBlur", throwable);
        }
    }

    public static int MTLCommandBuffer_encodeSpatialScreenshot(
            final MemorySegment commandBuffer,
            final MemorySegment rawSceneTexture,
            final MemorySegment uiTexture,
            final MemorySegment destinationTexture,
            final MemorySegment globalFence,
            final int sourceEncoding,
            final float currentHeadroom
    ) {
        try {
            return (int) MTLCommandBufferEncodeSpatialScreenshot.invokeExact(
                    segment(commandBuffer),
                    segment(rawSceneTexture),
                    segment(uiTexture),
                    segment(destinationTexture),
                    segment(globalFence),
                    sourceEncoding,
                    currentHeadroom
            );
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_MTLCommandBuffer_encodeSpatialScreenshot", throwable);
        }
    }

    public static void metallum_release_object(final MemorySegment object) {
        try {
            releaseObject.invokeExact(segment(object));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_release_object", throwable);
        }
    }

    public static void metallum_release_static_geometry_buffer(final MemorySegment buffer) {
        try {
            releaseStaticGeometryBuffer.invokeExact(segment(buffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_release_static_geometry_buffer", throwable);
        }
    }

    public static MemorySegment metallum_create_fence(final MemorySegment device) {
        try {
            return (MemorySegment) createFence.invokeExact(segment(device));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_create_fence", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            MTLRenderCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_updateFence", throwable);
        }
    }

    public static void MTLRenderCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence, final long stages) {
        try {
            MTLRenderCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence), stages);
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLRenderCommandEncoder_waitForFence", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_updateFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            MTLBlitCommandEncoderUpdateFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_updateFence", throwable);
        }
    }

    public static void MTLBlitCommandEncoder_waitForFence(final MemorySegment encoder, final MemorySegment fence) {
        try {
            MTLBlitCommandEncoderWaitForFence.invokeExact(segment(encoder), segment(fence));
        } catch (Throwable throwable) {
            throw bridgeFailure("MTLBlitCommandEncoder_waitForFence", throwable);
        }
    }

    public static MemorySegment metallum_get_buffer_contents(final MemorySegment buffer) {
        try {
            return (MemorySegment) getBufferContents.invokeExact(segment(buffer));
        } catch (Throwable throwable) {
            throw bridgeFailure("metallum_get_buffer_contents", throwable);
        }
    }

    public static ByteBuffer nativeByteBufferView(final MemorySegment pointer, final long byteSize) {
        if (pointer == null || pointer.address() == 0L) {
            throw new IllegalArgumentException("Cannot create a ByteBuffer view for a null native pointer");
        }
        if (byteSize < 0L) {
            throw new IllegalArgumentException("Byte size must be non-negative");
        }
        return MemorySegment.ofAddress(pointer.address()).reinterpret(byteSize).asByteBuffer();
    }

    private static MemorySegment segment(final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L ? MemorySegment.NULL : pointer;
    }

    private static MemorySegment toCString(final Arena arena, final String value) {
        return value == null ? MemorySegment.NULL : arena.allocateFrom(value);
    }

    public static boolean isNullHandle(@Nullable final MemorySegment pointer) {
        return pointer == null || pointer.address() == 0L;
    }

    private static RuntimeException bridgeFailure(final String symbol, final Throwable throwable) {
        return new IllegalStateException("Native bridge call failed: " + symbol, throwable);
    }
}
