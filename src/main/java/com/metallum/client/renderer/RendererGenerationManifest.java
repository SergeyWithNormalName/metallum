package com.metallum.client.renderer;

import java.util.List;
import java.util.Objects;

/** Exact resource/work and scene-storage declaration for one resolved generation. */
public record RendererGenerationManifest(
        int version,
        RendererGenerationConfig config,
        SceneStorageContract sceneStorageContract,
        HdrPipelineContract hdrPipelineContract,
        boolean executable,
        List<Resource> resources,
        List<Pass> passes,
        List<Encoder> encoders,
        List<Pipeline> pipelines,
        List<WorkQueue> workQueues,
        String admissionBlocker
) {
    public static final int CURRENT_VERSION = 6;

    /** Storage and transfer contract of the external main-color target. */
    public enum SceneStorageContract {
        LEGACY_SDR_SRGB8(4, false, false),
        LEGACY_SDR_SRGB_RGBA16F_COMPAT(8, false, false),
        LEGACY_HDR_SEMANTIC_SRGB8(4, false, false),
        LEGACY_HDR_SEMANTIC_RGBA16F(8, false, false),
        METALLUM_SDR_LINEAR_RGBA8(4, true, false),
        METALLUM_SDR_LINEAR_RGBA16F_COMPAT(8, true, true),
        METALLUM_HDR_ACTUAL_RADIANCE_RGBA16F(8, true, true);

        private final int bytesPerPixel;
        private final boolean sceneLinear;
        private final boolean actualHdrRadiance;

        SceneStorageContract(
                final int bytesPerPixel,
                final boolean sceneLinear,
                final boolean actualHdrRadiance
        ) {
            this.bytesPerPixel = bytesPerPixel;
            this.sceneLinear = sceneLinear;
            this.actualHdrRadiance = actualHdrRadiance;
        }

        public int bytesPerPixel() {
            return this.bytesPerPixel;
        }

        public boolean sceneLinear() {
            return this.sceneLinear;
        }

        public boolean actualHdrRadiance() {
            return this.actualHdrRadiance;
        }
    }

    /** Meaning of HDR extraction/display work for the resolved render-contract path. */
    public enum HdrPipelineContract {
        NONE,
        LEGACY_SEMANTIC_RECONSTRUCTION,
        ACTUAL_RADIANCE_EXPOSURE_BLOOM
    }

    public enum Domain {
        BASE,
        HDR_ONLY,
        MATERIAL_ONLY,
        ADVANCED_LIGHTING_ONLY,
        UPSCALE_ONLY,
        INTERPOLATION_ONLY,
        DIAGNOSTIC_ONLY
    }

    public record Resource(String name, Domain domain, long bytes, boolean external) {
        public Resource {
            name = requireName(name);
            Objects.requireNonNull(domain, "domain");
            if (bytes < 0L) {
                throw new IllegalArgumentException("Resource bytes must be non-negative");
            }
            if (external && bytes != 0L) {
                throw new IllegalArgumentException("External resources are not owned byte estimates");
            }
        }
    }

    public record Pass(String name, Domain domain) {
        public Pass {
            name = requireName(name);
            Objects.requireNonNull(domain, "domain");
        }
    }

    public record Encoder(String name, Domain domain) {
        public Encoder {
            name = requireName(name);
            Objects.requireNonNull(domain, "domain");
        }
    }

    public record Pipeline(String name, Domain domain) {
        public Pipeline {
            name = requireName(name);
            Objects.requireNonNull(domain, "domain");
        }
    }

    public record WorkQueue(String name, Domain domain) {
        public WorkQueue {
            name = requireName(name);
            Objects.requireNonNull(domain, "domain");
        }
    }

    public RendererGenerationManifest {
        if (version <= 0) {
            throw new IllegalArgumentException("Manifest version must be positive");
        }
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sceneStorageContract, "sceneStorageContract");
        Objects.requireNonNull(hdrPipelineContract, "hdrPipelineContract");
        resources = List.copyOf(resources);
        passes = List.copyOf(passes);
        encoders = List.copyOf(encoders);
        pipelines = List.copyOf(pipelines);
        workQueues = List.copyOf(workQueues);
        if (executable && admissionBlocker != null) {
            throw new IllegalArgumentException("Executable manifest cannot have an admission blocker");
        }
        if (!executable) {
            admissionBlocker = requireName(admissionBlocker);
        }
        if (!matchesSceneStorage(config, sceneStorageContract)) {
            throw new IllegalArgumentException(
                    "Scene storage contract does not match the resolved contract/output modes"
            );
        }
        HdrPipelineContract expectedHdr = expectedHdrPipeline(config);
        if (hdrPipelineContract != expectedHdr) {
            throw new IllegalArgumentException(
                    "HDR pipeline contract does not match the resolved contract/output modes"
            );
        }
        if (config.renderContractMode() == RenderContractMode.LEGACY
                && domainHasWork(Domain.MATERIAL_ONLY, resources, passes, encoders, pipelines,
                workQueues)) {
            throw new IllegalArgumentException("Legacy generations cannot declare material work");
        }
        if (config.lightingModel() == LightingModel.VANILLA
                && domainHasWork(Domain.ADVANCED_LIGHTING_ONLY, resources, passes, encoders,
                pipelines, workQueues)) {
            throw new IllegalArgumentException("Vanilla lighting cannot declare Advanced work");
        }
        if (config.lightingModel() == LightingModel.ADVANCED
                && !domainHasWork(Domain.ADVANCED_LIGHTING_ONLY, resources, passes, encoders,
                pipelines, workQueues)) {
            throw new IllegalArgumentException("Advanced lighting requires an executable Advanced domain");
        }
        boolean interpolationFeature = config.featureMask().contains(
                RendererFeatureMask.FRAME_INTERPOLATION
        );
        boolean interpolationDomain = domainHasWork(
                Domain.INTERPOLATION_ONLY, resources, passes, encoders, pipelines, workQueues
        );
        if (interpolationFeature) {
            boolean temporal = config.featureMask().contains(RendererFeatureMask.TEMPORAL_UPSCALING);
            boolean spatial = config.featureMask().contains(RendererFeatureMask.SPATIAL_UPSCALING);
            if (!temporal && !spatial) {
                throw new IllegalArgumentException(
                        "Frame Interpolation requires a fixed Temporal or Spatial upstream feature"
                );
            }
            if (!interpolationDomain || resourceBytes(resources, Domain.INTERPOLATION_ONLY) <= 0L) {
                throw new IllegalArgumentException(
                        "Frame Interpolation requires owned interpolation resources and work"
                );
            }
        } else if (interpolationDomain) {
            throw new IllegalArgumentException(
                    "Interpolation work must not exist without the Frame Interpolation feature"
            );
        }
    }

    public long resourceBytes(final Domain domain) {
        Objects.requireNonNull(domain, "domain");
        return this.resources.stream()
                .filter(resource -> !resource.external() && resource.domain() == domain)
                .mapToLong(Resource::bytes)
                .reduce(0L, Math::addExact);
    }

    public long passCount(final Domain domain) {
        Objects.requireNonNull(domain, "domain");
        return this.passes.stream().filter(pass -> pass.domain() == domain).count();
    }

    public long encoderCount(final Domain domain) {
        Objects.requireNonNull(domain, "domain");
        return this.encoders.stream().filter(encoder -> encoder.domain() == domain).count();
    }

    public long pipelineCount(final Domain domain) {
        Objects.requireNonNull(domain, "domain");
        return this.pipelines.stream().filter(pipeline -> pipeline.domain() == domain).count();
    }

    public long workQueueCount(final Domain domain) {
        Objects.requireNonNull(domain, "domain");
        return this.workQueues.stream().filter(queue -> queue.domain() == domain).count();
    }

    private static boolean matchesSceneStorage(
            final RendererGenerationConfig config,
            final SceneStorageContract sceneStorageContract
    ) {
        if (config.renderContractMode() == RenderContractMode.METALLUM) {
            if (config.outputMode() == DisplayOutputMode.HDR) {
                return sceneStorageContract
                        == SceneStorageContract.METALLUM_HDR_ACTUAL_RADIANCE_RGBA16F;
            }
            return sceneStorageContract == SceneStorageContract.METALLUM_SDR_LINEAR_RGBA8
                    || sceneStorageContract
                    == SceneStorageContract.METALLUM_SDR_LINEAR_RGBA16F_COMPAT;
        }
        if (config.outputMode() == DisplayOutputMode.HDR) {
            return sceneStorageContract == SceneStorageContract.LEGACY_HDR_SEMANTIC_SRGB8
                    || sceneStorageContract
                    == SceneStorageContract.LEGACY_HDR_SEMANTIC_RGBA16F;
        }
        return sceneStorageContract == SceneStorageContract.LEGACY_SDR_SRGB8
                || sceneStorageContract == SceneStorageContract
                .LEGACY_SDR_SRGB_RGBA16F_COMPAT;
    }

    private static HdrPipelineContract expectedHdrPipeline(
            final RendererGenerationConfig config
    ) {
        if (config.outputMode() == DisplayOutputMode.SDR) {
            return HdrPipelineContract.NONE;
        }
        return config.renderContractMode() == RenderContractMode.METALLUM
                ? HdrPipelineContract.ACTUAL_RADIANCE_EXPOSURE_BLOOM
                : HdrPipelineContract.LEGACY_SEMANTIC_RECONSTRUCTION;
    }

    private static boolean domainHasWork(
            final Domain domain,
            final List<Resource> resources,
            final List<Pass> passes,
            final List<Encoder> encoders,
            final List<Pipeline> pipelines,
            final List<WorkQueue> workQueues
    ) {
        return resources.stream().anyMatch(resource -> resource.domain() == domain)
                || passes.stream().anyMatch(pass -> pass.domain() == domain)
                || encoders.stream().anyMatch(encoder -> encoder.domain() == domain)
                || pipelines.stream().anyMatch(pipeline -> pipeline.domain() == domain)
                || workQueues.stream().anyMatch(queue -> queue.domain() == domain);
    }

    private static long resourceBytes(final List<Resource> resources, final Domain domain) {
        return resources.stream()
                .filter(resource -> !resource.external() && resource.domain() == domain)
                .mapToLong(Resource::bytes)
                .reduce(0L, Math::addExact);
    }

    private static String requireName(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Manifest names must not be blank");
        }
        return value;
    }
}
