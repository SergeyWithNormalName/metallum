package com.metallum.client.gi.source;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.EnvironmentDescriptor;
import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.semantic.GiSemanticDirectFieldView;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightSectionCandidate;
import com.metallum.client.lighting.LightSectionTask;
import com.metallum.client.lighting.LightTemplate;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.StaticLightSectionScanner;

import java.util.Arrays;
import java.util.List;

/** Lightweight CPU contract checks; deliberately executable without a live renderer. */
public final class GiDirectSourceCpuTests {
    private GiDirectSourceCpuTests() {
    }

    public static void main(final String[] args) {
        negativeCoordinateMath();
        staticOrderingAndExclusion();
        dynamicFramesDoNotRotateStaticEpoch();
        idempotentStaticPublicationsDoNotRotateStaticEpoch();
        frozenTransportUsesActualStaticRegistryIdentity();
        provisionalLiveMasksRejectDesiredStaticAndEnvironmentDrift();
        cumulativeLiveMasksRequireCurrentExactCascadeAcknowledgement();
        transferredLiveMasksHaveDistinctDurableOwnership();
        interactiveFrozenPreparationUsesWallTime();
        environmentQuantization();
        coherentEnvironmentObservationRejectsWeatherHybrid();
        staleEpochIsRejected();
        productionRootRelationOnlyAuthorizesMonotonicLifecycleAdvance();
        worldRootRotationAllowsChildEpochResetOnly();
        originOnlyScrollDoesNotBecomePhysicalSourceDirt();
        metadataOnlyRolloverRequiresByteIdenticalInputs();
        liveInputSuccessorRequiresAnExactStableGrid();
        liveInputRebasePreservesQueuedBacklog();
        queueBoundsCoalescingAndStarvation();
        acceptedFailureRetriesAndStaleCompletionCannotPublish();
        sameCommandProjectedSourceSurvivesExactIdentityChurn();
        rotatedLifetimeCountersCanProveASettledCurrentField();
    }

    private static void negativeCoordinateMath() {
        // C0 spans 32 one-block cells: with origin -64 its final in-bounds block is -33.
        int near = GiDirectSourceLayout.brickIdForWorld(0, -64, -64, -64, -33, -33, -33);
        check(near == GiDirectSourceLayout.brickId(0, 3, 3, 3), "negative world coordinate mapping");
        check(GiDirectSourceLayout.brickIdForWorld(0, -64, -64, -64, -65, -1, -1) == -1,
                "negative outside mapping");
        check(GiDirectSourceLayout.brickMinWorldBlock(0, -64, 0) == -64, "negative brick origin");
    }

    private static void staticOrderingAndExclusion() {
        LightWorldToken world = new LightWorldToken(1L, "minecraft:overworld");
        AdvancedLight low = block(9L, 1, 1.0, 1.0, 1.0);
        AdvancedLight high = block(4L, 8, 2.0, 2.0, 2.0);
        AdvancedLight entity = new AdvancedLight(7L, 1L, LightSourceKind.ENTITY,
                2.0, 2.0, 2.0, 4.0F, 1.0F, 1.0F, 1.0F, 1.0F, 99);
        GiStaticSourceSnapshot.WorldAabb bounds = new GiStaticSourceSnapshot.WorldAabb(
                0.0, 0.0, 0.0, 8.0, 8.0, 8.0
        );
        GiStaticSourceSnapshot first = GiStaticSourceSnapshot.select(world, 1L, bounds,
                List.of(low, entity, high), 16);
        GiStaticSourceSnapshot second = GiStaticSourceSnapshot.select(world, 1L, bounds,
                List.of(high, low, entity), 16);
        check(first.sources().size() == 2, "dynamic source exclusion");
        check(first.sources().getFirst().stableId() == high.stableId(), "priority ordering");
        check(first.sources().equals(second.sources()), "view-independent ordering");
    }

    private static void environmentQuantization() {
        GiEnvironmentSource a = new GiEnvironmentSource(7L, 1.0F, 0.0F, 0.0F,
                2.0F, 1.0F, 0.5F, 0.4F, 0.3F, 0.2F);
        GiEnvironmentSource b = new GiEnvironmentSource(8L, 1.0F, 0.000001F, 0.0F,
                2.0F, 1.0F, 0.5F, 0.4F, 0.3F, 0.2F);
        check(Math.abs(a.toLightX() - 1.0F) < 0.00001F, "source direction normalization");
        check(a.quantizedDigest() == b.quantizedDigest(), "quantized environment digest");
        check(GiEnvironmentSource.nextEpoch(a, b) == a.epoch(), "stable environment epoch");
    }

    private static void coherentEnvironmentObservationRejectsWeatherHybrid() {
        EnvironmentDescriptor clear = environment(1.0F, 1.0F, 0.0F);
        EnvironmentDescriptor hybrid = environment(1.0F, 1.0F, 1.0F);
        EnvironmentDescriptor coherentRain = environment(0.72F, 0.76F, 1.0F);
        GiEnvironmentObservationLatch latch = new GiEnvironmentObservationLatch();
        Object firstWorld = new Object();
        Object secondWorld = new Object();

        check(latch.observe(firstWorld, clear, false) == null,
                "initial G3 environment escaped before a coherent producer boundary");
        check(latch.observe(firstWorld, clear, true) == clear,
                "initial coherent producer boundary did not establish G3 authority");
        check(latch.observe(firstWorld, hybrid, false) == clear,
                "mixed-boundary weather observation replaced the coherent G3 source");
        check(latch.observe(firstWorld, coherentRain, true) == coherentRain,
                "producer boundary did not admit the final coherent weather source");
        check(latch.observe(secondWorld, clear, false) == null,
                "ClientLevel identity change reused a prior world's environment authority");
        check(latch.observe(secondWorld, clear, true) == clear,
                "fresh ClientLevel boundary did not restore environment authority");
    }

    private static EnvironmentDescriptor environment(
            final float skyTint,
            final float skyFactor,
            final float rain
    ) {
        return EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.45F,
                skyTint, skyTint, skyTint,
                skyFactor,
                0.2F, 0.2F, 0.2F,
                rain, 0.0F, 1.0F
        );
    }

    private static void dynamicFramesDoNotRotateStaticEpoch() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        LightWorldToken world = registry.openWorld(new Object(), "minecraft:overworld");
        GiStaticSourceState before = registry.staticSourceStateForGi(world.dimensionId());
        registry.publishDynamicFrame(world, List.of(), 0);
        GiStaticSourceState after = registry.staticSourceStateForGi(world.dimensionId());
        check(before != null && before.equals(after),
                "dynamic L3 frame rotated the camera-independent G3 source epoch");
    }

    private static void idempotentStaticPublicationsDoNotRotateStaticEpoch() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object worldIdentity = new Object();
        LightWorldToken world = registry.openWorld(worldIdentity, "minecraft:overworld");
        GiStaticSourceState initial = registry.staticSourceStateForGi(world.dimensionId());
        LightSectionTask empty = registry.beginSectionTask(
                worldIdentity, world.dimensionId(), 17L
        );
        check(registry.publishAccepted(scan(empty, null)), "empty static section was rejected");
        GiStaticSourceState afterEmpty = registry.staticSourceStateForGi(world.dimensionId());
        check(initial != null && initial.equals(afterEmpty),
                "empty static publication rotated the physical G3 source epoch");

        LightTemplate emitter = new LightTemplate(
                LightSourceKind.BLOCK, 1.5, 2.5, 3.5,
                8.0F, 1.0F, 0.5F, 0.25F, 1.0F, 15
        );
        LightSectionTask first = registry.beginSectionTask(
                worldIdentity, world.dimensionId(), 17L
        );
        check(registry.publishAccepted(scan(first, emitter)),
                "initial static emitter section was rejected");
        GiStaticSourceState afterFirst = registry.staticSourceStateForGi(world.dimensionId());
        LightSectionTask duplicate = registry.beginSectionTask(
                worldIdentity, world.dimensionId(), 17L
        );
        check(registry.publishAccepted(scan(duplicate, emitter)),
                "idempotent static emitter section was rejected");
        check(afterFirst != null && afterFirst.equals(
                        registry.staticSourceStateForGi(world.dimensionId())),
                "idempotent static publication rotated the physical G3 source epoch");
    }

    private static LightSectionCandidate scan(
            final LightSectionTask task,
            final LightTemplate emitter
    ) {
        return StaticLightSectionScanner.scan(
                task, 0, 0, 0, AdvancedLightRegistry.MAX_LIGHTS_PER_SECTION,
                (localIndex, x, y, z) -> localIndex == 0 ? emitter : null
        );
    }

    private static void frozenTransportUsesActualStaticRegistryIdentity() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        Object worldIdentity = new Object();
        LightWorldToken world = registry.openWorld(worldIdentity, "minecraft:overworld");
        registry.recordBlockChange(
                worldIdentity, world.dimensionId(), 7L, 3, 101L, null
        );
        registry.recordBlockChange(
                worldIdentity, world.dimensionId(), 7L, 4, 102L, null
        );
        GiStaticSourceState frozen = registry.staticSourceStateForGi(world.dimensionId());
        check(frozen != null && frozen.registryEpoch() > 1L,
                "test registry did not separate its actual epoch from G3's logical epoch");
        GiDirectSourceEpoch logical = new GiDirectSourceEpoch(
                1L, 1L, 1L, 1L, 1L, 1L, world, 1L, 1L
        );
        check(GiDirectSourceCoordinator.frozenStaticSourceIdentityStillCurrent(
                        logical, frozen, registry),
                "frozen G4 source confused G3's logical epoch with the registry epoch");
        registry.recordBlockChange(
                worldIdentity, world.dimensionId(), 7L, 5, 103L, null
        );
        check(!GiDirectSourceCoordinator.frozenStaticSourceIdentityStillCurrent(
                        logical, frozen, registry),
                "real post-freeze registry drift was not rejected");
    }

    private static void provisionalLiveMasksRejectDesiredStaticAndEnvironmentDrift() {
        long admittedStatic = 17L;
        long admittedEnvironment = 0x6a11ce5L;
        check(GiDirectSourceCoordinator.liveAffectedMaskIdentityMatches(
                        admittedStatic, admittedEnvironment,
                        admittedStatic, admittedEnvironment),
                "matching G3 static/environment identity rejected a local affected mask");
        check(!GiDirectSourceCoordinator.liveAffectedMaskIdentityMatches(
                        admittedStatic, admittedEnvironment,
                        admittedStatic + 1L, admittedEnvironment),
                "desired static registry drift reused a stale G3 affected mask");
        check(!GiDirectSourceCoordinator.liveAffectedMaskIdentityMatches(
                        admittedStatic, admittedEnvironment,
                        admittedStatic, admittedEnvironment + 1L),
                "desired environment drift reused a stale G3 affected mask");
        check(!GiDirectSourceCoordinator.liveAffectedMaskIdentityMatches(
                        0L, admittedEnvironment,
                        admittedStatic, admittedEnvironment),
                "an unadmitted G3 identity exposed a provisional affected mask");
    }

    private static void cumulativeLiveMasksRequireCurrentExactCascadeAcknowledgement() {
        long brickX = 1L << 7;
        long brickY = 1L << 19;

        // G6 retains basis A. G3 publishes B for x, then rotates to C and publishes y before G6
        // can copy B's per-epoch mask. The cumulative C handoff must still contain x.
        long afterB = GiDirectSourceCoordinator.cumulativeAffectedMaskAfterMark(0L, brickX);
        long afterC = GiDirectSourceCoordinator.cumulativeAffectedMaskAfterMark(afterB, brickY);
        check(afterC == (brickX | brickY),
                "G3 B -> C rotation forgot a brick still stale relative to G6 basis A");

        // A late completion for B cannot acknowledge C: either the identity changed or the
        // snapshot no longer equals the complete cumulative mask.
        check(GiDirectSourceCoordinator.cumulativeAffectedMaskAfterAcknowledgement(
                        afterC, afterB, false, true) == afterC,
                "stale G6 B completion cleared the newer C cumulative mask");
        check(GiDirectSourceCoordinator.cumulativeAffectedMaskAfterAcknowledgement(
                        afterC, afterB, true, true) == afterC,
                "partial cumulative snapshot acknowledged intervening G3 dirt");
        check(GiDirectSourceCoordinator.cumulativeAffectedMaskAfterAcknowledgement(
                        afterC, afterC, true, false) == afterC,
                "non-exact G6 cascade acknowledged cumulative G3 dirt");
        check(GiDirectSourceCoordinator.cumulativeAffectedMaskAfterAcknowledgement(
                        afterC, afterC, true, true) == 0L,
                "current fully-exact G6 C cascade did not acknowledge its cumulative mask");

        long[] cascades = {afterC, brickX, 0L};
        cascades[1] = GiDirectSourceCoordinator.cumulativeAffectedMaskAfterAcknowledgement(
                cascades[1], brickX, true, true
        );
        check(cascades[0] == afterC && cascades[1] == 0L && cascades[2] == 0L,
                "per-cascade G6 acknowledgement changed another cascade's cumulative truth");
        check(GiDirectSourceCoordinator.cumulativeAffectedMaskAfterMark(afterC, -1L) == -1L,
                "structural G3 reset did not conservatively affect the full cascade");

        GiDirectSourceEpoch epoch = epoch(7L, 11L);
        int[] origins = {-64, -32, -64, -128, -64, -128, -256, -128, -256};
        long current = GiDirectSourceCoordinator.liveTransportSourceStamp(
                epoch, 13L, 17L, 0, origins
        );
        int[] movedOrigins = origins.clone();
        movedOrigins[0]++;
        check(current != GiDirectSourceCoordinator.liveTransportSourceStamp(
                        epoch(8L, 11L), 13L, 17L, 0, origins)
                        && current != GiDirectSourceCoordinator.liveTransportSourceStamp(
                        epoch, 14L, 17L, 0, origins)
                        && current != GiDirectSourceCoordinator.liveTransportSourceStamp(
                        epoch, 13L, 18L, 0, origins)
                        && current != GiDirectSourceCoordinator.liveTransportSourceStamp(
                        epoch, 13L, 17L, 0, movedOrigins),
                "G3 live identity did not reject epoch/dynamic/hash/origin acknowledgement races");
    }

    private static void transferredLiveMasksHaveDistinctDurableOwnership() {
        long brickX = 1L << 7;
        long brickY = 1L << 19;
        long audit = GiDirectSourceCoordinator.cumulativeAffectedMaskAfterMark(0L, brickX);
        long transfer = GiDirectSourceCoordinator.cumulativeAffectedMaskAfterMark(0L, brickX);

        transfer = GiDirectSourceCoordinator.untransferredAffectedMaskAfterPlan(
                transfer, brickX, true, true
        );
        check(transfer == 0L && audit == brickX,
                "accepted G6 plan did not transfer work independently of cumulative audit");

        // A later change to the same bit is new work even though the historical audit bit was
        // already set; B -> C on another bit must likewise survive stale/failed transfer races.
        transfer = GiDirectSourceCoordinator.cumulativeAffectedMaskAfterMark(transfer, brickX);
        transfer = GiDirectSourceCoordinator.cumulativeAffectedMaskAfterMark(transfer, brickY);
        check(GiDirectSourceCoordinator.untransferredAffectedMaskAfterPlan(
                        transfer, brickX, true, true) == (brickX | brickY)
                        && GiDirectSourceCoordinator.untransferredAffectedMaskAfterPlan(
                        transfer, transfer, false, true) == (brickX | brickY)
                        && GiDirectSourceCoordinator.untransferredAffectedMaskAfterPlan(
                        transfer, transfer, true, false) == (brickX | brickY),
                "stale, partial, or failed G6 plan cleared untransferred A/B/C dirt");
        check(GiDirectSourceCoordinator.untransferredAffectedMaskAfterPlan(
                        transfer, brickX | brickY, true, true) == 0L,
                "current accepted G6 plan did not transfer the complete A/B/C union");
        check(audit == brickX,
                "plan ownership transfer unexpectedly acknowledged cumulative audit truth");
    }

    private static void interactiveFrozenPreparationUsesWallTime() {
        long tenSeconds = GiDirectSourceCoordinator.INTERACTIVE_FROZEN_INPUT_SETTLE_NANOS;
        check(!GiDirectSourceCoordinator.frozenInputSettled(
                        true, 0L, 10_000L, 7L, 7L + tenSeconds - 1L),
                "interactive G3 froze before ten real seconds elapsed");
        check(GiDirectSourceCoordinator.frozenInputSettled(
                        true, 0L, 10_000L, 7L, 7L + tenSeconds),
                "interactive G3 did not freeze after ten real seconds");
        check(!GiDirectSourceCoordinator.frozenInputSettled(
                        false, 10L, 609L, 0L, Long.MAX_VALUE),
                "benchmark G3 ignored the exact 600-frame settle gate");
        check(GiDirectSourceCoordinator.frozenInputSettled(
                        false, 10L, 610L, 0L, 0L),
                "benchmark G3 did not retain the exact 600-frame settle gate");
        check(!GiDirectSourceCoordinator.isStructuralDrift("content")
                        && !GiDirectSourceCoordinator.isStructuralDrift("static_sources"),
                "late Sodium content incorrectly restarted the camera-stability timer");
        check(GiDirectSourceCoordinator.isStructuralDrift("origin")
                        && GiDirectSourceCoordinator.isStructuralDrift("clipmap"),
                "camera/clipmap drift did not restart the camera-stability timer");
    }

    private static void staleEpochIsRejected() {
        GiDirectSourceEpoch first = epoch(1L, 1L);
        GiDirectSourceEpoch next = epoch(2L, 2L);
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(first);
        check(queue.enqueue(first, 0, 1L) == GiDirectDirtyQueue.OfferResult.ENQUEUED, "first enqueue");
        queue.rotateEpoch(next);
        check(queue.enqueue(first, 0, 2L) == GiDirectDirtyQueue.OfferResult.STALE, "stale epoch rejection");
        check(queue.telemetry().discarded() == 2L && queue.telemetry().algebraIsExact(),
                "rotation and stale discard accounting");
    }

    private static void productionRootRelationOnlyAuthorizesMonotonicLifecycleAdvance() {
        String dimension = "minecraft:overworld";
        LightWorldToken staticRoot = new LightWorldToken(13L, dimension);
        GiDirectSourceEpoch previous = new GiDirectSourceEpoch(
                5L, 7L, 11L, 17L, 19L, 23L,
                staticRoot, 29L, 31L
        );
        com.metallum.client.gi.semantic.GiSemanticWorldToken same =
                new com.metallum.client.gi.semantic.GiSemanticWorldToken(
                        5L, 7L, 11L, dimension
                );
        check(GiDirectSourceCoordinator.productionRootRelation(
                        previous, same, staticRoot
                ) == GiDirectSourceCoordinator.ProductionRootRelation.SAME,
                "unchanged G3 production root was not stable");
        check(GiDirectSourceCoordinator.productionRootRelation(
                        previous,
                        new com.metallum.client.gi.semantic.GiSemanticWorldToken(
                                6L, 1L, 1L, dimension
                        ),
                        new LightWorldToken(1L, dimension)
                ) == GiDirectSourceCoordinator.ProductionRootRelation.ADVANCE
                        && GiDirectSourceCoordinator.productionRootRelation(
                        previous,
                        new com.metallum.client.gi.semantic.GiSemanticWorldToken(
                                5L, 8L, 11L, dimension
                        ), staticRoot
                ) == GiDirectSourceCoordinator.ProductionRootRelation.ADVANCE
                        && GiDirectSourceCoordinator.productionRootRelation(
                        previous, same, new LightWorldToken(14L, dimension)
                ) == GiDirectSourceCoordinator.ProductionRootRelation.ADVANCE,
                "new world/resource/L3 root did not authorize a full child reset");
        check(GiDirectSourceCoordinator.productionRootRelation(
                        previous,
                        new com.metallum.client.gi.semantic.GiSemanticWorldToken(
                                4L, 99L, 99L, dimension
                        ), staticRoot
                ) == GiDirectSourceCoordinator.ProductionRootRelation.STALE
                        && GiDirectSourceCoordinator.productionRootRelation(
                        previous,
                        new com.metallum.client.gi.semantic.GiSemanticWorldToken(
                                5L, 6L, 11L, dimension
                        ), staticRoot
                ) == GiDirectSourceCoordinator.ProductionRootRelation.STALE
                        && GiDirectSourceCoordinator.productionRootRelation(
                        previous,
                        new com.metallum.client.gi.semantic.GiSemanticWorldToken(
                                5L, 7L, 10L, dimension
                        ), staticRoot
                ) == GiDirectSourceCoordinator.ProductionRootRelation.STALE
                        && GiDirectSourceCoordinator.productionRootRelation(
                        previous, same, new LightWorldToken(12L, dimension)
                ) == GiDirectSourceCoordinator.ProductionRootRelation.STALE
                        && GiDirectSourceCoordinator.productionRootRelation(
                        previous,
                        new com.metallum.client.gi.semantic.GiSemanticWorldToken(
                                5L, 7L, 11L, "minecraft:the_nether"
                        ), new LightWorldToken(13L, "minecraft:the_nether")
                ) == GiDirectSourceCoordinator.ProductionRootRelation.STALE,
                "regressed or mismatched G3 production root was authorized as a reset");
    }

    private static void worldRootRotationAllowsChildEpochResetOnly() {
        GiSemanticController semantic = new GiSemanticController();
        Object worldIdentity = new Object();
        semantic.openWorld(worldIdentity, "minecraft:overworld", 0, 64, 0);
        semantic.advanceMaterialAtlasEpoch(List.of());
        check(semantic.updateCamera(worldIdentity, 128, 64, 0),
                "G2 pre-reload field did not advance its clipmap child epoch");
        GiSemanticDirectFieldView firstField = semantic.directField(worldIdentity);
        check(firstField != null, "G2 pre-reload direct field is unavailable");
        LightWorldToken firstLightWorld = new LightWorldToken(7L, "minecraft:overworld");
        GiDirectSourceEpoch first = new GiDirectSourceEpoch(
                firstField.world().worldGeneration(), firstField.world().resourceEpoch(),
                firstField.world().materialEpoch(), firstField.clipmapGeneration(),
                firstField.paletteGeneration(), firstField.contentGeneration(),
                firstLightWorld, 31L, 37L
        );

        // Sodium destroys and recreates RenderSectionManager during resource reload. Its G2
        // lifecycle mixin therefore closes and reopens the same ClientLevel identity, creating a
        // fresh field assembler whose child generations restart below the prior field's values.
        semantic.closeWorld(worldIdentity);
        semantic.openWorld(worldIdentity, "minecraft:overworld", 0, 64, 0);
        GiSemanticDirectFieldView reloadedField = semantic.directField(worldIdentity);
        check(reloadedField != null, "G2 post-reload direct field is unavailable");
        GiDirectSourceEpoch reloaded = new GiDirectSourceEpoch(
                reloadedField.world().worldGeneration(), reloadedField.world().resourceEpoch(),
                reloadedField.world().materialEpoch(), reloadedField.clipmapGeneration(),
                reloadedField.paletteGeneration(), reloadedField.contentGeneration(),
                new LightWorldToken(8L, "minecraft:overworld"), 1L, 1L
        );
        check(reloaded.g2WorldGeneration() > first.g2WorldGeneration()
                        && reloaded.g2ClipmapGeneration() < first.g2ClipmapGeneration()
                        && reloaded.g2PaletteGeneration() < first.g2PaletteGeneration()
                        && reloaded.g2ContentGeneration() < first.g2ContentGeneration(),
                "G2 close/open did not reproduce the root-advance child-reset tuple");
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(first);
        check(queue.enqueue(first, 0, 1L) == GiDirectDirtyQueue.OfferResult.ENQUEUED,
                "pre-reload G3 enqueue failed");
        int[] batch = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        check(queue.drainTo(first, 1L, batch) == 1,
                "pre-reload G3 batch was not owned in flight");

        // Native G3 treats worldGeneration as the root identity: when it advances, every
        // subordinate counter may restart. Queue rotation must discard the old ownership with
        // the same rule instead of rejecting the resource-reload tuple before native admission.
        queue.rotateEpoch(reloaded);
        check(queue.completeBatch(first, batch, 1)
                        == GiDirectDirtyQueue.CompletionResult.STALE_EPOCH,
                "pre-reload G3 completion survived a world-root rotation");
        check(queue.telemetry().discarded() == 1L
                        && queue.telemetry().pending() == 0
                        && queue.telemetry().inFlight() == 0
                        && queue.telemetry().algebraIsExact(),
                "world-root G3 rotation lost in-flight discard algebra");
        check(queue.enqueue(reloaded, 1, 2L) == GiDirectDirtyQueue.OfferResult.ENQUEUED,
                "post-reload G3 epoch was not admitted");

        GiDirectSourceEpoch sameRootRegression = new GiDirectSourceEpoch(
                reloaded.g2WorldGeneration(), reloaded.g2ResourceEpoch(),
                reloaded.g2MaterialEpoch(), reloaded.g2ClipmapGeneration(),
                reloaded.g2PaletteGeneration(), reloaded.g2ContentGeneration(),
                reloaded.staticLightWorld(), reloaded.staticLightRegistryEpoch(),
                reloaded.environmentEpoch() + 1L
        );
        GiDirectSourceEpoch sameRootNewer = new GiDirectSourceEpoch(
                reloaded.g2WorldGeneration(), reloaded.g2ResourceEpoch(),
                reloaded.g2MaterialEpoch(), reloaded.g2ClipmapGeneration(),
                reloaded.g2PaletteGeneration(), reloaded.g2ContentGeneration() + 1L,
                reloaded.staticLightWorld(), reloaded.staticLightRegistryEpoch(),
                reloaded.environmentEpoch()
        );
        queue.rotateEpoch(sameRootNewer);
        try {
            queue.rotateEpoch(sameRootRegression);
            throw new AssertionError("same-root G3 child regression was accepted");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("previous=")
                            && expected.getMessage().contains("next="),
                    "G3 epoch failure omitted the diagnostic tuples");
        }
        check(queue.telemetry().algebraIsExact(),
                "rejected same-root G3 regression changed queue ownership");
    }

    private static void metadataOnlyRolloverRequiresByteIdenticalInputs() {
        LightWorldToken world = new LightWorldToken(3L, "minecraft:overworld");
        GiDirectSourceEpoch previous = new GiDirectSourceEpoch(
                5L, 7L, 11L, 13L, 17L, 19L, world, 23L, 29L
        );
        GiDirectSourceEpoch outsideClipmapRollover = new GiDirectSourceEpoch(
                5L, 7L, 11L, 13L, 17L, 20L, world, 24L, 29L
        );
        int[] origins = {-64, -32, -64, -128, -64, -128, -256, -128, -256};
        long[] submittedStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] desiredStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] submittedSources = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] desiredSources = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        Arrays.fill(submittedStamps, 31L);
        Arrays.fill(desiredStamps, 31L);
        Arrays.fill(submittedSources, 37L);
        Arrays.fill(desiredSources, 37L);
        check(GiDirectSourceCoordinator.metadataOnlyRolloverCompatible(
                        previous, outsideClipmapRollover,
                        origins, origins, 41L, 41L,
                        submittedStamps, desiredStamps, submittedSources, desiredSources),
                "outside-clipmap raw static/G2 rollover did not become metadata-usable");

        desiredStamps[GiDirectSourceLayout.TOTAL_BRICKS - 1]++;
        check(!GiDirectSourceCoordinator.metadataOnlyRolloverCompatible(
                        previous, outsideClipmapRollover,
                        origins, origins, 41L, 41L,
                        submittedStamps, desiredStamps, submittedSources, desiredSources),
                "changed G2 brick was relabelled without a dispatch");
        desiredStamps[GiDirectSourceLayout.TOTAL_BRICKS - 1]--;
        desiredSources[0]++;
        check(!GiDirectSourceCoordinator.metadataOnlyRolloverCompatible(
                        previous, outsideClipmapRollover,
                        origins, origins, 41L, 41L,
                        submittedStamps, desiredStamps, submittedSources, desiredSources),
                "changed source payload was relabelled without a dispatch");
        desiredSources[0]--;

        int[] movedOrigins = origins.clone();
        movedOrigins[0]++;
        check(!GiDirectSourceCoordinator.metadataOnlyRolloverCompatible(
                        previous, outsideClipmapRollover,
                        origins, movedOrigins, 41L, 41L,
                        submittedStamps, desiredStamps, submittedSources, desiredSources),
                "origin drift was admitted as metadata-only");
        check(!GiDirectSourceCoordinator.metadataOnlyRolloverCompatible(
                        previous, outsideClipmapRollover,
                        origins, origins, 41L, 42L,
                        submittedStamps, desiredStamps, submittedSources, desiredSources),
                "environment payload drift was admitted as metadata-only");

        GiDirectSourceEpoch changedClipmap = new GiDirectSourceEpoch(
                5L, 7L, 11L, 14L, 17L, 20L, world, 24L, 29L
        );
        check(!GiDirectSourceCoordinator.metadataOnlyRolloverCompatible(
                        previous, changedClipmap,
                        origins, origins, 41L, 41L,
                        submittedStamps, desiredStamps, submittedSources, desiredSources),
                "clipmap drift was admitted as metadata-only");
        GiDirectSourceEpoch regressedContent = new GiDirectSourceEpoch(
                5L, 7L, 11L, 13L, 17L, 18L, world, 24L, 29L
        );
        check(!GiDirectSourceCoordinator.metadataOnlyRolloverCompatible(
                        previous, regressedContent,
                        origins, origins, 41L, 41L,
                        submittedStamps, desiredStamps, submittedSources, desiredSources),
                "regressed content identity was admitted as metadata-only");
    }

    private static void originOnlyScrollDoesNotBecomePhysicalSourceDirt() {
        LightWorldToken world = new LightWorldToken(3L, "minecraft:overworld");
        GiDirectSourceEpoch previous = new GiDirectSourceEpoch(
                5L, 7L, 11L, 13L, 17L, 19L, world, 23L, 29L
        );
        GiDirectSourceEpoch oneScroll = new GiDirectSourceEpoch(
                5L, 7L, 11L, 14L, 17L, 20L, world, 23L, 29L
        );
        GiDirectSourceEpoch twoCoalescedScrolls = new GiDirectSourceEpoch(
                5L, 7L, 11L, 15L, 17L, 21L, world, 23L, 29L
        );
        check(GiDirectSourceCoordinator.originOnlyFieldRelocation(
                        previous, oneScroll, true, false, false, false)
                        && GiDirectSourceCoordinator.originOnlyFieldRelocation(
                        previous, twoCoalescedScrolls, true, false, false, false),
                "pure clipmap relocation was reported as physical G3 source dirt");

        GiDirectSourceEpoch scrollPlusBlock = new GiDirectSourceEpoch(
                5L, 7L, 11L, 14L, 17L, 21L, world, 23L, 29L
        );
        check(!GiDirectSourceCoordinator.originOnlyFieldRelocation(
                        previous, scrollPlusBlock, true, false, false, false)
                        && !GiDirectSourceCoordinator.originOnlyFieldRelocation(
                        previous, oneScroll, true, true, false, false)
                        && !GiDirectSourceCoordinator.originOnlyFieldRelocation(
                        previous, oneScroll, true, false, true, false)
                        && !GiDirectSourceCoordinator.originOnlyFieldRelocation(
                        previous, oneScroll, true, false, false, true)
                        && !GiDirectSourceCoordinator.originOnlyFieldRelocation(
                        previous, oneScroll, false, false, false, false),
                "scroll suppressed coalesced semantic/source/environment dirt");
    }

    private static void liveInputSuccessorRequiresAnExactStableGrid() {
        LightWorldToken world = new LightWorldToken(3L, "minecraft:overworld");
        GiDirectSourceEpoch previous = new GiDirectSourceEpoch(
                5L, 7L, 11L, 13L, 17L, 19L, world, 23L, 29L
        );
        GiDirectSourceEpoch content = new GiDirectSourceEpoch(
                5L, 7L, 11L, 13L, 17L, 20L, world, 23L, 29L
        );
        GiDirectSourceEpoch staticSource = new GiDirectSourceEpoch(
                5L, 7L, 11L, 13L, 17L, 19L, world, 24L, 29L
        );
        check(content.isLiveInputSuccessorOf(previous)
                        && staticSource.isLiveInputSuccessorOf(previous),
                "strict G2 content successor was not rebase-compatible");
        check(!previous.isLiveInputSuccessorOf(previous)
                        && !new GiDirectSourceEpoch(
                        5L, 7L, 11L, 14L, 17L, 20L, world, 23L, 29L
                ).isLiveInputSuccessorOf(previous)
                        && !new GiDirectSourceEpoch(
                        5L, 7L, 11L, 13L, 17L, 20L, world, 23L, 30L
                ).isLiveInputSuccessorOf(previous)
                        && !new GiDirectSourceEpoch(
                        5L, 8L, 11L, 13L, 17L, 20L, world, 23L, 29L
                ).isLiveInputSuccessorOf(previous)
                        && !new GiDirectSourceEpoch(
                        5L, 7L, 12L, 13L, 17L, 20L, world, 23L, 29L
                ).isLiveInputSuccessorOf(previous)
                        && !new GiDirectSourceEpoch(
                        5L, 7L, 11L, 13L, 17L, 20L,
                        new LightWorldToken(4L, "minecraft:overworld"), 23L, 29L
                ).isLiveInputSuccessorOf(previous),
                "G3 content-only predicate accepted structural/source drift");
    }

    private static void liveInputRebasePreservesQueuedBacklog() {
        GiDirectSourceEpoch first = epoch(1L, 1L);
        GiDirectSourceEpoch second = epoch(2L, 1L);
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(first);
        queue.enqueue(first, 3, 2L);
        queue.enqueue(first, 7, 3L);
        queue.enqueue(first, 11, 4L);

        queue.rebaseLiveInputEpoch(second);
        check(queue.activeEpoch().equals(second)
                        && queue.telemetry().pending() == 3
                        && queue.telemetry().discarded() == 0L
                        && queue.telemetry().algebraIsExact(),
                "content rebase discarded or detached pending G3 work");
        check(queue.enqueue(second, 7, 5L) == GiDirectDirtyQueue.OfferResult.COALESCED,
                "content rebase lost pending-brick coalescing");
        int[] batch = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        int count = queue.drainTo(second, 5L, batch);
        check(count == 3 && batch[0] == 3 && batch[1] == 7 && batch[2] == 11,
                "content rebase changed pending age/sequence order");
        check(!queue.epochTelemetry().fullVolumeEnqueued(),
                "live content rebase retained a frozen full-population proof");

        GiDirectDirtyQueue inFlight = new GiDirectDirtyQueue();
        inFlight.rotateEpoch(first);
        inFlight.enqueue(first, 1, 1L);
        check(inFlight.drainTo(first, 1L, batch) == 1,
                "content rebase in-flight fixture did not drain");
        try {
            inFlight.rebaseLiveInputEpoch(second);
            throw new AssertionError("content rebase accepted in-flight G3 ownership");
        } catch (IllegalStateException expected) {
            check(inFlight.activeEpoch().equals(first)
                            && inFlight.telemetry().inFlight() == 1
                            && inFlight.telemetry().discarded() == 0L,
                    "rejected content rebase mutated in-flight ownership");
        }
        try {
            queue.rebaseLiveInputEpoch(first);
            throw new AssertionError("content rebase accepted a regressed epoch");
        } catch (IllegalArgumentException expected) {
            check(queue.activeEpoch().equals(second),
                    "rejected content regression changed the queue epoch");
        }
    }

    private static void queueBoundsCoalescingAndStarvation() {
        GiDirectSourceEpoch epoch = epoch(1L, 1L);
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(epoch);
        queue.enqueueAll(epoch, 0L);
        check(queue.enqueue(epoch, 0, 1L) == GiDirectDirtyQueue.OfferResult.COALESCED, "coalescing");
        int[] batch = new int[GiDirectSourceLayout.TOTAL_BRICKS];
        int drained = queue.drainTo(epoch, GiDirectDirtyQueue.STARVATION_TICKS, batch);
        check(drained == GiDirectSourceLayout.MAX_DRAIN_PER_FRAME, "bounded drain");
        check(queue.telemetry().pending() == GiDirectSourceLayout.TOTAL_BRICKS - drained, "pending count");
        check(queue.telemetry().inFlight() == drained
                        && queue.telemetry().algebraIsExact(),
                "drained G3 work lost fixed in-flight ownership");
        check(queue.telemetry().starvationPromotions() == drained, "starvation promotion");
        check(queue.telemetry().fullVolumeRebuilds() == 1L,
                "initial full-field invalidation was not counted exactly once");
    }

    private static void acceptedFailureRetriesAndStaleCompletionCannotPublish() {
        GiDirectSourceEpoch current = epoch(1L, 1L);
        GiDirectDirtyQueue priorityQueue = new GiDirectDirtyQueue();
        priorityQueue.rotateEpoch(current);
        for (int brick = 0; brick < GiDirectSourceLayout.MAX_DRAIN_PER_FRAME + 1; brick++) {
            priorityQueue.enqueue(current, brick, 1L);
        }
        int[] priorityBatch = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        int priorityCount = priorityQueue.drainTo(current, 1L, priorityBatch);
        int[] acceptedPriority = Arrays.copyOf(priorityBatch, priorityCount);
        priorityQueue.retryBatch(current, priorityBatch, priorityCount, 2L);
        int repeatedCount = priorityQueue.drainTo(current, 2L, priorityBatch);
        check(repeatedCount == priorityCount
                        && Arrays.equals(acceptedPriority, priorityBatch),
                "failed G3 batch was delayed behind younger backlog");

        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(current);
        queue.enqueue(current, 3, 1L);
        queue.enqueue(current, 7, 1L);
        int[] drained = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        int count = queue.drainTo(current, 1L, drained);
        check(count == 2 && queue.telemetry().pending() == 0
                        && queue.telemetry().inFlight() == 2,
                "accepted G3 batch was not retained in flight");

        long[] desiredStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] desiredKeys = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] submittedStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] submittedKeys = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        desiredStamps[3] = 31L;
        desiredStamps[7] = 37L;
        desiredKeys[3] = 41L;
        desiredKeys[7] = 43L;
        GiDirectSourceCoordinator.AcceptedBatch accepted =
                new GiDirectSourceCoordinator.AcceptedBatch();
        accepted.admit(
                current, drained, count, desiredStamps, desiredKeys,
                1L, 101L, 103L, 10L, 2L
        );
        check(accepted.classify(current, GiDirectSourceGpuResources.STATUS_BUSY)
                        == GiDirectSourceCoordinator.AcceptedBatchState.IN_FLIGHT,
                "in-flight G3 completion was retired early");
        check(accepted.classify(current, GiDirectSourceGpuResources.STATUS_REJECTED)
                        == GiDirectSourceCoordinator.AcceptedBatchState.FAILED_CURRENT,
                "failed accepted G3 completion was not classified for retry");
        check(queue.retryBatch(
                        accepted.epoch(), accepted.brickIds(), accepted.count(),
                        accepted.sourceTick()) == GiDirectDirtyQueue.CompletionResult.COMPLETED,
                "failed accepted G3 batch was not requeued");
        accepted.clear();
        check(queue.telemetry().pending() == 2 && queue.telemetry().inFlight() == 0
                        && submittedStamps[3] == 0L && submittedKeys[7] == 0L,
                "failed G3 completion published or lost its unchanged inputs");

        int retried = queue.drainTo(current, 2L, drained);
        check(retried == count && drained[0] == 3 && drained[1] == 7,
                "unchanged G3 inputs did not repeat the same accepted batch");
        accepted.admit(
                current, drained, retried, desiredStamps, desiredKeys,
                2L, 107L, 109L, 10L, 3L
        );
        check(accepted.classify(current, GiDirectSourceGpuResources.STATUS_OK)
                        == GiDirectSourceCoordinator.AcceptedBatchState.COMPLETED_CURRENT,
                "successful retry did not restore current native readiness");
        check(queue.completeBatch(
                        accepted.epoch(), accepted.brickIds(), accepted.count())
                        == GiDirectDirtyQueue.CompletionResult.COMPLETED,
                "successful G3 retry did not retire current ownership");
        accepted.publishTo(submittedStamps, submittedKeys);
        accepted.clear();
        check(submittedStamps[3] == 31L && submittedStamps[7] == 37L
                        && submittedKeys[3] == 41L && submittedKeys[7] == 43L
                        && queue.telemetry().pending() == 0
                        && queue.telemetry().inFlight() == 0
                        && queue.telemetry().algebraIsExact(),
                "successful G3 retry did not publish the current field exactly once");

        queue.enqueue(current, 11, 3L);
        count = queue.drainTo(current, 3L, drained);
        desiredStamps[11] = 47L;
        desiredKeys[11] = 53L;
        accepted.admit(
                current, drained, count, desiredStamps, desiredKeys,
                3L, 113L, 127L, 11L, 3L
        );
        GiDirectSourceEpoch next = epoch(2L, 2L);
        queue.rotateEpoch(next);
        check(accepted.classify(next, GiDirectSourceGpuResources.STATUS_OK)
                        == GiDirectSourceCoordinator.AcceptedBatchState.STALE_COMPLETION,
                "superseded G3 completion was treated as current");
        check(accepted.classify(next, GiDirectSourceGpuResources.STATUS_INVALID)
                        == GiDirectSourceCoordinator.AcceptedBatchState.INVALID_COMPLETION,
                "invalid completion receipt was hidden as a stale success");
        accepted.clear();
        check(submittedStamps[11] == 0L && submittedKeys[11] == 0L
                        && queue.telemetry().discarded() == 1L,
                "stale G3 completion published submitted identity");
    }

    private static void rotatedLifetimeCountersCanProveASettledCurrentField() {
        GiDirectSourceEpoch first = epoch(1L, 1L);
        GiDirectSourceEpoch second = epoch(2L, 2L);
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(first);
        queue.enqueueAll(first, 0L);
        int[] batch = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        for (int iteration = 0; iteration < 2; iteration++) {
            int count = queue.drainTo(first, iteration, batch);
            queue.completeBatch(first, batch, count);
        }
        queue.rotateEpoch(second);
        queue.enqueueAll(second, 4L);
        for (int iteration = 0; iteration < 12; iteration++) {
            int count = queue.drainTo(second, 4L + iteration, batch);
            queue.completeBatch(second, batch, count);
        }
        GiDirectDirtyQueue.Telemetry telemetry = queue.telemetry();
        check(telemetry.queued() == 384L && telemetry.completed() == 224L
                        && telemetry.discarded() == 160L && telemetry.pending() == 0,
                "rotated G3 lifetime counters changed");
        check(GiDirectSourceCoordinator.isSettledTransportSource(queue.epochTelemetry()),
                "settled current G3 epoch was confused with lifetime discard history");
        queue.enqueue(second, 0, 40L);
        check(!GiDirectSourceCoordinator.isSettledTransportSource(queue.epochTelemetry()),
                "pending current G3 work was admitted to G4");
    }

    private static void sameCommandProjectedSourceSurvivesExactIdentityChurn() {
        long[] desiredStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] desiredKeys = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] submittedStamps = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        long[] submittedKeys = new long[GiDirectSourceLayout.TOTAL_BRICKS];
        int[] acceptedBricks = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        GiDirectSourceCoordinator.AcceptedBatch accepted =
                new GiDirectSourceCoordinator.AcceptedBatch();

        // TELEPORT_OUT keeps publishing one or two exact G3 changes per renderer submit. There
        // is deliberately no quiet frame here: every current batch must authorize only its own
        // command buffer and must close the complete selected-cascade gap set.
        for (long submit = 1L; submit <= 190L; submit++) {
            int first = (int) (submit % GiDirectSourceLayout.BRICKS_PER_CASCADE);
            int second = (int) ((submit * 17L + 5L)
                    % GiDirectSourceLayout.BRICKS_PER_CASCADE);
            if (second == first) {
                second = (second + 1) % GiDirectSourceLayout.BRICKS_PER_CASCADE;
            }
            acceptedBricks[0] = first;
            acceptedBricks[1] = second;
            desiredStamps[first] = 1_000L + submit;
            desiredStamps[second] = 2_000L + submit;
            desiredKeys[first] = 3_000L + submit;
            desiredKeys[second] = 4_000L + submit;
            long commandBufferAddress = 10_000L + submit * 2L;
            long fenceAddress = 20_000L + submit * 2L;
            accepted.admit(
                    epoch(submit, 1L), acceptedBricks, 2,
                    desiredStamps, desiredKeys, submit,
                    commandBufferAddress, fenceAddress, submit - 1L, 0L
            );
            check(accepted.closesEveryGapInCascade(
                            0, submittedStamps, submittedKeys,
                            desiredStamps, desiredKeys),
                    "same-command G3 batch did not close current exact cascade gaps at submit "
                            + submit);
            check(accepted.matchesSubmission(
                            commandBufferAddress, fenceAddress, submit),
                    "same-command G3 batch rejected its exact submit identity");
            check(!accepted.matchesSubmission(
                            commandBufferAddress + 1L, fenceAddress, submit)
                            && !accepted.matchesSubmission(
                            commandBufferAddress, fenceAddress + 1L, submit)
                            && !accepted.matchesSubmission(
                            commandBufferAddress, fenceAddress, submit + 1L),
                    "G3 projected completion escaped to a different CB/fence/tick");

            if (submit == 1L) {
                int uncovered = (second + 1) % GiDirectSourceLayout.BRICKS_PER_CASCADE;
                while (uncovered == first || uncovered == second) {
                    uncovered = (uncovered + 1)
                            % GiDirectSourceLayout.BRICKS_PER_CASCADE;
                }
                desiredStamps[uncovered] = 9_999L;
                desiredKeys[uncovered] = 8_888L;
                check(!accepted.closesEveryGapInCascade(
                                0, submittedStamps, submittedKeys,
                                desiredStamps, desiredKeys),
                        "G3 projected completion hid an uncovered cascade gap");
                desiredStamps[uncovered] = submittedStamps[uncovered];
                desiredKeys[uncovered] = submittedKeys[uncovered];
            }

            accepted.publishTo(submittedStamps, submittedKeys);
            accepted.clear();
            check(Arrays.equals(submittedStamps, desiredStamps)
                            && Arrays.equals(submittedKeys, desiredKeys),
                    "same-command exact identity did not converge after submit " + submit);
        }
    }

    private static AdvancedLight block(final long stableId, final int priority, final double x, final double y, final double z) {
        return new AdvancedLight(stableId, 1L, LightSourceKind.BLOCK,
                x, y, z, 1.0F, 1.0F, 0.5F, 0.25F, 1.0F, priority);
    }

    private static GiDirectSourceEpoch epoch(final long contentEpoch, final long environmentEpoch) {
        return new GiDirectSourceEpoch(1L, 1L, 1L, 1L, 1L, contentEpoch,
                new LightWorldToken(1L, "minecraft:overworld"), 1L, environmentEpoch);
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
