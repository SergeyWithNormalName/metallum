package com.metallum.client.metal.render.framegraph;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.renderer.MetalCapabilities;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Versioned L3 upload, cluster-build and forward-lighting dependency contract. */
public final class AdvancedLightingFrameGraph {
    public static final String GRAPH_ID = "advanced-clustered-lighting-v1";

    private static final FrameGraph.PassId UPLOAD = new FrameGraph.PassId(0, "light_upload");
    private static final FrameGraph.PassId PREPARE = new FrameGraph.PassId(1, "cluster_prepare");
    private static final FrameGraph.PassId MASK_BUILD = new FrameGraph.PassId(2, "cluster_masks");
    private static final FrameGraph.PassId DIRECT = new FrameGraph.PassId(3, "direct_lighting");

    private static final FrameGraph.ResourceId UPLOAD_RING = resource(0, "lighting_upload_ring");
    private static final FrameGraph.ResourceId PARAMS = resource(1, "lighting_params");
    private static final FrameGraph.ResourceId LIGHTS = resource(2, "gpu_lights");
    private static final FrameGraph.ResourceId MEMBERSHIP_MASKS = resource(
            3, "cluster_membership_masks");
    private static final FrameGraph.ResourceId BLOCK_STATISTICS = resource(
            4, "cluster_block_statistics");
    private static final FrameGraph.ResourceId STATISTICS = resource(5, "cluster_statistics");
    private static final FrameGraph.ResourceId SCENE = resource(6, "scene_radiance");
    private static final FrameGraph GRAPH = create();
    private static boolean initialized;

    private AdvancedLightingFrameGraph() {
    }

    public static FrameGraph graph() {
        return GRAPH;
    }

    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = FrameGraphAbi.encode(
                    GRAPH,
                    FrameGraphAbi.CAPABILITY_TYPED_ATTACHMENTS,
                    arena
            );
            int status = MetalNativeBridge.metallum_validate_frame_graph_v1(packet);
            if (status != 1) {
                throw new IllegalStateException(
                        "Advanced lighting frame graph failed ABI validation: " + status
                );
            }
        }
        initialized = true;
    }

    private static FrameGraph create() {
        FrameGraph.Lifetime whole = FrameGraph.Lifetime.wholeGraph();
        FrameGraph.PassContract contract = new FrameGraph.PassContract(
                Set.of(MetalCapabilities.Feature.ADVANCED_LIGHTING),
                Set.of(),
                new FrameGraph.PassImplementation(
                        "metal3-clustered-forward-v1",
                        FrameGraph.ImplementationTarget.METAL3
                ),
                Optional.empty(),
                FrameGraph.OutputApplicability.ANY,
                FrameGraph.RenderContractApplicability.METALLUM_ONLY,
                FrameGraph.LightingModelApplicability.ADVANCED_ONLY,
                FrameGraph.PresentationUiContract.NOT_PRESENTATION
        );
        FrameGraph.AttachmentContract sceneAttachment = FrameGraph.AttachmentContract.attachment(
                FrameGraph.AttachmentRole.COLOR,
                FrameGraph.LoadAction.LOAD,
                FrameGraph.StoreAction.STORE
        );
        return FrameGraph.validated(
                List.of(
                        buffer(UPLOAD_RING, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "lighting_batch_v1", true, whole, FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(PARAMS, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "lighting_params_v1", false, whole, FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(LIGHTS, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "gpu_light_v1", false, whole, FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(MEMBERSHIP_MASKS, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "cluster_membership_mask_v1", false, whole,
                                FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(BLOCK_STATISTICS, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "cluster_block_statistics_v1", false, whole,
                                FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(STATISTICS, FrameGraph.PersistenceClass.READBACK,
                                "cluster_statistics_v1", false, whole, FrameGraph.ResourceRole.CLUSTER_DATA),
                        new FrameGraph.ResourceDesc(
                                SCENE,
                                FrameGraph.PersistenceClass.EXTERNAL_FRAME,
                                new FrameGraph.ResourceShape(
                                        FrameGraph.ResourceType.TEXTURE,
                                        "generation_scene_color",
                                        "render_extent"
                                ),
                                true,
                                whole,
                                FrameGraph.ResourceRole.SCENE_RADIANCE
                        )
                ),
                List.of(
                        new FrameGraph.PassDesc(
                                UPLOAD,
                                FrameGraph.EncoderClass.BLIT,
                                List.of(),
                                List.of(
                                        access(UPLOAD_RING, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.BLIT),
                                        access(LIGHTS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.BLIT)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                PREPARE,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(UPLOAD),
                                List.of(
                                        access(PARAMS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(LIGHTS, FrameGraph.AccessKind.READ_WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(STATISTICS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                MASK_BUILD,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(PREPARE),
                                List.of(
                                        access(PARAMS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(LIGHTS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(MEMBERSHIP_MASKS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(BLOCK_STATISTICS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(STATISTICS, FrameGraph.AccessKind.READ_WRITE,
                                                FrameGraph.PipelineStage.COMPUTE)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                DIRECT,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(MASK_BUILD),
                                List.of(
                                        access(PARAMS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(LIGHTS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(MEMBERSHIP_MASKS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        new FrameGraph.ResourceAccess(
                                                SCENE,
                                                FrameGraph.AccessKind.READ_WRITE,
                                                FrameGraph.PipelineStage.FRAGMENT,
                                                sceneAttachment
                                        )
                                ),
                                contract
                        )
                )
        );
    }

    private static FrameGraph.ResourceId resource(final int id, final String name) {
        return new FrameGraph.ResourceId(id, name);
    }

    private static FrameGraph.ResourceDesc buffer(
            final FrameGraph.ResourceId id,
            final FrameGraph.PersistenceClass persistence,
            final String format,
            final boolean initiallyDefined,
            final FrameGraph.Lifetime lifetime,
            final FrameGraph.ResourceRole role
    ) {
        return new FrameGraph.ResourceDesc(
                id,
                persistence,
                new FrameGraph.ResourceShape(FrameGraph.ResourceType.BUFFER, format,
                        "generation_capacity"),
                initiallyDefined,
                lifetime,
                role
        );
    }

    private static FrameGraph.ResourceAccess access(
            final FrameGraph.ResourceId id,
            final FrameGraph.AccessKind kind,
            final FrameGraph.PipelineStage stage
    ) {
        return new FrameGraph.ResourceAccess(id, kind, stage);
    }
}
