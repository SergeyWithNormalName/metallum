package com.metallum.client.gi.live;

import com.metallum.client.gi.receiver.GiReceiverBindingAbi;
import com.metallum.client.gi.receiver.GiReceiverLayout;
import com.metallum.client.hdr.MetallumMaterialShaderPatcher;
import com.metallum.client.lighting.TerrainEnvironmentSpecialization;
import com.metallum.client.lighting.shader.AdvancedDirectLightingShaderPatcher;
import com.metallum.client.renderer.GlobalIlluminationMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.RendererConfig;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Source and real generated-MSL proof for the production three-cascade G6 receiver. */
public final class GiLiveReceiverSourceChainTests {
    private static final String SODIUM_NAMESPACE = "sodium";
    private static final String SODIUM_TERRAIN = "blocks/block_layer_opaque";
    private static final String INVERSE_PI = "0.31830988618";

    private GiLiveReceiverSourceChainTests() {
    }

    public static void main(final String[] arguments) throws Exception {
        testDefaultOffAndFailClosedPatchAdmission();
        testThreeCascadeVertexOnlySourceContract();
        testCarrierFailureAndReflectionCoexistence();
        testSourceTickAndSteadyAllocationGuards();
        testTerminalFailClosedPublicationGuards();
        testRealGeneratedMslForEveryTerrainFlavor();
        System.out.println("G6 live receiver source-chain tests passed");
    }

    private static void testDefaultOffAndFailClosedPatchAdmission() {
        RendererConfig defaults = RendererConfig.defaults();
        require(!defaults.improvedLighting()
                        && defaults.globalIllumination() == GlobalIlluminationMode.OFF,
                "renderer defaults no longer keep production GI off");
        require(!basicVertexSource().contains(GiLiveReceiverShaderPatcher.MARKER)
                        && !fragmentSource().contains(GiLiveReceiverShaderPatcher.MARKER)
                        && !basicVertexSource().contains(GiReceiverBindingAbi.PARAMS_BLOCK)
                        && !fragmentSource().contains(GiReceiverBindingAbi.VARYING),
                "unpatched/default-off terrain source retained G6 work");

        GiLiveReceiverShaderPatcher.Result missingAdmission =
                GiLiveReceiverShaderPatcher.patch(
                        GiLiveReceiverShaderPatcher.Stage.VERTEX,
                        basicVertexSource().replace(
                                "// METALLUM_ADVANCED_DIRECT_LIGHTING_V1", ""
                        )
                );
        require(!missingAdmission.success()
                        && missingAdmission.failureReason().contains("requires an admitted"),
                "G6 patched a terrain shader without Advanced admission");

        String duplicateAnchor = basicVertexSource().replace(
                "out float metallumSkyVisibility;",
                "out float metallumSkyVisibility;\nout float metallumSkyVisibility;"
        );
        GiLiveReceiverShaderPatcher.Result ambiguous = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.VERTEX, duplicateAnchor
        );
        require(!ambiguous.success()
                        && ambiguous.source().equals(duplicateAnchor)
                        && ambiguous.failureReason().contains("anchor changed"),
                "G6 did not fail closed on an ambiguous varying anchor");
        require(!GiLiveReceiverShaderPatcher.patch(null, basicVertexSource()).success()
                        && !GiLiveReceiverShaderPatcher.patch(
                        GiLiveReceiverShaderPatcher.Stage.FRAGMENT, null).success(),
                "G6 accepted a missing shader stage/source");
    }

    private static void testThreeCascadeVertexOnlySourceContract() {
        GiLiveReceiverShaderPatcher.Result vertex = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.VERTEX, basicVertexSource()
        );
        GiLiveReceiverShaderPatcher.Result fragment = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.FRAGMENT, fragmentSource()
        );
        require(vertex.success(), "G6 vertex patch failed: " + vertex.failureReason());
        require(fragment.success(), "G6 fragment patch failed: " + fragment.failureReason());

        require(count(vertex.source(), "// " + GiLiveReceiverShaderPatcher.MARKER) == 1
                        && count(fragment.source(),
                        "// " + GiLiveReceiverShaderPatcher.MARKER) == 1,
                "G6 patch marker is missing or repeated");
        require(count(vertex.source(), "textureLod(") == 12,
                "G6 did not retain exactly four bounded vertex reads per cascade");
        require(count(vertex.source(), "uvec2 sampleableBrickMasks[3];") == 1
                        && !fragment.source().contains("sampleableBrickMasks"),
                "G6 exact-visible brick masks are missing or escaped into fragment source");
        for (String sampler : GiReceiverBindingAbi.samplerNames()) {
            require(count(vertex.source(), "uniform sampler3D " + sampler + ";") == 1
                            && count(vertex.source(), sampler + ", metallumGiSampleUvw") == 3,
                    "G6 vertex source lost three reads for " + sampler);
            require(!fragment.source().contains("sampler3D " + sampler)
                            && !fragment.source().contains("textureLod(\n        " + sampler),
                    "G6 fragment source retained 3D receiver resource " + sampler);
        }
        for (int cascade = 0; cascade < 3; cascade++) {
            String cascadeBlock = slice(
                    vertex.source(),
                    "vec3 metallumGiLocal" + cascade + " =",
                    cascade < 2
                            ? "vec3 metallumGiLocal" + (cascade + 1) + " ="
                            : GiReceiverBindingAbi.VARYING + " = vec4(0.0);"
            );
            String compact = normalizeWhitespace(cascadeBlock);
            String sampleCell = "metallumGiSampleCell" + cascade;
            String minCell = "metallumGiFootprintMinCell" + cascade;
            String maxCell = "metallumGiFootprintMaxCell" + cascade;
            String minBrick = "metallumGiFootprintMinBrick" + cascade;
            String maxBrick = "metallumGiFootprintMaxBrick" + cascade;
            String brick = "metallumGiFootprintBrickId" + cascade;
            String mask = "metallumGiSampleableMask" + cascade;
            String word = "metallumGiFootprintSampleableWord" + cascade;
            String sampleable = "metallumGiFootprintSampleable" + cascade;
            int footprintStart = cascadeBlock.indexOf("vec3 " + sampleCell + " =");
            int exactGate = cascadeBlock.indexOf("if (" + sampleable + ") {");
            int exactOpenBrace = exactGate < 0 ? -1 : cascadeBlock.indexOf('{', exactGate);
            int exactCloseBrace = matchingBrace(cascadeBlock, exactOpenBrace);
            require(compact.contains("vec3 " + sampleCell + " = clamp( "
                            + "metallumGiLocal" + cascade
                            + " * 32.0, vec3(0.5), vec3(31.5));")
                            && compact.contains("uvec3 " + minCell
                            + " = uvec3(clamp(floor( " + sampleCell
                            + " - vec3(0.5)), vec3(0.0), vec3(31.0)));")
                            && compact.contains("uvec3 " + maxCell
                            + " = uvec3(clamp(ceil( " + sampleCell
                            + " - vec3(0.5)), vec3(0.0), vec3(31.0)));")
                            && compact.contains("uvec3 " + minBrick + " = "
                            + minCell + " >> uvec3(3u);")
                            && compact.contains("uvec3 " + maxBrick + " = "
                            + maxCell + " >> uvec3(3u);")
                            && compact.contains("for (uint metallumGiBrickZ" + cascade
                            + " = " + minBrick + ".z;")
                            && compact.contains("for (uint metallumGiBrickY" + cascade
                            + " = " + minBrick + ".y;")
                            && compact.contains("for (uint metallumGiBrickX" + cascade
                            + " = " + minBrick + ".x;")
                            && compact.contains("uvec2 " + mask + " = "
                            + GiReceiverBindingAbi.PARAMS_BLOCK
                            + ".sampleableBrickMasks[" + cascade + "];")
                            && compact.contains("uint " + brick + " =")
                            && compact.contains("uint " + word + " = " + brick
                            + " < 32u ? " + mask + ".x : " + mask + ".y;")
                            && compact.contains("bool " + sampleable + " = true;")
                            && compact.contains(sampleable + " = " + sampleable + " && (" + word
                            + " & (1u << (" + brick + " & 31u))) != 0u;")
                            && footprintStart >= 0 && exactGate > footprintStart
                            && exactCloseBrace > exactOpenBrace
                            && count(cascadeBlock.substring(
                            exactOpenBrace, exactCloseBrace), "textureLod(") == 4,
                    "G6 cascade " + cascade
                            + " does not sampleable-gate its four reads by every trilinear-footprint brick");
            require(count(vertex.source(),
                            "receiverState.x & (1u << " + cascade + ")") == 1
                            && count(vertex.source(),
                            "metallumGiCascadeValue" + cascade) >= 3
                            && count(vertex.source(),
                            "metallumGiCascadeValid" + cascade) >= 3,
                    "G6 cascade " + cascade + " is absent or structurally incomplete");
        }
        require(vertex.source().contains("cascadeOrigins[3]")
                        && vertex.source().contains("cascadeScaleAndCellSize[3]")
                        && vertex.source().contains("metallumGiBlendedValid")
                        && vertex.source().contains(
                        "mix(metallumGiBlended, metallumGiCascadeValue0")
                        && count(vertex.source(),
                        "out vec4 " + GiReceiverBindingAbi.VARYING + ";") == 1,
                "G6 three-cascade blend/varying contract is incomplete");

        require(!fragment.source().contains("sampler3D")
                        && !fragment.source().contains("textureLod(")
                        && count(fragment.source(),
                        "in vec4 " + GiReceiverBindingAbi.VARYING + ";") == 1
                        && count(fragment.source(), INVERSE_PI) == 1
                        && count(fragment.source(),
                        "return albedo * diffuse * " + INVERSE_PI + ";") == 1
                        && fragment.source().contains(
                        "vec3 diffuse = metallumGiFallbackAmbient;")
                        && fragment.source().contains(
                        "if (metallumGiConfidence > 0.0)")
                        && !fragment.source().contains(
                        "metallumGiIncomingAmbient * " + INVERSE_PI),
                "G6 fragment lost its exactly-once rho/pi or fallback composition");

        GiLiveReceiverShaderPatcher.Result vertexAgain = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.VERTEX, vertex.source()
        );
        GiLiveReceiverShaderPatcher.Result fragmentAgain = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.FRAGMENT, fragment.source()
        );
        require(vertexAgain.success() && fragmentAgain.success()
                        && vertexAgain.source().equals(vertex.source())
                        && fragmentAgain.source().equals(fragment.source()),
                "G6 patch is not byte-idempotent");

        String missingExactGate = vertex.source().replace(
                "if (metallumGiFootprintSampleable1)",
                "if (true /* sampleable gate removed */)"
        );
        GiLiveReceiverShaderPatcher.Result corrupted = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.VERTEX, missingExactGate
        );
        require(!corrupted.success()
                        && corrupted.source().equals(missingExactGate)
                        && corrupted.failureReason().contains("sampleable-footprint gate"),
                "G6 idempotent validation did not fail closed after a sampleable gate was lost");

        String missingFootprintBit = vertex.source().replace(
                "&& (metallumGiFootprintSampleableWord1",
                "&& (metallumGiSampleableMask1.x /* sampleable footprint bit removed */"
        );
        GiLiveReceiverShaderPatcher.Result bitCorrupted = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.VERTEX, missingFootprintBit
        );
        require(!bitCorrupted.success()
                        && bitCorrupted.source().equals(missingFootprintBit)
                        && bitCorrupted.failureReason().contains("sampleable-footprint gate"),
                "G6 idempotent validation admitted a footprint loop without sampleable-bit proof");
    }

    private static void testCarrierFailureAndReflectionCoexistence() {
        GiLiveReceiverShaderPatcher.Result basic = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.VERTEX, basicVertexSource()
        );
        require(basic.success(), "G6 basic source did not patch");
        String source = basic.source();
        int carrierSafe = source.indexOf(
                "bool metallumGiCarrierSafe = metallumGiReceiver.receiverState.y == 1u;"
        );
        int contractReady = source.indexOf(
                "bool metallumGiContractReady = metallumGiCarrierSafe"
        );
        int zeroOutput = source.indexOf(
                GiReceiverBindingAbi.VARYING + " = vec4(0.0);"
        );
        int firstSample = source.indexOf("textureLod(");
        require(carrierSafe >= 0 && contractReady > carrierSafe
                        && source.contains(
                        "metallumGiCarrierSafe && metallumGiPositionAxisCarrier")
                        && zeroOutput > contractReady
                        && firstSample > contractReady
                        && source.contains("if (metallumGiContractReady")
                        && source.contains("if (metallumGiBlendedValid)"),
                "G6 unsafe carrier/contract path is not zero-output and fail-closed");

        GiLiveReceiverShaderPatcher.Result reflected = GiLiveReceiverShaderPatcher.patch(
                GiLiveReceiverShaderPatcher.Stage.VERTEX, reflectionVertexSource()
        );
        require(reflected.success(),
                "G6/reflection coexistence failed: " + reflected.failureReason());
        require(!reflected.source().contains("MetallumGiLiveCameraV1")
                        && reflected.source().contains(
                        "metallumReflectionGiAxisEligible ? metallumReflectionFaceCode : 0u")
                        && reflected.source().contains(
                        "vec3 metallumGiWorldPosition = metallumWorldPos;")
                        && reflected.source().contains(
                        "metallumGiCarrierSafe && metallumGiPositionAxisCarrier")
                        && count(reflected.source(), "textureLod(") == 12,
                "G6 did not reuse reflection world/face truth without duplicating its camera ABI");
    }

    private static void testSourceTickAndSteadyAllocationGuards() throws IOException {
        String mixin = workspaceSource(
                "src/main/java/com/metallum/mixin/lighting/"
                        + "LevelExtractorAdvancedLightMixin.java"
        );
        String beginTick = slice(
                mixin,
                "private void metallum$beginGiDynamicSourceTick",
                "private void metallum$offerCameraHeldLight"
        );
        Matcher observationTick = Pattern.compile(
                "long\\s+(\\w+)\\s*=\\s*collector\\.nextObservationTick\\(world\\);"
        ).matcher(beginTick);
        require(observationTick.find(),
                "G6 source tick is no longer collector-owned extraction cadence");
        String tickVariable = observationTick.group(1);
        int observationAdvance = beginTick.indexOf(observationTick.group());
        int observationOpen = beginTick.indexOf(
                "collector.beginTick(world, " + tickVariable + ");"
        );
        int publicationPair = beginTick.indexOf(
                "this.metallum$giDynamicOpenTick = " + tickVariable + ";"
        );
        require(!observationTick.find()
                        && count(beginTick, "Math.incrementExact(") == 0
                        && !mixin.contains("metallum$giDynamicSourceTick")
                        && !mixin.contains("metallum$giDynamicObservationTick")
                        && !mixin.contains("metallum$giDynamicLastWorldTick")
                        && !beginTick.contains("collector.isWorldOpen(world)")
                        && !beginTick.contains("collector.snapshot()")
                        && !beginTick.contains("this.level.getGameTime()")
                        && observationAdvance >= 0
                        && observationOpen > observationAdvance
                        && publicationPair > observationOpen
                        && count(mixin,
                        "this.metallum$beginGiDynamicSourceTick(token);") == 1,
                "G6 real extraction no longer pairs publication to its sole collector tick");

        String commit = slice(
                mixin,
                "private void metallum$commitDynamicLightFrame",
                "private static String metallum$dimensionId"
        );
        String publication = slice(
                commit,
                "GiDynamicSourceCollector giCollector =",
                "if (collector != null)"
        );
        Matcher publishCalls = Pattern.compile(
                "GiLiveRuntime\\.publishDynamicSources\\((.*?)\\);",
                Pattern.DOTALL
        ).matcher(publication);
        int publicationCount = 0;
        while (publishCalls.find()) {
            String arguments = publishCalls.group(1);
            require(arguments.contains("this.metallum$giDynamicOpenTick")
                            && !arguments.contains("getGameTime")
                            && !arguments.contains("worldTick"),
                    "G6 publication admitted a non-source clock");
            publicationCount++;
        }
        require(publicationCount == 1
                        && publication.contains("giCollector.finishTick(),")
                        && !publication.contains(
                        "this.metallum$giDynamicSources.snapshot(),"),
                "G6 publication must finish the open extraction tick without a stale fallback");

        String entityExtraction = slice(
                mixin,
                "private boolean metallum$extractDynamicLightBeforeVisibilityFilter",
                "private void metallum$commitDynamicLightFrame"
        );
        int entityFactory = entityExtraction.indexOf(
                "AdvancedLight light = MinecraftLightPolicy.entity("
        );
        int giOffer = entityExtraction.indexOf("giCollector.offer(light);");
        int advancedDedupe = entityExtraction.indexOf(
                "MinecraftLightPolicy.cameraHeldStableIdMatches("
        );
        int visibilityDecision = entityExtraction.indexOf(
                "return original.call(extractor, entity, frustum"
        );
        String cameraHeldExtraction = slice(
                mixin,
                "private void metallum$offerCameraHeldLight",
                "private static CameraHeldLightTracker.CameraPose metallum$firstPersonHeldPose"
        );
        require(entityFactory >= 0
                        && giOffer > entityFactory
                        && advancedDedupe > giOffer
                        && visibilityDecision > advancedDedupe
                        && count(entityExtraction, "giCollector.offer(light);") == 1
                        && !cameraHeldExtraction.contains("GiDynamicSourceCollector")
                        && !cameraHeldExtraction.contains("giCollector")
                        && !mixin.contains(
                        "GiDynamicSourceCollector.heldAtEntityWorldPosition("),
                "G6 held/entity extraction allocates camera truth instead of reusing one "
                        + "body-space AdvancedLight before frustum admission");

        String coordinator = workspaceSource(
                "src/main/java/com/metallum/client/gi/source/"
                        + "GiDirectSourceCoordinator.java"
        );
        String liveEncode = slice(
                coordinator,
                "/** Live G6 overload;",
                "public GiDirectDirtyQueue.Telemetry queueTelemetry"
        );
        String conditionalEpoch = ") ? this.activeEpoch : new GiDirectSourceEpoch(";
        require(liveEncode.contains("GiDirectSourceEpoch nextEpoch = matchesEpoch(")
                        && liveEncode.contains(conditionalEpoch)
                        && count(liveEncode, "new GiDirectSourceEpoch(") == 1
                        && !liveEncode.contains("dirtyQueue.telemetry()"),
                "G3 live encode lost its allocation-free stable epoch/primitive telemetry path");

        String liveGpu = workspaceSource(
                "src/main/java/com/metallum/client/gi/live/GiLiveGpuResources.java"
        );
        String cascadePlan = slice(
                liveGpu,
                "public int planCascade(",
                "public void markCascadeUnprepared("
        );
        require(cascadePlan.contains("this.activeSourceTick")
                        && cascadePlan.contains("this.activeResetKind")
                        && !cascadePlan.contains("final long sourceTick")
                        && !cascadePlan.contains("final GiLiveLayout.ResetKind resetKind"),
                "G6 cascade refinement no longer latches its epoch source tick/reset kind");
        String statsQuery = slice(
                liveGpu,
                "private Stats queryStats()",
                "@Override"
        );
        String liveCoordinator = workspaceSource(
                "src/main/java/com/metallum/client/gi/live/GiLiveCoordinator.java"
        );
        String liveObserve = slice(
                liveCoordinator,
                "public int observeAndEncode(",
                "public int bindVertex("
        );
        require(statsQuery.contains(
                        "decodeStats(this.statsPacket, this.statsView)")
                        && !statsQuery.contains("new Stats(")
                        && !liveObserve.contains("new GiLiveGpuResources.Stats(")
                        && !liveObserve.contains("new Stats("),
                "G6 stable submit path resumed allocating immutable telemetry snapshots");
        require(liveObserve.contains(
                        "this.direct.observedLiveEnvironmentDigest()")
                        && liveObserve.contains(
                        "if (!sourceEnvironmentIdentityReady(environmentDigest))")
                        && liveObserve.indexOf("int retirementStatus = retirePendingBatch(stats);")
                        < liveObserve.indexOf(
                        "if (!sourceEnvironmentIdentityReady(environmentDigest))")
                        && liveObserve.contains(
                        "recordDeferredObservation(GiLiveUpdateClass.FULL_RESET, submitIndex);")
                        && !liveObserve.contains("quantizedDigest(environment)"),
                "G6 observation is no longer keyed to G3's admitted environment identity");
        String observationRotation = slice(
                liveObserve,
                "boolean changed = observationChanged(",
                "long dynamicEpoch = dynamic.epoch().sourceEpoch();"
        );
        int acceptedWriteGate = observationRotation.indexOf(
                "if (stats.buildInFlight()) {"
        );
        int observedUpdate = observationRotation.indexOf("beginObservedUpdate(");
        require(acceptedWriteGate >= 0 && observedUpdate > acceptedWriteGate
                        && observationRotation.indexOf(
                        "return canRetainDeferredHistory(", acceptedWriteGate) > acceptedWriteGate
                        && observationRotation.contains("STATUS_RETAINED_HISTORY")
                        && observationRotation.indexOf(
                        "recordDeferredSourceMasks(", acceptedWriteGate) > acceptedWriteGate
                        && liveCoordinator.contains("EpochTransition.RETAINED_PROVISIONAL")
                        && liveCoordinator.contains(
                        "this.workAdmissionOccurred = true;")
                        && liveCoordinator.contains(
                        "shouldRetainPendingBasis(")
                        && liveCoordinator.contains(
                        "shouldRetainOpenFullResetChild(")
                        && liveCoordinator.contains(
                        "sameObservedStructuralRoot(field), sameObservedOrigins(field)")
                        && liveCoordinator.contains(
                        "this.fullResetRootOpen = true;")
                        && liveCoordinator.contains(
                        "EpochTransition.FULL_RESET_ROOT_CHILD")
                        && liveCoordinator.contains(
                        "boolean startsLatency = !this.fullResetRootOpen")
                        && liveCoordinator.contains(
                        "stickyKnownAffectedMask(")
                        && liveCoordinator.contains(
                        "this.conservativeMasks[cascade] = stickyKnown;")
                        && liveObserve.contains(
                        "GiDirectSourceCoordinator.LiveSource available = staticHealthy")
                        && liveObserve.contains(
                        "if (!sourceMatchesObservation(field, available, dynamic))")
                        && liveCoordinator.contains(
                        "this.latency.beginPending(observedUpdateClass, affectedSubmitIndex);")
                        && liveCoordinator.contains(
                        "this.firstAffectedSubmitIndex = "
                                + "this.latency.pendingFirstAffectedSubmitIndex();")
                        && liveCoordinator.contains(
                        "epoch, this.activeUpdateClass, publicationSubmitIndex"),
                "G6 can rotate a shared-atlas epoch in flight or repeat an admitted scroll remap");
        String provisionalMasks = slice(
                liveCoordinator,
                "private void computeProvisionalMasks(",
                "private void computeCoalescedMasks("
        );
        require(provisionalMasks.contains(
                        "this.activeUpdateClass == GiLiveUpdateClass.STATIC_SOURCE")
                        && provisionalMasks.contains(
                        "this.observedContent != field.contentGeneration()")
                        && provisionalMasks.contains(
                        "mergeChangedBlockMasks(field, this.requiredMasks);")
                        && liveCoordinator.contains(
                        "this.firstAffectedSubmitIndex = "
                                + "this.latency.pendingFirstAffectedSubmitIndex();"),
                        "G6 full-reset child coalescing lost known block dirt or earliest SLA ownership");
        String observedUpdateBody = slice(
                liveCoordinator,
                "private void beginObservedUpdate(",
                "private void beginRetainedProvisionalOverlap("
        );
        require(observedUpdateBody.indexOf(
                        "observedUpdateClass = GiLiveUpdateClass.merge(")
                        < observedUpdateBody.indexOf(
                        "boolean retainedFullResetRoot = shouldRetainOpenFullResetChild(")
                        && observedUpdateBody.indexOf(
                        "boolean retainedFullResetRoot = shouldRetainOpenFullResetChild(")
                        < observedUpdateBody.indexOf("this.fullResetRootOpen = true;"),
                "deferred FULL_RESET can be dropped by child retention or fail to open its root");
        int planningLoop = liveObserve.indexOf("do {");
        int planCascade = liveObserve.indexOf(
                "if (!prepareNextCascadePlan(", planningLoop
        );
        int continuePlanning = liveObserve.indexOf(
                "shouldContinueAuthoritativePlanning(", planCascade
        );
        int finalPlanStats = liveObserve.indexOf(
                "stats = this.resources.stats();", continuePlanning
        );
        int finalPlanReceipt = liveObserve.indexOf(
                "observeCompletion(stats, submitIndex);", finalPlanStats
        );
        int unavailableReturn = liveObserve.indexOf(
                "if (authoritativeSourceUnavailable) {", finalPlanReceipt
        );
        require(planningLoop >= 0 && planCascade > planningLoop
                        && continuePlanning > planCascade
                        && finalPlanStats > continuePlanning
                        && finalPlanReceipt > finalPlanStats
                        && liveObserve.contains(
                        "boolean authoritativeSourceUnavailable = false;")
                        && liveObserve.contains(
                        "boolean authoritativePlanAvailable = hasAnyAuthoritativePlan();")
                        && liveObserve.indexOf(
                        "authoritativeSourceUnavailable = true;", planCascade
                ) > planCascade
                        && liveObserve.indexOf(
                        "authoritativePlanAvailable = true;", planCascade
                ) > planCascade
                        && unavailableReturn > finalPlanReceipt
                        && liveObserve.indexOf(
                        "return unavailableAuthoritativeSourceStatus(", unavailableReturn
                ) > unavailableReturn,
                "G6 zero-required cascade planning can starve far or miss final near readiness");
        int immediateRemap = liveObserve.indexOf(
                "this.resources.encodeScrollRemap("
        );
        int firstSourceQuery = liveObserve.indexOf(
                "GiDirectSourceCoordinator.LiveSource available = staticHealthy"
        );
        String liveResources = workspaceSource(
                "src/main/java/com/metallum/client/gi/live/GiLiveGpuResources.java"
        );
        String remapAdmission = slice(
                liveResources,
                "public int encodeScrollRemap(",
                "/** Binds the atlas and exact per-slot receiver packet"
        );
        require(immediateRemap >= 0 && immediateRemap < firstSourceQuery
                        && liveObserve.contains("shouldEncodeProvisionalNearScrollRemap(")
                        && count(liveObserve, "provisionalReceiverHistoryCanBind(") == 2
                        && liveObserve.contains("this.completion.admit(\n                    0, 0, true, 0L")
                        && liveObserve.contains("advanceReceiverOrigin(epoch, 0);")
                        && liveCoordinator.contains("field.originComponent(offset)"
                        + " - this.receiverOrigins[offset]")
                        && liveCoordinator.contains("private final int[] receiverOrigins")
                        && liveCoordinator.contains("computePhysicalScrollPlan(field);")
                        && liveCoordinator.contains(
                        "field.originComponent(offset) - this.receiverOrigins[offset]")
                        && liveCoordinator.contains(
                        "if (prepare) advanceReceiverOrigin(epoch, cascade);")
                        && liveCoordinator.contains("EpochTransition.AUTHORITATIVE_REBASE")
                        && liveCoordinator.contains(
                        "this.handoffRetainedMasks[cascade] = this.exactMasks[cascade];")
                        && remapAdmission.contains("field.copyCascade(cascade, cellPacket)")
                        && remapAdmission.contains("flags, 0, 0L, requiredMask")
                        && !remapAdmission.contains("LiveSource")
                        && !remapAdmission.contains("awaitReady(")
                        && !remapAdmission.contains("Arena.allocate"),
                "G6 waits for G3 before remapping compatible near coverage or rebases it unsafely");
        String completionPublication = slice(
                liveCoordinator,
                "private void observeCompletion(",
                "private void publishFinalTelemetry("
        );
        String nativeSource = workspaceSource("src/main/native/MetallumNative.swift");
        String nativeWriteParams = slice(
                nativeSource, "private func writeParams(", "func bindVertex("
        );
        String nativeLiveEncode = slice(
                nativeSource, "func encodeCascade(", "private func writeParams("
        );
        require(completionPublication.contains(
                        "latencyRecoveryIsReceiverVisible(")
                        && completionPublication.contains(
                        "pending, this.exactMasks[0], (stats.readyMask() & 1) != 0")
                        && nativeWriteParams.contains(
                        "let sampleable = carrierSafe ? receiverBrickMasks[cascade] : 0")
                        && nativeWriteParams.contains(
                        "if sampleable != 0 { visibleMask |= UInt32(1) << UInt32(cascade) }")
                        && nativeLiveEncode.contains(
                        "receiverBrickMasks[cascade] = preserving ? retainedReceiver : 0")
                        && nativeLiveEncode.contains(
                        "if preparing && !preserving && !scrolling")
                        && nativeSource.contains(
                        "retainedReceiverBrickMasks[cascade] = receiverBrickMasks[cascade]")
                        && nativeSource.contains(
                        "previousReceiverMask: retainedReceiverBrickMasks[cascade]")
                        && workspaceSource("src/main/metal/MetallumGiTransport.metal").contains(
                        "params.previousReceiverMask & (1ul << sourceBrick)")
                        && !workspaceSource("src/main/metal/MetallumGiTransport.metal").contains(
                        "params.requiredMask & (1ul << destinationBrick)")
                        && nativeLiveEncode.contains(
                        "let remapOnly = preparing && preserving && scrolling")
                        && nativeLiveEncode.contains(
                        "let source = remapOnly ? nil : direct.liveTransportSnapshot(")
                        && nativeLiveEncode.contains("if let source {")
                        && completionPublication.contains(
                        "shouldCloseOpenFullResetRoot(")
                        && completionPublication.contains(
                        "this.fullResetRootOpen, this.activeEpochAuthoritative, true")
                        && completionPublication.contains(
                        "this.fullResetRootOpen = false;"),
                "G6 SLA visibility diverged from exact native receiver admission or full-reset "
                        + "root closes before latest authoritative all-ready publication");
        int finalIdentityReadiness = liveObserve.indexOf("observeCompletion(stats, submitIndex);");
        require(finalIdentityReadiness > observedUpdate,
                "G6 recorded old-epoch readiness before applying the submit's final identity");

        String metalDevice = workspaceSource(
                "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
        );
        require(metalDevice.contains("GiLiveCoordinator.STATUS_RETAINED_HISTORY")
                        && metalDevice.contains(
                        "this.giLiveFrameCompatible = liveStatus\n"
                                + "                                        != GiLiveCoordinator.STATUS_INPUT_NOT_READY;"),
                "G6 did not distinguish retained history from structurally unsafe input");
        String g6Admission = slice(
                metalDevice,
                "private void reportG6TerrainDraw(",
                "/**\n     * Consumes at most one already-complete Sodium snapshot."
        );
        String benchmarkController = workspaceSource(
                "src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java"
        );
        String liveRuntime = workspaceSource(
                "src/main/java/com/metallum/client/gi/live/GiLiveRuntime.java"
        );
        int latestReceipt = g6Admission.indexOf("GiLiveRuntime.publishTerrainBinding(");
        int warmupGate = g6Admission.indexOf("!GiLiveRuntime.isBenchmarkWarmup()");
        int admissionLog = g6Admission.indexOf(
                "METALLUM_BENCHMARK EVENT=GI_G6_ADMISSION "
        );
        int emittedReceipt = g6Admission.indexOf(
                "GiLiveRuntime.reportAdmissionReceiptEmitted("
        );
        require(g6Admission.contains("GiLiveRuntime.isBenchmarkWarmup()")
                        && !g6Admission.contains("GiTransportRuntime.isBenchmarkWarmup()")
                        && latestReceipt >= 0 && latestReceipt < warmupGate
                        && admissionLog >= 0 && admissionLog < emittedReceipt
                        && g6Admission.contains("latestBindStatus")
                        && g6Admission.contains("latestFrameCompatible")
                        && benchmarkController.contains(
                        "GiLiveRuntime.beginBenchmarkWarmup();")
                        && benchmarkController.contains(
                        "GiLiveRuntime.beginBenchmarkMeasurement();")
                        && benchmarkController.contains(
                        "GiLiveRuntime.finalReceiptIsCurrent(")
                        && liveRuntime.contains(
                        "deviceGenerationCounter = Math.incrementExact(deviceGenerationCounter);")
                        && liveRuntime.contains("dynamicSources = null;")
                        && liveRuntime.contains("dynamicSourceTick = -1L;"),
                "G6 latest-bind/admission/final receipt chain is no longer strict and reset-safe");
    }

    private static void testTerminalFailClosedPublicationGuards() throws IOException {
        String completionTracker = workspaceSource(
                "src/main/java/com/metallum/client/gi/live/GiLiveCompletionTracker.java"
        );
        require(completionTracker.contains("MAX_ASYNC_COMPLETION_FAILURES = 3")
                        && completionTracker.contains("exactlyOneGreater(")
                        && completionTracker.contains("State.INVALID_COMPLETION")
                        && completionTracker.contains("State.STALE_COMPLETION")
                        && completionTracker.contains(
                        "nativeFieldGeneration != this.epochVersion")
                        && !completionTracker.contains("awaitReady(")
                        && !completionTracker.contains("Arena.allocate"),
                "G6 completion tracker is not exact, bounded and allocation-free");

        String coordinator = workspaceSource(
                "src/main/java/com/metallum/client/gi/live/GiLiveCoordinator.java"
        );
        String observe = slice(
                coordinator, "public int observeAndEncode(", "public int bindVertex("
        );
        String retirement = slice(
                coordinator, "private int retirePendingBatch(",
                "private void failClosedPreparedBatch("
        );
        int retirementCall = observe.indexOf("int retirementStatus = retirePendingBatch(stats);");
        int terminalReturn = observe.indexOf("return retirementStatus;", retirementCall);
        int observation = observe.indexOf("boolean changed = observationChanged(");
        int exactCompletion = retirement.indexOf(
                "this.exactMasks[cascade] |= this.completion.batchMask();");
        int cumulativeAck = retirement.indexOf(
                "acknowledgeFullyExactCascade(cascade);");
        int acceptedClear = retirement.indexOf(
                "this.completion.clearAccepted();", cumulativeAck);
        require(coordinator.contains("STATUS_ASYNC_COMPLETION_FAILED = -11")
                        && retirementCall >= 0 && terminalReturn > retirementCall
                        && observe.substring(retirementCall, terminalReturn).contains(
                        "publishFinalTelemetry(this.resources.stats());")
                        && observation > terminalReturn
                        && retirement.contains("State.IDLE")
                        && retirement.contains("State.IN_FLIGHT")
                        && retirement.contains("State.COMPLETED_CURRENT")
                        && retirement.contains("State.FAILED_CURRENT")
                        && retirement.contains("State.STALE_COMPLETION")
                        && retirement.contains("this.scheduler.completeBatch(")
                        && retirement.contains("this.scheduler.retryBatch(")
                        && retirement.contains("retryWouldRepeatScrollRemap")
                        && retirement.contains("retryWouldRepeatNonIdempotentScroll(")
                        && retirement.contains("this.completion.recordFailure()")
                        && retirement.contains("STATUS_ASYNC_COMPLETION_FAILED")
                        && exactCompletion >= 0 && cumulativeAck > exactCompletion
                        && acceptedClear > cumulativeAck
                        && !retirement.contains("awaitReady(")
                        && !retirement.contains("Arena.allocate"),
                "G6 accepted completion does not retry twice then propagate terminal -11");

        String cumulativeAckBody = slice(
                coordinator,
                "private void acknowledgeFullyExactCascade(",
                "static boolean matchesAuthoritativeSourceIdentity("
        );
        String directCoordinator = workspaceSource(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
        );
        require(coordinator.contains(
                        "private final GiDirectSourceCoordinator.LiveSource[] "
                                + "authoritativeSources")
                        && coordinator.contains(
                        "this.authoritativeSources[cascade] = source;")
                        && cumulativeAckBody.contains("this.activeEpochAuthoritative")
                        && cumulativeAckBody.contains(
                        "this.exactMasks[cascade] != GiLiveLayout.ALL_BRICKS_MASK")
                        && cumulativeAckBody.contains(
                        "!matchesAuthoritativeSourceIdentity(epoch, source)")
                        && cumulativeAckBody.contains(
                        "this.direct.acknowledgeLiveAffectedBrickMask(cascade, source)")
                        && directCoordinator.contains(
                        "LiveSource current = liveTransportSource(")
                        && directCoordinator.contains(
                        "!sameLiveSourceIdentity(current, acknowledgedSource)")
                        && directCoordinator.contains(
                        "acknowledgedSource.cumulativeAffectedBrickMask()")
                        && directCoordinator.contains(
                        "!= this.unacknowledgedAffectedBrickMasks[cascade]"),
                "G3 -> G6 cumulative affected-mask acknowledgement is not exact/race closed");

        String runtime = workspaceSource(
                "src/main/java/com/metallum/client/gi/live/GiLiveRuntime.java"
        );
        String invalidation = slice(
                runtime,
                "public static synchronized void reportInvalid(",
                "public static void publishDynamicSources("
        );
        String publication = slice(
                runtime,
                "public static synchronized void publishDynamicSources(",
                "public static synchronized void closeDynamicWorld("
        );
        require(invalidation.contains("admissionState = AdmissionState.INVALID;")
                        && invalidation.contains("dynamicSources = null;")
                        && invalidation.contains("dynamicSourceTick = -1L;")
                        && publication.contains("if (!isOperational()) return;"),
                "terminal G6 admission no longer atomically retires/rejects dynamic publication");

        String extraction = workspaceSource(
                "src/main/java/com/metallum/mixin/lighting/"
                        + "LevelExtractorAdvancedLightMixin.java"
        );
        String beginTick = slice(
                extraction,
                "private void metallum$beginGiDynamicSourceTick",
                "private void metallum$offerCameraHeldLight"
        );
        require(beginTick.contains("if (!GiLiveRuntime.isOperational()"),
                "LevelExtractor still opens G6 source ticks after terminal invalidation");

        String metalDevice = workspaceSource(
                "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
        );
        String liveStatusPath = slice(
                metalDevice, "int liveStatus = this.commandEncoder.encodeGiLive(",
                "} catch (RuntimeException exception) {"
        );
        require(liveStatusPath.contains(
                        "GiLiveRuntime.reportInvalid(\n"
                                + "                                        \"native G6 status \" + liveStatus")
                        && !liveStatusPath.contains(
                        "liveStatus == GiLiveCoordinator.STATUS_ASYNC_COMPLETION_FAILED"),
                "G6 terminal completion status no longer reaches the existing INVALID path");
        String directSourcePath = slice(
                metalDevice,
                "// G4 latches one frozen G3 generation.",
                "// Re-prove the live tuple every submit."
        );
        require(directSourcePath.contains(
                        "GiTransportRuntime.isPopulationRequested() || GiLiveRuntime.isOperational()")
                        && directSourcePath.contains(
                        "if (GiLiveRuntime.isOperational()) {")
                        && directSourcePath.contains(
                        "this.commandEncoder.encodeGiDirectSource("),
                "terminal G6 invalidation can still enter the live G3 source encoder");
    }

    private static void testRealGeneratedMslForEveryTerrainFlavor() throws Exception {
        String sodiumVertex = preprocess(
                SODIUM_NAMESPACE, SODIUM_TERRAIN,
                MetallumMaterialShaderPatcher.Stage.VERTEX
        );
        String sodiumFragment = preprocess(
                SODIUM_NAMESPACE, SODIUM_TERRAIN,
                MetallumMaterialShaderPatcher.Stage.FRAGMENT
        );
        MetallumMaterialShaderPatcher.Result materialVertex =
                MetallumMaterialShaderPatcher.patch(
                        SODIUM_NAMESPACE, SODIUM_TERRAIN,
                        MetallumMaterialShaderPatcher.Stage.VERTEX, sodiumVertex
                );
        MetallumMaterialShaderPatcher.Result materialFragment =
                MetallumMaterialShaderPatcher.patch(
                        SODIUM_NAMESPACE, SODIUM_TERRAIN,
                        MetallumMaterialShaderPatcher.Stage.FRAGMENT, sodiumFragment
                );
        require(materialVertex.success() && materialFragment.success(),
                "real Sodium material source failed to patch");

        Map<String, ShaderDefines> variants = new LinkedHashMap<>();
        variants.put("solid", ShaderDefines.builder()
                .define("USE_VERTEX_COMPRESSION").define("USE_FOG").build());
        variants.put("cutout", ShaderDefines.builder()
                .define("USE_VERTEX_COMPRESSION").define("USE_FOG")
                .define("ALPHA_CUTOUT", 0.5F).build());
        variants.put("translucent", ShaderDefines.builder()
                .define("USE_VERTEX_COMPRESSION").define("USE_FOG")
                .define("ALPHA_CUTOUT", 0.01F).build());

        boolean negativeValidatorChecked = false;
        for (TerrainEnvironmentSpecialization specialization
                : TerrainEnvironmentSpecialization.values()) {
            AdvancedDirectLightingShaderPatcher.Result advancedVertex =
                    AdvancedDirectLightingShaderPatcher.patch(
                            SODIUM_NAMESPACE, SODIUM_TERRAIN,
                            MetallumMaterialShaderPatcher.Stage.VERTEX,
                            LightingModel.ADVANCED, materialVertex.source(),
                            specialization, false
                    );
            AdvancedDirectLightingShaderPatcher.Result advancedFragment =
                    AdvancedDirectLightingShaderPatcher.patch(
                            SODIUM_NAMESPACE, SODIUM_TERRAIN,
                            MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                            LightingModel.ADVANCED, materialFragment.source(),
                            specialization, false
                    );
            require(advancedVertex.success() && advancedFragment.success(),
                    "real Sodium Advanced " + specialization + " source failed to patch");
            GiLiveReceiverShaderPatcher.Result liveVertex =
                    GiLiveReceiverShaderPatcher.patch(
                            GiLiveReceiverShaderPatcher.Stage.VERTEX,
                            advancedVertex.source()
                    );
            GiLiveReceiverShaderPatcher.Result liveFragment =
                    GiLiveReceiverShaderPatcher.patch(
                            GiLiveReceiverShaderPatcher.Stage.FRAGMENT,
                            advancedFragment.source()
                    );
            require(liveVertex.success() && liveFragment.success(),
                    "G6 rejected real Sodium " + specialization + " source: vertex="
                            + liveVertex.failureReason() + ", fragment="
                            + liveFragment.failureReason());
            require(exactlyOneRhoOverPiAfterGiComposition(liveFragment.source()),
                    "real Sodium " + specialization
                            + " source did not compose GI through one downstream rho/pi");

            for (Map.Entry<String, ShaderDefines> variant : variants.entrySet()) {
                String offVertexMsl = compileToMsl(
                        advancedVertex.source(), ShaderType.VERTEX, variant.getValue()
                );
                String offFragmentMsl = compileToMsl(
                        advancedFragment.source(), ShaderType.FRAGMENT, variant.getValue()
                );
                GiReceiverBindingAbi.validateMsl(
                        offVertexMsl, offFragmentMsl, false, 3
                );

                String vertexMsl = compileToMsl(
                        liveVertex.source(), ShaderType.VERTEX, variant.getValue()
                );
                String fragmentMsl = compileToMsl(
                        liveFragment.source(), ShaderType.FRAGMENT, variant.getValue()
                );
                validateGeneratedMsl(
                        specialization + "/" + variant.getKey(), vertexMsl, fragmentMsl
                );
                if (!negativeValidatorChecked) {
                    String missingRead = replaceFirstExactly(
                            vertexMsl,
                            GiReceiverBindingAbi.SH_RED_SAMPLER + ".sample(",
                            GiReceiverBindingAbi.SH_RED_SAMPLER + ".removedSample("
                    );
                    expectIllegalState(() -> GiReceiverBindingAbi.validateMsl(
                            missingRead, fragmentMsl, true, 3
                    ));
                    expectIllegalState(() -> GiReceiverBindingAbi.validateMsl(
                            vertexMsl,
                            fragmentMsl + "\ntexture3d<float> "
                                    + GiReceiverBindingAbi.SH_RED_SAMPLER + ";",
                            true, 3
                    ));
                    negativeValidatorChecked = true;
                }
                System.out.println("GI_G6_GENERATED_MSL specialization="
                        + specialization + " variant=" + variant.getKey()
                        + " vertex_sha256=" + sha256(vertexMsl)
                        + " fragment_sha256=" + sha256(fragmentMsl));
            }
        }

        AdvancedDirectLightingShaderPatcher.Result reflectionVertex =
                AdvancedDirectLightingShaderPatcher.patch(
                        SODIUM_NAMESPACE, SODIUM_TERRAIN,
                        MetallumMaterialShaderPatcher.Stage.VERTEX,
                        LightingModel.ADVANCED, materialVertex.source(),
                        TerrainEnvironmentSpecialization.FULL, true
                );
        AdvancedDirectLightingShaderPatcher.Result reflectionFragment =
                AdvancedDirectLightingShaderPatcher.patch(
                        SODIUM_NAMESPACE, SODIUM_TERRAIN,
                        MetallumMaterialShaderPatcher.Stage.FRAGMENT,
                        LightingModel.ADVANCED, materialFragment.source(),
                        TerrainEnvironmentSpecialization.FULL, true
                );
        GiLiveReceiverShaderPatcher.Result liveReflectionVertex =
                GiLiveReceiverShaderPatcher.patch(
                        GiLiveReceiverShaderPatcher.Stage.VERTEX,
                        reflectionVertex.source()
                );
        GiLiveReceiverShaderPatcher.Result liveReflectionFragment =
                GiLiveReceiverShaderPatcher.patch(
                        GiLiveReceiverShaderPatcher.Stage.FRAGMENT,
                        reflectionFragment.source()
                );
        require(liveReflectionVertex.success() && liveReflectionFragment.success(),
                "G6 rejected the real reflected Sodium flavor");
        ShaderDefines solid = variants.get("solid");
        String reflectionVertexMsl = compileToMsl(
                liveReflectionVertex.source(), ShaderType.VERTEX, solid
        );
        String reflectionFragmentMsl = compileToMsl(
                liveReflectionFragment.source(), ShaderType.FRAGMENT, solid
        );
        validateGeneratedMsl(
                "FULL/reflection-solid", reflectionVertexMsl, reflectionFragmentMsl
        );
        require(reflectionVertexMsl.contains("metallumReflectionRadiance")
                        && !reflectionFragmentMsl.contains(
                        "texture3d<float> " + GiReceiverBindingAbi.SH_RED_SAMPLER),
                "generated G6/reflection MSL lost coexistence or moved GI reads to fragment");
        System.out.println("GI_G6_REFLECTION_GENERATED_MSL vertex_sha256="
                + sha256(reflectionVertexMsl) + " fragment_sha256="
                + sha256(reflectionFragmentMsl));
    }

    private static void validateGeneratedMsl(
            final String flavor,
            final String vertexMsl,
            final String fragmentMsl
    ) {
        GiReceiverBindingAbi.validateMsl(vertexMsl, fragmentMsl, true, 3);
        int[] slots = GiReceiverBindingAbi.textureSlots();
        for (int index = 0; index < slots.length; index++) {
            String sampler = GiReceiverBindingAbi.samplerNames().get(index);
            require(count(vertexMsl, sampler + ".sample(") == 3
                            && count(vertexMsl,
                            "[[texture(" + slots[index] + ")]]") == 1
                            && count(vertexMsl,
                            "[[sampler(" + slots[index] + ")]]") == 1,
                    flavor + " generated MSL lost three vertex reads/bindings for " + sampler);
            require(!fragmentMsl.contains("texture3d<float> " + sampler)
                            && !fragmentMsl.contains("sampler " + sampler + "Smplr")
                            && !fragmentMsl.contains(sampler + ".sample("),
                    flavor + " generated fragment MSL retained receiver " + sampler);
        }
        require(!fragmentMsl.contains("texture3d<")
                        && count(vertexMsl,
                        "[[buffer(" + GiReceiverLayout.PARAMS_BUFFER_SLOT + ")]]") == 1
                        && !fragmentMsl.contains(
                        "[[buffer(" + GiReceiverLayout.PARAMS_BUFFER_SLOT + ")]]")
                        && vertexMsl.contains(GiReceiverBindingAbi.VARYING)
                        && fragmentMsl.contains("in." + GiReceiverBindingAbi.VARYING)
                        && count(vertexMsl, "sampleableBrickMasks") >= 4
                        && !fragmentMsl.contains("sampleableBrickMasks"),
                flavor + " generated MSL is not vertex-only");
    }

    private static boolean exactlyOneRhoOverPiAfterGiComposition(final String source) {
        int composition = source.indexOf("vec3 metallumGiFallbackAmbient =");
        int firstReturn = source.indexOf(
                "return albedo * diffuse * " + INVERSE_PI + ";", composition
        );
        if (composition < 0 || firstReturn < composition) {
            return false;
        }
        String between = source.substring(composition, firstReturn);
        int returnEnd = firstReturn
                + ("return albedo * diffuse * " + INVERSE_PI + ";").length();
        return count(between, INVERSE_PI) == 0
                && count(source.substring(firstReturn, returnEnd), INVERSE_PI) == 1;
    }

    private static String basicVertexSource() {
        return """
                #version 450
                out float metallumSkyVisibility;
                // METALLUM_ADVANCED_DIRECT_LIGHTING_V1
                void main() {
                    vec3 position = _vert_position + translation;
                    metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
                }
                """;
    }

    private static String reflectionVertexSource() {
        return """
                #version 450
                out float metallumSkyVisibility;
                // METALLUM_ADVANCED_DIRECT_LIGHTING_V1
                void main() {
                    vec3 position = _vert_position + translation;
                    metallumLightingPosition = (u_ModelViewMatrix * vec4(position, 1.0)).xyz;
                    uint metallumReflectionFaceCode = 2u;
                    bool metallumReflectionGiAxisEligible = true;
                    vec3 metallumWorldPos = vec3(1.0);
                    vec4 metallumCoarseReflectionDirectionVal = vec4(0.0);
                    metallumCoarseReflectionDirection = metallumCoarseReflectionDirectionVal;
                }
                """;
    }

    private static String fragmentSource() {
        return """
                #version 450
                in float metallumSkyVisibility;
                // METALLUM_ADVANCED_DIRECT_LIGHTING_V1
                vec3 environment(vec3 albedo) {
                    vec3 diffuse = max(metallumEnvironment.ambientRadiance.rgb, vec3(0.0));
                    diffuse += skyAndSun;
                    return albedo * diffuse * 0.31830988618;
                }
                """;
    }

    private static String compileToMsl(
            final String glslSource,
            final ShaderType stage,
            final ShaderDefines defines
    ) throws ShaderCompileException {
        String prepared = GlslPreprocessor.injectDefines(glslSource, defines);
        try (GlslCompiler glslCompiler = new GlslCompiler();
             IntermediaryShaderModule module = glslCompiler.createIntermediary(
                     "gi_g6_live_receiver", prepared, stage
             );
             MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer words = module.spirv().asIntBuffer();
            PointerBuffer pointer = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pointer), "create SPIRV-Cross context");
            long context = pointer.get(0);
            try {
                checkSpvc(Spvc.spvc_context_parse_spirv(
                        context, words, words.remaining(), pointer
                ), "parse SPIR-V");
                long ir = pointer.get(0);
                checkSpvc(Spvc.spvc_context_create_compiler(
                        context, Spvc.SPVC_BACKEND_MSL, ir,
                        Spvc.SPVC_CAPTURE_MODE_COPY, pointer
                ), "create MSL compiler");
                long compiler = pointer.get(0);
                checkSpvc(Spvc.spvc_compiler_create_compiler_options(
                        compiler, pointer
                ), "create MSL options");
                long options = pointer.get(0);
                checkSpvc(Spvc.spvc_compiler_options_set_uint(
                        options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM,
                        Spvc.SPVC_MSL_PLATFORM_MACOS
                ), "select macOS");
                checkSpvc(Spvc.spvc_compiler_options_set_uint(
                        options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, 0x040000
                ), "select MSL 4.0");
                checkSpvc(Spvc.spvc_compiler_options_set_bool(
                        options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true
                ), "preserve explicit bindings");
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(
                        compiler, options
                ), "install options");
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(
                        compiler, pointer
                ), "collect active interface");
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(
                        compiler, pointer.get(0)
                ), "enable active interface");
                checkSpvc(Spvc.spvc_compiler_compile(
                        compiler, pointer
                ), "compile MSL");
                return MemoryUtil.memUTF8(pointer.get(0));
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    private static String preprocess(
            final String namespace,
            final String path,
            final MetallumMaterialShaderPatcher.Stage stage
    ) throws IOException {
        String extension = stage == MetallumMaterialShaderPatcher.Stage.VERTEX
                ? ".vsh" : ".fsh";
        String source = resource("assets/" + namespace + "/shaders/" + path + extension);
        Set<String> imported = new HashSet<>();
        GlslPreprocessor preprocessor = new GlslPreprocessor() {
            @Override
            public String applyImport(final boolean relative, final String importPath) {
                String importNamespace = namespace;
                String relativePath = importPath;
                int separator = importPath.indexOf(':');
                if (!relative && separator > 0) {
                    importNamespace = importPath.substring(0, separator);
                    relativePath = importPath.substring(separator + 1);
                }
                String resourcePath = "assets/" + importNamespace
                        + "/shaders/include/" + relativePath;
                if (!imported.add(resourcePath)) {
                    return null;
                }
                return resourceOrNull(resourcePath);
            }
        };
        return String.join("", preprocessor.process(source));
    }

    private static String resource(final String path) throws IOException {
        String source = resourceOrNull(path);
        if (source == null) {
            throw new IOException("Required runtime shader resource is missing: " + path);
        }
        return source;
    }

    private static String resourceOrNull(final String path) {
        ClassLoader loader = GiLiveReceiverSourceChainTests.class.getClassLoader();
        try (InputStream stream = loader.getResourceAsStream(path)) {
            if (stream == null) {
                return null;
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read shader resource " + path, exception);
        }
    }

    private static String workspaceSource(final String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static String slice(
            final String source,
            final String startToken,
            final String endToken
    ) {
        int start = source.indexOf(startToken);
        int end = start < 0 ? -1 : source.indexOf(endToken, start + startToken.length());
        if (start < 0 || end < 0 || end <= start) {
            throw new AssertionError(
                    "Required source-chain slice is missing: " + startToken + " .. " + endToken
            );
        }
        return source.substring(start, end);
    }

    private static void checkSpvc(final int result, final String stage)
            throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException(
                    stage + " failed with SPIRV-Cross status " + result
            );
        }
    }

    private static String sha256(final String source) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(source.getBytes(StandardCharsets.UTF_8))
        );
    }

    private static String replaceFirstExactly(
            final String source,
            final String target,
            final String replacement
    ) {
        int position = source.indexOf(target);
        if (position < 0) {
            throw new AssertionError("Required generated-MSL mutation target is missing: " + target);
        }
        return source.substring(0, position) + replacement
                + source.substring(position + target.length());
    }

    private static void expectIllegalState(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static int count(final String source, final String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    private static String normalizeWhitespace(final String source) {
        return source.replaceAll("\\s+", " ").trim();
    }

    private static int matchingBrace(final String source, final int openingBrace) {
        if (openingBrace < 0 || openingBrace >= source.length()
                || source.charAt(openingBrace) != '{') {
            return -1;
        }
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
