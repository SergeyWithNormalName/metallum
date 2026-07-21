# T1A Motion & Reactive Infrastructure Audit (T1A Foundation)

This document establishes the architecture, boundaries, and validation contracts for the motion/reactive vector generation path in the Metallum rendering backend.

## 1. Stage T1A Foundation Scope & Limitations

The scope of this stage is constrained to **T1A Foundation**:
1. **Camera & Static Terrain**: All valid-depth pixels receive camera-induced motion.
2. **Dynamic Geometry & Entities**: Dynamic geometry (including rigid entities, animated players, and first-person held items) is not identified or supported in this stage, receiving the same camera-induced motion. Precise dynamic tracking is deferred.
3. **Disocclusion Policy**: Limited to **out-of-frame invalidation** (offscreen NDC reprojection check). In-frame depth-mismatch disocclusion checks are deferred.

---

## 2. Existing Render Roles and Draw Paths

### 2.1. Opaque and Translucent Terrain (Static World Geometry)
- **Role**: Blocks, water, foliage, and structural geometry.
- **Draw Path**: Rendered via Sodium's optimized chunk section meshes.
- **Transforms**: Uniform `viewRotationMatrix` and `projectionMatrix` supplied per frame. Static vertices do not have per-vertex dynamic model matrices.

### 2.2. Rigid Entities
- **Role**: Mobs, players, dropped items, item frames.
- **Draw Path**: Executed via entity rendering pipelines (`executeSolid`, `executeTranslucent`).
- **Transforms**: Model matrices interpolated per frame based on partial ticks.

### 2.3. First-Person Held Item (Hand)
- **Role**: Hand and active item.
- **Draw Path**: Rendered via `GameRenderer.renderItemInHand`.
- **Transforms**: Rendered with custom bobbing and sway matrices, independent of the main camera.

---

## 3. MetalFX Motion Vector Convention

The motion vectors must map pixel displacement from the **current** frame to the **previous** frame:
1. **Units**: Render-resolution pixels.
2. **Direction**:
   - **X**: Positive is displacement to the right (+X screen space).
   - **Y**: Positive is displacement down (+Y screen space / top-left origin).
3. **Reprojection Math**:
   Given current NDC coordinate $P_c$ and previous NDC coordinate $P_p$:
   \[V_x = (P_{p.x} - P_{c.x}) \times \frac{Width}{2}\]
   \[V_y = -(P_{p.y} - P_{c.y}) \times \frac{Height}{2}\]

---

## 4. Reset & Out-of-Frame Invalidation Policy

### 4.1. Global Resets
A full history reset (writing `reactive = 1.0` and `motion = (0,0)`) is triggered by:
- `FIRST_FRAME`, `RESIZE`, `TELEPORT`, `DIMENSION_CHANGE`, `WORLD_LOAD_UNLOAD`, `CAMERA_CUT`, and `FOV_PROJECTION_CHANGE`.

### 4.2. Out-of-Frame Invalidation
Pixels whose reprojected previous NDC coordinates fall outside the screen boundary $[-1, 1]$ are invalidated (`reactive = 1.0`, `motion = (0,0)`).

---

## 5. Zero-Cost Disabled Path

When `METALLUM_TEMPORAL_DIAGNOSTICS=0`:
- **0 allocations** of `temporal_motion_ring` and `temporal_reactive_ring`.
- **0 HDR semantic allocations** due to diagnostics (the legacy semantic mask is allocated only for Legacy HDR mode).
- **0 render passes** and **0 command encoders** encoded for diagnostics.
- **0 pipeline state objects (PSOs)** loaded or created.
- **0 additional MRT stores** (locations 1 and 2 are completely unbound and unused).
- **0 per-frame heap allocations or logging**.

---

## 6. Implemented Boundary

- [TemporalDiagnosticFrameGraph.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/framegraph/TemporalDiagnosticFrameGraph.java): declares the isolated depth, motion, and reactive resources.
- [RendererGenerationPlanner.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/renderer/RendererGenerationPlanner.java): allocates diagnostic resources only when diagnostics are enabled.
- [MetalDevice.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/MetalDevice.java), [MetalCommandEncoder.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java), and [MTLCommandBuffer.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/mtl/MTLCommandBuffer.java): submit the isolated diagnostics pass and optional debug-classification attachment.
- [MetalNativeBridge.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java), [MetallumNative.swift](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/native/MetallumNative.swift), and [MetallumTemporalDiagnostics.metal](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/metal/MetallumTemporalDiagnostics.metal): keep Java/Swift ABI aligned and implement camera/static-depth reprojection.
