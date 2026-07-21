# Nether Lava Stress Performance Analysis

This report documents the performance results and architectural analysis of the Native Apple Metal rendering backend (**Metallum**) in Minecraft under extreme lighting stress in the Nether dimension.

---

## 1. Executive Summary

We executed a reproducible 8-configuration performance matrix on an **Apple M1 Pro (10-core CPU, 16-core GPU, 16 GB unified memory)**. The benchmark scene target was a massive Nether lava lake at coordinates `[0.0, 32.0, 0.0]` (pitch `15.0`). The matrix covers static and rotating camera sweeps under four distinct lighting presets (**OFF**, **PERFORMANCE**, **BALANCED**, **ULTRA**).

The primary findings demonstrate:
* **Off Preset (Vanilla baseline):** Achieves extremely high framerates (~154-159 FPS) with a very low GPU cost (~6.3-7.2 ms p95), as no advanced clustered lighting or local voxel shadow passes are active.
* **Performance Impact of Advanced Lighting:** Enabling advanced clustered lighting introduces significant GPU load due to the sheer density of lava light sources. p95 GPU times scale from ~40.7 ms (Performance) to ~69.9 ms (Ultra).
* **Cluster Saturation & Overflow:** The cluster occupancy p95 hits the absolute ceiling of **256 lights per cluster** in all active configurations. This causes active light rejection/overflow, scaling up to 372 overflowed clusters in the static Ultra configuration.
* **Camera Rotation Variance:** When rotating, the average FPS is higher (~22.1-25.7 FPS) compared to the static view (~3.8-19.6 FPS) because the frustum sweeps away from the high-density lava center, reducing cluster workload and light count per frame.

---

## 2. Performance Matrix Results

The table below summarizes the aggregated telemetry metrics collected over 3,000 measurement frames per configuration (following a 1,800-frame warmup phase).

| Scenario | FPS | GPU p95 (ms) | Light Count | Cluster Occupancy (p95) | Cluster Overflow | Shadow Cost (p95) |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| **static_off** | 159.4 | 6.36 | 0 | 0 | 0 | 0.000 (n/a) |
| **static_performance** | 19.6 | 40.69 | 1024 | 256 | 150 | 0.000 (n/a) |
| **static_balanced** | 8.5 | 55.38 | 2048 | 256 | 228 | 0.000 (n/a) |
| **static_ultra** | 3.8 | 69.93 | 4096 | 256 | 372 | 0.000 (n/a) |
| **rotate_off** | 154.0 | 7.27 | 0 | 0 | 0 | 0.000 (n/a) |
| **rotate_performance** | 25.7 | 41.81 | 1024 | 256 | 7 | 0.000 (n/a) |
| **rotate_balanced** | 23.2 | 46.21 | 2048 | 256 | 96 | 0.000 (n/a) |
| **rotate_ultra** | 22.1 | 48.37 | 4096 | 256 | 212 | 0.000 (n/a) |

> [!NOTE]
> * **Shadow Cost:** Represents the GPU stage time for `dynamic local shadow` tracing. In this Nether environment under frozen simulation, all terrain blocks (static light emitters) are handled by the static voxel clipmap. Because no dynamic light sources (e.g. entities, flying fireballs) were actively updating or moving, the dynamic local shadow stage cost remains `0.000 ms`.

---

## 3. Deep Architectural Analysis

### 3.1. CPU Light Compaction & Upload Pacing
The CPU registry extracts raw light candidates (representing all emitting lava blocks within render distance) and performs a multi-phase compaction pass before uploading them to the GPU.
* **Candidate Sifting:** The scanner identifies approximately **1,723,000 raw light sources** in the active chunk region.
* **Compaction Ratio:** The registry successfully discards non-visible or occluded emitters, compacting them down to **~106,000 active lights** (a ~16x reduction).
* **Upload Limits:** The backend caps the final GPU upload buffer size depending on the lighting preset:
  * **Performance:** 1,024 lights
  * **Balanced:** 2,048 lights
  * **Ultra:** 4,096 lights

### 3.2. GPU Cluster Sizing & Slicing
The GPU voxel cluster grid consists of `16 x 9 x 64` light cells.
* **Occupancy Cap:** Each individual cluster grid cell has a hard limit of 256 light indices. Under extreme lava lake visibility, the p95 occupancy hits the **256 cap** across all active presets.
* **Overflow Behavior:** The surplus lights that cannot be stored in the saturated cluster cells trigger overflows. In the static view, raising the upload limit increases the number of saturated cells:
  * 1,024 limit $\rightarrow$ **150 overflowed clusters**
  * 2,048 limit $\rightarrow$ **228 overflowed clusters**
  * 4,096 limit $\rightarrow$ **372 overflowed clusters**
* **Rotational Mitigation:** Sweeping the camera around distributes the clusters. For example, in the performance preset, the average overflow count drops from **150** (static) to **7** (rotating).

---

## 4. Key Performance Bottlenecks & Recommendations

1. **Cluster Density Cap (Hard Limit):**
   The 256 lights-per-cluster limit is currently reached in high-density lava areas. While increasing this cap would prevent overflows, it would significantly increase the GPU thread local memory footprint and slow down the shading pass.
   * *Recommendation:* Implement a **hierarchical light clustering** or **distance-based attenuation scaling** during CPU compaction to prioritize brighter/closer light sources.

2. **Lava Light Cull Optimization:**
   Currently, lava blocks are treated as individual point lights.
   * *Recommendation:* Implement **light source merging/approximation** (voxels to area lights) to merge contiguous lava blocks into simplified volume emitters, reducing the light count from millions to thousands before the compaction pass.
