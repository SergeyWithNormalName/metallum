package com.metallum.client.metal.render;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LocalShadowSourceClass;
import com.metallum.client.lighting.ShadowEmitterFootprint;
import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;
import com.metallum.client.renderer.LocalVoxelShadowLayout;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Standalone contracts for the future resident local-shadow atlas foundation. */
public final class LocalVoxelShadowAtlasResidencyTests {
    private LocalVoxelShadowAtlasResidencyTests() {
    }

    public static void main(final String[] args) {
        testLayoutAccounting();
        testDynamicSuffixLayout();
        testDynamicMotionAndPromotion();
        testSubThresholdMotionAccumulates();
        testPresetAdmissionCaps();
        testHeldAdmissionAndHysteresis();
        testDuplicateStableIdCoalescesHeldProxy();
        testStableResidencyAndLease();
        testReplacementDefersOldPageRetirement();
        testSameEdgeReplacementNeverOverwritesInFlightPage();
        testAbandonedReplacementKeepsOldPageActive();
        testFullAtlasRejectsSameEdgeOverwrite();
        testCapacityRecoveryWaitsForFence();
        testAbsentPageRetiresBehindSubmitFence();
        testFragmentedAtlasRejectsStagingAdmission();
        testApproximateDirectOverflow();
        testExpiredLeaseAndExactRangeReuse();
        System.out.println("Resident local-shadow atlas contracts passed");
    }

    private static void testLayoutAccounting() {
        require(LocalVoxelShadowAtlasLayout.PAGE_EDGES.equals(java.util.List.of(8, 16, 32, 64)),
                "resident atlas page edges changed");
        require(LocalVoxelShadowAtlasLayout.pagePayloadBytes(8) == 12_288L
                        && LocalVoxelShadowAtlasLayout.pagePayloadBytes(16) == 49_152L
                        && LocalVoxelShadowAtlasLayout.pagePayloadBytes(32) == 196_608L
                        && LocalVoxelShadowAtlasLayout.pagePayloadBytes(64) == 786_432L,
                "resident atlas no longer preserves L6 four-layer hit sizing");
        for (int edge : LocalVoxelShadowAtlasLayout.PAGE_EDGES) {
            require(LocalVoxelShadowAtlasLayout.pageAllocationBytes(edge)
                            == LocalVoxelShadowAtlasLayout.pagePayloadBytes(edge),
                    "supported atlas page unexpectedly needs padding");
        }
        LocalVoxelShadowAtlasLayout.Budget balanced = LocalVoxelShadowAtlasLayout.balancedBudget();
        require(balanced.atlasBytes() == 64L * LocalVoxelShadowAtlasLayout.MEBIBYTE
                        && balanced.descriptorRingBytes()
                        == (long) AdvancedLightingLayout.MAX_GPU_CANDIDATE_LIGHTS * 16L * 3L
                        && balanced.totalDedicatedBytes()
                        == balanced.atlasBytes() + balanced.descriptorRingBytes(),
                "balanced resident atlas accounting changed");
        require(LocalVoxelShadowAtlasLayout.forPreset(LightingPreset.PERFORMANCE).atlasBytes()
                        == 32L * LocalVoxelShadowAtlasLayout.MEBIBYTE
                        && LocalVoxelShadowAtlasLayout.forPreset(LightingPreset.ULTRA).atlasBytes()
                        == 128L * LocalVoxelShadowAtlasLayout.MEBIBYTE,
                "resident atlas preset budgets changed");
    }

    private static void testDynamicSuffixLayout() {
        long[] expectedDynamicBytes = {147_456L, 1_179_648L, 2_359_296L};
        int[] expectedSlots = {1, 2, 4};
        int[] expectedEdges = {16, 32, 32};
        LightingPreset[] presets = LightingPreset.values();
        for (int preset = 0; preset < presets.length; preset++) {
            LocalVoxelShadowLayout.Budget budget = LocalVoxelShadowLayout.forPreset(
                    presets[preset]
            );
            LocalVoxelShadowLayout.DynamicShadowBudget dynamic = budget.dynamicShadows();
            require(dynamic.heroSlots() == expectedSlots[preset]
                            && dynamic.pageEdge() == expectedEdges[preset]
                            && dynamic.atlasBytes() == expectedDynamicBytes[preset]
                            && budget.visibilityCacheBytes()
                            == LocalVoxelShadowAtlasLayout.forPreset(presets[preset]).atlasBytes(),
                    "Dynamic suffix changed static atlas residency or preset quality");
            long cursor = budget.visibilityCacheBytes();
            for (int inFlight = 0; inFlight < LocalVoxelShadowLayout.PARAMS_RING_SLOTS;
                    inFlight++) {
                for (int hero = 0; hero < dynamic.heroSlots(); hero++) {
                    require(dynamic.pageOffset(
                                    budget.visibilityCacheBytes(), hero, inFlight
                            ) == cursor,
                            "Dynamic suffix pages overlap or leave undeclared gaps");
                    cursor += dynamic.pageBytes();
                }
            }
            require(cursor == budget.totalVisibilityAtlasBytes(),
                    "Dynamic suffix exceeds its Metal atlas allocation");
        }
    }

    private static void testDynamicMotionAndPromotion() {
        DynamicShadowAdmission admission = new DynamicShadowAdmission();
        LocalVoxelShadowLayout.DynamicShadowBudget budget =
                LocalVoxelShadowLayout.forPreset(LightingPreset.BALANCED).dynamicShadows();
        AdvancedLight entity = light(11L, 0.0, 0, LocalShadowSourceClass.ENTITY_DYNAMIC);
        DynamicShadowAdmission.Result result = admission.select(
                List.of(candidate(entity, false)), budget, 0.0, 0.0, 0.0
        );
        require(result.tracked(11L).phase() == DynamicShadowAdmission.Phase.MOVING
                        && result.selected(11L) != null
                        && !result.tracked(11L).staticBuildAllowed(),
                "New moving entity did not enter the GPU path immediately");
        for (int frame = 0; frame < DynamicShadowAdmission.STOP_HOLD_FRAMES; frame++) {
            result = admission.select(
                    List.of(candidate(entity, false)), budget, 0.0, 0.0, 0.0
            );
            require(result.tracked(11L).phase() == DynamicShadowAdmission.Phase.MOVING,
                    "Moving entity left its 12-frame stop hold early");
        }
        result = admission.select(
                List.of(candidate(entity, false)), budget, 0.0, 0.0, 0.0
        );
        require(result.tracked(11L).phase() == DynamicShadowAdmission.Phase.PROMOTING
                        && result.tracked(11L).staticBuildAllowed()
                        && result.selected(11L) != null,
                "Stopped entity did not retain its dynamic page during CPU promotion");
        result = admission.select(
                List.of(candidate(entity, true)), budget, 0.0, 0.0, 0.0
        );
        require(result.tracked(11L).phase() == DynamicShadowAdmission.Phase.STATIC
                        && result.selected(11L) == null
                        && result.dynamicToStaticTransitions() == 1,
                "Dynamic page did not switch atomically to exact static READY");

        AdvancedLight subThreshold = light(
                11L, DynamicShadowAdmission.MOVEMENT_THRESHOLD, 0,
                LocalShadowSourceClass.ENTITY_DYNAMIC
        );
        result = admission.select(
                List.of(candidate(subThreshold, true)), budget, 0.0, 0.0, 0.0
        );
        require(result.tracked(11L).phase() == DynamicShadowAdmission.Phase.STATIC,
                "Exactly 1/64-block jitter incorrectly restarted dynamic shadows");
        AdvancedLight moved = light(
                11L, DynamicShadowAdmission.MOVEMENT_THRESHOLD * 2.0 + 0.001, 0,
                LocalShadowSourceClass.ENTITY_DYNAMIC
        );
        result = admission.select(
                List.of(candidate(moved, false)), budget, 0.0, 0.0, 0.0
        );
        require(result.tracked(11L).phase() == DynamicShadowAdmission.Phase.MOVING
                        && result.selected(11L) != null
                        && result.staticToDynamicTransitions() == 1,
                "Movement did not return to a GPU page in the same frame");
    }

    private static void testHeldAdmissionAndHysteresis() {
        DynamicShadowAdmission admission = new DynamicShadowAdmission();
        LocalVoxelShadowLayout.DynamicShadowBudget budget =
                LocalVoxelShadowLayout.forPreset(LightingPreset.BALANCED).dynamicShadows();
        AdvancedLight held = light(91L, 10.0, -100, LocalShadowSourceClass.CAMERA_HELD);
        AdvancedLight near = light(92L, 2.0, 100, LocalShadowSourceClass.ENTITY_DYNAMIC);
        AdvancedLight nearlyEqual = light(93L, 2.05, 100,
                LocalShadowSourceClass.ENTITY_DYNAMIC);
        DynamicShadowAdmission.Result first = admission.select(
                List.of(candidate(held, true), candidate(near, true),
                        candidate(nearlyEqual, true)),
                budget, 0.0, 0.0, 0.0
        );
        require(first.heldAdmitted()
                        && first.selected(91L) != null
                        && first.selected(91L).heroSlot() == 0
                        && first.selected(92L) != null
                        && first.dropped() == 1,
                "Held light did not reserve slot zero ahead of more significant entities");
        AdvancedLight tinyAdvantage = light(93L, 1.99, 100,
                LocalShadowSourceClass.ENTITY_DYNAMIC);
        DynamicShadowAdmission.Result second = admission.select(
                List.of(candidate(held, true), candidate(near, true),
                        candidate(tinyAdvantage, true)),
                budget, 0.0, 0.0, 0.0
        );
        require(second.selected(92L) != null && second.selected(93L) == null,
                "Sub-material candidate difference churned the retained hero slot");
    }

    private static void testDuplicateStableIdCoalescesHeldProxy() {
        LocalVoxelShadowLayout.DynamicShadowBudget budget =
                LocalVoxelShadowLayout.forPreset(LightingPreset.BALANCED).dynamicShadows();
        AdvancedLight held = light(211L, 12.0, -100, LocalShadowSourceClass.CAMERA_HELD);
        AdvancedLight body = light(211L, 1.0, 100, LocalShadowSourceClass.ENTITY_DYNAMIC);
        AdvancedLight remote = light(212L, 3.0, 10, LocalShadowSourceClass.ENTITY_DYNAMIC);

        DynamicShadowAdmission.Result forward = new DynamicShadowAdmission().select(
                List.of(
                        new DynamicShadowAdmission.Candidate(held, false, false),
                        new DynamicShadowAdmission.Candidate(body, true, true),
                        candidate(remote, false)
                ),
                budget, 0.0, 0.0, 0.0
        );
        DynamicShadowAdmission.Result reverse = new DynamicShadowAdmission().select(
                List.of(
                        candidate(remote, false),
                        new DynamicShadowAdmission.Candidate(body, true, true),
                        new DynamicShadowAdmission.Candidate(held, false, false)
                ),
                budget, 0.0, 0.0, 0.0
        );

        require(forward.tracked().size() == 2
                        && forward.candidates() == 2
                        && forward.selected().size() == 2
                        && forward.dropped() == 0
                        && forward.heldAdmitted()
                        && forward.selected(211L) != null
                        && forward.selected(211L).heroSlot() == 0
                        && forward.tracked(211L).candidate().light().shadowSourceClass()
                        == LocalShadowSourceClass.CAMERA_HELD
                        && !forward.tracked(211L).candidate().cacheCovered()
                        && !forward.tracked(211L).candidate().exactStaticReady(),
                "held/body duplicate transferred body cache state to the camera anchor");
        require(new HashSet<>(forward.tracked().stream()
                        .map(value -> value.candidate().light().stableId()).toList())
                        .equals(new HashSet<>(reverse.tracked().stream()
                                .map(value -> value.candidate().light().stableId()).toList()))
                        && forward.selected().stream().map(value -> value.candidate().candidate().light().stableId()
                                + ":" + value.heroSlot()).toList().equals(reverse.selected().stream()
                                .map(value -> value.candidate().candidate().light().stableId()
                                        + ":" + value.heroSlot()).toList())
                        && reverse.selected(211L) != null
                        && reverse.selected(211L).heroSlot() == 0
                        && !reverse.tracked(211L).candidate().cacheCovered()
                        && !reverse.tracked(211L).candidate().exactStaticReady(),
                "duplicate coalescing changed with input insertion order");

        DynamicShadowAdmission.Selected selected = forward.selected(211L);
        Set<Long> claimed = new HashSet<>();
        require(LocalVoxelShadowGpuResources.claimDynamicHeroSlot(body, selected, claimed) == -1
                        && LocalVoxelShadowGpuResources.claimDynamicHeroSlot(
                                held, selected, claimed
                        ) == 0
                        && LocalVoxelShadowGpuResources.claimDynamicHeroSlot(
                                held, selected, claimed
                        ) == -1,
                "duplicate representations published the same dynamic atlas hero slot");
        claimed.clear();
        require(LocalVoxelShadowGpuResources.claimDynamicHeroSlot(held, selected, claimed) == 0
                        && LocalVoxelShadowGpuResources.claimDynamicHeroSlot(
                                body, selected, claimed
                        ) == -1,
                "dynamic atlas hero-slot ownership changed with duplicate insertion order");
    }

    private static void testSubThresholdMotionAccumulates() {
        DynamicShadowAdmission admission = new DynamicShadowAdmission();
        LocalVoxelShadowLayout.DynamicShadowBudget budget =
                LocalVoxelShadowLayout.forPreset(LightingPreset.BALANCED).dynamicShadows();
        DynamicShadowAdmission.Result result = admission.select(
                List.of(candidate(light(301L, 0.0, 1,
                        LocalShadowSourceClass.ENTITY_DYNAMIC), false)),
                budget, 0.0, 0.0, 0.0
        );
        for (int frame = 1; frame <= 8; frame++) {
            result = admission.select(
                    List.of(candidate(light(
                            301L,
                            frame * (DynamicShadowAdmission.MOVEMENT_THRESHOLD / 4.0),
                            1,
                            LocalShadowSourceClass.ENTITY_DYNAMIC
                    ), false)),
                    budget, 0.0, 0.0, 0.0
            );
        }
        require(result.tracked(301L).phase() == DynamicShadowAdmission.Phase.MOVING
                        && !result.tracked(301L).staticBuildAllowed(),
                "Sub-threshold per-frame drift did not accumulate into dynamic motion");
    }

    private static void testPresetAdmissionCaps() {
        for (LightingPreset preset : LightingPreset.values()) {
            DynamicShadowAdmission admission = new DynamicShadowAdmission();
            LocalVoxelShadowLayout.DynamicShadowBudget budget =
                    LocalVoxelShadowLayout.forPreset(preset).dynamicShadows();
            List<DynamicShadowAdmission.Candidate> candidates = new java.util.ArrayList<>();
            candidates.add(candidate(light(401L, 0.0, -100,
                    LocalShadowSourceClass.CAMERA_HELD), false));
            for (int index = 0; index < 8; index++) {
                candidates.add(candidate(light(
                        410L + index,
                        1.0 + index,
                        100 - index,
                        LocalShadowSourceClass.ENTITY_DYNAMIC
                ), false));
            }
            DynamicShadowAdmission.Result result = admission.select(
                    candidates, budget, 0.0, 0.0, 0.0
            );
            require(result.selected().size() == budget.heroSlots()
                            && result.selected(401L) != null
                            && result.selected(401L).heroSlot() == 0
                            && result.dropped() == candidates.size() - budget.heroSlots(),
                    "Dynamic admission no longer obeys the 1/2/4 preset budget");
        }
    }

    private static DynamicShadowAdmission.Candidate candidate(
            final AdvancedLight light,
            final boolean staticReady
    ) {
        return new DynamicShadowAdmission.Candidate(light, true, staticReady);
    }

    private static AdvancedLight light(
            final long stableId,
            final double x,
            final int priority,
            final LocalShadowSourceClass sourceClass
    ) {
        return new AdvancedLight(
                stableId,
                1L,
                LightSourceKind.ENTITY,
                x,
                0.0,
                0.0,
                8.0f,
                1.0f,
                0.8f,
                0.6f,
                1.0f,
                priority,
                false,
                ShadowEmitterFootprint.empty(),
                sourceClass
        );
    }

    private static void testStableResidencyAndLease() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8 * 2L);
        LocalVoxelShadowAtlasResidency.Decision first = residency.acquire(7L, 8, 10L, 4L, 10L);
        LocalVoxelShadowAtlasResidency.Decision touched = residency.acquire(7L, 8, 12L, 8L, 12L);
        require(first.path() == LocalVoxelShadowAtlasResidency.VisibilityPath.CACHED
                        && touched.path() == LocalVoxelShadowAtlasResidency.VisibilityPath.CACHED
                        && first.page().allocationId() == touched.page().allocationId()
                        && first.page().offsetBytes() == touched.page().offsetBytes()
                        && residency.leased(7L, 20L)
                        && !residency.leased(7L, 21L),
                "stable-id residency did not preserve or extend its lease");
    }

    private static void testReplacementDefersOldPageRetirement() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        long page64 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(64);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(
                page8 * 2L + page64
        );
        LocalVoxelShadowAtlasResidency.Page old = residency.acquire(1L, 8, 1L, 8L, 1L).page();
        residency.acquire(2L, 8, 1L, 8L, 1L);
        LocalVoxelShadowAtlasResidency.ReplacementReservation replacement =
                residency.reserveReplacement(
                1L, 64, 2L, 8L, 9L
        );
        require(replacement != null
                        && replacement.page().allocationId() != old.allocationId()
                        && residency.activePage(1L).allocationId() == old.allocationId()
                        && residency.retiredPageCount() == 0
                        && residency.usedBytes() == page8 * 2L + page64,
                "replacement did not allocate before retiring the active page");
        LocalVoxelShadowAtlasResidency.Page committed =
                residency.commitReplacement(replacement);
        require(residency.activePage(1L).allocationId() == committed.allocationId()
                        && residency.retiredPageCount() == 1,
                "size-changing replacement did not commit atomically");
        require(residency.releaseCompleted(8L) == 0 && residency.usedBytes() == page8 * 2L + page64,
                "resident page became reusable before its submit fence");
        require(residency.releaseCompleted(9L) == 1 && residency.usedBytes() == page8 + page64,
                "retired resident page was not released exactly at its submit fence");
    }

    private static void testApproximateDirectOverflow() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8, 1);
        require(residency.acquire(11L, 8, 1L, 1L, 1L).path()
                        == LocalVoxelShadowAtlasResidency.VisibilityPath.CACHED,
                "single-page resident atlas could not allocate its first page");
        LocalVoxelShadowAtlasResidency.Decision overflow = residency.acquire(12L, 8, 1L, 1L, 1L);
        require(overflow.path()
                        == LocalVoxelShadowAtlasResidency.VisibilityPath.APPROXIMATE_DIRECT
                        && overflow.page() == null,
                "full resident atlas did not choose approximate direct visibility");
        require(LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_APPROXIMATE_DIRECT == 0
                        && LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_READY == 1
                        && LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_FAIL_CLOSED == 4,
                "atlas descriptor states no longer express explicit shadow fallback");
    }

    private static void testSameEdgeReplacementNeverOverwritesInFlightPage() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8 * 2L);
        LocalVoxelShadowAtlasResidency.Page old = residency.acquire(
                21L, 8, 3L, 8L, 3L
        ).page();
        LocalVoxelShadowAtlasResidency.ReplacementReservation replacement =
                residency.reserveReplacement(
                21L, 8, 4L, 8L, 7L
        );
        require(replacement != null
                        && replacement.page().allocationId() != old.allocationId()
                        && replacement.page().offsetBytes() != old.offsetBytes()
                        && residency.activePage(21L).allocationId() == old.allocationId()
                        && residency.retiredPageCount() == 0
                        && residency.usedBytes() == page8 * 2L,
                "uncommitted same-edge rebuild replaced the active atlas page");
        LocalVoxelShadowAtlasResidency.Page committed =
                residency.commitReplacement(replacement);
        require(residency.activePage(21L).allocationId() == committed.allocationId()
                        && residency.retiredPageCount() == 1,
                "encoded same-edge replacement did not commit atomically");
        require(residency.releaseCompleted(6L) == 0
                        && residency.releaseCompleted(7L) == 1
                        && residency.usedBytes() == page8,
                "same-edge retired page ignored its reuse fence");
    }

    private static void testAbandonedReplacementKeepsOldPageActive() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8 * 2L);
        LocalVoxelShadowAtlasResidency.Page old = residency.acquire(
                23L, 8, 3L, 8L, 3L
        ).page();
        LocalVoxelShadowAtlasResidency.ReplacementReservation replacement =
                residency.reserveReplacement(23L, 8, 4L, 8L, 7L);
        require(replacement != null, "same-edge replacement could not reserve failure test page");
        residency.abandonReplacement(replacement);
        require(residency.activePage(23L).allocationId() == old.allocationId()
                        && residency.retiredPageCount() == 1
                        && residency.usedBytes() == page8 * 2L
                        && residency.releaseCompleted(6L) == 0
                        && residency.releaseCompleted(7L) == 1
                        && residency.usedBytes() == page8,
                "failed replacement discarded the old page or reused its range early");
    }

    private static void testFullAtlasRejectsSameEdgeOverwrite() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8);
        LocalVoxelShadowAtlasResidency.Page old = residency.acquire(
                22L, 8, 1L, 8L, 1L
        ).page();
        LocalVoxelShadowAtlasResidency.ReplacementReservation blocked =
                residency.reserveReplacement(
                22L, 8, 2L, 8L, 5L
        );
        require(blocked == null
                        && residency.activePage(22L).allocationId() == old.allocationId()
                        && residency.activePage(22L).offsetBytes() == old.offsetBytes()
                        && residency.retiredPageCount() == 0,
                "full atlas exposed an unsafe same-edge replacement destination");
    }

    private static void testCapacityRecoveryWaitsForFence() {
        long page64 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(64);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(
                page64 * 2L
        );
        LocalVoxelShadowAtlasResidency.Page oldTarget = residency.acquire(
                51L, 64, 1L, 8L, 1L
        ).page();
        residency.acquire(52L, 64, 1L, 8L, 1L);
        require(!residency.canAcquireReplacement(51L, 64)
                        && residency.retire(52L, 5L)
                        && residency.retiredPageCount() == 1
                        && residency.releaseCompleted(4L) == 0
                        && !residency.canAcquireReplacement(51L, 64),
                "capacity recovery reused a visible page before its submit fence");
        require(residency.releaseCompleted(5L) == 1
                        && residency.canAcquireReplacement(51L, 64),
                "capacity recovery did not expose its retired page at the exact fence");
        LocalVoxelShadowAtlasResidency.ReplacementReservation replacement =
                residency.reserveReplacement(51L, 64, 6L, 8L, 9L);
        require(replacement != null
                        && residency.activePage(51L).allocationId()
                        == oldTarget.allocationId(),
                "capacity recovery replaced the target before its page was encoded");
        LocalVoxelShadowAtlasResidency.Page committed =
                residency.commitReplacement(replacement);
        require(committed.allocationId() != oldTarget.allocationId()
                        && residency.activePage(51L).allocationId()
                        == committed.allocationId()
                        && residency.activePage(52L) == null
                        && residency.retiredPageCount() == 1,
                "capacity recovery did not atomically publish and fence-retire the target");
    }

    private static void testAbsentPageRetiresBehindSubmitFence() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8);
        LocalVoxelShadowAtlasResidency.Page page = residency.acquire(
                41L, 8, 6L, 120L, 6L
        ).page();
        require(residency.activeStableIds().equals(java.util.List.of(41L))
                        && residency.retire(41L, 9L)
                        && residency.activeStableIds().isEmpty()
                        && residency.releaseCompleted(8L) == 0
                        && residency.releaseCompleted(9L) == 1,
                "light leaving the direct snapshot retained its atlas lease or reused it early");
        LocalVoxelShadowAtlasResidency.Page reused = residency.acquire(
                42L, 8, 10L, 1L, 10L
        ).page();
        require(reused.offsetBytes() == page.offsetBytes(),
                "retired invisible-light page was not deterministically reused");
    }

    private static void testFragmentedAtlasRejectsStagingAdmission() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8 * 8L);
        for (long stableId = 1L; stableId <= 8L; stableId++) {
            residency.acquire(stableId, 8, 1L, 8L, 1L);
        }
        for (long stableId : new long[]{1L, 3L, 5L, 7L}) {
            residency.retire(stableId, 2L);
        }
        require(residency.releaseCompleted(2L) == 4
                        && residency.freeBytes()
                        == LocalVoxelShadowAtlasLayout.pageAllocationBytes(16)
                        && !residency.canAcquireReplacement(9L, 16),
                "fragmented atlas admitted staging without one contiguous destination");
        residency.retire(2L, 3L);
        residency.retire(4L, 3L);
        residency.releaseCompleted(3L);
        require(residency.canAcquireReplacement(9L, 16),
                "coalesced atlas range stayed unavailable after adjacent retirements");
    }

    private static void testExpiredLeaseAndExactRangeReuse() {
        long page8 = LocalVoxelShadowAtlasLayout.pageAllocationBytes(8);
        LocalVoxelShadowAtlasResidency residency = new LocalVoxelShadowAtlasResidency(page8);
        LocalVoxelShadowAtlasResidency.Page page = residency.acquire(31L, 8, 2L, 2L, 2L).page();
        require(residency.retireExpiredLeases(4L, 7L) == 0
                        && residency.retireExpiredLeases(5L, 7L) == 1
                        && residency.activePageCount() == 0
                        && residency.retiredPageCount() == 1,
                "lease hysteresis did not defer retirement through its final submit");
        require(residency.acquire(32L, 8, 5L, 2L, 5L).path()
                        == LocalVoxelShadowAtlasResidency.VisibilityPath.APPROXIMATE_DIRECT,
                "deferred page became reusable before GPU completion");
        require(residency.releaseCompleted(7L) == 1,
                "completed deferred page did not return to atlas free list");
        LocalVoxelShadowAtlasResidency.Page reused = residency.acquire(32L, 8, 8L, 2L, 8L).page();
        require(reused.offsetBytes() == page.offsetBytes()
                        && residency.usedBytes() == page8
                        && residency.freeBytes() == 0L,
                "coalesced free range did not deterministically reuse the old page");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
