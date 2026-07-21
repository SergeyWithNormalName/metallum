package com.metallum.client.renderer.temporal;

import org.joml.Matrix4f;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class EntityVelocityDrawRecorder {
    private static final EntityVelocityDrawRecorder INSTANCE = new EntityVelocityDrawRecorder();

    public static EntityVelocityDrawRecorder getInstance() {
        return INSTANCE;
    }

    private final List<EntityVelocityPacket> recordedPackets = new ArrayList<>();

    private UUID activeEntityUuid = null;
    private int activeEntityId = -1;
    private String activeEntityType = "unknown";
    private boolean activeEntityHasRecordedDraw;

    private long countEligibleDraws = 0;
    private long countCapturedDraws = 0;
    private long countReplayedDraws = 0;
    private long countOpaquePackets = 0;
    private long countCutoutPackets = 0;
    private long countCutoutNoCullPackets = 0;
    private long countMergedSkippedDraws = 0;
    private long countUnsupportedLayoutDraws = 0;
    private long countMissingOwnerDraws = 0;
    private long countStaleGenerationDraws = 0;
    private long countInvalidMatrixDraws = 0;
    private long countInvalidBufferDraws = 0;
    private long countEntityResetPackets = 0;

    private EntityVelocityDrawRecorder() {}

    public synchronized void beginEntitySubmit(
            UUID uuid,
            int entityId,
            String entityType,
            EntityTransformTracker tracker,
            Matrix4f currentModelView,
            long frameId,
            int useAlphaTest,
            int indexCount
    ) {
        if (uuid == null || entityId < 0) {
            countMissingOwnerDraws++;
            return;
        }

        this.activeEntityUuid = uuid;
        this.activeEntityId = entityId;
        this.activeEntityType = entityType != null ? entityType : "unknown";
        this.activeEntityHasRecordedDraw = false;
    }

    /**
     * Records one unmerged Metal draw belonging to the active entity submit scope.
     *
     * <p>Entity submission itself does not expose Metal buffers. The draw hook must supply the
     * real live handles here; fabricating placeholders would make the native replay dereference
     * arbitrary memory. Until such a hook is installed, the scope remains intentionally inert.</p>
     */
    public synchronized void recordDraw(
            final long vertexBufferHandle,
            final long vertexBufferOffset,
            final int vertexLayoutId,
            final int vertexStride,
            final long indexBufferHandle,
            final int indexType,
            final long indexByteOffset,
            final int primitiveType,
            final int indexCount,
            final int baseVertex,
            final int instanceCount,
            final int baseInstance,
            final int winding,
            final int cullMode,
            final float depthBias,
            final float slopeScale,
            final float clamp,
            final long textureHandle,
            final long samplerHandle,
            final float alphaCutoff,
            final int useAlphaTest,
            final EntityTransformTracker tracker,
            final Matrix4f currentModelView,
            final Matrix4f currentUnjitteredProjection,
            final Matrix4f previousUnjitteredProjection,
            final long frameId,
            final long rendererGeneration,
            final int inFlightSlot
    ) {
        if (this.activeEntityUuid == null || this.activeEntityId < 0) {
            this.countMissingOwnerDraws++;
            return;
        }
        if (this.activeEntityHasRecordedDraw) {
            this.countMergedSkippedDraws++;
            return;
        }
        if (vertexBufferHandle == 0L || indexBufferHandle == 0L || indexCount <= 0 || vertexStride <= 0) {
            this.countInvalidBufferDraws++;
            return;
        }

        this.countEligibleDraws++;
        EntityTransformHistory history = tracker.getHistoryMap().get(this.activeEntityUuid);
        boolean isDiscontinuous = history == null || history.resetState();
        Matrix4f previousModelView = history == null ? currentModelView : history.previousModelView();
        if (currentModelView == null || previousModelView == null
                || !currentModelView.isFinite() || !previousModelView.isFinite()) {
            this.countInvalidMatrixDraws++;
            isDiscontinuous = true;
        }
        Matrix4f previousFromCurrentView = isDiscontinuous
                ? new Matrix4f()
                : new Matrix4f(previousModelView).mul(new Matrix4f(currentModelView).invert());
        if (!previousFromCurrentView.isFinite()) {
            this.countInvalidMatrixDraws++;
            isDiscontinuous = true;
            previousFromCurrentView.identity();
        }
        if (isDiscontinuous) {
            this.countEntityResetPackets++;
        }
        if (useAlphaTest != 0) {
            this.countCutoutPackets++;
        } else {
            this.countOpaquePackets++;
        }

        this.recordedPackets.add(new EntityVelocityPacket(
                vertexBufferHandle, vertexBufferOffset, vertexLayoutId, vertexStride,
                indexBufferHandle, indexType, indexByteOffset, primitiveType, indexCount,
                baseVertex, instanceCount, baseInstance, winding, cullMode,
                depthBias, slopeScale, clamp, textureHandle, samplerHandle, alphaCutoff, useAlphaTest,
                this.activeEntityUuid, this.activeEntityId, isDiscontinuous, previousFromCurrentView,
                new Matrix4f(currentUnjitteredProjection), new Matrix4f(previousUnjitteredProjection),
                frameId, rendererGeneration, inFlightSlot
        ));
        this.activeEntityHasRecordedDraw = true;
        this.countCapturedDraws++;
    }

    public synchronized void endEntitySubmit() {
        this.activeEntityUuid = null;
        this.activeEntityId = -1;
        this.activeEntityType = "unknown";
        this.activeEntityHasRecordedDraw = false;
    }

    public synchronized void clearFrame() {
        this.recordedPackets.clear();
        this.activeEntityUuid = null;
        this.activeEntityId = -1;
        this.activeEntityType = "unknown";
        this.activeEntityHasRecordedDraw = false;
    }

    public synchronized List<EntityVelocityPacket> getRecordedPackets() {
        return new ArrayList<>(this.recordedPackets);
    }

    public synchronized long countEligibleDraws() { return countEligibleDraws; }
    public synchronized long countCapturedDraws() { return countCapturedDraws; }
    public synchronized long countReplayedDraws() { return countReplayedDraws; }
    public synchronized long countOpaquePackets() { return countOpaquePackets; }
    public synchronized long countCutoutPackets() { return countCutoutPackets; }
    public synchronized long countCutoutNoCullPackets() { return countCutoutNoCullPackets; }
    public synchronized long countMergedSkippedDraws() { return countMergedSkippedDraws; }
    public synchronized long countUnsupportedLayoutDraws() { return countUnsupportedLayoutDraws; }
    public synchronized long countMissingOwnerDraws() { return countMissingOwnerDraws; }
    public synchronized long countStaleGenerationDraws() { return countStaleGenerationDraws; }
    public synchronized long countInvalidMatrixDraws() { return countInvalidMatrixDraws; }
    public synchronized long countInvalidBufferDraws() { return countInvalidBufferDraws; }
    public synchronized long countEntityResetPackets() { return countEntityResetPackets; }
}
