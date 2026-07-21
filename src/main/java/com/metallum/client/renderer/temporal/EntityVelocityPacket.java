package com.metallum.client.renderer.temporal;

import org.joml.Matrix4f;
import java.util.UUID;

/** Single-entity velocity draw packet for native Metal replay pass. */
public final class EntityVelocityPacket {
    private final long vertexBufferHandle;
    private final long vertexBufferOffset;
    private final int vertexLayoutId;
    private final int vertexStride;
    private final long indexBufferHandle;
    private final int indexType;
    private final long indexByteOffset;
    private final int primitiveType;
    private final int indexCount;
    private final int baseVertex;
    private final int instanceCount;
    private final int baseInstance;
    private final int winding;
    private final int cullMode;
    private final float depthBias;
    private final float slopeScale;
    private final float clamp;
    private final long textureHandle;
    private final long samplerHandle;
    private final float alphaCutoff;
    private final int useAlphaTest;
    private final UUID uuid;
    private final int entityId;
    private final boolean isDiscontinuous;
    private final Matrix4f previousFromCurrentView;
    private final Matrix4f currentUnjitteredProjection;
    private final Matrix4f previousUnjitteredProjection;
    private final long frameId;
    private final long rendererGeneration;
    private final int inFlightSlot;

    public EntityVelocityPacket(
            long vertexBufferHandle,
            long vertexBufferOffset,
            int vertexLayoutId,
            int vertexStride,
            long indexBufferHandle,
            int indexType,
            long indexByteOffset,
            int primitiveType,
            int indexCount,
            int baseVertex,
            int instanceCount,
            int baseInstance,
            int winding,
            int cullMode,
            float depthBias,
            float slopeScale,
            float clamp,
            long textureHandle,
            long samplerHandle,
            float alphaCutoff,
            int useAlphaTest,
            UUID uuid,
            int entityId,
            boolean isDiscontinuous,
            Matrix4f previousFromCurrentView,
            Matrix4f currentUnjitteredProjection,
            Matrix4f previousUnjitteredProjection,
            long frameId,
            long rendererGeneration,
            int inFlightSlot
    ) {
        this.vertexBufferHandle = vertexBufferHandle;
        this.vertexBufferOffset = vertexBufferOffset;
        this.vertexLayoutId = vertexLayoutId;
        this.vertexStride = vertexStride;
        this.indexBufferHandle = indexBufferHandle;
        this.indexType = indexType;
        this.indexByteOffset = indexByteOffset;
        this.primitiveType = primitiveType;
        this.indexCount = indexCount;
        this.baseVertex = baseVertex;
        this.instanceCount = instanceCount;
        this.baseInstance = baseInstance;
        this.winding = winding;
        this.cullMode = cullMode;
        this.depthBias = depthBias;
        this.slopeScale = slopeScale;
        this.clamp = clamp;
        this.textureHandle = textureHandle;
        this.samplerHandle = samplerHandle;
        this.alphaCutoff = alphaCutoff;
        this.useAlphaTest = useAlphaTest;
        this.uuid = uuid;
        this.entityId = entityId;
        this.isDiscontinuous = isDiscontinuous;
        this.previousFromCurrentView = previousFromCurrentView;
        this.currentUnjitteredProjection = currentUnjitteredProjection;
        this.previousUnjitteredProjection = previousUnjitteredProjection;
        this.frameId = frameId;
        this.rendererGeneration = rendererGeneration;
        this.inFlightSlot = inFlightSlot;
    }

    public long vertexBufferHandle() { return vertexBufferHandle; }
    public long vertexBufferOffset() { return vertexBufferOffset; }
    public int vertexLayoutId() { return vertexLayoutId; }
    public int vertexStride() { return vertexStride; }
    public long indexBufferHandle() { return indexBufferHandle; }
    public int indexType() { return indexType; }
    public long indexByteOffset() { return indexByteOffset; }
    public int primitiveType() { return primitiveType; }
    public int indexCount() { return indexCount; }
    public int baseVertex() { return baseVertex; }
    public int instanceCount() { return instanceCount; }
    public int baseInstance() { return baseInstance; }
    public int winding() { return winding; }
    public int cullMode() { return cullMode; }
    public float depthBias() { return depthBias; }
    public float slopeScale() { return slopeScale; }
    public float clamp() { return clamp; }
    public long textureHandle() { return textureHandle; }
    public long samplerHandle() { return samplerHandle; }
    public float alphaCutoff() { return alphaCutoff; }
    public int useAlphaTest() { return useAlphaTest; }
    public UUID uuid() { return uuid; }
    public int entityId() { return entityId; }
    public boolean isDiscontinuous() { return isDiscontinuous; }
    public Matrix4f previousFromCurrentView() { return previousFromCurrentView; }
    public Matrix4f currentUnjitteredProjection() { return currentUnjitteredProjection; }
    public Matrix4f previousUnjitteredProjection() { return previousUnjitteredProjection; }
    public long frameId() { return frameId; }
    public long rendererGeneration() { return rendererGeneration; }
    public int inFlightSlot() { return inFlightSlot; }
}
