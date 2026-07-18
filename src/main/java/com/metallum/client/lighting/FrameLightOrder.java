package com.metallum.client.lighting;

import java.util.Comparator;

/** Deterministic relevance and admission orders for one bounded light frame. */
public final class FrameLightOrder {
    static final double MATERIAL_REPLACEMENT_RATIO = 1.20;
    static final double INFLUENCE_DISTANCE_REPLACEMENT_RATIO = 0.85;
    static final double MINIMUM_INFLUENCE_DISTANCE_IMPROVEMENT = 0.50;

    private static final Comparator<AdvancedLight> ADMISSION_ORDER = (left, right) -> {
        int materialOrder = comparePriorityAndSignificance(left, right);
        return materialOrder != 0
                ? materialOrder
                : AdvancedLight.PRIORITY_ORDER.compare(left, right);
    };

    private FrameLightOrder() {
    }

    public static Comparator<AdvancedLight> comparator(
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        requireFinite(cameraX, "cameraX");
        requireFinite(cameraY, "cameraY");
        requireFinite(cameraZ, "cameraZ");
        return (left, right) -> {
            int materialOrder = comparePriorityAndSignificance(left, right);
            if (materialOrder != 0) {
                return materialOrder;
            }
            int distanceOrder = compareInfluenceDistance(
                    left,
                    right,
                    cameraX,
                    cameraY,
                    cameraZ
            );
            if (distanceOrder != 0) {
                return distanceOrder;
            }
            return AdvancedLight.PRIORITY_ORDER.compare(left, right);
        };
    }

    /** Camera-independent order consumed by deterministic native admission and overflow. */
    public static Comparator<AdvancedLight> admissionComparator() {
        return ADMISSION_ORDER;
    }

    /**
     * Returns true only when a challenger is materially better than a retained light.
     * Small camera motion therefore cannot churn equal emitters around a distance bisector.
     */
    public static boolean materiallyOutranks(
            final AdvancedLight challenger,
            final AdvancedLight retained,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        requireFinite(cameraX, "cameraX");
        requireFinite(cameraY, "cameraY");
        requireFinite(cameraZ, "cameraZ");
        int sourceClassOrder = compareShadowSourceClass(challenger, retained);
        if (sourceClassOrder != 0) {
            return sourceClassOrder < 0;
        }
        if (challenger.priority() != retained.priority()) {
            return challenger.priority() > retained.priority();
        }

        double challengerSignificance = significance(challenger);
        double retainedSignificance = significance(retained);
        if (challengerSignificance > retainedSignificance * MATERIAL_REPLACEMENT_RATIO) {
            return true;
        }
        if (retainedSignificance > challengerSignificance * MATERIAL_REPLACEMENT_RATIO) {
            return false;
        }

        double challengerDistance = influenceDistance(
                challenger,
                cameraX,
                cameraY,
                cameraZ
        );
        double retainedDistance = influenceDistance(retained, cameraX, cameraY, cameraZ);
        double requiredImprovement = Math.max(
                MINIMUM_INFLUENCE_DISTANCE_IMPROVEMENT,
                retainedDistance * (1.0 - INFLUENCE_DISTANCE_REPLACEMENT_RATIO)
        );
        return challengerDistance + requiredImprovement < retainedDistance;
    }

    static double significance(final AdvancedLight light) {
        return (double) light.intensity() * light.radius() * light.radius();
    }

    static double influenceDistance(
            final AdvancedLight light,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        return Math.max(
                0.0,
                Math.sqrt(distanceSquared(light, cameraX, cameraY, cameraZ)) - light.radius()
        );
    }

    private static int comparePriorityAndSignificance(
            final AdvancedLight left,
            final AdvancedLight right
    ) {
        int sourceClassOrder = compareShadowSourceClass(left, right);
        if (sourceClassOrder != 0) {
            return sourceClassOrder;
        }
        int priorityOrder = Integer.compare(right.priority(), left.priority());
        return priorityOrder != 0
                ? priorityOrder
                : Double.compare(significance(right), significance(left));
    }

    private static int compareShadowSourceClass(
            final AdvancedLight left,
            final AdvancedLight right
    ) {
        return Integer.compare(
                shadowSourceRank(left.shadowSourceClass()),
                shadowSourceRank(right.shadowSourceClass())
        );
    }

    private static int shadowSourceRank(final LocalShadowSourceClass sourceClass) {
        return sourceClass == LocalShadowSourceClass.CAMERA_HELD ? 0 : 1;
    }

    private static int compareInfluenceDistance(
            final AdvancedLight left,
            final AdvancedLight right,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        if (Float.compare(left.radius(), right.radius()) == 0) {
            return Double.compare(
                    distanceSquared(left, cameraX, cameraY, cameraZ),
                    distanceSquared(right, cameraX, cameraY, cameraZ)
            );
        }
        return Double.compare(
                influenceDistance(left, cameraX, cameraY, cameraZ),
                influenceDistance(right, cameraX, cameraY, cameraZ)
        );
    }

    private static double distanceSquared(
            final AdvancedLight light,
            final double cameraX,
            final double cameraY,
            final double cameraZ
    ) {
        double dx = light.x() - cameraX;
        double dy = light.y() - cameraY;
        double dz = light.z() - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static void requireFinite(final double value, final String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
