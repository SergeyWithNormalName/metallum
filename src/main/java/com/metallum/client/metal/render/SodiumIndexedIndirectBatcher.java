package com.metallum.client.metal.render;

import com.metallum.client.sodium.SodiumIndexedIndirectBatchAccess;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.gpu.device.batch.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gpu.device.context.DrawContext;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDrawIndexedIndirectCommand;

import java.util.Iterator;

/**
 * Uploads finalized Sodium indirect batches before terrain rendering begins.
 *
 * <p>Sodium deliberately fills every per-region batch in a first loop and draws the same
 * batches in a second loop. Snapshotting in that gap keeps the AGX lifetime fix intact while
 * allowing every shared-to-private copy to share one blit encoder. The draw loop then consumes
 * only Metallum-owned immutable ranges and therefore keeps one render encoder open.</p>
 */
public final class SodiumIndexedIndirectBatcher {
    private static long nextRenderInvocationEpoch = 1L;
    private static long activeRenderInvocationEpoch = Long.MIN_VALUE;

    private SodiumIndexedIndirectBatcher() {
    }

    public static void prepare(
            final ChunkRenderListIterable renderLists,
            final TerrainRenderPass pass
    ) {
        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            activeRenderInvocationEpoch = Long.MIN_VALUE;
            return;
        }
        MetalCommandEncoder encoder = device.createCommandEncoder();
        long submitIndex = encoder.currentSubmitIndex();
        long renderInvocationEpoch = nextRenderInvocationEpoch++;
        if (renderInvocationEpoch == Long.MIN_VALUE) {
            renderInvocationEpoch = nextRenderInvocationEpoch++;
        }
        activeRenderInvocationEpoch = renderInvocationEpoch;

        Iterator<ChunkRenderList> iterator = renderLists.iterator(pass.isTranslucent());
        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();
            RenderRegion region = renderList.getRegion();
            SectionRenderDataStorage storage = region.getStorage(pass);
            if (storage == null || region.getResources() == null) {
                continue;
            }
            MultiDrawBatch batch = region.getCachedBatch(pass);
            if (batch.isEmpty() || !(batch instanceof SodiumIndexedIndirectBatchAccess access)) {
                continue;
            }

            long byteLength = Math.multiplyExact(
                    (long) batch.size,
                    VkDrawIndexedIndirectCommand.SIZEOF
            );
            if (access.metallum$commandAddress() == MemoryUtil.NULL || byteLength > Integer.MAX_VALUE) {
                continue;
            }
            long drawnG5CarrierSlices = access.metallum$getDrawnG5CarrierSlices();
            access.metallum$setPreparedSnapshot(
                    submitIndex,
                    renderInvocationEpoch,
                    drawnG5CarrierSlices,
                    encoder.snapshotSodiumIndexedIndirectCommands(
                            access.metallum$commandAddress(),
                            Math.toIntExact(byteLength)
                    )
            );
        }
    }

    public static boolean drawPrepared(
            final MultiDrawBatch batch,
            final DrawContext context
    ) {
        if (!(context instanceof MetalDrawContext metalContext)
                || !(batch instanceof SodiumIndexedIndirectBatchAccess access)) {
            return false;
        }
        MetalDevice device = MetalDevice.getInstance();
        if (device == null) {
            return false;
        }

        long requiredBytes = Math.multiplyExact(
                (long) batch.size,
                VkDrawIndexedIndirectCommand.SIZEOF
        );
        long submitIndex = device.currentSubmitIndex();
        long renderInvocationEpoch = activeRenderInvocationEpoch;
        long drawnG5CarrierSlices = access.metallum$getPreparedDrawnG5CarrierSlices(
                submitIndex,
                renderInvocationEpoch
        );
        GpuBufferSlice snapshot = access.metallum$takePreparedSnapshot(
                submitIndex,
                renderInvocationEpoch
        );
        if (snapshot == null || !preparedSnapshotMatches(
                submitIndex,
                submitIndex,
                renderInvocationEpoch,
                renderInvocationEpoch,
                snapshot.length(),
                requiredBytes
        ) || drawnG5CarrierSlices < 0L) {
            return false;
        }
        metalContext.drawPreparedIndexedIndirect(snapshot, batch.size, drawnG5CarrierSlices);
        return true;
    }

    static boolean preparedSnapshotMatches(
            final long preparedSubmitIndex,
            final long currentSubmitIndex,
            final long preparedRenderInvocationEpoch,
            final long currentRenderInvocationEpoch,
            final long preparedBytes,
            final long requiredBytes
    ) {
        return preparedSubmitIndex == currentSubmitIndex
                && preparedRenderInvocationEpoch == currentRenderInvocationEpoch
                && currentRenderInvocationEpoch != Long.MIN_VALUE
                && requiredBytes > 0L
                && preparedBytes >= requiredBytes;
    }
}
