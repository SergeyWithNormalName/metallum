package com.metallum.client.renderer.temporal;

import java.util.UUID;

public interface EntityRenderStateAccessor {
    UUID metallum$getUuid();
    void metallum$setUuid(UUID uuid);

    int metallum$getEntityId();
    void metallum$setEntityId(int entityId);

    boolean metallum$isTeleported();
    void metallum$setTeleported(boolean teleported);

    long metallum$getLastFrameId();
    void metallum$setLastFrameId(long lastFrameId);

    int metallum$getSubmitCount();
    void metallum$setSubmitCount(int submitCount);
}
