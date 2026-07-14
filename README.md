## Metallum

Metallum is an experimental Metal rendering backend for Minecraft on macOS. It replaces the normal graphics backend with Apple Metal and includes a native EDR HDR output path for Apple Silicon displays.

The project is still experimental. Performance and compatibility can vary by macOS version, resource pack, and installed mods.

## HDR

The default `auto` mode enables the scene-wide HDR path when macOS reports an EDR-capable display and falls back to SDR otherwise. The HDR path uses:

- an `RGBA16Float` `CAMetalLayer` in extended linear sRGB;
- an FP16 Minecraft MainTarget, FP16 scene continuations, and an extended-range world lightmap;
- an atomic shader preflight which, when successful, decodes supported vanilla and Sodium raster output at the FP16 boundary so Metal's fixed-function blending operates on linear RGB;
- the display's live EDR headroom as the output limit;
- scene- and headroom-adaptive highlight reconstruction that lets illuminated sky, clouds, foliage, and terrain use EDR while preserving ordinary shadows and midtones;
- full-resolution, depth-validated source-authored emission from Minecraft and Sodium, plus coverage-weighted FP16 bloom that does not flatten emissive texture detail;
- a separately seeded SDR GUI target, so HUD, text, menus, blur, vignette, and destination-dependent overlays retain their normal SDR appearance;
- depth-aware semantic masking, so an emissive source hidden behind nearer geometry cannot bloom through it;
- safe fallback from enhanced HDR to EDR and then SDR if native setup or presentation fails.

Semantic HDR currently covers Sodium terrain and fluids, luminous block states and emissive quads, plus vanilla emissive entities, beacon beams, lightning/dragon rays, stars, and the sun/moon pass. Transparent contributions are weighted by their actual alpha.

This produces real extended-range scene values and display pixels above `1.0`; it is not merely an HDR-capable swapchain. The current linearization is deliberately limited to the raster-output boundary: after successful preflight, the final encoded RGB from supported scene shaders is decoded before it reaches the FP16 target, so ordinary fixed-function alpha blending happens in linear light. It does **not** yet make the whole Minecraft shader pipeline physically linear. Texture and material composition, lightmap application, fog, and other shader-local color math still follow Minecraft's original encoded-color assumptions before that boundary conversion; moving those operations to linear light is Phase C work. If preflight cannot validate every required shader, Metallum keeps the entire scene on the safe encoded-color path rather than mixing color contracts within one frame.

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
- `mode=off`: standard color-managed sRGB `BGRA8` SDR output. If HDR is disabled live after an FP16 scene started, the final pass converts scene-linear RGB back to sRGB instead of presenting the darker linear values directly. Semantic MRT shaders are not enabled when the game starts in this mode.
- `sourceEncoding=srgb`: the safe configured fallback and legacy contract. Leave this setting at `srgb`; in scene mode Metallum automatically switches the effective scene contract to linear only after the atomic shader preflight succeeds. If validation fails, it forces the whole scene back to the encoded-color path. Manually selecting `linear` is neither required nor a way to bypass that safety gate.
- `hdrStrength`: semantic highlight strength from `0.0` to `2.0`.
- `bloomStrength`: bloom strength from `0.0` to `1.0`.
- `diagnosticPattern=true`: replaces the frame with an EDR ramp up to `8.0`; the green strip shows the current safe display headroom.

Older configuration files may contain `experimentalFp16=true`. This key is deprecated and is retained only for backward compatibility: it opts `mode=enhanced` into the scene-wide FP16 path. `auto` and `scene` select that path without the flag; `off` and `edr` ignore it.

The system properties `metallum.hdr.mode` and `metallum.hdr.diagnosticPattern` can temporarily override those two settings for diagnostics.

F2 screenshots remain conventional SDR images. When the seeded GUI target is available, screenshots use its completed full-frame result, including HUD and menus; the display-only HDR expansion is not baked into the PNG.

## GPU timing reports

GPU timing is opt-in and disabled during a normal game launch. It uses Metal timestamp measurements from the running game, so it remains useful on Apple M1 Pro where Xcode's hardware-counter Performance view cannot profile the trace.

```bash
METALLUM_GPU_TIMING=1 \
METALLUM_GPU_TIMING_DETAIL=0 \
METALLUM_GPU_TIMING_REPORT="$PWD/logs/metallum-gpu-timing.jsonl" \
  ./gradlew runClient --args='--quickPlaySingleplayer <world>'
```

- `METALLUM_GPU_TIMING=1` writes a rolling 300-presented-frame summary to the game log: present pacing, presenting-command-buffer GPU average/p50/p95/p99/max, and CPU waits. The GPU duration is not a whole CPU frame-time measurement.
- `METALLUM_GPU_TIMING_DETAIL=1` additionally measures the native world, HDR, MetalFX, UI, and present stage boundaries. This is diagnostic instrumentation and can modestly perturb frame timing.
- `METALLUM_GPU_TIMING_REPORT=/absolute/path/report.jsonl` additionally appends schema-v2 JSON objects. Benchmark reports tag each command buffer with its immutable warmup/measurement generation, so late GPU completions cannot contaminate the measured phase. The report also records present-interval summaries, per-window 1%/0.1% lows, metadata, per-stage average/max values, CPU waits, dropped timing events, and whether detailed instrumentation was active.

The report path has no effect unless `METALLUM_GPU_TIMING=1` is set. On M1 Pro the available Metal counter is a GPU timestamp; the report can attribute elapsed time between the instrumented boundaries, but cannot provide hardware occupancy, cache, or bandwidth counters.

For the reproducible release gate on this MacBook, use the console-only launcher. It refuses the wrong monitor or configuration, targets the built-in Retina display at 3024x1964@120 with VSync off, disables screenshots and intrusive timing detail, and accepts only 3000 phase-aligned measurement frames:

```bash
scripts/run_metal_benchmark.sh --preflight-only
scripts/run_metal_benchmark.sh \
  --route benchmark/routes/hdrtest-static-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json \
  --metalfx OFF \
  --label baseline
python3 tools/metal_benchmark_report.py compare BASELINE.jsonl CANDIDATE.jsonl
```

The tracked route specification fixes the fixture digest, offline player identity, dimension, camera pose, paused clock, frozen clear weather, and the complete integrated-server simulation. This benchmark-only freeze preserves the rendered scene, including existing entities, but prevents a mob or another world tick from moving the camera between runs. It does not change normal gameplay. The source world lives at the ignored, read-only path `run/benchmark-fixtures/<fixture-id>/world`; the launcher never opens `HDRTest` directly. Each real run uses `clonefile(2)` to create a unique APFS copy-on-write world below `run/saves`, verifies that its content matches the fixture, and removes only that owner-token-protected clone on exit.

The separate tracked settings specification fixes the selected performance and image-quality state without hashing private or machine-specific data from the whole `run` directory. It covers the relevant Minecraft options, complete Sodium quality/performance/debug state, active resource-pack IDs and external-pack contents, HDR properties, persistent MetalFX state, and the resolved M1 Pro Sodium worker count. During the run the controller also rejects an inactive or iconified window, AFK throttling, a framerate limit other than 260, changed live render options, or a different active pack set. The timing JSON records the settings/spec/content digests plus the important decoded values and actual HDR presentation values.

Every real run first builds the exact Java classes, resources, and packaged native dylib that `runClient` will load, records their combined content digest, and verifies that digest again after Minecraft exits. The launcher also recomputes the source, settings, fixture, and resource-pack fingerprints. It creates a sibling `*.accepted.json` only after independently re-reading the raw report and proving the ordered server-freeze, `ARMED`, built-in-display `WINDOW_READY`, route, 1800-frame warmup, 3000-frame measurement, and completion markers; the Sodium worker count; the absence of benchmark/Metal failures; and SHA-256 bindings for the raw report, summary, Minecraft log, and console log.

Strict `compare` accepts only schema-v2 attested bundles and requires the same source and built-artifact digests by default. Its verdict is a composite stability gate: GPU p95/p99/worst, weighted and worst-window FPS, 1%/0.1% lows, and present-interval p95/p99/worst can each reject a candidate. For an intentional before/after optimization comparison between two fully attested source revisions, use `--allow-source-change`; `--provisional` is reserved for legacy or exploratory reports and is not a release gate. The sidecar is a local consistency receipt, not a cryptographic signature or an external trust root.

To create a new fixture version explicitly while Minecraft is stopped, clone an existing world once and copy the printed digest into the new tracked route specification. Never refresh an existing fixture ID in place:

```bash
python3 tools/metal_benchmark_fixture.py prepare \
  run/saves/HDRTest \
  run/benchmark-fixtures/hdrtest-static-v1/world
```

`--preflight-only` validates the route, immutable fixture digest, read-only permissions, normalized settings contract, HDR/VSync/fullscreen settings, and the built-in-laptop-display contract without building artifacts or creating a temporary world. Reports and copied runtime logs are written below the ignored `run/logs/metallum-benchmarks` directory.

## Compatibility

Validated target:

- Minecraft Java 26.2;
- Fabric Loader 0.19.3;
- Java 25;
- Sodium 0.9.1;
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
