package com.metallum.client.gui;

import com.metallum.client.lighting.reflection.CloudReflectionConfig;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Reflection controls kept in their own Sodium page so the frozen GI page contract stays intact. */
public final class MetallumReflectionSodiumConfig implements ConfigEntryPoint {
    private static final StorageEventHandler STORAGE_HANDLER = () -> {
        // The binding persists its value immediately.
    };

    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        builder.registerModOptions("metallum_reflections", "Metallum Reflections", "1")
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("metallum.options.reflections.page"))
                        .addOptionGroup(builder.createOptionGroup()
                                .setName(Component.translatable(
                                        "metallum.options.group.reflection_composition"
                                ))
                                .addOption(builder.createBooleanOption(
                                                Identifier.fromNamespaceAndPath(
                                                        "metallum", "cloud_reflections"
                                                )
                                        )
                                        .setStorageHandler(STORAGE_HANDLER)
                                        .setName(Component.translatable(
                                                "metallum.options.cloud_reflections.name"
                                        ))
                                        .setTooltip(Component.translatable(
                                                "metallum.options.cloud_reflections.tooltip"
                                        ))
                                        .setDefaultValue(true)
                                        .setBinding(
                                                CloudReflectionConfig::setEnabled,
                                                CloudReflectionConfig::isEnabled
                                        )
                                )
                        )
                );
    }
}
