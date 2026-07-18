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
 * <p>It intentionally exposes only cached visibility or exact-DDA fallback. There is no
 * unshadowed decision: callers that cannot acquire a page must retain exact visibility work
 * until a page becomes available.</p>
 */
final class LocalVoxelShadowAtlasResidency {
    enum VisibilityPath {
        CACHED,
        DDA_FALLBACK
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

    record Decision(VisibilityPath path, Page page, boolean replacementAllocated) {
        Decision {
            Objects.requireNonNull(path, "path");
            if ((path == VisibilityPath.CACHED) != (page != null)) {
                throw new IllegalArgumentException("Resident atlas decision has inconsistent visibility path");
            }
            if (replacementAllocated && path != VisibilityPath.CACHED) {
                throw new IllegalArgumentException("Only a cached page may replace an old page");
            }
        }

        static Decision ddaFallback() {
            return new Decision(VisibilityPath.DDA_FALLBACK, null, false);
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
     * Returns the current page for a stable source, or exact DDA when no page can fit. A changed
     * page size first allocates its replacement and retires the old page only after the caller's
     * submitted-work fence, so a visible source never loses its last valid cache during rebuild.
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
                reusableAfterSubmitIndex,
                false
        );
    }

    /**
     * Allocates a fresh destination even when the active page has the same edge. Cached geometry
     * updates must never overwrite a range that an earlier in-flight frame can still read.
     */
    Decision acquireReplacement(
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
                reusableAfterSubmitIndex,
                true
        );
    }

    /**
     * Non-mutating admission check used before allocating transient staging. Residency is owned
     * by the render thread, so a following {@link #acquireReplacement} cannot race this result.
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
            final long reusableAfterSubmitIndex,
            final boolean forceReplacement
    ) {
        requireStableId(stableId);
        requireSubmitArguments(submitIndex, leaseSubmitCount, reusableAfterSubmitIndex);
        long payloadBytes = LocalVoxelShadowAtlasLayout.pagePayloadBytes(edge);
        long allocationBytes = LocalVoxelShadowAtlasLayout.pageAllocationBytes(edge);
        Page active = this.activeByStableId.get(stableId);
        long leaseUntil = leaseUntil(submitIndex, leaseSubmitCount);
        if (active != null && active.edge() == edge && !forceReplacement) {
            Page touched = touch(active, leaseUntil);
            this.activeByStableId.put(stableId, touched);
            return new Decision(VisibilityPath.CACHED, touched, false);
        }

        if (active == null && this.activeByStableId.size() >= this.maximumActivePages) {
            return Decision.ddaFallback();
        }
        Long offset = allocateRange(allocationBytes);
        if (offset == null) {
            if (active == null) {
                return Decision.ddaFallback();
            }
            Page touched = touch(active, leaseUntil);
            this.activeByStableId.put(stableId, touched);
            return new Decision(VisibilityPath.CACHED, touched, false);
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
        if (active != null) {
            this.retiredPages.add(new RetiredPage(active, reusableAfterSubmitIndex));
        }
        return new Decision(VisibilityPath.CACHED, replacement, active != null);
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
