# T1B Replay Design & Timing Proofs

This document records the design and automated math checks for the **T1B.2 Transform Tracking** and planned **T1B.3 Rigid Entity Velocity Replay Pass** components of Metallum. It is not live feature-completion evidence.

> Current boundary: transform history, packet ABI, Metal shaders, and native matrix tests exist. Production does not yet intercept the real Metal entity draw buffers, so no replay packets are emitted in-game. The recorder fails closed rather than fabricating native pointers.

---

## 1. Vertex Position Space & PoseStack Coordinate Space

### 1.1. Position Space Analysis
For the supported entity RenderTypes (`entitySolid`, `entityCutout`, `entityCutoutNoCull`), the position space in the Metal-ready vertex buffer uploaded to the GPU is **camera/view space** (also known as ModelView or Eye space).

This occurs because Minecraft performs vertex transformations on the CPU before uploading them to the GPU.

### 1.2. CPU Transformation Code
From Minecraft's `VertexConsumer` interface:

```java
default VertexConsumer vertex(Matrix4f matrix, float x, float y, float z) {
    Vector4f vector4f = matrix.transform(new Vector4f(x, y, z, 1.0F));
    return this.addVertex(vector4f.x(), vector4f.y(), vector4f.z());
}
```

Here:
- `matrix` is the top matrix of the `PoseStack`, which represents the combined Model-View matrix $V \cdot M$ (mapping local entity coordinates directly to camera/view space).
- `x`, `y`, `z` are local model coordinates.
- `addVertex(...)` writes the transformed camera-space position directly into the vertex builder memory.

### 1.3. PoseStack Matrix Space Definition
At the start of rendering, Minecraft's `GameRenderer` applies the camera's view rotation matrix conjugate ($V_{\text{rot}}$) to the root of the `PoseStack`:
$$\text{poseStack.mulPose(camera.rotation().conjugate())}$$
Therefore, the top of the stack during entity rendering contains the fully combined model-view matrix:
$$MV = V_{\text{rot}} \cdot M_{\text{cam-rel}}$$
Where $M_{\text{cam-rel}}$ is the model matrix relative to the camera position.
Thus, **the exact space of `PoseStack.last().pose()` is Model-View matrix space.**

---

## 2. Planned T1B.3 GPU Replay Math & Shader Pipeline

### 2.1. CPU Per-Packet Matrix Inversion
Instead of performing redundant per-vertex matrix inversions in the MSL vertex shader, the transition matrix $W$ is calculated **once per draw packet on the CPU**:
$$W = MV_{\text{prev}} \cdot (MV_{\text{curr}})^{-1}$$
This matrix transform $W$ is passed directly as a uniform buffer parameter to the GPU.

### 2.2. Raster Depth vs Motion Math Separation
- **Raster Depth Position**: `[[position]]` in the vertex shader is calculated using `currentJitteredProjection * float4(P_view, 1.0f)` for exact subpixel depth testing against the main depth buffer.
- **Unjittered Clip Positions**: The vertex shader outputs `currentClip` (using `currentUnjitteredProjection`) and `previousClip` (using `previousUnjitteredProjection` applied to $P_{\text{prev\_view}} = W \cdot P_{\text{view}}$).

### 2.3. Per-Fragment Perspective Divide
The fragment shader performs perspective division and NDC delta pixel conversion per fragment:
$$currentNdc = \frac{currentClip.xy}{currentClip.w}$$
$$previousNdc = \frac{previousClip.xy}{previousClip.w}$$
$$ndcDelta = previousNdc - currentNdc$$
$$\text{motion.x} = \text{ndcDelta.x} \cdot 0.5 \cdot \text{extent.x}$$
$$\text{motion.y} = -\text{ndcDelta.y} \cdot 0.5 \cdot \text{extent.y}$$

---

## 3. Planned PSO Families & Classification Contract

- **Production Family**: Motion (`.rg16Float` at color 0) + Reactive (`.r8Unorm` at color 1).
- **Debug Family**: Motion (`.rg16Float` at color 0) + Reactive (`.r8Unorm` at color 1) + Classification (`.r8Unorm` at color 2).
- **Classification Value Mapping**:
  - `5.0 / 255.0`: Rigid entity
  - `6.0 / 255.0`: Entity reset (teleport, spawn, missing previous, or non-finite matrix)

---

## 4. Planned Depth & Rasterizer State Contract

- **Depth State**: `depthCompareFunction = .equal`, `isDepthWriteEnabled = false`.
- **Attachment Load Action**: `MTLLoadAction.load` for motion, reactive, classification, and depth.
- **Rasterizer Replication**: Replicates `cullMode`, `winding`, and `depthBias` matching the captured draw packet.

---

## 5. Verification Results & A/B Timing

### 5.1. Native Matrix Validation Suite (`entityMatrixValidation`)
```
Starting Entity Matrix & Motion Validation Native Suite...
  PASS: Pure Translation -> Motion Vector: (77.119995 px, -15.4239855 px)
  PASS: Pure Rotation -> Motion Vector: (-6.791568 px, 0.12863338 px)
  PASS: Non-Uniform Scale -> Motion Vector: (9.640002 px, -0.0 px)
  PASS: Combined Camera + Entity Rotation -> Motion Vector: (-15.284149 px, -2.2462482 px)
Native Entity Matrix & Motion Validation Suite PASSED successfully.
```

### 5.2. Runtime proof still required

No live entity replay or T1B.3 A/B frametime run has been performed. The passing native matrix suite proves only the transform and motion math. Before enabling the replay, wire `EntityVelocityDrawRecorder.recordDraw(...)` to the Metal draw path with real buffer handles, validate the Java/Swift packet ABI at runtime, and capture a live scene containing moving and teleporting entities.
