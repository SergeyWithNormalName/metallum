## Metallum

Metallum is an experimental Metal rendering backend for Minecraft on macOS. It replaces the normal graphics backend with Apple Metal and includes a native EDR HDR output path for Apple Silicon displays.

The project is still experimental. Performance and compatibility can vary by macOS version, resource pack, and installed mods.

## HDR

The default `auto` mode enables the scene-wide HDR path when macOS reports an EDR-capable display and falls back to SDR otherwise. The HDR path uses:

- an `RGBA16Float` `CAMetalLayer` in extended linear sRGB;
- an FP16 Minecraft MainTarget, FP16 scene continuations, and an extended-range world lightmap;
- the display's live EDR headroom as the output limit;
- scene- and headroom-adaptive highlight reconstruction that preserves ordinary shadows and midtones;
- source-authored emission from Minecraft and Sodium, plus FP16 local highlights and bloom;
- a separately seeded SDR GUI target, so HUD, text, menus, blur, vignette, and destination-dependent overlays retain their normal SDR appearance;
- depth-aware semantic masking, so an emissive source hidden behind nearer geometry cannot bloom through it;
- safe fallback from enhanced HDR to EDR and then SDR if native setup or presentation fails.

Semantic HDR currently covers Sodium terrain and fluids, luminous block states and emissive quads, plus vanilla emissive entities, beacon beams, lightning/dragon rays, stars, and the sun/moon pass. Transparent contributions are weighted by their actual alpha.

This produces real extended-range scene values and display pixels above `1.0`; it is not merely an HDR-capable swapchain. It does **not** turn Minecraft into a physically linear renderer: vanilla shaders, texture authoring, and several blending decisions still follow Minecraft's original rendering assumptions. The reconstruction is designed to improve highlight range while preserving the intended SDR base image.

### Configuration

On first launch Metallum creates:

```text
.minecraft/config/metallum-hdr.properties
```

Example:

```properties
mode=auto
sourceEncoding=srgb
hdrStrength=1.0
bloomStrength=0.22
diagnosticPattern=false
```

Restart Minecraft after changing the file.
If the game is moved from an SDR display to an HDR display, restart it so the HDR render-target and shader policy can be selected for that display.

- `mode=auto`: the default scene-wide FP16 HDR path on an HDR/EDR display, SDR elsewhere.
- `mode=scene`: explicitly request scene-wide FP16 HDR. The aliases `hdr_scene` and `full` are also accepted.
- `mode=enhanced`: run adaptive scene-wide reconstruction and semantic HDR from the ordinary `RGBA8` scene, without FP16 scene propagation. It still falls back safely when HDR output is unavailable.
- `mode=edr`: EDR presentation of the ordinary SDR scene without semantic or scene-wide highlight enhancement.
- `mode=off`: standard `BGRA8` SDR output. Semantic MRT shaders are not enabled in this mode.
- `sourceEncoding=srgb`: the current contract. In scene mode, FP16 stores extended-range sRGB-encoded values which are decoded before HDR display mapping. Use `linear` only if the entire upstream scene is genuinely linear.
- `hdrStrength`: semantic highlight strength from `0.0` to `2.0`.
- `bloomStrength`: bloom strength from `0.0` to `1.0`.
- `diagnosticPattern=true`: replaces the frame with an EDR ramp up to `8.0`; the green strip shows the current safe display headroom.

Older configuration files may contain `experimentalFp16=true`. This key is deprecated and is retained only for backward compatibility: it opts `mode=enhanced` into the scene-wide FP16 path. `auto` and `scene` select that path without the flag; `off` and `edr` ignore it.

The system properties `metallum.hdr.mode` and `metallum.hdr.diagnosticPattern` can temporarily override those two settings for diagnostics.

F2 screenshots remain conventional SDR images. When the seeded GUI target is available, screenshots use its completed full-frame result, including HUD and menus; the display-only HDR expansion is not baked into the PNG.

## Compatibility

Validated target:

- Minecraft Java 26.2;
- Fabric Loader 0.19.3;
- Java 25;
- Sodium 0.9.0;
- Apple Silicon and macOS with Metal support.

Sodium is supported and is the primary terrain path. Iris is not supported by the HDR implementation.

## Building

```bash
./gradlew clean build
```

For a local Metal validation run:

```bash
MTL_DEBUG_LAYER=1 MTL_SHADER_VALIDATION=1 \
  ./gradlew runClient --args='--quickPlaySingleplayer <world>'
```

The built mod jar is written under `build/libs/`.
