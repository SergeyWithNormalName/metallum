package com.metallum.client.lighting;

import com.metallum.client.renderer.LocalVoxelShadowLayout;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/** Compact rigid entity proxy selected for the bounded L6 local-shadow atlas path. */
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
        return fromAABB(entity.getBoundingBox(), stable);
    }

    /** Uses the exact L3 entity-light identity when an active world token is available. */
    public static EntityShadowProxy fromEntity(final Entity entity, final LightWorldToken world) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        return fromAABB(entity.getBoundingBox(), StableLightIds.entity(world.dimensionId(), entity.getUUID()));
    }

    /** Uses partial-tick temporal interpolation so proxy bounds track rendered motion smoothly. */
    public static EntityShadowProxy fromEntity(
            final Entity entity,
            final float partialTick,
            final LightWorldToken world
    ) {
        if (world == null) {
            throw new NullPointerException("world");
        }
        AABB bounds = interpolatedBounds(entity, partialTick);
        return fromAABB(bounds, StableLightIds.entity(world.dimensionId(), entity.getUUID()));
    }

    /** Decomposes entities into bounded multi-primitive proxies for high silhouette fidelity. */
    public static List<EntityShadowProxy> proxiesForEntity(
            final Entity entity,
            final float partialTick,
            final LightWorldToken world,
            final boolean multiPrimitive
    ) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        if (world == null) {
            throw new NullPointerException("world");
        }
        long stableId = StableLightIds.entity(world.dimensionId(), entity.getUUID());
        AABB bounds = interpolatedBounds(entity, partialTick);
        if (!multiPrimitive) {
            return List.of(fromAABB(bounds, stableId));
        }
        double width = bounds.maxX - bounds.minX;
        double height = bounds.maxY - bounds.minY;
        double depth = bounds.maxZ - bounds.minZ;
        double centerX = (bounds.minX + bounds.maxX) * 0.5;
        double minY = bounds.minY;
        double centerZ = (bounds.minZ + bounds.maxZ) * 0.5;

        // Humanoids / Bipeds (Zombie, Skeleton, Player, Villager, Illager: height ~1.6 - 2.3)
        if (height >= 1.6 && height <= 2.3 && width <= 0.85 && depth <= 0.85) {
            EntityShadowProxy head = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height - 0.22,
                    centerZ,
                    Math.min(0.22f, (float) (width * 0.38)),
                    0.22f,
                    Math.min(0.22f, (float) (depth * 0.38))
            );
            EntityShadowProxy torso = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.55,
                    centerZ,
                    Math.min(0.26f, (float) (width * 0.45)),
                    (float) (height * 0.22),
                    Math.min(0.18f, (float) (depth * 0.32))
            );
            EntityShadowProxy legs = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.20,
                    centerZ,
                    Math.min(0.20f, (float) (width * 0.35)),
                    (float) (height * 0.20),
                    Math.min(0.16f, (float) (depth * 0.30))
            );
            return List.of(head, torso, legs);
        }

        // Tall slender mobs / Enderman (height >= 2.4)
        if (height >= 2.4 && width <= 0.85 && depth <= 0.85) {
            EntityShadowProxy head = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height - 0.20,
                    centerZ,
                    Math.min(0.18f, (float) (width * 0.35)),
                    0.20f,
                    Math.min(0.18f, (float) (depth * 0.35))
            );
            EntityShadowProxy torso = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.65,
                    centerZ,
                    Math.min(0.20f, (float) (width * 0.40)),
                    (float) (height * 0.18),
                    Math.min(0.14f, (float) (depth * 0.30))
            );
            EntityShadowProxy legs = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.25,
                    centerZ,
                    Math.min(0.14f, (float) (width * 0.30)),
                    (float) (height * 0.25),
                    Math.min(0.14f, (float) (depth * 0.30))
            );
            return List.of(head, torso, legs);
        }

        // Spiders / Wide low mobs (width >= 0.9, height <= 1.2)
        if (width >= 0.9 && height <= 1.2 && depth >= 0.9) {
            EntityShadowProxy head = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.40,
                    centerZ - depth * 0.20,
                    (float) (width * 0.30),
                    (float) (height * 0.35),
                    (float) (depth * 0.25)
            );
            EntityShadowProxy abdomen = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.50,
                    centerZ + depth * 0.20,
                    (float) (width * 0.42),
                    (float) (height * 0.45),
                    (float) (depth * 0.35)
            );
            return List.of(head, abdomen);
        }

        // Quadrupeds / Animals (Cow, Pig, Sheep, Horse, Wolf: height 0.7 - 1.8, depth/width elongated)
        if (height >= 0.7 && height <= 1.8 && (depth >= width * 1.05 || width >= depth * 1.05)) {
            EntityShadowProxy body = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.55,
                    centerZ,
                    (float) (width * 0.42),
                    (float) (height * 0.28),
                    (float) (depth * 0.42)
            );
            EntityShadowProxy head = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.75,
                    centerZ,
                    (float) (width * 0.32),
                    (float) (height * 0.22),
                    (float) (depth * 0.30)
            );
            EntityShadowProxy legs = new EntityShadowProxy(
                    stableId,
                    centerX,
                    minY + height * 0.18,
                    centerZ,
                    (float) (width * 0.38),
                    (float) (height * 0.18),
                    (float) (depth * 0.38)
            );
            return List.of(body, head, legs);
        }

        // Generic fallback / Slimes
        return List.of(fromAABB(bounds, stableId));
    }

    public static AABB interpolatedBounds(final Entity entity, final float partialTick) {
        if (entity == null) {
            throw new NullPointerException("entity");
        }
        AABB raw = entity.getBoundingBox();
        if (Float.isNaN(partialTick) || partialTick == 0.0f) {
            return raw;
        }
        double lerpX = entity.xOld + (entity.getX() - entity.xOld) * (double) partialTick;
        double lerpY = entity.yOld + (entity.getY() - entity.yOld) * (double) partialTick;
        double lerpZ = entity.zOld + (entity.getZ() - entity.zOld) * (double) partialTick;
        double dx = lerpX - entity.getX();
        double dy = lerpY - entity.getY();
        double dz = lerpZ - entity.getZ();
        if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
            return raw;
        }
        return raw.move(dx, dy, dz);
    }

    public static EntityShadowProxy fromAABB(final AABB bounds, final long stableId) {
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

    public static int wireStrideBytes() {
        return LocalVoxelShadowLayout.PROXY_STRIDE_BYTES;
    }

    private static float positiveExtent(final double extent) {
        if (!Double.isFinite(extent) || extent < 0.0) {
            throw new IllegalArgumentException("Entity bounds are non-finite");
        }
        return Math.max(0.03125f, (float) (extent * 0.5));
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
