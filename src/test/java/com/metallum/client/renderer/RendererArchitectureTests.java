package com.metallum.client.renderer;

import java.util.EnumSet;

/** Dependency-free renderer architecture contract tests. */
public final class RendererArchitectureTests {
    private RendererArchitectureTests() {
    }

    public static void main(final String[] args) {
        testIndependentModeMatrix();
        testProductionMetallumRejection();
        testFailClosedSelection();
        testImmutableCapabilitySnapshot();
        testInvalidConfigsAreRejected();
        System.out.println("Renderer architecture P1 tests passed");
    }

    private static void testIndependentModeMatrix() {
        MetalCapabilities all = MetalCapabilities.of(
                MetalCapabilities.Feature.METAL3_BASE,
                MetalCapabilities.Feature.METAL4_CORE,
                MetalCapabilities.Feature.HDR_OUTPUT,
                MetalCapabilities.Feature.METALLUM_LIGHTING
        );
        for (LightingMode lighting : LightingMode.values()) {
            for (DisplayOutputMode output : DisplayOutputMode.values()) {
                for (MetalExecutorKind executor : MetalExecutorKind.values()) {
                    RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                            lighting,
                            output,
                            executor,
                            DisplayOutputMode.SDR,
                            all,
                            RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION
                    );
                    require(!resolution.fellBack(), "supported matrix entry fell back");
                    require(resolution.config().lightingMode() == lighting, "lighting/output coupling");
                    require(resolution.config().outputMode() == output, "output/lighting coupling");
                    require(resolution.config().executorKind() == executor, "executor selection changed");
                }
            }
        }

        RendererGenerationConfig.Resolution metallumSdr = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.SDR,
                all,
                1
        );
        require(metallumSdr.config().lightingMode() == LightingMode.METALLUM,
                "HDR off incorrectly forced legacy lighting");
    }

    private static void testProductionMetallumRejection() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(true);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL3,
                DisplayOutputMode.HDR,
                production,
                1
        );
        require(resolution.fellBack(), "unimplemented Metallum lighting was accepted");
        require(resolution.rejectionReasons().equals(EnumSet.of(
                        RendererGenerationConfig.RejectionReason.LIGHTING_UNAVAILABLE)),
                "unexpected production fallback reason");
        require(resolution.config().lightingMode() == LightingMode.LEGACY,
                "unimplemented lighting did not fail closed");
        require(resolution.config().outputMode() == DisplayOutputMode.HDR,
                "lighting fallback changed the current safe HDR output");
    }

    private static void testFailClosedSelection() {
        MetalCapabilities sdrMetal3 = MetalCapabilities.productionMetal3(false);
        RendererGenerationConfig.Resolution resolution = RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.HDR,
                MetalExecutorKind.METAL4,
                DisplayOutputMode.HDR,
                sdrMetal3,
                1
        );
        require(resolution.rejectionReasons().equals(EnumSet.allOf(
                        RendererGenerationConfig.RejectionReason.class)),
                "unsupported combination did not report every rejection");
        require(resolution.config().lightingMode() == LightingMode.LEGACY
                        && resolution.config().outputMode() == DisplayOutputMode.SDR
                        && resolution.config().executorKind() == MetalExecutorKind.METAL3,
                "unsupported combination did not fail closed to Legacy + safe output + Metal 3");
    }

    private static void testImmutableCapabilitySnapshot() {
        EnumSet<MetalCapabilities.Feature> source = EnumSet.of(MetalCapabilities.Feature.METAL3_BASE);
        MetalCapabilities snapshot = MetalCapabilities.of(source.toArray(MetalCapabilities.Feature[]::new));
        source.add(MetalCapabilities.Feature.METAL4_CORE);
        require(!snapshot.supports(MetalCapabilities.Feature.METAL4_CORE),
                "capability snapshot retained a mutable source");
        expectUnsupported(() -> snapshot.features().add(MetalCapabilities.Feature.HDR_OUTPUT));
    }

    private static void testInvalidConfigsAreRejected() {
        MetalCapabilities production = MetalCapabilities.productionMetal3(false);
        expectIllegalArgument(() -> new RendererGenerationConfig(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                1
        ));
        expectIllegalArgument(() -> new RendererGenerationConfig(
                LightingMode.LEGACY,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL3,
                production,
                0
        ));
        expectIllegalState(() -> RendererGenerationConfig.resolve(
                LightingMode.METALLUM,
                DisplayOutputMode.SDR,
                MetalExecutorKind.METAL4,
                DisplayOutputMode.SDR,
                MetalCapabilities.of(MetalCapabilities.Feature.METAL4_CORE),
                1
        ));
    }

    private static void expectIllegalArgument(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectIllegalState(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void expectUnsupported(final Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
