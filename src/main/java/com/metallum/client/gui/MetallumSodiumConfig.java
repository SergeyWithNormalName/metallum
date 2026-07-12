package com.metallum.client.gui;

import com.metallum.client.hdr.HdrConfig;
import com.metallum.client.hdr.HdrMode;
import com.metallum.client.hdr.HdrSourceEncoding;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metalfx.SpatialScalingMode;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.client.Minecraft;

import java.util.Locale;

public class MetallumSodiumConfig implements ConfigEntryPoint {
    private static final StorageEventHandler STORAGE_HANDLER = () -> {
        // Each option binding persists the updated immutable config immediately.
    };

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
            .setName("Metallum")
            .addPage(builder.createOptionPage()
                .setName(Component.translatable("metallum.options.page"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("metallum.options.group.metalfx"))
                    .addOption(builder.createEnumOption(
                                Identifier.fromNamespaceAndPath("metallum", "spatial_scaling"),
                                SpatialScalingMode.class
                            )
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable("metallum.options.metalfx_spatial_scaling.name"))
                            .setTooltip(MetallumSodiumConfig::spatialScalingTooltip)
                            .setElementNameProvider(mode -> Component.translatable(mode.translationKey()))
                            .setDefaultValue(SpatialScalingMode.OFF)
                            .setBinding(
                                    MetalFxSpatialScaling::setRequestedMode,
                                    MetalFxSpatialScaling::requestedMode
                            )
                    )
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.literal("HDR Mode & Output"))
                    .addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath("metallum", "hdr_mode"), HdrMode.class)
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.literal("HDR Mode"))
                        .setTooltip(Component.literal("Controls HDR output. Changes to this setting require a restart."))
                        .setElementNameProvider(MetallumSodiumConfig::formatEnumValue)
                        .setDefaultValue(HdrMode.AUTO)
                        .setBinding(
                            mode -> updateConfig(c -> new HdrConfig(mode, c.sourceEncoding(), c.hdrStrength(), c.bloomStrength(), c.diagnosticPattern(), c.experimentalFp16())),
                            () -> getConfig().mode()
                        )
                    )
                    .addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath("metallum", "source_encoding"), HdrSourceEncoding.class)
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.literal("Source Encoding"))
                        .setTooltip(Component.literal("Source color space encoding. Changes to this setting require a restart."))
                        .setElementNameProvider(MetallumSodiumConfig::formatEnumValue)
                        .setDefaultValue(HdrSourceEncoding.SRGB)
                        .setBinding(
                            encoding -> updateConfig(c -> new HdrConfig(c.mode(), encoding, c.hdrStrength(), c.bloomStrength(), c.diagnosticPattern(), c.experimentalFp16())),
                            () -> getConfig().sourceEncoding()
                        )
                    )
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.literal("Post-Processing & Strengths"))
                    .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("metallum", "hdr_strength"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.literal("HDR Strength"))
                        .setTooltip(Component.literal("Multiplier for HDR scene brightness (range 0% to 200%). Applied dynamically."))
                        .setDefaultValue(100)
                        .setRange(0, 200, 5)
                        .setValueFormatter(val -> Component.literal(String.format(Locale.ROOT, "%.2f", val / 100.0f)))
                        .setBinding(
                            val -> updateConfig(c -> new HdrConfig(c.mode(), c.sourceEncoding(), val / 100.0f, c.bloomStrength(), c.diagnosticPattern(), c.experimentalFp16())),
                            () -> (int) (getConfig().hdrStrength() * 100)
                        )
                    )
                    .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("metallum", "bloom_strength"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.literal("Bloom Strength"))
                        .setTooltip(Component.literal("Strength of the FP16 coverage-weighted bloom pass. Applied dynamically."))
                        .setDefaultValue(22)
                        .setRange(0, 100, 1)
                        .setValueFormatter(val -> Component.literal(String.format(Locale.ROOT, "%.2f", val / 100.0f)))
                        .setBinding(
                            val -> updateConfig(c -> new HdrConfig(c.mode(), c.sourceEncoding(), c.hdrStrength(), val / 100.0f, c.diagnosticPattern(), c.experimentalFp16())),
                            () -> (int) (getConfig().bloomStrength() * 100)
                        )
                    )
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.literal("Experimental & Diagnostics"))
                    .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("metallum", "diagnostic_pattern"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.literal("Full-Screen HDR Calibration Pattern"))
                        .setTooltip(Component.literal("Replaces the entire frame with an HDR calibration pattern. Press Esc to exit the pattern."))
                        .setDefaultValue(false)
                        .setBinding(
                            val -> updateConfig(c -> c.withDiagnosticPattern(val)),
                            () -> getConfig().diagnosticPattern()
                        )
                    )
                    .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("metallum", "experimental_fp16"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.literal("Experimental FP16 Scene Path"))
                        .setTooltip(Component.literal("Enables the FP16 scene pipeline for enhanced modes. Changes require a restart."))
                        .setDefaultValue(false)
                        .setBinding(
                            val -> updateConfig(c -> new HdrConfig(c.mode(), c.sourceEncoding(), c.hdrStrength(), c.bloomStrength(), c.diagnosticPattern(), val)),
                            () -> getConfig().experimentalFp16()
                        )
                    )
                )
            );
    }

    private static Component formatEnumValue(Enum<?> value) {
        String label = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Component.literal(Character.toUpperCase(label.charAt(0)) + label.substring(1));
    }

    private static Component spatialScalingTooltip(final SpatialScalingMode mode) {
        Minecraft minecraft = Minecraft.getInstance();
        int displayWidth = minecraft != null && minecraft.getWindow() != null
                ? minecraft.getWindow().getWidth()
                : 1;
        int displayHeight = minecraft != null && minecraft.getWindow() != null
                ? minecraft.getWindow().getHeight()
                : 1;
        MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.dimensions(
                mode,
                displayWidth,
                displayHeight
        );
        if (!mode.enabled()) {
            return Component.translatable(
                    "metallum.options.metalfx_spatial_scaling.tooltip.off",
                    dimensions.displayWidth(),
                    dimensions.displayHeight()
            );
        }

        if (!MetalFxSpatialScaling.isSupported() || MetalFxSpatialScaling.isRuntimeDisabled()) {
            return Component.translatable(
                    "metallum.options.metalfx_spatial_scaling.tooltip.unavailable",
                    dimensions.displayWidth(),
                    dimensions.displayHeight()
            );
        }

        return Component.translatable(
                "metallum.options.metalfx_spatial_scaling.tooltip.enabled",
                dimensions.renderWidth(),
                dimensions.renderHeight(),
                dimensions.displayWidth(),
                dimensions.displayHeight(),
                mode.nominalLinearPercent(),
                Math.round(dimensions.actualWidthScale() * 1000.0f) / 10.0f,
                Math.round(dimensions.actualHeightScale() * 1000.0f) / 10.0f,
                Math.round(dimensions.actualPixelScale() * 1000.0f) / 10.0f
        );
    }

    private static HdrConfig getConfig() {
        MetalDevice device = MetalDevice.getInstance();
        return device != null ? device.hdrConfig() : HdrConfig.load();
    }

    private static void updateConfig(java.util.function.Function<HdrConfig, HdrConfig> updater) {
        MetalDevice device = MetalDevice.getInstance();
        HdrConfig current = device != null ? device.hdrConfig() : HdrConfig.load();
        HdrConfig updated = updater.apply(current);
        if (device != null) {
            device.updateHdrConfig(updated);
        } else {
            updated.save();
        }
    }
}
