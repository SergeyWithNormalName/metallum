package com.metallum.client.gi.source;

import com.metallum.client.gi.semantic.GiSemanticDirectFieldView;
import com.metallum.client.gi.semantic.GiSemanticWorldToken;
import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightWorldToken;

import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Dependency-free G6 -> G3 source injection and invalidation contracts. */
public final class GiDynamicSourceInjectionTests {
    private static final String OVERWORLD = "minecraft:overworld";

    private GiDynamicSourceInjectionTests() {
    }

    public static void main(final String[] args) throws Exception {
        mergeIsBoundedOrderedAndDeduplicated();
        registryWorldSidecarIsExactAndAllocationFree();
        movingSourceInvalidatesOnlyItsOldNewWorldSpaceUnion();
        snapshotWorldAndIdentityFailClosed();
        liveStampAndApiIncludeExactDynamicIdentity();
        levelExtractionReusesCameraIndependentEntityTruthAndFailsClosed();
        frozenLevelExtractionLifecycleIsObservationDriven();
        System.out.println("G6 dynamic-source injection tests passed");
    }

    private static void mergeIsBoundedOrderedAndDeduplicated() {
        LightWorldToken world = new LightWorldToken(1L, OVERWORLD);
        AdvancedLight[] initial = new AdvancedLight[GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK];
        for (int index = 0; index < initial.length; index++) {
            initial[index] = new AdvancedLight(
                    100L + index, world.generation(), LightSourceKind.BLOCK,
                    4.0 + index * 0.01, 4.0, 4.0,
                    2.0F, 1.0F, 0.5F, 0.25F, 1.0F, 100 - index
            );
        }
        Arrays.sort(initial, AdvancedLight.PRIORITY_ORDER);
        AdvancedLight replacement = entity(
                world, initial[15].stableId(), 4.0, 4.0, 4.0, 1_000
        );
        AdvancedLight added = entity(world, 900L, 5.0, 4.0, 4.0, 900);
        AdvancedLight outside = entity(world, 901L, 1_000.0, 4.0, 4.0, 2_000);
        GiDynamicSourceSnapshot dynamic = snapshot(
                world, 7L, List.of(outside, replacement, added)
        );

        AdvancedLight[] first = initial.clone();
        int firstCount = GiDirectSourceGpuResources.mergeDynamicSourcesForBrick(
                dynamic, 0.0, 0.0, 0.0, 16.0, 16.0, 16.0,
                first, initial.length
        );
        AdvancedLight[] second = initial.clone();
        int secondCount = GiDirectSourceGpuResources.mergeDynamicSourcesForBrick(
                dynamic, 0.0, 0.0, 0.0, 16.0, 16.0, 16.0,
                second, initial.length
        );

        check(firstCount == GiDirectSourceLayout.MAX_STATIC_SOURCES_PER_BRICK,
                "merged source count escaped the fixed 16-source packet");
        check(firstCount == secondCount
                        && Arrays.equals(
                                Arrays.copyOf(first, firstCount),
                                Arrays.copyOf(second, secondCount)
                        ),
                "merged G6 source selection was not deterministic");
        Set<Long> ids = new HashSet<>();
        boolean sawReplacement = false;
        boolean sawAdded = false;
        for (int index = 0; index < firstCount; index++) {
            AdvancedLight source = first[index];
            check(ids.add(source.stableId()), "stable source ID was uploaded twice");
            if (index > 0) {
                check(AdvancedLight.PRIORITY_ORDER.compare(first[index - 1], source) <= 0,
                        "merged sources lost AdvancedLight.PRIORITY_ORDER");
            }
            sawReplacement |= source.equals(replacement);
            sawAdded |= source.equals(added);
            check(source.stableId() != outside.stableId(),
                    "source outside the world-space brick entered its packet");
        }
        check(sawReplacement && sawAdded,
                "dynamic winner or stable-ID replacement was lost during merge");
    }

    private static void registryWorldSidecarIsExactAndAllocationFree() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object firstIdentity = new Object();
        LightWorldToken first = registry.openWorld(firstIdentity, OVERWORLD);
        check(registry.activeWorldIdentityForGi() == firstIdentity
                        && registry.activeWorldTokenForGi(firstIdentity, OVERWORLD) == first,
                "L3 did not expose its exact resident world identity/token sidecar");

        LightWorldToken reloaded = registry.reloadWorld(firstIdentity, OVERWORLD);
        check(reloaded != first && reloaded.generation() > first.generation()
                        && registry.activeWorldTokenForGi(firstIdentity, OVERWORLD) == reloaded,
                "same-world L3 reload did not rotate the exact source token");

        Object replacementIdentity = new Object();
        LightWorldToken replacement = registry.openWorld(replacementIdentity, OVERWORLD);
        check(registry.activeWorldIdentityForGi() == replacementIdentity
                        && registry.activeWorldTokenForGi(firstIdentity, OVERWORLD) == null
                        && registry.activeWorldTokenForGi(
                        replacementIdentity, OVERWORLD) == replacement,
                "same-dimension ClientLevel replacement retained the old G2/L3 pairing");
    }

    private static void movingSourceInvalidatesOnlyItsOldNewWorldSpaceUnion() {
        LightWorldToken world = new LightWorldToken(1L, OVERWORLD);
        UUID sourceId = new UUID(0x6000000000000000L, 42L);
        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(4, 3L);
        collector.beginTick(world, 1L);
        collector.offer(GiDynamicSourceCollector.entityAtWorldPosition(
                world, sourceId, 8.0, 8.0, 8.0,
                1.0F, 1.0F, 0.5F, 0.25F, 1.0F, 200
        ));
        GiDynamicSourceSnapshot previous = collector.finishTick();
        collector.beginTick(world, 2L);
        collector.offer(GiDynamicSourceCollector.entityAtWorldPosition(
                world, sourceId, 20.0, 8.0, 8.0,
                1.0F, 1.0F, 0.5F, 0.25F, 1.0F, 200
        ));
        GiDynamicSourceSnapshot next = collector.finishTick();
        int[] origins = new int[9];

        GiDirectSourceEpoch beforeEpoch = epoch(world, 1L);
        GiDirectSourceEpoch afterEpoch = epoch(world, 2L);
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(beforeEpoch);
        queue.rotateEpoch(afterEpoch);
        int affected = 0;
        for (int brick = 0; brick < GiDirectSourceLayout.TOTAL_BRICKS; brick++) {
            if (GiDirectSourceCoordinator.dynamicTransitionAffectsBrick(
                    previous, origins, next, origins, brick
            )) {
                queue.enqueue(afterEpoch, brick, 2L);
                affected++;
            }
        }
        // Block-scale C0: the old source lies on an 8 m brick corner (8 bricks), the new source
        // crosses to one X brick while remaining on Y/Z boundaries (4 bricks). C1 and C2 each
        // retain one shared coarse brick, for an exact 8 + 4 + 1 + 1 = 14-brick union.
        check(affected == 14,
                "small source motion escaped its exact block-scale old/new union: " + affected);
        check(affected <= GiDirectSourceLayout.MAX_DRAIN_PER_FRAME
                        && affected < GiDirectSourceLayout.TOTAL_BRICKS,
                "dynamic motion fell back to a full 192-brick rebuild");

        int[] drained = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        int count = queue.drainTo(afterEpoch, 2L, drained);
        queue.completeBatch(afterEpoch, drained, count);
        check(count == affected && queue.telemetry().pending() == 0,
                "bounded dynamic transition could not converge in one render batch");
        check(!GiDirectSourceCoordinator.dynamicSourceIdentityChanged(
                        next.epoch().sourceEpoch(), next.sourceHash(), next),
                "unchanged second source tick unnecessarily rotated the G3 source epoch");
    }

    private static void snapshotWorldAndIdentityFailClosed() {
        LightWorldToken world = new LightWorldToken(3L, OVERWORLD);
        GiSemanticWorldToken semantic = new GiSemanticWorldToken(30L, 4L, 5L, OVERWORLD);
        GiDynamicSourceSnapshot first = snapshot(
                world, 10L, List.of(entity(world, 700L, 1.0, 1.0, 1.0, 10))
        );
        GiDirectSourceCoordinator.validateDynamicSourceWorld(semantic, world, first);
        check(semantic.worldGeneration() != world.generation(),
                "G2/L3 independent-generation fixture accidentally shares a counter");

        LightWorldToken otherWorld = new LightWorldToken(4L, OVERWORLD);
        GiDynamicSourceSnapshot wrong = snapshot(
                otherWorld, 11L, List.of(entity(otherWorld, 701L, 1.0, 1.0, 1.0, 10))
        );
        expectIllegalArgument(() -> GiDirectSourceCoordinator.validateDynamicSourceWorld(
                semantic, world, wrong
        ));

        GiDynamicSourceSnapshot changedWithoutEpoch = snapshot(
                world, first.epoch().sourceEpoch(),
                List.of(entity(world, 700L, 2.0, 1.0, 1.0, 10))
        );
        expectIllegalArgument(() -> GiDirectSourceCoordinator.dynamicSourceIdentityChanged(
                first.epoch().sourceEpoch(), first.sourceHash(), changedWithoutEpoch
        ));
        expectIllegalArgument(() -> GiDirectSourceCoordinator.dynamicSourceIdentityChanged(
                first.epoch().sourceEpoch() + 1L, first.sourceHash(), first
        ));
    }

    private static void liveStampAndApiIncludeExactDynamicIdentity()
            throws ReflectiveOperationException {
        LightWorldToken world = new LightWorldToken(1L, OVERWORLD);
        GiDirectSourceEpoch epoch = epoch(world, 7L);
        int[] origins = {0, 0, 0, -32, -32, -32, -64, -64, -64};
        long baseline = GiDirectSourceCoordinator.liveTransportSourceStamp(
                epoch, 20L, 30L, 0, origins
        );
        check(baseline != 0L
                        && baseline != GiDirectSourceCoordinator.liveTransportSourceStamp(
                                epoch, 21L, 30L, 0, origins)
                        && baseline != GiDirectSourceCoordinator.liveTransportSourceStamp(
                                epoch, 20L, 31L, 0, origins)
                        && baseline != GiDirectSourceCoordinator.liveTransportSourceStamp(
                                epoch, 20L, 30L, 1, origins),
                "live source stamp omitted dynamic epoch/hash or cascade identity");

        GiDirectSourceCoordinator.class.getDeclaredMethod(
                "encodeFrame",
                MemorySegment.class, MemorySegment.class,
                GiSemanticDirectFieldView.class, EnvironmentDescriptor.class,
                AdvancedLightRegistry.class, long.class
        );
        GiDirectSourceCoordinator.class.getDeclaredMethod(
                "encodeFrame",
                MemorySegment.class, MemorySegment.class,
                GiSemanticDirectFieldView.class, EnvironmentDescriptor.class,
                AdvancedLightRegistry.class, GiDynamicSourceSnapshot.class, long.class
        );
        GiDirectSourceCoordinator.class.getDeclaredMethod(
                "liveTransportSource", int.class, long.class, long.class
        );
    }

    private static void levelExtractionReusesCameraIndependentEntityTruthAndFailsClosed()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/lighting/"
                        + "LevelExtractorAdvancedLightMixin.java"
        ));
        int entityFactory = source.indexOf(
                "AdvancedLight light = MinecraftLightPolicy.entity("
        );
        int dynamicOffer = source.indexOf("giCollector.offer(light);");
        int cameraHeldDedupe = source.indexOf(
                "MinecraftLightPolicy.cameraHeldStableIdMatches("
        );
        int visibilityDecision = source.indexOf(
                "return original.call(extractor, entity, frustum"
        );
        check(entityFactory >= 0
                        && dynamicOffer > entityFactory
                        && cameraHeldDedupe > dynamicOffer
                        && visibilityDecision > cameraHeldDedupe,
                "G6 no longer reuses body-space entity truth before frustum/deduplication");
        check(count(source, "giCollector.offer(light);") == 1
                        && source.contains(
                        "GiLiveRuntime.reportInvalid(\"dynamic entity source extraction failed\")"),
                "G6 entity reuse is ambiguous or extraction is not fail-closed");

        int extractionMethod = source.indexOf(
                "private boolean metallum$extractDynamicLightBeforeVisibilityFilter"
        );
        int extractionStart = source.indexOf(
                "BoundedDynamicLightCollector collector = this.metallum$dynamicLights;",
                extractionMethod
        );
        int extractionEnd = source.indexOf(
                "BoundedEntityShadowProxyCollector proxyCollector", extractionStart
        );
        check(extractionStart >= 0 && extractionEnd > extractionStart,
                "G6 entity extraction method is missing");
        String extraction = source.substring(extractionStart, extractionEnd);
        check(extraction.contains("MinecraftLightPolicy.worldSpaceEntityPartialTick(")
                        && extraction.contains(
                        "currentLevel.tickRateManager().runsNormally(),")
                        && extraction.contains(
                        "deltaTracker.getGameTimeDeltaPartialTick(true)")
                        && !extraction.contains("isEntityFrozen(entity)"),
                "frozen player extraction can reuse a changing render interpolation residual");

        int cameraHeldStart = source.indexOf(
                "private void metallum$offerCameraHeldLight"
        );
        int cameraHeldEnd = source.indexOf(
                "private static CameraHeldLightTracker.CameraPose metallum$firstPersonHeldPose",
                cameraHeldStart
        );
        check(cameraHeldStart >= 0 && cameraHeldEnd > cameraHeldStart,
                "camera-held extraction method is missing");
        String cameraHeld = source.substring(cameraHeldStart, cameraHeldEnd);
        check(!cameraHeld.contains("GiDynamicSourceCollector")
                        && !cameraHeld.contains("giCollector")
                        && !cameraHeld.contains("heldAtEntityWorldPosition")
                        && !source.contains(
                        "GiDynamicSourceCollector.heldAtEntityWorldPosition("),
                "G6 camera hook still allocates a second held-source model object");
    }

    private static void frozenLevelExtractionLifecycleIsObservationDriven() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/metallum/mixin/lighting/"
                        + "LevelExtractorAdvancedLightMixin.java"
        ));
        int beginStart = source.indexOf("private void metallum$beginGiDynamicSourceTick");
        int beginEnd = source.indexOf("private void metallum$offerCameraHeldLight", beginStart);
        check(beginStart >= 0 && beginEnd > beginStart,
                "G6 extraction lifecycle method is missing");
        String begin = source.substring(beginStart, beginEnd);
        check(begin.contains("collector.nextObservationTick(world)")
                        && begin.contains("collector.beginTick(world, observationTick);")
                        && begin.contains(
                        "this.metallum$giDynamicOpenTick = observationTick;")
                        && begin.contains("this.metallum$giDynamicTickCollector = collector;")
                        && !begin.contains("getGameTime()")
                        && !begin.contains("metallum$giDynamicLastWorldTick")
                        && !source.contains("metallum$giDynamicSourceTick")
                        && !begin.contains("Math.incrementExact")
                        && !begin.contains("collector.isWorldOpen(world)")
                        && !begin.contains("collector.snapshot()"),
                "G6 extraction no longer uses its collector tick as the sole publication cadence");

        int commitStart = source.indexOf("private void metallum$commitDynamicLightFrame");
        int commitEnd = source.indexOf("private static String metallum$dimensionId", commitStart);
        String commit = source.substring(commitStart, commitEnd);
        check(count(commit, "GiLiveRuntime.publishDynamicSources(") == 1
                        && commit.contains("giCollector.finishTick(),")
                        && !commit.contains("this.metallum$giDynamicSources.snapshot(),"),
                "G6 extraction commit retained a stale snapshot-only publication path");
    }

    private static int count(final String source, final String needle) {
        int total = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(needle, cursor)) >= 0) {
            total++;
            cursor += needle.length();
        }
        return total;
    }

    private static GiDynamicSourceSnapshot snapshot(
            final LightWorldToken world,
            final long sourceEpoch,
            final List<AdvancedLight> offered
    ) {
        List<AdvancedLight> ordered = new ArrayList<>(offered);
        ordered.sort(GiDynamicSourceSnapshot.SOURCE_ORDER);
        return new GiDynamicSourceSnapshot(
                GiDynamicSourceSnapshot.CURRENT_VERSION,
                new GiDynamicSourceEpoch(world, sourceEpoch),
                0L,
                Math.max(1, ordered.size()),
                2L,
                ordered,
                GiDynamicSourceSnapshot.computeSourceHash(world, ordered)
        );
    }

    private static AdvancedLight entity(
            final LightWorldToken world,
            final long stableId,
            final double x,
            final double y,
            final double z,
            final int priority
    ) {
        return new AdvancedLight(
                stableId, world.generation(), LightSourceKind.ENTITY,
                x, y, z, 2.0F,
                1.0F, 0.5F, 0.25F, 1.0F, priority
        );
    }

    private static GiDirectSourceEpoch epoch(
            final LightWorldToken world,
            final long sourceEpoch
    ) {
        return new GiDirectSourceEpoch(
                world.generation(), 1L, 1L, 1L, 1L, 1L,
                world, sourceEpoch, 1L
        );
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
