package com.metallum.client.lighting;

import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelMaterialClass;
import com.metallum.client.voxel.VoxelMaterialDescriptor;
import com.metallum.client.voxel.VoxelShadowTraversal;
import com.metallum.client.voxel.VoxelWorldToken;

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
                        && LocalVoxelShadowLayout.PROXY_STRIDE_BYTES == 32,
                "L6 compile or upload caps changed");
        require(balanced.paramsRingBytes() == 768L && balanced.proxyRingBytes() == 1_536L
                        && balanced.totalDedicatedBytes() == 2_304L,
                "L6 ring bytes changed");
    }

    private static void testExactBindingAbi() {
        require(VoxelShadowBindingAbi.PROXY_BUFFER_SLOT == 15
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
        require(VoxelShadowBindingAbi.ownsFragmentSlot(15)
                        && VoxelShadowBindingAbi.ownsFragmentSlot(25)
                        && !VoxelShadowBindingAbi.ownsFragmentSlot(14)
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
