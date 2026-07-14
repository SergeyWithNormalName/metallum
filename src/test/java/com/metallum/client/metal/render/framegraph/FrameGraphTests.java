package com.metallum.client.metal.render.framegraph;

import java.io.IOException;
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
                FrameGraph.PersistenceClass.SIZE,
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
