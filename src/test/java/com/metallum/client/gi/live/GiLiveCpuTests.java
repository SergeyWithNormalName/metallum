package com.metallum.client.gi.live;

import com.metallum.client.gi.source.GiDynamicSourceEpoch;
import com.metallum.client.gi.source.GiDynamicSourceSnapshot;
import com.metallum.client.gi.source.GiDirectSourceLayout;
import com.metallum.client.lighting.LightWorldToken;

import java.util.ArrayList;
import java.util.List;

/** Dependency-free CPU checks for the G6 live epoch, scheduler, publication and SLA core. */
public final class GiLiveCpuTests {
    private GiLiveCpuTests() {
    }

    public static void main(final String[] arguments) {
        epochCarriesEveryIdentityAndThreeAlignedOrigins();
        schedulerOrderCoalescingAndCapacityAreDeterministic();
        epochRotationDiscardsPendingAndInFlightWork();
        sameGridInputRebasePreservesPendingWork();
        defaultSchedulerUsesLiveBatchContract();
        nearBurstProtectsSlaWithoutStarvingOuterCascades();
        asynchronousCompletionIsExactBoundedAndRetrySafe();
        drainBudgetDependsOnSourceTicksNotPresentedFrames();
        fullResetPublishesZeroBeforeAnyReadyField();
        incrementalClassesRetainOnlyExactValidCoverage();
        trilinearReceiverFootprintRequiresEveryTouchedBrick();
        provisionalStaticAndDynamicPlansRecoverUnaffectedExactCoverage();
        sourceEnvironmentIdentityUsesTheAdmittedG3Digest();
        unfinishedFullResetLatencySurvivesIncrementalOverlap();
        unfinishedScrollLatencySurvivesMovingSourceOverlap();
        unfinishedScrollRetainsItsBasisOnlyBeforeRemapAdmission();
        simultaneousScrollAndSourceMutationRetainsReceiverOverlap();
        unknownSourceVisibilityDoesNotPoisonCapturedFullResetProgress();
        transferredRootDirtPreservesProductionLikeStreamingProgress();
        openFullResetRetainsChildrenUntilLatestAuthoritativeAllReady();
        latencyRecoveryUsesTheReceiverVisibleCoverageBoundary();
        latencyPercentilesEnforceEveryDeclaredSla();
        terminalAdmissionStateStopsRuntimeWork();
        dynamicSourcesUseTheExactL3WorldIdentity();
        finalReceiptRequiresCurrentAdmissionAndLatestExactTerrainBinding();
        System.out.println("G6 live CPU contract tests passed");
    }

    private static void epochCarriesEveryIdentityAndThreeAlignedOrigins() {
        GiLiveEpoch first = epoch(1L, 6L, 7L, 8L, 9L);
        require(first.origin(0).equals(new GiLiveEpoch.Origin(-64, -32, -16))
                        && first.origin(1).equals(new GiLiveEpoch.Origin(-128, -64, -32))
                        && first.origin(2).equals(new GiLiveEpoch.Origin(-256, -128, -64)),
                "G6 epoch lost one of its three world-snapped origins");
        GiLiveEpoch dynamicChange = epoch(2L, 6L, 8L, 8L, 9L);
        require(first.sameWorld(dynamicChange)
                        && !first.sameContentAndSources(dynamicChange),
                "G6 epoch omitted dynamic source identity");
        dynamicChange.requireStrictlyNewerThan(first);
        expectIllegalArgument(() -> first.requireStrictlyNewerThan(dynamicChange),
                "G6 epoch version regression was accepted");
        expectIllegalArgument(() -> new GiLiveEpoch(
                        3L, "test:g6", 1L, 1L, 1L, 1L, 1L, 1L,
                        1L, 1L, 1L,
                        new GiLiveEpoch.Origin(-63, -32, -16),
                        new GiLiveEpoch.Origin(-128, -64, -32),
                        new GiLiveEpoch.Origin(-256, -128, -64)
                ), "misaligned G6 origin was accepted");
    }

    private static void schedulerOrderCoalescingAndCapacityAreDeterministic() {
        GiLiveEpoch epoch = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler(3, 2);
        scheduler.rotateEpoch(epoch);
        require(scheduler.enqueue(epoch, 5, GiLiveUpdateClass.BLOCK, 10L)
                        == GiLiveDirtyScheduler.OfferResult.ENQUEUED,
                "first G6 brick was not enqueued");
        require(scheduler.enqueue(epoch, 3, GiLiveUpdateClass.BLOCK, 10L)
                        == GiLiveDirtyScheduler.OfferResult.ENQUEUED,
                "second G6 brick was not enqueued");
        require(scheduler.enqueue(epoch, 5, GiLiveUpdateClass.STATIC_SOURCE, 10L)
                        == GiLiveDirtyScheduler.OfferResult.COALESCED,
                "duplicate G6 brick did not coalesce");
        require(scheduler.enqueue(epoch, 9, GiLiveUpdateClass.SCROLL, 10L)
                        == GiLiveDirtyScheduler.OfferResult.ENQUEUED,
                "third G6 brick was not enqueued");
        require(scheduler.enqueue(epoch, 11, GiLiveUpdateClass.BLOCK, 10L)
                        == GiLiveDirtyScheduler.OfferResult.CAPACITY,
                "bounded G6 queue exceeded capacity");

        int[] bricks = new int[2];
        GiLiveUpdateClass[] classes = new GiLiveUpdateClass[2];
        int count = scheduler.drainTo(epoch, 10L, bricks, classes);
        require(count == 2 && bricks[0] == 3 && bricks[1] == 5,
                "same-tick G6 work was not ordered by stable brick key");
        require(classes[1] == GiLiveUpdateClass.STATIC_SOURCE,
                "coalesced G6 work lost its conservative classification");
        require(scheduler.drainTo(epoch, 10L, bricks, classes) == 0,
                "extra rendered frame exceeded the source-tick drain budget");
        require(scheduler.telemetry().algebraIsExact()
                        && scheduler.telemetry().capacityRejected() == 1L,
                "G6 scheduler telemetry algebra or capacity count diverged");
    }

    private static void epochRotationDiscardsPendingAndInFlightWork() {
        GiLiveEpoch first = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveEpoch next = epoch(2L, 2L, 2L, 2L, 2L);
        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler(8, 2);
        scheduler.rotateEpoch(first);
        scheduler.enqueue(first, 0, GiLiveUpdateClass.BLOCK, 1L);
        scheduler.enqueue(first, 1, GiLiveUpdateClass.BLOCK, 1L);
        scheduler.enqueue(first, 2, GiLiveUpdateClass.BLOCK, 1L);
        int[] bricks = new int[2];
        GiLiveUpdateClass[] classes = new GiLiveUpdateClass[2];
        require(scheduler.drainTo(first, 1L, bricks, classes) == 2,
                "G6 epoch-discard fixture did not create in-flight work");
        scheduler.rotateEpoch(next);
        GiLiveDirtyScheduler.Telemetry afterRotate = scheduler.telemetry();
        require(afterRotate.discarded() == 3L && afterRotate.pending() == 0
                        && afterRotate.inFlight() == 0 && afterRotate.algebraIsExact(),
                "G6 rotation retained or miscounted superseded work");
        require(scheduler.completeBatch(first, bricks, 2)
                        == GiLiveDirtyScheduler.CompletionResult.STALE_EPOCH,
                "late G6 completion from an old epoch was accepted");
        require(scheduler.enqueue(first, 3, GiLiveUpdateClass.BLOCK, 2L)
                        == GiLiveDirtyScheduler.OfferResult.STALE_EPOCH,
                "old G6 epoch enqueued after rotation");
        require(scheduler.telemetry().discarded() == 3L
                        && scheduler.telemetry().staleRejected() == 1L,
                "late G6 work was double-counted as discarded");
    }

    private static void asynchronousCompletionIsExactBoundedAndRetrySafe() {
        GiLiveEpoch current = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveEpoch next = epoch(2L, 2L, 2L, 2L, 2L);
        GiLiveCompletionTracker tracker = new GiLiveCompletionTracker();
        require(tracker.classify(current, false, current.version(), 10L, 4L)
                        == GiLiveCompletionTracker.State.IDLE,
                "idle G6 completion proof was treated as accepted work");
        tracker.admit(2, 0, true, (1L << 3) | (1L << 7),
                current.version(), 10L, 4L);
        require(tracker.classify(current, true, current.version(), 12L, 6L)
                        == GiLiveCompletionTracker.State.IN_FLIGHT,
                "in-flight G6 completion was retired before its serialized receipt");
        require(tracker.classify(current, false, current.version(), 11L, 4L)
                        == GiLiveCompletionTracker.State.COMPLETED_CURRENT,
                "exact +1 dispatch G6 receipt did not complete the current batch");
        require(tracker.classify(current, false, current.version(), 10L, 5L)
                        == GiLiveCompletionTracker.State.FAILED_CURRENT,
                "exact +1 rejection G6 receipt did not fail the current batch");
        require(tracker.classify(current, false, current.version(), 10L, 4L)
                        == GiLiveCompletionTracker.State.INVALID_COMPLETION
                        && tracker.classify(current, false, current.version(), 12L, 4L)
                        == GiLiveCompletionTracker.State.INVALID_COMPLETION
                        && tracker.classify(current, false, current.version(), 11L, 5L)
                        == GiLiveCompletionTracker.State.INVALID_COMPLETION
                        && tracker.classify(current, false, current.version(), 9L, 4L)
                        == GiLiveCompletionTracker.State.INVALID_COMPLETION,
                "unchanged, +2, dual-change or regressed G6 counters were accepted");
        require(tracker.classify(current, false, next.version(), 11L, 4L)
                        == GiLiveCompletionTracker.State.INVALID_COMPLETION,
                "current G6 receipt ignored a mismatched native field generation");
        require(tracker.classify(next, false, next.version(), 11L, 4L)
                        == GiLiveCompletionTracker.State.STALE_COMPLETION
                        && tracker.classify(next, false, next.version(), 10L, 5L)
                        == GiLiveCompletionTracker.State.STALE_COMPLETION
                        && tracker.classify(next, false, next.version(), 10L, 4L)
                        == GiLiveCompletionTracker.State.INVALID_COMPLETION,
                "stale G6 success/failure or malformed stale receipt was misclassified");

        require(!tracker.recordFailure() && tracker.consecutiveFailures() == 1
                        && !tracker.recordFailure() && tracker.consecutiveFailures() == 2
                        && tracker.recordFailure() && tracker.consecutiveFailures() == 3,
                "G6 did not become terminal on exactly the third completion failure");
        tracker.recordSuccess();
        require(tracker.consecutiveFailures() == 0 && !tracker.recordFailure(),
                "successful G6 completion did not reset its failure streak");
        int beforeStale = tracker.consecutiveFailures();
        require(tracker.classify(next, false, next.version(), 11L, 4L)
                        == GiLiveCompletionTracker.State.STALE_COMPLETION
                        && tracker.consecutiveFailures() == beforeStale,
                "stale G6 completion changed the new epoch's failure streak");
        tracker.beginEpoch();
        require(tracker.consecutiveFailures() == 0,
                "G6 epoch rotation did not reset its completion-failure streak");

        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler(8, 2);
        scheduler.rotateEpoch(current);
        scheduler.enqueue(current, 3, GiLiveUpdateClass.BLOCK, 7L);
        scheduler.enqueue(current, 7, GiLiveUpdateClass.STATIC_SOURCE, 7L);
        int[] bricks = new int[2];
        GiLiveUpdateClass[] classes = new GiLiveUpdateClass[2];
        int count = scheduler.drainTo(current, 7L, bricks, classes);
        require(count == 2 && scheduler.retryBatch(current, bricks, count)
                        == GiLiveDirtyScheduler.CompletionResult.COMPLETED
                        && scheduler.queuedTotal() == 2L
                        && scheduler.telemetry().pending() == 2
                        && scheduler.telemetry().inFlight() == 0
                        && scheduler.algebraIsExact(),
                "failed G6 completion changed queue lifetime algebra or lost ownership");
        require(scheduler.drainTo(current, 7L, bricks, classes) == 0
                        && scheduler.drainTo(current, 8L, bricks, classes) == 2
                        && bricks[0] == 3 && bricks[1] == 7
                        && classes[0] == GiLiveUpdateClass.BLOCK
                        && classes[1] == GiLiveUpdateClass.STATIC_SOURCE,
                "G6 retry changed the accepted batch inputs, age or classification");
        scheduler.rotateEpoch(next);
        require(scheduler.telemetry().discarded() == 2L
                        && scheduler.telemetry().owned() == 0
                        && scheduler.algebraIsExact(),
                "G6 epoch rotation did not discard retried in-flight ownership exactly once");
        tracker.clearAccepted();
        tracker.admit(0, 0, true, 0L, current.version(), 20L, 4L);
        require(tracker.remapOnly()
                        && tracker.classify(
                        current, false, current.version(), 21L, 4L
                ) == GiLiveCompletionTracker.State.COMPLETED_CURRENT,
                "source-independent G6 scroll remap lost its exact async receipt");
        tracker.clearAccepted();
        expectIllegalArgument(
                () -> tracker.admit(0, 0, false, 0L, current.version(), 21L, 4L),
                "zero-brick G6 work was accepted without a prepared scroll remap"
        );
    }

    private static void sameGridInputRebasePreservesPendingWork() {
        GiLiveEpoch first = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveEpoch content = epoch(2L, 2L, 1L, 1L, 1L);
        GiLiveEpoch staticSource = new GiLiveEpoch(
                3L, content.dimensionId(), content.worldGeneration(),
                content.resourceEpoch(), content.materialEpoch(),
                content.clipmapGeneration(), content.paletteGeneration(),
                content.contentGeneration(), content.staticSourceEpoch() + 1L,
                content.dynamicSourceEpoch(), content.environmentEpoch(),
                content.cascade0Origin(), content.cascade1Origin(), content.cascade2Origin()
        );
        require(content.isSameGridInputSuccessorOf(first)
                        && staticSource.isSameGridInputSuccessorOf(content),
                "G6 same-grid content/static successor was rejected");
        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler(8, 4);
        scheduler.rotateEpoch(first);
        scheduler.enqueue(first, 3, GiLiveUpdateClass.FULL_RESET, 1L);
        scheduler.enqueue(first, 7, GiLiveUpdateClass.FULL_RESET, 2L);
        scheduler.rebaseLiveInputEpoch(content);
        require(scheduler.activeEpoch().equals(content)
                        && scheduler.telemetry().pending() == 2
                        && scheduler.telemetry().discarded() == 0L
                        && scheduler.telemetry().algebraIsExact(),
                "G6 same-grid rebase discarded pending ownership");
        require(scheduler.enqueue(content, 7, GiLiveUpdateClass.STATIC_SOURCE, 3L)
                        == GiLiveDirtyScheduler.OfferResult.COALESCED,
                "G6 same-grid rebase lost queued coalescing");
        int[] bricks = new int[4];
        GiLiveUpdateClass[] classes = new GiLiveUpdateClass[4];
        int count = scheduler.drainTo(content, 3L, bricks, classes);
        require(count == 2 && bricks[0] == 3 && bricks[1] == 7
                        && classes[1] == GiLiveUpdateClass.FULL_RESET,
                "G6 same-grid rebase changed age or weakened conservative classification");

        GiLiveDirtyScheduler mixed = new GiLiveDirtyScheduler(8, 4);
        mixed.rotateEpoch(first);
        mixed.enqueue(first, GiDirectSourceLayout.BRICKS_PER_CASCADE + 1,
                GiLiveUpdateClass.FULL_RESET, 1L);
        mixed.enqueue(first, GiDirectSourceLayout.BRICKS_PER_CASCADE + 3,
                GiLiveUpdateClass.FULL_RESET, 1L);
        mixed.enqueue(first, 5, GiLiveUpdateClass.BLOCK, 2L);
        count = mixed.drainTo(first, 2L, bricks, classes);
        require(count == 1 && bricks[0] == 5,
                "G6 mixed queue did not keep a batch inside the near cascade");
        mixed.completeBatch(first, bricks, count);
        count = mixed.drainTo(first, 3L, bricks, classes);
        require(count == 2
                        && bricks[0] == GiDirectSourceLayout.BRICKS_PER_CASCADE + 1
                        && bricks[1] == GiDirectSourceLayout.BRICKS_PER_CASCADE + 3,
                "G6 mixed queue crossed cascades or lost stable far ordering");

        GiLiveDirtyScheduler inFlight = new GiLiveDirtyScheduler(8, 4);
        inFlight.rotateEpoch(first);
        inFlight.enqueue(first, 1, GiLiveUpdateClass.BLOCK, 1L);
        require(inFlight.drainTo(first, 1L, bricks, classes) == 1,
                "G6 rebase in-flight fixture did not drain");
        try {
            inFlight.rebaseLiveInputEpoch(content);
            throw new AssertionError("G6 rebase accepted in-flight ownership");
        } catch (IllegalStateException expected) {
            require(inFlight.activeEpoch().equals(first)
                            && inFlight.telemetry().inFlight() == 1
                            && inFlight.telemetry().discarded() == 0L,
                    "rejected G6 rebase mutated in-flight ownership");
        }

        GiLiveEpoch dynamic = epoch(4L, 3L, 2L, 1L, 1L);
        require(!dynamic.isSameGridInputSuccessorOf(content)
                        && !GiLiveCoordinator.shouldRebasePendingScheduler(
                        content, dynamic, GiLiveUpdateClass.STATIC_SOURCE,
                        true, false, 0, 2)
                        && !GiLiveCoordinator.shouldRebasePendingScheduler(
                        content, staticSource, GiLiveUpdateClass.FULL_RESET,
                        true, false, 0, 2)
                        && !GiLiveCoordinator.shouldRebasePendingScheduler(
                        content, staticSource, GiLiveUpdateClass.STATIC_SOURCE,
                        true, true, 0, 2)
                        && GiLiveCoordinator.shouldRebasePendingScheduler(
                        content, staticSource, GiLiveUpdateClass.STATIC_SOURCE,
                        true, false, 0, 2)
                        && !GiLiveCoordinator.shouldRebasePendingScheduler(
                        content, staticSource, GiLiveUpdateClass.SCROLL,
                        true, false, 0, 2),
                "G6 scheduler rebase crossed a structural, dynamic, accepted-write, or origin boundary");

        GiLiveDirtyScheduler pendingOuterScroll = new GiLiveDirtyScheduler();
        pendingOuterScroll.rotateEpoch(content);
        for (int local = 0; local < GiDirectSourceLayout.BRICKS_PER_CASCADE; local++) {
            pendingOuterScroll.enqueue(content, local, GiLiveUpdateClass.SCROLL, 4L);
            pendingOuterScroll.enqueue(content,
                    GiDirectSourceLayout.BRICKS_PER_CASCADE + local,
                    GiLiveUpdateClass.SCROLL, 4L);
            pendingOuterScroll.enqueue(content,
                    2 * GiDirectSourceLayout.BRICKS_PER_CASCADE + local,
                    GiLiveUpdateClass.SCROLL, 4L);
        }
        int nearCount = pendingOuterScroll.drainTo(content, 4L, bricks, classes);
        require(nearCount == bricks.length,
                "G6 pending-scroll rebase fixture did not drain one near batch");
        pendingOuterScroll.completeBatch(content, bricks, nearCount);
        long discardedBefore = pendingOuterScroll.telemetry().discarded();
        int pendingBefore = pendingOuterScroll.pendingCount();
        pendingOuterScroll.rebaseLiveInputEpoch(staticSource);
        require(pendingOuterScroll.activeEpoch().equals(staticSource)
                        && pendingOuterScroll.pendingCount() == pendingBefore
                        && pendingOuterScroll.ownedCount() == pendingBefore
                        && pendingOuterScroll.telemetry().discarded() == discardedBefore
                        && pendingOuterScroll.algebraIsExact(),
                "same-grid source child discarded unfinished outer-scroll ownership");
    }

    private static void nearBurstProtectsSlaWithoutStarvingOuterCascades() {
        GiLiveEpoch first = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveEpoch content = epoch(2L, 2L, 1L, 1L, 1L);
        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler();
        scheduler.rotateEpoch(first);
        for (int local = 0; local < GiDirectSourceLayout.BRICKS_PER_CASCADE; local++) {
            scheduler.enqueue(first, local, GiLiveUpdateClass.FULL_RESET, 0L);
            scheduler.enqueue(first, GiDirectSourceLayout.BRICKS_PER_CASCADE + local,
                    GiLiveUpdateClass.FULL_RESET, 0L);
            scheduler.enqueue(first, 2 * GiDirectSourceLayout.BRICKS_PER_CASCADE + local,
                    GiLiveUpdateClass.FULL_RESET, 0L);
        }
        int[] fullBatch = new int[GiLiveLayout.MAX_BRICKS_PER_SUBMIT];
        GiLiveUpdateClass[] fullClasses =
                new GiLiveUpdateClass[GiLiveLayout.MAX_BRICKS_PER_SUBMIT];
        GiLiveEpoch active = first;
        int nearBatchCount = Math.ceilDiv(
                GiDirectSourceLayout.BRICKS_PER_CASCADE,
                GiLiveLayout.MAX_BRICKS_PER_SUBMIT
        );
        for (long tick = 0L; tick < nearBatchCount; tick++) {
            int count = scheduler.drainTo(active, tick, fullBatch, fullClasses);
            int expected = Math.min(
                    GiLiveLayout.MAX_BRICKS_PER_SUBMIT,
                    GiDirectSourceLayout.BRICKS_PER_CASCADE
                            - Math.toIntExact(tick) * GiLiveLayout.MAX_BRICKS_PER_SUBMIT
            );
            require(count == expected
                            && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 0,
                    "G6 full near field did not receive its complete SLA burst");
            scheduler.completeBatch(active, fullBatch, count);
            if (tick == 3L) {
                scheduler.rebaseLiveInputEpoch(content);
                active = content;
            }
        }
        long firstOuterTick = nearBatchCount;
        int count = scheduler.drainTo(active, firstOuterTick, fullBatch, fullClasses);
        require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 1,
                "G6 rebase reset the near quota or starved the middle cascade");
        scheduler.completeBatch(active, fullBatch, count);
        count = scheduler.drainTo(active, firstOuterTick + 1L, fullBatch, fullClasses);
        require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 2,
                "G6 empty near queue did not alternate to the far cascade");
        scheduler.completeBatch(active, fullBatch, count);
        count = scheduler.drainTo(active, firstOuterTick + 2L, fullBatch, fullClasses);
        require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 1,
                "G6 outer backlog did not switch back to a prepared middle cascade");
        scheduler.completeBatch(active, fullBatch, count);

        for (int local = 0; local < GiDirectSourceLayout.BRICKS_PER_CASCADE; local++) {
            scheduler.enqueue(active, local, GiLiveUpdateClass.BLOCK, firstOuterTick + 3L);
        }
        count = scheduler.drainTo(active, firstOuterTick + 3L, fullBatch, fullClasses);
        require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 0,
                "G6 new near work did not preempt the remaining outer backlog");
        scheduler.completeBatch(active, fullBatch, count);
        require(scheduler.telemetry().algebraIsExact(),
                "G6 near/outer quota broke scheduler ownership algebra");

        GiLiveDirtyScheduler sustained = new GiLiveDirtyScheduler();
        sustained.rotateEpoch(first);
        for (int local = 0; local < GiDirectSourceLayout.BRICKS_PER_CASCADE; local++) {
            sustained.enqueue(first, local, GiLiveUpdateClass.BLOCK, 0L);
            sustained.enqueue(first, GiDirectSourceLayout.BRICKS_PER_CASCADE + local,
                    GiLiveUpdateClass.FULL_RESET, 0L);
            sustained.enqueue(first, 2 * GiDirectSourceLayout.BRICKS_PER_CASCADE + local,
                    GiLiveUpdateClass.FULL_RESET, 0L);
        }
        for (long tick = 0L; tick < GiLiveDirtyScheduler.MAX_CONSECUTIVE_NEAR_BATCHES;
                tick++) {
            count = sustained.drainTo(first, tick, fullBatch, fullClasses);
            require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                            && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 0,
                    "G6 sustained near work lost its full-cascade priority burst");
            sustained.completeBatch(first, fullBatch, count);
            for (int index = 0; index < count; index++) {
                sustained.enqueue(first, fullBatch[index], GiLiveUpdateClass.BLOCK, tick + 1L);
            }
        }
        long quotaOuterTick = GiLiveDirtyScheduler.MAX_CONSECUTIVE_NEAR_BATCHES;
        count = sustained.drainTo(first, quotaOuterTick, fullBatch, fullClasses);
        require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 1,
                "G6 sustained near work starved the first outer quota batch");
        sustained.completeBatch(first, fullBatch, count);
        for (long tick = quotaOuterTick + 1L;
                tick <= quotaOuterTick + GiLiveDirtyScheduler.MAX_CONSECUTIVE_NEAR_BATCHES;
                tick++) {
            count = sustained.drainTo(first, tick, fullBatch, fullClasses);
            require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                            && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 0,
                    "G6 outer quota interrupted more than one sustained near batch");
            sustained.completeBatch(first, fullBatch, count);
            for (int index = 0; index < count; index++) {
                sustained.enqueue(first, fullBatch[index], GiLiveUpdateClass.BLOCK, tick + 1L);
            }
        }
        long farQuotaTick = quotaOuterTick
                + GiLiveDirtyScheduler.MAX_CONSECUTIVE_NEAR_BATCHES + 1L;
        count = sustained.drainTo(first, farQuotaTick, fullBatch, fullClasses);
        require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && GiDirectSourceLayout.cascadeForBrickId(fullBatch[0]) == 2,
                "G6 sustained near work starved the alternating far quota batch");
        sustained.completeBatch(first, fullBatch, count);
        require(sustained.telemetry().algebraIsExact(),
                "G6 sustained quota broke scheduler ownership algebra");
    }

    private static void defaultSchedulerUsesLiveBatchContract() {
        GiLiveEpoch epoch = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler();
        scheduler.rotateEpoch(epoch);
        for (int brick = 0; brick < GiDirectSourceLayout.BRICKS_PER_CASCADE; brick++) {
            scheduler.enqueue(epoch, brick, GiLiveUpdateClass.SCROLL, 0L);
        }
        int[] bricks = new int[GiLiveLayout.MAX_BRICKS_PER_SUBMIT];
        GiLiveUpdateClass[] classes =
                new GiLiveUpdateClass[GiLiveLayout.MAX_BRICKS_PER_SUBMIT];
        int count = scheduler.drainTo(epoch, 1L, bricks, classes);
        require(count == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && scheduler.drainTo(epoch, 1L, bricks, classes) == 0
                        && scheduler.inFlightCount() == GiLiveLayout.MAX_BRICKS_PER_SUBMIT
                        && scheduler.algebraIsExact(),
                "default G6 scheduler drifted from the live batch contract");
        scheduler.completeBatch(epoch, bricks, count);
        require(scheduler.algebraIsExact(),
                "live batch completion broke scheduler ownership algebra");

        GiLiveDirtyScheduler scrollSlab = new GiLiveDirtyScheduler();
        scrollSlab.rotateEpoch(epoch);
        int exposedSlabBricks = 32;
        for (int brick = 0; brick < exposedSlabBricks; brick++) {
            scrollSlab.enqueue(epoch, brick, GiLiveUpdateClass.SCROLL, 0L);
        }
        int completionBoundaries = 0;
        for (long tick = 1L; scrollSlab.ownedCount() != 0; tick++) {
            count = scrollSlab.drainTo(epoch, tick, bricks, classes);
            require(count > 0 && count <= GiLiveLayout.MAX_BRICKS_PER_SUBMIT,
                    "G6 exposed scroll slab exceeded its batch bound");
            scrollSlab.completeBatch(epoch, bricks, count);
            completionBoundaries++;
        }
        require(completionBoundaries == Math.ceilDiv(
                        exposedSlabBricks, GiLiveLayout.MAX_BRICKS_PER_SUBMIT)
                        && completionBoundaries == 2
                        && scrollSlab.algebraIsExact(),
                "G6 32-brick scroll slab retained more than two serialized completions");
    }

    private static void drainBudgetDependsOnSourceTicksNotPresentedFrames() {
        GiLiveEpoch epoch = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveDirtyScheduler oneFrame = populatedScheduler(epoch);
        GiLiveDirtyScheduler manyFrames = populatedScheduler(epoch);
        List<Integer> firstOrder = drainAcrossTicks(oneFrame, epoch, 1);
        List<Integer> repeatedOrder = drainAcrossTicks(manyFrames, epoch, 7);
        require(firstOrder.equals(repeatedOrder) && firstOrder.size() == 12,
                "G6 drain progress changed with presented-frame multiplicity");
        expectIllegalArgument(() -> manyFrames.drainTo(
                        epoch, 2L, new int[3], new GiLiveUpdateClass[3]
                ), "G6 source tick regression was accepted");
    }

    private static GiLiveDirtyScheduler populatedScheduler(final GiLiveEpoch epoch) {
        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler(12, 3);
        scheduler.rotateEpoch(epoch);
        for (int brick = 11; brick >= 0; brick--) {
            scheduler.enqueue(epoch, brick, GiLiveUpdateClass.BLOCK, 0L);
        }
        return scheduler;
    }

    private static List<Integer> drainAcrossTicks(
            final GiLiveDirtyScheduler scheduler,
            final GiLiveEpoch epoch,
            final int renderCallsPerTick
    ) {
        List<Integer> order = new ArrayList<>();
        int[] bricks = new int[3];
        GiLiveUpdateClass[] classes = new GiLiveUpdateClass[3];
        for (long tick = 1L; tick <= 4L; tick++) {
            for (int frame = 0; frame < renderCallsPerTick; frame++) {
                int count = scheduler.drainTo(epoch, tick, bricks, classes);
                for (int index = 0; index < count; index++) order.add(bricks[index]);
                scheduler.completeBatch(epoch, bricks, count);
            }
        }
        return order;
    }

    private static void fullResetPublishesZeroBeforeAnyReadyField() {
        GiLiveEpoch first = epoch(1L, 1L, 1L, 1L, 1L);
        GiLivePublication publication = new GiLivePublication();
        require(publication.beginEpoch(first, GiLiveUpdateClass.FULL_RESET, 100L)
                        == GiLivePublication.BeginResult.STARTED,
                "G6 full reset did not start");
        require(publication.snapshot().coverage() == GiLivePublication.CoverageState.ZERO
                        && !publication.snapshot().receiverReady(),
                "G6 full reset exposed pre-reset coverage");
        require(publication.publishReady(first, 100L)
                        == GiLivePublication.PublishResult.FIRST_SUBMIT_MUST_BE_ZERO
                        && publication.snapshot().coverage() == GiLivePublication.CoverageState.ZERO,
                "G6 full reset skipped its mandatory first zero submit");
        require(publication.publishReady(first, 101L)
                        == GiLivePublication.PublishResult.PUBLISHED
                        && publication.snapshot().receiverReady(),
                "G6 compatible field was not published after the reset barrier");
    }

    private static void incrementalClassesRetainOnlyExactValidCoverage() {
        require(GiLiveUpdateClass.merge(
                        GiLiveUpdateClass.BLOCK, GiLiveUpdateClass.STATIC_SOURCE)
                        == GiLiveUpdateClass.STATIC_SOURCE,
                "simultaneous content/emitter mutation lost source-class attribution");
        GiLiveEpoch first = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveEpoch block = epoch(2L, 2L, 1L, 1L, 1L);
        GiLiveEpoch scroll = epoch(3L, 2L, 1L, 1L, 2L);
        GiLivePublication publication = new GiLivePublication();
        publication.beginEpoch(first, GiLiveUpdateClass.FULL_RESET, 0L);
        publication.publishReady(first, 1L);
        publication.beginEpoch(block, GiLiveUpdateClass.BLOCK, 2L);
        require(publication.snapshot().coverage()
                        == GiLivePublication.CoverageState.EXACT_VALID_ONLY,
                "G6 block update exposed incompatible stale cells");
        require(publication.publishReady(first, 3L)
                        == GiLivePublication.PublishResult.STALE_EPOCH,
                "old G6 block epoch republished after invalidation");
        publication.publishReady(block, 3L);
        publication.beginEpoch(scroll, GiLiveUpdateClass.SCROLL, 4L);
        require(publication.snapshot().coverage()
                        == GiLivePublication.CoverageState.EXACT_VALID_ONLY,
                "G6 scroll did not restrict reuse to exact overlap");
        require(publication.publishReady(scroll, 4L)
                        == GiLivePublication.PublishResult.PUBLISHED,
                "incremental G6 publication incorrectly required a full-reset barrier");
    }

    private static void trilinearReceiverFootprintRequiresEveryTouchedBrick() {
        long center = GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                7.5 / 32.0, 7.5 / 32.0, 7.5 / 32.0
        );
        require(center == 1L,
                "G6 texel-center lookup escaped its single exact brick");

        long boundary = GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                8.0 / 32.0, 8.0 / 32.0, 8.0 / 32.0
        );
        long expectedBoundary = (1L << 0) | (1L << 1) | (1L << 4) | (1L << 5)
                | (1L << 16) | (1L << 17) | (1L << 20) | (1L << 21);
        require(boundary == expectedBoundary && Long.bitCount(boundary) == 8,
                "G6 three-axis trilinear boundary did not require all eight exact bricks");
        require((boundary & (boundary & ~(1L << 21))) != boundary,
                "G6 exact admission accepted a footprint with one stale neighboring brick");

        long xBoundary = GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                8.0 / 32.0, 12.5 / 32.0, 20.5 / 32.0
        );
        require(xBoundary == ((1L << 36) | (1L << 37)),
                "G6 one-axis trilinear boundary did not require both exact bricks");
        require(GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                        0.0, 0.0, 0.0) == 1L
                        && GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                        Math.nextDown(1.0), Math.nextDown(1.0), Math.nextDown(1.0))
                        == (1L << 63),
                "G6 cascade-edge clamp escaped into another brick or cascade");
        expectIllegalArgument(() ->
                        GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                                1.0, 0.5, 0.5),
                "G6 footprint mirror admitted an outside-cascade sample");
    }

    private static void provisionalStaticAndDynamicPlansRecoverUnaffectedExactCoverage() {
        long retainedExact = GiLiveLayout.ALL_BRICKS_MASK;
        long localStatic = 1L << 21;
        long localDynamicOldNewUnion = localStatic | (1L << 22);

        long rejectedStaticDrift = GiLiveCoordinator.authoritativeAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE,
                GiLiveLayout.ALL_BRICKS_MASK, localStatic, false
        );
        long rejectedEnvironmentDrift = GiLiveCoordinator.authoritativeAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE,
                GiLiveLayout.ALL_BRICKS_MASK, localDynamicOldNewUnion, false
        );
        require(rejectedStaticDrift == GiLiveLayout.ALL_BRICKS_MASK
                        && rejectedEnvironmentDrift == GiLiveLayout.ALL_BRICKS_MASK,
                "stale G3 static/environment masks weakened provisional ALL invalidation");
        require(GiLiveCoordinator.exactMaskAfterPlan(
                        retainedExact, rejectedStaticDrift, false) == 0L,
                "provisional source drift exposed retained cells before replacement");

        long staticRequired = GiLiveCoordinator.authoritativeAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE,
                GiLiveLayout.ALL_BRICKS_MASK, localStatic, true
        );
        long dynamicRequired = GiLiveCoordinator.authoritativeAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE,
                GiLiveLayout.ALL_BRICKS_MASK, localDynamicOldNewUnion, true
        );
        long staticRecovered = GiLiveCoordinator.exactMaskAfterPlan(
                retainedExact, staticRequired, false
        );
        long dynamicRecovered = GiLiveCoordinator.exactMaskAfterPlan(
                retainedExact, dynamicRequired, false
        );
        require(Long.bitCount(staticRequired) == 27
                        && staticRecovered != 0L && dynamicRecovered != 0L,
                "authoritative local source plan failed to recover unaffected exact bricks");
        require((staticRecovered & staticRequired) == 0L
                        && (dynamicRecovered & dynamicRequired) == 0L
                        && (staticRecovered & (1L << 63)) != 0L
                        && (dynamicRecovered & (1L << 63)) != 0L,
                "local static/dynamic recovery exposed affected cells or lost distant exact cells");

        require(GiLiveCoordinator.authoritativeAffectedMask(
                        GiLiveUpdateClass.FULL_RESET,
                        GiLiveLayout.ALL_BRICKS_MASK, 0L, true
                ) == GiLiveLayout.ALL_BRICKS_MASK,
                "an empty source mask erased mandatory full-reset population");
        long semanticBlock = 1L << 2;
        long sourceBlock = 1L << 50;
        long combinedBlock = GiLiveCoordinator.authoritativeAffectedMask(
                GiLiveUpdateClass.BLOCK, semanticBlock, sourceBlock, true
        );
        require((combinedBlock & semanticBlock) != 0L
                        && (combinedBlock & sourceBlock) != 0L,
                "source refinement erased semantic block dirt");

        long partiallyExact = GiLiveLayout.ALL_BRICKS_MASK & ~(1L << 9);
        require(GiLiveCoordinator.requiredToConverge(partiallyExact, 0L) == (1L << 9),
                "a superseded epoch stranded its unfinished exact brick");
        require(GiLiveCoordinator.requiredToConverge(
                        GiLiveLayout.ALL_BRICKS_MASK, localStatic) == localStatic,
                "a fully exact cascade rebuilt outside its new affected mask");

        int oneBrickDx = GiDirectSourceLayout.BRICK_EDGE_CELLS
                * GiDirectSourceLayout.cellSizeBlocks(0);
        long scrolledExact = GiLiveCoordinator.scrollRetainedExactMask(
                GiLiveLayout.ALL_BRICKS_MASK, 0, oneBrickDx, 0, 0
        );
        require(Long.bitCount(scrolledExact) == 48,
                "one-brick scroll did not retain the exact 3x4x4 overlap");

        long[] preProvisional = {retainedExact, retainedExact, retainedExact};
        long[] mixedProvisional = {0L, retainedExact & ~staticRequired, retainedExact};
        long[] authoritativeRequired = {1L, staticRequired, 0L};
        for (int cascade = 0; cascade < GiLiveLayout.CASCADE_COUNT; cascade++) {
            long retained = GiLiveCoordinator.retainedExactForTransition(
                    preProvisional[cascade], mixedProvisional[cascade], true
            );
            long restored = GiLiveCoordinator.exactMaskAfterPlan(
                    retained, authoritativeRequired[cascade], false
            );
            require(restored == (retainedExact & ~authoritativeRequired[cascade]),
                    "mixed provisional cascade overwrote the pre-provisional exact snapshot");
        }
        require(GiLiveCoordinator.retainedExactForTransition(
                        retainedExact, 0L, false) == 0L,
                "ordinary invalidation did not capture a zero exact mask per cascade");
        require(GiLiveCoordinator.deferredHistoryCanBind(
                        GiLiveUpdateClass.BLOCK, true, true, false)
                        && GiLiveCoordinator.deferredHistoryCanBind(
                        GiLiveUpdateClass.STATIC_SOURCE, true, true, false)
                        && GiLiveCoordinator.deferredHistoryCanBind(
                        GiLiveUpdateClass.SCROLL, true, true, false)
                        && GiLiveCoordinator.deferredHistoryCanBind(
                        GiLiveUpdateClass.FULL_RESET, true, true, true),
                "compatible deferred update discarded last-proven receiver history");
        require(!GiLiveCoordinator.deferredHistoryCanBind(
                        GiLiveUpdateClass.FULL_RESET, true, true, false)
                        && !GiLiveCoordinator.deferredHistoryCanBind(
                        GiLiveUpdateClass.BLOCK, false, true, false)
                        && !GiLiveCoordinator.deferredHistoryCanBind(
                        GiLiveUpdateClass.BLOCK, true, false, false),
                "structural/unproven deferred update exposed receiver history");
        require(GiLiveCoordinator.compatibleEnvironmentSuccessor(
                        false, true, true, true, 41L, 43L)
                        && !GiLiveCoordinator.compatibleEnvironmentSuccessor(
                        true, true, true, true, 41L, 43L)
                        && !GiLiveCoordinator.compatibleEnvironmentSuccessor(
                        false, true, false, true, 41L, 43L)
                        && !GiLiveCoordinator.compatibleEnvironmentSuccessor(
                        false, true, true, false, 41L, 43L)
                        && !GiLiveCoordinator.compatibleEnvironmentSuccessor(
                        false, true, true, true, 41L, 41L),
                "environment history compatibility crossed a structural/origin/reset boundary");
    }

    private static void sourceEnvironmentIdentityUsesTheAdmittedG3Digest() {
        require(!GiLiveCoordinator.sourceEnvironmentIdentityReady(0L),
                "G6 admitted an unavailable G3 environment identity");
        require(GiLiveCoordinator.sourceEnvironmentIdentityReady(1L)
                        && GiLiveCoordinator.sourceEnvironmentIdentityReady(Long.MIN_VALUE),
                "G6 rejected a valid signed G3 environment digest");
        require(GiLiveCoordinator.unavailableEnvironmentHistoryCanBind(
                        false, true, true, true, 41L)
                        && !GiLiveCoordinator.unavailableEnvironmentHistoryCanBind(
                        true, true, true, true, 41L)
                        && !GiLiveCoordinator.unavailableEnvironmentHistoryCanBind(
                        false, false, true, true, 41L)
                        && !GiLiveCoordinator.unavailableEnvironmentHistoryCanBind(
                        false, true, false, true, 41L)
                        && !GiLiveCoordinator.unavailableEnvironmentHistoryCanBind(
                        false, true, true, false, 41L)
                        && !GiLiveCoordinator.unavailableEnvironmentHistoryCanBind(
                        false, true, true, true, 0L),
                "unavailable G3 identity retained history across an unsafe boundary");
        require(GiLiveCoordinator.retrySourceHistoryCanBind(
                        true, GiLiveUpdateClass.BLOCK, false)
                        && GiLiveCoordinator.retrySourceHistoryCanBind(
                        true, GiLiveUpdateClass.STATIC_SOURCE, false)
                        && GiLiveCoordinator.retrySourceHistoryCanBind(
                        true, GiLiveUpdateClass.SCROLL, true)
                        && !GiLiveCoordinator.retrySourceHistoryCanBind(
                        true, GiLiveUpdateClass.SCROLL, false)
                        && !GiLiveCoordinator.retrySourceHistoryCanBind(
                        false, GiLiveUpdateClass.FULL_RESET, true),
                "transient source retry hid compatible history or crossed a reset boundary");
        require(GiLiveCoordinator.retryWouldRepeatNonIdempotentScroll(true, true)
                        && !GiLiveCoordinator.retryWouldRepeatNonIdempotentScroll(true, false)
                        && !GiLiveCoordinator.retryWouldRepeatNonIdempotentScroll(false, true),
                "G6 scroll-remap completion retry is no longer terminal only when unsafe");
        require(GiLiveCoordinator.recoveredUnavailableEnvironmentMatchesObserved(
                        true, GiLiveUpdateClass.FULL_RESET, 41L, 41L, true),
                "valid D -> unavailable -> valid D retained a stale deferred reset");
        require(!GiLiveCoordinator.recoveredUnavailableEnvironmentMatchesObserved(
                        true, GiLiveUpdateClass.FULL_RESET, 41L, 43L, true)
                        && !GiLiveCoordinator.recoveredUnavailableEnvironmentMatchesObserved(
                        true, GiLiveUpdateClass.FULL_RESET, 41L, 41L, false)
                        && !GiLiveCoordinator.recoveredUnavailableEnvironmentMatchesObserved(
                        false, GiLiveUpdateClass.FULL_RESET, 41L, 41L, true)
                        && !GiLiveCoordinator.recoveredUnavailableEnvironmentMatchesObserved(
                        true, GiLiveUpdateClass.BLOCK, 41L, 41L, true),
                "unavailable-environment recovery consumed a real successor mutation");
    }

    private static void latencyRecoveryUsesTheReceiverVisibleCoverageBoundary() {
        long oneExactNearBrick = 1L << 17;
        require(!GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.BLOCK, oneExactNearBrick, false
                ) && !GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.STATIC_SOURCE, oneExactNearBrick, false
                ) && GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.BLOCK, GiLiveLayout.ALL_BRICKS_MASK, true
                ) && GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.STATIC_SOURCE, GiLiveLayout.ALL_BRICKS_MASK, true
                ),
                "incremental G6 recovery was closed by unrelated retained near coverage");
        require(!GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.SCROLL, 0L, false
                ) && !GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.FULL_RESET, 0L, false
                ) && GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.SCROLL, oneExactNearBrick, false
                ) && GiLiveCoordinator.latencyRecoveryIsReceiverVisible(
                        GiLiveUpdateClass.FULL_RESET, oneExactNearBrick, false
                ),
                "scroll/reset G6 recovery no longer matches receiver-visible exact near coverage");
        require(GiLiveCoordinator.shouldEncodeProvisionalNearScrollRemap(
                        false, GiLiveUpdateClass.SCROLL, true, false
                ) && !GiLiveCoordinator.shouldEncodeProvisionalNearScrollRemap(
                        true, GiLiveUpdateClass.SCROLL, true, false
                ) && !GiLiveCoordinator.shouldEncodeProvisionalNearScrollRemap(
                        false, GiLiveUpdateClass.SCROLL, true, true
                ) && !GiLiveCoordinator.shouldEncodeProvisionalNearScrollRemap(
                        false, GiLiveUpdateClass.BLOCK, true, false
                ),
                "G6 immediate scroll remap escaped its provisional near boundary");
        require(!GiLiveCoordinator.scrollRemapPendingAfterProvisionalRebase(true, true)
                        && GiLiveCoordinator.scrollRemapPendingAfterProvisionalRebase(true, false)
                        && !GiLiveCoordinator.scrollRemapPendingAfterProvisionalRebase(false, false),
                "G6 provisional near remap consumed or invented an outer scroll remap");
        require(GiLiveCoordinator.provisionalReceiverHistoryCanBind(
                        false, true, GiLiveUpdateClass.BLOCK, false
                ) && GiLiveCoordinator.provisionalReceiverHistoryCanBind(
                        false, true, GiLiveUpdateClass.SCROLL, true
                ) && !GiLiveCoordinator.provisionalReceiverHistoryCanBind(
                        false, true, GiLiveUpdateClass.SCROLL, false
                ) && !GiLiveCoordinator.provisionalReceiverHistoryCanBind(
                        false, false, GiLiveUpdateClass.FULL_RESET, true
                ) && !GiLiveCoordinator.provisionalReceiverHistoryCanBind(
                        true, true, GiLiveUpdateClass.BLOCK, true
                ),
                "G6 compatible receiver history was hidden or crossed an unsafe boundary");
    }

    private static void latencyPercentilesEnforceEveryDeclaredSla() {
        GiLiveLatencyTracker tracker = new GiLiveLatencyTracker();
        recordDistribution(tracker, GiLiveUpdateClass.BLOCK, 95, 8, 5, 16, 0, 0);
        recordDistribution(tracker, GiLiveUpdateClass.STATIC_SOURCE, 95, 9, 5, 16, 0, 0);
        recordDistribution(tracker, GiLiveUpdateClass.SCROLL, 95, 16, 5, 32, 0, 0);
        recordDistribution(tracker, GiLiveUpdateClass.FULL_RESET, 95, 32, 4, 64, 1, 65);

        GiLiveLatencyTracker.Snapshot block = tracker.snapshot(GiLiveUpdateClass.BLOCK);
        GiLiveLatencyTracker.Snapshot staticSource =
                tracker.snapshot(GiLiveUpdateClass.STATIC_SOURCE);
        GiLiveLatencyTracker.Snapshot scroll = tracker.snapshot(GiLiveUpdateClass.SCROLL);
        GiLiveLatencyTracker.Snapshot reset = tracker.snapshot(GiLiveUpdateClass.FULL_RESET);
        require(block.p95Submits() == 8 && block.p99Submits() == 16 && block.meetsSla(),
                "G6 block SLA boundary changed");
        require(staticSource.p95Submits() == 9 && !staticSource.meetsSla(),
                "G6 static-source p95 violation was accepted");
        require(scroll.p95Submits() == 16 && scroll.p99Submits() == 32
                        && scroll.meetsSla(),
                "G6 scroll SLA boundary changed");
        require(reset.p95Submits() == 32 && reset.p99Submits() == 64
                        && reset.meetsSla(),
                "G6 full-reset p95/p99 boundary changed");
        expectIllegalArgument(() -> tracker.record(GiLiveUpdateClass.BLOCK, 7L, 6L),
                "negative G6 recovery latency was accepted");
    }

    private static void unfinishedFullResetLatencySurvivesIncrementalOverlap() {
        GiLiveLatencyTracker tracker = new GiLiveLatencyTracker();
        tracker.beginPending(GiLiveUpdateClass.FULL_RESET, 100L);
        tracker.beginPending(GiLiveUpdateClass.STATIC_SOURCE, 104L);
        tracker.beginPending(GiLiveUpdateClass.BLOCK, 108L);
        require(tracker.hasPending()
                        && tracker.pendingClassification() == GiLiveUpdateClass.FULL_RESET
                        && tracker.pendingFirstAffectedSubmitIndex() == 100L,
                "incremental overlap downgraded or restarted an unfinished full reset");
        tracker.recordPendingReady(132L);
        require(!tracker.hasPending()
                        && tracker.pendingFirstAffectedSubmitIndex() == -1L
                        && tracker.sampleCount(GiLiveUpdateClass.FULL_RESET) == 1L
                        && tracker.p95Submits(GiLiveUpdateClass.FULL_RESET) == 32
                        && tracker.sampleCount(GiLiveUpdateClass.STATIC_SOURCE) == 0L
                        && tracker.sampleCount(GiLiveUpdateClass.BLOCK) == 0L,
                "unfinished full reset was recorded as a superseding incremental class");

        tracker.beginPending(GiLiveUpdateClass.STATIC_SOURCE, 140L);
        tracker.beginPending(GiLiveUpdateClass.BLOCK, 144L);
        tracker.recordPendingReady(148L);
        require(tracker.sampleCount(GiLiveUpdateClass.STATIC_SOURCE) == 0L
                        && tracker.sampleCount(GiLiveUpdateClass.BLOCK) == 1L
                        && tracker.p95Submits(GiLiveUpdateClass.BLOCK) == 4,
                "a fresh incremental window lost last-epoch accounting");
        expectIllegalArgument(
                () -> {
                    tracker.beginPending(GiLiveUpdateClass.BLOCK, 160L);
                    tracker.beginPending(GiLiveUpdateClass.BLOCK, 159L);
                },
                "overlapping latency window accepted a backwards first submit"
        );
    }

    private static void unfinishedScrollLatencySurvivesMovingSourceOverlap() {
        GiLiveLatencyTracker tracker = new GiLiveLatencyTracker();
        tracker.beginPending(GiLiveUpdateClass.SCROLL, 200L);
        tracker.beginPending(GiLiveUpdateClass.STATIC_SOURCE, 204L);
        tracker.beginPending(GiLiveUpdateClass.BLOCK, 208L);
        require(tracker.hasPending()
                        && tracker.pendingClassification() == GiLiveUpdateClass.SCROLL
                        && tracker.pendingFirstAffectedSubmitIndex() == 200L,
                "moving held-source refinement downgraded an unfinished G6 scroll");
        tracker.recordPendingReady(216L);
        require(!tracker.hasPending()
                        && tracker.sampleCount(GiLiveUpdateClass.SCROLL) == 1L
                        && tracker.p95Submits(GiLiveUpdateClass.SCROLL) == 16
                        && tracker.sampleCount(GiLiveUpdateClass.STATIC_SOURCE) == 0L
                        && tracker.sampleCount(GiLiveUpdateClass.BLOCK) == 0L,
                "combined scroll/source recovery was attributed to the wrong SLA class");

        tracker.beginPending(GiLiveUpdateClass.BLOCK, 220L);
        tracker.beginPending(GiLiveUpdateClass.SCROLL, 224L);
        require(tracker.pendingClassification() == GiLiveUpdateClass.SCROLL
                        && tracker.pendingFirstAffectedSubmitIndex() == 224L,
                "a newer scroll did not start its own visible recovery interval");
    }

    private static void unfinishedScrollRetainsItsBasisOnlyBeforeRemapAdmission() {
        require(GiLiveCoordinator.shouldRetainPendingBasis(
                        GiLiveUpdateClass.SCROLL, true,
                        GiLiveUpdateClass.STATIC_SOURCE, false)
                        && GiLiveCoordinator.shouldRetainPendingBasis(
                        GiLiveUpdateClass.SCROLL, true,
                        GiLiveUpdateClass.BLOCK, false),
                "unfinished scroll did not coalesce pre-remap source/block dirt");
        require(!GiLiveCoordinator.shouldRetainPendingBasis(
                        GiLiveUpdateClass.SCROLL, true,
                        GiLiveUpdateClass.STATIC_SOURCE, true)
                        && !GiLiveCoordinator.shouldRetainPendingBasis(
                        GiLiveUpdateClass.STATIC_SOURCE, false,
                        GiLiveUpdateClass.STATIC_SOURCE, false),
                "captured scroll basis survived remap admission or an incompatible state");
        require(GiLiveCoordinator.shouldRetainPendingBasis(
                        GiLiveUpdateClass.STATIC_SOURCE, true,
                        GiLiveUpdateClass.STATIC_SOURCE, false)
                        && GiLiveCoordinator.shouldRetainPendingBasis(
                        GiLiveUpdateClass.BLOCK, true,
                        GiLiveUpdateClass.STATIC_SOURCE, false),
                "same-origin source chain recaptured and eroded an unconsumed exact basis");

        long scrollDirt = 1L << 3;
        long sourceDirt = 1L << 42;
        long exactSource = GiLiveCoordinator.retainedProvisionalAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE, scrollDirt, sourceDirt, true, true
        );
        require((exactSource & scrollDirt) != 0L
                        && (exactSource & sourceDirt) != 0L
                        && exactSource != GiLiveLayout.ALL_BRICKS_MASK,
                "exact held-source overlap erased scroll dirt or became a full rebuild");
        require(GiLiveCoordinator.retainedProvisionalAffectedMask(
                        GiLiveUpdateClass.STATIC_SOURCE, scrollDirt, 0L, false, true
                ) == GiLiveLayout.ALL_BRICKS_MASK,
                "unknown held-source overlap exposed provisional stale lighting");
        require(GiLiveCoordinator.retainedProvisionalAffectedMask(
                        GiLiveUpdateClass.BLOCK, scrollDirt, 0L, false, false
                ) == scrollDirt,
                "semantic block dirt was widened without an unavailable source transition");
        require(GiLiveCoordinator.retainedProvisionalAffectedMask(
                        GiLiveUpdateClass.BLOCK, scrollDirt, 0L, false, true
                ) == GiLiveLayout.ALL_BRICKS_MASK
                        && GiLiveCoordinator.provisionalAffectedMask(
                        GiLiveUpdateClass.BLOCK, scrollDirt, 0L, false, true
                ) == GiLiveLayout.ALL_BRICKS_MASK,
                "simultaneous block/source drift exposed stale provisional source GI");
        long sticky = GiLiveCoordinator.retainedProvisionalAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE, scrollDirt, 1L << 5, true, true
        );
        sticky = GiLiveCoordinator.retainedProvisionalAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE, sticky, 1L << 50, true, true
        );
        require((sticky & (1L << 5)) != 0L && (sticky & (1L << 50)) != 0L,
                "A-to-B source dirt vanished when the pending basis advanced B-to-C");
        long finalPlan = GiLiveCoordinator.authoritativePlanAffectedMask(
                sticky, 1L << 11
        );
        require((finalPlan & sticky) == sticky && (finalPlan & (1L << 11)) != 0L,
                "authoritative G3 refinement erased sticky provisional source dirt");

        int oneBrickDx = GiDirectSourceLayout.BRICK_EDGE_CELLS
                * GiDirectSourceLayout.cellSizeBlocks(0);
        long retained = GiLiveCoordinator.scrollRetainedExactMask(
                GiLiveLayout.ALL_BRICKS_MASK, 0, oneBrickDx, 0, 0
        );
        long required = GiLiveCoordinator.requiredToConverge(retained, exactSource);
        require(Long.bitCount(retained) == 48
                        && (required & ~retained) == ~retained
                        && (required & exactSource) == exactSource,
                "coalesced source dirt lost the 48-brick scroll basis algebra");
    }

    /**
     * Scripted movement regression: a physical one-brick scroll and a source mutation arrive in
     * the same observation. The coordinate change must rotate old brick ownership, but the
     * completed remap keeps its 3x4x4 receiver history sampleable. A later source successor on
     * that already-remapped grid must rebase its pending work without blanking that overlap.
     */
    private static void simultaneousScrollAndSourceMutationRetainsReceiverOverlap() {
        GiLiveEpoch beforeScroll = epoch(1L, 1L, 1L, 1L, 1L);
        GiLiveEpoch scrollWithSourceMutation = new GiLiveEpoch(
                2L, beforeScroll.dimensionId(), beforeScroll.worldGeneration(),
                beforeScroll.resourceEpoch(), beforeScroll.materialEpoch(),
                beforeScroll.clipmapGeneration() + 1L, beforeScroll.paletteGeneration(),
                beforeScroll.contentGeneration(), beforeScroll.staticSourceEpoch() + 1L,
                beforeScroll.dynamicSourceEpoch(), beforeScroll.environmentEpoch(),
                new GiLiveEpoch.Origin(-56, -32, -16),
                new GiLiveEpoch.Origin(-96, -64, -32),
                new GiLiveEpoch.Origin(-192, -128, -64)
        );
        GiLiveEpoch sourceSuccessor = new GiLiveEpoch(
                3L, scrollWithSourceMutation.dimensionId(),
                scrollWithSourceMutation.worldGeneration(),
                scrollWithSourceMutation.resourceEpoch(),
                scrollWithSourceMutation.materialEpoch(),
                scrollWithSourceMutation.clipmapGeneration(),
                scrollWithSourceMutation.paletteGeneration(),
                scrollWithSourceMutation.contentGeneration(),
                scrollWithSourceMutation.staticSourceEpoch() + 1L,
                scrollWithSourceMutation.dynamicSourceEpoch(),
                scrollWithSourceMutation.environmentEpoch(),
                scrollWithSourceMutation.cascade0Origin(),
                scrollWithSourceMutation.cascade1Origin(),
                scrollWithSourceMutation.cascade2Origin()
        );
        require(!scrollWithSourceMutation.isSameGridInputSuccessorOf(beforeScroll)
                        && sourceSuccessor.isSameGridInputSuccessorOf(scrollWithSourceMutation),
                "movement/source trace lost its coordinate-rotate then same-grid boundary");

        GiLivePublication publication = new GiLivePublication();
        publication.beginEpoch(beforeScroll, GiLiveUpdateClass.FULL_RESET, 0L);
        publication.publishReady(beforeScroll, 1L);
        publication.beginEpoch(scrollWithSourceMutation, GiLiveUpdateClass.SCROLL, 2L);
        require(publication.snapshot().coverage()
                        == GiLivePublication.CoverageState.EXACT_VALID_ONLY
                        && GiLiveCoordinator.provisionalReceiverHistoryCanBind(
                        false, true, GiLiveUpdateClass.SCROLL, true
                ), "completed scroll remap did not keep compatible receiver history bindable");

        int c0OneBrick = GiDirectSourceLayout.BRICK_EDGE_CELLS
                * GiDirectSourceLayout.cellSizeBlocks(0);
        long remappedReceiverMask = GiLiveCoordinator.scrollRetainedExactMask(
                GiLiveLayout.ALL_BRICKS_MASK, 0, c0OneBrick, 0, 0
        );
        long exposedSlabMask = ~remappedReceiverMask;
        require(Long.bitCount(remappedReceiverMask) == 48
                        && Long.bitCount(exposedSlabMask) == 16,
                "one-brick remap did not preserve exactly the 3x4x4 receiver overlap");
        long retainedInteriorFootprint = GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                20.0 / 32.0, 20.0 / 32.0, 20.0 / 32.0
        );
        long newlyExposedFootprint = GiLiveReceiverShaderPatcher.exactTrilinearFootprintMask(
                28.0 / 32.0, 20.0 / 32.0, 20.0 / 32.0
        );
        require((retainedInteriorFootprint & remappedReceiverMask) == retainedInteriorFootprint
                        && (newlyExposedFootprint & remappedReceiverMask)
                        != newlyExposedFootprint,
                "scroll remap made retained terrain unsampleable or exposed slab sampleable");

        GiLiveDirtyScheduler scheduler = new GiLiveDirtyScheduler();
        scheduler.rotateEpoch(beforeScroll);
        scheduler.enqueue(beforeScroll, 0, GiLiveUpdateClass.SCROLL, 2L);
        scheduler.enqueue(beforeScroll, 1, GiLiveUpdateClass.SCROLL, 2L);
        scheduler.enqueue(beforeScroll, 2, GiLiveUpdateClass.SCROLL, 2L);
        scheduler.rotateEpoch(scrollWithSourceMutation);
        require(scheduler.telemetry().discarded() == 3L
                        && scheduler.pendingCount() == 0
                        && !GiLiveCoordinator.shouldRebasePendingScheduler(
                        beforeScroll, scrollWithSourceMutation, GiLiveUpdateClass.SCROLL,
                        true, false, 0, 3
                ), "coordinate rotate retained untranslatable old-grid scheduler ownership");

        for (int local = 0; local < GiLiveLayout.BRICKS_PER_CASCADE; local++) {
            if ((exposedSlabMask & (1L << local)) != 0L) {
                scheduler.enqueue(scrollWithSourceMutation, local,
                        GiLiveUpdateClass.SCROLL, 3L);
            }
        }
        int pendingAfterRemap = scheduler.pendingCount();
        long discardedAfterCoordinateRotate = scheduler.telemetry().discarded();
        require(pendingAfterRemap == 16
                        && GiLiveCoordinator.shouldRebasePendingScheduler(
                        scrollWithSourceMutation, sourceSuccessor,
                        GiLiveUpdateClass.STATIC_SOURCE,
                        true, false, 0, pendingAfterRemap
                ), "post-remap same-grid source successor was not eligible to preserve work");
        scheduler.rebaseLiveInputEpoch(sourceSuccessor);
        require(scheduler.pendingCount() == pendingAfterRemap
                        && scheduler.telemetry().discarded() == discardedAfterCoordinateRotate
                        && scheduler.algebraIsExact(),
                "same-grid source successor discarded translated exposed-slab work");

        long sourceAffected = GiLiveCoordinator.expandTransportHalo(1L << 0);
        long sourceBaseExact = remappedReceiverMask & ~sourceAffected;
        long sourceRequired = GiLiveCoordinator.requiredToConverge(
                sourceBaseExact, sourceAffected
        );
        long exactAfterSourcePlan = GiLiveCoordinator.exactMaskAfterPlan(
                remappedReceiverMask, sourceRequired, false
        );
        long receiverSampleableAfterSourcePlan = remappedReceiverMask;
        publication.beginEpoch(sourceSuccessor, GiLiveUpdateClass.STATIC_SOURCE, 3L);
        require(publication.snapshot().coverage()
                        == GiLivePublication.CoverageState.EXACT_VALID_ONLY
                        && receiverSampleableAfterSourcePlan == remappedReceiverMask
                        && (retainedInteriorFootprint & receiverSampleableAfterSourcePlan)
                        == retainedInteriorFootprint
                        && (exactAfterSourcePlan & ~(sourceAffected | exposedSlabMask))
                        == (remappedReceiverMask & ~(sourceAffected | exposedSlabMask)),
                "source successor erased stable remapped overlap outside its affected halo");
    }

    private static void unknownSourceVisibilityDoesNotPoisonCapturedFullResetProgress() {
        long semanticBlock = 1L << 3;
        long unknownSticky = GiLiveCoordinator.stickyKnownAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE, semanticBlock, 0L, false
        );
        long unknownVisibility = GiLiveCoordinator.retainedProvisionalAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE,
                unknownSticky, 0L, false, true
        );
        require(unknownSticky == semanticBlock
                        && unknownVisibility == GiLiveLayout.ALL_BRICKS_MASK,
                "unknown G3 source visibility either exposed stale GI or poisoned known dirt");

        long exactSource = 1L << 63;
        long exactSticky = GiLiveCoordinator.stickyKnownAffectedMask(
                GiLiveUpdateClass.STATIC_SOURCE, semanticBlock, exactSource, true
        );
        require((exactSticky & semanticBlock) != 0L
                        && (exactSticky & exactSource) != 0L
                        && GiLiveCoordinator.provisionalAffectedMask(
                        GiLiveUpdateClass.STATIC_SOURCE,
                        semanticBlock, exactSource, true, true
                ) == exactSticky,
                "merged block/static transition erased known semantic or source dirt");
        long authoritative = GiLiveCoordinator.authoritativePlanAffectedMask(
                unknownSticky, exactSource
        );
        long captured = GiLiveLayout.ALL_BRICKS_MASK;
        long recovered = captured & ~authoritative;
        long required = GiLiveCoordinator.requiredToConverge(recovered, authoritative);
        require(required == authoritative && recovered == (captured & ~authoritative)
                        && recovered != 0L
                        && (required & ~authoritative) == 0L,
                "authoritative G3 mask did not recover captured unaffected full-reset bricks");

        // Regression for the v6 teleport churn: 190 same-root children temporarily need ALL
        // visibility zero, but their authoritative source says no near-cascade brick changed.
        // With known dirt kept separately, one bounded 8-brick batch advances on every source
        // tick and reaches all 64 bits within the bounded batch count. The old poisoned model
        // rebuilds the first batch forever
        // forever because ALL is ORed into every authoritative plan.
        long correctedExact = 0L;
        long poisonedExact = 0L;
        int firstReadyTick = -1;
        for (int child = 0; child < 190; child++) {
            long knownDirt = GiLiveCoordinator.stickyKnownAffectedMask(
                    GiLiveUpdateClass.STATIC_SOURCE, 0L, 0L, false
            );
            long provisionalVisibility = GiLiveCoordinator.retainedProvisionalAffectedMask(
                    GiLiveUpdateClass.STATIC_SOURCE,
                    knownDirt, 0L, false, true
            );
            require(knownDirt == 0L
                            && provisionalVisibility == GiLiveLayout.ALL_BRICKS_MASK,
                    "same-root unknown child no longer fails provisional visibility closed");

            long correctedAffected = GiLiveCoordinator.authoritativePlanAffectedMask(
                    knownDirt, 0L
            );
            long correctedBase = correctedExact & ~correctedAffected;
            long correctedRequired = GiLiveCoordinator.requiredToConverge(
                    correctedBase, correctedAffected
            );
            correctedExact = correctedBase | takeLowestBits(
                    correctedRequired, GiLiveLayout.MAX_BRICKS_PER_SUBMIT
            );

            long poisonedAffected = GiLiveCoordinator.authoritativePlanAffectedMask(
                    GiLiveLayout.ALL_BRICKS_MASK, 0L
            );
            long poisonedBase = poisonedExact & ~poisonedAffected;
            long poisonedRequired = GiLiveCoordinator.requiredToConverge(
                    poisonedBase, poisonedAffected
            );
            poisonedExact = poisonedBase | takeLowestBits(
                    poisonedRequired, GiLiveLayout.MAX_BRICKS_PER_SUBMIT
            );

            if (correctedExact == GiLiveLayout.ALL_BRICKS_MASK && firstReadyTick < 0) {
                firstReadyTick = child + 1;
            }
            require(Long.bitCount(correctedExact) == Math.min(
                            64, (child + 1) * GiLiveLayout.MAX_BRICKS_PER_SUBMIT),
                    "same-root full-reset progress was not monotonic and batch bounded");
        }
        require(firstReadyTick == Math.ceilDiv(
                        GiLiveLayout.BRICKS_PER_CASCADE,
                        GiLiveLayout.MAX_BRICKS_PER_SUBMIT)
                        && correctedExact == GiLiveLayout.ALL_BRICKS_MASK
                        && poisonedExact == takeLowestBits(
                        GiLiveLayout.ALL_BRICKS_MASK, GiLiveLayout.MAX_BRICKS_PER_SUBMIT),
                "190-child churn regression did not distinguish recoverable from poisoned masks");
    }

    private static void transferredRootDirtPreservesProductionLikeStreamingProgress() {
        long rootSticky = GiLiveCoordinator.stickyKnownPhysicalDirt(
                GiLiveUpdateClass.FULL_RESET,
                GiLiveLayout.ALL_BRICKS_MASK,
                GiLiveLayout.ALL_BRICKS_MASK,
                true
        );
        require(rootSticky == 0L
                        && GiLiveCoordinator.provisionalAffectedMask(
                        GiLiveUpdateClass.FULL_RESET,
                        GiLiveLayout.ALL_BRICKS_MASK,
                        GiLiveLayout.ALL_BRICKS_MASK,
                        true, true
                ) == GiLiveLayout.ALL_BRICKS_MASK,
                "FULL_RESET structural visibility leaked into sticky incremental dirt");

        // First root batch: the physical atlas owns exactly eight bits, while structural work
        // remains in exact/required. A zero-delta child must retain ff and request only ~ff.
        long exact = 0xffL;
        long zeroChildBase = exact;
        long zeroChildRequired = GiLiveCoordinator.requiredToConverge(
                zeroChildBase, 0L
        );
        require(zeroChildBase == 0xffL && zeroChildRequired == ~0xffL
                        && GiLiveCoordinator.stickyKnownPhysicalDirt(
                        GiLiveUpdateClass.FULL_RESET,
                        GiLiveLayout.ALL_BRICKS_MASK, 0L, false
                ) == 0L
                        && GiLiveCoordinator.requiredToConverge(0L, 0L)
                        == GiLiveLayout.ALL_BRICKS_MASK,
                "root ownership did not move from sticky ALL into exact/required progress");

        long sourceBit = 1L << 21;
        long sourceHalo = GiLiveCoordinator.expandTransportHalo(sourceBit);
        long preDispatchBase = GiLiveLayout.ALL_BRICKS_MASK & ~sourceHalo;
        long transferred = GiLiveCoordinator.conservativeMaskAfterAcceptedPlan(
                true, 0L, sourceBit
        );
        long noDrainChildRequired = GiLiveCoordinator.requiredToConverge(
                preDispatchBase,
                GiLiveCoordinator.authoritativePlanAffectedMask(transferred, 0L)
        );
        long completed = takeLowestBits(sourceHalo, GiLiveLayout.MAX_BRICKS_PER_SUBMIT);
        long partialExact = preDispatchBase | completed;
        long partialChildRequired = GiLiveCoordinator.requiredToConverge(
                partialExact,
                GiLiveCoordinator.authoritativePlanAffectedMask(transferred, 0L)
        );
        require(transferred == 0L
                        && noDrainChildRequired == sourceHalo
                        && (partialChildRequired & completed) == 0L
                        && (partialChildRequired & (sourceHalo & ~completed))
                        == (sourceHalo & ~completed),
                "accepted plan lost queued dirt or re-invalidated completed exact bricks");
        require(GiLiveCoordinator.stickyKnownPhysicalDirt(
                        GiLiveUpdateClass.STATIC_SOURCE, 1L << 3, 0L, false
                ) == (1L << 3)
                        && GiLiveCoordinator.retainedProvisionalAffectedMask(
                        GiLiveUpdateClass.STATIC_SOURCE,
                        1L << 3, 0L, false, true
                ) == GiLiveLayout.ALL_BRICKS_MASK,
                "unknown source ALL became sticky or stopped failing visibility closed");

        // Production-like v8 model: the audit accumulator remains ALL after TELEPORT, but only
        // untransferred current dirt participates in each child plan. Twelve real source changes
        // re-dirty their exact halos; after they stop, bounded batches converge. The old model
        // keeps reapplying cumulative ALL and therefore never advances beyond the first byte.
        long correctedExact = exact;
        long poisonedExact = exact;
        int firstReadyChild = -1;
        for (int child = 1; child <= 190; child++) {
            long newlyUntransferred = child <= 12
                    ? 1L << ((child * 13) & 63) : 0L;
            long childKnown = GiLiveCoordinator.stickyKnownPhysicalDirt(
                    GiLiveUpdateClass.STATIC_SOURCE,
                    0L, newlyUntransferred, true
            );
            long childAffected = GiLiveCoordinator.authoritativePlanAffectedMask(
                    childKnown, newlyUntransferred
            );
            long previousExact = correctedExact;
            long base = correctedExact & ~childAffected;
            require((base & ~childAffected) == (previousExact & ~childAffected),
                    "child plan erased exact progress outside genuinely changed source halos");
            long required = GiLiveCoordinator.requiredToConverge(base, childAffected);
            correctedExact = base | takeLowestBits(
                    required, GiLiveLayout.MAX_BRICKS_PER_SUBMIT
            );

            long poisonedBase = poisonedExact & ~GiLiveLayout.ALL_BRICKS_MASK;
            poisonedExact = poisonedBase | takeLowestBits(
                    GiLiveCoordinator.requiredToConverge(
                            poisonedBase, GiLiveLayout.ALL_BRICKS_MASK
                    ),
                    GiLiveLayout.MAX_BRICKS_PER_SUBMIT
            );
            if (correctedExact == GiLiveLayout.ALL_BRICKS_MASK
                    && firstReadyChild < 0) {
                firstReadyChild = child;
            }
        }
        require(firstReadyChild > 0 && firstReadyChild <= 64
                        && correctedExact == GiLiveLayout.ALL_BRICKS_MASK
                        && poisonedExact == takeLowestBits(
                        GiLiveLayout.ALL_BRICKS_MASK, GiLiveLayout.MAX_BRICKS_PER_SUBMIT),
                "transferred TELEPORT dirt did not converge under 190 exact-identity children");
    }

    private static void openFullResetRetainsChildrenUntilLatestAuthoritativeAllReady() {
        GiLiveUpdateClass deferredPromoted = GiLiveUpdateClass.merge(
                GiLiveUpdateClass.FULL_RESET, GiLiveUpdateClass.STATIC_SOURCE
        );
        require(deferredPromoted == GiLiveUpdateClass.FULL_RESET
                        && !GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        false, true, true, false, GiLiveUpdateClass.STATIC_SOURCE
                ),
                "deferred full reset failed to promote and open a previously absent root");
        require(GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, true, false, GiLiveUpdateClass.BLOCK)
                        && GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, true, false, GiLiveUpdateClass.STATIC_SOURCE)
                        && GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, false, false, GiLiveUpdateClass.SCROLL),
                "same-root content/source child escaped its unfinished full-reset transaction");
        require(!GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        false, true, true, false, GiLiveUpdateClass.STATIC_SOURCE)
                        && !GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, false, true, false, GiLiveUpdateClass.STATIC_SOURCE)
                        && !GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, false, false, GiLiveUpdateClass.BLOCK)
                        && !GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, false, true, GiLiveUpdateClass.SCROLL)
                        && !GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, true, true, GiLiveUpdateClass.STATIC_SOURCE)
                        && !GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, true, false, GiLiveUpdateClass.FULL_RESET),
                "structural/origin reset incorrectly retained an older exact basis");
        require(GiLiveCoordinator.classifyIncrementalObservation(false, true, false)
                        == GiLiveUpdateClass.STATIC_SOURCE
                        && GiLiveCoordinator.classifyIncrementalObservation(true, false, false)
                        == GiLiveUpdateClass.BLOCK
                        && GiLiveCoordinator.classifyIncrementalObservation(true, true, false)
                        == GiLiveUpdateClass.STATIC_SOURCE
                        && GiLiveCoordinator.classifyIncrementalObservation(false, true, true)
                        == GiLiveUpdateClass.FULL_RESET
                        && GiLiveCoordinator.classifyIncrementalObservation(true, true, true)
                        == GiLiveUpdateClass.FULL_RESET,
                "global environment rebuild lost its honest full-volume latency class");
        int stable = GiLiveCoordinator.FULL_RESET_STABLE_PUBLICATION_SUBMITS;
        require(!GiLiveCoordinator.shouldCloseOpenFullResetRoot(true, false, true, stable)
                        && !GiLiveCoordinator.shouldCloseOpenFullResetRoot(
                        true, true, false, stable)
                        && !GiLiveCoordinator.shouldCloseOpenFullResetRoot(
                        false, true, true, stable)
                        && !GiLiveCoordinator.shouldCloseOpenFullResetRoot(
                        true, true, true, stable - 1)
                        && GiLiveCoordinator.shouldCloseOpenFullResetRoot(
                        true, true, true, stable),
                "full-reset root closed before latest authoritative all-cascade readiness");
        require(GiLiveCoordinator.shouldHoldCompletedFullResetPublication(
                        true, true, true, true, true, GiLiveUpdateClass.BLOCK)
                        && GiLiveCoordinator.shouldHoldCompletedFullResetPublication(
                        true, true, true, true, true, GiLiveUpdateClass.STATIC_SOURCE)
                        && !GiLiveCoordinator.shouldHoldCompletedFullResetPublication(
                        true, true, true, true, true, GiLiveUpdateClass.SCROLL)
                        && !GiLiveCoordinator.shouldHoldCompletedFullResetPublication(
                        true, true, true, false, true, GiLiveUpdateClass.BLOCK),
                "G6 stable publication hold admitted an incompatible successor");
        expectIllegalArgument(() -> GiLiveCoordinator.shouldCloseOpenFullResetRoot(
                        true, true, true, -1),
                "negative full-reset publication stability was accepted");
        require(GiLiveCoordinator.shouldContinueAuthoritativePlanning(0, 1)
                        && GiLiveCoordinator.shouldContinueAuthoritativePlanning(1, 1)
                        && !GiLiveCoordinator.shouldContinueAuthoritativePlanning(
                        0, GiLiveLayout.CASCADE_COUNT
                ),
                "authoritative planning stopped before every available cascade was coalesced");
        require(GiLiveCoordinator.unavailableAuthoritativeSourceStatus(
                        false, GiLiveLayout.READY_MASK_ALL)
                        == GiLiveCoordinator.STATUS_RETAINED_HISTORY
                        && GiLiveCoordinator.unavailableAuthoritativeSourceStatus(
                        true, GiLiveLayout.READY_MASK_ALL)
                        == GiLiveCoordinator.STATUS_NO_WORK
                        && GiLiveCoordinator.unavailableAuthoritativeSourceStatus(false, 4)
                        == GiLiveCoordinator.STATUS_INPUT_NOT_READY,
                "authoritative source gap hid complete receiver history or exposed partial input");
        require(GiLiveCoordinator.allCascadesHaveSampleableReceiverHistory(
                        GiLiveLayout.READY_MASK_ALL)
                        && !GiLiveCoordinator.allCascadesHaveSampleableReceiverHistory(0)
                        && !GiLiveCoordinator.allCascadesHaveSampleableReceiverHistory(4),
                "native all-cascade receiver-history proof admitted a partial tuple");
        expectIllegalArgument(() -> GiLiveCoordinator.unavailableAuthoritativeSourceStatus(
                        false, 8),
                "out-of-range authoritative receiver mask was accepted");
        expectIllegalArgument(() ->
                        GiLiveCoordinator.allCascadesHaveSampleableReceiverHistory(8),
                "out-of-range retry receiver mask was accepted");
        require(GiLiveCoordinator.shouldDeferAuthoritativeScrollHandoffUntilNearSource(
                        false, GiLiveUpdateClass.SCROLL, true, 1)
                        && GiLiveCoordinator.shouldDeferAuthoritativeScrollHandoffUntilNearSource(
                        false, GiLiveUpdateClass.SCROLL, true, 2)
                        && !GiLiveCoordinator.shouldDeferAuthoritativeScrollHandoffUntilNearSource(
                        false, GiLiveUpdateClass.SCROLL, true, 0)
                        && !GiLiveCoordinator.shouldDeferAuthoritativeScrollHandoffUntilNearSource(
                        true, GiLiveUpdateClass.SCROLL, true, 1)
                        && !GiLiveCoordinator.shouldDeferAuthoritativeScrollHandoffUntilNearSource(
                        false, GiLiveUpdateClass.BLOCK, true, 1)
                        && !GiLiveCoordinator.shouldDeferAuthoritativeScrollHandoffUntilNearSource(
                        false, GiLiveUpdateClass.SCROLL, false, 1),
                "outer G3 source could still preempt a completed provisional C0 scroll remap");
        expectIllegalArgument(() ->
                        GiLiveCoordinator.shouldDeferAuthoritativeScrollHandoffUntilNearSource(
                                false, GiLiveUpdateClass.SCROLL, true, 3),
                "out-of-range authoritative source cascade was accepted");
        for (int child = 0; child < 190; child++) {
            int nextCascade = 0;
            int planned = 0;
            do {
                planned++;
                nextCascade++;
            } while (GiLiveCoordinator.shouldContinueAuthoritativePlanning(
                    0, nextCascade
            ));
            require(planned == GiLiveLayout.CASCADE_COUNT
                            && GiLiveCoordinator.shouldCloseOpenFullResetRoot(
                            true, true, planned == GiLiveLayout.CASCADE_COUNT, stable
                    ),
                    "zero-required child starved an outer cascade or left its root open");
        }
        expectIllegalArgument(() ->
                        GiLiveCoordinator.shouldContinueAuthoritativePlanning(-1, 0),
                "negative authoritative pending count was accepted");

        GiLiveLatencyTracker nearSla = new GiLiveLatencyTracker();
        nearSla.beginPending(GiLiveUpdateClass.FULL_RESET, 100L);
        nearSla.beginPending(GiLiveUpdateClass.STATIC_SOURCE, 104L);
        nearSla.recordPendingReady(132L);
        require(!nearSla.hasPending()
                        && nearSla.sampleCount(GiLiveUpdateClass.FULL_RESET) == 1L
                        && nearSla.p95Submits(GiLiveUpdateClass.FULL_RESET) == 32
                        && GiLiveCoordinator.shouldRetainOpenFullResetChild(
                        true, true, true, false, GiLiveUpdateClass.STATIC_SOURCE
                ),
                "near SLA receipt closed or retargeted the still-open full-reset root");
        require(GiLiveCoordinator.stickyKnownAffectedMask(
                        GiLiveUpdateClass.FULL_RESET,
                        GiLiveLayout.ALL_BRICKS_MASK, 0L, false
                ) == GiLiveLayout.ALL_BRICKS_MASK,
                "structural full reset stopped invalidating the complete receiver atlas");
    }

    private static long takeLowestBits(final long mask, final int maximum) {
        long selected = 0L;
        long remaining = mask;
        for (int count = 0; count < maximum && remaining != 0L; count++) {
            long bit = Long.lowestOneBit(remaining);
            selected |= bit;
            remaining &= ~bit;
        }
        return selected;
    }

    private static void dynamicSourcesUseTheExactL3WorldIdentity() {
        LightWorldToken expected = new LightWorldToken(3L, "minecraft:overworld");
        GiDynamicSourceSnapshot snapshot = new GiDynamicSourceSnapshot(
                GiDynamicSourceSnapshot.CURRENT_VERSION,
                new GiDynamicSourceEpoch(expected, 7L),
                11L, 1, 2L, List.of(),
                GiDynamicSourceSnapshot.computeSourceHash(expected, List.of())
        );
        require(GiLiveRuntime.dynamicWorldMatches(snapshot, expected),
                "exact L3 dynamic-source world was rejected");
        require(!GiLiveRuntime.dynamicWorldMatches(
                        snapshot, new LightWorldToken(4L, expected.dimensionId())),
                "stale same-dimension L3 dynamic-source world was accepted");
        require(!GiLiveRuntime.dynamicWorldMatches(
                        snapshot, new LightWorldToken(3L, "minecraft:the_nether")),
                "cross-dimension dynamic-source world was accepted");
        GiDynamicSourceSnapshot equalButReplaced = new GiDynamicSourceSnapshot(
                snapshot.version(), snapshot.epoch(), snapshot.publishedAtWorldTick(),
                snapshot.capacity(), snapshot.expiryTicks(), snapshot.sources(),
                snapshot.sourceHash()
        );
        require(snapshot.equals(equalButReplaced) && snapshot != equalButReplaced,
                "snapshot/tick interleaving fixture is not equal-but-distinct");
        require(GiLiveRuntime.matchingDynamicSourceTick(snapshot, snapshot, 19L) == 19L,
                "current dynamic snapshot lost its cadence tick");
        require(GiLiveRuntime.matchingDynamicSourceTick(
                        equalButReplaced, snapshot, 20L) == -1L,
                "a replaced equal snapshot was paired with another publication tick");
        require(!GiLiveRuntime.shouldClearDynamicWorld(
                        snapshot, new LightWorldToken(2L, expected.dimensionId())),
                "closing an older L3 token cleared a newer dynamic publication");
        require(GiLiveRuntime.shouldClearDynamicWorld(snapshot, expected)
                        && GiLiveRuntime.shouldClearDynamicWorld(snapshot, null),
                "exact-world or terminal dynamic publication close was ignored");
    }

    private static void terminalAdmissionStateStopsRuntimeWork() {
        require(GiLiveRuntime.isOperationalState(
                        true, GiLiveRuntime.AdmissionState.WAITING)
                        && GiLiveRuntime.isOperationalState(
                        true, GiLiveRuntime.AdmissionState.READY),
                "G6 waiting/ready admission stopped required runtime work");
        require(!GiLiveRuntime.isOperationalState(
                        true, GiLiveRuntime.AdmissionState.INVALID)
                        && !GiLiveRuntime.isOperationalState(
                        true, GiLiveRuntime.AdmissionState.DISABLED)
                        && !GiLiveRuntime.isOperationalState(
                        false, GiLiveRuntime.AdmissionState.DISABLED)
                        && !GiLiveRuntime.isOperationalState(
                        false, GiLiveRuntime.AdmissionState.READY),
                "G6 terminal/disabled admission still accepted runtime work");
    }

    private static void finalReceiptRequiresCurrentAdmissionAndLatestExactTerrainBinding() {
        long firstGeneration = GiLiveRuntime.resetDeviceState();
        long secondGeneration = GiLiveRuntime.resetDeviceState();
        require(secondGeneration > firstGeneration,
                "G6 device reset did not advance its monotonic generation");

        GiLiveRuntime.FinalSnapshot current = finalReceipt(
                secondGeneration, true, secondGeneration, 100L,
                secondGeneration, 200L, GiLiveLayout.STATUS_OK,
                true, true, 7, true, 41L, 99L
        );
        require(GiLiveRuntime.finalReceiptIsCurrent(current, secondGeneration),
                "current exact G6 terrain receipt was rejected");
        require(GiLiveRuntime.latestTerrainAllCascadeBindingIsUsable(
                        current, secondGeneration),
                "current all-cascade G6 terrain binding was rejected");

        GiLiveRuntime.FinalSnapshot retainedSuccessor = new GiLiveRuntime.FinalSnapshot(
                secondGeneration,
                4, true, 0L, 0L, 1L,
                42L, 100L, 1L, 8, 16, true,
                1L, 8, 16, true,
                1L, 8, 16, true,
                1L, 8, 16, true,
                1L, 1L, 0L, 0, 0, true, 1L,
                2L, 0L, 2L, 0L, 0L,
                true, secondGeneration, 100L,
                true, secondGeneration, 201L, GiLiveLayout.STATUS_OK,
                true, true, 4, true, GiLiveLayout.READY_MASK_ALL,
                42L, 100L
        );
        require(GiLiveRuntime.latestTerrainAllCascadeBindingIsUsable(
                        retainedSuccessor, secondGeneration)
                        && retainedSuccessor.readyMask() != GiLiveLayout.READY_MASK_ALL
                        && retainedSuccessor.buildInFlight(),
                "G6 retained all-cascade receiver was rejected during successor rebuild");

        GiLiveRuntime.FinalSnapshot noAdmission = finalReceipt(
                secondGeneration, false, -1L, -1L,
                secondGeneration, 200L, GiLiveLayout.STATUS_OK,
                true, true, 7, true, 41L, 99L
        );
        require(!GiLiveRuntime.finalReceiptIsCurrent(noAdmission, secondGeneration),
                "G6 final passed without an emitted admission receipt");

        GiLiveRuntime.FinalSnapshot laterFallback = finalReceipt(
                secondGeneration, true, secondGeneration, 100L,
                secondGeneration, 201L, GiLiveLayout.STATUS_ZERO_READY,
                true, true, 7, false, 41L, 99L
        );
        require(!GiLiveRuntime.finalReceiptIsCurrent(laterFallback, secondGeneration),
                "historical G6 admission survived a newer zero/fallback terrain bind");
        require(!GiLiveRuntime.latestTerrainAllCascadeBindingIsUsable(
                        laterFallback, secondGeneration),
                "zero G6 terrain binding passed motion continuity");

        GiLiveRuntime.FinalSnapshot partialTerrain = finalReceipt(
                secondGeneration, true, secondGeneration, 100L,
                secondGeneration, 201L, GiLiveLayout.STATUS_OK,
                true, true, 1, true, 41L, 99L
        );
        require(!GiLiveRuntime.finalReceiptIsCurrent(partialTerrain, secondGeneration),
                "G6 final paired an all-cascade census with a partial terrain receipt");
        require(!GiLiveRuntime.latestTerrainAllCascadeBindingIsUsable(
                        partialTerrain, secondGeneration),
                "partial G6 visible mask passed all-cascade motion continuity");

        GiLiveRuntime.FinalSnapshot recreatedDevice = finalReceipt(
                secondGeneration, true, firstGeneration, 100L,
                secondGeneration, 200L, GiLiveLayout.STATUS_OK,
                true, true, 7, true, 41L, 99L
        );
        require(!GiLiveRuntime.finalReceiptIsCurrent(recreatedDevice, secondGeneration),
                "G6 admission from a previous device generation paired with a new final");

        GiLiveRuntime.FinalSnapshot staleEpoch = finalReceipt(
                secondGeneration, true, secondGeneration, 100L,
                secondGeneration, 200L, GiLiveLayout.STATUS_OK,
                true, true, 7, true, 40L, 98L
        );
        require(!GiLiveRuntime.finalReceiptIsCurrent(staleEpoch, secondGeneration),
                "G6 latest terrain bind from an old field/source epoch passed final gating");
    }

    private static GiLiveRuntime.FinalSnapshot finalReceipt(
            final long deviceGeneration,
            final boolean admissionEmitted,
            final long admissionGeneration,
            final long admissionSubmit,
            final long terrainGeneration,
            final long terrainSubmit,
            final int bindStatus,
            final boolean carrierSafe,
            final boolean frameCompatible,
            final int terrainReadyMask,
            final boolean exactMaskNonzero,
            final long terrainFieldGeneration,
            final long terrainSourceTick
    ) {
        return new GiLiveRuntime.FinalSnapshot(
                deviceGeneration,
                7, false, 0L, 0L, 1L,
                41L, 99L, 1L, 8, 16, true,
                1L, 8, 16, true,
                1L, 8, 16, true,
                1L, 8, 16, true,
                1L, 1L, 0L, 0, 0, true, 1L,
                0L, 0L, 0L, 0L, 0L,
                admissionEmitted, admissionGeneration, admissionSubmit,
                true, terrainGeneration, terrainSubmit, bindStatus,
                carrierSafe, frameCompatible, terrainReadyMask, exactMaskNonzero,
                exactMaskNonzero ? terrainReadyMask : 0,
                terrainFieldGeneration, terrainSourceTick
        );
    }

    private static void recordDistribution(
            final GiLiveLatencyTracker tracker,
            final GiLiveUpdateClass classification,
            final int firstCount,
            final int firstLatency,
            final int secondCount,
            final int secondLatency,
            final int thirdCount,
            final int thirdLatency
    ) {
        long submit = 0L;
        for (int index = 0; index < firstCount; index++, submit++) {
            tracker.record(classification, submit, submit + firstLatency);
        }
        for (int index = 0; index < secondCount; index++, submit++) {
            tracker.record(classification, submit, submit + secondLatency);
        }
        for (int index = 0; index < thirdCount; index++, submit++) {
            tracker.record(classification, submit, submit + thirdLatency);
        }
    }

    private static GiLiveEpoch epoch(
            final long version,
            final long content,
            final long dynamicSources,
            final long environment,
            final long clipmap
    ) {
        return new GiLiveEpoch(
                version, "test:g6", 1L, 1L, 1L, clipmap, 1L, content,
                1L, dynamicSources, environment,
                new GiLiveEpoch.Origin(-64, -32, -16),
                new GiLiveEpoch.Origin(-128, -64, -32),
                new GiLiveEpoch.Origin(-256, -128, -64)
        );
    }

    private static void expectIllegalArgument(final Runnable action, final String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }
}
