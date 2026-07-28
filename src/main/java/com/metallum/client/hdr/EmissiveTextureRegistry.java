package com.metallum.client.hdr;

import com.metallum.Metallum;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.client.resources.metadata.texture.TextureMetadataSection;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discovers OptiFine-style emissive sidecars and exposes the stitched overlay sprite for a base
 * block-atlas sprite. The registry deliberately has no relation to Minecraft block light: it only
 * determines which visible pixels receive the separate HDR surface-emission pass.
 */
public final class EmissiveTextureRegistry {
    public static final String DEFAULT_SUFFIX = "_e";
    private static final String TEXTURES_PREFIX = "textures/";
    private static final String PNG_SUFFIX = ".png";
    private static final String EMISSIVE_PROPERTIES_PATH = "optifine/emissive.properties";
    private static final String VANILLA_PACK_ID = "vanilla";
    private static final float UV_EPSILON = 1.0e-6f;
    /**
     * Dark pixels that belong to an automatically detected emitter need a
     * visible (but still clearly weaker) HDR contribution. The generated
     * overlay keeps its original hue and lifts only its peak channel to this
     * minimum; authored {@code *_e.png} sidecars remain byte-for-byte intact.
     */
    private static final int GENERATED_EMISSION_MINIMUM_PEAK = 128;
    private static final int GENERATED_EMISSION_DIM_MINIMUM_PEAK = 18;
    /**
     * Saturated emissive details must retain their colour without competing
     * with true white highlights. The cap rises smoothly towards white.
     */
    private static final int GENERATED_EMISSION_COLORED_MAXIMUM_PEAK = 176;
    private static final Identifier BLOCK_ATLAS_TEXTURE =
            Identifier.withDefaultNamespace("textures/atlas/blocks.png");

    private static final AtomicReference<AtlasPlan> PENDING_BLOCK_PLAN =
            new AtomicReference<>(AtlasPlan.empty());
    private static volatile Map<Identifier, StitchedOverlay> stitchedOverlays = Map.of();

    private EmissiveTextureRegistry() {
    }

    /**
     * Called after the normal block atlas sources have populated {@code knownSprites}. It only
     * adds a sidecar when the matching base sprite is really present in this atlas, preventing
     * entity and item masks from needlessly occupying terrain atlas space.
     */
    public static void appendBlockAtlasOverlays(
            final ResourceManager resourceManager,
            final Map<Identifier, SpriteSource.DiscardableLoader> knownSprites,
            final SpriteSource.Output output
    ) {
        Objects.requireNonNull(resourceManager, "resourceManager");
        Objects.requireNonNull(knownSprites, "knownSprites");
        Objects.requireNonNull(output, "output");

        Map<String, Integer> packPriority = packPriority(resourceManager);
        Map<String, String> suffixes = suffixes(resourceManager);
        Map<Identifier, SidecarCandidate> sidecars = discoverSidecars(
                resourceManager, suffixes, packPriority
        );
        Map<Identifier, OverlayCandidate> selected = new LinkedHashMap<>();

        for (Map.Entry<Identifier, SidecarCandidate> entry : sidecars.entrySet()) {
            Identifier baseId = entry.getKey();
            SidecarCandidate candidate = entry.getValue();
            if (!knownSprites.containsKey(baseId)) {
                continue;
            }
            if (!knownSprites.containsKey(candidate.spriteId())) {
                output.add(candidate.spriteId(), candidate.resource());
            }
            selected.put(baseId, new OverlayCandidate(
                    candidate.spriteId(),
                    candidate.sourceDescription(),
                    requiresBlockEmissionForSidecar(baseId)
            ));
        }

        // output.add(...) can update the same map supplied as knownSprites.
        // Iterate a stable snapshot so built-in masks do not invalidate the
        // atlas loader's HashMap iterator during resource reload.
        for (Identifier baseId : List.copyOf(knownSprites.keySet())) {
            if (selected.containsKey(baseId)) {
                continue;
            }
            Resource baseResource = resourceManager.getResource(textureResourceId(baseId)).orElse(null);
            BuiltinMask mask = builtinMaskFor(baseId, baseResource);
            if (mask == null) {
                continue;
            }
            Identifier spriteId = builtinSpriteId(baseId);
            output.add(spriteId, new BuiltinMaskLoader(spriteId, baseResource, mask));
            selected.put(baseId, new OverlayCandidate(spriteId, "built-in " + baseId, true));
        }

        PENDING_BLOCK_PLAN.set(new AtlasPlan(Map.copyOf(selected)));
    }

    /** Atomically replaces the lookup only after the new block atlas has uploaded successfully. */
    public static void onBlockAtlasUploaded(
            final TextureAtlas atlas,
            final SpriteLoader.Preparations preparations
    ) {
        if (!BLOCK_ATLAS_TEXTURE.equals(atlas.location())) {
            return;
        }
        AtlasPlan plan = PENDING_BLOCK_PLAN.get();
        Map<Identifier, StitchedOverlay> resolved = new HashMap<>();
        for (Map.Entry<Identifier, OverlayCandidate> entry : plan.overlays().entrySet()) {
            TextureAtlasSprite overlay = preparations.regions().get(entry.getValue().spriteId());
            TextureAtlasSprite base = preparations.regions().get(entry.getKey());
            if (overlay == null || base == null) {
                Metallum.LOGGER.warn(
                        "Skipping emissive overlay {}: its base or sidecar did not stitch",
                        entry.getValue().sourceDescription()
                );
                continue;
            }
            if (!compatible(base, overlay)) {
                Metallum.LOGGER.warn(
                        "Skipping emissive overlay {}: base {} and sidecar {} have incompatible dimensions or animation",
                        entry.getValue().sourceDescription(), base.contents().name(), overlay.contents().name()
                );
                continue;
            }
            resolved.put(entry.getKey(), new StitchedOverlay(overlay, entry.getValue().requiresBlockEmission()));
        }
        stitchedOverlays = Map.copyOf(resolved);
        Metallum.LOGGER.info("Loaded {} partial emissive terrain texture overlays", stitchedOverlays.size());
    }

    @Nullable
    public static TextureAtlasSprite overlayFor(final TextureAtlasSprite baseSprite, final int blockLightEmission) {
        if (baseSprite == null || !BLOCK_ATLAS_TEXTURE.equals(baseSprite.atlasLocation())) {
            return null;
        }
        StitchedOverlay overlay = stitchedOverlays.get(baseSprite.contents().name());
        if (overlay == null || !allowsOverlay(overlay.requiresBlockEmission(), blockLightEmission)) {
            return null;
        }
        return overlay.sprite();
    }

    /** Reprojects a point in the base sprite's atlas rectangle into the overlay rectangle. */
    public static float remapCoordinate(
            final float coordinate,
            final float baseStart,
            final float baseEnd,
            final float overlayStart,
            final float overlayEnd
    ) {
        float baseSpan = baseEnd - baseStart;
        if (!Float.isFinite(coordinate) || !Float.isFinite(baseSpan)
                || !Float.isFinite(overlayStart) || !Float.isFinite(overlayEnd)
                || Math.abs(baseSpan) < UV_EPSILON) {
            return overlayStart;
        }
        float relative = Math.clamp((coordinate - baseStart) / baseSpan, 0.0f, 1.0f);
        return overlayStart + relative * (overlayEnd - overlayStart);
    }

    static String suffixFromProperties(final Properties properties) {
        if (properties == null) {
            return DEFAULT_SUFFIX;
        }
        String candidate = properties.getProperty("suffix.emissive", DEFAULT_SUFFIX).trim();
        if (candidate.isEmpty() || candidate.indexOf('/') >= 0 || candidate.indexOf('\\') >= 0
                || candidate.indexOf(':') >= 0 || candidate.contains("..")) {
            return DEFAULT_SUFFIX;
        }
        return candidate;
    }

    static boolean compatible(final TextureAtlasSprite base, final TextureAtlasSprite overlay) {
        return compatibleLayout(
                base.contents().width(), base.contents().height(), base.isAnimated(), base.contents().getUniqueFrames(),
                overlay.contents().width(), overlay.contents().height(), overlay.isAnimated(), overlay.contents().getUniqueFrames()
        );
    }

    static boolean compatibleLayout(
            final int baseWidth,
            final int baseHeight,
            final boolean baseAnimated,
            final Object baseFrames,
            final int overlayWidth,
            final int overlayHeight,
            final boolean overlayAnimated,
            final Object overlayFrames
    ) {
        return baseWidth == overlayWidth
                && baseHeight == overlayHeight
                && baseAnimated == overlayAnimated
                && Objects.equals(baseFrames, overlayFrames);
    }

    static boolean hasBuiltinMask(final Identifier baseSpriteId) {
        return BuiltinMask.forBaseSprite(baseSpriteId) != null;
    }

    static boolean usesGeneratedVanillaMask(final Identifier baseSpriteId, final String sourcePackId) {
        return VANILLA_PACK_ID.equals(sourcePackId)
                && "minecraft".equals(baseSpriteId.getNamespace())
                && baseSpriteId.getPath().startsWith("block/");
    }

    static boolean autoMaskMatches(final int argb) {
        return BuiltinMask.AUTO.matches(argb);
    }

    static boolean builtinMaskMatches(final Identifier baseSpriteId, final int argb) {
        BuiltinMask mask = BuiltinMask.forBaseSprite(baseSpriteId);
        return mask != null && mask.matches(argb);
    }

    static boolean hasAtLeastBasePriority(
            final String basePackId,
            final String overlayPackId,
            final int basePriority,
            final int overlayPriority
    ) {
        return basePackId.equals(overlayPackId) || overlayPriority >= basePriority;
    }

    static boolean allowsOverlay(final boolean requiresBlockEmission, final int blockLightEmission) {
        return !requiresBlockEmission || blockLightEmission > 0;
    }

    /**
     * These vanilla sprites are shared by an inactive block state and its
     * temporary light-emitting state. A standard {@code *_e.png} sidecar has
     * no block-state metadata, so gate it by the real state emission instead
     * of showing it permanently. All other authored sidecars retain normal
     * OptiFine-style always-on behavior.
     */
    static boolean requiresBlockEmissionForSidecar(final Identifier baseSpriteId) {
        return switch (baseSpriteId.toString()) {
            case "minecraft:block/redstone_ore", "minecraft:block/deepslate_redstone_ore" -> true;
            default -> false;
        };
    }

    private static Map<String, String> suffixes(final ResourceManager resourceManager) {
        Map<String, String> suffixes = new HashMap<>();
        for (String namespace : resourceManager.getNamespaces()) {
            Properties properties = new Properties();
            Optional<Resource> resource = resourceManager.getResource(
                    Identifier.fromNamespaceAndPath(namespace, EMISSIVE_PROPERTIES_PATH)
            );
            if (resource.isPresent()) {
                try (var reader = resource.get().openAsReader()) {
                    properties.load(reader);
                } catch (IOException exception) {
                    Metallum.LOGGER.warn(
                            "Could not read OptiFine emissive properties for namespace {}", namespace, exception
                    );
                }
            }
            suffixes.put(namespace, suffixFromProperties(properties));
        }
        return suffixes;
    }

    private static Map<Identifier, SidecarCandidate> discoverSidecars(
            final ResourceManager resourceManager,
            final Map<String, String> suffixes,
            final Map<String, Integer> packPriority
    ) {
        Map<Identifier, SidecarCandidate> candidates = new HashMap<>();
        // ResourceManager directory prefixes must not end in '/'. Keep the
        // slash-bearing constant for path slicing, but query the directory by
        // its canonical identifier form.
        resourceManager.listResources("textures", id -> id.getPath().endsWith(PNG_SUFFIX))
                .forEach((resourceId, overlayResource) -> {
                    String suffix = suffixes.getOrDefault(resourceId.getNamespace(), DEFAULT_SUFFIX);
                    String path = resourceId.getPath();
                    String marker = suffix + PNG_SUFFIX;
                    if (!path.startsWith(TEXTURES_PREFIX) || !path.endsWith(marker)) {
                        return;
                    }
                    String basePath = path.substring(TEXTURES_PREFIX.length(), path.length() - marker.length());
                    if (basePath.isEmpty()) {
                        return;
                    }
                    Identifier baseSpriteId = Identifier.fromNamespaceAndPath(resourceId.getNamespace(), basePath);
                    Resource baseResource = resourceManager.getResource(textureResourceId(baseSpriteId)).orElse(null);
                    if (baseResource == null || !hasAtLeastBasePriority(baseResource, overlayResource, packPriority)) {
                        return;
                    }
                    Identifier overlaySpriteId = Identifier.fromNamespaceAndPath(
                            resourceId.getNamespace(), path.substring(TEXTURES_PREFIX.length(), path.length() - PNG_SUFFIX.length())
                    );
                    candidates.put(baseSpriteId, new SidecarCandidate(
                            overlaySpriteId, overlayResource, resourceId.toString()
                    ));
                });
        return candidates;
    }

    private static boolean hasAtLeastBasePriority(
            final Resource base,
            final Resource overlay,
            final Map<String, Integer> packPriority
    ) {
        return hasAtLeastBasePriority(
                base.sourcePackId(), overlay.sourcePackId(),
                packPriority.getOrDefault(base.sourcePackId(), -1),
                packPriority.getOrDefault(overlay.sourcePackId(), -1)
        );
    }

    private static Map<String, Integer> packPriority(final ResourceManager resourceManager) {
        Map<String, Integer> priority = new HashMap<>();
        List<PackResources> packs = resourceManager.listPacks().toList();
        for (int index = 0; index < packs.size(); index++) {
            priority.put(packs.get(index).packId(), index);
        }
        return priority;
    }

    private static Identifier textureResourceId(final Identifier spriteId) {
        return spriteId.withPath(TEXTURES_PREFIX + spriteId.getPath() + PNG_SUFFIX);
    }

    private static Identifier builtinSpriteId(final Identifier baseId) {
        return Identifier.fromNamespaceAndPath(
                Metallum.MOD_ID, "emissive/" + baseId.getNamespace() + "/" + baseId.getPath()
        );
    }

    /**
     * Every untouched vanilla terrain sprite gets a generated fallback mask. It is only selected
     * for a block state that actually emits Minecraft light, so a shared off-state texture never
     * becomes emissive. Resource packs and mods must opt in with their own standard sidecar.
     */
    @Nullable
    private static BuiltinMask builtinMaskFor(final Identifier baseId, @Nullable final Resource baseResource) {
        if (baseResource == null || !usesGeneratedVanillaMask(baseId, baseResource.sourcePackId())) {
            return null;
        }
        BuiltinMask exactMask = BuiltinMask.forBaseSprite(baseId);
        return exactMask != null ? exactMask : BuiltinMask.AUTO;
    }

    private record SidecarCandidate(Identifier spriteId, Resource resource, String sourceDescription) {
    }

    private record OverlayCandidate(Identifier spriteId, String sourceDescription, boolean requiresBlockEmission) {
    }

    private record StitchedOverlay(TextureAtlasSprite sprite, boolean requiresBlockEmission) {
    }

    private record AtlasPlan(Map<Identifier, OverlayCandidate> overlays) {
        static AtlasPlan empty() {
            return new AtlasPlan(Map.of());
        }
    }

    /** Built-in vanilla masks are generated in memory only for an unmodified vanilla base sprite. */
    private enum BuiltinMask {
        REDSTONE((alpha, red, green, blue) -> alpha > 0 && red >= 96 && red > green * 1.12f && red > blue * 1.12f),
        GLOW_BERRIES((alpha, red, green, blue) -> alpha > 0 && red >= 96 && red > green * 1.18f && green > blue * 1.15f),
        AMETHYST((alpha, red, green, blue) -> alpha > 0 && red > green * 1.10f && blue > green * 1.10f),
        SCULK((alpha, red, green, blue) -> alpha > 0 && green >= 100 && blue >= 100 && green > red * 1.45f && blue > red * 1.45f),
        MAGMA((alpha, red, green, blue) -> alpha > 0 && red >= 140 && green >= 45 && blue <= 96 && red > green * 1.25f),
        CRYING_OBSIDIAN((alpha, red, green, blue) -> alpha > 0 && blue >= 105 && blue > red * 1.20f && blue > green * 1.25f),
        AUTO((alpha, red, green, blue) -> {
            if (alpha == 0) {
                return false;
            }
            int maximum = Math.max(red, Math.max(green, blue));
            int minimum = Math.min(red, Math.min(green, blue));
            return maximum >= 192 || maximum >= 144 && maximum - minimum >= 44;
        });

        private final PixelMatcher matcher;

        BuiltinMask(final PixelMatcher matcher) {
            this.matcher = matcher;
        }

        static @Nullable BuiltinMask forBaseSprite(final Identifier id) {
            return switch (id.toString()) {
                case "minecraft:block/redstone_ore", "minecraft:block/deepslate_redstone_ore" -> REDSTONE;
                case "minecraft:block/cave_vines_lit", "minecraft:block/cave_vines_plant_lit" -> GLOW_BERRIES;
                case "minecraft:block/small_amethyst_bud", "minecraft:block/medium_amethyst_bud",
                        "minecraft:block/large_amethyst_bud", "minecraft:block/amethyst_cluster" -> AMETHYST;
                case "minecraft:block/sculk_catalyst_side_bloom", "minecraft:block/sculk_catalyst_top_bloom",
                        "minecraft:block/sculk_sensor_tendril_active",
                        "minecraft:block/sculk_shrieker_can_summon_inner_top" -> SCULK;
                case "minecraft:block/magma" -> MAGMA;
                case "minecraft:block/crying_obsidian" -> CRYING_OBSIDIAN;
                default -> null;
            };
        }

        boolean matches(final int argb) {
            int alpha = argb >>> 24;
            int red = argb >>> 16 & 0xff;
            int green = argb >>> 8 & 0xff;
            int blue = argb & 0xff;
            return this.matcher.matches(alpha, red, green, blue);
        }
    }

    /**
     * Converts one opaque pixel of an in-memory vanilla fallback into its soft
     * emissive counterpart. Every opaque texel receives an HDR contribution:
     * selected detail is brighter, while the rest remains visibly dim.
     */
    static int generatedMaskPixel(final int argb, final boolean selected) {
        int alpha = argb >>> 24;
        if (alpha == 0) {
            return 0;
        }
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        int maximum = Math.max(red, Math.max(green, blue));
        int minimum = Math.min(red, Math.min(green, blue));
        int minimumPeak = selected
                ? GENERATED_EMISSION_MINIMUM_PEAK
                : GENERATED_EMISSION_DIM_MINIMUM_PEAK;
        if (maximum == 0) {
            return alpha << 24 | minimumPeak << 16 | minimumPeak << 8 | minimumPeak;
        }
        int targetPeak = Math.max(maximum, minimumPeak);
        if (selected && minimum < maximum) {
            int colorPreservingMaximum = GENERATED_EMISSION_COLORED_MAXIMUM_PEAK
                    + (minimum * (255 - GENERATED_EMISSION_COLORED_MAXIMUM_PEAK) + 127) / 255;
            targetPeak = Math.min(targetPeak, colorPreservingMaximum);
        }
        if (targetPeak == maximum) {
            return argb;
        }
        int liftedRed = Math.min(255, (red * targetPeak + maximum / 2) / maximum);
        int liftedGreen = Math.min(255, (green * targetPeak + maximum / 2) / maximum);
        int liftedBlue = Math.min(255, (blue * targetPeak + maximum / 2) / maximum);
        return alpha << 24 | liftedRed << 16 | liftedGreen << 8 | liftedBlue;
    }

    @FunctionalInterface
    private interface PixelMatcher {
        boolean matches(int alpha, int red, int green, int blue);
    }

    private static final class BuiltinMaskLoader implements SpriteSource.DiscardableLoader {
        private final Identifier spriteId;
        private final Resource baseResource;
        private final BuiltinMask mask;

        private BuiltinMaskLoader(final Identifier spriteId, final Resource baseResource, final BuiltinMask mask) {
            this.spriteId = spriteId;
            this.baseResource = baseResource;
            this.mask = mask;
        }

        @Override
        public @Nullable SpriteContents get(final net.minecraft.client.renderer.texture.atlas.SpriteResourceLoader ignored) {
            NativeImage base = null;
            NativeImage overlay = null;
            try (InputStream input = this.baseResource.open()) {
                base = NativeImage.read(input);
                overlay = new NativeImage(NativeImage.Format.RGBA, base.getWidth(), base.getHeight(), true);
                for (int y = 0; y < base.getHeight(); y++) {
                    for (int x = 0; x < base.getWidth(); x++) {
                        int pixel = base.getPixel(x, y);
                        boolean selected = this.mask.matches(pixel);
                        overlay.setPixel(x, y, generatedMaskPixel(pixel, selected));
                    }
                }
                ResourceMetadata metadata = this.baseResource.metadata();
                Optional<AnimationMetadataSection> animation = metadata.getSection(AnimationMetadataSection.TYPE);
                Optional<TextureMetadataSection> texture = metadata.getSection(TextureMetadataSection.TYPE);
                int width = overlay.getWidth();
                int height = overlay.getHeight();
                FrameSize frameSize = animation.isPresent()
                        ? animation.get().calculateFrameSize(width, height)
                        : new FrameSize(width, height);
                SpriteContents contents = new SpriteContents(
                        this.spriteId, frameSize, overlay, animation, List.of(), texture
                );
                overlay = null;
                return contents;
            } catch (IOException | RuntimeException exception) {
                Metallum.LOGGER.warn("Could not generate built-in emissive mask {}", this.spriteId, exception);
                return null;
            } finally {
                if (base != null) {
                    base.close();
                }
                if (overlay != null) {
                    overlay.close();
                }
            }
        }

    }
}
