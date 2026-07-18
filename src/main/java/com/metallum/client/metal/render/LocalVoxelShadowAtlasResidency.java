package com.metallum.client.metal.render;

import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Pure Java residency model for a bounded, stable-id-keyed local-shadow atlas.
 *
 * <p>It exposes cached visibility or an explicit approximate-direct decision. Page replacement
 * is two-phase so a failed Metal copy can never discard the last usable resident page.</p>
 */
final class LocalVoxelShadowAtlasResidency {
    enum VisibilityPath {
        CACHED,
        APPROXIMATE_DIRECT
    }

    record Page(
            long stableId,
            long allocationId,
            long offsetBytes,
            long payloadBytes,
            long allocationBytes,
            int edge,
            long leaseUntilSubmitIndex
    ) {
        Page {
            if (stableId == 0L || allocationId <= 0L || offsetBytes < 0L
                    || payloadBytes <= 0L || allocationBytes < payloadBytes
                    || allocationBytes % LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES != 0L
                    || !LocalVoxelShadowAtlasLayout.supportsPageEdge(edge)
                    || leaseUntilSubmitIndex < 0L) {
                throw new IllegalArgumentException("Invalid resident local-shadow page");
            }
        }
    }

    record Decision(VisibilityPath path, Page page) {
        Decision {
            Objects.requireNonNull(path, "path");
            if ((path == VisibilityPath.CACHED) != (page != null)) {
                throw new IllegalArgumentException("Resident atlas decision has inconsistent visibility path");
            }
        }

        static Decision approximateDirect() {
            return new Decision(VisibilityPath.APPROXIMATE_DIRECT, null);
        }
    }

    /** Fresh atlas range that is not visible through {@link #activePage(long)} until commit. */
    record ReplacementReservation(
            Page page,
            long expectedActiveAllocationId,
            long reusableAfterSubmitIndex
    ) {
        ReplacementReservation {
            Objects.requireNonNull(page, "page");
            if (expectedActiveAllocationId < 0L
                    || reusableAfterSubmitIndex < 0L) {
                throw new IllegalArgumentException("Invalid local-shadow replacement reservation");
            }
        }
    }

    private record RetiredPage(Page page, long reusableAfterSubmitIndex) {
        private RetiredPage {
            Objects.requireNonNull(page, "page");
            if (reusableAfterSubmitIndex < 0L) {
                throw new IllegalArgumentException("Retired page has a negative submit fence");
            }
        }
    }

    private static final Comparator<Long> UNSIGNED_LONG_ORDER = Long::compareUnsigned;
    private static final Comparator<RetiredPage> RETIRE_ORDER = (left, right) -> {
        int submitOrder = Long.compare(left.reusableAfterSubmitIndex(), right.reusableAfterSubmitIndex());
        return submitOrder != 0 ? submitOrder : Long.compare(left.page().offsetBytes(), right.page().offsetBytes());
    };

    private final long atlasBytes;
    private final int maximumActivePages;
    private final TreeMap<Long, Page> activeByStableId = new TreeMap<>(UNSIGNED_LONG_ORDER);
    private final TreeMap<Long, Long> freeRanges = new TreeMap<>();
    private final TreeMap<Long, ReplacementReservation> reservations = new TreeMap<>();
    private final PriorityQueue<RetiredPage> retiredPages = new PriorityQueue<>(RETIRE_ORDER);
    private long nextAllocationId;
    private long usedBytes;

    LocalVoxelShadowAtlasResidency(final long atlasBytes) {
        this(atlasBytes, LocalVoxelShadowAtlasLayout.MAX_LIGHT_DESCRIPTORS);
    }

    LocalVoxelShadowAtlasResidency(final long atlasBytes, final int maximumActivePages) {
        if (atlasBytes <= 0L
                || atlasBytes % LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES != 0L
                || maximumActivePages <= 0
                || maximumActivePages > LocalVoxelShadowAtlasLayout.MAX_LIGHT_DESCRIPTORS) {
            throw new IllegalArgumentException("Invalid resident local-shadow atlas capacity");
        }
        this.atlasBytes = atlasBytes;
        this.maximumActivePages = maximumActivePages;
        this.freeRanges.put(0L, atlasBytes);
    }

    /**
     * Acquires a first page or only extends the lease of an existing stable source. Every content
     * or size replacement must use the two-phase reservation API below.
     */
    Decision acquire(
            final long stableId,
            final int edge,
            final long submitIndex,
            final long leaseSubmitCount,
            final long reusableAfterSubmitIndex
    ) {
        return acquireInternal(
                stableId,
                edge,
                submitIndex,
                leaseSubmitCount,
                reusableAfterSubmitIndex
        );
    }

    /**
     * Reserves a fresh destination without changing the active stable-id mapping. The caller must
     * commit only after the replacement blit has been encoded, or abandon it on failure.
     */
    ReplacementReservation reserveReplacement(
            final long stableId,
            final int edge,
            final long submitIndex,
            final long leaseSubmitCount,
            final long reusableAfterSubmitIndex
    ) {
        requireStableId(stableId);
        requireSubmitArguments(submitIndex, leaseSubmitCount, reusableAfterSubmitIndex);
        long payloadBytes = LocalVoxelShadowAtlasLayout.pagePayloadBytes(edge);
        long allocationBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(edge);
        Page active = this.activeByStableId.get(stableId);
        if (active == null && this.activeByStableId.size() >= this.maximumActivePages) {
            return null;
        }
        Long offset = allocateRange(allocationBytes);
        if (offset == null) {
            return null;
        }
        Page replacement = new Page(
                stableId,
                nextAllocationId(),
                offset,
                payloadBytes,
                allocationBytes,
                edge,
                leaseUntil(submitIndex, leaseSubmitCount)
        );
        ReplacementReservation reservation = new ReplacementReservation(
                replacement,
                active == null ? 0L : active.allocationId(),
                reusableAfterSubmitIndex
        );
        this.reservations.put(replacement.allocationId(), reservation);
        return reservation;
    }

    /** Atomically publishes a successfully encoded replacement and fence-retires its predecessor. */
    Page commitReplacement(final ReplacementReservation reservation) {
        ReplacementReservation owned = ownedReservation(reservation);
        Page current = this.activeByStableId.get(owned.page().stableId());
        long currentAllocationId = current == null ? 0L : current.allocationId();
        if (currentAllocationId != owned.expectedActiveAllocationId()) {
            throw new IllegalStateException("Active local-shadow page changed during replacement");
        }
        this.reservations.remove(owned.page().allocationId());
        this.activeByStableId.put(owned.page().stableId(), owned.page());
        if (current != null) {
            this.retiredPages.add(new RetiredPage(
                    current, owned.reusableAfterSubmitIndex()
            ));
        }
        return owned.page();
    }

    /** Keeps the old active page and fence-retires only the unused reserved destination. */
    void abandonReplacement(final ReplacementReservation reservation) {
        ReplacementReservation owned = ownedReservation(reservation);
        this.reservations.remove(owned.page().allocationId());
        this.retiredPages.add(new RetiredPage(
                owned.page(), owned.reusableAfterSubmitIndex()
        ));
    }

    /**
     * Non-mutating admission check used before allocating transient staging. Residency is owned
     * by the render thread, so a following {@link #reserveReplacement} cannot race this result.
     */
    boolean canAcquireReplacement(final long stableId, final int edge) {
        requireStableId(stableId);
        long allocationBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(edge);
        Page active = this.activeByStableId.get(stableId);
        if (active == null && this.activeByStableId.size() >= this.maximumActivePages) {
            return false;
        }
        return findRangeOffset(allocationBytes) != null;
    }

    private Decision acquireInternal(
            final long stableId,
            final int edge,
            final long submitIndex,
            final long leaseSubmitCount,
            final long reusableAfterSubmitIndex
    ) {
        requireStableId(stableId);
        requireSubmitArguments(submitIndex, leaseSubmitCount, reusableAfterSubmitIndex);
        long payloadBytes = LocalVoxelShadowAtlasLayout.pagePayloadBytes(edge);
        long allocationBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(edge);
        Page active = this.activeByStableId.get(stableId);
        long leaseUntil = leaseUntil(submitIndex, leaseSubmitCount);
        if (active != null) {
            Page touched = touch(active, leaseUntil);
            this.activeByStableId.put(stableId, touched);
            return new Decision(VisibilityPath.CACHED, touched);
        }

        if (active == null && this.activeByStableId.size() >= this.maximumActivePages) {
            return Decision.approximateDirect();
        }
        Long offset = allocateRange(allocationBytes);
        if (offset == null) {
            return Decision.approximateDirect();
        }

        Page replacement = new Page(
                stableId,
                nextAllocationId(),
                offset,
                payloadBytes,
                allocationBytes,
                edge,
                leaseUntil
        );
        this.activeByStableId.put(stableId, replacement);
        return new Decision(VisibilityPath.CACHED, replacement);
    }

    /** Stops leasing one source; its page remains allocated until the specified GPU submit ends. */
    boolean retire(
            final long stableId,
            final long reusableAfterSubmitIndex
    ) {
        requireStableId(stableId);
        if (reusableAfterSubmitIndex < 0L) {
            throw new IllegalArgumentException("Resident atlas retire fence is negative");
        }
        Page active = this.activeByStableId.remove(stableId);
        if (active == null) {
            return false;
        }
        this.retiredPages.add(new RetiredPage(active, reusableAfterSubmitIndex));
        return true;
    }

    /** Schedules expired leases for deferred retirement in deterministic unsigned stable-id order. */
    int retireExpiredLeases(
            final long currentSubmitIndex,
            final long reusableAfterSubmitIndex
    ) {
        if (currentSubmitIndex < 0L || reusableAfterSubmitIndex < currentSubmitIndex) {
            throw new IllegalArgumentException("Invalid resident atlas expiry fence");
        }
        int retired = 0;
        var iterator = this.activeByStableId.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Page> entry = iterator.next();
            if (currentSubmitIndex <= entry.getValue().leaseUntilSubmitIndex()) {
                continue;
            }
            this.retiredPages.add(new RetiredPage(entry.getValue(), reusableAfterSubmitIndex));
            iterator.remove();
            retired++;
        }
        return retired;
    }

    /** Makes pages reusable only after every earlier GPU consumer has completed. */
    int releaseCompleted(final long completedSubmitIndex) {
        if (completedSubmitIndex < 0L) {
            throw new IllegalArgumentException("Completed submit index is negative");
        }
        int released = 0;
        while (!this.retiredPages.isEmpty()
                && this.retiredPages.peek().reusableAfterSubmitIndex() <= completedSubmitIndex) {
            RetiredPage retired = this.retiredPages.remove();
            freeRange(retired.page().offsetBytes(), retired.page().allocationBytes());
            released++;
        }
        return released;
    }

    Page activePage(final long stableId) {
        requireStableId(stableId);
        return this.activeByStableId.get(stableId);
    }

    boolean leased(final long stableId, final long submitIndex) {
        requireStableId(stableId);
        if (submitIndex < 0L) {
            throw new IllegalArgumentException("Submit index is negative");
        }
        Page page = this.activeByStableId.get(stableId);
        return page != null && submitIndex <= page.leaseUntilSubmitIndex();
    }

    int activePageCount() {
        return this.activeByStableId.size();
    }

    /** Deterministic snapshot used to retire pages that left the actual direct-light set. */
    List<Long> activeStableIds() {
        return List.copyOf(this.activeByStableId.keySet());
    }

    int retiredPageCount() {
        return this.retiredPages.size();
    }

    long atlasBytes() {
        return this.atlasBytes;
    }

    long usedBytes() {
        return this.usedBytes;
    }

    long freeBytes() {
        return this.atlasBytes - this.usedBytes;
    }

    private Long allocateRange(final long allocationBytes) {
        Long alignedStart = findRangeOffset(allocationBytes);
        if (alignedStart == null) {
            return null;
        }
        Map.Entry<Long, Long> range = this.freeRanges.floorEntry(alignedStart);
        if (range == null) {
            throw new IllegalStateException("Resident atlas lost an admitted free range");
        }
        long start = range.getKey();
        long length = range.getValue();
        long padding = alignedStart - start;
        this.freeRanges.remove(start);
        if (padding > 0L) {
            this.freeRanges.put(start, padding);
        }
        long trailingStart = Math.addExact(alignedStart, allocationBytes);
        long trailing = length - padding - allocationBytes;
        if (trailing > 0L) {
            this.freeRanges.put(trailingStart, trailing);
        }
        this.usedBytes = Math.addExact(this.usedBytes, allocationBytes);
        return alignedStart;
    }

    private Long findRangeOffset(final long allocationBytes) {
        for (Map.Entry<Long, Long> entry : this.freeRanges.entrySet()) {
            long start = entry.getKey();
            long length = entry.getValue();
            long alignedStart = alignUp(start, LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES);
            long padding = alignedStart - start;
            if (padding <= length && allocationBytes <= length - padding) {
                return alignedStart;
            }
        }
        return null;
    }

    private ReplacementReservation ownedReservation(
            final ReplacementReservation reservation
    ) {
        Objects.requireNonNull(reservation, "reservation");
        ReplacementReservation owned = this.reservations.get(
                reservation.page().allocationId()
        );
        if (owned == null || !owned.equals(reservation)) {
            throw new IllegalStateException("Unknown local-shadow replacement reservation");
        }
        return owned;
    }

    private void freeRange(final long offset, final long length) {
        if (offset < 0L || length <= 0L || offset % LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES != 0L
                || length % LocalVoxelShadowAtlasLayout.PAGE_ALIGNMENT_BYTES != 0L) {
            throw new IllegalStateException("Resident atlas attempted to free an invalid page range");
        }
        Map.Entry<Long, Long> lower = this.freeRanges.floorEntry(offset);
        long mergedOffset = offset;
        long mergedLength = length;
        if (lower != null && Math.addExact(lower.getKey(), lower.getValue()) == offset) {
            mergedOffset = lower.getKey();
            mergedLength = Math.addExact(mergedLength, lower.getValue());
            this.freeRanges.remove(lower.getKey());
        }
        Map.Entry<Long, Long> higher = this.freeRanges.ceilingEntry(offset);
        if (higher != null && Math.addExact(offset, length) == higher.getKey()) {
            mergedLength = Math.addExact(mergedLength, higher.getValue());
            this.freeRanges.remove(higher.getKey());
        }
        this.freeRanges.put(mergedOffset, mergedLength);
        this.usedBytes = Math.subtractExact(this.usedBytes, length);
    }

    private long nextAllocationId() {
        this.nextAllocationId = Math.addExact(this.nextAllocationId, 1L);
        return this.nextAllocationId;
    }

    private static Page touch(final Page page, final long leaseUntil) {
        return new Page(
                page.stableId(), page.allocationId(), page.offsetBytes(), page.payloadBytes(),
                page.allocationBytes(), page.edge(), Math.max(page.leaseUntilSubmitIndex(), leaseUntil)
        );
    }

    private static void requireStableId(final long stableId) {
        if (stableId == 0L) {
            throw new IllegalArgumentException("Stable light id zero is reserved");
        }
    }

    private static void requireSubmitArguments(
            final long submitIndex,
            final long leaseSubmitCount,
            final long reusableAfterSubmitIndex
    ) {
        if (submitIndex < 0L || leaseSubmitCount < 0L
                || reusableAfterSubmitIndex < submitIndex) {
            throw new IllegalArgumentException("Invalid resident atlas submit/lease arguments");
        }
    }

    private static long leaseUntil(final long submitIndex, final long leaseSubmitCount) {
        return Math.addExact(submitIndex, leaseSubmitCount);
    }

    private static long alignUp(final long value, final long alignment) {
        long remainder = value % alignment;
        return remainder == 0L ? value : Math.addExact(value, alignment - remainder);
    }
}
