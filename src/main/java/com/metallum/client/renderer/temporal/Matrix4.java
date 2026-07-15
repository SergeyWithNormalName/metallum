package com.metallum.client.renderer.temporal;

import java.util.Arrays;
import java.util.Objects;

/** Small immutable 4x4 numeric value used only by the frame contract model. */
public final class Matrix4 {
    public static final int ELEMENT_COUNT = 16;

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

    public static Matrix4 identity() {
        return of(
                1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0
        );
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
