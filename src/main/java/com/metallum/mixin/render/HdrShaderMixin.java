package com.metallum.mixin.render;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Flavor-specific HDR/material transforms are applied by MetalDevice after
 * the unmodified resource-pack source has been placed in its cache.
 */
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
abstract class HdrShaderMixin {
}
