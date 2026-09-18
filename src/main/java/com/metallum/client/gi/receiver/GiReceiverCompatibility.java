package com.metallum.client.gi.receiver;

import net.fabricmc.loader.api.FabricLoader;

/** Exact third-party layout gate for the Sodium 0.9.1 compact-position carrier. */
public final class GiReceiverCompatibility {
    public static final String MINECRAFT_VERSION = "26.2";
    public static final String SODIUM_VERSION = "0.9.1+mc26.2";
    public static final String MIXIN_EXTRAS_VERSION = "0.5.4";

    private static final boolean INSTALLED_SUPPORTED = detectInstalledSupport();
    private static volatile Boolean testOverride;

    private GiReceiverCompatibility() {
    }

    public static boolean supportsInstalledCompactPositionCarrier() {
        Boolean override = testOverride;
        return override != null ? override : INSTALLED_SUPPORTED;
    }

    public static boolean supportsExactVersions(
            final String minecraftVersion,
            final String sodiumVersion,
            final String mixinExtrasVersion
    ) {
        return MINECRAFT_VERSION.equals(minecraftVersion)
                && SODIUM_VERSION.equals(sodiumVersion)
                && MIXIN_EXTRAS_VERSION.equals(mixinExtrasVersion);
    }

    /** Unit-test override; production admission always uses Fabric Loader metadata. */
    public static void setTestOverride(final Boolean supported) {
        testOverride = supported;
    }

    private static boolean detectInstalledSupport() {
        FabricLoader loader = FabricLoader.getInstance();
        return hasExactVersion(loader, "minecraft", MINECRAFT_VERSION)
                && hasExactVersion(loader, "sodium", SODIUM_VERSION)
                && hasExactVersion(loader, "mixinextras", MIXIN_EXTRAS_VERSION);
    }

    private static boolean hasExactVersion(
            final FabricLoader loader,
            final String modId,
            final String expectedVersion
    ) {
        try {
            return loader.getModContainer(modId)
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .filter(expectedVersion::equals)
                    .isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
