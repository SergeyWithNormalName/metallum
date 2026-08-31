package com.metallum.client.benchmark;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalDevice;
import com.metallum.client.metal.render.MetalGpuTiming;
import com.metallum.client.metal.render.bridge.MetalNativeBridge;
import com.metallum.client.metalfx.BenchmarkScalingMode;
import com.metallum.client.metalfx.MetalFxUpscaling;
import com.metallum.client.lighting.AdvancedLightingRuntime;
import com.metallum.client.gi.live.GiLiveRuntime;
import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.transport.GiTransportRuntime;
import com.metallum.client.gi.receiver.CompactPositionCarrierSafety;
import com.metallum.client.gi.receiver.GiReceiverRuntime;
import com.metallum.client.hdr.SodiumHdrSemantic;
import com.metallum.client.lighting.reflection.FrozenReflectionFieldController;
import com.metallum.client.lighting.reflection.VertexReflectionExperiment;
import com.metallum.client.lighting.reflection.WaterReflectionQualityConfig;
import com.metallum.client.renderer.RendererConfig;
import com.metallum.client.renderer.interpolation.FrameInterpolationRuntimeStatus;
import com.metallum.client.sodium.SodiumLightSidecar;
import com.metallum.client.sodium.SodiumLightSidecarPacking;
import com.metallum.client.sodium.SodiumRelightFastPath;
import com.metallum.client.sodium.SodiumRelightOracle;
import com.metallum.client.sodium.SodiumRelightPlanCache;
import com.metallum.client.sodium.SodiumTerrainLightPatch;
import com.metallum.client.sodium.SodiumTerrainStaticShadow;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;

/**
 * Environment-gated, deterministic 5K benchmark driver.
 *
 * <p>The mixin which owns this controller is not applied unless
 * {@code METALLUM_BENCHMARK=1}, so normal clients have no transformed hot path.
 */
public final class MetalFxBenchmarkController {
    private static final int WINDOW_TRANSITION_TIMEOUT_FRAMES = 240;
    private static final int WINDOW_FOCUS_RETRY_INTERVAL_FRAMES = 30;
    private static final int G4_ADMISSION_TIMEOUT_FRAMES = 240;
    private static final int G6_TORCH_ON_SCREENSHOT_MEASURED_FRAME = 400;
    private static final int G4_SOURCE_RECEIPT_FRAMES = 300;
    private static final int ROUTE_SERVER_CHECK_INTERVAL_FRAMES = 30;
    private static final int G6_MATRIX_RECEIPT_ORBIT = 1;
    private static final int G6_MATRIX_RECEIPT_LAVA = 1 << 1;
    private static final int G6_MATRIX_RECEIPT_CHUNK_RELOAD = 1 << 2;
    private static final int G6_MATRIX_RECEIPT_RESOURCE_RELOAD = 1 << 3;
    private static final int G6_MATRIX_RECEIPT_DAY_NIGHT = 1 << 4;
    private static final int G6_MATRIX_RECEIPT_RAIN = 1 << 5;
    private static final int G6_MATRIX_RECEIPT_STREAM = 1 << 6;
    private static final int G6_MATRIX_RECEIPT_TELEPORT = 1 << 7;
    private static final int G6_MATRIX_RECEIPT_NETHER = 1 << 8;
    private static final int G6_MATRIX_RECEIPT_ALL = (1 << 9) - 1;
    private static final int G6_MATRIX_LATENCY_NONE = 0;
    private static final int G6_MATRIX_LATENCY_BLOCK = 1;
    private static final int G6_MATRIX_LATENCY_STATIC_SOURCE = 2;
    private static final int G6_MATRIX_LATENCY_SCROLL = 3;
    private static final int G6_MATRIX_LATENCY_FULL_RESET = 4;
    private static final int G6_MATRIX_RECOVERY_TIMEOUT_FRAMES = 180;
    /**
     * Harness-only all-cascade stabilization budget; the production FULL_RESET SLA is near-only.
     * Match the route's complete 260-frame gap so its final ten-frame poll can close before the
     * return teleport is evaluated in the same frame.
     */
    private static final int G6_MATRIX_TELEPORT_STABILIZATION_TIMEOUT_FRAMES = 260;
    /**
     * Cold dimension streaming is a harness convergence window, not the near-only FULL_RESET
     * SLA. Match the route's complete 470-frame gap so the final ten-frame polling boundary can
     * close an all-cascade receipt before the return action is evaluated in the same frame.
     */
    private static final int G6_MATRIX_DIMENSION_STABILIZATION_TIMEOUT_FRAMES = 470;
    private static final int G6_MATRIX_TERRAIN_RECOVERY_TIMEOUT_FRAMES = 240;
    private static final int G6_MATRIX_RELOAD_RECOVERY_GAP_FRAMES = 270;
    private static final int G6_MATRIX_STATIC_RECOVERY_GAP_FRAMES = 200;
    private static final int G6_MATRIX_SCROLL_STEP_MIN_FRAMES = 40;
    private static final int G6_MATRIX_SCROLL_STEP_MAX_FRAMES = 60;
    private static final int G6_MATRIX_SCROLL_FINAL_RECOVERY_GAP_FRAMES = 60;
    private static final int G6_MATRIX_TELEPORT_RECOVERY_GAP_FRAMES = 260;
    private static final int G6_MATRIX_RESET_RECOVERY_GAP_FRAMES = 190;
    private static final int G6_MATRIX_DIMENSION_RECOVERY_GAP_FRAMES = 470;
    private static final int WINDOWED_WIDTH = 1280;
    private static final int WINDOWED_HEIGHT = 720;
    private static final String BENCHMARK_PLAYER_NAME = "MetallumBench";
    private static final UUID BENCHMARK_PLAYER_UUID = UUID.fromString("b07a402a-d8ea-354f-9398-aaf208a798b9");
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final int FI_TRANSPORT_ACCEPTED_PAIRS = 0;
    private static final int FI_TRANSPORT_GENERATED_PRESENTATIONS = 1;
    private static final int FI_TRANSPORT_REAL_PRESENTATIONS = 2;
    private static final int FI_TRANSPORT_DROPPED_GENERATED_LATE = 3;
    private static final int FI_TRANSPORT_BACKPRESSURE_DROPS = 4;
    private static final int FI_TRANSPORT_SOURCE_ATTEMPTS = 5;
    private static final int FI_TRANSPORT_SCHEDULER_ACCEPTED = 6;
    private static final int FI_TRANSPORT_SCHEDULER_WARMING = 7;
    private static final int FI_TRANSPORT_SCHEDULER_TOO_SLOW = 8;
    private static final int FI_TRANSPORT_SCHEDULER_TOO_FAST = 9;
    private static final int FI_TRANSPORT_COORDINATOR_REAL_ONLY = 10;
    private static final int FI_TRANSPORT_INTERPOLATION_FAILURES = 11;
    private static final int FI_TRANSPORT_RAW_CADENCE_TOO_SLOW = 12;
    private static final int FI_TRANSPORT_SOURCE_DELTA_NANOS = 13;
    private static final int FI_TRANSPORT_SOURCE_DELTA_SAMPLES = 14;
    private static final int FI_TRANSPORT_OUT_OF_ORDER_PRESENTATIONS = 15;
    private static final int FI_TRANSPORT_TARGET_MISSES = 16;
    private static final int FI_TRANSPORT_TIMED_INTERVALS = 17;
    private static final int FI_TRANSPORT_TIMED_INTERVAL_NANOS = 18;
    private static final int FI_TRANSPORT_GENERATED_TO_REAL_INTERVALS = 19;
    private static final int FI_TRANSPORT_GENERATED_TO_REAL_NANOS = 20;
    private static final int FI_TRANSPORT_GENERATED_TO_REAL_MISSES = 21;
    private static final int FI_TRANSPORT_REAL_TO_GENERATED_INTERVALS = 22;
    private static final int FI_TRANSPORT_REAL_TO_GENERATED_NANOS = 23;
    private static final int FI_TRANSPORT_REAL_TO_GENERATED_MISSES = 24;
    private static final int FI_TRANSPORT_TARGET_120_INTERVALS = 25;
    private static final int FI_TRANSPORT_TARGET_80_INTERVALS = 26;
    private static final int FI_TRANSPORT_TARGET_60_INTERVALS = 27;
    private static final int FI_TRANSPORT_INTERVALS_OVER_22_MS = 28;
    private static final int FI_TRANSPORT_TIMED_TARGET_NANOS = 29;
    private static final int FI_TRANSPORT_TIMED_MEAN_SLACK_NANOS = 30;
    private static final int FI_TRANSPORT_SEVERE_LATE_INTERVALS = 31;
    private static final int FI_TRANSPORT_RETARGET_BOUNDARIES = 32;
    private static final int FI_TRANSPORT_ADMISSION_WAITS = 33;
    private static final int FI_TRANSPORT_ADMISSION_WAIT_NANOS = 34;
    private static final int FI_TRANSPORT_SEVERE_GENERATED_TO_REAL = 35;
    private static final int FI_TRANSPORT_SEVERE_REAL_TO_GENERATED = 36;
    private static final int FI_TRANSPORT_GATE_RELEASES = 37;
    private static final int FI_TRANSPORT_GATE_LATENESS_NANOS = 38;
    private static final int FI_TRANSPORT_GATE_LATENESS_MAX_NANOS = 39;
    private static final int FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_SAMPLES = 40;
    private static final int FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_NANOS = 41;
    private static final int FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_MAX_NANOS = 42;
    private static final int FI_TRANSPORT_REAL_DRAWABLE_WAIT_SAMPLES = 43;
    private static final int FI_TRANSPORT_REAL_DRAWABLE_WAIT_NANOS = 44;
    private static final int FI_TRANSPORT_REAL_DRAWABLE_WAIT_MAX_NANOS = 45;
    private static final int FI_TRANSPORT_REAL_ONLY_PRESENTATIONS = 46;
    private static final int FI_TRANSPORT_COUNTER_COUNT = 47;
    /** Practical process-counter snapshot tolerance for the two-deep coordinator. */
    private static final long FI_TRANSPORT_SNAPSHOT_TOLERANCE = 2L;
    /** Largest valid source interval admitted by production FI (30 FPS). */
    private static final long FI_CADENCE_SNAPSHOT_INTERVAL_NANOS = 33_333_334L;

    private enum Stage {
        IDLE,
        SELECT_MONITOR,
        EXIT_FULLSCREEN,
        MOVE_WINDOWED,
        WAIT_MOVED,
        ENTER_FULLSCREEN,
        WAIT_FRAMEBUFFER,
        WAIT_ROUTE,
        RUNNING,
        STOPPING
    }

    private enum SegmentPhase {
        WARMUP,
        WAIT_MEASURE_START_CHECK,
        MEASURE,
        WAIT_MEASURE_END_CHECK
    }

    private enum RouteCheckEvent {
        MEASURE_START,
        MEASURE_END
    }

    private enum G6MatrixServerAction {
        LAVA_APPLY,
        LAVA_REMOVE,
        DAY,
        NIGHT,
        RAIN,
        CLEAR,
        STREAM,
        TELEPORT,
        TELEPORT_RETURN,
        NETHER_ENTER,
        NETHER_RETURN
    }

    private enum WorkloadKind {
        STATIC,
        TORCH_EPOCH,
        TORCH_TOGGLE,
        L6_DYNAMIC_SHADOW,
        GI_G6_MATRIX;

        private static WorkloadKind fromEnvironment() {
            try {
                return valueOf(requiredEnv("METALLUM_BENCHMARK_ROUTE_KIND"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "METALLUM_BENCHMARK_ROUTE_KIND must be STATIC, TORCH_EPOCH, TORCH_TOGGLE, L6_DYNAMIC_SHADOW, or GI_G6_MATRIX",
                        exception
                );
            }
        }
    }

    /** Exact schema-5 timeline for the production G6 live-update acceptance route. */
    private record G6MatrixConfig(
            String heldItem,
            String entityItem,
            double entityX,
            double entityY,
            double entityZ,
            int lavaX,
            int lavaY,
            int lavaZ,
            String lavaInitialBlock,
            int lavaApplyFrame,
            int lavaRemoveFrame,
            int orbitStartFrame,
            int orbitEndFrame,
            float orbitYawAmplitudeDegrees,
            float orbitPitchAmplitudeDegrees,
            int orbitPeriodFrames,
            int chunkReloadFrame,
            int resourceReloadFrame,
            int dayFrame,
            long dayTicks,
            int nightFrame,
            long nightTicks,
            int rainFrame,
            int clearFrame,
            int streamStartFrame,
            int streamStepFrames,
            int[] streamOffsets,
            int[] streamYOffsets,
            int teleportFrame,
            int teleportOffsetX,
            int teleportOffsetY,
            int teleportOffsetZ,
            int teleportReturnFrame,
            int netherEnterFrame,
            double netherX,
            double netherY,
            double netherZ,
            int netherReturnFrame
    ) {
        private static G6MatrixConfig fromEnvironment() {
            String held = requiredEnv("METALLUM_BENCHMARK_G6_MATRIX_HELD_ITEM");
            String entity = requiredEnv("METALLUM_BENCHMARK_G6_MATRIX_ENTITY_ITEM");
            String initial = requiredEnv("METALLUM_BENCHMARK_G6_MATRIX_LAVA_INITIAL_BLOCK");
            if (!"minecraft:torch".equals(held) || !"minecraft:torch".equals(entity)
                    || !"minecraft:air".equals(initial)) {
                throw new IllegalArgumentException(
                        "G6 matrix requires exact torch/torch/air source identities"
                );
            }
            int[] stream = new int[8];
            int[] streamY = new int[8];
            for (int index = 0; index < stream.length; index++) {
                stream[index] = integer("METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_" + index);
                streamY[index] = integer(
                        "METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_" + index
                );
            }
            return new G6MatrixConfig(
                    held, entity,
                    finiteDouble("METALLUM_BENCHMARK_G6_MATRIX_ENTITY_POSITION_X"),
                    finiteDouble("METALLUM_BENCHMARK_G6_MATRIX_ENTITY_POSITION_Y"),
                    finiteDouble("METALLUM_BENCHMARK_G6_MATRIX_ENTITY_POSITION_Z"),
                    integer("METALLUM_BENCHMARK_G6_MATRIX_LAVA_POSITION_X"),
                    integer("METALLUM_BENCHMARK_G6_MATRIX_LAVA_POSITION_Y"),
                    integer("METALLUM_BENCHMARK_G6_MATRIX_LAVA_POSITION_Z"),
                    initial,
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_LAVA_APPLY_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_LAVA_REMOVE_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_ORBIT_START_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_ORBIT_END_FRAME"),
                    positiveFiniteFloat(
                            "METALLUM_BENCHMARK_G6_MATRIX_ORBIT_YAW_AMPLITUDE_DEGREES"),
                    positiveFiniteFloat(
                            "METALLUM_BENCHMARK_G6_MATRIX_ORBIT_PITCH_AMPLITUDE_DEGREES"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_ORBIT_PERIOD_FRAMES"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_CHUNK_RELOAD_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_RESOURCE_RELOAD_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_DAY_FRAME"),
                    nonNegativeLong("METALLUM_BENCHMARK_G6_MATRIX_DAY_TICKS"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_NIGHT_FRAME"),
                    nonNegativeLong("METALLUM_BENCHMARK_G6_MATRIX_NIGHT_TICKS"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_RAIN_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_CLEAR_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_STREAM_START_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_STREAM_STEP_FRAMES"),
                    stream, streamY,
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_FRAME"),
                    integer("METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_OFFSET_X"),
                    integer("METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_OFFSET_Y"),
                    integer("METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_OFFSET_Z"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_RETURN_FRAME"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_NETHER_ENTER_FRAME"),
                    finiteDouble("METALLUM_BENCHMARK_G6_MATRIX_NETHER_POSITION_X"),
                    finiteDouble("METALLUM_BENCHMARK_G6_MATRIX_NETHER_POSITION_Y"),
                    finiteDouble("METALLUM_BENCHMARK_G6_MATRIX_NETHER_POSITION_Z"),
                    positiveIntStrict("METALLUM_BENCHMARK_G6_MATRIX_NETHER_RETURN_FRAME")
            );
        }

        private BlockPos lavaPosition() {
            return new BlockPos(this.lavaX, this.lavaY, this.lavaZ);
        }
    }

    private record TorchEpochConfig(
            int x,
            int y,
            int z,
            int applyAfterMeasuredFrames,
            int observationFrames,
            int removeAfterMeasuredFrames
    ) {
        private static TorchEpochConfig fromEnvironment(final WorkloadKind workloadKind) {
            String initialBlock = requiredEnv("METALLUM_BENCHMARK_TORCH_INITIAL_BLOCK");
            String supportBlock = requiredEnv("METALLUM_BENCHMARK_TORCH_SUPPORT_BLOCK");
            if (!"minecraft:air".equals(initialBlock)) {
                throw new IllegalArgumentException(
                        "METALLUM_BENCHMARK_TORCH_INITIAL_BLOCK must be minecraft:air"
                );
            }
            if (!"minecraft:grass_block".equals(supportBlock)) {
                throw new IllegalArgumentException(
                        "METALLUM_BENCHMARK_TORCH_SUPPORT_BLOCK must be minecraft:grass_block"
                );
            }
            return new TorchEpochConfig(
                    integer("METALLUM_BENCHMARK_TORCH_POSITION_X"),
                    integer("METALLUM_BENCHMARK_TORCH_POSITION_Y"),
                    integer("METALLUM_BENCHMARK_TORCH_POSITION_Z"),
                    positiveIntStrict("METALLUM_BENCHMARK_TORCH_APPLY_AFTER_MEASURED_FRAMES"),
                    positiveIntStrict("METALLUM_BENCHMARK_TORCH_OBSERVATION_FRAMES"),
                    workloadKind == WorkloadKind.TORCH_TOGGLE
                            ? positiveIntStrict("METALLUM_BENCHMARK_TORCH_REMOVE_AFTER_MEASURED_FRAMES")
                            : 0
            );
        }
        private BlockPos position() {
            return new BlockPos(this.x, this.y, this.z);
        }

        private long endMeasuredFrame() {
            return (long) this.applyAfterMeasuredFrames + this.observationFrames;
        }

        private boolean removesTorch() {
            return this.removeAfterMeasuredFrames > 0;
        }
    }

    /** Benchmark-only motion fixture exercising the production held/entity extraction paths. */
    private record L6DynamicShadowConfig(
            double orbitRadius,
            float orbitYawAmplitudeDegrees,
            float orbitPitchAmplitudeDegrees,
            int orbitPeriodFrames,
            int probeCount,
            double probeOriginX,
            double probeOriginY,
            double probeOriginZ,
            double probeRadius,
            double probeVerticalAmplitude,
            int probePeriodFrames
    ) {
        private static L6DynamicShadowConfig fromEnvironment() {
            if (!"minecraft:torch".equals(requiredEnv("METALLUM_BENCHMARK_L6_HELD_ITEM"))) {
                throw new IllegalArgumentException("METALLUM_BENCHMARK_L6_HELD_ITEM must be minecraft:torch");
            }
            int probeCount = positiveIntStrict("METALLUM_BENCHMARK_L6_PROBE_COUNT");
            if (probeCount != 4) {
                throw new IllegalArgumentException("METALLUM_BENCHMARK_L6_PROBE_COUNT must be exactly 4");
            }
            int orbitPeriod = positiveIntStrict("METALLUM_BENCHMARK_L6_ORBIT_PERIOD_FRAMES");
            int probePeriod = positiveIntStrict("METALLUM_BENCHMARK_L6_PROBE_PERIOD_FRAMES");
            if (orbitPeriod < 60 || orbitPeriod % 60 != 0
                    || probePeriod < 60 || probePeriod % 60 != 0) {
                throw new IllegalArgumentException("L6 motion periods must be 60-frame multiples");
            }
            float yawAmplitude = positiveFiniteFloat(
                    "METALLUM_BENCHMARK_L6_ORBIT_YAW_AMPLITUDE_DEGREES"
            );
            float pitchAmplitude = positiveFiniteFloat(
                    "METALLUM_BENCHMARK_L6_ORBIT_PITCH_AMPLITUDE_DEGREES"
            );
            if (yawAmplitude > 45.0F || pitchAmplitude > 30.0F) {
                throw new IllegalArgumentException("L6 camera orbit exceeds its route bounds");
            }
            return new L6DynamicShadowConfig(
                    positiveFiniteDouble("METALLUM_BENCHMARK_L6_ORBIT_RADIUS"),
                    yawAmplitude,
                    pitchAmplitude,
                    orbitPeriod,
                    probeCount,
                    finiteDouble("METALLUM_BENCHMARK_L6_PROBE_ORIGIN_X"),
                    finiteDouble("METALLUM_BENCHMARK_L6_PROBE_ORIGIN_Y"),
                    finiteDouble("METALLUM_BENCHMARK_L6_PROBE_ORIGIN_Z"),
                    positiveFiniteDouble("METALLUM_BENCHMARK_L6_PROBE_RADIUS"),
                    positiveFiniteDouble("METALLUM_BENCHMARK_L6_PROBE_VERTICAL_AMPLITUDE"),
                    probePeriod
            );
        }
    }

    private record RouteConfig(
            String routeId,
            String routeSha256,
            String fixtureId,
            String fixtureSha256,
            String playerName,
            UUID playerUuid,
            ResourceKey<Level> dimension,
            String dimensionName,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            long clockTicks,
            int clearWeatherTicks,
            String weatherMode,
            boolean simulationFrozen,
            int stableFrames,
            int timeoutFrames,
            double positionEpsilon,
            float angleEpsilon,
            WorkloadKind workloadKind,
            TorchEpochConfig torchEpoch,
            L6DynamicShadowConfig l6DynamicShadow,
            G6MatrixConfig g6Matrix
    ) {
        private static RouteConfig fromEnvironment() {
            String routeId = requiredMatching("METALLUM_BENCHMARK_ROUTE_ID", SAFE_ID);
            String routeSha256 = requiredMatching("METALLUM_BENCHMARK_ROUTE_SHA256", SHA_256);
            String fixtureId = requiredMatching("METALLUM_BENCHMARK_FIXTURE_ID", SAFE_ID);
            String fixtureSha256 = requiredMatching("METALLUM_BENCHMARK_FIXTURE_SHA256", SHA_256);
            String playerName = requiredEnv("METALLUM_BENCHMARK_PLAYER_NAME");
            UUID playerUuid = UUID.fromString(requiredEnv("METALLUM_BENCHMARK_PLAYER_UUID"));
            if (!BENCHMARK_PLAYER_NAME.equals(playerName) || !BENCHMARK_PLAYER_UUID.equals(playerUuid)) {
                throw new IllegalArgumentException("benchmark route player identity is not the fixed MetallumBench identity");
            }

            String dimensionName = requiredEnv("METALLUM_BENCHMARK_DIMENSION");
            ResourceKey<Level> dimension = ResourceKey.create(
                    Registries.DIMENSION,
                    Identifier.parse(dimensionName)
            );
            double x = finiteDouble("METALLUM_BENCHMARK_POSITION_X");
            double y = finiteDouble("METALLUM_BENCHMARK_POSITION_Y");
            double z = finiteDouble("METALLUM_BENCHMARK_POSITION_Z");
            float yaw = finiteFloat("METALLUM_BENCHMARK_YAW");
            float pitch = finiteFloat("METALLUM_BENCHMARK_PITCH");
            if (pitch < -90.0f || pitch > 90.0f) {
                throw new IllegalArgumentException("METALLUM_BENCHMARK_PITCH must be between -90 and 90");
            }

            long clockTicks = nonNegativeLong("METALLUM_BENCHMARK_CLOCK_TICKS");
            int clearWeatherTicks = positiveIntStrict("METALLUM_BENCHMARK_CLEAR_WEATHER_TICKS");
            String weatherMode = System.getenv("METALLUM_BENCHMARK_WEATHER_MODE");
            if (weatherMode == null || weatherMode.isEmpty()) {
                weatherMode = "clear";
            }
            boolean simulationFrozen = "1".equals(requiredEnv("METALLUM_BENCHMARK_SIMULATION_FROZEN"));
            if (!simulationFrozen) {
                throw new IllegalArgumentException("benchmark route requires frozen simulation ticks");
            }
            int stableFrames = positiveIntStrict("METALLUM_BENCHMARK_ROUTE_STABLE_FRAMES");
            int timeoutFrames = positiveIntStrict("METALLUM_BENCHMARK_ROUTE_TIMEOUT_FRAMES");
            if (timeoutFrames <= stableFrames) {
                throw new IllegalArgumentException("route timeout must exceed stable frame count");
            }
            double positionEpsilon = positiveFiniteDouble("METALLUM_BENCHMARK_POSITION_EPSILON");
            float angleEpsilon = positiveFiniteFloat("METALLUM_BENCHMARK_ANGLE_EPSILON");
            WorkloadKind workloadKind = WorkloadKind.fromEnvironment();
            TorchEpochConfig torchEpoch = workloadKind == WorkloadKind.TORCH_EPOCH
                    || workloadKind == WorkloadKind.TORCH_TOGGLE
                    ? TorchEpochConfig.fromEnvironment(workloadKind)
                    : null;
            L6DynamicShadowConfig l6DynamicShadow = workloadKind == WorkloadKind.L6_DYNAMIC_SHADOW
                    ? L6DynamicShadowConfig.fromEnvironment()
                    : null;
            G6MatrixConfig g6Matrix = workloadKind == WorkloadKind.GI_G6_MATRIX
                    ? G6MatrixConfig.fromEnvironment()
                    : null;
            return new RouteConfig(
                    routeId,
                    routeSha256,
                    fixtureId,
                    fixtureSha256,
                    playerName,
                    playerUuid,
                    dimension,
                    dimensionName,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    clockTicks,
                    clearWeatherTicks,
                    weatherMode,
                    simulationFrozen,
                    stableFrames,
                    timeoutFrames,
                    positionEpsilon,
                    angleEpsilon,
                    workloadKind,
                    torchEpoch,
                    l6DynamicShadow,
                    g6Matrix
            );
        }
    }

    private final String monitorName;
    private final int targetWidth;
    private final int targetHeight;
    private final int warmupFrames;
    private final int measureFrames;
    private final int expectedMaxFps;
    private final int expectedRenderDistance;
    private final int expectedSimulationDistance;
    private final int expectedParticles;
    private final int expectedMipmapLevels;
    private final int expectedBiomeBlendRadius;
    private final int expectedCloudRange;
    private final int expectedConfiguredGuiScale;
    private final double expectedEntityDistanceScaling;
    private final String expectedGraphicsPreset;
    private final String expectedCloudsMode;
    private final boolean expectedAmbientOcclusion;
    private final List<String> expectedResourcePackIds;
    private final boolean expectedVsync;
    private final boolean fiValidationRequired;
    private final boolean fiOverlayRequested;
    private final int fiMinimumGenerated;
    private final boolean useCurrentWindow;
    private final BenchmarkLightingAdmission.RequiredModel expectedLightingModel;
    private final boolean captureScreenshots;
    private final List<BenchmarkScalingMode> sequence;
    private final RouteConfig route;
    private final String configurationError;

    private Stage stage = Stage.IDLE;
    private int stageFrames;
    private int segmentIndex;
    private int segmentFrame;
    private int measuredFrames;
    private SegmentPhase segmentPhase = SegmentPhase.WARMUP;
    private RouteCheckEvent boundaryCheckEvent;
    private int boundaryCheckFrames;
    private long boundaryCheckToken;
    private int expectedFramebufferWidth;
    private int expectedFramebufferHeight;
    private boolean advancedAdmissionLogged;
    private long targetMonitor;
    private VideoMode targetVideoMode;
    private Optional<VideoMode> originalFullscreenMode = Optional.empty();
    private CameraType originalCameraType;
    private Entity originalCameraEntity;
    private boolean routeClientStateApplied;
    private final AtomicBoolean routeServerTaskPending = new AtomicBoolean();
    private boolean routeApplyRequested;
    private boolean routeApplyLogged;
    private boolean routeServerTicksFrozenLogged;
    private long routeApplyToken;
    private long nextRouteServerToken;
    private volatile long completedRouteServerToken;
    private volatile String routeServerMismatch;
    private volatile String routeServerFailure;
    private volatile boolean routeServerTicksFrozen;
    private int routeServerCheckCountdown;
    private int routeStableFrames;
    private int g4SourceReceiptFrames;
    private boolean g4ModePreapplied;
    private final AtomicBoolean torchEpochServerTaskPending = new AtomicBoolean();
    private boolean torchEpochRequested;
    private boolean torchEpochAppliedLogged;
    private boolean torchEpochRemovalRequested;
    private boolean torchEpochRemovedLogged;
    private boolean torchEpochFinished;
    private long torchEpochToken;
    private long torchEpochRemovalToken;
    private long nextTorchEpochToken;
    private volatile long completedTorchEpochToken;
    private volatile String torchEpochFailure;
    private int torchEpochAppliedMeasuredFrame = -1;
    private int torchEpochRemovedMeasuredFrame = -1;
    private final AtomicBoolean survivalGuardTaskPending = new AtomicBoolean();
    private UUID guardedPlayerId;
    private volatile boolean survivalGuardApplied;
    private volatile String survivalGuardFailure;
    private boolean originalInvulnerable;
    private float originalHealth;
    private int originalFoodLevel;
    private float originalSaturation;
    private boolean originalClientStateCaptured;
    private boolean l6DynamicReady;
    private int l6MotionFrame;
    private ItemStack l6OriginalMainHand = ItemStack.EMPTY;
    private boolean l6OriginalMainHandCaptured;
    private final List<ItemEntity> l6ProbeEntities = new ArrayList<>();
    private boolean g6MatrixReady;
    private int g6MatrixReceiptMask;
    private long g6MatrixOrbitFieldGeneration = -1L;
    private long g6MatrixOrbitBlockSamples = -1L;
    private long g6MatrixOrbitStaticSourceSamples = -1L;
    private long g6MatrixOrbitScrollSamples = -1L;
    private long g6MatrixOrbitFullResetSamples = -1L;
    private boolean g6MatrixOrbitStarted;
    private final AtomicBoolean g6MatrixServerTaskPending = new AtomicBoolean();
    private volatile G6MatrixServerAction g6MatrixCompletedServerAction;
    private volatile String g6MatrixServerFailure;
    private G6MatrixServerAction g6MatrixAwaitingClientAction;
    private int g6MatrixActiveRequestedFrame;
    private int g6MatrixStreamIndex = -1;
    private int g6MatrixStreamTargetOffset;
    private int g6MatrixStreamTargetYOffset;
    private CompletableFuture<Void> g6MatrixResourceReload;
    private boolean g6MatrixChunkReloadRequested;
    private boolean g6MatrixResourceReloadRequested;
    private String g6MatrixAwaitingRecovery;
    private long g6MatrixRecoveryFieldGeneration = -1L;
    private long g6MatrixRecoverySampleCount = -1L;
    private int g6MatrixRecoveryLatencyClass = G6_MATRIX_LATENCY_NONE;
    private boolean g6MatrixRecoveryActionVisible;
    private boolean g6MatrixRecoveryRequiresTerrainBinding;
    private long g6MatrixRecoveryTerrainSubmit = -1L;
    private long g6MatrixRecoveryTerrainDeviceGeneration = -1L;
    private long g6MatrixRecoveryTerrainFieldGeneration = -1L;
    private long g6MatrixRecoveryTerrainSourceTick = -1L;
    private int g6MatrixRecoveryReceiptBit;
    private int g6MatrixRecoveryRequestedFrame;
    private int g6MatrixRecoveryDeadline;
    private long g6MatrixMeasurementAccountedBytes = -1L;
    private volatile boolean g6MatrixNetherPrepared;
    private boolean g6MatrixNetherChunkForcedByBenchmark;
    private int g6MatrixNetherChunkX;
    private int g6MatrixNetherChunkZ;
    private boolean g6MatrixFinalValidated;
    private boolean armed;
    private boolean resolutionOverlayOverridden;
    private boolean originalResolutionOverlayEnabled;
    private boolean fiGeneratedMeasurementStarted;
    private long fiGeneratedMeasurementStart;
    private FrameInterpolationRuntimeStatus fiRuntimeMeasurementStart;
    private final long[] fiTransportMeasurementStart = new long[FI_TRANSPORT_COUNTER_COUNT];
    private final int[] framebufferWidthScratch = new int[1];
    private final int[] framebufferHeightScratch = new int[1];

    public MetalFxBenchmarkController() {
        String error = null;
        RouteConfig parsedRoute = null;
        int parsedMaxFps = -1;
        int parsedRenderDistance = -1;
        int parsedSimulationDistance = -1;
        int parsedParticles = -1;
        int parsedMipmapLevels = -1;
        int parsedBiomeBlendRadius = -1;
        int parsedCloudRange = -1;
        int parsedConfiguredGuiScale = -1;
        double parsedEntityDistanceScaling = -1.0;
        String parsedGraphicsPreset = "";
        String parsedCloudsMode = "";
        boolean parsedAmbientOcclusion = false;
        List<String> parsedResourcePackIds = List.of();
        boolean parsedExpectedVsync = false;
        boolean parsedFiValidationRequired = "1".equals(System.getenv("METALLUM_BENCHMARK_FI_REQUIRED"));
        boolean parsedFiOverlayRequested = false;
        int parsedFiMinimumGenerated = 0;
        BenchmarkLightingAdmission.RequiredModel parsedExpectedLighting = parsedFiValidationRequired
                ? BenchmarkLightingAdmission.RequiredModel.VANILLA
                : BenchmarkLightingAdmission.RequiredModel.ADVANCED;
        try {
            parsedRoute = RouteConfig.fromEnvironment();
            parsedMaxFps = positiveIntStrict("METALLUM_BENCHMARK_MAX_FPS");
            parsedRenderDistance = positiveIntStrict("METALLUM_BENCHMARK_RENDER_DISTANCE");
            parsedSimulationDistance = positiveIntStrict("METALLUM_BENCHMARK_SIMULATION_DISTANCE");
            parsedParticles = nonNegativeIntStrict("METALLUM_BENCHMARK_PARTICLES");
            parsedMipmapLevels = nonNegativeIntStrict("METALLUM_BENCHMARK_MIPMAP_LEVELS");
            parsedBiomeBlendRadius = nonNegativeIntStrict("METALLUM_BENCHMARK_BIOME_BLEND_RADIUS");
            parsedCloudRange = nonNegativeIntStrict("METALLUM_BENCHMARK_CLOUD_RANGE");
            parsedConfiguredGuiScale = nonNegativeIntStrict("METALLUM_BENCHMARK_CONFIGURED_GUI_SCALE");
            parsedEntityDistanceScaling = positiveFiniteDouble(
                    "METALLUM_BENCHMARK_ENTITY_DISTANCE_SCALING"
            );
            parsedGraphicsPreset = requiredEnv("METALLUM_BENCHMARK_GRAPHICS_PRESET");
            parsedCloudsMode = requiredEnv("METALLUM_BENCHMARK_CLOUDS_MODE");
            parsedAmbientOcclusion = requiredBoolean("METALLUM_BENCHMARK_AO");
            parsedResourcePackIds = requiredCsv("METALLUM_BENCHMARK_ACTIVE_RESOURCE_PACKS");
            parsedExpectedVsync = optionalBoolean("METALLUM_BENCHMARK_EXPECTED_VSYNC", false);
            parsedExpectedLighting = BenchmarkLightingAdmission.RequiredModel.parse(
                    requiredEnv("METALLUM_BENCHMARK_EXPECTED_LIGHTING_MODEL")
            );
            if (parsedFiValidationRequired) {
                parsedFiOverlayRequested = optionalBoolean(
                        "METALLUM_BENCHMARK_FI_OVERLAY",
                        false
                );
                parsedFiMinimumGenerated = positiveIntStrict("METALLUM_BENCHMARK_FI_MIN_GENERATED");
            }
        } catch (RuntimeException exception) {
            error = "invalid deterministic benchmark configuration: " + exception.getMessage();
        }
        this.monitorName = env("METALLUM_BENCHMARK_MONITOR", "PHL");
        this.targetWidth = positiveInt("METALLUM_BENCHMARK_WIDTH", 5120);
        this.targetHeight = positiveInt("METALLUM_BENCHMARK_HEIGHT", 2880);
        this.warmupFrames = positiveInt("METALLUM_BENCHMARK_WARMUP_FRAMES", 1800);
        this.measureFrames = positiveInt("METALLUM_BENCHMARK_MEASURE_FRAMES", 3000);
        this.expectedMaxFps = parsedMaxFps;
        this.expectedRenderDistance = parsedRenderDistance;
        this.expectedSimulationDistance = parsedSimulationDistance;
        this.expectedParticles = parsedParticles;
        this.expectedMipmapLevels = parsedMipmapLevels;
        this.expectedBiomeBlendRadius = parsedBiomeBlendRadius;
        this.expectedCloudRange = parsedCloudRange;
        this.expectedConfiguredGuiScale = parsedConfiguredGuiScale;
        this.expectedEntityDistanceScaling = parsedEntityDistanceScaling;
        this.expectedGraphicsPreset = parsedGraphicsPreset;
        this.expectedCloudsMode = parsedCloudsMode;
        this.expectedAmbientOcclusion = parsedAmbientOcclusion;
        this.expectedResourcePackIds = parsedResourcePackIds;
        this.expectedVsync = parsedExpectedVsync;
        this.fiValidationRequired = parsedFiValidationRequired;
        this.fiOverlayRequested = parsedFiOverlayRequested;
        this.fiMinimumGenerated = parsedFiMinimumGenerated;
        this.expectedLightingModel = parsedExpectedLighting;
        this.useCurrentWindow = "1".equals(System.getenv("METALLUM_BENCHMARK_CURRENT_WINDOW"));
        this.captureScreenshots = "1".equals(System.getenv("METALLUM_BENCHMARK_SCREENSHOTS"));
        this.expectedFramebufferWidth = this.targetWidth;
        this.expectedFramebufferHeight = this.targetHeight;

        List<BenchmarkScalingMode> parsed = new ArrayList<>();
        try {
            for (String value : env("METALLUM_BENCHMARK_SEQUENCE", "OFF").split(",")) {
                parsed.add(BenchmarkScalingMode.parse(value));
            }
            if (parsed.isEmpty()) {
                error = "benchmark sequence is empty";
            }
        } catch (IllegalArgumentException exception) {
            if (error == null) {
                error = "invalid METALLUM_BENCHMARK_SEQUENCE";
            }
        }
        if (error == null && GiTransportRuntime.isPopulationRequested()
                && !isFrozenG4Sequence(parsed)) {
            error = "G4 requires exactly one frozen OFF benchmark segment";
        }
        if (error == null && parsedRoute != null && parsedRoute.torchEpoch() != null) {
            TorchEpochConfig torchEpoch = parsedRoute.torchEpoch();
            if (parsed.size() != 1) {
                error = "TORCH_EPOCH requires exactly one benchmark segment";
            } else if (Math.floorMod(torchEpoch.x(), 16) != 0
                    || Math.floorMod(torchEpoch.z(), 16) != 0) {
                error = "TORCH_EPOCH must run on an x/z section boundary";
            } else if (torchEpoch.applyAfterMeasuredFrames() != 300
                    || torchEpoch.observationFrames() != 300) {
                error = "TORCH_EPOCH requires a 300-frame baseline and observation window";
            } else if (torchEpoch.removesTorch()
                    && torchEpoch.removeAfterMeasuredFrames() != 450) {
                error = "TORCH_TOGGLE must remove the torch after exactly 450 measured frames";
            } else if (torchEpoch.removesTorch()
                    && (torchEpoch.removeAfterMeasuredFrames() <= torchEpoch.applyAfterMeasuredFrames()
                    || torchEpoch.removeAfterMeasuredFrames() >= torchEpoch.endMeasuredFrame())) {
                error = "TORCH_TOGGLE removal must lie strictly inside the observation window";
            } else if (torchEpoch.endMeasuredFrame() >= this.measureFrames) {
                error = "TORCH_EPOCH must end before the measurement segment ends";
            }
        }
        if (error == null && parsedRoute != null && parsedRoute.g6Matrix() != null) {
            G6MatrixConfig matrix = parsedRoute.g6Matrix();
            long streamEndFrame = (long) matrix.streamStartFrame()
                    + (long) (matrix.streamOffsets().length - 1) * matrix.streamStepFrames();
            boolean ordered = matrix.orbitStartFrame() < matrix.orbitEndFrame()
                    && matrix.orbitEndFrame() < matrix.lavaApplyFrame()
                    && matrix.lavaApplyFrame() < matrix.lavaRemoveFrame()
                    && matrix.lavaRemoveFrame() < matrix.chunkReloadFrame()
                    && matrix.chunkReloadFrame() < matrix.resourceReloadFrame()
                    && matrix.resourceReloadFrame() < matrix.dayFrame()
                    && matrix.dayFrame() < matrix.nightFrame()
                    && matrix.nightFrame() < matrix.rainFrame()
                    && matrix.rainFrame() < matrix.clearFrame()
                    && matrix.clearFrame() < matrix.streamStartFrame()
                    && streamEndFrame < matrix.teleportFrame()
                    && matrix.teleportFrame() < matrix.teleportReturnFrame()
                    && matrix.teleportReturnFrame() < matrix.netherEnterFrame()
                    && matrix.netherEnterFrame() < matrix.netherReturnFrame();
            boolean recoveryWindows = g6MatrixRecoveryWindowsSufficient(
                    matrix.chunkReloadFrame(), matrix.resourceReloadFrame(),
                    matrix.dayFrame(), matrix.nightFrame(), matrix.rainFrame(),
                    matrix.clearFrame(), matrix.streamStartFrame(),
                    matrix.teleportFrame(), matrix.teleportReturnFrame(),
                    matrix.netherEnterFrame(), matrix.netherReturnFrame(), this.measureFrames
            );
            boolean scrollWindows = g6MatrixScrollWindowsSufficient(
                    matrix.streamStartFrame(), matrix.streamStepFrames(),
                    matrix.streamOffsets().length, matrix.teleportFrame()
            );
            if (parsed.size() != 1) {
                error = "GI_G6_MATRIX requires exactly one benchmark segment";
            } else if (!ordered
                    || matrix.streamYOffsets().length != matrix.streamOffsets().length
                    || matrix.streamOffsets()[matrix.streamOffsets().length - 1] != 0
                    || matrix.streamYOffsets()[matrix.streamYOffsets().length - 1] != 0
                    || (matrix.orbitEndFrame() - matrix.orbitStartFrame())
                    % matrix.orbitPeriodFrames() != 0) {
                error = "GI_G6_MATRIX event schedule is not exact, ordered, and recoverable";
            } else if (!recoveryWindows) {
                error = "GI_G6_MATRIX recovery gaps are below the exact 270/200/260/190/470-frame floors";
            } else if (!scrollWindows) {
                error = "GI_G6_MATRIX scroll cadence/recovery is below the exact 40/60-frame floors";
            }
        }
        this.sequence = List.copyOf(parsed);
        this.route = parsedRoute;
        this.configurationError = error;
    }

    public void arm() {
        if (this.armed) {
            return;
        }
        this.armed = true;
        if (this.fiValidationRequired && this.fiOverlayRequested) {
            this.originalResolutionOverlayEnabled = MetalFxUpscaling.isResolutionOverlayEnabled();
            MetalFxUpscaling.setResolutionOverlayEnabled(true);
            this.resolutionOverlayOverridden = true;
        }
        this.stage = Stage.SELECT_MONITOR;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=ARMED scope={} target={}x{} warmup={} measure={} sequence={} route={}",
                this.useCurrentWindow ? "current-window" : this.monitorName,
                this.targetWidth,
                this.targetHeight,
                this.warmupFrames,
                this.measureFrames,
                this.sequence,
                this.route == null ? "invalid" : this.route.routeId()
        );
    }

    public void driveWindow(final Minecraft minecraft) {
        if (!this.armed || this.stage == Stage.IDLE || this.stage == Stage.STOPPING) {
            return;
        }
        if (!minecraft.isGameLoadFinished() || minecraft.level == null || minecraft.player == null) {
            return;
        }
        if (!this.originalClientStateCaptured) {
            Window window = minecraft.getWindow();
            this.originalFullscreenMode = window.getPreferredFullscreenVideoMode();
            this.originalCameraType = minecraft.options.getCameraType();
            this.originalCameraEntity = minecraft.getCameraEntity();
            this.originalClientStateCaptured = true;
        }
        // Automated runs intentionally have no input. Keep Minecraft's AFK
        // limiter from replacing the requested uncapped framerate with 30 FPS.
        minecraft.getFramerateLimitTracker().onInputReceived();
        maintainSurvivalGuard(minecraft);
        if (this.route != null && minecraft.level != null && minecraft.level.tickRateManager().isFrozen()) {
            lockPlayerPose(minecraft);
        }
        if (this.survivalGuardFailure != null) {
            fail(minecraft, this.survivalGuardFailure);
            return;
        }
        if (this.routeServerFailure != null) {
            fail(minecraft, this.routeServerFailure);
            return;
        }
        if (this.stage == Stage.RUNNING) {
            return;
        }
        if (this.configurationError != null) {
            fail(minecraft, this.configurationError);
            return;
        }

        this.stageFrames++;
        Window window = minecraft.getWindow();
        switch (this.stage) {
            case SELECT_MONITOR -> {
                if (this.useCurrentWindow) {
                    selectCurrentWindow(minecraft, window);
                } else {
                    selectMonitor(minecraft, window);
                }
            }
            case EXIT_FULLSCREEN -> exitFullscreen(minecraft, window);
            case MOVE_WINDOWED -> moveWindow(minecraft, window);
            case WAIT_MOVED -> waitForMove(minecraft, window);
            case ENTER_FULLSCREEN -> enterFullscreen(minecraft, window);
            case WAIT_FRAMEBUFFER -> waitForFramebuffer(minecraft, window);
            case WAIT_ROUTE -> waitForRoute(minecraft);
            default -> {
            }
        }
    }

    public void onPresentedFrame(final Minecraft minecraft) {
        if (this.stage != Stage.RUNNING) {
            return;
        }
        String lightingAdmissionFailure = verifyLightingAdmission();
        if (lightingAdmissionFailure != null) {
            fail(minecraft, lightingAdmissionFailure);
            return;
        }
        String runtimeMismatch = runtimePacingMismatch(minecraft);
        if (runtimeMismatch != null) {
            fail(minecraft, runtimeMismatch);
            return;
        }
        if (!isTargetFramebuffer(minecraft.getWindow())) {
            fail(minecraft, "benchmark framebuffer changed during measurement");
            return;
        }
        if (GiTransportRuntime.isPopulationRequested() && GiTransportRuntime.isInvalid()) {
            fail(minecraft, "G4 transport became invalid: " + GiTransportRuntime.invalidReason());
            return;
        }
        if (GiLiveRuntime.isRequested()
                && GiLiveRuntime.admissionState() == GiLiveRuntime.AdmissionState.INVALID) {
            fail(minecraft, "G6 live GI became invalid: " + GiLiveRuntime.invalidReason());
            return;
        }
        if (GiReceiverRuntime.isFrozenRequested()
                && (GiReceiverRuntime.admission().state()
                == GiReceiverRuntime.AdmissionState.INVALID
                || GiReceiverRuntime.admission().carrierSkipCount() != 0L)) {
            fail(minecraft, "G5 receiver became invalid: "
                    + (GiReceiverRuntime.admission().carrierSkipCount() != 0L
                    ? GiReceiverRuntime.admission().lastCarrierSkipReason()
                    : GiReceiverRuntime.admission().invalidReason()));
            return;
        }
        if (GiTransportRuntime.isPopulationRequested()
                && this.segmentPhase != SegmentPhase.WARMUP
                && !GiTransportRuntime.isResolvedReady()) {
            fail(minecraft, "G4 transport is not READY outside benchmark warmup");
            return;
        }

        switch (this.segmentPhase) {
            case WARMUP -> {
                driveL6DynamicShadow(minecraft);
                driveNetherLavaStress(minecraft);
                this.segmentFrame++;
                if (GiTransportRuntime.isPopulationRequested()
                        && this.segmentFrame >= G4_ADMISSION_TIMEOUT_FRAMES
                        && !GiTransportRuntime.isResolvedReady()) {
                    fail(minecraft, "G4 transport did not resolve READY within 240 warmup frames");
                    return;
                }
                if (this.segmentFrame >= this.warmupFrames) {
                    if (GiTransportRuntime.isPopulationRequested()
                            && !GiTransportRuntime.isResolvedReady()) {
                        fail(minecraft, "G4 transport did not resolve READY before measurement");
                        return;
                    }
                    beginBoundaryCheck(minecraft, RouteCheckEvent.MEASURE_START);
                }
            }
            case WAIT_MEASURE_START_CHECK, WAIT_MEASURE_END_CHECK -> pollBoundaryCheck(minecraft);
            case MEASURE -> {
                this.segmentFrame++;
                this.measuredFrames++;
                driveL6DynamicShadow(minecraft);
                driveTorchEpoch(minecraft);
                driveG6Matrix(minecraft);
                driveNetherLavaStress(minecraft);
                if (this.stage != Stage.RUNNING) {
                    return;
                }
                if (this.captureScreenshots
                        && GiLiveRuntime.isRequested()
                        && this.measuredFrames == G6_TORCH_ON_SCREENSHOT_MEASURED_FRAME) {
                    if (!this.torchEpochAppliedLogged || this.torchEpochRemovalRequested) {
                        fail(minecraft, "G6 torch-on screenshot frame is outside the confirmed torch epoch");
                        return;
                    }
                    TorchEpochConfig torch = this.route.torchEpoch();
                    if (torch == null || minecraft.level == null
                            || !minecraft.level.getBlockState(torch.position()).is(Blocks.TORCH)) {
                        fail(minecraft, "G6 torch-on screenshot lacks the synchronized client torch state");
                        return;
                    }
                    if (!minecraft.levelRenderer.hasRenderedAllSections()) {
                        fail(minecraft, "G6 torch-on screenshot terrain rebuild is still pending");
                        return;
                    }
                    Screenshot.grab(minecraft, false);
                    Metallum.LOGGER.info(
                            "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED index={} mode={} phase=TORCH_ON measured_frame={}",
                            this.segmentIndex + 1,
                            this.sequence.get(this.segmentIndex),
                            this.measuredFrames
                    );
                }
                if (this.captureScreenshots
                        && this.fiValidationRequired
                        && this.segmentIndex == this.sequence.size() - 1
                        && this.measuredFrames == Math.max(1, this.measureFrames - 60)) {
                    Screenshot.grab(minecraft, false);
                    Metallum.LOGGER.info(
                            "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED index={} mode={} phase=MEASURE_TAIL",
                            this.segmentIndex + 1,
                            this.sequence.get(this.segmentIndex)
                    );
                }
                if (this.measuredFrames >= this.measureFrames) {
                    String g6MatrixFailure = completeG6Matrix();
                    if (g6MatrixFailure != null) {
                        fail(minecraft, g6MatrixFailure);
                        return;
                    }
                    String l6CoverageFailure = completeL6DynamicShadowCoverage();
                    if (l6CoverageFailure != null) {
                        fail(minecraft, l6CoverageFailure);
                        return;
                    }
                    if (this.fiValidationRequired
                            && this.segmentIndex == this.sequence.size() - 1
                            && !completeFiGeneratedValidation(minecraft)) {
                        return;
                    }
                    if (this.route != null && "nether-lava-stress-v1".equals(this.route.routeId())) {
                        Metallum.LOGGER.info(
                                "METALLUM_BENCHMARK EVENT=NETHER_LAVA_TELEMETRY route={} {}",
                                this.route.routeId(),
                                com.metallum.client.lighting.AdvancedLightRegistry.getTelemetryString()
                        );
                    }
                    logSegmentEvent("MEASURE_END");
                    beginBoundaryCheck(minecraft, RouteCheckEvent.MEASURE_END);
                }
            }
        }
    }

    private void driveTorchEpoch(final Minecraft minecraft) {
        TorchEpochConfig config = this.route.torchEpoch();
        if (config == null || this.torchEpochFinished) {
            return;
        }
        if (this.torchEpochFailure != null) {
            fail(minecraft, this.torchEpochFailure);
            return;
        }
        if (!this.torchEpochRequested
                && this.measuredFrames == config.applyAfterMeasuredFrames()) {
            beginTorchEpoch(minecraft, config);
        }
        if (this.torchEpochRequested && !this.torchEpochAppliedLogged) {
            pollTorchEpoch(minecraft, config);
        }
        if (this.stage != Stage.RUNNING) {
            return;
        }
        if (config.removesTorch()
                && !this.torchEpochRemovalRequested
                && this.measuredFrames == config.removeAfterMeasuredFrames()) {
            beginTorchEpochRemoval(minecraft, config);
        }
        if (this.torchEpochRemovalRequested && !this.torchEpochRemovedLogged) {
            pollTorchEpochRemoval(minecraft, config);
        }
        if (this.stage != Stage.RUNNING) {
            return;
        }
        if (this.measuredFrames == config.endMeasuredFrame()) {
            finishTorchEpoch(minecraft, config);
        }
    }

    private void beginTorchEpoch(
            final Minecraft minecraft,
            final TorchEpochConfig config
    ) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            fail(minecraft, "TORCH_EPOCH requires an integrated singleplayer server");
            return;
        }
        if (!this.torchEpochServerTaskPending.compareAndSet(false, true)) {
            fail(minecraft, "TORCH_EPOCH server task was already pending");
            return;
        }

        this.torchEpochRequested = true;
        this.torchEpochToken = ++this.nextTorchEpochToken;
        TorchEpochTelemetry.begin(this.torchEpochToken);
        SodiumRelightOracle.beginObservation(this.torchEpochToken);
        SodiumRelightFastPath.beginObservation(this.torchEpochToken);
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_BEGIN route={} position={},{},{} measured_frame={} observation_frames={}",
                this.route.routeId(),
                config.x(),
                config.y(),
                config.z(),
                this.measuredFrames,
                config.observationFrames()
        );
        try {
            long token = this.torchEpochToken;
            server.executeIfPossible(() -> {
                try {
                    this.torchEpochFailure = applyTorchEpoch(server, config);
                } catch (RuntimeException exception) {
                    this.torchEpochFailure = "TORCH_EPOCH server task failed: "
                            + exception.getClass().getSimpleName();
                    Metallum.LOGGER.error(
                            "METALLUM_BENCHMARK TORCH_EPOCH server task failed",
                            exception
                    );
                } finally {
                    this.completedTorchEpochToken = token;
                    this.torchEpochServerTaskPending.set(false);
                }
            });
        } catch (RuntimeException exception) {
            this.torchEpochServerTaskPending.set(false);
            this.torchEpochFailure = "could not submit TORCH_EPOCH server task: "
                    + exception.getClass().getSimpleName();
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK TORCH_EPOCH task submission failed",
                    exception
            );
        }
    }

    private String applyTorchEpoch(
            final IntegratedServer server,
            final TorchEpochConfig config
    ) {
        ServerLevel level = server.getLevel(this.route.dimension());
        if (level == null) {
            return "TORCH_EPOCH benchmark dimension is unavailable";
        }
        BlockPos position = config.position();
        level.getChunkSource().getChunk(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ()),
                ChunkStatus.FULL,
                true
        );
        if (!level.getBlockState(position).is(Blocks.AIR)) {
            return "TORCH_EPOCH initial block is not air";
        }
        if (!level.getBlockState(position.below()).is(Blocks.GRASS_BLOCK)) {
            return "TORCH_EPOCH support block is not grass_block";
        }
        var torch = Blocks.TORCH.defaultBlockState();
        if (!torch.canSurvive(level, position)) {
            return "TORCH_EPOCH torch cannot survive at the tracked position";
        }
        if (!level.setBlock(position, torch, Block.UPDATE_ALL)) {
            return "TORCH_EPOCH server rejected the torch placement";
        }
        if (!level.getBlockState(position).is(Blocks.TORCH)) {
            return "TORCH_EPOCH server state did not become torch";
        }
        return null;
    }

    private void pollTorchEpoch(
            final Minecraft minecraft,
            final TorchEpochConfig config
    ) {
        if (this.torchEpochFailure != null) {
            fail(minecraft, this.torchEpochFailure);
            return;
        }
        if (this.torchEpochServerTaskPending.get()
                || this.completedTorchEpochToken < this.torchEpochToken) {
            return;
        }
        this.torchEpochAppliedLogged = true;
        this.torchEpochAppliedMeasuredFrame = this.measuredFrames;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_APPLIED route={} position={},{},{} measured_frame={} requested_frame={}",
                this.route.routeId(),
                config.x(),
                config.y(),
                config.z(),
                this.torchEpochAppliedMeasuredFrame,
                config.applyAfterMeasuredFrames()
        );
    }

    private void beginTorchEpochRemoval(
            final Minecraft minecraft,
            final TorchEpochConfig config
    ) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            fail(minecraft, "TORCH_TOGGLE requires an integrated singleplayer server");
            return;
        }
        if (!this.torchEpochAppliedLogged
                || this.torchEpochServerTaskPending.get()
                || this.routeServerTaskPending.get()
                || this.survivalGuardTaskPending.get()
                || this.completedTorchEpochToken < this.torchEpochToken) {
            fail(minecraft, "TORCH_TOGGLE placement server task was not idle before removal");
            return;
        }
        if (!minecraft.level.getBlockState(config.position()).is(Blocks.TORCH)) {
            fail(minecraft, "TORCH_TOGGLE client did not receive the torch before removal");
            return;
        }
        if (!minecraft.levelRenderer.hasRenderedAllSections()) {
            fail(minecraft, "TORCH_TOGGLE first terrain refresh did not drain before removal");
            return;
        }
        if (!this.torchEpochServerTaskPending.compareAndSet(false, true)) {
            fail(minecraft, "TORCH_TOGGLE removal server task was already pending");
            return;
        }

        this.torchEpochRemovalRequested = true;
        this.torchEpochRemovalToken = ++this.nextTorchEpochToken;
        try {
            long token = this.torchEpochRemovalToken;
            server.executeIfPossible(() -> {
                try {
                    this.torchEpochFailure = removeTorchEpoch(server, config);
                } catch (RuntimeException exception) {
                    this.torchEpochFailure = "TORCH_TOGGLE removal server task failed: "
                            + exception.getClass().getSimpleName();
                    Metallum.LOGGER.error(
                            "METALLUM_BENCHMARK TORCH_TOGGLE removal server task failed",
                            exception
                    );
                } finally {
                    this.completedTorchEpochToken = token;
                    this.torchEpochServerTaskPending.set(false);
                }
            });
        } catch (RuntimeException exception) {
            this.torchEpochServerTaskPending.set(false);
            this.torchEpochFailure = "could not submit TORCH_TOGGLE removal server task: "
                    + exception.getClass().getSimpleName();
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK TORCH_TOGGLE removal task submission failed",
                    exception
            );
        }
    }

    private String removeTorchEpoch(
            final IntegratedServer server,
            final TorchEpochConfig config
    ) {
        ServerLevel level = server.getLevel(this.route.dimension());
        if (level == null) {
            return "TORCH_TOGGLE benchmark dimension is unavailable";
        }
        BlockPos position = config.position();
        level.getChunkSource().getChunk(
                SectionPos.blockToSectionCoord(position.getX()),
                SectionPos.blockToSectionCoord(position.getZ()),
                ChunkStatus.FULL,
                true
        );
        if (!level.getBlockState(position).is(Blocks.TORCH)) {
            return "TORCH_TOGGLE server block was not torch before removal";
        }
        if (!level.getBlockState(position.below()).is(Blocks.GRASS_BLOCK)) {
            return "TORCH_TOGGLE support block is not grass_block before removal";
        }
        if (!level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) {
            return "TORCH_TOGGLE server rejected the torch removal";
        }
        if (!level.getBlockState(position).is(Blocks.AIR)) {
            return "TORCH_TOGGLE server state did not return to air";
        }
        return null;
    }

    private void pollTorchEpochRemoval(
            final Minecraft minecraft,
            final TorchEpochConfig config
    ) {
        if (this.torchEpochFailure != null) {
            fail(minecraft, this.torchEpochFailure);
            return;
        }
        if (this.torchEpochServerTaskPending.get()
                || this.completedTorchEpochToken < this.torchEpochRemovalToken) {
            return;
        }
        this.torchEpochRemovedLogged = true;
        this.torchEpochRemovedMeasuredFrame = this.measuredFrames;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_REMOVED route={} position={},{},{} measured_frame={} requested_frame={}",
                this.route.routeId(),
                config.x(),
                config.y(),
                config.z(),
                this.torchEpochRemovedMeasuredFrame,
                config.removeAfterMeasuredFrames()
        );
    }

    private void finishTorchEpoch(
            final Minecraft minecraft,
            final TorchEpochConfig config
    ) {
        if (config.removesTorch()) {
            if (this.torchEpochServerTaskPending.get()
                    || !this.torchEpochRemovalRequested
                    || this.completedTorchEpochToken < this.torchEpochRemovalToken
                    || !this.torchEpochRemovedLogged) {
                fail(minecraft, "TORCH_TOGGLE removal did not complete inside its observation window");
                return;
            }
            if (!minecraft.level.getBlockState(config.position()).is(Blocks.AIR)) {
                fail(minecraft, "TORCH_TOGGLE client final block did not return to air");
                return;
            }
        } else {
            if (this.torchEpochServerTaskPending.get()
                    || this.completedTorchEpochToken < this.torchEpochToken
                    || !this.torchEpochAppliedLogged) {
                fail(minecraft, "TORCH_EPOCH placement did not complete inside its observation window");
                return;
            }
            if (!minecraft.level.getBlockState(config.position()).is(Blocks.TORCH)) {
                fail(minecraft, "TORCH_EPOCH client did not receive the torch state");
                return;
            }
        }
        if (!minecraft.levelRenderer.hasRenderedAllSections()) {
            fail(minecraft, "TORCH_EPOCH terrain rebuild queue did not drain");
            return;
        }

        TorchEpochTelemetry.Snapshot snapshot = TorchEpochTelemetry.end();
        SodiumRelightOracle.Snapshot relightOracle = SodiumRelightOracle.endObservation();
        SodiumRelightFastPath.Snapshot relightFast = SodiumRelightFastPath.endObservation();
        long targetSectionKey = SectionPos.asLong(
                SectionPos.blockToSectionCoord(config.x()),
                SectionPos.blockToSectionCoord(config.y()),
                SectionPos.blockToSectionCoord(config.z())
        );
        boolean targetSectionProducedOutput = TorchEpochTelemetry.wasBuildOutput(targetSectionKey);
        boolean targetSectionRefreshedInPlace = TorchEpochTelemetry.wasInPlaceGeometryRefreshed(targetSectionKey);
        boolean targetSectionCompactLightPatched = TorchEpochTelemetry.wasCompactLightPatched(targetSectionKey);
        SodiumLightSidecar.Snapshot sidecar = SodiumLightSidecar.snapshot();
        SodiumTerrainLightPatch.Snapshot lightPatch = SodiumTerrainLightPatch.snapshot();
        SodiumTerrainStaticShadow.Snapshot shadow = lightPatch.shadow();
        long submittedGeometryBytes = snapshot.acceptedGeometryPayloadBytes()
                - snapshot.geometryBytesElided();
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_COUNTERS epoch={} requests={}/{} light_requests={} geometry_or_unknown_requests={} unique_light_sections={} unique_geometry_or_unknown_sections={} light_only_sections={} mixed_cause_sections={} light_scope_mismatches={} light_scope_failures={} tasks={}/{} outputs={}/{} accepted_mesh_payload_bytes={} queue={}/{} busy={}/{} pending_results={}/{} max_pending_age_ns={} errors={} overflow={}",
                snapshot.epochId(),
                snapshot.rebuildRequestCount(),
                snapshot.uniqueRebuildRequestSections(),
                snapshot.lightRebuildRequestCount(),
                snapshot.geometryOrUnknownRebuildRequestCount(),
                snapshot.uniqueLightRebuildRequestSections(),
                snapshot.uniqueGeometryOrUnknownRebuildRequestSections(),
                snapshot.lightOnlyRebuildSections(),
                snapshot.mixedRebuildCauseSections(),
                snapshot.lightScopeMismatchCount(),
                snapshot.lightScopeFailureCount(),
                snapshot.rebuildTaskCount(),
                snapshot.uniqueRebuildTaskSections(),
                snapshot.buildOutputCount(),
                snapshot.uniqueBuildOutputSections(),
                snapshot.acceptedMeshPayloadBytes(),
                snapshot.maximumBuilderQueueDepth(),
                snapshot.finalBuilderQueueDepth(),
                snapshot.maximumBusyWorkerCount(),
                snapshot.finalBusyWorkerCount(),
                snapshot.maximumPendingResultCount(),
                snapshot.finalPendingResultCount(),
                snapshot.maximumPendingAgeNanos(),
                snapshot.errorCount(),
                snapshot.overflowCount()
        );
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_SIDECAR epoch={} configured={} active={} accepted_geometry_payload_bytes={} in_place_outputs={} unique_in_place_sections={} in_place_geometry_bytes={} in_place_mesh_commands={} full_upload_geometry_bytes={} target_section_output={} target_section_in_place={} produced_bytes={} uploaded_bytes={} upload_commands={} resize_copy_bytes={} resize_copy_commands={} telemetry_fallbacks={} companions={} live_geometry_bytes={} live_sidecar_bytes={} peak_sidecar_bytes={} patched_pipelines={} runtime_fallbacks={}",
                snapshot.epochId(),
                sidecar.configured(),
                sidecar.runtimeActive(),
                snapshot.acceptedGeometryPayloadBytes(),
                snapshot.inPlaceGeometryRefreshOutputs(),
                snapshot.uniqueInPlaceGeometryRefreshSections(),
                snapshot.inPlaceGeometryRefreshBytes(),
                snapshot.inPlaceGeometryRefreshMeshCommands(),
                submittedGeometryBytes,
                targetSectionProducedOutput,
                targetSectionRefreshedInPlace,
                snapshot.sidecarProducedBytes(),
                snapshot.sidecarUploadedBytes(),
                snapshot.sidecarUploadCommands(),
                snapshot.sidecarResizeCopyBytes(),
                snapshot.sidecarResizeCopyCommands(),
                snapshot.sidecarFallbackCount(),
                sidecar.companionCount(),
                sidecar.liveGeometryBytes(),
                sidecar.liveSidecarBytes(),
                sidecar.peakSidecarBytes(),
                sidecar.patchedPipelineCount(),
                sidecar.fallbackCount()
        );
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_LIGHT_PATCH epoch={} configured={} active={} compact_outputs={} unique_compact_sections={} geometry_bytes_elided={} geometry_mesh_commands_elided={} submitted_geometry_bytes={} native_dispatches={} native_mesh_commands={} local_fallbacks={} runtime_fallbacks={} target_section_compact={} shadow_capacity_bytes={} shadow_live_bytes={} shadow_peak_bytes={} resident_shadows={} shadow_evictions={} shadow_rejected_captures={}",
                snapshot.epochId(),
                lightPatch.configured(),
                lightPatch.runtimeActive(),
                snapshot.compactLightPatchOutputs(),
                snapshot.uniqueCompactLightPatchSections(),
                snapshot.geometryBytesElided(),
                snapshot.geometryMeshCommandsElided(),
                submittedGeometryBytes,
                snapshot.nativeLightPatchDispatches(),
                snapshot.nativeLightPatchMeshCommands(),
                snapshot.compactLightPatchFallbackCount(),
                lightPatch.fallbackCount(),
                targetSectionCompactLightPatched,
                shadow.capacityBytes(),
                shadow.liveBytes(),
                shadow.peakBytes(),
                shadow.residentShadows(),
                shadow.evictionCount(),
                shadow.rejectedCaptureCount()
        );
        SodiumRelightPlanCache.Snapshot relightCache = relightOracle.planCache();
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_RELIGHT_ORACLE epoch={} configured={} active={} tasks={} light_only_tasks={} captured_plans={} captured_quads={} replay_attempts={} replay_matches={} mismatched_tasks={} byte_mismatches={} static_shadow_mismatches={} static_shadow_rejections={} rejected_tasks={} scope_failures={} stale_candidates={} discarded_candidates={} published_candidates={} errors={} skipped_full_remeshes={} cache_capacity_bytes={} cache_live_bytes={} cache_peak_bytes={} resident_plans={} pinned_leases={} cache_evictions={} oversized_rejections={} pinned_pressure_rejections={}",
                relightOracle.epochId(),
                relightOracle.configured(),
                relightOracle.active(),
                relightOracle.tasks(),
                relightOracle.lightOnlyTasks(),
                relightOracle.capturedPlans(),
                relightOracle.capturedQuads(),
                relightOracle.replayAttempts(),
                relightOracle.replayMatches(),
                relightOracle.mismatchedTasks(),
                relightOracle.byteMismatches(),
                relightOracle.staticShadowMismatches(),
                relightOracle.staticShadowRejections(),
                relightOracle.rejectedTasks(),
                relightOracle.scopeFailures(),
                relightOracle.staleCandidates(),
                relightOracle.discardedCandidates(),
                relightOracle.publishedCandidates(),
                relightOracle.errors(),
                relightOracle.skippedFullRemeshes(),
                relightCache.capacityBytes(),
                relightCache.liveBytes(),
                relightCache.peakBytes(),
                relightCache.residentPlans(),
                relightCache.pinnedLeases(),
                relightCache.evictionCount(),
                relightCache.oversizedRejectionCount(),
                relightCache.pinnedPressureRejectionCount()
        );
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_RELIGHT_FAST epoch={} configured={} active={} decisions={} created_outputs={} fallback_to_original={} cancelled={} original_calls={} accepted_outputs={} stale_or_disposed={} compact_commits={} full_upload_commits={} forced_rebuilds={} generation_mismatches={} topology_fallbacks={} replay_fallbacks={} reconstruction_fallbacks={} created_geometry_bytes={} errors={}",
                relightFast.epochId(),
                relightFast.configured(),
                relightFast.active(),
                relightFast.taskDecisions(),
                relightFast.fastOutputsCreated(),
                relightFast.fallbackToOriginal(),
                relightFast.cancelledTasks(),
                relightFast.originalCalls(),
                relightFast.acceptedOutputs(),
                relightFast.staleOrDisposedOutputs(),
                relightFast.compactCommits(),
                relightFast.fullUploadCommits(),
                relightFast.forcedRebuilds(),
                relightFast.generationMismatches(),
                relightFast.topologyFallbacks(),
                relightFast.replayFallbacks(),
                relightFast.reconstructionFallbacks(),
                relightFast.createdGeometryBytes(),
                relightFast.errors()
        );
        if (snapshot.epochId() != this.torchEpochToken) {
            fail(minecraft, "TORCH_EPOCH telemetry epoch identity changed");
            return;
        }
        if (snapshot.errorCount() != 0L || snapshot.overflowCount() != 0L) {
            fail(minecraft, "TORCH_EPOCH telemetry reported an error or overflow");
            return;
        }
        if (relightOracle.configured()) {
            if (!relightOracle.active()
                    || relightOracle.epochId() != this.torchEpochToken
                    || relightOracle.tasks() <= 0L
                    || relightOracle.lightOnlyTasks() <= 0L
                    || relightOracle.capturedPlans() <= 0L
                    || relightOracle.capturedQuads() <= 0L
                    || relightOracle.replayAttempts() <= 0L
                    || relightOracle.replayMatches() <= 0L
                    || relightOracle.replayMatches() != relightOracle.replayAttempts()
                    || relightOracle.publishedCandidates() <= 0L
                    || relightOracle.mismatchedTasks() != 0L
                    || relightOracle.byteMismatches() != 0L
                    || relightOracle.staticShadowMismatches() != 0L
                    || relightOracle.scopeFailures() != 0L
                    || relightOracle.staleCandidates() != 0L
                    || relightOracle.discardedCandidates() != 0L
                    || relightOracle.errors() != 0L
                    || relightOracle.skippedFullRemeshes() != 0L
                    || relightCache.liveBytes() > relightCache.capacityBytes()
                    || relightCache.pinnedLeases() != 0L) {
                fail(minecraft, "TORCH_EPOCH exact relight oracle did not prove a zero-mismatch full-remesh comparison");
                return;
            }
        }
        if (relightFast.configured()) {
            if (!relightFast.active()
                    || relightFast.epochId() != this.torchEpochToken
                    || relightFast.taskDecisions() <= 0L
                    || relightFast.fastOutputsCreated() <= 0L
                    || relightFast.acceptedOutputs() <= 0L
                    || relightFast.compactCommits() <= 0L
                    || relightFast.createdGeometryBytes() <= 0L
                    || relightFast.taskDecisions()
                    != relightFast.fastOutputsCreated()
                    + relightFast.fallbackToOriginal()
                    + relightFast.cancelledTasks()
                    || relightFast.originalCalls() != relightFast.fallbackToOriginal()
                    || relightFast.fastOutputsCreated()
                    != relightFast.acceptedOutputs() + relightFast.staleOrDisposedOutputs()
                    || relightFast.acceptedOutputs()
                    != relightFast.compactCommits() + relightFast.fullUploadCommits()
                    || relightFast.fullUploadCommits() != 0L
                    || relightFast.forcedRebuilds() != 0L
                    || relightFast.generationMismatches() != 0L
                    || relightFast.topologyFallbacks() != 0L
                    || relightFast.replayFallbacks() != 0L
                    || relightFast.reconstructionFallbacks() != 0L
                    || relightFast.errors() != 0L
                    || targetSectionCompactLightPatched) {
                fail(minecraft, "TORCH_EPOCH relight fast path violated its exact lifecycle contract");
                return;
            }
        }
        if (snapshot.finalBuilderQueueDepth() != 0
                || snapshot.finalBusyWorkerCount() != 0
                || snapshot.finalPendingResultCount() != 0) {
            fail(minecraft, "TORCH_EPOCH Sodium builder work did not drain");
            return;
        }
        if (snapshot.rebuildRequestCount() == 0L
                || snapshot.rebuildTaskCount() == 0L
                || snapshot.buildOutputCount() == 0L
                || snapshot.acceptedMeshPayloadBytes() == 0L) {
            fail(minecraft, "TORCH_EPOCH did not observe the expected rebuild lifecycle");
            return;
        }
        if (sidecar.configured()) {
            if (!sidecar.runtimeActive()
                    || sidecar.fallbackCount() != 0L
                    || snapshot.sidecarFallbackCount() != 0L) {
                fail(minecraft, "TORCH_EPOCH Sodium light sidecar entered its legacy fallback path");
                return;
            }
            if (sidecar.patchedPipelineCount() == 0) {
                fail(minecraft, "TORCH_EPOCH did not observe a patched Sodium light pipeline");
                return;
            }

            long acceptedGeometryBytes = snapshot.acceptedGeometryPayloadBytes();
            if (acceptedGeometryBytes <= 0L
                    || acceptedGeometryBytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L) {
                fail(minecraft, "TORCH_EPOCH accepted geometry payload was not compact Sodium vertex data");
                return;
            }
            long inPlaceGeometryBytes = snapshot.inPlaceGeometryRefreshBytes();
            long inPlaceOutputs = snapshot.inPlaceGeometryRefreshOutputs();
            long uniqueInPlaceSections = snapshot.uniqueInPlaceGeometryRefreshSections();
            long inPlaceMeshCommands = snapshot.inPlaceGeometryRefreshMeshCommands();
            long fullUploadGeometryBytes = acceptedGeometryBytes - snapshot.geometryBytesElided();
            if (!targetSectionProducedOutput) {
                fail(minecraft, "TORCH_EPOCH did not observe an output for the torch-containing section");
                return;
            }
            if (targetSectionRefreshedInPlace) {
                fail(minecraft, "TORCH_EPOCH reused the torch-containing section despite mesh layout change");
                return;
            }
            if (inPlaceOutputs <= 0L
                    || inPlaceOutputs >= snapshot.buildOutputCount()
                    || uniqueInPlaceSections <= 0L
                    || uniqueInPlaceSections > inPlaceOutputs
                    || inPlaceMeshCommands < inPlaceOutputs
                    || inPlaceGeometryBytes <= 0L
                    || inPlaceGeometryBytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L
                    || fullUploadGeometryBytes <= 0L
                    || fullUploadGeometryBytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L) {
                fail(minecraft, "TORCH_EPOCH did not split resident refreshes from full geometry uploads");
                return;
            }
            long expectedUploadedSidecarBytes = acceptedGeometryBytes
                    / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
                    * SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
            if (snapshot.sidecarProducedBytes() != expectedUploadedSidecarBytes
                    || snapshot.sidecarUploadedBytes() != expectedUploadedSidecarBytes
                    || snapshot.sidecarUploadCommands() == 0L) {
                fail(minecraft, "TORCH_EPOCH Sodium light sidecar upload did not exactly mirror accepted geometry bytes");
                return;
            }

            long liveGeometryBytes = sidecar.liveGeometryBytes();
            if (sidecar.companionCount() == 0
                    || liveGeometryBytes <= 0L
                    || liveGeometryBytes % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L
                    || sidecar.liveSidecarBytes() != liveGeometryBytes
                    / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
                    * SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE) {
                fail(minecraft, "TORCH_EPOCH Sodium light sidecar live storage ratio changed from 10 percent");
                return;
            }
        }

        if (lightPatch.configured()) {
            if (!lightPatch.runtimeActive()
                    || lightPatch.fallbackCount() != 0L
                    || snapshot.compactLightPatchFallbackCount() != 0L) {
                fail(minecraft, "TORCH_EPOCH compact Sodium light patch entered a fallback path");
                return;
            }
            if (!targetSectionProducedOutput) {
                fail(minecraft, "TORCH_EPOCH compact light patch did not observe the torch-containing section");
                return;
            }
            if (targetSectionCompactLightPatched) {
                fail(minecraft, "TORCH_EPOCH compact light patch reused the geometry-changing target section");
                return;
            }

            long acceptedGeometryBytes = snapshot.acceptedGeometryPayloadBytes();
            long inPlaceOutputs = snapshot.inPlaceGeometryRefreshOutputs();
            long uniqueInPlaceSections = snapshot.uniqueInPlaceGeometryRefreshSections();
            long inPlaceGeometryBytes = snapshot.inPlaceGeometryRefreshBytes();
            long inPlaceMeshCommands = snapshot.inPlaceGeometryRefreshMeshCommands();
            long compactOutputs = snapshot.compactLightPatchOutputs();
            long uniqueCompactSections = snapshot.uniqueCompactLightPatchSections();
            long geometryBytesElided = snapshot.geometryBytesElided();
            long geometryMeshCommandsElided = snapshot.geometryMeshCommandsElided();
            long nativeDispatches = snapshot.nativeLightPatchDispatches();
            long nativeMeshCommands = snapshot.nativeLightPatchMeshCommands();
            long fullSubmittedGeometryBytes = acceptedGeometryBytes - geometryBytesElided;
            if (compactOutputs <= 0L
                    || compactOutputs > inPlaceOutputs
                    || uniqueCompactSections <= 0L
                    || uniqueCompactSections > compactOutputs
                    || uniqueCompactSections > uniqueInPlaceSections
                    || geometryBytesElided <= 0L
                    || geometryBytesElided > inPlaceGeometryBytes
                    || geometryBytesElided % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L
                    || geometryMeshCommandsElided < compactOutputs
                    || geometryMeshCommandsElided > inPlaceMeshCommands
                    || nativeDispatches <= 0L
                    || nativeDispatches > nativeMeshCommands
                    || nativeMeshCommands != geometryMeshCommandsElided
                    || fullSubmittedGeometryBytes <= 0L
                    || fullSubmittedGeometryBytes
                    % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L) {
                fail(minecraft, "TORCH_EPOCH compact light-patch accounting was not an exact in-place subset");
                return;
            }

            long expectedUploadedSidecarBytes = acceptedGeometryBytes
                    / SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE
                    * SodiumLightSidecarPacking.SIDECAR_VERTEX_STRIDE;
            if (acceptedGeometryBytes <= 0L
                    || acceptedGeometryBytes
                    % SodiumLightSidecarPacking.GEOMETRY_VERTEX_STRIDE != 0L
                    || snapshot.sidecarProducedBytes() != expectedUploadedSidecarBytes
                    || snapshot.sidecarUploadedBytes() != expectedUploadedSidecarBytes) {
                fail(minecraft, "TORCH_EPOCH compact light patch did not preserve exact sidecar coverage");
                return;
            }
        }

        this.torchEpochFinished = true;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_END route={} position={},{},{} measured_frame={} rebuild_requests={} unique_requested_sections={} meshing_tasks={} unique_meshed_sections={} mesh_outputs={} unique_uploaded_sections={} accepted_mesh_payload_bytes={} in_place_outputs={} unique_in_place_sections={} in_place_geometry_bytes={} full_upload_geometry_bytes={} target_section_output={} target_section_in_place={} max_builder_queue_depth={} final_builder_queue_depth={} max_busy_workers={} final_busy_workers={} max_pending_results={} final_pending_results={} max_pending_age_ns={} telemetry_errors={} telemetry_overflow={}",
                this.route.routeId(),
                config.x(),
                config.y(),
                config.z(),
                this.measuredFrames,
                snapshot.rebuildRequestCount(),
                snapshot.uniqueRebuildRequestSections(),
                snapshot.rebuildTaskCount(),
                snapshot.uniqueRebuildTaskSections(),
                snapshot.buildOutputCount(),
                snapshot.uniqueBuildOutputSections(),
                snapshot.acceptedMeshPayloadBytes(),
                snapshot.inPlaceGeometryRefreshOutputs(),
                snapshot.uniqueInPlaceGeometryRefreshSections(),
                snapshot.inPlaceGeometryRefreshBytes(),
                submittedGeometryBytes,
                targetSectionProducedOutput,
                targetSectionRefreshedInPlace,
                snapshot.maximumBuilderQueueDepth(),
                snapshot.finalBuilderQueueDepth(),
                snapshot.maximumBusyWorkerCount(),
                snapshot.finalBusyWorkerCount(),
                snapshot.maximumPendingResultCount(),
                snapshot.finalPendingResultCount(),
                snapshot.maximumPendingAgeNanos(),
                snapshot.errorCount(),
                snapshot.overflowCount()
        );
    }

    private void selectMonitor(final Minecraft minecraft, final Window window) {
        PointerBuffer monitors = GLFW.glfwGetMonitors();
        if (monitors == null) {
            // GLFW may temporarily fail to materialize the monitor list on
            // macOS even though the primary display remains queryable. The
            // built-in Retina benchmark targets that primary display, so use
            // it as a safe fallback instead of failing a valid run.
            long primaryMonitor = GLFW.glfwGetPrimaryMonitor();
            if (primaryMonitor != 0L && selectMonitorCandidate(window, primaryMonitor)) {
                return;
            }
            if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
                fail(minecraft, "GLFW returned no monitors before timeout");
            }
            return;
        }

        for (int index = 0; index < monitors.limit(); index++) {
            if (selectMonitorCandidate(window, monitors.get(index))) {
                return;
            }
        }
        fail(minecraft, "requested external monitor or exact video mode was not found");
    }

    private boolean selectMonitorCandidate(final Window window, final long monitor) {
        String name = GLFW.glfwGetMonitorName(monitor);
        GLFWVidMode.Buffer modes = GLFW.glfwGetVideoModes(monitor);
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=MONITOR handle={} name={} modes={}",
                monitor,
                name,
                summarizeModes(modes)
        );
        if (name == null
                || !name.toLowerCase(Locale.ROOT).contains(this.monitorName.toLowerCase(Locale.ROOT))) {
            return false;
        }
        VideoMode best = bestExactMode(monitor, modes);
        if (best == null) {
            return false;
        }
        this.targetMonitor = monitor;
        this.targetVideoMode = best;
        transition(GLFW.glfwGetWindowMonitor(window.handle()) == 0L
                ? Stage.MOVE_WINDOWED
                : Stage.EXIT_FULLSCREEN);
        return true;
    }

    private void selectCurrentWindow(final Minecraft minecraft, final Window window) {
        int[] framebufferWidth = new int[1];
        int[] framebufferHeight = new int[1];
        GLFW.glfwGetFramebufferSize(window.handle(), framebufferWidth, framebufferHeight);
        boolean ready = framebufferWidth[0] > 0
                && framebufferHeight[0] > 0
                && window.getWidth() == framebufferWidth[0]
                && window.getHeight() == framebufferHeight[0]
                && this.survivalGuardApplied;
        if (!ready) {
            if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
                fail(minecraft, "current benchmark window did not become stable before timeout");
            }
            return;
        }
        this.targetMonitor = GLFW.glfwGetWindowMonitor(window.handle());
        this.expectedFramebufferWidth = framebufferWidth[0];
        this.expectedFramebufferHeight = framebufferHeight[0];
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=WINDOW_READY monitor=current framebuffer={}x{} window={}x{} screen={}x{}",
                framebufferWidth[0],
                framebufferHeight[0],
                window.getWidth(),
                window.getHeight(),
                window.getScreenWidth(),
                window.getScreenHeight()
        );
        transition(Stage.WAIT_ROUTE);
    }

    private VideoMode bestExactMode(final long monitor, final GLFWVidMode.Buffer modes) {
        if (modes == null) {
            return null;
        }
        float[] scaleX = new float[1];
        float[] scaleY = new float[1];
        GLFW.glfwGetMonitorContentScale(monitor, scaleX, scaleY);
        if (!(scaleX[0] > 0.0f) || !(scaleY[0] > 0.0f)) {
            return null;
        }
        VideoMode best = null;
        for (int index = 0; index < modes.limit(); index++) {
            GLFWVidMode mode = modes.get(index);
            int framebufferWidth = Math.round(mode.width() * scaleX[0]);
            int framebufferHeight = Math.round(mode.height() * scaleY[0]);
            boolean scaledMatch = framebufferWidth == this.targetWidth
                    && framebufferHeight == this.targetHeight;
            // GLFW reports the built-in Retina display modes in backing
            // pixels, while some external HiDPI displays expose logical mode
            // dimensions that still need the monitor content scale. Accept
            // either representation and verify the actual framebuffer after
            // entering fullscreen.
            boolean backingPixelMatch = mode.width() == this.targetWidth
                    && mode.height() == this.targetHeight;
            if (!scaledMatch && !backingPixelMatch) {
                continue;
            }
            VideoMode candidate = new VideoMode(
                    mode.width(),
                    mode.height(),
                    mode.redBits(),
                    mode.greenBits(),
                    mode.blueBits(),
                    mode.refreshRate()
            );
            if (best == null || candidate.getRefreshRate() > best.getRefreshRate()) {
                best = candidate;
            }
        }
        return best;
    }

    private static String summarizeModes(final GLFWVidMode.Buffer modes) {
        if (modes == null) {
            return "[]";
        }
        List<String> summaries = new ArrayList<>();
        for (int index = 0; index < modes.limit(); index++) {
            GLFWVidMode mode = modes.get(index);
            summaries.add(mode.width() + "x" + mode.height() + "@" + mode.refreshRate());
        }
        return summaries.toString();
    }

    private void exitFullscreen(final Minecraft minecraft, final Window window) {
        if (GLFW.glfwGetWindowMonitor(window.handle()) == 0L) {
            transition(Stage.MOVE_WINDOWED);
            return;
        }
        if (window.isFullscreen()) {
            window.toggleFullScreen();
            window.updateFullscreenIfChanged();
        } else {
            fail(minecraft, "GLFW window is fullscreen while Minecraft window state is windowed");
            return;
        }
        if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
            fail(minecraft, "timed out leaving fullscreen");
        }
    }

    private void moveWindow(final Minecraft minecraft, final Window window) {
        int[] x = new int[1];
        int[] y = new int[1];
        int[] width = new int[1];
        int[] height = new int[1];
        GLFW.glfwGetMonitorWorkarea(this.targetMonitor, x, y, width, height);
        if (width[0] <= 0 || height[0] <= 0) {
            fail(minecraft, "target monitor has an invalid work area");
            return;
        }
        GLFW.glfwRestoreWindow(window.handle());
        GLFW.glfwSetWindowSize(window.handle(), Math.min(WINDOWED_WIDTH, width[0]), Math.min(WINDOWED_HEIGHT, height[0]));
        GLFW.glfwSetWindowPos(window.handle(), x[0] + 32, y[0] + 32);
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=MOVE_WINDOW monitor={} workarea={},{} {}x{}",
                this.targetVideoMode,
                x[0],
                y[0],
                width[0],
                height[0]
        );
        transition(Stage.WAIT_MOVED);
    }

    private void waitForMove(final Minecraft minecraft, final Window window) {
        Monitor bestMonitor = window.findBestMonitor();
        if (bestMonitor != null && bestMonitor.monitor() == this.targetMonitor) {
            transition(Stage.ENTER_FULLSCREEN);
            return;
        }
        if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
            fail(minecraft, "timed out moving the window to the external monitor");
        }
    }

    private void enterFullscreen(final Minecraft minecraft, final Window window) {
        Monitor bestMonitor = window.findBestMonitor();
        if (bestMonitor == null || bestMonitor.monitor() != this.targetMonitor) {
            fail(minecraft, "external monitor stopped being the window's best monitor");
            return;
        }
        window.setPreferredFullscreenVideoMode(Optional.of(this.targetVideoMode));
        if (!window.isFullscreen()) {
            window.toggleFullScreen();
        }
        window.updateFullscreenIfChanged();
        transition(Stage.WAIT_FRAMEBUFFER);
    }

    private void waitForFramebuffer(final Minecraft minecraft, final Window window) {
        if (isTargetFramebuffer(window) && this.survivalGuardApplied) {
            int[] framebufferWidth = new int[1];
            int[] framebufferHeight = new int[1];
            GLFW.glfwGetFramebufferSize(window.handle(), framebufferWidth, framebufferHeight);
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=WINDOW_READY monitor={} video_mode={} framebuffer={}x{} window={}x{} screen={}x{}",
                    GLFW.glfwGetMonitorName(this.targetMonitor),
                    this.targetVideoMode,
                    framebufferWidth[0],
                    framebufferHeight[0],
                    window.getWidth(),
                    window.getHeight(),
                    window.getScreenWidth(),
                    window.getScreenHeight()
            );
            transition(Stage.WAIT_ROUTE);
            return;
        }
        if (this.stageFrames >= WINDOW_TRANSITION_TIMEOUT_FRAMES) {
            fail(minecraft, framebufferTimeoutDetails(window));
        }
    }

    private void waitForRoute(final Minecraft minecraft) {
        if (this.route == null) {
            fail(minecraft, "deterministic route configuration is unavailable");
            return;
        }
        if (GiTransportRuntime.isPopulationRequested() && GiTransportRuntime.isInvalid()) {
            fail(minecraft, "G4 transport admission failed: "
                    + GiTransportRuntime.invalidReason());
            return;
        }
        if (GiLiveRuntime.isRequested()
                && GiLiveRuntime.admissionState() == GiLiveRuntime.AdmissionState.INVALID) {
            fail(minecraft, "G6 live GI admission failed: " + GiLiveRuntime.invalidReason());
            return;
        }
        if (GiReceiverRuntime.isFrozenRequested()
                && (GiReceiverRuntime.admission().state()
                == GiReceiverRuntime.AdmissionState.INVALID
                || GiReceiverRuntime.admission().carrierSkipCount() != 0L)) {
            fail(minecraft, "G5 receiver admission failed: "
                    + (GiReceiverRuntime.admission().carrierSkipCount() != 0L
                    ? GiReceiverRuntime.admission().lastCarrierSkipReason()
                    : GiReceiverRuntime.admission().invalidReason()));
            return;
        }
        restoreBenchmarkWindowFocus(minecraft);
        String identityMismatch = clientIdentityMismatch(minecraft);
        if (identityMismatch != null) {
            fail(minecraft, identityMismatch);
            return;
        }
        if (!this.routeClientStateApplied) {
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            minecraft.setCameraEntity(minecraft.player);
            this.routeClientStateApplied = true;
        }
        if (!this.routeApplyRequested) {
            long token = submitRouteServerCheck(minecraft, true);
            if (token != 0L) {
                this.routeApplyToken = token;
                this.routeApplyRequested = true;
            }
        }
        if (this.routeApplyRequested
                && !this.routeApplyLogged
                && this.completedRouteServerToken >= this.routeApplyToken) {
            this.routeApplyLogged = true;
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=ROUTE_APPLY route={} fixture={} player={}/{} dimension={}",
                    this.route.routeId(),
                    this.route.fixtureId(),
                    this.route.playerName(),
                    this.route.playerUuid(),
                    this.route.dimensionName()
            );
        }
        if (shouldEmitServerTicksFrozenEvidence(
                this.routeApplyLogged,
                this.routeServerTicksFrozenLogged,
                this.routeServerTicksFrozen
        )) {
            this.routeServerTicksFrozenLogged = true;
            Metallum.LOGGER.info("METALLUM_BENCHMARK EVENT=SERVER_TICKS_FROZEN");
        }

        if (this.routeApplyLogged && this.routeServerCheckCountdown-- <= 0) {
            if (!this.routeServerTaskPending.get()) {
                submitRouteServerCheck(minecraft, false);
                this.routeServerCheckCountdown = ROUTE_SERVER_CHECK_INTERVAL_FRAMES;
            }
        }

        if (this.routeApplyLogged) {
            String l6SetupFailure = ensureL6DynamicShadowReady(minecraft);
            if (l6SetupFailure != null) {
                fail(minecraft, l6SetupFailure);
                return;
            }
            String g6MatrixSetupFailure = ensureG6MatrixReady(minecraft);
            if (g6MatrixSetupFailure != null) {
                fail(minecraft, g6MatrixSetupFailure);
                return;
            }
        }

        String clientMismatch = clientRouteMismatch(minecraft, true);
        if (this.stageFrames % 100 == 0) {
            Metallum.LOGGER.info("METALLUM_BENCHMARK_DEBUG waiting_for_route mismatch client={} server={} stable_frames={} target_stable_frames={}",
                    clientMismatch, this.routeServerMismatch, this.routeStableFrames, this.route.stableFrames());
        }
        if (this.routeApplyLogged
                && this.routeServerTicksFrozenLogged
                && this.routeServerMismatch == null
                && clientMismatch == null) {
            this.routeStableFrames++;
        } else {
            this.routeStableFrames = 0;
        }
        boolean rawG4SourceReady = GiTransportRuntime.isBenchmarkSourceReady();
        if (GiTransportRuntime.isPopulationRequested()) {
            if (!rawG4SourceReady) {
                this.g4SourceReceiptFrames = 0;
            } else if (this.g4SourceReceiptFrames < G4_SOURCE_RECEIPT_FRAMES) {
                this.g4SourceReceiptFrames++;
                if (this.g4SourceReceiptFrames == G4_SOURCE_RECEIPT_FRAMES) {
                    Metallum.LOGGER.info(
                            "METALLUM_BENCHMARK EVENT=GI_G3_STARTUP_RECEIPT "
                                    + "active_frames=24 drain_frames={} status=PASS",
                            G4_SOURCE_RECEIPT_FRAMES
                    );
                }
            }
        }
        boolean g4SourceReady = !GiTransportRuntime.isPopulationRequested()
                || this.g4SourceReceiptFrames >= G4_SOURCE_RECEIPT_FRAMES;
        if (GiTransportRuntime.isPopulationRequested()
                && !this.g4ModePreapplied
                && this.routeStableFrames >= this.route.stableFrames()
                && !GiSemanticController.global().hasActiveCandidates()
                && !this.routeServerTaskPending.get()) {
            this.sequence.getFirst().apply();
            this.g4ModePreapplied = true;
            this.routeStableFrames = 0;
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G4_MODE_FROZEN "
                            + "mode=OFF phase=PRE_ROUTE status=PASS"
            );
            return;
        }
        if (GiTransportRuntime.isPopulationRequested()
                && this.g4ModePreapplied
                && !GiTransportRuntime.hasSourcePreparationStarted()
                && this.routeStableFrames >= this.route.stableFrames()
                && !GiSemanticController.global().hasActiveCandidates()
                && !this.routeServerTaskPending.get()) {
            GiTransportRuntime.beginSourcePreparation();
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G3_PREPARE_BEGIN "
                            + "route={} stable_frames={} candidates=0 status=PASS",
                    this.route.routeId(),
                    this.routeStableFrames
            );
        }
        if (this.routeStableFrames >= this.route.stableFrames()
                && g4SourceReady
                && !this.routeServerTaskPending.get()) {
            String lightingAdmissionFailure = verifyLightingAdmission();
            if (lightingAdmissionFailure != null) {
                fail(minecraft, lightingAdmissionFailure);
                return;
            }
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=ROUTE_READY route={} stable_frames={} pose=[{},{},{};{},{}] max_fps={} resolved_gui_scale={} resource_packs={}",
                    this.route.routeId(),
                    this.route.stableFrames(),
                    this.route.x(),
                    this.route.y(),
                    this.route.z(),
                    this.route.yaw(),
                    this.route.pitch(),
                    this.expectedMaxFps,
                    minecraft.getWindow().getGuiScale(),
                    String.join(",", this.expectedResourcePackIds)
            );
            this.stage = Stage.RUNNING;
            this.segmentIndex = 0;
            startSegment();
            return;
        }
        if (this.stageFrames >= this.route.timeoutFrames()) {
            String reason = clientMismatch != null ? clientMismatch : this.routeServerMismatch;
            if (reason == null && !g4SourceReady) {
                reason = "G4 frozen G3 source did not settle before route timeout";
            }
            fail(
                    minecraft,
                    "deterministic route did not stabilize before timeout"
                            + (reason == null ? "" : ": " + reason)
            );
        }
    }

    /**
     * Makes the renderer's fail-closed behavior explicit to the benchmark.
     * Interactive fallback is safe; using it as a cheaper timing workload is not.
     */
    private String verifyLightingAdmission() {
        BenchmarkLightingAdmission.Decision decision = BenchmarkLightingAdmission.evaluate(
                this.expectedLightingModel,
                RendererConfig.lastLoadStatus(),
                AdvancedLightingRuntime.benchmarkStatus()
        );
        boolean passed = decision.valid();
        if (!passed || !this.advancedAdmissionLogged) {
            if (passed) {
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION expected={} schema={} defaults_used={} requested={} resolved={} l3={} l5={} l6={} status=PASS generation={} shader_epoch={} reason=none",
                        decision.required().name().toLowerCase(),
                        decision.parsedSchema(),
                        decision.defaultsUsed(),
                        decision.requested(),
                        decision.resolved(),
                        decision.l3Active(),
                        decision.l5Active(),
                        decision.l6Active(),
                        decision.generationId(),
                        decision.shaderAdmissionEpoch()
                );
            } else {
                Metallum.LOGGER.error(
                        "METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION expected={} schema={} defaults_used={} requested={} resolved={} l3={} l5={} l6={} status=FAIL generation={} shader_epoch={} reason={}",
                        decision.required().name().toLowerCase(),
                        decision.parsedSchema(),
                        decision.defaultsUsed(),
                        decision.requested(),
                        decision.resolved(),
                        decision.l3Active(),
                        decision.l5Active(),
                        decision.l6Active(),
                        decision.generationId(),
                        decision.shaderAdmissionEpoch(),
                        decision.reason()
                );
            }
        }
        this.advancedAdmissionLogged |= passed;
        return passed ? null : "benchmark lighting admission failed: " + decision.reason();
    }

    private void beginBoundaryCheck(
            final Minecraft minecraft,
            final RouteCheckEvent event
    ) {
        restoreL6DynamicShadowMotion(minecraft);
        this.boundaryCheckEvent = event;
        this.boundaryCheckFrames = 0;
        this.boundaryCheckToken = submitRouteServerCheck(minecraft, false);
        this.segmentPhase = event == RouteCheckEvent.MEASURE_START
                ? SegmentPhase.WAIT_MEASURE_START_CHECK
                : SegmentPhase.WAIT_MEASURE_END_CHECK;
    }

    private void pollBoundaryCheck(final Minecraft minecraft) {
        // The controller counts the current client tick before its render command buffer is
        // registered. Keep native telemetry in MEASURE through that submission, then close it
        // on the following tick so an N-frame benchmark produces exactly N measured frames.
        if (shouldCloseBenchmarkTiming(
                this.boundaryCheckEvent == RouteCheckEvent.MEASURE_END,
                this.boundaryCheckFrames
        )) {
            MetalGpuTiming.completeBenchmark(
                    this.segmentIndex,
                    this.sequence.get(this.segmentIndex).name()
            );
        }
        this.boundaryCheckFrames++;
        if (this.boundaryCheckToken == 0L && !this.routeServerTaskPending.get()) {
            this.boundaryCheckToken = submitRouteServerCheck(minecraft, false);
        }
        if (this.routeServerFailure != null) {
            fail(minecraft, this.routeServerFailure);
            return;
        }
        if (this.boundaryCheckToken == 0L
                || this.completedRouteServerToken < this.boundaryCheckToken
                || this.routeServerTaskPending.get()) {
            if (this.boundaryCheckFrames >= this.route.timeoutFrames()) {
                fail(minecraft, "timed out waiting for the route boundary check");
            }
            return;
        }
        if (this.routeServerMismatch != null) {
            fail(minecraft, "server route boundary mismatch: " + this.routeServerMismatch);
            return;
        }
        String clientMismatch = clientRouteMismatch(minecraft, false);
        if (clientMismatch != null) {
            fail(minecraft, "client route boundary mismatch: " + clientMismatch);
            return;
        }

        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=ROUTE_CHECK event={} route={} status=ready",
                this.boundaryCheckEvent,
                this.route.routeId()
        );
        if (this.boundaryCheckEvent == RouteCheckEvent.MEASURE_START) {
            String g6WarmupFailure = verifyGiLiveWarmupAdmission();
            if (g6WarmupFailure != null) {
                fail(minecraft, g6WarmupFailure);
                return;
            }
            String reflectionAdmissionFailure = verifyVertexReflectionAdmission();
            if (reflectionAdmissionFailure != null) {
                fail(minecraft, reflectionAdmissionFailure);
                return;
            }
            if (this.fiValidationRequired
                    && !this.fiGeneratedMeasurementStarted
                    && !beginFiGeneratedValidation(minecraft)) {
                return;
            }
            GiTransportRuntime.beginBenchmarkMeasurement();
            GiLiveRuntime.beginBenchmarkMeasurement();
            if (this.route.g6Matrix() != null) {
                this.g6MatrixMeasurementAccountedBytes =
                        GiLiveRuntime.finalSnapshot().accountedBytes();
            }
            MetalGpuTiming.beginBenchmarkMeasurement(
                    this.segmentIndex,
                    this.sequence.get(this.segmentIndex).name()
            );
            if (this.route.l6DynamicShadow() != null) {
                L6DynamicShadowBenchmarkTelemetry.begin();
            }
            com.metallum.client.lighting.AdvancedLightRegistry.global().resetBenchmarkTelemetry();
            if (this.captureScreenshots
                    && !this.fiValidationRequired
                    && !GiLiveRuntime.isRequested()) {
                Screenshot.grab(minecraft, false);
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED index={} mode={}",
                        this.segmentIndex + 1,
                        this.sequence.get(this.segmentIndex)
                );
            }
            this.measuredFrames = 0;
            this.segmentPhase = SegmentPhase.MEASURE;
            logSegmentEvent("MEASURE_START");
            return;
        }

        this.segmentIndex++;
        if (this.segmentIndex >= this.sequence.size()) {
            String g6FinalFailure = null;
            String g5FinalFailure = null;
            boolean completeLogged = false;
            if (GiLiveRuntime.isRequested()) {
                GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();
                long currentDeviceGeneration = GiLiveRuntime.deviceGeneration();
                if (this.route.g6Matrix() != null) {
                    if (!this.g6MatrixFinalValidated) {
                        g6FinalFailure = "G6 matrix final receipt was not validated";
                    } else {
                        Metallum.LOGGER.info(
                                "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_FINAL route={} "
                                        + "receipts=511 orbit_field_stable=true "
                                        + "queue_converged=true accounted_delta=0 "
                                        + "status=PASS contract=6",
                                this.route.routeId()
                        );
                    }
                }
                if (GiLiveRuntime.admissionState() != GiLiveRuntime.AdmissionState.READY
                        || !GiLiveRuntime.finalReceiptIsCurrent(
                        snapshot, currentDeviceGeneration
                )
                        || snapshot.readyMask() != 7
                        || snapshot.buildInFlight()
                        || snapshot.staleRejects() != 0L
                        || snapshot.rejectedCount() != 0L
                        || snapshot.accountedBytes() > 25_165_824L
                        || snapshot.fieldGeneration() <= 0L
                        || snapshot.sourceTick() < 0L
                        || snapshot.blockSamples() <= 0L
                        || snapshot.blockP95Submits() > 8
                        || snapshot.blockP99Submits() > 16
                        || !snapshot.blockSla()
                        || snapshot.schedulerPending() != 0
                        || snapshot.schedulerInFlight() != 0
                        || !snapshot.schedulerAlgebraExact()
                        || snapshot.schedulerQueued()
                        != snapshot.schedulerCompleted() + snapshot.schedulerDiscarded()
                        || snapshot.measurementStartAccountedBytes() <= 0L
                        || snapshot.measurementStartAccountedBytes()
                        != snapshot.accountedBytes()) {
                    g6FinalFailure = (g6FinalFailure == null ? "" : g6FinalFailure + "; ")
                            + (GiLiveRuntime.admissionState()
                            == GiLiveRuntime.AdmissionState.INVALID
                            ? GiLiveRuntime.invalidReason() + "; "
                            + giG6CensusSummary(snapshot, currentDeviceGeneration)
                            : "stable G6 ready/SLA/memory/current-receipt census was not clean; "
                            + giG6CensusSummary(snapshot, currentDeviceGeneration));
                } else {
                    Metallum.LOGGER.info(
                            "METALLUM_BENCHMARK EVENT=GI_G6_FINAL "
                                    + "state=READY device_generation={} "
                                    + "admission_emitted=true admission_device_generation={} "
                                    + "ready_mask={} build_in_flight=false "
                                    + "field_generation={} source_tick={} stale=0 rejected=0 "
                                    + "latest_terrain_device_generation={} "
                                    + "latest_terrain_submit={} latest_bind_status={} "
                                    + "latest_carrier_safe={} latest_frame_compatible={} "
                                    + "latest_ready_mask={} latest_exact_mask_nonzero={} "
                                    + "latest_field_generation={} latest_source_tick={} "
                                    + "combined_accounted_bytes={} cap_bytes=25165824 "
                                    + "block_samples={} block_p95_submits={} "
                                    + "block_p99_submits={} block_sla=true "
                                    + "static_samples={} static_p95_submits={} "
                                    + "static_p99_submits={} static_sla={} "
                                    + "scroll_samples={} scroll_p95_submits={} "
                                    + "scroll_p99_submits={} scroll_sla={} "
                                    + "reset_samples={} reset_p95_submits={} "
                                    + "reset_p99_submits={} reset_sla={} "
                                    + "queue_queued={} queue_completed={} queue_discarded={} "
                                    + "queue_pending=0 queue_in_flight=0 queue_algebra=true "
                                    + "measurement_start_bytes={} accounted_delta=0 "
                                    + "readback_bytes=0 "
                                    + "status=PASS contract=6",
                            currentDeviceGeneration,
                            snapshot.admissionDeviceGeneration(),
                            snapshot.readyMask(),
                            snapshot.fieldGeneration(),
                            snapshot.sourceTick(),
                            snapshot.latestTerrainDeviceGeneration(),
                            snapshot.latestTerrainSubmitIndex(),
                            snapshot.latestTerrainBindStatus(),
                            snapshot.latestTerrainCarrierSafe(),
                            snapshot.latestTerrainFrameCompatible(),
                            snapshot.latestTerrainReadyMask(),
                            snapshot.latestTerrainExactMaskNonzero(),
                            snapshot.latestTerrainFieldGeneration(),
                            snapshot.latestTerrainSourceTick(),
                            snapshot.accountedBytes(),
                            snapshot.blockSamples(),
                            snapshot.blockP95Submits(),
                            snapshot.blockP99Submits(),
                            snapshot.staticSourceSamples(),
                            snapshot.staticSourceP95Submits(),
                            snapshot.staticSourceP99Submits(),
                            snapshot.staticSourceSla(),
                            snapshot.scrollSamples(),
                            snapshot.scrollP95Submits(),
                            snapshot.scrollP99Submits(),
                            snapshot.scrollSla(),
                            snapshot.fullResetSamples(),
                            snapshot.fullResetP95Submits(),
                            snapshot.fullResetP99Submits(),
                            snapshot.fullResetSla(),
                            snapshot.schedulerQueued(),
                            snapshot.schedulerCompleted(),
                            snapshot.schedulerDiscarded(),
                            snapshot.measurementStartAccountedBytes()
                    );
                }
            }
            if (GiReceiverRuntime.isFrozenRequested()) {
                CompactPositionCarrierSafety.beginCarrierAwareDraw();
                try {
                    GiReceiverRuntime.FinalSnapshot snapshot = GiReceiverRuntime.admission()
                            .finalSnapshot(SodiumHdrSemantic.g5CarrierWriteCount());
                    boolean carrierSafe = CompactPositionCarrierSafety.isSafe();
                    if (snapshot.state() != GiReceiverRuntime.AdmissionState.READY
                            || snapshot.carrierSkipCount() != 0L
                            || !carrierSafe
                            || snapshot.successfulCarrierWrites() <= 0L
                            || snapshot.drawnG5CarrierSlices() <= 0L
                            || !snapshot.terrainDrawEncoded()
                            || (GiTransportRuntime.isBenchmarkActive()
                            && !snapshot.benchmarkReceiptEmitted())) {
                        g5FinalFailure = snapshot.carrierSkipCount() != 0L
                                ? snapshot.lastCarrierSkipReason()
                                : !carrierSafe
                                ? CompactPositionCarrierSafety.conflictReason()
                                : snapshot.successfulCarrierWrites() <= 0L
                                ? "no exact G5 position carrier was written"
                                : snapshot.drawnG5CarrierSlices() <= 0L
                                ? "no resident G5 carrier slice reached the pinned Sodium batch"
                                : !snapshot.terrainDrawEncoded()
                                ? "no pinned Sodium terrain draw was encoded"
                                : !snapshot.benchmarkReceiptEmitted()
                                ? "warmup binding receipt was not emitted"
                                : snapshot.invalidReason();
                    } else {
                        String arm = GiReceiverRuntime.arm().name().toLowerCase(Locale.ROOT);
                        String field = GiReceiverRuntime.arm()
                                == com.metallum.client.gi.GiRuntimeStages.ReceiverArm.FIELD
                                ? "g4" : "zero";
                        Metallum.LOGGER.info(
                                "METALLUM_BENCHMARK EVENT=GI_G5_FINAL "
                                        + "state=READY carrier_skips=0 g5_carrier_writes={} "
                                        + "drawn_g5_carrier_slices={} status=PASS "
                                        + "arm={} field={} contract=4",
                                snapshot.successfulCarrierWrites(),
                                snapshot.drawnG5CarrierSlices(),
                                arm,
                                field
                        );
                        Metallum.LOGGER.info(
                                "METALLUM_BENCHMARK EVENT=COMPLETE segments={} measured_frames={} "
                                        + "framebuffer={}x{}",
                                this.sequence.size(),
                                this.sequence.size() * this.measureFrames,
                                this.expectedFramebufferWidth,
                                this.expectedFramebufferHeight
                        );
                        completeLogged = true;
                    }
                } finally {
                    CompactPositionCarrierSafety.endCarrierAwareDraw();
                }
            }
            if (g6FinalFailure != null) {
                fail(minecraft, "G6 final live census failed: " + g6FinalFailure);
                return;
            }
            if (g5FinalFailure != null) {
                fail(minecraft, "G5 final carrier census failed: " + g5FinalFailure);
                return;
            }
            if (!completeLogged) {
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=COMPLETE segments={} measured_frames={} "
                                + "framebuffer={}x{}",
                        this.sequence.size(),
                        this.sequence.size() * this.measureFrames,
                        this.expectedFramebufferWidth,
                        this.expectedFramebufferHeight
                );
            }
            finish(minecraft);
            return;
        }
        startSegment();
    }

    private long submitRouteServerCheck(final Minecraft minecraft, final boolean apply) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            this.routeServerFailure = "benchmark route requires an integrated singleplayer server";
            return 0L;
        }
        if (!this.routeServerTaskPending.compareAndSet(false, true)) {
            return 0L;
        }
        long token = ++this.nextRouteServerToken;
        try {
            server.execute(() -> {
                try {
                    this.routeServerMismatch = applyAndVerifyServerRoute(server, apply);
                } catch (RuntimeException exception) {
                    this.routeServerFailure = "benchmark route server task failed: "
                            + exception.getClass().getSimpleName();
                    Metallum.LOGGER.error("METALLUM_BENCHMARK route server task failed", exception);
                } finally {
                    this.completedRouteServerToken = token;
                    this.routeServerTaskPending.set(false);
                }
            });
        } catch (RuntimeException exception) {
            this.routeServerTaskPending.set(false);
            this.routeServerFailure = "could not submit benchmark route server task: "
                    + exception.getClass().getSimpleName();
            Metallum.LOGGER.error("METALLUM_BENCHMARK route task submission failed", exception);
            return 0L;
        }
        return token;
    }

    private String applyAndVerifyServerRoute(
            final IntegratedServer server,
            final boolean apply
    ) {
        this.routeServerTicksFrozen = false;
        ServerLevel level = server.getLevel(this.route.dimension());
        ServerPlayer player = server.getPlayerList().getPlayer(this.route.playerUuid());
        Minecraft mc = Minecraft.getInstance();
        boolean clientDimensionMatches = mc.player != null && mc.player.level().dimension().equals(this.route.dimension());
        Metallum.LOGGER.info("METALLUM_BENCHMARK_DEBUG at=start level_exists={} player_exists={} server_dim={} server_paused={} ticks={} stageFrames={} server_frozen={} client_ready={}",
                level != null,
                player != null,
                player != null ? player.level().dimension().identifier().toString() : "null",
                server.isPaused(),
                server.getTickCount(),
                this.stageFrames,
                server.tickRateManager().isFrozen(),
                clientDimensionMatches
        );

        if (level == null) {
            Metallum.LOGGER.info("METALLUM_BENCHMARK_DEBUG early_return reason=level_null");
            return "benchmark dimension is unavailable";
        }
        if (player == null) {
            Metallum.LOGGER.info("METALLUM_BENCHMARK_DEBUG early_return reason=player_null");
            return "benchmark server player is unavailable";
        }
        if (!this.route.playerName().equals(player.getGameProfile().name())
                || !this.route.playerUuid().equals(player.getGameProfile().id())) {
            Metallum.LOGGER.info("METALLUM_BENCHMARK_DEBUG early_return reason=player_identity_mismatch expected_name={} actual_name={} expected_uuid={} actual_uuid={}",
                    this.route.playerName(), player.getGameProfile().name(), this.route.playerUuid(), player.getGameProfile().id());
            return "benchmark server player identity differs from the route";
        }

        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElse(null);
        int chunkX = Mth.floor(this.route.x()) >> 4;
        int chunkZ = Mth.floor(this.route.z()) >> 4;
        if (apply) {
            level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
            String netherPreparationFailure = prepareG6MatrixNetherChunk(server);
            if (netherPreparationFailure != null) return netherPreparationFailure;
            level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
            if (clock != null) {
                server.clockManager().setTotalTicks(clock, this.route.clockTicks());
                server.clockManager().setPaused(clock, true);
            }
            if ("rain".equals(this.route.weatherMode())) {
                server.setWeatherParameters(0, this.route.clearWeatherTicks(), true, false);
                level.setRainLevel(1.0f);
                if (mc.level != null) {
                    mc.execute(() -> mc.level.setRainLevel(1.0f));
                }
            } else {
                server.setWeatherParameters(this.route.clearWeatherTicks(), 0, false, false);
                level.setRainLevel(0.0f);
                if (mc.level != null) {
                    mc.execute(() -> mc.level.setRainLevel(0.0f));
                }
            }
            if (!player.level().dimension().equals(this.route.dimension())) {
                server.tickRateManager().setFrozen(false);
            }
            Metallum.LOGGER.info("METALLUM_BENCHMARK_DEBUG before_teleport player_dim={}", player.level().dimension().identifier().toString());
            boolean teleported = player.teleportTo(
                    level,
                    this.route.x(),
                    this.route.y(),
                    this.route.z(),
                    Set.<Relative>of(),
                    this.route.yaw(),
                    this.route.pitch(),
                    true
            );
            Metallum.LOGGER.info("METALLUM_BENCHMARK_DEBUG after_teleport player_dim={} teleported={}", player.level().dimension().identifier().toString(), teleported);
            if (!teleported) {
                return "server rejected the benchmark teleport";
            }
            player.setDeltaMovement(0.0, 0.0, 0.0);
        }
        if (player.level().dimension().equals(this.route.dimension()) && clientDimensionMatches) {
            if (!server.tickRateManager().isFrozen()) {
                server.tickRateManager().setFrozen(true);
            }
            this.routeServerTicksFrozen = server.tickRateManager().isFrozen();
            if (!samePose(player)) {
                player.teleportTo(
                        level,
                        this.route.x(),
                        this.route.y(),
                        this.route.z(),
                        Set.<Relative>of(),
                        this.route.yaw(),
                        this.route.pitch(),
                        true
                );
                player.setDeltaMovement(0.0, 0.0, 0.0);
            }
        }
        return serverRouteMismatch(server, level, player, clock, chunkX, chunkZ);
    }

    private String prepareG6MatrixNetherChunk(final IntegratedServer server) {
        G6MatrixConfig config = this.route.g6Matrix();
        if (config == null || this.g6MatrixNetherPrepared) return null;
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null) return "G6 matrix Nether dimension is unavailable during preparation";
        int chunkX = Mth.floor(config.netherX()) >> 4;
        int chunkZ = Mth.floor(config.netherZ()) >> 4;
        long chunkKey = ChunkPos.pack(chunkX, chunkZ);
        boolean alreadyForced = nether.getForceLoadedChunks().contains(chunkKey);
        if (!alreadyForced) {
            if (!nether.setChunkForced(chunkX, chunkZ, true)) {
                return "G6 matrix Nether target chunk could not be forced before measurement";
            }
            this.g6MatrixNetherChunkForcedByBenchmark = true;
        }
        this.g6MatrixNetherChunkX = chunkX;
        this.g6MatrixNetherChunkZ = chunkZ;
        nether.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
        if (nether.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return "G6 matrix Nether target chunk is not FULL after preparation";
        }
        this.g6MatrixNetherPrepared = true;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_NETHER_PREPARE route={} "
                        + "target={},{},{} chunk={},{} full=true forced=true status=PASS",
                this.route.routeId(), Mth.floor(config.netherX()), Mth.floor(config.netherY()),
                Mth.floor(config.netherZ()), chunkX, chunkZ
        );
        return null;
    }

    static boolean shouldEmitServerTicksFrozenEvidence(
            final boolean routeApplyLogged,
            final boolean evidenceLogged,
            final boolean serverTicksFrozen
    ) {
        return routeApplyLogged && !evidenceLogged && serverTicksFrozen;
    }

    static boolean isFrozenG4Sequence(final List<BenchmarkScalingMode> sequence) {
        return sequence != null && sequence.size() == 1
                && sequence.getFirst() == BenchmarkScalingMode.OFF;
    }

    private String serverRouteMismatch(
            final IntegratedServer server,
            final ServerLevel level,
            final ServerPlayer player,
            final Holder<WorldClock> clock,
            final int chunkX,
            final int chunkZ
    ) {
        if (!player.level().dimension().equals(this.route.dimension())) {
            if (this.stageFrames % 60 == 0) {
                Metallum.LOGGER.info("METALLUM_BENCHMARK SERVER_DIMENSION_MISMATCH expected={} found={}", this.route.dimension().identifier(), player.level().dimension().identifier());
            }
            return "server player is in a different dimension";
        }
        if (this.route.l6DynamicShadow() == null && !samePose(player)) {
            return "server player pose differs from the route (expected "
                    + routePose() + ", found " + entityPose(player) + ")";
        }
        if (!server.tickRateManager().isFrozen()) {
            return "server simulation ticks are not frozen";
        }
        if (level.getGameRules().get(GameRules.ADVANCE_TIME)
                || level.getGameRules().get(GameRules.ADVANCE_WEATHER)) {
            return "benchmark time or weather gamerule is advancing";
        }
        if (clock != null) {
            ClockState clockState = server.clockManager().packState().clocks().get(clock);
            if (clockState == null
                    || !clockState.paused()
                    || clockState.totalTicks() != this.route.clockTicks()) {
                return "server clock differs from the paused route clock";
            }
        }
        if ("rain".equals(this.route.weatherMode())) {
            if (!level.getWeatherData().isRaining()
                    || level.getWeatherData().isThundering()) {
                return "server weather is not frozen rain";
            }
        } else {
            if (level.getWeatherData().isRaining()
                    || level.getWeatherData().isThundering()
                    || level.getWeatherData().getClearWeatherTime() != this.route.clearWeatherTicks()
                    || level.getRainLevel(1.0f) != 0.0f
                    || level.getThunderLevel(1.0f) != 0.0f) {
                return "server weather is not frozen and clear";
            }
        }
        String workloadMismatch = serverWorkloadStateMismatch(level);
        if (workloadMismatch != null) {
            return workloadMismatch;
        }
        ChunkPos center = new ChunkPos(chunkX, chunkZ);
        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null
                || !level.areEntitiesActuallyLoadedAndTicking(center)) {
            return "server route chunk is not fully ticking";
        }
        return null;
    }

    private String clientRouteMismatch(
            final Minecraft minecraft,
            final boolean requireTerrainReady
    ) {
        String identityMismatch = clientIdentityMismatch(minecraft);
        if (identityMismatch != null) {
            return identityMismatch;
        }
        String runtimeMismatch = runtimeSettingsMismatch(minecraft);
        if (runtimeMismatch != null) {
            return runtimeMismatch;
        }
        if (minecraft.options.getCameraType() != CameraType.FIRST_PERSON
                || minecraft.getCameraEntity() != minecraft.player) {
            return "client camera is not fixed to first person";
        }
        if (!minecraft.player.level().dimension().equals(this.route.dimension())) {
            if (this.stageFrames % 60 == 0) {
                Metallum.LOGGER.info("METALLUM_BENCHMARK CLIENT_DIMENSION_MISMATCH expected={} found={}", this.route.dimension().identifier(), minecraft.player.level().dimension().identifier());
            }
            return "client player is in a different dimension";
        }
        if (!samePose(minecraft.player)) {
            return "client player pose differs from the route (expected "
                    + routePose() + ", found " + entityPose(minecraft.player) + ")";
        }
        if (minecraft.level.dimensionType().defaultClock().isPresent()
                && minecraft.level.getDefaultClockTime() != this.route.clockTicks()) {
            return "client clock differs from the route";
        }
        if (!minecraft.level.tickRateManager().isFrozen()) {
            return "client simulation ticks are not frozen";
        }
        if ("rain".equals(this.route.weatherMode())) {
            if (!minecraft.level.isRaining()) {
                return "client weather is not rain";
            }
        } else {
            if (minecraft.level.getRainLevel(1.0f) != 0.0f
                    || minecraft.level.getThunderLevel(1.0f) != 0.0f) {
                return "client weather is not clear";
            }
        }
        String workloadMismatch = clientWorkloadStateMismatch(minecraft);
        if (workloadMismatch != null) {
            return workloadMismatch;
        }
        if (requireTerrainReady) {
            int chunkX = Mth.floor(this.route.x()) >> 4;
            int chunkZ = Mth.floor(this.route.z()) >> 4;
            if (minecraft.level.getChunkSource().getChunk(
                    chunkX,
                    chunkZ,
                    ChunkStatus.FULL,
                    false
            ) == null) {
                return "client route chunk is not full";
            }
            if (!minecraft.levelRenderer.hasRenderedAllSections()) {
                return "client terrain is not fully rendered";
            }
        }
        return null;
    }

    private String serverWorkloadStateMismatch(final ServerLevel level) {
        G6MatrixConfig matrix = this.route.g6Matrix();
        if (matrix != null) {
            return level.getBlockState(matrix.lavaPosition()).is(Blocks.AIR)
                    ? null : "G6 matrix lava position differs from its air boundary state";
        }
        TorchEpochConfig config = this.route.torchEpoch();
        if (config == null) {
            return null;
        }
        BlockPos position = config.position();
        if (!level.getBlockState(position.below()).is(Blocks.GRASS_BLOCK)) {
            return "TORCH_EPOCH server support block differs from grass_block";
        }
        if (config.removesTorch() && this.torchEpochRemovedLogged) {
            return level.getBlockState(position).is(Blocks.AIR)
                    ? null
                    : "TORCH_TOGGLE server final block differs from air";
        }
        if (this.torchEpochAppliedLogged) {
            return level.getBlockState(position).is(Blocks.TORCH)
                    ? null
                    : "TORCH_EPOCH server block differs from torch";
        }
        return level.getBlockState(position).is(Blocks.AIR)
                ? null
                : "TORCH_EPOCH server initial block differs from air";
    }

    private String clientWorkloadStateMismatch(final Minecraft minecraft) {
        if (this.route.g6Matrix() != null) {
            if (!this.g6MatrixReady || !minecraft.player.getMainHandItem().is(Items.TORCH)
                    || this.l6ProbeEntities.size() != 1) {
                return "G6 matrix did not retain its camera-independent sources";
            }
            ItemEntity probe = this.l6ProbeEntities.getFirst();
            if (probe.isRemoved() || probe.level() != minecraft.level
                    || !probe.getItem().is(Items.TORCH)) {
                return "G6 matrix entity source is absent from the current route level";
            }
            return null;
        }
        L6DynamicShadowConfig l6 = this.route.l6DynamicShadow();
        if (l6 != null) {
            if (!this.l6DynamicReady || !minecraft.player.getMainHandItem().is(Items.TORCH)) {
                return "L6 dynamic route did not retain the held torch";
            }
            if (this.l6ProbeEntities.size() != l6.probeCount()) {
                return "L6 dynamic route probe count differs from the route";
            }
            for (ItemEntity probe : this.l6ProbeEntities) {
                if (probe.isRemoved() || probe.level() != minecraft.level
                        || !probe.getItem().is(Items.TORCH)) {
                    return "L6 dynamic route probe state differs from the route";
                }
            }
            return null;
        }
        TorchEpochConfig config = this.route.torchEpoch();
        if (config == null) {
            return null;
        }
        BlockPos position = config.position();
        if (!minecraft.level.getBlockState(position.below()).is(Blocks.GRASS_BLOCK)) {
            return "TORCH_EPOCH client support block differs from grass_block";
        }
        if (config.removesTorch() && this.torchEpochRemovedLogged) {
            return minecraft.level.getBlockState(position).is(Blocks.AIR)
                    ? null
                    : "TORCH_TOGGLE client final block differs from air";
        }
        if (this.torchEpochAppliedLogged) {
            return minecraft.level.getBlockState(position).is(Blocks.TORCH)
                    ? null
                    : "TORCH_EPOCH client block differs from torch";
        }
        return minecraft.level.getBlockState(position).is(Blocks.AIR)
                ? null
                : "TORCH_EPOCH client initial block differs from air";
    }

    private String ensureL6DynamicShadowReady(final Minecraft minecraft) {
        L6DynamicShadowConfig config = this.route.l6DynamicShadow();
        if (config == null || this.l6DynamicReady) {
            return null;
        }
        if (minecraft.player == null || minecraft.level == null) {
            return "L6 dynamic route requires a client player and level";
        }
        try {
            this.l6OriginalMainHand = minecraft.player.getMainHandItem().copy();
            this.l6OriginalMainHandCaptured = true;
            minecraft.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
            for (int index = 0; index < config.probeCount(); index++) {
                ItemEntity probe = new ItemEntity(
                        minecraft.level,
                        config.probeOriginX(),
                        config.probeOriginY(),
                        config.probeOriginZ(),
                        new ItemStack(Items.TORCH),
                        0.0,
                        0.0,
                        0.0
                );
                probe.setUUID(UUID.nameUUIDFromBytes(
                        ("metallum-l6-probe-" + index).getBytes(StandardCharsets.UTF_8)
                ));
                // ClientLevel.addEntity consumes a network-assigned non-zero ID. Benchmark-only
                // probes never cross the server boundary, so reserve a deterministic high range
                // and fail if another client entity unexpectedly occupies it.
                int probeId = 2_000_000_000 - index;
                if (minecraft.level.getEntity(probeId) != null
                        || minecraft.level.getEntity(probe.getUUID()) != null) {
                    throw new IllegalStateException("L6 benchmark probe identity collision");
                }
                probe.setId(probeId);
                probe.setNoGravity(true);
                probe.setDeltaMovement(0.0, 0.0, 0.0);
                minecraft.level.addEntity(probe);
                this.l6ProbeEntities.add(probe);
            }
            this.l6DynamicReady = true;
            this.l6MotionFrame = 0;
            applyL6DynamicShadowMotion(minecraft, 0);
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=L6_DYNAMIC_READY route={} held=minecraft:torch probes={} orbit_period={} probe_period={}",
                    this.route.routeId(),
                    config.probeCount(),
                    config.orbitPeriodFrames(),
                    config.probePeriodFrames()
            );
            return null;
        } catch (RuntimeException exception) {
            clearL6DynamicShadowRoute(minecraft);
            Metallum.LOGGER.error("L6 dynamic benchmark-route setup failed", exception);
            return "L6 dynamic route setup failed: " + exception.getClass().getSimpleName();
        }
    }

    private String ensureG6MatrixReady(final Minecraft minecraft) {
        G6MatrixConfig config = this.route.g6Matrix();
        if (config == null || this.g6MatrixReady) {
            return null;
        }
        if (minecraft.player == null || minecraft.level == null) {
            return "G6 matrix requires a client player and level";
        }
        try {
            this.l6OriginalMainHand = minecraft.player.getMainHandItem().copy();
            this.l6OriginalMainHandCaptured = true;
            minecraft.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
            ItemEntity probe = new ItemEntity(
                    minecraft.level,
                    config.entityX(), config.entityY(), config.entityZ(),
                    new ItemStack(Items.TORCH), 0.0, 0.0, 0.0
            );
            probe.setUUID(UUID.nameUUIDFromBytes(
                    "metallum-g6-matrix-probe".getBytes(StandardCharsets.UTF_8)
            ));
            int probeId = 1_999_999_900;
            if (minecraft.level.getEntity(probeId) != null
                    || minecraft.level.getEntity(probe.getUUID()) != null) {
                throw new IllegalStateException("G6 matrix probe identity collision");
            }
            probe.setId(probeId);
            probe.setNoGravity(true);
            probe.setDeltaMovement(0.0, 0.0, 0.0);
            minecraft.level.addEntity(probe);
            this.l6ProbeEntities.add(probe);
            this.g6MatrixReady = true;
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_READY route={} "
                            + "held=minecraft:torch entity=minecraft:torch entity_id={} status=PASS",
                    this.route.routeId(), probeId
            );
            return null;
        } catch (RuntimeException exception) {
            clearL6DynamicShadowRoute(minecraft);
            this.g6MatrixReady = false;
            Metallum.LOGGER.error("G6 matrix source setup failed", exception);
            return "G6 matrix source setup failed: " + exception.getClass().getSimpleName();
        }
    }

    private void driveL6DynamicShadow(final Minecraft minecraft) {
        if (!this.l6DynamicReady || this.route.l6DynamicShadow() == null) {
            return;
        }
        this.l6MotionFrame++;
        applyL6DynamicShadowMotion(minecraft, this.l6MotionFrame);
    }

    private void driveG6Matrix(final Minecraft minecraft) {
        G6MatrixConfig config = this.route.g6Matrix();
        if (config == null || !this.g6MatrixReady) return;
        if (this.g6MatrixServerFailure != null) {
            fail(minecraft, this.g6MatrixServerFailure);
            return;
        }
        pollG6MatrixServerAction(minecraft);
        pollG6MatrixResourceReload(minecraft);
        if (this.stage != Stage.RUNNING) return;
        if (this.g6MatrixAwaitingRecovery != null && this.measuredFrames % 10 == 0) {
            pollG6MatrixRecovery(minecraft);
            if (this.stage != Stage.RUNNING) return;
        }

        int frame = this.measuredFrames;
        if (frame == config.orbitStartFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "ORBIT_BEGIN"
            );
            if (snapshot == null) return;
            this.g6MatrixOrbitFieldGeneration = snapshot.fieldGeneration();
            this.g6MatrixOrbitBlockSamples = snapshot.blockSamples();
            this.g6MatrixOrbitStaticSourceSamples = snapshot.staticSourceSamples();
            this.g6MatrixOrbitScrollSamples = snapshot.scrollSamples();
            this.g6MatrixOrbitFullResetSamples = snapshot.fullResetSamples();
            this.g6MatrixOrbitStarted = true;
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route={} "
                            + "action=ORBIT_BEGIN measured_frame={} field_generation={} "
                            + "block_samples={} static_source_samples={} scroll_samples={} "
                            + "full_reset_samples={} status=PASS",
                    this.route.routeId(), frame, this.g6MatrixOrbitFieldGeneration,
                    this.g6MatrixOrbitBlockSamples, this.g6MatrixOrbitStaticSourceSamples,
                    this.g6MatrixOrbitScrollSamples, this.g6MatrixOrbitFullResetSamples
            );
        }
        if (this.g6MatrixOrbitStarted && frame >= config.orbitStartFrame()
                && frame < config.orbitEndFrame() && minecraft.player != null) {
            double phase = Math.TAU * (frame - config.orbitStartFrame())
                    / config.orbitPeriodFrames();
            float yaw = this.route.yaw()
                    + config.orbitYawAmplitudeDegrees() * (float) Math.sin(phase);
            float pitch = Mth.clamp(
                    this.route.pitch()
                            + config.orbitPitchAmplitudeDegrees() * (float) Math.sin(phase),
                    -90.0F, 90.0F
            );
            minecraft.player.setYRot(yaw);
            minecraft.player.setXRot(pitch);
            minecraft.player.yRotO = yaw;
            minecraft.player.xRotO = pitch;
        }
        if (frame == config.orbitEndFrame()) {
            restoreG6MatrixPose(minecraft);
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "ORBIT_END"
            );
            if (snapshot == null) return;
            if (!this.g6MatrixOrbitStarted || !g6MatrixOrbitStable(
                    this.g6MatrixOrbitFieldGeneration, snapshot.fieldGeneration(),
                    this.g6MatrixOrbitBlockSamples, snapshot.blockSamples(),
                    this.g6MatrixOrbitStaticSourceSamples, snapshot.staticSourceSamples(),
                    this.g6MatrixOrbitScrollSamples, snapshot.scrollSamples(),
                    this.g6MatrixOrbitFullResetSamples, snapshot.fullResetSamples()
            )) {
                fail(minecraft, "G6 matrix camera orbit changed stationary-source field/latency identity");
                return;
            }
            this.g6MatrixReceiptMask |= G6_MATRIX_RECEIPT_ORBIT;
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route={} "
                            + "action=ORBIT_END measured_frame={} baseline_generation={} "
                            + "field_generation={} block_samples={} static_source_samples={} "
                            + "scroll_samples={} full_reset_samples={} status=PASS",
                    this.route.routeId(), frame, this.g6MatrixOrbitFieldGeneration,
                    snapshot.fieldGeneration(), snapshot.blockSamples(),
                    snapshot.staticSourceSamples(), snapshot.scrollSamples(),
                    snapshot.fullResetSamples()
            );
        }

        if (frame == config.lavaApplyFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "LAVA_APPLY"
            );
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.LAVA_APPLY, frame, snapshot
            );
        } else if (frame == config.lavaRemoveFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "LAVA_REMOVE"
            );
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.LAVA_REMOVE, frame, snapshot
            );
        } else if (frame == config.chunkReloadFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "CHUNK_RELOAD"
            );
            if (snapshot == null) return;
            minecraft.levelExtractor.allChanged();
            this.g6MatrixChunkReloadRequested = true;
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route={} "
                            + "action=CHUNK_RELOAD requested_frame={} measured_frame={} status=PASS",
                    this.route.routeId(), frame, frame
            );
            beginG6MatrixTerrainRecovery(
                    "CHUNK_RELOAD", frame, snapshot, G6_MATRIX_RECEIPT_CHUNK_RELOAD
            );
        } else if (frame == config.resourceReloadFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "RESOURCE_RELOAD"
            );
            if (snapshot == null) return;
            this.g6MatrixResourceReloadRequested = true;
            this.g6MatrixResourceReload = minecraft.reloadResourcePacks();
            beginG6MatrixRecovery(
                    "RESOURCE_RELOAD", frame, snapshot, G6_MATRIX_LATENCY_FULL_RESET,
                    G6_MATRIX_RECEIPT_RESOURCE_RELOAD, false
            );
            captureG6MatrixTerrainRecoveryBaseline(snapshot);
        } else if (frame == config.dayFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(minecraft, "DAY");
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.DAY, frame, snapshot
            );
        } else if (frame == config.nightFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(minecraft, "NIGHT");
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.NIGHT, frame, snapshot
            );
        } else if (frame == config.rainFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(minecraft, "RAIN");
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.RAIN, frame, snapshot
            );
        } else if (frame == config.clearFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(minecraft, "CLEAR");
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.CLEAR, frame, snapshot
            );
        }

        int streamEndFrame = config.streamStartFrame()
                + (config.streamOffsets().length - 1) * config.streamStepFrames();
        if (frame >= config.streamStartFrame() && frame <= streamEndFrame
                && (frame - config.streamStartFrame()) % config.streamStepFrames() == 0) {
            int index = (frame - config.streamStartFrame()) / config.streamStepFrames();
            if (index == 0) {
                this.g6MatrixStreamIndex = -1;
            }
            GiLiveRuntime.FinalSnapshot snapshot = index == 0
                    ? requireCleanG6MatrixSnapshot(
                    minecraft, g6MatrixStreamRecoveryName(index))
                    : requireNearReadyG6MatrixSnapshot(
                    minecraft, g6MatrixStreamRecoveryName(index));
            if (snapshot == null) return;
            this.g6MatrixStreamIndex = index;
            this.g6MatrixStreamTargetOffset = config.streamOffsets()[index];
            this.g6MatrixStreamTargetYOffset = config.streamYOffsets()[index];
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.STREAM, frame, snapshot
            );
        } else if (frame == config.teleportFrame()) {
            // STEP_7's exact-near receipt must already be closed, but unfinished compatible
            // mid/far scroll work is deliberately allowed here: the production coordinator
            // must defer and merge this more severe FULL_RESET instead of exposing stale GI.
            GiLiveRuntime.FinalSnapshot snapshot = requireNearReadyG6MatrixSnapshot(
                    minecraft, "TELEPORT_OUT"
            );
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.TELEPORT, frame, snapshot
            );
        } else if (frame == config.teleportReturnFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "TELEPORT_RETURN"
            );
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.TELEPORT_RETURN, frame, snapshot
            );
        } else if (frame == config.netherEnterFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "NETHER_ENTER"
            );
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.NETHER_ENTER, frame, snapshot
            );
        } else if (frame == config.netherReturnFrame()) {
            GiLiveRuntime.FinalSnapshot snapshot = requireCleanG6MatrixSnapshot(
                    minecraft, "NETHER_RETURN"
            );
            if (snapshot == null) return;
            requestG6MatrixServerAction(
                    minecraft, G6MatrixServerAction.NETHER_RETURN, frame, snapshot
            );
        }
    }

    private GiLiveRuntime.FinalSnapshot requireCleanG6MatrixSnapshot(
            final Minecraft minecraft, final String action
    ) {
        if (this.g6MatrixServerTaskPending.get()
                || this.g6MatrixAwaitingClientAction != null
                || this.g6MatrixAwaitingRecovery != null
                || (this.g6MatrixResourceReload != null
                && !this.g6MatrixResourceReload.isDone())) {
            logG6MatrixPendingRecovery(minecraft, action);
            fail(minecraft, "G6 matrix action " + action + " overlapped unfinished work");
            return null;
        }
        GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();
        if (GiLiveRuntime.admissionState() != GiLiveRuntime.AdmissionState.READY
                || !GiLiveRuntime.finalReceiptIsCurrent(snapshot, GiLiveRuntime.deviceGeneration())
                || snapshot.readyMask() != 7 || snapshot.buildInFlight()
                || snapshot.staleRejects() != 0L || snapshot.rejectedCount() != 0L) {
            fail(minecraft, "G6 matrix action " + action + " lacks a clean current field");
            return null;
        }
        return snapshot;
    }

    /** Rapid stream steps may supersede compatible mid/far work after exact near recovery. */
    private GiLiveRuntime.FinalSnapshot requireNearReadyG6MatrixSnapshot(
            final Minecraft minecraft, final String action
    ) {
        if (this.g6MatrixServerTaskPending.get()
                || this.g6MatrixAwaitingClientAction != null
                || this.g6MatrixAwaitingRecovery != null
                || (this.g6MatrixResourceReload != null
                && !this.g6MatrixResourceReload.isDone())) {
            logG6MatrixPendingRecovery(minecraft, action);
            fail(minecraft, "G6 matrix action " + action + " overlapped near recovery");
            return null;
        }
        GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();
        if (GiLiveRuntime.admissionState() != GiLiveRuntime.AdmissionState.READY
                || !GiLiveRuntime.finalReceiptIsCurrent(
                snapshot, GiLiveRuntime.deviceGeneration())
                || !g6MatrixRecoveryCoverageReady(
                snapshot.readyMask(), snapshot.buildInFlight(), true)
                || snapshot.staleRejects() != 0L || snapshot.rejectedCount() != 0L) {
            fail(minecraft, "G6 matrix action " + action
                    + " lacks current exact near coverage");
            return null;
        }
        return snapshot;
    }

    private void logG6MatrixPendingRecovery(
            final Minecraft minecraft, final String nextAction
    ) {
        GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();
        long sampleAfter = g6MatrixLatencySamples(
                snapshot, this.g6MatrixRecoveryLatencyClass
        );
        Metallum.LOGGER.error(
                "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_PENDING next_action={} "
                        + "measured_frame={} server_task_pending={} awaiting_client={} "
                        + "awaiting_recovery={} resource_reload_pending={} terrain_queue_empty={} "
                        + "deadline={} admission={} final_current={} device_generation={} "
                        + "receipt_device={} ready_mask={} build_in_flight={} stale={} rejected={} "
                        + "field_generation={} source_tick={} latency_class={} sample_before={} "
                        + "sample_after={} terrain_required={} terrain_submit_before={} "
                        + "terrain_submit_after={} terrain_device_before={} terrain_device_after={} "
                        + "terrain_field_before={} terrain_field_after={} terrain_source_before={} "
                        + "terrain_source_after={} bind_status={} carrier_safe={} "
                        + "frame_compatible={} exact_mask_nonzero={}",
                nextAction, this.measuredFrames, this.g6MatrixServerTaskPending.get(),
                this.g6MatrixAwaitingClientAction, this.g6MatrixAwaitingRecovery,
                this.g6MatrixResourceReload != null && !this.g6MatrixResourceReload.isDone(),
                minecraft.levelRenderer.hasRenderedAllSections(), this.g6MatrixRecoveryDeadline,
                GiLiveRuntime.admissionState(),
                GiLiveRuntime.finalReceiptIsCurrent(snapshot, GiLiveRuntime.deviceGeneration()),
                GiLiveRuntime.deviceGeneration(), snapshot.deviceGeneration(), snapshot.readyMask(),
                snapshot.buildInFlight(), snapshot.staleRejects(), snapshot.rejectedCount(),
                snapshot.fieldGeneration(), snapshot.sourceTick(),
                g6MatrixLatencyClassName(this.g6MatrixRecoveryLatencyClass),
                this.g6MatrixRecoverySampleCount, sampleAfter,
                this.g6MatrixRecoveryRequiresTerrainBinding,
                this.g6MatrixRecoveryTerrainSubmit, snapshot.latestTerrainSubmitIndex(),
                this.g6MatrixRecoveryTerrainDeviceGeneration,
                snapshot.latestTerrainDeviceGeneration(),
                this.g6MatrixRecoveryTerrainFieldGeneration,
                snapshot.latestTerrainFieldGeneration(),
                this.g6MatrixRecoveryTerrainSourceTick,
                snapshot.latestTerrainSourceTick(), snapshot.latestTerrainBindStatus(),
                snapshot.latestTerrainCarrierSafe(), snapshot.latestTerrainFrameCompatible(),
                snapshot.latestTerrainExactMaskNonzero()
        );
        Metallum.LOGGER.error(
                "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_PENDING_STATE {}",
                MetalDevice.getInstance().giLiveDebugSummary()
        );
    }

    private void requestG6MatrixServerAction(
            final Minecraft minecraft,
            final G6MatrixServerAction action,
            final int requestedFrame,
            final GiLiveRuntime.FinalSnapshot baseline
    ) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || this.g6MatrixAwaitingClientAction != null
                || !this.g6MatrixServerTaskPending.compareAndSet(false, true)) {
            fail(minecraft, "G6 matrix could not serialize server action " + action);
            return;
        }
        this.g6MatrixAwaitingClientAction = action;
        this.g6MatrixActiveRequestedFrame = requestedFrame;
        this.g6MatrixCompletedServerAction = null;
        beginG6MatrixRecovery(
                g6MatrixRecoveryName(action), requestedFrame, baseline,
                g6MatrixLatencyClass(action), g6MatrixReceiptBit(action), false,
                g6MatrixAllCascadeStabilizationTimeoutFrames(
                        action == G6MatrixServerAction.TELEPORT,
                        action == G6MatrixServerAction.NETHER_ENTER
                                || action == G6MatrixServerAction.NETHER_RETURN
                )
        );
        try {
            server.execute(() -> {
                try {
                    this.g6MatrixServerFailure = applyG6MatrixServerAction(server, action);
                    this.g6MatrixCompletedServerAction = action;
                } catch (RuntimeException exception) {
                    this.g6MatrixServerFailure = "G6 matrix server action " + action
                            + " failed: " + exception.getClass().getSimpleName();
                    Metallum.LOGGER.error("G6 matrix server action failed: " + action, exception);
                } finally {
                    this.g6MatrixServerTaskPending.set(false);
                }
            });
        } catch (RuntimeException exception) {
            this.g6MatrixServerTaskPending.set(false);
            this.g6MatrixAwaitingClientAction = null;
            fail(minecraft, "G6 matrix could not submit server action " + action);
        }
    }

    private String applyG6MatrixServerAction(
            final IntegratedServer server, final G6MatrixServerAction action
    ) {
        G6MatrixConfig config = this.route.g6Matrix();
        ServerPlayer player = server.getPlayerList().getPlayer(this.route.playerUuid());
        ServerLevel overworld = server.getLevel(this.route.dimension());
        if (config == null || player == null || overworld == null) {
            return "G6 matrix server state is unavailable";
        }
        switch (action) {
            case LAVA_APPLY -> {
                if (!overworld.getBlockState(config.lavaPosition()).is(Blocks.AIR)
                        || !overworld.setBlock(
                        config.lavaPosition(), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL
                )) return "G6 matrix lava placement was rejected";
            }
            case LAVA_REMOVE -> {
                if (!overworld.getBlockState(config.lavaPosition()).is(Blocks.LAVA)
                        || !overworld.setBlock(
                        config.lavaPosition(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL
                )) return "G6 matrix lava removal was rejected";
            }
            case DAY -> setG6MatrixClock(server, overworld, config.dayTicks());
            case NIGHT -> setG6MatrixClock(server, overworld, config.nightTicks());
            case RAIN -> setG6MatrixWeather(server, overworld, true);
            // Keep each exact-attribution matrix event to one physical source mutation.
            // DAY/NIGHT cover the clock; NETHER_RETURN restores the canonical route clock.
            case CLEAR -> setG6MatrixWeather(server, overworld, false);
            case STREAM -> {
                String failure = teleportG6MatrixPlayer(
                        server, player, overworld,
                        this.route.x() + this.g6MatrixStreamTargetOffset,
                        this.route.y() + this.g6MatrixStreamTargetYOffset,
                        this.route.z(), this.route.yaw(), this.route.pitch(), true
                );
                if (failure != null) return failure;
            }
            case TELEPORT -> {
                String failure = teleportG6MatrixPlayer(
                        server, player, overworld,
                        this.route.x() + config.teleportOffsetX(),
                        this.route.y() + config.teleportOffsetY(),
                        this.route.z() + config.teleportOffsetZ(),
                        this.route.yaw(), this.route.pitch(), true
                );
                if (failure != null) return failure;
            }
            case TELEPORT_RETURN -> {
                String failure = teleportG6MatrixPlayer(
                        server, player, overworld,
                        this.route.x(), this.route.y(), this.route.z(),
                        this.route.yaw(), this.route.pitch(), true
                );
                if (failure != null) return failure;
            }
            case NETHER_ENTER -> {
                ServerLevel nether = server.getLevel(Level.NETHER);
                if (nether == null) return "G6 matrix Nether dimension is unavailable";
                if (!this.g6MatrixNetherPrepared
                        || nether.getChunkSource().getChunkNow(
                        this.g6MatrixNetherChunkX, this.g6MatrixNetherChunkZ
                ) == null) {
                    return "G6 matrix Nether target chunk is not prepared and current";
                }
                String failure = teleportG6MatrixPlayer(
                        server, player, nether,
                        config.netherX(), config.netherY(), config.netherZ(),
                        0.0F, 15.0F, false
                );
                if (failure != null) return failure;
            }
            case NETHER_RETURN -> {
                setG6MatrixWeather(server, overworld, false);
                setG6MatrixClock(server, overworld, this.route.clockTicks());
                String failure = teleportG6MatrixPlayer(
                        server, player, overworld,
                        this.route.x(), this.route.y(), this.route.z(),
                        this.route.yaw(), this.route.pitch(), true
                );
                if (failure != null) return failure;
                releaseG6MatrixNetherChunk(server);
            }
        }
        return null;
    }

    private void setG6MatrixClock(
            final IntegratedServer server, final ServerLevel level, final long ticks
    ) {
        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElse(null);
        if (clock == null) throw new IllegalStateException("G6 matrix clock is unavailable");
        server.clockManager().setTotalTicks(clock, ticks);
        server.clockManager().setPaused(clock, true);
    }

    private static void setG6MatrixWeather(
            final IntegratedServer server, final ServerLevel level, final boolean rain
    ) {
        server.setWeatherParameters(rain ? 0 : 6000, rain ? 6000 : 0, rain, false);
        level.setRainLevel(rain ? 1.0F : 0.0F);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.level != null
                    && minecraft.level.dimension().equals(level.dimension())) {
                minecraft.level.setRainLevel(rain ? 1.0F : 0.0F);
            }
        });
    }

    private static String teleportG6MatrixPlayer(
            final IntegratedServer server,
            final ServerPlayer player,
            final ServerLevel target,
            final double x,
            final double y,
            final double z,
            final float yaw,
            final float pitch,
            final boolean allowChunkLoad
    ) {
        int chunkX = Mth.floor(x) >> 4;
        int chunkZ = Mth.floor(z) >> 4;
        if (allowChunkLoad) {
            target.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
        } else if (target.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            return "G6 matrix server teleport target chunk is not already loaded";
        }
        boolean frozen = server.tickRateManager().isFrozen();
        try {
            if (!player.level().dimension().equals(target.dimension()) && frozen) {
                server.tickRateManager().setFrozen(false);
            }
            boolean teleported = player.teleportTo(
                    target, x, y, z, Set.<Relative>of(), yaw, pitch, true
            );
            player.setDeltaMovement(0.0, 0.0, 0.0);
            return teleported ? null : "G6 matrix server teleport was rejected";
        } finally {
            server.tickRateManager().setFrozen(frozen);
        }
    }

    private void releaseG6MatrixNetherChunk(final IntegratedServer server) {
        if (!this.g6MatrixNetherChunkForcedByBenchmark) return;
        ServerLevel nether = server.getLevel(Level.NETHER);
        if (nether == null || !nether.setChunkForced(
                this.g6MatrixNetherChunkX, this.g6MatrixNetherChunkZ, false
        )) {
            throw new IllegalStateException("G6 matrix Nether target chunk could not be unforced");
        }
        this.g6MatrixNetherChunkForcedByBenchmark = false;
    }

    private void restoreG6MatrixNetherPreparation(final Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null) return;
        server.executeBlocking(() -> releaseG6MatrixNetherChunk(server));
    }

    private void pollG6MatrixServerAction(final Minecraft minecraft) {
        G6MatrixServerAction action = this.g6MatrixAwaitingClientAction;
        if (action == null || this.g6MatrixServerTaskPending.get()
                || this.g6MatrixCompletedServerAction != action) return;
        if (this.g6MatrixServerFailure != null) {
            fail(minecraft, this.g6MatrixServerFailure);
            return;
        }
        if (!g6MatrixClientActionIsVisible(minecraft, action)) return;
        if (action == G6MatrixServerAction.NETHER_RETURN
                && !restoreG6MatrixSourcesAfterDimension(minecraft)) return;
        logG6MatrixServerAction(action);
        this.g6MatrixAwaitingClientAction = null;
        this.g6MatrixCompletedServerAction = null;
        this.g6MatrixRecoveryActionVisible = true;
    }

    private boolean g6MatrixClientActionIsVisible(
            final Minecraft minecraft, final G6MatrixServerAction action
    ) {
        G6MatrixConfig config = this.route.g6Matrix();
        if (config == null || minecraft.player == null || minecraft.level == null) return false;
        return switch (action) {
            case LAVA_APPLY -> minecraft.level.dimension().equals(this.route.dimension())
                    && minecraft.level.getBlockState(config.lavaPosition()).is(Blocks.LAVA);
            case LAVA_REMOVE -> minecraft.level.dimension().equals(this.route.dimension())
                    && minecraft.level.getBlockState(config.lavaPosition()).is(Blocks.AIR);
            case DAY -> minecraft.level.dimension().equals(this.route.dimension())
                    && minecraft.level.getDefaultClockTime() == config.dayTicks();
            case NIGHT -> minecraft.level.dimension().equals(this.route.dimension())
                    && minecraft.level.getDefaultClockTime() == config.nightTicks();
            case RAIN -> minecraft.level.dimension().equals(this.route.dimension())
                    && minecraft.level.isRaining();
            case CLEAR -> g6MatrixClearVisible(
                    minecraft.level.dimension().equals(this.route.dimension()),
                    minecraft.level.getRainLevel(1.0F)
            );
            case STREAM -> sameG6MatrixPosition(
                    minecraft.player,
                    this.route.x() + this.g6MatrixStreamTargetOffset,
                    this.route.y() + this.g6MatrixStreamTargetYOffset, this.route.z()
            );
            case TELEPORT -> sameG6MatrixPosition(
                    minecraft.player,
                    this.route.x() + config.teleportOffsetX(),
                    this.route.y() + config.teleportOffsetY(),
                    this.route.z() + config.teleportOffsetZ()
            );
            case TELEPORT_RETURN -> minecraft.level.dimension().equals(this.route.dimension())
                    && sameG6MatrixPosition(
                    minecraft.player, this.route.x(), this.route.y(), this.route.z()
            );
            case NETHER_ENTER -> minecraft.level.dimension().equals(Level.NETHER)
                    && sameG6MatrixPosition(
                    minecraft.player, config.netherX(), config.netherY(), config.netherZ()
            );
            case NETHER_RETURN -> minecraft.level.dimension().equals(this.route.dimension())
                    && sameG6MatrixPosition(
                    minecraft.player, this.route.x(), this.route.y(), this.route.z()
            );
        };
    }

    private boolean sameG6MatrixPosition(
            final Entity entity, final double x, final double y, final double z
    ) {
        return Math.abs(entity.getX() - x) <= this.route.positionEpsilon()
                && Math.abs(entity.getY() - y) <= this.route.positionEpsilon()
                && Math.abs(entity.getZ() - z) <= this.route.positionEpsilon();
    }

    static boolean g6MatrixClearVisible(
            final boolean inRouteDimension, final float rainLevel
    ) {
        return inRouteDimension && rainLevel == 0.0F;
    }

    private void logG6MatrixServerAction(final G6MatrixServerAction action) {
        String marker = switch (action) {
            case LAVA_APPLY -> "LAVA_APPLIED";
            case LAVA_REMOVE -> "LAVA_REMOVED";
            case DAY -> "DAY";
            case NIGHT -> "NIGHT";
            case RAIN -> "RAIN";
            case CLEAR -> "CLEAR";
            case STREAM -> "STREAM_STEP";
            case TELEPORT -> "TELEPORT_OUT";
            case TELEPORT_RETURN -> "TELEPORT_RETURN";
            case NETHER_ENTER -> "NETHER_ENTER";
            case NETHER_RETURN -> "OVERWORLD_RETURN";
        };
        if (action == G6MatrixServerAction.STREAM) {
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route={} action={} "
                            + "requested_frame={} measured_frame={} index={} offset={} "
                            + "y_offset={} status=PASS",
                    this.route.routeId(), marker, this.g6MatrixActiveRequestedFrame,
                    this.measuredFrames, this.g6MatrixStreamIndex,
                    this.g6MatrixStreamTargetOffset, this.g6MatrixStreamTargetYOffset
            );
        } else {
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route={} action={} "
                            + "requested_frame={} measured_frame={} status=PASS",
                    this.route.routeId(), marker, this.g6MatrixActiveRequestedFrame,
                    this.measuredFrames
            );
        }
    }

    private void pollG6MatrixResourceReload(final Minecraft minecraft) {
        CompletableFuture<Void> reload = this.g6MatrixResourceReload;
        if (reload == null || !reload.isDone()) return;
        try {
            reload.join();
        } catch (RuntimeException exception) {
            fail(minecraft, "G6 matrix resource reload failed: "
                    + exception.getClass().getSimpleName());
            return;
        }
        this.g6MatrixResourceReload = null;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route={} "
                        + "action=RESOURCE_RELOAD requested_frame={} measured_frame={} status=PASS",
                this.route.routeId(), this.route.g6Matrix().resourceReloadFrame(),
                this.measuredFrames
        );
        this.g6MatrixRecoveryActionVisible = true;
    }

    private void beginG6MatrixRecovery(
            final String action,
            final int requestedFrame,
            final GiLiveRuntime.FinalSnapshot baseline,
            final int latencyClass,
            final int receiptBit,
            final boolean actionVisible
    ) {
        beginG6MatrixRecovery(
                action, requestedFrame, baseline, latencyClass, receiptBit, actionVisible,
                G6_MATRIX_RECOVERY_TIMEOUT_FRAMES
        );
    }

    private void beginG6MatrixRecovery(
            final String action,
            final int requestedFrame,
            final GiLiveRuntime.FinalSnapshot baseline,
            final int latencyClass,
            final int receiptBit,
            final boolean actionVisible,
            final int stabilizationTimeoutFrames
    ) {
        if (this.g6MatrixAwaitingRecovery != null || stabilizationTimeoutFrames <= 0) {
            throw new IllegalStateException("G6 matrix recovery windows overlapped");
        }
        this.g6MatrixAwaitingRecovery = action;
        this.g6MatrixRecoveryRequestedFrame = requestedFrame;
        this.g6MatrixRecoveryFieldGeneration = baseline.fieldGeneration();
        this.g6MatrixRecoverySampleCount = g6MatrixLatencySamples(baseline, latencyClass);
        this.g6MatrixRecoveryLatencyClass = latencyClass;
        this.g6MatrixRecoveryActionVisible = actionVisible;
        this.g6MatrixRecoveryRequiresTerrainBinding = false;
        this.g6MatrixRecoveryReceiptBit = receiptBit;
        this.g6MatrixRecoveryDeadline = Math.addExact(
                requestedFrame, stabilizationTimeoutFrames
        );
    }

    private void beginG6MatrixTerrainRecovery(
            final String action,
            final int requestedFrame,
            final GiLiveRuntime.FinalSnapshot baseline,
            final int receiptBit
    ) {
        beginG6MatrixRecovery(
                action, requestedFrame, baseline, G6_MATRIX_LATENCY_NONE, receiptBit, true
        );
        captureG6MatrixTerrainRecoveryBaseline(baseline);
    }

    private void captureG6MatrixTerrainRecoveryBaseline(
            final GiLiveRuntime.FinalSnapshot baseline
    ) {
        this.g6MatrixRecoveryRequiresTerrainBinding = true;
        this.g6MatrixRecoveryTerrainSubmit = baseline.latestTerrainSubmitIndex();
        this.g6MatrixRecoveryTerrainDeviceGeneration =
                baseline.latestTerrainDeviceGeneration();
        this.g6MatrixRecoveryTerrainFieldGeneration = baseline.latestTerrainFieldGeneration();
        this.g6MatrixRecoveryTerrainSourceTick = baseline.latestTerrainSourceTick();
        this.g6MatrixRecoveryDeadline = Math.addExact(
                this.g6MatrixRecoveryRequestedFrame,
                G6_MATRIX_TERRAIN_RECOVERY_TIMEOUT_FRAMES
        );
    }

    private String g6MatrixRecoveryName(final G6MatrixServerAction action) {
        return switch (action) {
            case LAVA_APPLY -> "LAVA_APPLY";
            case LAVA_REMOVE -> "LAVA_REMOVE";
            case DAY -> "DAY";
            case NIGHT -> "NIGHT";
            case RAIN -> "RAIN";
            case CLEAR -> "CLEAR";
            case STREAM -> g6MatrixStreamRecoveryName(this.g6MatrixStreamIndex);
            case TELEPORT -> "TELEPORT_OUT";
            case TELEPORT_RETURN -> "TELEPORT_RETURN";
            case NETHER_ENTER -> "NETHER_ENTER";
            case NETHER_RETURN -> "NETHER_RETURN";
        };
    }

    private static String g6MatrixStreamRecoveryName(final int index) {
        return switch (index) {
            case 0 -> "STREAM_STEP_0";
            case 1 -> "STREAM_STEP_1";
            case 2 -> "STREAM_STEP_2";
            case 3 -> "STREAM_STEP_3";
            case 4 -> "STREAM_STEP_4";
            case 5 -> "STREAM_STEP_5";
            case 6 -> "STREAM_STEP_6";
            case 7 -> "STREAM_STEP_7";
            default -> throw new IllegalArgumentException("G6 matrix stream index is invalid");
        };
    }

    private static int g6MatrixLatencyClass(final G6MatrixServerAction action) {
        return switch (action) {
            // Lava mutates both semantic occupancy and the static-emitter registry. G6 merges
            // simultaneous content/source invalidation to the source class, so the matrix must
            // predeclare that exact attribution instead of waiting on a BLOCK sample that cannot
            // honestly describe this mutation.
            case LAVA_APPLY, LAVA_REMOVE -> G6_MATRIX_LATENCY_STATIC_SOURCE;
            // Time/weather rotates the global environment input for every G3/G6 brick. It keeps
            // the full-volume recovery class even though compatible visible history is retained.
            case DAY, NIGHT, RAIN, CLEAR -> G6_MATRIX_LATENCY_FULL_RESET;
            case STREAM -> G6_MATRIX_LATENCY_SCROLL;
            case TELEPORT, TELEPORT_RETURN, NETHER_ENTER, NETHER_RETURN ->
                    G6_MATRIX_LATENCY_FULL_RESET;
        };
    }

    private int g6MatrixReceiptBit(final G6MatrixServerAction action) {
        return switch (action) {
            case LAVA_REMOVE -> G6_MATRIX_RECEIPT_LAVA;
            case NIGHT -> G6_MATRIX_RECEIPT_DAY_NIGHT;
            case CLEAR -> G6_MATRIX_RECEIPT_RAIN;
            case STREAM -> this.g6MatrixStreamIndex
                    == this.route.g6Matrix().streamOffsets().length - 1
                    ? G6_MATRIX_RECEIPT_STREAM : 0;
            case TELEPORT_RETURN -> G6_MATRIX_RECEIPT_TELEPORT;
            case NETHER_RETURN -> G6_MATRIX_RECEIPT_NETHER;
            default -> 0;
        };
    }

    private static long g6MatrixLatencySamples(
            final GiLiveRuntime.FinalSnapshot snapshot, final int latencyClass
    ) {
        return switch (latencyClass) {
            case G6_MATRIX_LATENCY_BLOCK -> snapshot.blockSamples();
            case G6_MATRIX_LATENCY_STATIC_SOURCE -> snapshot.staticSourceSamples();
            case G6_MATRIX_LATENCY_SCROLL -> snapshot.scrollSamples();
            case G6_MATRIX_LATENCY_FULL_RESET -> snapshot.fullResetSamples();
            default -> -1L;
        };
    }

    private static String g6MatrixLatencyClassName(final int latencyClass) {
        return switch (latencyClass) {
            case G6_MATRIX_LATENCY_BLOCK -> "BLOCK";
            case G6_MATRIX_LATENCY_STATIC_SOURCE -> "STATIC_SOURCE";
            case G6_MATRIX_LATENCY_SCROLL -> "SCROLL";
            case G6_MATRIX_LATENCY_FULL_RESET -> "FULL_RESET";
            default -> "NONE";
        };
    }

    static boolean g6MatrixMutationRecovered(
            final long baselineGeneration,
            final long currentGeneration,
            final long sampleBefore,
            final long sampleAfter
    ) {
        return baselineGeneration >= 0L && currentGeneration > baselineGeneration
                && sampleBefore >= 0L && sampleAfter == sampleBefore + 1L;
    }

    static boolean g6MatrixOrbitStable(
            final long baselineGeneration,
            final long currentGeneration,
            final long blockBefore,
            final long blockAfter,
            final long staticSourceBefore,
            final long staticSourceAfter,
            final long scrollBefore,
            final long scrollAfter,
            final long fullResetBefore,
            final long fullResetAfter
    ) {
        return baselineGeneration >= 0L && currentGeneration == baselineGeneration
                && blockBefore >= 0L && blockAfter == blockBefore
                && staticSourceBefore >= 0L && staticSourceAfter == staticSourceBefore
                && scrollBefore >= 0L && scrollAfter == scrollBefore
                && fullResetBefore >= 0L && fullResetAfter == fullResetBefore;
    }

    static boolean g6MatrixTerrainReloadRecovered(
            final long submitBefore,
            final long deviceBefore,
            final long submitAfter,
            final long deviceAfter,
            final long currentDevice,
            final int bindStatus,
            final boolean carrierSafe,
            final boolean frameCompatible,
            final boolean exactMaskNonzero
    ) {
        return submitBefore >= 0L && submitAfter > submitBefore
                && deviceBefore > 0L && deviceAfter == deviceBefore
                && deviceAfter == currentDevice && bindStatus == 1
                && carrierSafe && frameCompatible && exactMaskNonzero;
    }

    static boolean g6MatrixRecoveryWindowsSufficient(
            final long chunkReloadFrame,
            final long resourceReloadFrame,
            final long dayFrame,
            final long nightFrame,
            final long rainFrame,
            final long clearFrame,
            final long streamStartFrame,
            final long teleportFrame,
            final long teleportReturnFrame,
            final long netherEnterFrame,
            final long netherReturnFrame,
            final long measureFrames
    ) {
        return resourceReloadFrame - chunkReloadFrame
                >= G6_MATRIX_RELOAD_RECOVERY_GAP_FRAMES
                && dayFrame - resourceReloadFrame
                >= G6_MATRIX_RELOAD_RECOVERY_GAP_FRAMES
                && nightFrame - dayFrame >= G6_MATRIX_STATIC_RECOVERY_GAP_FRAMES
                && rainFrame - nightFrame >= G6_MATRIX_STATIC_RECOVERY_GAP_FRAMES
                && clearFrame - rainFrame >= G6_MATRIX_STATIC_RECOVERY_GAP_FRAMES
                && streamStartFrame - clearFrame >= G6_MATRIX_STATIC_RECOVERY_GAP_FRAMES
                && teleportReturnFrame - teleportFrame
                >= G6_MATRIX_TELEPORT_RECOVERY_GAP_FRAMES
                && netherEnterFrame - teleportReturnFrame
                >= G6_MATRIX_RESET_RECOVERY_GAP_FRAMES
                && netherReturnFrame - netherEnterFrame
                >= G6_MATRIX_DIMENSION_RECOVERY_GAP_FRAMES
                && measureFrames - netherReturnFrame
                >= G6_MATRIX_DIMENSION_RECOVERY_GAP_FRAMES;
    }

    static int g6MatrixAllCascadeStabilizationTimeoutFrames(
            final boolean teleportOut,
            final boolean dimensionChange
    ) {
        if (teleportOut) return G6_MATRIX_TELEPORT_STABILIZATION_TIMEOUT_FRAMES;
        return dimensionChange ? G6_MATRIX_DIMENSION_STABILIZATION_TIMEOUT_FRAMES
                : G6_MATRIX_RECOVERY_TIMEOUT_FRAMES;
    }

    static boolean g6MatrixScrollWindowsSufficient(
            final long streamStartFrame,
            final long streamStepFrames,
            final int streamStepCount,
            final long teleportFrame
    ) {
        if (streamStartFrame < 0L || streamStepCount != 8
                || streamStepFrames < G6_MATRIX_SCROLL_STEP_MIN_FRAMES
                || streamStepFrames > G6_MATRIX_SCROLL_STEP_MAX_FRAMES) return false;
        long streamEndFrame = Math.addExact(
                streamStartFrame, Math.multiplyExact(streamStepFrames, streamStepCount - 1L)
        );
        return teleportFrame - streamEndFrame
                >= G6_MATRIX_SCROLL_FINAL_RECOVERY_GAP_FRAMES;
    }

    static boolean g6MatrixRecoveryCoverageReady(
            final int readyMask,
            final boolean buildInFlight,
            final boolean nearScrollRecovery
    ) {
        return nearScrollRecovery ? (readyMask & 1) != 0
                : readyMask == 7 && !buildInFlight;
    }

    static boolean g6MatrixTerrainQueueGateSatisfied(
            final boolean requiresTerrainBinding,
            final boolean terrainQueueEmpty
    ) {
        return !requiresTerrainBinding || terrainQueueEmpty;
    }

    private void pollG6MatrixRecovery(final Minecraft minecraft) {
        if (!this.g6MatrixRecoveryActionVisible) return;
        if (this.measuredFrames > this.g6MatrixRecoveryDeadline) {
            logG6MatrixPendingRecovery(minecraft, "RECOVERY_TIMEOUT");
            fail(minecraft, "G6 matrix recovery timed out for " + this.g6MatrixAwaitingRecovery);
            return;
        }
        if (minecraft.level == null || !g6MatrixTerrainQueueGateSatisfied(
                this.g6MatrixRecoveryRequiresTerrainBinding,
                minecraft.levelRenderer.hasRenderedAllSections()
        )) return;
        GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();
        boolean nearScrollRecovery = this.g6MatrixRecoveryLatencyClass
                == G6_MATRIX_LATENCY_SCROLL;
        if (GiLiveRuntime.admissionState() != GiLiveRuntime.AdmissionState.READY
                || !GiLiveRuntime.finalReceiptIsCurrent(snapshot, GiLiveRuntime.deviceGeneration())
                || !g6MatrixRecoveryCoverageReady(
                snapshot.readyMask(), snapshot.buildInFlight(), nearScrollRecovery)) return;
        if (snapshot.staleRejects() != 0L || snapshot.rejectedCount() != 0L) {
            fail(minecraft, "G6 matrix recovery observed stale/rejected transport");
            return;
        }
        long sampleCount = g6MatrixLatencySamples(
                snapshot, this.g6MatrixRecoveryLatencyClass
        );
        boolean requiresMutationEvidence =
                this.g6MatrixRecoveryLatencyClass != G6_MATRIX_LATENCY_NONE;
        if (requiresMutationEvidence && !g6MatrixMutationRecovered(
                this.g6MatrixRecoveryFieldGeneration, snapshot.fieldGeneration(),
                this.g6MatrixRecoverySampleCount, sampleCount
        )) return;
        if (this.g6MatrixRecoveryRequiresTerrainBinding) {
            if (!g6MatrixTerrainReloadRecovered(
                    this.g6MatrixRecoveryTerrainSubmit,
                    this.g6MatrixRecoveryTerrainDeviceGeneration,
                    snapshot.latestTerrainSubmitIndex(),
                    snapshot.latestTerrainDeviceGeneration(),
                    GiLiveRuntime.deviceGeneration(),
                    snapshot.latestTerrainBindStatus(),
                    snapshot.latestTerrainCarrierSafe(),
                    snapshot.latestTerrainFrameCompatible(),
                    snapshot.latestTerrainExactMaskNonzero()
            )) return;
            if (requiresMutationEvidence) {
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_RECOVERY route={} action={} "
                                + "requested_frame={} measured_frame={} baseline_generation={} "
                                + "field_generation={} latency_class={} sample_before={} "
                                + "sample_after={} terrain_submit_before={} terrain_submit_after={} "
                                + "terrain_device_before={} terrain_device_after={} current_device={} "
                                + "terrain_field_before={} terrain_field_after={} current_field={} "
                                + "terrain_source_before={} terrain_source_after={} current_source={} "
                                + "bind_status=1 carrier_safe=true frame_compatible=true "
                                + "exact_mask_nonzero=true ready_mask=7 "
                                + "build_in_flight=false status=PASS",
                        this.route.routeId(), this.g6MatrixAwaitingRecovery,
                        this.g6MatrixRecoveryRequestedFrame, this.measuredFrames,
                        this.g6MatrixRecoveryFieldGeneration, snapshot.fieldGeneration(),
                        g6MatrixLatencyClassName(this.g6MatrixRecoveryLatencyClass),
                        this.g6MatrixRecoverySampleCount, sampleCount,
                        this.g6MatrixRecoveryTerrainSubmit, snapshot.latestTerrainSubmitIndex(),
                        this.g6MatrixRecoveryTerrainDeviceGeneration,
                        snapshot.latestTerrainDeviceGeneration(), GiLiveRuntime.deviceGeneration(),
                        this.g6MatrixRecoveryTerrainFieldGeneration,
                        snapshot.latestTerrainFieldGeneration(), snapshot.fieldGeneration(),
                        this.g6MatrixRecoveryTerrainSourceTick,
                        snapshot.latestTerrainSourceTick(), snapshot.sourceTick()
                );
            } else {
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_RECOVERY route={} action={} "
                                + "requested_frame={} measured_frame={} "
                                + "terrain_submit_before={} terrain_submit_after={} "
                                + "terrain_device_before={} terrain_device_after={} current_device={} "
                                + "terrain_field_before={} terrain_field_after={} current_field={} "
                                + "terrain_source_before={} terrain_source_after={} current_source={} "
                                + "bind_status=1 carrier_safe=true frame_compatible=true "
                                + "exact_mask_nonzero=true ready_mask=7 "
                                + "build_in_flight=false status=PASS",
                        this.route.routeId(), this.g6MatrixAwaitingRecovery,
                        this.g6MatrixRecoveryRequestedFrame, this.measuredFrames,
                        this.g6MatrixRecoveryTerrainSubmit, snapshot.latestTerrainSubmitIndex(),
                        this.g6MatrixRecoveryTerrainDeviceGeneration,
                        snapshot.latestTerrainDeviceGeneration(), GiLiveRuntime.deviceGeneration(),
                        this.g6MatrixRecoveryTerrainFieldGeneration,
                        snapshot.latestTerrainFieldGeneration(), snapshot.fieldGeneration(),
                        this.g6MatrixRecoveryTerrainSourceTick,
                        snapshot.latestTerrainSourceTick(), snapshot.sourceTick()
                );
            }
        } else {
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_RECOVERY route={} action={} "
                            + "requested_frame={} measured_frame={} baseline_generation={} "
                            + "field_generation={} latency_class={} sample_before={} "
                            + "sample_after={} ready_mask={} build_in_flight={} status=PASS",
                    this.route.routeId(), this.g6MatrixAwaitingRecovery,
                    this.g6MatrixRecoveryRequestedFrame, this.measuredFrames,
                    this.g6MatrixRecoveryFieldGeneration, snapshot.fieldGeneration(),
                    g6MatrixLatencyClassName(this.g6MatrixRecoveryLatencyClass),
                    this.g6MatrixRecoverySampleCount, sampleCount, snapshot.readyMask(),
                    snapshot.buildInFlight()
            );
        }
        this.g6MatrixReceiptMask |= this.g6MatrixRecoveryReceiptBit;
        this.g6MatrixAwaitingRecovery = null;
        this.g6MatrixRecoveryActionVisible = false;
        this.g6MatrixRecoveryRequiresTerrainBinding = false;
        this.g6MatrixRecoveryReceiptBit = 0;
    }

    private boolean restoreG6MatrixSourcesAfterDimension(final Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null
                || !minecraft.level.dimension().equals(this.route.dimension())) return false;
        for (ItemEntity probe : this.l6ProbeEntities) probe.discard();
        this.l6ProbeEntities.clear();
        minecraft.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
        G6MatrixConfig config = this.route.g6Matrix();
        ItemEntity probe = new ItemEntity(
                minecraft.level, config.entityX(), config.entityY(), config.entityZ(),
                new ItemStack(Items.TORCH), 0.0, 0.0, 0.0
        );
        probe.setUUID(UUID.nameUUIDFromBytes(
                "metallum-g6-matrix-probe-return".getBytes(StandardCharsets.UTF_8)
        ));
        int probeId = 1_999_999_899;
        if (minecraft.level.getEntity(probeId) != null) return false;
        probe.setId(probeId);
        probe.setNoGravity(true);
        probe.setDeltaMovement(0.0, 0.0, 0.0);
        minecraft.level.addEntity(probe);
        this.l6ProbeEntities.add(probe);
        restoreG6MatrixPose(minecraft);
        return true;
    }

    private void restoreG6MatrixPose(final Minecraft minecraft) {
        if (minecraft.player == null) return;
        minecraft.player.setPos(this.route.x(), this.route.y(), this.route.z());
        minecraft.player.xOld = this.route.x();
        minecraft.player.yOld = this.route.y();
        minecraft.player.zOld = this.route.z();
        minecraft.player.setYRot(this.route.yaw());
        minecraft.player.setXRot(this.route.pitch());
        minecraft.player.yRotO = this.route.yaw();
        minecraft.player.xRotO = this.route.pitch();
        minecraft.player.setDeltaMovement(0.0, 0.0, 0.0);
    }

    record G6MatrixFinalCensus(
            long queuePending,
            long queueInFlight,
            long queueQueued,
            long queueCompleted,
            long queueDiscarded,
            boolean queueAlgebra,
            long accountedCurrent,
            long accountedStart,
            long accountedExpectedStart,
            long blockChangeSamples,
            long blockChangeP95Submits,
            long blockChangeP99Submits,
            boolean blockChangeSla,
            long staticSourceSamples,
            long staticSourceP95Submits,
            long staticSourceP99Submits,
            boolean staticSourceSla,
            long scrollSamples,
            long scrollP95Submits,
            long scrollP99Submits,
            boolean scrollSla,
            long fullResetSamples,
            long fullResetP95Submits,
            long fullResetP99Submits,
            boolean fullResetSla
    ) {
        boolean queueConverged() {
            return this.queuePending == 0L && this.queueInFlight == 0L
                    && this.queueAlgebra
                    && this.queueQueued == this.queueCompleted + this.queueDiscarded;
        }

        long accountedDelta() {
            return this.accountedCurrent - this.accountedExpectedStart;
        }

        boolean accountingStable() {
            return accountedDelta() == 0L
                    && this.accountedStart == this.accountedExpectedStart;
        }

        boolean latencyCensusComplete() {
            return this.blockChangeSamples > 0L && this.blockChangeSla
                    && this.staticSourceSamples > 0L && this.staticSourceSla
                    && this.scrollSamples > 0L && this.scrollSla
                    && this.fullResetSamples > 0L && this.fullResetSla;
        }

        boolean passed() {
            return queueConverged() && accountingStable() && latencyCensusComplete();
        }
    }

    private G6MatrixFinalCensus g6MatrixFinalCensus(
            final GiLiveRuntime.FinalSnapshot snapshot
    ) {
        return new G6MatrixFinalCensus(
                snapshot.schedulerPending(), snapshot.schedulerInFlight(),
                snapshot.schedulerQueued(), snapshot.schedulerCompleted(),
                snapshot.schedulerDiscarded(), snapshot.schedulerAlgebraExact(),
                snapshot.accountedBytes(), snapshot.measurementStartAccountedBytes(),
                this.g6MatrixMeasurementAccountedBytes,
                snapshot.blockSamples(), snapshot.blockP95Submits(),
                snapshot.blockP99Submits(), snapshot.blockSla(),
                snapshot.staticSourceSamples(), snapshot.staticSourceP95Submits(),
                snapshot.staticSourceP99Submits(), snapshot.staticSourceSla(),
                snapshot.scrollSamples(), snapshot.scrollP95Submits(),
                snapshot.scrollP99Submits(), snapshot.scrollSla(),
                snapshot.fullResetSamples(), snapshot.fullResetP95Submits(),
                snapshot.fullResetP99Submits(), snapshot.fullResetSla()
        );
    }

    private void logG6MatrixFinalCensusFailure(final G6MatrixFinalCensus census) {
        Metallum.LOGGER.error(
                "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_FINAL_CENSUS route={} "
                        + "queue_pending={} queue_in_flight={} queue_queued={} "
                        + "queue_completed={} queue_discarded={} queue_algebra={} "
                        + "queue_converged={} accounted_current={} accounted_start={} "
                        + "accounted_expected_start={} accounted_delta={} accounting_stable={} "
                        + "block_change_samples={} block_change_p95_submits={} "
                        + "block_change_p99_submits={} block_change_sla={} "
                        + "static_source_samples={} static_source_p95_submits={} "
                        + "static_source_p99_submits={} static_source_sla={} "
                        + "scroll_samples={} scroll_p95_submits={} scroll_p99_submits={} "
                        + "scroll_sla={} full_reset_samples={} full_reset_p95_submits={} "
                        + "full_reset_p99_submits={} full_reset_sla={} "
                        + "latency_census_complete={} status=FAIL contract=6",
                this.route.routeId(), census.queuePending(), census.queueInFlight(),
                census.queueQueued(), census.queueCompleted(), census.queueDiscarded(),
                census.queueAlgebra(), census.queueConverged(), census.accountedCurrent(),
                census.accountedStart(), census.accountedExpectedStart(),
                census.accountedDelta(), census.accountingStable(),
                census.blockChangeSamples(), census.blockChangeP95Submits(),
                census.blockChangeP99Submits(), census.blockChangeSla(),
                census.staticSourceSamples(), census.staticSourceP95Submits(),
                census.staticSourceP99Submits(), census.staticSourceSla(),
                census.scrollSamples(), census.scrollP95Submits(),
                census.scrollP99Submits(), census.scrollSla(),
                census.fullResetSamples(), census.fullResetP95Submits(),
                census.fullResetP99Submits(), census.fullResetSla(),
                census.latencyCensusComplete()
        );
    }

    private String completeG6Matrix() {
        if (this.route.g6Matrix() == null) return null;
        if (this.g6MatrixReceiptMask != G6_MATRIX_RECEIPT_ALL
                || this.g6MatrixServerTaskPending.get()
                || this.g6MatrixAwaitingClientAction != null
                || this.g6MatrixAwaitingRecovery != null
                || this.g6MatrixResourceReload != null
                || !this.g6MatrixNetherPrepared
                || this.g6MatrixNetherChunkForcedByBenchmark) {
            return "G6 matrix did not complete every ordered live-update receipt: mask="
                    + this.g6MatrixReceiptMask;
        }
        GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();
        G6MatrixFinalCensus census = g6MatrixFinalCensus(snapshot);
        if (!census.passed()) {
            logG6MatrixFinalCensusFailure(census);
            return "G6 matrix final queue/SLA/accounting census is incomplete; "
                    + "see GI_G6_MATRIX_FINAL_CENSUS";
        }
        this.g6MatrixFinalValidated = true;
        return null;
    }

    private void driveNetherLavaStress(final Minecraft minecraft) {
        if (this.route == null || !"nether-lava-stress-v1".equals(this.route.routeId())) {
            return;
        }
        String rotateEnv = System.getenv("METALLUM_BENCHMARK_NETHER_ROTATE");
        if (rotateEnv == null || !"1".equals(rotateEnv)) {
            return;
        }
        if (minecraft.player == null) {
            return;
        }
        float yaw = this.route.yaw() + this.segmentFrame * 0.05f;
        minecraft.player.setYRot(yaw);
        minecraft.player.yRotO = yaw;
    }

    private void applyL6DynamicShadowMotion(final Minecraft minecraft, final int frame) {
        L6DynamicShadowConfig config = this.route.l6DynamicShadow();
        if (config == null || minecraft.player == null || minecraft.level == null) {
            return;
        }
        double orbitPhase = Math.TAU * frame / config.orbitPeriodFrames();
        double x = this.route.x() + config.orbitRadius() * (Math.cos(orbitPhase) - 1.0);
        double z = this.route.z() + config.orbitRadius() * Math.sin(orbitPhase);
        float yaw = this.route.yaw() + config.orbitYawAmplitudeDegrees() * (float) Math.sin(orbitPhase);
        float pitch = Mth.clamp(
                this.route.pitch() + config.orbitPitchAmplitudeDegrees() * (float) Math.sin(orbitPhase),
                -90.0F,
                90.0F
        );
        minecraft.player.setPos(x, this.route.y(), z);
        minecraft.player.xOld = x;
        minecraft.player.yOld = this.route.y();
        minecraft.player.zOld = z;
        minecraft.player.setYRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.yRotO = yaw;
        minecraft.player.xRotO = pitch;

        for (int index = 0; index < this.l6ProbeEntities.size(); index++) {
            ItemEntity probe = this.l6ProbeEntities.get(index);
            if (probe.isRemoved()) {
                continue;
            }
            double phase = Math.TAU * frame / config.probePeriodFrames()
                    + Math.TAU * index / config.probeCount();
            double probeX = config.probeOriginX() + config.probeRadius() * Math.cos(phase);
            double probeY = config.probeOriginY()
                    + config.probeVerticalAmplitude() * Math.sin(phase * 2.0);
            double probeZ = config.probeOriginZ() + config.probeRadius() * Math.sin(phase);
            probe.xOld = probe.getX();
            probe.yOld = probe.getY();
            probe.zOld = probe.getZ();
            probe.setPos(probeX, probeY, probeZ);
            probe.setDeltaMovement(0.0, 0.0, 0.0);
        }

    }

    private void restoreL6DynamicShadowMotion(final Minecraft minecraft) {
        if (!this.l6DynamicReady || minecraft.player == null) {
            return;
        }
        this.l6MotionFrame = 0;
        applyL6DynamicShadowMotion(minecraft, 0);
    }

    private void clearL6DynamicShadowRoute(final Minecraft minecraft) {
        restoreL6DynamicShadowMotion(minecraft);
        for (ItemEntity probe : this.l6ProbeEntities) probe.discard();
        this.l6ProbeEntities.clear();
        if (this.l6OriginalMainHandCaptured && minecraft.player != null) {
            minecraft.player.setItemInHand(InteractionHand.MAIN_HAND, this.l6OriginalMainHand);
        }
        this.l6OriginalMainHand = ItemStack.EMPTY;
        this.l6OriginalMainHandCaptured = false;
        this.l6DynamicReady = false;
        this.g6MatrixReady = false;
        this.l6MotionFrame = 0;
    }

    private String runtimeSettingsMismatch(final Minecraft minecraft) {
        String pacingMismatch = runtimePacingMismatch(minecraft);
        if (pacingMismatch != null) {
            return pacingMismatch;
        }
        Window window = minecraft.getWindow();
        if (minecraft.options.framerateLimit().get() != this.expectedMaxFps
                || minecraft.options.renderDistance().get() != this.expectedRenderDistance
                || minecraft.options.simulationDistance().get() != this.expectedSimulationDistance
                || minecraft.options.particles().get().ordinal() != this.expectedParticles
                || minecraft.options.mipmapLevels().get() != this.expectedMipmapLevels
                || minecraft.options.biomeBlendRadius().get() != this.expectedBiomeBlendRadius
                || minecraft.options.cloudRange().get() != this.expectedCloudRange
                || minecraft.options.guiScale().get() != this.expectedConfiguredGuiScale
                || minecraft.options.enableVsync().get() != this.expectedVsync
                || minecraft.options.ambientOcclusion().get() != this.expectedAmbientOcclusion
                || Double.compare(
                        minecraft.options.entityDistanceScaling().get(),
                        this.expectedEntityDistanceScaling
                ) != 0
                || !minecraft.options.graphicsPreset().get().getSerializedName().equals(
                        this.expectedGraphicsPreset
                )
                || !minecraft.options.cloudStatus().get().getSerializedName().equals(
                        this.expectedCloudsMode
                )) {
            return "live Minecraft rendering options differ from the tracked settings";
        }
        if (window.getGuiScale() <= 0) {
            return "resolved GUI scale is invalid";
        }
        List<String> selectedPacks = List.copyOf(
                minecraft.getResourcePackRepository().getSelectedIds()
        );
        if (!selectedPacks.equals(this.expectedResourcePackIds)) {
            return "active resource packs differ from settings (expected "
                    + this.expectedResourcePackIds + ", found " + selectedPacks + ")";
        }
        return null;
    }

    private String runtimePacingMismatch(final Minecraft minecraft) {
        Window window = minecraft.getWindow();
        minecraft.getFramerateLimitTracker().onInputReceived();
        if (!minecraft.isWindowActive()) {
            return "benchmark window is not active";
        }
        if (window.isIconified()) {
            return "benchmark window is iconified";
        }
        if (!window.isFullscreen()) {
            return "benchmark window is not fullscreen";
        }
        if (!"NONE".equals(minecraft.getFramerateLimitTracker().getThrottleReason().name())) {
            return "benchmark framerate is throttled by "
                    + minecraft.getFramerateLimitTracker().getThrottleReason();
        }
        if (minecraft.getFramerateLimitTracker().getFramerateLimit() != this.expectedMaxFps) {
            return "effective framerate limit differs from settings (expected "
                    + this.expectedMaxFps + ", found "
                    + minecraft.getFramerateLimitTracker().getFramerateLimit() + ")";
        }
        return null;
    }

    private void restoreBenchmarkWindowFocus(final Minecraft minecraft) {
        if (!shouldRequestBenchmarkWindowFocus(
                minecraft.isWindowActive(),
                this.stageFrames
        )) {
            return;
        }
        Window window = minecraft.getWindow();
        GLFW.glfwFocusWindow(window.handle());
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=WINDOW_FOCUS_REQUEST frame={} glfw_focused={}",
                this.stageFrames,
                GLFW.glfwGetWindowAttrib(window.handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE
        );
    }

    static boolean shouldRequestBenchmarkWindowFocus(
            final boolean windowActive,
            final int stageFrames
    ) {
        return !windowActive
                && stageFrames >= 0
                && stageFrames % WINDOW_FOCUS_RETRY_INTERVAL_FRAMES == 0;
    }

    static boolean shouldCloseBenchmarkTiming(
            final boolean measureEndBoundary,
            final int boundaryCheckFrames
    ) {
        return measureEndBoundary && boundaryCheckFrames == 0;
    }

    private String clientIdentityMismatch(final Minecraft minecraft) {
        if (!this.route.playerName().equals(minecraft.getUser().getName())
                || !this.route.playerUuid().equals(minecraft.getUser().getProfileId())
                || !this.route.playerName().equals(minecraft.player.getGameProfile().name())
                || !this.route.playerUuid().equals(minecraft.player.getGameProfile().id())) {
            return "benchmark client player identity differs from the route";
        }
        return null;
    }

    private boolean samePose(final Entity entity) {
        boolean rotating = "nether-lava-stress-v1".equals(this.route.routeId())
                && "1".equals(System.getenv("METALLUM_BENCHMARK_NETHER_ROTATE"));
        return Math.abs(entity.getX() - this.route.x()) <= this.route.positionEpsilon()
                && Math.abs(entity.getY() - this.route.y()) <= this.route.positionEpsilon()
                && Math.abs(entity.getZ() - this.route.z()) <= this.route.positionEpsilon()
                && (rotating || (Math.abs(Mth.wrapDegrees(entity.getYRot() - this.route.yaw()))
                <= this.route.angleEpsilon()
                && Math.abs(Mth.wrapDegrees(entity.getXRot() - this.route.pitch()))
                <= this.route.angleEpsilon()));
    }

    private String routePose() {
        return this.route.x() + "," + this.route.y() + "," + this.route.z()
                + ";" + this.route.yaw() + "," + this.route.pitch();
    }

    private static String entityPose(final Entity entity) {
        return entity.getX() + "," + entity.getY() + "," + entity.getZ()
                + ";" + entity.getYRot() + "," + entity.getXRot();
    }

    private boolean isTargetFramebuffer(final Window window) {
        if (!this.useCurrentWindow) {
            // A matching GLFW monitor attachment is the runtime exclusive-fullscreen
            // contract. Window.isFullscreen() is a cached Minecraft option and can
            // remain false while macOS has already entered the target mode.
            if (GLFW.glfwGetWindowMonitor(window.handle()) != this.targetMonitor) {
                return false;
            }
        }
        GLFW.glfwGetFramebufferSize(
                window.handle(),
                this.framebufferWidthScratch,
                this.framebufferHeightScratch
        );
        return hasExactTargetFramebuffer(
                this.framebufferWidthScratch[0],
                this.framebufferHeightScratch[0],
                this.expectedFramebufferWidth,
                this.expectedFramebufferHeight,
                window.getWidth(),
                window.getHeight()
        );
    }

    static boolean hasExactTargetFramebuffer(
            final int framebufferWidth,
            final int framebufferHeight,
            final int expectedFramebufferWidth,
            final int expectedFramebufferHeight,
            final int logicalWindowWidth,
            final int logicalWindowHeight
    ) {
        return framebufferWidth == expectedFramebufferWidth
                && framebufferHeight == expectedFramebufferHeight
                // macOS Retina reports a logical Window size that is smaller
                // than the Metal/GLFW backing framebuffer. It still has to be
                // a live window, but it must not be compared to backing pixels.
                && logicalWindowWidth > 0
                && logicalWindowHeight > 0;
    }

    private String framebufferTimeoutDetails(final Window window) {
        GLFW.glfwGetFramebufferSize(
                window.handle(),
                this.framebufferWidthScratch,
                this.framebufferHeightScratch
        );
        long actualMonitor = GLFW.glfwGetWindowMonitor(window.handle());
        Monitor bestMonitor = window.findBestMonitor();
        long bestMonitorHandle = bestMonitor == null ? 0L : bestMonitor.monitor();
        return "timed out waiting for exact target framebuffer"
                + " actual_monitor=" + actualMonitor + "/" + monitorName(actualMonitor)
                + " target_monitor=" + this.targetMonitor + "/" + monitorName(this.targetMonitor)
                + " best_monitor=" + bestMonitorHandle + "/" + monitorName(bestMonitorHandle)
                + " framebuffer=" + this.framebufferWidthScratch[0] + "x" + this.framebufferHeightScratch[0]
                + " logical_window=" + window.getWidth() + "x" + window.getHeight()
                + " screen=" + window.getScreenWidth() + "x" + window.getScreenHeight()
                + " fullscreen=" + window.isFullscreen()
                + " expected_framebuffer=" + this.expectedFramebufferWidth + "x" + this.expectedFramebufferHeight;
    }

    private static String monitorName(final long monitor) {
        return monitor == 0L ? "<none>" : String.valueOf(GLFW.glfwGetMonitorName(monitor));
    }

    private void startSegment() {
        BenchmarkScalingMode mode = this.sequence.get(this.segmentIndex);
        TorchEpochTelemetry.abort();
        L6DynamicShadowBenchmarkTelemetry.abort();
        SodiumRelightOracle.abortObservation();
        SodiumRelightFastPath.abortObservation();
        this.torchEpochServerTaskPending.set(false);
        this.torchEpochRequested = false;
        this.torchEpochAppliedLogged = false;
        this.torchEpochRemovalRequested = false;
        this.torchEpochRemovedLogged = false;
        this.torchEpochFinished = false;
        this.torchEpochToken = 0L;
        this.torchEpochRemovalToken = 0L;
        this.completedTorchEpochToken = 0L;
        this.torchEpochFailure = null;
        this.torchEpochAppliedMeasuredFrame = -1;
        this.torchEpochRemovedMeasuredFrame = -1;
        this.g6MatrixReceiptMask = 0;
        this.g6MatrixOrbitFieldGeneration = -1L;
        this.g6MatrixOrbitBlockSamples = -1L;
        this.g6MatrixOrbitStaticSourceSamples = -1L;
        this.g6MatrixOrbitScrollSamples = -1L;
        this.g6MatrixOrbitFullResetSamples = -1L;
        this.g6MatrixOrbitStarted = false;
        this.g6MatrixServerTaskPending.set(false);
        this.g6MatrixCompletedServerAction = null;
        this.g6MatrixServerFailure = null;
        this.g6MatrixAwaitingClientAction = null;
        this.g6MatrixActiveRequestedFrame = 0;
        this.g6MatrixStreamIndex = -1;
        this.g6MatrixStreamTargetOffset = 0;
        this.g6MatrixStreamTargetYOffset = 0;
        this.g6MatrixResourceReload = null;
        this.g6MatrixChunkReloadRequested = false;
        this.g6MatrixResourceReloadRequested = false;
        this.g6MatrixAwaitingRecovery = null;
        this.g6MatrixRecoveryFieldGeneration = -1L;
        this.g6MatrixRecoverySampleCount = -1L;
        this.g6MatrixRecoveryLatencyClass = G6_MATRIX_LATENCY_NONE;
        this.g6MatrixRecoveryActionVisible = false;
        this.g6MatrixRecoveryRequiresTerrainBinding = false;
        this.g6MatrixRecoveryTerrainSubmit = -1L;
        this.g6MatrixRecoveryTerrainDeviceGeneration = -1L;
        this.g6MatrixRecoveryTerrainFieldGeneration = -1L;
        this.g6MatrixRecoveryTerrainSourceTick = -1L;
        this.g6MatrixRecoveryReceiptBit = 0;
        this.g6MatrixRecoveryRequestedFrame = 0;
        this.g6MatrixRecoveryDeadline = 0;
        this.g6MatrixMeasurementAccountedBytes = -1L;
        this.g6MatrixFinalValidated = false;
        if (!this.g4ModePreapplied) {
            mode.apply();
        }
        GiTransportRuntime.beginBenchmarkWarmup();
        GiLiveRuntime.beginBenchmarkWarmup();
        MetalGpuTiming.beginBenchmarkWarmup(this.segmentIndex, mode.name());
        this.segmentFrame = 0;
        this.measuredFrames = 0;
        this.segmentPhase = SegmentPhase.WARMUP;
        this.boundaryCheckEvent = null;
        this.boundaryCheckFrames = 0;
        this.boundaryCheckToken = 0L;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=SEGMENT_START index={} total={} mode={} warmup={} measure={}",
                this.segmentIndex + 1,
                this.sequence.size(),
                mode,
                this.warmupFrames,
                this.measureFrames
        );
    }

    private void logSegmentEvent(final String event) {
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT={} index={} mode={} presented_frame={}",
                event,
                this.segmentIndex + 1,
                this.sequence.get(this.segmentIndex),
                this.segmentFrame
        );
    }

    /**
     * A reflection comparison is meaningful only after the single frozen field is bound.
     * Ordinary benchmark runs preserve the explicit OFF default and merely record it.
     */
    private @org.jspecify.annotations.Nullable String verifyVertexReflectionAdmission() {
        boolean enabled = VertexReflectionExperiment.isRuntimeEnabled();
        boolean faceAware = WaterReflectionQualityConfig.isFaceAwareAppearanceEnabled();
        boolean firstSurface = WaterReflectionQualityConfig.isFirstSurfaceBiasedIntegrationEnabled();
        boolean representationConfidence = WaterReflectionQualityConfig.isRepresentationConfidenceEnabled();
        FrozenReflectionFieldController.Snapshot snapshot = FrozenReflectionFieldController.global().snapshot();
        boolean ready = snapshot.state() == FrozenReflectionFieldController.State.READY;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=VERTEX_REFLECTION_ADMISSION enabled={} state={} ready={} generation={}/{} origin=[{},{},{}] sections={}/{}/{} expected={} invalidation={} quality_face_aware={} quality_first_surface={} quality_confidence={}",
                enabled,
                snapshot.state(),
                ready,
                snapshot.worldGeneration(),
                snapshot.fieldGeneration(),
                snapshot.originX(), snapshot.originY(), snapshot.originZ(),
                snapshot.publishedContent(), snapshot.knownEmpty(), snapshot.unavailable(), snapshot.expectedSections(),
                snapshot.invalidationReason(),
                faceAware,
                firstSurface,
                representationConfidence
        );
        if (enabled && !ready) {
            return "vertex reflection experiment was enabled but its frozen field was not READY";
        }
        if (enabled && (!matchesBooleanEnvironment(
                "METALLUM_BENCHMARK_WATER_REFLECTION_FACE_AWARE", faceAware)
                || !matchesBooleanEnvironment(
                        "METALLUM_BENCHMARK_WATER_REFLECTION_FIRST_SURFACE", firstSurface)
                || !matchesBooleanEnvironment(
                        "METALLUM_BENCHMARK_WATER_REFLECTION_CONFIDENCE", representationConfidence))) {
            return "vertex reflection quality did not match the benchmark launch contract";
        }
        return null;
    }

    private @org.jspecify.annotations.Nullable String verifyGiLiveWarmupAdmission() {
        if (!GiLiveRuntime.isRequested()) return null;
        GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();
        long currentDeviceGeneration = GiLiveRuntime.deviceGeneration();
        if (GiLiveRuntime.admissionState() == GiLiveRuntime.AdmissionState.INVALID) {
            return "G6 warmup became invalid: " + GiLiveRuntime.invalidReason() + "; "
                    + giG6CensusSummary(snapshot, currentDeviceGeneration)
                    + "; " + giG6RuntimeSummary();
        }
        if (GiLiveRuntime.admissionState() != GiLiveRuntime.AdmissionState.READY
                || !snapshot.admissionReceiptEmitted()
                || snapshot.admissionDeviceGeneration() != currentDeviceGeneration
                || snapshot.admissionSubmitIndex() < 0L) {
            return "G6 warmup ended without a current admission receipt; "
                    + giG6CensusSummary(snapshot, currentDeviceGeneration)
                    + "; " + giG6RuntimeSummary();
        }
        return null;
    }

    private static String giG6CensusSummary(
            final GiLiveRuntime.FinalSnapshot snapshot,
            final long currentDeviceGeneration
    ) {
        return String.format(
                Locale.ROOT,
                "state=%s current_device=%d telemetry_device=%d admission=%s/%d/%d "
                        + "ready=%d in_flight=%s field=%d source=%d stale=%d rejected=%d "
                        + "terrain=%s/%d/%d bind=%d carrier=%s frame=%s terrain_ready=%d "
                        + "terrain_exact=%s terrain_field=%d terrain_source=%d bytes=%d "
                        + "block_samples=%d block_p95=%d block_p99=%d block_sla=%s "
                        + "static_samples=%d static_p95=%d static_p99=%d static_sla=%s "
                        + "scroll_samples=%d scroll_p95=%d scroll_p99=%d scroll_sla=%s "
                        + "reset_samples=%d reset_p95=%d reset_p99=%d reset_sla=%s "
                        + "queue=%d/%d/%d/%d/%d queue_exact=%s measurement_bytes=%d",
                GiLiveRuntime.admissionState(), currentDeviceGeneration,
                snapshot.deviceGeneration(), snapshot.admissionReceiptEmitted(),
                snapshot.admissionDeviceGeneration(), snapshot.admissionSubmitIndex(),
                snapshot.readyMask(), snapshot.buildInFlight(), snapshot.fieldGeneration(),
                snapshot.sourceTick(), snapshot.staleRejects(), snapshot.rejectedCount(),
                snapshot.latestTerrainBindingObserved(),
                snapshot.latestTerrainDeviceGeneration(), snapshot.latestTerrainSubmitIndex(),
                snapshot.latestTerrainBindStatus(), snapshot.latestTerrainCarrierSafe(),
                snapshot.latestTerrainFrameCompatible(), snapshot.latestTerrainReadyMask(),
                snapshot.latestTerrainExactMaskNonzero(),
                snapshot.latestTerrainFieldGeneration(), snapshot.latestTerrainSourceTick(),
                snapshot.accountedBytes(), snapshot.blockSamples(), snapshot.blockP95Submits(),
                snapshot.blockP99Submits(), snapshot.blockSla(),
                snapshot.staticSourceSamples(), snapshot.staticSourceP95Submits(),
                snapshot.staticSourceP99Submits(), snapshot.staticSourceSla(),
                snapshot.scrollSamples(), snapshot.scrollP95Submits(),
                snapshot.scrollP99Submits(), snapshot.scrollSla(),
                snapshot.fullResetSamples(), snapshot.fullResetP95Submits(),
                snapshot.fullResetP99Submits(), snapshot.fullResetSla(),
                snapshot.schedulerQueued(), snapshot.schedulerCompleted(),
                snapshot.schedulerDiscarded(), snapshot.schedulerPending(),
                snapshot.schedulerInFlight(), snapshot.schedulerAlgebraExact(),
                snapshot.measurementStartAccountedBytes()
        );
    }

    private static String giG6RuntimeSummary() {
        MetalDevice device = MetalDevice.getInstance();
        return device == null ? "metal_device=null" : device.giLiveDebugSummary();
    }

    private static boolean matchesBooleanEnvironment(final String name, final boolean actual) {
        String expected = System.getenv(name);
        return expected == null || Boolean.parseBoolean(expected) == actual;
    }

    private String completeL6DynamicShadowCoverage() {
        if (this.route.l6DynamicShadow() == null) {
            return null;
        }
        L6DynamicShadowBenchmarkTelemetry.Snapshot snapshot =
                L6DynamicShadowBenchmarkTelemetry.end();
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=L6_DYNAMIC_COVERAGE route={} frames={} held_admitted_frames={} held_ready_frames={} dispatch_frames={} candidates_min={} candidates_max={} selected_min={} selected_max={} dropped_min={} dropped_max={} rays_min={} rays_max={} ready_min={} ready_max={} fallback_total={} coverage_miss_total={} failure_total={} pages_bytes_min={} pages_bytes_max={}",
                this.route.routeId(),
                snapshot.frames(),
                snapshot.heldAdmittedFrames(),
                snapshot.heldReadyFrames(),
                snapshot.dispatchFrames(),
                snapshot.candidatesMin(),
                snapshot.candidatesMax(),
                snapshot.selectedMin(),
                snapshot.selectedMax(),
                snapshot.droppedMin(),
                snapshot.droppedMax(),
                snapshot.raysMin(),
                snapshot.raysMax(),
                snapshot.readyMin(),
                snapshot.readyMax(),
                snapshot.fallbackTotal(),
                snapshot.coverageMissTotal(),
                snapshot.asyncFailureTotal(),
                snapshot.pageBytesMin(),
                snapshot.pageBytesMax()
        );
        if (snapshot.frames() != this.measureFrames) {
            return "L6 dynamic coverage observed " + snapshot.frames()
                    + " of " + this.measureFrames + " measured frames";
        }
        if (snapshot.heldAdmittedFrames() != this.measureFrames
                || snapshot.heldReadyFrames() != this.measureFrames
                || snapshot.dispatchFrames() != this.measureFrames) {
            return "L6 held dynamic shadow was not READY on every measured frame";
        }
        if (snapshot.fallbackTotal() != 0L
                || snapshot.coverageMissTotal() != 0L
                || snapshot.asyncFailureTotal() != 0L) {
            return "L6 dynamic coverage recorded fallback, coverage miss, or async failure";
        }
        return null;
    }

    private boolean beginFiGeneratedValidation(final Minecraft minecraft) {
        try {
            for (int selector = 0; selector < FI_TRANSPORT_COUNTER_COUNT; selector++) {
                this.fiTransportMeasurementStart[selector] =
                        MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(selector);
            }
            this.fiGeneratedMeasurementStart =
                    this.fiTransportMeasurementStart[FI_TRANSPORT_GENERATED_PRESENTATIONS];
            MetalDevice device = MetalDevice.getInstance();
            this.fiRuntimeMeasurementStart = device == null
                    ? null : device.frameInterpolationRuntimeStatus();
            if (this.fiRuntimeMeasurementStart == null) {
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=FI_RUNTIME_START state=MISSING reason=DEVICE_UNAVAILABLE"
                );
            } else {
                Metallum.LOGGER.info(
                        "METALLUM_BENCHMARK EVENT=FI_RUNTIME_START state={} reason={} session={} session_generated_presented={}",
                        this.fiRuntimeMeasurementStart.state(),
                        this.fiRuntimeMeasurementStart.reason(),
                        this.fiRuntimeMeasurementStart.sessionId(),
                        this.fiRuntimeMeasurementStart.presentedGeneratedCount()
                );
            }
            this.fiGeneratedMeasurementStarted = true;
            return true;
        } catch (RuntimeException | LinkageError unavailable) {
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK EVENT=FI_GENERATED_FAIL reason=counter_unavailable"
            );
            fail(minecraft, "FI generated presentation counter is unavailable");
            return false;
        }
    }

    private boolean completeFiGeneratedValidation(final Minecraft minecraft) {
        if (!this.fiGeneratedMeasurementStarted) {
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK EVENT=FI_GENERATED_FAIL reason=counter_start_missing"
            );
            fail(minecraft, "FI generated presentation counter was not sampled at MEASURE_START");
            return false;
        }
        final long acceptedPairsEnd;
        final long generatedEnd;
        final long realPresentationsEnd;
        final long droppedGeneratedLateEnd;
        final long backpressureDropsEnd;
        final long sourceAttemptsEnd;
        final long schedulerAcceptedEnd;
        final long schedulerWarmingEnd;
        final long schedulerTooSlowEnd;
        final long schedulerTooFastEnd;
        final long coordinatorRealOnlyEnd;
        final long interpolationFailuresEnd;
        final long rawCadenceTooSlowEnd;
        final long sourceDeltaNanosEnd;
        final long sourceDeltaSamplesEnd;
        final long outOfOrderPresentationsEnd;
        final long targetMissesEnd;
        final long[] cadenceDiagnosticsEnd = new long[FI_TRANSPORT_COUNTER_COUNT];
        try {
            acceptedPairsEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_ACCEPTED_PAIRS
            );
            generatedEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_GENERATED_PRESENTATIONS
            );
            realPresentationsEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_REAL_PRESENTATIONS
            );
            droppedGeneratedLateEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_DROPPED_GENERATED_LATE
            );
            backpressureDropsEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_BACKPRESSURE_DROPS
            );
            sourceAttemptsEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_SOURCE_ATTEMPTS
            );
            schedulerAcceptedEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_SCHEDULER_ACCEPTED
            );
            schedulerWarmingEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_SCHEDULER_WARMING
            );
            schedulerTooSlowEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_SCHEDULER_TOO_SLOW
            );
            schedulerTooFastEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_SCHEDULER_TOO_FAST
            );
            coordinatorRealOnlyEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_COORDINATOR_REAL_ONLY
            );
            interpolationFailuresEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_INTERPOLATION_FAILURES
            );
            rawCadenceTooSlowEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_RAW_CADENCE_TOO_SLOW
            );
            sourceDeltaNanosEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_SOURCE_DELTA_NANOS
            );
            sourceDeltaSamplesEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_SOURCE_DELTA_SAMPLES
            );
            outOfOrderPresentationsEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_OUT_OF_ORDER_PRESENTATIONS
            );
            targetMissesEnd = MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(
                    FI_TRANSPORT_TARGET_MISSES
            );
            for (int selector = FI_TRANSPORT_TIMED_INTERVALS;
                    selector < FI_TRANSPORT_COUNTER_COUNT;
                    selector++) {
                cadenceDiagnosticsEnd[selector] =
                        MetalNativeBridge.metallum_frame_interpolation_telemetry_counter_v1(selector);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK EVENT=FI_GENERATED_FAIL reason=counter_unavailable"
            );
            fail(minecraft, "FI generated presentation counter is unavailable");
            return false;
        }
        long acceptedPairsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_ACCEPTED_PAIRS], acceptedPairsEnd
        );
        long generatedDelta = frameInterpolationTransportCounterDelta(
                this.fiGeneratedMeasurementStart, generatedEnd
        );
        long realPresentationsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_REAL_PRESENTATIONS], realPresentationsEnd
        );
        long droppedGeneratedLateDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_DROPPED_GENERATED_LATE], droppedGeneratedLateEnd
        );
        long backpressureDropsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_BACKPRESSURE_DROPS], backpressureDropsEnd
        );
        long sourceAttemptsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SOURCE_ATTEMPTS], sourceAttemptsEnd
        );
        long schedulerAcceptedDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SCHEDULER_ACCEPTED], schedulerAcceptedEnd
        );
        long schedulerWarmingDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SCHEDULER_WARMING], schedulerWarmingEnd
        );
        long schedulerTooSlowDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SCHEDULER_TOO_SLOW], schedulerTooSlowEnd
        );
        long schedulerTooFastDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SCHEDULER_TOO_FAST], schedulerTooFastEnd
        );
        long coordinatorRealOnlyDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_COORDINATOR_REAL_ONLY], coordinatorRealOnlyEnd
        );
        long interpolationFailuresDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_INTERPOLATION_FAILURES], interpolationFailuresEnd
        );
        long rawCadenceTooSlowDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_RAW_CADENCE_TOO_SLOW], rawCadenceTooSlowEnd
        );
        long sourceDeltaNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SOURCE_DELTA_NANOS], sourceDeltaNanosEnd
        );
        long sourceDeltaSamplesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SOURCE_DELTA_SAMPLES], sourceDeltaSamplesEnd
        );
        long outOfOrderPresentationsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_OUT_OF_ORDER_PRESENTATIONS],
                outOfOrderPresentationsEnd
        );
        long targetMissesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TARGET_MISSES], targetMissesEnd
        );
        double sourceDeltaMeanMilliseconds = sourceDeltaSamplesDelta > 0L && sourceDeltaNanosDelta >= 0L
                ? (double) sourceDeltaNanosDelta / (double) sourceDeltaSamplesDelta / 1_000_000.0
                : -1.0;
        long timedIntervalsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TIMED_INTERVALS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_TIMED_INTERVALS]
        );
        long timedIntervalNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TIMED_INTERVAL_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_TIMED_INTERVAL_NANOS]
        );
        long generatedToRealIntervalsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_GENERATED_TO_REAL_INTERVALS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_GENERATED_TO_REAL_INTERVALS]
        );
        long generatedToRealNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_GENERATED_TO_REAL_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_GENERATED_TO_REAL_NANOS]
        );
        long generatedToRealMissesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_GENERATED_TO_REAL_MISSES],
                cadenceDiagnosticsEnd[FI_TRANSPORT_GENERATED_TO_REAL_MISSES]
        );
        long realToGeneratedIntervalsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_REAL_TO_GENERATED_INTERVALS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_REAL_TO_GENERATED_INTERVALS]
        );
        long realToGeneratedNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_REAL_TO_GENERATED_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_REAL_TO_GENERATED_NANOS]
        );
        long realToGeneratedMissesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_REAL_TO_GENERATED_MISSES],
                cadenceDiagnosticsEnd[FI_TRANSPORT_REAL_TO_GENERATED_MISSES]
        );
        long target120IntervalsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TARGET_120_INTERVALS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_TARGET_120_INTERVALS]
        );
        long target80IntervalsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TARGET_80_INTERVALS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_TARGET_80_INTERVALS]
        );
        long target60IntervalsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TARGET_60_INTERVALS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_TARGET_60_INTERVALS]
        );
        long intervalsOver22MillisecondsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_INTERVALS_OVER_22_MS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_INTERVALS_OVER_22_MS]
        );
        long timedTargetNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TIMED_TARGET_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_TIMED_TARGET_NANOS]
        );
        long timedMeanSlackNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_TIMED_MEAN_SLACK_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_TIMED_MEAN_SLACK_NANOS]
        );
        long severeLateIntervalsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SEVERE_LATE_INTERVALS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_SEVERE_LATE_INTERVALS]
        );
        long retargetBoundariesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_RETARGET_BOUNDARIES],
                cadenceDiagnosticsEnd[FI_TRANSPORT_RETARGET_BOUNDARIES]
        );
        long admissionWaitsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_ADMISSION_WAITS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_ADMISSION_WAITS]
        );
        long admissionWaitNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_ADMISSION_WAIT_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_ADMISSION_WAIT_NANOS]
        );
        long severeGeneratedToRealDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SEVERE_GENERATED_TO_REAL],
                cadenceDiagnosticsEnd[FI_TRANSPORT_SEVERE_GENERATED_TO_REAL]
        );
        long severeRealToGeneratedDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_SEVERE_REAL_TO_GENERATED],
                cadenceDiagnosticsEnd[FI_TRANSPORT_SEVERE_REAL_TO_GENERATED]
        );
        long severeOtherDelta = Math.max(
                severeLateIntervalsDelta - severeGeneratedToRealDelta - severeRealToGeneratedDelta,
                0L
        );
        long gateReleasesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_GATE_RELEASES],
                cadenceDiagnosticsEnd[FI_TRANSPORT_GATE_RELEASES]
        );
        long gateLatenessNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_GATE_LATENESS_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_GATE_LATENESS_NANOS]
        );
        long gateLatenessMaximumNanos = cadenceDiagnosticsEnd[FI_TRANSPORT_GATE_LATENESS_MAX_NANOS];
        long generatedDrawableWaitSamplesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_SAMPLES],
                cadenceDiagnosticsEnd[FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_SAMPLES]
        );
        long generatedDrawableWaitNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_NANOS]
        );
        long generatedDrawableWaitMaximumNanos =
                cadenceDiagnosticsEnd[FI_TRANSPORT_GENERATED_DRAWABLE_WAIT_MAX_NANOS];
        long realDrawableWaitSamplesDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_REAL_DRAWABLE_WAIT_SAMPLES],
                cadenceDiagnosticsEnd[FI_TRANSPORT_REAL_DRAWABLE_WAIT_SAMPLES]
        );
        long realDrawableWaitNanosDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_REAL_DRAWABLE_WAIT_NANOS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_REAL_DRAWABLE_WAIT_NANOS]
        );
        long realDrawableWaitMaximumNanos =
                cadenceDiagnosticsEnd[FI_TRANSPORT_REAL_DRAWABLE_WAIT_MAX_NANOS];
        long realOnlyPresentationsDelta = frameInterpolationTransportCounterDelta(
                this.fiTransportMeasurementStart[FI_TRANSPORT_REAL_ONLY_PRESENTATIONS],
                cadenceDiagnosticsEnd[FI_TRANSPORT_REAL_ONLY_PRESENTATIONS]
        );
        double timedIntervalMeanMilliseconds = timedIntervalsDelta > 0L
                ? (double) timedIntervalNanosDelta / (double) timedIntervalsDelta / 1_000_000.0
                : -1.0;
        double timedTargetMeanMilliseconds = timedIntervalsDelta > 0L
                ? (double) timedTargetNanosDelta / (double) timedIntervalsDelta / 1_000_000.0
                : -1.0;
        double timedMeanSlackMilliseconds = timedIntervalsDelta > 0L
                ? (double) timedMeanSlackNanosDelta / (double) timedIntervalsDelta / 1_000_000.0
                : -1.0;
        double generatedToRealMeanMilliseconds = generatedToRealIntervalsDelta > 0L
                ? (double) generatedToRealNanosDelta
                        / (double) generatedToRealIntervalsDelta / 1_000_000.0
                : -1.0;
        double realToGeneratedMeanMilliseconds = realToGeneratedIntervalsDelta > 0L
                ? (double) realToGeneratedNanosDelta
                        / (double) realToGeneratedIntervalsDelta / 1_000_000.0
                : -1.0;
        double admissionWaitMeanMilliseconds = admissionWaitsDelta > 0L
                && admissionWaitNanosDelta >= 0L
                ? (double) admissionWaitNanosDelta / (double) admissionWaitsDelta / 1_000_000.0
                : 0.0;
        double gateLatenessMeanMilliseconds = gateReleasesDelta > 0L
                ? (double) gateLatenessNanosDelta / (double) gateReleasesDelta / 1_000_000.0
                : 0.0;
        double generatedDrawableWaitMeanMilliseconds = generatedDrawableWaitSamplesDelta > 0L
                ? (double) generatedDrawableWaitNanosDelta
                        / (double) generatedDrawableWaitSamplesDelta / 1_000_000.0
                : 0.0;
        double realDrawableWaitMeanMilliseconds = realDrawableWaitSamplesDelta > 0L
                ? (double) realDrawableWaitNanosDelta
                        / (double) realDrawableWaitSamplesDelta / 1_000_000.0
                : 0.0;
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=FI_TRANSPORT_TELEMETRY accepted_pairs_delta={} generated_presentations_delta={} real_presentations_delta={} real_only_presentations_delta={} dropped_generated_late_delta={} backpressure_drops_delta={} out_of_order_presentations_delta={} target_misses_delta={}",
                acceptedPairsDelta,
                generatedDelta,
                realPresentationsDelta,
                realOnlyPresentationsDelta,
                droppedGeneratedLateDelta,
                backpressureDropsDelta,
                outOfOrderPresentationsDelta,
                targetMissesDelta
        );
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=FI_ADMISSION_TELEMETRY source_attempts_delta={} source_delta_samples_delta={} scheduler_accepted_delta={} scheduler_warming_delta={} scheduler_too_slow_delta={} scheduler_too_fast_delta={} coordinator_real_only_delta={} interpolation_failures_delta={} raw_cadence_too_slow_delta={} source_delta_mean_ms={} admission_waits_delta={} admission_wait_mean_ms={}",
                sourceAttemptsDelta,
                sourceDeltaSamplesDelta,
                schedulerAcceptedDelta,
                schedulerWarmingDelta,
                schedulerTooSlowDelta,
                schedulerTooFastDelta,
                coordinatorRealOnlyDelta,
                interpolationFailuresDelta,
                rawCadenceTooSlowDelta,
                sourceDeltaMeanMilliseconds,
                admissionWaitsDelta,
                admissionWaitMeanMilliseconds
        );
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=FI_ON_GLASS_CADENCE timed_intervals_delta={} timed_mean_ms={} target_mean_ms={} mean_slack_ms={} generated_to_real_intervals_delta={} generated_to_real_mean_ms={} generated_to_real_misses_delta={} real_to_generated_intervals_delta={} real_to_generated_mean_ms={} real_to_generated_misses_delta={} target_120_delta={} target_80_delta={} target_60_delta={} intervals_over_22_ms_delta={} severe_late_delta={} retarget_boundaries_delta={}",
                timedIntervalsDelta,
                timedIntervalMeanMilliseconds,
                timedTargetMeanMilliseconds,
                timedMeanSlackMilliseconds,
                generatedToRealIntervalsDelta,
                generatedToRealMeanMilliseconds,
                generatedToRealMissesDelta,
                realToGeneratedIntervalsDelta,
                realToGeneratedMeanMilliseconds,
                realToGeneratedMissesDelta,
                target120IntervalsDelta,
                target80IntervalsDelta,
                target60IntervalsDelta,
                intervalsOver22MillisecondsDelta,
                severeLateIntervalsDelta,
                retargetBoundariesDelta
        );
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=FI_PHASE_DIAGNOSTICS severe_generated_to_real_delta={} severe_real_to_generated_delta={} severe_other_delta={} gate_releases_delta={} gate_lateness_mean_ms={} gate_lateness_process_max_ms={} generated_drawable_wait_samples_delta={} generated_drawable_wait_mean_ms={} generated_drawable_wait_process_max_ms={} real_drawable_wait_samples_delta={} real_drawable_wait_mean_ms={} real_drawable_wait_process_max_ms={}",
                severeGeneratedToRealDelta,
                severeRealToGeneratedDelta,
                severeOtherDelta,
                gateReleasesDelta,
                gateLatenessMeanMilliseconds,
                gateLatenessMaximumNanos / 1_000_000.0,
                generatedDrawableWaitSamplesDelta,
                generatedDrawableWaitMeanMilliseconds,
                generatedDrawableWaitMaximumNanos / 1_000_000.0,
                realDrawableWaitSamplesDelta,
                realDrawableWaitMeanMilliseconds,
                realDrawableWaitMaximumNanos / 1_000_000.0
        );
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=FI_TELEMETRY generated_delta={}",
                generatedDelta
        );
        MetalDevice device = MetalDevice.getInstance();
        FrameInterpolationRuntimeStatus runtime = device == null
                ? null : device.frameInterpolationRuntimeStatus();
        if (runtime != null) {
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=FI_RUNTIME_STATUS state={} reason={} session={} session_generated_presented={}",
                    runtime.state(),
                    runtime.reason(),
                    runtime.sessionId(),
                    runtime.presentedGeneratedCount()
            );
        }
        if (this.fiRuntimeMeasurementStart != null
                && this.fiRuntimeMeasurementStart.state()
                == FrameInterpolationRuntimeStatus.State.UNAVAILABLE
                && isFrameInterpolationRuntimeFallbackReason(
                        this.fiRuntimeMeasurementStart.reason()
                )) {
            boolean safeFallback = generatedDelta == 0L
                    && realOnlyPresentationsDelta
                    >= Math.max(0L, this.measureFrames - FI_TRANSPORT_SNAPSHOT_TOLERANCE);
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT={} generated_tail_delta={} real_only_tail_delta={} measured_frames={} boundary_skew={} reason={}",
                    safeFallback ? "FI_FALLBACK_COMPLETE" : "FI_FALLBACK_FAIL",
                    generatedDelta,
                    realOnlyPresentationsDelta,
                    this.measureFrames,
                    FI_TRANSPORT_SNAPSHOT_TOLERANCE,
                    this.fiRuntimeMeasurementStart.reason()
            );
            if (!safeFallback) {
                fail(
                        minecraft,
                        "FI unavailable fallback did not preserve an uninterrupted real-only stream"
                );
                return false;
            }
        }
        // Safe fallback is a renderer-readiness proof, not an FI success.
        // The strict --fi-validation contract below still requires ACTIVE.
        if (runtime == null
                || runtime.state() != FrameInterpolationRuntimeStatus.State.ACTIVE) {
            String state = runtime == null ? "MISSING" : runtime.state().name();
            String reason = runtime == null ? "DEVICE_UNAVAILABLE" : runtime.reason().name();
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK EVENT=FI_RUNTIME_FAIL state={} reason={} real_only_presentations_delta={}",
                    state,
                    reason,
                    realOnlyPresentationsDelta
            );
            fail(
                    minecraft,
                    "FI did not reach and retain an on-glass Active state (state="
                            + state + ", reason=" + reason + ")"
            );
            return false;
        }
        if (!hasMinimumGeneratedPresentations(
                this.fiGeneratedMeasurementStart,
                generatedEnd,
                this.fiMinimumGenerated
        )) {
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK EVENT=FI_GENERATED_FAIL generated_delta={} minimum={}",
                    generatedDelta,
                    this.fiMinimumGenerated
            );
            fail(
                    minecraft,
                    "FI generated presentations below required minimum (generated_delta="
                            + generatedDelta + ", minimum=" + this.fiMinimumGenerated + ")"
            );
            return false;
        }
        if (!hasHealthyFrameInterpolationTransport(
                acceptedPairsDelta,
                generatedDelta,
                realPresentationsDelta,
                droppedGeneratedLateDelta,
                backpressureDropsDelta,
                outOfOrderPresentationsDelta,
                this.fiMinimumGenerated,
                this.measureFrames,
                FI_TRANSPORT_SNAPSHOT_TOLERANCE
        )) {
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK EVENT=FI_TRANSPORT_FAIL accepted_pairs_delta={} generated_presentations_delta={} real_presentations_delta={} backpressure_drops_delta={} out_of_order_presentations_delta={} target_misses_delta={} minimum_generated={} measured_frames={} boundary_skew={}",
                    acceptedPairsDelta,
                    generatedDelta,
                    realPresentationsDelta,
                    backpressureDropsDelta,
                    outOfOrderPresentationsDelta,
                    targetMissesDelta,
                    this.fiMinimumGenerated,
                    this.measureFrames,
                    FI_TRANSPORT_SNAPSHOT_TOLERANCE
            );
            fail(
                    minecraft,
                    "FI transport did not preserve generated/mandatory-real presentation "
                            + "without backpressure"
            );
            return false;
        }
        if (!hasHealthyFrameInterpolationCadence(
                generatedDelta,
                timedIntervalsDelta,
                timedIntervalNanosDelta,
                timedTargetNanosDelta,
                timedMeanSlackNanosDelta,
                generatedToRealIntervalsDelta,
                targetMissesDelta,
                severeLateIntervalsDelta,
                this.fiMinimumGenerated,
                FI_TRANSPORT_SNAPSHOT_TOLERANCE
        )) {
            Metallum.LOGGER.error(
                    "METALLUM_BENCHMARK EVENT=FI_CADENCE_FAIL generated_presentations_delta={} timed_intervals_delta={} timed_mean_ms={} target_mean_ms={} mean_slack_ms={} generated_to_real_intervals_delta={} stable_target_misses_delta={} severe_late_delta={} retarget_boundaries_delta={} minimum_generated={} boundary_skew={}",
                    generatedDelta,
                    timedIntervalsDelta,
                    timedIntervalMeanMilliseconds,
                    timedTargetMeanMilliseconds,
                    timedMeanSlackMilliseconds,
                    generatedToRealIntervalsDelta,
                    targetMissesDelta,
                    severeLateIntervalsDelta,
                    retargetBoundariesDelta,
                    this.fiMinimumGenerated,
                    FI_TRANSPORT_SNAPSHOT_TOLERANCE
            );
            fail(minecraft, "FI on-glass cadence did not meet the stable ProMotion contract");
            return false;
        }
        if (!hasPreferredFrameInterpolationMinorLateTail(
                timedIntervalsDelta,
                targetMissesDelta,
                FI_TRANSPORT_SNAPSHOT_TOLERANCE
        )) {
            Metallum.LOGGER.warn(
                    "METALLUM_BENCHMARK EVENT=FI_CADENCE_WARNING reason=minor_late_tail stable_target_misses_delta={} timed_intervals_delta={} preferred_maximum={}",
                    targetMissesDelta,
                    timedIntervalsDelta,
                    saturatedAdd(ceilDivide(timedIntervalsDelta, 20L),
                            FI_TRANSPORT_SNAPSHOT_TOLERANCE)
            );
        }
        Metallum.LOGGER.info(
                "METALLUM_BENCHMARK EVENT=FI_GENERATED_COMPLETE generated_delta={} minimum={}",
                generatedDelta,
                this.fiMinimumGenerated
        );
        return true;
    }

    static boolean isFrameInterpolationRuntimeFallbackReason(
            final FrameInterpolationRuntimeStatus.Reason reason
    ) {
        return reason == FrameInterpolationRuntimeStatus.Reason.ON_GLASS_CADENCE
                || reason == FrameInterpolationRuntimeStatus.Reason.ON_GLASS_TIMESTAMP
                || reason == FrameInterpolationRuntimeStatus.Reason.WARMUP_TIMEOUT;
    }

    /** A process-lifetime native counter must not regress inside one benchmark segment. */
    static long frameInterpolationTransportCounterDelta(final long start, final long end) {
        return end >= start ? end - start : -1L;
    }

    /**
     * Requires both useful generated output and the mandatory real stream to
     * reach glass. The tolerance accounts for a small callback tail around
     * process-counter snapshots; it is not used as a resource-lifetime proof.
     */
    static boolean hasHealthyFrameInterpolationTransport(
            final long acceptedPairs,
            final long generatedPresentations,
            final long realPresentations,
            final long droppedGeneratedLate,
            final long backpressureDrops,
            final long outOfOrderPresentations,
            final long minimumGenerated,
            final long measuredFrames,
            final long boundarySkew
    ) {
        if (acceptedPairs < 0L || generatedPresentations < 0L || realPresentations < 0L
                || droppedGeneratedLate < 0L || backpressureDrops < 0L
                || outOfOrderPresentations < 0L
                || minimumGenerated < 0L || measuredFrames < 0L
                || boundarySkew < 0L) {
            return false;
        }
        long minimumReal = Math.max(measuredFrames - boundarySkew, 0L);
        long minimumRealForGenerated = Math.max(generatedPresentations - boundarySkew, 0L);
        long acceptedGeneratedDifference = acceptedPairs >= generatedPresentations
                ? acceptedPairs - generatedPresentations
                : generatedPresentations - acceptedPairs;
        long maximumAcceptedGeneratedDifference = boundarySkew > Long.MAX_VALUE / 2L
                ? Long.MAX_VALUE : boundarySkew * 2L;
        return acceptedPairs >= minimumGenerated
                && generatedPresentations >= minimumGenerated
                && realPresentations >= minimumReal
                && realPresentations >= minimumRealForGenerated
                && acceptedGeneratedDifference <= maximumAcceptedGeneratedDifference
                && droppedGeneratedLate == 0L
                && backpressureDrops == 0L
                && outOfOrderPresentations == 0L;
    }

    /**
     * Validates only stable-target CAMetalDrawable callback intervals. Legal
     * ProMotion step alternation is judged by its aggregate mean, while the
     * severe p99 tail remains independently bounded. The preferred p95 minor
     * tail is reported separately as a quality warning. Target changes are
     * excluded natively instead of being mispriced against the newer target.
     */
    static boolean hasHealthyFrameInterpolationCadence(
            final long generatedPresentations,
            final long timedIntervals,
            final long observedNanoseconds,
            final long targetNanoseconds,
            final long meanSlackNanoseconds,
            final long generatedToRealIntervals,
            final long stableTargetMisses,
            final long severeLateIntervals,
            final long minimumGenerated,
            final long boundarySkew
    ) {
        if (generatedPresentations < 0L || timedIntervals < 0L
                || observedNanoseconds < 0L || targetNanoseconds <= 0L
                || meanSlackNanoseconds < 0L || generatedToRealIntervals < 0L
                || stableTargetMisses < 0L || severeLateIntervals < 0L
                || minimumGenerated < 0L || boundarySkew < 0L) {
            return false;
        }
        long minimumTimedIntervals = minimumGenerated > Long.MAX_VALUE / 2L
                ? Long.MAX_VALUE : minimumGenerated * 2L;
        long minimumGeneratedToReal = Math.max(minimumGenerated - boundarySkew, 0L);
        long snapshotAllowance = saturatedMultiply(
                boundarySkew,
                FI_CADENCE_SNAPSHOT_INTERVAL_NANOS
        );
        long aggregateBudget = saturatedAdd(
                saturatedAdd(targetNanoseconds, meanSlackNanoseconds),
                snapshotAllowance
        );
        long maximumSevereLate = saturatedAdd(ceilDivide(timedIntervals, 100L), boundarySkew);
        return generatedPresentations >= minimumGenerated
                && timedIntervals >= minimumTimedIntervals
                && generatedToRealIntervals >= minimumGeneratedToReal
                && observedNanoseconds <= aggregateBudget
                && severeLateIntervals <= maximumSevereLate;
    }

    /** Preferred p95 QoS target; reported as a warning, not FI delivery failure. */
    static boolean hasPreferredFrameInterpolationMinorLateTail(
            final long timedIntervals,
            final long stableTargetMisses,
            final long boundarySkew
    ) {
        if (timedIntervals <= 0L || stableTargetMisses < 0L || boundarySkew < 0L) {
            return false;
        }
        return stableTargetMisses <= saturatedAdd(
                ceilDivide(timedIntervals, 20L),
                boundarySkew
        );
    }

    private static long ceilDivide(final long value, final long divisor) {
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    private static long saturatedAdd(final long left, final long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedMultiply(final long left, final long right) {
        return left != 0L && right > Long.MAX_VALUE / left ? Long.MAX_VALUE : left * right;
    }

    /**
     * The native counter advances only from CAMetalDrawable presented handlers
     * for generated frames. It deliberately excludes the source and real
     * frames that keep interpolation fail-open.
     */
    static boolean hasMinimumGeneratedPresentations(
            final long generatedStart,
            final long generatedEnd,
            final long minimum
    ) {
        return minimum >= 0L
                && Long.compareUnsigned(generatedEnd, generatedStart) >= 0
                && Long.compareUnsigned(generatedEnd - generatedStart, minimum) >= 0;
    }

    private void fail(final Minecraft minecraft, final String reason) {
        Metallum.LOGGER.error("METALLUM_BENCHMARK EVENT=FAIL reason={}", reason);
        finish(minecraft);
    }

    private void finish(final Minecraft minecraft) {
        if (this.stage == Stage.STOPPING) {
            return;
        }
        this.stage = Stage.STOPPING;
        if (TorchEpochTelemetry.snapshot().active()) {
            TorchEpochTelemetry.abort();
        }
        L6DynamicShadowBenchmarkTelemetry.abort();
        SodiumRelightOracle.abortObservation();
        SodiumRelightFastPath.abortObservation();
        restoreSurvivalGuard(minecraft);
        restoreG6MatrixNetherPreparation(minecraft);
        clearL6DynamicShadowRoute(minecraft);
        if (this.originalClientStateCaptured && this.originalCameraType != null) {
            minecraft.options.setCameraType(this.originalCameraType);
            if (this.originalCameraEntity != null) {
                minecraft.setCameraEntity(this.originalCameraEntity);
            }
        }
        minecraft.getWindow().setPreferredFullscreenVideoMode(this.originalFullscreenMode);
        BenchmarkScalingMode.clearOverrides();
        if (this.resolutionOverlayOverridden) {
            MetalFxUpscaling.setResolutionOverlayEnabled(this.originalResolutionOverlayEnabled);
            this.resolutionOverlayOverridden = false;
        }
        minecraft.stop();
    }

    private void lockPlayerPose(final Minecraft minecraft) {
        if (this.route == null || minecraft.player == null) {
            return;
        }
        if (this.route.g6Matrix() != null && this.segmentPhase == SegmentPhase.MEASURE) {
            return;
        }
        if (this.route.l6DynamicShadow() == null) {
            double x = this.route.x();
            double y = this.route.y();
            double z = this.route.z();
            minecraft.player.setPos(x, y, z);
            minecraft.player.xOld = x;
            minecraft.player.yOld = y;
            minecraft.player.zOld = z;
            minecraft.player.setDeltaMovement(0.0, 0.0, 0.0);
        }
        boolean rotating = "nether-lava-stress-v1".equals(this.route.routeId())
                && "1".equals(System.getenv("METALLUM_BENCHMARK_NETHER_ROTATE"));
        if (this.route.l6DynamicShadow() == null && !rotating) {
            float yaw = this.route.yaw();
            float pitch = this.route.pitch();
            minecraft.player.setYRot(yaw);
            minecraft.player.setXRot(pitch);
            minecraft.player.yRotO = yaw;
            minecraft.player.xRotO = pitch;
        }
    }

    private void maintainSurvivalGuard(final Minecraft minecraft) {
        if (this.survivalGuardApplied) {
            return;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            this.survivalGuardFailure = "benchmark requires an integrated singleplayer server";
            return;
        }
        UUID playerId = minecraft.player.getUUID();
        if (this.guardedPlayerId != null && !this.guardedPlayerId.equals(playerId)) {
            this.survivalGuardFailure = "benchmark player identity changed";
            return;
        }
        this.guardedPlayerId = playerId;
        if (!this.survivalGuardTaskPending.compareAndSet(false, true)) {
            return;
        }
        server.executeIfPossible(() -> {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                if (player == null) {
                    this.survivalGuardFailure = "benchmark server player is unavailable";
                    return;
                }
                if (!this.survivalGuardApplied) {
                    this.originalInvulnerable = player.isInvulnerable();
                    this.originalHealth = player.getHealth();
                    this.originalFoodLevel = player.getFoodData().getFoodLevel();
                    this.originalSaturation = player.getFoodData().getSaturationLevel();
                    this.survivalGuardApplied = true;
                    Metallum.LOGGER.info(
                            "METALLUM_BENCHMARK EVENT=SURVIVAL_GUARD_APPLIED player={} original_health={}",
                            playerId,
                            this.originalHealth
                    );
                }
                player.setInvulnerable(true);
                player.setHealth(player.getMaxHealth());
                player.getFoodData().setFoodLevel(20);
                player.getFoodData().setSaturation(20.0f);
            } catch (RuntimeException exception) {
                this.survivalGuardFailure = "benchmark survival guard failed: " + exception.getClass().getSimpleName();
                Metallum.LOGGER.error("METALLUM_BENCHMARK survival guard failed", exception);
            } finally {
                this.survivalGuardTaskPending.set(false);
            }
        });
    }

    private void restoreSurvivalGuard(final Minecraft minecraft) {
        IntegratedServer server = minecraft.getSingleplayerServer();
        UUID playerId = this.guardedPlayerId;
        if (server == null || playerId == null) {
            return;
        }
        server.executeBlocking(() -> {
            // executeBlocking is ordered after any already queued guard task.
            // Checking inside the server task closes the shutdown race where
            // the initial guard was applied just after finish() began.
            if (!this.survivalGuardApplied) {
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                return;
            }
            player.setInvulnerable(this.originalInvulnerable);
            player.setHealth(Math.min(this.originalHealth, player.getMaxHealth()));
            player.getFoodData().setFoodLevel(this.originalFoodLevel);
            player.getFoodData().setSaturation(this.originalSaturation);
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=SURVIVAL_GUARD_RESTORED player={} health={}",
                    playerId,
                    this.originalHealth
            );
        });
        this.survivalGuardApplied = false;
        this.survivalGuardTaskPending.set(false);
    }

    private void transition(final Stage next) {
        this.stage = next;
        this.stageFrames = 0;
        if (next == Stage.WAIT_ROUTE) {
            this.g4SourceReceiptFrames = 0;
        }
    }

    private static int positiveInt(final String name, final int defaultValue) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            int parsed = Integer.parseInt(raw);
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String requiredEnv(final String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String requiredMatching(final String name, final Pattern pattern) {
        String value = requiredEnv(name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " has an invalid value");
        }
        return value;
    }

    private static double finiteDouble(final String name) {
        try {
            double value = Double.parseDouble(requiredEnv(name));
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
    }

    private static float finiteFloat(final String name) {
        try {
            float value = Float.parseFloat(requiredEnv(name));
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
    }

    private static double positiveFiniteDouble(final String name) {
        double value = finiteDouble(name);
        if (value <= 0.0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    private static float positiveFiniteFloat(final String name) {
        float value = finiteFloat(name);
        if (value <= 0.0f) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    private static long nonNegativeLong(final String name) {
        try {
            long value = Long.parseLong(requiredEnv(name));
            if (value < 0L) {
                throw new IllegalArgumentException(name + " must be >= 0");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static int positiveIntStrict(final String name) {
        try {
            int value = Integer.parseInt(requiredEnv(name));
            if (value <= 0) {
                throw new IllegalArgumentException(name + " must be > 0");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static int integer(final String name) {
        try {
            return Integer.parseInt(requiredEnv(name));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static int nonNegativeIntStrict(final String name) {
        try {
            int value = Integer.parseInt(requiredEnv(name));
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be >= 0");
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }

    private static boolean requiredBoolean(final String name) {
        String value = requiredEnv(name);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static boolean optionalBoolean(final String name, final boolean defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(name + " must be true or false");
    }

    private static List<String> requiredCsv(final String name) {
        String value = requiredEnv(name);
        List<String> result = List.of(value.split(",", -1));
        if (result.stream().anyMatch(String::isBlank)
                || Set.copyOf(result).size() != result.size()) {
            throw new IllegalArgumentException(name + " must contain unique non-empty values");
        }
        return result;
    }

    private static String env(final String name, final String defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
