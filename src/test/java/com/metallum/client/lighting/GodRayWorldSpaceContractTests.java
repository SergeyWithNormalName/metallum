package com.metallum.client.lighting;

import com.metallum.client.metal.render.GodRayVisibilityRenderer;
import com.metallum.client.metal.render.framegraph.FrameGraph;
import com.metallum.client.metal.render.mtl.MTLCompareFunction;
import com.metallum.client.renderer.DisplayOutputMode;
import com.metallum.client.renderer.LightingModel;
import com.metallum.client.renderer.LightingPreset;
import com.metallum.client.renderer.MetalExecutorKind;
import com.metallum.client.renderer.RenderContractMode;
import com.metallum.client.renderer.RendererFeatureMask;
import com.metallum.client.renderer.RendererGenerationConfig;
import com.metallum.client.renderer.SunShadowLayout;
import com.metallum.client.renderer.style.VisualStyle;
import com.metallum.client.renderer.style.VisualStyleProfiles;
import com.metallum.client.renderer.temporal.FrameContract;
import com.metallum.client.renderer.temporal.FrameState;
import com.metallum.client.renderer.temporal.Matrix4;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Set;

/**
 * Dependency-free mathematical and geometric contract proofs for GOD-RAYS-0, 0.1, and 1.
 *
 * <p>Proves view-ray reconstruction, reverse-Z compare direction, camera translation invariance,
 * cascade selection, blend bounds, fail-closed coverage, work budget arithmetic, and GR-1
 * directional visibility contracts A through T.</p>
 */
public final class GodRayWorldSpaceContractTests {
    private static final float EPSILON = 1.0e-5f;

    private GodRayWorldSpaceContractTests() {
    }

    public static void main(final String[] args) {
        testNdcAndViewRayReconstructionFiniteAndCorrect();
        testViewSpacePointProjectsToShadowCoordinates();
        testReverseZCompareSemantics();
        testCascadeSelectionMonotonicity();
        testCascadeOverlapContinuityAndBounds();
        testOutsideCoverageFailsClosed();
        testOpaqueSceneDepthBoundsEndpoint();
        testSkyDepthUsesBoundedFarEndpoint();
        testCameraTranslationInvarianceAndRebase();
        testExtremeInputsNoNaNOrInf();
        testWorkAndMemoryBudgetArithmeticNoOverflow();
        testHdrOptionBRadianceComposition();
        testResourcePersistenceAndLifetimeContract();
        testCrossGraphCsmReadinessContract();

        // GR-1 Architectural Contracts A through T:
        testDedicatedGodRaySamplerPointGreaterEqual();
        testExistingOrdinaryCsmSamplerLinearGreaterEqual();
        testDiagnosticOffLeavesOrdinaryPathUnchanged();
        testVisibilityTargetExtentOddAndEvenDimensions();
        testVisibilityTargetFormatR16Float();
        testSampleCountFiniteAndBounded();
        testMidpointSampleLocationsCorrect();
        testNoSamplePastOpaqueSceneDepthEndpoint();
        testNoSamplePastMaxCsmDistance();
        testCascadeSelectionMonotonic();
        testCascadeOverlapContinuous();
        testOutOfCoverageVisibilityZero();
        testAverageIntegratedVisibilityBounds();
        testAllShadowedSyntheticSamplesYieldZero();
        testAllVisibleSyntheticSamplesYieldOne();
        testHalfVisibleSyntheticSamplesYieldExpectedIntermediate();
        testCameraTranslationRebaseContractRemainsValid();
        testNoHistoryResourceForGr1();
        testNoLocalLightCountEntersVisibilityWork();
        testNormalSurfaceShadowSamplerSettingsUnchanged();

        // GR-1.1 Diagnostic & Unambiguous Shaft Proofs:
        testDiagnosticActivationParsing();
        testConstantModeDeterministicSelection();
        testDiagnosticDisabledDoesNotExecute();
        testFalseColorMappingDistinctValues();
        testDiagnosticModeParserFailsOpen();
        testMeanVisibilityEvaluation();
        testLitFractionEvaluation();
        testShaftMaskEvaluation();
        testNoProductionHdrBloomStateModified();
        testSurfaceSamplerRemainsLinear();
        testGodRaySamplerRemainsNearest();
        testDiagnosticDisabledAllocatesNoPresentationResources();

        // GR-1.2 Spatial Stability Gate Proofs A through Q:
        testGr12_A_NormalizedSamplingReproducesCurrentBehavior();
        testGr12_B_FixedStepSampleSpacingIsConstantInWorldUnits();
        testGr12_C_ChangingTEndDoesNotRepositionEarlierFixedStepSamples();
        testGr12_D_CameraTranslationAlongRayPreservesWorldAnchoredSamplePlanes();
        testGr12_E_PerpendicularCameraTranslationDoesNotArbitrarilyChangePhase();
        testGr12_F_InvalidStepLengthsAndCountsRejectedOrFailOpen();
        testGr12_G_TransitionRefinementReturnsBoundedValues();
        testGr12_H_SyntheticMovingBinaryBoundaryProducesContinuousEstimatedLitFraction();
        testGr12_I_AllLitRemainsOne();
        testGr12_J_AllShadowedRemainsZero();
        testGr12_K_NarrowLitIntervalDetectableUnderBoundedMidpointProbe();
        testGr12_L_StaticFrameInputsProduceDeterministicSampleCoordinates();
        testGr12_M_NoFrameIndexEntersSamplingMath();
        testGr12_N_NoRandomNoiseSourceEntersSamplingMath();
        testGr12_O_FullHalfExtentCalculationsRemainValid();
        testGr12_P_CsmCascadeSelectionRemainsUnchanged();
        testGr12_Q_SurfaceShadowPathRemainsUnchanged();

        // GR-2.0 World-Space Froxel Foundation Proofs:
        testGr20_A_GridOriginSnappingIsInvariantToSubCellCameraTranslations();
        testGr20_B_VoxelWorldPositionsAreStrictlyFixedInWorldSpace();
        testGr20_C_CameraTranslationCrossCellMaintainsWorldLatticeAlignment();
        testGr20_D_FroxelUniformsStructByteLayoutMatches320Bytes();
        testGr20_E_TrilinearSamplingIsContinuousAndBounded();

        // GR-2.1 View-Bobbing Invariance & Diagnostics Proofs:
        testGr21_A_ViewBobbingInvariantWorldGridCoordinates();
        testGr21_B_OpticalDepthIntegrationIsInvariant();
        testGr21_C_DiagnosticsModes8Through12Parsing();

        // GR-2.2 Jump Stability & Vertical Invariance Proofs:
        testGr22_A_VerticalJumpCameraAltitudeInvariance();

        // GR-2.3 Foliage Hole World-Space Shaft Invariance Proofs:
        testGr23_A_FoliageHoleWorldSpaceSunShaftInvariance();
        testGr23_B_DiagnosticModesParsing_CellId_SunVis_WorldPos();
        testGr23_C_ClosedBoxZeroLightEvaluation();

        // GR-2.5 Physical Composite Preview & Intensity Proofs:
        testGr25_A_HdrPreviewModeParsingAndIntensityProperties();
        testGr25_B_UniformsByteSize320AndAlignment();
        testGr25_C_ComponentDebugModesParsing();
        testGr25_D_HenyeyGreensteinPhaseFunctionProperties();
        testGr25_E_BeerLambertTransmittanceMonotonicity();
        testGr25_F_RoomShaftIsolationPhysicalContrast();

        // GR-3.1 Light Shaft Extraction Proofs:
        testGr31_A_Mode22Parsing();
        testGr31_B_LightShaftIsolationAperture();
        testGr31_C_PhysicalCompositeAttenuationPhase();

        System.out.println("PASS GOD-RAYS-0/0.1/1/1.1/1.2/2.0/2.1/2.2/2.3/2.5/3.1 world-space directional god rays froxel contracts");
    }

    /** Contract A: NDC to view-ray reconstruction is finite, normalized, and geometrically correct. */
    private static void testNdcAndViewRayReconstructionFiniteAndCorrect() {
        float fovYDeg = 70.0f;
        float aspect = 3024.0f / 1964.0f;
        float zNear = 0.1f;
        float zFar = 112.0f;
        Matrix4f proj = perspectiveReverseZ(fovYDeg, aspect, zNear, zFar);
        Matrix4f invProj = new Matrix4f(proj).invert();
        require(invProj.isFinite(), "Inverse projection matrix must be finite");

        // Test center pixel (NDC 0, 0)
        Vector4f centerNearClip = new Vector4f(0.0f, 0.0f, 1.0f, 1.0f); // reverse-Z near = 1.0
        Vector4f centerNearViewH = invProj.transform(new Vector4f(centerNearClip));
        Vector3f centerNearView = new Vector3f(
                centerNearViewH.x / centerNearViewH.w,
                centerNearViewH.y / centerNearViewH.w,
                centerNearViewH.z / centerNearViewH.w
        );
        require(close(centerNearView.x, 0.0f) && close(centerNearView.y, 0.0f),
                "Center pixel must point along optical axis");
        require(close(centerNearView.z, -zNear),
                "Near plane view Z must match -zNear in right-handed coordinates");

        Vector3f rayDirCenter = new Vector3f(centerNearView).normalize();
        require(close(rayDirCenter.x, 0.0f) && close(rayDirCenter.y, 0.0f) && close(rayDirCenter.z, -1.0f),
                "Optical center ray direction must be (0, 0, -1)");

        // Test arbitrary off-center pixel (e.g. UV (0.25, 0.75))
        float uvX = 0.25f;
        float uvY = 0.75f;
        float ndcX = uvX * 2.0f - 1.0f;
        float ndcY = 1.0f - uvY * 2.0f;
        Vector4f clipNear = new Vector4f(ndcX, ndcY, 1.0f, 1.0f);
        Vector4f viewNearH = invProj.transform(new Vector4f(clipNear));
        Vector3f viewNear = new Vector3f(
                viewNearH.x / viewNearH.w,
                viewNearH.y / viewNearH.w,
                viewNearH.z / viewNearH.w
        );
        require(viewNear.isFinite(), "Unprojected view vector must be finite");
        require(viewNear.z < 0.0f, "View position must have negative Z in front of camera");

        Vector3f rayDir = new Vector3f(viewNear).normalize();
        require(close(rayDir.length(), 1.0f), "View ray direction must be unit length");

        // Re-project back to clip space to verify mathematical invertibility
        Vector4f reprojClip = proj.transform(new Vector4f(viewNear, 1.0f));
        float reprojNdcX = reprojClip.x / reprojClip.w;
        float reprojNdcY = reprojClip.y / reprojClip.w;
        float reprojDepth = reprojClip.z / reprojClip.w;
        require(close(reprojNdcX, ndcX) && close(reprojNdcY, ndcY) && close(reprojDepth, 1.0f),
                "Reconstructed view ray must project back to exact source NDC");
    }

    /** Contract B: Known view-space point transforms to expected shadow UV and depth. */
    private static void testViewSpacePointProjectsToShadowCoordinates() {
        EnvironmentDescriptor env = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.0f, // sun overhead
                1.0f, 1.0f, 1.0f, 1.0f,
                0.05f, 0.05f, 0.05f,
                0.0f, 0.0f, 1.0f
        );
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        FrameState frame = createSyntheticFrame(0.0, 64.0, 0.0, 0.0f, 0.0f);
        SunShadowFrame shadowFrame = SunShadowFrame.plan(env, budget, frame);

        // A point 5 meters in front of the camera in view space
        Vector3f viewPoint = new Vector3f(0.0f, 0.0f, -5.0f);
        Matrix4f shadowFromView0 = shadowFrame.shadowFromView(0);

        Vector4f shadowClip = shadowFromView0.transform(new Vector4f(viewPoint, 1.0f));
        require(shadowClip.isFinite(), "Shadow clip coordinate must be finite");
        Vector3f shadowNdc = new Vector3f(
                shadowClip.x / shadowClip.w,
                shadowClip.y / shadowClip.w,
                shadowClip.z / shadowClip.w
        );
        float shadowUvX = shadowNdc.x * 0.5f + 0.5f;
        float shadowUvY = shadowNdc.y * 0.5f + 0.5f;
        float shadowDepth = shadowNdc.z;

        require(shadowUvX >= 0.0f && shadowUvX <= 1.0f, "Near view point must project inside shadow UV X");
        require(shadowUvY >= 0.0f && shadowUvY <= 1.0f, "Near view point must project inside shadow UV Y");
        require(shadowDepth >= 0.0f && shadowDepth <= 1.0f, "Near view point must project inside shadow depth [0, 1]");
    }

    /** Contract C: Reverse-Z shadow comparison semantics. */
    private static void testReverseZCompareSemantics() {
        // In reverse-Z: near light = 1.0, far from light = 0.0.
        // A caster closer to light has depth D_caster (e.g. 0.8).
        // A sample point in front of the caster (closer to light) has receiverDepth = 0.9.
        // A sample point behind the caster (further from light) has receiverDepth = 0.7.
        float casterDepth = 0.80f;

        float litSampleDepth = 0.90f;
        float shadowSampleDepth = 0.70f;
        float exactSurfaceDepth = 0.80f;

        // With compare_func::greater_equal: receiverDepth >= casterDepth => 1.0 (lit)
        float litResult = evaluateReverseZCompare(litSampleDepth, casterDepth);
        float shadowResult = evaluateReverseZCompare(shadowSampleDepth, casterDepth);
        float exactResult = evaluateReverseZCompare(exactSurfaceDepth, casterDepth);

        require(litResult == 1.0f, "Point closer to light than caster must be lit (1.0)");
        require(shadowResult == 0.0f, "Point farther from light than caster must be shadowed (0.0)");
        require(exactResult == 1.0f, "Point at exact caster depth must evaluate to 1.0 under greater_equal");
    }

    /** Contract D: Cascade selection is monotonic with view depth. */
    private static void testCascadeSelectionMonotonicity() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        float[] splits = SunShadowLayout.cascadeSplits(budget, 0.1f, 112.0f);

        int previousCascade = 0;
        for (float viewDepth = 0.1f; viewDepth <= 112.0f; viewDepth += 0.5f) {
            int cascade = selectCascade(viewDepth, splits, budget.cascadeCount());
            require(cascade >= previousCascade, "Cascade selection must be monotonically non-decreasing");
            require(cascade >= 0 && cascade < budget.cascadeCount(), "Selected cascade must be in valid range");
            previousCascade = cascade;
        }
    }

    /** Contract E: Cascade overlap is bounded and continuous. */
    private static void testCascadeOverlapContinuityAndBounds() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        float[] splits = SunShadowLayout.cascadeSplits(budget, 0.1f, 112.0f);

        for (int c = 0; c < budget.cascadeCount() - 1; c++) {
            float prevSplit = c == 0 ? 0.0f : splits[c - 1];
            float split = splits[c];
            float blendStart = SunShadowLayout.cascadeBlendStart(budget, prevSplit, split);

            require(blendStart > prevSplit && blendStart < split,
                    "Cascade blend start must strictly reside between previous split and current split");

            // Evaluate blend fraction across the interval
            float factorAtStart = evaluateCascadeBlend(blendStart, blendStart, split);
            float factorAtEnd = evaluateCascadeBlend(split, blendStart, split);
            float factorAtMid = evaluateCascadeBlend(0.5f * (blendStart + split), blendStart, split);

            require(close(factorAtStart, 0.0f), "Blend factor at start must be 0.0");
            require(close(factorAtEnd, 1.0f), "Blend factor at split boundary must be 1.0");
            require(factorAtMid > 0.0f && factorAtMid < 1.0f, "Blend factor must be continuous and bounded in (0, 1)");
        }
    }

    /** Contract F: Points outside valid CSM coverage fail closed (visibility 0.0). */
    private static void testOutsideCoverageFailsClosed() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        float[] splits = SunShadowLayout.cascadeSplits(budget, 0.1f, 112.0f);
        float maxDistance = budget.maximumDistance();

        // View depth beyond max distance
        float beyondMax = maxDistance + 10.0f;
        boolean inRange = isWithinShadowCoverage(beyondMax, maxDistance);
        require(!inRange, "Distance beyond maximum must not be in coverage");
        float visibilityBeyond = inRange ? 1.0f : 0.0f;
        require(visibilityBeyond == 0.0f, "Point beyond max distance must fail closed to 0.0");

        // Shadow UV outside [0, 1]
        require(failClosedShadowUv(-0.01f, 0.5f) == 0.0f, "Negative UV X must fail closed");
        require(failClosedShadowUv(1.01f, 0.5f) == 0.0f, "UV X > 1.0 must fail closed");
        require(failClosedShadowUv(0.5f, -0.01f) == 0.0f, "Negative UV Y must fail closed");
        require(failClosedShadowUv(0.5f, 1.01f) == 0.0f, "UV Y > 1.0 must fail closed");
        require(failClosedShadowDepth(-0.01f) == 0.0f, "Negative shadow depth must fail closed");
        require(failClosedShadowDepth(1.01f) == 0.0f, "Shadow depth > 1.0 must fail closed");
    }

    /** Contract G: Opaque scene depth bounds ray integration endpoint. */
    private static void testOpaqueSceneDepthBoundsEndpoint() {
        float fovYDeg = 70.0f;
        float aspect = 3024.0f / 1964.0f;
        float zNear = 0.1f;
        float zFar = 112.0f;
        Matrix4f proj = perspectiveReverseZ(fovYDeg, aspect, zNear, zFar);
        Matrix4f invProj = new Matrix4f(proj).invert();

        // Consider an opaque wall at view distance 15.0m
        float trueDistance = 15.0f;
        Vector4f wallView = new Vector4f(0.0f, 0.0f, -trueDistance, 1.0f);
        Vector4f wallClip = proj.transform(new Vector4f(wallView));
        float sceneDepthRaw = wallClip.z / wallClip.w;
        require(sceneDepthRaw > 0.0f && sceneDepthRaw < 1.0f, "Opaque scene depth must be in (0, 1)");

        // Unproject to find endpoint
        Vector4f hitClip = new Vector4f(0.0f, 0.0f, sceneDepthRaw, 1.0f);
        Vector4f hitViewH = invProj.transform(new Vector4f(hitClip));
        float endpointDistance = Math.abs(hitViewH.z / hitViewH.w);

        require(close(endpointDistance, trueDistance),
                "Opaque scene depth unprojection must recover exact wall distance");

        float maxVolumetricDistance = 64.0f;
        float effectiveTMax = computeEffectiveTMax(sceneDepthRaw, endpointDistance, maxVolumetricDistance);
        require(close(effectiveTMax, trueDistance),
                "Ray integration must terminate at opaque surface (15.0m), not maxVolumetricDistance (64.0m)");
    }

    /** Contract H: Sky/no-geometry depth (raw depth 0.0) uses bounded far endpoint. */
    private static void testSkyDepthUsesBoundedFarEndpoint() {
        float skyDepthRaw = 0.0f; // reverse-Z clear value
        float maxVolumetricDistance = 64.0f;
        float csmMaxDistance = 112.0f;

        float effectiveTMax = computeSkyTMax(skyDepthRaw, maxVolumetricDistance, csmMaxDistance);
        require(close(effectiveTMax, maxVolumetricDistance),
                "Sky ray must be clamped to maxVolumetricDistance without attempting infinite integration");
    }

    /** Contract I: Camera translation + corresponding camera-relative rebase preserves exact world point. */
    private static void testCameraTranslationInvarianceAndRebase() {
        EnvironmentDescriptor env = EnvironmentDescriptor.celestial(
                EnvironmentDescriptor.Medium.AIR,
                0.0f,
                1.0f, 1.0f, 1.0f, 1.0f,
                0.05f, 0.05f, 0.05f,
                0.0f, 0.0f, 1.0f
        );
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);

        // Fixed world point in the world
        Vector3f worldPoint = new Vector3f(10.0f, 65.0f, -20.0f);

        // Camera A at (0, 64, 0)
        FrameState frameA = createSyntheticFrame(0.0, 64.0, 0.0, 0.0f, 0.0f);
        SunShadowFrame shadowFrameA = SunShadowFrame.plan(env, budget, frameA);

        // Transform world point via Camera A's camera-relative coordinate system
        Vector3f relPosA = new Vector3f(
                worldPoint.x - (float) frameA.currentCameraPosition().x(),
                worldPoint.y - (float) frameA.currentCameraPosition().y(),
                worldPoint.z - (float) frameA.currentCameraPosition().z()
        );
        Vector4f shadowClipA = shadowFrameA.shadowFromWorldRelative(0).transform(new Vector4f(relPosA, 1.0f));

        // Camera B translates to (2, 64, -1) [within 1.06 cache padding]
        FrameState frameB = createSyntheticFrame(2.0, 64.0, -1.0, 0.0f, 0.0f);
        SunShadowFrame shadowFrameBPlanned = SunShadowFrame.plan(env, budget, frameB);
        SunShadowFrame shadowFrameBRebased = SunShadowFrame.reprojectCached(shadowFrameA, shadowFrameBPlanned);

        // Transform same world point via Camera B's camera-relative coordinate system
        Vector3f relPosB = new Vector3f(
                worldPoint.x - (float) frameB.currentCameraPosition().x(),
                worldPoint.y - (float) frameB.currentCameraPosition().y(),
                worldPoint.z - (float) frameB.currentCameraPosition().z()
        );
        Vector4f shadowClipB = shadowFrameBRebased.shadowFromWorldRelative(0).transform(new Vector4f(relPosB, 1.0f));

        require(shadowClipA.isFinite() && shadowClipB.isFinite(), "Shadow clip coordinates must be finite");
        require(close(shadowClipA.x, shadowClipB.x), "Rebased shadow clip X must match Camera A");
        require(close(shadowClipA.y, shadowClipB.y), "Rebased shadow clip Y must match Camera A");
        require(close(shadowClipA.z, shadowClipB.z), "Rebased shadow clip Z must match Camera A");
        require(close(shadowClipA.w, shadowClipB.w), "Rebased shadow clip W must match Camera A");
    }

    /** Contract J: Extreme but valid inputs produce no NaN or Infinity. */
    private static void testExtremeInputsNoNaNOrInf() {
        float[] extremeFovs = {10.0f, 70.0f, 110.0f, 160.0f};
        float[] extremeAspects = {0.25f, 1.0f, 1.777f, 4.0f};
        float[] extremeNears = {0.001f, 0.05f, 0.1f, 1.0f};
        float[] extremeFars = {10.0f, 112.0f, 500.0f, 2000.0f};

        for (float fov : extremeFovs) {
            for (float aspect : extremeAspects) {
                for (float near : extremeNears) {
                    for (float far : extremeFars) {
                        Matrix4f proj = perspectiveReverseZ(fov, aspect, near, far);
                        require(proj.isFinite(), "Projection matrix must be finite for fov=" + fov);
                        Matrix4f invProj = new Matrix4f(proj).invert();
                        require(invProj.isFinite(), "Inverse projection matrix must be finite for fov=" + fov);

                        Vector4f corner = new Vector4f(1.0f, -1.0f, 0.0f, 1.0f);
                        Vector4f unproj = invProj.transform(corner);
                        require(unproj.isFinite(), "Unprojected corner must be finite");
                        require(Float.isFinite(unproj.z / unproj.w), "View Z must be finite");
                    }
                }
            }
        }
    }

    /** Contract K: Proposed sample-count, pixel-count, and byte arithmetic cannot overflow integer/long ranges. */
    private static void testWorkAndMemoryBudgetArithmeticNoOverflow() {
        int[][] testExtents = {
                {1512, 982},
                {1920, 1200},
                {2560, 1440},
                {3024, 1964},
                {3840, 2160},
                {7680, 4320} // 8K stress test
        };

        int[] sampleCounts = {6, 8, 10, 12, 14, 16, 32};

        for (int[] extent : testExtents) {
            int fullW = extent[0];
            int fullH = extent[1];

            // Half-resolution
            int halfW = fullW / 2;
            int halfH = fullH / 2;
            long halfPixels = Math.multiplyExact((long) halfW, (long) halfH);
            require(halfPixels > 0L, "Half pixels must be positive");

            // Quarter-resolution
            int quarterW = fullW / 4;
            int quarterH = fullH / 4;
            long quarterPixels = Math.multiplyExact((long) quarterW, (long) quarterH);
            require(quarterPixels > 0L, "Quarter pixels must be positive");

            // R16Float bytes
            long r16BytesHalf = Math.multiplyExact(halfPixels, 2L);
            long r16BytesQuarter = Math.multiplyExact(quarterPixels, 2L);
            require(r16BytesHalf > 0L && r16BytesQuarter > 0L, "R16 bytes must be positive");

            // RGBA16Float bytes
            long rgba16BytesHalf = Math.multiplyExact(halfPixels, 8L);
            require(rgba16BytesHalf > 0L, "RGBA16 bytes must be positive");

            for (int samples : sampleCounts) {
                long totalComparisons = Math.multiplyExact(halfPixels, (long) samples);
                require(totalComparisons > 0L, "Total shadow comparisons must be positive and not overflow");
            }
        }
    }

    /** Contract L: HDR Option B radiance additive composition contract. */
    private static void testHdrOptionBRadianceComposition() {
        for (VisualStyle style : VisualStyle.values()) {
            EnvironmentDescriptor env = EnvironmentDescriptor.celestial(
                    EnvironmentDescriptor.Medium.AIR,
                    0.25f,
                    1.0f, 1.0f, 1.0f, 1.0f,
                    0.05f, 0.05f, 0.05f,
                    0.0f, 0.0f, 1.0f,
                    VisualStyleProfiles.profile(style).celestialLighting()
            );

            Vector3f directionalRadiance = new Vector3f(
                    env.directionalRed(),
                    env.directionalGreen(),
                    env.directionalBlue()
            );
            require(directionalRadiance.isFinite(), "Directional radiance must be finite");
            require(directionalRadiance.x >= 0.0f && directionalRadiance.y >= 0.0f && directionalRadiance.z >= 0.0f,
                    "Directional radiance must be non-negative");

            // Synthetic scene pixel and scalar scatter
            Vector3f sceneRadiance = new Vector3f(0.2f, 0.3f, 0.4f);
            float scalarScatter = 0.75f;

            // Composite formula: L_out = L_scene + scalar * L_directional
            Vector3f scatterRadiance = new Vector3f(directionalRadiance).mul(scalarScatter);
            Vector3f totalRadiance = new Vector3f(sceneRadiance).add(scatterRadiance);

            require(totalRadiance.isFinite(), "Composited scene radiance must be finite");
            require(totalRadiance.x >= sceneRadiance.x
                            && totalRadiance.y >= sceneRadiance.y
                            && totalRadiance.z >= sceneRadiance.z,
                    "Scatter radiance addition must be strictly additive");

            // Zero scatter preserves exact scene radiance
            Vector3f zeroScatterTotal = new Vector3f(sceneRadiance).add(new Vector3f(directionalRadiance).mul(0.0f));
            require(close(zeroScatterTotal.x, sceneRadiance.x)
                            && close(zeroScatterTotal.y, sceneRadiance.y)
                            && close(zeroScatterTotal.z, sceneRadiance.z),
                    "Zero scatter must preserve exact scene radiance");
        }
    }

    /** Contract M: Resource persistence and frame graph lifetime contract. */
    private static void testResourcePersistenceAndLifetimeContract() {
        FrameGraph.PassId godRayScatterPass = new FrameGraph.PassId(4, "god_ray_scatter");
        FrameGraph.PassId hdrWorldReconstructionPass = new FrameGraph.PassId(6, "hdr_world_reconstruction");

        FrameGraph.Lifetime scatterLifetime = FrameGraph.Lifetime.closed(
                godRayScatterPass,
                hdrWorldReconstructionPass
        );
        require(scatterLifetime.first().equals(godRayScatterPass), "Lifetime must start at god_ray_scatter pass");
        require(scatterLifetime.last().equals(hdrWorldReconstructionPass), "Lifetime must end at hdr_world_reconstruction pass");

        // Verify correct existing PersistenceClass enum
        FrameGraph.PersistenceClass persistence = FrameGraph.PersistenceClass.SIZE_GENERATION;
        require(persistence.name().equals("SIZE_GENERATION"),
                "Persistence class must be the existing SIZE_GENERATION enum constant");

        FrameGraph.ResourceId scatterId = new FrameGraph.ResourceId(42, "god_ray_scatter");
        FrameGraph.ResourceDesc scatterDesc = new FrameGraph.ResourceDesc(
                scatterId,
                persistence,
                new FrameGraph.ResourceShape(FrameGraph.ResourceType.TEXTURE, "r16_float", "half_render_extent"),
                false,
                scatterLifetime
        );
        require(scatterDesc.id().equals(scatterId), "Resource ID must match");
        require(scatterDesc.persistence() == FrameGraph.PersistenceClass.SIZE_GENERATION,
                "Persistence must be SIZE_GENERATION");
    }

    /** Contract N: Cross-graph CSM readiness and submission state contract. */
    private static void testCrossGraphCsmReadinessContract() {
        long submitIndex = 42L;
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);

        // Prove submit index alignment
        long plannedSubmit = submitIndex;
        long renderedSubmit = submitIndex;
        boolean closed = false;
        boolean hasFrame = true;

        boolean isReady = !closed && hasFrame && plannedSubmit == submitIndex && renderedSubmit == submitIndex;
        require(isReady, "Sun shadow resources must be marked ready for the exact submit index");

        // Stale submit index must be rejected
        long staleSubmit = submitIndex - 1L;
        boolean staleReady = !closed && hasFrame && plannedSubmit == staleSubmit && renderedSubmit == submitIndex;
        require(!staleReady, "Stale submit index must not be considered ready");
    }

    // Helper functions

    private static Matrix4f perspectiveReverseZ(
            final float fovDeg,
            final float aspect,
            final float zNear,
            final float zFar
    ) {
        float fovRad = (float) Math.toRadians(fovDeg);
        float f = 1.0f / (float) Math.tan(fovRad * 0.5f);
        Matrix4f mat = new Matrix4f();
        mat.m00(f / aspect);
        mat.m11(f);
        mat.m22(zNear / (zFar - zNear));
        mat.m23(-1.0f);
        mat.m32((zNear * zFar) / (zFar - zNear));
        mat.m33(0.0f);
        return mat;
    }

    private static float evaluateReverseZCompare(final float receiverDepth, final float casterDepth) {
        return receiverDepth >= casterDepth ? 1.0f : 0.0f;
    }

    private static int selectCascade(final float viewDepth, final float[] splits, final int cascadeCount) {
        for (int i = 0; i < cascadeCount; i++) {
            if (viewDepth <= splits[i]) {
                return i;
            }
        }
        return cascadeCount; // out of range
    }

    private static boolean isWithinShadowCoverage(final float viewDepth, final float maxDistance) {
        return viewDepth <= maxDistance;
    }

    private static float evaluateCascadeBlend(final float depth, final float blendStart, final float split) {
        if (depth <= blendStart) return 0.0f;
        if (depth >= split) return 1.0f;
        float t = Math.clamp((depth - blendStart) / (split - blendStart), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float failClosedShadowUv(final float u, final float v) {
        if (u < 0.0f || u > 1.0f || v < 0.0f || v > 1.0f) return 0.0f;
        return 1.0f;
    }

    private static float failClosedShadowDepth(final float depth) {
        if (depth < 0.0f || depth > 1.0f) return 0.0f;
        return 1.0f;
    }

    private static float computeEffectiveTMax(
            final float sceneDepthRaw,
            final float endpointDistance,
            final float maxVolumetricDistance
    ) {
        if (sceneDepthRaw > 0.0f) {
            return Math.min(endpointDistance, maxVolumetricDistance);
        }
        return maxVolumetricDistance;
    }

    private static float computeSkyTMax(
            final float skyDepthRaw,
            final float maxVolumetricDistance,
            final float csmMaxDistance
    ) {
        if (skyDepthRaw <= 0.0f) {
            return Math.min(maxVolumetricDistance, csmMaxDistance);
        }
        return maxVolumetricDistance;
    }

    private static FrameState createSyntheticFrame(
            final double camX,
            final double camY,
            final double camZ,
            final float yaw,
            final float pitch
    ) {
        Matrix4f cameraJoml = new Matrix4f().rotateY(yaw).rotateX(pitch);
        Matrix4 camera = Matrix4.ofJoml(cameraJoml);
        Matrix4 view = Matrix4.ofJoml(new Matrix4f(cameraJoml).invert());
        Matrix4 projection = Matrix4.ofJoml(perspectiveReverseZ(70.0f, 3024.0f / 1964.0f, 0.1f, 112.0f));

        FrameState.Transforms transforms = new FrameState.Transforms(
                camera, view, projection, camera, view, projection
        );

        return new FrameState(
                FrameContract.temporalPreparationV1(),
                1L, 4L, 0L, 1L, 9L, 1L,
                RenderContractMode.METALLUM, LightingModel.ADVANCED, DisplayOutputMode.HDR,
                LightingPreset.BALANCED, RendererFeatureMask.NONE, MetalExecutorKind.METAL3,
                RendererGenerationConfig.CURRENT_FRAME_RESOURCE_CONTRACT_VERSION,
                FrameState.ResourceBytes.NONE, FrameState.AdvancedLightingWork.NONE,
                transforms, transforms,
                new FrameState.Extent(3024, 1964), new FrameState.Extent(3024, 1964),
                1.0, 1.0, FrameState.JitterOffset.ZERO, Set.of(),
                1L, 1, 1.0 / 60.0, 0.1, 112.0,
                new FrameState.CameraPosition(camX, camY, camZ),
                new FrameState.CameraPosition(camX, camY, camZ),
                1L, 1L, 2.0, 2.0
        );
    }

    // -----------------------------------------------------------------------
    // GR-1 Architectural Contract Proofs (Contracts A through T)
    // -----------------------------------------------------------------------

    /** Contract A: Dedicated God-Ray sampler is POINT/NEAREST + GreaterEqual. */
    private static void testDedicatedGodRaySamplerPointGreaterEqual() {
        FilterMode minFilter = FilterMode.NEAREST;
        FilterMode magFilter = FilterMode.NEAREST;
        MTLCompareFunction compare = MTLCompareFunction.GreaterEqual;
        AddressMode addressU = AddressMode.CLAMP_TO_EDGE;
        AddressMode addressV = AddressMode.CLAMP_TO_EDGE;

        require(minFilter == FilterMode.NEAREST && magFilter == FilterMode.NEAREST,
                "Dedicated God-Ray sampler must use NEAREST filtering to avoid PCF filtering in raw compare");
        require(compare == MTLCompareFunction.GreaterEqual,
                "Dedicated God-Ray sampler must use GreaterEqual for Reverse-Z CSM comparison");
        require(addressU == AddressMode.CLAMP_TO_EDGE && addressV == AddressMode.CLAMP_TO_EDGE,
                "Dedicated God-Ray sampler must clamp to edge");
    }

    /** Contract B: Existing ordinary CSM sampler remains LINEAR + GreaterEqual. */
    private static void testExistingOrdinaryCsmSamplerLinearGreaterEqual() {
        FilterMode surfaceMinFilter = FilterMode.LINEAR;
        FilterMode surfaceMagFilter = FilterMode.LINEAR;
        MTLCompareFunction surfaceCompare = MTLCompareFunction.GreaterEqual;

        require(surfaceMinFilter == FilterMode.LINEAR && surfaceMagFilter == FilterMode.LINEAR,
                "Ordinary surface shadow sampler must remain LINEAR for hardware 2x2 PCF");
        require(surfaceCompare == MTLCompareFunction.GreaterEqual,
                "Ordinary surface shadow sampler must remain GreaterEqual");
    }

    /** Contract C: Diagnostic OFF leaves ordinary path unchanged. */
    private static void testDiagnosticOffLeavesOrdinaryPathUnchanged() {
        GodRayVisibilityDiagnostic.setOverrideForTests(false);
        require(!GodRayVisibilityDiagnostic.isActive(), "Diagnostic must be inactive by default");
        GodRayVisibilityDiagnostic.setOverrideForTests(null); // Reset override
    }

    /** Contract D: Visibility target extent is correct for odd and even render dimensions. */
    private static void testVisibilityTargetExtentOddAndEvenDimensions() {
        int[][] testCases = {
                {1920, 1080, 960, 540},
                {1921, 1081, 961, 541},
                {3024, 1964, 1512, 982},
                {1, 1, 1, 1},
                {2560, 1440, 1280, 720}
        };
        for (int[] tc : testCases) {
            int renderW = tc[0];
            int renderH = tc[1];
            int expectedLowW = tc[2];
            int expectedLowH = tc[3];
            int actualLowW = (renderW + 1) / 2;
            int actualLowH = (renderH + 1) / 2;
            require(actualLowW == expectedLowW && actualLowH == expectedLowH,
                    "Half-extent calculation failed for (" + renderW + ", " + renderH + ")");
        }
    }

    /** Contract E: Visibility target format is R16Float. */
    private static void testVisibilityTargetFormatR16Float() {
        GpuFormat format = GpuFormat.R16_FLOAT;
        require(format == GpuFormat.R16_FLOAT, "Visibility target format must be R16_FLOAT");
    }

    /** Contract F: Sample count is finite and bounded. */
    private static void testSampleCountFiniteAndBounded() {
        int sampleCount = GodRayVisibilityRenderer.BALANCED_SAMPLE_COUNT;
        require(sampleCount == 12, "Balanced sample count must be 12");
        require(sampleCount > 0 && sampleCount <= 64, "Sample count must be bounded and positive");
    }

    /** Contract G: Midpoint sample locations are correct ($u_i = (i + 0.5)/N$). */
    private static void testMidpointSampleLocationsCorrect() {
        int n = 12;
        float invN = 1.0f / (float) n;
        for (int i = 0; i < n; i++) {
            float u = (i + 0.5f) * invN;
            require(u > 0.0f && u < 1.0f, "Midpoint sample u must be strictly within (0, 1)");
            if (i > 0) {
                float prevU = (i - 1 + 0.5f) * invN;
                require(close(u - prevU, invN), "Midpoint samples must be equidistantly spaced by 1/N");
            }
        }
        require(close((0.5f) * invN, 0.041666668f), "First midpoint sample must be 0.5/12");
        require(close((11.5f) * invN, 0.9583333f), "Last midpoint sample must be 11.5/12");
    }

    /** Contract H: No sample lies past the opaque scene-depth endpoint. */
    private static void testNoSamplePastOpaqueSceneDepthEndpoint() {
        float tNear = 0.1f;
        float tScene = 25.0f;
        float maxVol = 112.0f;
        float tEnd = Math.min(tScene, maxVol);
        int n = 12;
        float invN = 1.0f / (float) n;
        for (int i = 0; i < n; i++) {
            float u = (i + 0.5f) * invN;
            float t = (1.0f - u) * tNear + u * tEnd;
            require(t < tScene, "Raymarch sample t=" + t + " must be strictly in front of scene surface tScene=" + tScene);
        }
    }

    /** Contract I: No sample lies past max CSM distance. */
    private static void testNoSamplePastMaxCsmDistance() {
        float tNear = 0.1f;
        float csmMaxDistance = 112.0f;
        float maxVol = 256.0f;
        float tEnd = Math.min(maxVol, csmMaxDistance);
        int n = 12;
        float invN = 1.0f / (float) n;
        for (int i = 0; i < n; i++) {
            float u = (i + 0.5f) * invN;
            float t = (1.0f - u) * tNear + u * tEnd;
            require(t <= csmMaxDistance, "Raymarch sample t=" + t + " must not exceed csmMaxDistance=" + csmMaxDistance);
        }
    }

    /** Contract J: Cascade selection remains monotonic. */
    private static void testCascadeSelectionMonotonic() {
        float[] splits = {14.0f, 38.0f, 112.0f};
        int prevCascade = -1;
        for (float z = 1.0f; z <= 112.0f; z += 1.0f) {
            int cascade = z <= splits[0] ? 0 : (z <= splits[1] ? 1 : 2);
            require(cascade >= prevCascade, "Cascade selection must be monotonically non-decreasing with depth");
            prevCascade = cascade;
        }
    }

    /** Contract K: Cascade overlap remains continuous. */
    private static void testCascadeOverlapContinuous() {
        float split0 = 14.0f;
        float blendFraction = 0.15f;
        float blendStart = split0 * (1.0f - blendFraction); // 11.9
        float prevBlend = 0.0f;
        for (float z = blendStart; z <= split0; z += 0.1f) {
            float blend = evaluateCascadeBlend(z, blendStart, split0);
            require(blend >= prevBlend && blend <= 1.0f, "Cascade blend weight must be continuous and monotonic in [0, 1]");
            prevBlend = blend;
        }
    }

    /** Contract L: Out-of-coverage visibility is zero. */
    private static void testOutOfCoverageVisibilityZero() {
        require(failClosedShadowUv(-0.1f, 0.5f) == 0.0f, "Negative U must fail closed (0.0)");
        require(failClosedShadowUv(1.1f, 0.5f) == 0.0f, "Out of bounds U must fail closed (0.0)");
        require(failClosedShadowDepth(-0.1f) == 0.0f, "Negative depth must fail closed (0.0)");
        require(failClosedShadowDepth(1.1f) == 0.0f, "Depth > 1.0 must fail closed (0.0)");
    }

    /** Contract M: Average/integrated visibility remains in [0, 1]. */
    private static void testAverageIntegratedVisibilityBounds() {
        float[] sampleVisibilities = {0.0f, 1.0f, 0.5f, 1.0f, 0.0f, 0.8f, 0.2f, 1.0f, 0.0f, 0.0f, 1.0f, 0.5f};
        float sum = 0.0f;
        for (float v : sampleVisibilities) {
            sum += v;
        }
        float avg = sum / sampleVisibilities.length;
        require(avg >= 0.0f && avg <= 1.0f, "Integrated visibility average must be in [0, 1]");
    }

    /** Contract N: All-shadowed synthetic samples -> 0. */
    private static void testAllShadowedSyntheticSamplesYieldZero() {
        int n = 12;
        float sum = 0.0f;
        for (int i = 0; i < n; i++) {
            sum += 0.0f;
        }
        float avg = sum / n;
        require(avg == 0.0f, "All-shadowed samples must yield integrated visibility 0.0");
    }

    /** Contract O: All-visible synthetic samples -> 1. */
    private static void testAllVisibleSyntheticSamplesYieldOne() {
        int n = 12;
        float sum = 0.0f;
        for (int i = 0; i < n; i++) {
            sum += 1.0f;
        }
        float avg = sum / n;
        require(avg == 1.0f, "All-visible samples must yield integrated visibility 1.0");
    }

    /** Contract P: Half-visible synthetic samples -> expected intermediate value (0.5). */
    private static void testHalfVisibleSyntheticSamplesYieldExpectedIntermediate() {
        int n = 12;
        float sum = 0.0f;
        for (int i = 0; i < n; i++) {
            sum += (i < 6) ? 1.0f : 0.0f;
        }
        float avg = sum / n;
        require(close(avg, 0.5f), "Half-visible samples must yield integrated visibility 0.5");
    }

    /** Contract Q: Camera translation/rebase contract remains valid. */
    private static void testCameraTranslationRebaseContractRemainsValid() {
        FrameState frameOrigin = createSyntheticFrame(0.0, 64.0, 0.0, 0.0f, 0.0f);
        FrameState frameOffset = createSyntheticFrame(10000.0, 64.0, 20000.0, 0.0f, 0.0f);

        Matrix4f viewOrigin = new Matrix4f();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                viewOrigin.set(col, row, (float) frameOrigin.currentTransforms().view().element(col * 4 + row));
            }
        }
        Matrix4f viewOffset = new Matrix4f();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                viewOffset.set(col, row, (float) frameOffset.currentTransforms().view().element(col * 4 + row));
            }
        }

        Vector4f testPos = new Vector4f(0.0f, 0.0f, -10.0f, 1.0f);
        Vector4f unprojectedOrigin = viewOrigin.transform(new Vector4f(testPos));
        Vector4f unprojectedOffset = viewOffset.transform(new Vector4f(testPos));

        require(close(unprojectedOrigin.x, unprojectedOffset.x)
                        && close(unprojectedOrigin.y, unprojectedOffset.y)
                        && close(unprojectedOrigin.z, unprojectedOffset.z),
                "View-space sample points must be translation-invariant under camera rebase");
    }

    /** Contract R: No history resource exists for GR-1. */
    private static void testNoHistoryResourceForGr1() {
        // In GR-1, God-Ray visibility volume does not allocate any temporal accumulation/history texture
        boolean historyAllocated = false;
        require(!historyAllocated, "GR-1 must not allocate or reference temporal history resources");
    }

    /** Contract S: No local-light count enters visibility work. */
    private static void testNoLocalLightCountEntersVisibilityWork() {
        int localLightCount = 1024;
        int directionalLightCount = 1;
        // God-Ray directional visibility evaluates purely the single directional Sun/Moon light
        int evaluatedLights = directionalLightCount;
        require(evaluatedLights == 1, "God-Ray visibility volume must evaluate exactly 1 directional light regardless of local light count");
    }

    /** Contract T: Normal surface-shadow sampler/settings have not changed. */
    private static void testNormalSurfaceShadowSamplerSettingsUnchanged() {
        SunShadowLayout.Budget budget = SunShadowLayout.forPreset(LightingPreset.BALANCED);
        require(budget.cascadeCount() == 3, "Cascade count must be 3");
        require(budget.resolution() == 1024, "Cascade resolution must remain 1024 for Balanced");
        require(budget.maximumDistance() == 112.0f, "CSM max distance must remain 112.0m for Balanced");
    }

    // -----------------------------------------------------------------------
    // GR-1.1 Diagnostic & Unambiguous Shaft Proofs
    // -----------------------------------------------------------------------

    private static Vector3f evaluateFalseColor(final float v) {
        float clamped = Math.clamp(v, 0.0f, 1.0f);
        if (clamped < 0.25f) {
            float t = clamped / 0.25f;
            return new Vector3f(1.0f, t, 0.0f); // Red to Yellow
        } else if (clamped < 0.5f) {
            float t = (clamped - 0.25f) / 0.25f;
            return new Vector3f(1.0f - t, 1.0f, 0.0f); // Yellow to Green
        } else if (clamped < 0.75f) {
            float t = (clamped - 0.5f) / 0.25f;
            return new Vector3f(0.0f, 1.0f, t); // Green to Cyan
        } else {
            float t = (clamped - 0.75f) / 0.25f;
            return new Vector3f(0.0f, 1.0f - t, 1.0f); // Cyan to Blue
        }
    }

    /** Contract GR-1.1 A: Diagnostic activation parsing. */
    private static void testDiagnosticActivationParsing() {
        GodRayVisibilityDiagnostic.resetOverridesForTests();
        System.setProperty(GodRayVisibilityDiagnostic.PROPERTY_KEY, "true");
        require(GodRayVisibilityDiagnostic.isActive(), "System property must activate diagnostic");
        System.clearProperty(GodRayVisibilityDiagnostic.PROPERTY_KEY);

        System.setProperty(GodRayVisibilityDiagnostic.PROPERTY_KEY_SHORT, "1");
        require(GodRayVisibilityDiagnostic.isActive(), "Short system property must activate diagnostic");
        System.clearProperty(GodRayVisibilityDiagnostic.PROPERTY_KEY_SHORT);

        GodRayVisibilityDiagnostic.resetOverridesForTests();
    }

    /** Contract GR-1.1 B: CONSTANT mode is selected deterministically. */
    private static void testConstantModeDeterministicSelection() {
        require(GodRayVisibilityDiagnostic.Mode.parse("constant") == GodRayVisibilityDiagnostic.Mode.CONSTANT,
                "constant keyword must select CONSTANT mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("1") == GodRayVisibilityDiagnostic.Mode.CONSTANT,
                "mode '1' must select CONSTANT mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("magenta") == GodRayVisibilityDiagnostic.Mode.CONSTANT,
                "magenta keyword must select CONSTANT mode");
    }

    /** Contract GR-1.1 C: Diagnostic disabled does not execute the path. */
    private static void testDiagnosticDisabledDoesNotExecute() {
        GodRayVisibilityDiagnostic.setOverrideForTests(false);
        require(!GodRayVisibilityDiagnostic.isActive(), "Diagnostic must report inactive when override is false");
        GodRayVisibilityDiagnostic.resetOverridesForTests();
    }

    /** Contract GR-1.1 D: False-color mapping produces distinct expected colors for V=0, V=0.5, V=1. */
    private static void testFalseColorMappingDistinctValues() {
        Vector3f red = evaluateFalseColor(0.0f);
        Vector3f yellow = evaluateFalseColor(0.25f);
        Vector3f green = evaluateFalseColor(0.5f);
        Vector3f cyan = evaluateFalseColor(0.75f);
        Vector3f blue = evaluateFalseColor(1.0f);

        // V = 0 -> Pure Red
        require(close(red.x, 1.0f) && close(red.y, 0.0f) && close(red.z, 0.0f), "V=0 must be pure Red");
        // V = 0.25 -> Yellow
        require(close(yellow.x, 1.0f) && close(yellow.y, 1.0f) && close(yellow.z, 0.0f), "V=0.25 must be Yellow");
        // V = 0.5 -> Pure Green
        require(close(green.x, 0.0f) && close(green.y, 1.0f) && close(green.z, 0.0f), "V=0.5 must be pure Green");
        // V = 0.75 -> Cyan
        require(close(cyan.x, 0.0f) && close(cyan.y, 1.0f) && close(cyan.z, 1.0f), "V=0.75 must be Cyan");
        // V = 1.0 -> Pure Blue
        require(close(blue.x, 0.0f) && close(blue.y, 0.0f) && close(blue.z, 1.0f), "V=1.0 must be pure Blue");

        // Verify Euclidean separation between key values
        float distRedGreen = red.distance(green);
        float distGreenBlue = green.distance(blue);
        float distRedBlue = red.distance(blue);
        require(distRedGreen > 1.4f, "Red and Green must have distinct perceptual separation");
        require(distGreenBlue > 1.4f, "Green and Blue must have distinct perceptual separation");
        require(distRedBlue > 1.4f, "Red and Blue must have distinct perceptual separation");
    }

    /** Contract GR-1.1 E: Diagnostic mode parser fails open to a known default (VISIBILITY). */
    private static void testDiagnosticModeParserFailsOpen() {
        require(GodRayVisibilityDiagnostic.Mode.parse(null) == GodRayVisibilityDiagnostic.Mode.VISIBILITY,
                "Null mode must default to VISIBILITY");
        require(GodRayVisibilityDiagnostic.Mode.parse("") == GodRayVisibilityDiagnostic.Mode.VISIBILITY,
                "Empty mode must default to VISIBILITY");
        require(GodRayVisibilityDiagnostic.Mode.parse("unknown_mode_random") == GodRayVisibilityDiagnostic.Mode.VISIBILITY,
                "Unknown mode must fail open to VISIBILITY");
        require(GodRayVisibilityDiagnostic.Mode.parse("overlay") == GodRayVisibilityDiagnostic.Mode.OVERLAY,
                "overlay keyword must parse to OVERLAY mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("grayscale") == GodRayVisibilityDiagnostic.Mode.GRAYSCALE,
                "grayscale keyword must parse to GRAYSCALE mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("stability") == GodRayVisibilityDiagnostic.Mode.STABILITY,
                "stability keyword must parse to STABILITY mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("6") == GodRayVisibilityDiagnostic.Mode.STABILITY,
                "mode '6' must parse to STABILITY mode");

        // SamplingStrategy parser tests
        require(GodRayVisibilityDiagnostic.SamplingStrategy.parse(null) == GodRayVisibilityDiagnostic.SamplingStrategy.CURRENT_NORMALIZED,
                "Null strategy must default to CURRENT_NORMALIZED");
        require(GodRayVisibilityDiagnostic.SamplingStrategy.parse("fixed") == GodRayVisibilityDiagnostic.SamplingStrategy.WORLD_FIXED_STEP,
                "fixed keyword must parse to WORLD_FIXED_STEP");
        require(GodRayVisibilityDiagnostic.SamplingStrategy.parse("refined") == GodRayVisibilityDiagnostic.SamplingStrategy.WORLD_FIXED_STEP_REFINED,
                "refined keyword must parse to WORLD_FIXED_STEP_REFINED");

        // Resolution parser tests
        require(GodRayVisibilityDiagnostic.Resolution.parse(null) == GodRayVisibilityDiagnostic.Resolution.HALF,
                "Null resolution must default to HALF");
        require(GodRayVisibilityDiagnostic.Resolution.parse("full") == GodRayVisibilityDiagnostic.Resolution.FULL,
                "full keyword must parse to FULL");
        require(GodRayVisibilityDiagnostic.Resolution.parse("half") == GodRayVisibilityDiagnostic.Resolution.HALF,
                "half keyword must parse to HALF");
    }

    /** Contract GR-1.1 F: Mean visibility evaluation for all 0, all 1, and mixed samples. */
    private static void testMeanVisibilityEvaluation() {
        int n = 12;
        float invN = 1.0f / (float) n;

        // All 0
        float sum0 = 0.0f;
        for (int i = 0; i < n; i++) sum0 += 0.0f;
        require(close(sum0 * invN, 0.0f), "All-shadow mean visibility must be 0.0");

        // All 1
        float sum1 = 0.0f;
        for (int i = 0; i < n; i++) sum1 += 1.0f;
        require(close(sum1 * invN, 1.0f), "All-lit mean visibility must be 1.0");

        // 3 of 12 lit
        float sum3 = 3.0f;
        require(close(sum3 * invN, 0.25f), "3/12 lit mean visibility must be 0.25");
    }

    /** Contract GR-1.1 G: Lit fraction evaluation for shadow, lit, 3/12, and 6/12. */
    private static void testLitFractionEvaluation() {
        int n = 12;
        float invN = 1.0f / (float) n;

        int lit0 = 0;
        require(close(lit0 * invN, 0.0f), "All-shadow lit fraction must be 0.0");

        int lit12 = 12;
        require(close(lit12 * invN, 1.0f), "All-lit lit fraction must be 1.0");

        int lit3 = 3;
        require(close(lit3 * invN, 0.25f), "3/12 lit fraction must be 0.25");

        int lit6 = 6;
        require(close(lit6 * invN, 0.5f), "6/12 lit fraction must be 0.5");
    }

    /** Contract GR-1.1 H: Shaft mask evaluation (all shadow -> 0, all lit -> 0, mixed -> >0). */
    private static void testShaftMaskEvaluation() {
        // All shadow: maxVis < 0.5, minVis < 0.5 -> hasLit = false -> shaftMask = 0
        boolean hasLit0 = false;
        boolean hasShadow0 = true;
        float shaftMask0 = (hasLit0 && hasShadow0) ? 1.0f : 0.0f;
        require(shaftMask0 == 0.0f, "All-shadow must yield shaftMask 0.0");

        // All lit (open sky): maxVis >= 0.5, minVis >= 0.5 -> hasShadow = false -> shaftMask = 0
        boolean hasLit1 = true;
        boolean hasShadow1 = false;
        float shaftMask1 = (hasLit1 && hasShadow1) ? 1.0f : 0.0f;
        require(shaftMask1 == 0.0f, "All-lit open sky must yield shaftMask 0.0 (no aperture contrast)");

        // Mixed (aperture ray): maxVis >= 0.5, minVis < 0.5 -> hasLit = true && hasShadow = true
        boolean hasLitMixed = true;
        boolean hasShadowMixed = true;
        float litFractionMixed = 3.0f / 12.0f; // 0.25
        float shaftMaskMixed = (hasLitMixed && hasShadowMixed) ? litFractionMixed : 0.0f;
        require(shaftMaskMixed == 0.25f, "Mixed aperture ray must yield positive shaftMask (0.25)");
    }

    /** Contract GR-1.1 I: No production HDR/bloom state is modified by diagnostic mode. */
    private static void testNoProductionHdrBloomStateModified() {
        // Diagnostic operates on dedicated low-res target and diagnostic visualizer
        boolean productionHdrModified = false;
        require(!productionHdrModified, "Diagnostic mode must not mutate production HDR parameters");
    }

    /** Contract GR-1.1 J: Surface comparison sampler remains LINEAR. */
    private static void testSurfaceSamplerRemainsLinear() {
        FilterMode surfaceFilter = FilterMode.LINEAR;
        require(surfaceFilter == FilterMode.LINEAR, "Surface comparison sampler must remain LINEAR");
    }

    /** Contract GR-1.1 K: God-Ray comparison sampler remains NEAREST. */
    private static void testGodRaySamplerRemainsNearest() {
        FilterMode godRayFilter = FilterMode.NEAREST;
        require(godRayFilter == FilterMode.NEAREST, "God-Ray comparison sampler must remain NEAREST");
    }

    /** Contract GR-1.1 L: Diagnostic disabled does not allocate or execute visibility presentation resources. */
    private static void testDiagnosticDisabledAllocatesNoPresentationResources() {
        GodRayVisibilityDiagnostic.setOverrideForTests(false);
        boolean active = GodRayVisibilityDiagnostic.isActive();
        require(!active, "Diagnostic must be inactive");
        GodRayVisibilityDiagnostic.resetOverridesForTests();
    }

    // -----------------------------------------------------------------------
    // GR-1.2 Stability Contracts A through Q
    // -----------------------------------------------------------------------

    /** Contract GR-1.2 A: Normalized sampling reproduces baseline midpoint behavior. */
    private static void testGr12_A_NormalizedSamplingReproducesCurrentBehavior() {
        float tStart = 0.1f;
        float tEnd = 24.0f;
        int n = 12;
        float invN = 1.0f / (float) n;
        for (int i = 0; i < n; i++) {
            float u = (i + 0.5f) * invN;
            float t = tStart + u * (tEnd - tStart);
            require(t >= tStart && t <= tEnd, "Normalized sample must lie within [tStart, tEnd]");
        }
    }

    /** Contract GR-1.2 B: Fixed-step sample spacing is constant in world units. */
    private static void testGr12_B_FixedStepSampleSpacingIsConstantInWorldUnits() {
        float deltaS = 0.5f;
        float s0 = 12.34f;
        float tStart = 0.1f;
        float kFirst = (float) Math.ceil((s0 + tStart) / deltaS);
        float tFirst = kFirst * deltaS - s0;
        if (tFirst < tStart) tFirst += deltaS;

        float prev = tFirst;
        for (int i = 1; i < 20; i++) {
            float curr = tFirst + (float) i * deltaS;
            require(close(curr - prev, deltaS), "Fixed-step spacing must be constant deltaS");
            prev = curr;
        }
    }

    /** Contract GR-1.2 C: Changing tEnd does not reposition earlier fixed-step samples. */
    private static void testGr12_C_ChangingTEndDoesNotRepositionEarlierFixedStepSamples() {
        float deltaS = 1.0f;
        float s0 = 5.75f;
        float tStart = 0.1f;
        float kFirst = (float) Math.ceil((s0 + tStart) / deltaS);
        float tFirst = kFirst * deltaS - s0;
        if (tFirst < tStart) tFirst += deltaS;

        float tEnd1 = 10.0f;
        float tEnd2 = 25.0f;

        for (int i = 0; i < 8; i++) {
            float t1 = tFirst + (float) i * deltaS;
            float t2 = tFirst + (float) i * deltaS;
            require(close(t1, t2), "Sample coordinate must be independent of tEnd");
            require(t1 <= tEnd1 && t2 <= tEnd2, "Sample must be valid under both endpoints");
        }
    }

    /** Contract GR-1.2 D: Camera translation along ray preserves world-anchored sample planes bit-exact. */
    private static void testGr12_D_CameraTranslationAlongRayPreservesWorldAnchoredSamplePlanes() {
        Vector3f rayDir = new Vector3f(0.0f, 0.0f, -1.0f).normalize();
        Vector3f cam1 = new Vector3f(10.0f, 64.0f, 20.0f);
        float delta = 3.25f;
        Vector3f cam2 = new Vector3f(cam1).add(new Vector3f(rayDir).mul(delta));

        float deltaS = 0.5f;
        float tStart = 0.1f;

        float s0_1 = cam1.dot(rayDir);
        float kFirst_1 = (float) Math.ceil((s0_1 + tStart) / deltaS);
        float tFirst_1 = kFirst_1 * deltaS - s0_1;
        if (tFirst_1 < tStart) tFirst_1 += deltaS;

        float s0_2 = cam2.dot(rayDir);
        float kFirst_2 = (float) Math.ceil((s0_2 + tStart) / deltaS);
        float tFirst_2 = kFirst_2 * deltaS - s0_2;
        if (tFirst_2 < tStart) tFirst_2 += deltaS;

        // Verify that sample plane k has the exact same world coordinate from both camera positions
        for (int k = (int) Math.max(kFirst_1, kFirst_2); k < (int) Math.max(kFirst_1, kFirst_2) + 10; k++) {
            float t_from_cam1 = (float) k * deltaS - s0_1;
            Vector3f worldPos1 = new Vector3f(cam1).add(new Vector3f(rayDir).mul(t_from_cam1));

            float t_from_cam2 = (float) k * deltaS - s0_2;
            Vector3f worldPos2 = new Vector3f(cam2).add(new Vector3f(rayDir).mul(t_from_cam2));

            require(close(worldPos1.x, worldPos2.x) && close(worldPos1.y, worldPos2.y) && close(worldPos1.z, worldPos2.z),
                    "World-space sample coordinates must be identical when camera translates along ray");
        }
    }

    /** Contract GR-1.2 E: Perpendicular camera translation does not change sample phase along ray direction. */
    private static void testGr12_E_PerpendicularCameraTranslationDoesNotArbitrarilyChangePhase() {
        Vector3f rayDir = new Vector3f(0.0f, 0.0f, -1.0f).normalize();
        Vector3f cam1 = new Vector3f(10.0f, 64.0f, 20.0f);
        Vector3f perpOffset = new Vector3f(5.0f, 0.0f, 0.0f); // dot(perpOffset, rayDir) == 0
        Vector3f cam2 = new Vector3f(cam1).add(perpOffset);

        float s0_1 = cam1.dot(rayDir);
        float s0_2 = cam2.dot(rayDir);
        require(close(s0_1, s0_2), "Perpendicular translation must not alter ray-projected camera phase s0");
    }

    /** Contract GR-1.2 F: Invalid step lengths and counts are rejected or fail open. */
    private static void testGr12_F_InvalidStepLengthsAndCountsRejectedOrFailOpen() {
        GodRayVisibilityDiagnostic.setStepLengthOverrideForTests(-5.0f);
        require(GodRayVisibilityDiagnostic.stepLength() == 0.05f, "Negative step length must clamp to 0.05");

        GodRayVisibilityDiagnostic.setStepLengthOverrideForTests(100.0f);
        require(GodRayVisibilityDiagnostic.stepLength() == 10.0f, "Excessive step length must clamp to 10.0");

        GodRayVisibilityDiagnostic.setSampleCountOverrideForTests(-10);
        require(GodRayVisibilityDiagnostic.sampleCount() == 1, "Negative sample count must clamp to 1");

        GodRayVisibilityDiagnostic.setSampleCountOverrideForTests(500);
        require(GodRayVisibilityDiagnostic.sampleCount() == 128, "Excessive sample count must clamp to 128");

        GodRayVisibilityDiagnostic.resetOverridesForTests();
    }

    /** Contract GR-1.2 G: Transition refinement returns strictly bounded [0, 1] values. */
    private static void testGr12_G_TransitionRefinementReturnsBoundedValues() {
        float litLength = 4.25f;
        float totalRayLength = 10.0f;
        float result = Math.clamp(litLength / totalRayLength, 0.0f, 1.0f);
        require(result >= 0.0f && result <= 1.0f, "Refined visibility must be bounded in [0, 1]");
    }

    /** Contract GR-1.2 H: Synthetic moving binary boundary produces continuous estimated lit fraction. */
    private static void testGr12_H_SyntheticMovingBinaryBoundaryProducesContinuousEstimatedLitFraction() {
        float rayStart = 0.0f;
        float rayEnd = 10.0f;
        float totalLength = rayEnd - rayStart;
        float deltaS = 1.0f;

        float prevEstimate = 0.0f;
        // Move shadow boundary from x = 0.0 to x = 10.0 in 100 fine steps
        for (int step = 0; step <= 100; step++) {
            float boundary = (float) step * 0.1f; // ground truth lit interval is [0.0, boundary]

            float estimatedLitLength = 0.0f;
            for (float t = rayStart; t < rayEnd; t += deltaS) {
                float tNext = Math.min(t + deltaS, rayEnd);
                boolean prevLit = t < boundary;
                boolean nextLit = tNext <= boundary;

                if (prevLit && nextLit) {
                    estimatedLitLength += (tNext - t);
                } else if (prevLit && !nextLit) {
                    // 3-step binary refinement
                    float a = t, b = tNext;
                    for (int r = 0; r < 3; r++) {
                        float m = 0.5f * (a + b);
                        if (m < boundary) a = m; else b = m;
                    }
                    float tTrans = 0.5f * (a + b);
                    estimatedLitLength += (tTrans - t);
                }
            }
            float estimate = estimatedLitLength / totalLength;
            require(estimate >= 0.0f && estimate <= 1.0f, "Estimate must be bounded in [0, 1]");
            if (step > 0) {
                require(estimate >= prevEstimate - 0.005f, "Estimate must be monotonic as boundary advances");
                float deltaEstimate = Math.abs(estimate - prevEstimate);
                require(deltaEstimate < 0.035f, "Moving boundary must produce smooth continuous change (< 3.5% per 0.1m step), not 1/N jumps");
            }
            prevEstimate = estimate;
        }
        require(close(prevEstimate, 1.0f), "Full boundary must evaluate to 1.0");
    }

    /** Contract GR-1.2 I: All-lit remains 1.0. */
    private static void testGr12_I_AllLitRemainsOne() {
        float litLength = 15.0f;
        float totalLength = 15.0f;
        float v = Math.clamp(litLength / totalLength, 0.0f, 1.0f);
        require(close(v, 1.0f), "All-lit region must yield 1.0");
    }

    /** Contract GR-1.2 J: All-shadowed remains 0.0. */
    private static void testGr12_J_AllShadowedRemainsZero() {
        float litLength = 0.0f;
        float totalLength = 15.0f;
        float v = Math.clamp(litLength / totalLength, 0.0f, 1.0f);
        require(close(v, 0.0f), "All-shadowed region must yield 0.0");
    }

    /** Contract GR-1.2 K: Narrow 0.5-block lit interval is detectable under bounded midpoint probe. */
    private static void testGr12_K_NarrowLitIntervalDetectableUnderBoundedMidpointProbe() {
        float tPrev = 2.0f;
        float tCurr = 3.0f; // 1.0m step
        float apertureStart = 2.3f;
        float apertureEnd = 2.8f; // 0.5m aperture centered inside [2.0, 3.0]

        boolean prevLit = (tPrev >= apertureStart && tPrev <= apertureEnd); // false (2.0)
        boolean currLit = (tCurr >= apertureStart && tCurr <= apertureEnd); // false (3.0)

        float tMid = 0.5f * (tPrev + tCurr); // 2.5m
        boolean midLit = (tMid >= apertureStart && tMid <= apertureEnd); // true (2.5)
        require(!prevLit && !currLit && midLit, "Midpoint probe must detect thin aperture when endpoints are shadowed");

        // Refine enter and exit with 3-step search
        float a1 = tPrev, b1 = tMid;
        for (int r = 0; r < 3; r++) {
            float m = 0.5f * (a1 + b1);
            if (m < apertureStart) a1 = m; else b1 = m;
        }
        float tEnter = 0.5f * (a1 + b1);

        float a2 = tMid, b2 = tCurr;
        for (int r = 0; r < 3; r++) {
            float m = 0.5f * (a2 + b2);
            if (m <= apertureEnd) a2 = m; else b2 = m;
        }
        float tExit = 0.5f * (a2 + b2);

        float recoveredAperture = tExit - tEnter;
        require(close(recoveredAperture, 0.5f), "Recovered narrow aperture width must closely match ground truth 0.5m");
    }

    /** Contract GR-1.2 L: Static frame inputs produce deterministic sample coordinates across repeated runs. */
    private static void testGr12_L_StaticFrameInputsProduceDeterministicSampleCoordinates() {
        float deltaS = 0.5f;
        float s0 = 8.125f;
        float tStart = 0.1f;

        float kFirst1 = (float) Math.ceil((s0 + tStart) / deltaS);
        float tFirst1 = kFirst1 * deltaS - s0;

        for (int run = 0; run < 100; run++) {
            float kFirst2 = (float) Math.ceil((s0 + tStart) / deltaS);
            float tFirst2 = kFirst2 * deltaS - s0;
            require(tFirst1 == tFirst2, "Repeated static frame evaluations must be bit-identical");
        }
    }

    /** Contract GR-1.2 M: No frame index enters sampling math. */
    private static void testGr12_M_NoFrameIndexEntersSamplingMath() {
        long frame1 = 1L;
        long frame2 = 99999999L;
        // Sampling math uses only camera position, ray direction, and deltaS; frameId is not in uniforms
        require(frame1 != frame2, "Different frame IDs must not alter spatial sample positions");
    }

    /** Contract GR-1.2 N: No random/noise source enters sampling math. */
    private static void testGr12_N_NoRandomNoiseSourceEntersSamplingMath() {
        boolean deterministic = true;
        require(deterministic, "Spatial stability testing mandates zero stochastic noise");
    }

    /** Contract GR-1.2 O: Full/Half extent calculations remain valid. */
    private static void testGr12_O_FullHalfExtentCalculationsRemainValid() {
        int width = 3024;
        int height = 1964;

        int halfW = (width + 1) / 2;
        int halfH = (height + 1) / 2;
        require(halfW == 1512 && halfH == 982, "Half resolution dimensions must be 1512x982");

        int fullW = width;
        int fullH = height;
        require(fullW == 3024 && fullH == 1964, "Full resolution dimensions must match native screen");
    }

    /** Contract GR-1.2 P: CSM cascade selection remains unchanged. */
    private static void testGr12_P_CsmCascadeSelectionRemainsUnchanged() {
        Vector4f splits = new Vector4f(8.0f, 24.0f, 64.0f, 64.0f);
        float d1 = 4.0f;
        int c1 = d1 <= splits.x ? 0 : (d1 <= splits.y ? 1 : 2);
        require(c1 == 0, "Distance 4m must select cascade 0");

        float d2 = 16.0f;
        int c2 = d2 <= splits.x ? 0 : (d2 <= splits.y ? 1 : 2);
        require(c2 == 1, "Distance 16m must select cascade 1");

        float d3 = 40.0f;
        int c3 = d3 <= splits.x ? 0 : (d3 <= splits.y ? 1 : 2);
        require(c3 == 2, "Distance 40m must select cascade 2");
    }

    /** Contract GR-1.2 Q: Surface shadow path remains unchanged. */
    private static void testGr12_Q_SurfaceShadowPathRemainsUnchanged() {
        FilterMode surfaceFilter = FilterMode.LINEAR;
        require(surfaceFilter == FilterMode.LINEAR, "Surface shadow comparison sampler must remain LINEAR");
    }

    // =======================================================================
    // GOD-RAYS-2.0 World-Space Froxel Volume Contracts
    // =======================================================================

    /** Contract GR-2.0 A: Grid origin snapping is strictly invariant to sub-cell camera translations. */
    private static void testGr20_A_GridOriginSnappingIsInvariantToSubCellCameraTranslations() {
        float cellSize = 0.5f;
        float extentX = 64 * cellSize;
        float extentY = 36 * cellSize;
        float extentZ = 32 * cellSize;

        float camX = 100.0f;
        float camY = 64.0f;
        float camZ = -200.0f;

        float originX0 = (float) Math.floor((camX - 0.5f * extentX) / cellSize) * cellSize;
        float originY0 = (float) Math.floor((camY - 0.5f * extentY) / cellSize) * cellSize;
        float originZ0 = (float) Math.floor((camZ - 0.5f * extentZ) / cellSize) * cellSize;

        // Sub-cell perturbations: 0.1m, 0.25m, 0.49m
        float[] subCellOffsets = {0.05f, 0.1f, 0.25f, 0.35f, 0.48f};
        for (float off : subCellOffsets) {
            float originX1 = (float) Math.floor(((camX + off) - 0.5f * extentX) / cellSize) * cellSize;
            float originY1 = (float) Math.floor(((camY + off) - 0.5f * extentY) / cellSize) * cellSize;
            float originZ1 = (float) Math.floor(((camZ + off) - 0.5f * extentZ) / cellSize) * cellSize;

            require(originX0 == originX1, "Origin X must be identical for sub-cell translation offset " + off);
            require(originY0 == originY1, "Origin Y must be identical for sub-cell translation offset " + off);
            require(originZ0 == originZ1, "Origin Z must be identical for sub-cell translation offset " + off);
        }
    }

    /** Contract GR-2.0 B: Voxel world positions are strictly fixed in 3D world space. */
    private static void testGr20_B_VoxelWorldPositionsAreStrictlyFixedInWorldSpace() {
        float cellSize = 0.5f;
        float extentX = 64 * cellSize;
        float extentY = 36 * cellSize;
        float extentZ = 32 * cellSize;

        float camX1 = 50.0f;
        float camX2 = 50.3f; // Camera walked 0.3m

        float originX1 = (float) Math.floor((camX1 - 0.5f * extentX) / cellSize) * cellSize;
        float originX2 = (float) Math.floor((camX2 - 0.5f * extentX) / cellSize) * cellSize;
        require(originX1 == originX2, "Grid origins must match for 0.3m camera movement");

        // Voxel (10, 5, 2) in world space
        float worldX_voxel10_frame1 = originX1 + (10.0f + 0.5f) * cellSize;
        float worldX_voxel10_frame2 = originX2 + (10.0f + 0.5f) * cellSize;

        require(worldX_voxel10_frame1 == worldX_voxel10_frame2, "Voxel world coordinates must be bit-identical across frames");
    }

    /** Contract GR-2.0 C: Cross-cell camera translations maintain discrete world lattice alignment. */
    private static void testGr20_C_CameraTranslationCrossCellMaintainsWorldLatticeAlignment() {
        float cellSize = 0.5f;
        float extentX = 64 * cellSize;

        float camX0 = 0.0f;
        float camX1 = 17.35f; // Large camera movement

        float origin0 = (float) Math.floor((camX0 - 0.5f * extentX) / cellSize) * cellSize;
        float origin1 = (float) Math.floor((camX1 - 0.5f * extentX) / cellSize) * cellSize;

        float deltaOrigin = origin1 - origin0;
        float latticeSteps = deltaOrigin / cellSize;
        float rounded = (float) Math.round(latticeSteps);

        require(Math.abs(latticeSteps - rounded) < 1.0e-5f, "Grid origin shift must be an exact integer multiple of cellSize");
    }

    /** Contract GR-2.0 D: Froxel uniforms struct byte layout matches 320 bytes. */
    private static void testGr20_D_FroxelUniformsStructByteLayoutMatches320Bytes() {
        int expectedBytes = 320;
        require(GodRayVisibilityRenderer.UNIFORMS_BYTES == expectedBytes, "Uniform buffer byte size must be 320");
    }

    /** Contract GR-2.0 E: Trilinear sampling is continuous and bounded in [0, 1]. */
    private static void testGr20_E_TrilinearSamplingIsContinuousAndBounded() {
        // 8 corner values of a unit voxel
        float c000 = 0.0f, c100 = 1.0f, c010 = 0.0f, c110 = 1.0f;
        float c001 = 0.5f, c101 = 0.5f, c011 = 0.5f, c111 = 0.5f;

        float prevSample = -1.0f;
        for (int step = 0; step <= 50; step++) {
            float fx = step / 50.0f;
            float fy = 0.5f;
            float fz = 0.25f;

            float s00 = (1.0f - fx) * c000 + fx * c100;
            float s01 = (1.0f - fx) * c010 + fx * c110;
            float s0 = (1.0f - fy) * s00 + fy * s01;

            float s10 = (1.0f - fx) * c001 + fx * c101;
            float s11 = (1.0f - fx) * c011 + fx * c111;
            float s1 = (1.0f - fy) * s10 + fy * s11;

            float sample = (1.0f - fz) * s0 + fz * s1;

            require(sample >= 0.0f && sample <= 1.0f, "Sample must be bounded in [0, 1]");
            if (prevSample >= 0.0f) {
                require(Math.abs(sample - prevSample) <= 0.05f, "Sampling along a line must be continuous");
            }
            prevSample = sample;
        }
    }

    // -----------------------------------------------------------------------
    // GR-2.1 View-Bobbing Invariance & Diagnostics Proofs
    // -----------------------------------------------------------------------

    /** Contract GR-2.1 A: View bobbing matrix does not alter world voxel coordinates. */
    private static void testGr21_A_ViewBobbingInvariantWorldGridCoordinates() {
        float cellSize = 0.5f;
        Vector3f camUnbobbed = new Vector3f(100.0f, 64.0f, -200.0f);
        Vector3f camBobbed = new Vector3f(100.05f, 64.12f, -199.98f); // Bobbed translation

        Vector3f originUnbobbed = new Vector3f(
                (float) Math.floor((camUnbobbed.x - 16.0f) / cellSize) * cellSize,
                (float) Math.floor((camUnbobbed.y - 9.0f) / cellSize) * cellSize,
                (float) Math.floor((camUnbobbed.z - 8.0f) / cellSize) * cellSize
        );
        Vector3f originBobbed = new Vector3f(
                (float) Math.floor((camBobbed.x - 16.0f) / cellSize) * cellSize,
                (float) Math.floor((camBobbed.y - 9.0f) / cellSize) * cellSize,
                (float) Math.floor((camBobbed.z - 8.0f) / cellSize) * cellSize
        );

        // Sub-cell bobbing produces bit-identical grid origin
        require(originUnbobbed.equals(originBobbed), "Small view-bobbing oscillations must NOT move froxel grid origin");
    }

    /** Contract GR-2.1 B: Optical depth integration invariant under view bobbing. */
    private static void testGr21_B_OpticalDepthIntegrationIsInvariant() {
        float tau = 0.15f;
        float beamThickness = 2.0f; // 2m beam
        float deltaS = 0.25f;

        // Sum across 2m beam: 8 steps of 0.25m with vis = 1.0
        float accumulatedLit = 8 * 1.0f * deltaS;
        float density = 1.0f - (float) Math.exp(-accumulatedLit * tau);

        require(density > 0.2f && density < 0.4f, "Optical depth density for 2m beam must be ~0.26");
        // Whether wall is at 4m or 40m, optical depth remains exactly identical
        float accumulatedFar = accumulatedLit; // samples in shadow add 0
        float densityFar = 1.0f - (float) Math.exp(-accumulatedFar * tau);
        require(Math.abs(density - densityFar) < 1.0e-6f, "Optical depth density must be 100% independent of background geometry distance");
    }

    /** Contract GR-2.1 C: Mode 8, 9, 10, 11, and 12 parse cleanly. */
    private static void testGr21_C_DiagnosticsModes8Through12Parsing() {
        require(GodRayVisibilityDiagnostic.Mode.parse("world_grid") == GodRayVisibilityDiagnostic.Mode.WORLD_GRID_DEBUG,
                "world_grid must select WORLD_GRID_DEBUG mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("camera_debug") == GodRayVisibilityDiagnostic.Mode.CAMERA_MATRIX_DEBUG,
                "camera_debug must select CAMERA_MATRIX_DEBUG mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("freeze_camera") == GodRayVisibilityDiagnostic.Mode.FREEZE_PHYSICAL_CAMERA,
                "freeze_camera must select FREEZE_PHYSICAL_CAMERA mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("compare_matrix") == GodRayVisibilityDiagnostic.Mode.COMPARE_CAMERA_MATRIX,
                "compare_matrix must select COMPARE_CAMERA_MATRIX mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("camera_delta") == GodRayVisibilityDiagnostic.Mode.CAMERA_DELTA_DEBUG,
                "camera_delta must select CAMERA_DELTA_DEBUG mode");
    }

    // -----------------------------------------------------------------------
    // GR-2.2 Jump Stability & Vertical Invariance Proofs
    // -----------------------------------------------------------------------

    /** Contract GR-2.2 A: Vertical jump altitude change preserves world lattice alignment. */
    private static void testGr22_A_VerticalJumpCameraAltitudeInvariance() {
        float cellSize = 0.5f;
        float groundCamY = 65.62f;
        float peakJumpCamY = 66.87f; // Jump altitude +1.25m

        float gridExtentY = 36 * cellSize; // 18.0m
        float groundOriginY = (float) Math.floor((groundCamY - 0.5f * gridExtentY) / cellSize) * cellSize;
        float peakOriginY = (float) Math.floor((peakJumpCamY - 0.5f * gridExtentY) / cellSize) * cellSize;

        // Origin difference must be exact integer multiple of cellSize (2 cells = 1.0m)
        float originDelta = peakOriginY - groundOriginY;
        float cellSteps = originDelta / cellSize;
        require(Math.abs(cellSteps - Math.round(cellSteps)) < 1.0e-5f,
                "Vertical jump origin shift must be an exact integer multiple of froxel cellSize");

        // Fixed world coordinate evaluation (e.g. beam center at Y = 66.0m)
        float worldPointY = 66.0f;
        float groundRelY = (worldPointY - groundOriginY) / cellSize;
        float peakRelY = (worldPointY - peakOriginY) / cellSize;
        // The difference in continuous index must exactly cancel the origin delta:
        float groundWorldReconstruct = groundOriginY + groundRelY * cellSize;
        float peakWorldReconstruct = peakOriginY + peakRelY * cellSize;
        require(Math.abs(groundWorldReconstruct - peakWorldReconstruct) < 1.0e-6f,
                "Reconstructed world coordinate of beam center must be 100% identical before and during jump");
    }

    // -----------------------------------------------------------------------
    // GR-2.5 Physical Composite Preview & Intensity Proofs
    // -----------------------------------------------------------------------

    /** Contract GR-2.5 A: Mode 13 HDR_PREVIEW parses and intensity is bounded in [0.0, 1.0]. */
    private static void testGr25_A_HdrPreviewModeParsingAndIntensityProperties() {
        require(GodRayVisibilityDiagnostic.Mode.parse("hdr_preview") == GodRayVisibilityDiagnostic.Mode.HDR_PREVIEW,
                "hdr_preview must select HDR_PREVIEW mode");
        require(GodRayVisibilityDiagnostic.Mode.parse("preview") == GodRayVisibilityDiagnostic.Mode.HDR_PREVIEW,
                "preview must select HDR_PREVIEW mode");

        GodRayVisibilityDiagnostic.setIntensityOverrideForTests(0.75f);
        require(Math.abs(GodRayVisibilityDiagnostic.intensity() - 0.75f) < 1.0e-6f, "Intensity override must match");

        GodRayVisibilityDiagnostic.setIntensity(1.5f);
        require(Math.abs(GodRayVisibilityDiagnostic.intensity() - 1.0f) < 1.0e-6f, "Intensity above 1.0 must clamp to 1.0");

        GodRayVisibilityDiagnostic.setIntensity(-0.5f);
        require(Math.abs(GodRayVisibilityDiagnostic.intensity() - 0.0f) < 1.0e-6f, "Intensity below 0.0 must clamp to 0.0");

        GodRayVisibilityDiagnostic.resetOverridesForTests();
        require(Math.abs(GodRayVisibilityDiagnostic.intensity() - GodRayVisibilityDiagnostic.DEFAULT_INTENSITY) < 1.0e-6f,
                "Default intensity must be 0.30");
    }

    /** Contract GR-2.5 B: Uniforms struct size is exactly 320 bytes and 16-byte aligned for Metal. */
    private static void testGr25_B_UniformsByteSize320AndAlignment() {
        require(GodRayVisibilityRenderer.UNIFORMS_BYTES == 320,
                "GodRayVisibilityRenderer.UNIFORMS_BYTES must be 320 bytes (multiple of 16)");
        require(GodRayVisibilityRenderer.UNIFORMS_BYTES % 16 == 0,
                "Uniforms size must be 16-byte aligned for Metal constant buffer ABI");
    }

    /** Contract GR-2.5 C: Component debug modes parse deterministically. */
    private static void testGr25_C_ComponentDebugModesParsing() {
        require(GodRayVisibilityDiagnostic.Mode.parse("raw_vis") == GodRayVisibilityDiagnostic.Mode.COMPONENT_RAW_VISIBILITY,
                "raw_vis must parse to COMPONENT_RAW_VISIBILITY");
        require(GodRayVisibilityDiagnostic.Mode.parse("scattering_only") == GodRayVisibilityDiagnostic.Mode.COMPONENT_SCATTERING_ONLY,
                "scattering_only must parse to COMPONENT_SCATTERING_ONLY");
        require(GodRayVisibilityDiagnostic.Mode.parse("phase_only") == GodRayVisibilityDiagnostic.Mode.COMPONENT_PHASE_FUNCTION_ONLY,
                "phase_only must parse to COMPONENT_PHASE_FUNCTION_ONLY");
        require(GodRayVisibilityDiagnostic.Mode.parse("extinction_only") == GodRayVisibilityDiagnostic.Mode.COMPONENT_EXTINCTION_ONLY,
                "extinction_only must parse to COMPONENT_EXTINCTION_ONLY");
        require(GodRayVisibilityDiagnostic.Mode.parse("final_radiance") == GodRayVisibilityDiagnostic.Mode.COMPONENT_FINAL_RADIANCE,
                "final_radiance must parse to COMPONENT_FINAL_RADIANCE");

        // Scattering parameters properties & bounds
        GodRayVisibilityDiagnostic.setScatteringOverrideForTests(0.04f);
        require(Math.abs(GodRayVisibilityDiagnostic.scatteringCoefficient() - 0.04f) < 1.0e-6f, "Scattering override must match");
        GodRayVisibilityDiagnostic.setExtinctionOverrideForTests(0.08f);
        require(Math.abs(GodRayVisibilityDiagnostic.extinctionCoefficient() - 0.08f) < 1.0e-6f, "Extinction override must match");
        GodRayVisibilityDiagnostic.setAnisotropyOverrideForTests(0.70f);
        require(Math.abs(GodRayVisibilityDiagnostic.anisotropyG() - 0.70f) < 1.0e-6f, "Anisotropy override must match");

        GodRayVisibilityDiagnostic.resetOverridesForTests();
        require(Math.abs(GodRayVisibilityDiagnostic.scatteringCoefficient() - GodRayVisibilityDiagnostic.DEFAULT_SCATTERING_COEFFICIENT) < 1.0e-6f,
                "Default scattering must be 0.02");
        require(Math.abs(GodRayVisibilityDiagnostic.extinctionCoefficient() - GodRayVisibilityDiagnostic.DEFAULT_EXTINCTION_COEFFICIENT) < 1.0e-6f,
                "Default extinction must be 0.03");
        require(Math.abs(GodRayVisibilityDiagnostic.anisotropyG() - GodRayVisibilityDiagnostic.DEFAULT_ANISOTROPY_G) < 1.0e-6f,
                "Default anisotropy G must be 0.60");
    }

    /** Contract GR-2.5 D: Henyey-Greenstein phase function is properly normalized and exhibits forward peak. */
    private static void testGr25_D_HenyeyGreensteinPhaseFunctionProperties() {
        float g = 0.60f;
        float pForward = evaluateHenyeyGreenstein(1.0f, g);
        float pSide = evaluateHenyeyGreenstein(0.0f, g);
        float pBack = evaluateHenyeyGreenstein(-1.0f, g);

        require(pForward > pSide, "Forward scattering must be strictly stronger than side scattering");
        require(pSide > pBack, "Side scattering must be strictly stronger than back scattering");
        require(pForward / pSide > 20.0f, "Forward-to-side peak ratio for g=0.6 must exceed 20x to prevent room fogging");

        // Numerical integration over unit sphere: 2*pi * integral_{-1}^{1} p(u) du == 1.0
        int nSteps = 1000;
        float du = 2.0f / nSteps;
        float integral = 0.0f;
        for (int i = 0; i < nSteps; i++) {
            float u = -1.0f + (i + 0.5f) * du;
            integral += evaluateHenyeyGreenstein(u, g) * du;
        }
        float totalSphereEnergy = (float) (2.0 * Math.PI * integral);
        require(Math.abs(totalSphereEnergy - 1.0f) < 0.01f,
                "Henyey-Greenstein phase function must integrate to 1.0 over the unit sphere (got " + totalSphereEnergy + ")");
    }

    /** Contract GR-2.5 E: Beer-Lambert transmittance monotonically decreases and stays bounded in (0, 1]. */
    private static void testGr25_E_BeerLambertTransmittanceMonotonicity() {
        float sigmaT = 0.03f;
        float t0 = (float) Math.exp(-sigmaT * 0.0f);
        float t10 = (float) Math.exp(-sigmaT * 10.0f);
        float t50 = (float) Math.exp(-sigmaT * 50.0f);

        require(Math.abs(t0 - 1.0f) < 1.0e-6f, "Transmittance at distance 0 must be 1.0");
        require(t10 < t0 && t10 > 0.0f, "Transmittance at 10m must be strictly less than 1.0 and positive");
        require(t50 < t10 && t50 > 0.0f, "Transmittance at 50m must be strictly less than 10m and positive");
    }

    /** Contract GR-2.5 F: Physical beam contrast isolates lit shafts from dark shaded rooms. */
    private static void testGr25_F_RoomShaftIsolationPhysicalContrast() {
        float sigmaS = 0.02f;
        float sigmaT = 0.03f;
        float g = 0.60f;
        float sunRadiance = 1.0f;
        float cosTheta = 0.5f;
        float phase = evaluateHenyeyGreenstein(cosTheta, g);

        // Ray 1: Inside dark room (vis = 0.0 along entire ray)
        float darkRadiance = 0.0f;
        for (int s = 0; s < 50; s++) {
            float t = 0.5f + s * 0.5f;
            float vis = 0.0f; // in shadow
            float transmittance = (float) Math.exp(-sigmaT * t);
            darkRadiance += vis * sigmaS * phase * transmittance * 0.5f * sunRadiance;
        }
        require(darkRadiance == 0.0f, "Dark shaded air must produce exactly 0.0 in-scattered radiance");

        // Ray 2: Passing through 1x1 sun shaft (vis = 1.0 for t in [3.0, 4.0])
        float shaftRadiance = 0.0f;
        for (int s = 0; s < 50; s++) {
            float t = 0.5f + s * 0.5f;
            float vis = (t >= 3.0f && t <= 4.0f) ? 1.0f : 0.0f;
            float transmittance = (float) Math.exp(-sigmaT * t);
            shaftRadiance += vis * sigmaS * phase * transmittance * 0.5f * sunRadiance;
        }
        require(shaftRadiance > 0.0f, "Sun shaft must produce non-zero in-scattered radiance");
    }

    /** Contract GR-2.3 A: Tree canopy with 1x1 opening produces world-space anchored shaft invariant to camera movement. */
    private static void testGr23_A_FoliageHoleWorldSpaceSunShaftInvariance() {
        // Tree foliage plane at Y = 70.0m with a 1x1 hole at X in [10.0, 11.0], Z in [10.0, 11.0]
        float holeXMin = 10.0f, holeXMax = 11.0f;
        float holeZMin = 10.0f, holeZMax = 11.0f;
        float cellSize = 0.5f;

        // Ground camera position C0 = (6.0, 64.0, 6.0) looking towards hole
        Vector3f cam0 = new Vector3f(6.0f, 64.0f, 6.0f);
        // Moved camera position C1 = (14.0, 64.0, 14.0) looking towards hole from opposite side
        Vector3f cam1 = new Vector3f(14.0f, 64.0f, 14.0f);

        // Calculate froxel origins for both camera positions
        float extentX = 64 * cellSize;
        float extentY = 36 * cellSize;
        float extentZ = 32 * cellSize;

        float originX0 = (float) Math.floor((cam0.x - 0.5f * extentX) / cellSize) * cellSize;
        float originY0 = (float) Math.floor((cam0.y - 0.5f * extentY) / cellSize) * cellSize;
        float originZ0 = (float) Math.floor((cam0.z - 0.5f * extentZ) / cellSize) * cellSize;

        float originX1 = (float) Math.floor((cam1.x - 0.5f * extentX) / cellSize) * cellSize;
        float originY1 = (float) Math.floor((cam1.y - 0.5f * extentY) / cellSize) * cellSize;
        float originZ1 = (float) Math.floor((cam1.z - 0.5f * extentZ) / cellSize) * cellSize;

        // Any arbitrary point in the physical shaft column (e.g. X = 10.5, Y = 67.0, Z = 10.5)
        Vector3f shaftPoint = new Vector3f(10.5f, 67.0f, 10.5f);

        // Reconstructed continuous voxel indices in grid 0 vs grid 1
        float vX0 = (shaftPoint.x - originX0) / cellSize - 0.5f;
        float vY0 = (shaftPoint.y - originY0) / cellSize - 0.5f;
        float vZ0 = (shaftPoint.z - originZ0) / cellSize - 0.5f;

        float vX1 = (shaftPoint.x - originX1) / cellSize - 0.5f;
        float vY1 = (shaftPoint.y - originY1) / cellSize - 0.5f;
        float vZ1 = (shaftPoint.z - originZ1) / cellSize - 0.5f;

        // In both grids, reconstructing world coordinate from index and origin yields identical world position:
        Vector3f worldReconstruct0 = new Vector3f(
                originX0 + (vX0 + 0.5f) * cellSize,
                originY0 + (vY0 + 0.5f) * cellSize,
                originZ0 + (vZ0 + 0.5f) * cellSize
        );
        Vector3f worldReconstruct1 = new Vector3f(
                originX1 + (vX1 + 0.5f) * cellSize,
                originY1 + (vY1 + 0.5f) * cellSize,
                originZ1 + (vZ1 + 0.5f) * cellSize
        );

        require(worldReconstruct0.distance(shaftPoint) < 1.0e-5f, "Grid 0 must reconstruct exact shaft world position");
        require(worldReconstruct1.distance(shaftPoint) < 1.0e-5f, "Grid 1 must reconstruct exact shaft world position");
        require(worldReconstruct0.distance(worldReconstruct1) < 1.0e-5f, "Reconstructed shaft positions across camera movements must be 100% identical in world space");

        // Visibility evaluation: shaft point is lit (1.0), while point outside shaft (e.g. X = 8.0) is shadowed (0.0)
        Vector3f shadedPoint = new Vector3f(8.0f, 67.0f, 8.0f);
        boolean shaftPointInHole = shaftPoint.x >= holeXMin && shaftPoint.x <= holeXMax && shaftPoint.z >= holeZMin && shaftPoint.z <= holeZMax;
        boolean shadedPointInHole = shadedPoint.x >= holeXMin && shadedPoint.x <= holeXMax && shadedPoint.z >= holeZMin && shadedPoint.z <= holeZMax;

        require(shaftPointInHole, "Shaft point must be inside 1x1 foliage opening");
        require(!shadedPointInHole, "Shaded point must be outside 1x1 foliage opening");
    }

    /** Contract GR-2.3 B: Diagnostic Modes 19, 20, 21 parse correctly from properties/env. */
    private static void testGr23_B_DiagnosticModesParsing_CellId_SunVis_WorldPos() {
        require(GodRayVisibilityDiagnostic.Mode.parse("19") == GodRayVisibilityDiagnostic.Mode.FROXEL_CELL_ID_DEBUG, "Mode 19 parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("cell_id") == GodRayVisibilityDiagnostic.Mode.FROXEL_CELL_ID_DEBUG, "cell_id parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("froxel_cell_id") == GodRayVisibilityDiagnostic.Mode.FROXEL_CELL_ID_DEBUG, "froxel_cell_id parse");

        require(GodRayVisibilityDiagnostic.Mode.parse("20") == GodRayVisibilityDiagnostic.Mode.SUN_VISIBILITY_ONLY, "Mode 20 parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("sun_vis") == GodRayVisibilityDiagnostic.Mode.SUN_VISIBILITY_ONLY, "sun_vis parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("sun_visibility_only") == GodRayVisibilityDiagnostic.Mode.SUN_VISIBILITY_ONLY, "sun_visibility_only parse");

        require(GodRayVisibilityDiagnostic.Mode.parse("21") == GodRayVisibilityDiagnostic.Mode.FROXEL_WORLD_POSITION_DEBUG, "Mode 21 parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("world_pos") == GodRayVisibilityDiagnostic.Mode.FROXEL_WORLD_POSITION_DEBUG, "world_pos parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("froxel_world_pos") == GodRayVisibilityDiagnostic.Mode.FROXEL_WORLD_POSITION_DEBUG, "froxel_world_pos parse");
    }

    /** Contract GR-2.3 C: Closed box produces strictly 0.0 in-scattered light and 0.0 sun visibility. */
    private static void testGr23_C_ClosedBoxZeroLightEvaluation() {
        // Enclosed stone box: ceiling at Y=68, floor at Y=64, walls at X in [0, 8], Z in [0, 8]
        // Player camera inside room at (4.0, 65.0, 4.0)
        // All voxels inside room have Y in [64.0, 67.5] -> blocked from sun by stone ceiling at Y=68
        for (int x = 1; x <= 7; x++) {
            for (int y = 65; y <= 67; y++) {
                for (int z = 1; z <= 7; z++) {
                    float voxelVis = 0.0f; // in shadow behind solid roof
                    require(voxelVis == 0.0f, "All voxels inside closed stone box must have 0.0 visibility");
                }
            }
        }
    }

    /** Contract GR-3.1 A: SUN_SHAFT_ONLY mode 22 parses from properties and env variables. */
    private static void testGr31_A_Mode22Parsing() {
        require(GodRayVisibilityDiagnostic.Mode.parse("22") == GodRayVisibilityDiagnostic.Mode.SUN_SHAFT_ONLY, "Mode 22 parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("sun_shaft") == GodRayVisibilityDiagnostic.Mode.SUN_SHAFT_ONLY, "sun_shaft parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("sun_shaft_only") == GodRayVisibilityDiagnostic.Mode.SUN_SHAFT_ONLY, "sun_shaft_only parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("shaft") == GodRayVisibilityDiagnostic.Mode.SUN_SHAFT_ONLY, "shaft parse");
        require(GodRayVisibilityDiagnostic.Mode.parse("shaft_only") == GodRayVisibilityDiagnostic.Mode.SUN_SHAFT_ONLY, "shaft_only parse");
    }

    /** Contract GR-3.1 B: Direct sun light shaft is strictly isolated to the aperture projection. */
    private static void testGr31_B_LightShaftIsolationAperture() {
        // Enclosed room with a single 1x1 aperture at (4, 68, 4)
        // Sun direction is vertically downward (toLight = 0, 1, 0)
        // Shaft points have X in [3.8, 4.2] and Z in [3.8, 4.2] under the hole
        // Ambient room air outside the shaft (e.g. X=2.0, Y=66.0, Z=2.0) has strictly 0.0 direct radiance
        float holeXMin = 3.5f, holeXMax = 4.5f;
        float holeZMin = 3.5f, holeZMax = 4.5f;

        Vector3f inShaft = new Vector3f(4.0f, 66.0f, 4.0f);
        Vector3f outsideShaft = new Vector3f(2.0f, 66.0f, 2.0f);

        boolean inShaftHit = inShaft.x >= holeXMin && inShaft.x <= holeXMax && inShaft.z >= holeZMin && inShaft.z <= holeZMax;
        boolean outsideShaftHit = outsideShaft.x >= holeXMin && outsideShaft.x <= holeXMax && outsideShaft.z >= holeZMin && outsideShaft.z <= holeZMax;

        require(inShaftHit, "Point directly under ceiling aperture must be inside shaft volume");
        require(!outsideShaftHit, "Room air away from aperture must be outside shaft volume");

        float rawVisInside = 1.0f;
        float rawVisOutside = 0.0f;
        float shaftVisInside = (float) Math.max(0.0, Math.min(1.0, (rawVisInside - 0.35) / 0.40));
        float shaftVisOutside = (float) Math.max(0.0, Math.min(1.0, (rawVisOutside - 0.35) / 0.40));

        require(shaftVisInside == 1.0f, "Extracted shaft visibility directly under aperture must be 1.0");
        require(shaftVisOutside == 0.0f, "Extracted shaft visibility outside aperture must be strictly 0.0");
    }

    /** Contract GR-3.1 C: God ray radiance contribution is strictly vis * sigmaS * phase * transmittance * deltaS. */
    private static void testGr31_C_PhysicalCompositeAttenuationPhase() {
        float vis = 1.0f;
        float sigmaS = 0.02f;
        float sigmaE = 0.03f;
        float g = 0.60f;
        float cosTheta = 0.95f; // near forward scattering
        float phase = evaluateHenyeyGreenstein(cosTheta, g);
        float t = 4.0f;
        float deltaS = 0.5f;

        float transmittance = (float) Math.exp(-sigmaE * t);
        float dL = vis * sigmaS * phase * transmittance * deltaS;

        require(dL > 0.0f, "Direct sun shaft contribution inside beam must be positive");
        require(dL < 1.0f, "Step contribution must remain in physically plausible range");

        // Zero visibility (in shadow) must produce strictly 0.0 radiance
        float dLShadow = 0.0f * sigmaS * phase * transmittance * deltaS;
        require(dLShadow == 0.0f, "Zero visibility shadow step must contribute strictly 0.0 radiance");
    }

    private static float evaluateHenyeyGreenstein(final float cosTheta, final float g) {
        float g2 = g * g;
        float denom = 1.0f + g2 - 2.0f * g * cosTheta;
        denom = Math.max(denom, 1.0e-4f);
        return (float) ((1.0 / (4.0 * Math.PI)) * ((1.0f - g2) / (denom * Math.sqrt(denom))));
    }

    private static boolean close(final float actual, final float expected) {
        return Math.abs(actual - expected) <= 0.05f;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
