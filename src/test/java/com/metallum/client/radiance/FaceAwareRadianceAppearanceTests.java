package com.metallum.client.radiance;

/** Dependency-light checks for the source-side TOP/SIDE/BOTTOM reducer. */
public final class FaceAwareRadianceAppearanceTests {
    private FaceAwareRadianceAppearanceTests() {
    }

    public static void main(final String[] args) {
        testSideExposureUsesSideAppearance();
        testWaterRelevantWeightsFavorAVisibleBankSide();
        testMissingRequestedFaceFallsBack();
        System.out.println("FaceAwareRadianceAppearanceTests passed successfully.");
    }

    private static void testSideExposureUsesSideAppearance() {
        FaceAwareRadianceAppearance.FacePalette palette = palette();
        float[] rgb = new float[3];
        boolean resolved = FaceAwareRadianceAppearance.resolvePalette(
                palette, null, null, null, null,
                1 << net.minecraft.core.Direction.EAST.ordinal(), rgb
        );
        require(resolved, "an exposed side with baked samples must resolve");
        require(close(rgb[0], 0.36F) && close(rgb[1], 0.12F) && close(rgb[2], 0.04F),
                "side-only exposure must use the brown side texture, not the green top");
    }

    private static void testWaterRelevantWeightsFavorAVisibleBankSide() {
        FaceAwareRadianceAppearance.FacePalette palette = palette();
        float[] rgb = new float[3];
        int mask = FaceAwareRadianceAppearance.TOP_MASK
                | (1 << net.minecraft.core.Direction.NORTH.ordinal());
        require(FaceAwareRadianceAppearance.resolvePalette(
                        palette, null, null, null, null, mask, rgb
                ), "top+side exposure must resolve");
        require(rgb[0] > rgb[1],
                "a visible bank side must keep earth/bark dominant over a grass-green top");
        require(rgb[1] > 0.12F && rgb[1] < 0.72F,
                "the top face must remain a bounded secondary contribution");
    }

    private static void testMissingRequestedFaceFallsBack() {
        FaceAwareRadianceAppearance.FacePalette topOnly = new FaceAwareRadianceAppearance.FacePalette(
                group(0.08F, 0.72F, 0.05F), null, null
        );
        float[] rgb = new float[]{7.0F, 7.0F, 7.0F};
        require(!FaceAwareRadianceAppearance.resolvePalette(
                        topOnly, null, null, null, null,
                        1 << net.minecraft.core.Direction.WEST.ordinal(), rgb
                ), "missing side samples must select the legacy MapColor fallback");
    }

    private static FaceAwareRadianceAppearance.FacePalette palette() {
        return new FaceAwareRadianceAppearance.FacePalette(
                group(0.08F, 0.72F, 0.05F),
                group(0.36F, 0.12F, 0.04F),
                group(0.12F, 0.05F, 0.02F)
        );
    }

    private static FaceAwareRadianceAppearance.FaceGroup group(
            final float red,
            final float green,
            final float blue
    ) {
        return new FaceAwareRadianceAppearance.FaceGroup(new FaceAwareRadianceAppearance.FaceSample[]{
                new FaceAwareRadianceAppearance.FaceSample(red, green, blue, -1, 1.0F)
        });
    }

    private static boolean close(final float actual, final float expected) {
        return Math.abs(actual - expected) < 1.0e-6F;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
