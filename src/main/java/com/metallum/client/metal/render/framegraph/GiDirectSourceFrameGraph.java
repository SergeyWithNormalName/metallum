package com.metallum.client.metal.render.framegraph;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.renderer.MetalCapabilities;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** G3-only compute graph; its output is private and has no image-path consumer. */
public final class GiDirectSourceFrameGraph {
    public static final String GRAPH_ID = "gi-g3-direct-source-field-v1";

    private static final FrameGraph.ResourceId HEADER = resource(0, "gi_source_header_ring");
    private static final FrameGraph.ResourceId BRICKS = resource(1, "gi_source_brick_ring");
    private static final FrameGraph.ResourceId CELLS = resource(2, "gi_source_cell_ring");
    private static final FrameGraph.ResourceId SOURCES = resource(3, "gi_static_source_ring");
    private static final FrameGraph.ResourceId GEOMETRY = resource(4, "gi_geometry_state_field");
    private static final FrameGraph.ResourceId DIRECT = resource(5, "gi_direct_irradiance_field");
    private static final FrameGraph.PassId APPLY = new FrameGraph.PassId(0, "gi_geometry_apply");
    private static final FrameGraph.PassId INJECT = new FrameGraph.PassId(1, "gi_source_inject");
    private static final FrameGraph GRAPH = create();
    private static boolean initialized;

    private GiDirectSourceFrameGraph() {
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
                throw new IllegalStateException("G3 direct-source frame graph validation failed: " + status);
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
                        "metal3-gi-direct-source-v1", FrameGraph.ImplementationTarget.METAL3),
                Optional.empty(),
                FrameGraph.OutputApplicability.ANY,
                FrameGraph.RenderContractApplicability.METALLUM_ONLY,
                FrameGraph.LightingModelApplicability.ADVANCED_ONLY,
                FrameGraph.PresentationUiContract.NOT_PRESENTATION
        );
        return FrameGraph.validated(
                List.of(
                        buffer(HEADER, "gi_direct_header_v1", whole),
                        buffer(BRICKS, "gi_direct_brick_v1", whole),
                        buffer(CELLS, "gi_direct_cell_v1", whole),
                        buffer(SOURCES, "gi_direct_static_source_v1", whole),
                        texture(GEOMETRY, "r8_uint_3x32x32x32", whole),
                        texture(DIRECT, "rgba16_float_3x32x32x32", whole)
                ),
                List.of(
                        new FrameGraph.PassDesc(
                                APPLY,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(),
                                List.of(
                                        access(HEADER, FrameGraph.AccessKind.READ),
                                        access(BRICKS, FrameGraph.AccessKind.READ),
                                        access(CELLS, FrameGraph.AccessKind.READ),
                                        access(GEOMETRY, FrameGraph.AccessKind.WRITE)
                                ),
                                contract
                        ),
                        new FrameGraph.PassDesc(
                                INJECT,
                                FrameGraph.EncoderClass.COMPUTE,
                                List.of(APPLY),
                                List.of(
                                        access(HEADER, FrameGraph.AccessKind.READ),
                                        access(BRICKS, FrameGraph.AccessKind.READ),
                                        access(CELLS, FrameGraph.AccessKind.READ),
                                        access(SOURCES, FrameGraph.AccessKind.READ),
                                        access(GEOMETRY, FrameGraph.AccessKind.READ),
                                        access(DIRECT, FrameGraph.AccessKind.WRITE)
                                ),
                                contract
                        )
                )
        );
    }

    private static FrameGraph.ResourceDesc buffer(
            final FrameGraph.ResourceId id,
            final String format,
            final FrameGraph.Lifetime lifetime
    ) {
        return new FrameGraph.ResourceDesc(
                id,
                FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                new FrameGraph.ResourceShape(
                        FrameGraph.ResourceType.BUFFER, format, "g3_fixed_bounded_capacity"),
                true,
                lifetime,
                FrameGraph.ResourceRole.GENERIC
        );
    }

    private static FrameGraph.ResourceDesc texture(
            final FrameGraph.ResourceId id,
            final String format,
            final FrameGraph.Lifetime lifetime
    ) {
        return new FrameGraph.ResourceDesc(
                id,
                FrameGraph.PersistenceClass.WORLD_PERSISTENT,
                new FrameGraph.ResourceShape(
                        FrameGraph.ResourceType.TEXTURE, format, "three_fixed_cascades"),
                true,
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
