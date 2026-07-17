package com.metallum.client.metal.render;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.RendererGenerationPlanner;
import com.metallum.client.hdr.HdrOutputMode;
import com.metallum.client.hdr.HdrPipelinePolicy;
import com.metallum.client.hdr.HdrShaderFlavor;
import com.metallum.client.hdr.HdrSourceEncoding;
import com.metallum.client.hdr.MetallumMaterialState;
import com.metallum.client.hdr.SceneLinearClearColor;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.LightFrameSnapshot;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RendererGenerationConfig;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.metal.render.mtl.MTLPixelFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.VulkanBindGroupEntryType;
import net.minecraft.client.renderer.RenderPipelines;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

public final class MetalRuntimeTests {
    private MetalRuntimeTests() {
    }

    public static void main(final String[] args) {
        testDestructionQueueDefersReentrantAdds();
        testDestructionQueueToleratesReentrantRotation();
        testDestructionQueueSpreadsBurst();
        testDestructionQueueClose();
        testClosedBufferRetainsHandleUntilDeferredRelease();
        testTexelViewCacheReuseAndInvalidation();
        testTextureBindingHolderUpdatesInPlace();
        testDynamicBackingPoolBoundsAndReuse();
        testPrivateGeometryHeapRouting();
        testPartialDynamicWritePreservation();
        testFenceTimeoutRounding();
        testEdrRefreshThrottle();
        testGpuTimingDetailGate();
        testLocalShadowDiagnosticCap();
        testLocalShadowLightSelection();
        testJavaWorkloadTelemetryGateAndReset();
        testJavaWorkloadTelemetryDoesNotInferMappedWrites();
        testGpuTimingStageAbi();
        testResourceBindingPacketReuseAndValidation();
        testResourceBindingBatchSelector();
        testSodiumLightLegacyPatchPacketAndFacadeValidation();
        testCanonicalShaderResourceLayout();
        testVoxelShadowTraversalMslLoopContract();
        testPipelineLocalBindingRemap();
        testPendingUiSeedConsumeOnceLifecycle();
        testTrackedUiTextureAllocationScope();
        testWorldSceneCaptureGate();
        testMaterialWorldPassGate();
        testAdvancedLightingAdmissionLimits();
        testAdvancedLightingWorkDeclaration();
        testVoxelFailureIsolationPolicy();
        testVoxelDebugChecksumScheduling();
        testAdvancedLightingPerSubmitLatch();
        testMojangLogoFp16BlendCompatibility();
        testHdrSceneColorRouting();
        testL2GenerationResourceRouting();
        testL2AtomicMaterialFallback();
        testAutomaticMaterialContractAndCompatibilityOverride();
    }

    private static void testVoxelShadowTraversalMslLoopContract() {
        String loop = "    for (uint hardStep = 0u; hardStep < maxSteps; hardStep++) {\n"
                + "        visibility *= 0.5f;\n"
                + "    }\n";
        String patched = MetalCrossShaderCompiler.preserveVoxelShadowTraversalLoop(loop);
        require(patched.contains("#pragma clang loop unroll(disable)\n" + loop),
                "L6 MSL traversal lost its no-unroll loop contract");
        expectIllegalState(() -> MetalCrossShaderCompiler.preserveVoxelShadowTraversalLoop(
                loop + loop));
        expectIllegalState(() -> MetalCrossShaderCompiler.preserveVoxelShadowTraversalLoop(
                "static float unrelated() { return 1.0; }"));
    }

    private static void testLocalShadowDiagnosticCap() {
        require(LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, false, "0") == 2,
                "release timing accepted the diagnostic L6 light cap");
        require(LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, true, null) == 2
                        && LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, true, "") == 2
                        && LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, true, "broken") == 2
                        && LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, true, "3") == 2,
                "invalid diagnostic L6 light cap changed production work");
        require(LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, true, "0") == 0
                        && LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, true, " 1 ") == 1
                        && LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(2, true, "2") == 2,
                "bounded diagnostic L6 light cap was not applied");
        expectIllegalArgument(() -> LocalVoxelShadowGpuResources.diagnosticShadowedLocalLights(
                LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS + 1, true, "0"));
    }

    private static void testLocalShadowLightSelection() {
        LightWorldToken world = new LightWorldToken(1L, "minecraft:overworld");
        List<AdvancedLight> lights = List.of(
                new AdvancedLight(1L, 1L, LightSourceKind.BLOCK,
                        40.0, 64.0, 0.0, 12.0f, 1.0f, 0.8f, 0.5f, 1.0f, 10),
                new AdvancedLight(2L, 1L, LightSourceKind.BLOCK,
                        2.0, 64.0, 0.0, 12.0f, 1.0f, 0.8f, 0.5f, 1.0f, 10),
                new AdvancedLight(3L, 1L, LightSourceKind.BLOCK,
                        1.0, 64.0, 0.0, 15.0f, 1.0f, 0.8f, 0.5f, 1.0f, 9)
        );
        LightFrameSnapshot snapshot = new LightFrameSnapshot(
                LightFrameSnapshot.CURRENT_VERSION,
                world,
                1L,
                lights,
                lights.size(),
                0,
                0
        );
        FrameState.CameraPosition camera = new FrameState.CameraPosition(0.0, 64.0, 0.0);

        int[] none = LocalVoxelShadowGpuResources.selectShadowLightIndices(snapshot, camera, 0);
        int[] one = LocalVoxelShadowGpuResources.selectShadowLightIndices(snapshot, camera, 1);
        int[] two = LocalVoxelShadowGpuResources.selectShadowLightIndices(snapshot, camera, 2);
        require(none[0] == -1 && none[1] == -1,
                "zero L6 cap selected an upload light");
        require(one[0] == 1 && one[1] == -1,
                "L6 did not select the nearest equally important upload light");
        require(two[0] == 1 && two[1] == 0,
                "L6 selected-light order crossed the higher-priority prefix");
        expectIllegalArgument(() -> LocalVoxelShadowGpuResources.selectShadowLightIndices(
                snapshot, camera, LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS + 1));
    }

    private static void testCanonicalShaderResourceLayout() {
        VulkanBindGroupLayout.Entry projection = new VulkanBindGroupLayout.Entry(
                VulkanBindGroupEntryType.UNIFORM_BUFFER, "Projection", null);
        VulkanBindGroupLayout.Entry transforms = new VulkanBindGroupLayout.Entry(
                VulkanBindGroupEntryType.UNIFORM_BUFFER, "DynamicTransforms", null);
        VulkanBindGroupLayout.Entry sampler = new VulkanBindGroupLayout.Entry(
                VulkanBindGroupEntryType.SAMPLED_IMAGE, "Sampler0", null);
        List<VulkanBindGroupLayout.Entry> legacy = new ArrayList<>(List.of(
                projection, transforms, sampler));
        List<VulkanBindGroupLayout.Entry> patched = new ArrayList<>(List.of(
                transforms, sampler, projection));

        MetalCrossShaderCompiler.canonicalizeLayoutEntries(legacy);
        MetalCrossShaderCompiler.canonicalizeLayoutEntries(patched);

        require(legacy.equals(patched),
                "shader variants retained reflection-order-dependent resource indices");
        require(legacy.equals(List.of(transforms, projection, sampler)),
                "canonical shader resource ordering changed");
    }

    private static void testAdvancedLightingPerSubmitLatch() {
        MetalCapabilities capabilities = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT,
                MetalCapabilities.Feature.ADVANCED_LIGHTING
        );
        RendererGenerationConfig advancedGeneration = new RendererGenerationConfig(
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                capabilities,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        long submitIndex = 41L;

        AdvancedLightingRuntime.reset();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");
        AdvancedLightingRuntime.admitGeneration(true);
        boolean beforeFailure = MetalDevice.isAdvancedLightingWorldPassActive(
                advancedGeneration,
                true,
                true,
                submitIndex,
                submitIndex
        );
        HdrShaderFlavor firstFlavor = MetalCompiledRenderPipeline.selectMaterialWorldFlavor(
                beforeFailure,
                true
        );

        AdvancedLightingRuntime.reportRegistryAdmission(false, "synthetic mid-submit failure");
        require(!AdvancedLightingRuntime.isActive(),
                "synthetic mid-submit failure did not reject future Advanced frames");
        boolean afterFailure = MetalDevice.isAdvancedLightingWorldPassActive(
                advancedGeneration,
                true,
                true,
                submitIndex,
                submitIndex
        );
        HdrShaderFlavor secondFlavor = MetalCompiledRenderPipeline.selectMaterialWorldFlavor(
                afterFailure,
                true
        );

        require(beforeFailure && afterFailure
                        && firstFlavor == HdrShaderFlavor.METALLUM_ADVANCED
                        && secondFlavor == HdrShaderFlavor.METALLUM_ADVANCED,
                "failClosed changed the Advanced shader flavor inside one submit");
        require(!MetalDevice.isAdvancedLightingWorldPassActive(
                        advancedGeneration,
                        true,
                        true,
                        submitIndex,
                        submitIndex + 1L
                )
                        && MetalCompiledRenderPipeline.selectMaterialWorldFlavor(false, true)
                        == HdrShaderFlavor.METALLUM,
                "expired Advanced frame latch leaked into the next submit");
        AdvancedLightingRuntime.reset();
    }

    private static void testAdvancedLightingAdmissionLimits() {
        require(MetalDevice.advancedLightingAdmissionLimit(LightingPreset.PERFORMANCE)
                        == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && MetalDevice.advancedLightingAdmissionLimit(LightingPreset.BALANCED)
                        == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS
                        && MetalDevice.advancedLightingAdmissionLimit(LightingPreset.ULTRA)
                        == AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS,
                "Java retained candidate pool drifted from the native total-light contract");
        for (LightingPreset preset : LightingPreset.values()) {
            AdvancedLightingLayout.Budget budget = AdvancedLightingLayout.forGeneration(
                    preset,
                    1,
                    1
            );
            int expectedClusterCap = AdvancedLightingLayout.MAX_LIGHTS_PER_CLUSTER;
            require(budget.maxLights() == MetalDevice.advancedLightingAdmissionLimit(preset)
                            && budget.maxLightsPerCluster() == expectedClusterCap,
                    "Total candidate/per-cluster limits drifted for " + preset);
        }
    }

    private static void testAdvancedLightingWorkDeclaration() {
        FrameState.AdvancedLightingWork empty = MetalDevice.advancedLightingWork(0);
        require(empty.lightCount() == 0
                        && empty.passCount() == 3
                        && empty.encoderCount() == 2
                        && empty.psoCount() == 9
                        && empty.workQueueCount() == 2
                        && empty.dispatchCount() == 1
                        && empty.uploadBytes() == AdvancedLightingLayout.UPLOAD_HEADER_BYTES,
                "Empty Advanced frame does not describe upload, prepare and direct work");

        FrameState.AdvancedLightingWork populated = MetalDevice.advancedLightingWork(2);
        require(populated.lightCount() == 2
                        && populated.passCount() == 4
                        && populated.encoderCount() == 2
                        && populated.psoCount() == 9
                        && populated.workQueueCount() == 2
                        && populated.dispatchCount() == 6
                        && populated.uploadBytes() == AdvancedLightingLayout.UPLOAD_HEADER_BYTES
                        + 2L * AdvancedLightingLayout.GPU_LIGHT_STRIDE,
                "Populated Advanced frame does not describe the compact cluster pipeline");
        FrameState.AdvancedLightingWork shadowed = MetalDevice.advancedLightingWork(2, 3, true);
        require(shadowed.lightCount() == 2
                        && shadowed.passCount() == 7
                        && shadowed.encoderCount() == 11
                        && shadowed.psoCount() == 11
                        && shadowed.workQueueCount() == 2
                        && shadowed.dispatchCount() == 6
                        && shadowed.uploadBytes() == AdvancedLightingLayout.UPLOAD_HEADER_BYTES
                        + 2L * AdvancedLightingLayout.GPU_LIGHT_STRIDE
                        + com.metallum.client.renderer.SunShadowLayout.PARAMS_BYTES,
                "L6 frame does not declare static refresh/copy/dynamic shadow work");
        com.metallum.client.renderer.LocalVoxelShadowLayout.Budget localBudget =
                com.metallum.client.renderer.LocalVoxelShadowLayout.forPreset(
                        LightingPreset.BALANCED);
        FrameState.AdvancedLightingWork localShadowed = MetalDevice.advancedLightingWork(
                2, 3, true, localBudget);
        require(localShadowed.passCount() == shadowed.passCount()
                        && localShadowed.encoderCount() == shadowed.encoderCount()
                        && localShadowed.psoCount() == shadowed.psoCount()
                        && localShadowed.workQueueCount() == 3
                        && localShadowed.uploadBytes() == shadowed.uploadBytes()
                        + com.metallum.client.renderer.LocalVoxelShadowLayout.PARAMS_BYTES
                        + (long) localBudget.maxEntityProxies()
                        * com.metallum.client.renderer.LocalVoxelShadowLayout.PROXY_STRIDE_BYTES,
                "L6 local-shadow packet/proxy work is not explicitly bounded");
        FrameState.AdvancedLightingWork voxel = MetalDevice.withVoxelWork(populated, 3, 4096L);
        require(voxel.lightCount() == 2
                        && voxel.passCount() == 6
                        && voxel.encoderCount() == 4
                        && voxel.psoCount() == 11
                        && voxel.workQueueCount() == 4
                        && voxel.dispatchCount() == 9
                        && voxel.uploadBytes() == populated.uploadBytes() + 4096L,
                "L5 frame does not describe its bounded upload and update work");
        expectIllegalArgument(() -> MetalDevice.advancedLightingWork(-1));
        expectIllegalArgument(() -> MetalDevice.advancedLightingWork(0, 1, true));
        expectIllegalArgument(() -> MetalDevice.withVoxelWork(populated, 0, 4096L));
    }

    private static void testVoxelFailureIsolationPolicy() {
        AdvancedLightingRuntime.reset();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");
        AdvancedLightingRuntime.admitGeneration(true);

        require(MetalDevice.retainsL3L4AfterVoxelFailure(true, true)
                        && AdvancedLightingRuntime.isActive(),
                "An L5-only failure revoked the established L3/L4 admission");
        require(!MetalDevice.retainsL3L4AfterVoxelFailure(false, true)
                        && !MetalDevice.retainsL3L4AfterVoxelFailure(true, false),
                "L5 isolation masked a missing L3 or L4 native resource");
        require(!MetalDevice.hasUnacknowledgedVoxelNativeRejection(8L, 8L)
                        && MetalDevice.hasUnacknowledgedVoxelNativeRejection(8L, 9L),
                "A successful L5 queue rejected-delta was mistaken for an async GPU failure");
        expectIllegalArgument(() -> MetalDevice.hasUnacknowledgedVoxelNativeRejection(-1L, 0L));
        require(MetalDevice.isVoxelRetrySuppressed(12L, 4L, 12L, 4L)
                        && !MetalDevice.isVoxelRetrySuppressed(12L, 4L, 13L, 4L)
                        && !MetalDevice.isVoxelRetrySuppressed(12L, 4L, 12L, 5L)
                        && !MetalDevice.isVoxelRetrySuppressed(
                        Long.MIN_VALUE, Long.MIN_VALUE, 12L, 4L),
                "L5 retry latch was not scoped to exactly one renderer/lighting generation");
        AdvancedLightingRuntime.reset();
    }

    private static void testVoxelDebugChecksumScheduling() {
        long cadence = MetalDevice.VOXEL_DEBUG_CHECKSUM_CADENCE_FRAMES;
        require(MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.ADVANCED, true, true, false, false,
                        0L, Long.MIN_VALUE),
                "enabled L5 diagnostics did not schedule their first idle checksum");
        require(!MetalDevice.shouldScheduleVoxelDebugChecksum(
                        false, LightingModel.ADVANCED, true, true, false, false,
                        cadence, Long.MIN_VALUE)
                        && !MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.VANILLA, true, true, false, false,
                        cadence, Long.MIN_VALUE)
                        && !MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.ADVANCED, false, true, false, false,
                        cadence, Long.MIN_VALUE)
                        && !MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.ADVANCED, true, false, false, false,
                        cadence, Long.MIN_VALUE)
                        && !MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.ADVANCED, true, true, true, false,
                        cadence, Long.MIN_VALUE)
                        && !MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.ADVANCED, true, true, false, true,
                        cadence, Long.MIN_VALUE),
                "L5 diagnostics escaped their Advanced/healthy/idle isolation gate");
        require(!MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.ADVANCED, true, true, false, false,
                        cadence - 1L, 0L)
                        && MetalDevice.shouldScheduleVoxelDebugChecksum(
                        true, LightingModel.ADVANCED, true, true, false, false,
                        cadence, 0L),
                "L5 diagnostic checksum cadence is off by one frame");
        expectIllegalArgument(() -> MetalDevice.shouldScheduleVoxelDebugChecksum(
                true, LightingModel.ADVANCED, true, true, false, false,
                -1L, Long.MIN_VALUE
        ));
    }

    private static void testAutomaticMaterialContractAndCompatibilityOverride() {
        String key = "metallum.renderer.contract";
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            require(MetalDevice.requestedRenderContract() == RenderContractMode.METALLUM,
                    "normal startup did not automatically request the METALLUM contract");
            System.setProperty(key, "legacy");
            require(MetalDevice.requestedRenderContract() == RenderContractMode.LEGACY,
                    "hidden compatibility override did not force Legacy");
            System.setProperty(key, "unknown");
            require(MetalDevice.requestedRenderContract() == RenderContractMode.METALLUM,
                    "unknown compatibility override did not fail to the automatic contract");
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    private static void testDestructionQueueDefersReentrantAdds() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[2];
        queue.add(() -> {
            executions[0]++;
            queue.add(() -> executions[1]++);
        });

        queue.rotate();
        queue.rotate();
        require(executions[0] == 0, "destroy action ran before three rotations");
        queue.rotate();
        require(executions[0] == 1, "destroy action did not run after three rotations");
        require(executions[1] == 0, "reentrant destroy action ran in the same rotation");

        queue.rotate();
        queue.rotate();
        require(executions[1] == 0, "reentrant destroy action ran before its own delay");
        queue.rotate();
        require(executions[1] == 1, "reentrant destroy action did not preserve the queue delay");
        queue.close();
        require(executions[0] == 1 && executions[1] == 1, "destroy actions ran more than once");
    }

    private static void testDestructionQueueToleratesReentrantRotation() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[2];
        queue.add(() -> {
            executions[0]++;
            queue.rotate();
            queue.add(() -> executions[1]++);
        });

        queue.rotate();
        queue.rotate();
        queue.rotate();
        require(executions[0] == 1 && executions[1] == 0, "reentrant rotation callback mismatch");
        queue.rotate();
        queue.rotate();
        require(executions[1] == 0, "reentrant rotation aliased two queue slots");
        queue.rotate();
        require(executions[1] == 1, "reentrant rotation lost the deferred action");
        queue.close();
    }

    private static void testDestructionQueueClose() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[1];
        queue.add(() -> executions[0]++);
        queue.add(null);
        queue.close();
        require(executions[0] == 1, "close did not drain queued destruction exactly once");
    }

    private static void testDestructionQueueSpreadsBurst() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3, 2);
        int[] executions = new int[1];
        for (int index = 0; index < 5; index++) {
            queue.add(() -> executions[0]++);
        }

        queue.rotate();
        queue.rotate();
        require(executions[0] == 0, "destruction burst ran before the GPU-safe delay");
        queue.rotate();
        require(executions[0] == 2, "destruction burst ignored the per-frame drain budget");
        queue.rotate();
        require(executions[0] == 4, "destruction backlog did not continue on the next frame");
        queue.close();
        require(executions[0] == 5, "close did not drain the remaining destruction backlog");
    }

    private static void testClosedBufferRetainsHandleUntilDeferredRelease() {
        MemorySegment handle = MemorySegment.ofAddress(0x51A7E0L);
        MetalGpuBuffer.NativeHandleState state = new MetalGpuBuffer.NativeHandleState(handle);

        require(state.requireForEncoding().address() == handle.address(),
                "live Metal buffer did not expose its native handle");
        require(state.beginClose() == handle && state.isClosed(),
                "logical close did not retain the handle for an already-recorded render pass");
        require(state.requireForEncoding().address() == handle.address(),
                "logical close invalidated the native handle before deferred destruction");
        require(state.beginClose() == null, "second logical close scheduled another native release");

        state.markReleased(handle);
        boolean releasedRejected = false;
        try {
            state.requireForEncoding();
        } catch (IllegalStateException expected) {
            releasedRejected = true;
        }
        require(releasedRejected, "physically released Metal buffer still exposed its native handle");
    }

    private static void testTexelViewCacheReuseAndInvalidation() {
        List<String> released = new ArrayList<>();
        MetalGpuBuffer.TexelViewCache<String> cache = new MetalGpuBuffer.TexelViewCache<>(2, released::add);
        MetalGpuBuffer.TexelViewKey firstKey = new MetalGpuBuffer.TexelViewKey(70L, 0L, 16L, 64L);
        int[] creations = new int[1];

        require(!cache.isInitialized(), "texel view cache allocated storage eagerly");
        cache.drain();
        require(!cache.isInitialized(), "draining an unused texel view cache allocated storage");
        MetalGpuBuffer.TexelViewKey failedKey = new MetalGpuBuffer.TexelViewKey(0L, 0L, 1L, 4L);
        require(cache.getOrCreate(failedKey, ignored -> null) == null, "failed texel view creation returned a value");
        require(!cache.isInitialized(), "failed texel view creation initialized cache storage");

        String first = cache.getOrCreate(firstKey, ignored -> "view-" + ++creations[0]);
        require(cache.isInitialized(), "successful texel view creation did not initialize cache storage");
        String reused = cache.getOrCreate(firstKey, ignored -> "view-" + ++creations[0]);
        String differentRange = cache.getOrCreate(
                new MetalGpuBuffer.TexelViewKey(70L, 64L, 16L, 64L),
                ignored -> "view-" + ++creations[0]
        );
        require(first == reused, "identical texel view keys did not reuse the cached view");
        require(!first.equals(differentRange), "different texel ranges reused the same cached view");
        require(creations[0] == 2 && cache.size() == 2, "texel view cache creation count mismatch");

        cache.drain();
        require(cache.size() == 0, "texel view cache did not clear after backing invalidation");
        require(!cache.isInitialized(), "backing invalidation retained texel view cache storage");
        require(released.size() == 2 && released.contains(first) && released.contains(differentRange),
                "backing invalidation did not release every cached texel view");

        String afterInvalidation = cache.getOrCreate(firstKey, ignored -> "view-" + ++creations[0]);
        require(!first.equals(afterInvalidation), "backing invalidation reused a stale texel view");
        require(creations[0] == 3, "texel view was not recreated for the new backing");

        require(cache.getOrCreate(failedKey, ignored -> null) == null, "failed texel view creation returned a value");
        require(cache.getOrCreate(failedKey, ignored -> "retry") != null,
                "failed texel view creation was cached instead of allowing a retry");

        MetalGpuBuffer.TexelViewKey overflowKey = new MetalGpuBuffer.TexelViewKey(70L, 128L, 16L, 64L);
        cache.getOrCreate(overflowKey, ignored -> "overflow");
        require(cache.size() == 2, "texel view cache exceeded its configured bound");
        require(released.contains(afterInvalidation), "least-recently-used texel view was not released on eviction");
    }

    private static void testFenceTimeoutRounding() {
        require(MetalFence.timeoutMillis(-1L) == 0L, "negative timeout must remain non-blocking");
        require(MetalFence.timeoutMillis(0L) == 0L, "zero timeout must remain non-blocking");
        require(MetalFence.timeoutMillis(1L) == 1L, "positive sub-millisecond timeout rounded down");
        require(MetalFence.timeoutMillis(999_999L) == 1L, "sub-millisecond timeout rounded incorrectly");
        require(MetalFence.timeoutMillis(1_000_000L) == 1L, "whole millisecond changed");
        require(MetalFence.timeoutMillis(1_000_001L) == 2L, "fractional millisecond did not round up");
        require(
                MetalFence.timeoutMillis(Long.MAX_VALUE) == 9_223_372_036_855L,
                "maximum timeout overflowed"
        );
    }

    private static void testPrivateGeometryHeapRouting() {
        int geometryUsage = com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST
                | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_SRC
                | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_VERTEX
                | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX;
        require(MetalGpuBuffer.shouldUsePrivateGeometryHeap(geometryUsage, false),
                "static geometry usage did not select the private heap");
        require(!MetalGpuBuffer.shouldUsePrivateGeometryHeap(geometryUsage, true),
                "initial-data allocation unexpectedly selected the private geometry heap");
        require(!MetalGpuBuffer.shouldUsePrivateGeometryHeap(
                        geometryUsage | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_MAP_WRITE,
                        false
                ),
                "broader buffer usage unexpectedly selected the private geometry heap");
        require(!MetalGpuBuffer.shouldUsePrivateGeometryHeap(
                        geometryUsage & ~com.mojang.blaze3d.buffers.GpuBuffer.USAGE_INDEX,
                        false
                ),
                "non-geometry buffer usage unexpectedly selected the private heap");
    }

    private static void testEdrRefreshThrottle() {
        long interval = MetalSurface.EDR_REFRESH_INTERVAL_NANOS;
        require(MetalSurface.shouldRefreshEdrCapabilities(Long.MIN_VALUE, 5L),
                "initial EDR capability query was throttled");
        require(!MetalSurface.shouldRefreshEdrCapabilities(1_000L, 1_000L + interval - 1L),
                "EDR capability query ignored the refresh interval");
        require(MetalSurface.shouldRefreshEdrCapabilities(1_000L, 1_000L + interval),
                "EDR capability query did not run at the refresh boundary");
        require(MetalSurface.shouldRefreshEdrCapabilities(10_000L, 9_000L),
                "EDR capability query did not recover from a backwards clock sample");
    }

    private static void testGpuTimingStageAbi() {
        MetalGpuTimingStage[] stages = {
                MetalGpuTimingStage.WORLD_OPAQUE,
                MetalGpuTimingStage.TRANSLUCENT,
                MetalGpuTimingStage.ENTITIES,
                MetalGpuTimingStage.HDR_EXTRACT,
                MetalGpuTimingStage.HISTOGRAM_EXPOSURE,
                MetalGpuTimingStage.BLOOM_HORIZONTAL,
                MetalGpuTimingStage.BLOOM_VERTICAL,
                MetalGpuTimingStage.HDR_RECONSTRUCTION,
                MetalGpuTimingStage.METAL_FX,
                MetalGpuTimingStage.UI_SEED,
                MetalGpuTimingStage.UI,
                MetalGpuTimingStage.PRESENT,
                MetalGpuTimingStage.ACTUAL_HDR_DISPLAY,
                MetalGpuTimingStage.LIGHT_UPLOAD_CLUSTER_BUILD,
                MetalGpuTimingStage.SUN_SHADOW,
                MetalGpuTimingStage.VOXEL_UPDATE
        };
        require(stages.length == MetalGpuTimingStage.PROFILED_STAGE_COUNT,
                "GPU timing stage count does not match the native ABI");
        for (int index = 0; index < stages.length; index++) {
            require(stages[index].nativeId() == index,
                    "GPU timing stage native ID mismatch at index " + index);
        }
        require(MetalGpuTimingStage.NONE.nativeId() == -1,
                "GPU timing NONE sentinel does not match the native ABI");
    }

    private static void testGpuTimingDetailGate() {
        require(!MetalGpuTiming.timingEnabled(null),
                "GPU timing report was enabled without its primary flag");
        require(!MetalGpuTiming.timingEnabled("0"),
                "GPU timing report ignored an explicit disabled value");
        require(MetalGpuTiming.timingEnabled("1"),
                "GPU timing report did not accept its primary flag");
        require(!MetalGpuTiming.detailEnabled(null, null),
                "GPU timing detail was enabled without timing flags");
        require(!MetalGpuTiming.detailEnabled("1", null),
                "production-equivalent GPU timing unexpectedly enabled stage markers");
        require(!MetalGpuTiming.detailEnabled(null, "1"),
                "GPU timing detail flag bypassed the primary timing gate");
        require(!MetalGpuTiming.detailEnabled("0", "1"),
                "disabled GPU timing unexpectedly enabled stage markers");
        require(MetalGpuTiming.detailEnabled("1", "1"),
                "explicit GPU timing detail did not enable stage markers");
    }

    private static void testResourceBindingPacketReuseAndValidation() {
        MetalResourceBindingPacket packet = new MetalResourceBindingPacket();
        MemorySegment storage = packet.storage();
        long storageAddress = storage.address();

        packet.addUniformBuffer(
                MemorySegment.ofAddress(0x1000L),
                16L,
                32L,
                128L,
                2,
                MetalResourceBindingPacket.STAGE_VERTEX
        );
        packet.addTextureSampler(
                MemorySegment.ofAddress(0x2000L),
                MemorySegment.ofAddress(0x3000L),
                4,
                MetalResourceBindingPacket.STAGE_FRAGMENT
        );
        packet.addTexelTexture(
                MemorySegment.ofAddress(0x4000L),
                7,
                MetalResourceBindingPacket.STAGE_ALL
        );
        MemorySegment encoded = packet.finish();
        require(encoded.address() == storageAddress,
                "resource binding packet replaced its fixed native storage");
        require(encoded.get(ValueLayout.JAVA_INT, MetalResourceBindingPacket.HEADER_VERSION)
                        == MetalResourceBindingPacket.CURRENT_VERSION,
                "resource binding packet version mismatch");
        require(encoded.get(ValueLayout.JAVA_INT, MetalResourceBindingPacket.HEADER_BYTE_SIZE)
                        == MetalResourceBindingPacket.HEADER_BYTES + 3 * MetalResourceBindingPacket.RECORD_BYTES,
                "resource binding packet byte size mismatch");
        require(encoded.get(ValueLayout.JAVA_LONG, MetalResourceBindingPacket.HEADER_CAPABILITIES)
                        == MetalResourceBindingPacket.SUPPORTED_CAPABILITIES,
                "resource binding packet capability mask mismatch");
        require(encoded.get(ValueLayout.JAVA_INT, MetalResourceBindingPacket.HEADER_COUNT) == 3,
                "resource binding packet record count mismatch");
        long firstRecord = MetalResourceBindingPacket.HEADER_BYTES;
        require(encoded.get(ValueLayout.JAVA_INT, firstRecord + MetalResourceBindingPacket.RECORD_TYPE)
                        == MetalResourceBindingPacket.TYPE_UNIFORM_BUFFER,
                "resource binding packet encoded the wrong first record type");
        require(encoded.get(ValueLayout.JAVA_LONG, firstRecord + MetalResourceBindingPacket.RECORD_OFFSET) == 16L
                        && encoded.get(ValueLayout.JAVA_LONG,
                        firstRecord + MetalResourceBindingPacket.RECORD_LENGTH) == 32L,
                "resource binding packet encoded the wrong uniform range");

        packet.reset();
        packet.addTexelTexture(
                MemorySegment.ofAddress(0x5000L),
                1,
                MetalResourceBindingPacket.STAGE_FRAGMENT
        );
        require(packet.finish().address() == storageAddress && packet.count() == 1,
                "resource binding packet did not reuse storage after reset");
        require(packet.requiredCapabilities() == MetalResourceBindingPacket.CAPABILITY_TEXEL_TEXTURE,
                "resource binding packet retained capabilities across reset");

        packet.reset();
        packet.addTexelTexture(
                MemorySegment.ofAddress(0x6000L),
                3,
                MetalResourceBindingPacket.STAGE_VERTEX
        );
        boolean duplicateRejected = false;
        try {
            packet.addUniformBuffer(
                    MemorySegment.ofAddress(0x7000L), 0L, 16L, 16L, 3,
                    MetalResourceBindingPacket.STAGE_VERTEX
            );
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        require(duplicateRejected, "resource binding packet accepted a duplicate numeric binding");

        packet.reset();
        boolean rangeRejected = false;
        try {
            packet.addUniformBuffer(
                    MemorySegment.ofAddress(0x8000L), 12L, 8L, 16L, 0,
                    MetalResourceBindingPacket.STAGE_FRAGMENT
            );
        } catch (IllegalArgumentException expected) {
            rangeRejected = true;
        }
        require(rangeRejected, "resource binding packet accepted an out-of-bounds uniform range");

        packet.close();
        boolean closedRejected = false;
        try {
            packet.reset();
        } catch (IllegalStateException expected) {
            closedRejected = true;
        }
        require(closedRejected, "closed resource binding packet remained writable");
    }

    private static void testPipelineLocalBindingRemap() {
        Map<String, String> uniformValues = Map.of(
                "Projection", "projection-buffer",
                "Fog", "fog-buffer"
        );
        Map<String, String> samplerValues = Map.of("Sampler0", "texture-sampler");
        Object[] resourceSlots = new Object[Long.SIZE];

        List<MetalCompiledRenderPipeline.ResourceBinding> firstLayout = List.of(
                new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                        "Projection", 4, MetalCompiledRenderPipeline.STAGE_VERTEX, null
                ),
                new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE,
                        "Sampler0", 1, MetalCompiledRenderPipeline.STAGE_FRAGMENT, null
                ),
                new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER,
                        "Fog", 6, MetalCompiledRenderPipeline.STAGE_FRAGMENT, null
                )
        );
        MetalRenderPass.remapNamedBindings(uniformValues, samplerValues, firstLayout, resourceSlots);
        require("projection-buffer".equals(resourceSlots[4])
                        && "fog-buffer".equals(resourceSlots[6])
                        && "texture-sampler".equals(resourceSlots[1]),
                "initial pipeline-local resource remap failed");

        List<MetalCompiledRenderPipeline.ResourceBinding> secondLayout = List.of(
                new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                        "Projection", 2, MetalCompiledRenderPipeline.STAGE_FRAGMENT, null
                ),
                new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE,
                        "Sampler0", 7, MetalCompiledRenderPipeline.STAGE_VERTEX, null
                ),
                new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                        "Unbound", 4, MetalCompiledRenderPipeline.STAGE_VERTEX, null
                ),
                new MetalCompiledRenderPipeline.ResourceBinding(
                        MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE,
                        "SamplerMissing", 1, MetalCompiledRenderPipeline.STAGE_FRAGMENT, null
                )
        );
        MetalRenderPass.remapNamedBindings(uniformValues, samplerValues, secondLayout, resourceSlots);
        require("projection-buffer".equals(resourceSlots[2])
                        && "texture-sampler".equals(resourceSlots[7]),
                "pipeline switch did not remap canonical named bindings");
        require(resourceSlots[4] == null && resourceSlots[1] == null,
                "pipeline switch retained stale values in reused binding IDs");
    }

    private static void testResourceBindingBatchSelector() {
        require(!MetalRenderPass.shouldBatchResourceBindings(0L),
                "empty resource state unexpectedly selected the batch path");
        require(!MetalRenderPass.shouldBatchResourceBindings(1L << 17),
                "single dirty resource did not preserve the direct FFM path");
        require(MetalRenderPass.shouldBatchResourceBindings((1L << 2) | (1L << 61)),
                "multiple dirty resources did not select the packet batch path");
    }

    private static void testSodiumLightLegacyPatchPacketAndFacadeValidation() {
        require(SodiumLightLegacyPatchBatch.encode(null)
                        == SodiumLightLegacyPatchBatch.Status.INVALID_ARGUMENT,
                "null Sodium light patch batch was accepted");
        require(SodiumLightLegacyPatchBatch.encode(List.of())
                        == SodiumLightLegacyPatchBatch.Status.EMPTY,
                "empty Sodium light patch batch did not remain a no-op");
        require(SodiumLightLegacyPatchBatch.encode(List.of(
                        new SodiumLightLegacyPatchBatch.Patch(null, null, 0L, 1L)
                )) == SodiumLightLegacyPatchBatch.Status.INVALID_ARGUMENT,
                "null Sodium light patch buffers were accepted");

        GpuBuffer foreignBuffer = new GpuBuffer(GpuBuffer.USAGE_VERTEX, 40L) {
            @Override
            public boolean isClosed() {
                return false;
            }

            @Override
            public void close() {
            }

            @Override
            public GpuBufferSlice.MappedView map(
                    final long offset,
                    final long length,
                    final boolean read,
                    final boolean write
            ) {
                throw new UnsupportedOperationException();
            }
        };
        require(SodiumLightLegacyPatchBatch.encode(List.of(
                        new SodiumLightLegacyPatchBatch.Patch(foreignBuffer, foreignBuffer, 0L, 1L)
                )) == SodiumLightLegacyPatchBatch.Status.INVALID_BUFFER_TYPE,
                "non-Metal Sodium light patch buffer reached the native ABI");

        require(SodiumLightLegacyPatchBatch.packetBytes(0) == 0L
                        && SodiumLightLegacyPatchBatch.packetBytes(2) == 64L,
                "Sodium light patch packet size mismatch");
        boolean oversizedRejected = false;
        try {
            SodiumLightLegacyPatchBatch.packetBytes(SodiumLightLegacyPatchBatch.MAX_PATCHES + 1);
        } catch (IllegalArgumentException expected) {
            oversizedRejected = true;
        }
        require(oversizedRejected, "Sodium light patch packet exceeded its ABI record cap");

        ValueLayout.OfLong littleEndianLong = ValueLayout.JAVA_LONG_UNALIGNED
                .withOrder(ByteOrder.LITTLE_ENDIAN);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = arena.allocate(64L, Long.BYTES);
            SodiumLightLegacyPatchBatch.writeRecord(
                    packet,
                    1,
                    MemorySegment.ofAddress(0x1111L),
                    MemorySegment.ofAddress(0x2222L),
                    7L,
                    13L
            );
            require(packet.get(littleEndianLong, 32L) == 0x1111L
                            && packet.get(littleEndianLong, 40L) == 0x2222L
                            && packet.get(littleEndianLong, 48L) == 7L
                            && packet.get(littleEndianLong, 56L) == 13L,
                    "Sodium light patch record layout drifted from the native ABI");
        }

        for (int code = -9; code <= 1; code++) {
            require(SodiumLightLegacyPatchBatch.Status.fromNative(code).nativeCode() == code,
                    "Sodium light patch native status mapping mismatch for " + code);
        }
        require(SodiumLightLegacyPatchBatch.Status.fromNative(-1000)
                        == SodiumLightLegacyPatchBatch.Status.UNKNOWN_NATIVE_FAILURE,
                "unknown Sodium light patch native status was treated as success");
    }

    private static void testJavaWorkloadTelemetryGateAndReset() {
        require(MetalGpuTiming.createJavaWorkloadTelemetry(false) == null,
                "disabled GPU timing allocated a Java workload accumulator");
        MetalJavaWorkloadTelemetry telemetry = MetalGpuTiming.createJavaWorkloadTelemetry(true);
        require(telemetry != null, "enabled GPU timing did not allocate a Java workload accumulator");

        telemetry.recordCpuToShared(9L);
        telemetry.recordCpuToShared(7L);
        telemetry.recordCpuTransientRequested(11L);
        telemetry.recordCpuTransientRequested(5L);
        telemetry.recordCpuTransientReserved(32L);
        telemetry.recordGpuTransientRequested(20L);
        telemetry.recordGpuTransientRequested(12L);
        telemetry.recordGpuTransientReserved(64L);

        MetalJavaWorkloadTelemetry.Snapshot snapshot = telemetry.snapshot();
        require(snapshot.cpuToSharedBytes() == 16L && snapshot.cpuToSharedOperations() == 2L,
                "known CPU-to-shared copies were not counted exactly");
        require(snapshot.cpuTransientRequestedBytes() == 16L
                        && snapshot.cpuTransientReservedBytes() == 32L,
                "CPU transient requested/reserved accounting mismatch");
        require(snapshot.gpuTransientRequestedBytes() == 32L
                        && snapshot.gpuTransientReservedBytes() == 64L,
                "GPU transient requested/reserved accounting mismatch");

        telemetry.reset();
        MetalJavaWorkloadTelemetry.Snapshot reset = telemetry.snapshot();
        require(reset.cpuToSharedBytes() == 0L
                        && reset.cpuToSharedOperations() == 0L
                        && reset.cpuTransientRequestedBytes() == 0L
                        && reset.cpuTransientReservedBytes() == 0L
                        && reset.gpuTransientRequestedBytes() == 0L
                        && reset.gpuTransientReservedBytes() == 0L,
                "Java workload accumulator did not reset at the submit boundary");
    }

    private static void testJavaWorkloadTelemetryDoesNotInferMappedWrites() {
        MetalJavaWorkloadTelemetry telemetry = new MetalJavaWorkloadTelemetry();
        telemetry.recordGpuTransientRequested(24L);
        telemetry.recordGpuTransientReserved(64L);

        MetalJavaWorkloadTelemetry.Snapshot snapshot = telemetry.snapshot();
        require(snapshot.cpuToSharedBytes() == 0L && snapshot.cpuToSharedOperations() == 0L,
                "mapped reservation was incorrectly reported as an observed CPU write");
        require(snapshot.gpuTransientRequestedBytes() == 24L
                        && snapshot.gpuTransientReservedBytes() == 64L,
                "mapped reservation was not retained as transient allocator pressure");
    }

    private static void testPendingUiSeedConsumeOnceLifecycle() {
        MetalCommandEncoder.PendingUiSeedState<Object> pending =
                new MetalCommandEncoder.PendingUiSeedState<>();
        Object exactDestination = new Object();
        Object mismatchedDestination = new Object();

        pending.arm(exactDestination);
        require(pending.isPending() && pending.peek() == exactDestination,
                "deferred UI seed did not arm");
        require(!pending.consume(mismatchedDestination) && pending.isPending(),
                "mismatched render target consumed the deferred UI seed");
        require(pending.consume(exactDestination) && !pending.isPending(),
                "exact render target did not consume the deferred UI seed");
        require(!pending.consume(exactDestination),
                "deferred UI seed was consumed more than once");

        pending.arm(exactDestination);
        boolean rejectedRearm = false;
        try {
            pending.arm(mismatchedDestination);
        } catch (IllegalStateException expected) {
            rejectedRearm = true;
        }
        require(rejectedRearm && pending.peek() == exactDestination,
                "pending UI seed re-arm replaced unresolved state");
        require(pending.consume(exactDestination) && !pending.isPending(),
                "submit/read materialization did not resolve pending state exactly once");

        require(MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        false, false, 7L, 7L
                ), "exact pending UI destination was not eligible for fusion");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        false, true, 3024, 1964, 3024, 1964,
                        false, false, 7L, 7L
                ), "mismatched UI destination was eligible for fusion");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        false, false, 7L, 8L
                ), "stale-submit UI seed was eligible for fusion");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        true, false, 7L, 7L
                ), "explicit color clear was incorrectly fused after the UI seed");
        require(!MetalCommandEncoder.canFusePendingUiSeed(
                        true, true, 3024, 1964, 3024, 1964,
                        false, true, 7L, 7L
                ), "semantic MRT pass was incorrectly fused with the UI seed");
    }

    private static void testHdrSceneColorRouting() {
        require(!MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.PENDING_REDIRECT,
                        true, true, true, true, false, true
                ), "pending GUI redirect exposed the live HDR scene to presentation");
        require(!MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.DIRECT_SAFE,
                        false, true, true, true, false, true
                ), "title/loading backdrop was exposed as an HDR world scene");
        require(MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.DIRECT_SAFE,
                        true, true, true, true, false, true
                ), "confirmed GUI redirect did not expose the untouched live HDR scene");
        require(!MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.DIRECT_SAFE,
                        true, true, true, false, false, true
                ), "a different presented texture consumed the live HDR scene");
        require(!MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.DIRECT_SAFE,
                        true, true, true, true, true, true
                ), "a closed live HDR scene remained consumable");
        require(!MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.DIRECT_SAFE,
                        true, true, true, true, false, false
                ), "disabled spatial scaling retained a spatial direct scene");
        require(MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.SNAPSHOT,
                        true, true, false, false, false, true
                ), "materialized HDR snapshot was not consumable");
        require(!MetalDevice.isHdrSceneColorConsumable(
                        MetalDevice.HdrSceneColorState.SNAPSHOT,
                        true, false, false, false, false, true
                ), "missing HDR snapshot handle was accepted");
    }

    private static void testWorldSceneCaptureGate() {
        require(MetalHdrFrame.shouldCaptureWorldScene(true, true, true),
                "a rendered world frame was excluded from HDR scene capture");
        require(!MetalHdrFrame.shouldCaptureWorldScene(false, true, true),
                "loading frame was published as an HDR world scene");
        require(!MetalHdrFrame.shouldCaptureWorldScene(true, false, true),
                "non-advancing frame was published as an HDR world scene");
        require(!MetalHdrFrame.shouldCaptureWorldScene(true, true, false),
                "title frame was published as an HDR world scene");
        require(MetalDevice.shouldRouteScene(true, false, false, true),
                "Enhanced output lost its explicit scene-routing path");
        require(MetalDevice.shouldRouteScene(false, true, true, false),
                "Actual lighting lost scene routing for a rendered world");
        require(!MetalDevice.shouldRouteScene(false, true, false, false),
                "Actual SDR routed a title/loading frame through the world scene path");
        require(MetalDevice.shouldRouteScene(false, true, false, true),
                "Actual EDR title/loading frame lost its required SDR UI target");
        require(!MetalDevice.shouldRouteScene(false, false, true, false),
                "Legacy SDR unexpectedly enabled scene routing");
    }

    private static void testTrackedUiTextureAllocationScope() {
        require(!MetalDevice.shouldTrackTextureHazards(0),
                "ordinary Metal textures unexpectedly enabled implicit hazard tracking");
        require(MetalDevice.shouldTrackTextureHazards(1),
                "reused SDR UI attachments did not enable cross-submit hazard tracking");
        require(MetalDevice.shouldTrackTextureHazards(2),
                "nested tracked texture allocation scope lost its tracking contract");
    }

    private static void testMaterialWorldPassGate() {
        require(MetalDevice.capturedFrameSourceEncoding(
                        true, HdrSourceEncoding.LINEAR, true
                ) == HdrSourceEncoding.LINEAR.nativeValue(),
                "world material frame lost its linear FP16 source contract");
        require(MetalDevice.capturedFrameSourceEncoding(
                        false, HdrSourceEncoding.LINEAR, true
                ) == HdrSourceEncoding.EXTENDED_SRGB.nativeValue(),
                "FP16 title panorama retained the material linear contract");
        require(MetalDevice.capturedFrameSourceEncoding(
                        false, HdrSourceEncoding.LINEAR, false
                ) == HdrSourceEncoding.SRGB.nativeValue(),
                "RGBA8 title panorama retained the material linear contract");
    }

    private static void testMojangLogoFp16BlendCompatibility() {
        var mojangLogo = RenderPipelines.MOJANG_LOGO;
        var vanillaBlend = mojangLogo.getColorTargetState().blendFunction().orElseThrow();
        require(vanillaBlend.color().sourceFactor() == BlendFactor.SRC_ALPHA
                        && vanillaBlend.color().destFactor() == BlendFactor.ONE
                        && vanillaBlend.alpha().sourceFactor() == BlendFactor.SRC_ALPHA
                        && vanillaBlend.alpha().destFactor() == BlendFactor.ONE,
                "vanilla Mojang logo blend contract changed");
        var fp16Blend = MetalCompiledRenderPipeline.resolveBlendFunctionForAttachment(
                        mojangLogo.getLocation(), MTLPixelFormat.RGBA16Float, vanillaBlend
                );
        require(fp16Blend.equals(BlendFunction.TRANSLUCENT),
                "FP16 Mojang logo retained clamp-dependent additive blending");
        require(fp16Blend.alpha().sourceFactor() == BlendFactor.ONE
                        && fp16Blend.alpha().destFactor() == BlendFactor.ONE_MINUS_SRC_ALPHA,
                "FP16 Mojang logo retained over-range additive alpha");
        require(MetalCompiledRenderPipeline.resolveBlendFunctionForAttachment(
                        mojangLogo.getLocation(), MTLPixelFormat.RGBA8Unorm, vanillaBlend
                ).equals(BlendFunction.LIGHTNING),
                "RGBA8 Mojang logo lost its vanilla blend function");
        require(MetalCompiledRenderPipeline.resolveBlendFunctionForAttachment(
                        RenderPipelines.LIGHTNING.getLocation(),
                        MTLPixelFormat.RGBA16Float,
                        BlendFunction.LIGHTNING
                ).equals(BlendFunction.LIGHTNING),
                "unrelated FP16 additive pipeline was rewritten");
    }

    private static void testL2GenerationResourceRouting() {
        require(MetalDevice.usesLegacyHdrSemanticAttachment(
                        RenderContractMode.LEGACY, DisplayOutputMode.HDR
                ), "Legacy HDR lost its semantic attachment");
        require(!MetalDevice.usesLegacyHdrSemanticAttachment(
                        RenderContractMode.METALLUM, DisplayOutputMode.HDR
                ), "METALLUM HDR retained the semantic attachment");
        require(!MetalDevice.usesLegacyHdrSemanticAttachment(
                        RenderContractMode.LEGACY, DisplayOutputMode.SDR
                ), "Legacy SDR created a semantic attachment");
        require(!MetalDevice.usesLegacyHdrSemanticAttachment(
                        RenderContractMode.METALLUM, DisplayOutputMode.SDR
                ), "METALLUM SDR created a semantic attachment");

        require(MetalDevice.usesLegacyHdrDepthSnapshot(RenderContractMode.LEGACY, true),
                "Legacy enhanced HDR lost its depth snapshot");
        require(!MetalDevice.usesLegacyHdrDepthSnapshot(RenderContractMode.METALLUM, true),
                "METALLUM HDR retained inferred-reconstruction depth work");
        require(!MetalDevice.usesLegacyHdrDepthSnapshot(RenderContractMode.LEGACY, false),
                "inactive Legacy enhancement created depth work");

        require(MetalCompiledRenderPipeline.selectLegacyGenerationFlavor(
                        HdrPipelinePolicy.Role.SCENE_RASTER,
                        false, true, false, false, true
                ) == HdrShaderFlavor.LEGACY,
                "Legacy SDR selected a semantic shader flavor");
        require(MetalCompiledRenderPipeline.selectLegacyGenerationFlavor(
                        HdrPipelinePolicy.Role.SCENE_RASTER,
                        true, true, false, true, true
                ) == HdrShaderFlavor.LEGACY_HDR_SEMANTIC,
                "Legacy HDR fallback did not select its isolated semantic flavor");
        require(MetalCompiledRenderPipeline.selectLegacyGenerationFlavor(
                        HdrPipelinePolicy.Role.SCENE_RASTER,
                        true, true, true, true, true
                ) == HdrShaderFlavor.SCENE_RASTER_LINEAR,
                "Legacy scene-linear HDR did not retain its semantic scene flavor");
        require(MetalCompiledRenderPipeline.selectLegacyGenerationFlavor(
                        HdrPipelinePolicy.Role.SCENE_RASTER,
                        true, false, true, true, true
                ) == HdrShaderFlavor.LEGACY,
                "display/UI attachment selected a Legacy HDR scene flavor");

        require(!MetalDevice.usesSemanticShaderFlavor(HdrShaderFlavor.LEGACY)
                        && !MetalDevice.usesSemanticShaderFlavor(HdrShaderFlavor.METALLUM)
                        && MetalDevice.usesSemanticShaderFlavor(
                        HdrShaderFlavor.LEGACY_HDR_SEMANTIC),
                "semantic patching escaped its Legacy HDR-only shader variants");
        require(SceneLinearClearColor.shouldDecode(false, true, true),
                "METALLUM SDR scene clear was not decoded to linear light");
        require(SceneLinearClearColor.shouldDecode(true, true, true),
                "METALLUM HDR scene clear was not decoded to linear light");
        require(!SceneLinearClearColor.shouldDecode(false, false, true),
                "display/UI clear was decoded as material scene color");

        require(MetalDevice.resolveMainSceneStorage(true, false)
                        == RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
                        && MetalDevice.resolveMainSceneStorage(false, true)
                        == RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
                        && MetalDevice.resolveMainSceneStorage(false, false)
                        == RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA8,
                "MainTarget storage routing ignored a Legacy or METALLUM startup FP16 request");

        require(MetalDevice.resolveRendererOutputMode(HdrOutputMode.SDR)
                        == DisplayOutputMode.SDR
                        && MetalDevice.resolveRendererOutputMode(HdrOutputMode.EDR)
                        == DisplayOutputMode.HDR
                        && MetalDevice.resolveRendererOutputMode(HdrOutputMode.ENHANCED)
                        == DisplayOutputMode.HDR,
                "HDR scene-generation output was coupled to material admission");

        MetallumMaterialState.configure(true, false);
        require(MetalDevice.resolveAvailableHdrOutputMode(HdrOutputMode.EDR, false)
                        == HdrOutputMode.SDR
                        && MetalDevice.resolveAvailableHdrOutputMode(HdrOutputMode.ENHANCED, false)
                        == HdrOutputMode.SDR,
                "startup METALLUM RGBA8 routing admitted a live HDR output");
        MetallumMaterialState.configure(true, true);
        require(MetalDevice.resolveAvailableHdrOutputMode(HdrOutputMode.EDR, false)
                        == HdrOutputMode.EDR
                        && MetalDevice.resolveAvailableHdrOutputMode(HdrOutputMode.ENHANCED, false)
                        == HdrOutputMode.ENHANCED
                        && MetalDevice.resolveAvailableHdrOutputMode(HdrOutputMode.ENHANCED, true)
                        == HdrOutputMode.EDR,
                "startup METALLUM FP16 routing did not preserve independently safe output modes");
        MetallumMaterialState.reset();
    }

    private static void testL2AtomicMaterialFallback() {
        require(MetalCompiledRenderPipeline.shouldSuppressUnsupportedMaterialDraw(
                        true, true, false, HdrPipelinePolicy.Role.UNKNOWN,
                        false, true
                ), "unknown material scene pipeline was not suppressed");
        require(!MetalCompiledRenderPipeline.shouldSuppressUnsupportedMaterialDraw(
                        true, true, false, HdrPipelinePolicy.Role.SCENE_RASTER,
                        true, true
                ), "known material scene pipeline was suppressed");
        require(MetalCompiledRenderPipeline.shouldSuppressUnsupportedMaterialDraw(
                        true, true, false, HdrPipelinePolicy.Role.SCENE_RASTER,
                        false, false
                ), "failed lazy material variant was not suppressed");
        require(!MetalCompiledRenderPipeline.shouldSuppressUnsupportedMaterialDraw(
                        false, true, false, HdrPipelinePolicy.Role.UNKNOWN,
                        false, false
                ), "Legacy scene draw was suppressed");
        require(!MetalCompiledRenderPipeline.shouldSuppressUnsupportedMaterialDraw(
                        true, true, true, HdrPipelinePolicy.Role.UNKNOWN,
                        false, false
                ), "separate SDR UI/display draw was suppressed");
        require(!MetalCompiledRenderPipeline.shouldSuppressUnsupportedMaterialDraw(
                        true, false, false, HdrPipelinePolicy.Role.UNKNOWN,
                        false, false
                ), "non-scene data draw was suppressed");

        require(MetalCommandEncoder.shouldPresentRendererGeneration(
                        false, 7L, 7L, 11L, 11L
                ), "matching renderer generation was dropped");
        require(!MetalCommandEncoder.shouldPresentRendererGeneration(
                        true, 7L, 7L, 11L, 11L
                ), "explicitly invalidated renderer generation was presented");
        require(!MetalCommandEncoder.shouldPresentRendererGeneration(
                        false, 7L, 8L, 11L, 11L
                ), "stale renderer-generation ID was presented");
        require(!MetalCommandEncoder.shouldPresentRendererGeneration(
                        false, 7L, 7L, 11L, 12L
                ), "stale material-coverage epoch was presented");
        require(!MetalCommandEncoder.shouldPresentRendererGeneration(
                        false, Long.MIN_VALUE, 0L, Long.MIN_VALUE, 0L
                ), "unstamped command buffer was presented");

        require(!SceneLinearClearColor.shouldDecode(true, true, false, false),
                "Legacy SDR FP16 compatibility clear was decoded to linear");
        require(SceneLinearClearColor.shouldDecode(true, true, false, true),
                "resolved Legacy HDR scene-linear clear stayed gamma encoded");
    }

    private static void testTextureBindingHolderUpdatesInPlace() {
        FakeTextureView firstView = new FakeTextureView();
        FakeTextureView secondView = new FakeTextureView();
        FakeSampler firstSampler = new FakeSampler();
        FakeSampler secondSampler = new FakeSampler();
        Map<String, MetalRenderPass.TextureViewAndSampler> bindings = new HashMap<>();
        MetalRenderPass.TextureViewAndSampler originalBinding = MetalRenderPass.updateTextureBinding(
                bindings, "Sampler0", firstView, firstSampler
        );

        MetalRenderPass.TextureViewAndSampler rebound = MetalRenderPass.updateTextureBinding(
                bindings, "Sampler0", secondView, secondSampler
        );

        require(rebound == originalBinding, "texture binding holder was replaced instead of updated");
        require(bindings.size() == 1 && bindings.get("Sampler0") == originalBinding,
                "texture binding map did not retain the original holder");
        require(rebound.textureView() == secondView, "texture binding holder retained the previous texture view");
        require(rebound.sampler() == secondSampler, "texture binding holder retained the previous sampler");
    }

    private static void testDynamicBackingPoolBoundsAndReuse() {
        List<String> released = new ArrayList<>();
        DynamicBackingPool<String> pool = new DynamicBackingPool<>(16L, 2, released::add);

        pool.offer("four-a", 4L);
        pool.offer("four-b", 4L);
        pool.offer("four-overflow", 4L);
        require(released.equals(List.of("four-overflow")), "per-size backing limit did not release overflow");
        require(pool.pooledEntries() == 2 && pool.pooledBytes() == 8L, "pooled backing accounting mismatch");

        require("four-b".equals(pool.take(4L)), "dynamic backing pool did not reuse the newest exact-size entry");
        require(pool.pooledEntries() == 1 && pool.pooledBytes() == 4L, "take did not update pool accounting");

        pool.offer("sixteen", 16L);
        require(released.contains("four-a"), "byte budget did not evict the least-recently-used bucket");
        require(pool.pooledEntries() == 1 && pool.pooledBytes() == 16L, "byte-bounded pool retained excess entries");

        pool.offer("oversized", 32L);
        require(released.contains("oversized"), "oversized backing was retained");
        pool.drain();
        require(released.contains("sixteen"), "drain did not release retained backing");
        require(pool.pooledEntries() == 0 && pool.pooledBytes() == 0L, "drain did not reset pool accounting");
    }

    private static void testPartialDynamicWritePreservation() {
        java.nio.ByteBuffer previous = java.nio.ByteBuffer.allocate(16);
        java.nio.ByteBuffer fresh = java.nio.ByteBuffer.allocate(16);
        for (int index = 0; index < 16; index++) {
            previous.put(index, (byte) index);
            fresh.put(index, (byte) -1);
        }

        MetalCommandEncoder.copyPreservedDynamicRanges(previous, fresh, 4L, 4, 16L);
        for (int index = 0; index < 16; index++) {
            byte expected = index >= 4 && index < 8 ? (byte) -1 : (byte) index;
            require(fresh.get(index) == expected, "partial dynamic write preserved the wrong byte at " + index);
        }

        java.nio.ByteBuffer fullWrite = java.nio.ByteBuffer.allocate(16);
        for (int index = 0; index < 16; index++) {
            fullWrite.put(index, (byte) -1);
        }
        MetalCommandEncoder.copyPreservedDynamicRanges(previous, fullWrite, 0L, 16, 16L);
        for (int index = 0; index < 16; index++) {
            require(fullWrite.get(index) == (byte) -1, "full dynamic write copied obsolete contents");
        }
    }

    private static final class FakeTextureView extends GpuTextureView {
        private FakeTextureView() {
            super(null, 0, 1);
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isClosed() {
            return false;
        }
    }

    private static final class FakeSampler extends GpuSampler {
        @Override
        public AddressMode getAddressModeU() {
            return AddressMode.CLAMP_TO_EDGE;
        }

        @Override
        public AddressMode getAddressModeV() {
            return AddressMode.CLAMP_TO_EDGE;
        }

        @Override
        public FilterMode getMinFilter() {
            return FilterMode.NEAREST;
        }

        @Override
        public FilterMode getMagFilter() {
            return FilterMode.NEAREST;
        }

        @Override
        public int getMaxAnisotropy() {
            return 1;
        }

        @Override
        public OptionalDouble getMaxLod() {
            return OptionalDouble.empty();
        }

        @Override
        public void close() {
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectIllegalState(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }
}
