package com.metallum.client.gi.receiver;

import com.metallum.client.gi.GiRuntimeStages;
import com.metallum.client.sodium.SodiumG5CarrierMetadata;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Dependency-free CPU contract checks for G5 admission, SH math and composition. */
public final class GiReceiverCpuTests {
    private GiReceiverCpuTests() {
    }

    public static void main(final String[] arguments) throws InterruptedException {
        testRuntimeArms();
        testFailClosedAdmission();
        testCompatibilityGate();
        testDrawSafetyGate();
        testResidentCarrierMetadata();
        testLayout();
        testShReconstruction();
        testTextureMapping();
        testAmbientReplacement();
        System.out.println("G5 receiver CPU tests passed");
    }

    private static void testRuntimeArms() {
        GiRuntimeStages.ReceiverRequest disabled =
                GiRuntimeStages.resolveReceiverRequest(null, null);
        require(!disabled.enabled() && disabled.valid()
                        && disabled.arm() == GiRuntimeStages.ReceiverArm.OFF,
                "G5 disabled request did not remain structurally OFF");

        GiRuntimeStages.ReceiverRequest defaultField =
                GiRuntimeStages.resolveReceiverRequest("1", null);
        require(defaultField.valid()
                        && defaultField.arm() == GiRuntimeStages.ReceiverArm.FIELD
                        && defaultField.requestsTransportResources()
                        && defaultField.requestsTransportPopulation(),
                "G5 enabled request did not default to the FIELD arm");

        GiRuntimeStages.ReceiverRequest control =
                GiRuntimeStages.resolveReceiverRequest("true", "control");
        GiRuntimeStages.ReceiverRequest candidate =
                GiRuntimeStages.resolveReceiverRequest("yes", "candidate");
        GiRuntimeStages.ReceiverRequest field =
                GiRuntimeStages.resolveReceiverRequest("on", "field");
        require(control.arm().nativeId() == GiReceiverLayout.ARM_CONTROL
                        && candidate.arm().nativeId() == GiReceiverLayout.ARM_CANDIDATE
                        && field.arm().nativeId() == GiReceiverLayout.ARM_FIELD,
                "G5 Java/native arm identifiers differ");
        require(control.requestsTransportResources() && !control.requestsTransportPopulation()
                        && candidate.requestsTransportResources()
                        && !candidate.requestsTransportPopulation()
                        && field.requestsTransportPopulation(),
                "G5 arms do not preserve generic-resource versus population separation");
        require(GiRuntimeStages.transportResourcesRequested(false, candidate)
                        && !GiRuntimeStages.transportPopulationRequested(false, candidate)
                        && GiRuntimeStages.transportPopulationRequested(false, field)
                        && GiRuntimeStages.transportPopulationRequested(true, control),
                "G5/G4 cross-stage resource admission differs");

        GiRuntimeStages.ReceiverRequest invalid =
                GiRuntimeStages.resolveReceiverRequest("1", "maybe");
        require(invalid.enabled() && !invalid.valid()
                        && !invalid.requestsTransportResources()
                        && invalid.invalidReason().contains("control, candidate, or field"),
                "Unknown G5 arm did not fail closed");

        GiRuntimeStages.ReceiverRequest overlapping =
                GiRuntimeStages.resolveIsolatedReceiverRequest(
                        "1", "candidate", false, false, true
                );
        require(overlapping.enabled() && !overlapping.valid()
                        && !overlapping.requestsTransportResources()
                        && overlapping.invalidReason().contains("standalone G2/G3/G4"),
                "Standalone G4 population could contaminate a G5 candidate arm");
        require(GiRuntimeStages.resolveIsolatedReceiverRequest(
                        "1", "field", false, false, false
                ).requestsTransportPopulation(),
                "An isolated G5 field arm lost its owned G4 population route");
    }

    private static void testFailClosedAdmission() {
        GiReceiverRuntime.Admission admission = new GiReceiverRuntime.Admission(
                GiRuntimeStages.resolveReceiverRequest("1", "candidate")
        );
        require(admission.state() == GiReceiverRuntime.AdmissionState.WAITING
                        && !admission.carrierSafe(),
                "Unknown G5 carrier was admitted");
        admission.reportNativeReady();
        require(admission.state() == GiReceiverRuntime.AdmissionState.READY
                        && admission.bindingAllowed() && !admission.carrierSafe()
                        && !admission.benchmarkReceiptEmitted()
                        && !admission.terrainDrawEncoded(),
                "G5 native readiness bypassed the carrier gate");
        admission.beginCarrierWriteCensus(41L);
        require(admission.successfulCarrierWrites(41L) == 0L
                        && admission.successfulCarrierWrites(47L) == 6L,
                "G5 successful carrier census did not remain relative to device admission");
        admission.reportCarrierSafe();
        require(admission.carrierSafe(), "A verified G5 carrier was not admitted");
        admission.reportBenchmarkReceiptEmitted();
        admission.reportTerrainDrawEncoded(0L);
        require(!admission.terrainDrawEncoded(),
                "a carrier-free terrain batch qualified the G5 receipt");
        admission.reportTerrainDrawEncoded(3L);
        require(admission.benchmarkReceiptEmitted(),
                "G5 verified warmup binding receipt was not retained");
        require(admission.terrainDrawEncoded()
                        && admission.drawnG5CarrierSlices() == 3L,
                "G5 actual terrain draw receipt was not retained");
        GiReceiverRuntime.FinalSnapshot snapshot = admission.finalSnapshot(47L);
        require(snapshot.state() == GiReceiverRuntime.AdmissionState.READY
                        && snapshot.successfulCarrierWrites() == 6L
                        && snapshot.drawnG5CarrierSlices() == 3L
                        && snapshot.terrainDrawEncoded(),
                "G5 terminal census is not one immutable synchronized snapshot");
        admission.reportCarrierSkip("pre-owned compact position bits on one quad");
        require(admission.state() == GiReceiverRuntime.AdmissionState.INVALID
                        && admission.carrierState() == GiReceiverRuntime.CarrierState.CONFLICT
                        && !admission.carrierSafe()
                        && admission.carrierSkipCount() == 1L
                        && admission.lastCarrierSkipReason().contains("one quad")
                        && admission.invalidReason().contains("one quad"),
                "A per-quad carrier collision did not reject the G5 renderer generation");
        admission.reportCarrierConflict("foreign compact position layout");
        admission.reportCarrierSafe();
        admission.reportNativeReady();
        require(admission.state() == GiReceiverRuntime.AdmissionState.INVALID
                        && admission.carrierState() == GiReceiverRuntime.CarrierState.CONFLICT
                        && !admission.carrierSafe()
                        && admission.carrierSkipCount() == 1L
                        && admission.invalidReason().contains("one quad"),
                "G5 carrier conflict healed after terminal rejection");
    }

    private static void testResidentCarrierMetadata() {
        int packed = SodiumG5CarrierMetadata.replaceInfoFaceMask(
                0,
                net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.SOLID,
                1 << 2
        );
        packed = SodiumG5CarrierMetadata.replaceInfoFaceMask(
                packed,
                net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT,
                1 << 6
        );
        int infoFlags = SodiumG5CarrierMetadata.mergeInfoFlags(0x5, packed);
        require((infoFlags & 0xff) == 0x5
                        && SodiumG5CarrierMetadata.infoFaceMask(
                        infoFlags,
                        net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.SOLID
                ) == (1 << 2)
                        && SodiumG5CarrierMetadata.infoFaceMask(
                        infoFlags,
                        net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses.TRANSLUCENT
                ) == (1 << 6),
                "G5 worker face masks do not preserve Sodium's stock low-byte flags");
        int resident = SodiumG5CarrierMetadata.withResidentCarrierFaceMask(
                (1 << 2) | (1 << 6),
                1 << 6
        );
        require(SodiumG5CarrierMetadata.stockResidentFaceMask(resident)
                        == ((1 << 2) | (1 << 6))
                        && SodiumG5CarrierMetadata.residentCarrierFaceMask(resident)
                        == (1 << 6),
                "G5 accepted metadata does not round-trip through the resident slice word");
        expectIllegalArgument(
                () -> SodiumG5CarrierMetadata.withResidentCarrierFaceMask(1 << 2, 1 << 6),
                "a non-resident G5 face slice was admitted"
        );
        expectIllegalArgument(
                () -> SodiumG5CarrierMetadata.withResidentCarrierFaceMask(1 << 8, 0),
                "foreign resident high bits were mistaken for owned G5 metadata"
        );
        int finalPacked = packed;
        expectIllegalArgument(
                () -> SodiumG5CarrierMetadata.mergeInfoFlags(1 << 29, finalPacked),
                "foreign BuiltSectionInfo high bits were overwritten"
        );

    }

    private static void expectIllegalArgument(final Runnable runnable, final String message) {
        try {
            runnable.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void testCompatibilityGate() {
        require(GiReceiverCompatibility.supportsExactVersions(
                        "26.2", "0.9.1+mc26.2", "0.5.4")
                        && !GiReceiverCompatibility.supportsExactVersions(
                        "26.2", "0.9.2+mc26.2", "0.5.4")
                        && !GiReceiverCompatibility.supportsExactVersions(
                        "26.3", "0.9.1+mc26.2", "0.5.4"),
                "G5 compact-position compatibility is not exact-version locked");

        GiReceiverCompatibility.setTestOverride(true);
        CompactPositionCarrierSafety.resetForTests();
        try {
            GiReceiverRuntime.Admission rejected = new GiReceiverRuntime.Admission(
                    GiRuntimeStages.resolveReceiverRequest("1", "candidate")
            );
            require(!GiReceiverRuntime.admitCompactPositionCarrier(rejected, false)
                            && rejected.state() == GiReceiverRuntime.AdmissionState.INVALID
                            && rejected.carrierState() == GiReceiverRuntime.CarrierState.CONFLICT
                            && rejected.invalidReason().contains("Sodium 0.9.1+mc26.2")
                            && !CompactPositionCarrierSafety.isSafe(),
                    "incompatible Sodium layout did not terminally reject shared admission");

            CompactPositionCarrierSafety.resetForTests();
            GiReceiverRuntime.Admission accepted = new GiReceiverRuntime.Admission(
                    GiRuntimeStages.resolveReceiverRequest("1", "candidate")
            );
            require(GiReceiverRuntime.admitCompactPositionCarrier(accepted, true)
                            && accepted.state() == GiReceiverRuntime.AdmissionState.WAITING
                            && accepted.carrierSafe()
                            && CompactPositionCarrierSafety.isSafe(),
                    "exact Sodium layout did not admit the position carrier before native ready");
        } finally {
            CompactPositionCarrierSafety.resetForTests();
            GiReceiverCompatibility.setTestOverride(null);
        }
    }

    private static void testDrawSafetyGate() throws InterruptedException {
        GiReceiverCompatibility.setTestOverride(true);
        CompactPositionCarrierSafety.resetForTests();
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch writerCompleted = new CountDownLatch(1);
        Thread writer = null;
        boolean gateHeld = false;
        try {
            long initialRevision = CompactPositionCarrierSafety.revision();
            CompactPositionCarrierSafety.beginCarrierAwareDraw();
            gateHeld = true;
            writer = Thread.ofPlatform().name("g5-carrier-conflict-test").start(() -> {
                writerStarted.countDown();
                CompactPositionCarrierSafety.reportConflict("concurrent carrier conflict");
                writerCompleted.countDown();
            });
            require(writerStarted.await(5L, TimeUnit.SECONDS),
                    "carrier conflict writer did not start");
            require(!writerCompleted.await(50L, TimeUnit.MILLISECONDS)
                            && CompactPositionCarrierSafety.isSafe()
                            && CompactPositionCarrierSafety.revision() == initialRevision,
                    "carrier conflict became visible inside an in-flight draw boundary");
            CompactPositionCarrierSafety.endCarrierAwareDraw();
            gateHeld = false;
            require(writerCompleted.await(5L, TimeUnit.SECONDS)
                            && !CompactPositionCarrierSafety.isSafe()
                            && CompactPositionCarrierSafety.revision() > initialRevision,
                    "carrier conflict was not published between draw boundaries");
        } finally {
            if (gateHeld) {
                CompactPositionCarrierSafety.endCarrierAwareDraw();
            }
            if (writer != null) {
                writer.join(5_000L);
            }
            CompactPositionCarrierSafety.resetForTests();
            GiReceiverCompatibility.setTestOverride(null);
        }
    }

    private static void testLayout() {
        require(GiReceiverLayout.ABI_VERSION == 1
                        && GiReceiverLayout.LAYOUT_BYTES == 128
                        && GiReceiverLayout.PARAMS_BYTES == 64
                        && GiReceiverLayout.STATS_BYTES == 80,
                "G5 fixed native packet sizes differ");
        require(GiReceiverLayout.LIFETIME_OVERHEAD_BYTES == 65_536L,
                "G5 opaque lifetime census charge differs");
        require(GiReceiverLayout.SH_RED_TEXTURE_SLOT == 6
                        && GiReceiverLayout.SH_GREEN_TEXTURE_SLOT == 7
                        && GiReceiverLayout.SH_BLUE_TEXTURE_SLOT == 8
                        && GiReceiverLayout.CONFIDENCE_TEXTURE_SLOT == 9
                        && GiReceiverLayout.PARAMS_BUFFER_SLOT == 25,
                "G5 fixed vertex binding ABI differs");
        require(GiReceiverLayout.PARAM_FIELD_READY_FLOAT_OFFSET == 28
                        && GiReceiverLayout.PARAM_ARM_OFFSET == 32
                        && GiReceiverLayout.PARAM_CARRIER_SAFE_OFFSET == 36
                        && GiReceiverLayout.PARAM_CELL_SIZE_OFFSET == 40
                        && GiReceiverLayout.PARAM_ABI_VERSION_OFFSET == 44,
                "G5 parameter packet order differs from Swift");
        require(GiReceiverLayout.EDGE == 32
                        && GiReceiverLayout.CELL_SIZE_BLOCKS == 1
                        && GiReceiverLayout.SPAN_BLOCKS == 32,
                "G5 receiver no longer addresses the frozen G4 near cascade");
        GiReceiverBindingAbi.requireLegal();
    }

    private static void testShReconstruction() {
        GiReceiverMath.ShChannel red = new GiReceiverMath.ShChannel(2.0F, 1.0F, 0.0F, 0.0F);
        GiReceiverMath.ShChannel green = new GiReceiverMath.ShChannel(4.0F, 0.0F, 2.0F, 0.0F);
        GiReceiverMath.ShChannel blue = new GiReceiverMath.ShChannel(8.0F, 0.0F, 0.0F, 3.0F);
        GiReceiverMath.Vec3 positiveX = GiReceiverMath.reconstructRgb(red, green, blue, 2);
        GiReceiverMath.Vec3 negativeX = GiReceiverMath.reconstructRgb(red, green, blue, 1);
        require(close(positiveX.x(), 3.0F) && close(negativeX.x(), 1.0F)
                        && close(positiveX.y(), 4.0F) && close(positiveX.z(), 8.0F),
                "G5 scaled-real L1 reconstruction differs from c0+dot(cxyz,n)");
        require(GiReceiverMath.reconstruct(
                        new GiReceiverMath.ShChannel(0.25F, -1.0F, 0.0F, 0.0F),
                        GiReceiverMath.faceNormal(2)) == 0.0F,
                "G5 did not clamp a malformed negative SH reconstruction");
        require(GiReceiverMath.reconstructRgb(red, green, blue, 0)
                        .equals(new GiReceiverMath.Vec3(0.0F, 0.0F, 0.0F)),
                "Missing G5 face carrier produced irradiance");
        require(GiReceiverMath.confidence(Float.NaN) == 0.0F
                        && GiReceiverMath.confidence(-1.0F) == 0.0F
                        && GiReceiverMath.confidence(2.0F) == 1.0F,
                "G5 confidence does not fail closed");
    }

    private static void testTextureMapping() {
        GiReceiverMath.TextureCoordinate origin = GiReceiverMath.worldToTexture(
                -64.0F, 20.0F, 128.0F, -64, 20, 128
        );
        GiReceiverMath.TextureCoordinate last = GiReceiverMath.worldToTexture(
                -32.001F, 51.999F, 159.999F, -64, 20, 128
        );
        GiReceiverMath.TextureCoordinate outside = GiReceiverMath.worldToTexture(
                -32.0F, 52.0F, 160.0F, -64, 20, 128
        );
        require(origin.inside() && origin.u() == 0.0F
                        && last.inside() && last.u() < 1.0F
                        && !outside.inside() && outside.u() == 1.0F,
                "G5 finite-domain world mapping does not fail closed at the upper edge");
    }

    private static void testAmbientReplacement() {
        GiReceiverMath.Vec3 fallback = new GiReceiverMath.Vec3(0.1F, 0.2F, 0.3F);
        GiReceiverMath.Vec3 albedo = new GiReceiverMath.Vec3(0.5F, 0.25F, 1.0F);
        GiReceiverMath.Vec3 incoming = new GiReceiverMath.Vec3(2.0F, 4.0F, 0.5F);
        require(GiReceiverMath.replaceApproximateAmbient(
                        fallback, albedo, incoming, 0.0F) == fallback,
                "G5 confidence zero is not an exact existing fallback");
        GiReceiverMath.Vec3 physical = GiReceiverMath.replaceApproximateAmbient(
                fallback, albedo, incoming, 1.0F
        );
        require(close(physical.x(), fallback.x() + 1.0F * GiReceiverLayout.INVERSE_PI)
                        && close(physical.y(), fallback.y() + 1.0F * GiReceiverLayout.INVERSE_PI)
                        && close(physical.z(), fallback.z() + 0.5F * GiReceiverLayout.INVERSE_PI),
                "G5 receiver did not add the physical correction with albedo/pi exactly once");
        require(GiReceiverMath.replaceApproximateAmbient(
                        fallback, albedo, incoming, 0.25F).equals(physical),
                "G5 incorrectly used known-path confidence as a second energy weight");
        require(GiReceiverMath.replaceApproximateAmbient(
                        fallback, albedo, new GiReceiverMath.Vec3(0.0F, 0.0F, 0.0F), 1.0F)
                        .equals(fallback),
                "Known occlusion darkened the exact ambient fallback");
        GiReceiverMath.Vec3 redOnly = GiReceiverMath.replaceApproximateAmbient(
                fallback,
                new GiReceiverMath.Vec3(1.0F, 1.0F, 1.0F),
                new GiReceiverMath.Vec3(2.0F, 0.0F, 0.0F),
                1.0F
        );
        require(redOnly.x() > fallback.x()
                        && close(redOnly.y(), fallback.y())
                        && close(redOnly.z(), fallback.z()),
                "Red one-bounce transport invented green or blue ambient energy");
        require(close(redOnly.x(), fallback.x() + 2.0F * GiReceiverLayout.INVERSE_PI),
                "Red one-bounce transport was attenuated or gained in the receiver composition");
    }

    private static boolean close(final float actual, final float expected) {
        return Math.abs(actual - expected) <= 1.0e-6F;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
