#!/usr/bin/env python3
"""Verify the frozen, field-only G4 one-bounce transport contract and receipt."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


class ContractError(ValueError):
    pass


PENDING_STATUS = "G4_IMPLEMENTED_FIELD_ONLY_PENDING_TIER_B"
COMPLETE_STATUS = "G4_COMPLETE_FIELD_ONLY"
PENDING_DECISION = "PENDING_TIER_B_STOP_GATE"
COMPLETE_DECISION = "PASS_FIELD_ONLY_ONE_BOUNCE"

EXPECTED_CORRECTNESS_GATES = [
    "ZERO_SOURCE_EXACT_ZERO",
    "BLACK_RHO_EXACT_ZERO",
    "RED_INPUT_NO_GREEN_BLUE",
    "VISIBLE_RED_WALL_TO_WHITE_FLOOR",
    "SEALED_SUPERCOVER_WALL_EXACT_ZERO",
    "APERTURE_OPEN_CLOSE_CONNECTIVITY",
    "UNKNOWN_FALLBACK_AIR_OCCLUDED_ZERO",
    "GLOBAL_DC_ENERGY_BOUND",
    "FINITE_NONNEGATIVE_L1_RECONSTRUCTION",
    "SAME_MODE_REPEAT_RAW_HASH",
    "STALE_EPOCH_REJECTED",
    "WRONG_THREAD_REJECTED",
    "RELEASE_WHILE_IN_FLIGHT_SAFE",
    "FORGED_NATIVE_CAPABILITY_REJECTED",
    "MISALIGNED_PACKET_REJECTED",
    "SUBMITTED_COMPLETION_STATE_FAIL_CLOSED",
    "POST_FREEZE_SOURCE_DRIFT_STALE",
    "SOURCE_LIBRARY_MODE_ASSERTED",
    "BUNDLED_LIBRARY_MODE_ASSERTED",
]

EXPECTED_MECHANICAL_KEYS = {
    "gradle_check",
    "java_swift_msl_abi",
    "source_compiled_metal_validation",
    "bundled_metallib_validation",
    "frame_graph_validation",
    "transport_cpu_tests",
    "source_chain_exclusion_test",
    "benchmark_report_contract_v2",
    "receipt_validator_self_test",
    "release_contract_gi_guard",
}

REQUIRED_SOURCE_MANIFEST = frozenset({
    "build.gradle",
    "docs/GI_G4.md",
    "scripts/run_metal_benchmark.sh",
    "tools/gi_g3_contract.py",
    "tools/gi_g4_contract.py",
    "tools/gi_release_contract_guard.sh",
    "tools/metal_benchmark_report.py",
    "tools/test_gi_release_contract_guard.sh",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticController.java",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticFieldAssembler.java",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticRuntime.java",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticTransportFieldView.java",
    "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java",
    "src/main/java/com/metallum/client/gi/source/GiDirectSourceGpuResources.java",
    "src/main/java/com/metallum/client/gi/source/GiEnvironmentSource.java",
    "src/main/java/com/metallum/client/gi/transport/GiTransportCoordinator.java",
    "src/main/java/com/metallum/client/gi/transport/GiTransportEpoch.java",
    "src/main/java/com/metallum/client/gi/transport/GiTransportGpuResources.java",
    "src/main/java/com/metallum/client/gi/transport/GiTransportLayout.java",
    "src/main/java/com/metallum/client/gi/transport/GiTransportRuntime.java",
    "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java",
    "src/main/java/com/metallum/client/metal/render/MetalDevice.java",
    "src/main/java/com/metallum/client/metal/render/MetalGpuTimingStage.java",
    "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java",
    "src/main/java/com/metallum/client/metal/render/framegraph/GiTransportFrameGraph.java",
    "src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java",
    "src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java",
    "src/main/java/com/metallum/mixin/render/GameRendererMetalFxMixin.java",
    "src/main/metal/MetallumGiTransport.metal",
    "src/main/native/MetallumNative.swift",
    "src/test/java/com/metallum/client/gi/semantic/GiSemanticTransportFieldViewTests.java",
    "src/test/java/com/metallum/client/gi/source/GiDirectSourceSourceChainTests.java",
    "src/test/java/com/metallum/client/gi/source/GiTransportGpuValidation.java",
    "src/test/java/com/metallum/client/gi/transport/GiTransportCpuTests.java",
    "src/test/java/com/metallum/client/gi/transport/GiTransportSourceChainTests.java",
    "src/test/java/com/metallum/client/metal/render/MetalRuntimeTests.java",
    "src/test/java/com/metallum/client/metal/render/framegraph/FrameGraphTests.java",
})

G4_EXACT_GI_COUNTERS = {
    "contract_version": 3,
    "resource_count": 11,
    "pass_count": 4,
    "binding_count": 0,
    "shader_symbol_count": 0,
    "dirty_queued_total": 192,
    "dirty_completed_total": 192,
    "dirty_discarded_total": 0,
    "dirty_pending": 0,
    "injection_dispatches": 192,
    "full_volume_rebuilds": 1,
    "transport_dispatches": 1,
    "field_epoch": 1,
    "stale_cell_rejects": 0,
}

G4_PROFILE_METADATA = {
    "device_name": "Apple M1 Pro",
    "monitor": "Built-in Retina Display",
    "render_width": 3024,
    "render_height": 1964,
    "display_width": 3024,
    "display_height": 1964,
    "refresh_hz": 120,
    "display_sync_enabled": False,
    "scaler_active": False,
    "hdr_output_mode": "ENHANCED",
    "source_encoding": "LINEAR",
    "persistent_metalfx_mode": "off",
    "global_illumination_mode": "g4_transport",
    "graphics_preset": "fancy",
    "benchmark_simulation_frozen": True,
    "native_shader_library_mode": "PRECOMPILED",
    "native_shader_source_compile_count": 0,
    "native_pipeline_failure_count": 0,
}

G4_PROFILE_GENERATION = {
    "executor": "metal3",
    "lighting_preset": "balanced",
    "render_width": 3024,
    "render_height": 1964,
    "display_width": 3024,
    "display_height": 1964,
    "resolved_render_contract": "metallum",
    "resolved_lighting_model": "advanced",
    "resolved_output_mode": "hdr",
    "resolved_upscale_mode": "native",
    "resolved_interpolation_mode": "off",
}

G4_CONSOLE_TOKENS = (
    "Metallum benchmark preflight passed",
    "display: Built-in Retina Display, 3024x1964@120, exclusive fullscreen",
    "pacing: VSync off, maxFps=260",
    "scene: output=scene, source=sRGB, lighting=advanced (true/balanced)",
    "MetalFX: OFF (persistent config remains off)",
    "frames: 600 warmup + 600 measurement",
    "GI_G4_TRANSPORT_ADMISSION mode=g4_transport g2_capture=true g3_inject=true "
    "frozen_near_cascade=true jacobi_iterations=1 field_only=true receiver=false "
    "image_binding=false diagnostic_only=true release=false status=REQUESTED",
    "METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=600 "
    "framebuffer=3024x1964",
    "Benchmark validated: COMPLETE present, no FAIL/screenshots, dropped timing events = 0",
)


def strict_json_text(value: str, label: str) -> Any:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ContractError(f"duplicate JSON key {key!r} in {label}")
            result[key] = value
        return result

    def parse_float(raw: str) -> float:
        parsed = float(raw)
        if not math.isfinite(parsed):
            raise ContractError(f"non-finite JSON number in {label}")
        return parsed

    def reject_constant(raw: str) -> None:
        raise ContractError(f"non-standard JSON constant {raw!r} in {label}")

    return json.loads(
        value,
        object_pairs_hook=pairs,
        parse_float=parse_float,
        parse_constant=reject_constant,
    )


def strict_object(path: Path) -> dict[str, Any]:
    value = strict_json_text(path.read_text(encoding="utf-8"), str(path))
    if not isinstance(value, dict):
        raise ContractError(f"{path} must contain an object")
    return value


def strict_json_lines(path: Path) -> list[dict[str, Any]]:
    values: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        value = strict_json_text(line, f"{path}:{line_number}")
        if not isinstance(value, dict):
            raise ContractError(f"{path}:{line_number} must contain an object")
        values.append(value)
    if not values:
        raise ContractError(f"{path} contains no JSON timing windows")
    return values


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8")


def strip_c_comments(value: str) -> str:
    value = re.sub(r"/\*.*?\*/", "", value, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", value)


def require_tokens(value: str, tokens: tuple[str, ...], label: str) -> None:
    for token in tokens:
        if token not in value:
            raise ContractError(f"{label} token is missing: {token}")


def reject_tokens(value: str, tokens: tuple[str, ...], label: str) -> None:
    for token in tokens:
        if token in value:
            raise ContractError(f"{label} contains forbidden token: {token}")


def close(actual: Any, expected: Any, label: str, tolerance: float = 1e-12) -> None:
    if isinstance(actual, bool) or not isinstance(actual, (int, float)) \
            or not math.isclose(float(actual), float(expected), rel_tol=0.0, abs_tol=tolerance):
        raise ContractError(f"{label} differs: {actual!r} != {expected!r}")


def verify_source_hashes(root: Path, implementation: dict[str, Any]) -> None:
    manifests = implementation.get("source_files_sha256")
    if not isinstance(manifests, dict) or set(manifests) != REQUIRED_SOURCE_MANIFEST:
        missing = sorted(REQUIRED_SOURCE_MANIFEST - set(manifests or {})) \
            if isinstance(manifests, dict) else sorted(REQUIRED_SOURCE_MANIFEST)
        extra = sorted(set(manifests or {}) - REQUIRED_SOURCE_MANIFEST) \
            if isinstance(manifests, dict) else []
        raise ContractError(
            f"G4 exact source digest manifest differs; missing={missing}, extra={extra}"
        )
    for relative, expected in manifests.items():
        if not isinstance(relative, str) or relative.startswith(("/", "../")) \
                or "/../" in relative or "\\" in relative:
            raise ContractError(f"unsafe G4 source digest path: {relative!r}")
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ContractError(f"invalid G4 SHA-256 digest: {relative}")
        path = root / relative
        if not path.is_file():
            raise ContractError(f"required G4 source is missing: {relative}")
        if sha256(path) != expected:
            raise ContractError(f"G4 source digest differs: {relative}")


def required_artifact(root: Path, descriptor: Any, label: str) -> Path:
    if not isinstance(descriptor, dict) or set(descriptor) != {"path", "sha256"}:
        raise ContractError(f"G4 {label} artifact descriptor differs")
    relative = descriptor["path"]
    expected = descriptor["sha256"]
    if not isinstance(relative, str) or relative.startswith(("/", "../")) \
            or "/../" in relative or "\\" in relative:
        raise ContractError(f"unsafe G4 {label} artifact path")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ContractError(f"invalid G4 {label} artifact digest")
    path = root / relative
    if not path.is_file():
        raise ContractError(f"required G4 {label} artifact is missing: {relative}")
    try:
        path.resolve().relative_to(root.resolve())
    except ValueError as error:
        raise ContractError(f"G4 {label} artifact resolves outside the repository") from error
    if sha256(path) != expected:
        raise ContractError(f"G4 {label} artifact digest differs")
    return path


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ContractError(f"{label} must be an object")
    return value


def require_exact_scalar(
    value: dict[str, Any], key: str, expected: Any, label: str,
) -> None:
    actual = value.get(key)
    if type(actual) is not type(expected) or actual != expected:
        raise ContractError(f"{label}.{key} differs: {actual!r} != {expected!r}")


def finite_nonnegative(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{label} must be a number")
    result = float(value)
    if not math.isfinite(result) or result < 0.0:
        raise ContractError(f"{label} must be finite and >= 0")
    return result


def verify_profile(metadata: Any, generation: Any, label: str) -> None:
    metadata_object = require_object(metadata, f"{label}.metadata")
    generation_object = require_object(generation, f"{label}.renderer_generation")
    for key, expected in G4_PROFILE_METADATA.items():
        require_exact_scalar(metadata_object, key, expected, f"{label}.metadata")
    for key, expected in G4_PROFILE_GENERATION.items():
        require_exact_scalar(generation_object, key, expected, f"{label}.renderer_generation")
    if finite_nonnegative(
        metadata_object.get("current_edr_headroom"),
        f"{label}.metadata.current_edr_headroom",
    ) <= 1.0:
        raise ContractError(f"{label} did not prove active HDR headroom")


def verify_gi_window(value: Any, label: str) -> dict[str, Any]:
    gi = require_object(value, f"{label}.global_illumination")
    require_exact_scalar(gi, "mode", "active", f"{label}.global_illumination")
    for key, expected in G4_EXACT_GI_COUNTERS.items():
        require_exact_scalar(gi, key, expected, f"{label}.global_illumination")
    for key in ("source_epoch", "probe_epoch"):
        epoch = gi.get(key)
        if isinstance(epoch, bool) or not isinstance(epoch, int) or epoch <= 0:
            raise ContractError(f"{label}.global_illumination.{key} must be positive")
    fallbacks = require_object(
        gi.get("fallback_reasons"), f"{label}.global_illumination.fallback_reasons"
    )
    if not fallbacks or any(
        isinstance(count, bool) or not isinstance(count, int) or count != 0
        for count in fallbacks.values()
    ):
        raise ContractError(f"{label} contains a G4 fallback reason")
    return gi


def verify_transport_stage(value: Any, label: str) -> dict[str, float]:
    stage = require_object(value, label)
    expected_keys = {
        "frames", "average_ms", "p50_ms", "p95_ms", "p99_ms", "maximum_ms",
    }
    if set(stage) != expected_keys or type(stage.get("frames")) is not int \
            or stage["frames"] != 1:
        raise ContractError(f"{label} must describe exactly one timed dispatch")
    metrics = {
        key: finite_nonnegative(stage.get(key), f"{label}.{key}")
        for key in ("average_ms", "p50_ms", "p95_ms", "p99_ms", "maximum_ms")
    }
    if metrics["average_ms"] > metrics["maximum_ms"] or not (
        metrics["p50_ms"] <= metrics["p95_ms"]
        <= metrics["p99_ms"] <= metrics["maximum_ms"]
    ):
        raise ContractError(f"{label} timing distribution is not monotonic")
    if metrics["p95_ms"] > 4.0 or metrics["maximum_ms"] > 6.0:
        raise ContractError(f"{label} exceeds the predeclared Tier B stop-gate")
    return metrics


def derive_runtime_results(windows: list[dict[str, Any]]) -> tuple[dict[str, Any], int]:
    warmup: list[dict[str, Any]] = []
    measured: list[dict[str, Any]] = []
    transport_timings: list[dict[str, float]] = []
    measured_gi: list[dict[str, Any]] = []

    for index, window in enumerate(windows, 1):
        label = f"G4 raw window {index}"
        require_exact_scalar(window, "schema_version", 6, label)
        require_exact_scalar(window, "detail_enabled", True, label)
        require_exact_scalar(window, "dropped_timing_events", 0, label)
        verify_profile(window.get("metadata"), window.get("renderer_generation"), label)
        gi = verify_gi_window(window.get("global_illumination"), label)
        benchmark = require_object(window.get("benchmark"), f"{label}.benchmark")
        phase = benchmark.get("phase")
        if phase not in {"startup", "warmup", "measure"}:
            raise ContractError(f"{label}.benchmark.phase is not a recognized phase")
        require_exact_scalar(benchmark, "enabled", True, f"{label}.benchmark")
        require_exact_scalar(benchmark, "scaler_mode", "OFF", f"{label}.benchmark")
        generation = benchmark.get("generation")
        if isinstance(generation, bool) or not isinstance(generation, int) \
                or generation < 0:
            raise ContractError(f"{label}.benchmark.generation must be an integer >= 0")
        require_exact_scalar(
            benchmark, "segment_index", -1 if phase == "startup" else 0,
            f"{label}.benchmark",
        )
        stages = require_object(window.get("stages"), f"{label}.stages")
        transport_stage = stages.get("GI_TRANSPORT")

        if phase in {"warmup", "measure"}:
            frames = window.get("presented_frames")
            if isinstance(frames, bool) or not isinstance(frames, int) or frames <= 0:
                raise ContractError(f"{label}.presented_frames must be a positive integer")
            (warmup if phase == "warmup" else measured).append(window)
            if phase == "measure":
                measured_gi.append(gi)

        if transport_stage is not None:
            if phase != "warmup":
                raise ContractError("GI_TRANSPORT timing must occur only during warmup")
            transport_timings.append(
                verify_transport_stage(transport_stage, f"{label}.stages.GI_TRANSPORT")
            )

    if sum(window["presented_frames"] for window in warmup) != 600:
        raise ContractError("G4 raw receipt must contain exactly 600 warmup frames")
    if sum(window["presented_frames"] for window in measured) != 600:
        raise ContractError("G4 raw receipt must contain exactly 600 measured frames")
    if len(transport_timings) != 1:
        raise ContractError("G4 raw receipt must contain one warmup-only GI_TRANSPORT timing")
    if not measured_gi:
        raise ContractError("G4 raw receipt contains no measured GI telemetry")

    stable_keys = (
        "dirty_queued_total", "dirty_completed_total", "dirty_discarded_total",
        "dirty_pending", "injection_dispatches", "full_volume_rebuilds",
        "transport_dispatches", "source_epoch", "probe_epoch", "field_epoch",
        "stale_cell_rejects",
    )
    for key in stable_keys:
        values = [gi[key] for gi in measured_gi]
        if min(values) != max(values):
            raise ContractError(f"G4 measured counter grew during measurement: {key}")

    timing = transport_timings[0]
    return {
        "builds": max(gi["full_volume_rebuilds"] for gi in measured_gi),
        "transport_dispatches": max(gi["transport_dispatches"] for gi in measured_gi),
        "measured_dispatch_growth": (
            max(gi["transport_dispatches"] for gi in measured_gi)
            - min(gi["transport_dispatches"] for gi in measured_gi)
        ),
        "dropped_timing_events": 0,
        "renderer_fallbacks": 0,
        "gi_transport_p95_ms": timing["p95_ms"],
        "gi_transport_maximum_ms": timing["maximum_ms"],
    }, len(measured)


def verify_summary_receipt(
    summary: dict[str, Any], results: dict[str, Any], measured_window_count: int,
) -> None:
    label = "G4 summary"
    require_exact_scalar(summary, "presented_frames", 600, label)
    require_exact_scalar(summary, "detail_enabled", True, label)
    require_exact_scalar(summary, "dropped_timing_events", 0, label)
    require_exact_scalar(summary, "schema_versions", [6], label)
    require_exact_scalar(summary, "metal_validation_contract", False, label)
    selection = summary.get("selection")
    if not isinstance(selection, str) \
            or "phase=measure segment=0 scaler=OFF" not in selection:
        raise ContractError("G4 summary measurement selection differs")
    verify_profile(summary.get("metadata"), summary.get("renderer_generation"), label)

    gi = require_object(summary.get("global_illumination"), f"{label}.global_illumination")
    require_exact_scalar(gi, "mode", "active", f"{label}.global_illumination")
    require_exact_scalar(gi, "contract_version", 3, f"{label}.global_illumination")
    require_exact_scalar(
        gi, "window_count", measured_window_count, f"{label}.global_illumination"
    )
    counters = require_object(gi.get("counters"), f"{label}.global_illumination.counters")
    for key, expected in G4_EXACT_GI_COUNTERS.items():
        if key == "contract_version":
            continue
        aggregate = require_object(
            counters.get(key), f"{label}.global_illumination.counters.{key}"
        )
        for aggregate_key in ("window_minimum", "window_maximum", "last_window"):
            require_exact_scalar(
                aggregate, aggregate_key, expected,
                f"{label}.global_illumination.counters.{key}",
            )
    fallbacks = require_object(
        gi.get("fallback_reasons"), f"{label}.global_illumination.fallback_reasons"
    )
    if not fallbacks or any(type(count) is not int or count != 0 for count in fallbacks.values()):
        raise ContractError("G4 summary contains a renderer/GI fallback")
    stages = summary.get("stages", {})
    if not isinstance(stages, dict) or stages.get("GI_TRANSPORT") is not None:
        raise ContractError("G4 measured summary contains GI_TRANSPORT work")
    if results["measured_dispatch_growth"] != 0:
        raise ContractError("G4 measured dispatch growth is not zero")


def verify_console_receipt(console_text: str) -> None:
    if not console_text.strip():
        raise ContractError("G4 console artifact is empty")
    if "METALLUM_BENCHMARK EVENT=FAIL" in console_text:
        raise ContractError("G4 console contains a benchmark FAIL event")
    for token in G4_CONSOLE_TOKENS:
        if token not in console_text:
            raise ContractError(f"G4 console admission/profile token is missing: {token}")
    admission_pattern = re.compile(
        r"METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION "
        r"expected=advanced schema=5 defaults_used=false requested=advanced "
        r"resolved=advanced l3=true l5=true l6=true status=PASS(?: [^\n]*)?"
    )
    if len(admission_pattern.findall(console_text)) != 1:
        raise ContractError("G4 console must contain one exact Advanced admission PASS")


def recompute_summary(root: Path, raw_path: Path) -> dict[str, Any]:
    command = [
        sys.executable,
        str(root / "tools/metal_benchmark_report.py"),
        "summarize",
        str(raw_path),
        "--measure-frames", "600",
        "--segment", "0",
        "--scaler-mode", "OFF",
        "--json",
    ]
    result = subprocess.run(command, cwd=root, text=True, capture_output=True, check=False)
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ContractError(f"G4 raw report fails the canonical parser: {detail}")
    value = strict_json_text(result.stdout, "canonical recomputed G4 summary")
    if not isinstance(value, dict):
        raise ContractError("canonical recomputed G4 summary is not an object")
    return value


def verify_runtime_receipt(
    root: Path,
    raw_path: Path,
    summary_path: Path,
    console_path: Path,
    *,
    canonical_report_validation: bool = True,
) -> dict[str, Any]:
    if not raw_path.name.endswith(".raw.jsonl"):
        raise ContractError("G4 raw artifact must use the .raw.jsonl suffix")
    stem = raw_path.name[:-len(".raw.jsonl")]
    if summary_path != raw_path.with_name(stem + ".summary.json") \
            or console_path != raw_path.with_name(stem + ".console.log"):
        raise ContractError("G4 raw/summary/console artifacts must share one exact stem")

    windows = strict_json_lines(raw_path)
    summary = strict_object(summary_path)
    results, measured_window_count = derive_runtime_results(windows)
    verify_summary_receipt(summary, results, measured_window_count)
    verify_console_receipt(console_path.read_text(encoding="utf-8"))

    if canonical_report_validation:
        recomputed = recompute_summary(root, raw_path)
        supplied_report = summary.get("report")
        recomputed_report = recomputed.get("report")
        if not isinstance(supplied_report, str) or not isinstance(recomputed_report, str) \
                or Path(supplied_report).resolve() != raw_path.resolve() \
                or Path(recomputed_report).resolve() != raw_path.resolve():
            raise ContractError("G4 summary does not identify the exact raw artifact")
        supplied = dict(summary)
        expected = dict(recomputed)
        supplied["report"] = "<verified-raw>"
        expected["report"] = "<verified-raw>"
        if supplied != expected:
            raise ContractError("G4 summary differs from canonical raw-report recomputation")
    return results


def verify_declared_runtime_results(runtime: Any, derived: dict[str, Any]) -> None:
    if not isinstance(runtime, dict) or set(runtime) != set(derived):
        raise ContractError("G4 live result set differs from raw-derived results")
    for key, expected in derived.items():
        actual = runtime.get(key)
        if key in {"gi_transport_p95_ms", "gi_transport_maximum_ms"}:
            close(actual, expected, f"G4 raw-derived {key}")
        elif type(actual) is not type(expected) or actual != expected:
            raise ContractError(
                f"G4 handwritten result differs from raw-derived {key}: "
                f"{actual!r} != {expected!r}"
            )


def verify_evidence(root: Path, evidence: dict[str, Any]) -> None:
    status = evidence.get("status")
    decision = evidence.get("decision")
    if evidence.get("schema_version") != 1 \
            or evidence.get("id") != "gi-g4-transport-evidence-v1" \
            or evidence.get("stage") != "G4" \
            or status not in {PENDING_STATUS, COMPLETE_STATUS} \
            or (status == PENDING_STATUS and decision != PENDING_DECISION) \
            or (status == COMPLETE_STATUS and decision != COMPLETE_DECISION) \
            or evidence.get("g5_allowed") is not False:
        raise ContractError("G4 decision or next-stage boundary differs")

    implementation = evidence.get("implementation")
    if not isinstance(implementation, dict) \
            or implementation.get("enabled_environment") != "METALLUM_GI_G4_TRANSPORT=1" \
            or implementation.get("production_default_enabled") is not False:
        raise ContractError("G4 implementation admission differs")
    verify_source_hashes(root, implementation)

    scope = evidence.get("scope")
    expected_scope = {
        "field_only": True,
        "frozen_near_cascade_only": True,
        "terrain_or_image_bindings": 0,
        "receiver_passes": 0,
        "fragment_or_render_passes": 0,
        "production_shader_changes": 0,
        "bounce_count": 1,
        "scroll_or_rebuild_after_ready": False,
        "dynamic_held_entity_sources": 0,
        "reads_camera_depth_history_or_lightmap": False,
    }
    if scope != expected_scope:
        raise ContractError("G4 field-only scope widened")

    input_contract = evidence.get("input_contract")
    expected_input = {
        "g2_source": "accepted immutable transport field view",
        "g3_source": "ready private direct irradiance and geometry context",
        "required_initial_g3_bricks": 192,
        "required_pending_bricks": 0,
        "required_discarded_bricks": 0,
        "epoch_and_origin_match": True,
        "unknown_or_fallback_visibility": "ZERO_TRANSFER",
    }
    if input_contract != expected_input:
        raise ContractError("G4 accepted G2/G3 input boundary differs")

    topology = evidence.get("topology")
    expected_topology = {
        "cascade_count": 1,
        "cascade": "near",
        "cells_per_axis": 32,
        "cell_size_blocks": 2,
        "cell_count": 32768,
        "accepted_cell_bytes": 16,
        "jacobi_iterations": 1,
        "signed_directions": 26,
        "maximum_distance_cells": 8,
        "maximum_distance_blocks": 16,
        "stencil_offsets": 208,
        "bounce_format": "RGBA16Float",
        "sh_formats": ["RGBA16Float", "RGBA16Float", "RGBA16Float"],
        "confidence_format": "R8Unorm",
        "storage_mode": "Private",
        "private_resource_count": 5,
        "compute_pass_count": 2,
    }
    if topology != expected_topology:
        raise ContractError("G4 fixed one-iteration topology differs")

    numerical = evidence.get("numerical_contract")
    if not isinstance(numerical, dict) \
            or numerical.get("bounce_equation") != "L_bounce0=rho/pi*E_direct" \
            or numerical.get("form_weight") != "1/(length(direction)*distance^2)" \
            or numerical.get("form_factor") != "pi*weight/normalization*source_face_support" \
            or numerical.get("source_face_support_l1_normalized") is not True \
            or numerical.get("sum_form_factor_upper_bound") != "pi" \
            or numerical.get("sh_basis") != ["1", "x", "y", "z"] \
            or numerical.get("sh_layout") != "channel-major RGB, each [c0,cx,cy,cz]" \
            or numerical.get("receiver_rho_application_count") != 0 \
            or numerical.get("source_rho_application_count") != 1:
        raise ContractError("G4 SH, rho/pi or energy contract differs")
    close(numerical.get("form_weight_normalization"), 29.17999846648958,
          "G4 form-weight normalization")
    close(numerical.get("fp16_absolute_tolerance"), 1.0 / 1024.0,
          "G4 FP16 absolute tolerance")
    close(numerical.get("fp16_relative_tolerance"), 1.0 / 512.0,
          "G4 FP16 relative tolerance")
    if numerical.get("global_energy_bound") \
            != "sum(E_indirect_DC)<=sum(rho*E_direct)+max(1/1024,reflected_input/512)":
        raise ContractError("G4 captured energy inequality differs")

    resources = evidence.get("resources")
    expected_resources = {
        "g2_conservative_end_to_end_bytes": 18_022_528,
        "g3_native_allocated_size_bytes": 1_104_096,
        "g3_java_persistent_packet_bytes": 70_216,
        "g3_end_to_end_bytes": 1_174_312,
        "g4_native_allocated_size_bytes": 2_916_480,
        "g4_java_persistent_packet_bytes": 524_608,
        "g4_end_to_end_bytes": 3_441_088,
        "combined_end_to_end_bytes": 22_637_928,
        "diffuse_gi_budget_bytes": 25_165_824,
        "actual_metal_allocated_size_required": True,
        "persistent_java_ffm_packets_required": True,
        "small_on_heap_control_objects_excluded": True,
        "within_budget": True,
    }
    if resources != expected_resources:
        raise ContractError("G4 inherited memory accounting differs")
    if resources["g3_end_to_end_bytes"] \
            != resources["g3_native_allocated_size_bytes"] \
            + resources["g3_java_persistent_packet_bytes"] \
            or resources["g4_end_to_end_bytes"] \
            != resources["g4_native_allocated_size_bytes"] \
            + resources["g4_java_persistent_packet_bytes"] \
            or resources["combined_end_to_end_bytes"] \
            != resources["g2_conservative_end_to_end_bytes"] \
            + resources["g3_end_to_end_bytes"] + resources["g4_end_to_end_bytes"]:
        raise ContractError("G4 combined memory arithmetic differs")
    if resources["combined_end_to_end_bytes"] > resources["diffuse_gi_budget_bytes"]:
        raise ContractError("G4 exceeds the 24 MiB diffuse-GI budget")

    validation = evidence.get("metal_validation")
    if not isinstance(validation, dict) \
            or validation.get("complete_g3_logical_bricks") != 192 \
            or validation.get("g3_bounded_batches") != 24 \
            or validation.get("g3_scheduler") != {
                "queued": 192, "completed": 192, "discarded": 0,
                "pending": 0, "full_volume_rebuilds": 1,
            } \
            or validation.get("persistent_bytes") != 1_081_344 \
            or validation.get("staging_bytes") != 524_416 \
            or validation.get("readback_bytes") != 1_310_720 \
            or validation.get("native_accounted_bytes") \
            != resources["g4_native_allocated_size_bytes"] \
            or validation.get("java_persistent_packet_bytes") \
            != resources["g4_java_persistent_packet_bytes"] \
            or validation.get("end_to_end_bytes") != resources["g4_end_to_end_bytes"]:
        raise ContractError("G4 source/bundled allocatedSize validation receipt differs")
    if validation["native_accounted_bytes"] != validation["persistent_bytes"] \
            + validation["staging_bytes"] + validation["readback_bytes"] \
            or validation["end_to_end_bytes"] != validation["native_accounted_bytes"] \
            + validation["java_persistent_packet_bytes"]:
        raise ContractError("G4 native/Java validation memory arithmetic differs")
    source_validation = validation.get("source")
    bundled_validation = validation.get("bundled")
    if not isinstance(source_validation, dict) or not isinstance(bundled_validation, dict) \
            or source_validation.get("shader_library_mode") != 2 \
            or bundled_validation.get("shader_library_mode") != 1:
        raise ContractError("G4 actual source/bundled shader-library modes differ")
    source_hash = source_validation.get("raw_sha256")
    bundled_hash = bundled_validation.get("raw_sha256")
    if not isinstance(source_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", source_hash) \
            or source_hash != bundled_hash:
        raise ContractError("G4 source/bundled raw validation hashes differ")

    correctness = evidence.get("correctness")
    if not isinstance(correctness, dict) \
            or correctness.get("required_gates") != EXPECTED_CORRECTNESS_GATES:
        raise ContractError("G4 correctness stop-gates differ")
    results = correctness.get("results")
    if not isinstance(results, dict) or set(results) != set(EXPECTED_CORRECTNESS_GATES):
        raise ContractError("G4 correctness result set differs")
    if status == COMPLETE_STATUS:
        if any(value != "PASS" for value in results.values()):
            raise ContractError("complete G4 correctness results must all be PASS")
    elif any(value not in {"PASS", "PENDING"} for value in results.values()):
        raise ContractError("pending G4 correctness results contain an invalid state")

    mechanical = evidence.get("mechanical_verification")
    if not isinstance(mechanical, dict) or set(mechanical) != EXPECTED_MECHANICAL_KEYS:
        raise ContractError("G4 mechanical verification set differs")
    allowed_mechanical = "PASS" if status == COMPLETE_STATUS else {"PASS", "PENDING"}
    if status == COMPLETE_STATUS:
        if any(value != allowed_mechanical for value in mechanical.values()):
            raise ContractError("complete G4 evidence contains a non-PASS mechanical result")
    elif any(value not in allowed_mechanical for value in mechanical.values()):
        raise ContractError("pending G4 evidence contains an invalid mechanical result")

    tier = evidence.get("tier_b")
    if not isinstance(tier, dict) \
            or tier.get("profile") \
            != "Apple M1 Pro, 3024x1964 HDR, Advanced/Balanced, native resolution, MetalFX and VSync off" \
            or tier.get("warmup_frames") != 600 or tier.get("measure_frames") != 600 \
            or tier.get("expected_builds") != 1 \
            or tier.get("expected_transport_dispatches") != 1 \
            or tier.get("expected_measured_dispatch_growth") != 0 \
            or tier.get("maximum_p95_ms") != 4.0 \
            or tier.get("maximum_single_dispatch_ms") != 6.0 \
            or tier.get("requires_zero_timing_drops") is not True \
            or tier.get("requires_no_renderer_fallback") is not True \
            or tier.get("classification") \
            != "TIER_B_DIAGNOSTIC_ATTRIBUTION_NOT_TIER_C_NOT_PRODUCTION_FPS":
        raise ContractError("G4 predeclared Tier B stop-gate differs")
    completed = tier.get("completed")
    if completed is not (status == COMPLETE_STATUS):
        raise ContractError("G4 status and Tier B completion disagree")
    if not completed:
        if tier.get("results") is not None or tier.get("artifacts") != {}:
            raise ContractError("pending G4 evidence must not claim runtime artifacts or results")
    else:
        artifacts = tier.get("artifacts")
        if not isinstance(artifacts, dict) or set(artifacts) != {"raw", "summary", "console_log"}:
            raise ContractError("G4 runtime artifact set differs")
        raw_path = required_artifact(root, artifacts["raw"], "raw")
        summary_path = required_artifact(root, artifacts["summary"], "summary")
        console_path = required_artifact(root, artifacts["console_log"], "console_log")
        derived = verify_runtime_receipt(root, raw_path, summary_path, console_path)
        verify_declared_runtime_results(tier.get("results"), derived)

    limitations = evidence.get("limitations")
    if not isinstance(limitations, list) \
            or not any("not visual or product acceptance" in value for value in limitations) \
            or not any("not Tier C" in value for value in limitations):
        raise ContractError("G4 evidence limitations hide the field-only/Tier-B boundary")


def verify_source_contract(root: Path) -> None:
    layout = source(root, "src/main/java/com/metallum/client/gi/transport/GiTransportLayout.java")
    runtime = source(root, "src/main/java/com/metallum/client/gi/transport/GiTransportRuntime.java")
    epoch = source(root, "src/main/java/com/metallum/client/gi/transport/GiTransportEpoch.java")
    resources = source(root, "src/main/java/com/metallum/client/gi/transport/GiTransportGpuResources.java")
    coordinator = source(root, "src/main/java/com/metallum/client/gi/transport/GiTransportCoordinator.java")
    direct_coordinator = source(
        root, "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java"
    )
    direct_resources = source(
        root, "src/main/java/com/metallum/client/gi/source/GiDirectSourceGpuResources.java"
    )
    environment_source = source(
        root, "src/main/java/com/metallum/client/gi/source/GiEnvironmentSource.java"
    )
    light_registry = source(
        root, "src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java"
    )
    semantic = source(root, "src/main/java/com/metallum/client/gi/semantic/GiSemanticTransportFieldView.java")
    frame_graph = source(root, "src/main/java/com/metallum/client/metal/render/framegraph/GiTransportFrameGraph.java")
    command_encoder = source(root, "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java")
    device = source(root, "src/main/java/com/metallum/client/metal/render/MetalDevice.java")
    timing = source(root, "src/main/java/com/metallum/client/metal/render/MetalGpuTimingStage.java")
    bridge = source(root, "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java")
    native = source(root, "src/main/native/MetallumNative.swift")
    metal = source(root, "src/main/metal/MetallumGiTransport.metal")
    gradle = source(root, "build.gradle")
    launcher = source(root, "scripts/run_metal_benchmark.sh")
    release_guard = source(root, "tools/gi_release_contract_guard.sh")
    release_guard_test = source(root, "tools/test_gi_release_contract_guard.sh")
    reporter = source(root, "tools/metal_benchmark_report.py")
    gpu_validation = source(
        root, "src/test/java/com/metallum/client/gi/source/GiTransportGpuValidation.java"
    )
    frame_graph_tests = source(
        root, "src/test/java/com/metallum/client/metal/render/framegraph/FrameGraphTests.java"
    )

    require_tokens(layout, (
        "ABI_VERSION = 1", "LAYOUT_BYTES = 160", "HEADER_BYTES = 128",
        "CELL_BYTES = GiSemanticTransportFieldView.CELL_BYTES", "STATS_BYTES = 192",
        "JAVA_PERSISTENT_PACKET_BYTES", "HEADER_BYTES + CELLS_BYTES + STATS_BYTES",
        "CASCADE_COUNT = 1", "CELL_SIZE_BLOCKS = 2", "EDGE = GiSemanticTransportFieldView.EDGE",
        "CELL_COUNT = GiSemanticTransportFieldView.CELL_COUNT", "ITERATION_COUNT = 1",
        "MAXIMUM_DISTANCE = 8", "SIGNED_DIRECTION_COUNT = 26",
        "STENCIL_OFFSET_COUNT = SIGNED_DIRECTION_COUNT * MAXIMUM_DISTANCE",
        "FORM_WEIGHT_NORMALIZATION = 29.17999846648958",
        "FP16_ABSOLUTE_TOLERANCE = 1.0F / 1_024.0F",
        "FP16_RELATIVE_TOLERANCE = 1.0F / 512.0F",
        "PRIVATE_RESOURCE_COUNT = 5", "COMPUTE_PASS_COUNT = 2",
        "RGBA16_FLOAT_PIXEL_FORMAT = 115", "R8_UNORM_PIXEL_FORMAT = 10",
    ), "G4 Java layout")
    require_tokens(runtime, (
        'TRANSPORT_ENV = "METALLUM_GI_G4_TRANSPORT"', "System.getenv(TRANSPORT_ENV)",
    ), "G4 runtime admission")
    require_tokens(epoch, (
        "worldGeneration", "clipmapGeneration", "paletteGeneration", "contentGeneration",
        "staticSourceEpoch", "environmentEpoch", "sourceStamp", "origins differ",
    ), "G4 frozen epoch")
    require_tokens(semantic, (
        "CELL_BYTES = 16", "EDGE = GiFieldLayout.CELLS_PER_AXIS", "CELL_COUNT",
        "copyNearCascade",
        "nearOriginX", "nearOriginY", "nearOriginZ",
    ), "G4 accepted semantic view")
    require_tokens(resources, (
        "validateNativeAbi", "PreparedFrozen", "metallum_gi_transport_encode_frozen_v1",
        "captureVolumeOnce",
        "deferredRelease.accept", "STATUS_STALE", "STATUS_WRONG_THREAD",
        "metallum_gi_transport_report_stale_v1",
    ), "G4 Java/native resources")
    require_tokens(coordinator, (
        "TransportSource", "frozenEpoch", "STATUS_INPUT_NOT_READY", "STATUS_NO_WORK",
        "GiTransportEpoch.from", "STATUS_STALE",
        "Creates every fixed Java/native resource at device admission",
        "this.resources = GiTransportGpuResources.create(",
        "SUBMITTED", "READY", "FAILED", "hasAcceptedEpoch", "observeFrame",
        "reportStale", "MAX_SUBMISSION_FRAMES",
    ), "G4 admission coordinator")
    require_tokens(direct_coordinator, (
        "public @Nullable TransportSource transportSource()",
        "public static final class TransportSource", "private TransportSource(",
        "TransportSourceIdentity",
        "queue.completed() != GiDirectSourceLayout.TOTAL_BRICKS",
        "queue.pending() != 0", "queue.discarded() != 0L",
        "!stats.ready()", "stats.buildInFlight()", "transportContextHandle()",
    ), "G4 completed-G3 admission")
    require_tokens(direct_resources, (
        "public static final long JAVA_PERSISTENT_PACKET_BYTES = HEADER_BYTES",
        "GiDirectSourceLayout.MAX_DRAIN_PER_FRAME", "STATS_BYTES",
    ), "G3 persistent Java packet owner")
    require_tokens(gpu_validation, (
        "GiDirectSourceGpuResources.JAVA_PERSISTENT_PACKET_BYTES",
        "== 70_216L", "GiTransportLayout.JAVA_PERSISTENT_PACKET_BYTES == 524_608L",
    ), "G3/G4 persistent Java packet census")
    require_tokens(environment_source, (
        "Allocation-free digest for observing whether a latched G4 source became stale",
        "public static long quantizedDigest(final EnvironmentDescriptor descriptor)",
    ), "G4 frozen environment observation")
    require_tokens(light_registry, (
        "staticSourceIdentityMatchesForGi", "this.activeWorld.staticEpoch == expectedEpoch",
    ), "G4 frozen static-source observation")
    encode_frame = coordinator[
        coordinator.index("public int encodeFrame") : coordinator.index("public boolean isFrozen")
    ]
    reject_tokens(strip_c_comments(encode_frame), (
        "Arena.allocate", "makeBuffer", "makeTexture", "captureVolumeOnce",
        "GiTransportGpuResources.create", "commandQueue", "MemorySegment device",
    ), "G4 render-loop coordinator")

    require_tokens(frame_graph, (
        'GRAPH_ID = "gi-g4-frozen-transport-v1"', "gi_bounce_init",
        "gi_jacobi_transport_sh", "G2_TRANSPORT_CELLS", "G3_GEOMETRY",
        "G3_DIRECT_IRRADIANCE", "INDIRECT_SH_R", "INDIRECT_SH_G", "INDIRECT_SH_B",
        "CONFIDENCE", "EncoderClass.COMPUTE", "PipelineStage.COMPUTE",
        "ResourceRole.GENERIC",
    ), "G4 compute-only frame graph")
    reject_tokens(frame_graph, (
        "ResourceRole.SCENE_COLOR", "ResourceRole.PRESENT", "PipelineStage.FRAGMENT",
        "EncoderClass.RENDER", "PipelineStage.VERTEX",
    ), "G4 field-only frame graph")
    require_tokens(command_encoder, ("encodeGiTransport",), "G4 existing command-buffer integration")
    require_tokens(device, (
        "GiTransportRuntime.isRequested()", "GiTransportFrameGraph.initialize()",
        "GiTransportGpuResources.validateNativeAbi()", "encodeGiTransport",
        "this.giTransportCoordinator = new GiTransportCoordinator(",
    ), "G4 MetalDevice structural admission")

    require_tokens(timing, ("GI_TRANSPORT(21)", "PROFILED_STAGE_COUNT = 22"),
                   "G4 Java timing ABI")
    require_tokens(launcher, (
        "METALLUM_GI_G4_TRANSPORT", "RELEASE_PROFILE_CANDIDATE",
        "metallum_require_release_gi_off",
        'RELEASE_ARG=--release-contract',
    ), "G4 benchmark launcher marker")
    require_tokens(release_guard, (
        "metallum_require_release_gi_off", '"$release_candidate" -eq 1',
        '"$gi_g2" -ne 0', '"$gi_g3" -ne 0', '"$gi_g4" -ne 0',
    ), "G4 release-contract environment guard")
    require_tokens(release_guard_test, (
        "expect_reject 1 1 0 0", "expect_reject 1 0 1 0",
        "expect_reject 1 0 0 1", "expect_accept 1 0 0 0",
    ), "G4 release-contract guard test")
    require_tokens(reporter, ('GI_TRANSPORT_STAGE = "GI_TRANSPORT"',),
                   "G4 benchmark report marker")
    require_tokens(frame_graph_tests, (
        'init.accesses().equals(List.of(', 'transport.accesses().equals(List.of(',
        'resourceId(2, "gi_g3_direct_irradiance")',
        'resourceId(7, "gi_transport_confidence")',
    ), "G4 exact frame-graph access-set test")
    require_tokens(gradle, (
        'GiTransport: layout.projectDirectory.file("src/main/metal/MetallumGiTransport.metal")',
        'tasks.register("giG4TransportContractTest", Exec)',
        'tasks.register("giSemanticTransportFieldUnitTest", JavaExec)',
        'tasks.register("giTransportCpuUnitTest", JavaExec)',
        'tasks.register("giTransportSourceChainUnitTest", JavaExec)',
        'tasks.register("giTransportGpuValidationSource", JavaExec)',
        'tasks.register("giTransportGpuValidationBundled", JavaExec)',
        'tasks.register("giReleaseContractGuardTest", Exec)',
        'mainClass.set("com.metallum.client.gi.source.GiTransportGpuValidation")',
    ), "G4 shader build input")

    for symbol in (
            "metallum_gi_transport_abi_version_v1", "metallum_gi_transport_layout_v1",
            "metallum_gi_transport_create_context_v1", "metallum_gi_transport_encode_frozen_v1",
            "metallum_gi_transport_await_ready_v1", "metallum_gi_transport_get_stats_v1",
            "metallum_gi_transport_report_stale_v1",
            "metallum_gi_transport_capture_volume_once_v1",
            "metallum_gi_transport_release_context_v1"):
        if symbol not in bridge or symbol not in native:
            raise ContractError(f"G4 Java/Swift bridge symbol is missing: {symbol}")

    native_start = native.index("private let metallumGiTransportAbiVersionV1")
    native_transport = native[native_start:]
    require_tokens(native_transport, (
        "metallumGiTransportAbiVersionV1: Int32 = 1",
        "metallumGiTransportLayoutBytesV1 = 160",
        "metallumGiTransportHeaderBytesV1 = 128",
        "metallumGiTransportCellBytesV1 = 16",
        "metallumGiTransportStatsBytesV1 = 192",
        "MetallumGiTransportContextV1", ".rgba16Float", ".r8Unorm",
        "descriptor.storageMode = .private", "stage: .giTransport",
        "memoryBarrier(scope: .textures)", "shaderLibraryMode",
    ), "G4 Swift private-field ABI")
    native_code = strip_c_comments(native_transport)
    reject_tokens(native_code, (
        "makeRenderCommandEncoder", "sceneColor", "drawable", "present(",
        "setFragmentTexture", "depthStencil",
    ), "G4 native field-only context")
    native_encode = native_transport[
        native_transport.index("func encodeFrozen(") : native_transport.index("func awaitReady(")
    ]
    reject_tokens(strip_c_comments(native_encode), (
        "makeBuffer", "makeTexture", "captureVolumeOnce", "waitUntilCompleted",
    ), "G4 repeated native encode")

    require_tokens(metal, (
        "metallumGiTransportEdge = 32u", "MetallumGiTransportCellV1",
        "sizeof(MetallumGiTransportCellV1) == 16", "MetallumGiTransportHeaderV1",
        "sizeof(MetallumGiTransportHeaderV1) == 128",
        "metallum_gi_transport_clear_v1", "metallum_gi_transport_bounce_init_v1",
        "metallum_gi_transport_jacobi_sh_v1", "header.iterationCount != 1u",
        "header.maximumDistance != 8u", "directionZ = -1; directionZ <= 1",
        "directionY = -1; directionY <= 1", "directionX = -1; directionX <= 1",
        "distance = 1u; distance <= header.maximumDistance", "rho * (1.0f / metallumGiTransportPi) * direct",
        "metallumGiTransportPi * normalizedWeight * sourceSupport",
        "coefficient0 += transfer", "coefficientX += transfer * omega.x",
        "coefficientY += transfer * omega.y", "coefficientZ += transfer * omega.z",
        "shRed.write", "shGreen.write", "shBlue.write", "knownWeight",
        "metallumGiTransportValidityUnknown", "metallumGiTransportValidityFallback",
        "MetallumGiTransportPathOccluded",
    ), "G4 one-bounce Metal transport")
    metal_code = strip_c_comments(metal)
    reject_tokens(metal_code, (
        "texture2d", "depth", "lightmap", "camera", "history", "sceneColor",
        "terrain", "fragment ", "vertex ",
    ), "G4 Metal field-only source")
    if metal_code.count("rho * (1.0f / metallumGiTransportPi) * direct") != 1:
        raise ContractError("G4 must apply source rho/pi exactly once")
    if metal_code.count("kernel void metallum_gi_transport_jacobi_sh_v1") != 1:
        raise ContractError("G4 must contain exactly one Jacobi transport kernel")


def self_test_receipt_validator() -> None:
    fallbacks = {
        key: 0 for key in (
            "none", "disabled", "unavailable", "invalid_input", "stale_data",
            "budget", "native_failure",
        )
    }
    resets = {
        key: 0 for key in (
            "none", "world_change", "teleport", "scroll", "source_epoch",
            "explicit", "device_reset",
        )
    }
    metadata = {**G4_PROFILE_METADATA, "current_edr_headroom": 8.0}
    generation = dict(G4_PROFILE_GENERATION)
    gi = {
        "mode": "active",
        **G4_EXACT_GI_COUNTERS,
        "source_epoch": 17,
        "probe_epoch": 19,
        "allocated_bytes": 4_020_576,
        "resident_bytes": 1_966_080,
        "valid_probes": 32_768,
        "unknown_probes": 0,
        "reset_reasons": resets,
        "fallback_reasons": fallbacks,
    }
    timing = {
        "frames": 1,
        "average_ms": 0.31,
        "p50_ms": 0.31,
        "p95_ms": 0.34,
        "p99_ms": 0.35,
        "maximum_ms": 0.36,
    }

    def window(phase: str, stage: dict[str, Any] | None = None) -> dict[str, Any]:
        return {
            "schema_version": 6,
            "detail_enabled": True,
            "presented_frames": 300,
            "dropped_timing_events": 0,
            "benchmark": {
                "enabled": True,
                "generation": 1 if phase == "warmup" else 2,
                "phase": phase,
                "segment_index": 0,
                "scaler_mode": "OFF",
            },
            "metadata": copy.deepcopy(metadata),
            "renderer_generation": copy.deepcopy(generation),
            "global_illumination": copy.deepcopy(gi),
            "stages": {"GI_TRANSPORT": copy.deepcopy(stage)},
        }

    windows = [
        window("warmup", timing), window("warmup"),
        window("measure"), window("measure"),
    ]
    counters = {
        key: {
            "window_minimum": expected,
            "window_maximum": expected,
            "last_window": expected,
        }
        for key, expected in G4_EXACT_GI_COUNTERS.items()
        if key != "contract_version"
    }
    summary = {
        "selection": "schema-v2+ phase=measure segment=0 scaler=OFF generation=2",
        "presented_frames": 600,
        "schema_versions": [6],
        "detail_enabled": True,
        "metal_validation_contract": False,
        "dropped_timing_events": 0,
        "metadata": copy.deepcopy(metadata),
        "renderer_generation": copy.deepcopy(generation),
        "global_illumination": {
            "mode": "active",
            "contract_version": 3,
            "window_count": 2,
            "counters": counters,
            "fallback_reasons": copy.deepcopy(fallbacks),
        },
        "stages": {},
    }
    console = "\n".join((*G4_CONSOLE_TOKENS,
        "METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION expected=advanced schema=5 "
        "defaults_used=false requested=advanced resolved=advanced l3=true l5=true "
        "l6=true status=PASS generation=6 shader_epoch=5 reason=none",
    )) + "\n"

    def expect_failure(callback: Any, expected: str) -> None:
        try:
            callback()
        except ContractError as error:
            if expected not in str(error):
                raise AssertionError(
                    f"expected {expected!r} in receipt failure, got {error!r}"
                ) from error
        else:
            raise AssertionError(f"expected G4 receipt failure containing {expected!r}")

    derived, measured_count = derive_runtime_results(copy.deepcopy(windows))
    verify_summary_receipt(copy.deepcopy(summary), derived, measured_count)
    verify_console_receipt(console)
    verify_declared_runtime_results(copy.deepcopy(derived), derived)

    expect_failure(
        lambda: strict_json_text('{"outer":{"value":1,"value":2}}', "duplicate"),
        "duplicate JSON key",
    )
    for invalid in ('{"value":NaN}', '{"value":Infinity}',
                    '{"value":-Infinity}', '{"value":1e999}'):
        expect_failure(
            lambda invalid=invalid: strict_json_text(invalid, "non-finite"),
            "JSON",
        )

    invalid_timing = copy.deepcopy(windows)
    invalid_timing[0]["stages"]["GI_TRANSPORT"]["p95_ms"] = math.nan
    expect_failure(lambda: derive_runtime_results(invalid_timing), "finite and >= 0")

    measured_stage = copy.deepcopy(windows)
    measured_stage[2]["stages"]["GI_TRANSPORT"] = copy.deepcopy(timing)
    expect_failure(lambda: derive_runtime_results(measured_stage), "only during warmup")

    measured_growth = copy.deepcopy(windows)
    measured_growth[3]["global_illumination"]["source_epoch"] += 1
    expect_failure(lambda: derive_runtime_results(measured_growth), "grew during measurement")

    wrong_summary = copy.deepcopy(summary)
    wrong_summary["global_illumination"]["counters"]["resource_count"][
        "last_window"
    ] = 10
    expect_failure(
        lambda: verify_summary_receipt(wrong_summary, derived, measured_count),
        "resource_count.last_window differs",
    )

    wrong_results = copy.deepcopy(derived)
    wrong_results["transport_dispatches"] = 2
    expect_failure(
        lambda: verify_declared_runtime_results(wrong_results, derived),
        "handwritten result differs",
    )
    expect_failure(
        lambda: verify_console_receipt(console.replace("status=PASS", "status=FAIL")),
        "Advanced admission PASS",
    )

    with tempfile.TemporaryDirectory(prefix="metallum-g4-receipt-") as temporary:
        root = Path(temporary)
        raw_path = root / "g4.raw.jsonl"
        summary_path = root / "g4.summary.json"
        console_path = root / "g4.console.log"
        raw_path.write_text(
            "\n".join(json.dumps(value) for value in windows) + "\n",
            encoding="utf-8",
        )
        summary_path.write_text(json.dumps(summary), encoding="utf-8")
        console_path.write_text(console, encoding="utf-8")
        actual = verify_runtime_receipt(
            root, raw_path, summary_path, console_path,
            canonical_report_validation=False,
        )
        if actual != derived:
            raise AssertionError("G4 receipt self-test derived values changed")
        expect_failure(
            lambda: required_artifact(
                root, {"path": "missing.raw.jsonl", "sha256": "0" * 64}, "raw"
            ),
            "artifact is missing",
        )


def verify(root: Path) -> None:
    self_test_receipt_validator()
    evidence = strict_object(root / "benchmark/gi/g4-transport-evidence-v1.json")
    verify_evidence(root, evidence)
    verify_source_contract(root)
    print("GI G4 transport/evidence/field-only contract passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    try:
        verify(arguments.root.resolve())
    except (ContractError, OSError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        raise SystemExit(f"GI G4 contract FAILED: {error}") from error


if __name__ == "__main__":
    main()
