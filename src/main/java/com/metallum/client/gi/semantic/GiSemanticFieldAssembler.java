package com.metallum.client.gi.semantic;

import com.metallum.client.gi.field.GiFieldLayout;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Render-thread-owned bounded placement of accepted sections into the complete 3x32^3 CPU field. */
public final class GiSemanticFieldAssembler {
    public static final int MAX_RESIDENT_SECTION_TAGS = 17 * 17 * 17;

    public enum ApplyResult { ACCEPTED, STALE, OUTSIDE, CAPACITY }

    private final Thread ownerThread;
    private GiSemanticWorldToken world;
    private GiSemanticPalette palette;
    private volatile long clipmapGeneration = 1L;
    private long contentGeneration = 1L;
    private final int[] origins = new int[GiFieldLayout.CASCADE_COUNT * 3];
    private final short[] albedoRgb = new short[GiSemanticFieldSnapshot.TOTAL_CELLS * 3];
    private final short[] emission = new short[GiSemanticFieldSnapshot.TOTAL_CELLS * 4];
    private final byte[] occupancy = new byte[GiSemanticFieldSnapshot.TOTAL_CELLS];
    private final byte[] medium = new byte[GiSemanticFieldSnapshot.TOTAL_CELLS];
    private final byte[] validity = new byte[GiSemanticFieldSnapshot.TOTAL_CELLS];
    private final byte[] provenance = new byte[GiSemanticFieldSnapshot.TOTAL_CELLS];
    private final byte[] faces = new byte[GiSemanticFieldSnapshot.TOTAL_CELLS * 6];
    private final byte[] coverage = new byte[GiSemanticFieldSnapshot.TOTAL_CELLS];
    private final short[] materialIds = new short[GiSemanticFieldSnapshot.TOTAL_CELLS];
    private final long[] contentGenerations = new long[GiSemanticFieldSnapshot.TOTAL_CELLS];
    private final Map<Long, ResidentTag> residentTags = new HashMap<>();

    public GiSemanticFieldAssembler(
            final GiSemanticWorldToken world,
            final GiSemanticPalette palette,
            final int cameraBlockX,
            final int cameraBlockY,
            final int cameraBlockZ
    ) {
        this.ownerThread = Thread.currentThread();
        this.world = requireMatchingPalette(world, palette);
        this.palette = palette;
        setOrigins(cameraBlockX, cameraBlockY, cameraBlockZ);
        clearTruth(false);
    }

    public long clipmapGeneration() {
        return this.clipmapGeneration;
    }

    public boolean containsSection(final long sectionKey) {
        assertOwnerThread();
        return intersectsAnyCascade(sectionKey);
    }

    public GiSemanticWorldToken world() {
        assertOwnerThread();
        return this.world;
    }

    public GiSemanticPalette palette() {
        assertOwnerThread();
        return this.palette;
    }

    public int[] origins() {
        assertOwnerThread();
        return this.origins.clone();
    }

    /** Cell-aligned origin changes preserve exact overlap and invalidate only newly exposed slabs. */
    public boolean updateCamera(final int blockX, final int blockY, final int blockZ) {
        assertOwnerThread();
        int[] next = centeredOrigins(blockX, blockY, blockZ);
        if (Arrays.equals(next, this.origins)) {
            return false;
        }
        for (int cascade = 0; cascade < GiFieldLayout.CASCADE_COUNT; cascade++) {
            int originIndex = cascade * 3;
            int cellSize = GiFieldLayout.cellSizeBlocks(cascade);
            long deltaBlockX = (long) next[originIndex] - this.origins[originIndex];
            long deltaBlockY = (long) next[originIndex + 1] - this.origins[originIndex + 1];
            long deltaBlockZ = (long) next[originIndex + 2] - this.origins[originIndex + 2];
            if (deltaBlockX % cellSize != 0L || deltaBlockY % cellSize != 0L
                    || deltaBlockZ % cellSize != 0L) {
                throw new IllegalStateException("G2 field origin lost cell alignment");
            }
            long deltaCellX = deltaBlockX / cellSize;
            long deltaCellY = deltaBlockY / cellSize;
            long deltaCellZ = deltaBlockZ / cellSize;
            if (Math.abs(deltaCellX) >= GiFieldLayout.CELLS_PER_AXIS
                    || Math.abs(deltaCellY) >= GiFieldLayout.CELLS_PER_AXIS
                    || Math.abs(deltaCellZ) >= GiFieldLayout.CELLS_PER_AXIS) {
                clearCascade(cascade);
            } else {
                scrollCascade(cascade, (int) deltaCellX, (int) deltaCellY, (int) deltaCellZ);
            }
        }
        this.clipmapGeneration = Math.incrementExact(this.clipmapGeneration);
        System.arraycopy(next, 0, this.origins, 0, next.length);
        this.residentTags.entrySet().removeIf(entry -> !intersectsAnyCascade(entry.getKey()));
        this.contentGeneration = Math.incrementExact(this.contentGeneration);
        return true;
    }

    public void reset(
            final GiSemanticWorldToken nextWorld,
            final GiSemanticPalette nextPalette,
            final int cameraBlockX,
            final int cameraBlockY,
            final int cameraBlockZ
    ) {
        assertOwnerThread();
        this.world = requireMatchingPalette(nextWorld, nextPalette);
        this.palette = nextPalette;
        this.clipmapGeneration = Math.incrementExact(this.clipmapGeneration);
        setOrigins(cameraBlockX, cameraBlockY, cameraBlockZ);
        clearTruth(true);
    }

    public ApplyResult apply(final GiSemanticSectionTask task, final GiSemanticSectionSnapshot snapshot) {
        assertOwnerThread();
        if (!this.world.equals(task.world()) || task.clipmapGeneration() != this.clipmapGeneration
                || task.paletteGeneration() != this.palette.generation()) {
            return ApplyResult.STALE;
        }
        if (!intersectsAnyCascade(task.sectionKey())) {
            return ApplyResult.OUTSIDE;
        }
        ResidentTag prior = this.residentTags.get(task.sectionKey());
        if (prior == null && this.residentTags.size() >= MAX_RESIDENT_SECTION_TAGS) {
            return ApplyResult.CAPACITY;
        }
        deposit(task.sectionKey(), task.revision(), snapshot);
        this.residentTags.put(task.sectionKey(), new ResidentTag(task.ownerToken(), task.revision()));
        this.contentGeneration = Math.incrementExact(this.contentGeneration);
        return ApplyResult.ACCEPTED;
    }

    public boolean removeIfOwner(final long sectionKey, final long ownerToken) {
        assertOwnerThread();
        ResidentTag resident = this.residentTags.get(sectionKey);
        if (resident == null || resident.ownerToken != ownerToken) {
            return false;
        }
        clearSection(sectionKey);
        this.residentTags.remove(sectionKey);
        this.contentGeneration = Math.incrementExact(this.contentGeneration);
        return true;
    }

    public long currentOwner(final long sectionKey) {
        assertOwnerThread();
        ResidentTag resident = this.residentTags.get(sectionKey);
        return resident == null ? 0L : resident.ownerToken;
    }

    public int residentSections() {
        assertOwnerThread();
        return this.residentTags.size();
    }

    public GiSemanticFieldSnapshot snapshot() {
        assertOwnerThread();
        return new GiSemanticFieldSnapshot(
                this.world, this.clipmapGeneration, this.palette.generation(), this.contentGeneration,
                this.palette.digest(),
                this.origins, this.albedoRgb, this.emission, this.occupancy, this.medium,
                this.validity, this.provenance, this.faces, this.coverage, this.materialIds,
                this.contentGenerations, this.residentTags.size()
        );
    }

    private void deposit(final long sectionKey, final long contentGeneration,
                         final GiSemanticSectionSnapshot snapshot) {
        int sectionBlockX = GiSemanticCoordinates.sectionToBlock(GiSemanticCoordinates.sectionX(sectionKey));
        int sectionBlockY = GiSemanticCoordinates.sectionToBlock(GiSemanticCoordinates.sectionY(sectionKey));
        int sectionBlockZ = GiSemanticCoordinates.sectionToBlock(GiSemanticCoordinates.sectionZ(sectionKey));
        for (int cascade = 0; cascade < GiFieldLayout.CASCADE_COUNT; cascade++) {
            int cellSize = GiFieldLayout.cellSizeBlocks(cascade);
            int localEdge = GiSemanticSectionSnapshot.CASCADE_CELL_EDGES[cascade];
            int originIndex = cascade * 3;
            int fieldStartX = Math.floorDiv(sectionBlockX - this.origins[originIndex], cellSize);
            int fieldStartY = Math.floorDiv(sectionBlockY - this.origins[originIndex + 1], cellSize);
            int fieldStartZ = Math.floorDiv(sectionBlockZ - this.origins[originIndex + 2], cellSize);
            for (int z = 0; z < localEdge; z++) {
                int fieldZ = fieldStartZ + z;
                if (fieldZ < 0 || fieldZ >= GiFieldLayout.CELLS_PER_AXIS) continue;
                for (int y = 0; y < localEdge; y++) {
                    int fieldY = fieldStartY + y;
                    if (fieldY < 0 || fieldY >= GiFieldLayout.CELLS_PER_AXIS) continue;
                    for (int x = 0; x < localEdge; x++) {
                        int fieldX = fieldStartX + x;
                        if (fieldX < 0 || fieldX >= GiFieldLayout.CELLS_PER_AXIS) continue;
                        int source = GiSemanticSectionSnapshot.cascadeCellIndex(cascade, x, y, z);
                        int destination = cascade * GiFieldLayout.CELLS_PER_CASCADE
                                + GiFieldLayout.cellIndex(fieldX, fieldY, fieldZ, GiFieldLayout.CELLS_PER_AXIS);
                        copyCell(snapshot, source, destination, contentGeneration);
                    }
                }
            }
        }
    }

    private void copyCell(final GiSemanticSectionSnapshot source, final int sourceCell,
                          final int destinationCell, final long contentGeneration) {
        for (int channel = 0; channel < 3; channel++) {
            this.albedoRgb[destinationCell * 3 + channel] = source.albedoUnchecked(sourceCell * 3 + channel);
        }
        for (int channel = 0; channel < 4; channel++) {
            this.emission[destinationCell * 4 + channel] = source.emissionUnchecked(sourceCell * 4 + channel);
        }
        for (int face = 0; face < 6; face++) {
            this.faces[destinationCell * 6 + face] = source.faceWeightUnchecked(sourceCell * 6 + face);
        }
        this.occupancy[destinationCell] = source.occupancyUnchecked(sourceCell);
        this.medium[destinationCell] = source.mediumMaskUnchecked(sourceCell);
        this.validity[destinationCell] = source.validityUnchecked(sourceCell);
        this.provenance[destinationCell] = source.provenanceUnchecked(sourceCell);
        this.coverage[destinationCell] = source.knownCoverageUnchecked(sourceCell);
        this.materialIds[destinationCell] = source.dominantMaterialIdUnchecked(sourceCell);
        this.contentGenerations[destinationCell] = contentGeneration;
    }

    private void scrollCascade(final int cascade, final int deltaX, final int deltaY, final int deltaZ) {
        int edge = GiFieldLayout.CELLS_PER_AXIS;
        int x = deltaX < 0 ? edge - 1 : 0;
        int y = deltaY < 0 ? edge - 1 : 0;
        int z = deltaZ < 0 ? edge - 1 : 0;
        int xEnd = deltaX < 0 ? -1 : edge;
        int yEnd = deltaY < 0 ? -1 : edge;
        int zEnd = deltaZ < 0 ? -1 : edge;
        int xStep = deltaX < 0 ? -1 : 1;
        int yStep = deltaY < 0 ? -1 : 1;
        int zStep = deltaZ < 0 ? -1 : 1;
        int cascadeOffset = cascade * GiFieldLayout.CELLS_PER_CASCADE;
        for (int destinationZ = z; destinationZ != zEnd; destinationZ += zStep) {
            int sourceZ = destinationZ + deltaZ;
            for (int destinationY = y; destinationY != yEnd; destinationY += yStep) {
                int sourceY = destinationY + deltaY;
                for (int destinationX = x; destinationX != xEnd; destinationX += xStep) {
                    int sourceX = destinationX + deltaX;
                    int destination = cascadeOffset
                            + GiFieldLayout.cellIndex(destinationX, destinationY, destinationZ, edge);
                    if (sourceX < 0 || sourceX >= edge || sourceY < 0 || sourceY >= edge
                            || sourceZ < 0 || sourceZ >= edge) {
                        clearFieldCell(destination);
                    } else {
                        int source = cascadeOffset + GiFieldLayout.cellIndex(sourceX, sourceY, sourceZ, edge);
                        copyFieldCell(source, destination);
                    }
                }
            }
        }
    }

    private void copyFieldCell(final int source, final int destination) {
        if (source == destination) return;
        System.arraycopy(this.albedoRgb, source * 3, this.albedoRgb, destination * 3, 3);
        System.arraycopy(this.emission, source * 4, this.emission, destination * 4, 4);
        System.arraycopy(this.faces, source * 6, this.faces, destination * 6, 6);
        this.occupancy[destination] = this.occupancy[source];
        this.medium[destination] = this.medium[source];
        this.validity[destination] = this.validity[source];
        this.provenance[destination] = this.provenance[source];
        this.coverage[destination] = this.coverage[source];
        this.materialIds[destination] = this.materialIds[source];
        this.contentGenerations[destination] = this.contentGenerations[source];
    }

    private void clearCascade(final int cascade) {
        int start = cascade * GiFieldLayout.CELLS_PER_CASCADE;
        int end = start + GiFieldLayout.CELLS_PER_CASCADE;
        for (int cell = start; cell < end; cell++) {
            clearFieldCell(cell);
        }
    }

    private void clearFieldCell(final int cell) {
        Arrays.fill(this.albedoRgb, cell * 3, cell * 3 + 3, (short) 0);
        Arrays.fill(this.emission, cell * 4, cell * 4 + 4, (short) 0);
        Arrays.fill(this.faces, cell * 6, cell * 6 + 6, (byte) 0);
        this.occupancy[cell] = (byte) 0xff;
        this.medium[cell] = (byte) GiSemanticMedium.UNKNOWN_CONSERVATIVE.mask();
        this.validity[cell] = (byte) GiSemanticValidity.UNKNOWN.abiId();
        this.provenance[cell] = 0;
        this.coverage[cell] = 0;
        this.materialIds[cell] = (short) GiSemanticPalette.UNKNOWN_ID;
        this.contentGenerations[cell] = 0L;
    }

    private boolean intersectsAnyCascade(final long sectionKey) {
        long minX = (long) GiSemanticCoordinates.sectionX(sectionKey) * 16L;
        long minY = (long) GiSemanticCoordinates.sectionY(sectionKey) * 16L;
        long minZ = (long) GiSemanticCoordinates.sectionZ(sectionKey) * 16L;
        long maxX = minX + 16L;
        long maxY = minY + 16L;
        long maxZ = minZ + 16L;
        for (int cascade = 0; cascade < GiFieldLayout.CASCADE_COUNT; cascade++) {
            int index = cascade * 3;
            long span = GiFieldLayout.spanBlocks(cascade);
            if (maxX > this.origins[index] && minX < this.origins[index] + span
                    && maxY > this.origins[index + 1] && minY < this.origins[index + 1] + span
                    && maxZ > this.origins[index + 2] && minZ < this.origins[index + 2] + span) {
                return true;
            }
        }
        return false;
    }

    private void clearSection(final long sectionKey) {
        deposit(sectionKey, 0L, GiSemanticSectionSnapshot.unknown());
    }

    private void clearTruth(final boolean advanceContentGeneration) {
        Arrays.fill(this.albedoRgb, (short) 0);
        Arrays.fill(this.emission, (short) 0);
        Arrays.fill(this.occupancy, (byte) 0xff);
        Arrays.fill(this.medium, (byte) GiSemanticMedium.UNKNOWN_CONSERVATIVE.mask());
        Arrays.fill(this.validity, (byte) GiSemanticValidity.UNKNOWN.abiId());
        Arrays.fill(this.provenance, (byte) 0);
        Arrays.fill(this.faces, (byte) 0);
        Arrays.fill(this.coverage, (byte) 0);
        Arrays.fill(this.materialIds, (short) GiSemanticPalette.UNKNOWN_ID);
        Arrays.fill(this.contentGenerations, 0L);
        this.residentTags.clear();
        if (advanceContentGeneration) {
            this.contentGeneration = Math.incrementExact(this.contentGeneration);
        }
    }

    private void setOrigins(final int x, final int y, final int z) {
        int[] next = centeredOrigins(x, y, z);
        System.arraycopy(next, 0, this.origins, 0, next.length);
    }

    private static int[] centeredOrigins(final int x, final int y, final int z) {
        int[] output = new int[GiFieldLayout.CASCADE_COUNT * 3];
        for (int cascade = 0; cascade < GiFieldLayout.CASCADE_COUNT; cascade++) {
            output[cascade * 3] = GiFieldLayout.centeredOriginBlock(x, cascade);
            output[cascade * 3 + 1] = GiFieldLayout.centeredOriginBlock(y, cascade);
            output[cascade * 3 + 2] = GiFieldLayout.centeredOriginBlock(z, cascade);
        }
        return output;
    }

    private static GiSemanticWorldToken requireMatchingPalette(
            final GiSemanticWorldToken world,
            final GiSemanticPalette palette
    ) {
        if (world == null || palette == null || world.resourceEpoch() != palette.resourceEpoch()
                || world.materialEpoch() != palette.materialEpoch()) {
            throw new IllegalArgumentException("G2 palette epochs do not match the world token");
        }
        return world;
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("G2 field assembler is confined to its render thread");
        }
    }

    private record ResidentTag(long ownerToken, long contentGeneration) {
    }
}
