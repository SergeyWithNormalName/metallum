package com.metallum.client.renderer.temporal;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class EntityTransformTracker {
    private final Map<UUID, EntityTransformHistory> historyMap = new HashMap<>();
    private long currentFrameId = -1L;
    private long worldIdentity = -1L;
    private long dimensionIdentity = -1L;

    // Telemetry Counters:
    private long countExtractCalls = 0;
    private long countSubmitCalls = 0;
    private long countAcceptedCaptures = 0;
    private long countStaleFrameRejections = 0;
    private long countRepeatedSubmits = 0;
    private long countMissingIdentity = 0;
    private long countExplicitTeleports = 0;
    private long countDistanceFallbacks = 0;
    private long countDuplicateUuidResets = 0;

    public synchronized void stepFrame(long frameId, long worldId, long dimensionId) {
        this.currentFrameId = frameId;
        if (this.worldIdentity != worldId || this.dimensionIdentity != dimensionId) {
            this.historyMap.clear();
            this.worldIdentity = worldId;
            this.dimensionIdentity = dimensionId;
        } else {
            long previousFrameId = frameId - 1;
            this.historyMap.values().removeIf(history -> history.lastFrameId() < previousFrameId);
        }
    }

    public synchronized EntityTransformHistory record(
            UUID uuid,
            int entityId,
            Matrix4fc currentModelView,
            boolean teleported,
            long extractFrameId,
            int submitCount
    ) {
        if (currentFrameId < 0L) {
            countAcceptedCaptures++;
            return new EntityTransformHistory(
                    uuid,
                    entityId,
                    worldIdentity,
                    dimensionIdentity,
                    new Matrix4f(currentModelView),
                    new Matrix4f(currentModelView),
                    true,
                    currentFrameId
            );
        }

        EntityTransformHistory existing = this.historyMap.get(uuid);

        if (existing != null && existing.lastFrameId() == currentFrameId) {
            if (existing.entityId() == entityId) {
                countRepeatedSubmits++;
                return existing;
            }
            countDuplicateUuidResets++;
            EntityTransformHistory duplicateConflict = new EntityTransformHistory(
                    uuid,
                    entityId,
                    worldIdentity,
                    dimensionIdentity,
                    new Matrix4f(currentModelView),
                    new Matrix4f(currentModelView),
                    true,
                    currentFrameId
            );
            this.historyMap.put(uuid, duplicateConflict);
            countAcceptedCaptures++;
            return duplicateConflict;
        }

        if (existing != null) {
            if (existing.entityId() != entityId
                    || existing.worldIdentity() != worldIdentity
                    || existing.dimensionIdentity() != dimensionIdentity
                    || teleported) {
                EntityTransformHistory resetHistory = new EntityTransformHistory(
                        uuid,
                        entityId,
                        worldIdentity,
                        dimensionIdentity,
                        new Matrix4f(currentModelView),
                        new Matrix4f(currentModelView),
                        true,
                        currentFrameId
                );
                this.historyMap.put(uuid, resetHistory);
                countAcceptedCaptures++;
                return resetHistory;
            }

            EntityTransformHistory updated = new EntityTransformHistory(
                    uuid,
                    entityId,
                    worldIdentity,
                    dimensionIdentity,
                    new Matrix4f(currentModelView),
                    new Matrix4f(existing.currentModelView()),
                    false,
                    currentFrameId
            );
            this.historyMap.put(uuid, updated);
            countAcceptedCaptures++;
            return updated;
        }

        EntityTransformHistory newHistory = new EntityTransformHistory(
                uuid,
                entityId,
                worldIdentity,
                dimensionIdentity,
                new Matrix4f(currentModelView),
                new Matrix4f(currentModelView),
                true,
                currentFrameId
        );
        this.historyMap.put(uuid, newHistory);
        countAcceptedCaptures++;
        return newHistory;
    }

    public synchronized void incrementExtractCalls() { countExtractCalls++; }
    public synchronized void incrementSubmitCalls() { countSubmitCalls++; }
    public synchronized void incrementStaleFrameRejections() { countStaleFrameRejections++; }
    public synchronized void incrementMissingIdentity() { countMissingIdentity++; }
    public synchronized void incrementExplicitTeleports() { countExplicitTeleports++; }
    public synchronized void incrementDistanceFallbacks() { countDistanceFallbacks++; }

    public synchronized void clear() {
        this.historyMap.clear();
        this.currentFrameId = -1L;
        this.worldIdentity = -1L;
        this.dimensionIdentity = -1L;
    }

    public synchronized Map<UUID, EntityTransformHistory> getHistoryMap() {
        return new HashMap<>(this.historyMap);
    }
}
