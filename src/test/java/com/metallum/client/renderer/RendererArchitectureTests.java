package com.metallum.client.renderer;

import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.FrameStateAbi;
import com.metallum.client.renderer.temporal.Matrix4;

import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;

/** Dependency-free renderer architecture contract tests. */
public final class RendererArchitectureTests {
    private RendererArchitectureTests() {
    }

    public static void main(final String[] args) {
        testIndependentModeMatrix();
        testProductionMetallumRejection();
        testFailClosedSelection();
        testIndependentOptionalFeatureFallback();
        testRendererConfigDefaults();
        testGenerationManifests();
        testImmutableCapabilitySnapshot();
        testNativeCapabilitySnapshot();
        testInvalidConfigsAreRejected();
        testTemporalPreparationContract();
        testFrameStateGenerationTransitions();
        testAllHistoryResetReasonsAreRepresentable();
        testFrameStateNumericContracts();
        testFrameStateLightingContractAndAbi();
        testFrameStateImmutability();
        System.out.println("Renderer architecture P1/P2/P4/L0 tests passed");
    }

    private static void testIndependentModeMatrix() {
        MetalCapabilities all = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METAL4_CORE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_LIGHTING
        );
        for (LightingMode lighting : LightingMode.values()) {
            for (DisplayOutputMode output : DisplayOutputMode.values()) {
                for (MetalExecutorKind executor : MetalExecutorKind.values()) {
                    RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                            lighting,
                            output,
                            executor,
                            DisplayOutputMode.SDR,
                            all,
                            RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
                    );
                    require(!resolution.fellBack(), "supported matrix entry fell back");
                    require(resolution.config().lightingMode() == lighting, "lighting/output coupling");
                    require(resolution.config().outputMode() == output, "output/lighting coupling");
                    require(resolution.config().executorKind() == executor, "executor selection changed");
                }
            }
        }

        RendererGenerationConfig.Resolution metallumSdr = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.SDR,
                all,
                1
        );
        require(metallumSdr.config().lightingMode() == LightingMode.METALLUM,
                "HDR off incorrectly forced legacy lighting");
    }

    private static void testProductionMetallumRejection() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(true);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.HDR,
                production,
                1
        );
        require(resolution.fellBack(), "unimplemented Metallum lighting was accepted");
        require(resolution.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.LIGHTING_UNAVAILABLE)),
                "unexpected production fallback reason");
        require(resolution.config().lightingMode() == LightingMode.LEGACY,
                "unimplemented lighting did not fail closed");
        require(resolution.config().outputMode() == DisplayOutputMode.HDR,
                "lighting fallback changed the current safe HDR output");
    }

    private static void testFailClosedSelection() {
        MetalCapabilities sdrMetal3 = MetalCapabilities.productionMetal3(false);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL4,
                DisplayOutputMode.HDR,
                sdrMetal3,
                1
        );
        require(resolution.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.LIGHTING_UNAVAILABLE,
                        RendererGenerationConfig.RejectionReason.OUTPUT_UNAVAILABLE,
                        RendererGenerationConfig.RejectionReason.EXECUTOR_UNAVAILABLE)),
                "unsupported combination did not report every rejection");
        require(resolution.config().lightingMode() == LightingMode.LEGACY
                        && resolution.config().outputMode() == DisplayOutputMode.SDR
                        && resolution.config().executorKind() == MetalExecutorKind.METAL3,
                "unsupported combination did not fail closed to Legacy + safe output + Metal 3");
    }

    private static void testIndependentOptionalFeatureFallback() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(true);
        RendererFeatureMask requested = RendererFeatureMask.of(
                RendererFeatureMask.SPATIAL_UPSCALING,
                RendererFeatureMask.FRAME_INTERPOLATION
        );
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                LightingMode.LEGACY,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.ULTRA,
                requested,
                DisplayOutputMode.HDR,
                production,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        require(resolution.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.UPSCALER_UNAVAILABLE,
                        RendererGenerationConfig.RejectionReason.INTERPOLATION_UNAVAILABLE)),
                "optional feature rejection reasons were coupled");
        require(resolution.config().lightingMode() == LightingMode.LEGACY
                        && resolution.config().outputMode() == DisplayOutputMode.HDR
                        && resolution.config().executorKind() == MetalExecutorKind.METAL3
                        && resolution.config().lightingPreset() == LightingPreset.ULTRA
                        && resolution.config().featureMask().equals(RendererFeatureMask.NONE),
                "optional feature failure changed an independent generation axis");

        MetalCapabilities spatial = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALFX_SPATIAL
        );
        RendererGenerationConfig.Resolution partial = RendererGenerationConfig.resolve(
                LightingMode.LEGACY,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                requested,
                DisplayOutputMode.HDR,
                spatial,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        require(partial.config().featureMask().contains(RendererFeatureMask.SPATIAL_UPSCALING)
                        && !partial.config().featureMask().contains(RendererFeatureMask.FRAME_INTERPOLATION)
                        && partial.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.INTERPOLATION_UNAVAILABLE)),
                "supported Spatial upscaling was removed with unsupported interpolation");
    }

    private static void testRendererConfigDefaults() {
        RendererConfig defaults = RendererConfig.from(new Properties());
        require(!defaults.improvedLighting()
                        && defaults.lightingPreset() == LightingPreset.BALANCED
                        && !defaults.frameInterpolation(),
                "renderer config defaults are not fail-closed");
        Properties configured = new Properties();
        configured.setProperty("improvedLighting", "true");
        configured.setProperty("lightingPreset", "ultra");
        configured.setProperty("frameInterpolation", "true");
        RendererConfig parsed = RendererConfig.from(configured);
        require(parsed.improvedLighting()
                        && parsed.lightingPreset() == LightingPreset.ULTRA
                        && parsed.frameInterpolation(),
                "renderer config axes were not parsed independently");
        configured.setProperty("lightingPreset", "unknown");
        require(RendererConfig.from(configured).lightingPreset() == LightingPreset.BALANCED,
                "invalid preset did not use the balanced default");
        expectIllegalArgument(() -> RendererFeatureMask.of(
                RendererFeatureMask.SPATIAL_UPSCALING,
                RendererFeatureMask.TEMPORAL_UPSCALING
        ));
    }

    private static void testGenerationManifests() {
        MetalCapabilities all = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_LIGHTING,
                MetalCapabilities.Feature.METALFX_SPATIAL
        );
        RendererGenerationPlanner.Extent render = new RendererGenerationPlanner.Extent(1280, 720);
        RendererGenerationPlanner.Extent display = new RendererGenerationPlanner.Extent(2560, 1440);
        for (LightingMode lighting : LightingMode.values()) {
            for (DisplayOutputMode output : DisplayOutputMode.values()) {
                RendererGenerationPlanner.Plan plan = RendererGenerationPlanner.plan(
                        lighting,
                        output,
                        MetalExecutorKind.METAL3,
                        LightingPreset.BALANCED,
                        RendererFeatureMask.NONE,
                        DisplayOutputMode.SDR,
                        all,
                        render,
                        display
                );
                require(plan.resolution().config().lightingMode() == lighting
                                && plan.resolution().config().outputMode() == output,
                        "one of the four declarative combinations failed to compile");
                require(plan.manifest().passCount(RendererGenerationManifest.Domain.LIGHTING_ONLY) == 0L
                                && plan.manifest().resourceBytes(
                                RendererGenerationManifest.Domain.LIGHTING_ONLY) == 0L,
                        "L0 unexpectedly allocated or scheduled lighting work");
                require(plan.manifest().executable() == (lighting == LightingMode.LEGACY),
                        "L0 coverage admission mismatch");
                if (output == DisplayOutputMode.SDR) {
                    require(plan.manifest().passCount(RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                                    && plan.manifest().resourceBytes(
                                    RendererGenerationManifest.Domain.HDR_ONLY) == 0L,
                            "SDR manifest retained HDR work");
                }
            }
        }

        RendererGenerationPlanner.Plan spatial = RendererGenerationPlanner.plan(
                LightingMode.LEGACY,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.PERFORMANCE,
                RendererFeatureMask.of(RendererFeatureMask.SPATIAL_UPSCALING),
                DisplayOutputMode.HDR,
                all,
                render,
                display
        );
        require(spatial.manifest().passCount(RendererGenerationManifest.Domain.UPSCALE_ONLY) == 1L
                        && spatial.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.UPSCALE_ONLY) > 0L,
                "Spatial manifest did not declare its isolated work");
        long renderPixels = 1280L * 720L;
        long displayPixels = 2560L * 1440L;
        long quarterPixels = 320L * 180L;
        long expectedSpatialHdrBytes = renderPixels * 4L
                + renderPixels * 4L
                + quarterPixels * 8L
                + quarterPixels * 8L
                + 64L * Integer.BYTES
                + 32L
                + renderPixels * 8L
                + displayPixels * 4L
                + displayPixels * 4L;
        require(spatial.manifest().resourceBytes(RendererGenerationManifest.Domain.HDR_ONLY)
                        == expectedSpatialHdrBytes,
                "Spatial HDR manifest sized its pre-upscale composite at display extent");
        require(spatial.manifest().passes().stream().anyMatch(pass ->
                        pass.name().equals("hdr_world_reconstruction"))
                        && spatial.manifest().passes().stream().anyMatch(pass ->
                        pass.name().equals("ui_render_with_seed"))
                        && spatial.manifest().passes().stream().noneMatch(pass ->
                        pass.name().equals("hdr_world_ui_seed")),
                "Spatial HDR manifest topology diverged from the executable frame graph");

        RendererGenerationPlanner.Plan nativeHdr = RendererGenerationPlanner.plan(
                LightingMode.LEGACY,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.PERFORMANCE,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                all,
                display,
                display
        );
        require(nativeHdr.manifest().passes().stream().anyMatch(pass ->
                        pass.name().equals("hdr_world_ui_seed"))
                        && nativeHdr.manifest().passes().stream().anyMatch(pass ->
                        pass.name().equals("ui_render"))
                        && nativeHdr.manifest().passes().stream().noneMatch(pass ->
                        pass.name().equals("hdr_world_reconstruction")),
                "Native HDR manifest topology diverged from the executable frame graph");

        RendererGenerationPlanner.Plan spatialSdr = RendererGenerationPlanner.plan(
                LightingMode.LEGACY,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.PERFORMANCE,
                RendererFeatureMask.of(RendererFeatureMask.SPATIAL_UPSCALING),
                DisplayOutputMode.SDR,
                all,
                render,
                display
        );
        require(spatialSdr.manifest().resourceBytes(RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                        && spatialSdr.manifest().passCount(
                        RendererGenerationManifest.Domain.HDR_ONLY) == 0L,
                "Spatial SDR manifest retained HDR work");
        require(spatialSdr.manifest().resourceBytes(RendererGenerationManifest.Domain.UPSCALE_ONLY)
                        == renderPixels * 4L
                        && spatialSdr.manifest().passCount(
                        RendererGenerationManifest.Domain.UPSCALE_ONLY) == 2L,
                "Spatial SDR manifest did not model direct output and perceptual preparation");
        require(spatialSdr.manifest().resources().stream().anyMatch(resource ->
                        resource.name().equals("metalfx_output") && resource.external())
                        && spatialSdr.manifest().passes().stream().anyMatch(pass ->
                        pass.name().equals("metalfx_perceptual_prepare")),
                "Spatial SDR manifest diverged from its direct-output path");
    }

    private static void testImmutableCapabilitySnapshot() {
        EnumSet<MetalCapabilities.Feature> source = EnumSet.of(MetalCapabilities.Feature.METAL3_BASE);
        MetalCapabilities snapshot = MetalCapabilities.of(source.toArray(MetalCapabilities.Feature[]::new));
        source.add(MetalCapabilities.Feature.METAL4_CORE);
        require(!snapshot.supports(MetalCapabilities.Feature.METAL4_CORE),
                "capability snapshot retained a mutable source");
        expectUnsupported(() -> snapshot.features().add(MetalCapabilities.Feature.HDR_OUTPUT));
    }

    private static void testNativeCapabilitySnapshot() {
        long nativeSnapshot = MetalCapabilities.NATIVE_METAL3_BASE
                | MetalCapabilities.NATIVE_METAL4_OS_API
                | MetalCapabilities.NATIVE_METAL4_GPU_FAMILY
                | MetalCapabilities.NATIVE_METAL4_CORE
                | MetalCapabilities.NATIVE_METAL4_COMPILER
                | MetalCapabilities.NATIVE_METAL4_COMMAND_LIFECYCLE
                | MetalCapabilities.NATIVE_METAL4_ARGUMENT_TABLES
                | MetalCapabilities.NATIVE_METAL4_EXPLICIT_BARRIERS
                | MetalCapabilities.NATIVE_METALFX_SPATIAL
                | MetalCapabilities.NATIVE_METALFX_TEMPORAL
                | MetalCapabilities.NATIVE_METALFX_FRAME_INTERPOLATION
                | MetalCapabilities.NATIVE_METALFX_TEMPORAL_METAL4
                | MetalCapabilities.NATIVE_METALFX_FRAME_INTERPOLATION_METAL4
                | MetalCapabilities.NATIVE_REQUIRED_TEXTURE_FORMATS_USAGES
                | MetalCapabilities.NATIVE_DISPLAY_REFRESH
                | MetalCapabilities.NATIVE_DISPLAY_HEADROOM
                | (120L << MetalCapabilities.NATIVE_REFRESH_SHIFT);
        MetalCapabilities capabilities = MetalCapabilities.fromNativeSnapshot(
                nativeSnapshot,
                new EdrCapabilities(1.25f, 2.0f)
        );
        require(capabilities.supports(MetalCapabilities.Feature.METAL4_OS_API)
                        && capabilities.supports(MetalCapabilities.Feature.METAL4_GPU_FAMILY)
                        && capabilities.supports(MetalCapabilities.Feature.METAL4_CORE)
                        && capabilities.supports(MetalCapabilities.Feature.METAL4_COMPILER)
                        && capabilities.supports(MetalCapabilities.Feature.METAL4_COMMAND_LIFECYCLE)
                        && capabilities.supports(MetalCapabilities.Feature.METAL4_ARGUMENT_TABLES)
                        && capabilities.supports(MetalCapabilities.Feature.METAL4_EXPLICIT_BARRIERS),
                "Metal 4 sub-capabilities collapsed or decoded incorrectly");
        require(capabilities.supports(MetalCapabilities.Feature.METALFX_SPATIAL)
                        && capabilities.supports(MetalCapabilities.Feature.METALFX_TEMPORAL)
                        && capabilities.supports(MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION)
                        && capabilities.supports(MetalCapabilities.Feature.METALFX_TEMPORAL_METAL4)
                        && capabilities.supports(
                        MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION_METAL4),
                "MetalFX capabilities were not decoded independently");
        require(capabilities.formatUsageProfile().requiredEngineFormatsAndUsages()
                        && !capabilities.formatUsageProfile().effectSpecificUsagesValidated(),
                "format probe was confused with effect-specific usage validation");
        require(capabilities.displayCapabilities().maximumFramesPerSecond() == 120
                        && capabilities.displayCapabilities().currentHeadroom() == 1.25f
                        && capabilities.displayCapabilities().potentialHeadroom() == 2.0f
                        && capabilities.supports(MetalCapabilities.Feature.HDR_OUTPUT),
                "display refresh/headroom snapshot mismatch");
        require(capabilities.evidenceFor(MetalCapabilities.Feature.METAL4_COMPILER)
                        == MetalCapabilities.Evidence.RUNTIME_PROBE
                        && capabilities.evidenceFor(MetalCapabilities.Feature.METALFX_TEMPORAL)
                        == MetalCapabilities.Evidence.FRAMEWORK_DEVICE_QUERY
                        && capabilities.evidenceFor(
                        MetalCapabilities.Feature.REQUIRED_TEXTURE_FORMATS_USAGES)
                        == MetalCapabilities.Evidence.FORMAT_USAGE_PROBE,
                "capability evidence sources were lost");
        require(!capabilities.supports(MetalCapabilities.Feature.METALLUM_LIGHTING),
                "native discovery accidentally enabled unimplemented lighting");
        expectUnsupported(() -> capabilities.evidence().put(
                MetalCapabilities.Feature.METALLUM_LIGHTING,
                MetalCapabilities.Evidence.DECLARED
        ));

        RendererGenerationConfig metal3WithTemporalAvailable = new RendererGenerationConfig(
                LightingMode.LEGACY,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                capabilities,
                1
        );
        require(metal3WithTemporalAvailable.executorKind() == MetalExecutorKind.METAL3
                        && metal3WithTemporalAvailable.capabilities().supports(
                        MetalCapabilities.Feature.METALFX_TEMPORAL)
                        && metal3WithTemporalAvailable.capabilities().supports(
                        MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION),
                "MetalFX support incorrectly forced a Metal 4 executor");

        MetalCapabilities osOnly = MetalCapabilities.fromNativeSnapshot(
                MetalCapabilities.NATIVE_METAL3_BASE | MetalCapabilities.NATIVE_METAL4_OS_API,
                EdrCapabilities.SDR
        );
        require(osOnly.supports(MetalCapabilities.Feature.METAL4_OS_API)
                        && !osOnly.supports(MetalCapabilities.Feature.METAL4_GPU_FAMILY)
                        && !osOnly.supports(MetalCapabilities.Feature.METAL4_CORE)
                        && !osOnly.supports(MetalCapabilities.Feature.METAL4_COMPILER),
                "OS/API availability was treated as GPU/compiler support");
    }

    private static void testInvalidConfigsAreRejected() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(false);
        expectIllegalArgument(() -> new RendererGenerationConfig(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                1
        ));
        expectIllegalArgument(() -> new RendererGenerationConfig(
                LightingMode.LEGACY,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                0
        ));
        expectIllegalState(() -> RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL4,
                DisplayOutputMode.SDR,
                MetalCapabilities.of(MetalCapabilities.Feature.METAL4_CORE),
                1
        ));
    }

    private static void testTemporalPreparationContract() {
        FrameContract contract = FrameContract.temporalPreparationV1();
        require(contract.version() == FrameContract.CURRENT_VERSION, "frame contract version mismatch");
        require(contract.motionVectors().availability()
                        == FrameContract.MotionVectorAvailability.UNAVAILABLE,
                "P2 unexpectedly advertises motion vectors");
        require(contract.motionVectors().delta()
                        == FrameContract.MotionVectorDelta.PREVIOUS_NDC_MINUS_CURRENT_NDC
                        && contract.motionVectors().units() == FrameContract.MotionVectorUnits.RENDER_PIXELS
                        && contract.motionVectors().horizontalAxis()
                        == FrameContract.HorizontalAxis.POSITIVE_RIGHT
                        && contract.motionVectors().verticalAxis()
                        == FrameContract.VerticalAxis.POSITIVE_DOWN,
                "motion-vector coordinate convention changed");
        require(contract.depth().range() == FrameContract.DepthRange.ZERO_TO_ONE
                        && contract.depth().reversedZ()
                        && contract.depth().nearPlaneValue() == 1.0
                        && contract.depth().farPlaneValue() == 0.0,
                "reversed-Z declaration changed");
        require(contract.reactiveMask() == FrameContract.ReactiveMaskAvailability.UNAVAILABLE,
                "P2 unexpectedly advertises a reactive mask");
        require(contract.uiComposition() == FrameContract.UiComposition.SEPARATE_SDR_TEXTURE,
                "SDR UI is no longer declared separately");
    }

    private static void testFrameStateGenerationTransitions() {
        FrameState base = frame(
                1L, 2L, 3L, 4L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1280, 720), new FrameState.Extent(2560, 1440), Set.of()
        );
        FrameState changed = frame(
                2L, 3L, 4L, 5L,
                LightingMode.METALLUM, DisplayOutputMode.HDR,
                new FrameState.Extent(1920, 1080), new FrameState.Extent(2560, 1440), Set.of()
        );
        Set<FrameState.HistoryResetReason> resets = FrameState.transitionResetReasons(
                base,
                changed,
                EnumSet.of(FrameState.HistoryResetReason.DIMENSION_CHANGE)
        );
        require(resets.equals(EnumSet.of(
                        FrameState.HistoryResetReason.DIMENSION_CHANGE,
                        FrameState.HistoryResetReason.INTERNAL_RENDER_SCALE_CHANGE,
                        FrameState.HistoryResetReason.RENDERER_GENERATION_CHANGE,
                        FrameState.HistoryResetReason.LIGHTING_MODE_CHANGE,
                        FrameState.HistoryResetReason.OUTPUT_MODE_CHANGE)),
                "generation transition reset policy mismatch: " + resets);
        require(FrameState.transitionResetReasons(null, base, Set.of()).equals(
                        EnumSet.of(FrameState.HistoryResetReason.FIRST_FRAME)),
                "first frame did not invalidate history");

        FrameState resized = frame(
                1L, 2L, 3L, 4L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1600, 900), new FrameState.Extent(3200, 1800), Set.of()
        );
        Set<FrameState.HistoryResetReason> resizeResets = FrameState.transitionResetReasons(
                base, resized, Set.of());
        require(resizeResets.equals(EnumSet.of(FrameState.HistoryResetReason.RESIZE)),
                "display resize was confused with an internal render-scale change");
    }

    private static void testAllHistoryResetReasonsAreRepresentable() {
        EnumSet<FrameState.HistoryResetReason> everyReason = EnumSet.allOf(
                FrameState.HistoryResetReason.class);
        FrameState state = frame(
                1L, 1L, 1L, 1L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(640, 360), new FrameState.Extent(1280, 720), everyReason
        );
        require(state.historyResetReasons().equals(everyReason),
                "one or more required history reset reasons cannot be represented");
    }

    private static void testFrameStateNumericContracts() {
        FrameState sdr = frame(
                7L, 11L, 13L, 17L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1280, 720), new FrameState.Extent(2560, 1440), Set.of()
        );
        FrameState hdr = frame(
                7L, 11L, 13L, 18L,
                LightingMode.LEGACY, DisplayOutputMode.HDR,
                sdr.renderExtent(), sdr.displayExtent(), Set.of()
        );
        require(sdr.renderExtent().width() == 1280 && sdr.renderExtent().height() == 720
                        && sdr.displayExtent().width() == 2560 && sdr.displayExtent().height() == 1440,
                "render/display extents were coupled or reordered");
        require(sdr.lightingGenerationId() == hdr.lightingGenerationId()
                        && sdr.lightingMode() == hdr.lightingMode()
                        && hdr.outputMode() == DisplayOutputMode.HDR,
                "changing SDR/HDR output changed the lighting contract");
        require(Double.doubleToLongBits(sdr.jitterOffset().x()) == Double.doubleToLongBits(0.0)
                        && Double.doubleToLongBits(sdr.jitterOffset().y())
                        == Double.doubleToLongBits(0.0),
                "P2 zero jitter is not deterministic");
        expectIllegalArgument(() -> new FrameState.JitterOffset(0.25, 0.0));
        expectIllegalArgument(() -> new FrameState.Extent(0, 720));
    }

    private static void testFrameStateImmutability() {
        double[] source = Matrix4.identity().elements();
        Matrix4 matrix = Matrix4.of(source);
        source[0] = 9.0;
        double[] copy = matrix.elements();
        copy[5] = 8.0;
        require(matrix.element(0) == 1.0 && matrix.element(5) == 1.0,
                "matrix retained mutable source/output storage");

        EnumSet<FrameState.HistoryResetReason> reasons = EnumSet.of(
                FrameState.HistoryResetReason.CAMERA_CUT);
        FrameState state = frame(
                1L, 1L, 1L, 1L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(640, 360), new FrameState.Extent(1280, 720), reasons
        );
        reasons.add(FrameState.HistoryResetReason.TELEPORT);
        require(!state.historyResetReasons().contains(FrameState.HistoryResetReason.TELEPORT),
                "frame state retained a mutable reset-reason source");
        expectUnsupported(() -> state.historyResetReasons().add(
                FrameState.HistoryResetReason.RESOURCE_PACK_SHADER_RELOAD));
    }

    private static void testFrameStateLightingContractAndAbi() {
        FrameState.Transforms transforms = FrameState.Transforms.identity();
        FrameState spatialHdr = new FrameState(
                FrameContract.temporalPreparationV1(),
                42L,
                7L,
                8L,
                9L,
                10L,
                LightingMode.LEGACY,
                DisplayOutputMode.HDR,
                LightingPreset.PERFORMANCE,
                RendererFeatureMask.of(RendererFeatureMask.SPATIAL_UPSCALING),
                MetalExecutorKind.METAL3,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION,
                new FrameState.ResourceBytes(0L, 32L, 0L, 64L, 0L),
                FrameState.LightingWork.NONE,
                transforms,
                transforms,
                new FrameState.Extent(1280, 720),
                new FrameState.Extent(2560, 1440),
                1.0,
                1.0,
                FrameState.JitterOffset.ZERO,
                Set.of()
        );
        try (Arena arena = Arena.ofConfined()) {
            var packet = FrameStateAbi.encode(spatialHdr, arena);
            require(packet.byteSize() == FrameStateAbi.PACKET_BYTES,
                    "FrameState ABI has an unexpected byte size");
            FrameStateAbi.validatePacket(packet);
            packet.set(ValueLayout.JAVA_INT, 0L, FrameStateAbi.CURRENT_VERSION + 1);
            expectIllegalArgument(() -> FrameStateAbi.validatePacket(packet));
        }

        expectIllegalArgument(() -> new FrameState.ResourceBytes(-1L, 0L, 0L, 0L, 0L));
        expectIllegalArgument(() -> new FrameState(
                FrameContract.temporalPreparationV1(), 1L, 1L, 1L, 1L, 1L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3, 2,
                new FrameState.ResourceBytes(0L, 0L, 1L, 0L, 0L),
                FrameState.LightingWork.NONE,
                transforms, transforms,
                new FrameState.Extent(1, 1), new FrameState.Extent(1, 1),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of()
        ));
        expectIllegalArgument(() -> new FrameState(
                FrameContract.temporalPreparationV1(), 1L, 1L, 1L, 1L, 1L,
                LightingMode.LEGACY, DisplayOutputMode.SDR,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3, 2,
                new FrameState.ResourceBytes(0L, 1L, 0L, 0L, 0L),
                FrameState.LightingWork.NONE,
                transforms, transforms,
                new FrameState.Extent(1, 1), new FrameState.Extent(1, 1),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of()
        ));
    }

    private static FrameState frame(
            final long rendererGeneration,
            final long historyGeneration,
            final long lightingGeneration,
            final long outputGeneration,
            final LightingMode lighting,
            final DisplayOutputMode output,
            final FrameState.Extent renderExtent,
            final FrameState.Extent displayExtent,
            final Set<FrameState.HistoryResetReason> resetReasons
    ) {
        FrameState.Transforms transforms = FrameState.Transforms.identity();
        return new FrameState(
                FrameContract.temporalPreparationV1(),
                42L,
                rendererGeneration,
                historyGeneration,
                lightingGeneration,
                outputGeneration,
                lighting,
                output,
                transforms,
                transforms,
                renderExtent,
                displayExtent,
                1.0,
                1.0,
                FrameState.JitterOffset.ZERO,
                resetReasons
        );
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

    private static void expectUnsupported(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
