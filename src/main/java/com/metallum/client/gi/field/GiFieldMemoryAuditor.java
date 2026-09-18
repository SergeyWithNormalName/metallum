package com.metallum.client.gi.field;

/** Compares arithmetic payload sizing with the allocation reported by the Metal device. */
public final class GiFieldMemoryAuditor {
    public record Report(long arithmeticBytes, long actualAllocatedBytes, long budgetBytes) {
        public boolean withinBudget() {
            return this.actualAllocatedBytes >= this.arithmeticBytes
                    && this.actualAllocatedBytes <= this.budgetBytes;
        }
    }

    private GiFieldMemoryAuditor() {
    }

    public static Report audit(final long actualAllocatedBytes) {
        if (actualAllocatedBytes < 0L) {
            throw new IllegalArgumentException("Actual G1 allocation cannot be negative");
        }
        return new Report(
                GiFieldLayout.arithmeticPersistentBytes(),
                actualAllocatedBytes,
                GiFieldLayout.DIFFUSE_GI_BUDGET_BYTES
        );
    }
}
