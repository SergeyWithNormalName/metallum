package com.metallum.client.lighting.reflection;

import com.metallum.client.lighting.shader.AdvancedLightingBindingAbi;
import com.metallum.client.lighting.shader.CloudShadowBindingAbi;
import com.metallum.client.lighting.shader.EnvironmentShadowBindingAbi;
import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;

import java.util.Objects;

/**
 * Explicit vertex-only binding contract for the rough-reflection experiment.
 *
 * <p>It deliberately reuses buffer 27 only across shader stages: L3 owns that
 * index in the fragment stage, while the experiment owns it in the vertex
 * stage. Texture/sampler slot 10 is below the L4 external range (12--15) and
 * within the Metal per-stage sampler range used by this backend.</p>
 */
public final class VertexReflectionBindingAbi {
    public static final int RADIANCE_TEXTURE_AND_SAMPLER_SLOT = 10;
    public static final int PARAMS_BUFFER_SLOT = 27;
    public static final int METAL_MAX_SAMPLER_SLOT = 15;

    private VertexReflectionBindingAbi() {
    }

    public static void requireLegal() {
        int slot = RADIANCE_TEXTURE_AND_SAMPLER_SLOT;
        if (slot < 0 || slot > METAL_MAX_SAMPLER_SLOT) {
            throw new IllegalStateException("Vertex reflection texture/sampler ABI is invalid");
        }
        if (slot >= CloudShadowBindingAbi.TEXTURE_SLOT
                || EnvironmentShadowBindingAbi.ownsShadowTextureSlot(slot)
                || PARAMS_BUFFER_SLOT == VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT
                || PARAMS_BUFFER_SLOT != AdvancedLightingBindingAbi.PARAMS_SLOT) {
            throw new IllegalStateException("Vertex reflection binding ABI overlaps a reserved stage contract");
        }
    }

    /** Checks emitted MSL rather than GLSL declarations or comments. */
    public static void validateMsl(
            final String vertexMsl,
            final String fragmentMsl,
            final boolean enabled
    ) {
        Objects.requireNonNull(vertexMsl, "vertexMsl");
        Objects.requireNonNull(fragmentMsl, "fragmentMsl");
        requireLegal();
        String radianceTexture = "texture3d<float> " + VertexReflectionExperiment.RADIANCE_SAMPLER_NAME
                + " [[texture(" + RADIANCE_TEXTURE_AND_SAMPLER_SLOT + ")]]";
        String radianceSampler = "sampler " + VertexReflectionExperiment.RADIANCE_SAMPLER_NAME
                + "Smplr [[sampler(" + RADIANCE_TEXTURE_AND_SAMPLER_SLOT + ")]]";
        boolean vertexContainsExperiment = vertexMsl.contains(VertexReflectionExperiment.RADIANCE_SAMPLER_NAME)
                || vertexMsl.contains("metallumCoarseReflection")
                || vertexMsl.contains("metallumCoarseReflectionDirection");
        boolean fragmentContainsExperiment = fragmentMsl.contains(VertexReflectionExperiment.RADIANCE_SAMPLER_NAME);
        if (!enabled) {
            if (vertexContainsExperiment || fragmentContainsExperiment) {
                throw new IllegalStateException("Reflection-OFF MSL retained vertex reflection resources");
            }
            return;
        }
        if (countOccurrences(vertexMsl, radianceTexture) != 1
                || countOccurrences(vertexMsl, radianceSampler) != 1
                || countOccurrences(vertexMsl, "[[buffer(" + PARAMS_BUFFER_SLOT + ")]]") != 1
                || countOccurrences(vertexMsl,
                "[[buffer(" + VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT + ")]]") != 1
                || !vertexMsl.contains("metallumCoarseReflection")
                || !vertexMsl.contains("metallumCoarseReflectionDirection")
                || fragmentContainsExperiment
                || fragmentMsl.contains("texture3d<")
                || !fragmentMsl.contains("in.metallumCoarseReflection")
                || !fragmentMsl.contains("in.metallumCoarseReflectionDirection")) {
            throw new IllegalStateException("Vertex reflection MSL ABI no longer matches the vertex-only contract");
        }
    }

    private static int countOccurrences(final String source, final String marker) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(marker, cursor)) >= 0) {
            count++;
            cursor += marker.length();
        }
        return count;
    }
}
