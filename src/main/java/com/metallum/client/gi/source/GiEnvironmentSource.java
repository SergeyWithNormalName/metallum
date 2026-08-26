package com.metallum.client.gi.source;

import com.metallum.client.lighting.EnvironmentDescriptor;

import java.util.Objects;

/** Immutable world-space L4 source packet for G3. */
public record GiEnvironmentSource(
        long epoch,
        float toLightX, float toLightY, float toLightZ,
        float directionalRed, float directionalGreen, float directionalBlue,
        float skyRed, float skyGreen, float skyBlue
) {
    private static final float QUANTUM = 1.0F / 4096.0F;

    public GiEnvironmentSource {
        if (epoch <= 0L) {
            throw new IllegalArgumentException("G3 environment epoch must be positive");
        }
        requireFinite(toLightX, "toLightX");
        requireFinite(toLightY, "toLightY");
        requireFinite(toLightZ, "toLightZ");
        double length = Math.sqrt((double) toLightX * toLightX + (double) toLightY * toLightY
                + (double) toLightZ * toLightZ);
        if (length == 0.0) {
            toLightX = 0.0F;
            toLightY = 0.0F;
            toLightZ = 0.0F;
        } else {
            toLightX = (float) (toLightX / length);
            toLightY = (float) (toLightY / length);
            toLightZ = (float) (toLightZ / length);
        }
        requireNonNegative(directionalRed, "directionalRed");
        requireNonNegative(directionalGreen, "directionalGreen");
        requireNonNegative(directionalBlue, "directionalBlue");
        requireNonNegative(skyRed, "skyRed");
        requireNonNegative(skyGreen, "skyGreen");
        requireNonNegative(skyBlue, "skyBlue");
    }

    public long quantizedDigest() {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, quantize(this.toLightX));
        hash = mix(hash, quantize(this.toLightY));
        hash = mix(hash, quantize(this.toLightZ));
        hash = mix(hash, quantize(this.directionalRed));
        hash = mix(hash, quantize(this.directionalGreen));
        hash = mix(hash, quantize(this.directionalBlue));
        hash = mix(hash, quantize(this.skyRed));
        hash = mix(hash, quantize(this.skyGreen));
        return mix(hash, quantize(this.skyBlue));
    }

    public GiEnvironmentSource withEpoch(final long nextEpoch) {
        return new GiEnvironmentSource(
                nextEpoch,
                this.toLightX, this.toLightY, this.toLightZ,
                this.directionalRed, this.directionalGreen, this.directionalBlue,
                this.skyRed, this.skyGreen, this.skyBlue
        );
    }

    /** Extracts only the world-space L4 physical sources; ambient/medium/view state is excluded. */
    public static GiEnvironmentSource fromDescriptor(
            final long epoch,
            final EnvironmentDescriptor descriptor
    ) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.medium() != EnvironmentDescriptor.Medium.AIR) {
            throw new IllegalArgumentException("G3 environment must be extracted for AIR");
        }
        return new GiEnvironmentSource(
                epoch,
                descriptor.toLightX(), descriptor.toLightY(), descriptor.toLightZ(),
                descriptor.directionalRed(), descriptor.directionalGreen(), descriptor.directionalBlue(),
                descriptor.skyRed(), descriptor.skyGreen(), descriptor.skyBlue()
        );
    }

    /** Keeps an epoch when quantized source energy is unchanged, otherwise advances it exactly once. */
    public static long nextEpoch(final GiEnvironmentSource previous, final GiEnvironmentSource next) {
        if (previous == null) {
            return next == null ? 1L : next.epoch();
        }
        if (next == null) {
            return Math.incrementExact(previous.epoch());
        }
        return previous.quantizedDigest() == next.quantizedDigest()
                ? previous.epoch()
                : Math.incrementExact(previous.epoch());
    }

    private static long quantize(final float value) {
        return Math.round((double) value / QUANTUM);
    }

    private static long mix(final long hash, final long value) {
        long mixed = hash ^ value;
        return Long.rotateLeft(mixed, 27) * 0x9e3779b185ebca87L;
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("G3 environment " + name + " must be finite");
        }
    }

    private static void requireNonNegative(final float value, final String name) {
        requireFinite(value, name);
        if (value < 0.0F) {
            throw new IllegalArgumentException("G3 environment " + name + " must be non-negative");
        }
    }
}
