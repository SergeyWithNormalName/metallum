package com.metallum.client.metal.render.framegraph;

import com.metallum.client.renderer.MetalCapabilities;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable, whole-resource frame graph contract. */
public final class FrameGraph {
    public enum AccessKind {
        READ(true, false),
        WRITE(false, true),
        READ_WRITE(true, true);

        private final boolean reads;
        private final boolean writes;

        AccessKind(final boolean reads, final boolean writes) {
            this.reads = reads;
            this.writes = writes;
        }

        public boolean reads() {
            return this.reads;
        }

        public boolean writes() {
            return this.writes;
        }
    }

    public enum PipelineStage {
        VERTEX,
        FRAGMENT,
        COMPUTE,
        BLIT,
        METALFX
    }

    public enum PersistenceClass {
        DEVICE_PERSISTENT,
        WORLD_PERSISTENT,
        SIZE_GENERATION,
        HISTORY,
        IN_FLIGHT_FRAME,
        PASS_TRANSIENT,
        READBACK,
        EXTERNAL_FRAME
    }

    public enum ResourceType {
        BUFFER,
        TEXTURE
    }

    /** Semantic roles are declarations only; they do not allocate a resource. */
    public enum ResourceRole {
        GENERIC,
        SCENE_RADIANCE,
        DEPTH,
        MOTION,
        REACTIVE_MASK,
        SHADOW_DATA,
        CLUSTER_DATA,
        VOXEL_DATA,
        LIGHTING_HISTORY,
        TEMPORAL_OUTPUT,
        INTERPOLATED_OUTPUT,
        SDR_UI
    }

    public enum HistoryRole {
        NONE(false, false),
        READ(true, false),
        WRITE(false, true),
        READ_WRITE(true, true);

        private final boolean reads;
        private final boolean writes;

        HistoryRole(final boolean reads, final boolean writes) {
            this.reads = reads;
            this.writes = writes;
        }

        public boolean reads() {
            return this.reads;
        }

        public boolean writes() {
            return this.writes;
        }
    }

    public enum AttachmentRole {
        NONE,
        COLOR,
        DEPTH,
        STENCIL
    }

    public enum LoadAction {
        NONE,
        LOAD,
        CLEAR,
        DONT_CARE
    }

    public enum StoreAction {
        NONE,
        STORE,
        DONT_CARE
    }

    public enum EncoderClass {
        RENDER,
        COMPUTE,
        BLIT,
        EXTERNAL_METALFX
    }

    public enum ImplementationTarget {
        EXECUTOR_NEUTRAL,
        METAL3,
        METAL4
    }

    public enum OutputApplicability {
        ANY,
        SDR_ONLY,
        HDR_ONLY
    }

    public enum RenderContractApplicability {
        ANY,
        LEGACY_ONLY,
        METALLUM_ONLY
    }

    public enum LightingModelApplicability {
        ANY,
        VANILLA_ONLY,
        ADVANCED_ONLY
    }

    public enum PresentationUiContract {
        NOT_PRESENTATION,
        SEPARATE_SDR_UI_REQUIRED,
        COMPOSITED_UI_REQUIRED
    }

    public record PassId(int value, String name) {
        public PassId {
            if (value < 0) {
                throw new IllegalArgumentException("Pass ID must be non-negative");
            }
            name = requireName(name, "pass");
        }
    }

    public record ResourceId(int value, String name) {
        public ResourceId {
            if (value < 0) {
                throw new IllegalArgumentException("Resource ID must be non-negative");
            }
            name = requireName(name, "resource");
        }
    }

    public record Lifetime(PassId first, PassId last) {
        private static final Lifetime WHOLE_GRAPH = new Lifetime(null, null);

        public Lifetime {
            if ((first == null) != (last == null)) {
                throw new IllegalArgumentException("A closed lifetime needs both endpoints");
            }
        }

        public static Lifetime wholeGraph() {
            return WHOLE_GRAPH;
        }

        public static Lifetime closed(final PassId first, final PassId last) {
            return new Lifetime(
                    Objects.requireNonNull(first, "first"),
                    Objects.requireNonNull(last, "last")
            );
        }

        public boolean isWholeGraph() {
            return this.first == null;
        }
    }

    public record ResourceShape(ResourceType type, String format, String extent) {
        public ResourceShape {
            Objects.requireNonNull(type, "type");
            format = requireName(format, "resource format");
            extent = requireName(extent, "resource extent");
        }
    }

    public record AttachmentContract(
            AttachmentRole role,
            LoadAction loadAction,
            StoreAction storeAction,
            String clearValue
    ) {
        private static final AttachmentContract NONE = new AttachmentContract(
                AttachmentRole.NONE,
                LoadAction.NONE,
                StoreAction.NONE,
                null
        );

        public AttachmentContract {
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(loadAction, "loadAction");
            Objects.requireNonNull(storeAction, "storeAction");
            if (role == AttachmentRole.NONE) {
                if (loadAction != LoadAction.NONE || storeAction != StoreAction.NONE || clearValue != null) {
                    throw new IllegalArgumentException("A non-attachment access cannot declare load/store state");
                }
            } else {
                if (loadAction == LoadAction.NONE || storeAction == StoreAction.NONE) {
                    throw new IllegalArgumentException("An attachment access needs load and store actions");
                }
                if (loadAction == LoadAction.CLEAR) {
                    clearValue = requireName(clearValue, "attachment clear value");
                } else if (clearValue != null) {
                    throw new IllegalArgumentException("Only a clear attachment may declare a clear value");
                }
            }
        }

        public static AttachmentContract none() {
            return NONE;
        }

        public static AttachmentContract attachment(
                final AttachmentRole role,
                final LoadAction loadAction,
                final StoreAction storeAction
        ) {
            return new AttachmentContract(role, loadAction, storeAction, null);
        }

        public static AttachmentContract clear(
                final AttachmentRole role,
                final StoreAction storeAction,
                final String clearValue
        ) {
            return new AttachmentContract(role, LoadAction.CLEAR, storeAction, clearValue);
        }

        public boolean isAttachment() {
            return this.role != AttachmentRole.NONE;
        }
    }

    public record ResourceDesc(
            ResourceId id,
            PersistenceClass persistence,
            ResourceShape shape,
            boolean initiallyDefined,
            Lifetime lifetime,
            ResourceRole role
    ) {
        public ResourceDesc {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(persistence, "persistence");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(lifetime, "lifetime");
            Objects.requireNonNull(role, "role");
        }

        public ResourceDesc(
                final ResourceId id,
                final PersistenceClass persistence,
                final ResourceShape shape,
                final boolean initiallyDefined,
                final Lifetime lifetime
        ) {
            this(id, persistence, shape, initiallyDefined, lifetime, ResourceRole.GENERIC);
        }
    }

    public record ResourceAccess(
            ResourceId resource,
            AccessKind kind,
            PipelineStage stage,
            AttachmentContract attachment,
            HistoryRole historyRole,
            long historyGeneration
    ) {
        public ResourceAccess {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(attachment, "attachment");
            Objects.requireNonNull(historyRole, "historyRole");
            if (historyRole == HistoryRole.NONE && historyGeneration != -1L) {
                throw new IllegalArgumentException("A non-history access cannot declare a history generation");
            }
            if (historyRole != HistoryRole.NONE && historyGeneration < 0L) {
                throw new IllegalArgumentException("A history access needs a non-negative generation");
            }
        }

        public ResourceAccess(
                final ResourceId resource,
                final AccessKind kind,
                final PipelineStage stage,
                final AttachmentContract attachment
        ) {
            this(resource, kind, stage, attachment, HistoryRole.NONE, -1L);
        }

        public ResourceAccess(
                final ResourceId resource,
                final AccessKind kind,
                final PipelineStage stage
        ) {
            this(resource, kind, stage, AttachmentContract.none(), HistoryRole.NONE, -1L);
        }

        public static ResourceAccess history(
                final ResourceId resource,
                final AccessKind kind,
                final PipelineStage stage,
                final HistoryRole historyRole,
                final long historyGeneration
        ) {
            return new ResourceAccess(
                    resource,
                    kind,
                    stage,
                    AttachmentContract.none(),
                    historyRole,
                    historyGeneration
            );
        }
    }

    public record PassImplementation(String name, ImplementationTarget target) {
        public PassImplementation {
            name = requireName(name, "pass implementation");
            Objects.requireNonNull(target, "target");
        }
    }

    /** Generation-level metadata; neutral defaults preserve existing production graph callers. */
    public record PassContract(
            Set<MetalCapabilities.Feature> requiredCapabilities,
            Set<MetalCapabilities.Feature> optionalCapabilities,
            PassImplementation primaryImplementation,
            Optional<PassImplementation> fallbackImplementation,
            OutputApplicability outputApplicability,
            RenderContractApplicability renderContractApplicability,
            LightingModelApplicability lightingModelApplicability,
            PresentationUiContract presentationUiContract
    ) {
        private static final PassContract NEUTRAL = new PassContract(
                Set.of(),
                Set.of(),
                new PassImplementation("executor-neutral", ImplementationTarget.EXECUTOR_NEUTRAL),
                Optional.empty(),
                OutputApplicability.ANY,
                RenderContractApplicability.ANY,
                LightingModelApplicability.ANY,
                PresentationUiContract.NOT_PRESENTATION
        );

        public PassContract {
            Objects.requireNonNull(requiredCapabilities, "requiredCapabilities");
            Objects.requireNonNull(optionalCapabilities, "optionalCapabilities");
            requiredCapabilities = immutableFeatures(requiredCapabilities);
            optionalCapabilities = immutableFeatures(optionalCapabilities);
            if (!Collections.disjoint(requiredCapabilities, optionalCapabilities)) {
                throw new IllegalArgumentException("Required and optional capabilities must be disjoint");
            }
            Objects.requireNonNull(primaryImplementation, "primaryImplementation");
            Objects.requireNonNull(fallbackImplementation, "fallbackImplementation");
            Objects.requireNonNull(outputApplicability, "outputApplicability");
            Objects.requireNonNull(renderContractApplicability, "renderContractApplicability");
            Objects.requireNonNull(lightingModelApplicability, "lightingModelApplicability");
            Objects.requireNonNull(presentationUiContract, "presentationUiContract");
        }

        public static PassContract neutral() {
            return NEUTRAL;
        }

        private static Set<MetalCapabilities.Feature> immutableFeatures(
                final Set<MetalCapabilities.Feature> source
        ) {
            EnumSet<MetalCapabilities.Feature> copy = source.isEmpty()
                    ? EnumSet.noneOf(MetalCapabilities.Feature.class)
                    : EnumSet.copyOf(source);
            return Collections.unmodifiableSet(copy);
        }
    }

    public record PassDesc(
            PassId id,
            EncoderClass encoder,
            List<PassId> dependencies,
            List<ResourceAccess> accesses,
            PassContract contract
    ) {
        public PassDesc {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(encoder, "encoder");
            dependencies = List.copyOf(dependencies);
            accesses = List.copyOf(accesses);
            Objects.requireNonNull(contract, "contract");
        }

        public PassDesc(
                final PassId id,
                final EncoderClass encoder,
                final List<PassId> dependencies,
                final List<ResourceAccess> accesses
        ) {
            this(id, encoder, dependencies, accesses, PassContract.neutral());
        }
    }

    private final List<ResourceDesc> resources;
    private final List<PassDesc> passes;

    public FrameGraph(final List<ResourceDesc> resources, final List<PassDesc> passes) {
        this.resources = List.copyOf(resources);
        this.passes = List.copyOf(passes);
    }

    public static FrameGraph validated(
            final List<ResourceDesc> resources,
            final List<PassDesc> passes
    ) {
        FrameGraph graph = new FrameGraph(resources, passes);
        FrameGraphValidator.validate(graph);
        return graph;
    }

    public List<ResourceDesc> resources() {
        return this.resources;
    }

    public List<PassDesc> passes() {
        return this.passes;
    }

    private static String requireName(final String name, final String kind) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(kind + " name must not be blank");
        }
        return name;
    }
}
