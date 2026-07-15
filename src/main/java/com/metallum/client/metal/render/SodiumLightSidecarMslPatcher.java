package com.metallum.client.metal.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Adds a Metal-only raw light companion without changing Sodium's public vertex interface. */
final class SodiumLightSidecarMslPatcher {
    private static final String MARKER = "METALLUM_SODIUM_LIGHT_SIDECAR_V2";
    private static final String TARGET_NAMESPACE = "sodium";
    private static final String TARGET_SHADER = "blocks/block_layer_opaque";
    private static final Set<String> TARGET_PIPELINES = Set.of(
            "pipeline/solid_terrain",
            "pipeline/cutout_terrain",
            "pipeline/translucent_terrain"
    );
    private static final String LIGHT_MEMBER = "a_LightAndData";
    private static final int MAX_METAL_BUFFER_INDEX = 30;

    private SodiumLightSidecarMslPatcher() {
    }

    static boolean isTarget(final RenderPipeline pipeline) {
        var location = pipeline.getLocation();
        var vertexShader = pipeline.getVertexShader();
        var fragmentShader = pipeline.getFragmentShader();
        return TARGET_NAMESPACE.equals(location.getNamespace())
                && TARGET_PIPELINES.contains(location.getPath())
                && TARGET_NAMESPACE.equals(vertexShader.getNamespace())
                && TARGET_SHADER.equals(vertexShader.getPath())
                && TARGET_NAMESPACE.equals(fragmentShader.getNamespace())
                && TARGET_SHADER.equals(fragmentShader.getPath());
    }

    static Result patch(
            final String source,
            final String entryPoint,
            final int dataBufferSlot,
            final int controlBufferSlot
    ) {
        if (isPatched(source)) {
            return new Result(source, true, "already patched");
        }
        if (dataBufferSlot < 0 || controlBufferSlot != dataBufferSlot + 1
                || controlBufferSlot > MAX_METAL_BUFFER_INDEX) {
            return new Result(source, false, "invalid Metal sidecar buffer slots");
        }

        Pattern entryPattern = Pattern.compile(
                "\\bvertex\\s+\\w+\\s+" + Pattern.quote(entryPoint)
                        + "\\s*\\(\\s*(\\w+)\\s+(\\w+)\\s*\\[\\[stage_in\\]\\]"
        );
        Matcher entry = entryPattern.matcher(source);
        if (!entry.find()) {
            return new Result(source, false, "unique vertex stage-in entry was not found");
        }
        int entryStart = entry.start();
        String stageInputName = entry.group(2);
        int entryArgumentsEnd = entry.end();
        if (entry.find()) {
            return new Result(source, false, "unique vertex stage-in entry was not found");
        }
        if (!source.contains(stageInputName + "." + LIGHT_MEMBER)) {
            return new Result(source, false, "Sodium light member was not found in generated MSL");
        }

        int bodyOpen = findEntryBodyOpen(source, entryStart, entryArgumentsEnd);
        if (bodyOpen < 0) {
            return new Result(source, false, "vertex entry body was not found");
        }

        String arguments = ",\n"
                + "    const device ushort* metallumLightSidecar [[buffer(" + dataBufferSlot + ")]],\n"
                + "    constant uint& metallumLightSidecarEnabled [[buffer(" + controlBufferSlot + ")]],\n"
                + "    uint metallumLightSidecarVertexId [[vertex_id]]";
        String withArguments = source.substring(0, entryArgumentsEnd)
                + arguments
                + source.substring(entryArgumentsEnd);
        bodyOpen += arguments.length();
        String body = "\n    // " + MARKER + "\n"
                + "    if (metallumLightSidecarEnabled != 0u)\n"
                + "    {\n"
                + "        const uint metallumPackedLight = uint(metallumLightSidecar[metallumLightSidecarVertexId]);\n"
                + "        " + stageInputName + "." + LIGHT_MEMBER + ".x = metallumPackedLight & 255u;\n"
                + "        " + stageInputName + "." + LIGHT_MEMBER + ".y = (metallumPackedLight >> 8u) & 255u;\n"
                + "    }\n";
        String patched = withArguments.substring(0, bodyOpen + 1)
                + body
                + withArguments.substring(bodyOpen + 1);
        return new Result(patched, isPatched(patched), "patched");
    }

    private static int findEntryBodyOpen(
            final String source,
            final int entryStart,
            final int stageInputEnd
    ) {
        int argumentsOpen = source.indexOf('(', entryStart);
        if (argumentsOpen < 0 || argumentsOpen >= stageInputEnd) {
            return -1;
        }

        int depth = 0;
        for (int index = argumentsOpen; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '(') {
                depth++;
                continue;
            }
            if (character != ')') {
                continue;
            }

            depth--;
            if (depth < 0) {
                return -1;
            }
            if (depth != 0) {
                continue;
            }

            int bodyOpen = index + 1;
            while (bodyOpen < source.length() && Character.isWhitespace(source.charAt(bodyOpen))) {
                bodyOpen++;
            }
            return bodyOpen < source.length() && source.charAt(bodyOpen) == '{'
                    ? bodyOpen
                    : -1;
        }
        return -1;
    }

    static boolean isPatched(final String source) {
        return source.contains(MARKER);
    }

    record Result(String source, boolean success, String reason) {
    }
}
