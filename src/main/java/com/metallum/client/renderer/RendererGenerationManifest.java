package com.metallum.client.renderer;

import java.util.List;
import java.util.Objects;

/** Exact L0 resource/pass declaration for one resolved generation. */
public record RendererGenerationManifest(
        int version,
        RendererGenerationConfig config,
        boolean executable,
        List<Resource> resources,
        List<Pass> passes,
        String admissionBlocker
) {
    public static final int CURRENT_VERSION = 1;

    public enum Domain {
        BASE,
        HDR_ONLY,
        LIGHTING_ONLY,
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

    public RendererGenerationManifest {
        if (version <= 0) {
            throw new IllegalArgumentException("Manifest version must be positive");
        }
        Objects.requireNonNull(config, "config");
        resources = List.copyOf(resources);
        passes = List.copyOf(passes);
        if (executable && admissionBlocker != null) {
            throw new IllegalArgumentException("Executable manifest cannot have an admission blocker");
        }
        if (!executable) {
            admissionBlocker = requireName(admissionBlocker);
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

    private static String requireName(final String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Manifest names must not be blank");
        }
        return value;
    }
}
