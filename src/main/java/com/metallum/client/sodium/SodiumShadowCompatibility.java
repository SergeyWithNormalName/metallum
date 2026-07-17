package com.metallum.client.sodium;

import net.fabricmc.loader.api.FabricLoader;

/** Exact bytecode gate for the L4 Sodium terrain target/uniform bridge. */
public final class SodiumShadowCompatibility {
    private static final String MINECRAFT_VERSION = "26.2";
    private static final String SODIUM_VERSION = "0.9.1+mc26.2";
    private static final boolean SUPPORTED = detectSupport();

    private SodiumShadowCompatibility() {
    }

    public static boolean supportsInstalledRenderer() {
        return SUPPORTED;
    }

    private static boolean detectSupport() {
        FabricLoader loader = FabricLoader.getInstance();
        if (!loader.isModLoaded("sodium")) {
            return true;
        }
        return hasExactVersion(loader, "minecraft", MINECRAFT_VERSION)
                && hasExactVersion(loader, "sodium", SODIUM_VERSION);
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
