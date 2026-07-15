package com.metallum.client.benchmark;

import com.metallum.Metallum;
import com.metallum.client.metal.render.MetalGpuTiming;
import com.metallum.client.metalfx.MetalFxSpatialScaling;
import com.metallum.client.metalfx.SpatialScalingMode;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Environment-gated, deterministic 5K benchmark driver.
 *
 * <p>The mixin which owns this controller is not applied unless
 * {@code METALLUM_BENCHMARK=1}, so normal clients have no transformed hot path.
 */
public final class MetalFxBenchmarkController {
    private static final int WINDOW_TRANSITION_TIMEOUT_FRAMES = 240;
    private static final int ROUTE_SERVER_CHECK_INTERVAL_FRAMES = 30;
    private static final int WINDOWED_WIDTH = 1280;
    private static final int WINDOWED_HEIGHT = 720;
    private static final String BENCHMARK_PLAYER_NAME = "MetallumBench";
    private static final UUID BENCHMARK_PLAYER_UUID = UUID.fromString("b07a402a-d8ea-354f-9398-aaf208a798b9");
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

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

    private enum WorkloadKind {
        STATIC,
        TORCH_EPOCH,
        TORCH_TOGGLE;

        private static WorkloadKind fromEnvironment() {
            try {
                return valueOf(requiredEnv("METALLUM_BENCHMARK_ROUTE_KIND"));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "METALLUM_BENCHMARK_ROUTE_KIND must be STATIC, TORCH_EPOCH, or TORCH_TOGGLE",
                        exception
                );
            }
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
            boolean simulationFrozen,
            int stableFrames,
            int timeoutFrames,
            double positionEpsilon,
            float angleEpsilon,
            WorkloadKind workloadKind,
            TorchEpochConfig torchEpoch
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
            TorchEpochConfig torchEpoch = workloadKind == WorkloadKind.STATIC
                    ? null
                    : TorchEpochConfig.fromEnvironment(workloadKind);
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
                    simulationFrozen,
                    stableFrames,
                    timeoutFrames,
                    positionEpsilon,
                    angleEpsilon,
                    workloadKind,
                    torchEpoch
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
    private final boolean useCurrentWindow;
    private final boolean captureScreenshots;
    private final List<SpatialScalingMode> sequence;
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
    private long targetMonitor;
    private VideoMode targetVideoMode;
    private Optional<VideoMode> originalFullscreenMode = Optional.empty();
    private CameraType originalCameraType;
    private Entity originalCameraEntity;
    private boolean routeClientStateApplied;
    private final AtomicBoolean routeServerTaskPending = new AtomicBoolean();
    private boolean routeApplyRequested;
    private boolean routeApplyLogged;
    private long routeApplyToken;
    private long nextRouteServerToken;
    private volatile long completedRouteServerToken;
    private volatile String routeServerMismatch;
    private volatile String routeServerFailure;
    private int routeServerCheckCountdown;
    private int routeStableFrames;
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
    private boolean armed;
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
        this.useCurrentWindow = "1".equals(System.getenv("METALLUM_BENCHMARK_CURRENT_WINDOW"));
        this.captureScreenshots = "1".equals(System.getenv("METALLUM_BENCHMARK_SCREENSHOTS"));
        this.expectedFramebufferWidth = this.targetWidth;
        this.expectedFramebufferHeight = this.targetHeight;

        List<SpatialScalingMode> parsed = new ArrayList<>();
        try {
            for (String value : env("METALLUM_BENCHMARK_SEQUENCE", "OFF").split(",")) {
                SpatialScalingMode mode = SpatialScalingMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
                if (!mode.concrete()) {
                    throw new IllegalArgumentException("AUTO is not a benchmark preset");
                }
                parsed.add(mode);
            }
            if (parsed.isEmpty()) {
                error = "benchmark sequence is empty";
            }
        } catch (IllegalArgumentException exception) {
            if (error == null) {
                error = "invalid METALLUM_BENCHMARK_SEQUENCE";
            }
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
        this.sequence = List.copyOf(parsed);
        this.route = parsedRoute;
        this.configurationError = error;
    }

    public void arm() {
        if (this.armed) {
            return;
        }
        this.armed = true;
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
        String runtimeMismatch = runtimePacingMismatch(minecraft);
        if (runtimeMismatch != null) {
            fail(minecraft, runtimeMismatch);
            return;
        }
        if (!isTargetFramebuffer(minecraft.getWindow())) {
            fail(minecraft, "benchmark framebuffer changed during measurement");
            return;
        }

        switch (this.segmentPhase) {
            case WARMUP -> {
                this.segmentFrame++;
                if (this.segmentFrame >= this.warmupFrames) {
                    beginBoundaryCheck(minecraft, RouteCheckEvent.MEASURE_START);
                }
            }
            case WAIT_MEASURE_START_CHECK, WAIT_MEASURE_END_CHECK -> pollBoundaryCheck(minecraft);
            case MEASURE -> {
                this.segmentFrame++;
                this.measuredFrames++;
                driveTorchEpoch(minecraft);
                if (this.stage != Stage.RUNNING) {
                    return;
                }
                if (this.measuredFrames >= this.measureFrames) {
                    logSegmentEvent("MEASURE_END");
                    MetalGpuTiming.completeBenchmark(
                            this.segmentIndex,
                            this.sequence.get(this.segmentIndex)
                    );
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
            fail(minecraft, "timed out waiting for exact external 5K framebuffer");
        }
    }

    private void waitForRoute(final Minecraft minecraft) {
        if (this.route == null) {
            fail(minecraft, "deterministic route configuration is unavailable");
            return;
        }
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

        if (this.routeApplyLogged && this.routeServerCheckCountdown-- <= 0) {
            if (!this.routeServerTaskPending.get()) {
                submitRouteServerCheck(minecraft, false);
                this.routeServerCheckCountdown = ROUTE_SERVER_CHECK_INTERVAL_FRAMES;
            }
        }

        String clientMismatch = clientRouteMismatch(minecraft, true);
        if (this.routeApplyLogged
                && this.routeServerMismatch == null
                && clientMismatch == null) {
            this.routeStableFrames++;
        } else {
            this.routeStableFrames = 0;
        }
        if (this.routeStableFrames >= this.route.stableFrames()
                && !this.routeServerTaskPending.get()) {
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
            fail(
                    minecraft,
                    "deterministic route did not stabilize before timeout"
                            + (reason == null ? "" : ": " + reason)
            );
        }
    }

    private void beginBoundaryCheck(
            final Minecraft minecraft,
            final RouteCheckEvent event
    ) {
        this.boundaryCheckEvent = event;
        this.boundaryCheckFrames = 0;
        this.boundaryCheckToken = submitRouteServerCheck(minecraft, false);
        this.segmentPhase = event == RouteCheckEvent.MEASURE_START
                ? SegmentPhase.WAIT_MEASURE_START_CHECK
                : SegmentPhase.WAIT_MEASURE_END_CHECK;
    }

    private void pollBoundaryCheck(final Minecraft minecraft) {
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
            MetalGpuTiming.beginBenchmarkMeasurement(
                    this.segmentIndex,
                    this.sequence.get(this.segmentIndex)
            );
            if (this.captureScreenshots) {
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
            Metallum.LOGGER.info(
                    "METALLUM_BENCHMARK EVENT=COMPLETE segments={} measured_frames={} framebuffer={}x{}",
                    this.sequence.size(),
                    this.sequence.size() * this.measureFrames,
                    this.expectedFramebufferWidth,
                    this.expectedFramebufferHeight
            );
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
            server.executeIfPossible(() -> {
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
        ServerLevel level = server.getLevel(this.route.dimension());
        if (level == null) {
            return "benchmark dimension is unavailable";
        }
        ServerPlayer player = server.getPlayerList().getPlayer(this.route.playerUuid());
        if (player == null) {
            return "benchmark server player is unavailable";
        }
        if (!this.route.playerName().equals(player.getGameProfile().name())
                || !this.route.playerUuid().equals(player.getGameProfile().id())) {
            return "benchmark server player identity differs from the route";
        }

        Holder<WorldClock> clock = level.dimensionType().defaultClock().orElse(null);
        if (clock == null) {
            return "benchmark dimension has no default clock";
        }
        int chunkX = Mth.floor(this.route.x()) >> 4;
        int chunkZ = Mth.floor(this.route.z()) >> 4;
        if (apply) {
            server.tickRateManager().setFrozen(true);
            level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
            level.getGameRules().set(GameRules.ADVANCE_TIME, false, server);
            level.getGameRules().set(GameRules.ADVANCE_WEATHER, false, server);
            server.clockManager().setTotalTicks(clock, this.route.clockTicks());
            server.clockManager().setPaused(clock, true);
            server.setWeatherParameters(this.route.clearWeatherTicks(), 0, false, false);
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
            if (!teleported) {
                return "server rejected the benchmark teleport";
            }
            player.setDeltaMovement(0.0, 0.0, 0.0);
        }
        return serverRouteMismatch(server, level, player, clock, chunkX, chunkZ);
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
            return "server player is in a different dimension";
        }
        if (!samePose(player)) {
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
        ClockState clockState = server.clockManager().packState().clocks().get(clock);
        if (clockState == null
                || !clockState.paused()
                || clockState.totalTicks() != this.route.clockTicks()) {
            return "server clock differs from the paused route clock";
        }
        if (level.getWeatherData().isRaining()
                || level.getWeatherData().isThundering()
                || level.getWeatherData().getClearWeatherTime() != this.route.clearWeatherTicks()
                || level.getRainLevel(1.0f) != 0.0f
                || level.getThunderLevel(1.0f) != 0.0f) {
            return "server weather is not frozen and clear";
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
            return "client player is in a different dimension";
        }
        if (!samePose(minecraft.player)) {
            return "client player pose differs from the route (expected "
                    + routePose() + ", found " + entityPose(minecraft.player) + ")";
        }
        if (minecraft.level.getDefaultClockTime() != this.route.clockTicks()) {
            return "client clock differs from the route";
        }
        if (!minecraft.level.tickRateManager().isFrozen()) {
            return "client simulation ticks are not frozen";
        }
        if (minecraft.level.getRainLevel(1.0f) != 0.0f
                || minecraft.level.getThunderLevel(1.0f) != 0.0f) {
            return "client weather is not clear";
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
                || minecraft.options.enableVsync().get()
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
        return Math.abs(entity.getX() - this.route.x()) <= this.route.positionEpsilon()
                && Math.abs(entity.getY() - this.route.y()) <= this.route.positionEpsilon()
                && Math.abs(entity.getZ() - this.route.z()) <= this.route.positionEpsilon()
                && Math.abs(Mth.wrapDegrees(entity.getYRot() - this.route.yaw()))
                <= this.route.angleEpsilon()
                && Math.abs(Mth.wrapDegrees(entity.getXRot() - this.route.pitch()))
                <= this.route.angleEpsilon();
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
        if (!this.useCurrentWindow && GLFW.glfwGetWindowMonitor(window.handle()) != this.targetMonitor) {
            return false;
        }
        GLFW.glfwGetFramebufferSize(
                window.handle(),
                this.framebufferWidthScratch,
                this.framebufferHeightScratch
        );
        return this.framebufferWidthScratch[0] == this.expectedFramebufferWidth
                && this.framebufferHeightScratch[0] == this.expectedFramebufferHeight
                && window.getWidth() == this.expectedFramebufferWidth
                && window.getHeight() == this.expectedFramebufferHeight;
    }

    private void startSegment() {
        SpatialScalingMode mode = this.sequence.get(this.segmentIndex);
        TorchEpochTelemetry.abort();
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
        MetalFxSpatialScaling.setBenchmarkOverride(mode);
        MetalGpuTiming.beginBenchmarkWarmup(this.segmentIndex, mode);
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
        SodiumRelightOracle.abortObservation();
        SodiumRelightFastPath.abortObservation();
        restoreSurvivalGuard(minecraft);
        if (this.originalClientStateCaptured && this.originalCameraType != null) {
            minecraft.options.setCameraType(this.originalCameraType);
            if (this.originalCameraEntity != null) {
                minecraft.setCameraEntity(this.originalCameraEntity);
            }
        }
        minecraft.getWindow().setPreferredFullscreenVideoMode(this.originalFullscreenMode);
        MetalFxSpatialScaling.clearBenchmarkOverride();
        minecraft.stop();
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
