# Metallum Directional World-Space God Rays Architecture & Specification

This document defines the authoritative architectural, mathematical, and geometric contract for the **Directional World-Space God Rays / Volumetric Single-Scattering System** in Metallum (Stages GOD-RAYS-0 and GOD-RAYS-0.1).

---

## 1. Scope

The Directional God Rays system provides physically motivated, world-space anchored volumetric single scattering from celestial light sources (Sun and Moon) in Minecraft on macOS with Apple Metal.

- **Primary Light Source**: Celestial directional light only (Sun/Moon), defined authoritatively by `EnvironmentDescriptor`.
- **Visibility Source**: Rasterized Cascaded Shadow Maps (CSM) rendered by `SunShadowRenderer` into working cascade depth textures (`metallumSunShadow0/1/2`).
- **Geometric Invariant**: Volumetric light shafts are anchored to 3D Minecraft world coordinates and preserve full lateral parallax under camera translation and rotation.
- **Integration Range**: Bounded view-ray marching starting at the near camera plane and terminating at either the opaque scene geometry (reconstructed from `SCENE_DEPTH_SNAPSHOT`) or a bounded volumetric far distance for sky pixels.

---

## 2. Explicit Non-Goals

The following techniques and systems are strictly excluded from the Directional God Rays architecture:

- **No Screen-Space Hacks**: No 2D radial blur from screen-space sun position, post-process streaks, screen-space brightness extrusions, or camera-fixed halo billboards.
- **No Local-Light Volumetrics**: No per-local-light (torches, lava, lanterns) raymarching or clustered volume marching.
- **No Voxel-Occupancy Approximations for Directional Shadowing**: Directional visibility must evaluate real polygon geometry in the rasterized CSM, not discrete block-voxel grids.
- **No Global Illumination (GI) or Multi-Scattering**: Only bounded single scattering from the celestial light source is evaluated.
- **No Froxel 3D Textures / LUT Tables**: No 3D froxel volume allocations; rays integrate on-demand across 2D cascade depth maps.
- **No Stochastic Path Tracing**: No random Monte Carlo ray branching.
- **No Temporal History in GR-1**: The initial visual implementation (GOD-RAYS-1) is strictly spatial-only to prove geometric correctness before considering temporal accumulation (GOD-RAYS-5).

---

## 3. Coordinate Systems & Transforms

All transformations adhere to Metallum's coordinate and matrix contracts:

1. **World Space**: Right-handed Minecraft world coordinates $(X, Y, Z)$ where $+Y$ is up.
2. **Camera-Relative World Coordinates**:
   $$\mathbf{P}_{\text{rel}} = \mathbf{P}_{\text{world}} - \mathbf{C}_{\text{cam}}$$
   where $\mathbf{C}_{\text{cam}}$ is double-precision camera position. This ensures 32-bit single-precision floating point operations retain millimeter accuracy near the player.
3. **View Space**:
   - Right-handed coordinate system.
   - Camera looks down the $-Z$ axis.
   - $+X$ points right, $+Y$ points up, $-Z$ points forward.
   - Positive view depth is $z_{\text{view}} = -\mathbf{P}_{\text{view}}.z$.
4. **Normalized Device Coordinates (NDC)**:
   - $X_{\text{ndc}} \in [-1.0, 1.0]$ (left to right)
   - $Y_{\text{ndc}} \in [-1.0, 1.0]$ (bottom to top in clip space)
   - $Z_{\text{ndc}} \in [0.0, 1.0]$ (reverse-Z: $1.0 = \text{near}$, $0.0 = \text{far}$)
5. **Texture Coordinates (UV)**:
   - $U \in [0.0, 1.0]$ (left to right)
   - $V \in [0.0, 1.0]$ (top to bottom for Metal 2D textures)

---

## 4. Depth Convention & Unprojection Proof

Metallum employs a strict **Reverse-Z** floating-point depth convention across both main scene depth and shadow depth buffers:

- **Main Scene Depth Clear Value**: `0.0` (representing infinite / far background).
- **Near Plane Clip Value**: `1.0` at $z_{\text{view}} = z_{\text{near}}$.
- **Far Plane Clip Value**: `0.0` at $z_{\text{view}} = z_{\text{far}}$.
- **Perspective Projection Matrix (Reverse-Z with $[0, 1]$ depth)**:
  $$\mathbf{M}_{\text{proj}} = \begin{pmatrix} \frac{f}{\text{aspect}} & 0 & 0 & 0 \\ 0 & f & 0 & 0 \\ 0 & 0 & \frac{z_{\text{near}}}{z_{\text{far}} - z_{\text{near}}} & \frac{z_{\text{near}} \cdot z_{\text{far}}}{z_{\text{far}} - z_{\text{near}}} \\ 0 & 0 & -1 & 0 \end{pmatrix}, \quad \text{where } f = \frac{1}{\tan(\text{fov}_y / 2)}$$
- **Depth Unprojection**:
  Given raw scene depth $d_{\text{raw}} \in [0.0, 1.0]$ sampled from `SCENE_DEPTH_SNAPSHOT`:
  $$\mathbf{P}_{\text{clip}} = \begin{pmatrix} \text{ndcX} \\ \text{ndcY} \\ d_{\text{raw}} \\ 1.0 \end{pmatrix}, \quad \mathbf{P}_{\text{view}, h} = \mathbf{M}_{\text{proj}}^{-1} \mathbf{P}_{\text{clip}}, \quad \mathbf{P}_{\text{view}} = \frac{\mathbf{P}_{\text{view}, h}.xyz}{\mathbf{P}_{\text{view}, h}.w}$$
  The distance along the view ray is $t_{\text{hit}} = \|\mathbf{P}_{\text{view}}\|$.
- **Sky Pixel Handling**:
  When $d_{\text{raw}} = 0.0$ (no geometry drawn, clear value), $t_{\text{hit}}$ is unconstrained by scene geometry and is clamped to $\min(t_{\text{max, volumetric}}, \text{csmMaxDistance})$.

---

## 5. World-Space View-Ray Reconstruction

For each pixel $(x, y)$ in the volumetric low-resolution rendering target $(W_{\text{low}}, H_{\text{low}})$:

1. **Compute NDC Ray**:
   $$\text{uv}_x = \frac{x + 0.5}{W_{\text{low}}}, \quad \text{uv}_y = \frac{y + 0.5}{H_{\text{low}}}$$
   $$\text{ndcX} = \text{uv}_x \cdot 2.0 - 1.0, \quad \text{ndcY} = 1.0 - \text{uv}_y \cdot 2.0$$
2. **Reconstruct View Ray Direction**:
   $$\mathbf{P}_{\text{near, clip}} = (\text{ndcX}, \text{ndcY}, 1.0, 1.0)^T$$
   $$\mathbf{v}_{\text{near}} = \frac{(\mathbf{M}_{\text{proj}}^{-1} \mathbf{P}_{\text{near, clip}}).xyz}{(\mathbf{M}_{\text{proj}}^{-1} \mathbf{P}_{\text{near, clip}}).w}$$
   $$\vec{\mathbf{D}}_{\text{view}} = \frac{\mathbf{v}_{\text{near}}}{\|\mathbf{v}_{\text{near}}\|}$$
3. **Determine Ray Bounds $[t_{\text{start}}, t_{\text{end}}]$**:
   - $t_{\text{start}} = z_{\text{near}}$
   - Sample scene depth $d_{\text{scene}}$ at $(\text{uv}_x, \text{uv}_y)$ from `SCENE_DEPTH_SNAPSHOT`.
   - If $d_{\text{scene}} > 0.0$: $t_{\text{scene}} = \|\mathbf{P}_{\text{view}}(d_{\text{scene}})\|$, then $t_{\text{end}} = \min(t_{\text{scene}}, t_{\text{max, volumetric}})$.
   - If $d_{\text{scene}} == 0.0$: $t_{\text{end}} = \min(t_{\text{max, volumetric}}, \text{csmMaxDistance})$.
4. **Sample Points Generation**:
   For sample index $i \in [0, N-1]$:
   $$t_i = t_{\text{start}} + \frac{i + \xi}{N} (t_{\text{end}} - t_{\text{start}})$$
   $$\mathbf{P}_{\text{view}}(t_i) = \vec{\mathbf{D}}_{\text{view}} \cdot t_i$$
   where $\xi \in [0, 1)$ is jitter (fixed at $0.5$ for deterministic GR-1; blue-noise in later stages).

---

## 6. CSM Visibility & Shadow Compare Contract

### 6.1 Cascade Selection & Monotonicity
Let $z = -\mathbf{P}_{\text{view}}(t_i).z$.
The active cascade $k \in \{0, \dots, \text{cascadeCount}-1\}$ is selected via:
$$k = \begin{cases} 0 & z \le \text{splits}[0] \\ 1 & z \le \text{splits}[1] \\ 2 & z \le \text{splits}[2] \end{cases}$$
If $z > \text{splits}[\text{cascadeCount}-1]$, the point is outside CSM coverage and **fails closed** ($\text{visibility} = 0.0$).

### 6.2 Shadow Transform & Texture UV
Transform view-space position $\mathbf{P}_{\text{view}}(t_i)$ to shadow clip space using the authoritative matrix from `EnvironmentShadowBindingAbi`:
$$\mathbf{P}_{\text{shadow, clip}} = \mathbf{M}_{\text{shadowFromView}}[k] \cdot \begin{pmatrix} \mathbf{P}_{\text{view}}(t_i) \\ 1.0 \end{pmatrix}$$
$$\mathbf{P}_{\text{shadow, ndc}} = \frac{\mathbf{P}_{\text{shadow, clip}}.xyz}{\mathbf{P}_{\text{shadow, clip}}.w}$$
$$\text{uv}_{\text{shadow}} = \mathbf{P}_{\text{shadow, ndc}}.xy \cdot 0.5 + 0.5, \quad z_{\text{shadow}} = \mathbf{P}_{\text{shadow, ndc}}.z$$

- **Fail-Closed Boundary Guard**:
  If $\text{uv}_{\text{shadow}}.x \notin [0, 1]$ or $\text{uv}_{\text{shadow}}.y \notin [0, 1]$ or $z_{\text{shadow}} \notin [0, 1]$, return $0.0$.

### 6.3 Reverse-Z Depth Comparison
The CSM depth map stores caster depth in reverse-Z ($1.0 = \text{near light}$, $0.0 = \text{far from light}$).
Comparison sampler evaluates `compare_func::greater_equal`:
$$\text{visibility}(t_i) = \begin{cases} 1.0 & \text{if } z_{\text{shadow}} \ge d_{\text{map}}(\text{uv}_{\text{shadow}}) \\ 0.0 & \text{if } z_{\text{shadow}} < d_{\text{map}}(\text{uv}_{\text{shadow}}) \end{cases}$$

### 6.4 Sampling Modes & Filtering
- **GR-1 Starting Baseline**: `RAW_COMPARE` (1 point comparison per ray sample). This maximizes edge sharpness and isolates geometry without blur.
- **2x2 Bounded Filter**: A 4-tap bilinear PCF comparison. Smooths sub-texel staircasing while preserving thin sub-block silhouettes.
- **Surface 3x3 PCF**: Not recommended for volumetrics because wide 9-tap kernel blurs sub-block aperture boundaries in air and triples texture fetch bandwidth.

### 6.5 Air Bias Contract & Refinement Policy
- **Receiver Normal Bias**: Strictly **0.0** (points in air have no surface normal; any normal offset distorts the view ray).
- **Receiver Depth Bias**: **0.0** is the authoritative geometry-proof baseline for GR-1. Points in open air do not have self-shadowing polygons.
- **Refinement Policy**: Non-zero volumetric depth bias is not permanently forbidden. If GR-1 empirical testing reveals precision-induced false occlusion near caster surfaces, a minimal bounded depth epsilon ($\epsilon < 10^{-4}$) may be evaluated in a later stage, accompanied by visual evidence and regression tests on sub-block apertures.

### 6.6 Cascade Overlap Blending
In the transition region between cascade $k$ and cascade $k+1$, blend smoothly using `SunShadowLayout.cascadeBlendStart`:
$$\alpha = \text{smoothstep}(\text{blendStart}[k], \text{splits}[k], z)$$
$$\text{visibility} = (1.0 - \alpha) \cdot \text{vis}_k + \alpha \cdot \text{vis}_{k+1}$$
For the outermost cascade boundary, $\text{visibility}$ fades to $0.0$ at the far boundary.

---

## 7. Camera-Translation Invariance Proof

For any fixed world point $\mathbf{P}_{\text{world}}$, moving the camera from $\mathbf{C}_A$ to $\mathbf{C}_B = \mathbf{C}_A + \vec{\boldsymbol{\Delta}}$ produces camera-relative coordinate:
$$\mathbf{P}_{\text{rel}, B} = \mathbf{P}_{\text{world}} - \mathbf{C}_B = \mathbf{P}_{\text{rel}, A} - \vec{\boldsymbol{\Delta}}$$
Under `SunShadowFrame.reprojectCached`, the static shadow matrix is rebased via:
$$\mathbf{M}_{\text{shadowFromWorldRel}, B} = \mathbf{M}_{\text{shadowFromWorldRel}, A} \cdot \text{translate}(\vec{\boldsymbol{\Delta}})$$
Therefore:
$$\mathbf{M}_{\text{shadowFromWorldRel}, B} \cdot \mathbf{P}_{\text{rel}, B} = \mathbf{M}_{\text{shadowFromWorldRel}, A} \cdot (\mathbf{P}_{\text{rel}, B} + \vec{\boldsymbol{\Delta}}) = \mathbf{M}_{\text{shadowFromWorldRel}, A} \cdot \mathbf{P}_{\text{rel}, A}$$
The projected shadow UV and depth are identical down to floating-point precision, guaranteeing that shafts remain solidly locked to world geometry during translation.

---

## 8. Sub-Block Aperture Feasibility Matrix

| Aperture Case | Dimensions / Shape | Texels in Cascade 0 (Balanced) | Feasibility Classification | Rationale |
| :--- | :--- | :--- | :--- | :--- |
| **A: 2x2 Block** | $2.0\text{ m} \times 2.0\text{ m}$ | $\approx 35 - 50$ texels wide | **`EXPECTED_RELIABLE`** | Well above Nyquist limit; fully resolved across all cascades. |
| **B: 1x1 Block** | $1.0\text{ m} \times 1.0\text{ m}$ | $\approx 17 - 25$ texels wide | **`EXPECTED_RELIABLE`** | Easily resolved in Cascade 0 & 1; crisp rectangular beam. |
| **C: Slab Opening** | $1.0\text{ m} \times 0.5\text{ m}$ | $\approx 17 \times 8\text{--}12$ texels | **`EXPECTED_RELIABLE`** | Sodium chunk mesh includes exact slab polygon quads; $8-12$ texels provides sharp edge definition in Cascade 0. |
| **D: Stair Opening** | L-shaped stepped profile | $\approx 17$ texels with step | **`EXPECTED_RELIABLE`** | Sodium triangulates stair corner/step geometry directly into chunk buffers; steps are resolved in CSM. |
| **E: ~0.25 Block Scale** | $0.25\text{ m}$ (fences, iron bars) | $\approx 4 - 6$ texels in Cascade 0 | **`RESOLUTION_DEPENDENT`** | Fully resolvable close to camera in Cascade 0 ($4-6$ texels), but becomes sub-texel / aliased in Cascades 1 & 2 ($0.5-1.5$ texels). |

---

## 9. Pass Architecture: Low-Res Render Pass vs Compute

### 9.1 Comparison & Architectural Evaluation

| Criteria | Option A: Compute Pass | Option B: Low-Res Render Pass (Selected) |
| :--- | :--- | :--- |
| **Pass Structure** | Compute kernel dispatch (`MTLComputeCommandEncoder`) | Fullscreen triangle fragment render (`MTLRenderCommandEncoder`) |
| **Java Changes** | New compute encoder setup & pipeline dispatch logic | Standard `createTrackedTextureTarget` & render pass |
| **Swift / Bridge Changes**| New compute downcall signatures for shadow binding | **Zero changes** — reuses standard render encoder downcalls |
| **CSM Resource Binding** | Requires new compute buffer/texture binding plumbing | **Reuses existing** `SunShadowGpuResources.bind(encoder, inFlightSlot)` |
| **Comparison Sampler** | Requires separate compute sampler binding | **Reuses existing** `comparisonSampler` (`GreaterEqual`, Linear) |
| **Target Output** | `texture2d<float, access::write>` | `r16_float` color attachment (`AttachmentRole.COLOR`) |
| **Number of Encoders** | 1 compute encoder | 1 render encoder |
| **RAW_COMPARE Semantics**| Preserved | Preserved |
| **Scene Depth Sampling** | Direct texture read | Direct texture read (`SCENE_DEPTH_SNAPSHOT`) |
| **Sample Integration** | Natural compute loop | Natural fragment shader loop |
| **Complexity Level** | High (custom ABI / plumbing) | **Minimum Complexity (100% aligned with engine)** |

**Decision**: **Option B (Low-Res Render Pass)** is selected as the minimum-complexity, maximum-reliability architecture for GR-1 and future production single scattering.

---

## 10. Frame Graph Integration, Resource Lifetime & Persistence

### 10.1 Pass Order
```
1. SUN_SHADOW               [Existing: Renders working CSM into metallumSunShadow0/1/2]
2. WORLD_RENDER             [Existing: Renders world into MAIN_COLOR & MAIN_DEPTH]
3. CAPTURE_SCENE_DEPTH      [Existing: Blits MAIN_DEPTH -> SCENE_DEPTH_SNAPSHOT]
4. GOD_RAY_SCATTER          [PROPOSED GR-1: Low-res render pass writes god_ray_scatter (R16F)]
5. HDR_EXTRACT / BLOOM      [Existing: Reads MAIN_COLOR & god_ray_scatter -> builds histogram/bloom]
6. HDR_WORLD_RECONSTRUCTION [Existing: Tone-maps & composites scatter radiance into HDR scene-linear world]
7. METALFX_SPATIAL          [Existing: Upscales composite from render_extent to display_extent if active]
8. UI_RENDER                [Existing: Draws GUI / HUD at display_extent onto SDR_UI_COLOR]
9. PRESENT                  [Existing: Presents final composite to CAMetalLayer drawable]
```

### 10.2 Corrected Resource Specification
- **Resource ID**: `god_ray_scatter`
- **Resource Shape**: `TEXTURE`, format `r16_float`, extent `half_render_extent` (or `quarter_render_extent` for Performance mode).
- **Persistence Class**: **`FrameGraph.PersistenceClass.SIZE_GENERATION`** (Allocated per render extent / generation, safely retained across frames with in-flight submissions).
- **Lifetime**: **`FrameGraph.Lifetime.closed(GOD_RAY_SCATTER, HDR_WORLD_RECONSTRUCTION)`**.

---

## 11. HDR / Exposure / Bloom Integration & Radiance Contract

### 11.1 Option B Architecture (Zero Extra Fullscreen Composite Passes)
1. **Low-Res Scatter Generation**:
   `GOD_RAY_SCATTER` computes scalar integrated optical scattering $S \ge 0$ into `god_ray_scatter` (`r16_float`).
2. **Exposure & Bloom Participation (`HDR_EXTRACT`)**:
   `HDR_EXTRACT` samples `god_ray_scatter` alongside `MAIN_COLOR` when downsampling $4\times 4$ blocks:
   $$\mathbf{C}_{\text{total}} = \mathbf{C}_{\text{scene}} + S \cdot \mathbf{L}_{\text{directional}}$$
   $$Y = \text{luminance}(\mathbf{C}_{\text{total}})$$
   The resulting luminance $Y$ is accumulated into the exposure histogram. Bright light shafts naturally adapt exposure and contribute to natural bloom without altering `MAIN_COLOR`.
3. **World Reconstruction & Composition (`HDR_WORLD_RECONSTRUCTION`)**:
   During tone-mapping, `HDR_WORLD_RECONSTRUCTION` samples `god_ray_scatter` and adds $S \cdot \mathbf{L}_{\text{directional}}$ to `sceneRadiance` before exposure attenuation and display headroom mapping.
4. **Authoritative Radiance Propagation**:
   Scene-linear `directionalRadiance` ($\mathbf{L}_{\text{directional}}$) is computed authoritatively by `EnvironmentDescriptor` / `EnvironmentShadowBindingAbi` and passed directly in uniform buffers to `HDR_EXTRACT` and `HDR_WORLD_RECONSTRUCTION`. Zero duplicate lighting policy is introduced.

---

## 12. Cross-Graph CSM Lifetime Proof

The working CSM depth textures are guaranteed to be valid, immutable, and resident during `GOD_RAY_SCATTER`:

1. **Ownership**: `SunShadowGpuResources` is owned persistently by `MetalDevice` for the active `plannedLightingGeneration`.
2. **Readiness**: `SunShadowRenderer.render` executes at the start of the frame, copies static terrain, renders dynamic features into `workingCascades`, and marks `renderedSubmitIndex = submitIndex`.
3. **Stationary Residency**: `workingCascades` reside in GPU memory for the duration of the frame. They are not cleared or reused until the next frame's shadow pass.
4. **Submit Index Alignment**: `device.currentSubmitIndex()` and `inFlightSlot` are identical across `WORLD_RENDER`, `CAPTURE_SCENE_DEPTH`, and `GOD_RAY_SCATTER` within the same Metal command buffer on the single Render Thread.
5. **Zero Cross-Graph Synchronization Hazards**: All passes record sequentially into the same render thread command stream; Metal handles render-pass store to texture-read transitions automatically.

---

## 13. Subsystem Ownership Boundaries

- **`LightingPreset` (`PERFORMANCE`, `BALANCED`, `ULTRA`)**:
  - Owns computational budget: sample count per ray ($8 / 12 / 16$) and volumetric buffer resolution ratio (quarter vs half render extent).
  - Never controls visual color or atmospheric fog styling.
- **`VisualStyle` (`VANILLA`, `NATURAL`, `REALISM`)**:
  - Owns artistic celestial chromaticity and phase asymmetry $g$.
  - Never alters sample counts, buffer resolutions, or GPU memory budgets.
- **`AtmosphereProfile` (ATMOSPHERE-1)**:
  - Owns air optical density $\rho$, baseline aerial perspective, and weather attenuation.
- **`Temporal Scaling` (MetalFX / DRS)**:
  - God ray scattering executes strictly at `render_extent`. Spatial/Temporal upscaling operates downstream on the composited scene.

---

## 14. Performance Work Model (Corrected Arithmetic Facts)

The table below summarizes exact arithmetic operation counts and texture memory requirements. No assumptions are made regarding GPU cache residency or execution times.

| Render Extent | Volumetric Resolution | Low-Res Extent | Low-Res Pixels | Samples/Ray | CSM Compares / Frame | R16Float Memory |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1512 x 982** | Half ($1/4$ px) | 756 x 491 | 371,196 | 8 (Perf) | 2,969,568 | 0.71 MiB |
| **1512 x 982** | Half ($1/4$ px) | 756 x 491 | 371,196 | 12 (Bal) | 4,454,352 | 0.71 MiB |
| **1512 x 982** | Half ($1/4$ px) | 756 x 491 | 371,196 | 16 (Ultra) | 5,939,136 | 0.71 MiB |
| **1920 x 1200** | Half ($1/4$ px) | 960 x 600 | 576,000 | 8 (Perf) | 4,608,000 | 1.10 MiB |
| **1920 x 1200** | Half ($1/4$ px) | 960 x 600 | 576,000 | 12 (Bal) | 6,912,000 | 1.10 MiB |
| **1920 x 1200** | Half ($1/4$ px) | 960 x 600 | 576,000 | 16 (Ultra) | 9,216,000 | 1.10 MiB |
| **3024 x 1964** | Half ($1/4$ px) | 1512 x 982 | 1,484,784 | 8 (Perf) | 11,878,272 | 2.83 MiB |
| **3024 x 1964** | Half ($1/4$ px) | 1512 x 982 | 1,484,784 | 12 (Bal) | 17,817,408 | 2.83 MiB |
| **3024 x 1964** | Half ($1/4$ px) | 1512 x 982 | 1,484,784 | 16 (Ultra) | **23,756,544** | 2.83 MiB |
| **3024 x 1964** | Quarter ($1/16$ px)| 756 x 491 | 371,196 | 8 (Perf) | 2,969,568 | 0.71 MiB |

- **Empirical Measurements**: Cache hit rates, texture memory bandwidth, and GPU frame execution milliseconds remain **NOT MEASURED** and will be benchmarked via Instruments and Metal System Trace during later performance optimization stages.

---

## 15. GOD-RAYS-1 Visual Acceptance Matrix

| Scene / Fixture | Time / Sun Pitch | Aperture Geometry | Camera Angle & Distance | Expected GR-1 Debug Output |
| :--- | :--- | :--- | :--- | :--- |
| **Aperture 2x2** | 6000 (Noon Sun, $45^\circ$) | 2x2 hole in 3-thick wall | Profile view (8m away, looking perpendicular to sun beam) | Crisp bright shaft behind 2x2 opening; black (0.0) in shadowed shadow cone. |
| **Aperture 1x1** | 6000 (Noon Sun, $45^\circ$) | 1x1 hole in 3-thick wall | Profile view (6m away) | Crisp 1-block cross-section beam; lateral parallax under camera translation. |
| **Slab 1x0.5** | 6000 (Noon Sun, $45^\circ$) | 1x0.5 slot formed by bottom stone slab | Profile & front view (5m away) | Half-height rectangular light sheet matching slab aperture geometry. |
| **Stair Slot** | 6000 (Noon Sun, $45^\circ$) | L-shaped opening formed by stone stairs | Profile view (5m away) | Stepped cross-section beam matching stair step silhouette. |
| **Control: SEALED**| 6000 (Noon Sun, $45^\circ$) | Fully sealed solid wall | Inside chamber behind wall | Solid black (0.0); zero light leak through solid geometry. |
| **Control: OPEN** | 6000 (Noon Sun, $45^\circ$) | No wall / open sky | Open field | Uniform white (1.0) / constant unshadowed volume. |

---

## 16. Identified Risks & Mitigation

1. **Sub-Sampling Undersampling Aliasing**: Low sample counts (e.g. 8-12) can produce step-slicing banding on grazing rays.
   - *Mitigation in GR-2*: Jitter ray start offsets with screen-space Bayer / blue noise and apply a 3x3 depth-aware bilateral spatial filter.
2. **Sub-Block Silhouette Aliasing at Far Distances**: $0.25$-block features (fences, bars) are sub-texel beyond Cascade 0 ($> 15\text{m}$).
   - *Mitigation*: Cascade 0 covers the near camera field where sub-block apertures are viewed; distant shafts naturally merge into broader beams.
3. **Grazing Sun Angles**: When sun is near the horizon ($\text{altitude} < 0.035$), CSM cascade extrusion bounds must contain long shadow casters.
   - *Mitigation*: Handled by existing `casterExtrusion = max(24.0, maximumDistance)` in `SunShadowFrame`.

---

## 17. GOD-RAYS-1 Implementation & Verification

### 17.1 Dedicated Sampler & Pipeline Separation
- **Dedicated God-Ray Sampler**: Created in `SunShadowGpuResources` with `FilterMode.NEAREST`, `AddressMode.CLAMP_TO_EDGE`, and `MTLCompareFunction.GreaterEqual`.
- **Ordinary Surface Shadow Sampler**: Retains `FilterMode.LINEAR`, `AddressMode.CLAMP_TO_EDGE`, and `MTLCompareFunction.GreaterEqual` completely untouched for hardware 2x2 PCF filtering.
- **Pass Execution**: `GodRayVisibilityRenderer` encodes a low-resolution fullscreen render pass (`metallum_hdr_vs` + `metallum_god_ray_visibility_fs`) immediately after `SCENE_DEPTH_SNAPSHOT`.
- **Output Target**: `godRayVisibility` texture target with format `GpuFormat.R16_FLOAT`, extent `((width + 1) / 2, (height + 1) / 2)`, managed under `FrameGraph.PersistenceClass.SIZE_GENERATION`.

### 17.2 Diagnostic Activation
- **Environment Variable**: `METALLUM_BENCHMARK_GOD_RAY_VISIBILITY_DEBUG=1`
- **System Property**: `-Dmetallum.benchmark.god.ray.visibility.debug=true`
- **Zero Overhead**: When inactive, no low-res texture is allocated, no pipeline is encoded, and normal production rendering is 100% byte-identical.

### 17.3 Deterministic Test Verification
- Run contract unit tests:
  ```bash
  ./gradlew godRayWorldSpaceUnitTest --console=plain
  ```
- Run full codebase check:
  ```bash
  ./gradlew check --console=plain
  ```

---

## 18. GOD-RAYS-1.1 Diagnostic & Unambiguous Shaft Proofs

### 18.1 Root Cause of Previously Ambiguous Screenshots
In production `RenderContractMode.METALLUM` with 1:1 scale (no temporal upscaler), `legacyHdrScene` and `temporalScene` flags were `false`. Consequently, `MetalDevice.captureHdrScene` did not allocate or copy the scene depth snapshot (`hdrSceneDepthSnapshot == null`), causing the God-Ray visibility pass to skip execution. Furthermore, grayscale mapping in dark/bright environments could resemble surface lighting.

### 18.2 Multi-Mode Diagnostics & False-Color Spectrum
- **Mode 1: `CONSTANT`** (`-Dmetallum.benchmark.god.ray.debug.mode=constant`):
  Outputs full-screen solid MAGENTA `(1.0, 0.0, 1.0)` to conclusively prove the debug presentation path reaches the screen.
- **Mode 2: `DEPTH`** (`-Dmetallum.benchmark.god.ray.debug.mode=depth`):
  Visualizes geometry-aligned linearized scene depth (near = bright white, far = dark gray, sky = dark navy blue).
- **Mode 3: `VISIBILITY`** (default, `-Dmetallum.benchmark.god.ray.debug.mode=visibility`):
  Outputs false-color spectrum scalar visibility:
  - $V = 0.0 \implies \text{RED } (1, 0, 0)$ (fully shadowed air)
  - $V = 0.25 \implies \text{YELLOW } (1, 1, 0)$
  - $V = 0.5 \implies \text{GREEN } (0, 1, 0)$ (half-lit air)
  - $V = 0.75 \implies \text{CYAN } (0, 1, 1)$
  - $V = 1.0 \implies \text{BLUE } (0, 0, 1)$ (fully sun-visible air)
- **Mode 4: `SHAFT_MASK`** (`-Dmetallum.benchmark.god.ray.debug.mode=shaft_mask`):
  Outputs positive false-color only for rays exhibiting mixed visibility (both illuminated and shadowed segments along the view ray).

### 18.3 Metrics & Sample Count Override
- **Metrics**: `LIT_FRACTION` (default), `MEAN_VISIBILITY`, `SHAFT_MASK` via `-Dmetallum.benchmark.god.ray.metric=...`.
- **Sample Count**: Overridden via `-Dmetallum.benchmark.god.ray.sample.count=N` ($N \in [1, 128]$, default 12).

---

## 19. GOD-RAYS-1.2 Spatial Stability Gate & Integrator Analysis

### 19.1 Spatial Instability Root Cause Analysis
Manual user testing in GOD-RAYS-1.1 revealed strong visual oscillations and perceived shaft movement during camera walking and view bobbing. The root cause analysis identified five compounding mechanisms:

1. **$1/N$ Quantization**: For discrete sample counts (e.g. $N=12$), the discrete counter $\text{litCount}/N$ jumps in $8.33\%$ increments ($0.0833$).
2. **Normalized $t$-Lattice Shifting**: In baseline `CURRENT_NORMALIZED`, sample distances are computed as $t_i = \text{mix}(tStart, tEnd, (i+0.5)/N)$. When the ray hits an opaque block at changing distance $tEnd$, **every single sample $t_i$ slides along the ray simultaneously**, causing artificial movement of stationary world shafts.
3. **Discrete False-Color Banding**: False-color diagnostic gradients map tiny $0.05$ visibility fluctuations across contrasting primary hues (Red $\to$ Yellow $\to$ Green $\to$ Cyan $\to$ Blue), severely exaggerating minor spatial noise.
4. **View Bobbing Screen-Space Projection Shift**: Minecraft's view bobbing shifts the camera translation and tilt periodically during walking, translating the screen-space ray lattice across fixed world shadow geometry.
5. **Low-Resolution Bilinear Filtering**: Bilinear reconstruction of half-resolution visibility targets at steep occlusion edges causes sub-pixel edge crawling.

### 19.2 Continuous Neutral Stability Mode (`STABILITY`)
To eliminate false-color distortion and $1/N$ quantization exaggeration during stability assessment:
- **Mode 6: `STABILITY`** (`-Dmetallum.benchmark.god.ray.debug.mode=stability` or `-Dmetallum.benchmark.god.ray.debug.mode=grayscale`):
  Uses a continuous neutral grayscale transfer function: $V \in [0.0, 1.0] \implies \text{float3}(V, V, V)$ without threshold buckets or contrasting color jumps.

### 19.3 Evaluated Spatial Integrator Candidates

#### Strategy 0: `CURRENT_NORMALIZED`
- **Formula**: $t_i = \text{mix}(tStart, tEnd, (i + 0.5) / N)$.
- **Characteristics**: Ray-normalized. Suffers from lattice sliding when $tEnd$ changes.

#### Strategy 1: `WORLD_FIXED_STEP`
- **Formula**: Spaced by fixed world distance $\Delta s$ (e.g. $0.5\text{m}$) with world-anchored phase:
  $$s_0 = \mathbf{C}_{\text{world}} \cdot \vec{\mathbf{D}}_{\text{world}}$$
  $$k_{\text{first}} = \left\lceil \frac{s_0 + tStart}{\Delta s} \right\rceil, \quad t_{\text{first}} = k_{\text{first}} \cdot \Delta s - s_0$$
  $$t_i = t_{\text{first}} + i \cdot \Delta s$$
- **Invariance Proof**: If camera moves along the ray by $\delta$, the new world sample positions $\mathbf{P}'_i = \mathbf{P}_i$ remain **bit-exact invariant in 3D world space**. Changing $tEnd$ never shifts existing earlier samples.

#### Strategy 2: `WORLD_FIXED_STEP_REFINED`
- **Formula**: World-fixed step lattice augmented with bounded 3-step binary transition search ($\Delta s / 8$ resolution) and narrow-aperture midpoint probes:
  $$V = \frac{\sum L_{\text{lit}}}{tEnd - tStart}$$
- **Characteristics**: Continuous lit-length integration eliminates discrete $1/N$ jumps as shadow boundaries move across the view ray. Midpoint probing preserves sub-block ($0.5$-block) sunbeams.

## 20. GOD-RAYS-2.0 World-Space Froxel Foundation

### 20.1 Purpose & Architectural Paradigm Shift
Screen-space ray integration (GOD-RAYS-1.x) evaluates visibility along 2D perspective view rays originating from the camera eye point. While world-anchored phase formulas stabilize 1D samples along individual rays, dynamic camera rotation and lateral strafing cause the perspective ray fan to sweep across 3D shadow boundaries, creating view-dependent discretization artifacts.

**GOD-RAYS-2.0** replaces screen-space raymarching with a **True World-Space Volumetric Froxel Grid**:
- The volumetric visibility field is evaluated and stored at **stationary 3D world coordinates** in world space.
- Camera motion does **not** change the lighting volume — it only changes the perspective projection through which the static 3D volume is sampled.
- The sunlight shaft behaves like a physical 3D object in the Minecraft world, identical to CSM shadow cascades and voxel blocks.

### 20.2 Froxel Grid Specification
- **Grid Resolution**: $64 \times 36 \times 32$ cells ($73,728$ voxels).
- **Voxel Format**: `MTLPixelFormat.R16Float` ($147\text{ KB}$ VRAM footprint).
- **Tiled Atlas Storage**: $512 \times 144$ texture ($8 \times 4$ grid of $64 \times 36$ slices).
- **Default Cell Size**: $\Delta s = 0.5\text{m}$ (configurable via `-Dmetallum.benchmark.god.ray.froxel.cell.size=0.5`).
- **World Coverage**: $32.0\text{m} \times 18.0\text{m} \times 16.0\text{m}$ volume centered around the camera.

### 20.3 Discrete World-Origin Snapping
To guarantee that sub-cell camera motion produces zero shifting of the voxel lattice, the grid origin $\mathbf{O} = (O_x, O_y, O_z)$ is strictly snapped to integer multiples of $\Delta s$:
$$\mathbf{O} = \left\lfloor \frac{\mathbf{C}_{\text{world}} - 0.5 \mathbf{L}}{\Delta s} \right\rfloor \cdot \Delta s$$
Where $\mathbf{L} = (32.0, 18.0, 16.0)$ is the grid world extent.

- **Invariance Invariant**: For any camera translation $\Delta$ with $\|\Delta\| < \Delta s$, $\mathbf{O}$ is **bit-identical**.
- **Cross-Cell Movement**: When the camera traverses cell boundaries, $\mathbf{O}' - \mathbf{O} = \mathbf{k} \cdot \Delta s$ ($\mathbf{k} \in \mathbb{Z}^3$). Voxel centers land on the **exact same global discrete 3D lattice**.

### 20.4 Froxel Evaluation Pass
The froxel building pass (`metallum_god_ray_froxel_build_fs`) evaluates CSM shadow visibility for each world voxel center:
$$\mathbf{W}(i, j, k) = \mathbf{O} + \left(i + 0.5, j + 0.5, k + 0.5\right) \cdot \Delta s$$
- Uses existing SunShadow CSM cascades and reverse-Z `GreaterEqual` depth comparisons.
- Zero dependency on camera ray direction, screen pixels, or scene depth endpoints.

### 20.5 Hardware Trilinear Voxel Sampling
In the composite pass (`metallum_god_ray_visualize_fs` with `-Dmetallum.benchmark.god.ray.debug.mode=froxel`):
- For each screen ray from near plane to scene depth $tEnd$, the shader steps through world space at $\Delta s$ intervals.
- Samples the 3D froxel atlas using hardware bilinear texture filtering on slices $k_0$ and $k_1$, followed by a 1D linear `mix` along $Z$ (2 texture samples total per 3D evaluation point).
- Integrates the continuous trilinear scalar field into a stable neutral grayscale output $V \in [0.0, 1.0]$.
