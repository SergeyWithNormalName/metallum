package com.metallum.client.metal.render.framegraph;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.renderer.MetalCapabilities;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Versioned L3-L6 clustered, cached-shadow and voxel-visibility contract. */
public final class AdvancedLightingFrameGraph {
    public static final String GRAPH_ID = "advanced-clustered-resident-shadow-voxel-v5";

    private static final FrameGraph.PassId UPLOAD = new FrameGraph.PassId(0, "light_upload");
    private static final FrameGraph.PassId PREPARE = new FrameGraph.PassId(1, "cluster_prepare");
    private static final FrameGraph.PassId CLUSTER_BUILD = new FrameGraph.PassId(2, "cluster_build");
    private static final FrameGraph.PassId VOXEL_UPLOAD = new FrameGraph.PassId(3, "voxel_upload");
    private static final FrameGraph.PassId VOXEL_UPDATE = new FrameGraph.PassId(4, "voxel_update");
    private static final FrameGraph.PassId SUN_STATIC_REFRESH = new FrameGraph.PassId(
            5, "sun_shadow_static_refresh");
    private static final FrameGraph.PassId SUN_STATIC_COPY = new FrameGraph.PassId(
            6, "sun_shadow_static_copy");
    private static final FrameGraph.PassId SUN_DYNAMIC = new FrameGraph.PassId(
            7, "sun_shadow_dynamic");
    private static final FrameGraph.PassId DIRECT = new FrameGraph.PassId(8, "direct_lighting");

    private static final FrameGraph.ResourceId UPLOAD_RING = resource(0, "lighting_upload_ring");
    private static final FrameGraph.ResourceId PARAMS = resource(1, "lighting_params");
    private static final FrameGraph.ResourceId LIGHTS = resource(2, "gpu_lights");
    private static final FrameGraph.ResourceId MEMBERSHIP_SCRATCH = resource(
            3, "cluster_membership_scratch");
    private static final FrameGraph.ResourceId COMPACT_HEADERS = resource(
            4, "cluster_compact_headers");
    private static final FrameGraph.ResourceId COMPACT_INDICES = resource(
            5, "cluster_compact_indices");
    private static final FrameGraph.ResourceId STATISTICS = resource(6, "cluster_statistics");
    private static final FrameGraph.ResourceId ENVIRONMENT = resource(
            7, "environment_shadow_params_ring");
    private static final FrameGraph.ResourceId SUN_STATIC_DEPTH = resource(
            8, "sun_shadow_static_cascades");
    private static final FrameGraph.ResourceId SUN_WORKING_DEPTH = resource(
            9, "sun_shadow_working_cascades");
    private static final FrameGraph.ResourceId SCENE = resource(10, "scene_radiance");
    private static final FrameGraph.ResourceId VOXEL_UPLOAD_RING = resource(
            11, "voxel_upload_ring");
    private static final FrameGraph.ResourceId VOXEL_PRIVATE_PATCH_RING = resource(
            12, "voxel_private_patch_ring");
    private static final FrameGraph.ResourceId VOXEL_INDIRECT_ARGS = resource(
            13, "voxel_indirect_args");
    private static final FrameGraph.ResourceId VOXEL_OCCUPANCY = resource(
            14, "voxel_occupancy");
    private static final FrameGraph.ResourceId VOXEL_OPTICAL = resource(
            15, "voxel_transmittance_material");
    private static final FrameGraph.ResourceId VOXEL_TAGS = resource(
            16, "voxel_brick_tags");
    private static final FrameGraph.ResourceId LOCAL_SHADOW_PARAMS = resource(
            17, "local_shadow_params_ring");
    private static final FrameGraph.ResourceId ENTITY_SHADOW_PROXIES = resource(
            18, "entity_shadow_proxies_ring");
    private static final FrameGraph.ResourceId LOCAL_SHADOW_REFERENCES = resource(
            19, "local_shadow_reference_ring");
    private static final FrameGraph.ResourceId LOCAL_SHADOW_ATLAS = resource(
            20, "local_shadow_visibility_atlas");
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
        FrameGraph.AttachmentContract shadowAttachment = FrameGraph.AttachmentContract.clear(
                FrameGraph.AttachmentRole.DEPTH,
                FrameGraph.StoreAction.STORE,
                "reverse_z_zero"
        );
        FrameGraph.AttachmentContract loadedShadowAttachment = FrameGraph.AttachmentContract.attachment(
                FrameGraph.AttachmentRole.DEPTH,
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
                        buffer(MEMBERSHIP_SCRATCH, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "cluster_membership_scratch_v1", false, whole,
                                FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(COMPACT_HEADERS, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "cluster_header_v1", false, whole,
                                FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(COMPACT_INDICES, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "cluster_light_index_v1", false, whole,
                                FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(STATISTICS, FrameGraph.PersistenceClass.READBACK,
                                "cluster_statistics_v1", false, whole, FrameGraph.ResourceRole.CLUSTER_DATA),
                        buffer(ENVIRONMENT, FrameGraph.PersistenceClass.SIZE_GENERATION,
                                "environment_shadow_params_v1", true, whole,
                                FrameGraph.ResourceRole.SHADOW_DATA),
                        new FrameGraph.ResourceDesc(
                                SUN_STATIC_DEPTH,
                                FrameGraph.PersistenceClass.SIZE_GENERATION,
                                new FrameGraph.ResourceShape(
                                        FrameGraph.ResourceType.TEXTURE,
                                        "d32_float_cascades_2_3",
                                        "lighting_preset_extent"
                                ),
                                false,
                                whole,
                                FrameGraph.ResourceRole.SHADOW_DATA
                        ),
                        new FrameGraph.ResourceDesc(
                                SUN_WORKING_DEPTH,
                                FrameGraph.PersistenceClass.SIZE_GENERATION,
                                new FrameGraph.ResourceShape(
                                        FrameGraph.ResourceType.TEXTURE,
                                        "d32_float_cascades_2_3",
                                        "lighting_preset_extent"
                                ),
                                false,
                                whole,
                                FrameGraph.ResourceRole.SHADOW_DATA
                        ),
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
                        ),
                        buffer(VOXEL_UPLOAD_RING, FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                "voxel_batch_v1", true, whole,
                                FrameGraph.ResourceRole.VOXEL_DATA),
                        buffer(VOXEL_PRIVATE_PATCH_RING,
                                FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                "voxel_patch_v1", false, whole,
                                FrameGraph.ResourceRole.VOXEL_DATA),
                        buffer(VOXEL_INDIRECT_ARGS, FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                "dispatch_threadgroups_indirect_v1", false, whole,
                                FrameGraph.ResourceRole.VOXEL_DATA),
                        buffer(VOXEL_OCCUPANCY, FrameGraph.PersistenceClass.WORLD_PERSISTENT,
                                "voxel_occupancy_bits_v1", true, whole,
                                FrameGraph.ResourceRole.VOXEL_DATA),
                        buffer(VOXEL_OPTICAL, FrameGraph.PersistenceClass.WORLD_PERSISTENT,
                                "voxel_optical_material_v1", true, whole,
                                FrameGraph.ResourceRole.VOXEL_DATA),
                        buffer(VOXEL_TAGS, FrameGraph.PersistenceClass.WORLD_PERSISTENT,
                                "voxel_brick_tag_v1", true, whole,
                                FrameGraph.ResourceRole.VOXEL_DATA),
                        buffer(LOCAL_SHADOW_PARAMS, FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                "local_voxel_shadow_params_v1", true, whole,
                                FrameGraph.ResourceRole.SHADOW_DATA),
                        buffer(ENTITY_SHADOW_PROXIES, FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                "entity_shadow_proxy_v1", true, whole,
                                FrameGraph.ResourceRole.SHADOW_DATA),
                        buffer(LOCAL_SHADOW_REFERENCES,
                                FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                "local_shadow_reference_v1", true, whole,
                                FrameGraph.ResourceRole.SHADOW_DATA),
                        buffer(LOCAL_SHADOW_ATLAS,
                                FrameGraph.PersistenceClass.WORLD_PERSISTENT,
                                "local_shadow_atlas_hits_v1", true, whole,
                                FrameGraph.ResourceRole.SHADOW_DATA)
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
                                CLUSTER_BUILD,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(PREPARE),
                                List.of(
                                        access(PARAMS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(LIGHTS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(MEMBERSHIP_SCRATCH, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(COMPACT_HEADERS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(COMPACT_INDICES, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(STATISTICS, FrameGraph.AccessKind.READ_WRITE,
                                                FrameGraph.PipelineStage.COMPUTE)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                VOXEL_UPLOAD,
                                FrameGraph.EncoderClass.BLIT,
                                List.of(),
                                List.of(
                                        access(VOXEL_UPLOAD_RING, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.BLIT),
                                        access(VOXEL_PRIVATE_PATCH_RING,
                                                FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.BLIT),
                                        access(VOXEL_INDIRECT_ARGS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.BLIT)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                VOXEL_UPDATE,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(VOXEL_UPLOAD),
                                List.of(
                                        access(VOXEL_PRIVATE_PATCH_RING,
                                                FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(VOXEL_INDIRECT_ARGS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(VOXEL_OCCUPANCY, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(VOXEL_OPTICAL, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE),
                                        access(VOXEL_TAGS, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.COMPUTE)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                SUN_STATIC_REFRESH,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(),
                                List.of(
                                        access(ENVIRONMENT, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.VERTEX),
                                        new FrameGraph.ResourceAccess(
                                                SUN_STATIC_DEPTH,
                                                FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.FRAGMENT,
                                                shadowAttachment
                                        )
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                SUN_STATIC_COPY,
                                FrameGraph.EncoderClass.BLIT,
                                List.of(SUN_STATIC_REFRESH),
                                List.of(
                                        access(SUN_STATIC_DEPTH, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.BLIT),
                                        access(SUN_WORKING_DEPTH, FrameGraph.AccessKind.WRITE,
                                                FrameGraph.PipelineStage.BLIT)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                SUN_DYNAMIC,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(SUN_STATIC_COPY),
                                List.of(
                                        access(ENVIRONMENT, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.VERTEX),
                                        new FrameGraph.ResourceAccess(
                                                SUN_WORKING_DEPTH,
                                                FrameGraph.AccessKind.READ_WRITE,
                                                FrameGraph.PipelineStage.FRAGMENT,
                                                loadedShadowAttachment
                                        )
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                DIRECT,
                                FrameGraph.EncoderClass.RENDER,
                                List.of(CLUSTER_BUILD, VOXEL_UPDATE, SUN_DYNAMIC),
                                List.of(
                                        access(PARAMS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(LIGHTS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(COMPACT_HEADERS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(COMPACT_INDICES, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(ENVIRONMENT, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(SUN_WORKING_DEPTH, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(VOXEL_OCCUPANCY, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(VOXEL_OPTICAL, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(VOXEL_TAGS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(LOCAL_SHADOW_PARAMS, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(ENTITY_SHADOW_PROXIES, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(LOCAL_SHADOW_REFERENCES, FrameGraph.AccessKind.READ,
                                                FrameGraph.PipelineStage.FRAGMENT),
                                        access(LOCAL_SHADOW_ATLAS, FrameGraph.AccessKind.READ,
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
