package com.metallum.client.renderer;

import com.metallum.client.hdr.EdrCapabilities;
import com.metallum.client.hdr.HdrOutputMode;
import com.metallum.client.hdr.HdrSceneState;
import com.metallum.client.hdr.HdrSourceEncoding;
import com.metallum.client.hdr.MetallumMaterialPreflightGate;
import com.metallum.client.hdr.MetallumMaterialState;
import com.metallum.client.hdr.SceneLinearClearColor;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.FrameStateAbi;
import com.metallum.client.renderer.temporal.Matrix4;
import com.metallum.client.voxel.VoxelClipmapLayout;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free renderer architecture contract tests. */
public final class RendererArchitectureTests {
    private RendererArchitectureTests() {
    }

    public static void main(final String[] args) {
        testIndependentModeMatrix();
        testProductionMetallumRejection();
        testRuntimeMaterialCapabilityAdmission();
        testMaterialGenerationPublication();
        testFailClosedSelection();
        testIndependentOptionalFeatureFallback();
        testRendererConfigDefaults();
        testGenerationManifests();
        testAdvancedLightingManifest();
        testAdvancedLightingRuntimeAdmission();
        testAdvancedLightingAdmissionRace();
        testAdvancedLightingRegistryHandoffRace();
        testImmutableCapabilitySnapshot();
        testNativeCapabilitySnapshot();
        testInvalidConfigsAreRejected();
        testTemporalPreparationContract();
        testFrameStateGenerationTransitions();
        testAllHistoryResetReasonsAreRepresentable();
        testFrameStateNumericContracts();
        testFrameStateLightingContractAndAbi();
        testFrameStateImmutability();
        System.out.println("Renderer architecture P1/P2/P4/L0/L2/L2.5/L3/L4/L5 tests passed");
    }

    private static void testIndependentModeMatrix() {
        MetalCapabilities all = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METAL4_CORE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT,
                MetalCapabilities.Feature.ADVANCED_LIGHTING
        );
        int admittedCombinations = 0;
        for (RenderContractMode contract : RenderContractMode.values()) {
            for (LightingModel lighting : LightingModel.values()) {
                if (contract == RenderContractMode.LEGACY
                        && lighting == LightingModel.ADVANCED) {
                    continue;
                }
                for (DisplayOutputMode output : DisplayOutputMode.values()) {
                    RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                            contract,
                            lighting,
                            output,
                            MetalExecutorKind.METAL3,
                            DisplayOutputMode.SDR,
                            all,
                            RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
                    );
                    require(!resolution.fellBack(), "supported matrix entry fell back");
                    require(resolution.config().renderContractMode() == contract,
                            "contract/output coupling");
                    require(resolution.config().lightingModel() == lighting,
                            "lighting/output coupling");
                    require(resolution.config().outputMode() == output, "output/lighting coupling");
                    admittedCombinations++;
                }
            }
        }
        require(admittedCombinations == 6, "resolved mode matrix does not contain six entries");
        expectIllegalArgument(() -> new RendererGenerationConfig(
                RenderContractMode.LEGACY,
                LightingModel.ADVANCED,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                all,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        ));

        RendererGenerationConfig.Resolution metallumSdr = RendererGenerationConfig.resolve(
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.SDR,
                all,
                1
        );
        require(metallumSdr.config().renderContractMode() == RenderContractMode.METALLUM,
                "HDR off incorrectly forced legacy lighting");

        MetalCapabilities materialOnly = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT
        );
        RendererGenerationConfig.Resolution advancedFallback = RendererGenerationConfig.resolve(
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.HDR,
                materialOnly,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        require(advancedFallback.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.ADVANCED_LIGHTING_UNAVAILABLE))
                        && advancedFallback.config().renderContractMode()
                        == RenderContractMode.METALLUM
                        && advancedFallback.config().lightingModel() == LightingModel.VANILLA
                        && advancedFallback.config().outputMode() == DisplayOutputMode.HDR,
                "Advanced rejection changed material or output axes");
    }

    private static void testProductionMetallumRejection() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(true);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.HDR,
                production,
                1
        );
        require(resolution.fellBack(), "unavailable material contract was accepted");
        require(resolution.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.MATERIAL_CONTRACT_UNAVAILABLE)),
                "unexpected production fallback reason");
        require(resolution.config().renderContractMode() == RenderContractMode.LEGACY
                        && resolution.config().lightingModel() == LightingModel.VANILLA,
                "material admission failure did not fail closed");
        require(resolution.config().outputMode() == DisplayOutputMode.HDR,
                "material fallback changed the current safe HDR output");
    }

    private static void testRuntimeMaterialCapabilityAdmission() {
        MetalCapabilities discovered = MetalCapabilities.productionMetal3(true);
        MetalCapabilities admitted = discovered.withRuntimeFeature(
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT
        );
        require(!discovered.supports(MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT),
                "runtime material admission mutated the discovery snapshot");
        require(admitted.supports(MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT)
                        && admitted.evidenceFor(MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT)
                        == MetalCapabilities.Evidence.RUNTIME_PROBE,
                "runtime material admission lost its capability evidence");

        RendererGenerationConfig.Resolution admittedHdr = RendererGenerationConfig.resolve(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.HDR,
                admitted,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        require(!admittedHdr.fellBack()
                        && admittedHdr.config().renderContractMode() == RenderContractMode.METALLUM
                        && admittedHdr.config().outputMode() == DisplayOutputMode.HDR,
                "runtime material capability did not admit METALLUM HDR atomically");

        RendererGenerationConfig.Resolution rejectedHdr = RendererGenerationConfig.resolve(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.HDR,
                discovered,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        require(rejectedHdr.fellBack()
                        && rejectedHdr.config().renderContractMode() == RenderContractMode.LEGACY
                        && rejectedHdr.config().outputMode() == DisplayOutputMode.HDR,
                "material rejection changed the independently supported HDR output");
    }

    private static void testMaterialGenerationPublication() {
        HdrSceneState.reset();
        MetallumMaterialState.configure(true, false);
        long pendingEpoch = MetallumMaterialState.admission().coverageEpoch();
        require(!MetallumMaterialState.isGenerationActive()
                        && HdrSceneState.sourceEncoding() == HdrSourceEncoding.SRGB,
                "pending material coverage leaked into the published scene contract");
        require(!MetallumMaterialState.requiresFp16Scene(),
                "METALLUM SDR requested an FP16 main scene");
        require(MetallumMaterialState.isSceneStorageCompatible(false)
                        && !MetallumMaterialState.isSceneStorageCompatible(true),
                "METALLUM SDR storage admitted the HDR scene contract");
        require(MetallumMaterialState.resolveCompatibleOutput(HdrOutputMode.SDR)
                        == HdrOutputMode.SDR
                        && MetallumMaterialState.resolveCompatibleOutput(HdrOutputMode.EDR)
                        == HdrOutputMode.SDR
                        && MetallumMaterialState.resolveCompatibleOutput(HdrOutputMode.ENHANCED)
                        == HdrOutputMode.SDR,
                "startup METALLUM RGBA8 storage did not clamp only the live output axis to SDR");

        MetallumMaterialPreflightGate.install(new MetallumMaterialPreflightGate.Evaluation(
                true, "synthetic complete material coverage"
        ));
        MetallumMaterialState.Admission admission = MetallumMaterialState.admission();
        require(admission.active() && admission.coverageEpoch() > pendingEpoch,
                "material coverage did not advance the renderer generation epoch");
        MetallumMaterialState.publishGeneration(true);
        require(MetallumMaterialState.isGenerationActive()
                        && HdrSceneState.sourceEncoding() == HdrSourceEncoding.LINEAR
                        && SceneLinearClearColor.shouldDecode(false, true, true),
                "admitted material generation did not publish one linear decode contract");

        MetallumMaterialState.configure(true, true);
        require(MetallumMaterialState.requiresFp16Scene()
                        && MetallumMaterialState.isSceneStorageCompatible(true)
                        && MetallumMaterialState.isSceneStorageCompatible(false)
                        && MetallumMaterialState.resolveCompatibleOutput(HdrOutputMode.EDR)
                        == HdrOutputMode.EDR
                        && MetallumMaterialState.resolveCompatibleOutput(HdrOutputMode.ENHANCED)
                        == HdrOutputMode.ENHANCED
                        && !MetallumMaterialState.isGenerationActive()
                        && HdrSceneState.sourceEncoding() == HdrSourceEncoding.SRGB,
                "startup METALLUM FP16 storage did not admit both HDR and SDR outputs");
        MetallumMaterialPreflightGate.install(new MetallumMaterialPreflightGate.Evaluation(
                true, "synthetic FP16 material coverage"
        ));
        MetallumMaterialState.publishGeneration(true);
        require(HdrSceneState.sourceEncoding() == HdrSourceEncoding.LINEAR
                        && SceneLinearClearColor.shouldDecode(true, true, true),
                "METALLUM FP16-to-SDR transition lost its scene-linear clear contract");
        MetallumMaterialState.reset();
        HdrSceneState.reset();
    }

    private static void testFailClosedSelection() {
        MetalCapabilities sdrMetal3 = MetalCapabilities.productionMetal3(false);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL4,
                DisplayOutputMode.HDR,
                sdrMetal3,
                1
        );
        require(resolution.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.MATERIAL_CONTRACT_UNAVAILABLE,
                        RendererGenerationConfig.RejectionReason.OUTPUT_UNAVAILABLE,
                        RendererGenerationConfig.RejectionReason.EXECUTOR_UNAVAILABLE)),
                "unsupported combination did not report every rejection");
        require(resolution.config().renderContractMode() == RenderContractMode.LEGACY
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
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
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
        require(resolution.config().renderContractMode() == RenderContractMode.LEGACY
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
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
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
                        && !defaults.frameInterpolation()
                        && !defaults.voxelDebugChecksum(),
                "renderer config defaults are not fail-closed");
        RendererConfig lightingEnabled = defaults.withImprovedLighting(true);
        require(lightingEnabled.improvedLighting()
                        && lightingEnabled.lightingPreset() == LightingPreset.BALANCED
                        && !lightingEnabled.frameInterpolation()
                        && !lightingEnabled.voxelDebugChecksum(),
                "Sodium improved-lighting toggle changed another renderer policy axis");
        RendererConfig checksumEnabled = lightingEnabled.withVoxelDebugChecksum(true);
        require(checksumEnabled.improvedLighting()
                        && checksumEnabled.lightingPreset() == LightingPreset.BALANCED
                        && !checksumEnabled.frameInterpolation()
                        && checksumEnabled.voxelDebugChecksum(),
                "Sodium L5 checksum toggle changed another renderer policy axis");
        Path temporaryDirectory = null;
        try {
            temporaryDirectory = Files.createTempDirectory("metallum-renderer-config-");
            Path configPath = temporaryDirectory.resolve("metallum-renderer.properties");
            Path hdrPath = temporaryDirectory.resolve("metallum-hdr.properties");
            String hdrSentinel = "mode=scene\nhdrStrength=1.37\n";
            Files.writeString(hdrPath, hdrSentinel);

            for (boolean legacyLighting : new boolean[]{false, true}) {
                Files.writeString(
                        configPath,
                        "improvedLighting=" + legacyLighting
                                + "\nlightingPreset=ultra\nframeInterpolation=true\n"
                );
                RendererConfig migrated = RendererConfig.load(configPath);
                require(!migrated.improvedLighting()
                                && migrated.lightingPreset() == LightingPreset.ULTRA
                                && migrated.frameInterpolation()
                                && !migrated.voxelDebugChecksum(),
                        "v1 renderer config did not migrate Advanced to Off");
                String firstMigration = Files.readString(configPath);
                require(firstMigration.contains("schemaVersion=3")
                                && firstMigration.contains("improvedLighting=false")
                                && firstMigration.contains("voxelDebugChecksum=false"),
                        "v1 renderer config was not persisted as schema 3");
                RendererConfig repeated = RendererConfig.load(configPath);
                require(repeated.equals(migrated)
                                && Files.readString(configPath).equals(firstMigration),
                        "schema-3 reload rewrote or changed the migrated config");
                require(Files.readString(hdrPath).equals(hdrSentinel),
                        "renderer migration read or changed the separate HDR file");
            }

            Files.writeString(
                    configPath,
                    "schemaVersion=2\nimprovedLighting=true\nlightingPreset=performance\n"
                            + "frameInterpolation=true\n"
            );
            RendererConfig schemaTwo = RendererConfig.load(configPath);
            require(schemaTwo.improvedLighting()
                            && schemaTwo.lightingPreset() == LightingPreset.PERFORMANCE
                            && schemaTwo.frameInterpolation()
                            && !schemaTwo.voxelDebugChecksum()
                            && Files.readString(configPath).contains("schemaVersion=3"),
                    "schema-2 renderer config did not migrate with L5 checksum Off");

            RendererConfig original = new RendererConfig(
                    false, LightingPreset.ULTRA, true, true
            );
            original.save(configPath);
            original.withImprovedLighting(true).save(configPath);
            RendererConfig persisted = RendererConfig.load(configPath);
            require(persisted.improvedLighting()
                            && persisted.lightingPreset() == LightingPreset.ULTRA
                            && persisted.frameInterpolation()
                            && persisted.voxelDebugChecksum(),
                    "persisted lighting toggle lost another renderer config axis");

            for (String invalid : new String[]{
                    "schemaVersion=99\nimprovedLighting=true\n",
                    "schemaVersion=broken\nimprovedLighting=true\n",
                    "schemaVersion=3\nimprovedLighting=maybe\n",
                    "schemaVersion=3\nimprovedLighting=true\nvoxelDebugChecksum=maybe\n"
            }) {
                Files.writeString(configPath, invalid);
                require(RendererConfig.load(configPath).equals(RendererConfig.defaults()),
                        "invalid renderer schema did not fail closed");
                require(Files.readString(configPath).equals(invalid),
                        "invalid renderer schema was overwritten");
                require(Files.readString(hdrPath).equals(hdrSentinel),
                        "invalid renderer config changed the separate HDR file");
            }
            Files.delete(configPath);
            Files.delete(hdrPath);
            Files.delete(temporaryDirectory);
            temporaryDirectory = null;
        } catch (IOException exception) {
            throw new AssertionError("renderer config roundtrip failed", exception);
        } finally {
            if (temporaryDirectory != null) {
                try {
                    Files.deleteIfExists(temporaryDirectory.resolve(
                            "metallum-renderer.properties"
                    ));
                    Files.deleteIfExists(temporaryDirectory.resolve(
                            "metallum-hdr.properties"
                    ));
                    Files.deleteIfExists(temporaryDirectory);
                } catch (IOException ignored) {
                    // The assertion above already carries the original failure.
                }
            }
        }
        Properties configured = new Properties();
        configured.setProperty("schemaVersion", "3");
        configured.setProperty("improvedLighting", "true");
        configured.setProperty("lightingPreset", "ultra");
        configured.setProperty("frameInterpolation", "true");
        configured.setProperty("voxelDebugChecksum", "true");
        RendererConfig parsed = RendererConfig.from(configured);
        require(parsed.improvedLighting()
                        && parsed.lightingPreset() == LightingPreset.ULTRA
                        && parsed.frameInterpolation()
                        && parsed.voxelDebugChecksum(),
                "renderer config axes were not parsed independently");
        configured.setProperty("lightingPreset", "unknown");
        require(RendererConfig.from(configured).equals(RendererConfig.defaults()),
                "invalid schema-3 preset did not fail closed");
        expectIllegalArgument(() -> RendererFeatureMask.of(
                RendererFeatureMask.SPATIAL_UPSCALING,
                RendererFeatureMask.TEMPORAL_UPSCALING
        ));
    }

    private static void testGenerationManifests() {
        MetalCapabilities all = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT,
                MetalCapabilities.Feature.METALFX_SPATIAL
        );
        RendererGenerationPlanner.Extent render = new RendererGenerationPlanner.Extent(1280, 720);
        RendererGenerationPlanner.Extent display = new RendererGenerationPlanner.Extent(2560, 1440);
        long renderPixels = 1280L * 720L;
        long displayPixels = 2560L * 1440L;
        long quarterPixels = 320L * 180L;

        for (RenderContractMode renderContract : RenderContractMode.values()) {
            for (DisplayOutputMode output : DisplayOutputMode.values()) {
                RendererGenerationPlanner.Plan plan = RendererGenerationPlanner.plan(
                        renderContract,
                        LightingModel.VANILLA,
                        output,
                        MetalExecutorKind.METAL3,
                        LightingPreset.BALANCED,
                        RendererFeatureMask.NONE,
                        DisplayOutputMode.SDR,
                        all,
                        render,
                        display
                );
                require(plan.resolution().config().renderContractMode() == renderContract
                                && plan.resolution().config().outputMode() == output,
                        "one of the four declarative combinations failed to compile");
                require(plan.manifest().executable(),
                        "capability-admitted L2 generation was not executable");
                requireNoL3Work(plan.manifest());
                if (output == DisplayOutputMode.SDR) {
                    require(plan.manifest().passCount(RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                                    && plan.manifest().resourceBytes(
                                    RendererGenerationManifest.Domain.HDR_ONLY) == 0L,
                            "SDR manifest retained HDR work");
                }
            }
        }

        RendererGenerationPlanner.Plan legacySdr = RendererGenerationPlanner.plan(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.SDR,
                all,
                render,
                display
        );
        require(legacySdr.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract.LEGACY_SDR_SRGB8
                        && legacySdr.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract.NONE,
                "Legacy + SDR storage/output contract changed");
        require(resourceNames(legacySdr.manifest()).equals(Set.of(
                        "main_color", "main_depth", "drawable"
                )) && passNames(legacySdr.manifest()).equals(Set.of(
                        "world_render", "ui_render", "present"
                )),
                "Legacy + SDR retained optional work");
        require(legacySdr.manifest().resourceBytes(RendererGenerationManifest.Domain.MATERIAL_ONLY)
                        == 0L
                        && legacySdr.manifest().passCount(
                        RendererGenerationManifest.Domain.MATERIAL_ONLY) == 0L,
                "Legacy + SDR retained material work");

        RendererGenerationPlanner.Plan legacyHdr = RendererGenerationPlanner.plan(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                all,
                render,
                display
        );
        require(legacyHdr.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .LEGACY_HDR_SEMANTIC_RGBA16F
                        && legacyHdr.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract
                        .LEGACY_SEMANTIC_RECONSTRUCTION,
                "Legacy + HDR semantic reconstruction contract changed");
        require(resourceNames(legacyHdr.manifest()).equals(Set.of(
                        "main_color", "main_depth", "drawable", "hdr_semantic",
                        "scene_depth_snapshot", "hdr_emission", "hdr_bloom",
                        "hdr_histogram", "hdr_adaptive_state", "hdr_world_composite",
                        "sdr_ui_color", "sdr_ui_depth"
                )),
                "Legacy + HDR resource topology changed");
        require(passNames(legacyHdr.manifest()).equals(Set.of(
                        "world_render", "ui_render", "present", "scene_depth_snapshot",
                        "hdr_extract", "hdr_exposure_reduce", "hdr_bloom_combined",
                        "hdr_world_ui_seed"
                )),
                "Legacy + HDR pass topology changed");
        long expectedLegacyHdrBytes = renderPixels * 4L
                + renderPixels * 4L
                + quarterPixels * 8L
                + quarterPixels * 8L
                + 64L * Integer.BYTES
                + 32L
                + displayPixels * 8L
                + displayPixels * 4L
                + displayPixels * 4L;
        require(legacyHdr.manifest().resourceBytes(RendererGenerationManifest.Domain.HDR_ONLY)
                        == expectedLegacyHdrBytes,
                "Legacy + HDR owned resource sizes changed");

        RendererGenerationPlanner.Plan legacyHdrStartup = RendererGenerationPlanner.plan(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                all,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
        );
        require(legacyHdrStartup.resolution().config().renderContractMode() == RenderContractMode.LEGACY
                        && legacyHdrStartup.resolution().config().outputMode()
                        == DisplayOutputMode.HDR
                        && legacyHdrStartup.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .LEGACY_HDR_SEMANTIC_RGBA16F,
                "startup Legacy HDR FP16 storage was rejected as an RGBA8 MainTarget");

        RendererGenerationPlanner.Plan legacyEnhancedStartup = RendererGenerationPlanner.plan(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                all,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA8
        );
        require(legacyEnhancedStartup.resolution().config().renderContractMode()
                        == RenderContractMode.LEGACY
                        && legacyEnhancedStartup.resolution().config().outputMode()
                        == DisplayOutputMode.HDR
                        && legacyEnhancedStartup.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .LEGACY_HDR_SEMANTIC_SRGB8
                        && legacyEnhancedStartup.manifest().sceneStorageContract().bytesPerPixel()
                        == 4
                        && !legacyEnhancedStartup.manifest().sceneStorageContract().sceneLinear()
                        && legacyEnhancedStartup.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract
                        .LEGACY_SEMANTIC_RECONSTRUCTION,
                "live EDR-to-Enhanced transition rejected the startup-fixed RGBA8 scene");
        Set<String> legacyEnhancedResources = Set.of(
                "main_color", "main_depth", "drawable", "hdr_semantic",
                "scene_depth_snapshot", "scene_color_snapshot", "hdr_emission", "hdr_bloom",
                "hdr_histogram", "hdr_adaptive_state", "hdr_ui_control_a", "hdr_ui_control_b"
        );
        Set<String> legacyEnhancedPasses = Set.of(
                "world_render", "ui_render", "present", "scene_color_snapshot",
                "scene_depth_snapshot", "hdr_extract", "hdr_exposure_reduce",
                "hdr_bloom_combined", "hdr_ui_compare", "hdr_ui_dilate"
        );
        long halfDisplayPixels = ((display.width() + 1L) / 2L)
                * ((display.height() + 1L) / 2L);
        long expectedLegacyEnhancedBytes = renderPixels * 4L
                + renderPixels * 4L
                + renderPixels * 4L
                + quarterPixels * 8L
                + quarterPixels * 8L
                + 64L * Integer.BYTES
                + 32L
                + halfDisplayPixels * 2L
                + halfDisplayPixels * 2L;
        require(resourceNames(legacyEnhancedStartup.manifest()).equals(legacyEnhancedResources)
                        && passNames(legacyEnhancedStartup.manifest()).equals(legacyEnhancedPasses)
                        && legacyEnhancedStartup.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.HDR_ONLY)
                        == expectedLegacyEnhancedBytes,
                "Legacy Enhanced RGBA8 manifest diverged from its fallback frame graph");

        RendererGenerationPlanner.Plan legacyLiveEnhanced = RendererGenerationPlanner.plan(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                all,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA8,
                false
        );
        Set<String> liveEnhancedResources = new java.util.HashSet<>(legacyEnhancedResources);
        liveEnhancedResources.remove("hdr_semantic");
        require(resourceNames(legacyLiveEnhanced.manifest()).equals(liveEnhancedResources)
                        && passNames(legacyLiveEnhanced.manifest()).equals(legacyEnhancedPasses)
                        && legacyLiveEnhanced.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.HDR_ONLY)
                        == expectedLegacyEnhancedBytes - renderPixels * 4L,
                "live EDR-to-Enhanced manifest retained an unavailable semantic attachment");

        RendererGenerationPlanner.Plan legacyHdrStorageToSdr = RendererGenerationPlanner.plan(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.SDR,
                all,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
        );
        require(legacyHdrStorageToSdr.resolution().config().renderContractMode()
                        == RenderContractMode.LEGACY
                        && legacyHdrStorageToSdr.resolution().config().outputMode()
                        == DisplayOutputMode.SDR
                        && legacyHdrStorageToSdr.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .LEGACY_SDR_SRGB_RGBA16F_COMPAT
                        && legacyHdrStorageToSdr.manifest().sceneStorageContract().bytesPerPixel()
                        == 8
                        && !legacyHdrStorageToSdr.manifest().sceneStorageContract().sceneLinear()
                        && legacyHdrStorageToSdr.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract.NONE
                        && legacyHdrStorageToSdr.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                        && legacyHdrStorageToSdr.manifest().passCount(
                        RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                        && resourceNames(legacyHdrStorageToSdr.manifest()).equals(
                        resourceNames(legacySdr.manifest()))
                        && passNames(legacyHdrStorageToSdr.manifest()).equals(
                        passNames(legacySdr.manifest())),
                "Legacy HDR-to-SDR transition mismatched its fixed FP16 backing or retained HDR work");

        RendererGenerationPlanner.Plan metallumSdr = RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.SDR,
                all,
                render,
                display
        );
        require(metallumSdr.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .METALLUM_SDR_LINEAR_RGBA8
                        && metallumSdr.manifest().sceneStorageContract().bytesPerPixel() == 4
                        && metallumSdr.manifest().sceneStorageContract().sceneLinear()
                        && metallumSdr.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract.NONE,
                "Metallum + SDR did not select compact scene-linear RGBA8 storage");
        require(resourceNames(metallumSdr.manifest()).equals(Set.of(
                        "main_color", "main_depth", "drawable", "sdr_ui_color", "sdr_ui_depth"
                )),
                "Metallum + SDR did not isolate its SDR UI resources");
        require(passNames(metallumSdr.manifest()).equals(Set.of(
                        "world_render", "scene_linear_ui_seed", "ui_render", "present"
                )),
                "Metallum + SDR retained HDR or reconstruction passes");
        require(metallumSdr.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.MATERIAL_ONLY) == displayPixels * 8L
                        && metallumSdr.manifest().passCount(
                        RendererGenerationManifest.Domain.MATERIAL_ONLY) == 1L,
                "Metallum + SDR UI isolation estimate changed");

        RendererGenerationPlanner.Plan metallumHdrStorageToSdr = RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.SDR,
                all,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
        );
        require(metallumHdrStorageToSdr.resolution().config().renderContractMode()
                        == RenderContractMode.METALLUM
                        && metallumHdrStorageToSdr.resolution().config().outputMode()
                        == DisplayOutputMode.SDR
                        && metallumHdrStorageToSdr.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .METALLUM_SDR_LINEAR_RGBA16F_COMPAT
                        && metallumHdrStorageToSdr.manifest().sceneStorageContract().bytesPerPixel()
                        == 8
                        && metallumHdrStorageToSdr.manifest().sceneStorageContract().sceneLinear()
                        && metallumHdrStorageToSdr.manifest().sceneStorageContract()
                        .actualHdrRadiance()
                        && metallumHdrStorageToSdr.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract.NONE,
                "startup METALLUM HDR storage did not remain an explicit FP16-linear SDR contract");
        require(metallumHdrStorageToSdr.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                        && metallumHdrStorageToSdr.manifest().passCount(
                        RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                        && resourceNames(metallumHdrStorageToSdr.manifest()).equals(
                        resourceNames(metallumSdr.manifest()))
                        && passNames(metallumHdrStorageToSdr.manifest()).equals(
                        passNames(metallumSdr.manifest())),
                "METALLUM FP16-compatible SDR generation retained HDR resources or work");

        RendererGenerationPlanner.Plan metallumHdr = RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                all,
                render,
                display
        );
        require(metallumHdr.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .METALLUM_HDR_ACTUAL_RADIANCE_RGBA16F
                        && metallumHdr.manifest().sceneStorageContract().bytesPerPixel() == 8
                        && metallumHdr.manifest().sceneStorageContract().sceneLinear()
                        && metallumHdr.manifest().sceneStorageContract().actualHdrRadiance()
                        && metallumHdr.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract
                        .ACTUAL_RADIANCE_EXPOSURE_BLOOM,
                "Metallum + HDR did not declare actual FP16 scene radiance");
        require(resourceNames(metallumHdr.manifest()).equals(Set.of(
                        "main_color", "main_depth", "drawable", "hdr_emission", "hdr_bloom",
                        "hdr_histogram", "hdr_adaptive_state", "hdr_world_composite",
                        "sdr_ui_color", "sdr_ui_depth"
                )),
                "Metallum + HDR retained semantic/depth-reconstruction resources");
        require(passNames(metallumHdr.manifest()).equals(Set.of(
                        "world_render", "ui_render", "present", "hdr_extract",
                        "hdr_exposure_reduce", "hdr_bloom_combined",
                        "hdr_world_actual_radiance"
                )),
                "Metallum + HDR retained inferred reconstruction passes");
        long expectedMetallumHdrBytes = quarterPixels * 8L
                + quarterPixels * 8L
                + 64L * Integer.BYTES
                + 32L
                + displayPixels * 8L
                + displayPixels * 4L
                + displayPixels * 4L;
        require(metallumHdr.manifest().resourceBytes(RendererGenerationManifest.Domain.HDR_ONLY)
                        == expectedMetallumHdrBytes,
                "Metallum + HDR resource estimate retained semantic/depth allocations");
        expectIllegalArgument(() -> RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.SDR,
                all,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA8
        ));

        MetalCapabilities noMaterialShaders = MetalCapabilities.productionMetal3(true);
        RendererGenerationPlanner.Plan shaderRoleFallback = RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                noMaterialShaders,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
        );
        require(shaderRoleFallback.resolution().rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.MATERIAL_CONTRACT_UNAVAILABLE))
                        && shaderRoleFallback.resolution().config().renderContractMode()
                        == RenderContractMode.LEGACY
                        && shaderRoleFallback.resolution().config().outputMode()
                        == DisplayOutputMode.HDR
                        && shaderRoleFallback.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .LEGACY_HDR_SEMANTIC_RGBA16F,
                "material shader-role fallback changed the independently safe HDR output axis");

        RendererGenerationPlanner.Plan shaderRoleFallbackSdr = RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.SDR,
                noMaterialShaders,
                render,
                display,
                false,
                RendererGenerationPlanner.MaterialSceneStorage.FIXED_LINEAR_RGBA16F
        );
        require(shaderRoleFallbackSdr.resolution().rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.MATERIAL_CONTRACT_UNAVAILABLE))
                        && shaderRoleFallbackSdr.resolution().config().renderContractMode()
                        == RenderContractMode.LEGACY
                        && shaderRoleFallbackSdr.resolution().config().outputMode()
                        == DisplayOutputMode.SDR
                        && shaderRoleFallbackSdr.manifest().sceneStorageContract()
                        == RendererGenerationManifest.SceneStorageContract
                        .LEGACY_SDR_SRGB_RGBA16F_COMPAT
                        && shaderRoleFallbackSdr.manifest().sceneStorageContract().bytesPerPixel()
                        == 8
                        && !shaderRoleFallbackSdr.manifest().sceneStorageContract().sceneLinear()
                        && !shaderRoleFallbackSdr.manifest().sceneStorageContract()
                        .actualHdrRadiance()
                        && shaderRoleFallbackSdr.manifest().hdrPipelineContract()
                        == RendererGenerationManifest.HdrPipelineContract.NONE
                        && shaderRoleFallbackSdr.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.HDR_ONLY) == 0L
                        && shaderRoleFallbackSdr.manifest().passCount(
                        RendererGenerationManifest.Domain.HDR_ONLY) == 0L,
                "material shader-role fallback mismatched the safe SDR FP16 backing contract");

        RendererGenerationPlanner.Plan spatial = RendererGenerationPlanner.plan(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
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
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
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
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
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

    private static void testAdvancedLightingManifest() {
        MetalCapabilities all = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT,
                MetalCapabilities.Feature.ADVANCED_LIGHTING
        );
        RendererGenerationPlanner.Extent extent = new RendererGenerationPlanner.Extent(3024, 1964);
        RendererGenerationPlanner.Plan sdr = RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.SDR,
                all,
                extent,
                extent
        );
        RendererGenerationPlanner.Plan hdr = RendererGenerationPlanner.plan(
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                LightingPreset.BALANCED,
                RendererFeatureMask.NONE,
                DisplayOutputMode.HDR,
                all,
                extent,
                extent
        );
        AdvancedLightingLayout.Budget budget = AdvancedLightingLayout.forGeneration(
                LightingPreset.BALANCED,
                extent.width(),
                extent.height()
        );
        SunShadowLayout.Budget shadowBudget = SunShadowLayout.forPreset(
                LightingPreset.BALANCED
        );
        VoxelClipmapLayout.Budget voxelBudget = VoxelClipmapLayout.forPreset(
                VoxelClipmapLayout.Preset.BALANCED
        );
        Set<String> expectedResources = Set.of(
                "lighting_upload_ring",
                "gpu_lights",
                "cluster_compact_headers",
                "cluster_membership_scratch",
                "cluster_compact_indices",
                "lighting_params",
                "cluster_statistics",
                "environment_shadow_params_ring",
                "sun_shadow_cascades",
                "voxel_upload_ring",
                "voxel_private_patch_ring",
                "voxel_indirect_args",
                "voxel_occupancy",
                "voxel_transmittance_material",
                "voxel_brick_tags"
        );
        Set<String> expectedPasses = Set.of(
                "light_upload", "cluster_prepare", "cluster_build", "voxel_upload",
                "voxel_update", "sun_shadow", "direct_lighting"
        );
        Set<String> expectedPipelines = Set.of(
                "cluster_prepare_pso", "cluster_masks_pso", "cluster_count_pso",
                "cluster_prefix_blocks_pso", "cluster_prefix_groups_pso",
                "cluster_prefix_add_pso", "cluster_fill_pso",
                "terrain_direct_lighting_pso", "entity_direct_lighting_pso",
                "terrain_sun_shadow_pso", "entity_sun_shadow_pso",
                "voxel_apply_pso", "voxel_debug_checksum_pso"
        );
        for (RendererGenerationManifest manifest : java.util.List.of(
                sdr.manifest(), hdr.manifest())) {
            require(manifest.config().lightingModel() == LightingModel.ADVANCED,
                    "Advanced manifest fell back to Vanilla");
            require(manifest.resourceBytes(
                            RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY)
                            == budget.totalBytes() + shadowBudget.totalBytes()
                            + voxelManifestBytes(voxelBudget),
                    "Advanced manifest byte count diverged from its layout budget");
            require(manifest.resources().stream()
                            .filter(resource -> resource.domain()
                                    == RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY)
                            .map(RendererGenerationManifest.Resource::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())
                            .equals(expectedResources),
                    "Advanced manifest resource names diverged from the binding ABI");
            require(manifest.passes().stream()
                            .filter(pass -> pass.domain()
                                    == RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY)
                            .map(RendererGenerationManifest.Pass::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())
                            .equals(expectedPasses)
                            && manifest.encoderCount(
                            RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY) == 5L
                            && manifest.pipelines().stream()
                            .filter(pipeline -> pipeline.domain()
                                    == RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY)
                            .map(RendererGenerationManifest.Pipeline::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet())
                            .equals(expectedPipelines)
                            && manifest.workQueueCount(
                            RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY) == 4L,
                    "Advanced manifest work declarations are incomplete");
        }
        require(sdr.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY)
                        == hdr.manifest().resourceBytes(
                        RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY),
                "SDR/HDR changed the clustered-lighting resource layout");

        long[] memoryCaps = {192L << 20, 256L << 20, 384L << 20};
        LightingPreset[] presets = LightingPreset.values();
        for (int index = 0; index < presets.length; index++) {
            AdvancedLightingLayout.Budget candidate = AdvancedLightingLayout.forGeneration(
                    presets[index], extent.width(), extent.height());
            SunShadowLayout.Budget shadowCandidate = SunShadowLayout.forPreset(presets[index]);
            VoxelClipmapLayout.Budget voxelCandidate = VoxelClipmapLayout.forPreset(
                    VoxelClipmapLayout.Preset.values()[index]);
            require(candidate.totalBytes() + shadowCandidate.totalBytes()
                            + voxelManifestBytes(voxelCandidate) <= memoryCaps[index],
                    "L3/L4/L5 layout exceeded the preset persistent-memory budget");
            require(candidate.indexCapacity() <= Math.multiplyExact(
                            candidate.clusterCount(),
                            AdvancedLightingLayout.MAX_LIGHTS_PER_CLUSTER),
                    "L3 index capacity exceeds the bounded per-cluster contract");
        }
    }

    private static long voxelManifestBytes(final VoxelClipmapLayout.Budget budget) {
        return budget.totalDedicatedBytes();
    }

    private static void testAdvancedLightingRuntimeAdmission() {
        AdvancedLightingRuntime.reset();
        long initialEpoch = AdvancedLightingRuntime.admission().epoch();
        require(!AdvancedLightingRuntime.shouldCollect()
                        && !AdvancedLightingRuntime.admission().ready()
                        && !AdvancedLightingRuntime.isActive(),
                "Advanced runtime did not reset fail-closed");
        AdvancedLightingRuntime.configureRequested(true);
        require(AdvancedLightingRuntime.shouldCollect()
                        && !AdvancedLightingRuntime.admission().ready(),
                "Advanced collection did not follow the request before admission");
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");
        require(AdvancedLightingRuntime.admission().ready()
                        && AdvancedLightingRuntime.admission().epoch() > initialEpoch,
                "Advanced runtime rejected complete native/shader admission");
        AdvancedLightingRuntime.admitGeneration(true);
        require(AdvancedLightingRuntime.isActive(),
                "Advanced runtime did not publish the admitted generation");
        AdvancedLightingRuntime.reportRegistryAdmission(false, "synthetic registry failure");
        require(!AdvancedLightingRuntime.isActive()
                        && !AdvancedLightingRuntime.admission().ready()
                        && !AdvancedLightingRuntime.shouldCollect(),
                "Advanced runtime remained active after registry admission failure");
        AdvancedLightingRuntime.reset();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");
        AdvancedLightingRuntime.admitGeneration(true);
        AdvancedLightingRuntime.reportShaderAdmission(false, "synthetic shader failure");
        require(!AdvancedLightingRuntime.isActive()
                        && !AdvancedLightingRuntime.admission().ready(),
                "Advanced runtime remained active after shader admission failure");
        AdvancedLightingRuntime.configureRequested(false);
        require(!AdvancedLightingRuntime.shouldCollect(),
                "Advanced runtime kept collecting after the request was disabled");
        AdvancedLightingRuntime.reset();
    }

    private static void testAdvancedLightingAdmissionRace() {
        AdvancedLightingRuntime.reset();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");

        CountDownLatch snapshotCaptured = new CountDownLatch(1);
        CountDownLatch failurePublished = new CountDownLatch(1);
        AtomicBoolean committed = new AtomicBoolean(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread generationThread = new Thread(() -> {
            try {
                AdvancedLightingRuntime.Admission snapshot = AdvancedLightingRuntime.admission();
                require(snapshot.ready(), "race test could not capture a ready admission");
                snapshotCaptured.countDown();
                require(failurePublished.await(5L, TimeUnit.SECONDS),
                        "race test did not publish the registry failure");
                committed.set(AdvancedLightingRuntime.tryAdmitGeneration(snapshot.epoch(), true));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "metallum-l3-admission-race");
        generationThread.start();
        await(snapshotCaptured, "race test did not capture its admission snapshot");
        AdvancedLightRegistry.global().failClosed("synthetic concurrent registry failure", null);
        failurePublished.countDown();
        join(generationThread, "race test generation thread did not finish");

        require(failure.get() == null, "atomic admission race threw: " + failure.get());
        require(!committed.get()
                        && !AdvancedLightingRuntime.isActive()
                        && !AdvancedLightingRuntime.admission().ready(),
                "stale admission committed after failClosed");
        AdvancedLightingRuntime.reset();
    }

    private static void testAdvancedLightingRegistryHandoffRace() {
        AdvancedLightingRuntime.reset();
        AdvancedLightingRuntime.configureRequested(true);
        AdvancedLightingRuntime.reportNativeAdmission(true, "");
        AdvancedLightingRuntime.reportShaderAdmission(true, "");
        AdvancedLightingRuntime.Admission snapshot = AdvancedLightingRuntime.admission();
        require(snapshot.ready(), "handoff race could not capture a ready admission");

        AdvancedLightRegistry registry = AdvancedLightRegistry.global();
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread failureThread = new Thread(() -> {
            try {
                registry.failClosed("synthetic registry handoff failure", null);
            } catch (Throwable throwable) {
                workerFailure.set(throwable);
            }
        }, "metallum-l3-registry-handoff-race");

        synchronized (AdvancedLightingRuntime.class) {
            failureThread.start();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (registry.readiness().healthy()
                    && failureThread.isAlive()
                    && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            require(!registry.readiness().healthy() && failureThread.isAlive(),
                    "registry failure did not reach the Registry-to-Runtime handoff");
            require(registry.snapshotForFrameIfHealthy(0.0, 0.0, 0.0, 8) == null,
                    "unhealthy registry published an empty Advanced frame snapshot");
            require(!AdvancedLightingRuntime.tryAdmitGeneration(snapshot.epoch(), true)
                            && !AdvancedLightingRuntime.isActive(),
                    "registry failure committed inside the Runtime handoff window");
        }
        join(failureThread, "registry handoff failure worker did not finish");
        require(workerFailure.get() == null
                        && !AdvancedLightingRuntime.admission().ready()
                        && !AdvancedLightingRuntime.isActive(),
                "registry handoff failure did not finish fail-closed: " + workerFailure.get());
        AdvancedLightingRuntime.reset();
    }

    private static void await(final CountDownLatch latch, final String failure) {
        try {
            require(latch.await(5L, TimeUnit.SECONDS), failure);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, exception);
        }
    }

    private static void join(final Thread thread, final String failure) {
        try {
            thread.join(5_000L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure, exception);
        }
        require(!thread.isAlive(), failure);
    }

    private static Set<String> resourceNames(final RendererGenerationManifest manifest) {
        return Set.copyOf(manifest.resources().stream()
                .map(RendererGenerationManifest.Resource::name)
                .toList());
    }

    private static Set<String> passNames(final RendererGenerationManifest manifest) {
        return Set.copyOf(manifest.passes().stream()
                .map(RendererGenerationManifest.Pass::name)
                .toList());
    }

    private static void requireNoL3Work(final RendererGenerationManifest manifest) {
        Set<String> allowedMaterialResources = Set.of("sdr_ui_color", "sdr_ui_depth");
        Set<String> allowedMaterialPasses = Set.of("scene_linear_ui_seed");
        require(manifest.resources().stream()
                        .filter(resource -> resource.domain()
                                == RendererGenerationManifest.Domain.MATERIAL_ONLY)
                        .allMatch(resource -> allowedMaterialResources.contains(resource.name())),
                "L2 manifest declared an unexpected material resource");
        require(manifest.passes().stream()
                        .filter(pass -> pass.domain()
                                == RendererGenerationManifest.Domain.MATERIAL_ONLY)
                        .allMatch(pass -> allowedMaterialPasses.contains(pass.name())),
                "L2 manifest scheduled an unexpected material pass");
        require(manifest.resourceBytes(
                        RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY) == 0L
                        && manifest.passCount(
                        RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY) == 0L
                        && manifest.encoderCount(
                        RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY) == 0L
                        && manifest.pipelineCount(
                        RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY) == 0L
                        && manifest.workQueueCount(
                        RendererGenerationManifest.Domain.ADVANCED_LIGHTING_ONLY) == 0L,
                "L2.5 manifest declared Advanced resources or work");
        require(manifest.encoders().size() == manifest.passes().size()
                        && manifest.pipelines().size() == manifest.passes().size()
                        && manifest.workQueues().size() == manifest.passes().size(),
                "manifest did not explicitly declare encoder/PSO/queue work");
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
        require(!capabilities.supports(MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT),
                "native discovery accidentally enabled unimplemented lighting");
        expectUnsupported(() -> capabilities.evidence().put(
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT,
                MetalCapabilities.Evidence.DECLARED
        ));

        RendererGenerationConfig metal3WithTemporalAvailable = new RendererGenerationConfig(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
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
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                1
        ));
        expectIllegalArgument(() -> new RendererGenerationConfig(
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                0
        ));
        expectIllegalState(() -> RendererGenerationConfig.resolve(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
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
                RenderContractMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1280, 720), new FrameState.Extent(2560, 1440), Set.of()
        );
        FrameState changed = frame(
                2L, 3L, 4L, 5L,
                RenderContractMode.METALLUM, DisplayOutputMode.HDR,
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
                        FrameState.HistoryResetReason.RENDER_CONTRACT_CHANGE,
                        FrameState.HistoryResetReason.OUTPUT_MODE_CHANGE)),
                "generation transition reset policy mismatch: " + resets);
        require(FrameState.transitionResetReasons(null, base, Set.of()).equals(
                        EnumSet.of(FrameState.HistoryResetReason.FIRST_FRAME)),
                "first frame did not invalidate history");

        FrameState.Transforms transforms = FrameState.Transforms.identity();
        FrameState advanced = new FrameState(
                changed.contract(), changed.frameId(), changed.rendererGenerationId(),
                changed.historyGeneration(), changed.renderContractGenerationId(),
                changed.lightingGenerationId() + 1L, changed.outputGenerationId(),
                RenderContractMode.METALLUM, LightingModel.ADVANCED, changed.outputMode(),
                transforms, transforms, changed.renderExtent(), changed.displayExtent(),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of()
        );
        require(FrameState.transitionResetReasons(changed, advanced, Set.of()).equals(
                        EnumSet.of(FrameState.HistoryResetReason.LIGHTING_MODEL_CHANGE)),
                "lighting-model transition did not independently reset history");

        FrameState resized = frame(
                1L, 2L, 3L, 4L,
                RenderContractMode.LEGACY, DisplayOutputMode.SDR,
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
                RenderContractMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(640, 360), new FrameState.Extent(1280, 720), everyReason
        );
        require(state.historyResetReasons().equals(everyReason),
                "one or more required history reset reasons cannot be represented");
    }

    private static void testFrameStateNumericContracts() {
        FrameState sdr = frame(
                7L, 11L, 13L, 17L,
                RenderContractMode.LEGACY, DisplayOutputMode.SDR,
                new FrameState.Extent(1280, 720), new FrameState.Extent(2560, 1440), Set.of()
        );
        FrameState hdr = frame(
                7L, 11L, 13L, 18L,
                RenderContractMode.LEGACY, DisplayOutputMode.HDR,
                sdr.renderExtent(), sdr.displayExtent(), Set.of()
        );
        require(sdr.renderExtent().width() == 1280 && sdr.renderExtent().height() == 720
                        && sdr.displayExtent().width() == 2560 && sdr.displayExtent().height() == 1440,
                "render/display extents were coupled or reordered");
        require(sdr.lightingGenerationId() == hdr.lightingGenerationId()
                        && sdr.renderContractMode() == hdr.renderContractMode()
                        && hdr.outputMode() == DisplayOutputMode.HDR,
                "changing SDR/HDR output changed the render or lighting axes");
        require(Double.doubleToLongBits(sdr.jitterOffset().x()) == Double.doubleToLongBits(0.0)
                        && Double.doubleToLongBits(sdr.jitterOffset().y())
                        == Double.doubleToLongBits(0.0),
                "P2 zero jitter is not deterministic");
        require(new FrameState.JitterOffset(0.25, 0.0).x() == 0.25,
                "Temporal-ready jitter offsets should admit sub-pixel samples");
        expectIllegalArgument(() -> new FrameState.JitterOffset(0.5001, 0.0));
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
                RenderContractMode.LEGACY, DisplayOutputMode.SDR,
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
                11L,
                RenderContractMode.LEGACY,
                LightingModel.VANILLA,
                DisplayOutputMode.HDR,
                LightingPreset.PERFORMANCE,
                RendererFeatureMask.of(RendererFeatureMask.SPATIAL_UPSCALING),
                MetalExecutorKind.METAL3,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION,
                new FrameState.ResourceBytes(0L, 0L, 32L, 0L, 64L, 0L),
                FrameState.AdvancedLightingWork.NONE,
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
            MemorySegment invalidJitter = FrameStateAbi.encode(spatialHdr, arena);
            invalidJitter.set(ValueLayout.JAVA_FLOAT, 160L, 0.75f);
            expectIllegalArgument(() -> FrameStateAbi.validatePacket(invalidJitter));
            MemorySegment negativeResourceBytes = FrameStateAbi.encode(spatialHdr, arena);
            negativeResourceBytes.set(ValueLayout.JAVA_LONG, 192L, -1L);
            expectIllegalArgument(() -> FrameStateAbi.validatePacket(negativeResourceBytes));
        }

        expectIllegalArgument(() -> new FrameState.ResourceBytes(-1L, 0L, 0L, 0L, 0L, 0L));
        expectIllegalArgument(() -> new FrameState(
                FrameContract.temporalPreparationV1(), 1L, 1L, 1L, 1L, 1L, 1L,
                RenderContractMode.LEGACY, LightingModel.VANILLA, DisplayOutputMode.SDR,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3, 2,
                new FrameState.ResourceBytes(0L, 0L, 1L, 0L, 0L, 0L),
                FrameState.AdvancedLightingWork.NONE,
                transforms, transforms,
                new FrameState.Extent(1, 1), new FrameState.Extent(1, 1),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of()
        ));
        expectIllegalArgument(() -> new FrameState(
                FrameContract.temporalPreparationV1(), 1L, 1L, 1L, 1L, 1L, 1L,
                RenderContractMode.LEGACY, LightingModel.ADVANCED, DisplayOutputMode.SDR,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3, 3,
                FrameState.ResourceBytes.NONE, FrameState.AdvancedLightingWork.NONE,
                transforms, transforms,
                new FrameState.Extent(1, 1), new FrameState.Extent(1, 1),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of()
        ));
        expectIllegalArgument(() -> new FrameState(
                FrameContract.temporalPreparationV1(), 1L, 1L, 1L, 1L, 1L, 1L,
                RenderContractMode.METALLUM, LightingModel.VANILLA, DisplayOutputMode.SDR,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3, 3,
                new FrameState.ResourceBytes(0L, 1L, 0L, 1L, 0L, 0L),
                new FrameState.AdvancedLightingWork(1, 1, 1, 1, 1, 1, 16L),
                transforms, transforms,
                new FrameState.Extent(1, 1), new FrameState.Extent(1, 1),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of()
        ));
        expectIllegalArgument(() -> new FrameState(
                FrameContract.temporalPreparationV1(), 1L, 1L, 1L, 1L, 1L, 1L,
                RenderContractMode.LEGACY, LightingModel.VANILLA, DisplayOutputMode.SDR,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3, 2,
                new FrameState.ResourceBytes(0L, 1L, 0L, 0L, 0L, 0L),
                FrameState.AdvancedLightingWork.NONE,
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
            final RenderContractMode lighting,
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
                1L,
                outputGeneration,
                lighting,
                LightingModel.VANILLA,
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
