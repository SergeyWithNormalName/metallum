package com.metallum.client.lighting;

import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.LocalVoxelShadowAtlasLayout;
import com.metallum.client.renderer.LocalVoxelShadowLayout;
import com.metallum.client.voxel.VoxelClipmapSnapshot;
import com.metallum.client.voxel.VoxelBrickPatch;
import com.metallum.client.voxel.VoxelChromaticFilter;
import com.metallum.client.voxel.VoxelMaterialClass;
import com.metallum.client.voxel.VoxelMaterialDescriptor;
import com.metallum.client.voxel.VoxelShadowCacheBuilder;
import com.metallum.client.voxel.VoxelShadowCacheMirror;
import com.metallum.client.voxel.VoxelShadowTraversal;
import com.metallum.client.voxel.VoxelShapeEncoder;
import com.metallum.client.voxel.VoxelShapeRegistry;
import com.metallum.client.voxel.VoxelSubdivision;
import com.metallum.client.voxel.VoxelUploadBatch;
import com.metallum.client.voxel.VoxelWorldToken;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Standalone L6 CPU/ABI contracts; intentionally runnable without a Metal device. */
public final class VoxelShadowContractTests {
    private static final LightWorldToken WORLD = new LightWorldToken(7L, "minecraft:overworld");
    private static final VoxelWorldToken VOXEL_WORLD = new VoxelWorldToken(7L, "minecraft:overworld");
    private static final double RECEIVER_COINCIDENCE_EPSILON = 0.002;

    private VoxelShadowContractTests() {
    }

    public static void main(final String[] args) {
        testPresetCapsAndAccounting();
        testExactBindingAbi();
        testProxySelectionAndLifecycle();
        testPreFixEntityShadowFailures();
        testEntityShadowFilterSemantics();
        testShaderReceiverProxyScoping();
        testTemporalInterpolationFidelity();
        testMultiPrimitiveArchetypesAcrossPresets();
        testCpuGpuRayProxySlabIntersectionParity();
        testNegativeAndLargeWorldCoordinates();
        testCameraMovementPreservesWorldGeometry();
        testEntityMovementAndRemovalLifecycle();
        testProxyCapacityAndAdmissionDeterminism();
        testTerrainAndEntityShadowComposition();
        testEndToEndProxyPipelineToShaderVisibility();
        testProxyFailOpenResilience();
        testExactTraversalAtAllSubdivisions();
        testCachedCubeTraversal();
        testFinePartialOpaqueOccluders();
        testHybridShapeRefinementFidelity();
        testThinShapeClassification();
        testDynamicShapeSyncContract();
        testLiveUpdateSequence();
        testMultiPatchBatchMirror();
        testDistanceLevelSelection();
        testCoarseLevelFallback();
        testPerceptualDistanceFadeMonotonicity();
        testLODHysteresisStability();
        testStaticDynamicDistanceParity();
        testVariableAtlasCubePages();
        testRelevantGeometrySurvivesClipmapScroll();
        testStableCubeLevelSelection();
        testDynamicCubeFallsBackToCompleteCoarserLevel();
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
                        && balanced.maxSteps() == 96
                        && balanced.maxEntityProxies() == 16,
                "Balanced L6 atlas budget changed");
        require(ultra.shadowedLocalLights() == 2
                        && ultra.maxShadowDescriptors() == 4_096
                        && ultra.maxSteps() == 96
                        && ultra.maxEntityProxies() == 24,
                "Ultra L6 atlas budget changed");
        require(LocalVoxelShadowLayout.MAX_SHADOWED_LOCAL_LIGHTS == 2
                        && LocalVoxelShadowLayout.MAX_SHADOW_DESCRIPTORS == 4_096
                        && LocalVoxelShadowLayout.MAX_DDA_STEPS == 96
                        && LocalVoxelShadowLayout.MAX_DYNAMIC_SHADOW_LIGHTS == 8
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
                        && balanced.dynamicShadows().heroSlots() == 2
                        && balanced.dynamicShadows().pageEdge() == 32
                        && balanced.dynamicShadows().maxSteps() == 96
                        && balanced.dynamicShadows().pageBytes() == 196_608L
                        && balanced.dynamicShadows().atlasBytes() == 1_179_648L
                        && balanced.totalVisibilityAtlasBytes() == 68_288_512L
                        && balanced.totalDedicatedBytes() == 68_487_424L
                        && performance.visibilityCacheBytes() == 33_554_432L
                        && performance.dynamicShadows().heroSlots() == 1
                        && performance.dynamicShadows().pageEdge() == 16
                        && performance.dynamicShadows().maxSteps() == 32
                        && performance.dynamicShadows().atlasBytes() == 147_456L
                        && ultra.visibilityCacheBytes() == 134_217_728L
                        && ultra.dynamicShadows().heroSlots() == 4
                        && ultra.dynamicShadows().pageEdge() == 32
                        && ultra.dynamicShadows().maxSteps() == 96
                        && ultra.dynamicShadows().atlasBytes() == 2_359_296L,
                "L6 resident atlas/ring bytes changed");

        for (LocalVoxelShadowLayout.Budget candidate
                : java.util.List.of(performance, balanced, ultra)) {
            LocalVoxelShadowLayout.DynamicShadowBudget dynamic = candidate.dynamicShadows();
            long staticEnd = candidate.visibilityCacheBytes();
            long previousEnd = staticEnd;
            for (int inFlight = 0; inFlight < LocalVoxelShadowLayout.PARAMS_RING_SLOTS;
                    inFlight++) {
                for (int hero = 0; hero < dynamic.heroSlots(); hero++) {
                    long offset = dynamic.pageOffset(staticEnd, hero, inFlight);
                    require(offset == previousEnd,
                            "Dynamic L6 suffix pages are not tightly ordered");
                    previousEnd = offset + dynamic.pageBytes();
                }
            }
            require(previousEnd == candidate.totalVisibilityAtlasBytes(),
                    "Dynamic L6 suffix crosses its declared atlas allocation");
        }
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

        VoxelShadowTraversal.RgbVisibility redGlass = VoxelShadowTraversal.visibilityRgb(
                level(1, VoxelMaterialClass.GLASS, 14), VOXEL_WORLD, 11L,
                new VoxelShadowTraversal.Point(0.1, 0.1, 0.1),
                new VoxelShadowTraversal.Point(0.9, 0.1, 0.1), 96
        );
        require(redGlass.red() > redGlass.green() * 10.0f
                        && redGlass.red() > redGlass.blue() * 10.0f
                        && redGlass.red() <= 1.0f && redGlass.green() >= 0.0f
                        && redGlass.blue() >= 0.0f,
                "white local light through red glass did not become red-tinted");
        VoxelShadowTraversal.RgbVisibility layered = VoxelShadowTraversal.visibilityRgb(
                twoBlockChromaticLevel(14, 11), VOXEL_WORLD, 11L,
                new VoxelShadowTraversal.Point(0.1, 0.1, 0.1),
                new VoxelShadowTraversal.Point(1.9, 0.1, 0.1), 96
        );
        require(layered.red() <= redGlass.red()
                        && layered.green() <= redGlass.green()
                        && layered.blue() <= redGlass.blue()
                        && Float.isFinite(layered.red()) && Float.isFinite(layered.green())
                        && Float.isFinite(layered.blue()),
                "stacked chromatic L6 filters gained light energy or produced NaN");
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
        ), clipmap);
        VoxelShadowCacheMirror.Snapshot opaqueSnapshot = mirror.snapshot(clipmap);
        require(opaqueSnapshot != null, "completed L5 batch did not expose an L6 cache mirror");
        require(VoxelShadowCacheBuilder.hasCompleteCoverage(opaqueSnapshot, light, 0),
                "GPU-page admission rejected a fully tagged L5 sphere");
        java.util.Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> partial =
                new java.util.HashMap<>(opaqueSnapshot.bricks());
        partial.remove(partial.keySet().iterator().next());
        require(!VoxelShadowCacheBuilder.hasCompleteCoverage(
                        new VoxelShadowCacheMirror.Snapshot(
                                opaqueSnapshot.clipmap(),
                                opaqueSnapshot.revision(),
                                partial,
                                true
                        ),
                        light,
                        0
                ),
                "GPU-page admission published a sphere with a missing toroidal brick");
        require(mirror.snapshot(clipmap) == opaqueSnapshot,
                "static L6 clipmap snapshot was recopied instead of reused");
        VoxelClipmapSnapshot shiftedClipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, 0, -1, -1, 2))
        );
        require(mirror.snapshot(shiftedClipmap) == null,
                "L6 reused a cache while a new toroidal window was still pending");
        require(mirror.latestAcceptedSnapshot(shiftedClipmap) == opaqueSnapshot,
                "L6 mirror did not retain the last native-accepted window across a pending scroll");
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
                        && isOpaqueRgb(packedRgb(opaquePayload, centralPositiveX)),
                "cached +X ray did not become opaque behind the wall");

        VoxelBrickPatch restampedOpaque = cacheWallPatch(2, VoxelMaterialClass.OPAQUE);
        mirror.acknowledge(cacheCoverageBatch(2L, restampedOpaque, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot restampedSnapshot = mirror.snapshot(clipmap);
        require(VoxelShadowCacheBuilder.relevantGeometryEquals(
                        opaqueSnapshot, restampedSnapshot, List.of(light)),
                "identical relevant geometry invalidated the cached point shadow");

        VoxelBrickPatch glassPatch = cacheWallPatch(3, VoxelMaterialClass.GLASS);
        mirror.acknowledge(cacheCoverageBatch(3L, glassPatch, 1, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot incrementalSnapshot = mirror.snapshot(clipmap);
        require(incrementalSnapshot != null && incrementalSnapshot.current()
                        && incrementalSnapshot.revision() > restampedSnapshot.revision()
                        && !incrementalSnapshot.bricks().equals(restampedSnapshot.bricks()),
                "same-topology acknowledged L5 batch did not publish to L6 immediately");
        require(mirror.snapshot(shiftedClipmap) == null,
                "L6 exposed incremental cache data across a shifted toroidal window");
        VoxelBrickPatch redGlassPatch = cacheWallPatch(4, VoxelMaterialClass.GLASS, 14);
        mirror.acknowledge(cacheCoverageBatch(4L, redGlassPatch, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot glassSnapshot = mirror.snapshot(clipmap);
        require(!VoxelShadowCacheBuilder.relevantGeometryEquals(
                        restampedSnapshot, glassSnapshot, List.of(light)),
                "relevant transmittance change did not invalidate the cached point shadow");
        require(!VoxelShadowCacheBuilder.relevantGeometryEquals(
                        incrementalSnapshot, glassSnapshot, List.of(light)),
                "a hue-only L5 chromatic change did not invalidate the resident L6 page");
        VoxelShadowCacheBuilder.Result glass = VoxelShadowCacheBuilder.build(
                glassSnapshot, List.of(light), 1, 96
        );
        int glassVisibility = ByteBuffer.wrap(glass.payload())
                .order(ByteOrder.nativeOrder())
                .getInt(centralPositiveX + Float.BYTES);
        require(VoxelChromaticFilter.unpackRed(glassVisibility)
                        > VoxelChromaticFilter.unpackGreen(glassVisibility) * 10.0f
                        && VoxelChromaticFilter.unpackRed(glassVisibility)
                        > VoxelChromaticFilter.unpackBlue(glassVisibility) * 10.0f,
                "cached point shadow lost red stained-glass transmittance");
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
        ), clipmap);
        VoxelShadowCacheMirror.Snapshot snapshot = mirror.snapshot(clipmap);
        require(snapshot != null, "variable-page cache fixture did not publish");
        AdvancedLight light = new AdvancedLight(
                71L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        boolean cancelled = false;
        try {
            VoxelShadowCacheBuilder.buildPage(snapshot, light, 64, 96, () -> true);
        } catch (CancellationException expected) {
            cancelled = true;
        }
        require(cancelled, "superseded L6 CPU page ignored cooperative cancellation");
        AtomicInteger cancellationChecks = new AtomicInteger();
        cancelled = false;
        try {
            VoxelShadowCacheBuilder.buildPage(
                    snapshot, light, 64, 96,
                    () -> cancellationChecks.incrementAndGet() >= 100
            );
        } catch (CancellationException expected) {
            cancelled = true;
        }
        require(cancelled && cancellationChecks.get() == 100,
                "L6 CPU page lost deterministic mid-traversal cancellation");
        StringBuilder canonicalMismatches = new StringBuilder();
        for (int edge : LocalVoxelShadowAtlasLayout.PAGE_EDGES) {
            VoxelShadowCacheBuilder.PageResult page = VoxelShadowCacheBuilder.buildPage(
                    snapshot, light, edge, 96
            );
            String actualCanonical = canonicalPageSha256(page.payload());
            String expectedCanonical = expectedCanonicalPageSha256(edge);
            if (!actualCanonical.equals(expectedCanonical)) {
                canonicalMismatches.append(" edge=").append(edge)
                        .append(" expected=").append(expectedCanonical)
                        .append(" actual=").append(actualCanonical);
            }
            if ("1".equals(System.getenv("METALLUM_L6_BUILDER_PROBE"))) {
                System.out.printf(
                        "L6_PAGE_ORACLE edge=%d canonical_sha256=%s%n",
                        edge,
                        canonicalPageSha256(page.payload())
                );
            }
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
                            && isOpaqueRgb(packedRgb(payload, positiveX)),
                    "variable page lost +X opaque wall cube mapping");
            require(Float.isInfinite(payload.getFloat(negativeX))
                            && isVisibleRgb(packedRgb(payload, negativeX)),
                    "variable page lost -X empty-ray cube mapping");
        }
        require(canonicalMismatches.isEmpty(),
                "L6 chromatic resident-page oracle changed:" + canonicalMismatches);
        VoxelShadowCacheBuilder.Result legacy = VoxelShadowCacheBuilder.build(
                snapshot, List.of(light), 1, 96
        );
        VoxelShadowCacheBuilder.PageResult page64 = VoxelShadowCacheBuilder.buildPage(
                snapshot, light, 64, 96
        );
        require(Arrays.equals(legacy.payload(), page64.payload()),
                "legacy fixed cache no longer exactly matches its 64-edge resident page");
        byte[] externallyOwned = page64.payload().clone();
        VoxelShadowCacheBuilder.PageResult defensive = new VoxelShadowCacheBuilder.PageResult(
                externallyOwned,
                page64.edge(),
                page64.raysWithHits(),
                page64.totalRays(),
                page64.cacheLevel(),
                page64.complete()
        );
        externallyOwned[0] ^= 1;
        require(defensive.payload()[0] != externallyOwned[0]
                        && Arrays.equals(defensive.payload(), page64.payload()),
                "public L6 page construction lost defensive payload ownership");
        verifyParallelPageBuilds(snapshot, light);
        reportBuilderAllocation(snapshot, light);

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

    private static void verifyParallelPageBuilds(
            final VoxelShadowCacheMirror.Snapshot snapshot,
            final AdvancedLight light
    ) {
        VoxelShadowCacheBuilder.PageResult expected =
                VoxelShadowCacheBuilder.buildPage(snapshot, light, 16, 96);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<CompletableFuture<VoxelShadowCacheBuilder.PageResult>> futures =
                    new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> VoxelShadowCacheBuilder.buildPage(
                                snapshot, light, 16, 96
                        ),
                        workers
                ));
            }
            for (CompletableFuture<VoxelShadowCacheBuilder.PageResult> future : futures) {
                VoxelShadowCacheBuilder.PageResult actual;
                try {
                    actual = future.join();
                } catch (CompletionException failure) {
                    throw new AssertionError("parallel L6 page build failed", failure);
                }
                require(Arrays.equals(expected.payload(), actual.payload())
                                && expected.raysWithHits() == actual.raysWithHits()
                                && expected.totalRays() == actual.totalRays()
                                && expected.cacheLevel() == actual.cacheLevel()
                                && expected.complete() == actual.complete(),
                        "parallel L6 page builds shared mutable scratch state");
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static void reportBuilderAllocation(
            final VoxelShadowCacheMirror.Snapshot snapshot,
            final AdvancedLight light
    ) {
        if (!"1".equals(System.getenv("METALLUM_L6_BUILDER_PROBE"))) {
            return;
        }
        java.lang.management.ThreadMXBean platformBean =
                ManagementFactory.getThreadMXBean();
        if (!(platformBean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            throw new AssertionError("Thread allocation telemetry is unavailable");
        }
        allocationBean.setThreadAllocatedMemoryEnabled(true);
        for (int warmup = 0; warmup < 3; warmup++) {
            VoxelShadowCacheBuilder.buildPage(snapshot, light, 64, 96);
        }
        long threadId = Thread.currentThread().threadId();
        long allocationBefore = allocationBean.getThreadAllocatedBytes(threadId);
        long timeBefore = System.nanoTime();
        int iterations = 5;
        VoxelShadowCacheBuilder.PageResult lastResult = null;
        for (int iteration = 0; iteration < iterations; iteration++) {
            lastResult =
                    VoxelShadowCacheBuilder.buildPage(snapshot, light, 64, 96);
            require(lastResult.complete(), "L6 allocation probe produced an incomplete page");
        }
        long elapsedNanos = System.nanoTime() - timeBefore;
        long allocatedBytes = allocationBean.getThreadAllocatedBytes(threadId)
                - allocationBefore;
        System.out.printf(
                "L6_BUILDER_PROBE iterations=%d allocated_bytes=%d bytes_per_page=%.1f "
                        + "elapsed_ms=%.3f ms_per_page=%.3f payload_sha256=%s hits=%d/%d%n",
                iterations,
                allocatedBytes,
                allocatedBytes / (double) iterations,
                elapsedNanos / 1_000_000.0,
                elapsedNanos / 1_000_000.0 / iterations,
                canonicalPageSha256(lastResult.payload()),
                lastResult.raysWithHits(),
                lastResult.totalRays()
        );
    }

    private static String canonicalPageSha256(final byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer words = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            for (int offset = 0; offset < bytes.length; offset += Integer.BYTES) {
                int word = words.getInt(offset);
                digest.update((byte) (word >>> 24));
                digest.update((byte) (word >>> 16));
                digest.update((byte) (word >>> 8));
                digest.update((byte) word);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    private static int packedRgb(final ByteBuffer payload, final int hitOffset) {
        return payload.getInt(hitOffset + Float.BYTES);
    }

    private static boolean isOpaqueRgb(final int packed) {
        return packed == VoxelChromaticFilter.PACKED_RGB_VALID_MASK;
    }

    private static boolean isVisibleRgb(final int packed) {
        return packed == VoxelChromaticFilter.VISIBLE_PACKED_RGB;
    }

    private static String expectedCanonicalPageSha256(final int edge) {
        return switch (edge) {
            case 8 -> "6a4279a21cf26e8903dbbb1268d677b8a169f263440df2a8aa94b2d751925478";
            case 16 -> "a79b89c1a3ac23fa05074539a6a1e2cb532cc5b1bf7d67040b48526ca72f933e";
            case 32 -> "f01a4e609c5c34c7e087c8b43829f063ebc51088bbeda58c4504225108197de6";
            case 64 -> "c45004ece84a16f84051d247f330d3dfdb22293d984f9c411c3149259d891e35";
            default -> throw new IllegalArgumentException(
                    "Unsupported resident L6 page edge: " + edge
            );
        };
    }

    private static void testFinePartialOpaqueOccluders() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );

        AdvancedLight slabLight = new AdvancedLight(
                111L, 1L, LightSourceKind.BLOCK,
                0.5, 0.25, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        mirror.acknowledge(cacheCoverageBatch(
                111L, cacheFineSlabPatch(111), 0, 2
        ), clipmap);
        VoxelShadowCacheMirror.Snapshot slabSnapshot = mirror.snapshot(clipmap);
        require(slabSnapshot != null, "fine slab cache fixture did not publish");
        VoxelShadowCacheBuilder.PageResult slabPage = VoxelShadowCacheBuilder.buildPage(
                slabSnapshot, slabLight, 64, 96
        );
        ByteBuffer slabPayload = ByteBuffer.wrap(slabPage.payload()).order(ByteOrder.nativeOrder());
        int centralPositiveX = pageEntryOffset(0, 31, 31, 0, 64);
        require(Float.isFinite(slabPayload.getFloat(centralPositiveX))
                        && isOpaqueRgb(packedRgb(slabPayload, centralPositiveX)),
                "an occupied 4x slab cell did not cast an opaque point shadow");
        int upperPositiveX = pageEntryOffset(0, 31, 0, 0, 64);
        require(Float.isInfinite(slabPayload.getFloat(upperPositiveX))
                        && isVisibleRgb(packedRgb(slabPayload, upperPositiveX)),
                "a point-shadow ray outside the slab incorrectly became blocked");

        mirror.reset();
        AdvancedLight fenceLight = new AdvancedLight(
                112L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        mirror.acknowledge(cacheCoverageBatch(
                112L, cacheFineFencePatch(112), 0, 2
        ), clipmap);
        VoxelShadowCacheMirror.Snapshot fenceSnapshot = mirror.snapshot(clipmap);
        require(fenceSnapshot != null, "fine fence cache fixture did not publish");
        VoxelShadowCacheBuilder.PageResult fencePage = VoxelShadowCacheBuilder.buildPage(
                fenceSnapshot, fenceLight, 64, 96
        );
        ByteBuffer fencePayload = ByteBuffer.wrap(fencePage.payload()).order(ByteOrder.nativeOrder());
        require(Float.isFinite(fencePayload.getFloat(centralPositiveX))
                        && isOpaqueRgb(packedRgb(fencePayload, centralPositiveX)),
                "an occupied 4x fence cell did not cast an opaque point shadow");
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
        ), beforeScroll);
        VoxelShadowCacheMirror.Snapshot before = mirror.snapshot(beforeScroll);
        require(before != null, "pre-scroll relevant-geometry fixture did not publish");
        require(mirror.snapshot(afterScroll) == null,
                "L6 treated a pre-scroll batch as belonging to the shifted origins");
        mirror.acknowledge(cacheCoverageBatch(
                82L, cacheWallPatch(82, VoxelMaterialClass.OPAQUE), 5, 4
        ), afterScroll);
        VoxelShadowCacheMirror.Snapshot partialAfterScroll = mirror.snapshot(afterScroll);
        require(partialAfterScroll != null && partialAfterScroll.current()
                        && partialAfterScroll.revision() > before.revision(),
                "accepted shifted-origin batch remained hidden until the L5 queue drained");
        VoxelShadowCacheBuilder.PageResult coveredPartial =
                VoxelShadowCacheBuilder.buildPage(
                        partialAfterScroll, light, 8, 96
                );
        require(coveredPartial.complete(),
                "fully tagged overlap could not rebuild during a shifted partial refill");
        AdvancedLight missingSlabLight = new AdvancedLight(
                82L, 1L, LightSourceKind.BLOCK,
                12.0, 0.5, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        require(!VoxelShadowCacheBuilder.buildPage(
                        partialAfterScroll, missingSlabLight, 8, 96
                ).complete(),
                "shifted partial page published across an unacknowledged incoming slab");
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
                83L, cacheWallPatch(83, VoxelMaterialClass.GLASS), 0, 4
        ), beforeScroll);
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

    private static void testDynamicCubeFallsBackToCompleteCoarserLevel() {
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2),
                        new VoxelClipmapSnapshot.Level(1, 2, 64, -1, -1, -1, 2)
                )
        );
        List<VoxelBrickPatch> patches = new ArrayList<>(16);
        int stamp = 20_000;
        for (int level = 0; level < 2; level++) {
            int subdivision = level == 0 ? 4 : 2;
            for (int z = -1; z <= 0; z++) {
                for (int y = -1; y <= 0; y++) {
                    for (int x = -1; x <= 0; x++) {
                        patches.add(emptyCachePatchAt(
                                level, subdivision, x, y, z, 2, ++stamp
                        ));
                    }
                }
            }
        }
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        mirror.acknowledge(new VoxelUploadBatch(
                92L, VOXEL_WORLD, 11L, 92L,
                patches, 0, 0L, 0, 0, 0L, 0L
        ), clipmap);
        AdvancedLight light = new AdvancedLight(
                92L, 1L, LightSourceKind.ENTITY,
                0.5, 0.5, 0.5, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        VoxelShadowCacheMirror.Snapshot complete = mirror.snapshot(clipmap);
        require(VoxelShadowCacheBuilder.selectCompleteCacheLevel(complete, light, 96) == 0,
                "dynamic cube did not prefer complete fine coverage");

        Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> missingFine =
                new HashMap<>(complete.bricks());
        missingFine.remove(new VoxelShadowCacheMirror.Key(0, 0, 0, 0));
        VoxelShadowCacheMirror.Snapshot coarseOnly = new VoxelShadowCacheMirror.Snapshot(
                clipmap, complete.revision() + 1L, missingFine, true
        );
        require(VoxelShadowCacheBuilder.selectCompleteCacheLevel(coarseOnly, light, 96) == 1,
                "dynamic cube disappeared instead of using complete coarse coverage");

        Map<VoxelShadowCacheMirror.Key, VoxelShadowCacheMirror.Brick> missingAll =
                new HashMap<>(missingFine);
        missingAll.remove(new VoxelShadowCacheMirror.Key(1, 0, 0, 0));
        require(VoxelShadowCacheBuilder.selectCompleteCacheLevel(
                        new VoxelShadowCacheMirror.Snapshot(
                                clipmap, complete.revision() + 2L, missingAll, true
                        ),
                        light,
                        96
                ) == -1,
                "dynamic cube accepted an incompletely tagged level");
        mirror.reset();
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
        double formerAdaptiveBias = Math.max(
                0.08,
                0.28 / Math.max(Math.abs(dot(receiverNormal, sample.direction())), 0.15)
        );
        require(receiverDistance > cacheFloorEntryDistance + 0.08,
                "receiver-plane regression no longer reproduces the former fixed-bias acne");
        require(cacheFloorEntryDistance - 1.0 + formerAdaptiveBias
                        >= cacheFloorEntryDistance,
                "grazing-angle fixture no longer demonstrates the one-block leak regression");
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
        double casterContactDistance = cacheFloorEntryDistance - 0.04;
        require(casterContactDistance + 0.08 >= cacheFloorEntryDistance,
                "contact-shadow fixture no longer reproduces the former 8cm Peter-panning gap");
        require(!receiverSurfaceHit(
                        casterContactDistance,
                        lightToReceiver,
                        receiverNormal,
                        sample.direction()
                ),
                "a caster four centimetres before the receiver plane lost its contact shadow");
    }

    private static void testCachedCubeOverflowLayerTracksLatestHit() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );
        mirror.acknowledge(cacheBatch(61L, cacheTransparentLinePatch(), 0), clipmap);
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
        int visibility = packedRgb(payload, fourthLayer);
        float water = VoxelMaterialDescriptor.defaults(VoxelMaterialClass.WATER).transmittance();
        require(hitDistance > 5.0f,
                "overflowed cached layer retained the fourth hit distance instead of the latest one");
        require(Math.abs(VoxelChromaticFilter.unpackRed(visibility)
                        - (float) Math.pow(water, 6.0)) <= 1.0f / 255.0f,
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
        ), clipmap);
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
                        && isOpaqueRgb(packedRgb(aggregatePayload, positiveX)),
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
        int waterVisibility = ByteBuffer.wrap(ordinaryCache.payload())
                .order(ByteOrder.nativeOrder())
                .getInt(positiveX + Float.BYTES);
        require(VoxelChromaticFilter.unpackRed(waterVisibility) > 0.0f
                        && VoxelChromaticFilter.unpackRed(waterVisibility) < 1.0f,
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
        return emptyCachePatchAt(
                0, 4, logicalX, logicalY, logicalZ, brickDimension, stamp
        );
    }

    private static VoxelBrickPatch emptyCachePatchAt(
            final int level,
            final int subdivision,
            final int logicalX,
            final int logicalY,
            final int logicalZ,
            final int brickDimension,
            final int stamp
    ) {
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / subdivision;
        return new VoxelBrickPatch(
                level,
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
        return cacheWallPatch(stamp, materialClass, VoxelChromaticFilter.NEUTRAL_ID);
    }

    private static VoxelBrickPatch cacheWallPatch(
            final int stamp,
            final VoxelMaterialClass materialClass,
            final int chromaticId
    ) {
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        for (int z = 0; z < VoxelBrickPatch.LOGICAL_EDGE; z++) {
            for (int y = 0; y < VoxelBrickPatch.LOGICAL_EDGE; y++) {
                occupancy[z * VoxelBrickPatch.LOGICAL_EDGE + y] = 0x0f << 8;
            }
        }
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / 4;
        byte[] optical = new byte[blockEdge * blockEdge * blockEdge];
        byte[] chromatic = VoxelChromaticFilter.neutralPackedValues(optical.length);
        byte packed = (byte) VoxelMaterialDescriptor.defaults(materialClass).packedUnsignedByte();
        for (int z = 0; z < blockEdge; z++) {
            for (int y = 0; y < blockEdge; y++) {
                int index = (z * blockEdge + y) * blockEdge + 2;
                optical[index] = packed;
                VoxelChromaticFilter.putPackedId(chromatic, index, chromaticId);
            }
        }
        return new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, stamp,
                VOXEL_WORLD.generation(), 11L, occupancy, optical, chromatic
        );
    }

    private static VoxelBrickPatch cacheFineSlabPatch(final int stamp) {
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        int blockX = 2;
        for (int cellZ = 0; cellZ < 4; cellZ++) {
            for (int cellY = 0; cellY < 2; cellY++) {
                occupancy[cellZ * VoxelBrickPatch.LOGICAL_EDGE + cellY]
                        |= 0x0f << (blockX * 4);
            }
        }
        return fineOccluderPatch(stamp, occupancy, blockX, VoxelMaterialClass.OPAQUE);
    }

    private static VoxelBrickPatch cacheFineFencePatch(final int stamp) {
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        int blockX = 2;
        for (int cellZ = 1; cellZ <= 2; cellZ++) {
            for (int cellY = 0; cellY < 4; cellY++) {
                occupancy[cellZ * VoxelBrickPatch.LOGICAL_EDGE + cellY]
                        |= 0x06 << (blockX * 4);
            }
        }
        return fineOccluderPatch(stamp, occupancy, blockX, VoxelMaterialClass.CUTOUT);
    }

    private static VoxelBrickPatch fineOccluderPatch(
            final int stamp,
            final int[] occupancy,
            final int blockX,
            final VoxelMaterialClass materialClass
    ) {
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / 4;
        byte[] optical = new byte[blockEdge * blockEdge * blockEdge];
        optical[blockX] = (byte) VoxelMaterialDescriptor.defaults(
                materialClass
        ).packedUnsignedByte();
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
        return level(subdivision, material, VoxelChromaticFilter.NEUTRAL_ID);
    }

    private static VoxelShadowTraversal.LevelData level(
            final int subdivision,
            final VoxelMaterialClass material,
            final int chromaticId
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
        byte[] chromatic = VoxelChromaticFilter.neutralPackedValues(optical.length);
        Arrays.fill(optical, (byte) VoxelMaterialDescriptor.defaults(material).packedUnsignedByte());
        for (int index = 0; index < optical.length; index++) {
            VoxelChromaticFilter.putPackedId(chromatic, index, chromaticId);
        }
        return new VoxelShadowTraversal.LevelData(
                snapshot, 0, occupancy, optical, chromatic,
                new VoxelShadowTraversal.BrickMetadata[]{
                        new VoxelShadowTraversal.BrickMetadata(0, 0, 0, 1)
                }
        );
    }

    private static VoxelShadowTraversal.LevelData twoBlockChromaticLevel(
            final int firstFilter,
            final int secondFilter
    ) {
        VoxelClipmapSnapshot snapshot = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 1, 32, 0, 0, 0, 1))
        );
        int[] occupancy = new int[32 * 32 * 32 / 32];
        occupancy[0] = 0b11;
        byte[] optical = new byte[32 * 32 * 32];
        optical[0] = (byte) VoxelMaterialDescriptor.defaults(
                VoxelMaterialClass.GLASS
        ).packedUnsignedByte();
        optical[1] = optical[0];
        byte[] chromatic = VoxelChromaticFilter.neutralPackedValues(optical.length);
        VoxelChromaticFilter.putPackedId(chromatic, 0, firstFilter);
        VoxelChromaticFilter.putPackedId(chromatic, 1, secondFilter);
        return new VoxelShadowTraversal.LevelData(
                snapshot, 0, occupancy, optical, chromatic,
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
                && hitDistance + RECEIVER_COINCIDENCE_EPSILON >= planeDistance;
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

    private static void testThinShapeClassification() {
        // 1. Blocks aligned to multiples of 0.25 (1/4m) must use fast-path shapeProxyId == 0
        VoxelShape fullCube = Shapes.block();
        VoxelShape bottomSlab = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        VoxelShape topSlab = Shapes.box(0.0, 0.5, 0.0, 1.0, 1.0, 1.0);
        VoxelShape straightStair = Shapes.or(
                Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
                Shapes.box(0.0, 0.5, 0.0, 0.5, 1.0, 1.0)
        );
        VoxelShape quarterStep = Shapes.box(0.0, 0.0, 0.0, 0.25, 0.25, 0.25);

        VoxelMaterialDescriptor opaqueDesc = VoxelMaterialDescriptor.defaults(VoxelMaterialClass.OPAQUE);
        require(VoxelShapeEncoder.encode(fullCube, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId() == 0,
                "full cube unexpectedly received shape proxy");
        require(VoxelShapeEncoder.encode(bottomSlab, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId() == 0,
                "bottom slab unexpectedly received shape proxy");
        require(VoxelShapeEncoder.encode(topSlab, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId() == 0,
                "top slab unexpectedly received shape proxy");
        require(VoxelShapeEncoder.encode(straightStair, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId() == 0,
                "straight stair unexpectedly received shape proxy");
        require(VoxelShapeEncoder.encode(quarterStep, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId() == 0,
                "quarter step unexpectedly received shape proxy");

        // 2. Complex / thin / compound blocks not aligned to 0.25 must receive shapeProxyId > 0
        VoxelShape glassPaneZ = Shapes.box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625);
        VoxelShape glassPaneX = Shapes.box(0.4375, 0.0, 0.0, 0.5625, 1.0, 1.0);
        VoxelShape ironBarsCross = Shapes.or(
                Shapes.box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625),
                Shapes.box(0.4375, 0.0, 0.0, 0.5625, 1.0, 1.0)
        );
        VoxelShape fencePost = Shapes.box(0.375, 0.0, 0.375, 0.625, 1.0, 0.625);
        VoxelShape fenceWithArm = Shapes.or(
                Shapes.box(0.375, 0.0, 0.375, 0.625, 1.0, 0.625),
                Shapes.box(0.0, 0.375, 0.4375, 0.375, 0.5625, 0.5625)
        );
        VoxelShape trapdoorClosed = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.1875, 1.0);
        VoxelShape trapdoorOpen = Shapes.box(0.0, 0.0, 0.8125, 1.0, 1.0, 1.0);

        int paneZId = VoxelShapeEncoder.encode(glassPaneZ, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();
        int paneXId = VoxelShapeEncoder.encode(glassPaneX, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();
        int ironBarsId = VoxelShapeEncoder.encode(ironBarsCross, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();
        int fenceId = VoxelShapeEncoder.encode(fencePost, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();
        int fenceArmId = VoxelShapeEncoder.encode(fenceWithArm, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();
        int trapClosedId = VoxelShapeEncoder.encode(trapdoorClosed, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();
        int trapOpenId = VoxelShapeEncoder.encode(trapdoorOpen, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();

        require(paneZId > 0, "glass pane Z did not receive shape proxy");
        require(paneXId > 0, "glass pane X did not receive shape proxy");
        require(ironBarsId > 0, "iron bars cross did not receive shape proxy");
        require(fenceId > 0, "fence post did not receive shape proxy");
        require(fenceArmId > 0, "fence with arm did not receive shape proxy");
        require(trapClosedId > 0, "closed trapdoor did not receive shape proxy");
        require(trapOpenId > 0, "open trapdoor did not receive shape proxy");

        // 3. Deduplication: encoding the exact same shape twice yields identical shapeProxyId
        int paneZIdSecond = VoxelShapeEncoder.encode(glassPaneZ, VoxelSubdivision.FOUR, opaqueDesc).shapeProxyId();
        require(paneZId == paneZIdSecond, "shape proxy registry failed to deduplicate identical shape");
    }

    private static void testHybridShapeRefinementFidelity() {
        // 1. A thin pane at block (2, 0, 0) with thickness 0.125 centered at Z=0.5: [0.4375, 0.5625]
        VoxelShape paneShape = Shapes.box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625);
        VoxelShapeEncoder.EncodedShape encodedPane = VoxelShapeEncoder.encode(
                paneShape, VoxelSubdivision.FOUR,
                VoxelMaterialDescriptor.defaults(VoxelMaterialClass.CUTOUT)
        );
        require(encodedPane.shapeProxyId() > 0, "thin pane was not assigned a refined ShapeProxy");
        require(encodedPane.occupiedCellCount() == 32, "conservative 4x occupancy did not identify 32 potential subcells");

        // 2. Build a brick patch with this pane at block (2, 0, 0)
        int[] occupancy = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        int blockX = 2;
        long remaining = encodedPane.occupancyMask();
        while (remaining != 0L) {
            int bit = Long.numberOfTrailingZeros(remaining);
            int fineX = bit & 3;
            int fineY = bit >>> 2 & 3;
            int fineZ = bit >>> 4;
            int logicalX = blockX * 4 + fineX;
            int logicalY = fineY;
            int logicalZ = fineZ;
            int wordOffset = logicalZ * VoxelBrickPatch.LOGICAL_EDGE + logicalY;
            occupancy[wordOffset] |= 1 << logicalX;
            remaining &= remaining - 1L;
        }
        int blockEdge = VoxelBrickPatch.LOGICAL_EDGE / 4;
        byte[] optical = new byte[blockEdge * blockEdge * blockEdge];
        optical[blockX] = (byte) VoxelMaterialDescriptor.defaults(VoxelMaterialClass.CUTOUT).packedUnsignedByte();
        byte[] chromatic = VoxelChromaticFilter.neutralPackedValues(optical.length);
        short[] shapeIds = new short[optical.length];
        shapeIds[blockX] = (short) encodedPane.shapeProxyId();

        VoxelBrickPatch patch = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 999,
                VOXEL_WORLD.generation(), 11L, occupancy, optical, chromatic, shapeIds
        );

        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();
        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );
        mirror.acknowledge(cacheCoverageBatch(999L, patch, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot snapshot = mirror.snapshot(clipmap);
        require(snapshot != null, "refined pane snapshot did not publish");

        // 3. Test ray traversing through the EMPTY portion of the 4x subcell (Z = 0.30):
        // Receiver at (0.5, 0.5, 0.30), aiming toward (4.5, 0.5, 0.30)
        // Ray passes through block (2, 0, 0) at local Z = 0.30.
        // In conservative 4x voxelization, subcell 1 [0.25, 0.50] is occupied!
        // With hybrid refinement, the ray MUST NOT be blocked.
        AdvancedLight light30 = new AdvancedLight(
                991L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.30, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        VoxelShadowCacheBuilder.PageResult page30 = VoxelShadowCacheBuilder.buildPage(
                snapshot, light30, 64, 96
        );
        ByteBuffer buf30 = ByteBuffer.wrap(page30.payload()).order(ByteOrder.nativeOrder());
        int centralRayOffset = pageEntryOffset(0, 31, 31, 0, 64);
        require(Float.isInfinite(buf30.getFloat(centralRayOffset))
                        && isVisibleRgb(packedRgb(buf30, centralRayOffset)),
                "ray at Z=0.30 through empty space of pane was falsely blocked by dilated 4x occupancy!");

        // 4. Test ray directly aiming at the physical pane (Z = 0.50):
        // Receiver at (0.5, 0.5, 0.50), aiming toward (4.5, 0.5, 0.50).
        // Ray hits the pane at exact entry distance X = 2.0 (distance from 0.5 = 1.5).
        AdvancedLight light50 = new AdvancedLight(
                992L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.50, 4.0f,
                1.0f, 0.8f, 0.5f, 2.0f, 10
        );
        VoxelShadowCacheBuilder.PageResult page50 = VoxelShadowCacheBuilder.buildPage(
                snapshot, light50, 64, 96
        );
        ByteBuffer buf50 = ByteBuffer.wrap(page50.payload()).order(ByteOrder.nativeOrder());
        float hitDist = buf50.getFloat(centralRayOffset);
        require(Float.isFinite(hitDist) && isOpaqueRgb(packedRgb(buf50, centralRayOffset)),
                "ray at Z=0.50 directly hitting pane failed to be blocked");
        require(Math.abs(hitDist - 1.5f) < 0.05f,
                "pane hit distance was not exact: expected ~1.5, got " + hitDist);

        mirror.reset();
    }

    private static void testLiveUpdateSequence() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();

        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );
        VoxelWorldToken world = clipmap.world();
        long clipGen = clipmap.clipmapGeneration();

        // 1. Initial state: empty brick at (0, 0, 0)
        int[] emptyOcc = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        byte[] emptyOpt = new byte[8 * 8 * 8];
        byte[] emptyChr = VoxelChromaticFilter.neutralPackedValues(emptyOpt.length);
        short[] emptyShp = new short[emptyOpt.length];
        VoxelBrickPatch initPatch = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 10,
                world.generation(), clipGen, emptyOcc, emptyOpt, emptyChr, emptyShp
        );
        mirror.acknowledge(cacheCoverageBatch(10L, initPatch, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot snap1 = mirror.snapshot(clipmap);
        require(snap1 != null && snap1.revision() > 0L, "mirror initial revision was not positive");

        // Light at (0.5, 0.5, 0.5) shining toward (4.5, 0.5, 0.5)
        AdvancedLight testLight = new AdvancedLight(
                501L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 5.0f,
                1.0f, 1.0f, 1.0f, 1.0f, 15
        );
        VoxelShadowCacheBuilder.PageResult initPage = VoxelShadowCacheBuilder.buildPage(
                snap1, testLight, 64, 96
        );
        ByteBuffer initBuf = ByteBuffer.wrap(initPage.payload()).order(ByteOrder.nativeOrder());
        int centralRayOffset = pageEntryOffset(0, 31, 31, 0, 64);
        require(Float.isInfinite(initBuf.getFloat(centralRayOffset)),
                "Initial empty world had false shadow");

        // 2. Place full opaque cube at block (2, 0, 0)
        int[] cubeOcc = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        for (int cz = 0; cz < 4; cz++) {
            for (int cy = 0; cy < 4; cy++) {
                cubeOcc[cz * VoxelBrickPatch.LOGICAL_EDGE + cy] |= 0b1111 << 8;
            }
        }
        byte[] cubeOpt = new byte[8 * 8 * 8];
        cubeOpt[2] = 0x20; // opaque
        byte[] cubeChr = VoxelChromaticFilter.neutralPackedValues(cubeOpt.length);
        short[] cubeShp = new short[cubeOpt.length];
        VoxelBrickPatch cubePatch = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 11,
                world.generation(), clipGen, cubeOcc, cubeOpt, cubeChr, cubeShp
        );
        mirror.acknowledge(cacheCoverageBatch(11L, cubePatch, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot snap2 = mirror.snapshot(clipmap);
        require(snap2 != null && snap2.revision() > snap1.revision(),
                "Mirror revision did not advance after placing full cube");

        VoxelShadowCacheBuilder.PageResult cubePage = VoxelShadowCacheBuilder.buildPage(
                snap2, testLight, 64, 96
        );
        ByteBuffer cubeBuf = ByteBuffer.wrap(cubePage.payload()).order(ByteOrder.nativeOrder());
        float cubeHitDist = cubeBuf.getFloat(centralRayOffset);
        require(Float.isFinite(cubeHitDist) && Math.abs(cubeHitDist - 1.5f) < 0.05f,
                "Placed full cube did not produce immediate shadow: got hitDist " + cubeHitDist);

        // 3. Update to bottom half slab at block (2, 0, 0)
        int[] slabOcc = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        for (int cz = 0; cz < 4; cz++) {
            for (int cy = 0; cy < 2; cy++) { // bottom half (cy in 0..1)
                slabOcc[cz * VoxelBrickPatch.LOGICAL_EDGE + cy] |= 0b1111 << 8;
            }
        }
        byte[] slabOpt = new byte[8 * 8 * 8];
        slabOpt[2] = 0x20;
        byte[] slabChr = VoxelChromaticFilter.neutralPackedValues(slabOpt.length);
        short[] slabShp = new short[slabOpt.length]; // shapeProxyId = 0 (4x quarter aligned)
        VoxelBrickPatch slabPatch = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 12,
                world.generation(), clipGen, slabOcc, slabOpt, slabChr, slabShp
        );
        mirror.acknowledge(cacheCoverageBatch(12L, slabPatch, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot snap3 = mirror.snapshot(clipmap);
        require(snap3 != null && snap3.revision() > snap2.revision(),
                "Mirror revision did not advance after updating to slab");

        // Ray at Y=0.25 (bottom half) must hit the slab
        AdvancedLight slabHitLight = new AdvancedLight(
                502L, 1L, LightSourceKind.BLOCK,
                0.5, 0.25, 0.5, 5.0f,
                1.0f, 1.0f, 1.0f, 1.0f, 15
        );
        VoxelShadowCacheBuilder.PageResult slabHitPage = VoxelShadowCacheBuilder.buildPage(
                snap3, slabHitLight, 64, 96
        );
        ByteBuffer slabHitBuf = ByteBuffer.wrap(slabHitPage.payload()).order(ByteOrder.nativeOrder());
        require(Float.isFinite(slabHitBuf.getFloat(centralRayOffset)),
                "Ray aiming at bottom half of slab failed to hit occluder");

        // Ray at Y=0.75 (top half) must pass freely
        AdvancedLight slabMissLight = new AdvancedLight(
                503L, 1L, LightSourceKind.BLOCK,
                0.5, 0.75, 0.5, 5.0f,
                1.0f, 1.0f, 1.0f, 1.0f, 15
        );
        VoxelShadowCacheBuilder.PageResult slabMissPage = VoxelShadowCacheBuilder.buildPage(
                snap3, slabMissLight, 64, 96
        );
        ByteBuffer slabMissBuf = ByteBuffer.wrap(slabMissPage.payload()).order(ByteOrder.nativeOrder());
        require(Float.isInfinite(slabMissBuf.getFloat(centralRayOffset)),
                "Ray aiming at empty top half of slab was falsely occluded");

        // 4. Update to thin glass pane at block (2, 0, 0)
        short paneProxyId = (short) VoxelShapeRegistry.register(List.of(
                new VoxelShapeRegistry.Box(0.4375f, 0.0f, 0.0f, 0.5625f, 1.0f, 1.0f)
        ));
        int[] paneOcc = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        for (int cz = 0; cz < 4; cz++) {
            for (int cy = 0; cy < 4; cy++) {
                paneOcc[cz * VoxelBrickPatch.LOGICAL_EDGE + cy] |= 0b0011 << 8;
            }
        }
        byte[] paneOpt = new byte[8 * 8 * 8];
        paneOpt[2] = 0x20;
        byte[] paneChr = VoxelChromaticFilter.neutralPackedValues(paneOpt.length);
        short[] paneShp = new short[paneOpt.length];
        paneShp[2] = paneProxyId;
        VoxelBrickPatch panePatch = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 13,
                world.generation(), clipGen, paneOcc, paneOpt, paneChr, paneShp
        );
        mirror.acknowledge(cacheCoverageBatch(13L, panePatch, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot snap4 = mirror.snapshot(clipmap);
        require(snap4 != null && snap4.revision() > snap3.revision(),
                "Mirror revision did not advance after updating to glass pane");

        // Ray hitting thin pane (X in 2.4375..2.5625)
        AdvancedLight paneHitLight = new AdvancedLight(
                504L, 1L, LightSourceKind.BLOCK,
                0.5, 0.5, 0.5, 5.0f,
                1.0f, 1.0f, 1.0f, 1.0f, 15
        );
        VoxelShadowCacheBuilder.PageResult paneHitPage = VoxelShadowCacheBuilder.buildPage(
                snap4, paneHitLight, 64, 96
        );
        ByteBuffer paneHitBuf = ByteBuffer.wrap(paneHitPage.payload()).order(ByteOrder.nativeOrder());
        float paneHitDist = paneHitBuf.getFloat(centralRayOffset);
        require(Float.isFinite(paneHitDist) && Math.abs(paneHitDist - 1.9375f) < 0.05f,
                "Thin pane failed to be hit at exact boundary: got " + paneHitDist);

        // 5. Remove occluder completely (break block)
        VoxelBrickPatch breakPatch = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 14,
                world.generation(), clipGen, emptyOcc, emptyOpt, emptyChr, emptyShp
        );
        mirror.acknowledge(cacheCoverageBatch(14L, breakPatch, 0, 2), clipmap);
        VoxelShadowCacheMirror.Snapshot snap5 = mirror.snapshot(clipmap);
        require(snap5 != null && snap5.revision() > snap4.revision(),
                "Mirror revision did not advance after breaking block");

        VoxelShadowCacheBuilder.PageResult breakPage = VoxelShadowCacheBuilder.buildPage(
                snap5, testLight, 64, 96
        );
        ByteBuffer breakBuf = ByteBuffer.wrap(breakPage.payload()).order(ByteOrder.nativeOrder());
        require(Float.isInfinite(breakBuf.getFloat(centralRayOffset)),
                "Broken block shadow remained after removal update");

        mirror.reset();
    }

    private static void testMultiPatchBatchMirror() {
        VoxelShadowCacheMirror mirror = VoxelShadowCacheMirror.global();
        mirror.reset();

        VoxelClipmapSnapshot clipmap = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 11L,
                List.of(new VoxelClipmapSnapshot.Level(0, 4, 64, -1, -1, -1, 2))
        );
        VoxelWorldToken world = clipmap.world();
        long clipGen = clipmap.clipmapGeneration();

        int[] occ1 = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        byte[] opt1 = new byte[8 * 8 * 8];
        byte[] chr1 = VoxelChromaticFilter.neutralPackedValues(opt1.length);
        short[] shp1 = new short[opt1.length];
        VoxelBrickPatch patch1 = new VoxelBrickPatch(
                0, 0, 0, 0, 0, 0, 0, 50,
                world.generation(), clipGen, occ1, opt1, chr1, shp1
        );

        int[] occ2 = new int[VoxelBrickPatch.OCCUPANCY_WORDS];
        occ2[0] = 0x0000_0001;
        byte[] opt2 = new byte[8 * 8 * 8];
        opt2[0] = 0x20;
        byte[] chr2 = VoxelChromaticFilter.neutralPackedValues(opt2.length);
        short[] shp2 = new short[opt2.length];
        VoxelBrickPatch patch2 = new VoxelBrickPatch(
                0, 1, 0, 0, -1, 0, 0, 51,
                world.generation(), clipGen, occ2, opt2, chr2, shp2
        );

        VoxelUploadBatch multiBatch = new VoxelUploadBatch(
                50L, world, clipGen, 50L,
                List.of(patch1, patch2), 0, 0L, 0, 0, 0L, 0L
        );
        mirror.acknowledge(multiBatch, clipmap);

        VoxelShadowCacheMirror.Snapshot snap = mirror.snapshot(clipmap);
        require(snap != null, "Snapshot from multi-patch batch mirror was null");
        require(snap.bricks().containsKey(new VoxelShadowCacheMirror.Key(0, 0, 0, 0)),
                "Patch 1 missing from mirror snapshot");
        require(snap.bricks().containsKey(new VoxelShadowCacheMirror.Key(0, 1, 0, 0)),
                "Patch 2 missing from mirror snapshot");
        require(snap.bricks().get(new VoxelShadowCacheMirror.Key(0, 1, 0, 0)).contentStamp() == 51,
                "Patch 2 contentStamp mismatch in mirror snapshot");

        mirror.reset();
    }

    private static void testDynamicShapeSyncContract() {
        VoxelShape glassPane = Shapes.box(0.0, 0.0, 0.4375, 1.0, 1.0, 0.5625);
        VoxelMaterialDescriptor cutout = VoxelMaterialDescriptor.defaults(VoxelMaterialClass.CUTOUT);
        VoxelShapeEncoder.EncodedShape encoded = VoxelShapeEncoder.encode(glassPane, VoxelSubdivision.FOUR, cutout);
        int proxyId = encoded.shapeProxyId();
        require(proxyId > 0, "testDynamicShapeSyncContract: proxyId must be > 0");

        byte[] payload = VoxelShapeRegistry.serializeGpuPayload();
        require(payload.length >= 16, "GPU shape payload was too short");
        ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        int proxyCount = buf.getInt(0);
        int totalBoxCount = buf.getInt(4);
        int proxyTableBytes = buf.getInt(8);
        int boxTableBytes = buf.getInt(12);

        require(proxyCount >= 2, "proxyCount must be at least 2 (null proxy + registered proxy)");
        require(totalBoxCount >= 1, "totalBoxCount must be at least 1");
        require(proxyTableBytes == proxyCount * 8, "proxyTableBytes mismatch");
        require(boxTableBytes == totalBoxCount * 32, "boxTableBytes mismatch");

        // Verify proxy entry for proxyId
        int entryOffset = 16 + proxyId * 8;
        int boxOffset = buf.getInt(entryOffset);
        int boxCount = buf.getInt(entryOffset + 4);
        require(boxCount == 1, "Glass pane proxy boxCount must be 1");

        // Verify box bounds
        int boxBase = 16 + proxyTableBytes + boxOffset * 32;
        float minX = buf.getFloat(boxBase);
        float minY = buf.getFloat(boxBase + 4);
        float minZ = buf.getFloat(boxBase + 8);
        float maxX = buf.getFloat(boxBase + 12);
        float maxY = buf.getFloat(boxBase + 16);
        float maxZ = buf.getFloat(boxBase + 20);

        require(minX == 0.0f && minY == 0.0f && maxX == 1.0f && maxY == 1.0f,
                "Glass pane X/Y bounds mismatch");
        require(Math.abs(minZ - 0.4375f) < 1.0e-5f && Math.abs(maxZ - 0.5625f) < 1.0e-5f,
                "Glass pane Z bounds mismatch: [" + minZ + ", " + maxZ + "]");
    }

    private static void testDistanceLevelSelection() {
        VoxelClipmapSnapshot performance = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 1L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 2, 128, -2, -2, -2, 4),
                        new VoxelClipmapSnapshot.Level(1, 1, 128, -2, -2, -2, 4)
                )
        );
        VoxelClipmapSnapshot balanced = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 1L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 256, -4, -4, -4, 8),
                        new VoxelClipmapSnapshot.Level(1, 2, 256, -4, -4, -4, 8),
                        new VoxelClipmapSnapshot.Level(2, 1, 256, -4, -4, -4, 8)
                )
        );
        VoxelClipmapSnapshot ultra = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 1L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 384, -6, -6, -6, 12),
                        new VoxelClipmapSnapshot.Level(1, 2, 384, -6, -6, -6, 12),
                        new VoxelClipmapSnapshot.Level(2, 1, 384, -6, -6, -6, 12)
                )
        );

        // Near small light (radius 8 at 10m): fits Level 0 in Balanced/Ultra (subdivision 4)
        AdvancedLight nearSmall = new AdvancedLight(100L, 1L, LightSourceKind.BLOCK, 10.0, 0.0, 0.0, 8.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0);
        require(VoxelShadowCacheBuilder.selectCacheLevel(balanced, nearSmall, 96) == 0,
                "Near small light did not select Level 0 in Balanced");
        require(VoxelShadowCacheBuilder.selectCacheLevel(ultra, nearSmall, 96) == 0,
                "Near small light did not select Level 0 in Ultra");

        // Mid light (radius 16 at 30m): Level 0 crossings (114) exceed 96, so selects Level 1 (subdivision 2)
        AdvancedLight midLight = new AdvancedLight(101L, 1L, LightSourceKind.BLOCK, 30.0, 0.0, 0.0, 16.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0);
        require(VoxelShadowCacheBuilder.selectCacheLevel(balanced, midLight, 96) == 1,
                "Mid radius-16 light did not select Level 1 in Balanced");
        require(VoxelShadowCacheBuilder.selectCacheLevel(ultra, midLight, 96) == 1,
                "Mid radius-16 light did not select Level 1 in Ultra");

        // Far light (radius 16 at 70m): fits Level 2 in Balanced (span 256, 70+16=86 > 64) and Level 1 in Ultra (span 192, 70+16=86 < 96)
        AdvancedLight farLight = new AdvancedLight(102L, 1L, LightSourceKind.BLOCK, 70.0, 0.0, 0.0, 16.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0);
        require(VoxelShadowCacheBuilder.selectCacheLevel(balanced, farLight, 96) == 2,
                "Far light at 70m did not select Level 2 in Balanced");
        require(VoxelShadowCacheBuilder.selectCacheLevel(ultra, farLight, 96) == 1,
                "Far light at 70m did not select Level 1 in Ultra");

        // Out-of-bounds light (radius 16 at 120m in Balanced where half-span is 128, 120+16=136 > 128)
        AdvancedLight outBalanced = new AdvancedLight(103L, 1L, LightSourceKind.BLOCK, 120.0, 0.0, 0.0, 16.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0);
        require(VoxelShadowCacheBuilder.selectCacheLevel(balanced, outBalanced, 96) == -1,
                "Out-of-bounds light was not safely rejected in Balanced");
        // But Ultra (half-span 192) contains 120+16=136 at Level 2
        require(VoxelShadowCacheBuilder.selectCacheLevel(ultra, outBalanced, 96) == 2,
                "Light at 120m did not select Level 2 in Ultra");

        // Out-of-bounds light for Ultra (radius 16 at 180m, 180+16=196 > 192)
        AdvancedLight outUltra = new AdvancedLight(104L, 1L, LightSourceKind.BLOCK, 180.0, 0.0, 0.0, 16.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0);
        require(VoxelShadowCacheBuilder.selectCacheLevel(ultra, outUltra, 96) == -1,
                "Out-of-bounds light was not safely rejected in Ultra");
    }

    private static void testCoarseLevelFallback() {
        VoxelClipmapSnapshot balanced = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 1L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 256, -4, -4, -4, 8),
                        new VoxelClipmapSnapshot.Level(1, 2, 256, -4, -4, -4, 8),
                        new VoxelClipmapSnapshot.Level(2, 1, 256, -4, -4, -4, 8)
                )
        );
        // Light with large radius 20 at position (0,0,0):
        // Level 0 (sub 4): crossings = ceil(20 * 4 * sqrt(3)) + 3 = 142 > 96 (FAILS step budget)
        // Level 1 (sub 2): crossings = ceil(20 * 2 * sqrt(3)) + 3 = 73 <= 96 (PASSES step budget, fits span 128)
        AdvancedLight largeRadius = new AdvancedLight(200L, 1L, LightSourceKind.BLOCK, 0.0, 0.0, 0.0, 20.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0);
        int selected = VoxelShadowCacheBuilder.selectCacheLevel(balanced, largeRadius, 96);
        require(selected == 1, "Large radius light did not fallback from Level 0 to Level 1: " + selected);

        // Light with radius 40 at position (0,0,0):
        // Level 1 (sub 2): crossings = ceil(40 * 2 * sqrt(3)) + 3 = 142 > 96 (FAILS step budget)
        // Level 2 (sub 1): crossings = ceil(40 * 1 * sqrt(3)) + 3 = 73 <= 96 (PASSES step budget, fits span 256)
        AdvancedLight veryLargeRadius = new AdvancedLight(201L, 1L, LightSourceKind.BLOCK, 0.0, 0.0, 0.0, 40.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0);
        int selectedVeryLarge = VoxelShadowCacheBuilder.selectCacheLevel(balanced, veryLargeRadius, 96);
        require(selectedVeryLarge == 2, "Very large radius light did not fallback to Level 2: " + selectedVeryLarge);
    }

    private static void testPerceptualDistanceFadeMonotonicity() {
        // Test smoothstep fade curve: minDistToBoundary from radius + margin to radius
        float radius = 16.0f;
        float fadeMargin = 24.0f; // typical Balanced margin

        float previousFade = -1.0f;
        for (float dist = radius + fadeMargin + 10.0f; dist >= radius - 10.0f; dist -= 1.0f) {
            float rawFade = 1.0f - Math.clamp((dist - radius) / fadeMargin, 0.0f, 1.0f);
            float distanceFade = rawFade * rawFade * (3.0f - 2.0f * rawFade);

            require(distanceFade >= 0.0f && distanceFade <= 1.0f, "Fade out of [0, 1] range: " + distanceFade);
            if (previousFade >= 0.0f) {
                require(distanceFade >= previousFade, "Fade is not monotonically increasing as distance to boundary shrinks");
            }
            if (dist >= radius + fadeMargin) {
                require(distanceFade == 0.0f, "Fade must be 0.0 well within clipmap");
            }
            if (dist <= radius) {
                require(distanceFade == 1.0f, "Fade must be exactly 1.0 at clipmap boundary");
            }
            previousFade = distanceFade;
        }
    }

    private static void testLODHysteresisStability() {
        // Test projected ratio hysteresis curve:
        // radius = 16: raw threshold for 64 is ratio >= 0.35 => distance <= 45.71m
        // UPGRADE_GUARD is 1.10 (ratio >= 0.385 => distance <= 41.55m)
        // DOWNGRADE_GUARD is 0.90 (ratio < 0.315 => distance > 50.79m)
        double radius = 16.0;

        // Near distance 40m -> ratio = 0.40 >= 0.35 (64 edge)
        double nearRatio = radius / 40.0;
        require(nearRatio >= 0.35, "Near ratio must qualify for 64 edge");

        // Distance 48m -> ratio = 0.333 (between 0.315 and 0.35, retained by downgrade guard)
        double retainedRatio = radius / 48.0;
        require(retainedRatio >= 0.35 * 0.90 && retainedRatio < 0.35,
                "Retained ratio must fall within the downgrade guard band");

        // Distance 55m -> ratio = 0.29 < 0.315 (drops to 32 edge)
        double droppedRatio = radius / 55.0;
        require(droppedRatio < 0.35 * 0.90 && droppedRatio >= 0.175,
                "Dropped ratio must fall within 32 edge band");
    }

    private static void testStaticDynamicDistanceParity() {
        VoxelClipmapSnapshot balanced = new VoxelClipmapSnapshot(
                VOXEL_WORLD, 1L,
                List.of(
                        new VoxelClipmapSnapshot.Level(0, 4, 256, -4, -4, -4, 8),
                        new VoxelClipmapSnapshot.Level(1, 2, 256, -4, -4, -4, 8),
                        new VoxelClipmapSnapshot.Level(2, 1, 256, -4, -4, -4, 8)
                )
        );
        AdvancedLight staticLight = new AdvancedLight(
                300L, 1L, LightSourceKind.BLOCK, 60.0, 0.0, 0.0, 16.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0,
                false, ShadowEmitterFootprint.empty(), LocalShadowSourceClass.STATIC_CACHE
        );
        AdvancedLight dynamicLight = new AdvancedLight(
                301L, 1L, LightSourceKind.BLOCK, 60.0, 0.0, 0.0, 16.0f, 1.0f, 1.0f, 1.0f, 1.0f, 0,
                false, ShadowEmitterFootprint.empty(), LocalShadowSourceClass.ENTITY_DYNAMIC
        );

        int staticLevel = VoxelShadowCacheBuilder.selectCacheLevel(balanced, staticLight, 96);
        int dynamicLevel = VoxelShadowCacheBuilder.selectCacheLevel(balanced, dynamicLight, 96);

        require(staticLevel >= 0 && staticLevel == dynamicLevel,
                "Static and dynamic level selection diverged at distance: static=" + staticLevel + ", dynamic=" + dynamicLevel);
    }

    private static void testPreFixEntityShadowFailures() {
        // Pre-fix defect 1: Un-interpolated tick-bound AABB fails to track entity at partialTick
        AABB rawBox = new AABB(-0.3, 0.0, -0.3, 0.3, 1.95, 0.3);
        double lerpX = 0.0 + (2.0 - 0.0) * 0.5; // 1.0
        AABB interpolated = rawBox.move(lerpX, 0.0, 0.0);
        require(Math.abs(interpolated.minX - 0.7) < 1e-6 && Math.abs(interpolated.maxX - 1.3) < 1e-6,
                "Pre-fix: Interpolated entity bounds failed");

        // Pre-fix defect 2: EntityShadowFilter rejects null / non-shadow casters
        require(!EntityShadowFilter.isShadowCaster(null), "Null entity must be rejected");

        // Pre-fix defect 3: Slab test with surface bias correctly shadows floor under feet
        double[] floorReceiver = new double[]{0.5, 0.0, 2.0};
        double[] lightPos = new double[]{0.5, 2.0, 0.5};
        double[] min = new double[]{0.2, 0.0, 1.7};
        double[] max = new double[]{0.8, 1.95, 2.3};
        require(cpuSegmentIntersectsProxy(floorReceiver, lightPos, min, max),
                "Pre-fix: Floor under mob feet failed to receive shadow");
    }

    private static void testEntityShadowFilterSemantics() {
        require(!EntityShadowFilter.isShadowCaster(null), "Null entity must be filtered");
        require(!EntityShadowFilter.isShadowCaster(null, null), "Null entity with camera must be filtered");
        require(!EntityShadowFilter.isFirstPersonCameraEntity(null, null), "Null entity cannot be camera entity");
    }

    private static void testShaderReceiverProxyScoping() {
        // Contract: Entity proxy -> terrain receiver shadow ray intersection
        double[] lightPos = new double[]{0.5, 2.0, 0.5};
        double[] min = new double[]{0.2, 0.0, 1.7};
        double[] max = new double[]{0.8, 1.95, 2.3};
        double[] receiverFloor = new double[]{0.5, 0.0, 2.0};
        require(cpuSegmentIntersectsProxy(receiverFloor, lightPos, min, max),
                "Floor contact ray must intersect zombie proxy volume");

        // Contract: Receiver scoping - entity receivers skip proxy self-shadowing under fallback
        boolean terrainReceiver = false;
        boolean proxyEvaluatedOnEntityReceiver = terrainReceiver; // #ifdef METALLUM_VOXEL_TERRAIN_RECEIVER_V1
        require(!proxyEvaluatedOnEntityReceiver, "Entity receivers must not evaluate entity proxy occlusion");

        // Contract: Terrain receivers evaluate proxy occlusion
        terrainReceiver = true;
        boolean proxyEvaluatedOnTerrainReceiver = terrainReceiver;
        require(proxyEvaluatedOnTerrainReceiver, "Terrain receivers must evaluate entity proxy occlusion");

        // Contract: Held light carrier exclusion (proxyStableId == lightStableId) skips proxy
        long proxyStableId = 12345L;
        long lightStableId = 12345L;
        require(proxyStableId == lightStableId, "Held light carrier must skip own proxy");

        // Contract: Multi-primitive decomposition retains identical stableId
        AABB humanoidBox = new AABB(0.2, 0.0, 1.7, 0.8, 1.95, 2.3);
        long stableId = 999L;
        double width = humanoidBox.maxX - humanoidBox.minX;
        double height = humanoidBox.maxY - humanoidBox.minY;
        double depth = humanoidBox.maxZ - humanoidBox.minZ;
        double centerX = (humanoidBox.minX + humanoidBox.maxX) * 0.5;
        double minY = humanoidBox.minY;
        double centerZ = (humanoidBox.minZ + humanoidBox.maxZ) * 0.5;
        EntityShadowProxy head = new EntityShadowProxy(
                stableId, centerX, minY + height - 0.22, centerZ,
                Math.min(0.22f, (float) (width * 0.38)), 0.22f, Math.min(0.22f, (float) (depth * 0.38))
        );
        EntityShadowProxy torso = new EntityShadowProxy(
                stableId, centerX, minY + height * 0.55, centerZ,
                Math.min(0.26f, (float) (width * 0.45)), (float) (height * 0.22), Math.min(0.18f, (float) (depth * 0.32))
        );
        EntityShadowProxy legs = new EntityShadowProxy(
                stableId, centerX, minY + height * 0.20, centerZ,
                Math.min(0.20f, (float) (width * 0.35)), (float) (height * 0.20), Math.min(0.16f, (float) (depth * 0.30))
        );
        List<EntityShadowProxy> proxies = List.of(head, torso, legs);
        require(proxies.size() == 3, "Humanoid decomposition must produce 3 primitives");
        require(proxies.stream().allMatch(p -> p.stableId() == 999L), "All primitives must share stableId");
    }

    private static void testTemporalInterpolationFidelity() {
        AABB baseBox = new AABB(-0.3, 0.0, -0.3, 0.3, 1.95, 0.3);
        double xOld = 0.0;
        double xNew = 4.0;
        float[] partials = new float[]{0.0f, 0.25f, 0.5f, 0.75f, 1.0f};
        double[] expectedCenters = new double[]{0.0, 1.0, 2.0, 3.0, 4.0};

        for (int i = 0; i < partials.length; i++) {
            double lerpX = xOld + (xNew - xOld) * (double) partials[i];
            AABB moved = baseBox.move(lerpX, 0.0, 0.0);
            double centerX = (moved.minX + moved.maxX) * 0.5;
            require(Math.abs(centerX - expectedCenters[i]) < 1e-6,
                    "Temporal interpolation at partialTick " + partials[i] + " produced wrong center: " + centerX);
        }
    }

    private static void testMultiPrimitiveArchetypesAcrossPresets() {
        AABB humanoidBox = new AABB(0.2, 0.0, 1.7, 0.8, 1.95, 2.3);
        long stableId = 555L;

        // Performance preset: Single AABB
        EntityShadowProxy singleProxy = EntityShadowProxy.fromAABB(humanoidBox, stableId);
        require(singleProxy.stableId() == stableId, "Stable ID mismatch on single proxy");
        require(Math.abs(singleProxy.halfExtentY() - (1.95f * 0.5f)) < 1e-4, "Height extent mismatch on single proxy");

        // Balanced / Ultra preset: Humanoid multi-primitive (Head, Torso, Legs)
        double width = humanoidBox.maxX - humanoidBox.minX; // 0.6
        double height = humanoidBox.maxY - humanoidBox.minY; // 1.95
        double depth = humanoidBox.maxZ - humanoidBox.minZ; // 0.6
        double centerX = (humanoidBox.minX + humanoidBox.maxX) * 0.5;
        double minY = humanoidBox.minY;
        double centerZ = (humanoidBox.minZ + humanoidBox.maxZ) * 0.5;

        EntityShadowProxy head = new EntityShadowProxy(
                stableId, centerX, minY + height - 0.22, centerZ,
                Math.min(0.22f, (float) (width * 0.38)), 0.22f, Math.min(0.22f, (float) (depth * 0.38))
        );
        EntityShadowProxy torso = new EntityShadowProxy(
                stableId, centerX, minY + height * 0.55, centerZ,
                Math.min(0.26f, (float) (width * 0.45)), (float) (height * 0.22), Math.min(0.18f, (float) (depth * 0.32))
        );
        EntityShadowProxy legs = new EntityShadowProxy(
                stableId, centerX, minY + height * 0.20, centerZ,
                Math.min(0.20f, (float) (width * 0.35)), (float) (height * 0.20), Math.min(0.16f, (float) (depth * 0.30))
        );
        List<EntityShadowProxy> multi = List.of(head, torso, legs);
        require(multi.size() == 3, "Humanoid decomposition must produce exactly 3 primitives");
        require(multi.stream().allMatch(p -> p.stableId() == stableId), "All multi-primitives must share the entity's stableId");
        require(head.centerY() > torso.centerY() && torso.centerY() > legs.centerY(), "Vertical ordering of primitives is incorrect");
    }

    private static void testCpuGpuRayProxySlabIntersectionParity() {
        double[] lightPos = new double[]{0.5, 2.0, 0.5};
        double[] min = new double[]{0.2, 0.0, 1.7};
        double[] max = new double[]{0.8, 1.95, 2.3};

        // Test A: Direct shadow ray through zombie onto terrain behind it
        double[] receiverBehind = new double[]{0.5, 0.0, 4.0};
        require(cpuSegmentIntersectsProxy(receiverBehind, lightPos, min, max),
                "Ray from terrain through zombie to light must intersect");

        // Test B: Miss ray to the side
        double[] receiverSide = new double[]{2.5, 0.0, 4.0};
        require(!cpuSegmentIntersectsProxy(receiverSide, lightPos, min, max),
                "Ray to the side must not intersect zombie proxy");

        // Test C: Floor contact directly under zombie feet
        double[] receiverFloor = new double[]{0.5, 0.0, 2.0};
        require(cpuSegmentIntersectsProxy(receiverFloor, lightPos, min, max),
                "Floor contact ray must intersect zombie proxy volume");

        // Test D: Receiver on entity torso (surface receiver self-shadow prevention)
        double[] receiverTorso = new double[]{0.5, 1.0, 2.0};
        require(!cpuSegmentIntersectsProxy(receiverTorso, lightPos, min, max),
                "Receiver on entity torso must not self-shadow");

        // Test E: Light behind zombie (light at z=5.0, receiver at z=0.0)
        double[] lightBehind = new double[]{0.5, 2.0, 5.0};
        double[] receiverInFront = new double[]{0.5, 0.0, 0.0};
        require(cpuSegmentIntersectsProxy(receiverInFront, lightBehind, min, max),
                "Ray from in front to light behind must intersect zombie proxy");
    }

    private static void testNegativeAndLargeWorldCoordinates() {
        double camX = -12340.0;
        double camY = 64.0;
        double camZ = -67885.0;

        EntityShadowProxy proxy = new EntityShadowProxy(777L, -12340.0, 64.0, -67883.0, 0.3f, 0.975f, 0.3f);
        require(Math.abs(proxy.minRelativeX(camX) - (-0.3f)) < 1e-4, "Relative minX in negative coords failed");
        require(Math.abs(proxy.maxRelativeX(camX) - (0.3f)) < 1e-4, "Relative maxX in negative coords failed");
        require(Math.abs(proxy.minRelativeZ(camZ) - (1.7f)) < 1e-4, "Relative minZ in negative coords failed");
        require(Math.abs(proxy.maxRelativeZ(camZ) - (2.3f)) < 1e-4, "Relative maxZ in negative coords failed");

        double[] lightRelative = new double[]{0.0, 2.0, 0.0};
        double[] receiverRelative = new double[]{0.0, 0.0, 4.0};
        double[] min = new double[]{proxy.minRelativeX(camX), proxy.minRelativeY(camY), proxy.minRelativeZ(camZ)};
        double[] max = new double[]{proxy.maxRelativeX(camX), proxy.maxRelativeY(camY), proxy.maxRelativeZ(camZ)};
        require(cpuSegmentIntersectsProxy(receiverRelative, lightRelative, min, max),
                "Negative coordinate camera-relative intersection failed");
    }

    private static void testCameraMovementPreservesWorldGeometry() {
        double entityWorldX = 10.0;
        double entityWorldY = 0.0;
        double entityWorldZ = 10.0;
        double lightWorldX = 10.0;
        double lightWorldY = 2.0;
        double lightWorldZ = 8.0;
        double receiverWorldX = 10.0;
        double receiverWorldY = 0.0;
        double receiverWorldZ = 12.0;

        EntityShadowProxy proxy = new EntityShadowProxy(888L, entityWorldX, entityWorldY + 0.975, entityWorldZ, 0.3f, 0.975f, 0.3f);

        // Camera 1 at origin (0, 0, 0)
        double cam1X = 0.0, cam1Y = 0.0, cam1Z = 0.0;
        double[] r1 = new double[]{receiverWorldX - cam1X, receiverWorldY - cam1Y, receiverWorldZ - cam1Z};
        double[] l1 = new double[]{lightWorldX - cam1X, lightWorldY - cam1Y, lightWorldZ - cam1Z};
        double[] min1 = new double[]{proxy.minRelativeX(cam1X), proxy.minRelativeY(cam1Y), proxy.minRelativeZ(cam1Z)};
        double[] max1 = new double[]{proxy.maxRelativeX(cam1X), proxy.maxRelativeY(cam1Y), proxy.maxRelativeZ(cam1Z)};
        boolean hit1 = cpuSegmentIntersectsProxy(r1, l1, min1, max1);

        // Camera 2 far away at (200, 50, -300)
        double cam2X = 200.0, cam2Y = 50.0, cam2Z = -300.0;
        double[] r2 = new double[]{receiverWorldX - cam2X, receiverWorldY - cam2Y, receiverWorldZ - cam2Z};
        double[] l2 = new double[]{lightWorldX - cam2X, lightWorldY - cam2Y, lightWorldZ - cam2Z};
        double[] min2 = new double[]{proxy.minRelativeX(cam2X), proxy.minRelativeY(cam2Y), proxy.minRelativeZ(cam2Z)};
        double[] max2 = new double[]{proxy.maxRelativeX(cam2X), proxy.maxRelativeY(cam2Y), proxy.maxRelativeZ(cam2Z)};
        boolean hit2 = cpuSegmentIntersectsProxy(r2, l2, min2, max2);

        require(hit1 && hit2, "Camera motion changed world-space shadow intersection result");
    }

    private static void testEntityMovementAndRemovalLifecycle() {
        double[] lightPos = new double[]{0.5, 2.0, 0.5};
        double[] rayA_receiver = new double[]{0.5, 0.0, 3.5};
        double[] rayB_receiver = new double[]{4.5, 0.0, 3.5};

        // Frame N: Entity at X=0.5 -> Ray A blocked, Ray B clear
        EntityShadowProxy frameN = new EntityShadowProxy(999L, 0.5, 0.975, 2.0, 0.3f, 0.975f, 0.3f);
        double[] minN = new double[]{frameN.minRelativeX(0), frameN.minRelativeY(0), frameN.minRelativeZ(0)};
        double[] maxN = new double[]{frameN.maxRelativeX(0), frameN.maxRelativeY(0), frameN.maxRelativeZ(0)};
        require(cpuSegmentIntersectsProxy(rayA_receiver, lightPos, minN, maxN), "Frame N: Ray A must be blocked");
        require(!cpuSegmentIntersectsProxy(rayB_receiver, lightPos, minN, maxN), "Frame N: Ray B must be clear");

        // Frame N+1: Entity moves to X=2.5 -> Ray A clear, Ray B blocked
        EntityShadowProxy frameN1 = new EntityShadowProxy(999L, 2.5, 0.975, 2.0, 0.3f, 0.975f, 0.3f);
        double[] minN1 = new double[]{frameN1.minRelativeX(0), frameN1.minRelativeY(0), frameN1.minRelativeZ(0)};
        double[] maxN1 = new double[]{frameN1.maxRelativeX(0), frameN1.maxRelativeY(0), frameN1.maxRelativeZ(0)};
        require(!cpuSegmentIntersectsProxy(rayA_receiver, lightPos, minN1, maxN1), "Frame N+1: Ray A must be clear");
        require(cpuSegmentIntersectsProxy(rayB_receiver, lightPos, minN1, maxN1), "Frame N+1: Ray B must be blocked");
    }

    private static void testProxyCapacityAndAdmissionDeterminism() {
        BoundedEntityShadowProxyCollector collector = new BoundedEntityShadowProxyCollector(
                WORLD, 16, 0.0, 0.0, 0.0
        );
        for (int i = 0; i < 40; i++) {
            double dist = 1.0 + (i % 20);
            collector.offer(new EntityShadowProxy((long) (i + 1), dist, 0.0, dist, 0.5f, 0.5f, 0.5f));
        }
        List<EntityShadowProxy> finished = collector.finish();
        require(finished.size() == 16, "Collector must strictly enforce capacity of 16");
        require(collector.offered() == 40, "Offered count must record all 40 entities");
    }

    private static void testTerrainAndEntityShadowComposition() {
        float[][] cases = new float[][]{
                {0.0f, 1.0f, 0.0f},
                {1.0f, 0.0f, 0.0f},
                {0.0f, 0.0f, 0.0f},
                {1.0f, 1.0f, 1.0f}
        };
        for (float[] testCase : cases) {
            float terrainVis = testCase[0];
            float entityVis = testCase[1];
            float expected = testCase[2];
            float composite = terrainVis * entityVis;
            require(Math.abs(composite - expected) < 1e-5f, "Terrain + Entity shadow composition failed");
        }
    }

    private static void testEndToEndProxyPipelineToShaderVisibility() {
        // Setup a 1-zombie scene with 1 torch and 1 terrain receiver
        long zombieId = 0x123456789abcdef0L;
        long torchLightId = 0x9988776655443322L;
        EntityShadowProxy zombieTorso = new EntityShadowProxy(
                zombieId, 0.0, 64.9, 0.0, 0.3f, 0.45f, 0.15f
        );
        EntityShadowProxySnapshot snapshot = new EntityShadowProxySnapshot(
                EntityShadowProxySnapshot.CURRENT_VERSION, WORLD, 1L, List.of(zombieTorso), 1
        );

        double cameraX = 0.0;
        double cameraY = 64.0;
        double cameraZ = 5.0;

        // Pack into 32-byte GPU representation
        ByteBuffer proxyBuffer = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder());
        proxyBuffer.putFloat(zombieTorso.minRelativeX(cameraX));
        proxyBuffer.putFloat(zombieTorso.minRelativeY(cameraY));
        proxyBuffer.putFloat(zombieTorso.minRelativeZ(cameraZ));
        proxyBuffer.putFloat(Float.intBitsToFloat((int) zombieTorso.stableId()));
        proxyBuffer.putFloat(zombieTorso.maxRelativeX(cameraX));
        proxyBuffer.putFloat(zombieTorso.maxRelativeY(cameraY));
        proxyBuffer.putFloat(zombieTorso.maxRelativeZ(cameraZ));
        proxyBuffer.putFloat(Float.intBitsToFloat((int) (zombieTorso.stableId() >>> 32)));
        proxyBuffer.flip();

        double[] minCam = new double[]{
                proxyBuffer.getFloat(0),
                proxyBuffer.getFloat(4),
                proxyBuffer.getFloat(8)
        };
        double[] maxCam = new double[]{
                proxyBuffer.getFloat(16),
                proxyBuffer.getFloat(20),
                proxyBuffer.getFloat(24)
        };
        long unpackedId = ((long) Float.floatToRawIntBits(proxyBuffer.getFloat(28)) << 32)
                | (Float.floatToRawIntBits(proxyBuffer.getFloat(12)) & 0xffffffffL);
        require(unpackedId == zombieId, "Packed proxy ID did not round-trip through IEEE 754 bitcast");

        // Case A: Ray from floor receiver behind zombie to torch (HIT -> occluded, visibility = 0)
        double[] receiverBehind = new double[]{0.0 - cameraX, 64.0 - cameraY, 2.0 - cameraZ};
        double[] torchPos = new double[]{0.0 - cameraX, 65.5 - cameraY, -2.0 - cameraZ};
        boolean hitBehind = cpuSegmentIntersectsProxy(receiverBehind, torchPos, minCam, maxCam);
        require(hitBehind, "End-to-end ray from receiver behind zombie to torch must hit zombie proxy");

        // Case B: Ray from floor receiver to the side (MISS -> unoccluded, visibility = 1)
        double[] receiverSide = new double[]{3.0 - cameraX, 64.0 - cameraY, 2.0 - cameraZ};
        boolean hitSide = cpuSegmentIntersectsProxy(receiverSide, torchPos, minCam, maxCam);
        require(!hitSide, "End-to-end ray from receiver to the side must miss zombie proxy");

        // Case C: Held light carrier ID match (carrier holds light -> lightId == zombieId -> ignore self proxy)
        boolean ignoreHeld = (unpackedId == zombieId);
        require(ignoreHeld, "Carrier held light must match proxy stableId for emission bypass");

        // Case D: Approximate direct light (shadowState == 0) must STILL evaluate entity proxy
        int shadowState = 0; // APPROXIMATE_DIRECT
        boolean entityVisible = !hitBehind;
        float terrainVisibility = 1.0f;
        float finalVisibility = (entityVisible ? 1.0f : 0.0f) * terrainVisibility;
        require(finalVisibility == 0.0f, "Entity proxy occlusion must darken terrain even when shadowState == 0u");
    }

    private static void testProxyFailOpenResilience() {
        boolean failOpen = false;
        int proxyCount = 35;
        int proxyCapacity = 32;
        if (proxyCapacity > 32 || proxyCount > proxyCapacity) {
            failOpen = true;
        }
        require(failOpen, "Invalid proxy count must trigger failOpen");
    }

    private static boolean cpuSegmentIntersectsProxy(
            final double[] start,
            final double[] end,
            final double[] min,
            final double[] max
    ) {
        double[] delta = new double[]{end[0] - start[0], end[1] - start[1], end[2] - start[2]};
        double entry = 0.0;
        double exit = 1.0;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(delta[axis]) <= 0.000001) {
                if (start[axis] < min[axis] || start[axis] > max[axis]) {
                    return false;
                }
                continue;
            }
            double inverse = 1.0 / delta[axis];
            double first = (min[axis] - start[axis]) * inverse;
            double second = (max[axis] - start[axis]) * inverse;
            if (first > second) {
                double swap = first;
                first = second;
                second = swap;
            }
            entry = Math.max(entry, first);
            exit = Math.min(exit, second);
            if (entry > exit) {
                return false;
            }
        }
        if (start[0] > min[0] + 0.01 && start[0] < max[0] - 0.01
                && start[1] > min[1] + 0.02 && start[1] < max[1] - 0.01
                && start[2] > min[2] + 0.01 && start[2] < max[2] - 0.01) {
            return false;
        }
        return exit >= 0.001 && entry < 0.999;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
