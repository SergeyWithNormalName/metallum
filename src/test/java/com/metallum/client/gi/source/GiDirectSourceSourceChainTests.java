package com.metallum.client.gi.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Ensures G3 stays detached from view/receiver/G4 implementation paths except its opaque handoff. */
public final class GiDirectSourceSourceChainTests {
    private GiDirectSourceSourceChainTests() {
    }

    public static void main(final String[] args) throws IOException {
        Path root = Path.of("src/main/java/com/metallum/client/gi/source");
        List<String> forbidden = List.of(
                "directlightfrustum", "snapshotforframe", "publishdynamicframe",
                "lightmap", "brightness", "screen", "depth", "history",
                "receiver", "albedo", "reflectance"
        );
        try (var paths = Files.walk(root)) {
            for (Path source : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String content = Files.readString(source).toLowerCase(java.util.Locale.ROOT);
                for (String forbiddenToken : forbidden) {
                    if (content.contains(forbiddenToken)) {
                        throw new AssertionError(source + " retains forbidden G3 source-chain token " + forbiddenToken);
                    }
                }
                if (content.contains("com.metallum.client.gi.transport")
                        || content.contains("gisemantictransportfieldview")) {
                    throw new AssertionError(source
                            + " depends on G4 implementation instead of exposing an opaque source owner");
                }
            }
        }
        resourceReloadRootEpochIsExplicitlyGuarded();
        metadataOnlyRelabelIsWiredAndNonDestructive();
        coherentEnvironmentBoundaryIsLiveOnlyAndFailClosedAtWorldStart();
        asynchronousAcceptedBatchIsRetiredExactlyOnce();
        sameCommandLiveHandoffIsExactAndFailClosed();
        cumulativeLiveMasksAreAcknowledgedPerCascadeAndRaceClosed();
    }

    private static void resourceReloadRootEpochIsExplicitlyGuarded() throws IOException {
        String queue = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectDirtyQueue.java"
        ));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
        ));
        String swift = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        require(queue.contains("next.g2WorldGeneration() <= previous.g2WorldGeneration()")
                        && queue.contains("previous=\"")
                        && queue.contains("\", next=\"")
                        && coordinator.contains("this.dirtyQueue.activeEpoch()")
                        && coordinator.contains("G3 coordinator/queue epoch invariant failed"),
                "G3 resource-reload root rotation lost strict same-root rejection or tuple detail");
        require(coordinator.contains("enum ProductionRootRelation { SAME, ADVANCE, STALE }")
                        && coordinator.contains("ProductionRootRelation rootRelation =")
                        && coordinator.contains(
                        "rootRelation == ProductionRootRelation.STALE")
                        && coordinator.contains("return STATUS_INPUT_NOT_READY;")
                        && coordinator.contains(
                        "rootRelation == ProductionRootRelation.ADVANCE")
                        && coordinator.contains("resetActiveSource();")
                        && coordinator.contains("liveSourceObservationBlocked")
                        && coordinator.contains("production_root_regressed"),
                "G3 production root does not separate monotonic reset from stale no-mutation");
        int resetStart = swift.indexOf(
                "    func reset(world: UInt64, clipmap: UInt64, palette: UInt64,");
        int resetEnd = swift.indexOf(
                "\n    private func copyCompactRows", resetStart);
        require(resetStart >= 0 && resetEnd > resetStart,
                "native G3 trusted reset body is missing");
        String reset = swift.substring(resetStart, resetEnd);
        require(reset.contains("guard !buildInFlight")
                        && reset.contains("guard world >= worldGeneration")
                        && reset.contains("populationEpoch = nil")
                        && reset.contains("populationMasks = (0, 0, 0)")
                        && reset.contains("ready = false")
                        && !reset.contains("clipmap >= clipmapGeneration"),
                "native G3 reset cannot safely restart same-world children after owner advance");
    }

    private static void metadataOnlyRelabelIsWiredAndNonDestructive() throws IOException {
        String symbol = "metallum_gi_direct_source_relabel_v1";
        String swift = Files.readString(Path.of("src/main/native/MetallumNative.swift"));
        String bridge = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java"
        ));
        String resources = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceGpuResources.java"
        ));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
        ));
        require(swift.contains("@_cdecl(\"" + symbol + "\")")
                        && bridge.contains("\"" + symbol + "\"")
                        && bridge.contains("public static int " + symbol + "(")
                        && resources.contains("MetalNativeBridge." + symbol + "("),
                "G3 metadata-only relabel ABI is not wired through Swift/FFM/resources");
        require(coordinator.contains("if (count == 0)")
                        && coordinator.contains("this.resources.fieldMatches(nextEpoch)")
                        && coordinator.contains("this.resources.relabelMetadataOnly(")
                        && coordinator.contains("metadataRelabelPending"),
                "G3 zero-dirty coordinator path does not retry metadata relabel");
        require(coordinator.contains("public long observedLiveEnvironmentDigest()")
                        && coordinator.contains("return this.affectedMaskEnvironmentDigest;"),
                "G3 live environment observation does not expose the adopted digest");

        int start = swift.indexOf("    func relabelMetadataOnly(");
        int end = swift.indexOf("\n    func reset(", start);
        require(start >= 0 && end > start, "G3 native metadata-only relabel body is missing");
        String relabelBody = swift.substring(start, end);
        require(relabelBody.contains("isOwnerThread()")
                        && relabelBody.contains("!buildInFlight")
                        && relabelBody.contains("ready")
                        && relabelBody.contains("populationEpoch"),
                "G3 native relabel omits owner/readiness/population guards");
        require(!relabelBody.contains("reset(")
                        && !relabelBody.contains("clearPipeline")
                        && !relabelBody.contains("ready = false")
                        && !relabelBody.contains("populationMasks = (0, 0, 0)")
                        && !relabelBody.contains("populationCompleted = 0"),
                "G3 metadata-only relabel destroys field readiness or population proof");
    }

    private static void coherentEnvironmentBoundaryIsLiveOnlyAndFailClosedAtWorldStart()
            throws IOException {
        String latch = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiEnvironmentObservationLatch.java"
        ));
        String mixin = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/render/GameRendererMetalFxMixin.java"
        ));
        String capture = Files.readString(Path.of(
                "src/main/java/com/metallum/client/renderer/temporal/FrameCapture.java"
        ));
        String device = Files.readString(Path.of(
                "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
        ));
        require(latch.contains("observedWorldIdentity != this.worldIdentity")
                        && latch.contains("this.descriptor = null;")
                        && latch.contains("if (sourceBoundary)")
                        && latch.contains("this.descriptor = observed;"),
                "G3 environment latch can reuse an old world or admit outside its source boundary");
        int stabilized = mixin.indexOf("GiTransportRuntime.stabilizeDebugEnvironment(");
        int liveGate = mixin.indexOf("if (GiLiveRuntime.isRequested())", stabilized);
        int boundary = mixin.indexOf(
                "this.gameRenderState.lightmapRenderState.needsUpdate", liveGate
        );
        require(stabilized >= 0 && liveGate > stabilized && boundary > liveGate
                        && mixin.contains("this.metallum$giEnvironmentLatch.observe(")
                        && mixin.contains("giEnvironmentReady = coherentGiEnvironment != null;"),
                "G6 environment source is not latched at the renderer's coherent update boundary");
        require(capture.contains("boolean giEnvironmentReady")
                        && device.contains("|| !capture.giEnvironmentReady()")
                        && device.contains("&& capture.giEnvironmentReady()"),
                "unready initial/world G6 environment can still enter G3 or G6 submission");
    }

    private static void asynchronousAcceptedBatchIsRetiredExactlyOnce() throws IOException {
        String queue = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectDirtyQueue.java"
        ));
        String resources = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceGpuResources.java"
        ));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
        ));
        String swift = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        require(queue.contains("entry.inFlight = true;")
                        && queue.contains("validateInFlight(brickIds, count);")
                        && queue.contains("int ownedCount()")
                        && queue.contains("this.pendingCount, this.inFlightCount")
                        && queue.contains("Preserve the original age/sequence"),
                "G3 dirty queue does not retain exact accepted ownership");
        require(resources.contains("int pollAcceptedBatchCompletion(")
                        && resources.contains("rejected == Math.incrementExact(rejectedBaseline)")
                        && resources.contains("batches == Math.incrementExact(batchesBaseline)")
                        && resources.contains("this.stats.get(LE_INT, 0L) == 1")
                        && resources.contains("acceptedEpoch.environmentEpoch()"),
                "G3 native completion poll does not require one exact ready/current receipt");

        int submitStart = coordinator.indexOf("int status = this.resources.encode(");
        int submitEnd = coordinator.indexOf(
                "int telemetryStatus = this.resources.publishScheduler", submitStart
        );
        require(submitStart >= 0 && submitEnd > submitStart,
                "G3 accepted submit body is missing");
        String submit = coordinator.substring(submitStart, submitEnd);
        require(submit.contains("this.acceptedBatch.admit(")
                        && !submit.contains("this.dirtyQueue.completeBatch(")
                        && !submit.contains("submittedBrickStamps["),
                "G3 submit publishes or drops work before command-buffer completion");

        int retireStart = coordinator.indexOf("private int retireAcceptedBatch()");
        int retireEnd = coordinator.indexOf("\n    public GiDirectDirtyQueue.Telemetry", retireStart);
        require(retireStart >= 0 && retireEnd > retireStart,
                "G3 accepted retirement body is missing");
        String retire = coordinator.substring(retireStart, retireEnd);
        require(retire.contains("pollAcceptedBatchCompletion(")
                        && retire.contains("AcceptedBatchState.COMPLETED_CURRENT")
                        && retire.contains("this.dirtyQueue.completeBatch(")
                        && retire.contains("this.acceptedBatch.publishTo(")
                        && retire.contains("AcceptedBatchState.FAILED_CURRENT")
                        && retire.contains("this.dirtyQueue.retryBatch(")
                        && retire.contains("AcceptedBatchState.STALE_COMPLETION")
                        && retire.contains("STATUS_ASYNC_COMPLETION_FAILED")
                        && !retire.contains("awaitReady(")
                        && !retire.contains("Arena.allocate"),
                "G3 accepted retirement is not allocation-free retry-or-terminal");

        int encodeStart = swift.indexOf("    func encodeDirty(");
        int completionStart = swift.indexOf(
                "commandBuffer.addCompletedHandler { [self, slot] completed in", encodeStart
        );
        int encodeEnd = swift.indexOf(
                "return metallumGiDirectSourceStatusOK", completionStart
        );
        require(encodeStart >= 0 && completionStart > encodeStart && encodeEnd > completionStart,
                "G3 native completion handler is missing");
        String completion = swift.substring(completionStart, encodeEnd);
        require(completion.contains("completed.status == .completed")
                        && completion.contains("buildInFlight = false")
                        && completion.contains("ready = true")
                        && completion.contains("batches &+= 1")
                        && completion.contains("rejectedCount &+= 1"),
                "G3 native completion handler cannot distinguish success from rejection");
    }

    private static void cumulativeLiveMasksAreAcknowledgedPerCascadeAndRaceClosed()
            throws IOException {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
        ));
        require(coordinator.contains(
                        "private final long[] unacknowledgedAffectedBrickMasks")
                        && coordinator.contains(
                        "private final long[] untransferredAffectedBrickMasks")
                        && coordinator.contains(
                        "this.unacknowledgedAffectedBrickMasks[cascade] = "
                                + "cumulativeAffectedMaskAfterMark(")
                        && coordinator.contains(
                        "this.untransferredAffectedBrickMasks[cascade] = "
                                + "cumulativeAffectedMaskAfterMark(")
                        && coordinator.contains(
                        "this.cachedLiveSources[cascade] = null;")
                        && coordinator.contains(
                        "this.untransferredAffectedBrickMasks[cascade],")
                        && coordinator.contains(
                        "this.unacknowledgedAffectedBrickMasks[cascade],")
                        && coordinator.contains(
                        "this.untransferredAffectedBrickMasks, 0, destination, 0")
                        && coordinator.contains(
                        "Arrays.fill(this.unacknowledgedAffectedBrickMasks, -1L);")
                        && coordinator.contains(
                        "Arrays.fill(this.untransferredAffectedBrickMasks, -1L);")
                        && count(coordinator,
                        "Arrays.fill(this.unacknowledgedAffectedBrickMasks, 0L);") == 4
                        && count(coordinator,
                        "Arrays.fill(this.untransferredAffectedBrickMasks, 0L);") == 4,
                "G3 audit and plan-transfer affected-mask lifetimes are not distinct");

        int transferStart = coordinator.indexOf(
                "public boolean transferLiveAffectedBrickMask(");
        int transferEnd = coordinator.indexOf(
                "\n    /**\n     * Clears exactly one cumulative mask", transferStart);
        require(transferStart >= 0 && transferEnd > transferStart,
                "G3 plan ownership-transfer body is missing");
        String transfer = coordinator.substring(transferStart, transferEnd);
        require(transfer.contains("liveTransportSourceForSubmit(")
                        && transfer.contains("commandBuffer, fence, sourceTick")
                        && transfer.contains("!sameLiveSourceIdentity(current, plannedSource)")
                        && transfer.contains("current.affectedBrickMask() != plannedMask")
                        && transfer.contains(
                        "plannedMask != this.untransferredAffectedBrickMasks[cascade]")
                        && transfer.contains("untransferredAffectedMaskAfterPlan(")
                        && transfer.contains("this.cachedLiveSources[cascade] = null;"),
                "G3 transfer is not exact-current, same-submit, mask-complete, or race closed");

        int ackStart = coordinator.indexOf(
                "public boolean acknowledgeLiveAffectedBrickMask(");
        int ackEnd = coordinator.indexOf(
                "\n    private static boolean sameLiveSourceIdentity(", ackStart);
        require(ackStart >= 0 && ackEnd > ackStart,
                "G3 cumulative live acknowledgement body is missing");
        String ack = coordinator.substring(ackStart, ackEnd);
        require(ack.contains("LiveSource current = liveTransportSource(")
                        && ack.contains("!sameLiveSourceIdentity(current, acknowledgedSource)")
                        && ack.contains("acknowledgedSource.cumulativeAffectedBrickMask()")
                        && ack.contains("!= this.unacknowledgedAffectedBrickMasks[cascade]")
                        && ack.contains(
                        "this.unacknowledgedAffectedBrickMasks[cascade] =")
                        && ack.contains("this.cachedLiveSources[cascade] = null;")
                        && !ack.contains("Arrays.fill("),
                "G3 acknowledgement is not exact-current, cascade-local, or race closed");
    }

    private static void sameCommandLiveHandoffIsExactAndFailClosed() throws IOException {
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
        ));
        String live = Files.readString(Path.of(
                "src/main/java/com/metallum/client/gi/live/GiLiveCoordinator.java"
        ));
        String swift = Files.readString(Path.of("src/main/native/MetallumNative.swift"));

        int acceptedStart = coordinator.indexOf("static final class AcceptedBatch");
        int acceptedEnd = coordinator.indexOf(
                "\n    /** Public, handle-free identity", acceptedStart
        );
        require(acceptedStart >= 0 && acceptedEnd > acceptedStart,
                "G3 accepted-batch source proof is missing");
        String accepted = coordinator.substring(acceptedStart, acceptedEnd);
        require(accepted.contains("private long commandBufferAddress;")
                        && accepted.contains("private long fenceAddress;")
                        && accepted.contains("boolean matchesSubmission(")
                        && accepted.contains("this.sourceTick == expectedSourceTick")
                        && accepted.contains("boolean closesEveryGapInCascade(")
                        && accepted.contains("this.brickStamps[index] == desiredBrickStamps[brick]")
                        && accepted.contains("this.sourceKeys[index] == desiredSourceKeys[brick]")
                        && accepted.contains("if (!covered) return false;"),
                "G3 projected source is not exact to CB/fence/tick and every cascade gap");

        int sourceStart = coordinator.indexOf(
                "public @Nullable LiveSource liveTransportSourceForSubmit("
        );
        int sourceEnd = coordinator.indexOf(
                "\n    /**\n     * Lifetime scheduler counters", sourceStart
        );
        require(sourceStart >= 0 && sourceEnd > sourceStart,
                "G3 same-submit source body is missing");
        String source = coordinator.substring(sourceStart, sourceEnd);
        require(source.contains("MetalNativeBridge.isNullHandle(commandBuffer)")
                        && source.contains("this.acceptedBatch.epoch().equals(this.activeEpoch)")
                        && source.contains("this.dirtyQueue.inFlightCount()")
                        && source.contains("this.acceptedBatch.matchesSubmission(")
                        && source.contains("this.acceptedBatch.closesEveryGapInCascade(")
                        && source.contains("if (!settled && !projected)"),
                "G3 live source relaxed the settled gate without exact accepted ownership");
        require(count(live, "liveTransportSourceForSubmit(") == 3
                        && live.contains("commandBuffer, fence, inputSourceTick")
                        && live.contains("g3_source=")
                        && coordinator.contains("accepted_closes="),
                "G6 does not use or diagnose the exact same-submit source on every query");
        int planStart = live.indexOf("private boolean prepareNextCascadePlan(");
        int planEnd = live.indexOf("\n    /**\n     * Advances G3's cumulative-mask", planStart);
        require(planStart >= 0 && planEnd > planStart,
                "G6 authoritative plan body is missing");
        String plan = live.substring(planStart, planEnd);
        int nativePlan = plan.indexOf("this.resources.planCascade(");
        int transfer = plan.indexOf("this.direct.transferLiveAffectedBrickMask(");
        require(nativePlan >= 0 && transfer > nativePlan
                        && plan.substring(nativePlan, transfer).contains(
                        "if (status != GiLiveLayout.STATUS_OK)")
                        && plan.contains("conservativeMaskAfterAcceptedPlan(")
                        && plan.contains("source.affectedBrickMask()"),
                "G6 clears source ownership before native plan success or loses pre-dispatch dirt");

        int directStart = swift.indexOf("private final class MetallumGiDirectSourceContextV1");
        int directEnd = swift.indexOf(
                "private enum MetallumGiDirectSourceContextRegistryV1", directStart
        );
        require(directStart >= 0 && directEnd > directStart,
                "native G3 direct context source is missing");
        String direct = swift.substring(directStart, directEnd);
        int snapshotStart = direct.indexOf("    func liveTransportSnapshot(");
        int snapshotEnd = direct.indexOf(
                "\n    func isTransportAttachmentOwnerThread()", snapshotStart
        );
        require(snapshotStart >= 0 && snapshotEnd > snapshotStart,
                "native same-command source snapshot is missing");
        String snapshot = direct.substring(snapshotStart, snapshotEnd);
        require(direct.contains("private var inFlightCommandBufferAddress: UInt = 0")
                        && direct.contains("private var inFlightFenceAddress: UInt = 0")
                        && direct.contains(
                        "private var inFlightPopulationEpoch: MetallumGiDirectPopulationEpochV1?")
                        && direct.contains(
                        "inFlightCommandBufferAddress = objectAddress(commandBuffer)")
                        && direct.contains("inFlightFenceAddress = fence.map(objectAddress) ?? 0")
                        && direct.contains("encoder.updateFence(fence)")
                        && direct.contains("clearInFlightSourceIdentityLocked()"),
                "native G3 did not retain and fence the exact admitted command identity");
        require(snapshot.contains("let settled = ready && !buildInFlight")
                        && snapshot.contains("let sameCommandProjected = buildInFlight")
                        && snapshot.contains("commandBuffer.status == .notEnqueued")
                        && snapshot.contains(
                        "inFlightCommandBufferAddress == objectAddress(commandBuffer)")
                        && snapshot.contains("inFlightFenceAddress == objectAddress($0)")
                        && snapshot.contains("inFlightPopulationEpoch?.matchesCurrentContext(")
                        && snapshot.contains("guard schedulerOwned, settled || sameCommandProjected"),
                "native G3 snapshot admits another command buffer/header or drops settled gating");

        int liveEncodeStart = swift.indexOf("    func encodeCascade(");
        int liveEncodeEnd = swift.indexOf(
                "\n    func bindVertex(", liveEncodeStart
        );
        require(liveEncodeStart >= 0 && liveEncodeEnd > liveEncodeStart,
                "native G6 cascade encoder is missing");
        String liveEncode = swift.substring(liveEncodeStart, liveEncodeEnd);
        require(liveEncode.contains(
                        "commandBuffer: commandBuffer, fence: fence,")
                        && liveEncode.contains("cascade: cascade, header: header")
                        && liveEncode.contains("encoder.waitForFence(fence)")
                        && liveEncode.contains("completed.status == .completed")
                        && liveEncode.contains("readyMask &= ~cascadeBit"),
                "native G6 does not wait for or fail closed with its same-CB G3 producer");
    }

    private static int count(final String source, final String needle) {
        int count = 0;
        for (int index = source.indexOf(needle); index >= 0;
             index = source.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
