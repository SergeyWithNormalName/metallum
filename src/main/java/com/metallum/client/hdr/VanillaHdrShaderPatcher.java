package com.metallum.client.hdr;

public final class VanillaHdrShaderPatcher {
    public static final String ENTITY = "core/entity";
    public static final String BEACON_BEAM = "core/rendertype_beacon_beam";
    public static final String LIGHTNING = "core/rendertype_lightning";
    public static final String STARS = "core/stars";
    public static final String CELESTIAL = "core/position_tex";

    private static final String COLOR_OUTPUT = "out vec4 fragColor;";
    private static final String SEMANTIC_OUTPUT = "metallumHdrSemantic";

    private static final Target ENTITY_TARGET = new Target(
            "    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);",
            15,
            true,
            true,
            false
    );
    private static final Target BEACON_BEAM_TARGET = new Target(
            "    fragColor = apply_fog(color, fragmentDistance, fragmentDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);",
            15,
            true,
            false,
            false
    );
    private static final Target LIGHTNING_TARGET = new Target(
            "    fragColor = vertexColor * ColorModulator * (1.0f - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd));",
            15,
            true,
            false,
            false
    );
    private static final Target STARS_TARGET = new Target(
            "    fragColor = ColorModulator;",
            7,
            false,
            false,
            false
    );
    private static final Target CELESTIAL_TARGET = new Target(
            "    fragColor = color * ColorModulator;",
            12,
            false,
            false,
            true
    );

    private VanillaHdrShaderPatcher() {
    }

    public static boolean isTarget(final String path) {
        return target(path) != null;
    }

    public static String patchFragmentSource(final String path, final String source) {
        Target target = target(path);
        if (target == null || source.contains(SEMANTIC_OUTPUT)) {
            return source;
        }
        if (!source.contains(COLOR_OUTPUT) || !source.contains(target.assignment())) {
            return source;
        }

        String declaration = target.emissiveGuard()
                ? COLOR_OUTPUT + "\n#ifdef EMISSIVE\nlayout(location = 1) out vec4 " + SEMANTIC_OUTPUT + ";\n#endif"
                : COLOR_OUTPUT + "\nlayout(location = 1) out vec4 " + SEMANTIC_OUTPUT + ";";
        String write = semanticWrite(target.emission(), target.exact(), target.luminanceGate());
        if (target.emissiveGuard()) {
            write = "#ifdef EMISSIVE\n" + write + "\n#endif";
        }

        String patched = replaceOnce(source, COLOR_OUTPUT, declaration);
        return replaceOnce(patched, target.assignment(), target.assignment() + "\n" + write);
    }

    public static boolean isPatched(final String source) {
        return source.contains(SEMANTIC_OUTPUT);
    }

    private static String semanticWrite(
            final int emission,
            final boolean exact,
            final boolean luminanceGate
    ) {
        int flags = 0x10 | (exact ? 0x20 : 0);
        String visible = luminanceGate
                ? "metallumHdrEmission != 0u && dot(max(fragColor.rgb, vec3(0.0)), vec3(0.2126, 0.7152, 0.0722)) > 0.0039215686"
                : "metallumHdrEmission != 0u";
        return "    uint metallumHdrEmission = uint(round(clamp(fragColor.a, 0.0, 1.0) * " + emission + ".0));\n"
                + "    uint metallumHdrCode = " + visible + " ? (" + flags + "u | metallumHdrEmission) : 0u;\n"
                + "    uint metallumHdrDepth = uint(round(clamp(gl_FragCoord.z, 0.0, 1.0) * 16777215.0));\n"
                + "    " + SEMANTIC_OUTPUT + " = vec4(\n"
                + "            float(metallumHdrCode),\n"
                + "            float(metallumHdrDepth & 255u),\n"
                + "            float((metallumHdrDepth >> 8u) & 255u),\n"
                + "            float((metallumHdrDepth >> 16u) & 255u)) / 255.0;";
    }

    private static Target target(final String path) {
        return switch (path) {
            case ENTITY -> ENTITY_TARGET;
            case BEACON_BEAM -> BEACON_BEAM_TARGET;
            case LIGHTNING -> LIGHTNING_TARGET;
            case STARS -> STARS_TARGET;
            case CELESTIAL -> CELESTIAL_TARGET;
            default -> null;
        };
    }

    private static String replaceOnce(final String source, final String needle, final String replacement) {
        int index = source.indexOf(needle);
        if (index < 0) {
            return source;
        }
        return source.substring(0, index) + replacement + source.substring(index + needle.length());
    }

    private record Target(
            String assignment,
            int emission,
            boolean exact,
            boolean emissiveGuard,
            boolean luminanceGate
    ) {
    }
}
