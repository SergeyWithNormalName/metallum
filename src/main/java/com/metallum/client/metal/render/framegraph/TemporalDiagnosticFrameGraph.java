package com.metallum.client.metal.render.framegraph;

import com.metallum.client.metal.render.bridge.MetalNativeBridge;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/** Isolated camera/static-depth diagnostic graph; never a production motion declaration. */
public final class TemporalDiagnosticFrameGraph {
    public static final String GRAPH_ID = "temporal-camera-motion-diagnostic-v1";

    private static final FrameGraph.PassId PASS = new FrameGraph.PassId(0, "temporal_camera_motion_diagnostic");
    private static final FrameGraph.ResourceId DEPTH = new FrameGraph.ResourceId(0, "main_depth");
    private static final FrameGraph.ResourceId MOTION = new FrameGraph.ResourceId(1, "diagnostic_motion");
    private static final FrameGraph.ResourceId REACTIVE = new FrameGraph.ResourceId(2, "diagnostic_reactive");
    private static final FrameGraph.Lifetime LIFETIME = FrameGraph.Lifetime.closed(PASS, PASS);
    private static final FrameGraph GRAPH = create();
    private static boolean initialized;

    private TemporalDiagnosticFrameGraph() {
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
                throw new IllegalStateException("Temporal diagnostic frame graph failed ABI validation: " + status);
            }
        }
        initialized = true;
    }

    private static FrameGraph create() {
        FrameGraph.AttachmentContract output = FrameGraph.AttachmentContract.attachment(
                FrameGraph.AttachmentRole.COLOR,
                FrameGraph.LoadAction.DONT_CARE,
                FrameGraph.StoreAction.STORE
        );
        return FrameGraph.validated(
                List.of(
                        new FrameGraph.ResourceDesc(
                                DEPTH,
                                FrameGraph.PersistenceClass.EXTERNAL_FRAME,
                                new FrameGraph.ResourceShape(FrameGraph.ResourceType.TEXTURE, "depth32_float", "render_extent"),
                                true,
                                LIFETIME,
                                FrameGraph.ResourceRole.DEPTH
                        ),
                        new FrameGraph.ResourceDesc(
                                MOTION,
                                FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                new FrameGraph.ResourceShape(FrameGraph.ResourceType.TEXTURE, "rg16_float", "render_extent"),
                                false,
                                LIFETIME,
                                FrameGraph.ResourceRole.MOTION
                        ),
                        new FrameGraph.ResourceDesc(
                                REACTIVE,
                                FrameGraph.PersistenceClass.IN_FLIGHT_FRAME,
                                new FrameGraph.ResourceShape(FrameGraph.ResourceType.TEXTURE, "r8_unorm", "render_extent"),
                                false,
                                LIFETIME,
                                FrameGraph.ResourceRole.REACTIVE_MASK
                        )
                ),
                List.of(new FrameGraph.PassDesc(
                        PASS,
                        FrameGraph.EncoderClass.RENDER,
                        List.of(),
                        List.of(
                                new FrameGraph.ResourceAccess(DEPTH, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                new FrameGraph.ResourceAccess(MOTION, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.FRAGMENT, output),
                                new FrameGraph.ResourceAccess(REACTIVE, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.FRAGMENT, output)
                        )
                ))
        );
    }
}
