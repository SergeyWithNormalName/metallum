package com.metallum.client.hdr;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source-level color-boundary transforms for the opt-in scene shader flavors. */
public final class SceneLinearShaderPatcher {
    public enum Stage {
        VERTEX,
        FRAGMENT
    }

    public record Result(String source, boolean success, String failureReason) {
        static Result success(final String source) {
            return new Result(source, true, "");
        }

        static Result failure(final String source, final String reason) {
            return new Result(source, false, reason);
        }
    }

    private record PostTarget(String inputAnchor, String colorVariable) {
    }

    private static final String COLOR_OUTPUT = "out vec4 fragColor;";
    private static final String RASTER_MARKER = "METALLUM_SCENE_RASTER_LINEAR_BOUNDARY";
    private static final String POST_MARKER = "METALLUM_SCENE_POST_ENCODED_OPERATION";
    private static final Pattern MAIN_PATTERN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern RETURN_PATTERN = Pattern.compile("\\breturn\\b");

    private static final String DECODE_HELPER = "\n// " + RASTER_MARKER + "\n"
            + "float metallumExtendedSrgbChannelToLinear(float value) {\n"
            + "    float magnitude = abs(value);\n"
            + "    float linearValue = magnitude <= 0.04045\n"
            + "            ? magnitude / 12.92\n"
            + "            : pow((magnitude + 0.055) / 1.055, 2.4);\n"
            + "    return value < 0.0 ? -linearValue : linearValue;\n"
            + "}\n\n"
            + "vec3 metallumExtendedSrgbToLinear(vec3 value) {\n"
            + "    return vec3(\n"
            + "            metallumExtendedSrgbChannelToLinear(value.r),\n"
            + "            metallumExtendedSrgbChannelToLinear(value.g),\n"
            + "            metallumExtendedSrgbChannelToLinear(value.b));\n"
            + "}\n";

    private static final String POST_HELPERS = "\n// " + POST_MARKER + "\n"
            + DECODE_HELPER.substring(DECODE_HELPER.indexOf("float metallumExtendedSrgbChannelToLinear"))
            + "\nfloat metallumLinearChannelToExtendedSrgb(float value) {\n"
            + "    float magnitude = abs(value);\n"
            + "    float encodedValue = magnitude <= 0.0031308\n"
            + "            ? magnitude * 12.92\n"
            + "            : 1.055 * pow(magnitude, 1.0 / 2.4) - 0.055;\n"
            + "    return value < 0.0 ? -encodedValue : encodedValue;\n"
            + "}\n\n"
            + "vec3 metallumLinearToExtendedSrgb(vec3 value) {\n"
            + "    return vec3(\n"
            + "            metallumLinearChannelToExtendedSrgb(value.r),\n"
            + "            metallumLinearChannelToExtendedSrgb(value.g),\n"
            + "            metallumLinearChannelToExtendedSrgb(value.b));\n"
            + "}\n";

    private SceneLinearShaderPatcher() {
    }

    public static Result patch(
            final String namespace,
            final String path,
            final Stage stage,
            final HdrShaderFlavor flavor,
            final String source
    ) {
        if (source == null) {
            return Result.failure(null, "shader source is missing");
        }
        if (flavor == HdrShaderFlavor.LEGACY
                || flavor == HdrShaderFlavor.LEGACY_HDR_SEMANTIC
                || stage == Stage.VERTEX) {
            return Result.success(source);
        }
        return switch (flavor) {
            case LEGACY -> Result.success(source);
            case LEGACY_HDR_SEMANTIC -> Result.success(source);
            case SCENE_RASTER_LINEAR -> patchRasterBoundary(source);
            case SCENE_POST_LINEAR -> patchPost(namespace, path, source);
            case METALLUM, METALLUM_ADVANCED, METALLUM_ADVANCED_REACTIVE, SUN_SHADOW -> Result.failure(
                    source,
                    flavor + " uses a dedicated shader patcher"
            );
        };
    }

    public static boolean isRasterPatched(final String source) {
        return source != null
                && source.contains(RASTER_MARKER)
                && source.contains("fragColor.rgb = metallumExtendedSrgbToLinear(fragColor.rgb);");
    }

    public static boolean isDisplayPostPatched(final String source) {
        return source != null
                && source.contains(POST_MARKER)
                && source.contains("metallumLinearToExtendedSrgb")
                && source.contains("fragColor.rgb = metallumExtendedSrgbToLinear(fragColor.rgb);");
    }

    static float extendedSrgbToLinear(final float value) {
        float magnitude = Math.abs(value);
        float linear = magnitude <= 0.04045f
                ? magnitude / 12.92f
                : (float) Math.pow((magnitude + 0.055f) / 1.055f, 2.4);
        return Math.copySign(linear, value);
    }

    static float linearToExtendedSrgb(final float value) {
        float magnitude = Math.abs(value);
        float encoded = magnitude <= 0.0031308f
                ? magnitude * 12.92f
                : 1.055f * (float) Math.pow(magnitude, 1.0 / 2.4) - 0.055f;
        return Math.copySign(encoded, value);
    }

    private static Result patchRasterBoundary(final String source) {
        if (isRasterPatched(source)) {
            return Result.success(source);
        }
        Result structural = validateFragmentMain(source);
        if (!structural.success()) {
            return structural;
        }

        int mainEnd = findMainEnd(source);
        String withHelper = replaceOnce(source, COLOR_OUTPUT, COLOR_OUTPUT + DECODE_HELPER);
        int helperGrowth = withHelper.length() - source.length();
        int insertion = mainEnd + helperGrowth;
        String patched = withHelper.substring(0, insertion)
                + "    fragColor.rgb = metallumExtendedSrgbToLinear(fragColor.rgb);\n"
                + withHelper.substring(insertion);
        return isRasterPatched(patched)
                ? Result.success(patched)
                : Result.failure(source, "raster boundary marker was not installed");
    }

    private static Result patchPost(
            final String namespace,
            final String path,
            final String source
    ) {
        PostTarget target = displayAuthoredPostTarget(namespace, path);
        if (target == null) {
            return Result.success(source);
        }
        if (isDisplayPostPatched(source)) {
            return Result.success(source);
        }
        Result structural = validateFragmentMain(source);
        if (!structural.success()) {
            return structural;
        }
        if (!occursExactlyOnce(source, target.inputAnchor())) {
            return Result.failure(source, "expected post input anchor exactly once");
        }

        String withHelper = replaceOnce(source, COLOR_OUTPUT, COLOR_OUTPUT + POST_HELPERS);
        String encodedInput = target.inputAnchor()
                + "\n    " + target.colorVariable() + ".rgb = metallumLinearToExtendedSrgb("
                + target.colorVariable() + ".rgb);";
        String withInputEncoding = replaceOnce(withHelper, target.inputAnchor(), encodedInput);
        int mainEnd = findMainEnd(withInputEncoding);
        String patched = withInputEncoding.substring(0, mainEnd)
                + "    fragColor.rgb = metallumExtendedSrgbToLinear(fragColor.rgb);\n"
                + withInputEncoding.substring(mainEnd);
        return isDisplayPostPatched(patched)
                ? Result.success(patched)
                : Result.failure(source, "display-authored post wrapper was not installed");
    }

    private static Result validateFragmentMain(final String source) {
        if ((source.contains("metallumExtendedSrgbChannelToLinear")
                || source.contains("metallumExtendedSrgbToLinear")
                || source.contains("metallumLinearChannelToExtendedSrgb")
                || source.contains("metallumLinearToExtendedSrgb"))
                && !source.contains(RASTER_MARKER)
                && !source.contains(POST_MARKER)) {
            return Result.failure(source, "shader collides with Metallum color-boundary helper names");
        }
        if (!occursExactlyOnce(source, COLOR_OUTPUT)) {
            return Result.failure(source, "expected exactly one fragColor output");
        }
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return Result.failure(source, "expected exactly one void main()");
        }
        int mainStart = matcher.start();
        if (matcher.find()) {
            return Result.failure(source, "expected exactly one void main()");
        }
        int mainEnd = findMainEnd(source);
        if (mainEnd < 0) {
            return Result.failure(source, "main() braces are unbalanced");
        }
        String mainBody = source.substring(mainStart, mainEnd);
        if (!mainBody.contains("fragColor") || !mainBody.contains("=")) {
            return Result.failure(source, "main() does not assign fragColor");
        }
        if (RETURN_PATTERN.matcher(mainBody).find()) {
            return Result.failure(source, "main() contains an early return");
        }
        return Result.success(source);
    }

    private static int findMainEnd(final String source) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return -1;
        }
        int openingBrace = source.indexOf('{', matcher.start());
        int depth = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    index++;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                index++;
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static PostTarget displayAuthoredPostTarget(final String namespace, final String path) {
        if (!"minecraft".equals(namespace)) {
            return null;
        }
        return switch (path) {
            case "post/invert" -> new PostTarget(
                    "    vec4 diffuseColor = texture(InSampler, texCoord);",
                    "diffuseColor"
            );
            case "post/color_convolve" -> new PostTarget(
                    "    vec4 InTexel = texture(InSampler, texCoord);",
                    "InTexel"
            );
            case "post/bits" -> new PostTarget(
                    "    vec4 baseTexel = texture(InSampler, texCoord - fractPix);",
                    "baseTexel"
            );
            default -> null;
        };
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
