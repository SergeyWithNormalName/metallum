package com.metallum.client.lighting;

import net.minecraft.world.entity.LightningBolt;

import java.util.Objects;

/**
 * Deterministic policy computing atmospheric environment lighting pulses from lightning strikes.
 *
 * <p>The policy operates in scene-linear radiance and computes additive sky irradiance
 * that is integrated into the celestial environment descriptor. Additive ambient irradiance
 * is strictly zero to prevent lightning flashes from leaking into sealed caves.</p>
 *
 * <p>State tracking is fed exclusively through the existing LevelExtractor entity iteration,
 * eliminating redundant world/entity scans while accurately tracking multi-stroke lifecycles.</p>
 */
public final class LightningEnvironmentPolicy {

    /** Chromaticity reference matching the local lightning light in {@link MinecraftLightPolicy}. */
    public static final float LIGHTNING_RED = 0.63f;
    public static final float LIGHTNING_GREEN = 0.78f;
    public static final float LIGHTNING_BLUE = 1.00f;

    /** Peak scene-linear additive sky irradiance scale for a nearby bolt in air. */
    public static final float PEAK_SKY_SCALE = 1.20f;
    /** Additive ambient scale is strictly 0.0 to prevent unoccluded ambient flash in sealed caves. */
    public static final float PEAK_AMBIENT_SCALE = 0.0f;

    public static final float PEAK_SKY_RED = LIGHTNING_RED * PEAK_SKY_SCALE;
    public static final float PEAK_SKY_GREEN = LIGHTNING_GREEN * PEAK_SKY_SCALE;
    public static final float PEAK_SKY_BLUE = LIGHTNING_BLUE * PEAK_SKY_SCALE;

    public static final float PEAK_AMBIENT_RED = 0.0f;
    public static final float PEAK_AMBIENT_GREEN = 0.0f;
    public static final float PEAK_AMBIENT_BLUE = 0.0f;

    /** Temporal envelope timings in seconds per stroke. */
    public static final float ENVELOPE_RISE_END_SECONDS = 0.040f;
    public static final float ENVELOPE_PLATEAU_END_SECONDS = 0.080f;
    public static final float ENVELOPE_DURATION_SECONDS = 0.400f;

    /** Atmospheric distance attenuation bounds in blocks. */
    public static final float DISTANCE_NEAR_BLOCKS = 64.0f;
    public static final float DISTANCE_FAR_BLOCKS = 448.0f;

    /** Maximum concurrent lightning bolts tracked without heap allocations. */
    public static final int MAX_TRACKED_BOLTS = 8;

    private static final TrackedStroke[] TRACKED_STROKES = new TrackedStroke[MAX_TRACKED_BOLTS];
    private static int trackedCount = 0;

    static {
        for (int i = 0; i < MAX_TRACKED_BOLTS; i++) {
            TRACKED_STROKES[i] = new TrackedStroke();
        }
    }

    private LightningEnvironmentPolicy() {
    }

    /**
     * Mutable internal tracker slot for an active lightning stroke.
     */
    private static final class TrackedStroke {
        int entityId;
        long strokeSeed;
        int strokeStartTick;
        double x;
        double y;
        double z;
        long lastObservedGameTime;
        int lastObservedTickCount;
        boolean active;

        void clear() {
            this.entityId = 0;
            this.strokeSeed = 0L;
            this.strokeStartTick = 0;
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
            this.lastObservedGameTime = -1L;
            this.lastObservedTickCount = 0;
            this.active = false;
        }
    }

    /**
     * Immutable candidate representing an active lightning strike event for testing and evaluation.
     */
    public record LightningStrikeCandidate(
            double x,
            double y,
            double z,
            int strokeTickAge
    ) {
        public LightningStrikeCandidate {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Candidate coordinates must be finite");
            }
            if (strokeTickAge < 0) {
                throw new IllegalArgumentException("Candidate strokeTickAge must be non-negative: " + strokeTickAge);
            }
        }
    }

    /**
     * Immutable result containing the flash strength and linear additive irradiance contributions.
     */
    public record FlashContribution(
            float flashStrength,
            float skyRed,
            float skyGreen,
            float skyBlue,
            float ambientRed,
            float ambientGreen,
            float ambientBlue
    ) {
        public static final FlashContribution NONE = new FlashContribution(
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f
        );

        public FlashContribution {
            requireNonNegative(flashStrength, "flashStrength");
            requireNonNegative(skyRed, "skyRed");
            requireNonNegative(skyGreen, "skyGreen");
            requireNonNegative(skyBlue, "skyBlue");
            requireNonNegative(ambientRed, "ambientRed");
            requireNonNegative(ambientGreen, "ambientGreen");
            requireNonNegative(ambientBlue, "ambientBlue");
        }

        private static void requireNonNegative(final float value, final String label) {
            if (!Float.isFinite(value) || value < 0.0f) {
                throw new IllegalArgumentException(label + " must be finite and non-negative: " + value);
            }
        }
    }

    /**
     * Resets all tracked lightning state (e.g. on world/dimension transitions).
     */
    public static synchronized void reset() {
        for (int i = 0; i < MAX_TRACKED_BOLTS; i++) {
            TRACKED_STROKES[i].clear();
        }
        trackedCount = 0;
    }

    /**
     * Marks the beginning of an entity extraction frame.
     */
    public static synchronized void beginFrame(final long gameTime) {
        // No per-frame allocation
    }

    public record TrackedStrokeMock(
            int entityId,
            long strokeSeed,
            int tickCount,
            double x,
            double y,
            double z,
            long gameTime
    ) {}

    public static synchronized void observeMock(final TrackedStrokeMock mock) {
        if (mock != null) {
            observeRaw(mock.entityId(), mock.strokeSeed(), mock.tickCount(), mock.x(), mock.y(), mock.z(), mock.gameTime());
        }
    }

    public static synchronized void observeBolt(final LightningBolt bolt, final long gameTime) {
        if (bolt == null || !bolt.isAlive()) {
            return;
        }
        observeRaw(bolt.getId(), bolt.seed, bolt.tickCount, bolt.getX(), bolt.getY(), bolt.getZ(), gameTime);
    }

    public static synchronized void observeRaw(
            final int entityId,
            final long strokeSeed,
            final int tickCount,
            final double x,
            final double y,
            final double z,
            final long gameTime
    ) {
        // 1. Check if this bolt is already tracked
        for (int i = 0; i < trackedCount; i++) {
            TrackedStroke stroke = TRACKED_STROKES[i];
            if (stroke.active && stroke.entityId == entityId) {
                if (stroke.strokeSeed != strokeSeed) {
                    // New stroke on the same bolt entity
                    stroke.strokeSeed = strokeSeed;
                    stroke.strokeStartTick = tickCount;
                }
                stroke.x = x;
                stroke.y = y;
                stroke.z = z;
                stroke.lastObservedGameTime = gameTime;
                stroke.lastObservedTickCount = tickCount;
                return;
            }
        }

        // 2. If new bolt, find a slot in bounded array
        if (trackedCount < MAX_TRACKED_BOLTS) {
            TrackedStroke stroke = TRACKED_STROKES[trackedCount++];
            stroke.entityId = entityId;
            stroke.strokeSeed = strokeSeed;
            stroke.strokeStartTick = tickCount;
            stroke.x = x;
            stroke.y = y;
            stroke.z = z;
            stroke.lastObservedGameTime = gameTime;
            stroke.lastObservedTickCount = tickCount;
            stroke.active = true;
            return;
        }

        // 3. Array full: replace the oldest or expired slot
        int replaceIndex = 0;
        int maxAgeTicks = -1;
        for (int i = 0; i < MAX_TRACKED_BOLTS; i++) {
            TrackedStroke stroke = TRACKED_STROKES[i];
            int ageTicks = stroke.lastObservedTickCount - stroke.strokeStartTick;
            if (ageTicks > maxAgeTicks) {
                maxAgeTicks = ageTicks;
                replaceIndex = i;
            }
        }
        TrackedStroke stroke = TRACKED_STROKES[replaceIndex];
        stroke.entityId = entityId;
        stroke.strokeSeed = strokeSeed;
        stroke.strokeStartTick = tickCount;
        stroke.x = x;
        stroke.y = y;
        stroke.z = z;
        stroke.lastObservedGameTime = gameTime;
        stroke.lastObservedTickCount = tickCount;
        stroke.active = true;
    }

    /**
     * Marks the end of an entity extraction frame and purges expired entries.
     */
    public static synchronized void finishFrame(final long currentGameTime) {
        int write = 0;
        for (int read = 0; read < trackedCount; read++) {
            TrackedStroke stroke = TRACKED_STROKES[read];
            boolean staleTime = currentGameTime >= 0 && stroke.lastObservedGameTime >= 0
                    && (currentGameTime - stroke.lastObservedGameTime > 4);
            boolean expiredEnvelope = (stroke.lastObservedTickCount - stroke.strokeStartTick) * 0.050f >= ENVELOPE_DURATION_SECONDS;

            if (!staleTime && !expiredEnvelope && stroke.active) {
                if (write != read) {
                    copyStroke(stroke, TRACKED_STROKES[write]);
                }
                write++;
            } else {
                stroke.clear();
            }
        }
        trackedCount = write;
    }

    private static void copyStroke(final TrackedStroke src, final TrackedStroke dst) {
        dst.entityId = src.entityId;
        dst.strokeSeed = src.strokeSeed;
        dst.strokeStartTick = src.strokeStartTick;
        dst.x = src.x;
        dst.y = src.y;
        dst.z = src.z;
        dst.lastObservedGameTime = src.lastObservedGameTime;
        dst.lastObservedTickCount = src.lastObservedTickCount;
        dst.active = src.active;
    }

    /**
     * Evaluates the published lightning environment state from tracked strokes without any entity iteration.
     */
    public static synchronized FlashContribution evaluate(
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final float partialTick,
            final EnvironmentDescriptor.Medium medium
    ) {
        if (trackedCount == 0) {
            return FlashContribution.NONE;
        }
        float mediumTransmission = evaluateMediumTransmission(medium);
        if (mediumTransmission <= 0.0f) {
            return FlashContribution.NONE;
        }
        float safePartialTick = Math.clamp(partialTick, 0.0f, 1.0f);
        float maxStrength = 0.0f;

        for (int i = 0; i < trackedCount; i++) {
            TrackedStroke stroke = TRACKED_STROKES[i];
            if (!stroke.active) {
                continue;
            }
            int strokeAgeTicks = Math.max(0, stroke.lastObservedTickCount - stroke.strokeStartTick);
            float ageSeconds = (strokeAgeTicks + safePartialTick) * 0.050f;
            float envelope = evaluateTemporalEnvelope(ageSeconds);
            if (envelope <= 0.0f) {
                continue;
            }
            double dx = stroke.x - cameraX;
            double dy = stroke.y - cameraY;
            double dz = stroke.z - cameraZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            float distAtten = evaluateDistanceAttenuation(distance);
            float strength = envelope * distAtten * mediumTransmission;
            if (strength > maxStrength) {
                maxStrength = strength;
            }
        }

        if (maxStrength <= 0.0f) {
            return FlashContribution.NONE;
        }
        return createContribution(Math.clamp(maxStrength, 0.0f, 1.0f));
    }

    /**
     * Evaluates the continuous temporal envelope [0.0, 1.0] for a stroke of the given age in seconds.
     */
    public static float evaluateTemporalEnvelope(final float ageSeconds) {
        if (!Float.isFinite(ageSeconds) || ageSeconds < 0.0f || ageSeconds >= ENVELOPE_DURATION_SECONDS) {
            return 0.0f;
        }
        if (ageSeconds < ENVELOPE_RISE_END_SECONDS) {
            return smoothstep(0.0f, ENVELOPE_RISE_END_SECONDS, ageSeconds);
        }
        if (ageSeconds <= ENVELOPE_PLATEAU_END_SECONDS) {
            return 1.0f;
        }
        float decayProgress = (ageSeconds - ENVELOPE_PLATEAU_END_SECONDS)
                / (ENVELOPE_DURATION_SECONDS - ENVELOPE_PLATEAU_END_SECONDS);
        return 1.0f - smoothstep(0.0f, 1.0f, decayProgress);
    }

    /**
     * Evaluates the atmospheric distance attenuation [0.0, 1.0] from camera to strike.
     */
    public static float evaluateDistanceAttenuation(final double distance) {
        if (!Double.isFinite(distance) || distance < 0.0) {
            return 0.0f;
        }
        if (distance <= DISTANCE_NEAR_BLOCKS) {
            return 1.0f;
        }
        if (distance >= DISTANCE_FAR_BLOCKS) {
            return 0.0f;
        }
        return 1.0f - smoothstep(DISTANCE_NEAR_BLOCKS, DISTANCE_FAR_BLOCKS, (float) distance);
    }

    /**
     * Returns the medium transmission multiplier for atmospheric lightning flash.
     */
    public static float evaluateMediumTransmission(final EnvironmentDescriptor.Medium medium) {
        Objects.requireNonNull(medium, "medium");
        return switch (medium) {
            case AIR -> 1.0f;
            case WATER -> 0.55f;
            case POWDER_SNOW -> 0.22f;
            case LAVA -> 0.0f;
        };
    }

    /**
     * Evaluates the single-candidate flash strength [0.0, 1.0].
     */
    public static float evaluateCandidateStrength(
            final LightningStrikeCandidate candidate,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final float partialTick,
            final EnvironmentDescriptor.Medium medium
    ) {
        if (candidate == null) {
            return 0.0f;
        }
        float mediumTransmission = evaluateMediumTransmission(medium);
        if (mediumTransmission <= 0.0f) {
            return 0.0f;
        }
        float safePartialTick = Math.clamp(partialTick, 0.0f, 1.0f);
        float ageSeconds = (candidate.strokeTickAge() + safePartialTick) * 0.050f;
        float envelope = evaluateTemporalEnvelope(ageSeconds);
        if (envelope <= 0.0f) {
            return 0.0f;
        }
        double dx = candidate.x() - cameraX;
        double dy = candidate.y() - cameraY;
        double dz = candidate.z() - cameraZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float distanceAttenuation = evaluateDistanceAttenuation(distance);
        if (distanceAttenuation <= 0.0f) {
            return 0.0f;
        }
        return Math.clamp(envelope * distanceAttenuation * mediumTransmission, 0.0f, 1.0f);
    }

    /**
     * Pure calculation of flash contribution across an iterable of strike candidates.
     */
    public static FlashContribution evaluateCandidates(
            final Iterable<LightningStrikeCandidate> candidates,
            final double cameraX,
            final double cameraY,
            final double cameraZ,
            final float partialTick,
            final EnvironmentDescriptor.Medium medium
    ) {
        if (candidates == null) {
            return FlashContribution.NONE;
        }
        float maxStrength = 0.0f;
        for (LightningStrikeCandidate candidate : candidates) {
            float strength = evaluateCandidateStrength(
                    candidate, cameraX, cameraY, cameraZ, partialTick, medium
            );
            if (strength > maxStrength) {
                maxStrength = strength;
            }
        }
        if (maxStrength <= 0.0f) {
            return FlashContribution.NONE;
        }
        return createContribution(maxStrength);
    }

    /**
     * Creates an immutable flash contribution record from a normalized flash strength in [0, 1].
     */
    public static FlashContribution createContribution(final float strength) {
        float safeStrength = Math.clamp(strength, 0.0f, 1.0f);
        if (safeStrength <= 0.0f) {
            return FlashContribution.NONE;
        }
        return new FlashContribution(
                safeStrength,
                safeStrength * PEAK_SKY_RED,
                safeStrength * PEAK_SKY_GREEN,
                safeStrength * PEAK_SKY_BLUE,
                0.0f,
                0.0f,
                0.0f
        );
    }

    private static float smoothstep(final float low, final float high, final float value) {
        float t = Math.clamp((value - low) / (high - low), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
