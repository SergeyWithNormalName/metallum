package com.metallum.client.lighting;

import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        testVariableAtlasCubePages();
        testRelevantGeometrySurvivesClipmapScroll();
        testStableCubeLevelSelection();
        testCachedCubeReceiverSurfaceRecognition();
        testCachedCubeOverflowLayerTracksLatestHit();
        testCompactedEmitterDoesNotShadowItself();
        testLongDiagonalCoarsensBeforeStepCap();
        testTraversalFailsOpen();
        System.out.println("L6 voxel-shadow CPU contracts passed");
    }

    private static void testPresetCapsAndAccounting() {
        LocalVoxelShadowLayout.Budget performance = LocalVoxelShadowLayout.forPreset(LightingPreset.PERFORMANCE);
        LocalVoxelShadowLayout.Budget balanced = LocalVoxelShadowLayout.forPreset(LightingPreset.BALANCED);
        LocalVoxelShadowLayout.Budget ultra = LocalVoxelShadowLayout.forPreset(LightingPreset.ULTRA);
        require(performance.shadowedLocalLights() == 1
                        && performance.maxShadowDescriptors() == 4_096
                        && performance.maxSteps() == 32
                        && performance.maxEntityProxies() == 8,
                "Performance L6 atlas budget changed");
        require(balanced.shadowedLocalLights() == 2
                        && balanced.maxShadowDescriptors() == 4_096
                        && balanced.maxSteps() == 64
                        && balanced.maxEntityProxies() == 16,
                "Balanced L6 atlas budget changed");
        require(ultra.shadowedLocalLights() == 2
                        && ultra.maxShadowDescriptors() == 4_096
                        && ultra.maxSteps() == 80
                        && ultra.maxEntityProxies() == 24,
                "Ultra L6 atlas budget changed");
        require(LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS == 2
                        && LocalVoxelShadowLayout.MAX_SHADOW_DESCRIPTORS == 4_096
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
                        && balanced.shadowReferenceRingBytes() == 196_608L
                        && balanced.visibilityCacheBytes() == 67_108_864L
                        && balanced.totalDedicatedBytes() == 67_307_776L
                        && performance.visibilityCacheBytes() == 33_554_432L
                        && ultra.visibilityCacheBytes() == 134_217_728L,
                "L6 resident atlas/ring bytes changed");
    }

    private static void testExactBindingAbi() {
        require(VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT == 14
                        && VoxelShadowBindingAbi.PROXY_BUFFER_SLOT == 15
                        && VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT == 16
                        && Arrays.equals(VoxelShadowBindingAbi.occupancyTextureSlots(), new int[]{17, 18, 19})
                        && Arrays.equals(VoxelShadowBindingAbi.opticalTextureSlots(), new int[]{20, 21, 22})
                        && Arrays.equals(VoxelShadowBindingAbi.metadataBufferSlots(), new int[]{23, 24, 25})
                        && VoxelShadowBindingAbi.SHADOW_REF_BUFFER_SLOT == 13,
                "L6 fragment binding slots changed");
        require(VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_STRIDE_BYTES
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STRIDE_BYTES
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_STATE_OFFSET
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_OFFSET
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_ATLAS_OFFSET_LO_OFFSET
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_ATLAS_OFFSET_LO_OFFSET
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_ATLAS_OFFSET_HI_OFFSET
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_ATLAS_OFFSET_HI_OFFSET
                        && VoxelShadowBindingAbi.SHADOW_REF_DESCRIPTOR_PAGE_EDGE_OFFSET
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_PAGE_EDGE_OFFSET
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_APPROXIMATE_DIRECT
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_APPROXIMATE_DIRECT
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_READY
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_READY
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_STALE_RETAINED
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_STALE_RETAINED
                        && VoxelShadowBindingAbi.SHADOW_REF_STATE_BUILDING
                        == LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_BUILDING
                        && LocalVoxelShadowAtlasLayout.DESCRIPTOR_STATE_FAIL_CLOSED == 4,
                "L6 resident atlas descriptor ABI diverged from its layout");
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
                        && VoxelShadowBindingAbi.ownsFragmentSlot(13)
                        && !VoxelShadowBindingAbi.ownsFragmentSlot(26)
                        && !VoxelShadowBindingAbi.ownsFragmentSlot(32),
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
        mirror.acknowledge(cacheCoverageBatch(
                1L, cacheWallPatch(1, VoxelMaterialClass.OPAQUE), 0, 2
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
        mirror.acknowledge(cacheCoverageBatch(2L, restampedOpaque, 0, 2));
        VoxelShadowCacheMirror.Snapshot restampedSnapshot = mirror.snapshot(clipmap);
        require(VoxelShadowCacheBuilder.relevantGeometryEquals(
                        opaqueSnapshot, restampedSnapshot, List.of(light)),
                "identical relevant geometry invalidated the cached point shadow");

        VoxelBrickPatch glassPatch = cacheWallPatch(3, VoxelMaterialClass.GLASS);
        mirror.acknowledge(cacheCoverageBatch(3L, glassPatch, 1, 2));
        VoxelShadowCacheMirror.Snapshot incrementalSnapshot = mirror.snapshot(clipmap);
        require(incrementalSnapshot != null && incrementalSnapshot.current()
                        && incrementalSnapshot.revision() > restampedSnapshot.revision()
                        && !incrementalSnapshot.bricks().equals(restampedSnapshot.bricks()),
                "same-topology acknowledged L5 batch did not publish to L6 immediately");
        require(mirror.snapshot(shiftedClipmap) == null,
                "L6 exposed incremental cache data across a shifted toroidal window");
        mirror.acknowledge(cacheCoverageBatch(4L, glassPatch, 0, 2));
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

    private static void testVariableAtlasCubePages() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );
        mirror.acknowledge(cacheCoverageBatch(
                71L, cacheWallPatch(71, VoxelMaterialClass.OPAQUE), 0, 2
        ));
        VoxelShadowCacheMirror.Snapshot snapshot = mirror.snapshot(clipmap);
        require(snapshot != null, "variable-page cache fixture did not publish");
        AdvancedLight light = new AdvancedLight(
                71L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        for (int edge : LocalVoxelShadowAtlasLayout.PAGE_EDGES) {
            VoxelShadowCacheBuilder.PageResult page = VoxelShadowCacheBuilder.buildPage(
                    snapshot, light, edge, 96
            );
            require(page.edge() == edge
                            && page.payload().length
                            == LocalVoxelShadowAtlasLayout.pagePayloadBytes(edge)
                            && page.totalRays() == 6 * edge * edge
                            && page.raysWithHits() > 0
                            && page.cacheLevel() == 0
                            && page.complete(),
                    "variable resident page did not preserve edge, bytes, rays or stable LOD");
            ByteBuffer payload = ByteBuffer.wrap(page.payload()).order(ByteOrder.nativeOrder());
            int centre = edge / 2 - 1;
            int positiveX = pageEntryOffset(0, centre, centre, 0, edge);
            int negativeX = pageEntryOffset(1, centre, centre, 0, edge);
            require(Float.isFinite(payload.getFloat(positiveX))
                            && payload.getFloat(positiveX + Float.BYTES) == 0.0f,
                    "variable page lost +X opaque wall cube mapping");
            require(Float.isInfinite(payload.getFloat(negativeX))
                            && payload.getFloat(negativeX + Float.BYTES) == 1.0f,
                    "variable page lost -X empty-ray cube mapping");
        }
        VoxelShadowCacheBuilder.Result legacy = VoxelShadowCacheBuilder.build(
                snapshot, List.of(light), 1, 96
        );
        VoxelShadowCacheBuilder.PageResult page64 = VoxelShadowCacheBuilder.buildPage(
                snapshot, light, 64, 96
        );
        require(Arrays.equals(legacy.payload(), page64.payload()),
                "legacy fixed cache no longer exactly matches its 64-edge resident page");

        Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> missingBrick =
                new HashMap<>(snapshot.bricks());
        missingBrick.remove(new VoxelShadowCacheMirror.Key(0, 1, 1, 1));
        VoxelShadowCacheBuilder.PageResult incomplete = VoxelShadowCacheBuilder.buildPage(
                new VoxelShadowCacheMirror.Snapshot(
                        clipmap, snapshot.revision() + 1L, missingBrick, true
                ),
                light,
                8,
                96
        );
        require(!incomplete.complete() && incomplete.cacheLevel() == 0,
                "resident page hid a missing relevant L5 brick behind visible payload");

        AdvancedLight uncovered = new AdvancedLight(
                72L, 1L, LightSourceKind.BLOCK,
                100.5, 100.5, 100.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        VoxelShadowCacheBuilder.PageResult uncoveredPage = VoxelShadowCacheBuilder.buildPage(
                snapshot, uncovered, 8, 96
        );
        require(!uncoveredPage.complete() && uncoveredPage.cacheLevel() == -1,
                "uncovered resident page could be published as a visible READY cache");
        mirror.reset();
    }

    private static void testRelevantGeometrySurvivesClipmapScroll() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        VoxelClipmapSnapshot beforeScroll = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 128, -2, -2, -2, 4))
        );
        VoxelClipmapSnapshot afterScroll = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 128, -1, -2, -2, 4))
        );
        AdvancedLight light = new AdvancedLight(
                81L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        mirror.acknowledge(cacheCoverageBatch(
                81L, cacheWallPatch(81, VoxelMaterialClass.OPAQUE), 0, 4
        ));
        VoxelShadowCacheMirror.Snapshot before = mirror.snapshot(beforeScroll);
        require(before != null, "pre-scroll relevant-geometry fixture did not publish");
        VoxelShadowCacheMirror.Snapshot remapped = new VoxelShadowCacheMirror.Snapshot(
                afterScroll, before.revision() + 1L, new HashMap<>(before.bricks()), true
        );
        require(VoxelShadowCacheBuilder.relevantGeometryEquals(
                        before, remapped, List.of(light)),
                "camera-driven clipmap origin shift invalidated identical relevant geometry");

        Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> coarseBricks =
                new HashMap<>();
        for (Map.Entry<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> entry
                : before.bricks().entrySet()) {
            VoxelShadowCacheMirror.Key key = entry.getKey();
            coarseBricks.put(
                    new VoxelShadowCacheMirror.Key(
                            1, key.destinationX(), key.destinationY(), key.destinationZ()
                    ),
                    entry.getValue()
            );
        }
        VoxelClipmapSnapshot coarseBeforeScroll = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 128, 8, 8, 8, 4),
                        new VoxelClipmapSnapshot.Level(1, 2, 128, -2, -2, -2, 4)
                )
        );
        VoxelClipmapSnapshot coarseAfterScroll = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 128, 9, 8, 8, 4),
                        new VoxelClipmapSnapshot.Level(1, 2, 128, -1, -2, -2, 4)
                )
        );
        VoxelShadowCacheMirror.Snapshot coarseBefore = new VoxelShadowCacheMirror.Snapshot(
                coarseBeforeScroll, before.revision() + 10L, coarseBricks, true
        );
        VoxelShadowCacheMirror.Snapshot coarseAfter = new VoxelShadowCacheMirror.Snapshot(
                coarseAfterScroll, before.revision() + 11L, coarseBricks, true
        );
        require(VoxelShadowCacheBuilder.relevantGeometryEquals(
                        coarseBefore, coarseAfter, light, 1),
                "resident coarse page was invalidated by an uncovered fine-level scroll");
        require(!VoxelShadowCacheBuilder.relevantGeometryEquals(
                        coarseBefore, coarseAfter, light, 0),
                "resident page validation accepted an uncovered traced level");

        Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> missing =
                new HashMap<>(before.bricks());
        missing.remove(new VoxelShadowCacheMirror.Key(0, 3, 3, 3));
        VoxelShadowCacheMirror.Snapshot missingCoverage =
                new VoxelShadowCacheMirror.Snapshot(
                        afterScroll, before.revision() + 2L, missing, true
                );
        require(!VoxelShadowCacheBuilder.relevantGeometryEquals(
                        before, missingCoverage, List.of(light)),
                "origin-shift reuse accepted a missing relevant logical brick");
        VoxelShadowCacheMirror.Snapshot sameMissingCoverage =
                new VoxelShadowCacheMirror.Snapshot(
                        afterScroll, missingCoverage.revision() + 1L,
                        new HashMap<>(missing), true
                );
        require(!VoxelShadowCacheBuilder.relevantGeometryEquals(
                        missingCoverage, sameMissingCoverage, light, 0)
                        && VoxelShadowCacheBuilder.relevantRetryGeometryEquals(
                        missingCoverage, sameMissingCoverage, light, 0)
                        && !VoxelShadowCacheBuilder.relevantRetryGeometryEquals(
                        missingCoverage, before, light, 0),
                "unchanged missing L5 data bypassed retry backoff or hid newly loaded data");

        VoxelClipmapSnapshot changedTopology = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 2, 128, -1, -2, -2, 4))
        );
        require(!VoxelShadowCacheBuilder.relevantGeometryEquals(
                        before,
                        new VoxelShadowCacheMirror.Snapshot(
                                changedTopology, before.revision() + 3L,
                                before.bricks(), true
                        ),
                        List.of(light)),
                "origin-shift reuse accepted changed clipmap topology");

        mirror.acknowledge(cacheCoverageBatch(
                82L, cacheWallPatch(82, VoxelMaterialClass.GLASS), 0, 4
        ));
        VoxelShadowCacheMirror.Snapshot changed = mirror.snapshot(beforeScroll);
        require(changed != null && !VoxelShadowCacheBuilder.relevantGeometryEquals(
                        before,
                        new VoxelShadowCacheMirror.Snapshot(
                                afterScroll, changed.revision(), changed.bricks(), true
                        ),
                        List.of(light)),
                "origin-shift reuse accepted changed relevant brick geometry");
        mirror.reset();
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

    /**
     * A receiver on an open floor must not be shadowed merely because a neighbouring cubemap
     * centre ray reaches that same floor at a shorter distance.
     */
    private static void testCachedCubeReceiverSurfaceRecognition() {
        Vector lightToReceiver = new Vector(8.0, -0.5, 0.0);
        Vector receiverNormal = new Vector(0.0, 1.0, 0.0);
        CubeSample sample = cubeSample(lightToReceiver);
        require(sample.face() == 0 && sample.x() == 32 && sample.y() == 34,
                "receiver-plane regression no longer exercises the adjacent +X cube texel");

        double cacheFloorEntryDistance = receiverPlaneDistance(
                lightToReceiver, receiverNormal, sample.direction()
        );
        double receiverDistance = length(lightToReceiver);
        require(receiverDistance > cacheFloorEntryDistance + 0.08,
                "receiver-plane regression no longer reproduces the former fixed-bias acne");
        require(receiverSurfaceHit(
                        cacheFloorEntryDistance,
                        lightToReceiver,
                        receiverNormal,
                        sample.direction()
                ),
                "cache centre ray did not recognize the receiver's own floor plane");
        require(receiverSurfaceHit(
                        cacheFloorEntryDistance + 1.0,
                        lightToReceiver,
                        receiverNormal,
                        sample.direction()
                ),
                "cache hit behind the receiver plane incorrectly shadowed the receiver");
        require(!receiverSurfaceHit(
                        cacheFloorEntryDistance - 1.0,
                        lightToReceiver,
                        receiverNormal,
                        sample.direction()
                ),
                "a blocker materially before the receiver plane was treated as self-shadow acne");
    }

    private static void testCachedCubeOverflowLayerTracksLatestHit() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );
        mirror.acknowledge(cacheBatch(61L, cacheTransparentLinePatch(), 0));
        VoxelShadowCacheMirror.Snapshot snapshot = mirror.snapshot(clipmap);
        require(snapshot != null, "overflow-layer cache fixture did not publish");
        AdvancedLight light = new AdvancedLight(
                61L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 7.0f,
                1.0f, 1.0f, 1.0f, 1.0f, 1
        );
        VoxelShadowCacheBuilder.Result result = VoxelShadowCacheBuilder.build(
                snapshot, List.of(light), 1, 96
        );
        ByteBuffer payload = ByteBuffer.wrap(result.payload()).order(ByteOrder.nativeOrder());
        int fourthLayer = cacheEntryOffset(0, 31, 31, 3);
        float hitDistance = payload.getFloat(fourthLayer);
        float visibility = payload.getFloat(fourthLayer + Float.BYTES);
        float water = VoxelMaterialDescriptor.defaults(VoxelMaterialClass.WATER).transmittance();
        require(hitDistance > 5.0f,
                "overflowed cached layer retained the fourth hit distance instead of the latest one");
        require(Math.abs(visibility - (float) Math.pow(water, 6.0)) <= 1.0e-5f,
                "overflowed cached layer lost accumulated transparent visibility");
        mirror.reset();
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

    private static VoxelUploadBatch cacheCoverageBatch(
            final long batchId,
            final VoxelBrickPatch centralPatch,
            final int queueRemaining,
            final int brickDimension
    ) {
        List<VoxelBrickPatch> patches = new ArrayList<>(8);
        int stamp = Math.toIntExact(batchId * 16L);
        for (int logicalZ = -1; logicalZ <= 0; logicalZ++) {
            for (int logicalY = -1; logicalY <= 0; logicalY++) {
                for (int logicalX = -1; logicalX <= 0; logicalX++) {
                    if (logicalX == 0 && logicalY == 0 && logicalZ == 0) {
                        patches.add(centralPatch);
                    } else {
                        patches.add(emptyCachePatchAt(
                                logicalX, logicalY, logicalZ,
                                brickDimension, ++stamp
                        ));
                    }
                }
            }
        }
        return new VoxelUploadBatch(
                batchId, VOXEL_WORLD, 11L, batchId,
                patches, queueRemaining, 0L, 0, 0, 0L, 0L
        );
    }

    private static VoxelBrickPatch emptyCachePatchAt(
            final int logicalX,
            final int logicalY,
            final int logicalZ,
            final int brickDimension,
            final int stamp
    ) {
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / 4;
        return new VoxelBrickPatch(
                0,
                Math.floorMod(logicalX, brickDimension),
                Math.floorMod(logicalY, brickDimension),
                Math.floorMod(logicalZ, brickDimension),
                logicalX, logicalY, logicalZ, stamp,
                VOXEL_WORLD.generation(), 11L,
                new int[VoxelBrickPatch.OCCUPANCY_WORDS],
                new byte[blockEdge * blockEdge * blockEdge]
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

    private static VoxelBrickPatch cacheTransparentLinePatch() {
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        int subdivision = 4;
        int blockMask = 0;
        for (int blockX = 1; blockX <= 6; blockX++) {
            blockMask |= 0x0f << (blockX * subdivision);
        }
        for (int cellZ = 0; cellZ < VoxelBrickPatch.LOGICAL_EDGE; cellZ++) {
            for (int cellY = 0; cellY < VoxelBrickPatch.LOGICAL_EDGE; cellY++) {
                occupancy[cellZ * VoxelBrickPatch.LOGICAL_EDGE + cellY] = blockMask;
            }
        }
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / subdivision;
        byte[] optical = new byte[blockEdge * blockEdge * blockEdge];
        byte water = (byte) VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.WATER
        ).packedUnsignedByte();
        for (int blockZ = 0; blockZ < blockEdge; blockZ++) {
            for (int blockY = 0; blockY < blockEdge; blockY++) {
                for (int blockX = 1; blockX <= 6; blockX++) {
                    optical[(blockZ * blockEdge + blockY) * blockEdge + blockX] = water;
                }
            }
        }
        return new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 61,
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

    private static int pageEntryOffset(
            final int face,
            final int x,
            final int y,
            final int layer,
            final int edge
    ) {
        long texel = (long) face * edge * edge + (long) y * edge + x;
        return Math.toIntExact(
                (texel * LocalVoxelShadowAtlasLayout.LAYER_COUNT + layer)
                        * LocalVoxelShadowAtlasLayout.HIT_STRIDE_BYTES
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

    private static CubeSample cubeSample(final Vector direction) {
        double major = Math.max(Math.abs(direction.x()),
                Math.max(Math.abs(direction.y()), Math.abs(direction.z())));
        require(major > 0.0, "cube-sample direction was zero");
        int face;
        double u;
        double v;
        if (Math.abs(direction.x()) >= Math.abs(direction.y())
                && Math.abs(direction.x()) >= Math.abs(direction.z())) {
            if (direction.x() >= 0.0) {
                face = 0;
                u = -direction.z() / major;
                v = -direction.y() / major;
            } else {
                face = 1;
                u = direction.z() / major;
                v = -direction.y() / major;
            }
        } else if (Math.abs(direction.y()) >= Math.abs(direction.z())) {
            if (direction.y() >= 0.0) {
                face = 2;
                u = direction.x() / major;
                v = direction.z() / major;
            } else {
                face = 3;
                u = direction.x() / major;
                v = -direction.z() / major;
            }
        } else if (direction.z() >= 0.0) {
            face = 4;
            u = direction.x() / major;
            v = -direction.y() / major;
        } else {
            face = 5;
            u = -direction.x() / major;
            v = -direction.y() / major;
        }
        int edge = LocalVoxelShadowLayout.CACHE_FACE_EDGE;
        int x = Math.max(0, Math.min(edge - 1, (int) Math.floor((u * 0.5 + 0.5) * edge)));
        int y = Math.max(0, Math.min(edge - 1, (int) Math.floor((v * 0.5 + 0.5) * edge)));
        double centreU = 2.0 * (x + 0.5) / edge - 1.0;
        double centreV = 2.0 * (y + 0.5) / edge - 1.0;
        Vector raw = switch (face) {
            case 0 -> new Vector(1.0, -centreV, -centreU);
            case 1 -> new Vector(-1.0, -centreV, centreU);
            case 2 -> new Vector(centreU, 1.0, centreV);
            case 3 -> new Vector(centreU, -1.0, -centreV);
            case 4 -> new Vector(centreU, -centreV, 1.0);
            case 5 -> new Vector(-centreU, -centreV, -1.0);
            default -> throw new IllegalStateException("invalid cube face");
        };
        double length = length(raw);
        return new CubeSample(face, x, y, new Vector(
                raw.x() / length, raw.y() / length, raw.z() / length
        ));
    }

    private static boolean receiverSurfaceHit(
            final double hitDistance,
            final Vector lightToReceiver,
            final Vector receiverNormal,
            final Vector cacheDirection
    ) {
        double planeDistance = receiverPlaneDistance(
                lightToReceiver, receiverNormal, cacheDirection
        );
        return Double.isFinite(planeDistance) && planeDistance > 0.0
                && hitDistance + 0.08 >= planeDistance;
    }

    private static double receiverPlaneDistance(
            final Vector lightToReceiver,
            final Vector receiverNormal,
            final Vector cacheDirection
    ) {
        double denominator = dot(receiverNormal, cacheDirection);
        return Math.abs(denominator) > 1.0e-6
                ? dot(receiverNormal, lightToReceiver) / denominator
                : Double.NaN;
    }

    private static double dot(final Vector left, final Vector right) {
        return left.x() * right.x() + left.y() * right.y() + left.z() * right.z();
    }

    private static double length(final Vector value) {
        return Math.sqrt(dot(value, value));
    }

    private record Vector(double x, double y, double z) {
    }

    private record CubeSample(int face, int x, int y, Vector direction) {
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
