package com.metallum.client.metal.render;

import com.metallum.client.hdr.HdrPipelinePolicy;
import com.metallum.client.hdr.HdrShaderFlavor;
import com.metallum.client.hdr.MetallumMaterialPreflightGate;
import com.metallum.client.hdr.SceneLinearPreflightGate;
import com.metallum.client.lighting.shader.AdvancedDirectLightingShaderPatcher;
import com.metallum.client.lighting.shader.AdvancedLightingBindingAbi;
import com.metallum.client.lighting.shader.AdvancedLightingPreflightGate;
import com.metallum.client.lighting.shader.EnvironmentShadowBindingAbi;
import com.metallum.client.lighting.shader.VoxelShadowBindingAbi;
import com.metallum.client.sodium.SodiumLightSidecar;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout.VulkanBindGroupEntryType;
import com.mojang.blaze3d.vulkan.glsl.*;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslShaderInterfaceVar2;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
final class MetalCrossShaderCompiler {
    private static final Set<String> BUILT_IN_UNIFORMS = Set.of("Projection", "Lighting", "Fog", "Globals");
    private static final int MSL_VERSION_4_0 = 0x040000;
    private static final Pattern VERTEX_ENTRY_PATTERN = Pattern.compile("\\bvertex\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final Pattern FRAGMENT_ENTRY_PATTERN = Pattern.compile("\\bfragment\\s+\\w+\\s+(\\w+)\\s*\\(");
    private static final String VOXEL_TRAVERSAL_LOOP_MSL =
            "    for (uint hardStep = 0u; hardStep < maxSteps; hardStep++)";
    private static final String VOXEL_TRAVERSAL_LOOP_NO_UNROLL_MSL =
            "    #pragma clang loop unroll(disable)\n" + VOXEL_TRAVERSAL_LOOP_MSL;

    private MetalCrossShaderCompiler() {
    }

    static MetalCompiledRenderPipeline compile(final MetalDevice device, final RenderPipeline pipeline, final ShaderSource shaderSource) {
        try {
            HdrPipelinePolicy.Role role = classifyPipeline(pipeline);
            EnumMap<HdrShaderFlavor, MetalCompiledRenderPipeline.ShaderVariantSource> variants =
                    new EnumMap<>(HdrShaderFlavor.class);

            MetalCompiledRenderPipeline.ShaderVariantSource legacy = compileVariant(
                    device,
                    pipeline,
                    shaderSource,
                    HdrShaderFlavor.LEGACY
            );
            variants.put(HdrShaderFlavor.LEGACY, legacy);

            if (com.metallum.client.hdr.HdrSemanticState.isRequested()
                    && role == HdrPipelinePolicy.Role.SCENE_RASTER) {
                try {
                    MetalCompiledRenderPipeline.ShaderVariantSource semantic = compileVariant(
                            device,
                            pipeline,
                            shaderSource,
                            HdrShaderFlavor.LEGACY_HDR_SEMANTIC
                    );
                    validateVariantParity(
                            pipeline, HdrShaderFlavor.LEGACY_HDR_SEMANTIC, legacy, semantic
                    );
                    variants.put(HdrShaderFlavor.LEGACY_HDR_SEMANTIC, semantic);
                } catch (ShaderCompileException | RuntimeException exception) {
                    com.metallum.Metallum.LOGGER.warn(
                            "Legacy HDR semantic variant is unavailable for {}; output remains on the base Legacy shader: {}",
                            pipeline.getLocation(),
                            failureMessage(exception)
                    );
                }
            }

            if (SceneLinearPreflightGate.shouldCompileSceneVariants() && role.supportsSceneLinearFlavor()) {
                HdrShaderFlavor sceneFlavor = role.sceneLinearFlavor();
                try {
                    MetalCompiledRenderPipeline.ShaderVariantSource sceneLinear = compileVariant(
                            device,
                            pipeline,
                            shaderSource,
                            sceneFlavor
                    );
                    validateVariantParity(pipeline, sceneFlavor, legacy, sceneLinear);
                    variants.put(sceneFlavor, sceneLinear);
                } catch (ShaderCompileException | RuntimeException exception) {
                    SceneLinearPreflightGate.rejectSceneVariant(
                            "failed to compile " + sceneFlavor + " for " + pipeline.getLocation()
                                    + ": " + failureMessage(exception)
                    );
                }
            }

            if (MetallumMaterialPreflightGate.shouldCompileMaterialVariants()) {
                if (role.supportsSceneLinearFlavor()) {
                    try {
                        MetalCompiledRenderPipeline.ShaderVariantSource material = compileVariant(
                                device,
                                pipeline,
                                shaderSource,
                                HdrShaderFlavor.METALLUM
                        );
                        validateVariantParity(pipeline, HdrShaderFlavor.METALLUM, legacy, material);
                        variants.put(HdrShaderFlavor.METALLUM, material);
                    } catch (ShaderCompileException | RuntimeException exception) {
                        MetallumMaterialPreflightGate.rejectMaterialVariant(
                                "failed to compile METALLUM for " + pipeline.getLocation()
                                        + ": " + failureMessage(exception)
                        );
                    }
                }
            }

            if (AdvancedLightingPreflightGate.shouldCompileAdvancedVariants()
                    && isAdvancedLightingPipeline(pipeline)) {
                try {
                    MetalCompiledRenderPipeline.ShaderVariantSource advanced = compileVariant(
                            device,
                            pipeline,
                            shaderSource,
                            HdrShaderFlavor.METALLUM_ADVANCED
                    );
                    validateVariantParity(
                            pipeline,
                            HdrShaderFlavor.METALLUM_ADVANCED,
                            legacy,
                            advanced
                    );
                    variants.put(HdrShaderFlavor.METALLUM_ADVANCED, advanced);

                    MetalCompiledRenderPipeline.ShaderVariantSource shadow = compileVariant(
                            device,
                            pipeline,
                            shaderSource,
                            HdrShaderFlavor.SUN_SHADOW,
                            legacy.resources()
                    );
                    validateShadowVariantLayout(pipeline, legacy, shadow);
                    variants.put(HdrShaderFlavor.SUN_SHADOW, shadow);
                } catch (ShaderCompileException | RuntimeException exception) {
                    AdvancedLightingPreflightGate.rejectAdvancedVariant(
                            "failed to compile METALLUM_ADVANCED/L4 shadow for "
                                    + pipeline.getLocation()
                                    + ": " + failureMessage(exception)
                    );
                }
            }

            EnumMap<HdrShaderFlavor, MetalCompiledRenderPipeline.ShaderVariantSource> patchedVariants =
                    patchSodiumLightSidecar(device, pipeline, variants);
            if (patchedVariants != null) {
                MetalCompiledRenderPipeline patchedPipeline = null;
                try {
                    patchedPipeline = new MetalCompiledRenderPipeline(device, pipeline, role, patchedVariants);
                    if (patchedPipeline.isValid()) {
                        SodiumLightSidecar.notePatchedPipeline(pipeline.getLocation().toString());
                        return patchedPipeline;
                    }
                    patchedPipeline.close();
                    SodiumLightSidecar.fail(
                            "Metal rejected the patched terrain shader pipeline " + pipeline.getLocation(),
                            null
                    );
                } catch (RuntimeException exception) {
                    if (patchedPipeline != null) {
                        patchedPipeline.close();
                    }
                    SodiumLightSidecar.fail(
                            "Metal could not compile the patched terrain shader pipeline " + pipeline.getLocation(),
                            exception
                    );
                }
            }

            return new MetalCompiledRenderPipeline(device, pipeline, role, variants);
        } catch (ShaderCompileException e) {
            throw new IllegalStateException("Failed to compile Metal cross shader for pipeline " + pipeline.getLocation(), e);
        }
    }

    @Nullable
    private static EnumMap<HdrShaderFlavor, MetalCompiledRenderPipeline.ShaderVariantSource> patchSodiumLightSidecar(
            final MetalDevice device,
            final RenderPipeline pipeline,
            final EnumMap<HdrShaderFlavor, MetalCompiledRenderPipeline.ShaderVariantSource> variants
    ) {
        if (!SodiumLightSidecar.isRuntimeActive() || !SodiumLightSidecarMslPatcher.isTarget(pipeline)) {
            return null;
        }

        MetalCompiledRenderPipeline.ShaderVariantSource legacy = variants.get(HdrShaderFlavor.LEGACY);
        if (legacy == null) {
            SodiumLightSidecar.fail(
                    "target terrain pipeline is missing its legacy shader flavor " + pipeline.getLocation(),
                    null
            );
            return null;
        }

        try {
            device.sodiumLightSidecarBindings();
            int dataBufferSlot = Math.addExact(
                    MetalCompiledRenderPipeline.firstAvailableVertexBufferSlot(legacy.resources()),
                    MetalRenderPass.MAX_VERTEX_BUFFERS
            );
            int controlBufferSlot = Math.addExact(dataBufferSlot, 1);
            EnumMap<HdrShaderFlavor, MetalCompiledRenderPipeline.ShaderVariantSource> patchedVariants =
                    new EnumMap<>(HdrShaderFlavor.class);

            for (Map.Entry<HdrShaderFlavor, MetalCompiledRenderPipeline.ShaderVariantSource> entry
                    : variants.entrySet()) {
                MetalCompiledRenderPipeline.ShaderVariantSource variant = entry.getValue();
                SodiumLightSidecarMslPatcher.Result patch = SodiumLightSidecarMslPatcher.patch(
                        variant.vertexMsl(),
                        variant.vertexEntryPoint(),
                        dataBufferSlot,
                        controlBufferSlot
                );
                if (!patch.success()) {
                    com.metallum.Metallum.LOGGER.warn(
                            "Sodium light sidecar did not patch pipeline {} flavor {}; keeping legacy packed lighting: {}",
                            pipeline.getLocation(),
                            entry.getKey(),
                            patch.reason()
                    );
                    SodiumLightSidecar.fail(
                            "could not patch terrain pipeline " + pipeline.getLocation()
                                    + " flavor " + entry.getKey() + ": " + patch.reason(),
                            null
                    );
                    return null;
                }
                patchedVariants.put(entry.getKey(), new MetalCompiledRenderPipeline.ShaderVariantSource(
                        patch.source(),
                        variant.fragmentMsl(),
                        variant.vertexEntryPoint(),
                        variant.fragmentEntryPoint(),
                        variant.resources(),
                        variant.semanticOutput()
                ));
            }

            return patchedVariants;
        } catch (RuntimeException exception) {
            com.metallum.Metallum.LOGGER.warn(
                    "Sodium light sidecar could not prepare pipeline {}; keeping legacy packed lighting",
                    pipeline.getLocation(),
                    exception
            );
            SodiumLightSidecar.fail(
                    "could not prepare terrain shader sidecar slots for pipeline " + pipeline.getLocation(),
                    exception
            );
            return null;
        }
    }

    private static String failureMessage(final Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    private static MetalCompiledRenderPipeline.ShaderVariantSource compileVariant(
            final MetalDevice device,
            final RenderPipeline pipeline,
            final ShaderSource shaderSource,
            final HdrShaderFlavor flavor
    ) throws ShaderCompileException {
        return compileVariant(device, pipeline, shaderSource, flavor, null);
    }

    private static MetalCompiledRenderPipeline.ShaderVariantSource compileVariant(
            final MetalDevice device,
            final RenderPipeline pipeline,
            final ShaderSource shaderSource,
            final HdrShaderFlavor flavor,
            @Nullable final List<MetalCompiledRenderPipeline.ResourceBinding> canonicalResources
    ) throws ShaderCompileException {
        IntermediaryShaderModule vertexSpirv = device.getOrCompileShader(
                pipeline.getVertexShader(),
                ShaderType.VERTEX,
                pipeline.getShaderDefines(),
                shaderSource,
                flavor
        );
        IntermediaryShaderModule fragmentSpirv = device.getOrCompileShader(
                pipeline.getFragmentShader(),
                ShaderType.FRAGMENT,
                pipeline.getShaderDefines(),
                shaderSource,
                flavor
        );
        if (vertexSpirv == IntermediaryShaderModule.INVALID || fragmentSpirv == IntermediaryShaderModule.INVALID) {
            throw new IllegalStateException(
                    "Couldn't compile " + flavor + " shader for pipeline " + pipeline.getLocation()
            );
        }
        IntermediaryShaderModule fragmentLayoutSpirv = withoutExternalShadowSamplers(
                fragmentSpirv,
                flavor
        );

        List<VulkanBindGroupLayout.Entry> layoutEntries = new ArrayList<>();
        if (canonicalResources != null) {
            seedCanonicalLayout(layoutEntries, canonicalResources);
        }
        addToBindGroup(layoutEntries, vertexSpirv, pipeline);
        addToBindGroup(layoutEntries, fragmentLayoutSpirv, pipeline);
        canonicalizeLayoutEntries(layoutEntries);
        List<String> vertexOutputs = extractVariableNames(vertexSpirv.outputs());

        vertexSpirv.rebind(
                tolerateUnprovidedInputs(MetalPipelineSupport.vertexAttributeNames(pipeline), vertexSpirv.inputs()),
                layoutEntries
        );
        MslShader vertexMsl = spirvToMsl(
                vertexSpirv.spirv(),
                layoutEntries.size(),
                vertexAttributeFormats(pipeline)
        );

        fragmentLayoutSpirv.rebind(
                tolerateUnprovidedInputs(vertexOutputs, fragmentLayoutSpirv.inputs()),
                layoutEntries
        );
        MslShader fragmentMsl = spirvToMsl(fragmentSpirv.spirv(), layoutEntries.size(), Map.of());

        String fragmentSource = flavor == HdrShaderFlavor.METALLUM_ADVANCED
                ? preserveVoxelShadowTraversalLoop(fragmentMsl.source())
                : fragmentMsl.source();
        String vertexEntryPoint = extractEntryPoint(vertexMsl.source(), VERTEX_ENTRY_PATTERN, "main0");
        String fragmentEntryPoint = extractEntryPoint(fragmentSource, FRAGMENT_ENTRY_PATTERN, "main0");
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = List.copyOf(
                buildResourceBindings(layoutEntries, vertexMsl, fragmentMsl)
        );
        return new MetalCompiledRenderPipeline.ShaderVariantSource(
                vertexMsl.source(),
                fragmentSource,
                vertexEntryPoint,
                fragmentEntryPoint,
                resources,
                fragmentSource.contains("[[color(1)]]")
        );
    }

    /**
     * SPIRV-Cross drops the GLSL/SPIR-V no-unroll hint when emitting MSL. Preserve the pragma when
     * the legacy L6 DDA helper survives dead-code elimination; the cached runtime path has no
     * fragment traversal loop, so a module without that helper is canonical too.
     */
    static String preserveVoxelShadowTraversalLoop(final String source) {
        Objects.requireNonNull(source, "source");
        int loopCount = countOccurrences(source, VOXEL_TRAVERSAL_LOOP_MSL);
        if (loopCount == 0 && !source.contains(VOXEL_TRAVERSAL_LOOP_NO_UNROLL_MSL)) {
            return source;
        }
        if (loopCount != 1 || source.contains(VOXEL_TRAVERSAL_LOOP_NO_UNROLL_MSL)) {
            throw new IllegalStateException(
                    "Advanced MSL exposed a non-canonical legacy L6 traversal loop"
            );
        }
        String patched = source.replace(
                VOXEL_TRAVERSAL_LOOP_MSL,
                VOXEL_TRAVERSAL_LOOP_NO_UNROLL_MSL
        );
        if (countOccurrences(patched, VOXEL_TRAVERSAL_LOOP_NO_UNROLL_MSL) != 1) {
            throw new IllegalStateException("Could not preserve the L6 traversal loop");
        }
        return patched;
    }

    private static void seedCanonicalLayout(
            final List<VulkanBindGroupLayout.Entry> entries,
            final List<MetalCompiledRenderPipeline.ResourceBinding> resources
    ) {
        for (MetalCompiledRenderPipeline.ResourceBinding resource : resources) {
            if (resource.name().equals("push_constants")) {
                continue;
            }
            VulkanBindGroupEntryType type = switch (resource.kind()) {
                case UNIFORM_BUFFER -> VulkanBindGroupEntryType.UNIFORM_BUFFER;
                case SAMPLED_IMAGE -> VulkanBindGroupEntryType.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> VulkanBindGroupEntryType.TEXEL_BUFFER;
            };
            addBindingIfAbsent(entries, type, resource.name(), resource.texelBufferFormat());
        }
    }

    private static void validateShadowVariantLayout(
            final RenderPipeline pipeline,
            final MetalCompiledRenderPipeline.ShaderVariantSource legacy,
            final MetalCompiledRenderPipeline.ShaderVariantSource shadow
    ) {
        if (shadow.semanticOutput() || legacy.resources().size() != shadow.resources().size()) {
            throw new IllegalStateException(
                    "L4 shadow variant changed the canonical resource count for "
                            + pipeline.getLocation()
            );
        }
        for (int index = 0; index < legacy.resources().size(); index++) {
            MetalCompiledRenderPipeline.ResourceBinding expected = legacy.resources().get(index);
            MetalCompiledRenderPipeline.ResourceBinding actual = shadow.resources().get(index);
            if (expected.kind() != actual.kind()
                    || !expected.name().equals(actual.name())
                    || expected.bindingIndex() != actual.bindingIndex()
                    || !Objects.equals(expected.texelBufferFormat(), actual.texelBufferFormat())) {
                throw new IllegalStateException(
                        "L4 shadow variant changed canonical binding " + index + " for "
                                + pipeline.getLocation() + ": expected=" + expected
                                + ", actual=" + actual
                );
            }
        }
    }

    private static IntermediaryShaderModule withoutExternalShadowSamplers(
            final IntermediaryShaderModule module,
            final HdrShaderFlavor flavor
    ) {
        if (flavor != HdrShaderFlavor.METALLUM_ADVANCED) {
            return module;
        }
        var filtered = module.samplers().stream()
                .filter(sampler -> !AdvancedDirectLightingShaderPatcher
                        .isExternalShadowSampler(sampler.name()))
                .toList();
        int removed = module.samplers().size() - filtered.size();
        if (removed != EnvironmentShadowBindingAbi.shadowTextureSlots().length) {
            throw new IllegalStateException(
                    "Advanced fragment must expose exactly three external L4 shadow samplers"
            );
        }
        return new IntermediaryShaderModule(
                module.name(),
                module.spirv(),
                module.uniformBuffers(),
                filtered,
                module.outputs(),
                module.inputs()
        );
    }

    private static void validateVariantParity(
            final RenderPipeline pipeline,
            final HdrShaderFlavor flavor,
            final MetalCompiledRenderPipeline.ShaderVariantSource legacy,
            final MetalCompiledRenderPipeline.ShaderVariantSource variant
    ) {
        if (!legacy.resources().equals(variant.resources())) {
            throw new IllegalStateException(
                    "HDR shader variants changed resource layout for pipeline " + pipeline.getLocation()
                            + ": legacy=" + legacy.resources()
                            + ", " + flavor + "=" + variant.resources()
            );
        }
        if (flavor == HdrShaderFlavor.METALLUM
                || flavor == HdrShaderFlavor.METALLUM_ADVANCED) {
            if (variant.semanticOutput()) {
                throw new IllegalStateException(
                        flavor + " variant retained a semantic attachment for pipeline "
                                + pipeline.getLocation()
                );
            }
            if (flavor == HdrShaderFlavor.METALLUM_ADVANCED) {
                validateAdvancedLightingBindings(pipeline, variant);
            }
        } else if (flavor != HdrShaderFlavor.LEGACY_HDR_SEMANTIC
                && flavor != HdrShaderFlavor.SCENE_RASTER_LINEAR
                && flavor != HdrShaderFlavor.SCENE_POST_LINEAR
                && legacy.semanticOutput() != variant.semanticOutput()) {
            throw new IllegalStateException(
                    "HDR shader variants changed semantic attachment output for pipeline " + pipeline.getLocation()
            );
        }
    }

    /**
     * Shader compilation is allowed to enumerate otherwise-identical resources in a different
     * order after source patching. Rebinding must use a canonical order so every flavor retains
     * the same numeric Metal layout.
     */
    static void canonicalizeLayoutEntries(final List<VulkanBindGroupLayout.Entry> entries) {
        entries.sort(Comparator
                .comparing((VulkanBindGroupLayout.Entry entry) -> entry.type().ordinal())
                .thenComparing(VulkanBindGroupLayout.Entry::name));
    }

    private static void validateAdvancedLightingBindings(
            final RenderPipeline pipeline,
            final MetalCompiledRenderPipeline.ShaderVariantSource variant
    ) {
        for (MetalCompiledRenderPipeline.ResourceBinding binding : variant.resources()) {
            if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER
                    && (AdvancedLightingBindingAbi.ownsFragmentSlot(binding.bindingIndex())
                    || binding.bindingIndex() == EnvironmentShadowBindingAbi.PARAMS_SLOT
                    || VoxelShadowBindingAbi.ownsFragmentSlot(binding.bindingIndex()))) {
                throw new IllegalStateException(
                        "Advanced lighting fragment slot " + binding.bindingIndex()
                                + " collides with pipeline resource " + binding.name()
                                + " for " + pipeline.getLocation()
                );
            }
            if (binding.kind() == MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE
                    && EnvironmentShadowBindingAbi.ownsShadowTextureSlot(
                    binding.bindingIndex())) {
                throw new IllegalStateException(
                        "L4 shadow texture slot " + binding.bindingIndex()
                                + " collides with pipeline resource " + binding.name()
                                + " for " + pipeline.getLocation()
                );
            }
        }
        for (int slot : AdvancedLightingBindingAbi.fragmentSlots()) {
            String marker = "[[buffer(" + slot + ")]]";
            if (countOccurrences(variant.fragmentMsl(), marker) != 1
                    || variant.vertexMsl().contains(marker)) {
                throw new IllegalStateException(
                        "Advanced lighting fragment buffer slot " + slot
                                + " is missing, repeated, or visible to the vertex stage for pipeline "
                                + pipeline.getLocation()
                );
            }
        }
        String visibilityCacheMarker = "[[buffer("
                + VoxelShadowBindingAbi.VISIBILITY_CACHE_BUFFER_SLOT + ")]]";
        if (countOccurrences(variant.fragmentMsl(), visibilityCacheMarker) != 1
                || variant.vertexMsl().contains(visibilityCacheMarker)) {
            throw new IllegalStateException(
                    "L6 visibility-cache buffer is missing, repeated, or visible to the vertex stage for "
                            + pipeline.getLocation()
            );
        }
        for (int slot = VoxelShadowBindingAbi.PROXY_BUFFER_SLOT;
             slot <= VoxelShadowBindingAbi.PARAMS_BUFFER_SLOT;
             slot++) {
            String marker = "[[buffer(" + slot + ")]]";
            if (countOccurrences(variant.fragmentMsl(), marker) != 1
                    || variant.vertexMsl().contains(marker)) {
                throw new IllegalStateException(
                        "L6 active local-shadow fragment buffer slot " + slot
                                + " is missing, repeated, or visible to the vertex stage for pipeline "
                                + pipeline.getLocation()
                );
            }
        }
        for (int slot = VoxelShadowBindingAbi.OCCUPANCY_TEXTURE_0_SLOT;
             slot <= VoxelShadowBindingAbi.METADATA_BUFFER_2_SLOT;
             slot++) {
            String marker = "[[buffer(" + slot + ")]]";
            if (variant.fragmentMsl().contains(marker)
                    || variant.vertexMsl().contains(marker)) {
                throw new IllegalStateException(
                        "Cached L6 shader retained dead per-fragment DDA slot " + slot
                                + " for " + pipeline.getLocation()
                );
            }
        }
        String environmentMarker = "[[buffer("
                + EnvironmentShadowBindingAbi.PARAMS_SLOT + ")]]";
        if (countOccurrences(variant.fragmentMsl(), environmentMarker) != 1
                || variant.vertexMsl().contains(environmentMarker)) {
            throw new IllegalStateException(
                    "L4 environment buffer is missing, repeated, or visible to the vertex stage for "
                            + pipeline.getLocation()
            );
        }
        for (int slot : EnvironmentShadowBindingAbi.shadowTextureSlots()) {
            String textureMarker = "[[texture(" + slot + ")]]";
            String samplerMarker = "[[sampler(" + slot + ")]]";
            if (countOccurrences(variant.fragmentMsl(), textureMarker) != 1
                    || countOccurrences(variant.fragmentMsl(), samplerMarker) != 1
                    || variant.vertexMsl().contains(textureMarker)
                    || variant.vertexMsl().contains(samplerMarker)) {
                throw new IllegalStateException(
                        "L4 shadow texture/sampler slot " + slot
                                + " is missing, repeated, or visible to the vertex stage for "
                                + pipeline.getLocation()
                );
            }
        }
    }

    private static int countOccurrences(final String source, final String marker) {
        int count = 0;
        int cursor = 0;
        while ((cursor = source.indexOf(marker, cursor)) >= 0) {
            count++;
            cursor += marker.length();
        }
        return count;
    }

    private static boolean isAdvancedLightingPipeline(final RenderPipeline pipeline) {
        var vertex = pipeline.getVertexShader();
        var fragment = pipeline.getFragmentShader();
        boolean sodiumTerrain = vertex.getNamespace().equals("sodium")
                && fragment.getNamespace().equals("sodium")
                && vertex.getPath().equals(AdvancedDirectLightingShaderPatcher.SODIUM_TERRAIN_PATH)
                && fragment.getPath().equals(AdvancedDirectLightingShaderPatcher.SODIUM_TERRAIN_PATH);
        boolean vanillaEntity = vertex.getNamespace().equals("minecraft")
                && fragment.getNamespace().equals("minecraft")
                && vertex.getPath().equals(AdvancedDirectLightingShaderPatcher.VANILLA_ENTITY_PATH)
                && fragment.getPath().equals(AdvancedDirectLightingShaderPatcher.VANILLA_ENTITY_PATH);
        return sodiumTerrain || vanillaEntity;
    }

    private static HdrPipelinePolicy.Role classifyPipeline(final RenderPipeline pipeline) {
        var location = pipeline.getLocation();
        var vertexShader = pipeline.getVertexShader();
        var fragmentShader = pipeline.getFragmentShader();
        return HdrPipelinePolicy.classify(
                location.getNamespace(),
                location.getPath(),
                vertexShader.getNamespace(),
                vertexShader.getPath(),
                fragmentShader.getNamespace(),
                fragmentShader.getPath()
        );
    }

    private static void addToBindGroup(
            final List<VulkanBindGroupLayout.Entry> entries,
            final IntermediaryShaderModule shader,
            final RenderPipeline pipeline
    ) throws ShaderCompileException {
        List<UniformDescription> uniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts());
        List<String> samplers = BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts());
        for (SpvUniformBuffer buffer : shader.uniformBuffers()) {
            String name = buffer.name();
            if (findUniform(uniforms, name) == null && !BUILT_IN_UNIFORMS.contains(name)) {
                throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
            }
            addBindingIfAbsent(entries, VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
        }

        for (SpvSampler sampler : shader.samplers()) {
            String name = sampler.name();
            UniformDescription uniform = findUniform(uniforms, name);
            int dimensions = sampler.dimensions();
            if (uniform != null) {
                if (dimensions != Spv.SpvDimBuffer) {
                    throw new ShaderCompileException("UTB (" + name + ") must have type of SpvDimBuffer");
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.TEXEL_BUFFER, name, uniform.gpuFormat());
            } else {
                if (!samplers.contains(name)) {
                    throw new ShaderCompileException("Unable to find shader defined uniform (" + name + ")");
                }
                if (dimensions != Spv.SpvDim2D && dimensions != Spv.SpvDimCube) {
                    throw new ShaderCompileException("Sampled texture (" + name + ") must have type of SpvDim2D or SpvDimCube");
                }
                addBindingIfAbsent(entries, VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
            }
        }
    }

    @Nullable
    private static UniformDescription findUniform(final List<UniformDescription> uniforms, final String name) {
        for (UniformDescription uniform : uniforms) {
            if (uniform.name().equals(name)) {
                return uniform;
            }
        }
        return null;
    }

    private static void addBindingIfAbsent(
            final List<VulkanBindGroupLayout.Entry> entries,
            final VulkanBindGroupEntryType type,
            final String name,
            @Nullable final GpuFormat texelBufferFormat
    ) {
        for (VulkanBindGroupLayout.Entry entry : entries) {
            if (entry.type() == type && entry.name().equals(name)) {
                return;
            }
        }
        entries.add(new VulkanBindGroupLayout.Entry(type, name, texelBufferFormat));
    }

    private static List<String> tolerateUnprovidedInputs(final List<String> provided, final List<SpvVariable> shaderInputs) {
        List<String> result = null;
        for (SpvVariable input : shaderInputs) {
            String name = input.name();
            if (!provided.contains(name)) {
                if (result == null) {
                    result = new ArrayList<>(provided);
                }
                if (!result.contains(name)) {
                    result.add(name);
                }
            }
        }
        return result == null ? provided : result;
    }

    private static List<String> extractVariableNames(final List<SpvVariable> variables) {
        List<String> names = new ArrayList<>(variables.size());
        for (SpvVariable variable : variables) {
            names.add(variable.name());
        }
        return names;
    }

    private static String extractEntryPoint(final String msl, final Pattern pattern, final String fallback) {
        Matcher matcher = pattern.matcher(msl);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private static List<MetalCompiledRenderPipeline.ResourceBinding> buildResourceBindings(
            final List<VulkanBindGroupLayout.Entry> entries,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        List<MetalCompiledRenderPipeline.ResourceBinding> resources = new ArrayList<>(entries.size() + 1);
        for (int index = 0; index < entries.size(); index++) {
            VulkanBindGroupLayout.Entry entry = entries.get(index);
            MetalCompiledRenderPipeline.ResourceKind kind = switch (entry.type()) {
                case UNIFORM_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER;
                case SAMPLED_IMAGE -> MetalCompiledRenderPipeline.ResourceKind.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> MetalCompiledRenderPipeline.ResourceKind.TEXEL_BUFFER;
            };
            GpuFormat texelFormat = entry.type() == VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER ? entry.texelBufferFormat() : null;
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(kind, entry.name(), index, stageMask(entry.name(), vertexMsl, fragmentMsl), texelFormat));
        }

        int pushConstantStageMask = (vertexMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_VERTEX : 0)
                | (fragmentMsl.hasPushConstants() ? MetalCompiledRenderPipeline.STAGE_FRAGMENT : 0);
        if (pushConstantStageMask != 0) {
            resources.add(new MetalCompiledRenderPipeline.ResourceBinding(
                    MetalCompiledRenderPipeline.ResourceKind.UNIFORM_BUFFER,
                    "push_constants",
                    entries.size(),
                    pushConstantStageMask,
                    null
            ));
        }
        return resources;
    }

    private static int stageMask(
            final String name,
            final MslShader vertexMsl,
            final MslShader fragmentMsl
    ) {
        int mask = 0;
        if (vertexMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_VERTEX;
        }
        if (fragmentMsl.activeResources().contains(name)) {
            mask |= MetalCompiledRenderPipeline.STAGE_FRAGMENT;
        }
        if (mask == 0) {
            mask = MetalCompiledRenderPipeline.STAGE_ALL;
        }

        return mask;
    }

    private static Map<String, GpuFormat> vertexAttributeFormats(final RenderPipeline pipeline) {
        Map<String, GpuFormat> formats = new LinkedHashMap<>();
        for (VertexFormat binding : pipeline.getVertexFormatBindings()) {
            if (binding != null) {
                for (VertexFormatElement element : binding.getElements()) {
                    formats.putIfAbsent(element.name(), element.format());
                }
            }
        }
        return formats;
    }

    private static void registerIntegerInputConversions(
            final MemoryStack stack,
            final long compiler,
            final Map<String, GpuFormat> attributeFormats
    ) throws ShaderCompileException {
        if (attributeFormats.isEmpty()) {
            return;
        }

        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");

        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(pResources.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, pList, pCount), "spvc_resources_get_resource_list_for_type(STAGE_INPUT)");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }

        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            SpvcReflectedResource input = list.get(i);
            GpuFormat format = attributeFormats.get(input.nameString());
            if (format == null || !format.name().endsWith("_UINT")) {
                continue;
            }
            int width = format.name().contains("8") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT8
                    : format.name().contains("16") ? Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_UINT16
                      : Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER;
            if (width == Spvc.SPVC_MSL_SHADER_VARIABLE_FORMAT_OTHER) {
                continue;
            }

            long typeHandle = Spvc.spvc_compiler_get_type_handle(compiler, input.type_id());
            int baseType = Spvc.spvc_type_get_basetype(typeHandle);
            if (baseType != Spvc.SPVC_BASETYPE_INT8 && baseType != Spvc.SPVC_BASETYPE_INT16
                    && baseType != Spvc.SPVC_BASETYPE_INT32 && baseType != Spvc.SPVC_BASETYPE_INT64) {
                continue;
            }

            SpvcMslShaderInterfaceVar2 var = SpvcMslShaderInterfaceVar2.malloc(stack);
            Spvc.spvc_msl_shader_interface_var_init_2(var);
            var.location(Spvc.spvc_compiler_get_decoration(compiler, input.id(), Spv.SpvDecorationLocation));
            var.vecsize(Spvc.spvc_type_get_vector_size(typeHandle));
            var.format(width);
            var.rate(Spvc.SPVC_MSL_SHADER_VARIABLE_RATE_PER_VERTEX);
            checkSpvc(Spvc.spvc_compiler_msl_add_shader_input_2(compiler, var), "spvc_compiler_msl_add_shader_input_2");
        }
    }

    private static MslShader spirvToMsl(final ByteBuffer spirvBytes, final int pushConstantBinding, final Map<String, GpuFormat> attributeFormats) throws ShaderCompileException {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirvBytes.asIntBuffer();

            PointerBuffer pContext = stack.mallocPointer(1);
            checkSpvc(Spvc.spvc_context_create(pContext), "spvc_context_create");
            long context = pContext.get(0);
            try {
                PointerBuffer pIr = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr), "spvc_context_parse_spirv");

                PointerBuffer pCompiler = stack.mallocPointer(1);
                checkSpvc(
                        Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, pIr.get(0), Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler),
                        "spvc_context_create_compiler"
                );
                long compiler = pCompiler.get(0);

                PointerBuffer pOptions = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_compiler_options(compiler, pOptions), "spvc_compiler_create_compiler_options");
                long options = pOptions.get(0);
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS),
                        "spvc_compiler_options_set_uint(MSL_PLATFORM)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, MSL_VERSION_4_0),
                        "spvc_compiler_options_set_uint(MSL_VERSION)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true),
                        "spvc_compiler_options_set_bool(MSL_ENABLE_DECORATION_BINDING)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true),
                        "spvc_compiler_options_set_bool(MSL_TEXTURE_BUFFER_NATIVE)"
                );
                checkSpvc(
                        Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true),
                        "spvc_compiler_options_set_bool(FLIP_VERTEX_Y)"
                );
                checkSpvc(Spvc.spvc_compiler_install_compiler_options(compiler, options), "spvc_compiler_install_compiler_options");

                registerIntegerInputConversions(stack, compiler, attributeFormats);

                PointerBuffer pActiveSet = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet), "spvc_compiler_get_active_interface_variables");
                long activeSet = pActiveSet.get(0);
                checkSpvc(Spvc.spvc_compiler_set_enabled_interface_variables(compiler, activeSet), "spvc_compiler_set_enabled_interface_variables");

                Set<String> activeResources = collectActiveResourceNames(stack, compiler, activeSet);

                PointerBuffer pResources = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_create_shader_resources(compiler, pResources), "spvc_compiler_create_shader_resources");
                long resources = pResources.get(0);

                PointerBuffer pList = stack.mallocPointer(1);
                PointerBuffer pCount = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_PUSH_CONSTANT, pList, pCount), "spvc_resources_get_resource_list_for_type");
                boolean hasPushConstants = pCount.get(0) > 0;
                if (hasPushConstants) {
                    SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), 1);
                    Spvc.spvc_compiler_set_decoration(compiler, list.get(0).id(), Spv.SpvDecorationBinding, pushConstantBinding);
                }

                PointerBuffer pSource = stack.mallocPointer(1);
                checkSpvc(Spvc.spvc_compiler_compile(compiler, pSource), "spvc_compiler_compile");
                return new MslShader(MemoryUtil.memUTF8(pSource.get(0)), hasPushConstants, activeResources);
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }

    record MslShader(String source, boolean hasPushConstants, Set<String> activeResources) {
    }

    private static Set<String> collectActiveResourceNames(final MemoryStack stack, final long compiler, final long activeSet) throws ShaderCompileException {
        PointerBuffer pResources = stack.mallocPointer(1);
        checkSpvc(
                Spvc.spvc_compiler_create_shader_resources_for_active_variables(compiler, pResources, activeSet),
                "spvc_compiler_create_shader_resources_for_active_variables"
        );
        long resources = pResources.get(0);

        Set<String> names = new HashSet<>();
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_IMAGE, names);
        collectResourceNames(stack, resources, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, names);
        return names;
    }

    private static void collectResourceNames(
            final MemoryStack stack,
            final long resources,
            final int resourceType,
            final Set<String> out
    ) throws ShaderCompileException {
        PointerBuffer pList = stack.mallocPointer(1);
        PointerBuffer pCount = stack.mallocPointer(1);
        checkSpvc(Spvc.spvc_resources_get_resource_list_for_type(resources, resourceType, pList, pCount), "spvc_resources_get_resource_list_for_type");
        int count = (int) pCount.get(0);
        if (count == 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(pList.get(0), count);
        for (int i = 0; i < count; i++) {
            out.add(list.get(i).nameString());
        }
    }

    private static void checkSpvc(final int result, final String stage) throws ShaderCompileException {
        if (result != Spvc.SPVC_SUCCESS) {
            throw new ShaderCompileException("SPIRV-Cross error at " + stage + ": " + result);
        }
    }
}
