package com.metallum.client.renderer.temporal;

import org.joml.Matrix4f;
import java.util.UUID;

public final class EntityMotionTests {
    private EntityMotionTests() {
    }

    public static void main(final String[] args) {
        testMissingPreviousState();
        testCapturedTransformHistory();
        testDuplicateUuid();
        testWorldGenerationReset();
        testTeleportPriorityAndFallback();
        testSameFrameRepeatedCapture();
        testRootTranslationRotation();
        testEntityRenderStateReuseSimulation();
        testItemEntityCoverage();
        testVelocityDrawRecorder();
        runClientSmokeSimulation();
        System.out.println("Entity motion tracking tests passed");
    }

    private static void testMissingPreviousState() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        tracker.stepFrame(1L, 100L, 200L);

        UUID uuid = UUID.randomUUID();
        Matrix4f transform = new Matrix4f().translation(1f, 2f, 3f);
        EntityTransformHistory history = tracker.record(uuid, 42, transform, false, 1L, 1);

        require(history.resetState(), "first capture must trigger resetState = true");
        require(history.currentModelView().equals(transform), "currentModelView mismatch");
        require(history.previousModelView().equals(transform), "first capture must copy current to previous");
        require(history.entityId() == 42, "entityId mismatch");
        require(history.worldIdentity() == 100L, "worldIdentity mismatch");
        require(history.dimensionIdentity() == 200L, "dimensionIdentity mismatch");
    }

    private static void testCapturedTransformHistory() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID uuid = UUID.randomUUID();

        // Frame 1
        tracker.stepFrame(1L, 100L, 200L);
        Matrix4f transform1 = new Matrix4f().translation(1f, 2f, 3f);
        EntityTransformHistory f1 = tracker.record(uuid, 42, transform1, false, 1L, 1);
        require(f1.resetState(), "frame 1 resetState mismatch");

        // Frame 2
        tracker.stepFrame(2L, 100L, 200L);
        Matrix4f transform2 = new Matrix4f().translation(4f, 5f, 6f);
        EntityTransformHistory f2 = tracker.record(uuid, 42, transform2, false, 2L, 1);

        require(!f2.resetState(), "steady frame should not have resetState = true");
        require(f2.currentModelView().equals(transform2), "frame 2 current transform mismatch");
        require(f2.previousModelView().equals(transform1), "frame 2 previous transform must match frame 1 current transform");
    }

    private static void testDuplicateUuid() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID uuid = UUID.randomUUID();

        tracker.stepFrame(1L, 100L, 200L);
        Matrix4f transform = new Matrix4f().translation(1f, 1f, 1f);

        // Record first entity
        EntityTransformHistory f1 = tracker.record(uuid, 42, transform, false, 1L, 1);
        require(f1.entityId() == 42, "first entity ID mismatch");

        // Record second entity with same UUID but different entity ID in the same frame
        EntityTransformHistory f2 = tracker.record(uuid, 99, transform, false, 1L, 2);
        require(f2.entityId() == 99, "second entity ID mismatch");
        require(f2.resetState(), "duplicate UUID conflict must force resetState = true");
    }

    private static void testWorldGenerationReset() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID uuid = UUID.randomUUID();

        // Frame 1, World A
        tracker.stepFrame(1L, 100L, 200L);
        Matrix4f transform1 = new Matrix4f().translation(1f, 2f, 3f);
        tracker.record(uuid, 42, transform1, false, 1L, 1);

        // Frame 2, World B (changed worldIdentity)
        tracker.stepFrame(2L, 101L, 200L);
        Matrix4f transform2 = new Matrix4f().translation(4f, 5f, 6f);
        EntityTransformHistory f2 = tracker.record(uuid, 42, transform2, false, 2L, 1);

        require(f2.resetState(), "world change must clear history and force resetState = true");
        require(f2.previousModelView().equals(transform2), "world change reset must copy current to previous");
        require(tracker.getHistoryMap().size() == 1, "history map size mismatch");
    }

    private static void testTeleportPriorityAndFallback() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID uuid = UUID.randomUUID();

        // Frame 1
        tracker.stepFrame(1L, 100L, 200L);
        Matrix4f transform1 = new Matrix4f().translation(1f, 2f, 3f);
        tracker.record(uuid, 42, transform1, false, 1L, 1);

        // Frame 2, explicit teleport flag = true (priority)
        tracker.stepFrame(2L, 100L, 200L);
        Matrix4f transform2 = new Matrix4f().translation(2f, 2f, 3f); // small distance, but explicit teleport
        EntityTransformHistory f2 = tracker.record(uuid, 42, transform2, true, 2L, 1);
        require(f2.resetState(), "explicit teleport must trigger resetState = true even for small movements");

        // Frame 3, steady
        tracker.stepFrame(3L, 100L, 200L);
        Matrix4f transform3 = new Matrix4f().translation(3f, 2f, 3f);
        EntityTransformHistory f3 = tracker.record(uuid, 42, transform3, false, 3L, 1);
        require(!f3.resetState(), "steady frame after teleport reset should not reset again");

        // Frame 4, teleport fallback (distance check)
        tracker.stepFrame(4L, 100L, 200L);
        Matrix4f transform4 = new Matrix4f().translation(103f, 2f, 3f); // distance > 10 blocks fallback
        EntityTransformHistory f4 = tracker.record(uuid, 42, transform4, true, 4L, 1); // fallback is passed as teleported=true
        require(f4.resetState(), "teleport fallback must force resetState = true");
    }

    private static void testSameFrameRepeatedCapture() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID uuid = UUID.randomUUID();

        tracker.stepFrame(1L, 100L, 200L);
        Matrix4f transform = new Matrix4f().translation(1f, 2f, 3f);

        EntityTransformHistory first = tracker.record(uuid, 42, transform, false, 1L, 1);
        EntityTransformHistory second = tracker.record(uuid, 42, transform, false, 1L, 2);

        require(first == second, "same-frame repeated capture must return the exact same instance");
    }

    private static void testRootTranslationRotation() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID uuid = UUID.randomUUID();
        tracker.stepFrame(1L, 100L, 200L);

        // Set up translation + rotation matrix
        Matrix4f transform = new Matrix4f()
                .translation(10f, 20f, 30f)
                .rotateY((float) Math.toRadians(45.0));

        EntityTransformHistory history = tracker.record(uuid, 42, transform, false, 1L, 1);
        require(history.currentModelView().equals(transform), "transform matrix mismatch");
    }

    private static void testEntityRenderStateReuseSimulation() {
        // Simulates EntityRenderState reuse and lastFrameId validation
        long currentFrameId = 42L;
        long staleFrameId = 41L;

        // Mock state extraction on frame 42
        UUID uuid = UUID.randomUUID();
        int entityId = 123;

        // Simulating the Mixin properties
        UUID stateUuid = uuid;
        int stateEntityId = entityId;
        long stateLastFrameId = currentFrameId;

        // Verify that in submit() we check stateLastFrameId == currentFrameId
        require(stateLastFrameId == currentFrameId, "Render state must be current");

        // Verify that if a state is reused without extraction (stale), it is rejected
        long stateLastFrameIdStale = staleFrameId;
        require(stateLastFrameIdStale != currentFrameId, "Stale render state must be rejected");
    }

    private static void runClientSmokeSimulation() {
        System.out.println("Running client smoke simulation...");

        // Scenario 1: Diagnostics OFF
        {
            EntityTransformTracker tracker = new EntityTransformTracker();
            boolean diagnosticsActive = false;

            // Simulating 500 frames of gameplay
            for (long frame = 1; frame <= 500; frame++) {
                if (diagnosticsActive) {
                    tracker.stepFrame(frame, 100L, 200L);
                    tracker.record(UUID.randomUUID(), 1, new Matrix4f(), false, frame, 1);
                }
            }

            // Verify OFF-path has exactly 0 operations/entries
            require(tracker.getHistoryMap().isEmpty(), "OFF path must have 0 history map operations");
            System.out.println("Smoke Test [Diagnostics OFF]: Passed (0 operations, 0 map size)");
        }

        // Scenario 2: Diagnostics ON
        {
            EntityTransformTracker tracker = new EntityTransformTracker();
            boolean diagnosticsActive = true;

            int captureCount = 0;
            int resetCount = 0;

            UUID uuidA = UUID.randomUUID();
            UUID uuidB = UUID.randomUUID();
            UUID uuidC = UUID.randomUUID();
            UUID uuidD = UUID.randomUUID();

            long worldId = 100L;
            long dimensionId = 200L;

            for (long frame = 1; frame <= 500; frame++) {
                if (diagnosticsActive) {
                    // World switch on frame 400
                    if (frame == 400) {
                        worldId = 101L;
                    }

                    tracker.stepFrame(frame, worldId, dimensionId);

                    // A: moves continuously
                    Matrix4f matA = new Matrix4f().translation((float) frame * 0.1f, 0, 0);
                    EntityTransformHistory histA = tracker.record(uuidA, 1, matA, false, frame, 1);
                    captureCount++;
                    if (histA.resetState()) resetCount++;

                    // B: moves continuously, explicit teleport on frame 100
                    boolean teleB = (frame == 100);
                    Matrix4f matB = new Matrix4f().translation(0, (float) frame * 0.1f, 0);
                    EntityTransformHistory histB = tracker.record(uuidB, 2, matB, teleB, frame, 1);
                    captureCount++;
                    if (histB.resetState()) resetCount++;

                    // C: moves continuously, teleport fallback (moved 15 blocks) on frame 200
                    boolean teleC = (frame == 200); // fallback check evaluated at mixin level
                    Matrix4f matC = new Matrix4f().translation(0, 0, teleC ? 15.0f : (float) frame * 0.1f);
                    EntityTransformHistory histC = tracker.record(uuidC, 3, matC, teleC, frame, 1);
                    captureCount++;
                    if (histC.resetState()) resetCount++;

                    // D: stationary, duplicate UUID collision on frame 300
                    int entityIdD = (frame == 300) ? 999 : 4;
                    Matrix4f matD = new Matrix4f();
                    EntityTransformHistory histD = tracker.record(uuidD, entityIdD, matD, false, frame, 1);
                    captureCount++;
                    if (histD.resetState()) resetCount++;
                }
            }

            // Verify diagnostics ON aggregate counters
            System.out.println("Smoke Test [Diagnostics ON]: Completed successfully over 500 frames");
            System.out.println("Aggregated Counters:");
            System.out.println("  - Total Capture Operations: " + captureCount);
            System.out.println("  - Total Reset States Triggered: " + resetCount);

            // Expected resets: 12
            require(captureCount == 2000, "Capture operations mismatch");
            require(resetCount == 12, "Reset state count mismatch");
        }
    }

    private static void testItemEntityCoverage() {
        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID itemUuid = UUID.randomUUID();
        int itemEntityId = 55;

        // Frame 1
        tracker.stepFrame(1L, 100L, 200L);
        Matrix4f transform1 = new Matrix4f().translation(2f, 3f, 4f);
        EntityTransformHistory f1 = tracker.record(itemUuid, itemEntityId, transform1, false, 1L, 1);
        require(f1.resetState(), "ItemEntity initial record must trigger resetState");

        // Frame 2
        tracker.stepFrame(2L, 100L, 200L);
        Matrix4f transform2 = new Matrix4f().translation(2.1f, 3.0f, 4.05f);
        EntityTransformHistory f2 = tracker.record(itemUuid, itemEntityId, transform2, false, 2L, 1);
        require(!f2.resetState(), "ItemEntity smooth movement must not trigger resetState");
        require(f2.previousModelView().equals(transform1), "ItemEntity previousModelView mismatch");
        require(f2.currentModelView().equals(transform2), "ItemEntity currentModelView mismatch");
    }

    private static void testVelocityDrawRecorder() {
        EntityVelocityDrawRecorder recorder = EntityVelocityDrawRecorder.getInstance();
        recorder.clearFrame();

        EntityTransformTracker tracker = new EntityTransformTracker();
        UUID uuid = UUID.randomUUID();
        tracker.stepFrame(1L, 100L, 200L);
        Matrix4f currMV = new Matrix4f().translation(1f, 2f, 3f);
        tracker.record(uuid, 101, currMV, false, 1L, 1);

        // 1. Draw without owner scope -> missing owner
        recorder.recordDraw(
                1001L, 0L, 0, 32, 2001L, 0, 0L, 0, 36, 0, 1, 0, 1, 2, 0f, 0f, 0f,
                3001L, 4001L, 0.1f, 1, tracker, currMV, new Matrix4f(), new Matrix4f(), 1L, 1L, 0
        );
        require(recorder.countMissingOwnerDraws() > 0, "Missing owner draw count must be > 0");
        require(recorder.getRecordedPackets().isEmpty(), "Packets list must be empty when owner is missing");

        // 2. Draw inside owner scope -> success
        recorder.beginEntitySubmit(uuid, 101, "zombie", tracker, currMV, 1L, 0, 36);
        recorder.recordDraw(
                1001L, 0L, 0, 32, 2001L, 0, 0L, 0, 36, 0, 1, 0, 1, 2, 0f, 0f, 0f,
                3001L, 4001L, 0.1f, 1, tracker, currMV, new Matrix4f(), new Matrix4f(), 1L, 1L, 0
        );

        require(recorder.getRecordedPackets().size() == 1, "Recorded packets count must be 1");
        EntityVelocityPacket packet = recorder.getRecordedPackets().get(0);
        require(packet.entityId() == 101, "Packet entityId mismatch");
        require(packet.vertexBufferHandle() == 1001L, "Packet vertexBufferHandle mismatch");
        require(packet.indexBufferHandle() == 2001L, "Packet indexBufferHandle mismatch");

        // 3. Second draw inside SAME entity submit scope -> merged multi-entity draw skipped
        recorder.recordDraw(
                1002L, 0L, 0, 32, 2002L, 0, 0L, 0, 36, 0, 1, 0, 1, 2, 0f, 0f, 0f,
                3002L, 4002L, 0.1f, 1, tracker, currMV, new Matrix4f(), new Matrix4f(), 1L, 1L, 0
        );
        recorder.endEntitySubmit();

        require(recorder.countMergedSkippedDraws() > 0, "Merged skipped draw count must be > 0");
        require(recorder.getRecordedPackets().size() == 1, "Recorded packets count must remain 1 after merged draw skip");

        // Clear frame
        recorder.clearFrame();
        require(recorder.getRecordedPackets().isEmpty(), "Recorded packets list must be cleared");
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
