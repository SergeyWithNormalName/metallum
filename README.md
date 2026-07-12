## Metallum

Metallum is an experimental Metal rendering backend for Minecraft on macOS. It replaces the normal graphics backend with Apple Metal and includes a native EDR HDR output path for Apple Silicon displays.

The project is still experimental. Performance and compatibility can vary by macOS version, resource pack, and installed mods.

## HDR

The default `auto` mode enables enhanced HDR when macOS reports an EDR-capable display and falls back to SDR otherwise. The HDR path uses:

- an `RGBA16Float` `CAMetalLayer` in extended linear sRGB;
- the display's live EDR headroom as the output limit;
- source-authored emission from Minecraft and Sodium rather than a global brightness filter;
- FP16 local highlights and bloom;
- a pre-GUI scene snapshot so HUD, text, menus, and overlays remain at SDR reference white;
- depth-aware semantic masking, so an emissive source hidden behind nearer geometry cannot bloom through it;
- safe fallback from enhanced HDR to EDR and then SDR if native setup or presentation fails.

Semantic HDR currently covers Sodium terrain and fluids, luminous block states and emissive quads, plus vanilla emissive entities, beacon beams, lightning/dragon rays, stars, and the sun/moon pass. Transparent contributions are weighted by their actual alpha.

This produces real extended-range pixels above `1.0`; it is not merely an HDR-capable swapchain. Minecraft's main scene buffer is still `RGBA8`, however, so this is a semantic HDR compositor rather than a full FP16 rewrite of every internal Minecraft render target.

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
If the game is moved from an SDR display to an HDR display, restart it so the semantic shader variant can be selected for that display.

- `mode=auto`: enhanced HDR on an HDR/EDR display, SDR elsewhere.
- `mode=enhanced`: request semantic HDR explicitly; still falls back safely when HDR output is unavailable.
- `mode=edr`: linear FP16 EDR presentation without highlight enhancement. Semantic MRT shaders are not enabled in this mode.
- `mode=off`: standard `BGRA8` SDR output. Semantic MRT shaders are not enabled in this mode.
- `sourceEncoding=srgb`: correct for Minecraft's current `RGBA8_UNORM` main target. Change this only if the upstream render target becomes linear.
- `hdrStrength`: semantic highlight strength from `0.0` to `2.0`.
- `bloomStrength`: bloom strength from `0.0` to `1.0`.
- `diagnosticPattern=true`: replaces the frame with an EDR ramp up to `8.0`; the green strip shows the current safe display headroom.

The system properties `metallum.hdr.mode` and `metallum.hdr.diagnosticPattern` can temporarily override those two settings for diagnostics.

Standard Minecraft screenshots are captured before the display-only HDR pass and therefore remain SDR. This is intentional: it avoids saving unclamped linear values into an SDR image format.

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
