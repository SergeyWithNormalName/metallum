package com.metallum.client.hdr;

import java.util.Set;

/** Explicit output-color policy for shader programs known to Metallum. */
public final class HdrPipelinePolicy {
    public enum Role {
        SCENE_RASTER,
        SCENE_POST,
        LIGHTMAP_DATA,
        UNKNOWN;

        public boolean supportsSceneLinearFlavor() {
            return this == SCENE_RASTER || this == SCENE_POST;
        }

        public HdrShaderFlavor sceneLinearFlavor() {
            return switch (this) {
                case SCENE_RASTER -> HdrShaderFlavor.SCENE_RASTER_LINEAR;
                case SCENE_POST -> HdrShaderFlavor.SCENE_POST_LINEAR;
                case LIGHTMAP_DATA, UNKNOWN -> HdrShaderFlavor.LEGACY;
            };
        }
    }

    private static final Set<String> VANILLA_SCENE_FRAGMENT_SHADERS = Set.of(
            "core/blit_screen",
            "core/block",
            "core/entity",
            "core/glint",
            "core/gui",
            "core/item",
            "core/panorama",
            "core/particle",
            "core/position",
            "core/position_color",
            "core/position_tex",
            "core/position_tex_color",
            "core/rendertype_beacon_beam",
            "core/rendertype_clouds",
            "core/rendertype_crumbling",
            "core/rendertype_end_portal",
            "core/rendertype_entity_shadow",
            "core/rendertype_leash",
            "core/rendertype_lightning",
            "core/rendertype_lines",
            "core/rendertype_outline",
            "core/rendertype_water_mask",
            "core/rendertype_world_border",
            "core/sky",
            "core/stars",
            "core/terrain",
            "core/text",
            "core/text_background"
    );

    /*
     * Minecraft 26.2 normally pairs a scene fragment shader with the vertex
     * shader of the same name. The two intentional exceptions are
     * debug_point -> position_color and screenquad -> blit_screen. Keep the
     * complete vertex set explicit so a resource reload cannot activate a
     * material generation which will later fail when either exception is
     * compiled.
     */
    private static final Set<String> VANILLA_SCENE_VERTEX_SHADERS = Set.of(
            "core/block",
            "core/debug_point",
            "core/entity",
            "core/glint",
            "core/gui",
            "core/item",
            "core/panorama",
            "core/particle",
            "core/position",
            "core/position_color",
            "core/position_tex",
            "core/position_tex_color",
            "core/rendertype_beacon_beam",
            "core/rendertype_clouds",
            "core/rendertype_crumbling",
            "core/rendertype_end_portal",
            "core/rendertype_entity_shadow",
            "core/rendertype_leash",
            "core/rendertype_lightning",
            "core/rendertype_lines",
            "core/rendertype_outline",
            "core/rendertype_water_mask",
            "core/rendertype_world_border",
            "core/screenquad",
            "core/sky",
            "core/stars",
            "core/terrain",
            "core/text",
            "core/text_background"
    );

    private static final Set<String> VANILLA_SCENE_POST_SHADERS = Set.of(
            "post/bits",
            "post/blit",
            "post/box_blur",
            "post/color_convolve",
            "post/entity_outline_box_blur",
            "post/entity_sobel",
            "post/invert",
            "post/spiderclip",
            "post/transparency"
    );

    private static final Set<String> VANILLA_SCENE_POST_VERTEX_SHADERS = Set.of(
            "post/rotscale"
    );

    private static final Set<String> SODIUM_SCENE_PIPELINES = Set.of(
            "pipeline/solid_terrain",
            "pipeline/cutout_terrain",
            "pipeline/translucent_terrain"
    );

    private HdrPipelinePolicy() {
    }

    static Set<String> requiredVanillaRasterFragmentShaders() {
        return VANILLA_SCENE_FRAGMENT_SHADERS;
    }

    static Set<String> requiredVanillaRasterVertexShaders() {
        return VANILLA_SCENE_VERTEX_SHADERS;
    }

    static Set<String> requiredVanillaPostFragmentShaders() {
        return VANILLA_SCENE_POST_SHADERS;
    }

    static Set<String> requiredVanillaPostVertexShaders() {
        return VANILLA_SCENE_POST_VERTEX_SHADERS;
    }

    public static Role classify(
            final String pipelineNamespace,
            final String pipelinePath,
            final String vertexShaderNamespace,
            final String vertexShaderPath,
            final String fragmentShaderNamespace,
            final String fragmentShaderPath
    ) {
        if ("minecraft".equals(pipelineNamespace)
                && "pipeline/lightmap".equals(pipelinePath)
                && "minecraft".equals(fragmentShaderNamespace)
                && "core/lightmap".equals(fragmentShaderPath)) {
            return Role.LIGHTMAP_DATA;
        }
        if ("minecraft".equals(pipelineNamespace)
                && ("pipeline/animate_sprite_blit".equals(pipelinePath)
                || "pipeline/animate_sprite_interpolate".equals(pipelinePath))) {
            return Role.LIGHTMAP_DATA;
        }

        if ("minecraft".equals(pipelineNamespace)
                && "minecraft".equals(vertexShaderNamespace)
                && "minecraft".equals(fragmentShaderNamespace)) {
            if (VANILLA_SCENE_POST_SHADERS.contains(fragmentShaderPath)) {
                return Role.SCENE_POST;
            }
            if (VANILLA_SCENE_FRAGMENT_SHADERS.contains(fragmentShaderPath)) {
                return Role.SCENE_RASTER;
            }
        }

        if ("sodium".equals(pipelineNamespace)
                && SODIUM_SCENE_PIPELINES.contains(pipelinePath)
                && "sodium".equals(vertexShaderNamespace)
                && "blocks/block_layer_opaque".equals(vertexShaderPath)
                && "sodium".equals(fragmentShaderNamespace)
                && "blocks/block_layer_opaque".equals(fragmentShaderPath)) {
            return Role.SCENE_RASTER;
        }

        return Role.UNKNOWN;
    }

    public static HdrShaderFlavor selectFlavor(
            final Role role,
            final boolean sceneHdrRequested,
            final boolean rgba16FloatAttachment
    ) {
        if (!sceneHdrRequested || !rgba16FloatAttachment) {
            return HdrShaderFlavor.LEGACY;
        }
        return role.sceneLinearFlavor();
    }
}
