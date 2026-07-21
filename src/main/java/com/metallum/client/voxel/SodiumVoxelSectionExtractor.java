package com.metallum.client.voxel;

import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Exact Sodium 0.9.1 worker adapter. It samples only the central 16^3 section from the same
 * {@link LevelSlice} that has just produced the accepted mesh output; it never reads live world
 * state from the render thread.
 */
public final class SodiumVoxelSectionExtractor {
    private SodiumVoxelSectionExtractor() {
    }

    public static VoxelSectionCandidate encode(
            final VoxelSectionTask task,
            final LevelSlice worldSlice
    ) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (worldSlice == null) {
            throw new NullPointerException("worldSlice");
        }

        int originX = SectionPos.x(task.sectionKey()) << 4;
        int originY = SectionPos.y(task.sectionKey()) << 4;
        int originZ = SectionPos.z(task.sectionKey()) << 4;
        long[] occupancyMasks = new long[VoxelSectionSnapshot.BLOCK_COUNT];
        byte[] optical = new byte[VoxelSectionSnapshot.BLOCK_COUNT];
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

        for (int localIndex = 0; localIndex < VoxelSectionSnapshot.BLOCK_COUNT; localIndex++) {
            int localX = localIndex & 15;
            int localZ = localIndex >>> 4 & 15;
            int localY = localIndex >>> 8 & 15;
            int worldX = originX + localX;
            int worldY = originY + localY;
            int worldZ = originZ + localZ;
            position.set(worldX, worldY, worldZ);
            try {
                BlockState state = worldSlice.getBlockState(worldX, worldY, worldZ);
                VoxelMaterialDescriptor material = materialFor(state);
                VoxelShape shape = state.getBlock() instanceof VegetationBlock
                        ? Shapes.empty()
                        : state.getShape(worldSlice, position);
                if (!state.getFluidState().isEmpty()) {
                    // Water normally has an empty block collision/outline shape. Its medium
                    // shape is still geometry for L5 opacity, and must remain paired with the
                    // WATER material rather than accidentally becoming transparent air.
                    shape = Shapes.or(shape, state.getFluidState().getShape(worldSlice, position));
                }
                VoxelShapeEncoder.EncodedShape encoded = VoxelShapeEncoder.encode(
                        shape,
                        VoxelSubdivision.FOUR,
                        material
                );
                occupancyMasks[localIndex] = encoded.occupancyMask();
                optical[localIndex] = effectiveOptical(material, encoded);
            } catch (RuntimeException ignored) {
                // A modded shape is allowed to fail locally. Keep the L5 producer safe and
                // conservative without propagating a failure into Sodium's accepted geometry.
                occupancyMasks[localIndex] = -1L;
                optical[localIndex] = (byte) VoxelMaterialDescriptor.defaults(
                        VoxelMaterialClass.UNKNOWN_CONSERVATIVE
                ).packedUnsignedByte();
            }
        }
        return new VoxelSectionCandidate(
                task,
                new VoxelSectionSnapshot(occupancyMasks, optical),
                VoxelSectionSnapshot.BLOCK_COUNT
        );
    }

    static VoxelMaterialDescriptor materialFor(final BlockState state) {
        if (state.isAir()) {
            return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.AIR);
        }
        if (state.getBlock() instanceof VegetationBlock) {
            return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.AIR);
        }
        if (!state.getFluidState().isEmpty()) {
            return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.WATER);
        }
        if (state.is(BlockTags.LEAVES)) {
            return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.FOLIAGE);
        }
        // Vanilla block-light propagation deliberately lets slabs, stairs and fences pass light.
        // That does not make their occupied voxel cells glass for a ray shadow: only a block
        // that is actually rendered as transparent keeps the GLASS material class.
        if (state.getBlock() instanceof TransparentBlock) {
            return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.GLASS);
        }
        if (!state.isSolidRender()) {
            return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.CUTOUT);
        }
        if (state.getLightDampening() < 15) {
            return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.TRANSLUCENT);
        }
        return VoxelMaterialDescriptor.defaults(VoxelMaterialClass.OPAQUE);
    }

    /**
     * Optical storage is per world block, while occupancy is sub-block. Fold aggregate shape
     * coverage into transmittance for the coarse 2x/1x clipmap levels so a fence/pane does not
     * become a full opaque block there. The packer restores base opacity for an occupied exact
     * 4x cell of fully occluding material before L6 consumes it.
     */
    private static byte effectiveOptical(
            final VoxelMaterialDescriptor material,
            final VoxelShapeEncoder.EncodedShape encoded
    ) {
        float coverage = encoded.coverageByte() / 255.0f;
        float effectiveOpacity = coverage * material.opacity();
        return (byte) new VoxelMaterialDescriptor(
                material.materialClass(),
                1.0f - effectiveOpacity
        ).packedUnsignedByte();
    }
}
