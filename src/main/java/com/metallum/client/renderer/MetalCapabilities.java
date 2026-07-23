package com.metallum.client.renderer;

import com.metallum.client.hdr.EdrCapabilities;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable capability snapshot captured once per renderer generation. */
public final class MetalCapabilities {
    public enum Feature {
        METAL3_BASE,
        METAL4_OS_API,
        METAL4_GPU_FAMILY,
        METAL4_CORE,
        METAL4_COMPILER,
        METAL4_COMMAND_LIFECYCLE,
        METAL4_ARGUMENT_TABLES,
        METAL4_EXPLICIT_BARRIERS,
        METALFX_SPATIAL,
        METALFX_TEMPORAL,
        METALFX_FRAME_INTERPOLATION,
        METALFX_TEMPORAL_METAL4,
        METALFX_FRAME_INTERPOLATION_METAL4,
        REQUIRED_TEXTURE_FORMATS_USAGES,
        DISPLAY_REFRESH,
        DISPLAY_HEADROOM,
        HDR_OUTPUT,
        METALLUM_MATERIAL_CONTRACT,
        ADVANCED_LIGHTING
    }

    public enum Evidence {
        DECLARED,
        ENGINE_BASELINE,
        OS_API_AVAILABILITY,
        GPU_FAMILY_QUERY,
        COMBINED_VALIDATION,
        RUNTIME_PROBE,
        API_DERIVED,
        FRAMEWORK_DEVICE_QUERY,
        FRAMEWORK_METAL4_QUERY,
        FORMAT_USAGE_PROBE,
        DISPLAY_QUERY
    }

    public record FormatUsageProfile(
            boolean requiredEngineFormatsAndUsages,
            boolean effectSpecificUsagesValidated
    ) {
        public static final FormatUsageProfile UNAVAILABLE = new FormatUsageProfile(false, false);
    }

    public record TemporalProfile(
            boolean metalFxTemporal,
            boolean reactiveMaskApi,
            boolean depth32Float,
            boolean rg16Float,
            boolean r8Unorm,
            boolean requiredTextureUsages
    ) {
        public static final TemporalProfile UNAVAILABLE = new TemporalProfile(
                false, false, false, false, false, false
        );

        public boolean diagnosticsSupported() {
            return this.metalFxTemporal && this.reactiveMaskApi && this.depth32Float
                    && this.rg16Float && this.r8Unorm && this.requiredTextureUsages;
        }
    }

    /** One-time Apple MetalFX Frame Interpolation admission evidence scaffold. */
    public record FrameInterpolationProfile(
            boolean metalFxFrameInterpolation,
            boolean metal4FxFrameInterpolation,
            boolean depth32Float,
            boolean rg16Float,
            boolean nativeProfileValidated
    ) {
        public static final FrameInterpolationProfile UNAVAILABLE = new FrameInterpolationProfile(
                false, false, false, false, false
        );
    }

    public record DisplayCapabilities(
            int maximumFramesPerSecond,
            float currentHeadroom,
            float potentialHeadroom
    ) {
        public static final DisplayCapabilities UNKNOWN_SDR = new DisplayCapabilities(0, 1.0f, 1.0f);

        public DisplayCapabilities {
            if (maximumFramesPerSecond < 0 || maximumFramesPerSecond > 1_000) {
                throw new IllegalArgumentException("Display refresh must be in [0, 1000]");
            }
            EdrCapabilities sanitized = new EdrCapabilities(currentHeadroom, potentialHeadroom);
            currentHeadroom = sanitized.currentHeadroom();
            potentialHeadroom = sanitized.potentialHeadroom();
        }

        public boolean refreshKnown() {
            return this.maximumFramesPerSecond > 0;
        }

        public boolean hdrAvailable() {
            return this.potentialHeadroom > 1.01f;
        }
    }

    static final long NATIVE_METAL3_BASE = 1L << 0;
    static final long NATIVE_METAL4_OS_API = 1L << 1;
    static final long NATIVE_METAL4_GPU_FAMILY = 1L << 2;
    static final long NATIVE_METAL4_CORE = 1L << 3;
    static final long NATIVE_METAL4_COMPILER = 1L << 4;
    static final long NATIVE_METAL4_COMMAND_LIFECYCLE = 1L << 5;
    static final long NATIVE_METAL4_ARGUMENT_TABLES = 1L << 6;
    static final long NATIVE_METAL4_EXPLICIT_BARRIERS = 1L << 7;
    static final long NATIVE_METALFX_SPATIAL = 1L << 8;
    static final long NATIVE_METALFX_TEMPORAL = 1L << 9;
    static final long NATIVE_METALFX_FRAME_INTERPOLATION = 1L << 10;
    static final long NATIVE_METALFX_TEMPORAL_METAL4 = 1L << 11;
    static final long NATIVE_METALFX_FRAME_INTERPOLATION_METAL4 = 1L << 12;
    static final long NATIVE_REQUIRED_TEXTURE_FORMATS_USAGES = 1L << 13;
    static final long NATIVE_DISPLAY_REFRESH = 1L << 14;
    static final long NATIVE_DISPLAY_HEADROOM = 1L << 15;
    static final long NATIVE_TEMPORAL_PROFILE = 1L << 16;
    static final int NATIVE_REFRESH_SHIFT = 48;
    static final long NATIVE_REFRESH_MASK = 0xffffL << NATIVE_REFRESH_SHIFT;

    private final Set<Feature> features;
    private final Map<Feature, Evidence> evidence;
    private final FormatUsageProfile formatUsageProfile;
    private final TemporalProfile temporalProfile;
    private final FrameInterpolationProfile frameInterpolationProfile;
    private final DisplayCapabilities displayCapabilities;

    private MetalCapabilities(
            final Set<Feature> features,
            final Map<Feature, Evidence> evidence,
            final FormatUsageProfile formatUsageProfile,
            final TemporalProfile temporalProfile,
            final FrameInterpolationProfile frameInterpolationProfile,
            final DisplayCapabilities displayCapabilities
    ) {
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(evidence, "evidence");
        this.formatUsageProfile = Objects.requireNonNull(formatUsageProfile, "formatUsageProfile");
        this.temporalProfile = Objects.requireNonNull(temporalProfile, "temporalProfile");
        this.frameInterpolationProfile = Objects.requireNonNull(
                frameInterpolationProfile, "frameInterpolationProfile"
        );
        this.displayCapabilities = Objects.requireNonNull(displayCapabilities, "displayCapabilities");
        EnumSet<Feature> featureCopy = features.isEmpty()
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(features);
        EnumMap<Feature, Evidence> evidenceCopy = new EnumMap<>(Feature.class);
        evidenceCopy.putAll(evidence);
        if (!featureCopy.equals(evidenceCopy.keySet())) {
            throw new IllegalArgumentException("Every advertised feature needs exactly one evidence source");
        }
        this.features = Collections.unmodifiableSet(featureCopy);
        this.evidence = Collections.unmodifiableMap(evidenceCopy);
    }

    public static MetalCapabilities of(final Feature... features) {
        Objects.requireNonNull(features, "features");
        EnumSet<Feature> values = EnumSet.noneOf(Feature.class);
        Collections.addAll(values, features);
        EnumMap<Feature, Evidence> evidence = new EnumMap<>(Feature.class);
        values.forEach(feature -> evidence.put(feature, Evidence.DECLARED));
        boolean formats = values.contains(Feature.REQUIRED_TEXTURE_FORMATS_USAGES);
        DisplayCapabilities display = values.contains(Feature.HDR_OUTPUT)
                ? new DisplayCapabilities(0, 1.0f, 2.0f)
                : DisplayCapabilities.UNKNOWN_SDR;
        boolean fiSupport = values.contains(Feature.METALFX_FRAME_INTERPOLATION);
        boolean fi4Support = values.contains(Feature.METALFX_FRAME_INTERPOLATION_METAL4);
        return new MetalCapabilities(
                values,
                evidence,
                new FormatUsageProfile(formats, false),
                TemporalProfile.UNAVAILABLE,
                fiSupport
                        ? new FrameInterpolationProfile(true, fi4Support, true, true, false)
                        : FrameInterpolationProfile.UNAVAILABLE,
                display
        );
    }

    /** Current production baseline. Material and Advanced admission are added by runtime gates. */
    public static MetalCapabilities productionMetal3(final boolean hdrOutputAvailable) {
        return hdrOutputAvailable
                ? of(Feature.METAL3_BASE, Feature.HDR_OUTPUT)
                : of(Feature.METAL3_BASE);
    }

    /** Decodes the one-time native discovery packet and the initial display headroom sample. */
    public static MetalCapabilities fromNativeSnapshot(
            final long nativeSnapshot,
            final EdrCapabilities edrCapabilities
    ) {
        Objects.requireNonNull(edrCapabilities, "edrCapabilities");
        EnumSet<Feature> features = EnumSet.noneOf(Feature.class);
        EnumMap<Feature, Evidence> evidence = new EnumMap<>(Feature.class);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL3_BASE,
                Feature.METAL3_BASE, Evidence.ENGINE_BASELINE);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL4_OS_API,
                Feature.METAL4_OS_API, Evidence.OS_API_AVAILABILITY);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL4_GPU_FAMILY,
                Feature.METAL4_GPU_FAMILY, Evidence.GPU_FAMILY_QUERY);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL4_CORE,
                Feature.METAL4_CORE, Evidence.COMBINED_VALIDATION);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL4_COMPILER,
                Feature.METAL4_COMPILER, Evidence.RUNTIME_PROBE);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL4_COMMAND_LIFECYCLE,
                Feature.METAL4_COMMAND_LIFECYCLE, Evidence.API_DERIVED);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL4_ARGUMENT_TABLES,
                Feature.METAL4_ARGUMENT_TABLES, Evidence.API_DERIVED);
        addNative(features, evidence, nativeSnapshot, NATIVE_METAL4_EXPLICIT_BARRIERS,
                Feature.METAL4_EXPLICIT_BARRIERS, Evidence.API_DERIVED);
        addNative(features, evidence, nativeSnapshot, NATIVE_METALFX_SPATIAL,
                Feature.METALFX_SPATIAL, Evidence.FRAMEWORK_DEVICE_QUERY);
        addNative(features, evidence, nativeSnapshot, NATIVE_METALFX_TEMPORAL,
                Feature.METALFX_TEMPORAL, Evidence.FRAMEWORK_DEVICE_QUERY);
        addNative(features, evidence, nativeSnapshot, NATIVE_METALFX_FRAME_INTERPOLATION,
                Feature.METALFX_FRAME_INTERPOLATION, Evidence.FRAMEWORK_DEVICE_QUERY);
        addNative(features, evidence, nativeSnapshot, NATIVE_METALFX_TEMPORAL_METAL4,
                Feature.METALFX_TEMPORAL_METAL4, Evidence.FRAMEWORK_METAL4_QUERY);
        addNative(features, evidence, nativeSnapshot, NATIVE_METALFX_FRAME_INTERPOLATION_METAL4,
                Feature.METALFX_FRAME_INTERPOLATION_METAL4, Evidence.FRAMEWORK_METAL4_QUERY);
        addNative(features, evidence, nativeSnapshot, NATIVE_REQUIRED_TEXTURE_FORMATS_USAGES,
                Feature.REQUIRED_TEXTURE_FORMATS_USAGES, Evidence.FORMAT_USAGE_PROBE);
        addNative(features, evidence, nativeSnapshot, NATIVE_DISPLAY_REFRESH,
                Feature.DISPLAY_REFRESH, Evidence.DISPLAY_QUERY);
        addNative(features, evidence, nativeSnapshot, NATIVE_DISPLAY_HEADROOM,
                Feature.DISPLAY_HEADROOM, Evidence.DISPLAY_QUERY);
        if (edrCapabilities.isHdrDisplay()) {
            features.add(Feature.HDR_OUTPUT);
            evidence.put(Feature.HDR_OUTPUT, Evidence.DISPLAY_QUERY);
        }

        int refresh = (int) ((nativeSnapshot & NATIVE_REFRESH_MASK) >>> NATIVE_REFRESH_SHIFT);
        boolean formats = features.contains(Feature.REQUIRED_TEXTURE_FORMATS_USAGES);
        boolean temporalProfile = (nativeSnapshot & NATIVE_TEMPORAL_PROFILE) != 0L;
        boolean fiSupport = features.contains(Feature.METALFX_FRAME_INTERPOLATION);
        boolean fi4Support = features.contains(Feature.METALFX_FRAME_INTERPOLATION_METAL4);
        return new MetalCapabilities(
                features,
                evidence,
                new FormatUsageProfile(formats, temporalProfile),
                temporalProfile
                        ? new TemporalProfile(true, true, true, true, true, true)
                        : TemporalProfile.UNAVAILABLE,
                fiSupport
                        ? new FrameInterpolationProfile(true, fi4Support, true, true, false)
                        : FrameInterpolationProfile.UNAVAILABLE,
                new DisplayCapabilities(
                        features.contains(Feature.DISPLAY_REFRESH) ? refresh : 0,
                        edrCapabilities.currentHeadroom(),
                        edrCapabilities.potentialHeadroom()
                )
        );
    }

    private static void addNative(
            final Set<Feature> features,
            final Map<Feature, Evidence> evidence,
            final long snapshot,
            final long bit,
            final Feature feature,
            final Evidence source
    ) {
        if ((snapshot & bit) != 0L) {
            features.add(feature);
            evidence.put(feature, source);
        }
    }

    public Set<Feature> features() {
        return this.features;
    }

    public Map<Feature, Evidence> evidence() {
        return this.evidence;
    }

    public Evidence evidenceFor(final Feature feature) {
        return this.evidence.get(Objects.requireNonNull(feature, "feature"));
    }

    public FormatUsageProfile formatUsageProfile() {
        return this.formatUsageProfile;
    }

    public TemporalProfile temporalProfile() {
        return this.temporalProfile;
    }

    public FrameInterpolationProfile frameInterpolationProfile() {
        return this.frameInterpolationProfile;
    }

    public DisplayCapabilities displayCapabilities() {
        return this.displayCapabilities;
    }

    public boolean supports(final Feature feature) {
        return this.features.contains(Objects.requireNonNull(feature, "feature"));
    }

    /** Adds one feature proven by a runtime admission gate without mutating discovery evidence. */
    public MetalCapabilities withRuntimeFeature(final Feature feature) {
        Objects.requireNonNull(feature, "feature");
        if (this.features.contains(feature)) {
            return this;
        }
        EnumSet<Feature> resolvedFeatures = this.features.isEmpty()
                ? EnumSet.noneOf(Feature.class)
                : EnumSet.copyOf(this.features);
        resolvedFeatures.add(feature);
        EnumMap<Feature, Evidence> resolvedEvidence = new EnumMap<>(Feature.class);
        resolvedEvidence.putAll(this.evidence);
        resolvedEvidence.put(feature, Evidence.RUNTIME_PROBE);
        return new MetalCapabilities(
                resolvedFeatures,
                resolvedEvidence,
                this.formatUsageProfile,
                this.temporalProfile,
                this.frameInterpolationProfile,
                this.displayCapabilities
        );
    }

    public boolean supports(final RenderContractMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case LEGACY -> true;
            case METALLUM -> supports(Feature.METALLUM_MATERIAL_CONTRACT);
        };
    }

    public boolean supports(final LightingModel model) {
        return switch (Objects.requireNonNull(model, "model")) {
            case VANILLA -> true;
            case ADVANCED -> supports(Feature.ADVANCED_LIGHTING);
        };
    }

    public boolean supports(final DisplayOutputMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case SDR -> true;
            case HDR -> supports(Feature.HDR_OUTPUT);
        };
    }

    public boolean supports(final MetalExecutorKind executor) {
        return switch (Objects.requireNonNull(executor, "executor")) {
            case METAL3 -> supports(Feature.METAL3_BASE);
            case METAL4 -> supports(Feature.METAL4_CORE);
        };
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof MetalCapabilities capabilities
                && this.features.equals(capabilities.features)
                && this.evidence.equals(capabilities.evidence)
                && this.formatUsageProfile.equals(capabilities.formatUsageProfile)
                && this.frameInterpolationProfile.equals(capabilities.frameInterpolationProfile)
                && this.displayCapabilities.equals(capabilities.displayCapabilities);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                this.features,
                this.evidence,
                this.formatUsageProfile,
                this.frameInterpolationProfile,
                this.displayCapabilities
        );
    }

    @Override
    public String toString() {
        return "MetalCapabilities{features=" + this.features
                + ", formatUsageProfile=" + this.formatUsageProfile
                + ", frameInterpolationProfile=" + this.frameInterpolationProfile
                + ", displayCapabilities=" + this.displayCapabilities + '}';
    }
}
