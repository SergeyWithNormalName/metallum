# Metallum Cloud Shadows (CLOUD-1)

## 1. Overview & Architectural Philosophy

**Metallum Cloud Shadows** provides real-time, moving cloud shadows in Metallum Advanced Lighting on macOS (Apple Silicon).

The design follows a strict physical and architectural principle:
> **"The cloud shadow must belong to the cloud the player can see."**

Cloud shadows in Metallum do not use a synthetic arbitrary noise pattern or an independent toggle. Instead, they derive directly and automatically from Minecraft 26.2's active cloud configuration, cloud texture (`textures/environment/clouds.png`), animation state, cloud height, visual opacity, and dimension rules.

```
┌─────────────────────────────────────────────────────────────┐
│                 Minecraft 26.2 Environment                  │
│   (CloudStatus: OFF/FAST/FANCY, cloudHeight, cloudColor,    │
│    gameTime + partialTick, textures/.../clouds.png)        │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│               CloudShadowSource (CPU Pattern)                │
│   Prefiltered 2D toroidal coverage, reload generation       │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│       CloudShadowGpuResources (Periodic Preintegration)      │
│   - FLAT: 2D coverage x opacity                             │
│   - VOLUMETRIC: 8-tap slab raymarch along celestial ray     │
│   - Uploads 256x256 R8_UNORM texture (64 KB)                │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────┐
│          Advanced Direct Lighting MSL / GLSL Shader         │
│   - Evaluates receiver world position (P_world)              │
│   - If P_y >= cloudTop -> T = 1.0 (unshadowed above clouds)  │
│   - Intersects ray with cloud slab -> UV coordinate         │
│   - Exactly ONE bilinear sample: texture(metallumCloudShadow)│
│   - Smooth near-horizon fade: smoothstep(0.04, 0.10, Ly)    │
│   - Multiplies ONLY direct celestial light (sun/moon)       │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Minecraft 26.2 Cloud Mapping & State Resolution

### 2.1 Mode Mapping
Minecraft's `CloudStatus` maps deterministically to `CloudShadowMode`:
- **`CloudStatus.OFF`** $\longrightarrow$ **`CloudShadowMode.NONE`**:
  No cloud shadows. The GPU shader branches out immediately ($T = 1.0$). Zero cloud texture sampling and zero shadow work.
- **`CloudStatus.FAST`** $\longrightarrow$ **`CloudShadowMode.FLAT`**:
  Single 2D layer shadowing matching Minecraft's flat cloud plane. Attenuation is bounded up to $40\%$ ($\min T = 0.60$).
- **`CloudShadowMode.FANCY`** $\longrightarrow$ **`CloudShadowMode.VOLUMETRIC`**:
  Thickness-aware shadowing matching Minecraft's 3D extruded $4.0$-block cloud slab. Evaluates path-length density through the 3D volume with attenuation up to $55\%$ ($\min T = 0.45$).

### 2.2 Geometry & Cell Dimensions
- **Cell Size ($X/Z$)**: $12.0$ blocks per cloud texel (`CloudShadowPolicy.CELL_SIZE_BLOCKS = 12.0f`).
- **Cloud Thickness ($Y$)**: $4.0$ blocks vertical slab (`CloudShadowPolicy.CLOUD_THICKNESS_BLOCKS = 4.0f`).
- **Cloud Slab Bounds**: $[H_{\text{cloud}}, H_{\text{cloud}} + 4.0]$ where $H_{\text{cloud}}$ is `LevelRenderState.cloudHeight`.
- **World Repeat Period**: $W_{\text{world}} = W_{\text{texture}} \times 12.0 = 3072.0$ blocks, $H_{\text{world}} = H_{\text{texture}} \times 12.0 = 3072.0$ blocks (for standard 256x256 cloud textures).

### 2.3 Exact Vanilla Animation & Movement
The horizontal movement velocity in Vanilla Minecraft is:
$$\text{ticksPerGrid} = W_{\text{texture}} \times 400$$
$$\text{gameTimeOffset} = (\text{gameTime} \pmod{\text{ticksPerGrid}}) + \text{partialTick}$$
$$\text{cloudOffsetX} = \text{gameTimeOffset} \times 0.030000001\text{ blocks}$$
$$\text{cloudOffsetZ} = 3.9600000381469727\text{ blocks}$$

---

## 3. Physical Model & Shader Mathematics

### 3.1 Receiver Above Clouds
When a receiver (e.g. a player in an aircraft or standing on a high mountain) has world elevation $P_y \ge H_{\text{cloud}} + 4.0$, the cloud layer is beneath the receiver. Because celestial light comes from above ($L_y > 0$), clouds cannot cast a shadow on the receiver:
$$\text{if } P_y \ge H_{\text{top}} \implies T = 1.0$$

### 3.2 Ray Projection to Cloud Layer
For a receiver at $P = (P_x, P_y, P_z)$ with light direction $L = (L_x, L_y, L_z)$:
$$t = \frac{H_{\text{target}} - P_y}{L_y}$$
where $H_{\text{target}} = H_{\text{cloud}}$ for `FLAT` mode, or $H_{\text{cloud}} + 2.0$ (midpoint) for `VOLUMETRIC` mode.

The intersection point in world coordinates:
$$C_x = P_x + t \cdot L_x + \text{cloudOffsetX}$$
$$C_z = P_z + t \cdot L_z + \text{cloudOffsetZ}$$
$$u = \frac{C_x}{W_{\text{world}}}, \quad v = \frac{C_z}{H_{\text{world}}}$$

### 3.3 Near-Horizon Stability & Fade
When the sun or moon approaches the horizon ($L_y \to 0$), ray projection distance $t \to \infty$. To prevent infinite projection stretching, edge artifacts, and division-by-zero, Metallum applies a smooth stability fade:
$$\text{weight} = \text{smoothstep}(0.04, 0.10, L_y)$$
$$T_{\text{final}} = \text{mix}(1.0, T_{\text{sampled}}, \text{weight})$$

- When $L_y \le 0.04$: $\text{weight} = 0.0 \implies T = 1.0$ (unshadowed, completely stable).
- When $L_y \ge 0.10$: $\text{weight} = 1.0 \implies T = T_{\text{sampled}}$ (full projected shadow).
- Transition zone $(0.04 < L_y < 0.10)$: smooth $C^1$-continuous hermite interpolation.

---

## 4. Periodic Preintegration & GPU Workload

Rather than performing expensive per-pixel 3D raymarching inside `WorldOpaque` fragment shaders (which would severely hurt framerates on Apple Silicon), Metallum performs **Periodic CPU Preintegration** into a compact $256 \times 256$ `R8_UNORM` texture ($64\text{ KB}$):

1. **Prefiltered Base Coverage**:
   On resource reload, the discrete vanilla cloud texture is filtered with a 3x3 toroidal anti-aliasing kernel so cloud boundaries are soft and smooth.
2. **Volumetric Directional Preintegration**:
   When celestial direction changes by $\ge 0.57^\circ$ ($\Delta L \ge 0.01$), 8 density samples are ray-marched through the 3D volume on CPU:
   $$\Delta u = \frac{L_x}{L_y} \cdot \frac{4.0}{12.0} = \frac{L_x}{3 L_y}, \quad \Delta v = \frac{L_z}{3 L_y}$$
   $$\text{opticalDensity} = \left(\frac{1}{8} \sum_{k=0}^7 C\left(u + s_k \Delta u, v + s_k \Delta v\right)\right) \cdot \text{clamp}\left(\frac{1}{\max(L_y, 0.10)}, 1.0, 2.5\right)$$
   $$T = \text{clamp}(1.0 - 0.55 \cdot \min(\text{opticalDensity}, 1.0) \cdot \text{opacity}, 0.0, 1.0)$$
3. **GPU Render Cost**:
   The `WorldOpaque` raster pipeline performs **exactly ONE 2D texture fetch** (`layout(binding = 16) uniform sampler2D metallumCloudShadow;`).
   - Steady-state GPU cost: $\approx 0.02\text{ ms}$ on Apple M1 Pro.

---

## 5. Direct Celestial Lighting Composition

Cloud shadows represent line-of-sight occlusion of the sun and moon disc. Consequently:
- **Direct Celestial Radiance**: Multiplied by cloud transmittance:
  $$E_{\text{direct}} = L_{\text{directional}} \cdot (\text{skyOcclusion} \cdot (\vec{N} \cdot \vec{L})) \cdot \text{sunVisibility}_{\text{CSM}} \cdot T_{\text{cloud}}$$
- **Geometric Shadows (CSM)**: Compose multiplicatively ($\text{visibility} = \text{sunVisibility} \cdot T_{\text{cloud}}$). If a surface is in mountain/terrain shadow ($\text{sunVisibility} = 0$), direct light is zero regardless of cloud cover.
- **Sky Irradiance & Ambient Radiance**: **Completely independent and untouched** by cloud shadow transmittance.
- **Clustered Local Lights (Torches, Lanterns, Emissives)**: **Completely independent and untouched**.
- **Specular Highlights (GGX)**: Direct celestial specular highlights are scaled by $T_{\text{cloud}}$, while ambient sky specular reflections remain intact.

---

## 6. Verification & Test Suite

The cloud shadow implementation is verified by dedicated automated tests:
1. `cloudShadowUnitTest` (`com.metallum.client.lighting.cloud.CloudShadowTests`):
   - Mode resolution (`OFF` $\to$ `NONE`, `FAST` $\to$ `FLAT`, `FANCY` $\to$ `VOLUMETRIC`, NaN height, opacity threshold).
   - Ray intersection projection math.
   - Receiver above clouds ($P_y \ge H_{\text{top}} \implies T=1$).
   - Near-horizon smoothstep stability.
   - Periodic toroidal wrapping and negative coordinate handling.
   - Opacity scaling monotonicity and boundary enforcement.
   - Volumetric path-length thickness integration.
   - Exact animation offset validation.
   - Pattern generation and fail-open guarantees.
2. `cloudShadowLightingUnitTest` (`com.metallum.client.lighting.cloud.CloudShadowLightingIntegrationTests`):
   - Multiplicative composition with direct celestial light.
   - Absolute independence of sky and ambient irradiance.
   - Multiplicative composition with cascaded shadow maps (CSM).
   - Independence of local clustered point lights.
   - Direct specular highlight attenuation.
3. `advancedDirectLightingShaderUnitTest` (`com.metallum.client.lighting.shader.AdvancedDirectLightingShaderTests`):
   - MSL / SPIRV compilation of all 11 receiver shaders with `metallumCloudShadow` and `metallumCloudTransmittanceV1`.
   - 448-byte ABI alignment of `MetallumEnvironmentShadowV1`.
