package com.metallum.mixin.render;

import com.metallum.client.renderer.temporal.EntityRenderStateAccessor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import java.util.UUID;

@Mixin(EntityRenderState.class)
public class EntityRenderStateMixin implements EntityRenderStateAccessor {
    @Unique
    private UUID metallum$uuid;
    @Unique
    private int metallum$entityId;
    @Unique
    private boolean metallum$teleported;
    @Unique
    private long metallum$lastFrameId = -1L;
    @Unique
    private int metallum$submitCount = 0;

    @Override
    public UUID metallum$getUuid() {
        return this.metallum$uuid;
    }

    @Override
    public void metallum$setUuid(UUID uuid) {
        this.metallum$uuid = uuid;
    }

    @Override
    public int metallum$getEntityId() {
        return this.metallum$entityId;
    }

    @Override
    public void metallum$setEntityId(int entityId) {
        this.metallum$entityId = entityId;
    }

    @Override
    public boolean metallum$isTeleported() {
        return this.metallum$teleported;
    }

    @Override
    public void metallum$setTeleported(boolean teleported) {
        this.metallum$teleported = teleported;
    }

    @Override
    public long metallum$getLastFrameId() {
        return this.metallum$lastFrameId;
    }

    @Override
    public void metallum$setLastFrameId(long lastFrameId) {
        this.metallum$lastFrameId = lastFrameId;
    }

    @Override
    public int metallum$getSubmitCount() {
        return this.metallum$submitCount;
    }

    @Override
    public void metallum$setSubmitCount(int submitCount) {
        this.metallum$submitCount = submitCount;
    }
}
