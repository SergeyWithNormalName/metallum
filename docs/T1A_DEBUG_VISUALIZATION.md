# T1A Debug Visualization & Observability

This document details the opt-in developer-only debug visualization tools implemented for the T1A Foundation motion/reactive vectors.

---

## 1. Enabling the Tool

The debug visualization is controlled via the environment variable `METALLUM_DEBUG_VISUALIZATION`.

When this variable is set, the engine:
1. Automatically configures and enables camera-motion temporal diagnostics.
2. Allocates a debug classification attachment.
3. Injects a debug post-pass render encoder immediately before presentation, which overwrites the game world texture with debug colors before the UI is composited.

When the environment variable is **not** set, the debug shader pipelines are not compiled, and no overhead or passes are introduced (**zero cost**).

---

## 2. Visualization Modes

The variable `METALLUM_DEBUG_VISUALIZATION` supports five modes (either as integers or strings):

### Mode 1: Motion Direction (`1` or `motion_direction`)
Encodes the screen-space direction of motion vectors into a color wheel:
- **Red/Green Channels**: Maps the normalized 2D direction of pixel motion:
  $$\text{Color}_{\text{RG}} = \text{dir} \times 0.5 + 0.5$$
- **Intensity**: Scaled by the motion magnitude (from $0.2$ to $1.0$ for up to 10 pixels of motion).
- **Static Areas**: Pitch black ($0$ motion).

### Mode 2: Motion Magnitude Heatmap (`2` or `motion_magnitude`)
Segments motion vectors by their magnitude in pixels to inspect subpixel jitter or extreme values:
- **Blue**: Small motion / drift ($0 < \text{magnitude} \le 0.5$ px).
- **Green**: Normal motion ($0.5 < \text{magnitude} \le 16.0$ px).
- **Red**: Extreme or invalid motion ($\text{magnitude} > 16.0$ px).
- **Black**: Zero motion.

### Mode 3: Reprojection Validity (`3` or `reprojection_validity`)
Displays the pixel classification from the diagnostics shader:
- **Green**: Valid reprojected pixel (successful temporal history match).
- **Orange**: Reset frame or global reset.
- **Dark Gray**: Sky or invalid depth (depth $\le 0.0$ or $\ge 1.0$).
- **Yellow**: Out-of-frame invalidation (history pixel lies outside the viewport boundaries).
- **Red**: Other invalidation (e.g. calculation NaN/Inf).

### Mode 4: Reactive Mask (`4` or `reactive_mask`)
Directly outputs the 1-channel reactive mask as grayscale:
- **Black**: $0.0$ (history is highly valid/trusted).
- **White**: $1.0$ (history is rejected/fully reactive).

### Mode 5: Camera Motion Vectors (`5` or `camera_motion`)
Directly visualizes the signed components of the camera-induced motion vectors:
- **Red**: Horizontal motion component ($X$), mapped as $v_x \times 0.1 + 0.5$.
- **Green**: Vertical motion component ($Y$), mapped as $v_y \times 0.1 + 0.5$.
- **Blue**: Midpoint constant ($0.5$).

---

## 3. Architecture & Pipeline Safety

To satisfy all visual excellence and stability rules:
- **UI Decoupling**: The debug overlay applies exclusively to the world output texture (`sourceTexture`). The UI overlay texture (`uiTexture`) is blended normally on top of this visualization *after* the post-pass, ensuring **the UI does not receive jitter or debug tints**.
- **Pipeline Integrity**: Debug mode does not modify the layout, parameters, or states of the HDR, Spatial, Temporal, UI, or ProMotion pipelines. It runs as a transparent, optional post-pass.

---

## 4. T1A Foundation Limitations

The following limitations are expected under the T1A Foundation scope and will be visualized:
- **Camera-Induced Motion Only**: Since dynamic geometry is not supported in T1A, entities (mobs, players) and held items will not produce correct motion vectors and will show up as static or invalidated (e.g. reset/reactive) regions.
- **Out-of-Frame Invalidation Only**: Disocclusion detection in T1A is limited to boundary checks. Advanced disocclusion (rejecting pixels whose current depth deviates from reprojected history depth) is deferred to the T1B phase.
