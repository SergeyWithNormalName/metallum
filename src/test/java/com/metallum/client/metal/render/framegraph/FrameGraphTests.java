package com.metallum.client.metal.render.framegraph;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;

public final class FrameGraphTests {
    private FrameGraphTests() {
    }

    public static void main(final String[] args) throws Exception {
        testValidWorldHdrUiPresentGraph();
        testReadBeforeWrite();
        testUnorderedWriteConflict();
        testLifetimeViolation();
        testMissingDependencyAndCycle();
        testAttachmentContracts();
        testNativeHdrGraphTopology();
        testAbiHeader();
        testDeterministicDiagnosticsAndGate();
    }

    private static void testValidWorldHdrUiPresentGraph() {
        FrameGraph graph = validGraph();
        require(graph.passes().size() == 4 && graph.resources().size() == 5,
                "valid frame graph changed during construction");
    }

    private static void testReadBeforeWrite() {
        FrameGraph.PassId read = passId(0, "read");
        FrameGraph.ResourceId scratch = resourceId(0, "scratch");
        expectInvalid(
                new FrameGraph(
                        List.of(resource(scratch, false, FrameGraph.Lifetime.wholeGraph())),
                        List.of(pass(read, FrameGraph.EncoderClass.COMPUTE, List.of(),
                                access(scratch, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.COMPUTE)))
                ),
                "read before"
        );
    }

    private static void testUnorderedWriteConflict() {
        FrameGraph.PassId left = passId(0, "left");
        FrameGraph.PassId right = passId(1, "right");
        FrameGraph.ResourceId target = resourceId(0, "target");
        expectInvalid(
                new FrameGraph(
                        List.of(resource(target, false, FrameGraph.Lifetime.wholeGraph())),
                        List.of(
                                pass(left, FrameGraph.EncoderClass.COMPUTE, List.of(),
                                        access(target, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.COMPUTE)),
                                pass(right, FrameGraph.EncoderClass.COMPUTE, List.of(),
                                        access(target, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.COMPUTE))
                        )
                ),
                "unordered conflicting"
        );
    }

    private static void testLifetimeViolation() {
        FrameGraph.PassId first = passId(0, "first");
        FrameGraph.PassId last = passId(1, "last");
        FrameGraph.ResourceId target = resourceId(0, "target");
        expectInvalid(
                new FrameGraph(
                        List.of(resource(target, false, FrameGraph.Lifetime.closed(last, last))),
                        List.of(
                                pass(first, FrameGraph.EncoderClass.BLIT, List.of(),
                                        access(target, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.BLIT)),
                                pass(last, FrameGraph.EncoderClass.BLIT, List.of(first))
                        )
                ),
                "outside its declared lifetime"
        );
    }

    private static void testMissingDependencyAndCycle() {
        FrameGraph.PassId first = passId(0, "first");
        FrameGraph.PassId second = passId(1, "second");
        FrameGraph.PassId missing = passId(9, "missing");
        expectInvalid(
                new FrameGraph(List.of(), List.of(pass(first, FrameGraph.EncoderClass.BLIT, List.of(missing)))),
                "missing pass"
        );
        expectInvalid(
                new FrameGraph(List.of(), List.of(
                        pass(first, FrameGraph.EncoderClass.BLIT, List.of(second)),
                        pass(second, FrameGraph.EncoderClass.BLIT, List.of(first))
                )),
                "cycle"
        );
    }

    private static void testAttachmentContracts() {
        FrameGraph.PassId render = passId(0, "render");
        FrameGraph.ResourceId buffer = resourceId(0, "buffer");
        FrameGraph.ResourceDesc bufferResource = new FrameGraph.ResourceDesc(
                buffer,
                FrameGraph.PersistenceClass.SIZE_GENERATION,
                new FrameGraph.ResourceShape(FrameGraph.ResourceType.BUFFER, "uint", "one_record"),
                false,
                FrameGraph.Lifetime.closed(render, render)
        );
        FrameGraph.AttachmentContract clearColor = FrameGraph.AttachmentContract.clear(
                FrameGraph.AttachmentRole.COLOR,
                FrameGraph.StoreAction.STORE,
                "0,0,0,0"
        );
        expectInvalid(
                new FrameGraph(
                        List.of(bufferResource),
                        List.of(pass(render, FrameGraph.EncoderClass.RENDER, List.of(),
                                new FrameGraph.ResourceAccess(
                                        buffer,
                                        FrameGraph.AccessKind.WRITE,
                                        FrameGraph.PipelineStage.FRAGMENT,
                                        clearColor
                                )))
                ),
                "must be a texture"
        );

        FrameGraph.ResourceId texture = resourceId(1, "texture");
        expectInvalid(
                new FrameGraph(
                        List.of(resource(texture, false, FrameGraph.Lifetime.closed(render, render))),
                        List.of(pass(render, FrameGraph.EncoderClass.RENDER, List.of(),
                                new FrameGraph.ResourceAccess(
                                        texture,
                                        FrameGraph.AccessKind.WRITE,
                                        FrameGraph.PipelineStage.FRAGMENT,
                                        FrameGraph.AttachmentContract.attachment(
                                                FrameGraph.AttachmentRole.COLOR,
                                                FrameGraph.LoadAction.LOAD,
                                                FrameGraph.StoreAction.STORE
                                        )
                                )))
                ),
                "load action"
        );
    }

    private static void testNativeHdrGraphTopology() {
        FrameGraph graph = NativeHdrFrameGraph.graph();
        require(graph.passes().size() == 8, "native HDR graph pass count mismatch");
        require(graph.resources().size() == 12, "native HDR graph resource count mismatch");
        String passNames = graph.passes().stream()
                .map(pass -> pass.id().name())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        require(passNames.equals(
                        "world_render,scene_depth_snapshot,hdr_extract,hdr_exposure_reduce,"
                                + "hdr_bloom_combined,hdr_world_ui_seed,ui_render,present"),
                "native HDR graph pass order mismatch: " + passNames);

        FrameGraph.PassDesc composite = graph.passes().get(5);
        require(composite.encoder() == FrameGraph.EncoderClass.RENDER
                        && composite.dependencies().size() == 2,
                "native HDR composite dependency contract mismatch");
        long compositeAttachments = composite.accesses().stream()
                .filter(access -> access.attachment().isAttachment())
                .filter(access -> access.attachment().loadAction() == FrameGraph.LoadAction.DONT_CARE)
                .filter(access -> access.attachment().storeAction() == FrameGraph.StoreAction.STORE)
                .count();
        require(compositeAttachments == 2L,
                "native HDR MRT output contract must be two dontCare/store attachments");
        require(composite.accesses().stream()
                        .anyMatch(access -> access.resource().name().equals("main_color")
                                && access.kind() == FrameGraph.AccessKind.READ),
                "native HDR result=4 composite must sample the live main color");
        require(graph.resources().stream()
                        .noneMatch(resource -> resource.id().name().equals("scene_color_snapshot")),
                "native HDR result=4 graph retained the removed color snapshot");
        FrameGraph.PassDesc depthSnapshot = graph.passes().get(1);
        require(depthSnapshot.accesses().size() == 2
                        && depthSnapshot.accesses().stream().anyMatch(access ->
                                access.resource().name().equals("main_depth")
                                        && access.kind() == FrameGraph.AccessKind.READ)
                        && depthSnapshot.accesses().stream().anyMatch(access ->
                                access.resource().name().equals("scene_depth_snapshot")
                                        && access.kind() == FrameGraph.AccessKind.WRITE),
                "native HDR snapshot pass must copy depth only");

        FrameGraph.PassDesc ui = graph.passes().get(6);
        FrameGraph.ResourceAccess uiColor = ui.accesses().stream()
                .filter(access -> access.resource().name().equals("sdr_ui_color"))
                .findFirst().orElseThrow();
        require(uiColor.kind() == FrameGraph.AccessKind.READ_WRITE
                        && uiColor.attachment().loadAction() == FrameGraph.LoadAction.LOAD
                        && uiColor.attachment().storeAction() == FrameGraph.StoreAction.STORE,
                "native HDR GUI must load and store the seeded SDR target");

        FrameGraph.PassDesc present = graph.passes().get(7);
        FrameGraph.ResourceAccess drawable = present.accesses().stream()
                .filter(access -> access.resource().name().equals("drawable"))
                .findFirst().orElseThrow();
        require(drawable.attachment().loadAction() == FrameGraph.LoadAction.DONT_CARE
                        && drawable.attachment().storeAction() == FrameGraph.StoreAction.STORE,
                "native HDR drawable contract mismatch");
    }

    private static void testAbiHeader() {
        int bytes = FrameGraphAbi.checkedPacketBytes(3, 24);
        FrameGraphAbi.validate(
                new FrameGraphAbi.Header(FrameGraphAbi.CURRENT_VERSION, bytes, 0b0010L),
                bytes,
                0b0110L
        );
        expectFailure(() -> FrameGraphAbi.validate(
                new FrameGraphAbi.Header(2, bytes, 0L), bytes, 0L), "version");
        expectFailure(() -> FrameGraphAbi.validate(
                new FrameGraphAbi.Header(1, bytes - 1, 0L), bytes, 0L), "byte size");
        expectFailure(() -> FrameGraphAbi.validate(
                new FrameGraphAbi.Header(1, bytes, 0b1000L), bytes, 0b0010L), "capabilities");
        expectFailure(() -> FrameGraphAbi.checkedPacketBytes(Integer.MAX_VALUE, 16), "overflow");
        expectFailure(() -> FrameGraphAbi.packetBytes(Integer.MAX_VALUE, 1, 1), "overflow");

        FrameGraph graph = NativeHdrFrameGraph.graph();
        int accessCount = graph.passes().stream().mapToInt(pass -> pass.accesses().size()).sum();
        int expectedBytes = FrameGraphAbi.packetBytes(
                graph.resources().size(),
                graph.passes().size(),
                accessCount
        );
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = FrameGraphAbi.encode(
                    graph,
                    FrameGraphAbi.CAPABILITY_TYPED_ATTACHMENTS,
                    arena
            );
            require(packet.byteSize() == expectedBytes, "frame graph ABI packet byte size mismatch");
            require(packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_VERSION)
                            == FrameGraphAbi.CURRENT_VERSION
                            && packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_BYTE_SIZE)
                            == expectedBytes
                            && packet.get(ValueLayout.JAVA_LONG, FrameGraphAbi.HEADER_CAPABILITIES)
                            == FrameGraphAbi.CAPABILITY_TYPED_ATTACHMENTS,
                    "frame graph ABI header encoding mismatch");
            require(packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_RESOURCE_COUNT) == 12
                            && packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_PASS_COUNT) == 8
                            && packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_ACCESS_COUNT) == accessCount,
                    "frame graph ABI counts mismatch");

            long firstResource = FrameGraphAbi.HEADER_BYTES;
            require(packet.get(ValueLayout.JAVA_INT, firstResource + FrameGraphAbi.RESOURCE_ID) == 0
                            && packet.get(ValueLayout.JAVA_INT, firstResource + FrameGraphAbi.RESOURCE_TYPE) == 2
                            && packet.get(ValueLayout.JAVA_INT, firstResource + FrameGraphAbi.RESOURCE_PERSISTENCE) == 3,
                    "frame graph ABI resource codes mismatch");
            long firstPass = firstResource + (long) graph.resources().size() * FrameGraphAbi.RESOURCE_BYTES;
            require(packet.get(ValueLayout.JAVA_INT, firstPass + FrameGraphAbi.PASS_ID) == 0
                            && packet.get(ValueLayout.JAVA_INT, firstPass + FrameGraphAbi.PASS_ENCODER) == 1
                            && packet.get(ValueLayout.JAVA_LONG, firstPass + FrameGraphAbi.PASS_DEPENDENCY_MASK) == 0L,
                    "frame graph ABI pass codes mismatch");
            long firstAccess = firstPass + (long) graph.passes().size() * FrameGraphAbi.PASS_BYTES;
            require(packet.get(ValueLayout.JAVA_INT, firstAccess + FrameGraphAbi.ACCESS_RESOURCE_ID) == 0
                            && packet.get(ValueLayout.JAVA_INT, firstAccess + FrameGraphAbi.ACCESS_KIND) == 2
                            && packet.get(ValueLayout.JAVA_INT, firstAccess + FrameGraphAbi.ACCESS_STAGE) == 2
                            && packet.get(ValueLayout.JAVA_INT, firstAccess + FrameGraphAbi.ACCESS_ATTACHMENT_ROLE) == 1
                            && packet.get(ValueLayout.JAVA_INT, firstAccess + FrameGraphAbi.ACCESS_LOAD_ACTION) == 2
                            && packet.get(ValueLayout.JAVA_INT, firstAccess + FrameGraphAbi.ACCESS_STORE_ACTION) == 1,
                    "frame graph ABI access codes mismatch");
        }

        FrameGraph.PassId first = passId(0, "first");
        FrameGraph.PassId outsideMask = passId(64, "outside_mask");
        expectFailure(() -> {
            try (Arena arena = Arena.ofConfined()) {
                FrameGraphAbi.encode(
                        new FrameGraph(List.of(), List.of(
                                pass(first, FrameGraph.EncoderClass.BLIT, List.of(outsideMask))
                        )),
                        0L,
                        arena
                );
            }
        }, "outside [0, 63]");

        FrameGraph.PassId wrongPassId = passId(1, "wrong");
        expectFailure(() -> {
            try (Arena arena = Arena.ofConfined()) {
                FrameGraphAbi.encode(
                        new FrameGraph(List.of(), List.of(
                                pass(wrongPassId, FrameGraph.EncoderClass.BLIT, List.of())
                        )),
                        0L,
                        arena
                );
            }
        }, "dense and ordered");

        FrameGraph.ResourceId wrongResourceId = resourceId(1, "wrong_resource");
        expectFailure(() -> {
            try (Arena arena = Arena.ofConfined()) {
                FrameGraphAbi.encode(
                        new FrameGraph(
                                List.of(resource(wrongResourceId, true, FrameGraph.Lifetime.wholeGraph())),
                                List.of()
                        ),
                        0L,
                        arena
                );
            }
        }, "dense and ordered");
    }

    private static void testDeterministicDiagnosticsAndGate() throws IOException {
        FrameGraph graph = validGraph();
        require(FrameGraphDiagnostics.toDot(graph).equals(FrameGraphDiagnostics.toDot(graph)),
                "DOT diagnostics are not deterministic");
        require(FrameGraphDiagnostics.toJson(graph).equals(FrameGraphDiagnostics.toJson(graph)),
                "JSON diagnostics are not deterministic");

        List<String> writes = new ArrayList<>();
        boolean disabled = FrameGraphDiagnostics.writeIfConfigured(
                graph,
                "",
                (path, contents) -> writes.add(path + contents)
        );
        require(!disabled && writes.isEmpty(), "disabled diagnostics invoked the writer");
        boolean enabled = FrameGraphDiagnostics.writeIfConfigured(
                graph,
                "/tmp/metallum-frame-graph-test",
                (path, contents) -> writes.add(path.getFileName() + ":" + contents.charAt(0))
        );
        require(enabled && writes.equals(List.of(
                        "metallum-frame-graph-test.dot:d",
                        "metallum-frame-graph-test.json:{"
                )), "enabled diagnostics output mismatch");
    }

    private static FrameGraph validGraph() {
        FrameGraph.PassId world = passId(0, "world");
        FrameGraph.PassId hdr = passId(1, "hdr");
        FrameGraph.PassId ui = passId(2, "ui");
        FrameGraph.PassId present = passId(3, "present");
        FrameGraph.ResourceId scene = resourceId(0, "scene");
        FrameGraph.ResourceId depth = resourceId(1, "depth");
        FrameGraph.ResourceId hdrOutput = resourceId(2, "hdr_output");
        FrameGraph.ResourceId uiOutput = resourceId(3, "ui_output");
        FrameGraph.ResourceId drawable = resourceId(4, "drawable");

        return FrameGraph.validated(
                List.of(
                        resource(scene, false, FrameGraph.Lifetime.closed(world, present)),
                        resource(depth, false, FrameGraph.Lifetime.closed(world, hdr)),
                        resource(hdrOutput, false, FrameGraph.Lifetime.closed(hdr, present)),
                        resource(uiOutput, false, FrameGraph.Lifetime.closed(ui, present)),
                        resource(drawable, false, FrameGraph.Lifetime.closed(present, present))
                ),
                List.of(
                        pass(world, FrameGraph.EncoderClass.RENDER, List.of(),
                                access(scene, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.FRAGMENT),
                                access(depth, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.FRAGMENT)),
                        pass(hdr, FrameGraph.EncoderClass.COMPUTE, List.of(world),
                                access(scene, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.COMPUTE),
                                access(depth, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.COMPUTE),
                                access(hdrOutput, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.COMPUTE)),
                        pass(ui, FrameGraph.EncoderClass.RENDER, List.of(hdr),
                                access(hdrOutput, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(uiOutput, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.FRAGMENT)),
                        pass(present, FrameGraph.EncoderClass.RENDER, List.of(ui),
                                access(hdrOutput, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(uiOutput, FrameGraph.AccessKind.READ, FrameGraph.PipelineStage.FRAGMENT),
                                access(drawable, FrameGraph.AccessKind.WRITE, FrameGraph.PipelineStage.FRAGMENT))
                )
        );
    }

    private static FrameGraph.ResourceDesc resource(
            final FrameGraph.ResourceId id,
            final boolean initiallyDefined,
            final FrameGraph.Lifetime lifetime
    ) {
        return new FrameGraph.ResourceDesc(
                id,
                FrameGraph.PersistenceClass.SIZE_GENERATION,
                new FrameGraph.ResourceShape(
                        FrameGraph.ResourceType.TEXTURE,
                        "test_format",
                        "test_extent"
                ),
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

    private static FrameGraph.PassId passId(final int value, final String name) {
        return new FrameGraph.PassId(value, name);
    }

    private static FrameGraph.ResourceId resourceId(final int value, final String name) {
        return new FrameGraph.ResourceId(value, name);
    }

    private static void expectInvalid(final FrameGraph graph, final String messagePart) {
        expectFailure(() -> FrameGraphValidator.validate(graph), messagePart);
    }

    private static void expectFailure(final Runnable operation, final String messagePart) {
        try {
            operation.run();
            throw new AssertionError("Expected failure containing: " + messagePart);
        } catch (IllegalArgumentException exception) {
            require(exception.getMessage().toLowerCase().contains(messagePart.toLowerCase()),
                    "unexpected validation failure: " + exception.getMessage());
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
