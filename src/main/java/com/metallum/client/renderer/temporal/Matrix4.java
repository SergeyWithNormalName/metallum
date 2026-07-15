package com.metallum.client.renderer.temporal;

import java.util.Arrays;
import java.util.Objects;

import org.joml.Matrix4fc;

/** Small immutable 4x4 numeric value used only by the frame contract model. */
public final class Matrix4 {
    public static final int ELEMENT_COUNT = 16;
    private static final Matrix4 IDENTITY = of(
            1.0, 0.0, 0.0, 0.0,
            0.0, 1.0, 0.0, 0.0,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
    );

    private final double[] elements;

    private Matrix4(final double[] elements) {
        this.elements = elements;
    }

    public static Matrix4 of(final double... elements) {
        Objects.requireNonNull(elements, "elements");
        if (elements.length != ELEMENT_COUNT) {
            throw new IllegalArgumentException("A 4x4 matrix needs exactly 16 elements");
        }
        double[] copy = elements.clone();
        for (double element : copy) {
            if (!Double.isFinite(element)) {
                throw new IllegalArgumentException("Matrix elements must be finite");
            }
        }
        return new Matrix4(copy);
    }

    public static Matrix4 ofFloats(final float[] elements) {
        Objects.requireNonNull(elements, "elements");
        if (elements.length != ELEMENT_COUNT) {
            throw new IllegalArgumentException("A 4x4 matrix needs exactly 16 elements");
        }
        double[] converted = new double[ELEMENT_COUNT];
        for (int index = 0; index < ELEMENT_COUNT; index++) {
            if (!Float.isFinite(elements[index])) {
                throw new IllegalArgumentException("Matrix elements must be finite");
            }
            converted[index] = elements[index];
        }
        return new Matrix4(converted);
    }

    /** Copies JOML's column-major representation without a temporary float array. */
    public static Matrix4 ofJoml(final Matrix4fc value) {
        Objects.requireNonNull(value, "value");
        return of(
                value.m00(), value.m01(), value.m02(), value.m03(),
                value.m10(), value.m11(), value.m12(), value.m13(),
                value.m20(), value.m21(), value.m22(), value.m23(),
                value.m30(), value.m31(), value.m32(), value.m33()
        );
    }

    public static Matrix4 identity() {
        return IDENTITY;
    }

    public double element(final int index) {
        return this.elements[index];
    }

    public double[] elements() {
        return this.elements.clone();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Matrix4 matrix && Arrays.equals(this.elements, matrix.elements);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.elements);
    }

    @Override
    public String toString() {
        return "Matrix4" + Arrays.toString(this.elements);
    }
}
