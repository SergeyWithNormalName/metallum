package com.metallum.client.lighting;

import com.metallum.client.renderer.LocalVoxelShadowLayout;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/** Compact rigid entity proxy selected for the later bounded L6 local-shadow atlas path. */
public record EntityShadowProxy(
        long stableId,
        double centerX,
        double centerY,
        double centerZ,
        float halfExtentX,
        float halfExtentY,
        float halfExtentZ
) {
    public EntityShadowProxy {
        if (stableId == 0L || !Double.isFinite(centerX) || !Double.isFinite(centerY)
                || !Double.isFinite(centerZ) || !finitePositive(halfExtentX) || !finitePositive(halfExtentY)
                || !finitePositive(halfExtentZ)) {
            throw new IllegalArgumentException("Invalid L6 entity shadow proxy");
        }
    }

    public static EntityShadowProxy fromEntity(final Entity entity) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        UUID id = entity.getUUID();
        long stable = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 17);
        if (stable == 0L) {
            stable = 1L;
        }
        return fromBounds(entity, stable);
    }

    /** Uses the exact L3 entity-light identity when an active world token is available. */
    public static EntityShadowProxy fromEntity(final Entity entity, final LightWorldToken world) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        return fromBounds(entity, StableLightIds.entity(world.dimensionId(), entity.getUUID()));
    }

    public double volume() {
        return 8.0 * halfExtentX * halfExtentY * halfExtentZ;
    }

    public float minRelativeX(final double cameraX) {
        return relative(centerX - halfExtentX, cameraX);
    }

    public float minRelativeY(final double cameraY) {
        return relative(centerY - halfExtentY, cameraY);
    }

    public float minRelativeZ(final double cameraZ) {
        return relative(centerZ - halfExtentZ, cameraZ);
    }

    public float maxRelativeX(final double cameraX) {
        return relative(centerX + halfExtentX, cameraX);
    }

    public float maxRelativeY(final double cameraY) {
        return relative(centerY + halfExtentY, cameraY);
    }

    public float maxRelativeZ(final double cameraZ) {
        return relative(centerZ + halfExtentZ, cameraZ);
    }

    public double distanceSquaredTo(final double x, final double y, final double z) {
        double dx = centerX - x;
        double dy = centerY - y;
        double dz = centerZ - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Future upload is exactly two camera-relative float4 values: AABB min then AABB max. */
    public static int wireStrideBytes() {
        return LocalVoxelShadowLayout.PROXY_STRIDE_BYTES;
    }

    private static float positiveExtent(final double extent) {
        if (!Double.isFinite(extent) || extent < 0.0) {
            throw new IllegalArgumentException("Entity bounds are non-finite");
        }
        return Math.max(0.03125f, (float) (extent * 0.5));
    }

    private static EntityShadowProxy fromBounds(final Entity entity, final long stableId) {
        AABB bounds = entity.getBoundingBox();
        return new EntityShadowProxy(
                stableId,
                (bounds.minX + bounds.maxX) * 0.5,
                (bounds.minY + bounds.maxY) * 0.5,
                (bounds.minZ + bounds.maxZ) * 0.5,
                positiveExtent(bounds.maxX - bounds.minX),
                positiveExtent(bounds.maxY - bounds.minY),
                positiveExtent(bounds.maxZ - bounds.minZ)
        );
    }

    private static boolean finitePositive(final float value) {
        return Float.isFinite(value) && value > 0.0f;
    }

    private static float relative(final double world, final double camera) {
        double value = world - camera;
        if (!Double.isFinite(camera) || !Double.isFinite(value)
                || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
            throw new IllegalArgumentException("Entity proxy relative coordinate is not float-representable");
        }
        return (float) value;
    }
}
