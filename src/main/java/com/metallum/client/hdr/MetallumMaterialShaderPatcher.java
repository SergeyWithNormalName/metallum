package com.metallum.client.hdr;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minecraft 26.2 / Sodium 0.9.1 source adapters for the L2 scene-linear material flavor. */
public final class MetallumMaterialShaderPatcher {
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

    public static final float REFERENCE_WHITE = 1.0f;
    public static final float MAX_EMISSION_RADIANCE = 4.0f;
    private static final String MARKER = "METALLUM_MATERIAL_LINEAR_V1";
    private static final String COLOR_OUTPUT = "out vec4 fragColor;";
    private static final String TEXT_MASK_SAMPLE = "texture(Sampler0, texCoord0).rrrr";
    private static final String TEXT_MASK_PLACEHOLDER = "METALLUM_TEXT_COVERAGE_SAMPLE_RAW";
    private static final Pattern MAIN_PATTERN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*\\)\\s*\\{");
    private static final Pattern TEXTURE_CALL = Pattern.compile("\\b(texture|textureGrad|textureLod|textureProj)\\s*\\(");

    private static final String HELPERS = "\n// " + MARKER + "\n"
            + "float metallumMaterialSrgbChannelToLinear(float value) {\n"
            + "    float magnitude = abs(value);\n"
            + "    float linearValue = magnitude <= 0.04045\n"
            + "            ? magnitude / 12.92\n"
            + "            : pow((magnitude + 0.055) / 1.055, 2.4);\n"
            + "    return value < 0.0 ? -linearValue : linearValue;\n"
            + "}\n\n"
            + "vec3 metallumMaterialSrgbToLinear(vec3 value) {\n"
            + "    return vec3(\n"
            + "            metallumMaterialSrgbChannelToLinear(value.r),\n"
            + "            metallumMaterialSrgbChannelToLinear(value.g),\n"
            + "            metallumMaterialSrgbChannelToLinear(value.b));\n"
            + "}\n\n"
            + "vec4 metallumMaterialDecodeColor(vec4 value) {\n"
            + "    return vec4(metallumMaterialSrgbToLinear(value.rgb), value.a);\n"
            + "}\n";

    private MetallumMaterialShaderPatcher() {
    }

    public static Result patch(
            final String namespace,
            final String path,
            final Stage stage,
            final String source
    ) {
        if (source == null) {
            return Result.failure(null, "shader source is missing");
        }
        if (source.contains("metallumHdrSemantic")) {
            return Result.failure(source, "METALLUM material shaders must not expose the Legacy HDR semantic MRT");
        }
        if (source.contains(MARKER)) {
            return Result.success(source);
        }
        if (hasHelperCollision(source)) {
            return Result.failure(source, "shader collides with METALLUM material helper names");
        }
        if ("sodium".equals(namespace) && "blocks/block_layer_opaque".equals(path)) {
            return patchSodium(stage, source);
        }
        if (!"minecraft".equals(namespace)) {
            return Result.failure(source, "no material adapter for namespace " + namespace);
        }
        if (stage == Stage.VERTEX && HdrPipelinePolicy.requiredVanillaPostVertexShaders().contains(path)) {
            return markOnly(source);
        }
        if (stage == Stage.FRAGMENT && HdrPipelinePolicy.requiredVanillaPostFragmentShaders().contains(path)) {
            return patchPost(path, source);
        }
        if (stage == Stage.VERTEX && HdrPipelinePolicy.requiredVanillaRasterVertexShaders().contains(path)) {
            return patchVanillaVertex(path, source);
        }
        if (stage == Stage.FRAGMENT && HdrPipelinePolicy.requiredVanillaRasterFragmentShaders().contains(path)) {
            return patchVanillaFragment(path, source);
        }
        return Result.failure(source, "no material adapter for shader " + namespace + ':' + path + ' ' + stage);
    }

    public static boolean isPatched(final String source) {
        return source != null && source.contains(MARKER);
    }

    static float srgbToLinear(final float encoded) {
        float magnitude = Math.abs(encoded);
        float linear = magnitude <= 0.04045f
                ? magnitude / 12.92f
                : (float) Math.pow((magnitude + 0.055f) / 1.055f, 2.4);
        return Math.copySign(linear, encoded);
    }

    static float emissionRadiance(final int emission) {
        return MAX_EMISSION_RADIANCE * Math.clamp(emission, 0, 15) / 15.0f;
    }

    static float linearBlend(final float source, final float destination, final float sourceAlpha) {
        float alpha = Math.clamp(sourceAlpha, 0.0f, 1.0f);
        return source * alpha + destination * (1.0f - alpha);
    }

    private static Result patchVanillaVertex(final String path, final String source) {
        Result marked = installHelpersBeforeMain(source);
        if (!marked.success()) {
            return marked;
        }
        String patched = replaceIdentifierInMain(marked.source(), "Color",
                "metallumMaterialDecodeColor(Color)");
        patched = replaceIdentifierInMain(patched, "ColorModulator",
                "metallumMaterialDecodeColor(ColorModulator)");
        patched = replaceIdentifierInMain(patched, "CloudColor",
                "metallumMaterialDecodeColor(CloudColor)");
        patched = replaceExactlyOnce(
                patched,
                "overlayColor = texelFetch(Sampler1, UV1, 0);",
                "overlayColor = metallumMaterialDecodeColor(texelFetch(Sampler1, UV1, 0));"
        );
        return validateVanillaVertexTransform(path, source, patched);
    }

    private static Result patchVanillaFragment(final String path, final String source) {
        Result withHelpers = installHelpersAfterOutput(source);
        if (!withHelpers.success()) {
            return withHelpers;
        }
        String patched = withHelpers.source();

        if ("core/text".equals(path)) {
            patched = patched.replace(
                    TEXT_MASK_SAMPLE,
                    TEXT_MASK_PLACEHOLDER
            );
        }
        if (!"core/blit_screen".equals(path)
                && !"core/rendertype_outline".equals(path)) {
            patched = wrapTextureCalls(patched);
        }
        if ("core/text".equals(path)) {
            patched = patched.replace(
                    TEXT_MASK_PLACEHOLDER,
                    TEXT_MASK_SAMPLE
            );
        }

        patched = replaceIdentifierInMain(patched, "ColorModulator",
                "metallumMaterialDecodeColor(ColorModulator)");
        patched = replaceIdentifierInMain(patched, "FogColor",
                "metallumMaterialDecodeColor(FogColor)");
        if ("core/rendertype_end_portal".equals(path)) {
            patched = replaceExactlyOnce(
                    patched,
                    "COLORS[0]",
                    "metallumMaterialSrgbToLinear(COLORS[0])"
            );
            patched = replaceExactlyOnce(
                    patched,
                    "COLORS[i]",
                    "metallumMaterialSrgbToLinear(COLORS[i])"
            );
        }
        patched = addTrustedEmission(path, patched);
        return validateVanillaFragmentTransform(path, source, patched);
    }

    private static Result patchSodium(final Stage stage, final String source) {
        if (stage == Stage.VERTEX) {
            Result withHelpers = installHelpersBeforeMain(source);
            if (!withHelpers.success()) {
                return withHelpers;
            }
            String patched = replaceExactlyOnce(
                    withHelpers.source(),
                    "out vec2 v_TexCoord;",
                    "out vec2 v_TexCoord;\nout vec4 metallumTintColor;\nflat out uint metallumMaterial;"
            );
            patched = replaceExactlyOnce(
                    patched,
                    "    _vert_init();",
                    "    _vert_init();\n    metallumMaterial = _material_params;"
            );
            patched = replaceExactlyOnce(
                    patched,
                    "    v_Color = _vert_color * texture(u_LightTex, _vert_tex_light_coord);",
                    "    metallumTintColor = metallumMaterialDecodeColor(_vert_color);\n"
                            + "    vec4 metallumLightmap = texture(u_LightTex, _vert_tex_light_coord);\n"
                            + "    v_Color = metallumTintColor * metallumLightmap;"
            );
            if (!patched.contains("metallumMaterial = _material_params;")
                    || !patched.contains("v_Color = metallumTintColor * metallumLightmap;")) {
                return Result.failure(source, "Sodium vertex anchors changed");
            }
            return validateMarkedMain(patched, "Sodium vertex");
        }

        Result withHelpers = installHelpersAfterOutput(source);
        if (!withHelpers.success()) {
            return withHelpers;
        }
        String patched = wrapTextureCalls(withHelpers.source());
        patched = replaceExactlyOnce(
                patched,
                "in vec2 v_TexCoord;",
                "in vec2 v_TexCoord;\nin vec4 metallumTintColor;\nflat in uint metallumMaterial;"
        );
        String sampleAnchor = "    vec4 color = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);\n"
                + "    color *= v_Color; // Apply per-vertex color modulator";
        String sampleReplacement = "    vec4 metallumSample = u_UseRGSS ? sampleRGSS(u_BlockTex, v_TexCoord, u_TexelSize) : sampleNearest(u_BlockTex, v_TexCoord, u_TexelSize);\n"
                + "    vec4 metallumAlbedo = metallumSample; // Individual atlas taps are already linear\n"
                + "    vec4 color = metallumAlbedo * v_Color; // Linear albedo, tint, and lightmap\n"
                + "    vec3 metallumUnlitBase = max(metallumAlbedo.rgb * metallumTintColor.rgb, vec3(0.0));";
        patched = replaceExactlyOnce(patched, sampleAnchor, sampleReplacement);
        String fogAnchor = "    fragColor = _linearFog(color, v_FragDistance, u_FogColor, u_EnvironmentFog, u_RenderFog, fadeFactor);";
        String fogReplacement = "    uint metallumEmissionCode = (metallumMaterial >> 3u) & 15u;\n"
                + "    if (metallumEmissionCode != 0u) {\n"
                + "        float metallumEmission = 4.0 * float(metallumEmissionCode) / 15.0;\n"
                + "        bool metallumExactEmission = ((metallumMaterial >> 7u) & 1u) != 0u;\n"
                + "        vec3 metallumAuthoredRadiance = metallumUnlitBase * metallumEmission;\n"
                + "        color.rgb = metallumExactEmission\n"
                + "                ? max(color.rgb, metallumAuthoredRadiance)\n"
                + "                : color.rgb + metallumAuthoredRadiance;\n"
                + "    }\n"
                + "    fragColor = _linearFog(color, v_FragDistance, metallumMaterialDecodeColor(u_FogColor), u_EnvironmentFog, u_RenderFog, fadeFactor);";
        patched = replaceExactlyOnce(patched, fogAnchor, fogReplacement);
        if (!patched.contains("metallumAuthoredRadiance")
                || !patched.contains("metallumMaterialDecodeColor(u_FogColor)")
                || countOccurrences(patched, "metallumMaterialDecodeColor(texture")
                != countTextureCalls(source)) {
            return Result.failure(source, "Sodium fragment anchors changed");
        }
        return validateMarkedMain(patched, "Sodium fragment");
    }

    private static Result patchPost(final String path, final String source) {
        SceneLinearShaderPatcher.Result post = SceneLinearShaderPatcher.patch(
                "minecraft", path, SceneLinearShaderPatcher.Stage.FRAGMENT,
                HdrShaderFlavor.SCENE_POST_LINEAR, source
        );
        if (!post.success()) {
            return Result.failure(source, post.failureReason());
        }
        if ("post/blit".equals(path)) {
            Result withHelpers = installHelpersAfterOutput(post.source());
            if (!withHelpers.success()) {
                return withHelpers;
            }
            String patched = replaceIdentifierInMain(
                    withHelpers.source(),
                    "ColorModulate",
                    "metallumMaterialDecodeColor(ColorModulate)"
            );
            if (!patched.contains("metallumMaterialDecodeColor(ColorModulate)")) {
                return Result.failure(source, "post/blit ColorModulate anchor changed");
            }
            return validateMarkedMain(patched, "post " + path);
        }
        Result marked = markOnly(post.source());
        return marked.success() ? validateMarkedMain(marked.source(), "post " + path) : marked;
    }

    private static String addTrustedEmission(final String path, final String source) {
        return switch (path) {
            case VanillaHdrShaderPatcher.ENTITY -> source.replace(
                    "#ifndef EMISSIVE\n    color *= lightMapColor;\n#endif",
                    "vec3 metallumUnlitBase = max(color.rgb, vec3(0.0));\n"
                            + "#ifndef EMISSIVE\n    color *= lightMapColor;\n"
                            + "#else\n    color.rgb = max(color.rgb, metallumUnlitBase * 4.0);\n#endif"
            );
            case VanillaHdrShaderPatcher.BEACON_BEAM -> source.replace(
                    "    float fragmentDistance = 1.0 / gl_FragCoord.w;",
                    "    vec3 metallumUnlitBase = max(color.rgb, vec3(0.0));\n"
                            + "    color.rgb = max(color.rgb, metallumUnlitBase * 4.0);\n"
                            + "    float fragmentDistance = 1.0 / gl_FragCoord.w;"
            );
            case VanillaHdrShaderPatcher.LIGHTNING -> source.replace(
                    "    fragColor = vertexColor * metallumMaterialDecodeColor(ColorModulator) * (1.0f - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd));",
                    "    vec4 color = vertexColor * metallumMaterialDecodeColor(ColorModulator);\n"
                            + "    color.rgb = max(color.rgb, max(color.rgb, vec3(0.0)) * 4.0);\n"
                            + "    fragColor = color * (1.0f - total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd));"
            );
            case VanillaHdrShaderPatcher.STARS -> source.replace(
                    "    fragColor = metallumMaterialDecodeColor(ColorModulator);",
                    "    vec4 color = metallumMaterialDecodeColor(ColorModulator);\n"
                            + "    color.rgb *= 1.8666667;\n"
                            + "    fragColor = color;"
            );
            default -> source;
        };
    }

    private static Result installHelpersAfterOutput(final String source) {
        if (!occursExactlyOnce(source, COLOR_OUTPUT)) {
            return Result.failure(source, "expected exactly one fragColor output");
        }
        return Result.success(replaceExactlyOnce(source, COLOR_OUTPUT, COLOR_OUTPUT + HELPERS));
    }

    private static Result installHelpersBeforeMain(final String source) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return Result.failure(source, "expected exactly one void main()");
        }
        int mainStart = matcher.start();
        if (matcher.find()) {
            return Result.failure(source, "expected exactly one void main()");
        }
        return Result.success(source.substring(0, mainStart) + HELPERS + '\n' + source.substring(mainStart));
    }

    private static Result markOnly(final String source) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return Result.failure(source, "expected exactly one void main()");
        }
        int mainStart = matcher.start();
        if (matcher.find()) {
            return Result.failure(source, "expected exactly one void main()");
        }
        return Result.success(source.substring(0, mainStart)
                + "// " + MARKER + "\n" + source.substring(mainStart));
    }

    private static Result validateMarkedMain(final String source, final String description) {
        if (!source.contains(MARKER)) {
            return Result.failure(source, description + " marker was not installed");
        }
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return Result.failure(source, description + " no longer has exactly one main()");
        }
        int mainStart = matcher.start();
        if (matcher.find()) {
            return Result.failure(source, description + " no longer has exactly one main()");
        }
        if (findMainEnd(source, mainStart) < 0) {
            return Result.failure(source, description + " has unbalanced main() braces");
        }
        return Result.success(source);
    }

    private static Result validateVanillaVertexTransform(
            final String path,
            final String original,
            final String patched
    ) {
        Result structural = validateMarkedMain(patched, "vanilla vertex " + path);
        if (!structural.success()) {
            return structural;
        }
        String originalMain = mainBody(original);
        String patchedMain = mainBody(patched);
        if (originalMain == null || patchedMain == null) {
            return Result.failure(original, "vanilla vertex " + path + " has an invalid main()");
        }
        for (String authoredColor : List.of("Color", "ColorModulator", "CloudColor")) {
            int originalUses = countIdentifier(originalMain, authoredColor);
            int decodedUses = countOccurrences(
                    patchedMain,
                    "metallumMaterialDecodeColor(" + authoredColor + ')'
            );
            if (decodedUses != originalUses) {
                return Result.failure(
                        original,
                        "vanilla vertex " + path + " did not decode every authored " + authoredColor + " value"
                );
            }
        }
        String overlayAnchor = "overlayColor = texelFetch(Sampler1, UV1, 0);";
        if (original.contains(overlayAnchor)
                && !patched.contains("overlayColor = metallumMaterialDecodeColor(texelFetch(Sampler1, UV1, 0));")) {
            return Result.failure(original, "vanilla vertex " + path + " did not decode its overlay color");
        }
        if (patched.contains("metallumMaterialDecodeColor(sample_lightmap")
                || patched.contains("metallumMaterialDecodeColor(lightMapColor")
                || patched.contains("metallumMaterialDecodeColor(texture(u_LightTex")) {
            return Result.failure(original, "vanilla vertex " + path + " decoded linear lightmap data");
        }
        return Result.success(patched);
    }

    private static Result validateVanillaFragmentTransform(
            final String path,
            final String original,
            final String patched
    ) {
        Result structural = validateMarkedMain(patched, "vanilla fragment " + path);
        if (!structural.success()) {
            return structural;
        }
        String originalMain = mainBody(original);
        String patchedMain = mainBody(patched);
        if (originalMain == null || patchedMain == null) {
            return Result.failure(original, "vanilla fragment " + path + " has an invalid main()");
        }
        for (String authoredColor : List.of("ColorModulator", "FogColor")) {
            int originalUses = countIdentifier(originalMain, authoredColor);
            int decodedUses = countOccurrences(
                    patchedMain,
                    "metallumMaterialDecodeColor(" + authoredColor + ')'
            );
            if (decodedUses != originalUses) {
                return Result.failure(
                        original,
                        "vanilla fragment " + path + " did not decode every authored " + authoredColor + " value"
                );
            }
        }

        int originalSamples = countTextureCalls(original);
        int decodedSamples = countOccurrences(patched, "metallumMaterialDecodeColor(texture");
        int expectedDecodedSamples;
        if ("core/blit_screen".equals(path) || "core/rendertype_outline".equals(path)) {
            expectedDecodedSamples = 0;
        } else if ("core/text".equals(path)) {
            if (!original.contains(TEXT_MASK_SAMPLE) || !patched.contains(TEXT_MASK_SAMPLE)) {
                return Result.failure(original, "grayscale text coverage no longer has a raw data sample");
            }
            expectedDecodedSamples = originalSamples - 1;
        } else {
            expectedDecodedSamples = originalSamples;
        }
        if (decodedSamples != expectedDecodedSamples) {
            return Result.failure(
                    original,
                    "vanilla fragment " + path + " decoded " + decodedSamples
                            + " of " + expectedDecodedSamples + " required color samples"
            );
        }

        if ("core/rendertype_end_portal".equals(path)
                && (!patched.contains("metallumMaterialSrgbToLinear(COLORS[0])")
                || !patched.contains("metallumMaterialSrgbToLinear(COLORS[i])"))) {
            return Result.failure(original, "end portal authored palette was not linearized");
        }
        if (VanillaHdrShaderPatcher.ENTITY.equals(path)
                && countOccurrences(patched, "color.rgb = max(color.rgb, metallumUnlitBase * 4.0);") != 1) {
            return Result.failure(original, "entity emissive material anchor changed");
        }
        if (VanillaHdrShaderPatcher.BEACON_BEAM.equals(path)
                && countOccurrences(patched, "color.rgb = max(color.rgb, metallumUnlitBase * 4.0);") != 1) {
            return Result.failure(original, "beacon exact-emission anchor changed");
        }
        if (VanillaHdrShaderPatcher.LIGHTNING.equals(path)
                && countOccurrences(
                        patched,
                        "color.rgb = max(color.rgb, max(color.rgb, vec3(0.0)) * 4.0);"
                ) != 1) {
            return Result.failure(original, "lightning exact-emission anchor changed");
        }
        if (VanillaHdrShaderPatcher.STARS.equals(path)
                && countOccurrences(patched, "color.rgb *= 1.8666667;") != 1) {
            return Result.failure(original, "stars emission anchor changed");
        }
        if (patched.contains("metallumExtendedSrgbToLinear(fragColor.rgb)")) {
            return Result.failure(original, "material fragment retained the legacy final-output decode");
        }
        return Result.success(patched);
    }

    private static String replaceIdentifierInMain(
            final String source,
            final String identifier,
            final String replacement
    ) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        int end = findMainEnd(source, matcher.start());
        if (end < 0) {
            return source;
        }
        String body = source.substring(matcher.start(), end);
        String replaced = body.replaceAll("\\b" + Pattern.quote(identifier) + "\\b",
                Matcher.quoteReplacement(replacement));
        return source.substring(0, matcher.start()) + replaced + source.substring(end);
    }

    private static String wrapTextureCalls(final String source) {
        List<int[]> calls = new ArrayList<>();
        Matcher matcher = TEXTURE_CALL.matcher(source);
        while (matcher.find()) {
            int opening = source.indexOf('(', matcher.start());
            int closing = findMatchingParenthesis(source, opening);
            if (closing >= 0) {
                calls.add(new int[]{matcher.start(), closing + 1});
            }
        }
        String patched = source;
        for (int index = calls.size() - 1; index >= 0; index--) {
            int[] call = calls.get(index);
            patched = patched.substring(0, call[0])
                    + "metallumMaterialDecodeColor(" + patched.substring(call[0], call[1]) + ')'
                    + patched.substring(call[1]);
        }
        return patched;
    }

    private static int findMatchingParenthesis(final String source, final int opening) {
        int depth = 0;
        for (int index = opening; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '(') {
                depth++;
            } else if (current == ')' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static int findMainEnd(final String source, final int mainStart) {
        int opening = source.indexOf('{', mainStart);
        if (opening < 0) {
            return -1;
        }
        int depth = 0;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int index = opening; index < source.length(); index++) {
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

    private static boolean hasHelperCollision(final String source) {
        return source.contains("metallumMaterialSrgbChannelToLinear")
                || source.contains("metallumMaterialSrgbToLinear")
                || source.contains("metallumMaterialDecodeColor")
                || source.contains("metallumMaterialTextCoverage");
    }

    private static String mainBody(final String source) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.start();
        if (matcher.find()) {
            return null;
        }
        int end = findMainEnd(source, start);
        return end < 0 ? null : source.substring(start, end);
    }

    private static int countIdentifier(final String source, final String identifier) {
        Matcher matcher = Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b").matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countTextureCalls(final String source) {
        Matcher matcher = TEXTURE_CALL.matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countOccurrences(final String source, final String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private static boolean occursExactlyOnce(final String source, final String needle) {
        int first = source.indexOf(needle);
        return first >= 0 && source.indexOf(needle, first + needle.length()) < 0;
    }

    private static String replaceExactlyOnce(
            final String source,
            final String needle,
            final String replacement
    ) {
        int first = source.indexOf(needle);
        if (first < 0 || source.indexOf(needle, first + needle.length()) >= 0) {
            return source;
        }
        return source.substring(0, first) + replacement + source.substring(first + needle.length());
    }
}
