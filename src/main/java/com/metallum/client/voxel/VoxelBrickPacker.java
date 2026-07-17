package com.metallum.client.voxel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Two-worker asynchronous full-brick packer fed only immutable accepted section snapshots. */
final class VoxelBrickPacker implements AutoCloseable {
    static final int MAX_WORKERS = 2;
    static final int MAX_PENDING_JOBS = 16;
    private static final long[] COARSE_2_MASKS = coarse2Masks();

    record Contributor(
            long sectionKey,
            long revision,
            long ownerToken,
            VoxelSectionSnapshot snapshot,
            boolean absent
    ) {
        Contributor {
            if (absent != (snapshot == null) || revision < 0L || ownerToken < 0L) {
                throw new IllegalArgumentException("Invalid voxel contributor state");
            }
        }
    }

    record Ticket(
            VoxelDirtyQueue.DirtyBrick dirty,
            VoxelWorldToken world,
            long clipmapGeneration,
            VoxelClipmapLayout.Level layout,
            int brickDimension,
            long desiredVersion,
            int contentStamp,
            List<Contributor> contributors
    ) {
        Ticket {
            Objects.requireNonNull(dirty, "dirty");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(layout, "layout");
            contributors = List.copyOf(Objects.requireNonNull(contributors, "contributors"));
            if (clipmapGeneration <= 0L || brickDimension <= 0 || desiredVersion <= 0L
                    || contentStamp == 0 || contributors.isEmpty()
                    || dirty.key().worldGeneration() != world.generation()
                    || dirty.key().clipmapGeneration() != clipmapGeneration) {
                throw new IllegalArgumentException("Invalid voxel pack ticket");
            }
        }
    }

    record Result(Ticket ticket, VoxelBrickPatch patch, Throwable failure) {
        Result {
            Objects.requireNonNull(ticket, "ticket");
            if ((patch == null) == (failure == null)) {
                throw new IllegalArgumentException("Voxel pack result must contain patch xor failure");
            }
        }
    }

    private static final AtomicInteger NEXT_THREAD = new AtomicInteger();

    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final ConcurrentLinkedQueue<Result> completed = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingJobs = new AtomicInteger();
    private volatile boolean closed;

    VoxelBrickPacker() {
        ThreadFactory factory = operation -> {
            Thread thread = new Thread(
                    operation, "Metallum-L5-Packer-" + NEXT_THREAD.incrementAndGet()
            );
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        ExecutorService service = Executors.newFixedThreadPool(MAX_WORKERS, factory);
        this.executor = service;
        this.ownedExecutor = service;
    }

    VoxelBrickPacker(final Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.ownedExecutor = null;
    }

    boolean submit(final Ticket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (this.closed || !reserveJob()) {
            return false;
        }
        try {
            this.executor.execute(() -> {
                Result result;
                try {
                    result = new Result(ticket, pack(ticket), null);
                } catch (Throwable failure) {
                    result = new Result(ticket, null, failure);
                }
                if (!this.closed) {
                    this.completed.add(result);
                }
                this.pendingJobs.decrementAndGet();
            });
            return true;
        } catch (RuntimeException failure) {
            this.pendingJobs.decrementAndGet();
            throw failure;
        }
    }

    int pendingJobs() {
        return this.pendingJobs.get();
    }

    Result pollCompleted() {
        return this.completed.poll();
    }

    static VoxelBrickPatch pack(final Ticket ticket) {
        VoxelDirtyQueue.BrickKey key = ticket.dirty().key();
        int subdivision = ticket.layout().subdivision().scale();
        int baseEdge = ticket.layout().brickBlockEdge();
        int opticalLength = Math.multiplyExact(Math.multiplyExact(baseEdge, baseEdge), baseEdge);
        byte[] payload = new byte[VoxelBrickPatch.OCCUPANCY_BYTES + opticalLength];
        ByteBuffer littleEndian = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        long originX = Math.multiplyExact(key.brickX(), baseEdge);
        long originY = Math.multiplyExact(key.brickY(), baseEdge);
        long originZ = Math.multiplyExact(key.brickZ(), baseEdge);
        ContributorGrid contributorGrid = ContributorGrid.from(ticket.contributors());
        for (int blockZ = 0; blockZ < baseEdge; blockZ++) {
            for (int blockY = 0; blockY < baseEdge; blockY++) {
                for (int blockX = 0; blockX < baseEdge; blockX++) {
                    long blockWorldX = originX + blockX;
                    long blockWorldY = originY + blockY;
                    long blockWorldZ = originZ + blockZ;
                    Contributor contributor = contributorGrid.at(
                            blockWorldX, blockWorldY, blockWorldZ
                    );
                    int localIndex = ((int) blockWorldY & 15) << 8
                            | ((int) blockWorldZ & 15) << 4 | ((int) blockWorldX & 15);
                    long sourceMask = contributor.absent()
                            ? 0L : contributor.snapshot().occupancyMaskUnchecked(localIndex);
                    int opticalOffset = VoxelBrickPatch.OCCUPANCY_BYTES
                            + (blockZ * baseEdge + blockY) * baseEdge + blockX;
                    payload[opticalOffset] = contributor.absent()
                            ? 0 : contributor.snapshot().opticalUnchecked(localIndex);
                    if (sourceMask != 0L) {
                        writeOccupancy(
                                littleEndian, sourceMask, subdivision, blockX, blockY, blockZ
                        );
                    }
                }
            }
        }
        return VoxelBrickPatch.fromOwnedPackedPayload(
                key.level(),
                Math.floorMod(key.brickX(), ticket.brickDimension()),
                Math.floorMod(key.brickY(), ticket.brickDimension()),
                Math.floorMod(key.brickZ(), ticket.brickDimension()),
                Math.toIntExact(key.brickX()),
                Math.toIntExact(key.brickY()),
                Math.toIntExact(key.brickZ()),
                ticket.contentStamp(),
                ticket.world().generation(),
                ticket.clipmapGeneration(),
                payload,
                opticalLength
        );
    }

    private static void writeOccupancy(
            final ByteBuffer output,
            final long sourceMask,
            final int subdivision,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        if (subdivision == 1) {
            setOccupied(output, blockX, blockY, blockZ);
            return;
        }
        if (subdivision == VoxelSectionSnapshot.SOURCE_SUBDIVISION) {
            long remaining = sourceMask;
            while (remaining != 0L) {
                int bit = Long.numberOfTrailingZeros(remaining);
                int fineX = bit & 3;
                int fineY = bit >>> 2 & 3;
                int fineZ = bit >>> 4;
                setOccupied(output,
                        blockX * subdivision + fineX,
                        blockY * subdivision + fineY,
                        blockZ * subdivision + fineZ);
                remaining &= remaining - 1L;
            }
            return;
        }
        for (int coarseZ = 0; coarseZ < 2; coarseZ++) {
            for (int coarseY = 0; coarseY < 2; coarseY++) {
                for (int coarseX = 0; coarseX < 2; coarseX++) {
                    long coarseMask = COARSE_2_MASKS[(coarseZ * 2 + coarseY) * 2 + coarseX];
                    if ((sourceMask & coarseMask) != 0L) {
                        setOccupied(output,
                                blockX * 2 + coarseX,
                                blockY * 2 + coarseY,
                                blockZ * 2 + coarseZ);
                    }
                }
            }
        }
    }

    private static void setOccupied(
            final ByteBuffer output,
            final int logicalX,
            final int logicalY,
            final int logicalZ
    ) {
        int wordOffset = (logicalZ * VoxelBrickPatch.LOGICAL_EDGE + logicalY) * Integer.BYTES;
        output.putInt(wordOffset, output.getInt(wordOffset) | 1 << logicalX);
    }

    private static long[] coarse2Masks() {
        long[] masks = new long[8];
        for (int coarseZ = 0; coarseZ < 2; coarseZ++) {
            for (int coarseY = 0; coarseY < 2; coarseY++) {
                for (int coarseX = 0; coarseX < 2; coarseX++) {
                    int coarse = (coarseZ * 2 + coarseY) * 2 + coarseX;
                    for (int fineZ = coarseZ * 2; fineZ < coarseZ * 2 + 2; fineZ++) {
                        for (int fineY = coarseY * 2; fineY < coarseY * 2 + 2; fineY++) {
                            for (int fineX = coarseX * 2; fineX < coarseX * 2 + 2; fineX++) {
                                masks[coarse] |= 1L << ((fineZ * 4 + fineY) * 4 + fineX);
                            }
                        }
                    }
                }
            }
        }
        return masks;
    }

    private record ContributorGrid(
            List<Contributor> contributors,
            int minX,
            int minY,
            int minZ,
            int countX,
            int countY
    ) {
        private static ContributorGrid from(final List<Contributor> contributors) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            for (Contributor contributor : contributors) {
                int x = net.minecraft.core.SectionPos.x(contributor.sectionKey());
                int y = net.minecraft.core.SectionPos.y(contributor.sectionKey());
                int z = net.minecraft.core.SectionPos.z(contributor.sectionKey());
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                minZ = Math.min(minZ, z);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            return new ContributorGrid(
                    contributors, minX, minY, minZ, maxX - minX + 1, maxY - minY + 1
            );
        }

        private Contributor at(final long blockX, final long blockY, final long blockZ) {
            int sectionX = Math.toIntExact(Math.floorDiv(blockX, 16L));
            int sectionY = Math.toIntExact(Math.floorDiv(blockY, 16L));
            int sectionZ = Math.toIntExact(Math.floorDiv(blockZ, 16L));
            int index = (sectionZ - this.minZ) * this.countY * this.countX
                    + (sectionY - this.minY) * this.countX + sectionX - this.minX;
            if (index < 0 || index >= this.contributors.size()) {
                throw new IllegalStateException("Voxel pack ticket omitted a contributing section");
            }
            return this.contributors.get(index);
        }
    }

    private boolean reserveJob() {
        while (true) {
            int current = this.pendingJobs.get();
            if (current >= MAX_PENDING_JOBS) {
                return false;
            }
            if (this.pendingJobs.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.completed.clear();
        if (this.ownedExecutor != null) {
            this.ownedExecutor.shutdownNow();
        }
    }
}
