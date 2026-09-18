package com.metallum.client.renderer;

import com.metallum.client.lighting.reflection.WaterReflectionConfig;
import com.metallum.client.lighting.reflection.WaterReflectionMode;
import com.metallum.client.metalfx.MetalFxUpscaling;
import com.metallum.client.metalfx.MetalFxUpscalingMode;

import java.util.Locale;
import java.util.Objects;

public enum GraphicsPreset {
    PERFORMANCE("metallum.options.graphics_preset.performance"),
    BALANCED("metallum.options.graphics_preset.balanced"),
    ULTRA("metallum.options.graphics_preset.ultra"),
    CUSTOM("metallum.options.graphics_preset.custom");

    private final String translationKey;

    GraphicsPreset(final String translationKey) {
        this.translationKey = translationKey;
    }

    public String translationKey() {
        return this.translationKey;
    }

    public static GraphicsPreset detect() {
        RendererConfig cfg = RendererConfig.load();
        WaterReflectionMode water = WaterReflectionConfig.getPersistedMode();
        MetalFxUpscalingMode metalfx = MetalFxUpscaling.requestedMode();

        if (cfg.lightingPreset() == LightingPreset.PERFORMANCE
                && cfg.improvedLighting()
                && !cfg.globalIllumination().isDynamic()
                && water == WaterReflectionMode.OFF
                && metalfx == MetalFxUpscalingMode.TEMPORAL) {
            return PERFORMANCE;
        }

        if (cfg.lightingPreset() == LightingPreset.BALANCED
                && cfg.improvedLighting()
                && !cfg.globalIllumination().isDynamic()
                && water == WaterReflectionMode.VOXELS
                && metalfx == MetalFxUpscalingMode.TEMPORAL) {
            return BALANCED;
        }

        if (cfg.lightingPreset() == LightingPreset.ULTRA
                && cfg.improvedLighting()
                && cfg.globalIllumination().isDynamic()
                && water == WaterReflectionMode.VOXELS
                && (metalfx == MetalFxUpscalingMode.TEMPORAL_FI || metalfx == MetalFxUpscalingMode.OFF)) {
            return ULTRA;
        }

        return CUSTOM;
    }

    public static void apply(final GraphicsPreset preset) {
        Objects.requireNonNull(preset, "preset");
        if (preset == CUSTOM) {
            return;
        }

        RendererConfig current = RendererConfig.load();
        switch (preset) {
            case PERFORMANCE -> {
                current.withLightingPreset(LightingPreset.PERFORMANCE)
                        .withImprovedLighting(true)
                        .withGlobalIllumination(GlobalIlluminationMode.OFF)
                        .save();
                WaterReflectionConfig.setMode(WaterReflectionMode.OFF);
                MetalFxUpscaling.setRequestedMode(MetalFxUpscalingMode.TEMPORAL);
            }
            case BALANCED -> {
                current.withLightingPreset(LightingPreset.BALANCED)
                        .withImprovedLighting(true)
                        .withGlobalIllumination(GlobalIlluminationMode.OFF)
                        .save();
                WaterReflectionConfig.setMode(WaterReflectionMode.VOXELS);
                MetalFxUpscaling.setRequestedMode(MetalFxUpscalingMode.TEMPORAL);
            }
            case ULTRA -> {
                current.withLightingPreset(LightingPreset.ULTRA)
                        .withImprovedLighting(true)
                        .withGlobalIllumination(GlobalIlluminationMode.DYNAMIC)
                        .save();
                WaterReflectionConfig.setMode(WaterReflectionMode.VOXELS);
                MetalFxUpscaling.setRequestedMode(MetalFxUpscalingMode.TEMPORAL_FI);
            }
        }
    }

    public static GraphicsPreset parse(final String value) {
        if (value == null || value.isBlank()) {
            return BALANCED;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BALANCED;
        }
    }
}
