package com.metallum.client.metal.render;

import com.metallum.client.renderer.AdvancedLightingLayout;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;

/** Standalone contracts for the future resident local-shadow atlas foundation. */
public final class LocalVoxelShadowAtlasResidencyTests {
    private LocalVoxelShadowAtlasResidencyTests() {
    }

    public static void main(final String[] args) {
        testLayoutAccounting();
        testStableResidencyAndLease();
        testReplacementDefersOldPageRetirement();
        testSameEdgeReplacementNeverOverwritesInFlightPage();
        testAbandonedReplacementKeepsOldPageActive();
        testFullAtlasRejectsSameEdgeOverwrite();
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
