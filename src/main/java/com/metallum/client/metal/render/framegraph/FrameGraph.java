package com.metallum.client.metal.render.framegraph;

import java.util.List;
import java.util.Objects;

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
            Lifetime lifetime
    ) {
        public ResourceDesc {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(persistence, "persistence");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(lifetime, "lifetime");
        }
    }

    public record ResourceAccess(
            ResourceId resource,
            AccessKind kind,
            PipelineStage stage,
            AttachmentContract attachment
    ) {
        public ResourceAccess {
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(attachment, "attachment");
        }

        public ResourceAccess(
                final ResourceId resource,
                final AccessKind kind,
                final PipelineStage stage
        ) {
            this(resource, kind, stage, AttachmentContract.none());
        }
    }

    public record PassDesc(
            PassId id,
            EncoderClass encoder,
            List<PassId> dependencies,
            List<ResourceAccess> accesses
    ) {
        public PassDesc {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(encoder, "encoder");
            dependencies = List.copyOf(dependencies);
            accesses = List.copyOf(accesses);
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
