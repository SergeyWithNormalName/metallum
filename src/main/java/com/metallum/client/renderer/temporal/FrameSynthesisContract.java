package com.metallum.client.renderer.temporal;

import com.metallum.client.renderer.MetalCapabilities;
import com.metallum.client.renderer.MetalExecutorKind;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure, executor-neutral admission model for future temporal scaling and frame synthesis. */
public final class FrameSynthesisContract {
    public enum TextureRole {
        WORLD_COLOR,
        DEPTH,
        MOTION,
        REACTIVE_MASK,
        SDR_UI
    }

    public enum PresentationKind {
        REAL_FRAME,
        GENERATED_FRAME
    }

    public enum Discontinuity {
        RESIZE,
        WORLD_RELOAD,
        GENERATION_SWITCH,
        SCENE_CUT
    }

    public enum RejectionReason {
        METALFX_TEMPORAL_UNAVAILABLE,
        FRAME_INTERPOLATION_UNAVAILABLE,
        MISSING_PREVIOUS_FRAME,
        MISSING_DEPTH,
        MISSING_MOTION,
        MISSING_REACTIVE_MASK,
        TEMPORAL_CONTRACT_UNAVAILABLE,
        UI_NOT_SEPARATED,
        FRAME_SEQUENCE_MISMATCH,
        GENERATION_MISMATCH,
        EXTENT_OR_FORMAT_MISMATCH,
        HISTORY_DISCONTINUITY,
        GENERATED_PRESENTATION_NOT_DECLARED,
        PRESENTATION_ORDER_INVALID,
        DRAWABLE_OWNERSHIP_INSUFFICIENT,
        IN_FLIGHT_OWNERSHIP_INSUFFICIENT,
        CADENCE_OUT_OF_BOUNDS,
        UNSUPPORTED_UPSTREAM_PROFILE,
        BACKPRESSURE_EXCEEDED,
        PRODUCTION_ADMISSION_DISABLED
    }

    public record TextureInput(
            String resourceId,
            TextureRole role,
            String format,
            FrameState.Extent extent,
            long resourceGeneration
    ) {
        public TextureInput {
            resourceId = requireName(resourceId, "texture resource");
            Objects.requireNonNull(role, "role");
            format = requireName(format, "texture format");
            Objects.requireNonNull(extent, "extent");
            requireNonNegative(resourceGeneration, "resource generation");
        }
    }

    public record RenderedFrame(
            FrameState state,
            long inFlightGeneration,
            TextureInput worldColor,
            Optional<TextureInput> depth,
            Optional<TextureInput> motion,
            Optional<TextureInput> reactiveMask,
            Optional<TextureInput> sdrUi,
            boolean worldColorContainsUi,
            Set<Discontinuity> discontinuities
    ) {
        public RenderedFrame {
            Objects.requireNonNull(state, "state");
            requireNonNegative(inFlightGeneration, "in-flight generation");
            requireRole(worldColor, TextureRole.WORLD_COLOR, "world color");
            depth = checkedOptional(depth, TextureRole.DEPTH, "depth");
            motion = checkedOptional(motion, TextureRole.MOTION, "motion");
            reactiveMask = checkedOptional(reactiveMask, TextureRole.REACTIVE_MASK, "reactive mask");
            sdrUi = checkedOptional(sdrUi, TextureRole.SDR_UI, "SDR UI");
            Objects.requireNonNull(discontinuities, "discontinuities");
            EnumSet<Discontinuity> copy = discontinuities.isEmpty()
                    ? EnumSet.noneOf(Discontinuity.class)
                    : EnumSet.copyOf(discontinuities);
            discontinuities = Collections.unmodifiableSet(copy);
        }

        public Set<String> resourceIds() {
            Set<String> ids = new HashSet<>();
            ids.add(this.worldColor.resourceId());
            this.depth.ifPresent(input -> ids.add(input.resourceId()));
            this.motion.ifPresent(input -> ids.add(input.resourceId()));
            this.reactiveMask.ifPresent(input -> ids.add(input.resourceId()));
            this.sdrUi.ifPresent(input -> ids.add(input.resourceId()));
            return Collections.unmodifiableSet(ids);
        }
    }

    /** A declaration only; it never acquires or retains a drawable itself. */
    public record DrawableOwnership(
            String ownerToken,
            int presentationSlots,
            boolean additionalAcquisitionAllowed
    ) {
        public DrawableOwnership {
            ownerToken = requireName(ownerToken, "drawable owner token");
            if (presentationSlots <= 0 || presentationSlots > 2) {
                throw new IllegalArgumentException("Drawable presentation slots must be in [1, 2]");
            }
        }

        public static DrawableOwnership preparation(final String ownerToken, final int slots) {
            return new DrawableOwnership(ownerToken, slots, false);
        }
    }

    public record PresentationIntent(
            long presentationId,
            PresentationKind kind,
            long sourceFrameId,
            long targetDisplayTimeNanos,
            String drawableOwnerToken
    ) {
        public PresentationIntent {
            requireNonNegative(presentationId, "presentation ID");
            Objects.requireNonNull(kind, "kind");
            requireNonNegative(sourceFrameId, "source frame ID");
            requireNonNegative(targetDisplayTimeNanos, "target display time");
            drawableOwnerToken = requireName(drawableOwnerToken, "drawable owner token");
        }
    }

    /** Real/render cadence stays separate from generated and display cadence. */
    public record DisplayCadence(double renderFps, double generatedFps, double displayFps) {
        public DisplayCadence {
            requirePositiveFinite(renderFps, "render FPS");
            requireNonNegativeFinite(generatedFps, "generated FPS");
            requirePositiveFinite(displayFps, "display FPS");
        }

        public double presentationFps() {
            return this.renderFps + this.generatedFps;
        }
    }

    /** Resources remain owned through the last declared presentation, without retaining native objects here. */
    public record InFlightGenerationOwnership(
            long rendererGeneration,
            long inFlightGeneration,
            long lastPresentationId,
            Set<String> retainedResourceIds
    ) {
        public InFlightGenerationOwnership {
            requireNonNegative(rendererGeneration, "renderer generation");
            requireNonNegative(inFlightGeneration, "in-flight generation");
            requireNonNegative(lastPresentationId, "last presentation ID");
            Objects.requireNonNull(retainedResourceIds, "retainedResourceIds");
            retainedResourceIds = Collections.unmodifiableSet(new HashSet<>(retainedResourceIds));
        }
    }

    public record Request(
            RenderedFrame current,
            Optional<RenderedFrame> previous,
            PresentationIntent realPresentation,
            Optional<PresentationIntent> generatedPresentation,
            DisplayCadence cadence,
            DrawableOwnership drawableOwnership,
            InFlightGenerationOwnership inFlightOwnership,
            MetalCapabilities capabilities,
            MetalExecutorKind executor
    ) {
        public Request {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(previous, "previous");
            Objects.requireNonNull(realPresentation, "realPresentation");
            Objects.requireNonNull(generatedPresentation, "generatedPresentation");
            Objects.requireNonNull(cadence, "cadence");
            Objects.requireNonNull(drawableOwnership, "drawableOwnership");
            Objects.requireNonNull(inFlightOwnership, "inFlightOwnership");
            Objects.requireNonNull(capabilities, "capabilities");
            Objects.requireNonNull(executor, "executor");
        }
    }

    public record Admission(Set<RejectionReason> rejectionReasons) {
        public Admission {
            Objects.requireNonNull(rejectionReasons, "rejectionReasons");
            EnumSet<RejectionReason> copy = rejectionReasons.isEmpty()
                    ? EnumSet.noneOf(RejectionReason.class)
                    : EnumSet.copyOf(rejectionReasons);
            rejectionReasons = Collections.unmodifiableSet(copy);
        }

        public boolean allowed() {
            return this.rejectionReasons.isEmpty();
        }
    }

    private FrameSynthesisContract() {
    }

    public static Admission evaluate(final Request request) {
        Objects.requireNonNull(request, "request");
        EnumSet<RejectionReason> reasons = EnumSet.noneOf(RejectionReason.class);
        RenderedFrame current = request.current();
        Optional<RenderedFrame> previous = request.previous();

        if (!request.capabilities().supports(MetalCapabilities.Feature.METALFX_TEMPORAL)) {
            reasons.add(RejectionReason.METALFX_TEMPORAL_UNAVAILABLE);
        }
        if (!request.capabilities().supports(MetalCapabilities.Feature.METALFX_FRAME_INTERPOLATION)) {
            reasons.add(RejectionReason.FRAME_INTERPOLATION_UNAVAILABLE);
        }
        if (previous.isEmpty()) {
            reasons.add(RejectionReason.MISSING_PREVIOUS_FRAME);
        }
        if (current.depth().isEmpty()) {
            reasons.add(RejectionReason.MISSING_DEPTH);
        }
        if (current.motion().isEmpty()) {
            reasons.add(RejectionReason.MISSING_MOTION);
        }
        if (current.reactiveMask().isEmpty()) {
            reasons.add(RejectionReason.MISSING_REACTIVE_MASK);
        }
        FrameContract frameContract = current.state().contract();
        if (frameContract.motionVectors().availability()
                        != FrameContract.MotionVectorAvailability.AVAILABLE
                || frameContract.reactiveMask() != FrameContract.ReactiveMaskAvailability.AVAILABLE) {
            reasons.add(RejectionReason.TEMPORAL_CONTRACT_UNAVAILABLE);
        }
        if (current.worldColorContainsUi() || current.sdrUi().isEmpty()
                || frameContract.uiComposition() != FrameContract.UiComposition.SEPARATE_SDR_TEXTURE) {
            reasons.add(RejectionReason.UI_NOT_SEPARATED);
        }
        if (!current.discontinuities().isEmpty() || !current.state().historyResetReasons().isEmpty()) {
            reasons.add(RejectionReason.HISTORY_DISCONTINUITY);
        }
        if (!inputsMatchState(current)) {
            reasons.add(RejectionReason.EXTENT_OR_FORMAT_MISMATCH);
        }
        if (request.generatedPresentation().isEmpty()) {
            reasons.add(RejectionReason.GENERATED_PRESENTATION_NOT_DECLARED);
        }

        previous.ifPresent(prior -> validateFramePair(prior, current, reasons));
        validatePresentations(request, reasons);
        validateOwnership(request, reasons);
        return new Admission(reasons);
    }

    private static void validateFramePair(
            final RenderedFrame previous,
            final RenderedFrame current,
            final Set<RejectionReason> reasons
    ) {
        if (previous.state().frameId() >= current.state().frameId()) {
            reasons.add(RejectionReason.FRAME_SEQUENCE_MISMATCH);
        }
        if (previous.state().rendererGenerationId() != current.state().rendererGenerationId()
                || previous.state().historyGeneration() != current.state().historyGeneration()
                || previous.state().renderContractGenerationId()
                != current.state().renderContractGenerationId()
                || previous.state().lightingGenerationId() != current.state().lightingGenerationId()
                || previous.state().outputGenerationId() != current.state().outputGenerationId()
                || previous.state().renderContractMode() != current.state().renderContractMode()
                || previous.state().lightingModel() != current.state().lightingModel()
                || previous.state().outputMode() != current.state().outputMode()
                || previous.inFlightGeneration() != current.inFlightGeneration()
                || !resourceGenerationsMatch(previous)
                || !resourceGenerationsMatch(current)) {
            reasons.add(RejectionReason.GENERATION_MISMATCH);
        }
        if (!FrameState.transitionResetReasons(previous.state(), current.state(), Set.of()).isEmpty()) {
            reasons.add(RejectionReason.HISTORY_DISCONTINUITY);
        }
        if (!previous.discontinuities().isEmpty() || !previous.state().historyResetReasons().isEmpty()) {
            reasons.add(RejectionReason.HISTORY_DISCONTINUITY);
        }
        if (!previous.state().contract().equals(current.state().contract())) {
            reasons.add(RejectionReason.TEMPORAL_CONTRACT_UNAVAILABLE);
        }
        if (!previous.state().renderExtent().equals(current.state().renderExtent())
                || !previous.state().displayExtent().equals(current.state().displayExtent())
                || !previous.worldColor().format().equals(current.worldColor().format())
                || !previous.worldColor().extent().equals(current.worldColor().extent())
                || !optionalInputsMatch(previous.depth(), current.depth())
                || !optionalInputsMatch(previous.motion(), current.motion())
                || !optionalInputsMatch(previous.reactiveMask(), current.reactiveMask())
                || !inputsMatchState(previous)) {
            reasons.add(RejectionReason.EXTENT_OR_FORMAT_MISMATCH);
        }
    }

    private static void validatePresentations(
            final Request request,
            final Set<RejectionReason> reasons
    ) {
        PresentationIntent real = request.realPresentation();
        Optional<PresentationIntent> generated = request.generatedPresentation();
        if (real.kind() != PresentationKind.REAL_FRAME
                || real.sourceFrameId() != request.current().state().frameId()) {
            reasons.add(RejectionReason.PRESENTATION_ORDER_INVALID);
        }
        // MetalFX Frame Interpolation sequence: generated intermediate N-1/2 precedes real N.
        // Both intents must share current.frameId as sourceFrameId.
        generated.ifPresent(intent -> {
            if (intent.kind() != PresentationKind.GENERATED_FRAME
                    || intent.sourceFrameId() != request.current().state().frameId()
                    || intent.presentationId() >= real.presentationId()
                    || intent.targetDisplayTimeNanos() >= real.targetDisplayTimeNanos()) {
                reasons.add(RejectionReason.PRESENTATION_ORDER_INVALID);
            }
        });
    }

    private static void validateOwnership(
            final Request request,
            final Set<RejectionReason> reasons
    ) {
        int requiredSlots = request.generatedPresentation().isPresent() ? 2 : 1;
        String token = request.drawableOwnership().ownerToken();
        boolean presentationTokensMatch = request.realPresentation().drawableOwnerToken().equals(token)
                && request.generatedPresentation().map(PresentationIntent::drawableOwnerToken)
                .map(token::equals).orElse(true);
        if (request.drawableOwnership().presentationSlots() < requiredSlots
                || request.drawableOwnership().additionalAcquisitionAllowed()
                || !presentationTokensMatch) {
            reasons.add(RejectionReason.DRAWABLE_OWNERSHIP_INSUFFICIENT);
        }

        InFlightGenerationOwnership ownership = request.inFlightOwnership();
        Set<String> requiredResources = new HashSet<>(request.current().resourceIds());
        request.previous().ifPresent(frame -> requiredResources.addAll(frame.resourceIds()));
        // Reactive mask is checked above as an upstream Temporal upscaling quality gate,
        // rather than a direct MetalFX Frame Interpolator texture parameter.
        long lastPresentation = Math.max(
                request.realPresentation().presentationId(),
                request.generatedPresentation().map(PresentationIntent::presentationId).orElse(0L)
        );
        if (ownership.rendererGeneration() != request.current().state().rendererGenerationId()
                || ownership.inFlightGeneration() != request.current().inFlightGeneration()
                || ownership.lastPresentationId() < lastPresentation
                || !ownership.retainedResourceIds().containsAll(requiredResources)) {
            reasons.add(RejectionReason.IN_FLIGHT_OWNERSHIP_INSUFFICIENT);
        }
    }

    private static Optional<TextureInput> checkedOptional(
            final Optional<TextureInput> input,
            final TextureRole role,
            final String name
    ) {
        Objects.requireNonNull(input, name);
        input.ifPresent(value -> requireRole(value, role, name));
        return input;
    }

    private static boolean inputsMatchState(final RenderedFrame frame) {
        return frame.worldColor().extent().equals(frame.state().displayExtent())
                && frame.depth().map(TextureInput::extent).map(frame.state().renderExtent()::equals).orElse(true)
                && frame.motion().map(TextureInput::extent).map(frame.state().renderExtent()::equals).orElse(true)
                && frame.reactiveMask().map(TextureInput::extent)
                .map(frame.state().renderExtent()::equals).orElse(true)
                && frame.sdrUi().map(TextureInput::extent).map(frame.state().displayExtent()::equals).orElse(true);
    }

    private static boolean resourceGenerationsMatch(final RenderedFrame frame) {
        long expected = frame.state().rendererGenerationId();
        return frame.worldColor().resourceGeneration() == expected
                && frame.depth().map(TextureInput::resourceGeneration).map(value -> value == expected).orElse(true)
                && frame.motion().map(TextureInput::resourceGeneration).map(value -> value == expected).orElse(true)
                && frame.reactiveMask().map(TextureInput::resourceGeneration)
                .map(value -> value == expected).orElse(true)
                && frame.sdrUi().map(TextureInput::resourceGeneration).map(value -> value == expected).orElse(true);
    }

    private static boolean optionalInputsMatch(
            final Optional<TextureInput> previous,
            final Optional<TextureInput> current
    ) {
        if (previous.isEmpty() || current.isEmpty()) {
            return previous.isEmpty() == current.isEmpty();
        }
        return previous.orElseThrow().format().equals(current.orElseThrow().format())
                && previous.orElseThrow().extent().equals(current.orElseThrow().extent());
    }

    private static void requireRole(
            final TextureInput input,
            final TextureRole role,
            final String name
    ) {
        Objects.requireNonNull(input, name);
        if (input.role() != role) {
            throw new IllegalArgumentException(name + " has role " + input.role() + " instead of " + role);
        }
    }

    private static String requireName(final String value, final String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static void requireNonNegative(final long value, final String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static void requirePositiveFinite(final double value, final String name) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be positive and finite");
        }
    }

    private static void requireNonNegativeFinite(final double value, final String name) {
        if (value < 0.0 || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be non-negative and finite");
        }
    }
}
