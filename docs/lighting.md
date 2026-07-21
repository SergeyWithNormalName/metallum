# Lighting System

This document outlines the design and implementation of the **Advanced Lighting** (L3+) pipeline in **Metallum**.

---

## 1. Pipeline Overview: Clustered Deferred / Forward Lighting
Metallum introduces a unified hybrid rendering approach for lights:
- **Base HDR path (L2)**: Integrates HDR rendering and EDR compositing but continues using the default Minecraft/Sodium lightmap data structure.
- **Advanced Clustered Lighting (L3)**: Divides the camera frustum into a 3D grid of clusters (voxels in screen-space: $X \times Y \times Z$). It uploads a list of active point and spot light sources, computes which lights intersect which clusters on the GPU, and performs direct shading on materials by culling the light lists down to only those influencing the pixel's cluster.

---

## 2. Light Types and Extraction

Metallum classifies light sources into three kinds:

### 2.1. Static Section Lights
Static lights are light-emitting blocks (torches, glowstone, lava, etc.) embedded inside world chunks.
- **Extraction**: Intercepts chunk meshing via [SodiumStaticLightExtractor](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/SodiumStaticLightExtractor.java). When Sodium rebuilds a chunk section, the extractor scans the blocks for light emission levels.
- **Section Residency**: Lights are tracked inside an [AdvancedLightRegistry](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java) which allocates [AdvancedLightResidentSlot](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/AdvancedLightResidentSlot.java) trackers up to `MAX_RESIDENT_SECTIONS = 8192`.

### 2.2. Dynamic Lights
Dynamic lights represent moving objects such as entities, particles, thrown projectiles (e.g. fireballs), and held items.
- **Held Items**: Luminous items held by the player or nearby entities are tracked frame-by-frame via [CameraHeldLightTracker](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/CameraHeldLightTracker.java) and [HeldItemEmission](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/hdr/HeldItemEmission.java).
- **Capping**: Dynamic light counts are capped at `MAX_DYNAMIC_LIGHTS = 512` per frame.

### 2.3. Direct Sun/Moon Light
Managed separately as a directional light source with shadow mapping cascades.

---

## 3. Light Registration and Hard Limits

The [AdvancedLightRegistry](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java) acts as the central coordinator. It maintains the database of active light sources and enforces the following bounds:
- **Maximum Resident Sections**: 8,192 chunk sections (`MAX_RESIDENT_SECTIONS`).
- **Maximum Dynamic Lights**: 512 active dynamic lights (`MAX_DYNAMIC_LIGHTS`).
- **Maximum Total Frame Lights**: 4,096 total lights uploaded to the GPU per frame (`MAX_FRAME_LIGHTS`).

If any of these limits are exceeded or if a critical error occurs, the registry switches to a **fail-closed** mode ([AdvancedLightRegistry.java:L122](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java#L122)) where advanced lighting is disabled and the engine falls back to standard Vanilla/Sodium lightmap rendering to prevent crashes.

---

## 4. GPU Clustered Culling and Shading

### 4.1. Cluster Bounds Building
The compute shader [MetallumClusterBuild.metal](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/src/main/metal/MetallumClusterBuild.metal) runs at the start of the frame. It divides the view frustum logarithmically along the depth axis and computes the AABB (Axis-Aligned Bounding Box) for each cluster.

### 4.2. Light Culling Pass
A subsequent compute kernel checks each light source against the cluster AABBs. It builds:
1. A flat list of light indices.
2. A grid buffer where each cell contains a count and an offset into the light index list.

### 4.3. Shading Calculations
During the world rendering pass, shaders read the culling grid buffer. For each pixel:
1. Identify the cluster index based on the pixel's screen coordinates and view-space depth.
2. Retrieve the count and offset of active light sources for that cluster.
3. Loop through only the culled lights, computing Lambertian diffuse and Blinn-Phong specular contributions using HDR intensity values.
4. Add block and sky lightmaps to model ambient indirect illumination.
