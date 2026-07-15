package com.metallum.client.renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Pure L0 admission and manifest planner; it allocates no GPU resources. */
public final class RendererGenerationPlanner {
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
            LightingMode requestedLighting,
            DisplayOutputMode requestedOutput,
            RendererFeatureMask requestedFeatures,
            RendererGenerationConfig.Resolution resolution,
            RendererGenerationManifest manifest
    ) {
        public Plan {
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
            final LightingMode requestedLighting,
            final DisplayOutputMode requestedOutput,
            final MetalExecutorKind requestedExecutor,
            final LightingPreset requestedPreset,
            final RendererFeatureMask requestedFeatures,
            final DisplayOutputMode currentSafeOutput,
            final MetalCapabilities capabilities,
            final Extent renderExtent,
            final Extent displayExtent
    ) {
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
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
                requestedLighting,
                requestedOutput,
                requestedFeatures,
                resolution,
                manifest(resolution.config(), renderExtent, displayExtent)
        );
    }

    public static RendererGenerationManifest manifest(
            final RendererGenerationConfig config,
            final Extent renderExtent,
            final Extent displayExtent
    ) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(renderExtent, "renderExtent");
        Objects.requireNonNull(displayExtent, "displayExtent");

        if (config.lightingMode() == LightingMode.METALLUM) {
            return new RendererGenerationManifest(
                    RendererGenerationManifest.CURRENT_VERSION,
                    config,
                    false,
                    List.of(),
                    List.of(),
                    "lighting shader-role coverage is incomplete"
            );
        }

        List<RendererGenerationManifest.Resource> resources = new ArrayList<>();
        List<RendererGenerationManifest.Pass> passes = new ArrayList<>();
        boolean spatial = config.featureMask().contains(RendererFeatureMask.SPATIAL_UPSCALING);
        resources.add(resource("main_color", RendererGenerationManifest.Domain.BASE, 0L, true));
        resources.add(resource("main_depth", RendererGenerationManifest.Domain.BASE, 0L, true));
        resources.add(resource("drawable", RendererGenerationManifest.Domain.BASE, 0L, true));
        passes.add(pass("world_render", RendererGenerationManifest.Domain.BASE));
        passes.add(pass(spatial ? "ui_render_with_seed" : "ui_render",
                RendererGenerationManifest.Domain.BASE));
        passes.add(pass("present", RendererGenerationManifest.Domain.BASE));

        if (config.outputMode() == DisplayOutputMode.HDR) {
            long renderPixels = renderExtent.pixels();
            long displayPixels = displayExtent.pixels();
            long quarterWidth = Math.max((renderExtent.width() + 3L) / 4L, 1L);
            long quarterHeight = Math.max((renderExtent.height() + 3L) / 4L, 1L);
            long quarterPixels = Math.multiplyExact(quarterWidth, quarterHeight);
            resources.add(resource("hdr_semantic", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(renderPixels, 4L), false));
            resources.add(resource("scene_depth_snapshot", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(renderPixels, 4L), false));
            resources.add(resource("hdr_emission", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(quarterPixels, 8L), false));
            resources.add(resource("hdr_bloom", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(quarterPixels, 8L), false));
            resources.add(resource("hdr_histogram", RendererGenerationManifest.Domain.HDR_ONLY,
                    64L * Integer.BYTES, false));
            resources.add(resource("hdr_adaptive_state", RendererGenerationManifest.Domain.HDR_ONLY,
                    32L, false));
            resources.add(resource("hdr_world_composite", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(spatial ? renderPixels : displayPixels, 8L), false));
            resources.add(resource("sdr_ui_color", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(displayPixels, 4L), false));
            resources.add(resource("sdr_ui_depth", RendererGenerationManifest.Domain.HDR_ONLY,
                    multiply(displayPixels, 4L), false));
            passes.add(pass("scene_depth_snapshot", RendererGenerationManifest.Domain.HDR_ONLY));
            passes.add(pass("hdr_extract", RendererGenerationManifest.Domain.HDR_ONLY));
            passes.add(pass("hdr_exposure_reduce", RendererGenerationManifest.Domain.HDR_ONLY));
            passes.add(pass("hdr_bloom_combined", RendererGenerationManifest.Domain.HDR_ONLY));
            passes.add(pass(spatial ? "hdr_world_reconstruction" : "hdr_world_ui_seed",
                    RendererGenerationManifest.Domain.HDR_ONLY));
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
        if (config.featureMask().contains(RendererFeatureMask.TEMPORAL_UPSCALING)) {
            throw new IllegalStateException("L0 must not create a Temporal manifest");
        }
        if (config.featureMask().contains(RendererFeatureMask.FRAME_INTERPOLATION)) {
            throw new IllegalStateException("L0 must not create a Frame Interpolation manifest");
        }
        return new RendererGenerationManifest(
                RendererGenerationManifest.CURRENT_VERSION,
                config,
                true,
                resources,
                passes,
                null
        );
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
