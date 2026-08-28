# Metallum Visual Styles Architecture

This document defines the architecture, ownership boundaries, and lifecycle contracts for built-in **Visual Styles** and **Style-Aware Celestial Lighting** in Metallum.

---

## 1. Purpose
Visual Styles provide built-in artistic rendering policies (celestial lighting curves, atmosphere coloration, and material response) while maintaining strict architectural isolation from GPU performance and quality budgets.

---

## 2. The Three Built-in Styles
Metallum defines exactly three built-in visual styles:

1. **`VANILLA`** (Default & Regression Baseline)
   - Preserves familiar Minecraft and reference Metallum visual character.
   - Restrained adjustments; acts as the regression-preserving visual oracle.
   - Exact legacy celestial color and moon-phase intensity curve.
   - Water bypasses Metallum L8 waves, reflection, refraction, absorption, and caustics.
2. **`NATURAL`**
   - Natural sunlight, moonlight, and smooth golden-hour atmospheric transitions while remaining recognizably Minecraft.
   - Visibly warm golden-orange low sun; less-blue moonlight; stronger lunar phase response.
   - Water keeps the vanilla texture and biome tint as its optical body, adding only subtle
     procedural surface breakup and a restrained environment highlight.
3. **`REALISM`**
   - Physically motivated lighting curves intended for future integration with GI, PBR materials, volumetrics, and realistic celestial optics.
   - Near-neutral daylight sun; deep golden/orange horizon sun with broad atmospheric transition; subtly cool moon; zero directional moonlight at new moon without darkening ambient night.
   - Water enables the complete L8 optical response: finer multi-scale waves, Fresnel reflection,
     bounded refraction, Beer-Lambert absorption, transmission, and synchronized caustics.

> [!IMPORTANT]
> No fourth style (such as Cinematic or custom packs) exists in this subsystem.

---

## 3. Strict Architectural Separation
Visual Style is **NOT** a quality/performance preset. The following systems remain completely orthogonal:

- **`LightingPreset`** (`PERFORMANCE`, `BALANCED`, `ULTRA`): Controls GPU compute/memory work budgets (cluster dimensions, shadow cascades, voxel clipmap resolutions).
- **`VisualStyle`** (`VANILLA`, `NATURAL`, `REALISM`): Controls artistic rendering policy parameters on the CPU.
- **`MetalFX` / DRS / Frame Interpolation**: Independent performance scaling and temporal reconstruction systems.
- **HDR (`HdrConfig`)**: Independent output dynamic range encoding and tone curve mapping.

All combinations (e.g. `REALISM + PERFORMANCE`, `VANILLA + ULTRA`) are valid. Selecting a style never alters lighting presets, render distance, shadow cascade counts, DRS targets, or MetalFX modes.

---

## 4. Persistent Ownership: `RendererConfig`
The selected `VisualStyle` is persisted in `metallum-renderer.properties` as part of `RendererConfig` (schema 5):

```properties
schemaVersion=5
improvedLighting=false
lightingPreset=balanced
frameInterpolation=false
voxelDebugChecksum=false
visualStyle=vanilla
```

- **Default**: `vanilla`.
- **Migration**: Old schemas (v1-v4) migrate safely to schema 5 with `visualStyle = VANILLA` and `globalIllumination = OFF` without resetting existing user settings.
- **Fail-Closed**: Unrecognized styles or malformed configurations fail closed to `VANILLA` without destructive file overwriting.

---

## 5. Runtime Ownership: `VisualStyleRuntime`
Process-local active style state is owned by `VisualStyleRuntime`:
- **Fast Access**: Active style and profile reads are trivial field/reference reads with zero heap allocation and zero disk I/O on render hot paths.
- **Startup Initialization**: Initialized once during `MetalDevice` startup from `RendererConfig.load().visualStyle()`. Does not emit a temporal reset.
- **Live User Switching**: Live UI changes update `VisualStyleRuntime.setStyle(...)`, persisting the new `RendererConfig` and emitting a single `VISUAL_STYLE_CHANGE` temporal reset if the style changed.

---

## 6. Profile Resolution Hierarchy
Style data flows hierarchically through immutable, validated records:

```
VisualStyle
    └── VisualStyleProfile
            ├── CelestialLightingProfile
            ├── AtmosphereProfile
            └── WaterStyleProfile
```

- `VisualStyleProfiles.profile(VisualStyle style)` authoritatively returns the immutable `VisualStyleProfile` without per-frame allocations.
- `CelestialLightingProfile` defines typed parameters for sun color, horizon transition intervals, sun intensity, moon color, moon intensity, and moon phase response.
- `WaterStyleProfile` defines the stable shader policy id and bounded L8 water strengths. Its
  values are embedded in generated shaders; only the id is uploaded per frame through the existing
  `materialContract.w` field.

---

## 7. Style-Aware Celestial Lighting (STYLE-1)

### 7.1 Decoupled Chromaticity vs Energy
Light chromaticity (color character) and directional light energy are strictly separated:
1. **Chromaticity Interpolation**:
   $$\text{warmth} = 1.0 - \text{smoothstep}(\text{minAlt}, \text{maxAlt}, \text{altitude})$$
   $$\mathbf{C}_{\text{raw}} = \text{lerp}(\mathbf{C}_{\text{high}}, \mathbf{C}_{\text{horizon}}, \text{warmth})$$
   $$\mathbf{C}_{\text{sun}} = \mathbf{C}_{\text{raw}} \cdot \frac{Y(\mathbf{C}_{\text{high}})}{Y(\mathbf{C}_{\text{raw}})}$$
   where $Y(\mathbf{C}) = 0.2126R + 0.7152G + 0.0722B$ is relative scene-linear luminance. This guarantees that sunset hue changes do not cause accidental luminance loss.
2. **Directional Energy**:
   $$I_{\text{sun}} = I_{\text{base}} \cdot \text{horizon}(\text{alt}) \cdot T_{\text{weather}} \cdot T_{\text{medium}}$$
   $$I_{\text{moon}} = I_{\text{base}} \cdot (\text{floor} + \text{response} \cdot \text{phase}) \cdot \text{horizon}(\text{alt}) \cdot T_{\text{weather}} \cdot T_{\text{medium}}$$

### 7.2 Active Style Parameter Matrix

| Parameter | VANILLA | NATURAL | REALISM |
| :--- | :--- | :--- | :--- |
| **Normal Sun Color** | `(1.00, 0.93, 0.78)` | `(1.00, 0.98, 0.92)` | `(1.00, 0.995, 0.97)` |
| **Horizon Sun Color** | `(1.00, 0.93, 0.78)` | `(1.00, 0.50, 0.18)` | `(1.00, 0.32, 0.07)` |
| **Sun Altitude Interval** | `[0.015, 0.16]` | `[0.035, 0.42]` | `[0.020, 0.55]` |
| **Sun Intensity Scale** | `1.65` | `1.65` | `1.65` |
| **Moon Color** | `(0.50, 0.62, 0.90)` | `(0.72, 0.80, 1.00)` | `(0.90, 0.94, 1.00)` |
| **Moon Intensity Scale** | `0.13` | `0.10` | `0.085` |
| **Moon Phase Floor** | `0.18` (18%) | `0.06` (6%) | `0.00` (0%) |
| **Moon Phase Response** | `0.82` (100% full) | `0.94` (100% full) | `1.00` (100% full) |

### 7.3 Sky & Ambient Invariance
Directional celestial lighting does **not** alter sky irradiance or ambient irradiance:
- At new moon in `REALISM`, directional moonlight reaches 0.0, but sky and ambient terms remain intact, keeping night terrain playable and readable.

---

## 8. Live Switching & Temporal Safety
Switching visual styles does **not** reconstruct GPU pipelines or renderer generations:
- **No Renderer Generation Rebuild**: `VisualStyle` is not part of `RendererGenerationKey`, `RendererFeatureMask`, or PSO keys.
- **No Resource Allocations**: Textures, buffers, MetalFX scalers, and shadow atlases are not recreated.
- **One-Shot Temporal Discontinuity**: Changing the active style signals `FrameState.HistoryResetReason.VISUAL_STYLE_CHANGE` via `TemporalResetEvents.signal(...)`. The temporal admission logic (`FrameSynthesisContract`) rejects cross-style history blending for that single transition frame.
- **No-Op Guard**: Re-selecting the already active style emits no reset events.

### 8.1 Water Style Matrix

| Water parameter | VANILLA | NATURAL | REALISM |
| :--- | ---: | ---: | ---: |
| Shader policy id | `0` | `1` | `2` |
| L8 water enabled | No | Yes | Yes |
| Wave strength | `0.00` | `0.22` | `1.00` |
| Reflection strength | `0.00` | `0.18` | `1.00` |
| Refraction strength | `0.00` | `0.00` | `0.55` |
| Reflection body coupling | `0.00` | `0.00` | `0.85` |
| Caustic strength | `0.00` | `0.00` | `1.00` |
| Roughness | `0.075` | `0.120` | `0.065` |
| Transmission | `0.00` | `0.00` | `0.38` |
| Optical depth | `0.00` | `0.00` | `1.15` |
| Absorption RGB | `(0, 0, 0)` | `(0, 0, 0)` | `(0.18, 0.055, 0.022)` |

`NATURAL` deliberately has zero transmission/absorption/refraction so Metallum does not replace
the vanilla biome water color. `REALISM` uses the same existing reflection sources when they are
admitted; selecting a style does not enable the default-off planar or voxel-reflection experiments.
When a coarse reflection has little confidence, its water-body energy subtraction is weighted by
that actual confidence instead of producing a dark shoreline halo.

---

## 9. Performance Contract
Water style selection adds only bounded scalar/profile selection arithmetic inside the existing L8
fragment path. It adds no frame-graph or memory work:
- 0 new render passes
- 0 new compute passes
- 0 new Metal command encoders
- 0 new persistent GPU textures / buffers
- 0 new PSO variants / shader permutations
- 0 GPU readbacks
- 0 per-frame disk I/O or heap allocations

---

## 10. Future Extension Rule
Profiles are introduced on-demand:
- Add a new typed sub-profile (e.g. `AtmosphereProfile`, `WaterProfile`, `MaterialProfile`) **only** when a concrete subsystem starts consuming style data.
- Avoid speculative empty profile types.

---

## 11. Explicit Non-Goals
The following are intentionally excluded:
- Custom Style Packs or external JSON style loaders.
- User-supplied `.metal` shader injection.
- Coupling styles to performance or quality budgets.
