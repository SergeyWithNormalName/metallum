package com.metallum.client.gi.transport;

import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;
import com.metallum.client.gi.source.GiDirectSourceCoordinator;
import com.metallum.client.gi.source.GiDirectSourceEpoch;

import java.util.Objects;

/** Immutable identity of the only G2/G3 tuple a frozen G4 context may consume. */
public record GiTransportEpoch(
        long worldGeneration,
        long clipmapGeneration,
        long paletteGeneration,
        long contentGeneration,
        long staticSourceEpoch,
        long environmentEpoch,
        int nearOriginX,
        int nearOriginY,
        int nearOriginZ,
        long sourceStamp
) {
    public GiTransportEpoch {
        if (worldGeneration <= 0L || clipmapGeneration <= 0L || paletteGeneration <= 0L
                || contentGeneration <= 0L || staticSourceEpoch <= 0L || environmentEpoch <= 0L
                || sourceStamp == 0L
                || Math.floorMod(nearOriginX, 2) != 0
                || Math.floorMod(nearOriginY, 2) != 0
                || Math.floorMod(nearOriginZ, 2) != 0) {
            throw new IllegalArgumentException("G4 frozen epochs/origin/stamp are invalid");
        }
    }

    public static GiTransportEpoch from(
            final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.TransportSource source
    ) {
        Objects.requireNonNull(source, "source");
        return from(field, source.identity());
    }

    public static GiTransportEpoch from(
            final GiSemanticTransportFieldView field,
            final GiDirectSourceCoordinator.TransportSourceIdentity source
    ) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(source, "source");
        GiDirectSourceEpoch direct = source.epoch();
        if (!field.world().dimensionId().equals(direct.staticLightWorld().dimensionId())
                || field.worldGeneration() != direct.g2WorldGeneration()
                || field.resourceEpoch() != direct.g2ResourceEpoch()
                || field.materialEpoch() != direct.g2MaterialEpoch()
                || field.clipmapGeneration() != direct.g2ClipmapGeneration()
                || field.paletteGeneration() != direct.g2PaletteGeneration()
                || field.contentGeneration() != direct.g2ContentGeneration()
                || field.nearOriginX() != source.nearOriginX()
                || field.nearOriginY() != source.nearOriginY()
                || field.nearOriginZ() != source.nearOriginZ()) {
            throw new IllegalArgumentException("G4 semantic and direct-source epochs/origins differ");
        }
        return new GiTransportEpoch(
                direct.g2WorldGeneration(), direct.g2ClipmapGeneration(),
                direct.g2PaletteGeneration(), direct.g2ContentGeneration(),
                direct.staticLightRegistryEpoch(), direct.environmentEpoch(),
                source.nearOriginX(), source.nearOriginY(), source.nearOriginZ(),
                source.sourceStamp()
        );
    }

    public boolean matches(final GiSemanticTransportFieldView field) {
        Objects.requireNonNull(field, "field");
        return field.worldGeneration() == this.worldGeneration
                && field.clipmapGeneration() == this.clipmapGeneration
                && field.paletteGeneration() == this.paletteGeneration
                && field.contentGeneration() == this.contentGeneration
                && field.nearOriginX() == this.nearOriginX
                && field.nearOriginY() == this.nearOriginY
                && field.nearOriginZ() == this.nearOriginZ;
    }
}
