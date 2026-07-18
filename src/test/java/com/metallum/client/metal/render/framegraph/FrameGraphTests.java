package com.metallum.client.metal.render.framegraph;

import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RendererGenerationConfig;
import com.metallum.client.renderer.temporal.FrameContract;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        testGenerationCapabilityCompiler();
        testNativeHdrGraphTopology();
        testSpatialHdrGraphTopology();
        testAdvancedLightingGraphTopology();
        testAbiHeader();
        testDeterministicDiagnosticsAndGate();
    }

    private static void testValidWorldHdrUiPresentGraph() {
        FrameGraph graph = validGraph();
        require(graph.passes().size() == 4 && graph.resources().size() == 5,
                "valid frame graph changed during construction");
    }

    private static void testAdvancedLightingGraphTopology() {
        FrameGraph graph = AdvancedLightingFrameGraph.graph();
        require(graph.resources().size() == 21 && graph.passes().size() == 9,
                "Advanced lighting frame graph has the wrong topology size");
        require(graph.resources().stream().map(resource -> resource.id().name()).toList().equals(
                        List.of(
                                "lighting_upload_ring", "lighting_params", "gpu_lights",
                                "cluster_membership_scratch", "cluster_compact_headers",
                                "cluster_compact_indices",
                                "cluster_statistics", "environment_shadow_params_ring",
                                "sun_shadow_static_cascades", "sun_shadow_working_cascades",
                                "scene_radiance",
                                "voxel_upload_ring", "voxel_private_patch_ring",
                                "voxel_indirect_args", "voxel_occupancy",
                                "voxel_transmittance_material", "voxel_brick_tags",
                                "local_shadow_params_ring", "entity_shadow_proxies_ring",
                                "local_shadow_reference_ring", "local_shadow_visibility_atlas"
                        )),
                "Advanced lighting resources do not describe the compact index and shadow path");
        for (int index = 0; index < graph.resources().size(); index++) {
            require(graph.resources().get(index).id().value() == index,
                    "Advanced lighting resource IDs are not dense and ordered at " + index);
        }
        List<String> passNames = graph.passes().stream()
                .map(pass -> pass.id().name())
                .toList();
        require(passNames.equals(List.of(
                        "light_upload", "cluster_prepare", "cluster_build", "voxel_upload",
                        "voxel_update", "sun_shadow_static_refresh",
                        "sun_shadow_static_copy", "sun_shadow_dynamic", "direct_lighting")),
                "Advanced lighting pass order changed");
        for (FrameGraph.PassDesc pass : graph.passes()) {
            require(pass.contract().requiredCapabilities().equals(
                            Set.of(MetalCapabilities.Feature.ADVANCED_LIGHTING))
                            && pass.contract().renderContractApplicability()
                            == FrameGraph.RenderContractApplicability.METALLUM_ONLY
                            && pass.contract().lightingModelApplicability()
                            == FrameGraph.LightingModelApplicability.ADVANCED_ONLY
                            && pass.contract().outputApplicability()
                            == FrameGraph.OutputApplicability.ANY,
                    "Advanced lighting pass is coupled to the wrong generation axes");
        }
        FrameGraph.PassDesc build = graph.passes().get(2);
        FrameGraph.PassDesc voxelUpload = graph.passes().get(3);
        FrameGraph.PassDesc voxelUpdate = graph.passes().get(4);
        FrameGraph.PassDesc staticShadow = graph.passes().get(5);
        FrameGraph.PassDesc staticCopy = graph.passes().get(6);
        FrameGraph.PassDesc dynamicShadow = graph.passes().get(7);
        require(build.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("cluster_membership_scratch")
                                && access.kind() == FrameGraph.AccessKind.WRITE),
                "Cluster build does not publish membership scratch");
        require(voxelUpdate.dependencies().equals(List.of(voxelUpload.id()))
                        && voxelUpdate.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("voxel_occupancy")
                                && access.kind() == FrameGraph.AccessKind.WRITE)
                        && voxelUpdate.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("voxel_transmittance_material")
                                && access.kind() == FrameGraph.AccessKind.WRITE)
                        && voxelUpdate.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("voxel_brick_tags")
                                && access.kind() == FrameGraph.AccessKind.WRITE),
                "Voxel update does not consume its private upload and publish all L5 fields");
        require(staticShadow.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("environment_shadow_params_ring")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && staticShadow.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("sun_shadow_static_cascades")
                                && access.kind() == FrameGraph.AccessKind.WRITE
                                && access.attachment().isAttachment()),
                "Static sun-shadow refresh does not declare its input and cached depth output");
        require(staticCopy.dependencies().equals(List.of(staticShadow.id()))
                        && staticCopy.encoder() == FrameGraph.EncoderClass.BLIT
                        && staticCopy.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("sun_shadow_static_cascades")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && staticCopy.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("sun_shadow_working_cascades")
                                && access.kind() == FrameGraph.AccessKind.WRITE)
                        && dynamicShadow.dependencies().equals(List.of(staticCopy.id()))
                        && dynamicShadow.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("sun_shadow_working_cascades")
                                && access.kind() == FrameGraph.AccessKind.READ_WRITE
                                && access.attachment().loadAction() == FrameGraph.LoadAction.LOAD),
                "Cached static copy and dynamic entity shadow ordering changed");
        FrameGraph.PassDesc direct = graph.passes().getLast();
        require(direct.dependencies().equals(List.of(
                        build.id(), voxelUpdate.id(), dynamicShadow.id()))
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("cluster_compact_headers")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("cluster_compact_indices")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("environment_shadow_params_ring")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("sun_shadow_working_cascades")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("voxel_occupancy")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("voxel_transmittance_material")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("voxel_brick_tags")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("local_shadow_params_ring")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("entity_shadow_proxies_ring")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("local_shadow_reference_ring")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("local_shadow_visibility_atlas")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && direct.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("scene_radiance")
                                && access.kind() == FrameGraph.AccessKind.READ_WRITE),
                "Direct lighting is not ordered after cluster, voxel and cached-shadow work");
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

    private static void testGenerationCapabilityCompiler() {
        require(EnumSet.complementOf(EnumSet.of(FrameGraph.ResourceRole.GENERIC)).equals(EnumSet.of(
                        FrameGraph.ResourceRole.SCENE_RADIANCE,
                        FrameGraph.ResourceRole.DEPTH,
                        FrameGraph.ResourceRole.MOTION,
                        FrameGraph.ResourceRole.REACTIVE_MASK,
                        FrameGraph.ResourceRole.SHADOW_DATA,
                        FrameGraph.ResourceRole.CLUSTER_DATA,
                        FrameGraph.ResourceRole.VOXEL_DATA,
                        FrameGraph.ResourceRole.LIGHTING_HISTORY,
                        FrameGraph.ResourceRole.TEMPORAL_OUTPUT,
                        FrameGraph.ResourceRole.INTERPOLATED_OUTPUT,
                        FrameGraph.ResourceRole.SDR_UI)),
                "future frame-graph resource roles are incomplete");

        RendererGenerationConfig sdrMetal3 = generation(
                RenderContractMode.LEGACY,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                MetalCapabilities.productionMetal3(false)
        );
        FrameContract separatedUi = FrameContract.temporalPreparationV1();

        expectGenerationFailure(
                graphWithContract(contract(
                        Set.of(MetalCapabilities.Feature.METAL4_CORE),
                        Set.of(),
                        FrameGraph.ImplementationTarget.EXECUTOR_NEUTRAL,
                        null,
                        FrameGraph.OutputApplicability.ANY,
                        FrameGraph.RenderContractApplicability.ANY,
                        FrameGraph.PresentationUiContract.NOT_PRESENTATION
                )),
                sdrMetal3,
                separatedUi,
                FrameGraphCompiler.HistoryState.invalid(),
                "required capability"
        );
        expectGenerationFailure(
                graphWithContract(contract(
                        Set.of(), Set.of(), FrameGraph.ImplementationTarget.METAL4, null,
                        FrameGraph.OutputApplicability.ANY,
                        FrameGraph.RenderContractApplicability.ANY,
                        FrameGraph.PresentationUiContract.NOT_PRESENTATION
                )),
                sdrMetal3,
                separatedUi,
                FrameGraphCompiler.HistoryState.invalid(),
                "no fallback"
        );
        expectGenerationFailure(
                graphWithContract(contract(
                        Set.of(), Set.of(), FrameGraph.ImplementationTarget.EXECUTOR_NEUTRAL, null,
                        FrameGraph.OutputApplicability.HDR_ONLY,
                        FrameGraph.RenderContractApplicability.ANY,
                        FrameGraph.PresentationUiContract.NOT_PRESENTATION
                )),
                sdrMetal3,
                separatedUi,
                FrameGraphCompiler.HistoryState.invalid(),
                "output applicability"
        );
        expectGenerationFailure(
                graphWithContract(contract(
                        Set.of(), Set.of(), FrameGraph.ImplementationTarget.EXECUTOR_NEUTRAL, null,
                        FrameGraph.OutputApplicability.ANY,
                        FrameGraph.RenderContractApplicability.METALLUM_ONLY,
                        FrameGraph.PresentationUiContract.NOT_PRESENTATION
                )),
                sdrMetal3,
                separatedUi,
                FrameGraphCompiler.HistoryState.invalid(),
                "render-contract applicability"
        );
        MetalCapabilities lightingCapabilities = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METALLUM_MATERIAL_CONTRACT,
                MetalCapabilities.Feature.ADVANCED_LIGHTING
        );
        RendererGenerationConfig metallumVanilla = generation(
                RenderContractMode.METALLUM,
                LightingModel.VANILLA,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                lightingCapabilities
        );
        FrameGraph advancedOnly = graphWithContract(new FrameGraph.PassContract(
                Set.of(),
                Set.of(),
                new FrameGraph.PassImplementation(
                        "advanced", FrameGraph.ImplementationTarget.EXECUTOR_NEUTRAL),
                Optional.empty(),
                FrameGraph.OutputApplicability.ANY,
                FrameGraph.RenderContractApplicability.METALLUM_ONLY,
                FrameGraph.LightingModelApplicability.ADVANCED_ONLY,
                FrameGraph.PresentationUiContract.NOT_PRESENTATION
        ));
        expectGenerationFailure(
                advancedOnly,
                metallumVanilla,
                separatedUi,
                FrameGraphCompiler.HistoryState.invalid(),
                "lighting-model applicability"
        );
        RendererGenerationConfig metallumAdvanced = generation(
                RenderContractMode.METALLUM,
                LightingModel.ADVANCED,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                lightingCapabilities
        );
        require(FrameGraphCompiler.compile(
                        advancedOnly,
                        metallumAdvanced,
                        separatedUi,
                        FrameGraphCompiler.HistoryState.invalid()
                ).passes().size() == 1,
                "METALLUM + ADVANCED pass was not admitted");

        FrameContract compositedUi = new FrameContract(
                separatedUi.version(),
                separatedUi.motionVectors(),
                separatedUi.depth(),
                separatedUi.reactiveMask(),
                FrameContract.UiComposition.COMPOSITED_WITH_WORLD
        );
        expectGenerationFailure(
                graphWithContract(contract(
                        Set.of(), Set.of(), FrameGraph.ImplementationTarget.EXECUTOR_NEUTRAL, null,
                        FrameGraph.OutputApplicability.ANY,
                        FrameGraph.RenderContractApplicability.ANY,
                        FrameGraph.PresentationUiContract.SEPARATE_SDR_UI_REQUIRED
                )),
                sdrMetal3,
                compositedUi,
                FrameGraphCompiler.HistoryState.invalid(),
                "presentation UI"
        );

        FrameGraph historyGraph = historyReadGraph(7L);
        expectGenerationFailure(
                historyGraph,
                sdrMetal3,
                separatedUi,
                FrameGraphCompiler.HistoryState.invalid(),
                "history read"
        );
        expectGenerationFailure(
                historyGraph,
                sdrMetal3,
                separatedUi,
                FrameGraphCompiler.HistoryState.valid(8L),
                "history read"
        );
        require(FrameGraphCompiler.compile(
                        historyGraph,
                        sdrMetal3,
                        separatedUi,
                        FrameGraphCompiler.HistoryState.valid(7L)
                ).passes().size() == 1,
                "matching history generation was rejected");

        MetalCapabilities fallbackCapabilities = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METAL4_CORE,
                MetalCapabilities.Feature.HDR_OUTPUT
        );
        RendererGenerationConfig fallbackGeneration = generation(
                RenderContractMode.LEGACY,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                fallbackCapabilities
        );
        FrameGraph fallbackGraph = graphWithContract(contract(
                Set.of(),
                Set.of(MetalCapabilities.Feature.HDR_OUTPUT),
                FrameGraph.ImplementationTarget.METAL4,
                FrameGraph.ImplementationTarget.METAL3,
                FrameGraph.OutputApplicability.ANY,
                FrameGraph.RenderContractApplicability.ANY,
                FrameGraph.PresentationUiContract.NOT_PRESENTATION
        ));
        FrameGraphCompiler.CompiledPass selected = FrameGraphCompiler.compile(
                fallbackGraph,
                fallbackGeneration,
                separatedUi,
                FrameGraphCompiler.HistoryState.invalid()
        ).passes().getFirst();
        require(selected.implementation().target() == FrameGraph.ImplementationTarget.METAL3
                        && selected.enabledOptionalCapabilities().equals(
                        EnumSet.of(MetalCapabilities.Feature.HDR_OUTPUT)),
                "generation compiler did not select the declared fallback/optional capability");
    }

    private static RendererGenerationConfig generation(
            final RenderContractMode lighting,
            final DisplayOutputMode output,
            final MetalExecutorKind executor,
            final MetalCapabilities capabilities
    ) {
        return generation(lighting, LightingModel.VANILLA, output, executor, capabilities);
    }

    private static RendererGenerationConfig generation(
            final RenderContractMode contract,
            final LightingModel lighting,
            final DisplayOutputMode output,
            final MetalExecutorKind executor,
            final MetalCapabilities capabilities
    ) {
        return new RendererGenerationConfig(
                contract, lighting, output, executor, capabilities, 1
        );
    }

    private static FrameGraph graphWithContract(final FrameGraph.PassContract contract) {
        return new FrameGraph(
                List.of(),
                List.of(new FrameGraph.PassDesc(
                        passId(0, "future"),
                        FrameGraph.EncoderClass.COMPUTE,
                        List.of(),
                        List.of(),
                        contract
                ))
        );
    }

    private static FrameGraph historyReadGraph(final long generation) {
        FrameGraph.PassId read = passId(0, "history_read");
        FrameGraph.ResourceId history = resourceId(0, "history");
        return new FrameGraph(
                List.of(new FrameGraph.ResourceDesc(
                        history,
                        FrameGraph.PersistenceClass.HISTORY,
                        new FrameGraph.ResourceShape(
                                FrameGraph.ResourceType.TEXTURE,
                                "rgba16_float",
                                "render_extent"
                        ),
                        true,
                        FrameGraph.Lifetime.wholeGraph(),
                        FrameGraph.ResourceRole.LIGHTING_HISTORY
                )),
                List.of(new FrameGraph.PassDesc(
                        read,
                        FrameGraph.EncoderClass.COMPUTE,
                        List.of(),
                        List.of(FrameGraph.ResourceAccess.history(
                                history,
                                FrameGraph.AccessKind.READ,
                                FrameGraph.PipelineStage.COMPUTE,
                                FrameGraph.HistoryRole.READ,
                                generation
                        ))
                ))
        );
    }

    private static FrameGraph.PassContract contract(
            final Set<MetalCapabilities.Feature> required,
            final Set<MetalCapabilities.Feature> optional,
            final FrameGraph.ImplementationTarget primary,
            final FrameGraph.ImplementationTarget fallback,
            final FrameGraph.OutputApplicability output,
            final FrameGraph.RenderContractApplicability lighting,
            final FrameGraph.PresentationUiContract ui
    ) {
        return new FrameGraph.PassContract(
                required,
                optional,
                new FrameGraph.PassImplementation("primary", primary),
                Optional.ofNullable(fallback).map(target ->
                        new FrameGraph.PassImplementation("fallback", target)),
                output,
                lighting,
                FrameGraph.LightingModelApplicability.ANY,
                ui
        );
    }

    private static void expectGenerationFailure(
            final FrameGraph graph,
            final RendererGenerationConfig generation,
            final FrameContract frameContract,
            final FrameGraphCompiler.HistoryState history,
            final String messagePart
    ) {
        expectFailure(
                () -> FrameGraphCompiler.compile(graph, generation, frameContract, history),
                messagePart
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

    private static void testSpatialHdrGraphTopology() {
        FrameGraph graph = NativeHdrFrameGraph.spatialGraph();
        require(graph.passes().size() == 9, "MetalFX HDR graph pass count mismatch");
        require(graph.resources().size() == 13, "MetalFX HDR graph resource count mismatch");
        String passNames = graph.passes().stream()
                .map(pass -> pass.id().name())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        require(passNames.equals(
                        "world_render,scene_depth_snapshot,hdr_extract,hdr_exposure_reduce,"
                                + "hdr_bloom_combined,hdr_world_reconstruction,metalfx_spatial,"
                                + "ui_render_with_seed,present"),
                "MetalFX HDR graph pass order mismatch: " + passNames);

        FrameGraph.ResourceDesc reconstruction = graph.resources().get(8);
        FrameGraph.ResourceDesc metalFxOutput = graph.resources().get(9);
        require(reconstruction.id().name().equals("hdr_world_composite")
                        && reconstruction.shape().extent().equals("render_extent"),
                "MetalFX input must remain at render extent");
        require(metalFxOutput.id().name().equals("metalfx_output")
                        && metalFxOutput.shape().format().equals("rgba16_float")
                        && metalFxOutput.shape().extent().equals("display_extent"),
                "MetalFX output must be full-resolution FP16");

        FrameGraph.PassDesc metalFx = graph.passes().get(6);
        require(metalFx.encoder() == FrameGraph.EncoderClass.EXTERNAL_METALFX
                        && metalFx.dependencies().equals(List.of(graph.passes().get(5).id()))
                        && metalFx.accesses().size() == 2
                        && metalFx.accesses().stream().allMatch(
                                access -> access.stage() == FrameGraph.PipelineStage.METALFX
                        ),
                "MetalFX opaque pass contract mismatch");

        FrameGraph.PassDesc ui = graph.passes().get(7);
        FrameGraph.ResourceAccess uiColor = ui.accesses().stream()
                .filter(access -> access.resource().name().equals("sdr_ui_color"))
                .findFirst().orElseThrow();
        FrameGraph.ResourceAccess uiDepth = ui.accesses().stream()
                .filter(access -> access.resource().name().equals("sdr_ui_depth"))
                .findFirst().orElseThrow();
        require(ui.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("metalfx_output")
                                && access.kind() == FrameGraph.AccessKind.READ),
                "fused MetalFX UI pass must sample the scaler output");
        require(uiColor.kind() == FrameGraph.AccessKind.WRITE
                        && uiColor.attachment().loadAction() == FrameGraph.LoadAction.DONT_CARE
                        && uiColor.attachment().storeAction() == FrameGraph.StoreAction.STORE,
                "fused MetalFX UI color contract must be dontCare/store");
        require(uiDepth.kind() == FrameGraph.AccessKind.WRITE
                        && uiDepth.attachment().loadAction() == FrameGraph.LoadAction.CLEAR
                        && uiDepth.attachment().storeAction() == FrameGraph.StoreAction.STORE
                        && uiDepth.attachment().clearValue().equals("0.0"),
                "fused MetalFX UI depth contract must clear reversed depth");

        FrameGraph.PassDesc present = graph.passes().get(8);
        require(present.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("metalfx_output")
                                && access.kind() == FrameGraph.AccessKind.READ)
                        && present.accesses().stream().anyMatch(access ->
                        access.resource().name().equals("sdr_ui_color")
                                && access.kind() == FrameGraph.AccessKind.READ),
                "MetalFX present must read the separated HDR and UI textures");
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

        FrameGraph spatialGraph = NativeHdrFrameGraph.spatialGraph();
        int spatialAccessCount = spatialGraph.passes().stream()
                .mapToInt(pass -> pass.accesses().size())
                .sum();
        long spatialCapabilities = FrameGraphAbi.CAPABILITY_TYPED_ATTACHMENTS
                | FrameGraphAbi.CAPABILITY_EXTERNAL_METALFX;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment packet = FrameGraphAbi.encode(spatialGraph, spatialCapabilities, arena);
            require(packet.get(ValueLayout.JAVA_LONG, FrameGraphAbi.HEADER_CAPABILITIES)
                            == spatialCapabilities
                            && packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_RESOURCE_COUNT) == 13
                            && packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_PASS_COUNT) == 9
                            && packet.get(ValueLayout.JAVA_INT, FrameGraphAbi.HEADER_ACCESS_COUNT)
                            == spatialAccessCount,
                    "MetalFX frame graph ABI header mismatch");

            long passBase = FrameGraphAbi.HEADER_BYTES
                    + (long) spatialGraph.resources().size() * FrameGraphAbi.RESOURCE_BYTES;
            long metalFxPass = passBase + 6L * FrameGraphAbi.PASS_BYTES;
            int firstMetalFxAccess = packet.get(
                    ValueLayout.JAVA_INT,
                    metalFxPass + FrameGraphAbi.PASS_FIRST_ACCESS
            );
            long accessBase = passBase
                    + (long) spatialGraph.passes().size() * FrameGraphAbi.PASS_BYTES;
            long metalFxAccess = accessBase
                    + (long) firstMetalFxAccess * FrameGraphAbi.ACCESS_BYTES;
            require(packet.get(ValueLayout.JAVA_INT, metalFxPass + FrameGraphAbi.PASS_ENCODER) == 4
                            && packet.get(ValueLayout.JAVA_INT,
                            metalFxAccess + FrameGraphAbi.ACCESS_STAGE) == 5,
                    "MetalFX frame graph ABI codes mismatch");
        }
        expectFailure(() -> {
            try (Arena arena = Arena.ofConfined()) {
                FrameGraphAbi.encode(
                        spatialGraph,
                        FrameGraphAbi.CAPABILITY_TYPED_ATTACHMENTS,
                        arena
                );
            }
        }, "capabilities");

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
