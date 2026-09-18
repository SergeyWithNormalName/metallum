package com.metallum.client.gi.capture;

import com.metallum.client.gi.semantic.GiSemanticMaterial;
import com.metallum.client.gi.semantic.GiSemanticMedium;
import com.metallum.client.gi.semantic.GiSemanticPalette;
import com.metallum.client.gi.semantic.GiSemanticProvenance;
import com.metallum.client.gi.semantic.GiSemanticQuadObservation;
import com.metallum.client.lighting.SurfaceMaterialPolicy;
import net.caffeinemc.mods.sodium.api.util.ColorABGR;
import net.caffeinemc.mods.sodium.client.model.quad.ModelQuadView;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Value-only copy of one buffered Sodium quad. It retains no sprite, image, or light array. */
public record GiSemanticQuadSample(
        int localIndex,
        String canonicalKey,
        GiSemanticMaterial material,
        GiSemanticMedium medium,
        float normalX,
        float normalY,
        float normalZ,
        float normalizedArea,
        float red,
        float green,
        float blue,
        int provenance
) {
    public static GiSemanticQuadSample capture(
            final ModelQuadView quad,
            final BlockPos position,
            final Identifier spriteId,
            final Material sodiumMaterial,
            final BlockState state,
            final GiSemanticMedium medium
    ) {
        int localIndex = GiSemanticCaptureScope.localIndex(position);
        if (localIndex < 0 || quad == null || spriteId == null || sodiumMaterial == null || state == null) {
            return null;
        }
        float ax = quad.getX(1) - quad.getX(0);
        float ay = quad.getY(1) - quad.getY(0);
        float az = quad.getZ(1) - quad.getZ(0);
        float bx = quad.getX(2) - quad.getX(0);
        float by = quad.getY(2) - quad.getY(0);
        float bz = quad.getZ(2) - quad.getZ(0);
        float cx = quad.getX(3) - quad.getX(0);
        float cy = quad.getY(3) - quad.getY(0);
        float cz = quad.getZ(3) - quad.getZ(0);
        float n1x = ay * bz - az * by;
        float n1y = az * bx - ax * bz;
        float n1z = ax * by - ay * bx;
        float n2x = by * cz - bz * cy;
        float n2y = bz * cx - bx * cz;
        float n2z = bx * cy - by * cx;
        float length1 = length(n1x, n1y, n1z);
        float length2 = length(n2x, n2y, n2z);
        float nx = n1x + n2x;
        float ny = n1y + n2y;
        float nz = n1z + n2z;
        float normalLength = length(nx, ny, nz);
        if (!(normalLength > 1.0e-7F) || !Float.isFinite(normalLength)) {
            return null;
        }
        nx /= normalLength;
        ny /= normalLength;
        nz /= normalLength;
        float red = 0.0F;
        float green = 0.0F;
        float blue = 0.0F;
        for (int vertex = 0; vertex < 4; vertex++) {
            int color = quad.getColor(vertex);
            red += com.metallum.client.gi.semantic.GiSemanticPacking.srgbToLinear(ColorABGR.unpackRed(color));
            green += com.metallum.client.gi.semantic.GiSemanticPacking.srgbToLinear(ColorABGR.unpackGreen(color));
            blue += com.metallum.client.gi.semantic.GiSemanticPacking.srgbToLinear(ColorABGR.unpackBlue(color));
        }
        red *= 0.25F;
        green *= 0.25F;
        blue *= 0.25F;
        GiSemanticSpriteColorAtlas.LinearRgb spriteColor = GiSemanticSpriteColorAtlas.color(spriteId);
        if (spriteColor == null) {
            return null;
        }
        red *= spriteColor.red();
        green *= spriteColor.green();
        blue *= spriteColor.blue();
        SurfaceMaterialPolicy.Descriptor descriptor = SurfaceMaterialPolicy.forTerrain(
                state, sodiumMaterial.isTranslucent()
        );
        String key = GiSemanticPaletteFactory.canonicalKey(state, descriptor, medium);
        int provenance = GiSemanticProvenance.RESOURCE_DERIVED | GiSemanticProvenance.MATERIAL_DERIVED;
        if (quad.getTintIndex() >= 0) {
            provenance |= GiSemanticProvenance.BIOME_TINTED;
        }
        return new GiSemanticQuadSample(
                localIndex, key, GiSemanticMaterial.from(descriptor), medium,
                nx, ny, nz, Math.clamp(0.5F * (length1 + length2), 1.0F / 65535.0F, 1.0F),
                red, green, blue, provenance
        );
    }

    /** Distributes area over signed axes, preserving diagonal face weights deterministically. */
    public List<Observation> observations(
            final float emissionRed,
            final float emissionGreen,
            final float emissionBlue,
            final int emissionLevel
    ) {
        float total = Math.abs(this.normalX) + Math.abs(this.normalY) + Math.abs(this.normalZ);
        List<Observation> result = new ArrayList<>(3);
        addAxis(result, this.normalX, 1, this.normalizedArea * Math.abs(this.normalX) / total,
                emissionRed, emissionGreen, emissionBlue, emissionLevel);
        addAxis(result, this.normalY, 4, this.normalizedArea * Math.abs(this.normalY) / total,
                emissionRed, emissionGreen, emissionBlue, emissionLevel);
        addAxis(result, this.normalZ, 16, this.normalizedArea * Math.abs(this.normalZ) / total,
                emissionRed, emissionGreen, emissionBlue, emissionLevel);
        return result;
    }

    public GiSemanticPalette.Seed paletteSeed(
            final float emissionRed,
            final float emissionGreen,
            final float emissionBlue,
            final int emissionLevel
    ) {
        return new GiSemanticPalette.Seed(
                this.canonicalKey, this.material, this.medium,
                this.red, this.green, this.blue,
                emissionRed, emissionGreen, emissionBlue,
                Math.clamp(emissionLevel, 0, 15) / 15.0F,
                this.provenance
        );
    }

    private void addAxis(
            final List<Observation> result,
            final float signedNormal,
            final int positiveFaceBit,
            final float area,
            final float emissionRed,
            final float emissionGreen,
            final float emissionBlue,
            final int emissionLevel
    ) {
        if (!(area > 0.0F)) return;
        int face = signedNormal < 0.0F ? positiveFaceBit : positiveFaceBit << 1;
        GiSemanticQuadObservation observation = GiSemanticQuadObservation.fromLinear(
                this.localIndex,
                face == 1 ? -1 : face == 2 ? 1 : 0,
                face == 4 ? -1 : face == 8 ? 1 : 0,
                face == 16 ? -1 : face == 32 ? 1 : 0,
                this.canonicalKey,
                this.red, this.green, this.blue,
                emissionRed, emissionGreen, emissionBlue,
                Math.clamp(emissionLevel, 0, 15) / 15.0F,
                Math.clamp(area, 1.0F / 65535.0F, 1.0F),
                this.provenance
        );
        result.add(new Observation(observation));
    }

    private static float length(final float x, final float y, final float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public record Observation(GiSemanticQuadObservation quad) {
        public void submit() {
            GiSemanticCaptureScope.observe(this.quad);
        }
    }
}
