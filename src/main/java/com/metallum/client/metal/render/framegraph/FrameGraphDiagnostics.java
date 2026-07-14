package com.metallum.client.metal.render.framegraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Deterministic diagnostics generated only when explicitly requested. */
public final class FrameGraphDiagnostics {
    private static final String CONFIGURED_BASE_PATH = System.getenv("METALLUM_FRAME_GRAPH_DIAGNOSTICS");

    private FrameGraphDiagnostics() {
    }

    @FunctionalInterface
    public interface Sink {
        void write(Path path, String contents) throws IOException;
    }

    public static boolean writeConfigured(final FrameGraph graph) throws IOException {
        return writeIfConfigured(graph, CONFIGURED_BASE_PATH, (path, contents) -> {
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, contents);
        });
    }

    public static boolean writeIfConfigured(
            final FrameGraph graph,
            final String configuredBasePath,
            final Sink sink
    ) throws IOException {
        if (configuredBasePath == null || configuredBasePath.isBlank()) {
            return false;
        }
        Path base = Path.of(configuredBasePath).toAbsolutePath().normalize();
        sink.write(Path.of(base + ".dot"), toDot(graph));
        sink.write(Path.of(base + ".json"), toJson(graph));
        return true;
    }

    public static String toDot(final FrameGraph graph) {
        StringBuilder result = new StringBuilder("digraph MetallumFrameGraph {\n  rankdir=LR;\n");
        for (FrameGraph.ResourceDesc resource : sortedResources(graph)) {
            result.append("  r").append(resource.id().value())
                    .append(" [shape=box,label=\"")
                    .append(escapeDot(resource.id().name())).append("\\n")
                    .append(resource.persistence()).append("\\n")
                    .append(resource.shape().type()).append(":")
                    .append(escapeDot(resource.shape().format())).append("@").append(
                            escapeDot(resource.shape().extent())
                    ).append("\"];\n");
        }
        for (FrameGraph.PassDesc pass : sortedPasses(graph)) {
            result.append("  p").append(pass.id().value())
                    .append(" [label=\"").append(escapeDot(pass.id().name())).append("\\n")
                    .append(pass.encoder()).append("\"];\n");
            pass.dependencies().stream()
                    .sorted(Comparator.comparingInt(FrameGraph.PassId::value))
                    .forEach(dependency -> result.append("  p").append(dependency.value())
                            .append(" -> p").append(pass.id().value()).append(";\n"));
            pass.accesses().stream()
                    .sorted(Comparator.comparingInt(access -> access.resource().value()))
                    .forEach(access -> {
                        String label = access.kind() + "@" + access.stage();
                        if (access.attachment().isAttachment()) {
                            label += ":" + access.attachment().role()
                                    + ":" + access.attachment().loadAction()
                                    + "/" + access.attachment().storeAction();
                        }
                        if (access.kind().reads()) {
                            result.append("  r").append(access.resource().value())
                                    .append(" -> p").append(pass.id().value());
                            result.append(" [style=dashed,label=\"").append(label).append("\"];\n");
                        }
                        if (access.kind().writes()) {
                            result.append("  p").append(pass.id().value())
                                    .append(" -> r").append(access.resource().value());
                            result.append(" [style=dashed,label=\"").append(label).append("\"];\n");
                        }
                    });
        }
        return result.append("}\n").toString();
    }

    public static String toJson(final FrameGraph graph) {
        StringBuilder result = new StringBuilder("{\n  \"resources\": [");
        List<FrameGraph.ResourceDesc> resources = sortedResources(graph);
        for (int index = 0; index < resources.size(); index++) {
            FrameGraph.ResourceDesc resource = resources.get(index);
            result.append(index == 0 ? "\n" : ",\n")
                    .append("    {\"id\": ").append(resource.id().value())
                    .append(", \"name\": \"").append(escapeJson(resource.id().name()))
                    .append("\", \"persistence\": \"").append(resource.persistence())
                    .append("\", \"type\": \"").append(resource.shape().type())
                    .append("\", \"format\": \"").append(escapeJson(resource.shape().format()))
                    .append("\", \"extent\": \"").append(escapeJson(resource.shape().extent()))
                    .append("\", \"initially_defined\": ").append(resource.initiallyDefined());
            if (resource.lifetime().isWholeGraph()) {
                result.append(", \"lifetime\": \"whole_graph\"}");
            } else {
                result.append(", \"lifetime\": {\"first_pass\": ")
                        .append(resource.lifetime().first().value())
                        .append(", \"last_pass\": ")
                        .append(resource.lifetime().last().value()).append("}}");
            }
        }
        result.append(resources.isEmpty() ? "],\n" : "\n  ],\n").append("  \"passes\": [");
        List<FrameGraph.PassDesc> passes = sortedPasses(graph);
        for (int index = 0; index < passes.size(); index++) {
            FrameGraph.PassDesc pass = passes.get(index);
            result.append(index == 0 ? "\n" : ",\n")
                    .append("    {\"id\": ").append(pass.id().value())
                    .append(", \"name\": \"").append(escapeJson(pass.id().name()))
                    .append("\", \"encoder\": \"").append(pass.encoder())
                    .append("\", \"dependencies\": [");
            List<FrameGraph.PassId> dependencies = new ArrayList<>(pass.dependencies());
            dependencies.sort(Comparator.comparingInt(FrameGraph.PassId::value));
            for (int dependencyIndex = 0; dependencyIndex < dependencies.size(); dependencyIndex++) {
                if (dependencyIndex > 0) {
                    result.append(", ");
                }
                result.append(dependencies.get(dependencyIndex).value());
            }
            result.append("], \"accesses\": [");
            List<FrameGraph.ResourceAccess> accesses = new ArrayList<>(pass.accesses());
            accesses.sort(Comparator.comparingInt(access -> access.resource().value()));
            for (int accessIndex = 0; accessIndex < accesses.size(); accessIndex++) {
                FrameGraph.ResourceAccess access = accesses.get(accessIndex);
                if (accessIndex > 0) {
                    result.append(", ");
                }
                result.append("{\"resource\": ").append(access.resource().value())
                        .append(", \"kind\": \"").append(access.kind())
                        .append("\", \"stage\": \"").append(access.stage()).append("\"");
                if (access.attachment().isAttachment()) {
                    result.append(", \"attachment\": {\"role\": \"")
                            .append(access.attachment().role())
                            .append("\", \"load\": \"").append(access.attachment().loadAction())
                            .append("\", \"store\": \"").append(access.attachment().storeAction())
                            .append("\"");
                    if (access.attachment().clearValue() != null) {
                        result.append(", \"clear\": \"")
                                .append(escapeJson(access.attachment().clearValue())).append("\"");
                    }
                    result.append("}");
                }
                result.append("}");
            }
            result.append("]}");
        }
        result.append(passes.isEmpty() ? "]\n" : "\n  ]\n");
        return result.append("}\n").toString();
    }

    private static List<FrameGraph.ResourceDesc> sortedResources(final FrameGraph graph) {
        List<FrameGraph.ResourceDesc> result = new ArrayList<>(graph.resources());
        result.sort(Comparator.comparingInt(resource -> resource.id().value()));
        return result;
    }

    private static List<FrameGraph.PassDesc> sortedPasses(final FrameGraph graph) {
        List<FrameGraph.PassDesc> result = new ArrayList<>(graph.passes());
        result.sort(Comparator.comparingInt(pass -> pass.id().value()));
        return result;
    }

    private static String escapeDot(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String escapeJson(final String value) {
        return escapeDot(value).replace("\n", "\\n").replace("\r", "\\r");
    }
}
