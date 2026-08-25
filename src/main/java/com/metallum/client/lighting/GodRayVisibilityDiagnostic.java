package com.metallum.client.lighting;

import com.metallum.Metallum;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encapsulates diagnostic activation, submodes, sample count overrides, and execution telemetry
 * for the Directional World-Space God-Ray Visibility Volume proof (Stage GOD-RAYS-1.1).
 *
 * <p>Activated via either the environment variable {@code METALLUM_BENCHMARK_GOD_RAY_VISIBILITY_DEBUG=1}
 * or the Java system property {@code -Dmetallum.benchmark.god.ray.visibility.debug=true}.</p>
 */
public final class GodRayVisibilityDiagnostic {
    public static final String ENV_VAR = "METALLUM_BENCHMARK_GOD_RAY_VISIBILITY_DEBUG";
    public static final String ENV_VAR_SHORT = "METALLUM_GOD_RAY_DEBUG";
    public static final String PROPERTY_KEY = "metallum.benchmark.god.ray.visibility.debug";
    public static final String PROPERTY_KEY_SHORT = "metallum.god.ray.debug";

    public static final String MODE_PROPERTY = "metallum.benchmark.god.ray.debug.mode";
    public static final String MODE_PROPERTY_SHORT = "metallum.god.ray.debug.mode";
    public static final String MODE_ENV = "METALLUM_BENCHMARK_GOD_RAY_DEBUG_MODE";
    public static final String MODE_ENV_SHORT = "METALLUM_GOD_RAY_DEBUG_MODE";

    public static final String METRIC_PROPERTY = "metallum.benchmark.god.ray.metric";
    public static final String METRIC_PROPERTY_SHORT = "metallum.god.ray.metric";
    public static final String METRIC_ENV = "METALLUM_BENCHMARK_GOD_RAY_METRIC";
    public static final String METRIC_ENV_SHORT = "METALLUM_GOD_RAY_METRIC";

    public static final String STRATEGY_PROPERTY = "metallum.benchmark.god.ray.strategy";
    public static final String STRATEGY_PROPERTY_SHORT = "metallum.god.ray.strategy";
    public static final String STRATEGY_ENV = "METALLUM_BENCHMARK_GOD_RAY_STRATEGY";
    public static final String STRATEGY_ENV_SHORT = "METALLUM_GOD_RAY_STRATEGY";

    public static final String STEP_LENGTH_PROPERTY = "metallum.benchmark.god.ray.step.length";
    public static final String STEP_LENGTH_PROPERTY_SHORT = "metallum.god.ray.step.length";
    public static final String STEP_LENGTH_ENV = "METALLUM_BENCHMARK_GOD_RAY_STEP_LENGTH";
    public static final String STEP_LENGTH_ENV_SHORT = "METALLUM_GOD_RAY_STEP_LENGTH";

    public static final String FROXEL_CELL_SIZE_PROPERTY = "metallum.benchmark.god.ray.froxel.cell.size";
    public static final String FROXEL_CELL_SIZE_PROPERTY_SHORT = "metallum.god.ray.froxel.cell.size";
    public static final String FROXEL_CELL_SIZE_ENV = "METALLUM_BENCHMARK_GOD_RAY_FROXEL_CELL_SIZE";
    public static final String FROXEL_CELL_SIZE_ENV_SHORT = "METALLUM_GOD_RAY_FROXEL_CELL_SIZE";

    public static final String RESOLUTION_PROPERTY = "metallum.benchmark.god.ray.resolution";
    public static final String RESOLUTION_PROPERTY_SHORT = "metallum.god.ray.resolution";
    public static final String RESOLUTION_ENV = "METALLUM_BENCHMARK_GOD_RAY_RESOLUTION";
    public static final String RESOLUTION_ENV_SHORT = "METALLUM_GOD_RAY_RESOLUTION";

    public static final String SAMPLE_COUNT_PROPERTY = "metallum.benchmark.god.ray.sample.count";
    public static final String SAMPLE_COUNT_PROPERTY_SHORT = "metallum.god.ray.sample.count";
    public static final String SAMPLE_COUNT_ENV = "METALLUM_BENCHMARK_GOD_RAY_SAMPLE_COUNT";
    public static final String SAMPLE_COUNT_ENV_SHORT = "METALLUM_GOD_RAY_SAMPLE_COUNT";
    public static final String INTENSITY_PROPERTY = "metallum.benchmark.god.ray.intensity";
    public static final String INTENSITY_PROPERTY_SHORT = "metallum.god.ray.intensity";
    public static final String INTENSITY_ENV = "METALLUM_BENCHMARK_GOD_RAY_INTENSITY";
    public static final String INTENSITY_ENV_SHORT = "METALLUM_GOD_RAY_INTENSITY";

    public static final String SCATTERING_PROPERTY = "metallum.benchmark.god.ray.scattering";
    public static final String SCATTERING_PROPERTY_SHORT = "metallum.god.ray.scattering";
    public static final String SCATTERING_ENV = "METALLUM_BENCHMARK_GOD_RAY_SCATTERING";
    public static final String SCATTERING_ENV_SHORT = "METALLUM_GOD_RAY_SCATTERING";

    public static final String EXTINCTION_PROPERTY = "metallum.benchmark.god.ray.extinction";
    public static final String EXTINCTION_PROPERTY_SHORT = "metallum.god.ray.extinction";
    public static final String EXTINCTION_ENV = "METALLUM_BENCHMARK_GOD_RAY_EXTINCTION";
    public static final String EXTINCTION_ENV_SHORT = "METALLUM_GOD_RAY_EXTINCTION";

    public static final String ANISOTROPY_PROPERTY = "metallum.benchmark.god.ray.anisotropy";
    public static final String ANISOTROPY_PROPERTY_SHORT = "metallum.god.ray.anisotropy";
    public static final String ANISOTROPY_ENV = "METALLUM_BENCHMARK_GOD_RAY_ANISOTROPY";
    public static final String ANISOTROPY_ENV_SHORT = "METALLUM_GOD_RAY_ANISOTROPY";

    public static final int DEFAULT_SAMPLE_COUNT = 12;
    public static final float DEFAULT_STEP_LENGTH = 0.5f;
    public static final float DEFAULT_FROXEL_CELL_SIZE = 0.5f;
    public static final float DEFAULT_INTENSITY = 0.30f;
    public static final float DEFAULT_SCATTERING_COEFFICIENT = 0.02f;
    public static final float DEFAULT_EXTINCTION_COEFFICIENT = 0.03f;
    public static final float DEFAULT_ANISOTROPY_G = 0.60f;

    public enum Mode {
        VISIBILITY(0, "visibility"),
        CONSTANT(1, "constant"),
        DEPTH(2, "depth"),
        SHAFT_MASK(3, "shaft_mask"),
        OVERLAY(4, "overlay"),
        GRAYSCALE(5, "grayscale"),
        STABILITY(6, "stability"),
        FROXEL(7, "froxel"),
        WORLD_GRID_DEBUG(8, "world_grid"),
        CAMERA_MATRIX_DEBUG(9, "camera_debug"),
        FREEZE_PHYSICAL_CAMERA(10, "freeze_camera"),
        COMPARE_CAMERA_MATRIX(11, "compare_matrix"),
        CAMERA_DELTA_DEBUG(12, "camera_delta"),
        HDR_PREVIEW(13, "hdr_preview"),
        COMPONENT_RAW_VISIBILITY(14, "component_raw_visibility"),
        COMPONENT_SCATTERING_ONLY(15, "component_scattering_only"),
        COMPONENT_PHASE_FUNCTION_ONLY(16, "component_phase_only"),
        COMPONENT_EXTINCTION_ONLY(17, "component_extinction_only"),
        COMPONENT_FINAL_RADIANCE(18, "component_final_radiance"),
        FROXEL_CELL_ID_DEBUG(19, "froxel_cell_id"),
        SUN_VISIBILITY_ONLY(20, "sun_visibility_only"),
        FROXEL_WORLD_POSITION_DEBUG(21, "froxel_world_pos"),
        SUN_SHAFT_ONLY(22, "sun_shaft_only");

        public final int id;
        public final String key;

        Mode(final int id, final String key) {
            this.id = id;
            this.key = key;
        }

        public static Mode parse(final @Nullable String value) {
            if (value == null || value.isBlank()) {
                return VISIBILITY;
            }
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            return switch (trimmed) {
                case "1", "constant", "magenta" -> CONSTANT;
                case "2", "depth", "scene_depth" -> DEPTH;
                case "3", "shaft_mask", "mask" -> SHAFT_MASK;
                case "4", "overlay", "composite", "blend" -> OVERLAY;
                case "5", "grayscale", "gray", "mono" -> GRAYSCALE;
                case "6", "stability", "stable", "neutral" -> STABILITY;
                case "7", "froxel", "volume", "grid", "3d" -> FROXEL;
                case "8", "world_grid", "grid_debug", "cells", "wireframe" -> WORLD_GRID_DEBUG;
                case "9", "camera_debug", "matrix_debug", "matrices" -> CAMERA_MATRIX_DEBUG;
                case "10", "freeze", "freeze_camera", "physical_camera" -> FREEZE_PHYSICAL_CAMERA;
                case "11", "compare", "compare_matrix", "camera_matrix" -> COMPARE_CAMERA_MATRIX;
                case "12", "camera_delta", "delta_debug", "jump_debug" -> CAMERA_DELTA_DEBUG;
                case "13", "hdr_preview", "preview", "composite_preview", "god_rays" -> HDR_PREVIEW;
                case "14", "raw_vis", "raw_visibility", "component_raw_vis", "component_raw_visibility" -> COMPONENT_RAW_VISIBILITY;
                case "15", "scattering_only", "component_scattering", "component_scattering_only", "raw_scattering_density", "scattering_density" -> COMPONENT_SCATTERING_ONLY;
                case "16", "phase_only", "phase_function", "component_phase", "component_phase_only" -> COMPONENT_PHASE_FUNCTION_ONLY;
                case "17", "extinction_only", "transmittance", "component_extinction", "component_extinction_only" -> COMPONENT_EXTINCTION_ONLY;
                case "18", "final_radiance", "component_final", "component_final_radiance" -> COMPONENT_FINAL_RADIANCE;
                case "19", "cell_id", "froxel_cell_id", "cell_id_debug", "cell" -> FROXEL_CELL_ID_DEBUG;
                case "20", "sun_vis", "sun_visibility", "sun_visibility_only", "sun_only", "raw_froxel_visibility_only", "raw_froxel_visibility", "froxel_vis_only" -> SUN_VISIBILITY_ONLY;
                case "21", "world_pos", "froxel_world_pos", "world_position", "froxel_world_coords" -> FROXEL_WORLD_POSITION_DEBUG;
                case "22", "sun_shaft", "sun_shaft_only", "shaft", "shaft_only" -> SUN_SHAFT_ONLY;
                case "0", "visibility", "field" -> VISIBILITY;
                default -> VISIBILITY;
            };
        }
    }

    public enum SamplingStrategy {
        CURRENT_NORMALIZED(0, "current_normalized"),
        WORLD_FIXED_STEP(1, "world_fixed_step"),
        WORLD_FIXED_STEP_REFINED(2, "world_fixed_step_refined");

        public final int id;
        public final String key;

        SamplingStrategy(final int id, final String key) {
            this.id = id;
            this.key = key;
        }

        public static SamplingStrategy parse(final @Nullable String value) {
            if (value == null || value.isBlank()) {
                return CURRENT_NORMALIZED;
            }
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            return switch (trimmed) {
                case "1", "fixed", "fixed_step", "world_fixed_step", "world_fixed" -> WORLD_FIXED_STEP;
                case "2", "refined", "world_fixed_step_refined", "fixed_refined" -> WORLD_FIXED_STEP_REFINED;
                case "0", "normalized", "current_normalized", "midpoint" -> CURRENT_NORMALIZED;
                default -> CURRENT_NORMALIZED;
            };
        }
    }

    public enum Resolution {
        HALF(0, "half"),
        FULL(1, "full");

        public final int id;
        public final String key;

        Resolution(final int id, final String key) {
            this.id = id;
            this.key = key;
        }

        public static Resolution parse(final @Nullable String value) {
            if (value == null || value.isBlank()) {
                return HALF;
            }
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            return switch (trimmed) {
                case "1", "full", "native", "1:1" -> FULL;
                case "0", "half", "low", "1/2" -> HALF;
                default -> HALF;
            };
        }
    }

    public enum Metric {
        LIT_FRACTION(0, "lit_fraction"),
        MEAN_VISIBILITY(1, "mean_visibility"),
        SHAFT_MASK(2, "shaft_mask");

        public final int id;
        public final String key;

        Metric(final int id, final String key) {
            this.id = id;
            this.key = key;
        }

        public static Metric parse(final @Nullable String value) {
            if (value == null || value.isBlank()) {
                return LIT_FRACTION;
            }
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            return switch (trimmed) {
                case "mean", "mean_visibility", "average", "avg" -> MEAN_VISIBILITY;
                case "mask", "shaft_mask" -> SHAFT_MASK;
                case "lit", "lit_fraction", "fraction" -> LIT_FRACTION;
                default -> LIT_FRACTION;
            };
        }
    }

    public enum ExecutionStatus {
        ACTIVE_AND_RENDERED,
        ACTIVE_BUT_NO_DEPTH,
        ACTIVE_BUT_NO_CSM,
        ACTIVE_BUT_CSM_NOT_READY,
        ACTIVE_BUT_NO_TARGET,
        ACTIVE_BUT_PIPELINE_FAILURE,
        INACTIVE
    }

    private static final AtomicBoolean STARTUP_LOGGED = new AtomicBoolean(false);
    private static volatile ExecutionStatus lastReportedStatus = ExecutionStatus.INACTIVE;

    private static volatile boolean activeOverride;
    private static volatile boolean activeOverrideSet;

    private static volatile Mode modeOverride;
    private static volatile Metric metricOverride;
    private static volatile SamplingStrategy strategyOverride;
    private static volatile Resolution resolutionOverride;
    private static volatile Float stepLengthOverride;
    private static volatile Float froxelCellSizeOverride;
    private static volatile Integer sampleCountOverride;
    private static volatile Float intensityOverride;
    private static volatile Float scatteringOverride;
    private static volatile Float extinctionOverride;
    private static volatile Float anisotropyOverride;

    private GodRayVisibilityDiagnostic() {
    }

    public static boolean isActive() {
        if (activeOverrideSet) {
            return activeOverride;
        }
        String propertyValue = System.getProperty(PROPERTY_KEY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(PROPERTY_KEY_SHORT);
        }
        if (propertyValue != null) {
            String trimmed = propertyValue.trim();
            return Boolean.parseBoolean(trimmed) || "1".equals(trimmed);
        }
        String envValue = System.getenv(ENV_VAR);
        if (envValue == null) {
            envValue = System.getenv(ENV_VAR_SHORT);
        }
        if (envValue != null) {
            String trimmed = envValue.trim();
            return Boolean.parseBoolean(trimmed) || "1".equals(trimmed);
        }
        return false;
    }

    public static Mode mode() {
        if (modeOverride != null) {
            return modeOverride;
        }
        String propertyValue = System.getProperty(MODE_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(MODE_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            return Mode.parse(propertyValue);
        }
        String envValue = System.getenv(MODE_ENV);
        if (envValue == null) {
            envValue = System.getenv(MODE_ENV_SHORT);
        }
        if (envValue != null) {
            return Mode.parse(envValue);
        }
        return Mode.VISIBILITY;
    }

    public static Metric metric() {
        if (metricOverride != null) {
            return metricOverride;
        }
        String propertyValue = System.getProperty(METRIC_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(METRIC_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            return Metric.parse(propertyValue);
        }
        String envValue = System.getenv(METRIC_ENV);
        if (envValue == null) {
            envValue = System.getenv(METRIC_ENV_SHORT);
        }
        if (envValue != null) {
            return Metric.parse(envValue);
        }
        return Metric.LIT_FRACTION;
    }

    public static SamplingStrategy strategy() {
        if (strategyOverride != null) {
            return strategyOverride;
        }
        String propertyValue = System.getProperty(STRATEGY_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(STRATEGY_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            return SamplingStrategy.parse(propertyValue);
        }
        String envValue = System.getenv(STRATEGY_ENV);
        if (envValue == null) {
            envValue = System.getenv(STRATEGY_ENV_SHORT);
        }
        if (envValue != null) {
            return SamplingStrategy.parse(envValue);
        }
        return SamplingStrategy.CURRENT_NORMALIZED;
    }

    public static Resolution resolution() {
        if (resolutionOverride != null) {
            return resolutionOverride;
        }
        String propertyValue = System.getProperty(RESOLUTION_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(RESOLUTION_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            return Resolution.parse(propertyValue);
        }
        String envValue = System.getenv(RESOLUTION_ENV);
        if (envValue == null) {
            envValue = System.getenv(RESOLUTION_ENV_SHORT);
        }
        if (envValue != null) {
            return Resolution.parse(envValue);
        }
        return Resolution.HALF;
    }

    public static float stepLength() {
        if (stepLengthOverride != null) {
            return Math.clamp(stepLengthOverride, 0.05f, 10.0f);
        }
        String propertyValue = System.getProperty(STEP_LENGTH_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(STEP_LENGTH_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            try {
                return Math.clamp(Float.parseFloat(propertyValue.trim()), 0.05f, 10.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv(STEP_LENGTH_ENV);
        if (envValue == null) {
            envValue = System.getenv(STEP_LENGTH_ENV_SHORT);
        }
        if (envValue != null) {
            try {
                return Math.clamp(Float.parseFloat(envValue.trim()), 0.05f, 10.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_STEP_LENGTH;
    }

    public static float froxelCellSize() {
        if (froxelCellSizeOverride != null) {
            return Math.clamp(froxelCellSizeOverride, 0.1f, 2.0f);
        }
        String propertyValue = System.getProperty(FROXEL_CELL_SIZE_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(FROXEL_CELL_SIZE_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            try {
                return Math.clamp(Float.parseFloat(propertyValue.trim()), 0.1f, 2.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv(FROXEL_CELL_SIZE_ENV);
        if (envValue == null) {
            envValue = System.getenv(FROXEL_CELL_SIZE_ENV_SHORT);
        }
        if (envValue != null) {
            try {
                return Math.clamp(Float.parseFloat(envValue.trim()), 0.1f, 2.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_FROXEL_CELL_SIZE;
    }

    public static int sampleCount() {
        if (sampleCountOverride != null) {
            return Math.clamp(sampleCountOverride, 1, 128);
        }
        String propertyValue = System.getProperty(SAMPLE_COUNT_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(SAMPLE_COUNT_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            try {
                return Math.clamp(Integer.parseInt(propertyValue.trim()), 1, 128);
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv(SAMPLE_COUNT_ENV);
        if (envValue == null) {
            envValue = System.getenv(SAMPLE_COUNT_ENV_SHORT);
        }
        if (envValue != null) {
            try {
                return Math.clamp(Integer.parseInt(envValue.trim()), 1, 128);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_SAMPLE_COUNT;
    }

    public static float intensity() {
        if (intensityOverride != null) {
            return Math.clamp(intensityOverride, 0.0f, 1.0f);
        }
        String propertyValue = System.getProperty(INTENSITY_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(INTENSITY_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            try {
                return Math.clamp(Float.parseFloat(propertyValue.trim()), 0.0f, 1.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv(INTENSITY_ENV);
        if (envValue == null) {
            envValue = System.getenv(INTENSITY_ENV_SHORT);
        }
        if (envValue != null) {
            try {
                return Math.clamp(Float.parseFloat(envValue.trim()), 0.0f, 1.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_INTENSITY;
    }

    public static void setIntensity(final float intensity) {
        intensityOverride = Math.clamp(intensity, 0.0f, 1.0f);
    }

    public static float scatteringCoefficient() {
        if (scatteringOverride != null) {
            return Math.clamp(scatteringOverride, 0.001f, 1.0f);
        }
        String propertyValue = System.getProperty(SCATTERING_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(SCATTERING_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            try {
                return Math.clamp(Float.parseFloat(propertyValue.trim()), 0.001f, 1.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv(SCATTERING_ENV);
        if (envValue == null) {
            envValue = System.getenv(SCATTERING_ENV_SHORT);
        }
        if (envValue != null) {
            try {
                return Math.clamp(Float.parseFloat(envValue.trim()), 0.001f, 1.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_SCATTERING_COEFFICIENT;
    }

    public static void setScatteringCoefficient(final float scattering) {
        scatteringOverride = Math.clamp(scattering, 0.001f, 1.0f);
    }

    public static float extinctionCoefficient() {
        if (extinctionOverride != null) {
            return Math.clamp(extinctionOverride, 0.001f, 2.0f);
        }
        String propertyValue = System.getProperty(EXTINCTION_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(EXTINCTION_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            try {
                return Math.clamp(Float.parseFloat(propertyValue.trim()), 0.001f, 2.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv(EXTINCTION_ENV);
        if (envValue == null) {
            envValue = System.getenv(EXTINCTION_ENV_SHORT);
        }
        if (envValue != null) {
            try {
                return Math.clamp(Float.parseFloat(envValue.trim()), 0.001f, 2.0f);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_EXTINCTION_COEFFICIENT;
    }

    public static void setExtinctionCoefficient(final float extinction) {
        extinctionOverride = Math.clamp(extinction, 0.001f, 2.0f);
    }

    public static float anisotropyG() {
        if (anisotropyOverride != null) {
            return Math.clamp(anisotropyOverride, -0.99f, 0.99f);
        }
        String propertyValue = System.getProperty(ANISOTROPY_PROPERTY);
        if (propertyValue == null) {
            propertyValue = System.getProperty(ANISOTROPY_PROPERTY_SHORT);
        }
        if (propertyValue != null) {
            try {
                return Math.clamp(Float.parseFloat(propertyValue.trim()), -0.99f, 0.99f);
            } catch (NumberFormatException ignored) {
            }
        }
        String envValue = System.getenv(ANISOTROPY_ENV);
        if (envValue == null) {
            envValue = System.getenv(ANISOTROPY_ENV_SHORT);
        }
        if (envValue != null) {
            try {
                return Math.clamp(Float.parseFloat(envValue.trim()), -0.99f, 0.99f);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_ANISOTROPY_G;
    }

    public static void setAnisotropyG(final float g) {
        anisotropyOverride = Math.clamp(g, -0.99f, 0.99f);
    }

    public static void logStartupIfNeeded() {
        if (STARTUP_LOGGED.compareAndSet(false, true)) {
            if (isActive()) {
                Metallum.LOGGER.info(
                        "[Metallum] God-Ray visibility diagnostic: ACTIVE (mode={}, metric={}, strategy={}, samples={}, stepLength={}, froxelCellSize={}, resolution={})",
                        mode().name(),
                        metric().name(),
                        strategy().name(),
                        sampleCount(),
                        stepLength(),
                        froxelCellSize(),
                        resolution().name()
                );
            } else {
                Metallum.LOGGER.info("[Metallum] God-Ray visibility diagnostic: INACTIVE");
            }
        }
    }

    public static void reportStatus(final ExecutionStatus status) {
        Objects.requireNonNull(status, "status");
        if (lastReportedStatus != status) {
            lastReportedStatus = status;
            Metallum.LOGGER.info("[Metallum] God-Ray visibility execution status: {}", status.name());
        }
    }

    public static ExecutionStatus lastReportedStatus() {
        return lastReportedStatus;
    }

    // -----------------------------------------------------------------------
    // Test overrides
    // -----------------------------------------------------------------------

    public static void setActive(final boolean active) {
        activeOverrideSet = true;
        activeOverride = active;
    }

    public static void setMode(final Mode mode) {
        modeOverride = Objects.requireNonNull(mode, "mode");
    }

    public static void setOverrideForTests(final @Nullable Boolean active) {
        if (active == null) {
            activeOverrideSet = false;
            activeOverride = false;
        } else {
            activeOverrideSet = true;
            activeOverride = active;
        }
    }

    public static void setModeOverrideForTests(final @Nullable Mode mode) {
        modeOverride = mode;
    }

    public static void setMetricOverrideForTests(final @Nullable Metric metric) {
        metricOverride = metric;
    }

    public static void setStrategyOverrideForTests(final @Nullable SamplingStrategy strategy) {
        strategyOverride = strategy;
    }

    public static void setResolutionOverrideForTests(final @Nullable Resolution resolution) {
        resolutionOverride = resolution;
    }

    public static void setStepLengthOverrideForTests(final @Nullable Float stepLength) {
        stepLengthOverride = stepLength;
    }

    public static void setFroxelCellSizeOverrideForTests(final @Nullable Float froxelCellSize) {
        froxelCellSizeOverride = froxelCellSize;
    }

    public static void setSampleCountOverrideForTests(final @Nullable Integer count) {
        sampleCountOverride = count;
    }

    public static void setIntensityOverrideForTests(final @Nullable Float intensity) {
        intensityOverride = intensity;
    }

    public static void setScatteringOverrideForTests(final @Nullable Float scattering) {
        scatteringOverride = scattering;
    }

    public static void setExtinctionOverrideForTests(final @Nullable Float extinction) {
        extinctionOverride = extinction;
    }

    public static void setAnisotropyOverrideForTests(final @Nullable Float anisotropy) {
        anisotropyOverride = anisotropy;
    }

    public static void resetOverridesForTests() {
        activeOverrideSet = false;
        activeOverride = false;
        modeOverride = null;
        metricOverride = null;
        strategyOverride = null;
        resolutionOverride = null;
        stepLengthOverride = null;
        froxelCellSizeOverride = null;
        sampleCountOverride = null;
        intensityOverride = null;
        scatteringOverride = null;
        extinctionOverride = null;
        anisotropyOverride = null;
        lastReportedStatus = ExecutionStatus.INACTIVE;
    }
}
