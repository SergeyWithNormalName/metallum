package com.metallum.client.gi.source;

import com.metallum.client.lighting.AdvancedLight;
import com.metallum.client.lighting.AdvancedLightRegistry;
import com.metallum.client.lighting.LightSourceKind;
import com.metallum.client.lighting.LightWorldToken;

import java.util.List;

/** Lightweight CPU contract checks; deliberately executable without a live renderer. */
public final class GiDirectSourceCpuTests {
    private GiDirectSourceCpuTests() {
    }

    public static void main(final String[] args) {
        negativeCoordinateMath();
        staticOrderingAndExclusion();
        dynamicFramesDoNotRotateStaticEpoch();
        environmentQuantization();
        staleEpochIsRejected();
        queueBoundsCoalescingAndStarvation();
        rotatedLifetimeCountersCanProveASettledCurrentField();
    }

    private static void negativeCoordinateMath() {
        int near = GiDirectSourceLayout.brickIdForWorld(0, -64, -64, -64, -1, -1, -1);
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

    private static void dynamicFramesDoNotRotateStaticEpoch() {
        AdvancedLightRegistry registry = new AdvancedLightRegistry();
        LightWorldToken world = registry.openWorld(new Object(), "minecraft:overworld");
        GiStaticSourceState before = registry.staticSourceStateForGi(world.dimensionId());
        registry.publishDynamicFrame(world, List.of(), 0);
        GiStaticSourceState after = registry.staticSourceStateForGi(world.dimensionId());
        check(before != null && before.equals(after),
                "dynamic L3 frame rotated the camera-independent G3 source epoch");
    }

    private static void staleEpochIsRejected() {
        GiDirectSourceEpoch first = epoch(1L, 1L);
        GiDirectSourceEpoch next = epoch(2L, 2L);
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(first);
        check(queue.enqueue(first, 0, 1L) == GiDirectDirtyQueue.OfferResult.ENQUEUED, "first enqueue");
        queue.rotateEpoch(next);
        check(queue.enqueue(first, 0, 2L) == GiDirectDirtyQueue.OfferResult.STALE, "stale epoch rejection");
        check(queue.telemetry().discarded() == 2L, "rotation and stale discard accounting");
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
        check(queue.telemetry().starvationPromotions() == drained, "starvation promotion");
        check(queue.telemetry().fullVolumeRebuilds() == 1L,
                "initial full-field invalidation was not counted exactly once");
    }

    private static void rotatedLifetimeCountersCanProveASettledCurrentField() {
        GiDirectSourceEpoch first = epoch(1L, 1L);
        GiDirectSourceEpoch second = epoch(2L, 2L);
        GiDirectDirtyQueue queue = new GiDirectDirtyQueue();
        queue.rotateEpoch(first);
        queue.enqueueAll(first, 0L);
        int[] batch = new int[GiDirectSourceLayout.MAX_DRAIN_PER_FRAME];
        for (int iteration = 0; iteration < 4; iteration++) {
            int count = queue.drainTo(first, iteration, batch);
            queue.completeBatch(first, batch, count);
        }
        queue.rotateEpoch(second);
        queue.enqueueAll(second, 4L);
        for (int iteration = 0; iteration < 24; iteration++) {
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
