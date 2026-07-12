package com.metallum.client.hdr;

/** Dependency-free numerical checks for the FP16 clear-color boundary. */
public final class SceneLinearClearColorTests {
    private static final float EPSILON = 0.000001f;

    private SceneLinearClearColorTests() {
    }

    public static void main(final String[] arguments) {
        run();
    }

    static void run() {
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(0.0f), 0.0f, "zero");
        require(
                Float.floatToRawIntBits(SceneLinearClearColor.extendedSrgbToLinear(-0.0f))
                        == Float.floatToRawIntBits(-0.0f),
                "negative zero sign"
        );
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(0.04045f), 0.003130805f, "linear threshold");
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(-0.04045f), -0.003130805f, "negative threshold");
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(0.5f), 0.21404114f, "sRGB half");
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(-0.5f), -0.21404114f, "negative sRGB half");
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(1.0f), 1.0f, "one");
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(1.5f), 2.5371552f, "extended positive");
        requireClose(SceneLinearClearColor.extendedSrgbToLinear(-1.5f), -2.5371552f, "extended negative");

        SceneLinearClearColor.Rgb rgb = SceneLinearClearColor.extendedSrgbToLinear(0.5f, -0.5f, 1.5f);
        requireClose(rgb.red(), 0.21404114f, "RGB red");
        requireClose(rgb.green(), -0.21404114f, "RGB green");
        requireClose(rgb.blue(), 2.5371552f, "RGB blue");

        SceneLinearPreflightGate.install(new SceneLinearPreflightGate.Evaluation(true, "clear test"));
        require(SceneLinearClearColor.shouldDecode(true, true), "active FP16 scene color decodes");
        require(!SceneLinearClearColor.shouldDecode(false, true), "RGBA8 scene color stays encoded");
        require(!SceneLinearClearColor.shouldDecode(true, false), "FP16 numerical data stays untouched");
        SceneLinearPreflightGate.resetForTests();
    }

    private static void requireClose(final float actual, final float expected, final String description) {
        if (Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void require(final boolean condition, final String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}
