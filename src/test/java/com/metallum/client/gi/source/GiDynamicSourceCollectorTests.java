package com.metallum.client.gi.source;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.LocalShadowSourceClass;
import com.metallum.client.lighting.MinecraftLightPolicy;
import com.metallum.client.lighting.StableLightIds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Dependency-free deterministic G6 dynamic-source contract checks. */
public final class GiDynamicSourceCollectorTests {
    private static final String OVERWORLD = "minecraft:overworld";

    private GiDynamicSourceCollectorTests() {
    }

    public static void main(final String[] args) {
        boundedSelectionAndOrderingIgnoreOfferOrder();
        duplicateSourcesCoalesceDeterministically();
        expiryUsesWorldTicksRatherThanFrames();
        frozenExtractionTicksObserveMoveRemovalAndExpiry();
        frozenPlayerTeleportUsesAuthoritativeCurrentPose();
        renderInterpolationWithinOneGiCellDoesNotRotateEpoch();
        sourceEpochIsSeparateAndContentDriven();
        heldSourceIsWorldAnchoredAndYawInvariant();
        worldResetDropsOldSourcesImmediately();
        lifecycleAndInputValidationFailClosed();
        System.out.println("G6 dynamic-source collector tests passed");
    }

    private static void boundedSelectionAndOrderingIgnoreOfferOrder() {
        LightWorldToken world = new LightWorldToken(1L, OVERWORLD);
        List<AdvancedLight> offered = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            offered.add(entity(world, index, index % 7, index - 16.0, 2.0, -index));
        }
        List<AdvancedLight> shuffled = new ArrayList<>(offered);
        Collections.shuffle(shuffled, new Random(0x6a17L));
        List<AdvancedLight> reversed = new ArrayList<>(shuffled);
        Collections.reverse(reversed);

        GiDynamicSourceSnapshot first = collect(world, shuffled, 5);
        GiDynamicSourceSnapshot second = collect(world, reversed, 5);
        check(first.sources().size() == 5, "dynamic selection exceeded its hard capacity");
        check(first.sources().equals(second.sources()),
                "bounded dynamic selection changed with offer order");
        check(first.sourceHash() == second.sourceHash(),
                "bounded dynamic hash changed with offer order");
        for (int index = 1; index < first.sources().size(); index++) {
            check(GiDynamicSourceSnapshot.SOURCE_ORDER.compare(
                            first.sources().get(index - 1), first.sources().get(index)) <= 0,
                    "published dynamic sources lost deterministic order");
        }
    }

    private static void duplicateSourcesCoalesceDeterministically() {
        LightWorldToken world = new LightWorldToken(1L, OVERWORLD);
        UUID id = uuid(77);
        AdvancedLight body = GiDynamicSourceCollector.entityAtWorldPosition(
                world, id, 10.0, 65.0, -4.0,
                6.0F, 1.0F, 0.5F, 0.25F, 2.0F, 240
        );
        AdvancedLight held = GiDynamicSourceCollector.heldAtEntityWorldPosition(
                world, id, 10.0, 65.0, -4.0,
                5.0F, 1.0F, 0.5F, 0.25F, 1.0F, 1
        );
        GiDynamicSourceCollector first = new GiDynamicSourceCollector(4, 3L);
        first.beginTick(world, 10L);
        first.offer(body);
        first.offer(held);
        GiDynamicSourceSnapshot firstSnapshot = first.finishTick();

        GiDynamicSourceCollector second = new GiDynamicSourceCollector(4, 3L);
        second.beginTick(world, 10L);
        second.offer(held);
        second.offer(body);
        GiDynamicSourceSnapshot secondSnapshot = second.finishTick();

        check(firstSnapshot.sources().equals(secondSnapshot.sources()),
                "same-ID coalescing changed with offer order");
        check(firstSnapshot.sources().size() == 1
                        && firstSnapshot.sources().getFirst().shadowSourceClass()
                        == LocalShadowSourceClass.CAMERA_HELD,
                "held/body stable-ID duplicate did not coalesce to the held source");
        check(first.telemetry().coalesced() == 1L && second.telemetry().coalesced() == 1L,
                "same-ID coalescing telemetry changed");
    }

    private static void expiryUsesWorldTicksRatherThanFrames() {
        LightWorldToken world = new LightWorldToken(1L, OVERWORLD);
        AdvancedLight source = entity(world, 4, 4, 1.0, 2.0, 3.0);
        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(4, 2L);
        collector.beginTick(world, 100L);
        collector.offer(source);
        GiDynamicSourceSnapshot observed = collector.finishTick();
        long observedEpoch = observed.epoch().sourceEpoch();

        for (int frame = 0; frame < 10_000; frame++) {
            check(collector.snapshot() == observed,
                    "displayed frames mutated the source-tick publication");
        }
        collector.beginTick(world, 101L);
        GiDynamicSourceSnapshot grace = collector.finishTick();
        check(grace == observed && grace.sources().size() == 1,
                "dynamic source expired before its exact world-tick deadline");

        collector.beginTick(world, 102L);
        GiDynamicSourceSnapshot expired = collector.finishTick();
        check(expired.sources().isEmpty()
                        && expired.epoch().sourceEpoch() == observedEpoch + 1L
                        && collector.telemetry().expired() == 1L,
                "dynamic source did not expire on its exact world/source tick");
    }

    private static void frozenExtractionTicksObserveMoveRemovalAndExpiry() {
        LightWorldToken world = new LightWorldToken(2L, OVERWORLD);
        UUID entityId = uuid(8080);
        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(4, 2L);

        long frozenGameTime = 12_345L;
        check(frozenGameTime == 12_345L,
                "frozen extraction fixture unexpectedly advanced game time");
        collector.beginTick(world, collector.nextObservationTick(world));
        AdvancedLight stationary = GiDynamicSourceCollector.entityAtWorldPosition(
                world, entityId, 8.0, 65.0, -8.0,
                4.0F, 1.0F, 0.5F, 0.25F, 1.0F, 100
        );
        collector.offer(stationary);
        GiDynamicSourceSnapshot first = collector.finishTick();
        check(first.publishedAtWorldTick() == 0L,
                "first frozen observation did not stamp its authoritative collector tick");

        collector.beginTick(world, collector.nextObservationTick(world));
        collector.offer(stationary);
        GiDynamicSourceSnapshot unchanged = collector.finishTick();
        check(unchanged == first && unchanged.sourceHash() == first.sourceHash(),
                "unchanged frozen extraction allocated or changed source identity");

        collector.beginTick(world, collector.nextObservationTick(world));
        AdvancedLight moved = GiDynamicSourceCollector.entityAtWorldPosition(
                world, entityId, 24.0, 65.0, -8.0,
                4.0F, 1.0F, 0.5F, 0.25F, 1.0F, 100
        );
        collector.offer(moved);
        GiDynamicSourceSnapshot movedSnapshot = collector.finishTick();
        check(movedSnapshot != first && movedSnapshot.sourceHash() != first.sourceHash()
                        && movedSnapshot.sources().equals(List.of(
                                GiDynamicSourceCollector.stabilizeForField(moved)
                        ))
                        && movedSnapshot.publishedAtWorldTick() == 2L,
                "content-changing frozen observation lost its authoritative collector tick");

        collector.beginTick(world, collector.nextObservationTick(world));
        GiDynamicSourceSnapshot grace = collector.finishTick();
        check(grace == movedSnapshot && grace.sources().size() == 1,
                "missing frozen extraction expired a source before its grace tick");
        collector.beginTick(world, collector.nextObservationTick(world));
        GiDynamicSourceSnapshot expired = collector.finishTick();
        check(expired.sources().isEmpty() && expired.sourceHash() != movedSnapshot.sourceHash()
                        && collector.telemetry().expired() == 1L,
                "frozen extraction ticks did not expire a removed entity source");
    }

    private static void frozenPlayerTeleportUsesAuthoritativeCurrentPose() {
        LightWorldToken world = new LightWorldToken(3L, OVERWORLD);
        UUID player = uuid(8_181);
        double oldX = 86.125;
        double currentX = 406.125;
        float[] changingRenderResiduals = {0.05F, 0.25F, 0.75F, 0.95F};
        GiDynamicSourceCollector frozen = new GiDynamicSourceCollector(4, 2L);
        GiDynamicSourceSnapshot first = null;
        for (float residual : changingRenderResiduals) {
            float partialTick = MinecraftLightPolicy.worldSpaceEntityPartialTick(
                    false, residual
            );
            check(partialTick == 1.0F,
                    "frozen player extraction did not select the authoritative current pose");
            AdvancedLight source = GiDynamicSourceCollector.entityAtWorldPosition(
                    world, player, lerp(oldX, currentX, partialTick), 106.0, -95.5,
                    12.75F, 1.0F, 0.26F, 0.035F, 3.15F, 240
            );
            frozen.beginTick(world, frozen.nextObservationTick(world));
            frozen.offer(source);
            GiDynamicSourceSnapshot snapshot = frozen.finishTick();
            if (first == null) {
                first = snapshot;
            } else {
                check(snapshot == first
                                && snapshot.epoch().sourceEpoch()
                                == first.epoch().sourceEpoch()
                                && snapshot.sourceHash() == first.sourceHash(),
                        "changing frozen render residual rotated player GI identity/hash");
            }
        }

    }

    private static void renderInterpolationWithinOneGiCellDoesNotRotateEpoch() {
        LightWorldToken world = new LightWorldToken(4L, OVERWORLD);
        UUID player = uuid(8_182);
        float runningEarly = MinecraftLightPolicy.worldSpaceEntityPartialTick(true, 0.25F);
        float runningLate = MinecraftLightPolicy.worldSpaceEntityPartialTick(true, 0.75F);
        double oldX = 100.10;
        double currentX = 101.10;
        check(runningEarly == 0.25F && runningLate == 0.75F
                        && lerp(oldX, currentX, runningEarly)
                        != lerp(oldX, currentX, runningLate),
                "normally running entity extraction lost render interpolation");
        GiDynamicSourceCollector running = new GiDynamicSourceCollector(4, 2L);
        running.beginTick(world, running.nextObservationTick(world));
        running.offer(GiDynamicSourceCollector.entityAtWorldPosition(
                world, player, lerp(oldX, currentX, runningEarly), 106.0, -95.5,
                12.75F, 1.0F, 0.26F, 0.035F, 3.15F, 240
        ));
        GiDynamicSourceSnapshot early = running.finishTick();
        running.beginTick(world, running.nextObservationTick(world));
        running.offer(GiDynamicSourceCollector.entityAtWorldPosition(
                world, player, lerp(oldX, currentX, runningLate), 106.0, -95.5,
                12.75F, 1.0F, 0.26F, 0.035F, 3.15F, 240
        ));
        GiDynamicSourceSnapshot late = running.finishTick();
        check(late == early && late.sourceHash() == early.sourceHash(),
                "sub-cell render interpolation rotated dynamic GI identity/hash");

        running.beginTick(world, running.nextObservationTick(world));
        running.offer(GiDynamicSourceCollector.entityAtWorldPosition(
                world, player, 102.10, 106.0, -95.5,
                12.75F, 1.0F, 0.26F, 0.035F, 3.15F, 240
        ));
        GiDynamicSourceSnapshot crossed = running.finishTick();
        check(crossed.epoch().sourceEpoch() == early.epoch().sourceEpoch() + 1L
                        && crossed.sourceHash() != early.sourceHash(),
                "crossing a near-field GI cell did not rotate dynamic source truth");
    }

    private static void sourceEpochIsSeparateAndContentDriven() {
        LightWorldToken world = new LightWorldToken(7L, OVERWORLD);
        AdvancedLight source = entity(world, 9, 3, 4.0, 5.0, 6.0);
        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(8, 4L);
        collector.beginTick(world, 1L);
        collector.offer(source);
        GiDynamicSourceSnapshot first = collector.finishTick();

        collector.beginTick(world, 2L);
        collector.offer(source);
        GiDynamicSourceSnapshot unchanged = collector.finishTick();
        check(unchanged == first,
                "identical source tick allocated or rotated the independent source epoch");

        AdvancedLight movedInsideCell = entity(world, 9, 3, 4.25, 5.0, 6.0);
        collector.beginTick(world, 3L);
        collector.offer(movedInsideCell);
        GiDynamicSourceSnapshot stable = collector.finishTick();
        check(stable == first && stable.sourceHash() == first.sourceHash(),
                "sub-cell world-space motion rotated the dynamic source epoch");

        AdvancedLight movedAcrossCell = entity(world, 9, 3, 6.0, 5.0, 6.0);
        collector.beginTick(world, 4L);
        collector.offer(movedAcrossCell);
        GiDynamicSourceSnapshot changed = collector.finishTick();
        check(changed.epoch().sourceEpoch() == first.epoch().sourceEpoch() + 1L
                        && changed.epoch().world().equals(world)
                        && changed.sourceHash() != first.sourceHash(),
                "GI-cell source change did not rotate only the dynamic source epoch");
        check(changed.epoch().isNewerThan(first.epoch()),
                "dynamic source epoch did not report monotonic progress");
    }

    private static void heldSourceIsWorldAnchoredAndYawInvariant() {
        LightWorldToken world = new LightWorldToken(3L, OVERWORLD);
        UUID player = uuid(1234);
        AdvancedLight yawZero = heldAtConceptualYaw(world, player, 0.0F);
        AdvancedLight yawTurned = heldAtConceptualYaw(world, player, 179.0F);
        check(yawZero.equals(yawTurned),
                "camera yaw changed the held GI source's world-space position or identity");
        check(yawZero.stableId() == StableLightIds.entity(world.dimensionId(), player)
                        && yawZero.x() == 12.25 && yawZero.y() == 64.75 && yawZero.z() == -8.5,
                "held GI source did not preserve its stable world-space entity anchor");

        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(2, 3L);
        collector.beginTick(world, 20L);
        collector.offer(yawZero);
        GiDynamicSourceSnapshot beforeYaw = collector.finishTick();
        collector.beginTick(world, 21L);
        collector.offer(yawTurned);
        GiDynamicSourceSnapshot afterYaw = collector.finishTick();
        check(afterYaw == beforeYaw && afterYaw.sourceHash() == beforeYaw.sourceHash(),
                "camera-only rotation changed held-source membership/hash/contribution input");
    }

    private static void worldResetDropsOldSourcesImmediately() {
        LightWorldToken overworld = new LightWorldToken(1L, OVERWORLD);
        LightWorldToken nether = new LightWorldToken(2L, "minecraft:the_nether");
        UUID id = uuid(42);
        AdvancedLight oldSource = GiDynamicSourceCollector.entityAtWorldPosition(
                overworld, id, 1.0, 2.0, 3.0,
                4.0F, 1.0F, 0.5F, 0.25F, 1.0F, 1
        );
        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(4, 20L);
        collector.beginTick(overworld, 900L);
        collector.offer(oldSource);
        GiDynamicSourceSnapshot before = collector.finishTick();

        collector.beginTick(nether, 0L);
        GiDynamicSourceSnapshot reset = collector.snapshot();
        check(reset.sources().isEmpty()
                        && reset.epoch().world().equals(nether)
                        && reset.epoch().sourceEpoch() > before.epoch().sourceEpoch(),
                "world reset exposed an incompatible old-world source");
        expectIllegalArgument(() -> collector.offer(oldSource));
        AdvancedLight newSource = GiDynamicSourceCollector.entityAtWorldPosition(
                nether, id, 1.0, 2.0, 3.0,
                4.0F, 1.0F, 0.5F, 0.25F, 1.0F, 1
        );
        check(newSource.stableId() != oldSource.stableId(),
                "stable entity ID did not include the dimension identity");
        collector.offer(newSource);
        GiDynamicSourceSnapshot after = collector.finishTick();
        check(after.sources().equals(List.of(
                        GiDynamicSourceCollector.stabilizeForField(newSource)
                )),
                "new-world source was not admitted after the immediate reset");
    }

    private static void lifecycleAndInputValidationFailClosed() {
        expectIllegalArgument(() -> new GiDynamicSourceCollector(0, 1L));
        expectIllegalArgument(() -> new GiDynamicSourceCollector(
                GiDynamicSourceCollector.MAX_CAPACITY + 1, 1L
        ));
        expectIllegalArgument(() -> new GiDynamicSourceCollector(1, 0L));

        LightWorldToken world = new LightWorldToken(1L, OVERWORLD);
        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(1, 1L);
        expectIllegalState(collector::finishTick);
        check(!collector.isWorldOpen(world),
                "unopened collector falsely reported an active world");
        check(collector.nextObservationTick(world) == 0L,
                "new-world observation sequence did not start at zero");
        collector.beginTick(world, collector.nextObservationTick(world));
        check(collector.isWorldOpen(world),
                "collector did not publish its world before first offers");
        expectIllegalState(() -> collector.beginTick(world, 1L));
        expectIllegalState(() -> collector.nextObservationTick(world));
        expectIllegalArgument(() -> collector.offer(new AdvancedLight(
                9L, world.generation(), LightSourceKind.BLOCK,
                0.0, 0.0, 0.0,
                1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1
        )));
        collector.finishTick();
        check(collector.nextObservationTick(world) == 1L,
                "collector-owned observation sequence did not follow its completed tick");
        expectIllegalArgument(() -> collector.beginTick(world, 0L));
        LightWorldToken regressedWorld = new LightWorldToken(1L, "minecraft:the_end");
        check(collector.nextObservationTick(regressedWorld) == 0L,
                "new-world observation sequence did not reset independently");
        expectIllegalArgument(() -> collector.beginTick(regressedWorld, 0L));
    }

    private static GiDynamicSourceSnapshot collect(
            final LightWorldToken world,
            final List<AdvancedLight> sources,
            final int capacity
    ) {
        GiDynamicSourceCollector collector = new GiDynamicSourceCollector(capacity, 3L);
        collector.beginTick(world, 0L);
        for (AdvancedLight source : sources) {
            collector.offer(source);
        }
        GiDynamicSourceSnapshot snapshot = collector.finishTick();
        check(collector.telemetry().residentSources() <= collector.telemetry().capacity(),
                "collector resident state exceeded capacity");
        return snapshot;
    }

    private static AdvancedLight entity(
            final LightWorldToken world,
            final int id,
            final int priority,
            final double x,
            final double y,
            final double z
    ) {
        return GiDynamicSourceCollector.entityAtWorldPosition(
                world, uuid(id), x, y, z,
                4.0F, 1.0F, 0.5F, 0.25F, 1.0F, priority
        );
    }

    /** Camera yaw is deliberately irrelevant to the G6 factory contract. */
    private static AdvancedLight heldAtConceptualYaw(
            final LightWorldToken world,
            final UUID player,
            final float ignoredCameraYaw
    ) {
        check(Float.isFinite(ignoredCameraYaw), "test yaw must be finite");
        return GiDynamicSourceCollector.heldAtEntityWorldPosition(
                world, player, 12.25, 64.75, -8.5,
                7.0F, 1.0F, 0.45F, 0.15F, 1.5F, 240
        );
    }

    private static UUID uuid(final int value) {
        return new UUID(0x6000000000000000L, Integer.toUnsignedLong(value) + 1L);
    }

    private static double lerp(final double oldValue, final double value, final float phase) {
        return oldValue + (value - oldValue) * phase;
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectIllegalState(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
