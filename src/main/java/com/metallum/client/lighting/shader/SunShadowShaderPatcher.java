package com.metallum.client.lighting.shader;

import com.metallum.client.hdr.MetallumMaterialShaderPatcher;

/** Builds the minimal L4 caster shaders used by the directional shadow pass. */
public final class SunShadowShaderPatcher {
    public static final String MARKER = "METALLUM_SUN_SHADOW_V1";

    private static final String SODIUM_VERTEX_MAIN = """
            void main() {
                _vert_init();

                vec3 translation = u_RegionOffset + _get_draw_translation(_draw_id);
                vec3 position = _vert_position + translation;
                gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
                v_TexCoord = (_vert_tex_diffuse_coord_bias * u_TexCoordShrink)
                        + _vert_tex_diffuse_coord;
            }
            """;

    private static final String SODIUM_FRAGMENT_MAIN = """
            void main() {
            #ifdef ALPHA_CUTOUT
                if (texture(u_BlockTex, v_TexCoord).a < ALPHA_CUTOUT) {
                    discard;
                }
            #endif
                fragColor = vec4(1.0);
            }
            """;

    private static final String ENTITY_VERTEX_MAIN = """
            void main() {
                gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);

            #ifdef PER_FACE_LIGHTING
                vertexPerFaceColorBack = Color;
                vertexPerFaceColorFront = Color;
            #else
                vertexColor = Color;
            #endif

                texCoord0 = UV0;
            #ifdef APPLY_TEXTURE_MATRIX
                texCoord0 = (TextureMat * vec4(UV0, 0.0, 1.0)).xy;
            #endif
            }
            """;

    private static final String ENTITY_FRAGMENT_MAIN = """
            void main() {
            #ifdef ALPHA_CUTOUT
                if (texture(Sampler0, texCoord0).a < ALPHA_CUTOUT) {
                    discard;
                }
            #endif

            #ifdef DISSOLVE
            #ifdef PER_FACE_LIGHTING
                float metallumCasterAlpha = gl_FrontFacing
                        ? vertexPerFaceColorFront.a
                        : vertexPerFaceColorBack.a;
            #else
                float metallumCasterAlpha = vertexColor.a;
            #endif
                if (metallumCasterAlpha < texture(DissolveMaskSampler, texCoord0).a) {
                    discard;
                }
            #endif

                fragColor = vec4(1.0);
            }
            """;

    private SunShadowShaderPatcher() {
    }

    public static Result patch(
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage,
            final String source
    ) {
        if (source == null || source.isBlank()) {
            return new Result(source, false, "shader source is empty");
        }
        String replacement;
        if (namespace.equals("sodium")
                && path.equals(AdvancedDirectLightingShaderPatcher.SODIUM_TERRAIN_PATH)) {
            replacement = stage == MetallumMaterialShaderPatcher.Stage.VERTEX
                    ? SODIUM_VERTEX_MAIN
                    : SODIUM_FRAGMENT_MAIN;
        } else if (namespace.equals("minecraft")
                && path.equals(AdvancedDirectLightingShaderPatcher.VANILLA_ENTITY_PATH)) {
            replacement = stage == MetallumMaterialShaderPatcher.Stage.VERTEX
                    ? ENTITY_VERTEX_MAIN
                    : ENTITY_FRAGMENT_MAIN;
        } else {
            return new Result(source, false, "shader is outside the L4 caster contract");
        }
        if (source.contains(MARKER)) {
            return new Result(source, true, "already patched");
        }

        int main = source.indexOf("void main()");
        if (main < 0 || source.indexOf("void main()", main + 1) >= 0) {
            return new Result(source, false, "unique main function was not found");
        }
        int bodyOpen = source.indexOf('{', main);
        int bodyClose = matchingBrace(source, bodyOpen);
        if (bodyOpen < 0 || bodyClose < 0) {
            return new Result(source, false, "main function body was not balanced");
        }

        String patched = source.substring(0, main)
                + "// " + MARKER + "\n"
                + replacement
                + source.substring(bodyClose + 1);
        return new Result(patched, patched.contains(MARKER), "patched");
    }

    private static int matchingBrace(final String source, final int openingBrace) {
        if (openingBrace < 0) {
            return -1;
        }
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    public record Result(String source, boolean success, String failureReason) {
    }
}
