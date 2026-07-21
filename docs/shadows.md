# Shadow Systems

This document describes the two shadow rendering systems implemented in **Metallum**: Cascaded Shadow Maps (CSM) for direct solar/lunar light, and Dynamic Voxel Shadows for local point/spot light sources.

---

## 1. Solar Shadows (Cascaded Shadow Maps)
Solar shadows are rendered using traditional Cascaded Shadow Maps (CSM) to cover the large open world of Minecraft.

### 1.1. Cascade Layout and Setup
As defined in [SunShadowLayout.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/renderer/SunShadowLayout.java), the camera view distance is partitioned into multiple shadow cascades (up to 4 cascades). A cascade matrix is calculated for each split, optimizing the shadow resolution near the player while maintaining shadow visibility in the distance.

### 1.2. CSM Stabilization
To prevent shadow edge shimmering and crawling artifacts (which occur as the camera moves and shadow-map pixels shift relative to world coordinate offsets), Metallum implements an integer phase alignment inside [SunShadowStabilizer.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/SunShadowStabilizer.java):
1. **Texel Alignment**: The stabilizer tracks camera translations on a grid matching the texel resolution of the shadow map:
   ```java
   residualX[cascade] = residualX[cascade] + deltaX;
   // Clamp residual delta to integer texel boundary phase offsets
   ```
2. **Phase Retention**: This locks camera movement increments to exact integer texel offsets in light space. It avoids projecting absolute camera coordinates into the rotating basis of the sun or moon, eliminating sub-pixel temporal noise.
3. **Hard Resets**: The stabilizer triggers a hard reset (wiping residuals to zero) during teleportation, world loading, dimension changes, or lighting preset swaps to prevent visual pops.

---

## 2. Local Voxel Shadows (Occupancy Clipmaps)
For local point and spot lights, traditional shadow mapping is too expensive (requires drawing the geometry multiple times from the light's perspective). Instead, Metallum implements an offline-caching **Voxel Clipmap** traversal system (L5/L6).

### 2.1. Voxel Occupancy Clipmaps (L5)
The world's solid blocks are serialized into a multi-level hierarchal grid called a Clipmap:
1. **Extraction**: Block solid states are parsed during chunk rebuilds via [SodiumVoxelSectionExtractor](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/voxel/SodiumVoxelSectionExtractor.java).
2. **GPU Storage**: Solid occupancies are stored as a 3D bit-packed occupancy texture/buffer managed by [VoxelOccupancyGpuResources.java](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/metal/render/VoxelOccupancyGpuResources.java).

### 2.2. Dynamic Voxel Shadows (L6)
For dynamic point lights, Metallum performs ray-traced shadow computation directly within the Metal shader:
1. **Ray Setup**: For each active dynamic shadow request, the GPU launches thread groups to cast rays in a 3D cubemap layout (6 faces) around the source light.
2. **DDA Traversal Algorithm**: The shader [MetallumDynamicVoxelShadow.metal](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/metal/MetallumDynamicVoxelShadow.metal#L199-L220) executes an integer-based Digital Differential Analyzer (DDA) algorithm:
   - It steps voxel-by-voxel along the ray path.
   - It references the level-specific occupancy buffer (`occupancy0`, `occupancy1`, `occupancy2`) and optical density buffer (`optical0`, `optical1`, `optical2`) to evaluate blockage.
   - If a solid voxel is hit, the ray terminates and records a shadow value.
3. **Atlas Storage**: The result is stored as a coverage mask inside a global flat shadow atlas (`atlas`).

---

## 3. Potential Bottlenecks and Known Issues

1. **Voxel Grid Invalidation**: When block modifications occur, the clipmap is modified. If updates occur rapidly (e.g. TNT explosions, fast-moving machines), clipmap rebuild queues (`VoxelDirtyQueue`) can fill up, causing temporary stuttering or outdated shadow frames.
2. **Self-Shadowing Acne**: Because voxels are discrete steps, geometry tracing near block surfaces is prone to self-shadowing rings. The DDA algorithm uses a start offset bias:
   ```metal
   const float startDistance = min(voxelSize * 0.08f, request.radius * 0.02f);
   ```
   If this bias is too small, acne appears; if too large, shadows float (Peter Panning).
3. **Shadow Atlas Bounds**: The shadow atlas has a finite capacity of dynamic requests per frame. If there are too many dynamic light sources, some will fail to allocate slots and render without shadows.
