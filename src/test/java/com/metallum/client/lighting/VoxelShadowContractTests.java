package com.metallum.client.lighting;

import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelMaterialClass;
import com.metallum.client.voxel.VoxelMaterialDescriptor;
import com.metallum.client.voxel.VoxelShadowCacheBuilder;
import com.metallum.client.voxel.VoxelShadowCacheMirror;
import com.metallum.client.voxel.VoxelShadowTraversal;
import com.metallum.client.voxel.VoxelUploadBatch;
import com.metallum.client.voxel.VoxelWorldToken;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

/** Standalone L6 CPU/ABI contracts; intentionally runnable without a Metal device. */
public final class VoxelShadowContractTests {
    private static final LightWorldToken WORLD = new LightWorldToken(7L, "minecraft:overworld");
    private static final VoxelWorldToken VOXEL_WORLD = new VoxelWorldToken(7L, "minecraft:overworld");

    private VoxelShadowContractTests() {
    }

    public static void main(final String[] args) {
        testPresetCapsAndAccounting();
        testExactBindingAbi();
        testProxySelectionAndLifecycle();
        testExactTraversalAtAllSubdivisions();
        testCachedCubeTraversal();
        testStableCubeLevelSelection();
        testCompactedEmitterDoesNotShadowItself();
        testLongDiagonalCoarsensBeforeStepCap();
        testTraversalFailsOpen();
        System.out.println("L6 voxel-shadow CPU contracts passed");
    }

    private static void testPresetCapsAndAccounting() {
        LocalVoxelShadowLayout.Budget performance = LocalVoxelShadowLayout.forPreset(LightingPreset.PERFORMANCE);
        LocalVoxelShadowLayout.Budget balanced = LocalVoxelShadowLayout.forPreset(LightingPreset.BALANCED);
        LocalVoxelShadowLayout.Budget ultra = LocalVoxelShadowLayout.forPreset(LightingPreset.ULTRA);
        require(performance.shadowedLocalLights() == 1 && performance.maxSteps() == 32
                        && performance.maxEntityProxies() == 8,
                "Performance L6 cap changed");
        require(balanced.shadowedLocalLights() == 2 && balanced.maxSteps() == 64
                        && balanced.maxEntityProxies() == 16,
                "Balanced L6 cap changed");
        require(ultra.shadowedLocalLights() == 2 && ultra.maxSteps() == 80
                        && ultra.maxEntityProxies() == 24,
                "Ultra L6 cap changed");
        require(LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS == 2
                        && LocalVoxelShadowLayout.MAX_DDA_STEPS == 96
                        && LocalVoxelShadowLayout.MAX_ENTITY_PROXIES == 32
                        && LocalVoxelShadowLayout.PARAMS_BYTES == 256
                        && LocalVoxelShadowLayout.PARAMS_RING_SLOTS == 3
                        && LocalVoxelShadowLayout.PROXY_STRIDE_BYTES == 32
                        && LocalVoxelShadowLayout.CACHE_FACE_EDGE == 64
                        && LocalVoxelShadowLayout.CACHE_FACE_COUNT == 6
                        && LocalVoxelShadowLayout.CACHE_LAYER_COUNT == 4
                        && LocalVoxelShadowLayout.CACHE_HIT_STRIDE_BYTES == 8,
                "L6 compile or upload caps changed");
        require(balanced.paramsRingBytes() == 768L && balanced.proxyRingBytes() == 1_536L
                        && balanced.visibilityCacheBytes() == 1_572_864L
                        && balanced.totalDedicatedBytes() == 1_575_168L,
                "L6 ring bytes changed");
    }

    private static void testExactBindingAbi() {
        require(VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT == 14
                        && VoxelShadowBindingAbi.PROXY_BUFFER_SLOT == 15
                        && VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT == 16
                        && Arrays.equals(VoxelShadowBindingAbi.occupancyTextureSlots(), new int[]{17, 18, 19})
                        && Arrays.equals(VoxelShadowBindingAbi.opticalTextureSlots(), new int[]{20, 21, 22})
                        && Arrays.equals(VoxelShadowBindingAbi.metadataBufferSlots(), new int[]{23, 24, 25}),
                "L6 fragment binding slots changed");
        require(VoxelShadowBindingAbi.PARAMS_BYTES == 256
                        && VoxelShadowBindingAbi.WORLD_FROM_VIEW_MATRIX_OFFSET == 0
                        && VoxelShadowBindingAbi.CAMERA_BLOCK_AND_FLAGS_OFFSET == 64
                        && VoxelShadowBindingAbi.CAMERA_FRACTION_AND_MIN_TRANSMITTANCE_OFFSET == 80
                        && VoxelShadowBindingAbi.CAPS_OFFSET == 96
                        && VoxelShadowBindingAbi.PROXY_AND_FRAME_OFFSET == 112
                        && VoxelShadowBindingAbi.levelOffset(0) == 128
                        && VoxelShadowBindingAbi.levelOffset(1) == 160
                        && VoxelShadowBindingAbi.levelOffset(2) == 192
                        && VoxelShadowBindingAbi.CONTRACT_OFFSET == 224
                        && VoxelShadowBindingAbi.WORLD_AND_FLAGS_OFFSET == 240,
                "L6 256-byte parameter layout changed");
        require(VoxelShadowBindingAbi.ownsFragmentSlot(14)
                        && VoxelShadowBindingAbi.ownsFragmentSlot(15)
                        && VoxelShadowBindingAbi.ownsFragmentSlot(25)
                        && !VoxelShadowBindingAbi.ownsFragmentSlot(26),
                "L6 ABI slot ownership is not closed");
    }

    private static void testProxySelectionAndLifecycle() {
        EntityShadowProxy farLarge = proxy(30L, 8.0, 2.0f);
        EntityShadowProxy nearSmall = proxy(20L, 1.0, 0.5f);
        EntityShadowProxy nearLargeHighId = proxy(40L, 1.0, 1.0f);
        EntityShadowProxy nearLargeLowId = proxy(10L, 1.0, 1.0f);
        BoundedEntityShadowProxyCollector collector = new BoundedEntityShadowProxyCollector(
                WORLD, 2, 0.0, 0.0, 0.0
        );
        collector.offer(farLarge);
        collector.offer(nearSmall);
        collector.offer(nearLargeHighId);
        collector.offer(nearLargeLowId);
        require(collector.finish().equals(List.of(nearLargeLowId, nearLargeHighId)),
                "proxy collector is not camera-distance/volume/stable-id deterministic");

        EntityShadowProxyRegistry registry = new EntityShadowProxyRegistry();
        Object identity = new Object();
        registry.openWorld(identity, WORLD);
        registry.publish(WORLD, collector.finish(), collector.offered());
        EntityShadowProxySnapshot snapshot = registry.snapshot(WORLD);
        require(snapshot != null && snapshot.droppedCount() == 2 && snapshot.proxies().size() == 2,
                "proxy snapshot did not preserve bounded selection");
        LightWorldToken reload = new LightWorldToken(8L, "minecraft:overworld");
        registry.openWorld(identity, reload);
        require(registry.snapshot(WORLD) == null && registry.snapshot(reload) == null,
                "proxy registry kept a snapshot across reload generation");
        registry.closeWorld(identity);
        require(registry.snapshot(reload) == null, "proxy registry kept a snapshot after world close");
    }

    private static void testExactTraversalAtAllSubdivisions() {
        for (int subdivision : new int[]{1, 2, 4}) {
            VoxelShadowTraversal.LevelData opaque = level(subdivision, VoxelMaterialClass.OPAQUE);
            float blocked = VoxelShadowTraversal.visibility(
                    opaque, VOXEL_WORLD, 11L,
                    new VoxelShadowTraversal.Point(0.1, 0.1, 0.1),
                    new VoxelShadowTraversal.Point(0.9, 0.1, 0.1), 96
            );
            require(blocked == 0.0f, "opaque " + subdivision + "x DDA did not early-exit");

            VoxelShadowTraversal.LevelData glass = level(subdivision, VoxelMaterialClass.GLASS);
            float transmitted = VoxelShadowTraversal.visibility(
                    glass, VOXEL_WORLD, 11L,
                    new VoxelShadowTraversal.Point(0.1, 0.1, 0.1),
                    new VoxelShadowTraversal.Point(0.9, 0.1, 0.1), 96
            );
            require(transmitted > 0.0f && transmitted < 1.0f,
                    "" + subdivision + "x DDA lost material transmittance");
        }

        VoxelShadowTraversal.LevelData glass4x = level(4, VoxelMaterialClass.GLASS);
        int[] occupiedSubcells = glass4x.occupancyWords();
        occupiedSubcells[0] = 0b1111;
        VoxelShadowTraversal.LevelData filledGlass4x = new VoxelShadowTraversal.LevelData(
                glass4x.snapshot(), glass4x.levelIndex(), occupiedSubcells,
                glass4x.opticalBytes(), glass4x.metadata()
        );
        float oneBlockVisibility = VoxelShadowTraversal.visibility(
                filledGlass4x, VOXEL_WORLD, 11L,
                new VoxelShadowTraversal.Point(0.1, 0.1, 0.1),
                new VoxelShadowTraversal.Point(0.9, 0.1, 0.1), 96
        );
        float packedGlass = VoxelMaterialDescriptor.fromPackedUnsignedByte(
                VoxelMaterialDescriptor.defaults(VoxelMaterialClass.GLASS).packedUnsignedByte()
        ).transmittance();
        require(Math.abs(oneBlockVisibility - packedGlass) <= 1.0e-6f,
                "4x DDA multiplied one block's transmittance more than once");
    }

    private static void testTraversalFailsOpen() {
        VoxelShadowTraversal.LevelData valid = level(4, VoxelMaterialClass.OPAQUE);
        VoxelShadowTraversal.Point start = new VoxelShadowTraversal.Point(0.1, 0.1, 0.1);
        VoxelShadowTraversal.Point end = new VoxelShadowTraversal.Point(0.9, 0.1, 0.1);
        require(VoxelShadowTraversal.visibility(valid, VOXEL_WORLD, 12L, start, end, 96) == 1.0f,
                "stale clipmap generation did not fail open");
        require(VoxelShadowTraversal.visibility(valid, VOXEL_WORLD, 11L,
                        new VoxelShadowTraversal.Point(8.0, 0.1, 0.1), end, 96) == 1.0f,
                "out-of-range ray did not fail open");
        require(VoxelShadowTraversal.visibility(valid, VOXEL_WORLD, 11L,
                        new VoxelShadowTraversal.Point(0.5, 0.1, 0.1),
                        new VoxelShadowTraversal.Point(1.5, 0.1, 0.1), 1) == 1.0f,
                "step-cap ray did not fail open");
        require(VoxelShadowTraversal.visibility(valid, VOXEL_WORLD, 11L,
                        new VoxelShadowTraversal.Point(Double.NaN, 0.1, 0.1), end, 96) == 1.0f,
                "non-finite ray did not fail open");

        VoxelShadowTraversal.BrickMetadata[] staleTags = valid.metadata();
        staleTags[0] = new VoxelShadowTraversal.BrickMetadata(1, 0, 0, 1);
        VoxelShadowTraversal.LevelData staleMetadata = new VoxelShadowTraversal.LevelData(
                valid.snapshot(), valid.levelIndex(), valid.occupancyWords(), valid.opticalBytes(), staleTags
        );
        require(VoxelShadowTraversal.visibility(staleMetadata, VOXEL_WORLD, 11L, start, end, 96) == 1.0f,
                "mismatched toroidal metadata did not fail open");
    }

    private static void testLongDiagonalCoarsensBeforeStepCap() {
        VoxelClipmapSnapshot balanced = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 256, 0, 0, 0, 8),
                        new VoxelClipmapSnapshot.Level(1, 2, 256, 0, 0, 0, 8),
                        new VoxelClipmapSnapshot.Level(2, 1, 256, 0, 0, 0, 8)
                )
        );
        VoxelShadowTraversal.Point receiver = new VoxelShadowTraversal.Point(1.1, 1.1, 1.1);
        VoxelShadowTraversal.Point shortLight = new VoxelShadowTraversal.Point(5.0, 5.0, 5.0);
        VoxelShadowTraversal.Point longLight = new VoxelShadowTraversal.Point(8.3, 8.3, 8.3);
        require(VoxelShadowTraversal.selectFinestFittingLevel(
                        balanced, receiver, shortLight, 64) == 0,
                "short diagonal did not retain the finest 4x level");
        require(VoxelShadowTraversal.selectFinestFittingLevel(
                        balanced, receiver, longLight, 64) == 1,
                "long diagonal did not coarsen from 4x to a bounded 2x traversal");
        require(VoxelShadowTraversal.selectFinestFittingLevel(
                        balanced, receiver, longLight, 32) == 2,
                "tight step budget did not continue coarsening to 1x");
    }

    private static void testCachedCubeTraversal() {
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );
        AdvancedLight light = new AdvancedLight(
                1L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.acknowledge(cacheBatch(
                1L, cacheWallPatch(1, VoxelMaterialClass.OPAQUE), 0
        ));
        VoxelShadowCacheMirror.Snapshot opaqueSnapshot = mirror.snapshot(clipmap);
        require(opaqueSnapshot != null, "completed L5 batch did not expose an L6 cache mirror");
        require(mirror.snapshot(clipmap) == opaqueSnapshot,
                "static L6 clipmap snapshot was recopied instead of reused");
        VoxelClipmapSnapshot shiftedClipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, 0, -1, -1, 2))
        );
        require(mirror.snapshot(shiftedClipmap) == null,
                "L6 reused a cache while a new toroidal window was still pending");
        VoxelShadowCacheBuilder.Result opaque = VoxelShadowCacheBuilder.build(
                opaqueSnapshot, List.of(light), 1, 96
        );
        require(opaque.payload().length == LocalVoxelShadowLayout.cacheBytes(1)
                        && opaque.raysWithHits() > 0
                        && opaque.totalRays() == 6 * 64 * 64
                        && opaque.cacheLevels().equals(List.of(0)),
                "bounded point-shadow cache did not record the opaque wall");
        ByteBuffer opaquePayload = ByteBuffer.wrap(opaque.payload()).order(ByteOrder.nativeOrder());
        int centralPositiveX = cacheEntryOffset(0, 31, 31, 0);
        require(Float.isFinite(opaquePayload.getFloat(centralPositiveX))
                        && opaquePayload.getFloat(centralPositiveX + Float.BYTES) == 0.0f,
                "cached +X ray did not become opaque behind the wall");

        VoxelBrickPatch restampedOpaque = cacheWallPatch(2, VoxelMaterialClass.OPAQUE);
        mirror.acknowledge(cacheBatch(2L, restampedOpaque, 0));
        VoxelShadowCacheMirror.Snapshot restampedSnapshot = mirror.snapshot(clipmap);
        require(VoxelShadowCacheBuilder.relevantGeometryEquals(
                        opaqueSnapshot, restampedSnapshot, List.of(light)),
                "identical relevant geometry invalidated the cached point shadow");

        VoxelBrickPatch glassPatch = cacheWallPatch(3, VoxelMaterialClass.GLASS);
        mirror.acknowledge(cacheBatch(3L, glassPatch, 1));
        VoxelShadowCacheMirror.Snapshot pendingSnapshot = mirror.snapshot(clipmap);
        require(pendingSnapshot != null && !pendingSnapshot.current()
                        && pendingSnapshot.revision() == restampedSnapshot.revision()
                        && pendingSnapshot.bricks().equals(restampedSnapshot.bricks()),
                "bounded dirty queue discarded the last complete L6 cache");
        mirror.acknowledge(cacheBatch(4L, glassPatch, 0));
        VoxelShadowCacheMirror.Snapshot glassSnapshot = mirror.snapshot(clipmap);
        require(!VoxelShadowCacheBuilder.relevantGeometryEquals(
                        restampedSnapshot, glassSnapshot, List.of(light)),
                "relevant transmittance change did not invalidate the cached point shadow");
        VoxelShadowCacheBuilder.Result glass = VoxelShadowCacheBuilder.build(
                glassSnapshot, List.of(light), 1, 96
        );
        float glassVisibility = ByteBuffer.wrap(glass.payload())
                .order(ByteOrder.nativeOrder())
                .getFloat(centralPositiveX + Float.BYTES);
        require(glassVisibility > 0.0f && glassVisibility < 1.0f,
                "cached point shadow lost voxel transmittance");
        mirror.reset();
        require(mirror.snapshot(clipmap) == null,
                "L6 cache mirror retained a closed voxel generation");
    }

    private static void testStableCubeLevelSelection() {
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 256, -4, -4, -4, 8),
                        new VoxelClipmapSnapshot.Level(1, 2, 256, -2, -2, -2, 8),
                        new VoxelClipmapSnapshot.Level(2, 1, 256, -1, -1, -1, 8)
                )
        );
        AdvancedLight lava = new AdvancedLight(
                91L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 12.75f,
                1.0f, 0.08f, 0.004f, 3.15f, 240, true
        );
        require(VoxelShadowCacheBuilder.selectCacheLevel(clipmap, lava, 96) == 0,
                "96-step cube did not retain one 4x level for every lava direction");
        require(VoxelShadowCacheBuilder.selectCacheLevel(clipmap, lava, 64) == 1,
                "64-step cube did not choose one seam-free 2x level for lava");
        require(VoxelShadowCacheBuilder.selectCacheLevel(clipmap, lava, 32) == 2,
                "32-step cube did not choose one seam-free 1x level for lava");
    }

    private static void testCompactedEmitterDoesNotShadowItself() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 128, -2, -2, -2, 4))
        );
        mirror.acknowledge(new VoxelUploadBatch(
                41L, VOXEL_WORLD, 11L, 41L,
                List.of(denseEmitterPatch(), emptyCachePatch()),
                0, 0L, 0, 0, 0L, 0L
        ));
        VoxelShadowCacheMirror.Snapshot snapshot = mirror.snapshot(clipmap);
        require(snapshot != null, "dense-emitter cache fixture did not publish");

        List<AdvancedLight> members = List.of(
                denseEmitter(101L, 0),
                denseEmitter(102L, 1),
                denseEmitter(103L, 2),
                denseEmitter(104L, 3)
        );
        AdvancedLight aggregate = DenseBlockLightCompactor.compact(
                "minecraft:overworld", members
        ).lights().getFirst();
        require(aggregate.shadowEmitterFootprint().blocks().size() == 4,
                "dense cache fixture did not compact to an exact four-cell footprint");
        VoxelShadowCacheBuilder.Result aggregateCache = VoxelShadowCacheBuilder.build(
                snapshot, List.of(aggregate), 1, 96
        );
        int positiveX = cacheEntryOffset(0, 31, 31, 0);
        ByteBuffer aggregatePayload = ByteBuffer.wrap(aggregateCache.payload())
                .order(ByteOrder.nativeOrder());
        require(aggregatePayload.getFloat(positiveX) > 1.8f
                        && aggregatePayload.getFloat(positiveX + Float.BYTES) == 0.0f,
                "compacted lava cells attenuated their own cached point light");

        AdvancedLight ordinary = new AdvancedLight(
                105L, 1L, LightSourceKind.BLOCK,
                aggregate.x(), aggregate.y(), aggregate.z(), aggregate.radius(),
                aggregate.red(), aggregate.green(), aggregate.blue(),
                aggregate.intensity(), aggregate.priority()
        );
        VoxelShadowCacheBuilder.Result ordinaryCache = VoxelShadowCacheBuilder.build(
                snapshot, List.of(ordinary), 1, 96
        );
        float waterVisibility = ByteBuffer.wrap(ordinaryCache.payload())
                .order(ByteOrder.nativeOrder())
                .getFloat(positiveX + Float.BYTES);
        require(waterVisibility > 0.0f && waterVisibility < 1.0f,
                "non-emitter fluid stopped attenuating local shadows");
        mirror.reset();
    }

    private static VoxelUploadBatch cacheBatch(
            final long batchId,
            final VoxelBrickPatch patch,
            final int queueRemaining
    ) {
        return new VoxelUploadBatch(
                batchId, VOXEL_WORLD, 11L, batchId,
                List.of(patch), queueRemaining, 0L, 0, 0, 0L, 0L
        );
    }

    private static VoxelBrickPatch cacheWallPatch(
            final int stamp,
            final VoxelMaterialClass materialClass
    ) {
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        for (int z = 0; z < VoxelBrickPatch.LOGICAL_EDGE; z++) {
            for (int y = 0; y < VoxelBrickPatch.LOGICAL_EDGE; y++) {
                occupancy[z * VoxelBrickPatch.LOGICAL_EDGE + y] = 0x0f << 8;
            }
        }
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / 4;
        byte[] optical = new byte[blockEdge * blockEdge * blockEdge];
        byte packed = (byte) VoxelMaterialDescriptor.defaults(materialClass).packedUnsignedByte();
        for (int z = 0; z < blockEdge; z++) {
            for (int y = 0; y < blockEdge; y++) {
                optical[(z * blockEdge + y) * blockEdge + 2] = packed;
            }
        }
        return new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, stamp,
                VOXEL_WORLD.generation(), 11L, occupancy, optical
        );
    }

    private static AdvancedLight denseEmitter(final long stableId, final int blockX) {
        return new AdvancedLight(
                stableId, 1L, LightSourceKind.BLOCK,
                blockX + 0.5, 0.5, 0.5, 9.0f,
                1.0f, 0.08f, 0.004f, 2.0f, 200, true
        );
    }

    private static VoxelBrickPatch denseEmitterPatch() {
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        int subdivision = 4;
        for (int blockX = 0; blockX <= 4; blockX++) {
            for (int cellZ = 0; cellZ < subdivision; cellZ++) {
                for (int cellY = 0; cellY < subdivision; cellY++) {
                    occupancy[cellZ * VoxelBrickPatch.LOGICAL_EDGE + cellY]
                            |= 0x0f << (blockX * subdivision);
                }
            }
        }
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / subdivision;
        byte[] optical = new byte[blockEdge * blockEdge * blockEdge];
        byte fluid = (byte) VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.WATER
        ).packedUnsignedByte();
        byte wall = (byte) VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.OPAQUE
        ).packedUnsignedByte();
        for (int blockX = 0; blockX < 4; blockX++) {
            optical[blockX] = fluid;
        }
        optical[4] = wall;
        return new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 1,
                VOXEL_WORLD.generation(), 11L, occupancy, optical
        );
    }

    private static VoxelBrickPatch emptyCachePatch() {
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / 4;
        return new VoxelBrickPatch(
                0, 1, 0, 0, 1, 0, 0, 2,
                VOXEL_WORLD.generation(), 11L,
                new int[VoxelBrickPatch.OCCUPANCY_WORDS],
                new byte[blockEdge * blockEdge * blockEdge]
        );
    }

    private static int cacheEntryOffset(
            final int face,
            final int x,
            final int y,
            final int layer
    ) {
        long texel = ((long) face * LocalVoxelShadowLayout.CACHE_FACE_EDGE
                * LocalVoxelShadowLayout.CACHE_FACE_EDGE)
                + (long) y * LocalVoxelShadowLayout.CACHE_FACE_EDGE + x;
        return Math.toIntExact(
                (texel * LocalVoxelShadowLayout.CACHE_LAYER_COUNT + layer)
                        * LocalVoxelShadowLayout.CACHE_HIT_STRIDE_BYTES
        );
    }

    private static VoxelShadowTraversal.LevelData level(
            final int subdivision,
            final VoxelMaterialClass material
    ) {
        VoxelClipmapSnapshot snapshot = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, subdivision, 32, 0, 0, 0, 1))
        );
        int cells = 32 * 32 * 32;
        int[] occupancy = new int[cells / 32];
        occupancy[0] = 1;
        int blockEdge = 32 / subdivision;
        byte[] optical = new byte[blockEdge * blockEdge * blockEdge];
        Arrays.fill(optical, (byte) VoxelMaterialDescriptor.defaults(material).packedUnsignedByte());
        return new VoxelShadowTraversal.LevelData(
                snapshot, 0, occupancy, optical,
                new VoxelShadowTraversal.BrickMetadata[]{
                        new VoxelShadowTraversal.BrickMetadata(0, 0, 0, 1)
                }
        );
    }

    private static EntityShadowProxy proxy(
            final long id,
            final double x,
            final float halfExtent
    ) {
        return new EntityShadowProxy(id, x, 0.0, 0.0, halfExtent, halfExtent, halfExtent);
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
