package com.metallum.client.gi.receiver;

import java.util.List;
import java.util.Objects;

/** Vertex-only resource and varying contract for G5 generated Metal shaders. */
public final class GiReceiverBindingAbi {
    public static final String SH_RED_SAMPLER = "metallumGiShRed";
    public static final String SH_GREEN_SAMPLER = "metallumGiShGreen";
    public static final String SH_BLUE_SAMPLER = "metallumGiShBlue";
    public static final String CONFIDENCE_SAMPLER = "metallumGiConfidence";
    public static final String PARAMS_BLOCK = "metallumGiReceiver";
    public static final String VARYING = "metallumGiIncomingIrradiance";

    public static final int METAL_MAX_SAMPLER_SLOT = 15;
    public static final int METAL_MAX_BUFFER_SLOT = 30;

    private static final List<String> SAMPLERS = List.of(
            SH_RED_SAMPLER, SH_GREEN_SAMPLER, SH_BLUE_SAMPLER, CONFIDENCE_SAMPLER
    );
    private static final int[] TEXTURE_SLOTS = {
            GiReceiverLayout.SH_RED_TEXTURE_SLOT,
            GiReceiverLayout.SH_GREEN_TEXTURE_SLOT,
            GiReceiverLayout.SH_BLUE_TEXTURE_SLOT,
            GiReceiverLayout.CONFIDENCE_TEXTURE_SLOT
    };

    private GiReceiverBindingAbi() {
    }

    public static List<String> samplerNames() {
        return SAMPLERS;
    }

    public static int[] textureSlots() {
        return TEXTURE_SLOTS.clone();
    }

    public static boolean isExternalSampler(final String name) {
        return SAMPLERS.contains(name);
    }

    public static void requireLegal() {
        if (TEXTURE_SLOTS.length != 4
                || TEXTURE_SLOTS[0] != 6
                || TEXTURE_SLOTS[1] != TEXTURE_SLOTS[0] + 1
                || TEXTURE_SLOTS[2] != TEXTURE_SLOTS[1] + 1
                || TEXTURE_SLOTS[3] != TEXTURE_SLOTS[2] + 1
                || TEXTURE_SLOTS[3] > METAL_MAX_SAMPLER_SLOT) {
            throw new IllegalStateException("G5 vertex texture/sampler slots are invalid");
        }
        if (GiReceiverLayout.PARAMS_BUFFER_SLOT != 25
                || GiReceiverLayout.PARAMS_BUFFER_SLOT > METAL_MAX_BUFFER_SLOT) {
            throw new IllegalStateException("G5 vertex parameter-buffer slot is invalid");
        }
    }

    /**
     * Checks the emitted MSL, not the intermediate GLSL. G5 textures may only occur in the
     * terrain vertex entry; fragment receives one interpolated irradiance/confidence value.
     */
    public static void validateMsl(
            final String vertexMsl,
            final String fragmentMsl,
            final boolean enabled
    ) {
        Objects.requireNonNull(vertexMsl, "vertexMsl");
        Objects.requireNonNull(fragmentMsl, "fragmentMsl");
        requireLegal();
        boolean vertexContains = containsReceiverSymbol(vertexMsl);
        List<String> fragmentSamplerSymbols = SAMPLERS.stream()
                .filter(name -> fragmentMsl.contains("texture3d<float> " + name)
                        || fragmentMsl.contains("sampler " + name + "Smplr")
                        || fragmentMsl.contains(name + ".sample("))
                .toList();
        boolean fragmentParams = fragmentMsl.contains(PARAMS_BLOCK);
        boolean fragmentContainsResources = !fragmentSamplerSymbols.isEmpty() || fragmentParams;
        if (!enabled) {
            if (vertexContains || fragmentContainsResources
                    || vertexMsl.contains(VARYING) || fragmentMsl.contains(VARYING)) {
                throw new IllegalStateException("G5-OFF MSL retained receiver resources or varyings");
            }
            return;
        }

        for (int index = 0; index < SAMPLERS.size(); index++) {
            String name = SAMPLERS.get(index);
            int slot = TEXTURE_SLOTS[index];
            if (countOccurrences(vertexMsl, name) < 2
                    || countOccurrences(vertexMsl, name + ".sample(") != 1
                    || countOccurrences(vertexMsl, "[[texture(" + slot + ")]]") != 1
                    || countOccurrences(vertexMsl, "[[sampler(" + slot + ")]]") != 1) {
                throw new IllegalStateException(
                        "G5 emitted vertex MSL lost sampler " + name + " at slot " + slot
                );
            }
        }
        int paramsBindings = countOccurrences(vertexMsl,
                "[[buffer(" + GiReceiverLayout.PARAMS_BUFFER_SLOT + ")]]");
        boolean vertexParams = vertexMsl.contains(PARAMS_BLOCK);
        boolean vertexVarying = vertexMsl.contains(VARYING);
        boolean fragmentVarying = fragmentMsl.contains(VARYING);
        boolean fragmentReadsVarying = fragmentMsl.contains("in." + VARYING);
        if (paramsBindings != 1 || !vertexParams || !vertexVarying
                || !fragmentVarying || !fragmentReadsVarying || fragmentContainsResources) {
            throw new IllegalStateException(
                    "G5 emitted MSL no longer matches the vertex-only receiver contract"
                            + " (paramsBindings=" + paramsBindings
                            + ", vertexParams=" + vertexParams
                            + ", vertexVarying=" + vertexVarying
                            + ", fragmentVarying=" + fragmentVarying
                            + ", fragmentReadsVarying=" + fragmentReadsVarying
                            + ", fragmentSamplerSymbols=" + fragmentSamplerSymbols
                            + ", fragmentParams=" + fragmentParams + ')'
            );
        }
    }

    private static boolean containsReceiverSymbol(final String source) {
        return source.contains(PARAMS_BLOCK)
                || source.contains(VARYING)
                || SAMPLERS.stream().anyMatch(source::contains);
    }

    private static int countOccurrences(final String source, final String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }
}
