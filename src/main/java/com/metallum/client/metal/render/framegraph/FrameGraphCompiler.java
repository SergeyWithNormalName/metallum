package com.metallum.client.metal.render.framegraph;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RendererGenerationConfig;
import com.metallum.client.renderer.temporal.FrameContract;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure generation compiler for declarative frame-graph metadata. */
public final class FrameGraphCompiler {
    public record HistoryState(boolean valid, long generation) {
        public HistoryState {
            if (valid && generation < 0L) {
                throw new IllegalArgumentException("Valid history needs a non-negative generation");
            }
            if (!valid) {
                generation = -1L;
            }
        }

        public static HistoryState invalid() {
            return new HistoryState(false, -1L);
        }

        public static HistoryState valid(final long generation) {
            return new HistoryState(true, generation);
        }
    }

    public record CompiledPass(
            FrameGraph.PassId id,
            FrameGraph.PassImplementation implementation,
            Set<MetalCapabilities.Feature> enabledOptionalCapabilities
    ) {
        public CompiledPass {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(implementation, "implementation");
            Objects.requireNonNull(enabledOptionalCapabilities, "enabledOptionalCapabilities");
            EnumSet<MetalCapabilities.Feature> copy = enabledOptionalCapabilities.isEmpty()
                    ? EnumSet.noneOf(MetalCapabilities.Feature.class)
                    : EnumSet.copyOf(enabledOptionalCapabilities);
            enabledOptionalCapabilities = Collections.unmodifiableSet(copy);
        }
    }

    public record CompiledGraph(FrameGraph source, List<CompiledPass> passes) {
        public CompiledGraph {
            Objects.requireNonNull(source, "source");
            passes = List.copyOf(passes);
        }
    }

    private FrameGraphCompiler() {
    }

    public static CompiledGraph compile(
            final FrameGraph graph,
            final RendererGenerationConfig generation,
            final FrameContract frameContract,
            final HistoryState history
    ) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(frameContract, "frameContract");
        Objects.requireNonNull(history, "history");
        FrameGraphValidator.validate(graph);

        List<CompiledPass> compiled = new ArrayList<>(graph.passes().size());
        for (FrameGraph.PassDesc pass : graph.passes()) {
            FrameGraph.PassContract contract = pass.contract();
            validateRequiredCapabilities(pass, contract, generation.capabilities());
            validateApplicability(pass, contract, generation, frameContract);
            validateHistory(pass, history);
            FrameGraph.PassImplementation implementation = selectImplementation(pass, generation.executorKind());
            compiled.add(new CompiledPass(
                    pass.id(),
                    implementation,
                    enabledOptional(contract, generation.capabilities())
            ));
        }
        return new CompiledGraph(graph, compiled);
    }

    private static void validateRequiredCapabilities(
            final FrameGraph.PassDesc pass,
            final FrameGraph.PassContract contract,
            final MetalCapabilities capabilities
    ) {
        for (MetalCapabilities.Feature required : contract.requiredCapabilities()) {
            if (!capabilities.supports(required)) {
                throw invalid(pass, "missing required capability " + required);
            }
        }
    }

    private static void validateApplicability(
            final FrameGraph.PassDesc pass,
            final FrameGraph.PassContract contract,
            final RendererGenerationConfig generation,
            final FrameContract frameContract
    ) {
        boolean outputMatches = switch (contract.outputApplicability()) {
            case ANY -> true;
            case SDR_ONLY -> generation.outputMode() == DisplayOutputMode.SDR;
            case HDR_ONLY -> generation.outputMode() == DisplayOutputMode.HDR;
        };
        if (!outputMatches) {
            throw invalid(pass, "output applicability does not match " + generation.outputMode());
        }

        boolean contractMatches = switch (contract.renderContractApplicability()) {
            case ANY -> true;
            case LEGACY_ONLY -> generation.renderContractMode() == RenderContractMode.LEGACY;
            case METALLUM_ONLY -> generation.renderContractMode() == RenderContractMode.METALLUM;
        };
        if (!contractMatches) {
            throw invalid(pass, "render-contract applicability does not match "
                    + generation.renderContractMode());
        }

        boolean lightingMatches = switch (contract.lightingModelApplicability()) {
            case ANY -> true;
            case VANILLA_ONLY -> generation.lightingModel() == LightingModel.VANILLA;
            case ADVANCED_ONLY -> generation.lightingModel() == LightingModel.ADVANCED;
        };
        if (!lightingMatches) {
            throw invalid(pass, "lighting-model applicability does not match "
                    + generation.lightingModel());
        }

        boolean uiMatches = switch (contract.presentationUiContract()) {
            case NOT_PRESENTATION -> true;
            case SEPARATE_SDR_UI_REQUIRED ->
                    frameContract.uiComposition() == FrameContract.UiComposition.SEPARATE_SDR_TEXTURE;
            case COMPOSITED_UI_REQUIRED ->
                    frameContract.uiComposition() == FrameContract.UiComposition.COMPOSITED_WITH_WORLD;
        };
        if (!uiMatches) {
            throw invalid(pass, "presentation UI contract is incompatible with the frame contract");
        }
    }

    private static void validateHistory(
            final FrameGraph.PassDesc pass,
            final HistoryState history
    ) {
        for (FrameGraph.ResourceAccess access : pass.accesses()) {
            if (!access.historyRole().reads()) {
                continue;
            }
            if (!history.valid() || access.historyGeneration() != history.generation()) {
                throw invalid(pass, "history read has no valid matching generation");
            }
        }
    }

    private static FrameGraph.PassImplementation selectImplementation(
            final FrameGraph.PassDesc pass,
            final MetalExecutorKind executor
    ) {
        FrameGraph.PassImplementation primary = pass.contract().primaryImplementation();
        if (matches(primary.target(), executor)) {
            return primary;
        }
        if (pass.contract().fallbackImplementation().isPresent()) {
            FrameGraph.PassImplementation fallback = pass.contract().fallbackImplementation().orElseThrow();
            if (matches(fallback.target(), executor)) {
                return fallback;
            }
        }
        throw invalid(pass, primary.target() + " implementation has no fallback for " + executor);
    }

    private static boolean matches(
            final FrameGraph.ImplementationTarget target,
            final MetalExecutorKind executor
    ) {
        return switch (target) {
            case EXECUTOR_NEUTRAL -> true;
            case METAL3 -> executor == MetalExecutorKind.METAL3;
            case METAL4 -> executor == MetalExecutorKind.METAL4;
        };
    }

    private static Set<MetalCapabilities.Feature> enabledOptional(
            final FrameGraph.PassContract contract,
            final MetalCapabilities capabilities
    ) {
        EnumSet<MetalCapabilities.Feature> enabled = EnumSet.noneOf(MetalCapabilities.Feature.class);
        for (MetalCapabilities.Feature optional : contract.optionalCapabilities()) {
            if (capabilities.supports(optional)) {
                enabled.add(optional);
            }
        }
        return enabled;
    }

    private static IllegalArgumentException invalid(
            final FrameGraph.PassDesc pass,
            final String message
    ) {
        return new IllegalArgumentException(
                "Frame graph generation rejected pass " + pass.id().name() + ": " + message
        );
    }
}
