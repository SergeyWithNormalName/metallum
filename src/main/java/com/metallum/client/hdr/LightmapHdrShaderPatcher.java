package com.metallum.client.hdr;

/**
 * Preserves light values above the vanilla lightmap's SDR clamp when the
 * scene render targets can store extended-range color.
 */
public final class LightmapHdrShaderPatcher {
    public static final String LIGHTMAP = "core/lightmap";

    private static final String PATCH_MARKER = "// METALLUM_HDR_LIGHTMAP_EXCESS";
    private static final String UNCLAMPED_COLOR = "metallumHdrUnclampedColor";
    private static final String EXCESS = "metallumHdrExcess";
    private static final String COMPRESSED_EXCESS = "metallumHdrCompressedExcess";

    private static final String CLAMP_ANCHOR = "    color = clamp(color, 0.0, 1.0);";
    private static final String OUTPUT_ANCHOR = "    fragColor = vec4(color, 1.0);";

    private static final String CLAMP_PATCH = "    " + PATCH_MARKER + "\n"
            + "    vec3 " + UNCLAMPED_COLOR + " = max(color, vec3(0.0));\n"
            + CLAMP_ANCHOR;
    private static final String OUTPUT_PATCH = "    vec3 " + EXCESS
            + " = max(" + UNCLAMPED_COLOR + " - vec3(1.0), vec3(0.0));\n"
            + "    vec3 " + COMPRESSED_EXCESS + " = " + EXCESS
            + " / (vec3(1.0) + " + EXCESS + ");\n"
            + "    fragColor = vec4(color + " + COMPRESSED_EXCESS + ", 1.0);";

    private LightmapHdrShaderPatcher() {
    }

    public static boolean isTarget(final String path) {
        return LIGHTMAP.equals(path);
    }

    public static String patchFragmentSource(final String source) {
        if (source == null || isPatched(source)) {
            return source;
        }
        if (source.contains(PATCH_MARKER)
                || source.contains(UNCLAMPED_COLOR)
                || source.contains(EXCESS)
                || source.contains(COMPRESSED_EXCESS)
                || !occursExactlyOnce(source, CLAMP_ANCHOR)
                || !occursExactlyOnce(source, OUTPUT_ANCHOR)) {
            return source;
        }

        String patched = replaceOnce(source, CLAMP_ANCHOR, CLAMP_PATCH);
        patched = replaceOnce(patched, OUTPUT_ANCHOR, OUTPUT_PATCH);
        return isPatched(patched) ? patched : source;
    }

    public static boolean isPatched(final String source) {
        return source != null
                && source.contains(PATCH_MARKER)
                && source.contains("vec3 " + UNCLAMPED_COLOR + " = max(color, vec3(0.0));")
                && source.contains("vec3 " + EXCESS + " = max(" + UNCLAMPED_COLOR
                + " - vec3(1.0), vec3(0.0));")
                && source.contains("fragColor = vec4(color + " + COMPRESSED_EXCESS + ", 1.0);");
    }

    private static boolean occursExactlyOnce(final String source, final String needle) {
        int first = source.indexOf(needle);
        return first >= 0 && source.indexOf(needle, first + needle.length()) < 0;
    }

    private static String replaceOnce(final String source, final String needle, final String replacement) {
        int index = source.indexOf(needle);
        if (index < 0) {
            return source;
        }
        return source.substring(0, index) + replacement + source.substring(index + needle.length());
    }
}
