package com.metallum.client.gui;

import com.metallum.client.hdr.HdrConfig;
import com.metallum.client.hdr.HdrMode;
import com.metallum.client.hdr.HdrOutputMode;
import com.metallum.client.hdr.HdrSourceEncoding;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metalfx.MetalFxTemporalScaling;
import com.metallum.client.metalfx.SpatialScalingMode;
import com.metallum.client.metalfx.TemporalAlgorithmPolicy;
import com.metallum.client.metalfx.TemporalScalingMode;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.RendererConfig;
import com.metallum.client.voxel.VoxelPreviewMode;
import com.metallum.client.voxel.VoxelPreviewSettings;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.StorageEventHandler;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
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
                    .setName(Component.translatable("metallum.options.group.lighting"))
                    .addOption(builder.createBooleanOption(
                                Identifier.fromNamespaceAndPath("metallum", "improved_lighting")
                            )
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable(
                                    "metallum.options.improved_lighting.name"
                            ))
                            .setTooltip(Component.translatable(
                                    "metallum.options.improved_lighting.tooltip"
                            ))
                            .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                            .setDefaultValue(false)
                            .setBinding(
                                    MetallumSodiumConfig::setImprovedLighting,
                                    () -> RendererConfig.load().improvedLighting()
                            )
                    )
                    .addOption(builder.createEnumOption(
                                Identifier.fromNamespaceAndPath("metallum", "lighting_preset"),
                                LightingPreset.class
                            )
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable(
                                    "metallum.options.lighting_preset.name"
                            ))
                            .setTooltip(Component.translatable(
                                    "metallum.options.lighting_preset.tooltip"
                            ))
                            .setElementNameProvider(preset -> Component.translatable(
                                    "metallum.options.lighting_preset."
                                            + preset.name().toLowerCase(Locale.ROOT)
                            ))
                            .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                            .setDefaultValue(LightingPreset.BALANCED)
                            .setBinding(
                                    MetallumSodiumConfig::setLightingPreset,
                                    () -> RendererConfig.load().lightingPreset()
                            )
                    )
                )
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
                    .addOption(builder.createEnumOption(
                                Identifier.fromNamespaceAndPath("metallum", "temporal_scaling"),
                                TemporalScalingMode.class
                            )
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable("metallum.options.metalfx_temporal_scaling.name"))
                            .setTooltip(MetallumSodiumConfig::temporalScalingTooltip)
                            .setElementNameProvider(mode -> Component.translatable(mode.translationKey()))
                            .setDefaultValue(TemporalScalingMode.OFF)
                            .setBinding(
                                    MetalFxTemporalScaling::setRequestedMode,
                                    MetalFxTemporalScaling::requestedMode
                            )
                    )
                    .addOption(builder.createEnumOption(
                                Identifier.fromNamespaceAndPath("metallum", "temporal_algorithm"),
                                TemporalAlgorithmPolicy.class
                            )
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable(
                                    "metallum.options.metalfx_temporal_algorithm.name"
                            ))
                            .setTooltip(MetallumSodiumConfig::temporalAlgorithmTooltip)
                            .setElementNameProvider(policy -> Component.translatable(policy.translationKey()))
                            .setDefaultValue(TemporalAlgorithmPolicy.AUTO)
                            .setBinding(
                                    MetalFxTemporalScaling::setRequestedAlgorithmPolicy,
                                    MetalFxTemporalScaling::requestedAlgorithmPolicy
                            )
                    )
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("metallum.options.group.hdr"))
                    .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("metallum", "hdr_enabled"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.translatable("metallum.options.hdr_enabled.name"))
                        .setTooltip(Component.translatable("metallum.options.hdr_enabled.tooltip"))
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setDefaultValue(true)
                        .setBinding(
                            enabled -> updateConfig(c -> new HdrConfig(enabled ? HdrMode.AUTO : HdrMode.OFF, c.sourceEncoding(), c.hdrStrength(), c.bloomStrength(), c.diagnosticPattern(), c.experimentalFp16())),
                            () -> getConfig().mode() != HdrMode.OFF
                        )
                    )
                    .addOption(builder.createEnumOption(Identifier.fromNamespaceAndPath("metallum", "source_encoding"), HdrSourceEncoding.class)
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.translatable("metallum.options.source_encoding.name"))
                        .setTooltip(Component.translatable("metallum.options.source_encoding.tooltip"))
                        .setElementNameProvider(mode -> Component.translatable("metallum.options.source_encoding." + mode.name().toLowerCase(Locale.ROOT)))
                        .setDefaultValue(HdrSourceEncoding.SRGB)
                        .setBinding(
                            encoding -> updateConfig(c -> new HdrConfig(c.mode(), encoding, c.hdrStrength(), c.bloomStrength(), c.diagnosticPattern(), c.experimentalFp16())),
                            () -> getConfig().sourceEncoding()
                        )
                    )
                )
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Component.translatable("metallum.options.group.post_processing"))
                    .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath("metallum", "hdr_strength"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.translatable("metallum.options.hdr_strength.name"))
                        .setTooltip(Component.translatable("metallum.options.hdr_strength.tooltip"))
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
                        .setName(Component.translatable("metallum.options.bloom_strength.name"))
                        .setTooltip(Component.translatable("metallum.options.bloom_strength.tooltip"))
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
                    .setName(Component.translatable("metallum.options.group.experimental"))
                    .addOption(builder.createEnumOption(
                                Identifier.fromNamespaceAndPath("metallum", "voxel_preview_mode"),
                                VoxelPreviewMode.class
                            )
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable(
                                    "metallum.options.voxel_preview_mode.name"
                            ))
                            .setTooltip(Component.translatable(
                                    "metallum.options.voxel_preview_mode.tooltip"
                            ))
                            .setElementNameProvider(mode -> Component.translatable(
                                    "metallum.options.voxel_preview_mode."
                                            + mode.name().toLowerCase(Locale.ROOT)
                            ))
                            .setDefaultValue(VoxelPreviewMode.OFF)
                            .setBinding(VoxelPreviewSettings::setMode,
                                    () -> VoxelPreviewSettings.get().mode())
                    )
                    .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(
                                    "metallum", "voxel_preview_level"
                            ))
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable(
                                    "metallum.options.voxel_preview_level.name"
                            ))
                            .setTooltip(Component.translatable(
                                    "metallum.options.voxel_preview_level.tooltip"
                            ))
                            .setDefaultValue(0)
                            .setRange(0, 2, 1)
                            .setValueFormatter(value -> Component.literal(Integer.toString(value)))
                            .setBinding(VoxelPreviewSettings::setLevel,
                                    () -> VoxelPreviewSettings.get().level())
                    )
                    .addOption(builder.createIntegerOption(Identifier.fromNamespaceAndPath(
                                    "metallum", "voxel_preview_slice"
                            ))
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable(
                                    "metallum.options.voxel_preview_slice.name"
                            ))
                            .setTooltip(Component.translatable(
                                    "metallum.options.voxel_preview_slice.tooltip"
                            ))
                            .setDefaultValue(0)
                            .setRange(0, 383, 1)
                            .setValueFormatter(value -> Component.literal(Integer.toString(value)))
                            .setBinding(VoxelPreviewSettings::setSlice,
                                    () -> VoxelPreviewSettings.get().slice())
                    )
                    .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath(
                                    "metallum", "voxel_debug_checksum"
                            ))
                            .setStorageHandler(STORAGE_HANDLER)
                            .setName(Component.translatable(
                                    "metallum.options.voxel_debug_checksum.name"
                            ))
                            .setTooltip(Component.translatable(
                                    "metallum.options.voxel_debug_checksum.tooltip"
                            ))
                            .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                            .setDefaultValue(false)
                            .setBinding(
                                    MetallumSodiumConfig::setVoxelDebugChecksum,
                                    () -> RendererConfig.load().voxelDebugChecksum()
                            )
                    )
                    .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("metallum", "diagnostic_pattern"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.translatable("metallum.options.diagnostic_pattern.name"))
                        .setTooltip(Component.translatable("metallum.options.diagnostic_pattern.tooltip"))
                        .setDefaultValue(false)
                        .setBinding(
                            val -> updateConfig(c -> c.withDiagnosticPattern(val)),
                            () -> getConfig().diagnosticPattern()
                        )
                    )
                    .addOption(builder.createBooleanOption(Identifier.fromNamespaceAndPath("metallum", "experimental_fp16"))
                        .setStorageHandler(STORAGE_HANDLER)
                        .setName(Component.translatable("metallum.options.experimental_fp16.name"))
                        .setTooltip(Component.translatable("metallum.options.experimental_fp16.tooltip"))
                        .setDefaultValue(false)
                        .setBinding(
                            val -> updateConfig(c -> new HdrConfig(c.mode(), c.sourceEncoding(), c.hdrStrength(), c.bloomStrength(), c.diagnosticPattern(), val)),
                            () -> getConfig().experimentalFp16()
                        )
                    )
                )
            );
    }


    private static Component spatialScalingTooltip(final SpatialScalingMode mode) {
        Minecraft minecraft = Minecraft.getInstance();
        int displayWidth = minecraft != null && minecraft.getWindow() != null
                ? minecraft.getWindow().getWidth()
                : 1;
        int displayHeight = minecraft != null && minecraft.getWindow() != null
                ? minecraft.getWindow().getHeight()
                : 1;
        MetalDevice device = MetalDevice.getInstance();
        HdrOutputMode outputMode = device != null ? device.hdrOutputMode() : HdrOutputMode.SDR;
        SpatialScalingMode resolvedMode = MetalFxSpatialScaling.resolveRequestedMode(mode, outputMode);
        MetalFxSpatialScaling.Dimensions dimensions = MetalFxSpatialScaling.dimensions(
                resolvedMode,
                displayWidth,
                displayHeight
        );
        if (mode == SpatialScalingMode.AUTO && !resolvedMode.enabled()) {
            return Component.translatable(
                    "metallum.options.metalfx_spatial_scaling.tooltip.auto_off",
                    dimensions.displayWidth(),
                    dimensions.displayHeight()
            );
        }
        if (!resolvedMode.enabled()) {
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

        String tooltipKey = mode == SpatialScalingMode.AUTO
                ? "metallum.options.metalfx_spatial_scaling.tooltip.auto_enabled"
                : "metallum.options.metalfx_spatial_scaling.tooltip.enabled";
        if (mode == SpatialScalingMode.AUTO) {
            return Component.translatable(
                    tooltipKey,
                    Component.translatable(resolvedMode.translationKey()),
                    dimensions.renderWidth(),
                    dimensions.renderHeight(),
                    dimensions.displayWidth(),
                    dimensions.displayHeight(),
                    resolvedMode.nominalLinearPercent(),
                    Math.round(dimensions.actualWidthScale() * 1000.0f) / 10.0f,
                    Math.round(dimensions.actualHeightScale() * 1000.0f) / 10.0f,
                    Math.round(dimensions.actualPixelScale() * 1000.0f) / 10.0f
            );
        }
        return Component.translatable(
                tooltipKey,
                dimensions.renderWidth(),
                dimensions.renderHeight(),
                dimensions.displayWidth(),
                dimensions.displayHeight(),
                resolvedMode.nominalLinearPercent(),
                Math.round(dimensions.actualWidthScale() * 1000.0f) / 10.0f,
                Math.round(dimensions.actualHeightScale() * 1000.0f) / 10.0f,
                Math.round(dimensions.actualPixelScale() * 1000.0f) / 10.0f
        );
    }

    private static Component temporalScalingTooltip(final TemporalScalingMode mode) {
        Minecraft minecraft = Minecraft.getInstance();
        int displayWidth = minecraft != null && minecraft.getWindow() != null
                ? minecraft.getWindow().getWidth()
                : 1;
        int displayHeight = minecraft != null && minecraft.getWindow() != null
                ? minecraft.getWindow().getHeight()
                : 1;
        MetalFxTemporalScaling.Dimensions dimensions = MetalFxTemporalScaling.dimensions(
                mode, displayWidth, displayHeight
        );
        if (!mode.enabled()) {
            return Component.translatable(
                    "metallum.options.metalfx_temporal_scaling.tooltip.off",
                    dimensions.displayWidth(), dimensions.displayHeight()
            );
        }
        if (!MetalFxTemporalScaling.isSupported() || MetalFxTemporalScaling.isRuntimeDisabled()) {
            return Component.translatable(
                    "metallum.options.metalfx_temporal_scaling.tooltip.unavailable",
                    dimensions.displayWidth(), dimensions.displayHeight()
            );
        }
        return Component.translatable(
                "metallum.options.metalfx_temporal_scaling.tooltip.enabled",
                dimensions.renderWidth(), dimensions.renderHeight(),
                dimensions.displayWidth(), dimensions.displayHeight(),
                mode.nominalLinearPercent(),
                Math.round(dimensions.actualWidthScale() * 1000.0f) / 10.0f,
                Math.round(dimensions.actualHeightScale() * 1000.0f) / 10.0f,
                Math.round(dimensions.actualPixelScale() * 1000.0f) / 10.0f
        );
    }

    private static Component temporalAlgorithmTooltip(final TemporalAlgorithmPolicy policy) {
        return Component.translatable(
                "metallum.options.metalfx_temporal_algorithm.tooltip."
                        + policy.name().toLowerCase(Locale.ROOT)
        );
    }

    private static HdrConfig getConfig() {
        MetalDevice device = MetalDevice.getInstance();
        return device != null ? device.hdrConfig() : HdrConfig.load();
    }

    private static void setImprovedLighting(final boolean enabled) {
        RendererConfig.load().withImprovedLighting(enabled).save();
    }

    private static void setLightingPreset(final LightingPreset preset) {
        RendererConfig.load().withLightingPreset(preset).save();
    }

    private static void setVoxelDebugChecksum(final boolean enabled) {
        RendererConfig.load().withVoxelDebugChecksum(enabled).save();
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
