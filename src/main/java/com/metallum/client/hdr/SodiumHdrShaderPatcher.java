package com.metallum.client.hdr;

public final class SodiumHdrShaderPatcher {
    public static final int SODIUM_MATERIAL_BASE_MASK = 0x07;
    public static final int HDR_VERTEX_EMISSION_MASK = 0x0f;
    public static final int HDR_VERTEX_EXACT_BIT = 0x10;
    public static final int HDR_VERTEX_MASK = HDR_VERTEX_EMISSION_MASK | HDR_VERTEX_EXACT_BIT;
    public static final int HDR_MATERIAL_SHIFT = 3;
    public static final int HDR_MATERIAL_MASK = HDR_VERTEX_MASK << HDR_MATERIAL_SHIFT;

    private static final String MATERIAL_VARYING = "metallumHdrMaterial";
    private static final String SEMANTIC_OUTPUT = "metallumHdrSemantic";

    private SodiumHdrShaderPatcher() {
    }

    public static String patchVertexSource(final String source) {
        if (source.contains(MATERIAL_VARYING)) {
            return source;
        }
        if (!source.contains("out vec2 v_TexCoord;") || !source.contains("    _vert_init();")) {
            return source;
        }

        String patched = replaceOnce(
                source,
                "out vec2 v_TexCoord;",
                "out vec2 v_TexCoord;\nflat out uint " + MATERIAL_VARYING + ";"
        );
        return replaceOnce(
                patched,
                "    _vert_init();",
                "    _vert_init();\n    " + MATERIAL_VARYING + " = _material_params;"
        );
    }

    public static String patchFragmentSource(final String source) {
        if (source.contains(SEMANTIC_OUTPUT)) {
            return source;
        }
        if (!source.contains("in vec2 v_TexCoord;")
                || !source.contains("out vec4 fragColor;")
                || !source.contains("    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);")) {
            return source;
        }

        String patched = replaceOnce(
                source,
                "in vec2 v_TexCoord;",
                "in vec2 v_TexCoord;\nflat in uint " + MATERIAL_VARYING + ";"
        );
        patched = replaceOnce(
                patched,
                "out vec4 fragColor;",
                "out vec4 fragColor;\nlayout(location = 1) out vec4 " + SEMANTIC_OUTPUT + ";"
        );
        return replaceOnce(
                patched,
                "    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);",
                "    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);\n"
                        + "    uint metallumHdrSourceEmission = (" + MATERIAL_VARYING + " >> 3u) & 15u;\n"
                        + "    float metallumHdrCoverage = clamp(fragColor.a, 0.0, 1.0)\n"
                        + "            * (float(metallumHdrSourceEmission) / 15.0);\n"
                        + "    uint metallumHdrStrength = uint(round(clamp(metallumHdrCoverage, 0.0, 1.0) * 127.0));\n"
                        + "    uint metallumHdrExact = (" + MATERIAL_VARYING + " >> 7u) & 1u;\n"
                        + "    uint metallumHdrCode = metallumHdrStrength == 0u\n"
                        + "            ? 0u\n"
                        + "            : (metallumHdrStrength | (metallumHdrExact << 7u));\n"
                        + "    uint metallumHdrDepth = uint(round(clamp(gl_FragCoord.z, 0.0, 1.0) * 16777215.0));\n"
                        + "    " + SEMANTIC_OUTPUT + " = vec4(\n"
                        + "            float(metallumHdrCode),\n"
                        + "            float(metallumHdrDepth & 255u),\n"
                        + "            float((metallumHdrDepth >> 8u) & 255u),\n"
                        + "            float((metallumHdrDepth >> 16u) & 255u)) / 255.0;"
        );
    }

    public static boolean isPatched(final String source) {
        return source.contains(MATERIAL_VARYING);
    }

    public static int encodeVertexSemantic(final int emission, final boolean exact) {
        int normalizedEmission = Math.clamp(emission, 0, HDR_VERTEX_EMISSION_MASK);
        if (normalizedEmission == 0) {
            return 0;
        }
        return normalizedEmission | (exact ? HDR_VERTEX_EXACT_BIT : 0);
    }

    public static int packMaterialBits(final int materialBits, final int vertexSemantic) {
        if ((materialBits & ~SODIUM_MATERIAL_BASE_MASK) != 0) {
            return materialBits;
        }
        return materialBits | ((vertexSemantic & HDR_VERTEX_MASK) << HDR_MATERIAL_SHIFT);
    }

    private static String replaceOnce(final String source, final String needle, final String replacement) {
        int index = source.indexOf(needle);
        if (index < 0) {
            return source;
        }
        return source.substring(0, index) + replacement + source.substring(index + needle.length());
    }
}
