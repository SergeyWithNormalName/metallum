package com.metallum.client.radiance;

import com.metallum.client.lighting.reflection.WaterReflectionQualityConfig;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Sodium worker snapshot adapter for radiance extraction with compact half-precision output.
 *
 * <p>Samples the central 16^3 section from the accepted {@link LevelSlice} snapshot on background
 * Sodium builder threads. Uses exposed-face lighting semantics so opaque surfaces receive adjacent
 * light rather than internal zero light.</p>
 */
public final class SodiumRadianceSectionExtractor {
    public static final int SECTION_BLOCK_COUNT = 16 * 16 * 16; // 4096

    private static final int[][] NEIGHBOR_OFFSETS = {
            {0, 1, 0},   // +Y
            {0, -1, 0},  // -Y
            {1, 0, 0},   // +X
            {-1, 0, 0},  // -X
            {0, 0, 1},   // +Z
            {0, 0, -1}   // -Z
    };
    private static final Direction[] NEIGHBOR_DIRECTIONS = {
            Direction.UP,
            Direction.DOWN,
            Direction.EAST,
            Direction.WEST,
            Direction.SOUTH,
            Direction.NORTH
    };

    @FunctionalInterface
    public interface BlockStateAccessor {
        BlockState getBlockState(int x, int y, int z);
    }

    @FunctionalInterface
    public interface LightBrightnessAccessor {
        int getBrightness(LightLayer layer, BlockPos pos);
    }

    private SodiumRadianceSectionExtractor() {
    }

    public static CompactSectionPayload empty(final long sectionKey, final long worldGeneration) {
        return CompactSectionPayload.empty(sectionKey, worldGeneration);
    }

    public static CompactSectionPayload extract(
            final long sectionKey,
            final long worldGeneration,
            final LevelSlice levelSlice
    ) {
        Objects.requireNonNull(levelSlice, "levelSlice");
        return extract(
                sectionKey,
                worldGeneration,
                levelSlice,
                levelSlice::getBlockState,
                levelSlice::getBrightness
        );
    }

    public static CompactSectionPayload extract(
            final long sectionKey,
            final long worldGeneration,
            final BlockGetter blockGetter,
            final BlockStateAccessor stateAccessor,
            final LightBrightnessAccessor lightAccessor
    ) {
        Objects.requireNonNull(stateAccessor, "stateAccessor");
        Objects.requireNonNull(lightAccessor, "lightAccessor");

        if (!RadianceRuntimeStorage.global().canAcceptCandidatePayload(CompactSectionPayload.ESTIMATED_NON_EMPTY_BYTES)) {
            RadianceRuntimeStorage.global().noteCandidateQueueOverrun();
            // Queue pressure is not authoritative empty world data.  Callers that build a finite
            // frozen field must fail the capture rather than silently introducing a transparent
            // hole; normal renderer work remains independent of this diagnostic producer.
            throw new IllegalStateException("Radiance snapshot budget exhausted before extraction");
        }

        int originX = SectionPos.x(sectionKey) << 4;
        int originY = SectionPos.y(sectionKey) << 4;
        int originZ = SectionPos.z(sectionKey) << 4;

        short[] packedRgba = new short[SECTION_BLOCK_COUNT * 4];
        byte[] classification = new byte[SECTION_BLOCK_COUNT];

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        BlockAndTintGetter tintWorld = blockGetter instanceof BlockAndTintGetter getter ? getter : null;
        boolean faceAwareEnabled = WaterReflectionQualityConfig.isFaceAwareAppearanceEnabled()
                && tintWorld != null;
        float[] faceAwareAlbedo = faceAwareEnabled ? new float[3] : null;

        int emissiveCount = 0;
        int occupiedCount = 0;
        int moddedFallbackCount = 0;
        int fallbackLightCount = 0;
        int neighborSamples = 0;
        int exposedFaces = 0;
        int missingNeighbors = 0;
        int opaqueLitNeighbors = 0;

        for (int localIndex = 0; localIndex < SECTION_BLOCK_COUNT; localIndex++) {
            int localX = localIndex & 15;
            int localZ = (localIndex >>> 4) & 15;
            int localY = (localIndex >>> 8) & 15;

            int worldX = originX + localX;
            int worldY = originY + localY;
            int worldZ = originZ + localZ;
            pos.set(worldX, worldY, worldZ);

            try {
                BlockState state = stateAccessor.getBlockState(worldX, worldY, worldZ);
                if (state == null || state.isAir()) {
                    classification[localIndex] = CompactSectionPayload.CLASS_EMPTY;
                    continue;
                }

                float selfOpacity = RadianceAppearanceModel.opacityFor(state);
                if (selfOpacity <= 0.0F) {
                    classification[localIndex] = CompactSectionPayload.CLASS_EMPTY;
                    continue;
                }

                int selfSky = 0;
                int selfBlock = 0;
                try {
                    selfSky = lightAccessor.getBrightness(LightLayer.SKY, pos);
                    selfBlock = lightAccessor.getBrightness(LightLayer.BLOCK, pos);
                } catch (RuntimeException ignored) {
                    fallbackLightCount++;
                }

                int maxNeighborSky = 0;
                int maxNeighborBlock = 0;
                int exposedCount = 0;
                int exposedFaceMask = 0;

                // Inspect 6 face-adjacent neighbors in LevelSlice
                for (int faceIndex = 0; faceIndex < NEIGHBOR_OFFSETS.length; faceIndex++) {
                    int[] offset = NEIGHBOR_OFFSETS[faceIndex];
                    int nx = worldX + offset[0];
                    int ny = worldY + offset[1];
                    int nz = worldZ + offset[2];
                    neighborSamples++;

                    try {
                        BlockState nState = stateAccessor.getBlockState(nx, ny, nz);
                        float nOpacity = RadianceAppearanceModel.opacityFor(nState);
                        if (nOpacity < selfOpacity || nOpacity < 0.9F) {
                            exposedFaces++;
                            exposedCount++;
                            exposedFaceMask |= 1 << NEIGHBOR_DIRECTIONS[faceIndex].ordinal();
                            neighborPos.set(nx, ny, nz);
                            int nSky = lightAccessor.getBrightness(LightLayer.SKY, neighborPos);
                            int nBlock = lightAccessor.getBrightness(LightLayer.BLOCK, neighborPos);
                            if (nSky > maxNeighborSky) maxNeighborSky = nSky;
                            if (nBlock > maxNeighborBlock) maxNeighborBlock = nBlock;
                        }
                    } catch (RuntimeException ignored) {
                        missingNeighbors++;
                    }
                }

                int effectiveSky;
                int effectiveBlock;
                if (selfOpacity < 0.5F) {
                    // Transparent/fluid media: water, glass, thin vegetation
                    effectiveSky = Math.max(selfSky, maxNeighborSky);
                    effectiveBlock = Math.max(selfBlock, maxNeighborBlock);
                } else {
                    // Solid/opaque terrain: sand, dirt, stone, wood
                    if (exposedCount > 0) {
                        effectiveSky = maxNeighborSky;
                        effectiveBlock = maxNeighborBlock;
                    } else {
                        effectiveSky = selfSky;
                        effectiveBlock = selfBlock;
                    }
                    if (selfSky == 0 && selfBlock == 0 && (effectiveSky > 0 || effectiveBlock > 0)) {
                        opaqueLitNeighbors++;
                    }
                }

                boolean faceAwareResolved = faceAwareEnabled && FaceAwareRadianceAppearance.resolve(
                        state, tintWorld, pos, exposedFaceMask, faceAwareAlbedo
                );
                RadianceAppearanceModel.EvaluatedAppearance app = faceAwareResolved
                        ? RadianceAppearanceModel.evaluateWithLinearAlbedo(
                                state, blockGetter, pos, effectiveSky, effectiveBlock,
                                faceAwareAlbedo[0], faceAwareAlbedo[1], faceAwareAlbedo[2]
                        )
                        : RadianceAppearanceModel.evaluate(
                                state, blockGetter, pos, effectiveSky, effectiveBlock
                        );

                int radIdx = localIndex * 4;
                packedRgba[radIdx] = Float16Compressor.packFloat(app.red());
                packedRgba[radIdx + 1] = Float16Compressor.packFloat(app.green());
                packedRgba[radIdx + 2] = Float16Compressor.packFloat(app.blue());
                packedRgba[radIdx + 3] = Float16Compressor.packFloat(app.opacity());

                if (state.getFluidState().is(FluidTags.WATER)) {
                    // The reflection reducer discards this receiver medium so shallow reflected
                    // rays cannot hit the water plane before reaching actual scene geometry.
                    classification[localIndex] = CompactSectionPayload.CLASS_WATER;
                    occupiedCount++;
                } else if (app.emissive()) {
                    classification[localIndex] = CompactSectionPayload.CLASS_EMISSIVE;
                    emissiveCount++;
                    occupiedCount++;
                } else if (app.occupied()) {
                    classification[localIndex] = CompactSectionPayload.CLASS_OCCUPIED;
                    occupiedCount++;
                } else {
                    classification[localIndex] = CompactSectionPayload.CLASS_EMPTY;
                }
            } catch (RuntimeException ignored) {
                // Modded block query failed; deterministic conservative fallback
                int radIdx = localIndex * 4;
                packedRgba[radIdx] = 0;
                packedRgba[radIdx + 1] = 0;
                packedRgba[radIdx + 2] = 0;
                packedRgba[radIdx + 3] = Float16Compressor.packFloat(1.0F);
                classification[localIndex] = CompactSectionPayload.CLASS_OCCUPIED;
                occupiedCount++;
                moddedFallbackCount++;
            }
        }

        if (occupiedCount == 0 && emissiveCount == 0 && moddedFallbackCount == 0) {
            CompactSectionPayload emptyPayload = CompactSectionPayload.empty(sectionKey, worldGeneration);
            RadianceRuntimeStorage.global().noteSectionExtracted(
                    emptyPayload,
                    SECTION_BLOCK_COUNT,
                    0,
                    0,
                    0,
                    neighborSamples,
                    exposedFaces,
                    missingNeighbors,
                    opaqueLitNeighbors
            );
            return emptyPayload;
        }

        CompactSectionPayload payload = new CompactSectionPayload(
                sectionKey,
                worldGeneration,
                false,
                packedRgba,
                classification
        );
        RadianceRuntimeStorage.global().noteSectionExtracted(
                payload,
                SECTION_BLOCK_COUNT,
                fallbackLightCount,
                moddedFallbackCount,
                emissiveCount,
                neighborSamples,
                exposedFaces,
                missingNeighbors,
                opaqueLitNeighbors
        );
        return payload;
    }
}
