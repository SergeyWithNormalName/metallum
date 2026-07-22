# Project: Metallum DRS & Sodium UI Simplification

## Architecture
- **Rendering Engine**: Apple Metal rendering backend for Minecraft (Java 22 Panama + Swift/Metal).
- **Timing & DRS**: `MetalGpuTiming` provides frame metrics; DRS controller adjusts `render_extent` dynamically between 0.5x and 1.0x to hit 60 FPS (16.6ms target).
- **UI Compositing**: `SDR_UI` layer renders at full display resolution (1.0x).
- **Settings & Config**: Sodium settings UI modified to offer `MetalFX Upscaling` (`[Off, Spatial, Temporal]`), while `metallum-renderer.properties` and `run_metal_benchmark.sh` support fixed-resolution overrides.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| 1 | Exploration & Mapping | Inspect existing GPU timing, Sodium UI, properties, benchmarks | None | DONE |
| 2 | R1: DRS Controller | GPU timing driven scaling, hysteresis, SDR_UI 1.0x preservation | M1 | DONE |
| 3 | R2: Sodium GUI Simplification | Single MetalFX dropdown [Off, Spatial, Temporal], DRS enable | M2 | DONE |
| 4 | R3: Benchmark & Config Integrity | Preserve properties & script overrides for tests | M2, M3 | DONE |
| 5 | E2E Verification & Audit | Unit tests, benchmark script validation, forensic integrity audit | M2, M3, M4 | DONE |



## Interface Contracts
- **DRS Controller**: Interfaces with `MetalGpuTiming` for GPU frame durations. Exposes scale setter/getter for `render_extent`.
- **Sodium UI**: Connects `MetalFX` option (`Off`, `Spatial`, `Temporal`) to DRS controller enable/mode state.
- **Config / Overrides**: Reads `metallum-renderer.properties` and CLI arguments in `run_metal_benchmark.sh` to override dynamic scaling with fixed scale factors.

## Code Layout
- `/src/main/java/com/metallum`: Java backend source code
- `/src/main/native`: Swift native code
- `/src/main/metal`: Metal shaders
