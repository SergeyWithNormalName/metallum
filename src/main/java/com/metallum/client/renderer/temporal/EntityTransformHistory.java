package com.metallum.client.renderer.temporal;

import org.joml.Matrix4f;
import java.util.UUID;

/**
 * Immutable history record for tracking a single entity's render model-view transforms.
 */
public record EntityTransformHistory(
        UUID uuid,
        int entityId,
        long worldIdentity,
        long dimensionIdentity,
        Matrix4f currentModelView,
        Matrix4f previousModelView,
        boolean resetState,
        long lastFrameId
) {
    public EntityTransformHistory {
        java.util.Objects.requireNonNull(uuid, "uuid");
        java.util.Objects.requireNonNull(currentModelView, "currentModelView");
        java.util.Objects.requireNonNull(previousModelView, "previousModelView");
    }
}
