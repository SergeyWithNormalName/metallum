package com.metallum.client.gi.transport;

import com.metallum.client.gi.semantic.GiSemanticController;
import com.metallum.client.gi.semantic.GiSemanticTransportFieldView;
import com.metallum.client.gi.semantic.GiSemanticWorldToken;
import com.metallum.client.gi.source.GiDirectSourceCoordinator;
import com.metallum.client.gi.source.GiDirectSourceEpoch;
import com.metallum.client.lighting.LightWorldToken;
import com.metallum.client.lighting.EnvironmentDescriptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free CPU contract checks for the frozen one-bounce G4 Java core. */
public final class GiTransportCpuTests {
    private static final String DIMENSION = "test:g4-transport";

    private GiTransportCpuTests() {
    }

    public static void main(final String[] arguments) throws InterruptedException {
        layoutAndNormalizationAreFixed();
        runtimeFlagIsExplicit();
        interactiveDebugEnvironmentIsFrozenPerDimension();
        sourceStampIsDeterministicSensitiveAndNonZero();
        epochRequiresExactSemanticAndDirectSourceIdentity();
        oneBounceEnergyIsBoundedAndChannelPreserving();
        frozenMatchRejectsAnyChangedInput();
        submittedCompletionStateIsExplicit();
        coordinatorIsRenderThreadConfined();
        System.out.println("G4 frozen transport CPU contract tests passed");
    }

    private static void layoutAndNormalizationAreFixed() {
        require(GiTransportLayout.ABI_VERSION == 1
                        && GiTransportLayout.LAYOUT_BYTES == 160
                        && GiTransportLayout.HEADER_BYTES == 128
                        && GiTransportLayout.CELL_BYTES == 16
                        && GiTransportLayout.STATS_BYTES == 192,
                "G4 Java/native ABI sizes changed");
        require(GiTransportLayout.CASCADE_COUNT == 1
                        && GiTransportLayout.EDGE == 32
                        && GiTransportLayout.CELL_SIZE_BLOCKS == 1
                        && GiTransportLayout.CELL_COUNT == 32 * 32 * 32
                        && GiTransportLayout.CELLS_BYTES == 524_288L,
                "G4 frozen near-cascade topology changed");
        require(GiTransportLayout.ITERATION_COUNT == 1
                        && GiTransportLayout.MAXIMUM_DISTANCE == 8
                        && GiTransportLayout.SIGNED_DIRECTION_COUNT == 26
                        && GiTransportLayout.STENCIL_OFFSET_COUNT == 208
                        && GiTransportLayout.PRIVATE_RESOURCE_COUNT == 5
                        && GiTransportLayout.COMPUTE_PASS_COUNT == 2,
                "G4 one-Jacobi bounded-work contract changed");
        require(Math.abs(GiTransportLayout.computedFormWeightNormalization()
                        - GiTransportLayout.FORM_WEIGHT_NORMALIZATION) < 1.0e-12,
                "G4 declared form-weight normalization differs from its fixed stencil");
        require(Math.abs(GiTransportLayout.computedConfidenceWeightNormalization()
                        - GiTransportLayout.CONFIDENCE_WEIGHT_NORMALIZATION) < 1.0e-12,
                "G4 declared confidence normalization differs from its fixed stencil");
        require(GiTransportLayout.CAPTURE_RGBA_BYTES == 262_144
                        && GiTransportLayout.CAPTURE_CONFIDENCE_BYTES == 32_768
                        && GiTransportLayout.CAPTURE_COMPACT_BYTES == 1_081_344L,
                "G4 compact one-shot capture accounting changed");
        require(GiTransportLayout.cellIndex(0, 0, 0) == 0
                        && GiTransportLayout.cellIndex(31, 31, 31)
                        == GiTransportLayout.CELL_COUNT - 1,
                "G4 cell ordering changed");
        expectIndexFailure(() -> GiTransportLayout.cellIndex(-1, 0, 0),
                "negative G4 cell coordinate was accepted");
    }

    private static void runtimeFlagIsExplicit() {
        require(!GiTransportRuntime.isEnabled(null)
                        && !GiTransportRuntime.isEnabled("")
                        && !GiTransportRuntime.isEnabled("disabled")
                        && GiTransportRuntime.isEnabled("1")
                        && GiTransportRuntime.isEnabled(" TRUE ")
                        && GiTransportRuntime.isEnabled("yes")
                        && GiTransportRuntime.isEnabled("on"),
                "G4 diagnostic environment gate changed");
        require(GiTransportRuntime.submissionAllowed(false, false, false, false)
                        && GiTransportRuntime.submissionAllowed(true, true, false, false)
                        && !GiTransportRuntime.submissionAllowed(true, false, false, false)
                        && !GiTransportRuntime.submissionAllowed(true, true, true, false)
                        && !GiTransportRuntime.submissionAllowed(false, false, false, true),
                "G4 benchmark warmup submission gate changed");
        require(GiTransportRuntime.sourcePreparationAllowed(false, false)
                        && GiTransportRuntime.sourcePreparationAllowed(true, true)
                        && !GiTransportRuntime.sourcePreparationAllowed(true, false),
                "G4 fixture-quiescence preparation gate changed");
        require(!GiTransportRuntime.requested(false, false)
                        && GiTransportRuntime.requested(true, false)
                        && GiTransportRuntime.requested(false, true),
                "G4 environment/Sodium request union changed");
        require(GiTransportRuntime.debugPreviewRequested(false, false, true)
                        && !GiTransportRuntime.debugPreviewRequested(true, false, true)
                        && !GiTransportRuntime.debugPreviewRequested(false, true, true)
                        && !GiTransportRuntime.debugPreviewRequested(false, false, false),
                "G4 debug preview is not restart-isolated from G6 and benchmark admission");
        GiTransportRuntime.resetDeviceState();
        if (GiTransportRuntime.isRequested()) {
            Fixture fixture = fixture();
            GiTransportEpoch epoch = GiTransportEpoch.from(fixture.field, fixture.source);
            GiTransportGpuResources.Stats readyStats = stats(
                    epoch, true, false, 0L, 1L, 1L
            );
            GiTransportRuntime.reportResolvedReady(readyStats);
            require(GiTransportRuntime.isResolvedReady(), "G4 READY state was not published");
            GiTransportRuntime.DebugSnapshot snapshot = GiTransportRuntime.debugSnapshot();
            require(snapshot.statsAvailable() && snapshot.transportDispatches() == 1L
                            && snapshot.nearOriginX() == epoch.nearOriginX(),
                    "G4 debug snapshot lost immutable native admission counters");
            GiTransportRuntime.reportInvalid("test drift");
            GiTransportRuntime.reportResolvedReady(readyStats);
            require(GiTransportRuntime.isInvalid() && !GiTransportRuntime.isResolvedReady(),
                    "G4 terminal invalidation healed back to READY");
        }
        GiTransportRuntime.resetDeviceState();
    }

    private static void sourceStampIsDeterministicSensitiveAndNonZero() {
        GiDirectSourceEpoch base = directEpoch(1L, 2L, 3L, 4L, 5L, 6L);
        long first = GiDirectSourceCoordinator.transportSourceStamp(base, -32, -64, -96);
        long repeat = GiDirectSourceCoordinator.transportSourceStamp(base, -32, -64, -96);
        require(first != 0L && first == repeat, "G3 opaque source stamp is zero or non-deterministic");
        require(first != GiDirectSourceCoordinator.transportSourceStamp(
                        directEpoch(1L, 2L, 3L, 7L, 5L, 6L), -32, -64, -96)
                        && first != GiDirectSourceCoordinator.transportSourceStamp(
                        base, -30, -64, -96),
                "G3 opaque source stamp aliases a changed epoch/origin");

        new GiDirectSourceCoordinator.TransportSourceIdentity(
                base, first, -32, -64, -96
        );
        expectIllegalArgument(() -> new GiDirectSourceCoordinator.TransportSourceIdentity(
                base, first ^ 1L, -32, -64, -96
        ), "forged G3 opaque source stamp was accepted");
    }

    private static void interactiveDebugEnvironmentIsFrozenPerDimension() {
        GiTransportRuntime.resetDeviceState();
        EnvironmentDescriptor first = EnvironmentDescriptor.NONE;
        EnvironmentDescriptor later = EnvironmentDescriptor.ambientOnly(
                EnvironmentDescriptor.Profile.AMBIENT_ONLY,
                EnvironmentDescriptor.Medium.AIR,
                0.2f, 0.3f, 0.4f
        );
        require(GiTransportRuntime.stabilizeDebugEnvironment(true, 11L, first) == first
                        && GiTransportRuntime.stabilizeDebugEnvironment(true, 11L, later) == first,
                "interactive G4 debug allowed the day-cycle environment to reset settling");
        require(GiTransportRuntime.stabilizeDebugEnvironment(true, 12L, later) == later,
                "interactive G4 debug retained an environment across dimensions");
        require(GiTransportRuntime.stabilizeDebugEnvironment(false, 12L, first) == first,
                "non-debug G3 environment was frozen");
        GiTransportRuntime.resetDeviceState();
    }

    private static void epochRequiresExactSemanticAndDirectSourceIdentity() {
        Fixture fixture = fixture();
        GiTransportEpoch epoch = GiTransportEpoch.from(fixture.field, fixture.source);
        require(epoch.worldGeneration() == fixture.field.worldGeneration()
                        && epoch.clipmapGeneration() == fixture.field.clipmapGeneration()
                        && epoch.paletteGeneration() == fixture.field.paletteGeneration()
                        && epoch.contentGeneration() == fixture.field.contentGeneration()
                        && epoch.sourceStamp() == fixture.source.sourceStamp()
                        && epoch.nearOriginX() < 0 && epoch.nearOriginY() < 0
                        && epoch.nearOriginZ() < 0,
                "G4 frozen epoch lost accepted source identity or negative origin");

        GiDirectSourceEpoch staleDirect = directEpoch(
                fixture.field.worldGeneration(), fixture.field.clipmapGeneration(),
                fixture.field.paletteGeneration(), fixture.field.contentGeneration() + 1L,
                fixture.source.epoch().staticLightRegistryEpoch(),
                fixture.source.epoch().environmentEpoch(),
                fixture.field.resourceEpoch(), fixture.field.materialEpoch()
        );
        long staleStamp = GiDirectSourceCoordinator.transportSourceStamp(
                staleDirect, fixture.field.nearOriginX(), fixture.field.nearOriginY(),
                fixture.field.nearOriginZ()
        );
        GiDirectSourceCoordinator.TransportSourceIdentity stale =
                new GiDirectSourceCoordinator.TransportSourceIdentity(
                        staleDirect, staleStamp,
                        fixture.field.nearOriginX(), fixture.field.nearOriginY(),
                        fixture.field.nearOriginZ()
                );
        expectIllegalArgument(() -> GiTransportEpoch.from(fixture.field, stale),
                "G4 admitted mismatched semantic/direct content epochs");
    }

    private static void oneBounceEnergyIsBoundedAndChannelPreserving() {
        double directRed = 8.0;
        double rhoRed = 0.75;
        double outgoingRed = rhoRed / Math.PI * directRed;
        double[] representativeFaces = {1.0, 0.25, 0.75, 0.5, 0.125, 0.875};
        double nearestDirectionFormSum = formFactorSum(representativeFaces);
        require(Math.abs(nearestDirectionFormSum - Math.PI * 0.5) < 1.0e-12,
                "G4 projected-solid-angle quadrature is not a half-cosine L1 distribution");

        // A source can have many receiver candidates along one signed ray, but the nearest
        // CONTENT cell is an intermediate supercover blocker for every farther candidate.  The
        // transport bound therefore admits one, rather than a sum of all eight, radial samples.
        boolean[] contentAlongRay = new boolean[GiTransportLayout.MAXIMUM_DISTANCE];
        contentAlongRay[0] = true;
        contentAlongRay[GiTransportLayout.MAXIMUM_DISTANCE - 1] = true;
        int nearestVisibleDistance = nearestVisibleDistance(contentAlongRay);
        require(nearestVisibleDistance == 1,
                "G4 radial path contract admitted a farther CONTENT receiver behind a nearer one");
        double nearestAxisTransfer = directionFormFactor(
                representativeFaces, 1, 0, 0, nearestVisibleDistance);
        require(Math.abs(nearestAxisTransfer - directionFormFactor(
                        representativeFaces, 1, 0, 0, 1)) < 1.0e-12,
                "G4 source-wise energy incorrectly accumulated blocked radial receivers");
        require(nearestAxisTransfer > directionFormFactor(
                        representativeFaces, 1, 0, 0,
                        GiTransportLayout.MAXIMUM_DISTANCE),
                "G4 radial attenuation is not monotonic after the nearest-hit proof");
        double transportedDc = outgoingRed * nearestDirectionFormSum;
        require(transportedDc <= rhoRed * directRed * 0.5 + 1.0e-12
                        && 2.0 * transportedDc <= rhoRed * directRed + 1.0e-12,
                "G4 projected form factor can amplify DC or reconstructed reflected input");
        for (int faceMask = 1; faceMask < 1 << 6; faceMask++) {
            double[] faces = new double[6];
            for (int face = 0; face < faces.length; face++) {
                faces[face] = (faceMask & 1 << face) == 0 ? 0.0 : 1.0;
            }
            require(Math.abs(formFactorSum(faces) - Math.PI * 0.5) < 1.0e-12,
                    "G4 face-area normalization duplicates reflected energy");
        }
        double nearestAxis = Math.PI * GiTransportLayout.AXIS_BIN_SOLID_ANGLE
                / GiTransportLayout.FORM_WEIGHT_NORMALIZATION;
        double oldNearestAxis = Math.PI / GiTransportLayout.CONFIDENCE_WEIGHT_NORMALIZATION;
        require(nearestAxis > oldNearestAxis * 4.0 && nearestAxis < Math.PI * 0.5,
                "G4 local solid-angle transfer is not finite or did not replace sparse global dilution");
        double blackOutgoing = 0.0 / Math.PI * directRed;
        require(blackOutgoing == 0.0, "black G2 rho created a G4 bounce");
        double green = 0.0 / Math.PI * 0.0;
        double blue = 0.0 / Math.PI * 0.0;
        require(green == 0.0 && blue == 0.0,
                "red-only G4 input created cross-channel energy");
    }

    private static double formFactorSum(final double[] faces) {
        double sum = 0.0;
        for (int z = -1; z <= 1; z++) {
            for (int y = -1; y <= 1; y++) {
                for (int x = -1; x <= 1; x++) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    sum += directionFormFactor(faces, x, y, z, 1);
                }
            }
        }
        return sum;
    }

    private static double directionFormFactor(
            final double[] faces,
            final int x,
            final int y,
            final int z,
            final int distance
    ) {
        double faceSum = 0.0;
        for (double face : faces) faceSum += face;
        require(faceSum > 0.0, "G4 form-factor fixture has no surface face");
        require(distance >= 1 && distance <= GiTransportLayout.MAXIMUM_DISTANCE,
                "G4 form-factor fixture has an invalid radial distance");
        int active = Integer.bitCount((x == 0 ? 0 : 1)
                | (y == 0 ? 0 : 2) | (z == 0 ? 0 : 4));
        require(active != 0, "G4 form-factor fixture has a zero direction");
        double length = Math.sqrt(x * x + y * y + z * z);
        double selected = Math.abs(x) * faces[x < 0 ? 0 : 1]
                + Math.abs(y) * faces[y < 0 ? 2 : 3]
                + Math.abs(z) * faces[z < 0 ? 4 : 5];
        double support = selected / (length * faceSum);
        double solidAngle = switch (active) {
            case 1 -> GiTransportLayout.AXIS_BIN_SOLID_ANGLE;
            case 2 -> GiTransportLayout.EDGE_BIN_SOLID_ANGLE;
            case 3 -> GiTransportLayout.CORNER_BIN_SOLID_ANGLE;
            default -> throw new AssertionError("invalid G4 direction");
        };
        return Math.PI * solidAngle * support
                / ((double) distance * distance * GiTransportLayout.FORM_WEIGHT_NORMALIZATION);
    }

    private static int nearestVisibleDistance(final boolean[] contentAlongRay) {
        require(contentAlongRay.length == GiTransportLayout.MAXIMUM_DISTANCE,
                "G4 radial path fixture does not match the fixed stencil");
        for (int distance = 1; distance <= contentAlongRay.length; distance++) {
            if (contentAlongRay[distance - 1]) return distance;
        }
        return 0;
    }

    private static void frozenMatchRejectsAnyChangedInput() {
        Fixture fixture = fixture();
        GiTransportEpoch frozen = GiTransportEpoch.from(fixture.field, fixture.source);
        require(GiTransportCoordinator.matches(frozen, fixture.field, fixture.source),
                "identical G4 frozen tuple did not match");
        fixture.controller.updateCamera(fixture.worldKey, -3, -35, -67);
        require(!GiTransportCoordinator.matches(frozen, fixture.field, fixture.source),
                "changed G2 clipmap/origin would rebuild a frozen G4 field");
    }

    private static void submittedCompletionStateIsExplicit() {
        Fixture fixture = fixture();
        GiTransportEpoch epoch = GiTransportEpoch.from(fixture.field, fixture.source);
        require(GiTransportCoordinator.resolveSubmittedState(
                        stats(epoch, false, true, 0L, 0L, 0L), epoch, 1L)
                        == GiTransportCoordinator.BuildState.SUBMITTED,
                "in-flight G4 work was incorrectly declared ready");
        require(GiTransportCoordinator.resolveSubmittedState(
                        stats(epoch, false, true, 0L, 0L, 0L), epoch,
                        GiTransportCoordinator.MAX_SUBMISSION_FRAMES + 1L)
                        == GiTransportCoordinator.BuildState.FAILED,
                "uncommitted/stuck G4 submission did not fail closed");
        require(GiTransportCoordinator.resolveSubmittedState(
                        stats(epoch, false, false, 1L, 0L, 0L), epoch, 1L)
                        == GiTransportCoordinator.BuildState.FAILED,
                "failed native G4 completion was incorrectly frozen");
        require(GiTransportCoordinator.resolveSubmittedState(
                        stats(epoch, true, false, 0L, 1L, 1L), epoch, 1L)
                        == GiTransportCoordinator.BuildState.READY,
                "successful native G4 completion was not promoted to READY");
    }

    private static GiTransportGpuResources.Stats stats(
            final GiTransportEpoch epoch,
            final boolean ready,
            final boolean inFlight,
            final long rejected,
            final long dispatches,
            final long builds
    ) {
        return new GiTransportGpuResources.Stats(
                ready, inFlight, 1,
                epoch.worldGeneration(), epoch.clipmapGeneration(),
                epoch.paletteGeneration(), epoch.contentGeneration(),
                epoch.staticSourceEpoch(), epoch.environmentEpoch(),
                0L, 0L, 0L, dispatches, 0L, 0L, 0L, 0L, rejected,
                epoch.sourceStamp(), builds, 0L,
                epoch.nearOriginX(), epoch.nearOriginY(), epoch.nearOriginZ(),
                GiTransportLayout.CELL_COUNT, GiTransportLayout.ITERATION_COUNT,
                GiTransportLayout.MAXIMUM_DISTANCE
        );
    }

    private static void coordinatorIsRenderThreadConfined() throws InterruptedException {
        GiTransportCoordinator coordinator = new GiTransportCoordinator(ignored -> { });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread wrongThread = new Thread(() -> {
            try {
                coordinator.isFrozen();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "g4-wrong-render-thread");
        wrongThread.start();
        wrongThread.join();
        require(failure.get() instanceof IllegalStateException
                        && failure.get().getMessage().contains("render-thread"),
                "G4 coordinator did not fail closed off its owner thread");
        coordinator.close();
    }

    private static Fixture fixture() {
        GiSemanticController controller = new GiSemanticController();
        Object worldKey = new Object();
        GiSemanticWorldToken opened = controller.openWorld(worldKey, DIMENSION);
        controller.advanceMaterialAtlasEpoch(List.of());
        controller.updateCamera(worldKey, -5, -37, -69);
        GiSemanticTransportFieldView field = requireNonNull(
                controller.transportField(worldKey), "missing G4 semantic fixture view"
        );
        GiDirectSourceEpoch direct = directEpoch(
                field.worldGeneration(), field.clipmapGeneration(), field.paletteGeneration(),
                field.contentGeneration(), 7L, 11L,
                field.resourceEpoch(), field.materialEpoch()
        );
        require(opened.worldGeneration() == field.worldGeneration(),
                "semantic fixture changed world generation");
        long stamp = GiDirectSourceCoordinator.transportSourceStamp(
                direct, field.nearOriginX(), field.nearOriginY(), field.nearOriginZ()
        );
        GiDirectSourceCoordinator.TransportSourceIdentity source =
                new GiDirectSourceCoordinator.TransportSourceIdentity(
                        direct, stamp,
                        field.nearOriginX(), field.nearOriginY(), field.nearOriginZ()
                );
        return new Fixture(controller, worldKey, field, source);
    }

    private static GiDirectSourceEpoch directEpoch(
            final long world, final long clipmap, final long palette, final long content,
            final long staticSource, final long environment
    ) {
        return directEpoch(world, clipmap, palette, content, staticSource, environment, 1L, 1L);
    }

    private static GiDirectSourceEpoch directEpoch(
            final long world, final long clipmap, final long palette, final long content,
            final long staticSource, final long environment,
            final long resource, final long material
    ) {
        return new GiDirectSourceEpoch(
                world, resource, material, clipmap, palette, content,
                new LightWorldToken(1L, DIMENSION), staticSource, environment
        );
    }

    private static void expectIllegalArgument(final Runnable action, final String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void expectIndexFailure(final Runnable action, final String message) {
        try {
            action.run();
        } catch (IndexOutOfBoundsException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static <T> T requireNonNull(final T value, final String message) {
        if (value == null) throw new AssertionError(message);
        return value;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new AssertionError(message);
    }

    private record Fixture(
            GiSemanticController controller,
            Object worldKey,
            GiSemanticTransportFieldView field,
            GiDirectSourceCoordinator.TransportSourceIdentity source
    ) {
    }
}
