package com.metallum.client.gi.field;

import java.util.concurrent.atomic.AtomicReference;

/** Pure-Java topology, memory, generation, and bounded-accounting checks for field-only G1. */
public final class GiFieldTests {
    private GiFieldTests() {
    }

    public static void main(final String[] args) throws InterruptedException {
        validateTopology();
        validateCandidateBudget();
        validateGenerationDiscipline();
        validateSnapshotOwnership();
        validateNoProductionBindingApi();
        System.out.println("G1 field infrastructure unit tests passed");
    }

    private static void validateTopology() {
        require(GiFieldLayout.CASCADE_COUNT == 3, "G1 cascade count changed");
        require(GiFieldLayout.CELLS_PER_AXIS == 32, "G1 field edge changed");
        require(GiFieldLayout.cellsIncludingMipsPerCascade() == 37_449,
                "G1 complete mip-cell count changed");
        require(GiFieldLayout.arithmeticPersistentBytes() == 1_011_123L,
                "G1 arithmetic field footprint changed");
        int[] expectedCellSizes = {2, 4, 8};
        int[] expectedSpans = {64, 128, 256};
        for (int cascade = 0; cascade < GiFieldLayout.CASCADE_COUNT; cascade++) {
            require(GiFieldLayout.cellSizeBlocks(cascade) == expectedCellSizes[cascade],
                    "G1 cascade spacing changed");
            require(GiFieldLayout.spanBlocks(cascade) == expectedSpans[cascade],
                    "G1 cascade span changed");
            int origin = GiFieldLayout.centeredOriginBlock(-1, cascade);
            require(Math.floorMod(origin, expectedCellSizes[cascade]) == 0,
                    "negative G1 origin is not world snapped");
            require(GiFieldLayout.contains(origin, origin, origin, origin, origin, origin, cascade),
                    "G1 lower bound is not covered");
            require(!GiFieldLayout.contains(origin + expectedSpans[cascade], origin, origin,
                            origin, origin, origin, cascade),
                    "G1 upper bound must be exclusive");
        }
        GiFieldMemoryAuditor.Report arithmetic = GiFieldMemoryAuditor.audit(
                GiFieldLayout.arithmeticPersistentBytes()
        );
        require(arithmetic.withinBudget(), "G1 arithmetic memory exceeds its diffuse budget");
    }

    private static void validateCandidateBudget() {
        GiFieldCandidateBudget budget = new GiFieldCandidateBudget(2, 96L);
        GiFieldCandidateBudget.Lease first = budget.tryAcquire(48L);
        GiFieldCandidateBudget.Lease second = budget.tryAcquire(48L);
        require(first != null && second != null, "G1 bounded candidates were rejected too early");
        require(budget.tryAcquire(1L) == null, "G1 candidate-count overflow was accepted");
        first.close();
        GiFieldCandidateBudget.Lease replacement = budget.tryAcquire(32L);
        require(replacement != null, "G1 released candidate capacity was not reusable");
        second.close();
        replacement.close();
        for (int index = 0; index < 10_000; index++) {
            try (GiFieldCandidateBudget.Lease lease = budget.tryAcquire(64L)) {
                require(lease != null, "G1 steady candidate publication lost bounded capacity");
            }
        }
        GiFieldCandidateBudget.Snapshot snapshot = budget.snapshot();
        require(snapshot.activeCandidates() == 0 && snapshot.activeBytes() == 0L,
                "G1 candidate budget retained steady-state payloads");
        require(snapshot.peakCandidates() == 2 && snapshot.peakBytes() == 96L,
                "G1 candidate high-water accounting changed");
    }

    private static void validateGenerationDiscipline() throws InterruptedException {
        GiFieldGenerationState state = new GiFieldGenerationState(41L, "minecraft:overworld");
        require(state.admitCandidate(41L, "minecraft:overworld"), "current G1 candidate was rejected");
        require(!state.admitCandidate(40L, "minecraft:overworld"), "stale G1 candidate was admitted");
        long generation = state.reset("minecraft:the_nether", GiFieldGenerationState.ResetReason.DIMENSION_CHANGE);
        require(generation == 42L, "G1 reset did not advance generation");
        require(!state.admitCandidate(41L, "minecraft:overworld"),
                "pre-reset G1 candidate survived a dimension reset");
        require(state.admitCandidate(42L, "minecraft:the_nether"),
                "post-reset G1 candidate was rejected");

        AtomicReference<Throwable> wrongThread = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                state.snapshot();
            } catch (Throwable throwable) {
                wrongThread.set(throwable);
            }
        }, "G1-invalid-worker");
        worker.start();
        worker.join();
        require(wrongThread.get() instanceof IllegalStateException,
                "G1 render-thread confinement was not enforced");
    }

    private static void validateSnapshotOwnership() {
        int cells = GiFieldLayout.CASCADE_COUNT * GiFieldLayout.CELLS_PER_CASCADE;
        int[] origins = new int[GiFieldLayout.CASCADE_COUNT * 3];
        short[] field = new short[cells * GiFieldLayout.FIELD_CHANNELS];
        byte[] coverage = new byte[cells];
        field[0] = Float.floatToFloat16(0.5F);
        coverage[0] = (byte) 0xff;
        GiFieldSnapshot snapshot = new GiFieldSnapshot(7L, origins, field, coverage);
        field[0] = 0;
        coverage[0] = 0;
        origins[0] = 99;
        require(snapshot.packedField()[0] == Float.floatToFloat16(0.5F)
                        && snapshot.coverage()[0] == (byte) 0xff
                        && snapshot.origins()[0] == 0,
                "G1 snapshot does not own immutable candidate arrays");
    }

    private static void validateNoProductionBindingApi() {
        for (var method : GiFieldGpuResources.class.getDeclaredMethods()) {
            String name = method.getName().toLowerCase(java.util.Locale.ROOT);
            require(!name.contains("bind") && !name.contains("texturehandle"),
                    "G1 field-only wrapper exposed a production receiver path: " + method.getName());
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
