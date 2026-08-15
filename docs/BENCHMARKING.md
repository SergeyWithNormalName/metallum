# Metallum Benchmarking Guide & Performance Methodology

This document is the canonical source of truth for Metallum benchmark methodology, execution, and evidence classification.

---

## 1. Purpose and Benchmark Philosophy

Metallum's benchmark system provides reproducible, console-driven, non-intrusive performance measurement of the native Apple Metal rendering backend on macOS (Apple Silicon).

### Core Philosophy
1. **Console-Driven Automation Without GUI Interaction**: Benchmarks run via console automation driving a real fullscreen Minecraft client (`Built-in Retina Display` at `3024x1964@120Hz` by default). Deterministic singleplayer routes and frozen world simulation ticks ensure no mouse macros, window drag listeners, or key presses perturb frame pacing.
2. **Immutable APFS CoW Fixtures**: Every benchmark clones a read-only, hash-verified world snapshot using strict Apple File System (APFS) Copy-on-Write (`clonefile`). No world modifications persist between runs.
3. **Strict Runtime and Settings Contracts**: Every benchmark run fingerprints and byte-verifies the git source tree, compiled artifacts (`libmetallum.dylib` + Java classes), tracked settings specs, and runtime `options.txt` / properties files.
4. **SHA-256 Attestation Receipts**: Benchmark results are published to structured JSONL logs and bound by a local SHA-256 acceptance receipt (`.accepted.json`) only if preflight contracts, runtime frame pacing, frame-count alignments, and teardown verification checks pass completely. The receipt is an integrity check, not an external signature or a performance result by itself.
5. **Clear Isolation between Attribution and Acceptance**: Diagnostic timing passes (which inject pass boundary markers) are strictly segregated from production acceptance runs. Diagnostic numbers MUST NEVER be presented as production FPS.

---

## 2. Required Preflight Checks

Before launching a benchmark, the launcher (`scripts/run_metal_benchmark.sh`) automatically enforces the following preflight requirements:

1. **Operating System & Architecture**:
   - macOS (`Darwin`) running on Apple Silicon (`arm64`/`aarch64`).
2. **Required Utilities**:
   - `python3`: Required for JSONL report analysis and fixture fingerprinting.
   - `pgrep`: Required for process isolation.
   - `mktemp` / `uuidgen`: Required for isolated temporary APFS CoW run worlds.
   - `cmp`: Required if running `--fi-validation` to verify runtime settings restoration.
   - `./gradlew`: Executable Gradle wrapper in repository root.
3. **Process Isolation**:
   - No existing `net.minecraft.client.main.Main`, `net.fabricmc.devlaunchinjector`, `KnotClient`, or `runClient` process can be active. If active, the run halts immediately to prevent GPU contention.
4. **Display Target & Video Mode**:
   - Default display: Built-in Retina Display (`Built-in Retina Display`), exclusive fullscreen at `3024x1964@120Hz`.
   - Custom display targets must be exact GLFW monitor attachments with valid backing pixels.
5. **Configuration Files Availability**:
   - All runtime files must exist in `run/`: `options.txt`, `config/metallum-hdr.properties`, `config/metallum-renderer.properties`, `config/metallum-metalfx.properties`, `config/sodium-options.json`, `config/sodium-mixins.properties`, `resourcepacks`, `data/fabric_default_resource_packs.json`, `saves/`.

---

## 3. Exact Artifact, Source, Fixture, and Settings Integrity Model

Metallum enforces an end-to-end fingerprinting chain to make a local benchmark run reproducible and internally consistent:

### Integrity Chain
- **Source SHA256**: Computed via `python3 tools/metal_benchmark_fixture.py source-digest .`. Fingerprints all repository source files before build and after teardown.
- **Artifact SHA256**: Computed via `python3 tools/metal_benchmark_fixture.py artifact-digest . build/classes/java/main build/resources/main build/generated/metallum/natives/macos/libmetallum.dylib`. Ensures the exact compiled binaries are tracked.
- **Fixture SHA256**: Read-only world directory under `run/benchmark-fixtures/<fixture-id>/world`. Validated via `tools/metal_benchmark_fixture.py verify-fixture` before cloning and after teardown. Any world modification causes a preflight/teardown abort.
- **Settings SHA256**: Computed via `tools/metal_benchmark_fixture.py settings-values`. Maps tracked settings JSON (`benchmark/settings/*.json`) to runtime `options.txt`, `metallum-*.properties`, and Sodium configs.
- **Run World Identity**: Temporary world allocated at `run/saves/MetallumBenchmark-<stamp>`. Protected by a UUID owner marker file (`.metallum-benchmark-owner`) and filesystem inode verification (`stat -f '%d:%i'`) to prevent unsafe directory cleanup.

---

## 4. Existing Route Types

Route files (`benchmark/routes/*.json`) define the player spawn pose, dimension, weather, time of day, freeze state, and workload behaviors.

| Workload Kind | Description | Examples |
| :--- | :--- | :--- |
| `STATIC` | Static pose and frozen simulation | `hdrtest-static-v1`, `nether-lava-stress-v1`, cave/foliage/rain routes |
| `TORCH_EPOCH` / `TORCH_TOGGLE` | A bounded block-light change at prescribed frames | Light-update routes |
| `L6_DYNAMIC_SHADOW` | Camera motion, held light and dynamic-entity probes | `hdrtest-l6-dynamic-v1` |

Route JSON is the source of truth for a route's schema and workload. Do not
copy its field count into this guide: the launcher validates it directly.

---

## 5. Existing Settings Profiles

Settings files (`benchmark/settings/*.json`) declare graphics options, render distances, and renderer capabilities:

1. **`benchmark/settings/native-hdr-fancy-v1.json`**:
   - **Primary production baseline profile**.
   - Output: EDR/HDR scene (`scene`), sRGB source encoding, Fancy graphics, Render/Sim Distance `16/12`, `maxFps=260`, VSync off, MetalFX off.
2. **`benchmark/settings/native-sdr-fancy-v1.json`**:
   - Standard SDR baseline profile (`off` HDR mode, sRGB source encoding).
3. **`benchmark/settings/nether-lava-stress-v1.json`**:
   - Nether fluid/lighting stress profile in HDR.
4. **`benchmark/settings/fi-hdr-temporal-ultra-performance-v1.json`**:
   - Opt-in Frame Interpolation validation profile (`maxFps=30`, VSync on, Frame Interpolation enabled, Temporal Ultra Performance).

---

## 6. Three Measurement Tiers

All performance testing in Metallum MUST strictly classify its runs into one of three measurement tiers:

```
+-------------------------------------------------------------------------+
|                        TIER A: PREFLIGHT / SMOKE                        |
| • Quick build/fixture/settings sanity check (--preflight-only or short) |
| • Used ONLY for contract verification | NEVER performance evidence     |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                        TIER B: DIAGNOSTIC                               |
| • Per-stage GPU profiling (METALLUM_L2_TIMING_DETAIL=1) or screening    |
| • Fast screening: 600 warmup + 600 or 900 measured frames (300-aligned) |
| • Used for attribution & bottleneck discovery | NEVER mixed with FPS    |
+-------------------------------------------------------------------------+
                                    |
                                    v
+-------------------------------------------------------------------------+
|                        TIER C: ACCEPTANCE                               |
| • Full production contract (1800 warmup + 3000 measured frames)         |
| • Clean environment, TIMING_DETAIL=0, >= 2 independent runs             |
| • MANDATORY for all optimization accepted/rejected decisions            |
+-------------------------------------------------------------------------+
```

### TIER A — PREFLIGHT / SMOKE
- **Purpose**: Validates build compilation, APFS CoW cloning, fixture hashes, settings contract matching, and launcher flags.
- **Run Length**: Zero frames (`--preflight-only`) or short runs.
- **Rule**: **NEVER** cite Tier A metrics as performance evidence.

### TIER B — DIAGNOSTIC
- **Purpose**: Stage-by-stage GPU profiling (`METALLUM_L2_TIMING_DETAIL=1`) and fast screening.
- **Run Length**: Fast screening contract — **600 warmup + 600 or 900 measured frames** (or flexible 300-aligned frame counts). Actual wall-clock duration depends on frame throughput and client startup cost.
- **Instrumentation**: Injects stage boundary markers (`WORLD_OPAQUE`, `SUN_SHADOW`, `VOXEL_UPDATE`, etc.), which split Metal render encoders.
- **Rule**: **NEVER** present Tier B overall FPS as production FPS or mix Tier B numbers into production baselines.

### TIER C — ACCEPTANCE
- **Purpose**: Authoritative decision-making for code optimization (Accept/Reject).
- **Run Length**: Standard contract — **1800 warmup presented frames + 3000 measured frames** (10 independent 300-frame windows).
- **Instrumentation**: Production mode only (`METALLUM_L2_TIMING_DETAIL=0`, `METALLUM_L2_METAL_VALIDATION=0`, no screenshots).
- **Replicability**: Must run at least **two independent runs** per candidate under comparable thermal states.
- **Attestation**: Must generate a valid `.accepted.json` SHA-256 attestation receipt via `tools/metal_benchmark_report.py attest`.

### Frame-Count 300-Frame Alignment Rule
The timing engine aggregates statistics in non-overlapping 300-frame windows.
- Any custom frame override (`METALLUM_L2_WARMUP_FRAMES` and `METALLUM_L2_MEASURE_FRAMES`) **MUST be a positive multiple of 300 and at least 300**.
- The runner automatically aborts with an error if an unaligned frame count is passed.

---

## 7. How to Run Preflight, Diagnostic, and Production Measurements

### Preflight Check
```bash
scripts/run_metal_benchmark.sh --preflight-only \
  --route benchmark/routes/hdrtest-static-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json
```

### Tier B Diagnostic Screening (Stage Attribution)
```bash
METALLUM_L2_TIMING_DETAIL=1 \
scripts/run_metal_benchmark.sh \
  --route benchmark/routes/hdrtest-static-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json \
  --label stage-attribution
```

### Tier C Acceptance Run (Full Production Measurement)
```bash
scripts/run_metal_benchmark.sh \
  --route benchmark/routes/hdrtest-static-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json \
  --lighting-preset balanced \
  --label candidate-experiment-1
```

### Report Comparison (Baseline vs Candidate)
```bash
python3 tools/metal_benchmark_report.py compare \
  --allow-source-change \
  run/logs/metallum-benchmarks/BASELINE_RAW.jsonl \
  run/logs/metallum-benchmarks/CANDIDATE_RAW.jsonl
```

---

## 8. Where Raw Artifacts are Stored

All benchmark outputs are written into `run/logs/metallum-benchmarks/`. The directory is strictly ignored by git.

For a run with stamp `STAMP`, commit `COMMIT`, worktree state `STATE`, label `LABEL`, and MetalFX mode `MODE`, the launcher generates:
- `${stem}.raw.jsonl`: The raw GPU window timing and telemetry JSON Lines report.
- `${stem}.summary.json`: Aggregated window metrics (FPS, percentiles, telemetry).
- `${stem}.minecraft.log`: Copy of `run/logs/latest.log` captured during the run.
- `${stem}.console.log`: Full stdout/stderr console output of `./gradlew runClient`.
- `${stem}.accepted.json`: SHA-256 attestation receipt generated when a Tier C release run succeeds.

---

## 9. How to Detect Invalid or Fallback Runs

A benchmark run MUST be rejected as INVALID if any of the following occur:

1. **Log Failure Markers**: `METALLUM_BENCHMARK EVENT=FAIL` in `.minecraft.log`.
2. **Metal Errors**: Any `[metallum] Metal command buffer failed`, `GPU timing sample invalid`, or static geometry lifecycle error in `.console.log`.
3. **Teardown Integrity Mismatch**:
   - Source fingerprint changes during run.
   - Built artifact SHA256 changes during run.
   - Immutability check of fixture world fails after run.
   - Tracked settings or renderer options change mid-run.
4. **Process Cleanup Failure**: Leftover `runClient` or `Minecraft` processes detected after exit.
5. **Telemetry Fallbacks & Thermal Invalidity**:
   - `private_geometry_heap.fallback_allocations_total > 0` (heap allocation fallback).
   - `L6_DYNAMIC_COVERAGE` reports `fallback_total > 0`, `coverage_miss_total > 0`, or `failure_total > 0`.
   - `thermal_state` reaches `Serious` or `Critical` during the measured interval.

`cluster_index_capacity_drops > 0` is not an automatic renderer failure. It
records a saturated dense-light workload and is valid evidence if the compared
runs have the same workload/admission contract. Report it; do not hide it or
silently treat the saturated path as an unsaturated one.

---

## 10. Existing Metrics and Interpretation Guide

### Primary Metrics
- **FPS (Frames Per Second)**: Weighted average frame throughput across 300-frame measurement windows.
- **1% Low FPS**: The 1st percentile frame rate calculated over timing windows. Indicates noticeable stutter.
- **0.1% Low FPS**: The 0.1th percentile frame rate over timing windows. Indicates severe micro-stutter / hitching.
- **GPU Latency (ms)**: Percentiles ($p_{50}, p_{95}, p_{99}, \max$) of GPU rendering time per frame. $p_{95}$ is the primary metric for GPU performance gates.
- **CPU Render Submission Latency (ms)**: Percentiles ($p_{50}, p_{95}, p_{99}, \max$) of time spent by Java on the render thread submitting draw calls and encoding Metal commands.
- **Present Interval (ms)**: Percentiles ($p_{50}, p_{95}, p_{99}, \max$) of physical display presentation intervals on glass.

### Telemetry Interpretation
- **Workload Encoders**: `encoders.pass_boundaries` MUST equal `render + compute + blit`.
- **Copy Bytes**: Distinguishes CPU-to-Shared (host uploads) vs Shared-to-Private (VRAM staging transfers).
- **Private Geometry Heap**:
  - `pages_current` / `pages_peak`: Active allocation pages.
  - `page_reuse_hits_total`: Sub-allocator reuse efficiency.
  - `fallback_allocations_total`: MUST BE ZERO in healthy runs.
- **Clustered Lighting**:
  - `cluster_occupancy_p50/p95/p99/max`: Number of light emitters assigned to froxel clusters (cap is 256 per cluster).
  - `cluster_index_capacity_drops`: requested indices that did not fit the bounded index capacity. It is a workload-saturation metric, not an automatic invalidation.
- **Voxel Clipmaps**:
  - `dirty_bricks_submitted/completed`: Dynamic clipmap voxel re-rasterization rate.
  - `heap_used_bytes` / `heap_bytes`: Voxel occupancy grid VRAM consumption.

---

## 11. Evidence Labels

All performance claims in pull requests, benchmark reports, and documents MUST be labeled with one of the following four evidence tiers:

- **`PROVEN`**: Directly measured with valid, reproducible Tier C A/B testing on identical hardware/settings, OR confirmed by a deterministic capability query.
- **`SUPPORTED`**: Strong empirical evidence (e.g. Tier B detailed stage timing or isolated microbenchmark), but not yet confirmed by a clean Tier C A/B run.
- **`SPECULATIVE`**: An unverified hypothesis based on code inspection, theoretical GPU models, or micro-architectural reasoning.
- **`UNKNOWN`**: No sufficient empirical evidence available.

> [!CAUTION]
> Code inspection or theoretical reasoning alone is **NEVER** sufficient to claim a change is `PROVEN`. Citing code inspection as proof of performance improvement is strictly forbidden.

---

## 12. OptimizationHistory Rules

Agents and developers MUST read [OptimizationHistory.md](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/OptimizationHistory.md) BEFORE proposing or implementing any performance change.

### Rules of Engagement:
1. **No Repeating Rejected Experiments**: Never re-attempt an experiment marked as REJECTED in `OptimizationHistory.md` unless presenting genuinely new hardware, compiler, or architectural evidence.
2. **Post-Rollback Source Verification**: Before implementing an accepted change, verify that current source code still matches the assumptions of the original optimization.
3. **Quality Reductions are NOT Optimizations**: Lowering render distance, reducing cascade resolution, turning off lighting features, or reducing sample counts is a quality drop, not a performance optimization.
4. **Mandatory Code Removal on Negative A/B**: If a candidate fails A/B testing or shows neutral/noisy results with added complexity, the candidate code MUST be completely removed from production source code and documented in `OptimizationHistory.md`.

---

## 13. Thermal and Run-Isolation Requirements

Apple Silicon GPUs dynamically adjust clock frequencies based on SoC thermal state (`thermal_state` metadata tag in benchmark output):

1. **Measurable Thermal Policy**: Always record `thermal_state` metadata before, during, and after benchmark runs.
2. **Thermal Validity**: Reject or invalidate benchmark runs where `thermal_state` is `Serious` or `Critical`. Require comparable thermal state for baseline and candidate during A/B testing.
3. **Thermal Equilibrium**: Automation may wait for `thermal_state` to return to `Nominal` before initiating Tier C acceptance benchmark runs.

---

## 14. Compact Command Cookbook

### 1. Run Preflight Verification
```bash
scripts/run_metal_benchmark.sh --preflight-only
```

### 2. Standard Production Baseline (Tier C Acceptance)
```bash
scripts/run_metal_benchmark.sh \
  --route benchmark/routes/hdrtest-static-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json \
  --lighting-preset balanced \
  --label baseline-run1
```

### 3. Detailed Stage Attribution Run (Tier B Diagnostic)
```bash
METALLUM_L2_TIMING_DETAIL=1 \
scripts/run_metal_benchmark.sh \
  --route benchmark/routes/hdrtest-static-v1.json \
  --settings benchmark/settings/native-hdr-fancy-v1.json \
  --label detailed-attribution
```

### 4. Alternate Nether Stress Benchmark
```bash
scripts/run_metal_benchmark.sh \
  --route benchmark/routes/nether-lava-stress-v1.json \
  --settings benchmark/settings/nether-lava-stress-v1.json \
  --label nether-stress-run1
```

### 5. Compare Baseline and Candidate Reports (Single Pair)
```bash
python3 tools/metal_benchmark_report.py compare \
  --allow-source-change \
  run/logs/metallum-benchmarks/baseline.raw.jsonl \
  run/logs/metallum-benchmarks/candidate.raw.jsonl
```

### 6. Compare Multiple Baselines and Candidates (Multi-Run Aggregation)
```bash
python3 tools/metal_benchmark_report.py compare-multi \
  --allow-source-change \
  --baseline baseline1.raw.jsonl baseline2.raw.jsonl \
  --candidate candidate1.raw.jsonl candidate2.raw.jsonl
```

---

## 15. Benchmark Environment v2 Features & Capabilities (P0 Infrastructure)

Metallum Benchmark Environment v2 adds automated multi-run statistical analysis, thermal enforcement, fallback verification, fast screening, and diagnostic shader ablation:

### 15.1. Multi-Run Aggregation (`compare-multi`)
Single-pair benchmark comparisons can suffer from hardware noise or thermal drift. `compare-multi` aggregates $N$ baseline runs against $M$ candidate runs:
- Computes mean, median, min, max, standard deviation, and range spread across runs.
- Performs all $N \times M$ pairwise comparisons with gate enforcement.
- Classifies direction of change:
  - `CONSISTENT_IMPROVEMENT`: 0 regressions, all pairs improved (or positive mean FPS delta $\ge +0.5\%$).
  - `CONSISTENT_REGRESSION`: 100% of pairs regressed out of gate bounds.
  - `MIXED`: Some pairs improved while others regressed.
  - `INCONCLUSIVE`: Noise within threshold range without clear directional signal.

### 15.2. Dynamic Thermal State Semantics
- `thermal_state` is dynamic run telemetry, NOT an immutable comparison metadata key. Runs are NOT rejected merely because thermal state shifts between `nominal` and `fair`.
- Aggregates `initial_thermal_state`, `final_thermal_state`, `best_thermal_state`, `worst_thermal_state`, `states_observed`, `has_serious`, `has_critical`, and `thermal_invalid`.
- Valid thermal transitions: `nominal -> nominal`, `nominal -> fair`, `fair -> fair`, `fair -> nominal`.
- `Serious` or `Critical` thermal states during the measured interval mark the run `thermal_invalid: true` and block Tier C release acceptance.

### 15.3. Renderer Fallback Validity Rules
- **Advanced Route Fallback Rejection**: If a route configured for Advanced lighting emits `resolved_lighting_model: "vanilla"`, the benchmark analyzer rejects the report as invalid evidence.
- **Cluster Capacity Drops are Valid Evidence**: Dense lighting workloads causing `cluster_index_capacity_drops > 0` indicate light index buffer saturation under load, which is **legitimate performance evidence**, NOT a test or renderer failure.

### 15.4. Fast-Screening Mode (`--screening`)
- Activated via `scripts/run_metal_benchmark.sh --screening` (600 warmup + 600 measure) or `--screening-900` (600 warmup + 900 measure).
- Enforces 300-frame alignment (`MEASURE_FRAMES=600` or `900`).
- Sets `diagnostic_pattern: "DIAGNOSTIC_SCREENING"`.
- `metal_benchmark_report.py attest` **strictly blocks** release attestation receipts for screening runs. Screening evidence is Tier B diagnostic only.

### 15.5. Shader Ablation Diagnostics (`--ablation`) and `compare-ablation`
- Supported modes: `FULL_ADVANCED`, `NO_L3_RECEIVER`, `NO_L4_RECEIVER`, `NO_L6_RECEIVER`. Mode `NO_SURFACE_PBR` is set to `DEFERRED_UNSUPPORTED_BOUNDARY` as material specular adapters cannot be elided without perturbing terrain diffuse shading.
- Injects `#define METALLUM_ABLATE_*` preprocessor defines into shader compilation.
- **Normal A/B vs Diagnostic Ablation Comparison**:
  - `compare-multi`: Requires 100% metadata identity including `ablation_mode`. Rejects any ablation mismatch.
  - `compare-ablation`: Explicitly allows `ablation_mode` to differ between baseline and candidate while enforcing strict identity for all other metadata (route, fixture, settings, resolution, HDR, MetalFX, lighting preset, display, executor).
- **Contract Label**: Output is explicitly labeled `DIAGNOSTIC_MARGINAL_COMPARISON` and NEVER produces an acceptance receipt.
- **Marginal Delta Interpretation**: Observed delta $\Delta T = T_{\text{full}} - T_{\text{ablation}}$ measures marginal observed cost under that specific shader configuration. It is **non-additive** because GPU warps, memory latency hiding, and ALU instruction scheduling overlap execution units.
- **Zero Production Contamination**: When diagnostics are off (default `FULL_ADVANCED`), preprocessor defines evaluate to 0, ensuring zero hot-path branches or overhead via shader compiler dead-code elimination.

### 15.6. Host Machine Metal Capabilities Probe
- Capability probe artifact: [`benchmark/current/M1_PRO_GPU_CAPABILITIES.json`](file:///Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/benchmark/current/M1_PRO_GPU_CAPABILITIES.json).
- Empirical hardware profile for Apple M1 Pro host system (`GPUTimestamp` timer, stage-boundary sampling, Metal 3 / Metal 4 SDK availability).

### 15.7. Local Noise Floor & Production Contamination Status
- **Contamination Status**: `SUPPORTED_NO_CONTAMINATION_EVIDENCE` (Conclusion B). Exact pre-P0 executable state is unavailable without P0 infrastructure files (`PRE_P0_EXACT_STATE_UNAVAILABLE`), but 100% MSL preprocessor dead-code elimination, out-of-process probes, and offline Python tooling guarantee zero runtime branch/pass overhead.
- **Observed Local Repeatability Range**: On this host Apple M1 Pro (Built-in Retina 3024x1964 @ 120Hz, Native HDR, Balanced preset, MetalFX OFF, Advanced Lighting, route `hdrtest-static-v1`), same-build back-to-back runs exhibited an observed local repeatability spread of $\approx \pm 0.18$ FPS ($\pm 0.45\%$) and $\approx \pm 0.22$ ms GPU p95 for that specific measurement session. This is an empirical observation for that route and hardware setup, NOT a universal acceptance threshold. Future agents must use multi-run A/B testing (`compare-multi`) across multiple baseline and candidate runs to evaluate all performance changes.
