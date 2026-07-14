package com.metallum.client.metal.render.framegraph;

import com.metallum.Metallum;

import java.io.IOException;
import java.util.List;

/**
 * Steady-state native-resolution Enhanced-HDR path with MetalFX disabled.
 *
 * <p>WORLD_RENDER and UI_RENDER are logical groups because the legacy backend
 * may reuse an encoder across multiple matching Minecraft render passes. Their
 * attachment contract records the first clear/load requirement and final
 * store, while fixed post-processing passes map one-to-one to native encoders.
 * The one-time histogram initialization blit is outside this steady-state
 * graph; an initialized histogram is therefore an imported history value.
 * This route has a semantic mask, so the combined bloom pass is present.</p>
 */
public final class NativeHdrFrameGraph {
    public static final String GRAPH_ID = "native-hdr-enhanced-metalfx-off-v2";

    private static final FrameGraph.PassId WORLD_RENDER = pass(0, "world_render");
    private static final FrameGraph.PassId CAPTURE_SCENE_DEPTH = pass(1, "scene_depth_snapshot");
    private static final FrameGraph.PassId HDR_EXTRACT = pass(2, "hdr_extract");
    private static final FrameGraph.PassId HDR_EXPOSURE_REDUCE = pass(3, "hdr_exposure_reduce");
    private static final FrameGraph.PassId HDR_BLOOM_COMBINED = pass(4, "hdr_bloom_combined");
    private static final FrameGraph.PassId HDR_WORLD_UI_SEED = pass(5, "hdr_world_ui_seed");
    private static final FrameGraph.PassId UI_RENDER = pass(6, "ui_render");
    private static final FrameGraph.PassId PRESENT = pass(7, "present");

    private static final FrameGraph.ResourceId MAIN_COLOR = resource(0, "main_color");
    private static final FrameGraph.ResourceId MAIN_DEPTH = resource(1, "main_depth");
    private static final FrameGraph.ResourceId HDR_SEMANTIC = resource(2, "hdr_semantic");
    private static final FrameGraph.ResourceId SCENE_DEPTH_SNAPSHOT = resource(3, "scene_depth_snapshot");
    private static final FrameGraph.ResourceId HDR_EMISSION = resource(4, "hdr_emission");
    private static final FrameGraph.ResourceId HDR_BLOOM = resource(5, "hdr_bloom");
    private static final FrameGraph.ResourceId HDR_HISTOGRAM = resource(6, "hdr_histogram");
    private static final FrameGraph.ResourceId HDR_ADAPTIVE_STATE = resource(7, "hdr_adaptive_state");
    private static final FrameGraph.ResourceId HDR_WORLD_COMPOSITE = resource(8, "hdr_world_composite");
    private static final FrameGraph.ResourceId SDR_UI_COLOR = resource(9, "sdr_ui_color");
    private static final FrameGraph.ResourceId SDR_UI_DEPTH = resource(10, "sdr_ui_depth");
    private static final FrameGraph.ResourceId DRAWABLE = resource(11, "drawable");

    private static final FrameGraph GRAPH = createGraph();
    private static boolean initialized;

    private NativeHdrFrameGraph() {
    }

    public static FrameGraph graph() {
        return GRAPH;
    }

    /** Validates on class initialization and writes optional diagnostics once. */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            FrameGraphDiagnostics.writeConfigured(GRAPH);
        } catch (IOException | RuntimeException exception) {
            Metallum.LOGGER.warn("Failed to write optional Metal frame graph diagnostics", exception);
        }
    }

    private static FrameGraph createGraph() {
        return FrameGraph.validated(
                List.of(
                        texture(MAIN_COLOR, "rgba16_float", "render_extent", false,
                                lifetime(WORLD_RENDER, HDR_WORLD_UI_SEED)),
                        texture(MAIN_DEPTH, "depth32_float", "render_extent", false,
                                lifetime(WORLD_RENDER, CAPTURE_SCENE_DEPTH)),
                        texture(HDR_SEMANTIC, "rgba8_unorm", "render_extent", false,
                                lifetime(WORLD_RENDER, HDR_WORLD_UI_SEED)),
                        texture(SCENE_DEPTH_SNAPSHOT, "depth32_float", "render_extent", false,
                                lifetime(CAPTURE_SCENE_DEPTH, HDR_WORLD_UI_SEED)),
                        texture(HDR_EMISSION, "rgba16_float", "quarter_render_extent", false,
                                lifetime(HDR_EXTRACT, HDR_WORLD_UI_SEED)),
                        texture(HDR_BLOOM, "rgba16_float", "quarter_render_extent", false,
                                lifetime(HDR_BLOOM_COMBINED, HDR_WORLD_UI_SEED)),
                        buffer(HDR_HISTOGRAM, "atomic_uint_bins", "histogram_bin_count", true,
                                lifetime(HDR_EXTRACT, HDR_EXPOSURE_REDUCE),
                                FrameGraph.PersistenceClass.SIZE_GENERATION),
                        buffer(HDR_ADAPTIVE_STATE, "adaptive_exposure_state", "one_record", true,
                                FrameGraph.Lifetime.wholeGraph(), FrameGraph.PersistenceClass.HISTORY),
                        texture(HDR_WORLD_COMPOSITE, "rgba16_float", "display_extent", false,
                                lifetime(HDR_WORLD_UI_SEED, PRESENT)),
                        texture(SDR_UI_COLOR, "rgba8_unorm", "display_extent", false,
                                lifetime(HDR_WORLD_UI_SEED, PRESENT)),
                        texture(SDR_UI_DEPTH, "depth32_float", "display_extent", false,
                                lifetime(UI_RENDER, UI_RENDER)),
                        new FrameGraph.ResourceDesc(
                                DRAWABLE,
                                FrameGraph.PersistenceClass.EXTERNAL_FRAME,
                                new FrameGraph.ResourceShape(
                                        FrameGraph.ResourceType.TEXTURE,
                                        "layer_pixel_format",
                                        "display_extent"
                                ),
                                false,
                                lifetime(PRESENT, PRESENT)
                        )
                ),
                List.of(
                        pass(
                                WORLD_RENDER,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(),
                                attachmentWrite(MAIN_COLOR, FrameGraph.AttachmentRole.COLOR,
                                        FrameGraph.LoadAction.CLEAR, "scene_clear"),
                                attachmentWrite(MAIN_DEPTH, FrameGraph.AttachmentRole.DEPTH,
                                        FrameGraph.LoadAction.CLEAR, "world_depth_clear"),
                                attachmentWrite(HDR_SEMANTIC, FrameGraph.AttachmentRole.COLOR,
                                        FrameGraph.LoadAction.CLEAR, "0,0,0,0")
                        ),
                        pass(
                                CAPTURE_SCENE_DEPTH,
                                FrameGraph.EncoderClass.BLIT,
                                List.of(WORLD_RENDER),
                                access(MAIN_DEPTH, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.BLIT),
                                access(SCENE_DEPTH_SNAPSHOT, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.BLIT)
                        ),
                        pass(
                                HDR_EXTRACT,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(CAPTURE_SCENE_DEPTH),
                                access(MAIN_COLOR, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(SCENE_DEPTH_SNAPSHOT, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(HDR_SEMANTIC, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                attachmentWrite(HDR_EMISSION, FrameGraph.AttachmentRole.COLOR,
                                        FrameGraph.LoadAction.DONT_CARE, null),
                                access(HDR_HISTOGRAM, FrameGraph.AccessKind.READ_WRITE, FrameGraph.PipelineStage.FRAGMENT)
                        ),
                        pass(
                                HDR_EXPOSURE_REDUCE,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(HDR_EXTRACT),
                                access(HDR_HISTOGRAM, FrameGraph.AccessKind.READ_WRITE, FrameGraph.PipelineStage.COMPUTE),
                                access(HDR_ADAPTIVE_STATE, FrameGraph.AccessKind.READ_WRITE, FrameGraph.PipelineStage.COMPUTE)
                        ),
                        pass(
                                HDR_BLOOM_COMBINED,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(HDR_EXTRACT),
                                access(HDR_EMISSION, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.COMPUTE),
                                access(HDR_BLOOM, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.COMPUTE)
                        ),
                        pass(
                                HDR_WORLD_UI_SEED,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(HDR_EXPOSURE_REDUCE, HDR_BLOOM_COMBINED),
                                access(MAIN_COLOR, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(SCENE_DEPTH_SNAPSHOT, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(HDR_SEMANTIC, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(HDR_EMISSION, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(HDR_BLOOM, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(HDR_ADAPTIVE_STATE, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                attachmentWrite(HDR_WORLD_COMPOSITE, FrameGraph.AttachmentRole.COLOR,
                                        FrameGraph.LoadAction.DONT_CARE, null),
                                attachmentWrite(SDR_UI_COLOR, FrameGraph.AttachmentRole.COLOR,
                                        FrameGraph.LoadAction.DONT_CARE, null)
                        ),
                        pass(
                                UI_RENDER,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(HDR_WORLD_UI_SEED),
                                attachmentReadWrite(SDR_UI_COLOR, FrameGraph.AttachmentRole.COLOR),
                                attachmentWrite(SDR_UI_DEPTH, FrameGraph.AttachmentRole.DEPTH,
                                        FrameGraph.LoadAction.CLEAR, "0.0")
                        ),
                        pass(
                                PRESENT,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(UI_RENDER),
                                access(SDR_UI_COLOR, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(HDR_WORLD_COMPOSITE, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                attachmentWrite(DRAWABLE, FrameGraph.AttachmentRole.COLOR,
                                        FrameGraph.LoadAction.DONT_CARE, null)
                        )
                )
        );
    }

    private static FrameGraph.ResourceDesc texture(
            final FrameGraph.ResourceId id,
            final String format,
            final String extent,
            final boolean initiallyDefined,
            final FrameGraph.Lifetime lifetime
    ) {
        return new FrameGraph.ResourceDesc(
                id,
                FrameGraph.PersistenceClass.SIZE_GENERATION,
                new FrameGraph.ResourceShape(FrameGraph.ResourceType.TEXTURE, format, extent),
                initiallyDefined,
                lifetime
        );
    }

    private static FrameGraph.ResourceDesc buffer(
            final FrameGraph.ResourceId id,
            final String format,
            final String extent,
            final boolean initiallyDefined,
            final FrameGraph.Lifetime lifetime,
            final FrameGraph.PersistenceClass persistence
    ) {
        return new FrameGraph.ResourceDesc(
                id,
                persistence,
                new FrameGraph.ResourceShape(FrameGraph.ResourceType.BUFFER, format, extent),
                initiallyDefined,
                lifetime
        );
    }

    private static FrameGraph.PassDesc pass(
            final FrameGraph.PassId id,
            final FrameGraph.EncoderClass encoder,
            final List<FrameGraph.PassId> dependencies,
            final FrameGraph.ResourceAccess... accesses
    ) {
        return new FrameGraph.PassDesc(id, encoder, dependencies, List.of(accesses));
    }

    private static FrameGraph.ResourceAccess access(
            final FrameGraph.ResourceId resource,
            final FrameGraph.AccessKind kind,
            final FrameGraph.PipelineStage stage
    ) {
        return new FrameGraph.ResourceAccess(resource, kind, stage);
    }

    private static FrameGraph.ResourceAccess attachmentWrite(
            final FrameGraph.ResourceId resource,
            final FrameGraph.AttachmentRole role,
            final FrameGraph.LoadAction loadAction,
            final String clearValue
    ) {
        FrameGraph.AttachmentContract attachment = loadAction == FrameGraph.LoadAction.CLEAR
                ? FrameGraph.AttachmentContract.clear(role, FrameGraph.StoreAction.STORE, clearValue)
                : FrameGraph.AttachmentContract.attachment(
                        role,
                        loadAction,
                        FrameGraph.StoreAction.STORE
                );
        return new FrameGraph.ResourceAccess(
                resource,
                FrameGraph.AccessKind.WRITE,
                FrameGraph.PipelineStage.FRAGMENT,
                attachment
        );
    }

    private static FrameGraph.ResourceAccess attachmentReadWrite(
            final FrameGraph.ResourceId resource,
            final FrameGraph.AttachmentRole role
    ) {
        return new FrameGraph.ResourceAccess(
                resource,
                FrameGraph.AccessKind.READ_WRITE,
                FrameGraph.PipelineStage.FRAGMENT,
                FrameGraph.AttachmentContract.attachment(
                        role,
                        FrameGraph.LoadAction.LOAD,
                        FrameGraph.StoreAction.STORE
                )
        );
    }

    private static FrameGraph.PassId pass(final int value, final String name) {
        return new FrameGraph.PassId(value, name);
    }

    private static FrameGraph.ResourceId resource(final int value, final String name) {
        return new FrameGraph.ResourceId(value, name);
    }

    private static FrameGraph.Lifetime lifetime(
            final FrameGraph.PassId first,
            final FrameGraph.PassId last
    ) {
        return FrameGraph.Lifetime.closed(first, last);
    }
}
