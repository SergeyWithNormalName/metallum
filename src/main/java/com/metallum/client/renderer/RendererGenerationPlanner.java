package com.metallum.client.renderer;

import com.metallum.client.voxel.VoxelClipmapLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure generation admission and manifest planner; it allocates no GPU resources. */
public final class RendererGenerationPlanner {
    /** Startup-fixed physical storage of Minecraft's MainTarget. */
    public enum MaterialSceneStorage {
        MODE_DEFAULT,
        FIXED_LINEAR_RGBA8,
        FIXED_LINEAR_RGBA16F
    }

    public record Extent(int width, int height) {
        public Extent {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Generation extents must be positive");
            }
        }

        long pixels() {
            return Math.multiplyExact((long) this.width, this.height);
        }
    }

    public record Plan(
            RenderContractMode requestedContract,
            LightingModel requestedLighting,
            DisplayOutputMode requestedOutput,
            RendererFeatureMask requestedFeatures,
            RendererGenerationConfig.Resolution resolution,
            RendererGenerationManifest manifest
    ) {
        public Plan {
            Objects.requireNonNull(requestedContract, "requestedContract");
            Objects.requireNonNull(requestedLighting, "requestedLighting");
            Objects.requireNonNull(requestedOutput, "requestedOutput");
            Objects.requireNonNull(requestedFeatures, "requestedFeatures");
            Objects.requireNonNull(resolution, "resolution");
            Objects.requireNonNull(manifest, "manifest");
            if (!resolution.config().equals(manifest.config())) {
                throw new IllegalArgumentException("Resolution and manifest generation differ");
            }
        }
    }

    private RendererGenerationPlanner() {
    }

    public static Plan plan(
            final RenderContractMode requestedContract,
            final LightingModel requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final LightingPreset requestedPreset,
            final RendererFeatureMask requestedFeatures,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final Extent renderExtent,
            final Extent displayExtent
    ) {
        return plan(
                requestedContract,
                requestedLighting,
                requestedOutput,
                requestedExecutor,
                requestedPreset,
                requestedFeatures,
                currentSafeOutput,
                capabilities,
                renderExtent,
                displayExtent,
                false
        );
    }

    public static Plan plan(
            final RenderContractMode requestedContract,
            final LightingModel requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final LightingPreset requestedPreset,
            final RendererFeatureMask requestedFeatures,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final Extent renderExtent,
            final Extent displayExtent,
            final boolean temporalDiagnostics
    ) {
        return plan(
                requestedContract,
                requestedLighting,
                requestedOutput,
                requestedExecutor,
                requestedPreset,
                requestedFeatures,
                currentSafeOutput,
                capabilities,
                renderExtent,
                displayExtent,
                temporalDiagnostics,
                MaterialSceneStorage.MODE_DEFAULT
        );
    }

    public static Plan plan(
            final RenderContractMode requestedContract,
            final LightingModel requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final LightingPreset requestedPreset,
            final RendererFeatureMask requestedFeatures,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final Extent renderExtent,
            final Extent displayExtent,
            final boolean temporalDiagnostics,
            final MaterialSceneStorage materialSceneStorage
    ) {
        return plan(
                requestedContract,
                requestedLighting,
                requestedOutput,
                requestedExecutor,
                requestedPreset,
                requestedFeatures,
                currentSafeOutput,
                capabilities,
                renderExtent,
                displayExtent,
                temporalDiagnostics,
                materialSceneStorage,
                true
        );
    }

    public static Plan plan(
            final RenderContractMode requestedContract,
            final LightingModel requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final LightingPreset requestedPreset,
            final RendererFeatureMask requestedFeatures,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final Extent renderExtent,
            final Extent displayExtent,
            final boolean temporalDiagnostics,
            final MaterialSceneStorage materialSceneStorage,
            final boolean legacySemanticAvailable
    ) {
        Objects.requireNonNull(materialSceneStorage, "materialSceneStorage");
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                requestedContract,
                requestedLighting,
                requestedOutput,
                requestedExecutor,
                requestedPreset,
                requestedFeatures,
                currentSafeOutput,
                capabilities,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
        );
        return new Plan(
                requestedContract,
                requestedLighting,
                requestedOutput,
                requestedFeatures,
                resolution,
                manifest(
                        resolution.config(),
                        renderExtent,
                        displayExtent,
                        temporalDiagnostics,
                        materialSceneStorage,
                        legacySemanticAvailable
                )
        );
    }

    public static RendererGenerationManifest manifest(
            final RendererGenerationConfig config,
            final Extent renderExtent,
            final Extent displayExtent
    ) {
        return manifest(config, renderExtent, displayExtent, false);
    }

    public static RendererGenerationManifest manifest(
            final RendererGenerationConfig config,
            final Extent renderExtent,
            final Extent displayExtent,
            final boolean temporalDiagnostics
    ) {
        return manifest(
                config,
                renderExtent,
                displayExtent,
                temporalDiagnostics,
                MaterialSceneStorage.MODE_DEFAULT
        );
    }

    public static RendererGenerationManifest manifest(
            final RendererGenerationConfig config,
            final Extent renderExtent,
            final Extent displayExtent,
            final boolean temporalDiagnostics,
            final MaterialSceneStorage materialSceneStorage
    ) {
        return manifest(
                config,
                renderExtent,
                displayExtent,
                temporalDiagnostics,
                materialSceneStorage,
                true
        );
    }

    public static RendererGenerationManifest manifest(
            final RendererGenerationConfig config,
            final Extent renderExtent,
            final Extent displayExtent,
            final boolean temporalDiagnostics,
            final MaterialSceneStorage materialSceneStorage,
            final boolean legacySemanticAvailable
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(renderExtent, "renderExtent");
        Objects.requireNonNull(displayExtent, "displayExtent");
        Objects.requireNonNull(materialSceneStorage, "materialSceneStorage");

        List<RendererGenerationManifest.Resource> resources = new ArrayList<>();
        List<RendererGenerationManifest.Pass> passes = new ArrayList<>();
        boolean metallum = config.renderContractMode() == RenderContractMode.METALLUM;
        boolean spatial = config.featureMask().contains(RendererFeatureMask.SPATIAL_UPSCALING);
        boolean temporal = config.featureMask().contains(RendererFeatureMask.TEMPORAL_UPSCALING);
        boolean upscaled = spatial || temporal;
        RendererGenerationManifest.SceneStorageContract sceneStorage = sceneStorage(
                config,
                materialSceneStorage
        );
        boolean legacyEncodedHdr = !metallum
                && config.outputMode() == DisplayOutputMode.HDR
                && sceneStorage == RendererGenerationManifest.SceneStorageContract
                .LEGACY_HDR_SEMANTIC_SRGB8;
        boolean legacyEncodedFallback = legacyEncodedHdr && !upscaled;
        RendererGenerationManifest.HdrPipelineContract hdrPipeline = hdrPipeline(config);
        resources.add(resource("main_color", RendererGenerationManifest.Domain.BASE, 0L, true));
        resources.add(resource("main_depth", RendererGenerationManifest.Domain.BASE, 0L, true));
        resources.add(resource("drawable", RendererGenerationManifest.Domain.BASE, 0L, true));
        passes.add(pass("world_render", RendererGenerationManifest.Domain.BASE));
        if (metallum && config.outputMode() == DisplayOutputMode.SDR) {
            passes.add(pass("scene_linear_ui_seed",
                    RendererGenerationManifest.Domain.MATERIAL_ONLY));
        }
        passes.add(pass(upscaled ? "ui_render_with_seed" : "ui_render",
                RendererGenerationManifest.Domain.BASE));
        passes.add(pass("present", RendererGenerationManifest.Domain.BASE));

        if (config.outputMode() == DisplayOutputMode.HDR) {
            long renderPixels = renderExtent.pixels();
            long displayPixels = displayExtent.pixels();
            long quarterWidth = Math.max((renderExtent.width() + 3L) / 4L, 1L);
            long quarterHeight = Math.max((renderExtent.height() + 3L) / 4L, 1L);
            long quarterPixels = Math.multiplyExact(quarterWidth, quarterHeight);
            if (!metallum) {
                if (legacySemanticAvailable) {
                    resources.add(resource(
                            "hdr_semantic",
                            RendererGenerationManifest.Domain.HDR_ONLY,
                            multiply(renderPixels, 4L),
                            false
                    ));
                }
                resources.add(resource("scene_depth_snapshot",
                        RendererGenerationManifest.Domain.HDR_ONLY,
                        multiply(renderPixels, 4L), false));
                if (legacyEncodedFallback) {
                    resources.add(resource("scene_color_snapshot",
                            RendererGenerationManifest.Domain.HDR_ONLY,
                            multiply(renderPixels, 4L), false));
                }
            }
            resources.add(resource("hdr_emission", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(quarterPixels, 8L), false));
            resources.add(resource("hdr_bloom", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(quarterPixels, 8L), false));
            resources.add(resource("hdr_histogram", RendererGenerationManifest.Domain.HDR_ONLY,
                    64L * Integer.BYTES, false));
            resources.add(resource("hdr_adaptive_state", RendererGenerationManifest.Domain.HDR_ONLY,
                    32L, false));
            if (legacyEncodedFallback) {
                long uiMaskWidth = Math.max((displayExtent.width() + 1L) / 2L, 1L);
                long uiMaskHeight = Math.max((displayExtent.height() + 1L) / 2L, 1L);
                long uiMaskPixels = Math.multiplyExact(uiMaskWidth, uiMaskHeight);
                resources.add(resource("hdr_ui_control_a",
                        RendererGenerationManifest.Domain.HDR_ONLY,
                        multiply(uiMaskPixels, 2L), false));
                resources.add(resource("hdr_ui_control_b",
                        RendererGenerationManifest.Domain.HDR_ONLY,
                        multiply(uiMaskPixels, 2L), false));
            } else {
                resources.add(resource("hdr_world_composite",
                        RendererGenerationManifest.Domain.HDR_ONLY,
                        multiply(upscaled ? renderPixels : displayPixels, 8L), false));
                resources.add(resource("sdr_ui_color",
                        RendererGenerationManifest.Domain.HDR_ONLY,
                        multiply(displayPixels, 4L), false));
                resources.add(resource("sdr_ui_depth",
                        RendererGenerationManifest.Domain.HDR_ONLY,
                        multiply(displayPixels, 4L), false));
            }
            if (!metallum) {
                if (legacyEncodedFallback) {
                    passes.add(pass("scene_color_snapshot",
                            RendererGenerationManifest.Domain.HDR_ONLY));
                }
                passes.add(pass("scene_depth_snapshot", RendererGenerationManifest.Domain.HDR_ONLY));
            }
            passes.add(pass("hdr_extract", RendererGenerationManifest.Domain.HDR_ONLY));
            passes.add(pass("hdr_exposure_reduce", RendererGenerationManifest.Domain.HDR_ONLY));
            passes.add(pass("hdr_bloom_combined", RendererGenerationManifest.Domain.HDR_ONLY));
            if (legacyEncodedFallback) {
                passes.add(pass("hdr_ui_compare", RendererGenerationManifest.Domain.HDR_ONLY));
                passes.add(pass("hdr_ui_dilate", RendererGenerationManifest.Domain.HDR_ONLY));
            } else {
                passes.add(pass(
                        metallum
                                ? "hdr_world_actual_radiance"
                                : upscaled ? "hdr_world_reconstruction" : "hdr_world_ui_seed",
                        RendererGenerationManifest.Domain.HDR_ONLY
                ));
            }
        } else if (metallum) {
            long displayPixels = displayExtent.pixels();
            resources.add(resource("sdr_ui_color", RendererGenerationManifest.Domain.MATERIAL_ONLY,
                    multiply(displayPixels, 4L), false));
            resources.add(resource("sdr_ui_depth", RendererGenerationManifest.Domain.MATERIAL_ONLY,
                    multiply(displayPixels, 4L), false));
        }

        if (spatial) {
            if (config.outputMode() == DisplayOutputMode.HDR) {
                resources.add(resource(
                        "metalfx_output",
                        RendererGenerationManifest.Domain.UPSCALE_ONLY,
                        multiply(displayExtent.pixels(), 8L),
                        false
                ));
            } else {
                resources.add(resource(
                        "metalfx_perceptual_input",
                        RendererGenerationManifest.Domain.UPSCALE_ONLY,
                        multiply(renderExtent.pixels(), 4L),
                        false
                ));
                resources.add(resource(
                        "metalfx_output",
                        RendererGenerationManifest.Domain.UPSCALE_ONLY,
                        0L,
                        true
                ));
                passes.add(pass("metalfx_perceptual_prepare",
                        RendererGenerationManifest.Domain.UPSCALE_ONLY));
            }
            passes.add(pass("metalfx_spatial", RendererGenerationManifest.Domain.UPSCALE_ONLY));
        }
        if (temporal) {
            long renderPixels = renderExtent.pixels();
            resources.add(resource(
                    "metalfx_temporal_motion_ring",
                    RendererGenerationManifest.Domain.UPSCALE_ONLY,
                    multiply(renderPixels, 4L * 3L),
                    false
            ));
            resources.add(resource(
                    "metalfx_temporal_reactive_ring",
                    RendererGenerationManifest.Domain.UPSCALE_ONLY,
                    multiply(renderPixels, 3L),
                    false
            ));
            resources.add(resource(
                    "metalfx_temporal_output",
                    RendererGenerationManifest.Domain.UPSCALE_ONLY,
                    multiply(displayExtent.pixels(), config.outputMode() == DisplayOutputMode.HDR ? 8L : 4L),
                    false
            ));
            passes.add(pass("temporal_motion_vectors", RendererGenerationManifest.Domain.UPSCALE_ONLY));
            passes.add(pass("metalfx_temporal", RendererGenerationManifest.Domain.UPSCALE_ONLY));
        }
        if (config.featureMask().contains(RendererFeatureMask.FRAME_INTERPOLATION)) {
            if (!temporal && !spatial) {
                throw new IllegalStateException(
                        "Frame Interpolation requires a fixed Temporal or Spatial upstream profile"
                );
            }
            long displayPixels = displayExtent.pixels();
            long renderPixels = renderExtent.pixels();
            RendererGenerationManifest.Domain domain = RendererGenerationManifest.Domain
                    .INTERPOLATION_ONLY;
            resources.add(resource("frame_interpolation_real_color_ring", domain,
                    multiply(displayPixels, 8L * 3L), false));
            resources.add(resource("frame_interpolation_generated_color_ring", domain,
                    multiply(displayPixels, 8L * 3L), false));
            resources.add(resource("frame_interpolation_depth_ring", domain,
                    multiply(renderPixels, 4L * 3L), false));
            resources.add(resource("frame_interpolation_motion_ring", domain,
                    multiply(renderPixels, 4L * 3L), false));
            resources.add(resource("frame_interpolation_ui_ring", domain,
                    multiply(displayPixels, 4L * 3L), false));
            resources.add(resource("frame_interpolation_composite_ring", domain,
                    multiply(displayPixels, 8L * 2L * 3L), false));
            passes.add(pass("frame_interpolation_prepare", domain));
            if (spatial) {
                // Spatial has no FrameInterpolatableScaler. Its depth/motion inputs
                // are built at render extent and the final world colors are already
                // display-sized after the ordinary spatial resolve.
                passes.add(pass("spatial_frame_interpolation_motion_vectors", domain));
            }
            passes.add(pass("frame_interpolation", domain));
            passes.add(pass("generated_present", domain));
            passes.add(pass("real_present", domain));
        }
        if (temporalDiagnostics && !temporal) {
            long renderPixels = renderExtent.pixels();
            long diagnosticBytes = multiply(renderPixels, 5L * 3L);
            resources.add(resource(
                    "temporal_motion_ring",
                    RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY,
                    multiply(renderPixels, 4L * 3L),
                    false
            ));
            resources.add(resource(
                    "temporal_reactive_ring",
                    RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY,
                    multiply(renderPixels, 3L),
                    false
            ));
            if (diagnosticBytes <= 0L) {
                throw new IllegalStateException("Temporal diagnostic byte count overflowed");
            }
            passes.add(pass("temporal_camera_motion_diagnostic",
                    RendererGenerationManifest.Domain.DIAGNOSTIC_ONLY));
        }
        List<RendererGenerationManifest.Encoder> encoders = passes.stream()
                .map(pass -> new RendererGenerationManifest.Encoder(
                        pass.name() + "_encoder", pass.domain()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<RendererGenerationManifest.Pipeline> pipelines = passes.stream()
                .map(pass -> new RendererGenerationManifest.Pipeline(
                        pass.name() + "_pso", pass.domain()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        List<RendererGenerationManifest.WorkQueue> workQueues = passes.stream()
                .map(pass -> new RendererGenerationManifest.WorkQueue(
                        pass.name() + "_queue", pass.domain()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        if (config.lightingModel() == LightingModel.ADVANCED) {
            AdvancedLightingLayout.Budget budget = AdvancedLightingLayout.forGeneration(
                    config.lightingPreset(),
                    renderExtent.width(),
                    renderExtent.height()
            );
            RendererGenerationManifest.Domain domain = RendererGenerationManifest.Domain
                    .ADVANCED_LIGHTING_ONLY;
            resources.add(resource("lighting_upload_ring", domain, budget.uploadRingBytes(), false));
            resources.add(resource("gpu_lights", domain,
                    AdvancedLightingLayout.nativeAllocationBytes(budget.gpuLightBytes()), false));
            resources.add(resource("cluster_compact_headers", domain,
                    AdvancedLightingLayout.nativeAllocationBytes(budget.clusterHeaderBytes()), false));
            resources.add(resource("cluster_membership_scratch", domain,
                    AdvancedLightingLayout.nativeAllocationBytes(budget.clusterScratchBytes()), false));
            resources.add(resource("cluster_compact_indices", domain,
                    AdvancedLightingLayout.nativeAllocationBytes(budget.clusterIndexBytes()), false));
            resources.add(resource("lighting_params", domain,
                    AdvancedLightingLayout.nativeAllocationBytes(
                            AdvancedLightingLayout.LIGHTING_PARAMS_BYTES), false));
            resources.add(resource("cluster_statistics", domain,
                    AdvancedLightingLayout.nativeAllocationBytes(
                            AdvancedLightingLayout.STATISTICS_BYTES), false));
            SunShadowLayout.Budget shadowBudget = SunShadowLayout.forPreset(
                    config.lightingPreset()
            );
            resources.add(resource(
                    "environment_shadow_params_ring",
                    domain,
                    shadowBudget.paramsRingBytes(),
                    false
            ));
            resources.add(resource(
                    "sun_shadow_static_cascades",
                    domain,
                    shadowBudget.staticTextureBytes(),
                    false
            ));
            resources.add(resource(
                    "sun_shadow_working_cascades",
                    domain,
                    shadowBudget.workingTextureBytes(),
                    false
            ));
            VoxelClipmapLayout.Budget voxelBudget = VoxelClipmapLayout.forPreset(
                    switch (config.lightingPreset()) {
                        case PERFORMANCE -> VoxelClipmapLayout.Preset.PERFORMANCE;
                        case BALANCED -> VoxelClipmapLayout.Preset.BALANCED;
                        case ULTRA -> VoxelClipmapLayout.Preset.ULTRA;
                    }
            );
            resources.add(resource(
                    "voxel_upload_ring", domain,
                    voxelBudget.sharedUploadRingBytes(), false));
            resources.add(resource(
                    "voxel_private_patch_ring", domain,
                    voxelBudget.privatePatchRingBytes(), false));
            resources.add(resource(
                    "voxel_indirect_args", domain,
                    voxelBudget.indirectParamsDebugOverheadBytes(), false));
            resources.add(resource(
                    "voxel_occupancy", domain, voxelBudget.occupancyBytes(), false));
            resources.add(resource(
                    "voxel_transmittance_material", domain,
                    voxelBudget.opticalBytes(), false));
            resources.add(resource(
                    "voxel_chromatic_filter", domain,
                    voxelBudget.chromaticBytes(), false));
            resources.add(resource(
                    "voxel_brick_tags", domain, voxelBudget.metadataBytes(), false));
            LocalVoxelShadowLayout.Budget localShadowBudget =
                    LocalVoxelShadowLayout.forPreset(config.lightingPreset());
            resources.add(resource(
                    "local_shadow_params_ring", domain,
                    localShadowBudget.paramsRingBytes(), false));
            resources.add(resource(
                    "entity_shadow_proxies_ring", domain,
                    localShadowBudget.proxyRingBytes(), false));
            resources.add(resource(
                    "local_shadow_reference_ring", domain,
                    localShadowBudget.shadowReferenceRingBytes(), false));
            resources.add(resource(
                    "local_shadow_visibility_atlas", domain,
                    localShadowBudget.totalVisibilityAtlasBytes(), false));

            passes.add(pass("light_upload", domain));
            passes.add(pass("cluster_prepare", domain));
            passes.add(pass("cluster_build", domain));
            passes.add(pass("voxel_upload", domain));
            passes.add(pass("voxel_update", domain));
            passes.add(pass("sun_shadow_static_refresh", domain));
            passes.add(pass("sun_shadow_static_copy", domain));
            passes.add(pass("sun_shadow_dynamic", domain));
            passes.add(pass("dynamic_local_shadow_compute", domain));
            passes.add(pass("local_shadow_atlas_upload", domain));
            passes.add(pass("direct_lighting", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "light_upload_blit_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "cluster_build_compute_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "voxel_upload_blit_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "voxel_update_compute_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "sun_shadow_static_render_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "sun_shadow_copy_blit_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "sun_shadow_dynamic_render_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "dynamic_local_shadow_compute_encoder", domain));
            encoders.add(new RendererGenerationManifest.Encoder(
                    "local_shadow_atlas_upload_blit_encoder", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("cluster_prepare_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("cluster_masks_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("cluster_count_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("cluster_prefix_blocks_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("cluster_prefix_groups_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("cluster_prefix_add_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("cluster_fill_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline(
                    "terrain_direct_lighting_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline(
                    "entity_direct_lighting_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline(
                    "terrain_sun_shadow_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline(
                    "entity_sun_shadow_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline("voxel_apply_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline(
                    "voxel_debug_checksum_pso", domain));
            pipelines.add(new RendererGenerationManifest.Pipeline(
                    "dynamic_local_shadow_pso", domain));
            workQueues.add(new RendererGenerationManifest.WorkQueue(
                    "static_light_registry", domain));
            workQueues.add(new RendererGenerationManifest.WorkQueue(
                    "dynamic_light_snapshot", domain));
            workQueues.add(new RendererGenerationManifest.WorkQueue(
                    "voxel_mutation_queue", domain));
            workQueues.add(new RendererGenerationManifest.WorkQueue(
                    "voxel_upload_queue", domain));
            workQueues.add(new RendererGenerationManifest.WorkQueue(
                    "entity_shadow_proxy_snapshot", domain));
        }
        return new RendererGenerationManifest(
                RendererGenerationManifest.CURRENT_VERSION,
                config,
                sceneStorage,
                hdrPipeline,
                true,
                resources,
                passes,
                encoders,
                pipelines,
                workQueues,
                null
        );
    }

    private static RendererGenerationManifest.SceneStorageContract sceneStorage(
            final RendererGenerationConfig config,
            final MaterialSceneStorage materialSceneStorage
    ) {
        if (config.renderContractMode() == RenderContractMode.METALLUM) {
            if (config.outputMode() == DisplayOutputMode.HDR) {
                if (materialSceneStorage == MaterialSceneStorage.FIXED_LINEAR_RGBA8) {
                    throw new IllegalArgumentException(
                            "Actual-radiance HDR cannot use the startup-fixed RGBA8 scene target"
                    );
                }
                return RendererGenerationManifest.SceneStorageContract
                        .METALLUM_HDR_ACTUAL_RADIANCE_RGBA16F;
            }
            return materialSceneStorage == MaterialSceneStorage.FIXED_LINEAR_RGBA16F
                    ? RendererGenerationManifest.SceneStorageContract
                    .METALLUM_SDR_LINEAR_RGBA16F_COMPAT
                    : RendererGenerationManifest.SceneStorageContract.METALLUM_SDR_LINEAR_RGBA8;
        }
        if (config.outputMode() == DisplayOutputMode.HDR) {
            return materialSceneStorage == MaterialSceneStorage.FIXED_LINEAR_RGBA8
                    ? RendererGenerationManifest.SceneStorageContract.LEGACY_HDR_SEMANTIC_SRGB8
                    : RendererGenerationManifest.SceneStorageContract
                    .LEGACY_HDR_SEMANTIC_RGBA16F;
        }
        return materialSceneStorage == MaterialSceneStorage.FIXED_LINEAR_RGBA16F
                ? RendererGenerationManifest.SceneStorageContract
                .LEGACY_SDR_SRGB_RGBA16F_COMPAT
                : RendererGenerationManifest.SceneStorageContract.LEGACY_SDR_SRGB8;
    }

    private static RendererGenerationManifest.HdrPipelineContract hdrPipeline(
            final RendererGenerationConfig config
    ) {
        if (config.outputMode() == DisplayOutputMode.SDR) {
            return RendererGenerationManifest.HdrPipelineContract.NONE;
        }
        return config.renderContractMode() == RenderContractMode.METALLUM
                ? RendererGenerationManifest.HdrPipelineContract
                .ACTUAL_RADIANCE_EXPOSURE_BLOOM
                : RendererGenerationManifest.HdrPipelineContract.LEGACY_SEMANTIC_RECONSTRUCTION;
    }

    private static long multiply(final long left, final long right) {
        return Math.multiplyExact(left, right);
    }

    private static RendererGenerationManifest.Resource resource(
            final String name,
            final RendererGenerationManifest.Domain domain,
            final long bytes,
            final boolean external
    ) {
        return new RendererGenerationManifest.Resource(name, domain, bytes, external);
    }

    private static RendererGenerationManifest.Pass pass(
            final String name,
            final RendererGenerationManifest.Domain domain
    ) {
        return new RendererGenerationManifest.Pass(name, domain);
    }
}
