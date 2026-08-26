package com.metallum.client.metal.render.framegraph;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.renderer.MetalCapabilities;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** G4-only frozen near-cascade transport graph; every output remains private. */
public final class GiTransportFrameGraph {
    public static final String GRAPH_ID = "gi-g4-frozen-transport-v1";

    private static final FrameGraph.ResourceId G2_TRANSPORT_CELLS =
            resource(0, "gi_g2_transport_cells");
    private static final FrameGraph.ResourceId G3_GEOMETRY =
            resource(1, "gi_g3_geometry_state");
    private static final FrameGraph.ResourceId G3_DIRECT_IRRADIANCE =
            resource(2, "gi_g3_direct_irradiance");
    private static final FrameGraph.ResourceId BOUNCE0_PING_SOURCE =
            resource(3, "gi_bounce0_ping_source");
    private static final FrameGraph.ResourceId INDIRECT_SH_R =
            resource(4, "gi_indirect_sh_r");
    private static final FrameGraph.ResourceId INDIRECT_SH_G =
            resource(5, "gi_indirect_sh_g");
    private static final FrameGraph.ResourceId INDIRECT_SH_B =
            resource(6, "gi_indirect_sh_b");
    private static final FrameGraph.ResourceId CONFIDENCE =
            resource(7, "gi_transport_confidence");

    private static final FrameGraph.PassId BOUNCE_INIT =
            new FrameGraph.PassId(0, "gi_bounce_init");
    private static final FrameGraph.PassId TRANSPORT_SH =
            new FrameGraph.PassId(1, "gi_jacobi_transport_sh");
    private static final FrameGraph GRAPH = create();
    private static boolean initialized;

    private GiTransportFrameGraph() {
    }

    public static FrameGraph graph() {
        return GRAPH;
    }

    public static synchronized void initialize() {
        if (initialized) return;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = FrameGraphAbi.encode(
                    GRAPH, FrameGraphAbi.CAPABILITY_TYPED_ATTACHMENTS, arena);
            int status = MetalNativeBridge.metallum_validate_frame_graph_v1(packet);
            if (status != 1) {
                throw new IllegalStateException("G4 transport frame graph validation failed: " + status);
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
                        "metal3-gi-frozen-transport-v1", FrameGraph.ImplementationTarget.METAL3),
                Optional.empty(),
                FrameGraph.OutputApplicability.ANY,
                FrameGraph.RenderContractApplicability.METALLUM_ONLY,
                FrameGraph.LightingModelApplicability.ADVANCED_ONLY,
                FrameGraph.PresentationUiContract.NOT_PRESENTATION
        );
        return FrameGraph.validated(
                List.of(
                        resource(G2_TRANSPORT_CELLS, FrameGraph.ResourceType.BUFFER,
                                "gi_transport_cell_v1", true, whole),
                        resource(G3_GEOMETRY, FrameGraph.ResourceType.TEXTURE,
                                "r8_uint", true, whole),
                        resource(G3_DIRECT_IRRADIANCE, FrameGraph.ResourceType.TEXTURE,
                                "rgba16_float", true, whole),
                        resource(BOUNCE0_PING_SOURCE, FrameGraph.ResourceType.TEXTURE,
                                "rgba16_float", false, whole),
                        resource(INDIRECT_SH_R, FrameGraph.ResourceType.TEXTURE,
                                "rgba16_float", false, whole),
                        resource(INDIRECT_SH_G, FrameGraph.ResourceType.TEXTURE,
                                "rgba16_float", false, whole),
                        resource(INDIRECT_SH_B, FrameGraph.ResourceType.TEXTURE,
                                "rgba16_float", false, whole),
                        resource(CONFIDENCE, FrameGraph.ResourceType.TEXTURE,
                                "r8_unorm", false, whole)
                ),
                List.of(
                        new FrameGraph.PassDesc(
                                BOUNCE_INIT,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(),
                                List.of(
                                        access(G2_TRANSPORT_CELLS, FrameGraph.AccessKind.READ),
                                        access(G3_GEOMETRY, FrameGraph.AccessKind.READ),
                                        access(G3_DIRECT_IRRADIANCE, FrameGraph.AccessKind.READ),
                                        access(BOUNCE0_PING_SOURCE, FrameGraph.AccessKind.WRITE)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                TRANSPORT_SH,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(BOUNCE_INIT),
                                List.of(
                                        access(G2_TRANSPORT_CELLS, FrameGraph.AccessKind.READ),
                                        access(G3_GEOMETRY, FrameGraph.AccessKind.READ),
                                        access(BOUNCE0_PING_SOURCE, FrameGraph.AccessKind.READ),
                                        access(INDIRECT_SH_R, FrameGraph.AccessKind.WRITE),
                                        access(INDIRECT_SH_G, FrameGraph.AccessKind.WRITE),
                                        access(INDIRECT_SH_B, FrameGraph.AccessKind.WRITE),
                                        access(CONFIDENCE, FrameGraph.AccessKind.WRITE)
                                ),
                                contract
                        )
                )
        );
    }

    private static FrameGraph.ResourceDesc resource(
            final FrameGraph.ResourceId id,
            final FrameGraph.ResourceType type,
            final String format,
            final boolean initiallyDefined,
            final FrameGraph.Lifetime lifetime
    ) {
        return new FrameGraph.ResourceDesc(
                id,
                FrameGraph.PersistenceClass.WORLD_PERSISTENT,
                new FrameGraph.ResourceShape(
                        type, format, "frozen_near_cascade_32x32x32"),
                initiallyDefined,
                lifetime,
                FrameGraph.ResourceRole.GENERIC
        );
    }

    private static FrameGraph.ResourceAccess access(
            final FrameGraph.ResourceId resource,
            final FrameGraph.AccessKind kind
    ) {
        return new FrameGraph.ResourceAccess(resource, kind, FrameGraph.PipelineStage.COMPUTE);
    }

    private static FrameGraph.ResourceId resource(final int id, final String name) {
        return new FrameGraph.ResourceId(id, name);
    }
}
