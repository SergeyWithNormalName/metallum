package com.metallum.client.metal.render.framegraph;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;

/** Fixed-width, versioned packet shared by the Java graph and native validator. */
public final class FrameGraphAbi {
    public static final int CURRENT_VERSION = 1;
    public static final int HEADER_BYTES = 32;
    public static final int RESOURCE_BYTES = 24;
    public static final int PASS_BYTES = 24;
    public static final int ACCESS_BYTES = 24;
    public static final int MAX_PASSES = Long.SIZE;
    public static final long CAPABILITY_TYPED_ATTACHMENTS = 1L;
    public static final long CAPABILITY_EXTERNAL_METALFX = 1L << 1;

    static final long HEADER_VERSION = 0L;
    static final long HEADER_BYTE_SIZE = 4L;
    static final long HEADER_CAPABILITIES = 8L;
    static final long HEADER_RESOURCE_COUNT = 16L;
    static final long HEADER_PASS_COUNT = 20L;
    static final long HEADER_ACCESS_COUNT = 24L;
    static final long HEADER_RESERVED = 28L;

    static final long RESOURCE_ID = 0L;
    static final long RESOURCE_TYPE = 4L;
    static final long RESOURCE_PERSISTENCE = 8L;
    static final long RESOURCE_FLAGS = 12L;
    static final long RESOURCE_FIRST_PASS = 16L;
    static final long RESOURCE_LAST_PASS = 20L;

    static final long PASS_ID = 0L;
    static final long PASS_ENCODER = 4L;
    static final long PASS_FIRST_ACCESS = 8L;
    static final long PASS_ACCESS_COUNT = 12L;
    static final long PASS_DEPENDENCY_MASK = 16L;

    static final long ACCESS_RESOURCE_ID = 0L;
    static final long ACCESS_KIND = 4L;
    static final long ACCESS_STAGE = 8L;
    static final long ACCESS_ATTACHMENT_ROLE = 12L;
    static final long ACCESS_LOAD_ACTION = 16L;
    static final long ACCESS_STORE_ACTION = 20L;

    private FrameGraphAbi() {
    }

    public record Header(int version, int byteSize, long requiredCapabilities) {
    }

    public static void validate(
            final Header header,
            final int actualByteSize,
            final long supportedCapabilities
    ) {
        if (header.version() != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported frame graph ABI version " + header.version());
        }
        if (actualByteSize < HEADER_BYTES || header.byteSize() != actualByteSize) {
            throw new IllegalArgumentException("Frame graph ABI byte size mismatch");
        }
        long unsupported = header.requiredCapabilities() & ~supportedCapabilities;
        if (unsupported != 0L) {
            throw new IllegalArgumentException("Frame graph ABI requires unsupported capabilities");
        }
    }

    public static int checkedPacketBytes(final int recordCount, final int recordStride) {
        if (recordCount < 0 || recordStride <= 0) {
            throw new IllegalArgumentException("Frame graph ABI count/stride is invalid");
        }
        try {
            return Math.addExact(HEADER_BYTES, Math.multiplyExact(recordCount, recordStride));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Frame graph ABI packet size overflow", exception);
        }
    }

    /** Encodes one validated graph into arena-owned native memory. */
    public static MemorySegment encode(
            final FrameGraph graph,
            final long requiredCapabilities,
            final Arena arena
    ) {
        if (graph == null || arena == null) {
            throw new NullPointerException("Frame graph ABI graph and arena are required");
        }
        List<FrameGraph.ResourceDesc> resources = graph.resources();
        List<FrameGraph.PassDesc> passes = graph.passes();
        if (passes.size() > MAX_PASSES) {
            throw new IllegalArgumentException("Frame graph ABI supports at most " + MAX_PASSES + " passes");
        }
        for (int index = 0; index < resources.size(); index++) {
            if (resources.get(index).id().value() != index) {
                throw new IllegalArgumentException("Frame graph ABI resource IDs must be dense and ordered");
            }
        }
        for (int index = 0; index < passes.size(); index++) {
            FrameGraph.PassDesc pass = passes.get(index);
            requireMaskId(pass.id().value(), "pass");
            if (pass.id().value() != index) {
                throw new IllegalArgumentException("Frame graph ABI pass IDs must be dense and ordered");
            }
            for (FrameGraph.PassId dependency : pass.dependencies()) {
                requireMaskId(dependency.value(), "dependency");
            }
        }
        FrameGraphValidator.validate(graph);
        long missingCapabilities = graphCapabilities(graph) & ~requiredCapabilities;
        if (missingCapabilities != 0L) {
            throw new IllegalArgumentException(
                    "Frame graph ABI packet omits required graph capabilities 0x"
                            + Long.toHexString(missingCapabilities)
            );
        }

        int accessCount = 0;
        try {
            for (FrameGraph.PassDesc pass : passes) {
                accessCount = Math.addExact(accessCount, pass.accesses().size());
                for (FrameGraph.PassId dependency : pass.dependencies()) {
                    if (dependency.value() >= pass.id().value()) {
                        throw new IllegalArgumentException(
                                "Frame graph ABI pass IDs must be in dependency order"
                        );
                    }
                }
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Frame graph ABI access count overflow", exception);
        }

        int byteSize = packetBytes(resources.size(), passes.size(), accessCount);
        MemorySegment packet = arena.allocate(byteSize, Long.BYTES);
        packet.fill((byte) 0);
        putInt(packet, HEADER_VERSION, CURRENT_VERSION);
        putInt(packet, HEADER_BYTE_SIZE, byteSize);
        packet.set(ValueLayout.JAVA_LONG, HEADER_CAPABILITIES, requiredCapabilities);
        putInt(packet, HEADER_RESOURCE_COUNT, resources.size());
        putInt(packet, HEADER_PASS_COUNT, passes.size());
        putInt(packet, HEADER_ACCESS_COUNT, accessCount);

        long resourceBase = HEADER_BYTES;
        for (int index = 0; index < resources.size(); index++) {
            FrameGraph.ResourceDesc resource = resources.get(index);
            long offset = resourceBase + (long) index * RESOURCE_BYTES;
            putInt(packet, offset + RESOURCE_ID, resource.id().value());
            putInt(packet, offset + RESOURCE_TYPE, resourceTypeCode(resource.shape().type()));
            putInt(packet, offset + RESOURCE_PERSISTENCE, persistenceCode(resource.persistence()));
            putInt(packet, offset + RESOURCE_FLAGS, resource.initiallyDefined() ? 1 : 0);
            if (resource.lifetime().isWholeGraph()) {
                putInt(packet, offset + RESOURCE_FIRST_PASS, -1);
                putInt(packet, offset + RESOURCE_LAST_PASS, -1);
            } else {
                putInt(packet, offset + RESOURCE_FIRST_PASS, resource.lifetime().first().value());
                putInt(packet, offset + RESOURCE_LAST_PASS, resource.lifetime().last().value());
            }
        }

        long passBase = resourceBase + (long) resources.size() * RESOURCE_BYTES;
        long accessBase = passBase + (long) passes.size() * PASS_BYTES;
        int firstAccess = 0;
        for (int index = 0; index < passes.size(); index++) {
            FrameGraph.PassDesc pass = passes.get(index);
            long passOffset = passBase + (long) index * PASS_BYTES;
            putInt(packet, passOffset + PASS_ID, pass.id().value());
            putInt(packet, passOffset + PASS_ENCODER, encoderCode(pass.encoder()));
            putInt(packet, passOffset + PASS_FIRST_ACCESS, firstAccess);
            putInt(packet, passOffset + PASS_ACCESS_COUNT, pass.accesses().size());
            long dependencyMask = 0L;
            for (FrameGraph.PassId dependency : pass.dependencies()) {
                dependencyMask |= 1L << dependency.value();
            }
            packet.set(ValueLayout.JAVA_LONG, passOffset + PASS_DEPENDENCY_MASK, dependencyMask);

            for (FrameGraph.ResourceAccess access : pass.accesses()) {
                long accessOffset = accessBase + (long) firstAccess * ACCESS_BYTES;
                putInt(packet, accessOffset + ACCESS_RESOURCE_ID, access.resource().value());
                putInt(packet, accessOffset + ACCESS_KIND, accessKindCode(access.kind()));
                putInt(packet, accessOffset + ACCESS_STAGE, stageCode(access.stage()));
                putInt(packet, accessOffset + ACCESS_ATTACHMENT_ROLE,
                        attachmentRoleCode(access.attachment().role()));
                putInt(packet, accessOffset + ACCESS_LOAD_ACTION,
                        loadActionCode(access.attachment().loadAction()));
                putInt(packet, accessOffset + ACCESS_STORE_ACTION,
                        storeActionCode(access.attachment().storeAction()));
                firstAccess++;
            }
        }
        return packet;
    }

    public static int packetBytes(
            final int resourceCount,
            final int passCount,
            final int accessCount
    ) {
        if (resourceCount < 0 || passCount < 0 || accessCount < 0) {
            throw new IllegalArgumentException("Frame graph ABI counts must be non-negative");
        }
        try {
            int bytes = HEADER_BYTES;
            bytes = Math.addExact(bytes, Math.multiplyExact(resourceCount, RESOURCE_BYTES));
            bytes = Math.addExact(bytes, Math.multiplyExact(passCount, PASS_BYTES));
            return Math.addExact(bytes, Math.multiplyExact(accessCount, ACCESS_BYTES));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Frame graph ABI packet size overflow", exception);
        }
    }

    private static void putInt(final MemorySegment packet, final long offset, final int value) {
        packet.set(ValueLayout.JAVA_INT, offset, value);
    }

    private static void requireMaskId(final int id, final String kind) {
        if (id < 0 || id >= MAX_PASSES) {
            throw new IllegalArgumentException("Frame graph ABI " + kind + " ID is outside [0, 63]");
        }
    }

    private static int resourceTypeCode(final FrameGraph.ResourceType value) {
        return switch (value) {
            case BUFFER -> 1;
            case TEXTURE -> 2;
        };
    }

    private static int persistenceCode(final FrameGraph.PersistenceClass value) {
        return switch (value) {
            case DEVICE_PERSISTENT -> 1;
            case WORLD_PERSISTENT -> 2;
            case SIZE_GENERATION -> 3;
            case HISTORY -> 4;
            case IN_FLIGHT_FRAME -> 5;
            case PASS_TRANSIENT -> 6;
            case READBACK -> 7;
            case EXTERNAL_FRAME -> 8;
        };
    }

    private static int encoderCode(final FrameGraph.EncoderClass value) {
        return switch (value) {
            case RENDER -> 1;
            case COMPUTE -> 2;
            case BLIT -> 3;
            case EXTERNAL_METALFX -> 4;
        };
    }

    private static int accessKindCode(final FrameGraph.AccessKind value) {
        return switch (value) {
            case READ -> 1;
            case WRITE -> 2;
            case READ_WRITE -> 3;
        };
    }

    private static int stageCode(final FrameGraph.PipelineStage value) {
        return switch (value) {
            case VERTEX -> 1;
            case FRAGMENT -> 2;
            case COMPUTE -> 3;
            case BLIT -> 4;
            case METALFX -> 5;
        };
    }

    private static long graphCapabilities(final FrameGraph graph) {
        long capabilities = 0L;
        for (FrameGraph.PassDesc pass : graph.passes()) {
            if (pass.encoder() == FrameGraph.EncoderClass.EXTERNAL_METALFX) {
                capabilities |= CAPABILITY_EXTERNAL_METALFX;
            }
            for (FrameGraph.ResourceAccess access : pass.accesses()) {
                if (access.attachment().isAttachment()) {
                    capabilities |= CAPABILITY_TYPED_ATTACHMENTS;
                }
                if (access.stage() == FrameGraph.PipelineStage.METALFX) {
                    capabilities |= CAPABILITY_EXTERNAL_METALFX;
                }
            }
        }
        return capabilities;
    }

    private static int attachmentRoleCode(final FrameGraph.AttachmentRole value) {
        return switch (value) {
            case NONE -> 0;
            case COLOR -> 1;
            case DEPTH -> 2;
            case STENCIL -> 3;
        };
    }

    private static int loadActionCode(final FrameGraph.LoadAction value) {
        return switch (value) {
            case NONE -> 0;
            case LOAD -> 1;
            case CLEAR -> 2;
            case DONT_CARE -> 3;
        };
    }

    private static int storeActionCode(final FrameGraph.StoreAction value) {
        return switch (value) {
            case NONE -> 0;
            case STORE -> 1;
            case DONT_CARE -> 2;
        };
    }
}
