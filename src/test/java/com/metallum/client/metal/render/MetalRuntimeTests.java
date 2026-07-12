package com.metallum.client.metal.render;

public final class MetalRuntimeTests {
    private MetalRuntimeTests() {
    }

    public static void main(final String[] args) {
        testDestructionQueueDefersReentrantAdds();
        testDestructionQueueToleratesReentrantRotation();
        testDestructionQueueClose();
        testFenceTimeoutRounding();
    }

    private static void testDestructionQueueDefersReentrantAdds() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[2];
        queue.add(() -> {
            executions[0]++;
            queue.add(() -> executions[1]++);
        });

        queue.rotate();
        queue.rotate();
        require(executions[0] == 0, "destroy action ran before three rotations");
        queue.rotate();
        require(executions[0] == 1, "destroy action did not run after three rotations");
        require(executions[1] == 0, "reentrant destroy action ran in the same rotation");

        queue.rotate();
        queue.rotate();
        require(executions[1] == 0, "reentrant destroy action ran before its own delay");
        queue.rotate();
        require(executions[1] == 1, "reentrant destroy action did not preserve the queue delay");
        queue.close();
        require(executions[0] == 1 && executions[1] == 1, "destroy actions ran more than once");
    }

    private static void testDestructionQueueToleratesReentrantRotation() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[2];
        queue.add(() -> {
            executions[0]++;
            queue.rotate();
            queue.add(() -> executions[1]++);
        });

        queue.rotate();
        queue.rotate();
        queue.rotate();
        require(executions[0] == 1 && executions[1] == 0, "reentrant rotation callback mismatch");
        queue.rotate();
        queue.rotate();
        require(executions[1] == 0, "reentrant rotation aliased two queue slots");
        queue.rotate();
        require(executions[1] == 1, "reentrant rotation lost the deferred action");
        queue.close();
    }

    private static void testDestructionQueueClose() {
        MetalDestructionQueue queue = new MetalDestructionQueue(3);
        int[] executions = new int[1];
        queue.add(() -> executions[0]++);
        queue.add(null);
        queue.close();
        require(executions[0] == 1, "close did not drain queued destruction exactly once");
    }

    private static void testFenceTimeoutRounding() {
        require(MetalFence.timeoutMillis(-1L) == 0L, "negative timeout must remain non-blocking");
        require(MetalFence.timeoutMillis(0L) == 0L, "zero timeout must remain non-blocking");
        require(MetalFence.timeoutMillis(1L) == 1L, "positive sub-millisecond timeout rounded down");
        require(MetalFence.timeoutMillis(999_999L) == 1L, "sub-millisecond timeout rounded incorrectly");
        require(MetalFence.timeoutMillis(1_000_000L) == 1L, "whole millisecond changed");
        require(MetalFence.timeoutMillis(1_000_001L) == 2L, "fractional millisecond did not round up");
        require(
                MetalFence.timeoutMillis(Long.MAX_VALUE) == 9_223_372_036_855L,
                "maximum timeout overflowed"
        );
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
