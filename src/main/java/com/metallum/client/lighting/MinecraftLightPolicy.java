package com.metallum.client.lighting;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/** Deterministic L3 point-light defaults expressed directly in scene-linear RGB. */
public final class MinecraftLightPolicy {
    private MinecraftLightPolicy() {
    }

    public static LightTemplate block(
            final BlockState state,
            final int blockX,
            final int blockY,
            final int blockZ
    ) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        EmissiveCell cell = emissiveCell(state);
        int emission = cell.emission();
        if (emission <= 0) {
            return null;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(cell.colorState().getBlock());
        float[] color = linearColorForIdentifier(id);
        float normalized = emission / 15.0F;
        float radius = 1.5F + 0.75F * emission;
        float intensity = 0.15F + 3.0F * normalized * (float) Math.sqrt(normalized);
        return new LightTemplate(
                LightSourceKind.BLOCK,
                blockX + 0.5,
                blockY + 0.5,
                blockZ + 0.5,
                radius,
                color[0],
                color[1],
                color[2],
                intensity,
                priorityForEmission(emission),
                cell.denseCellEligible()
        );
    }

    public static AdvancedLight entity(
            final Entity entity,
            final float partialTick,
            final LightWorldToken world
    ) {
        Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String path = id == null ? "unknown" : id.getPath();
        EntityProfile profile = brighter(
                entityProfile(path, entity.isOnFire()),
                carriedLightProfile(entity)
        );
        if (profile == null) {
            return null;
        }
        double x = entity.xOld + (entity.getX() - entity.xOld) * partialTick;
        double y = entity.yOld + (entity.getY() - entity.yOld) * partialTick
                + entity.getBbHeight() * 0.5;
        double z = entity.zOld + (entity.getZ() - entity.zOld) * partialTick;
        long stableId = StableLightIds.entity(world.dimensionId(), entity.getUUID());
        return new AdvancedLight(
                stableId,
                world.generation(),
                LightSourceKind.ENTITY,
                x,
                y,
                z,
                profile.radius,
                profile.red,
                profile.green,
                profile.blue,
                profile.intensity,
                profile.priority
        );
    }

    /**
     * Returns the local player's held-block light at a camera/hand anchor, or {@code null} when
     * the ordinary entity source is stronger (for example, a player on fire).
     */
    public static AdvancedLight cameraHeld(
            final LivingEntity player,
            final float partialTick,
            final LightWorldToken world,
            final CameraHeldLightTracker.CameraHeldLightAnchor anchor
    ) {
        if (player == null || world == null || anchor == null) {
            throw new NullPointerException("camera-held light arguments");
        }
        HeldLight held = heldLightProfile(player);
        if (held == null) {
            return null;
        }
        Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(player.getType());
        String path = entityId == null ? "unknown" : entityId.getPath();
        if (brighter(entityProfile(path, player.isOnFire()), held.profile) != held.profile) {
            return null;
        }
        return new AdvancedLight(
                StableLightIds.entity(world.dimensionId(), player.getUUID()),
                world.generation(),
                LightSourceKind.ENTITY,
                anchor.x(),
                anchor.y(),
                anchor.z(),
                held.profile.radius,
                held.profile.red,
                held.profile.green,
                held.profile.blue,
                held.profile.intensity,
                held.profile.priority,
                false,
                ShadowEmitterFootprint.empty(),
                LocalShadowSourceClass.CAMERA_HELD
        );
    }

    /** Main hand wins exact ties, matching the ordinary carried-light policy. */
    public static double selectedHandSideOffset(final LivingEntity player) {
        if (player == null) {
            throw new NullPointerException("player");
        }
        HeldLight held = heldLightProfile(player);
        if (held == null) {
            return 0.0;
        }
        return held.hand == InteractionHand.MAIN_HAND
                ? CameraHeldLightTracker.HAND_SIDE_OFFSET
                : -CameraHeldLightTracker.HAND_SIDE_OFFSET;
    }

    /** Identifies the local-player body light replaced by the separately extracted camera light. */
    public static boolean cameraHeldStableIdMatches(
            final Entity entity,
            final long cameraHeldStableId,
            final LightWorldToken world
    ) {
        return entity != null && cameraHeldStableId != 0L && world != null
                && StableLightIds.entity(world.dimensionId(), entity.getUUID()) == cameraHeldStableId;
    }

    static int effectiveEmission(final BlockState state) {
        return emissiveCell(state).emission();
    }

    /**
     * Returns whether a committed state transition leaves the static L3 light profile unchanged.
     * This deliberately accepts only identical non-zero emissive cells, so removals, dimming and
     * unfamiliar state-dependent emitters continue through the ordinary registry mutation path.
     */
    public static boolean hasEquivalentNonZeroBlockLightProfile(
            final BlockState oldState,
            final BlockState newState
    ) {
        if (oldState == null || newState == null) {
            return false;
        }
        EmissiveCell oldCell = emissiveCell(oldState);
        EmissiveCell newCell = emissiveCell(newState);
        return oldCell.emission() > 0
                && oldCell.emission() == newCell.emission()
                && oldCell.colorState().getBlock() == newCell.colorState().getBlock()
                && oldCell.denseCellEligible() == newCell.denseCellEligible();
    }

    static int priorityForEmission(final int emission) {
        return clampEmission(emission) * 16;
    }

    private static EmissiveCell emissiveCell(final BlockState state) {
        int blockEmission = clampEmission(state.getLightEmission());
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return new EmissiveCell(blockEmission, state, false);
        }
        BlockState fluidState = fluid.createLegacyBlock();
        int fluidEmission = clampEmission(fluidState.getLightEmission());
        return fluidEmission >= blockEmission && fluidEmission > 0
                ? new EmissiveCell(fluidEmission, fluidState, true)
                : new EmissiveCell(blockEmission, state, false);
    }

    private static int clampEmission(final int emission) {
        return Math.max(0, Math.min(15, emission));
    }

    static float[] linearColorForIdentifier(final Identifier id) {
        if (id == null || !"minecraft".equals(id.getNamespace())) {
            // Vanilla exposes intensity but no emitted chromaticity. Unknown mod sources must
            // still illuminate, and neutral is the only deterministic non-misleading fallback.
            return new float[]{1.0F, 1.0F, 1.0F};
        }
        String path = id.getPath();
        if (path.contains("soul_")) {
            return new float[]{0.035F, 0.34F, 1.0F};
        }
        if (path.equals("redstone_torch") || path.equals("redstone_wall_torch")) {
            return new float[]{1.0F, 0.012F, 0.003F};
        }
        if (path.contains("ochre_froglight")) {
            return new float[]{1.0F, 0.48F, 0.09F};
        }
        if (path.contains("pearlescent_froglight")) {
            return new float[]{0.69F, 0.15F, 1.0F};
        }
        if (path.contains("verdant_froglight")) {
            return new float[]{0.17F, 1.0F, 0.24F};
        }
        if (path.contains("sea_lantern") || path.contains("conduit")) {
            return new float[]{0.27F, 0.89F, 1.0F};
        }
        if (path.contains("end_rod")) {
            return new float[]{0.78F, 0.64F, 1.0F};
        }
        if (path.contains("lava") || path.contains("magma") || path.endsWith("fire")) {
            return new float[]{1.0F, 0.08F, 0.004F};
        }
        if (path.contains("glow_lichen")) {
            return new float[]{0.22F, 0.79F, 0.36F};
        }
        if (path.contains("sculk")) {
            return new float[]{0.006F, 0.28F, 0.52F};
        }
        if (path.contains("amethyst")) {
            return new float[]{0.48F, 0.12F, 1.0F};
        }
        if (path.contains("portal")) {
            return new float[]{0.48F, 0.12F, 1.0F};
        }
        return new float[]{1.0F, 0.26F, 0.035F};
    }

    private static EntityProfile entityProfile(final String path, final boolean onFire) {
        if (path.contains("lightning_bolt")) {
            return new EntityProfile(16.0F, 0.63F, 0.78F, 1.0F, 4.0F, 512);
        }
        if (path.contains("fireball") || path.contains("dragon_fireball")) {
            return new EntityProfile(12.0F, 1.0F, 0.08F, 0.004F, 3.2F, 448);
        }
        if (path.contains("blaze")) {
            return new EntityProfile(10.0F, 1.0F, 0.18F, 0.012F, 2.4F, 384);
        }
        if (path.contains("magma_cube")) {
            return new EntityProfile(8.0F, 1.0F, 0.035F, 0.002F, 1.7F, 352);
        }
        if (path.contains("glow_squid")) {
            return new EntityProfile(7.0F, 0.018F, 0.53F, 1.0F, 1.0F, 320);
        }
        if (path.contains("experience_orb")) {
            return new EntityProfile(6.0F, 0.18F, 1.0F, 0.09F, 0.85F, 288);
        }
        if (onFire) {
            return new EntityProfile(8.0F, 1.0F, 0.08F, 0.004F, 1.5F, 336);
        }
        return null;
    }

    private static EntityProfile carriedLightProfile(final Entity entity) {
        EntityProfile profile = null;
        if (entity instanceof LivingEntity living) {
            HeldLight held = heldLightProfile(living);
            profile = held == null ? null : held.profile;
        }
        if (entity instanceof ItemEntity item) {
            profile = brighter(profile, blockItemProfile(item.getItem()));
        }
        return profile;
    }

    private static HeldLight heldLightProfile(final LivingEntity living) {
        EntityProfile main = blockItemProfile(living.getMainHandItem());
        EntityProfile off = blockItemProfile(living.getOffhandItem());
        if (main == null && off == null) {
            return null;
        }
        if (main == null) {
            return new HeldLight(InteractionHand.OFF_HAND, off);
        }
        if (off == null || brighter(main, off) == main) {
            return new HeldLight(InteractionHand.MAIN_HAND, main);
        }
        return new HeldLight(InteractionHand.OFF_HAND, off);
    }

    private static EntityProfile blockItemProfile(final ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        BlockState state = blockItem.getBlock().defaultBlockState();
        int emission = state.getLightEmission();
        if (emission <= 0) {
            return null;
        }
        Identifier id = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        float[] color = linearColorForIdentifier(id);
        float normalized = emission / 15.0F;
        return new EntityProfile(
                1.5F + 0.75F * emission,
                color[0],
                color[1],
                color[2],
                0.15F + 3.0F * normalized * (float) Math.sqrt(normalized),
                priorityForEmission(emission)
        );
    }

    private static EntityProfile brighter(
            final EntityProfile first,
            final EntityProfile second
    ) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        float firstSignificance = first.intensity * first.radius * first.radius;
        float secondSignificance = second.intensity * second.radius * second.radius;
        return secondSignificance > firstSignificance ? second : first;
    }

    private record EntityProfile(
            float radius,
            float red,
            float green,
            float blue,
            float intensity,
            int priority
    ) {
    }

    private record HeldLight(InteractionHand hand, EntityProfile profile) {
    }

    private record EmissiveCell(
            int emission,
            BlockState colorState,
            boolean denseCellEligible
    ) {
    }
}
