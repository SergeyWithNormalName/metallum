package com.metallum.client.metal.render;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import net.caffeinemc.mods.sodium.client.gpu.device.context.VKIndirectContext;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;

import java.nio.ByteBuffer;

public final class MetalDrawContext extends VKIndirectContext {
    private static final int PUSH_CONSTANT_SIZE = 20;

    private MetalRenderPass metalPass;

    @Override
    public void setContext(RenderPass pass, RenderPipeline pipeline) {
        this.pass = pass;
        this.metalPass = (MetalRenderPass) ((net.caffeinemc.mods.sodium.mixin.core.RenderPassAccessor) pass).getBackend();
    }

    @Override
    public void updateData(RenderRegion region, CameraTransform camera) {
        float x = getCameraTranslation(region.getOriginX(), camera.intX, camera.fracX);
        float y = getCameraTranslation(region.getOriginY(), camera.intY, camera.fracY);
        float z = getCameraTranslation(region.getOriginZ(), camera.intZ, camera.fracZ);

        long byteOffset = this.addCommand(PUSH_CONSTANT_SIZE);
        GpuBufferSlice.MappedView mapped = this.mappedView;
        if (mapped == null) {
            throw new IllegalStateException("Sodium indirect ring is not mapped");
        }
        if (byteOffset < 0) {
            throw new IllegalStateException("Sodium indirect ring offset is negative");
        }

        GpuBufferSlice ringSlice = mapped.slice();
        ByteBuffer data = mapped.data();
        long byteEnd = Math.addExact(byteOffset, PUSH_CONSTANT_SIZE);
        if (byteEnd > ringSlice.length() || byteEnd > data.capacity()) {
            throw new IllegalStateException(
                    "Sodium indirect ring exhausted while reserving Metal push constants: offset="
                            + byteOffset + ", size=" + PUSH_CONSTANT_SIZE + ", sliceCapacity=" + ringSlice.length()
                            + ", mappedCapacity=" + data.capacity()
            );
        }

        int dataOffset = Math.toIntExact(byteOffset);
        data.putFloat(dataOffset, x);
        data.putFloat(dataOffset + 4, y);
        data.putFloat(dataOffset + 8, z);
        data.putInt(dataOffset + 12, Math.toIntExact(System.currentTimeMillis() - region.getCreationTime()));
        data.putInt(dataOffset + 16, region.getId());

        GpuBufferSlice pushConstantsBufferSlice = ringSlice.slice(byteOffset, PUSH_CONSTANT_SIZE);
        this.metalPass.setUniform("push_constants", pushConstantsBufferSlice);
    }
}
