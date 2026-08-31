#!/usr/bin/env python3
"""Fail-closed verifier for the immutable G6 live-update evidence bundle.

The structural runner contract proves that benchmark wiring exists.  This verifier
has a different job: it binds the declared decision to immutable artifacts, rebuilds
every JSON summary from its raw report, and parses the exact torch and full-matrix
receipts from the logs.  The schema intentionally requires the final matrix receipt;
an older torch-only manifest is incomplete and must fail.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import math
import os
import re
import stat
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path
from typing import Any, Iterable


MANIFEST_RELATIVE_PATH = Path("benchmark/gi/g6-live-evidence-v1.json")
EVIDENCE_BUNDLE = Path("benchmark/gi/evidence/gi-g6-live-2026-08-30-v1")
TORCH_ROUTE_RELATIVE_PATH = Path("benchmark/routes/hdrtest-torch-toggle-v1.json")
MATRIX_ROUTE_RELATIVE_PATH = Path("benchmark/routes/hdrtest-gi-g6-matrix-v1.json")
SETTINGS_RELATIVE_PATH = Path("benchmark/settings/native-hdr-fancy-gi-live-v1.json")

TORCH_ROUTE_ID = "hdrtest-torch-toggle-v1"
TORCH_ROUTE_SHA256 = "7f0a03058371964e81ef95002644a1744793def24958ccebb8c808fb91e46cc8"
MATRIX_ROUTE_ID = "hdrtest-gi-g6-matrix-v1"
MATRIX_ROUTE_SHA256 = "e7bc60c8082ef1bf98c487b6158f0c08b8595fc55deb1290f97d06fa412e3934"
FIXTURE_ID = "hdrtest-static-v1"
FIXTURE_SHA256 = "a4a7e4fa34bed9e335856bc88f7ad1035ae1ba68e28851906ccaf9a65911e3c5"
SETTINGS_ID = "native-hdr-fancy-gi-live-v1"
SETTINGS_SPEC_SHA256 = "8bf845b207048cc442620b2ef0e8bc05e6ca6721bc27018ab6121eba8ebd1817"
SETTINGS_SHA256 = "46bda4e1537db4ce145d4322ec985a034a30dd3b371dc918e6f4d60b36960c6c"
PLAYER_NAME = "MetallumBench"
PLAYER_UUID = "b07a402a-d8ea-354f-9398-aaf208a798b9"
MEMORY_CAP_BYTES = 25_165_824
WARMUP_FRAMES = 1_800
LIVE_MEASURE_FRAMES = 3_000
LIVE_WINDOWS = 10
MATRIX_MEASURE_FRAMES = 3_600
MATRIX_WINDOWS = 12

EXPECTED_LIMITATIONS_V1 = [
    "The source-bound live run proves the G6 update/receiver/SLA contract, "
    "not a comparative Tier C whole-frame performance delta.",
    "The PNG is a static gross-artifact review, not camera-motion, scroll, teleport, or artistic product acceptance.",
    "G5 visual acceptance remains missing by the user's explicit stage-boundary "
    "statement; it is not backfilled by this G6 receipt.",
    "The broader G7 live visual and product matrix remains a separate stage.",
]

EXPECTED_LIMITATIONS_V2 = [
    "The source-bound live and matrix runs prove the G6 update/receiver/SLA contract, "
    "not a comparative Tier C whole-frame performance delta.",
    "The PNG is a static gross-artifact review, not full human motion or artistic "
    "product acceptance.",
    "G5 visual acceptance remains missing by the user's explicit stage-boundary "
    "statement; it is not backfilled by this G6 receipt.",
    "G7 remains the separate artistic, compatibility, and product-release matrix; "
    "it does not reopen the completed G6 functional update matrix.",
]
EXPECTED_LIMITATIONS = EXPECTED_LIMITATIONS_V2

ARTIFACT_KEYS_V1 = frozenset({
    "live_raw", "live_summary", "live_minecraft_log", "live_console_log",
    "live_transcript_log",
    "capture_raw", "capture_summary", "capture_minecraft_log",
    "capture_console_log", "capture_transcript_log", "png",
})

ARTIFACT_KEYS_V2 = frozenset({
    "live_raw", "live_summary", "live_minecraft_log", "live_console_log",
    "live_transcript_log", "matrix_raw", "matrix_summary",
    "matrix_minecraft_log", "matrix_console_log", "matrix_transcript_log",
    "capture_raw", "capture_summary", "capture_minecraft_log",
    "capture_console_log", "capture_transcript_log", "png",
})
ARTIFACT_KEYS = ARTIFACT_KEYS_V2

TOP_LEVEL_KEYS_V1 = frozenset({
    "schema_version", "id", "stage", "status", "decision", "g7_allowed",
    "implementation", "scope", "live_receipt",
    "static_visual_receipt", "artifacts", "mechanical_verification",
    "limitations", "next_gate",
})

TOP_LEVEL_KEYS_V2 = frozenset({
    "schema_version", "id", "stage", "status", "decision", "g7_allowed",
    "implementation", "scope", "live_receipt", "matrix_receipt",
    "static_visual_receipt", "artifacts", "mechanical_verification",
    "limitations", "next_gate",
})
TOP_LEVEL_KEYS = TOP_LEVEL_KEYS_V2

MECHANICAL_KEYS_V1 = frozenset({
    "focused_g3_g6_cpu_source_chain_abi", "source_compiled_g3_metal_validation",
    "bundled_g3_metal_validation", "generated_msl_full_and_ambient_only_all_terrain_flavors",
    "g6_runner_contract", "gradle_check",
})

MECHANICAL_KEYS_V2 = frozenset({
    "focused_g3_g6_cpu_source_chain_abi", "source_compiled_g3_metal_validation",
    "bundled_g3_metal_validation", "generated_msl_full_and_ambient_only_all_terrain_flavors",
    "g6_runner_contract", "g6_evidence_contract", "gradle_check",
})
MECHANICAL_KEYS = MECHANICAL_KEYS_V2

IMPLEMENTATION_KEYS = frozenset({
    "base_commit", "runtime_source_sha256", "runtime_artifact_sha256",
    "dirty_worktree", "persistent_sodium_option", "persistent_renderer_value",
    "production_default_enabled", "restart_gated", "off_is_structural",
})
SCOPE_KEYS = frozenset({
    "production_receiver", "dynamic_updates", "cascade_count",
    "near_to_far_incremental_bricks", "block_placement_and_removal",
    "camera_independent_held_and_entity_sources", "clipmap_scroll_overlap",
    "fail_closed_world_device_resource_reset", "receiver_stage",
    "fragment_texture3d_samples", "bounce_count",
})
COMMON_RECEIPT_KEYS = frozenset({
    "classification", "profile", "route", "route_sha256", "fixture",
    "fixture_sha256", "settings", "settings_sha256", "settings_spec_sha256",
    "warmup_frames", "measure_frames", "windows", "thermal_state",
    "dropped_timing_events", "fps_elapsed_weighted", "fps_minimum_window",
    "one_percent_low_window_weighted", "zero_point_one_percent_low_window_weighted",
    "presenting_gpu_p50_ms_window_weighted", "presenting_gpu_p95_ms_window_weighted",
    "presenting_gpu_p99_ms_window_weighted", "maximum_presenting_gpu_ms",
    "admission", "final", "memory",
})
ADMISSION_KEYS = frozenset({
    "status", "device_generation", "ready_mask", "field_generation", "source_tick",
    "dynamic", "vertex_only", "fragment_texture3d", "stale", "rejected",
})
MEMORY_KEYS = frozenset({"combined_accounted_bytes", "cap_bytes", "within_cap"})
TORCH_FINAL_KEYS = frozenset({
    "status", "ready_mask", "build_in_flight", "field_generation", "source_tick",
    "latest_terrain_device_generation", "latest_terrain_submit", "latest_bind_status",
    "latest_carrier_safe", "latest_frame_compatible", "latest_exact_mask_nonzero",
    "stale", "rejected", "block_samples", "block_p95_submits",
    "block_p99_submits", "block_sla",
})
MATRIX_EVENTS_KEYS = frozenset({
    "ordered_receipt", "event_count", "recovery_count", "receipt_mask",
})
MATRIX_FINAL_KEYS = frozenset({
    "status", "ready_mask", "build_in_flight", "field_generation", "source_tick",
    "stale", "rejected", "block_samples", "block_p95_submits",
    "block_p99_submits", "block_sla", "static_samples",
    "static_p95_submits", "static_p99_submits", "static_sla",
    "scroll_samples", "scroll_p95_submits", "scroll_p99_submits", "scroll_sla",
    "reset_samples", "reset_p95_submits", "reset_p99_submits",
    "reset_sla", "queue_queued", "queue_completed",
    "queue_discarded", "queue_pending", "queue_in_flight",
    "queue_algebra", "measurement_start_bytes", "combined_accounted_bytes",
    "cap_bytes", "accounted_delta", "readback_bytes", "receipts",
    "orbit_field_stable", "queue_converged",
})

ADMISSION_LOG_KEYS = frozenset({
    "requested", "resolved", "contract", "state", "device_generation",
    "presented_frame", "ready_mask", "field_generation", "source_tick",
    "transport_dispatches", "cascade_builds", "invalidations", "bindings",
    "zero_bindings", "field_bindings", "resident_bytes", "staging_bytes",
    "java_packet_bytes", "combined_accounted_bytes", "cap_bytes", "dynamic",
    "vertex_only", "fragment_texture3d", "stale", "rejected", "status",
})
BASE_FINAL_LOG_KEYS = frozenset({
    "state", "device_generation", "admission_emitted", "admission_device_generation",
    "ready_mask", "build_in_flight", "field_generation", "source_tick", "stale",
    "rejected", "latest_terrain_device_generation", "latest_terrain_submit",
    "latest_bind_status", "latest_carrier_safe", "latest_frame_compatible",
    "latest_ready_mask", "latest_exact_mask_nonzero", "latest_field_generation",
    "latest_source_tick", "combined_accounted_bytes", "cap_bytes", "block_samples",
    "block_p95_submits", "block_p99_submits", "block_sla", "status", "contract",
})
EXTENDED_FINAL_LOG_KEYS = BASE_FINAL_LOG_KEYS | frozenset({
    "static_samples", "static_p95_submits",
    "static_p99_submits", "static_sla", "scroll_samples",
    "scroll_p95_submits", "scroll_p99_submits", "scroll_sla",
    "reset_samples", "reset_p95_submits", "reset_p99_submits",
    "reset_sla", "queue_queued", "queue_completed",
    "queue_discarded", "queue_pending", "queue_in_flight",
    "queue_algebra", "measurement_start_bytes", "accounted_delta",
    "readback_bytes",
})

# (action, requested frame, optional stream index, optional x/y offsets)
MATRIX_EVENTS = (
    ("ORBIT_BEGIN", 30, None, None, None),
    ("ORBIT_END", 270, None, None, None),
    ("LAVA_APPLIED", 300, None, None, None),
    ("LAVA_REMOVED", 420, None, None, None),
    ("CHUNK_RELOAD", 490, None, None, None),
    ("RESOURCE_RELOAD", 760, None, None, None),
    ("DAY", 1030, None, None, None),
    ("NIGHT", 1230, None, None, None),
    ("RAIN", 1430, None, None, None),
    ("CLEAR", 1630, None, None, None),
    ("STREAM_STEP", 1830, 0, 8, 1),
    ("STREAM_STEP", 1870, 1, 16, 0),
    ("STREAM_STEP", 1910, 2, 24, -1),
    ("STREAM_STEP", 1950, 3, 32, -1),
    ("STREAM_STEP", 1990, 4, 24, -1),
    ("STREAM_STEP", 2030, 5, 16, 0),
    ("STREAM_STEP", 2070, 6, 8, 1),
    ("STREAM_STEP", 2110, 7, 0, 0),
    ("TELEPORT_OUT", 2170, None, None, None),
    ("TELEPORT_RETURN", 2430, None, None, None),
    ("NETHER_ENTER", 2620, None, None, None),
    ("OVERWORLD_RETURN", 3090, None, None, None),
)
# (recovery action, requested frame, matching event index, latency class)
MATRIX_RECOVERIES = (
    ("LAVA_APPLY", 300, 2, "STATIC_SOURCE"),
    ("LAVA_REMOVE", 420, 3, "STATIC_SOURCE"),
    ("CHUNK_RELOAD", 490, 4, "TERRAIN_BIND"),
    ("RESOURCE_RELOAD", 760, 5, "FULL_RESET"),
    ("DAY", 1030, 6, "FULL_RESET"),
    ("NIGHT", 1230, 7, "FULL_RESET"),
    ("RAIN", 1430, 8, "FULL_RESET"),
    ("CLEAR", 1630, 9, "FULL_RESET"),
    ("STREAM_STEP_0", 1830, 10, "SCROLL"),
    ("STREAM_STEP_1", 1870, 11, "SCROLL"),
    ("STREAM_STEP_2", 1910, 12, "SCROLL"),
    ("STREAM_STEP_3", 1950, 13, "SCROLL"),
    ("STREAM_STEP_4", 1990, 14, "SCROLL"),
    ("STREAM_STEP_5", 2030, 15, "SCROLL"),
    ("STREAM_STEP_6", 2070, 16, "SCROLL"),
    ("STREAM_STEP_7", 2110, 17, "SCROLL"),
    ("TELEPORT_OUT", 2170, 18, "FULL_RESET"),
    ("TELEPORT_RETURN", 2430, 19, "FULL_RESET"),
    ("NETHER_ENTER", 2620, 20, "FULL_RESET"),
    ("NETHER_RETURN", 3090, 21, "FULL_RESET"),
)


class ContractError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ContractError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> Any:
    raise ContractError(f"non-finite JSON number: {value}")


def strict_json_text(text: str, label: str) -> Any:
    try:
        return json.loads(
            text,
            object_pairs_hook=_strict_object,
            parse_constant=_reject_constant,
        )
    except (json.JSONDecodeError, UnicodeDecodeError) as error:
        raise ContractError(f"cannot parse {label}: {error}") from error


def strict_json(path: Path) -> Any:
    try:
        raw = path.read_bytes()
        text = raw.decode("utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise ContractError(f"cannot read {path}: {error}") from error
    return strict_json_text(text, str(path))


def exact_keys(value: Any, expected: Iterable[str], label: str) -> dict[str, Any]:
    require(isinstance(value, dict), f"{label} must be an object")
    expected_set = set(expected)
    actual = set(value)
    require(actual == expected_set,
            f"{label} fields differ: missing={sorted(expected_set - actual)}, "
            f"extra={sorted(actual - expected_set)}")
    return value


def exact_bool(value: Any, expected: bool, label: str) -> None:
    require(type(value) is bool and value is expected, f"{label} must be {expected}")


def integer(value: Any, label: str, minimum: int = 0) -> int:
    require(type(value) is int and value >= minimum,
            f"{label} must be an integer >= {minimum}")
    return value


def finite_number(value: Any, label: str) -> float:
    require(type(value) in (int, float) and math.isfinite(float(value)),
            f"{label} must be finite")
    return float(value)


def lowercase_sha256(value: Any, label: str) -> str:
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None,
            f"{label} must be a lowercase SHA-256")
    return value


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                digest.update(chunk)
    except OSError as error:
        raise ContractError(f"cannot hash {path}: {error}") from error
    return digest.hexdigest()


def safe_artifact(root: Path, descriptor: Any, label: str) -> tuple[Path, str]:
    descriptor = exact_keys(descriptor, {"path", "sha256"}, label)
    relative_text = descriptor["path"]
    require(isinstance(relative_text, str) and relative_text != "",
            f"{label}.path must be a relative path")
    relative = Path(relative_text)
    require(not relative.is_absolute() and relative.as_posix() == relative_text,
            f"{label}.path must be normalized POSIX relative path")
    require("." not in relative.parts and ".." not in relative.parts,
            f"{label}.path contains an unsafe component")
    try:
        relative.relative_to(EVIDENCE_BUNDLE)
    except ValueError as error:
        raise ContractError(
            f"{label}.path is outside the canonical G6 evidence bundle"
        ) from error

    current = root
    for component in relative.parts:
        current = current / component
        try:
            entry = current.lstat()
        except OSError as error:
            raise ContractError(f"{label} is missing: {relative_text}") from error
        require(not stat.S_ISLNK(entry.st_mode), f"{label} traverses a symlink: {current}")
    final_stat = current.lstat()
    require(stat.S_ISREG(final_stat.st_mode), f"{label} is not a regular file")
    require(final_stat.st_nlink == 1, f"{label} must not be a hard-linked file")
    digest = lowercase_sha256(descriptor["sha256"], f"{label}.sha256")
    require(sha256(current) == digest, f"{label} SHA-256 differs")
    return current, digest


def verify_specs(root: Path) -> None:
    expected_files = (
        (TORCH_ROUTE_RELATIVE_PATH, TORCH_ROUTE_SHA256, "G6 torch route"),
        (MATRIX_ROUTE_RELATIVE_PATH, MATRIX_ROUTE_SHA256, "G6 matrix route"),
        (SETTINGS_RELATIVE_PATH, SETTINGS_SPEC_SHA256, "G6 settings spec"),
    )
    for relative, expected_hash, label in expected_files:
        path = root / relative
        require(path.is_file(), f"{label} is missing: {relative}")
        require(sha256(path) == expected_hash, f"{label} digest differs")

    route = exact_keys(strict_json(root / MATRIX_ROUTE_RELATIVE_PATH), {
        "schema_version", "id", "fixture", "player", "dimension", "position",
        "rotation", "camera", "clock", "weather", "simulation", "readiness",
        "gi_g6_matrix",
    }, "G6 matrix route")
    require(route["schema_version"] == 5 and route["id"] == MATRIX_ROUTE_ID,
            "G6 matrix route schema/id differs")
    require(route["fixture"] == {"id": FIXTURE_ID, "sha256": FIXTURE_SHA256},
            "G6 matrix fixture differs")
    require(route["player"] == {"name": PLAYER_NAME, "uuid": PLAYER_UUID},
            "G6 matrix player differs")
    require(route["dimension"] == "minecraft:overworld"
            and route["position"] == [86.1241372798128, 74.0, -95.57730763953901]
            and route["rotation"] == {"yaw": 155.39700317, "pitch": 13.20000648}
            and route["camera"] == "FIRST_PERSON",
            "G6 matrix starting view differs")
    require(route["clock"] == {"total_ticks": 181406, "paused": True}
            and route["weather"] == {
                "mode": "clear", "frozen": True, "clear_duration_ticks": 6000,
            }
            and route["simulation"] == {"frozen": True},
            "G6 matrix startup simulation is not frozen")
    require(route["readiness"] == {
        "stable_frames": 120, "timeout_frames": 1200,
        "position_epsilon": 0.0001, "angle_epsilon": 0.001,
    }, "G6 matrix readiness differs")
    require(route["gi_g6_matrix"] == {
        "stationary_source": {
            "held_item": "minecraft:torch", "entity_item": "minecraft:torch",
            "entity_position": [83.5, 75.0, -99.0],
        },
        "lava": {
            "position": [80, 75, -112], "initial_block": "minecraft:air",
            "apply_after_measured_frames": 300, "remove_after_measured_frames": 420,
        },
        "camera_orbit": {
            "start_after_measured_frames": 30, "end_after_measured_frames": 270,
            "yaw_amplitude_degrees": 30.0, "pitch_amplitude_degrees": 10.0,
            "period_frames": 120,
        },
        "chunk_reload_after_measured_frames": 490,
        "resource_reload_after_measured_frames": 760,
        "day": {"apply_after_measured_frames": 1030, "total_ticks": 1000},
        "night": {"apply_after_measured_frames": 1230, "total_ticks": 13000},
        "rain_after_measured_frames": 1430,
        "clear_after_measured_frames": 1630,
        "chunk_stream": {
            "start_after_measured_frames": 1830, "step_frames": 40,
            "offsets": [8, 16, 24, 32, 24, 16, 8, 0],
            "y_offsets": [1, 0, -1, -1, -1, 0, 1, 0],
        },
        "far_teleport": {
            "apply_after_measured_frames": 2170, "target_offset": [320, 32, 0],
            "return_after_measured_frames": 2430,
        },
        "nether_round_trip": {
            "enter_after_measured_frames": 2620, "position": [0, 96, 0],
            "return_after_measured_frames": 3090,
        },
    }, "G6 matrix timeline differs")

    settings = strict_json(root / SETTINGS_RELATIVE_PATH)
    require(isinstance(settings, dict) and settings.get("schema_version") == 3
            and settings.get("id") == SETTINGS_ID,
            "G6 settings schema/id differs")
    require(settings.get("renderer_properties") == {
        "improvedLighting": "true", "lightingPreset": "balanced",
        "globalIllumination": "dynamic",
    }, "G6 settings do not select persistent production GI")
    require(settings.get("metalfx_properties") == {"mode": "off"},
            "G6 settings do not keep MetalFX off")
    options = settings.get("options")
    require(isinstance(options, dict)
            and options.get("graphicsPreset") == "fancy"
            and options.get("enableVsync") is False
            and options.get("fullscreen") is True
            and options.get("exclusiveFullscreen") is True
            and options.get("renderDistance") == 16
            and options.get("simulationDistance") == 12,
            "G6 settings quality/fullscreen contract differs")


def recompute_summary(
        root: Path, raw_path: Path, measure_frames: int,
) -> dict[str, Any]:
    command = [
        sys.executable, str(root / "tools/metal_benchmark_report.py"),
        "summarize", str(raw_path), "--measure-frames", str(measure_frames),
        "--segment", "0", "--scaler-mode", "OFF", "--json",
    ]
    try:
        result = subprocess.run(
            command, cwd=root, text=True, capture_output=True, check=False, timeout=90,
        )
    except subprocess.TimeoutExpired as error:
        raise ContractError("canonical G6 report recomputation timed out") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ContractError(f"G6 raw report fails the canonical parser: {detail}")
    value = strict_json_text(result.stdout, f"canonical summary for {raw_path}")
    require(isinstance(value, dict), "canonical G6 summary is not an object")
    return value


def compare_summary(
        supplied: dict[str, Any], recomputed: dict[str, Any], raw_path: Path, label: str,
) -> None:
    supplied_report = supplied.get("report")
    recomputed_report = recomputed.get("report")
    require(isinstance(supplied_report, str) and Path(supplied_report).is_absolute()
            and Path(supplied_report).name == raw_path.name,
            f"{label} does not identify its exact raw filename")
    require(isinstance(recomputed_report, str)
            and Path(recomputed_report).resolve() == raw_path.resolve(),
            f"{label} recomputation used a different raw artifact")
    normalized_supplied = copy.deepcopy(supplied)
    normalized_recomputed = copy.deepcopy(recomputed)
    normalized_supplied["report"] = "<verified-raw>"
    normalized_recomputed["report"] = "<verified-raw>"
    require(normalized_supplied == normalized_recomputed,
            f"{label} differs from canonical raw recomputation")


def verify_raw_jsonl(path: Path, label: str) -> None:
    count = 0
    try:
        with path.open("r", encoding="utf-8", newline="") as handle:
            for line_number, line in enumerate(handle, start=1):
                require(line.endswith("\n"),
                        f"{label} line {line_number} lacks a terminating newline")
                require(line.strip() != "", f"{label} contains a blank line")
                value = strict_json_text(line, f"{label} line {line_number}")
                require(isinstance(value, dict),
                        f"{label} line {line_number} is not a JSON object")
                count += 1
    except (OSError, UnicodeDecodeError) as error:
        raise ContractError(f"cannot read {label}: {error}") from error
    require(count > 0, f"{label} is empty")


def _same_number(actual: Any, expected: Any, label: str) -> None:
    left = finite_number(actual, label)
    right = finite_number(expected, label)
    require(math.isclose(left, right, rel_tol=0.0, abs_tol=1e-12),
            f"{label} differs from the canonical summary")


def verify_summary_profile(
        summary: dict[str, Any], receipt: dict[str, Any] | None,
        implementation: dict[str, Any],
        route_id: str, route_sha: str, label: str,
        measure_frames: int, windows: int,
) -> None:
    require(summary.get("presented_frames") == measure_frames
            and summary.get("window_count") == windows,
            f"{label} is not a {measure_frames}-frame/{windows}-window receipt")
    require(summary.get("schema_versions") == [6]
            and summary.get("detail_enabled") is False
            and summary.get("metal_validation_contract") is False
            and summary.get("dropped_timing_events") == 0,
            f"{label} raw report profile differs")
    require(summary.get("selection") ==
            "schema-v2+ phase=measure segment=0 scaler=OFF generation=2",
            f"{label} selected a different segment/scaler")

    metadata = summary.get("metadata")
    require(isinstance(metadata, dict), f"{label} metadata is missing")
    expected_metadata = {
        "commit": implementation["base_commit"],
        "source_sha256": implementation["runtime_source_sha256"],
        "artifact_sha256": implementation["runtime_artifact_sha256"],
        "route": route_id,
        "route_sha256": route_sha,
        "fixture": FIXTURE_ID,
        "fixture_sha256": FIXTURE_SHA256,
        "settings_id": SETTINGS_ID,
        "settings_spec_sha256": SETTINGS_SPEC_SHA256,
        "settings_sha256": SETTINGS_SHA256,
        "benchmark_player_name": PLAYER_NAME,
        "benchmark_player_uuid": PLAYER_UUID,
        "benchmark_dimension": "minecraft:overworld",
        "benchmark_simulation_frozen": True,
        "global_illumination_mode": "g6_live",
        "native_shader_library_mode": "PRECOMPILED",
        "native_shader_source_compile_count": 0,
        "native_pipeline_failure_count": 0,
        "render_width": 3024,
        "render_height": 1964,
        "display_width": 3024,
        "display_height": 1964,
        "refresh_hz": 120,
        "graphics_preset": "fancy",
        "persistent_metalfx_mode": "off",
        "display_sync_enabled": False,
        "scaler_active": False,
        "dirty_worktree": True,
    }
    for key, expected in expected_metadata.items():
        require(metadata.get(key) == expected,
                f"{label} metadata.{key} differs (found {metadata.get(key)!r})")

    renderer = summary.get("renderer_generation")
    require(isinstance(renderer, dict)
            and renderer.get("display_width") == 3024
            and renderer.get("display_height") == 1964
            and renderer.get("render_width") == 3024
            and renderer.get("render_height") == 1964
            and renderer.get("resolved_lighting_model") == "advanced"
            and renderer.get("lighting_preset") == "balanced"
            and renderer.get("resolved_output_mode") == "hdr"
            and renderer.get("resolved_upscale_mode") == "native"
            and renderer.get("resolved_interpolation_mode") == "off"
            and renderer.get("resolved_render_contract") == "metallum",
            f"{label} renderer generation differs")
    thermal = summary.get("thermal")
    require(isinstance(thermal, dict)
            and thermal.get("thermal_invalid") is False
            and thermal.get("has_serious") is False
            and thermal.get("has_critical") is False
            and thermal.get("states_observed") == ["nominal"],
            f"{label} thermal state is not nominal")
    gi = summary.get("global_illumination")
    require(isinstance(gi, dict) and gi.get("contract_version") == 2
            and gi.get("mode") == "active" and gi.get("window_count") == windows,
            f"{label} lacks the active source-bound GI telemetry stream")
    counters = gi.get("counters")
    require(isinstance(counters, dict), f"{label} GI counter census is missing")

    def counter(name: str) -> dict[str, Any]:
        value = counters.get(name)
        value = exact_keys(
            value, {"last_window", "window_maximum", "window_minimum"},
            f"{label} GI counter {name}",
        )
        for field in value.values():
            integer(field, f"{label} GI counter {name}")
        require(value["window_minimum"] <= value["last_window"]
                <= value["window_maximum"],
                f"{label} GI counter {name} window bounds differ")
        return value

    stale_cells = counter("stale_cell_rejects")
    unknown_probes = counter("unknown_probes")
    queued = counter("dirty_queued_total")["last_window"]
    completed = counter("dirty_completed_total")["last_window"]
    discarded = counter("dirty_discarded_total")["last_window"]
    pending = counter("dirty_pending")["last_window"]
    injected = counter("injection_dispatches")["last_window"]
    require(stale_cells["window_maximum"] == 0
            and unknown_probes["window_maximum"] == 0,
            f"{label} source field reported stale/unknown cells")
    require(pending == 0 and queued == completed + discarded + pending,
            f"{label} raw GI scheduler census does not converge/algebraically close")
    if route_id == MATRIX_ROUTE_ID:
        # The matrix deliberately crosses resource and dimension resets. Queue counters are
        # rebased with the current field generation, while injection_dispatches remains
        # monotonic across those resets. The terminal GI_G6_FINAL receipt separately proves
        # the full-run queue algebra and zero pending/in-flight work.
        require(injected >= completed and injected > 0,
                f"{label} raw GI dispatch census moved backwards across a reset")
    else:
        require(injected == completed,
                f"{label} raw GI dispatch census differs from completed work")
    require(counter("allocated_bytes")["last_window"] > 0
            and counter("resident_bytes")["last_window"] > 0
            and counter("resource_count")["last_window"] > 0
            and counter("pass_count")["last_window"] == 2,
            f"{label} raw GI field resource/pass census differs")
    require(gi.get("fallback_reasons") == {
        "budget": 0, "disabled": 0, "invalid_input": 0, "native_failure": 0,
        "none": 0, "stale_data": 0, "unavailable": 0,
    },
            f"{label} source field reported a fallback")
    reset_reasons = exact_keys(gi.get("reset_reasons"), {
        "device_reset", "explicit", "none", "scroll", "source_epoch",
        "teleport", "world_change",
    }, f"{label} GI reset reasons")
    require(all(type(value) is int and value >= 0 for value in reset_reasons.values()),
            f"{label} GI reset reasons contain an invalid count")
    workload = summary.get("workload")
    copies = workload.get("aggregate_totals", {}).get("copy_bytes", {}) \
        if isinstance(workload, dict) else {}
    expected_readback_bytes = 3024 * 1964 * 4 if receipt is None else 0
    expected_readback_commands = 1 if receipt is None else 0
    require(copies.get("gpu_to_cpu") == expected_readback_bytes
            and copies.get("gpu_to_cpu_commands") == expected_readback_commands,
            f"{label} GPU-to-CPU census differs from its screenshot contract")

    summary_metrics = {
        "fps_elapsed_weighted": summary.get("fps", {}).get("elapsed_weighted"),
        "fps_minimum_window": summary.get("fps", {}).get("window_minimum"),
        "one_percent_low_window_weighted": summary.get(
            "fps_low_window_summaries", {}).get("one_percent", {}).get(
                "window_frame_weighted_mean"),
        "zero_point_one_percent_low_window_weighted": summary.get(
            "fps_low_window_summaries", {}).get("zero_point_one_percent", {}).get(
                "window_frame_weighted_mean"),
        "presenting_gpu_p50_ms_window_weighted": summary.get(
            "presenting_command_buffer_gpu_ms", {}).get(
                "percentile_window_summaries", {}).get("p50", {}).get(
                    "window_frame_weighted_mean"),
        "presenting_gpu_p95_ms_window_weighted": summary.get(
            "presenting_command_buffer_gpu_ms", {}).get(
                "percentile_window_summaries", {}).get("p95", {}).get(
                    "window_frame_weighted_mean"),
        "presenting_gpu_p99_ms_window_weighted": summary.get(
            "presenting_command_buffer_gpu_ms", {}).get(
                "percentile_window_summaries", {}).get("p99", {}).get(
                    "window_frame_weighted_mean"),
        "maximum_presenting_gpu_ms": summary.get(
            "presenting_command_buffer_gpu_ms", {}).get(
                "maximum_observed_in_any_window"),
    }
    if receipt is not None:
        for key, actual in summary_metrics.items():
            _same_number(actual, receipt.get(key), f"{label} {key}")


def _event_lines(text: str, event: str) -> list[str]:
    prefix = f"METALLUM_BENCHMARK EVENT={event}"
    result: list[str] = []
    for source_line in text.splitlines():
        position = source_line.find(prefix)
        if position >= 0:
            result.append(source_line[position:].strip())
    return result


def _one_event(text: str, event: str, label: str) -> tuple[str, dict[str, str]]:
    lines = _event_lines(text, event)
    require(len(lines) == 1, f"{label} must contain exactly one {event} marker")
    return lines[0], _event_fields(lines[0], event, label)


def _event_fields(line: str, event: str, label: str) -> dict[str, str]:
    prefix = f"METALLUM_BENCHMARK EVENT={event}"
    require(line.startswith(prefix), f"{label} has malformed {event} prefix")
    tail = line[len(prefix):].strip()
    result: dict[str, str] = {}
    for token in tail.split():
        require(token.count("=") == 1, f"{label} {event} has malformed token {token!r}")
        key, value = token.split("=", 1)
        require(key != "" and value != "" and key not in result,
                f"{label} {event} has empty/duplicate field {key!r}")
        result[key] = value
    return result


def _log_int(fields: dict[str, str], key: str, label: str, minimum: int = 0) -> int:
    value = fields.get(key, "")
    require(re.fullmatch(r"-?[0-9]+", value) is not None,
            f"{label}.{key} is not an integer")
    result = int(value)
    require(result >= minimum, f"{label}.{key} is below {minimum}")
    return result


def _log_bool(fields: dict[str, str], key: str, label: str) -> bool:
    value = fields.get(key)
    require(value in ("true", "false"), f"{label}.{key} is not a boolean")
    return value == "true"


def _position(text: str, marker: str, label: str) -> int:
    count = text.count(marker)
    require(count == 1, f"{label} must contain exactly one marker: {marker}")
    return text.index(marker)


def _verify_admission(
        text: str, receipt: dict[str, Any] | None, label: str,
) -> tuple[str, dict[str, str]]:
    line, fields = _one_event(text, "GI_G6_ADMISSION", label)
    exact_keys(fields, ADMISSION_LOG_KEYS, f"{label} GI_G6_ADMISSION")
    require(fields["requested"] == "g6_live" and fields["resolved"] == "g6_live"
            and fields["contract"] == "6" and fields["state"] == "READY"
            and fields["dynamic"] == "true" and fields["vertex_only"] == "true"
            and fields["fragment_texture3d"] == "0" and fields["stale"] == "0"
            and fields["rejected"] == "0" and fields["status"] == "PASS",
            f"{label} GI_G6_ADMISSION is not exact READY/PASS")
    require(1 <= _log_int(fields, "ready_mask", label) <= 7
            and _log_int(fields, "field_generation", label, 1) > 0
            and _log_int(fields, "combined_accounted_bytes", label) <= MEMORY_CAP_BYTES
            and _log_int(fields, "cap_bytes", label) == MEMORY_CAP_BYTES,
            f"{label} GI_G6_ADMISSION readiness/memory differs")
    if receipt is not None:
        admission = exact_keys(receipt.get("admission"), ADMISSION_KEYS,
                               f"{label} manifest admission")
        comparisons = {
            "status": fields["status"],
            "device_generation": _log_int(fields, "device_generation", label, 1),
            "ready_mask": _log_int(fields, "ready_mask", label, 1),
            "field_generation": _log_int(fields, "field_generation", label, 1),
            "source_tick": _log_int(fields, "source_tick", label),
            "dynamic": _log_bool(fields, "dynamic", label),
            "vertex_only": _log_bool(fields, "vertex_only", label),
            "fragment_texture3d": _log_int(fields, "fragment_texture3d", label),
            "stale": _log_int(fields, "stale", label),
            "rejected": _log_int(fields, "rejected", label),
        }
        require(admission == comparisons, f"{label} manifest admission differs from log")
    return line, fields


def _verify_common_log_boundaries(
        text: str, label: str, measure_frames: int = LIVE_MEASURE_FRAMES,
) -> dict[str, int]:
    require("METALLUM_BENCHMARK EVENT=FAIL" not in text,
            f"{label} contains a benchmark FAIL marker")
    markers = {}
    for event in ("SEGMENT_START", "MEASURE_START", "MEASURE_END", "COMPLETE"):
        line, _fields = _one_event(text, event, label)
        markers[event] = text.index(line)
    require(markers["SEGMENT_START"] < markers["MEASURE_START"]
            < markers["MEASURE_END"] < markers["COMPLETE"],
            f"{label} segment markers are out of order")
    complete = _event_fields(_event_lines(text, "COMPLETE")[0], "COMPLETE", label)
    exact_keys(complete, {"segments", "measured_frames", "framebuffer"},
               f"{label} COMPLETE")
    require(complete == {
        "segments": "1", "measured_frames": str(measure_frames),
        "framebuffer": "3024x1964",
    }, f"{label} COMPLETE differs")
    return markers


def _verify_base_final(
        text: str, label: str, *, require_all_classes: bool,
) -> tuple[str, dict[str, str]]:
    line, fields = _one_event(text, "GI_G6_FINAL", label)
    if require_all_classes or any(k in fields for k in EXTENDED_FINAL_LOG_KEYS - BASE_FINAL_LOG_KEYS):
        expected_keys = EXTENDED_FINAL_LOG_KEYS
    else:
        expected_keys = BASE_FINAL_LOG_KEYS
    exact_keys(fields, expected_keys, f"{label} GI_G6_FINAL")
    require(fields["state"] == "READY" and fields["admission_emitted"] == "true"
            and fields["ready_mask"] == "7" and fields["build_in_flight"] == "false"
            and fields["stale"] == "0" and fields["rejected"] == "0"
            and fields["latest_bind_status"] == "1"
            and fields["latest_carrier_safe"] == "true"
            and fields["latest_frame_compatible"] == "true"
            and fields["latest_ready_mask"] == "7"
            and fields["latest_exact_mask_nonzero"] == "true"
            and fields["block_sla"] == "true" and fields["status"] == "PASS"
            and fields["contract"] == "6",
            f"{label} GI_G6_FINAL is not exact READY/PASS")
    require(fields["device_generation"] == fields["admission_device_generation"]
            == fields["latest_terrain_device_generation"],
            f"{label} GI_G6_FINAL crosses device generations")
    require(fields["field_generation"] == fields["latest_field_generation"]
            and fields["source_tick"] == fields["latest_source_tick"],
            f"{label} GI_G6_FINAL terrain binding is not terminal")
    require(_log_int(fields, "latest_terrain_submit", label) >= 0,
            f"{label} GI_G6_FINAL has invalid terrain submit")
    require(_log_int(fields, "combined_accounted_bytes", label) <= MEMORY_CAP_BYTES
            and _log_int(fields, "cap_bytes", label) == MEMORY_CAP_BYTES,
            f"{label} GI_G6_FINAL exceeds memory cap")
    block_p95 = _log_int(fields, "block_p95_submits", label)
    block_p99 = _log_int(fields, "block_p99_submits", label)
    require(_log_int(fields, "block_samples", label, 1) > 0
            and block_p95 <= block_p99 and block_p95 <= 8 and block_p99 <= 16,
            f"{label} block recovery SLA failed")

    if expected_keys == EXTENDED_FINAL_LOG_KEYS:
        limits = (
            ("static", 8, 16), ("scroll", 16, 32), ("reset", 32, 64),
        )
        for prefix, p95_limit, p99_limit in limits:
            samples = _log_int(fields, f"{prefix}_samples", label)
            p95 = _log_int(fields, f"{prefix}_p95_submits", label, -1)
            p99 = _log_int(fields, f"{prefix}_p99_submits", label, -1)
            sla = _log_bool(fields, f"{prefix}_sla", label)
            if samples == 0:
                require(not require_all_classes and p95 == -1 and p99 == -1 and not sla,
                        f"{label} unused {prefix} recovery class differs")
            else:
                require(0 <= p95 <= p99 and p95 <= p95_limit and p99 <= p99_limit and sla,
                        f"{label} {prefix} recovery SLA failed")
        queued = _log_int(fields, "queue_queued", label)
        completed = _log_int(fields, "queue_completed", label)
        discarded = _log_int(fields, "queue_discarded", label)
        pending = _log_int(fields, "queue_pending", label)
        in_flight = _log_int(fields, "queue_in_flight", label)
        require(queued == completed + discarded + pending + in_flight
                and pending == 0 and in_flight == 0,
                f"{label} scheduler algebra/convergence failed")
        measurement_bytes = _log_int(fields, "measurement_start_bytes", label)
        final_bytes = _log_int(fields, "combined_accounted_bytes", label)
        require(measurement_bytes == final_bytes
                and _log_int(fields, "accounted_delta", label) == 0
                and _log_int(fields, "readback_bytes", label) == 0
                and _log_bool(fields, "queue_algebra", label),
                f"{label} steady-state allocation/readback contract failed")
    return line, fields


def _verify_torch_manifest_final(
        fields: dict[str, str], receipt_final: Any, label: str,
) -> None:
    declared = exact_keys(receipt_final, TORCH_FINAL_KEYS, f"{label} manifest final")
    expected = {
        "status": fields["status"], "ready_mask": 7, "build_in_flight": False,
        "field_generation": _log_int(fields, "field_generation", label, 1),
        "source_tick": _log_int(fields, "source_tick", label),
        "latest_terrain_device_generation": _log_int(
            fields, "latest_terrain_device_generation", label, 1),
        "latest_terrain_submit": _log_int(fields, "latest_terrain_submit", label),
        "latest_bind_status": _log_int(fields, "latest_bind_status", label),
        "latest_carrier_safe": True, "latest_frame_compatible": True,
        "latest_exact_mask_nonzero": True, "stale": 0, "rejected": 0,
        "block_samples": _log_int(fields, "block_samples", label, 1),
        "block_p95_submits": _log_int(fields, "block_p95_submits", label),
        "block_p99_submits": _log_int(fields, "block_p99_submits", label),
        "block_sla": True,
    }
    require(declared == expected, f"{label} manifest final differs from log")


def _verify_admission_to_final(
        admission: dict[str, str], final: dict[str, str],
        receipt: dict[str, Any] | None, label: str,
) -> None:
    require(admission["device_generation"] == final["device_generation"]
            and _log_int(final, "latest_terrain_submit", label)
            >= _log_int(admission, "presented_frame", label)
            and _log_int(final, "field_generation", label, 1)
            >= _log_int(admission, "field_generation", label, 1)
            and _log_int(final, "source_tick", label)
            >= _log_int(admission, "source_tick", label)
            and _log_int(admission, "combined_accounted_bytes", label)
            == _log_int(final, "combined_accounted_bytes", label),
            f"{label} admission/final source, terrain, or memory chain differs")
    if receipt is not None:
        memory = receipt["memory"]
        require(memory == {
            "combined_accounted_bytes": _log_int(
                final, "combined_accounted_bytes", label),
            "cap_bytes": MEMORY_CAP_BYTES,
            "within_cap": True,
        }, f"{label} manifest memory differs from final log")


def _verify_mirrors(
        minecraft_text: str, console_text: str, transcript_text: str,
        events: Iterable[str], label: str,
) -> None:
    for event in events:
        for line in _event_lines(minecraft_text, event):
            require(console_text.count(line) == 1,
                    f"{label} console does not mirror exact {event} marker")
            require(transcript_text.count(line) == 1,
                    f"{label} transcript does not mirror exact {event} marker")


def verify_torch_log(
        minecraft_text: str, console_text: str, transcript_text: str,
        receipt: dict[str, Any] | None, label: str, *, capture: bool,
        capture_receipt: dict[str, Any] | None = None,
) -> None:
    require((capture and receipt is None and capture_receipt is not None)
            or (not capture and receipt is not None and capture_receipt is None),
            f"{label} receipt mode differs")
    boundaries = _verify_common_log_boundaries(minecraft_text, label)
    admission_line, admission = _verify_admission(minecraft_text, receipt, label)
    final_line, final = _verify_base_final(
        minecraft_text, label, require_all_classes=False,
    )
    _verify_admission_to_final(admission, final, receipt, label)
    if receipt is not None:
        _verify_torch_manifest_final(final, receipt["final"], label)
    torch: dict[str, Any] | None = None
    if receipt is not None:
        torch = exact_keys(receipt.get("torch_epoch"), {
            "position", "apply_requested_frame", "applied_frame", "remove_requested_frame",
            "removed_frame", "observation_end_frame", "ordered_receipt",
        }, f"{label} torch epoch")
        require(torch["position"] == [80, 75, -112]
                and torch["apply_requested_frame"] == 300
                and torch["remove_requested_frame"] == 450
                and torch["observation_end_frame"] == 600
                and torch["ordered_receipt"] == "PASS",
                f"{label} torch route declaration differs")
    begin_line, begin = _one_event(minecraft_text, "TORCH_EPOCH_BEGIN", label)
    applied_line, applied = _one_event(minecraft_text, "TORCH_EPOCH_APPLIED", label)
    removed_line, removed = _one_event(minecraft_text, "TORCH_EPOCH_REMOVED", label)
    end_line, end = _one_event(minecraft_text, "TORCH_EPOCH_END", label)
    exact_keys(begin, {"route", "position", "measured_frame", "observation_frames"},
               f"{label} TORCH_EPOCH_BEGIN")
    exact_keys(applied, {"route", "position", "measured_frame", "requested_frame"},
               f"{label} TORCH_EPOCH_APPLIED")
    exact_keys(removed, {"route", "position", "measured_frame", "requested_frame"},
               f"{label} TORCH_EPOCH_REMOVED")
    require(begin == {
        "route": TORCH_ROUTE_ID, "position": "80,75,-112", "measured_frame": "300",
        "observation_frames": "300",
    }, f"{label} TORCH_EPOCH_BEGIN differs")
    applied_frame = _log_int(applied, "measured_frame", label)
    removed_frame = _log_int(removed, "measured_frame", label)
    require(applied["route"] == TORCH_ROUTE_ID and applied["position"] == "80,75,-112"
            and applied["requested_frame"] == "300"
            and applied_frame >= 300,
            f"{label} TORCH_EPOCH_APPLIED differs")
    require(removed["route"] == TORCH_ROUTE_ID and removed["position"] == "80,75,-112"
            and removed["requested_frame"] == "450"
            and removed_frame >= 450,
            f"{label} TORCH_EPOCH_REMOVED differs")
    if torch is not None:
        require(applied_frame == torch["applied_frame"]
                and removed_frame == torch["removed_frame"],
                f"{label} manifest torch frames differ from log")
    else:
        require(applied_frame < 400 and removed_frame < 600,
                f"{label} capture torch frames lie outside their observation windows")
    require(end.get("route") == TORCH_ROUTE_ID and end.get("position") == "80,75,-112"
            and end.get("measured_frame") == "600"
            and end.get("telemetry_errors") == "0" and end.get("telemetry_overflow") == "0",
            f"{label} TORCH_EPOCH_END differs")
    order = [
        boundaries["SEGMENT_START"], minecraft_text.index(admission_line),
        boundaries["MEASURE_START"],
        minecraft_text.index(begin_line), minecraft_text.index(applied_line),
    ]
    if capture:
        require(capture_receipt is not None, f"{label} capture receipt is missing")
        screenshot_line, screenshot = _one_event(minecraft_text, "SCREENSHOT_REQUESTED", label)
        exact_keys(screenshot, {"index", "mode", "phase", "measured_frame"},
                   f"{label} SCREENSHOT_REQUESTED")
        require(screenshot == {
            "index": "1", "mode": "OFF", "phase": "TORCH_ON", "measured_frame": "400",
        }, f"{label} screenshot marker differs")
        capture_final = {
            "g6_final_ready_mask": _log_int(final, "ready_mask", label),
            "g6_final_stale": _log_int(final, "stale", label),
            "g6_final_rejected": _log_int(final, "rejected", label),
            "block_samples": _log_int(final, "block_samples", label),
            "block_p95_submits": _log_int(final, "block_p95_submits", label),
            "block_p99_submits": _log_int(final, "block_p99_submits", label),
        }
        for key, actual in capture_final.items():
            require(capture_receipt[key] == actual,
                    f"{label} static receipt {key} differs from log")
        order.append(minecraft_text.index(screenshot_line))
    else:
        require(not _event_lines(minecraft_text, "SCREENSHOT_REQUESTED"),
                f"{label} performance receipt contains a screenshot")
    order.extend([
        minecraft_text.index(removed_line), minecraft_text.index(end_line),
        boundaries["MEASURE_END"], minecraft_text.index(final_line), boundaries["COMPLETE"],
    ])
    require(order == sorted(order) and len(set(order)) == len(order),
            f"{label} torch/admission/final markers are out of order")
    request = (
        "GI_G6_REQUEST mode=g6_live persistent=true dynamic=true receiver=true "
        f"diagnostic_flags=false route={TORCH_ROUTE_ID} status=REQUESTED"
    )
    require(transcript_text.count(request) == 1,
            f"{label} transcript lacks the exact G6 request")
    if capture:
        require("Reference capture validated (not performance-attested):" in transcript_text,
                f"{label} transcript lacks capture validation")
    else:
        require(transcript_text.count(
            "Benchmark validated: COMPLETE present, no FAIL/screenshots, "
            "dropped timing events = 0") == 1,
            f"{label} transcript lacks validated COMPLETE")
    _verify_mirrors(
        minecraft_text, console_text, transcript_text,
        ("GI_G6_ADMISSION", "TORCH_EPOCH_BEGIN", "TORCH_EPOCH_APPLIED",
         "TORCH_EPOCH_REMOVED", "TORCH_EPOCH_END", "GI_G6_FINAL", "COMPLETE"),
        label,
    )


def verify_matrix_log(
        minecraft_text: str, console_text: str, transcript_text: str,
        receipt: dict[str, Any], label: str,
) -> None:
    boundaries = _verify_common_log_boundaries(
        minecraft_text, label, MATRIX_MEASURE_FRAMES)
    admission_line, admission = _verify_admission(minecraft_text, receipt, label)
    ready_line, ready = _one_event(minecraft_text, "GI_G6_MATRIX_READY", label)
    exact_keys(ready, {"route", "held", "entity", "entity_id", "status"},
               f"{label} GI_G6_MATRIX_READY")
    require(ready == {
        "route": MATRIX_ROUTE_ID, "held": "minecraft:torch", "entity": "minecraft:torch",
        "entity_id": "1999999900", "status": "PASS",
    }, f"{label} GI_G6_MATRIX_READY differs")
    prepare_line, prepare = _one_event(
        minecraft_text, "GI_G6_MATRIX_NETHER_PREPARE", label)
    exact_keys(prepare, {
        "route", "target", "chunk", "full", "forced", "status",
    }, f"{label} GI_G6_MATRIX_NETHER_PREPARE")
    require(prepare == {
        "route": MATRIX_ROUTE_ID, "target": "0,96,0", "chunk": "0,0",
        "full": "true", "forced": "true", "status": "PASS",
    }, f"{label} GI_G6_MATRIX_NETHER_PREPARE differs")

    event_lines = _event_lines(minecraft_text, "GI_G6_MATRIX_EVENT")
    require(len(event_lines) == len(MATRIX_EVENTS),
            f"{label} must contain exactly {len(MATRIX_EVENTS)} matrix events")
    event_positions: list[int] = []
    event_frames: list[int] = []
    last_actual = -1
    orbit_identity: tuple[int, int, int, int, int] | None = None
    for event_index, (line, (action, requested, stream_index, offset, y_offset)) in enumerate(
            zip(event_lines, MATRIX_EVENTS)):
        fields = _event_fields(line, "GI_G6_MATRIX_EVENT", label)
        expected_keys = {"route", "action", "measured_frame", "status"}
        if action == "ORBIT_BEGIN":
            expected_keys.update({
                "field_generation", "block_samples", "static_source_samples",
                "scroll_samples", "full_reset_samples",
            })
        elif action == "ORBIT_END":
            expected_keys.update({
                "baseline_generation", "field_generation", "block_samples",
                "static_source_samples", "scroll_samples", "full_reset_samples",
            })
        else:
            expected_keys.add("requested_frame")
        if stream_index is not None:
            expected_keys.update({"index", "offset", "y_offset"})
        exact_keys(fields, expected_keys, f"{label} matrix event {action}")
        require(fields["route"] == MATRIX_ROUTE_ID and fields["action"] == action
                and fields["status"] == "PASS",
                f"{label} matrix event action/route/status differs")
        actual = _log_int(fields, "measured_frame", label)
        if action == "ORBIT_BEGIN":
            require(actual == requested, f"{label} {action} did not occur on its exact frame")
            orbit_identity = (
                _log_int(fields, "field_generation", label, 1),
                _log_int(fields, "block_samples", label),
                _log_int(fields, "static_source_samples", label),
                _log_int(fields, "scroll_samples", label),
                _log_int(fields, "full_reset_samples", label),
            )
        elif action == "ORBIT_END":
            require(actual == requested, f"{label} {action} did not occur on its exact frame")
            require(orbit_identity is not None, f"{label} ORBIT_END lacks ORBIT_BEGIN")
            orbit_after = (
                _log_int(fields, "field_generation", label, 1),
                _log_int(fields, "block_samples", label),
                _log_int(fields, "static_source_samples", label),
                _log_int(fields, "scroll_samples", label),
                _log_int(fields, "full_reset_samples", label),
            )
            require(_log_int(fields, "baseline_generation", label, 1)
                    == orbit_identity[0] and orbit_after == orbit_identity,
                    f"{label} camera orbit changed field/latency identity")
        else:
            next_requested = (MATRIX_EVENTS[event_index + 1][1]
                              if event_index + 1 < len(MATRIX_EVENTS)
                              else MATRIX_MEASURE_FRAMES)
            require(_log_int(fields, "requested_frame", label) == requested
                    and requested <= actual < next_requested,
                    f"{label} {action} lies outside its deterministic completion window")
        if stream_index is not None:
            require(_log_int(fields, "index", label) == stream_index
                    and _log_int(fields, "offset", label) == offset
                    and _log_int(fields, "y_offset", label, -64) == y_offset,
                    f"{label} STREAM_STEP index/offset/y_offset differs")
        require(actual >= last_actual, f"{label} matrix measured frames move backwards")
        last_actual = actual
        position = minecraft_text.index(line)
        event_positions.append(position)
        event_frames.append(actual)

    recovery_lines = _event_lines(minecraft_text, "GI_G6_MATRIX_RECOVERY")
    require(len(recovery_lines) == len(MATRIX_RECOVERIES),
            f"{label} must contain exactly {len(MATRIX_RECOVERIES)} recovery markers")
    recovery_positions: list[int] = []
    recovery_frames: list[int] = []
    for line, (action, requested, event_index, latency_class) in zip(
            recovery_lines, MATRIX_RECOVERIES):
        fields = _event_fields(line, "GI_G6_MATRIX_RECOVERY", label)
        common_keys = {
            "route", "action", "requested_frame", "measured_frame", "ready_mask",
            "build_in_flight", "status",
        }
        has_terrain_evidence = latency_class == "TERRAIN_BIND" or action == "RESOURCE_RELOAD"
        has_mutation_evidence = latency_class != "TERRAIN_BIND"
        expected_keys = set(common_keys)
        if has_terrain_evidence:
            expected_keys.update({
                "terrain_submit_before", "terrain_submit_after",
                "terrain_device_before", "terrain_device_after", "current_device",
                "terrain_field_before", "terrain_field_after", "current_field",
                "terrain_source_before", "terrain_source_after", "current_source", "bind_status",
                "carrier_safe", "frame_compatible", "exact_mask_nonzero",
            })
        if has_mutation_evidence:
            expected_keys.update({
                "baseline_generation", "field_generation", "latency_class",
                "sample_before", "sample_after",
            })
        exact_keys(fields, expected_keys, f"{label} matrix recovery {action}")
        recovery_frame = _log_int(fields, "measured_frame", label)
        ready_mask = _log_int(fields, "ready_mask", label, 1)
        ready_mask_matches = ((ready_mask & 1) != 0 and ready_mask <= 7
                              if latency_class == "SCROLL" else ready_mask == 7)
        build_in_flight = fields["build_in_flight"]
        require(fields["route"] == MATRIX_ROUTE_ID and fields["action"] == action
                and fields["status"] == "PASS"
                and _log_int(fields, "requested_frame", label) == requested
                and requested <= recovery_frame <= MATRIX_MEASURE_FRAMES
                and ready_mask_matches
                and build_in_flight in {"true", "false"}
                and (latency_class == "SCROLL" or build_in_flight == "false"),
                f"{label} matrix recovery {action} differs")
        next_event_frame = (event_frames[event_index + 1]
                            if event_index + 1 < len(event_frames)
                            else MATRIX_MEASURE_FRAMES)
        require(event_frames[event_index] <= recovery_frame < next_event_frame,
                f"{label} recovery {action} lies outside its numeric recovery window")
        if has_terrain_evidence:
            submit_before = _log_int(fields, "terrain_submit_before", label)
            submit_after = _log_int(fields, "terrain_submit_after", label)
            device_before = _log_int(fields, "terrain_device_before", label, 1)
            device_after = _log_int(fields, "terrain_device_after", label, 1)
            current_device = _log_int(fields, "current_device", label, 1)
            terrain_field_after = _log_int(fields, "terrain_field_after", label, 1)
            terrain_source_after = _log_int(fields, "terrain_source_after", label)
            require(submit_after > submit_before
                    and device_before == device_after == current_device
                    == _log_int(admission, "device_generation", label, 1)
                    and _log_int(fields, "terrain_field_before", label, 1) > 0
                    and terrain_field_after
                    == _log_int(fields, "current_field", label, 1)
                    and _log_int(fields, "terrain_source_before", label) >= 0
                    and terrain_source_after
                    == _log_int(fields, "current_source", label)
                    and fields["bind_status"] == "1"
                    and fields["carrier_safe"] == "true"
                    and fields["frame_compatible"] == "true"
                    and fields["exact_mask_nonzero"] == "true",
                    f"{label} F3+A recovery lacks a newer current exact terrain bind")
        if has_mutation_evidence:
            baseline = _log_int(fields, "baseline_generation", label, 1)
            generation = _log_int(fields, "field_generation", label, 1)
            sample_before = _log_int(fields, "sample_before", label)
            sample_after = _log_int(fields, "sample_after", label, 1)
            require(fields["latency_class"] == latency_class
                    and generation > baseline and sample_after == sample_before + 1
                    and (action != "RESOURCE_RELOAD"
                         or generation == _log_int(fields, "current_field", label, 1)),
                    f"{label} recovery {action} reused another mutation's evidence")
        position = minecraft_text.index(line)
        next_position = (event_positions[event_index + 1]
                         if event_index + 1 < len(event_positions)
                         else boundaries["MEASURE_END"])
        require(event_positions[event_index] < position < next_position,
                f"{label} recovery {action} did not close before the next mutation")
        recovery_positions.append(position)
        recovery_frames.append(recovery_frame)
    require(event_positions == sorted(event_positions)
            and recovery_positions == sorted(recovery_positions)
            and recovery_frames == sorted(recovery_frames),
            f"{label} matrix event/recovery order differs")
    require(recovery_positions[15] < event_positions[18],
            f"{label} STREAM_STEP_7 near receipt did not close before TELEPORT_OUT")
    require(event_positions[18] < recovery_positions[16] < event_positions[19],
            f"{label} TELEPORT_OUT FULL_RESET +1/ready7/!inflight did not close before RETURN")
    require(minecraft_text.index(prepare_line) < boundaries["MEASURE_START"]
            and minecraft_text.index(prepare_line) < event_positions[20],
            f"{label} Nether target was not prepared before measured entry")

    matrix_final_line, matrix_final = _one_event(
        minecraft_text, "GI_G6_MATRIX_FINAL", label)
    exact_keys(matrix_final, {
        "route", "receipts", "orbit_field_stable", "queue_converged",
        "accounted_delta", "status", "contract",
    }, f"{label} GI_G6_MATRIX_FINAL")
    require(matrix_final == {
        "route": MATRIX_ROUTE_ID, "receipts": "511", "orbit_field_stable": "true",
        "queue_converged": "true", "accounted_delta": "0", "status": "PASS",
        "contract": "6",
    }, f"{label} GI_G6_MATRIX_FINAL differs")
    final_line, final_fields = _verify_base_final(
        minecraft_text, label, require_all_classes=True,
    )
    _verify_admission_to_final(admission, final_fields, receipt, label)

    declared = exact_keys(receipt["final"], MATRIX_FINAL_KEYS, f"{label} manifest final")
    field_mapping = {
        "ready_mask": "ready_mask", "field_generation": "field_generation",
        "source_tick": "source_tick", "stale": "stale", "rejected": "rejected",
        "block_samples": "block_samples", "block_p95_submits": "block_p95_submits",
        "block_p99_submits": "block_p99_submits",
        "static_samples": "static_samples",
        "static_p95_submits": "static_p95_submits",
        "static_p99_submits": "static_p99_submits",
        "scroll_samples": "scroll_samples", "scroll_p95_submits": "scroll_p95_submits",
        "scroll_p99_submits": "scroll_p99_submits",
        "reset_samples": "reset_samples",
        "reset_p95_submits": "reset_p95_submits",
        "reset_p99_submits": "reset_p99_submits",
        "queue_queued": "queue_queued", "queue_completed": "queue_completed",
        "queue_discarded": "queue_discarded",
        "queue_pending": "queue_pending", "queue_in_flight": "queue_in_flight",
        "measurement_start_bytes": "measurement_start_bytes",
        "combined_accounted_bytes": "combined_accounted_bytes", "cap_bytes": "cap_bytes",
        "accounted_delta": "accounted_delta", "readback_bytes": "readback_bytes",
    }
    for manifest_key, log_key in field_mapping.items():
        require(declared[manifest_key] == _log_int(final_fields, log_key, label),
                f"{label} manifest final {manifest_key} differs from log")
    boolean_mapping = {
        "build_in_flight": "build_in_flight", "block_sla": "block_sla",
        "static_sla": "static_sla", "scroll_sla": "scroll_sla",
        "reset_sla": "reset_sla", "queue_algebra": "queue_algebra",
    }
    for manifest_key, log_key in boolean_mapping.items():
        require(declared[manifest_key] is _log_bool(final_fields, log_key, label),
                f"{label} manifest final {manifest_key} differs from log")
    require(declared["status"] == "PASS" and declared["receipts"] == 511
            and declared["orbit_field_stable"] is True
            and declared["queue_converged"] is True,
            f"{label} manifest matrix verdict differs")

    events = exact_keys(receipt.get("events"), MATRIX_EVENTS_KEYS,
                        f"{label} manifest events")
    require(events == {
        "ordered_receipt": "PASS", "event_count": len(MATRIX_EVENTS),
        "recovery_count": len(MATRIX_RECOVERIES), "receipt_mask": 511,
    }, f"{label} manifest event census differs")
    # Events and recoveries interleave, so validate the global milestone constraints separately.
    require(minecraft_text.index(ready_line) < boundaries["SEGMENT_START"]
            < minecraft_text.index(admission_line) < boundaries["MEASURE_START"]
            < event_positions[0] < event_positions[-1] < boundaries["MEASURE_END"]
            and all(boundaries["MEASURE_START"] < value < boundaries["MEASURE_END"]
                    for value in recovery_positions)
            and boundaries["MEASURE_END"] < minecraft_text.index(matrix_final_line)
            < minecraft_text.index(final_line) < boundaries["COMPLETE"],
            f"{label} matrix boundary/final order differs")
    require(not _event_lines(minecraft_text, "SCREENSHOT_REQUESTED"),
            f"{label} matrix performance receipt contains a screenshot")
    request = (
        "GI_G6_REQUEST mode=g6_live persistent=true dynamic=true receiver=true "
        f"diagnostic_flags=false route={MATRIX_ROUTE_ID} status=REQUESTED"
    )
    require(transcript_text.count(request) == 1,
            f"{label} transcript lacks exact matrix request")
    require(transcript_text.count(
        "Benchmark validated: COMPLETE present, no FAIL/screenshots, "
        "dropped timing events = 0") == 1,
        f"{label} transcript lacks validated COMPLETE")
    _verify_mirrors(
        minecraft_text, console_text, transcript_text,
        ("GI_G6_MATRIX_NETHER_PREPARE", "GI_G6_MATRIX_READY", "GI_G6_ADMISSION",
         "GI_G6_MATRIX_EVENT", "GI_G6_MATRIX_RECOVERY", "GI_G6_MATRIX_FINAL",
         "GI_G6_FINAL", "COMPLETE"),
        label,
    )


def _verify_receipt_shape(
        receipt: Any, label: str, *, matrix: bool,
) -> dict[str, Any]:
    expected = set(COMMON_RECEIPT_KEYS)
    expected.add("events" if matrix else "torch_epoch")
    receipt = exact_keys(receipt, expected, label)
    expected_route = MATRIX_ROUTE_ID if matrix else TORCH_ROUTE_ID
    expected_sha = MATRIX_ROUTE_SHA256 if matrix else TORCH_ROUTE_SHA256
    expected_measure_frames = (MATRIX_MEASURE_FRAMES
                               if matrix else LIVE_MEASURE_FRAMES)
    expected_windows = MATRIX_WINDOWS if matrix else LIVE_WINDOWS
    require(receipt["classification"] ==
            "SOURCE_BOUND_G6_LIVE_CONTRACT_NOT_COMPARATIVE_TIER_C"
            and receipt["profile"] ==
            "Apple M1 Pro, built-in Retina 3024x1964@120 HDR, Advanced/Balanced, "
            "Fancy 16/12, native resolution, MetalFX and VSync off",
            f"{label} classification/profile differs")
    require(receipt["route"] == expected_route and receipt["route_sha256"] == expected_sha
            and receipt["fixture"] == FIXTURE_ID
            and receipt["fixture_sha256"] == FIXTURE_SHA256
            and receipt["settings"] == SETTINGS_ID
            and receipt["settings_sha256"] == SETTINGS_SHA256
            and receipt["settings_spec_sha256"] == SETTINGS_SPEC_SHA256,
            f"{label} route/fixture/settings identity differs")
    require(receipt["warmup_frames"] == WARMUP_FRAMES
            and receipt["measure_frames"] == expected_measure_frames
            and receipt["windows"] == expected_windows
            and receipt["thermal_state"] == "nominal"
            and receipt["dropped_timing_events"] == 0,
            f"{label} benchmark profile differs")
    memory = exact_keys(receipt["memory"], MEMORY_KEYS, f"{label} memory")
    require(memory["cap_bytes"] == MEMORY_CAP_BYTES
            and type(memory["combined_accounted_bytes"]) is int
            and 0 < memory["combined_accounted_bytes"] <= MEMORY_CAP_BYTES
            and memory["within_cap"] is True,
            f"{label} memory receipt differs")
    return receipt


def verify_capture_manifest(value: Any) -> dict[str, Any]:
    value = exact_keys(value, {
        "classification", "phase", "measured_frame", "framebuffer", "capture_source",
        "ordered_between_torch_apply_and_remove", "g6_final_ready_mask",
        "g6_final_stale", "g6_final_rejected", "block_samples",
        "block_p95_submits", "block_p99_submits", "gross_artifact_review",
        "review_notes",
    }, "G6 static visual receipt")
    require(value["classification"] == "PASS_STATIC_REFERENCE_ONLY_NOT_MOTION_ACCEPTANCE"
            and value["phase"] == "TORCH_ON" and value["measured_frame"] == 400
            and value["framebuffer"] == "3024x1964"
            and value["capture_source"] == "full-resolution SDR GUI composite"
            and value["ordered_between_torch_apply_and_remove"] is True
            and value["g6_final_ready_mask"] == 7
            and value["g6_final_stale"] == 0 and value["g6_final_rejected"] == 0
            and type(value["block_samples"]) is int and value["block_samples"] > 0
            and type(value["block_p95_submits"]) is int
            and value["block_p95_submits"] <= 8
            and type(value["block_p99_submits"]) is int
            and value["block_p99_submits"] <= 16
            and value["gross_artifact_review"] == "PASS"
            and isinstance(value["review_notes"], str) and value["review_notes"].strip(),
            "G6 static visual receipt differs")
    return value


def verify_png(path: Path, capture: dict[str, Any]) -> None:
    try:
        payload = path.read_bytes()
    except OSError as error:
        raise ContractError(f"cannot read G6 PNG: {error}") from error
    require(payload.startswith(b"\x89PNG\r\n\x1a\n"), "G6 PNG signature is invalid")
    offset = 8
    chunks: list[tuple[bytes, bytes]] = []
    while offset < len(payload):
        require(offset + 12 <= len(payload), "G6 PNG contains a truncated chunk")
        length = struct.unpack(">I", payload[offset:offset + 4])[0]
        chunk_type = payload[offset + 4:offset + 8]
        end = offset + 12 + length
        require(end <= len(payload), "G6 PNG chunk length exceeds the artifact")
        data = payload[offset + 8:offset + 8 + length]
        expected_crc = struct.unpack(">I", payload[offset + 8 + length:end])[0]
        require(zlib.crc32(chunk_type + data) & 0xFFFF_FFFF == expected_crc,
                f"G6 PNG {chunk_type!r} CRC differs")
        chunks.append((chunk_type, data))
        offset = end
        if chunk_type == b"IEND":
            break
    require(offset == len(payload) and chunks and chunks[0][0] == b"IHDR"
            and chunks[-1] == (b"IEND", b""),
            "G6 PNG chunk order/trailing bytes differ")
    ihdr = chunks[0][1]
    require(len(ihdr) == 13, "G6 PNG IHDR length differs")
    width, height, bit_depth, color_type, compression, filtering, interlace = \
        struct.unpack(">IIBBBBB", ihdr)
    require((width, height) == (3024, 1964)
            and capture["framebuffer"] == f"{width}x{height}",
            "G6 PNG dimensions differ")
    require((bit_depth, color_type, compression, filtering, interlace)
            == (8, 6, 0, 0, 0),
            "G6 PNG is not the expected non-interlaced 8-bit RGBA composite")
    require(sum(chunk_type == b"IHDR" for chunk_type, _data in chunks) == 1
            and sum(chunk_type == b"IEND" for chunk_type, _data in chunks) == 1
            and any(chunk_type == b"IDAT" for chunk_type, _data in chunks),
            "G6 PNG lacks a unique IHDR/IEND or image payload")
    decompressor = zlib.decompressobj()
    decoded_bytes = 0
    try:
        for chunk_type, data in chunks:
            if chunk_type == b"IDAT":
                decoded_bytes += len(decompressor.decompress(data))
        decoded_bytes += len(decompressor.flush())
    except zlib.error as error:
        raise ContractError(f"G6 PNG image stream is invalid: {error}") from error
    require(decompressor.eof and not decompressor.unused_data
            and not decompressor.unconsumed_tail
            and decoded_bytes == (width * 4 + 1) * height,
            "G6 PNG decoded scanline extent differs")


def _transcript_path(
        text: str, prefix: str, expected_name: str, label: str, *, count: int = 1,
) -> None:
    values = [line.strip()[len(prefix):] for line in text.splitlines()
              if line.strip().startswith(prefix)]
    require(len(values) == count,
            f"{label} must contain exactly {count} {prefix!r} path receipt(s)")
    require(all(Path(value).is_absolute() and Path(value).name == expected_name
                for value in values),
            f"{label} {prefix!r} path differs")


def verify_transcript_identity(
        text: str, implementation: dict[str, Any], route_id: str, route_sha: str,
        raw: Path, summary: Path, minecraft_log: Path, console_log: Path,
        transcript_log: Path, label: str, *, png: Path | None = None,
        png_sha256: str | None = None,
) -> None:
    measure_frames = (MATRIX_MEASURE_FRAMES
                      if route_id == MATRIX_ROUTE_ID else LIVE_MEASURE_FRAMES)
    exact_lines = (
        f"commit: {implementation['base_commit']} (dirty worktree state)",
        f"source: {implementation['runtime_source_sha256']}",
        f"artifact: {implementation['runtime_artifact_sha256']}",
        f"route: {route_id} ({route_sha})",
        f"fixture: {FIXTURE_ID} ({FIXTURE_SHA256}, read-only)",
        f"settings: {SETTINGS_ID} ({SETTINGS_SHA256}; spec {SETTINGS_SPEC_SHA256})",
        f"frames: {WARMUP_FRAMES} warmup + {measure_frames} measurement",
    )
    stripped = [line.strip() for line in text.splitlines()]
    for expected in exact_lines:
        require(stripped.count(expected) == 1,
                f"{label} lacks exact transcript identity line {expected!r}")
    _transcript_path(text, "raw report: ", raw.name, label)
    _transcript_path(text, "raw: ", raw.name, label)
    _transcript_path(text, "summary: ", summary.name, label)
    _transcript_path(text, "Minecraft log: ", minecraft_log.name, label)
    _transcript_path(text, "console log: ", console_log.name, label, count=2)
    _transcript_path(text, "transcript: ", transcript_log.name, label, count=2)
    if png is None:
        require(png_sha256 is None and not any(
            line.startswith("Reference capture validated (not performance-attested): ")
            for line in stripped
        ), f"{label} unexpectedly contains a reference capture")
    else:
        require(png_sha256 is not None, f"{label} PNG digest is missing")
        _transcript_path(
            text, "Reference capture validated (not performance-attested): ",
            png.name, label,
        )
        require(stripped.count(f"sha256: {png_sha256}") == 1,
                f"{label} capture SHA-256 transcript line differs")


def verify_current_artifact(root: Path, expected: str) -> None:
    command = [
        sys.executable, str(root / "tools/metal_benchmark_fixture.py"),
        "artifact-digest", str(root), "build/classes/java/main",
        "build/resources/main",
        "build/generated/metallum/natives/macos/libmetallum.dylib",
    ]
    try:
        result = subprocess.run(
            command, cwd=root, text=True, capture_output=True, check=False, timeout=90,
        )
    except subprocess.TimeoutExpired as error:
        raise ContractError("current G6 artifact digest timed out") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ContractError(f"cannot compute current G6 artifact digest: {detail}")
    actual = lowercase_sha256(result.stdout.strip(), "current G6 artifact digest")
    require(actual == expected,
            "current compiled Java/resources/native artifact differs from G6 evidence")


def require_manifest_schema(raw_manifest: dict[str, Any]) -> None:
    schema = raw_manifest.get("schema_version")
    require(schema in (1, 2), "G6 manifest schema_version must be 1 or 2")


def verify_manifest(root: Path, manifest_path: Path, *, current_artifact: bool) -> None:
    raw_manifest = strict_json(manifest_path)
    require(isinstance(raw_manifest, dict), "G6 manifest must be an object")
    require_manifest_schema(raw_manifest)
    schema_ver = raw_manifest["schema_version"]
    top_keys = TOP_LEVEL_KEYS_V1 if schema_ver == 1 else TOP_LEVEL_KEYS_V2
    manifest = exact_keys(raw_manifest, top_keys, "G6 manifest")
    require(manifest["id"] == "gi-g6-live-evidence-v1"
            and manifest["stage"] == "G6"
            and manifest["status"] == "G6_COMPLETE_LIVE_DYNAMIC"
            and manifest["decision"] == "PASS_LIVE_DYNAMIC_GI"
            and manifest["g7_allowed"] is True,
            "G6 manifest schema/identity/decision differs")

    implementation = exact_keys(
        manifest["implementation"], IMPLEMENTATION_KEYS, "G6 implementation")
    require(isinstance(implementation["base_commit"], str)
            and re.fullmatch(r"[0-9a-f]{12}", implementation["base_commit"]) is not None,
            "G6 base commit must be an exact 12-digit lowercase commit id")
    lowercase_sha256(implementation["runtime_source_sha256"], "G6 runtime source SHA-256")
    artifact_digest = lowercase_sha256(
        implementation["runtime_artifact_sha256"], "G6 runtime artifact SHA-256")
    require(implementation["dirty_worktree"] is True
            and implementation["persistent_sodium_option"] == "global_illumination"
            and implementation["persistent_renderer_value"] == "globalIllumination=dynamic"
            and implementation["production_default_enabled"] is False
            and implementation["restart_gated"] is True
            and implementation["off_is_structural"] is True,
            "G6 implementation toggle/restart/off contract differs")

    scope = exact_keys(manifest["scope"], SCOPE_KEYS, "G6 scope")
    require(scope == {
        "production_receiver": True, "dynamic_updates": True, "cascade_count": 3,
        "near_to_far_incremental_bricks": True, "block_placement_and_removal": True,
        "camera_independent_held_and_entity_sources": True,
        "clipmap_scroll_overlap": True,
        "fail_closed_world_device_resource_reset": True,
        "receiver_stage": "vertex_only", "fragment_texture3d_samples": 0,
        "bounce_count": 1,
    }, "G6 scope differs")

    live = _verify_receipt_shape(manifest["live_receipt"], "G6 live receipt", matrix=False)
    matrix = _verify_receipt_shape(
        manifest["matrix_receipt"], "G6 matrix receipt", matrix=True) if schema_ver == 2 else None
    capture = verify_capture_manifest(manifest["static_visual_receipt"])

    artifact_keys = ARTIFACT_KEYS_V1 if schema_ver == 1 else ARTIFACT_KEYS_V2
    artifacts = exact_keys(manifest["artifacts"], artifact_keys, "G6 artifacts")
    resolved: dict[str, Path] = {}
    seen_paths: set[Path] = set()
    seen_hashes: set[str] = set()
    for name in sorted(artifact_keys):
        path, digest = safe_artifact(root, artifacts[name], f"G6 artifact {name}")
        require(path not in seen_paths, f"G6 artifact path is reused: {path}")
        require(digest not in seen_hashes, f"G6 artifact digest is reused: {digest}")
        seen_paths.add(path)
        seen_hashes.add(digest)
        resolved[name] = path

    groups = {
        "live": (live, TORCH_ROUTE_ID, TORCH_ROUTE_SHA256),
        "capture": (None, TORCH_ROUTE_ID, TORCH_ROUTE_SHA256),
    }
    if schema_ver == 2:
        groups["matrix"] = (matrix, MATRIX_ROUTE_ID, MATRIX_ROUTE_SHA256)

    canonical_summaries: dict[str, dict[str, Any]] = {}
    for group, (receipt, route_id, route_sha) in groups.items():
        measure_frames = (MATRIX_MEASURE_FRAMES
                          if group == "matrix" else LIVE_MEASURE_FRAMES)
        windows = MATRIX_WINDOWS if group == "matrix" else LIVE_WINDOWS
        raw = resolved[f"{group}_raw"]
        summary_path = resolved[f"{group}_summary"]
        require(raw.name.endswith(".raw.jsonl"), f"G6 {group} raw suffix differs")
        stem = raw.name[:-len(".raw.jsonl")]
        expected_names = {
            "summary": stem + ".summary.json",
            "minecraft_log": stem + ".minecraft.log",
            "console_log": stem + ".console.log",
            "transcript_log": stem + ".transcript.log",
        }
        for suffix, expected_name in expected_names.items():
            require(resolved[f"{group}_{suffix}"].name == expected_name,
                    f"G6 {group} {suffix} does not share the raw stem")
        supplied = strict_json(summary_path)
        require(isinstance(supplied, dict), f"G6 {group} supplied summary is not an object")
        verify_raw_jsonl(raw, f"G6 {group} raw report")
        canonical = recompute_summary(root, raw, measure_frames)
        compare_summary(supplied, canonical, raw, f"G6 {group} summary")
        verify_summary_profile(
            canonical, receipt, implementation, route_id, route_sha, f"G6 {group}",
            measure_frames, windows)
        canonical_summaries[group] = canonical

    # All functional and visual receipts must describe the final exact runtime, not a historical
    # predecessor with a coincidentally compatible copied decision.
    for group, summary in canonical_summaries.items():
        metadata = summary["metadata"]
        require(metadata["source_sha256"] == implementation["runtime_source_sha256"]
                and metadata["artifact_sha256"] == implementation["runtime_artifact_sha256"],
                f"G6 {group} is not bound to the final implementation")

    def read_log(name: str) -> str:
        try:
            return resolved[name].read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as error:
            raise ContractError(f"cannot read G6 log artifact {name}: {error}") from error

    for group, (_receipt, route_id, route_sha) in groups.items():
        verify_transcript_identity(
            read_log(f"{group}_transcript_log"), implementation, route_id, route_sha,
            resolved[f"{group}_raw"], resolved[f"{group}_summary"],
            resolved[f"{group}_minecraft_log"], resolved[f"{group}_console_log"],
            resolved[f"{group}_transcript_log"], f"G6 {group}",
            png=resolved["png"] if group == "capture" else None,
            png_sha256=artifacts["png"]["sha256"] if group == "capture" else None,
        )

    verify_torch_log(
        read_log("live_minecraft_log"), read_log("live_console_log"),
        read_log("live_transcript_log"), live, "G6 live", capture=False,
    )
    if schema_ver == 2:
        verify_matrix_log(
            read_log("matrix_minecraft_log"), read_log("matrix_console_log"),
            read_log("matrix_transcript_log"), matrix, "G6 matrix",
        )

    verify_torch_log(
        read_log("capture_minecraft_log"), read_log("capture_console_log"),
        read_log("capture_transcript_log"), None, "G6 capture", capture=True,
        capture_receipt=capture,
    )
    # Capture timing is recomputed and profile-checked, but is deliberately not copied into the
    # performance verdict: screenshot capture is a separate static-visual receipt.
    verify_png(resolved["png"], capture)

    mechanical_keys = MECHANICAL_KEYS_V1 if schema_ver == 1 else MECHANICAL_KEYS_V2
    mechanical = exact_keys(
        manifest["mechanical_verification"], mechanical_keys,
        "G6 mechanical verification",
    )
    require(all(value == "PASS" for value in mechanical.values()),
            "G6 mechanical verification contains a non-PASS result")

    expected_limitations = EXPECTED_LIMITATIONS_V1 if schema_ver == 1 else EXPECTED_LIMITATIONS_V2
    require(manifest["limitations"] == expected_limitations,
            "G6 limitations differ from the exact completed-functional-matrix boundary")
    expected_next_gate = (
        "G6 is complete; G7 remains the separate live visual, motion, compatibility, and release matrix."
        if schema_ver == 1 else
        "G6 is complete; G7 remains the separate artistic, compatibility, "
        "and product-release matrix."
    )
    require(manifest["next_gate"] == expected_next_gate,
            "G6 next_gate differs")

    if current_artifact:
        # The source fingerprint includes this manifest and its newly copied evidence, so it is
        # necessarily historical after publication.  Transcript/raw metadata bind that historical
        # source; the release gate compares the freshly built runtime artifact instead.
        verify_current_artifact(root, artifact_digest)


def _synthetic_matrix_final() -> dict[str, Any]:
    return {
        "status": "PASS", "ready_mask": 7, "build_in_flight": False,
        "field_generation": 900, "source_tick": 3600, "stale": 0, "rejected": 0,
        "block_samples": 3, "block_p95_submits": 1, "block_p99_submits": 2,
        "block_sla": True, "static_samples": 6,
        "static_p95_submits": 2, "static_p99_submits": 3,
        "static_sla": True, "scroll_samples": 8,
        "scroll_p95_submits": 8, "scroll_p99_submits": 12, "scroll_sla": True,
        "reset_samples": 4, "reset_p95_submits": 20,
        "reset_p99_submits": 30, "reset_sla": True,
        "queue_queued": 100, "queue_completed": 90,
        "queue_discarded": 10, "queue_pending": 0, "queue_in_flight": 0,
        "queue_algebra": True, "measurement_start_bytes": 24_000_000,
        "combined_accounted_bytes": 24_000_000, "cap_bytes": MEMORY_CAP_BYTES,
        "accounted_delta": 0, "readback_bytes": 0, "receipts": 511,
        "orbit_field_stable": True, "queue_converged": True,
    }


def _synthetic_matrix_log() -> tuple[str, dict[str, Any]]:
    receipt = {
        "admission": {
            "status": "PASS", "device_generation": 1, "ready_mask": 1,
            "field_generation": 10, "source_tick": 20, "dynamic": True,
            "vertex_only": True, "fragment_texture3d": 0, "stale": 0, "rejected": 0,
        },
        "events": {
            "ordered_receipt": "PASS", "event_count": len(MATRIX_EVENTS),
            "recovery_count": len(MATRIX_RECOVERIES), "receipt_mask": 511,
        },
        "final": _synthetic_matrix_final(),
        "memory": {
            "combined_accounted_bytes": 24_000_000,
            "cap_bytes": MEMORY_CAP_BYTES, "within_cap": True,
        },
    }
    lines = [
        "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_NETHER_PREPARE "
        f"route={MATRIX_ROUTE_ID} target=0,96,0 chunk=0,0 "
        "full=true forced=true status=PASS",
        "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_READY "
        f"route={MATRIX_ROUTE_ID} held=minecraft:torch entity=minecraft:torch "
        "entity_id=1999999900 status=PASS",
        "METALLUM_BENCHMARK EVENT=SEGMENT_START index=1 mode=OFF phase=WARMUP frames=1800",
        "METALLUM_BENCHMARK EVENT=GI_G6_ADMISSION requested=g6_live resolved=g6_live "
        "contract=6 state=READY device_generation=1 presented_frame=20 ready_mask=1 "
        "field_generation=10 source_tick=20 transport_dispatches=1 cascade_builds=1/0/0 "
        "invalidations=1 bindings=1 zero_bindings=0 field_bindings=1 resident_bytes=1 "
        "staging_bytes=1 java_packet_bytes=1 combined_accounted_bytes=24000000 "
        "cap_bytes=25165824 dynamic=true vertex_only=true fragment_texture3d=0 stale=0 "
        "rejected=0 status=PASS",
        "METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=OFF frames=3600",
    ]
    recovery_after = {event_index: (action, requested, latency_class)
                      for action, requested, event_index, latency_class
                      in MATRIX_RECOVERIES}
    for event_index, (action, requested, index, offset, y_offset) in enumerate(MATRIX_EVENTS):
        if action == "ORBIT_BEGIN":
            suffix = (f"measured_frame={requested} field_generation=50 block_samples=1 "
                      "static_source_samples=2 scroll_samples=3 full_reset_samples=4 "
                      "status=PASS")
        elif action == "ORBIT_END":
            suffix = (f"measured_frame={requested} baseline_generation=50 "
                      "field_generation=50 block_samples=1 static_source_samples=2 "
                      "scroll_samples=3 full_reset_samples=4 status=PASS")
        elif index is None:
            suffix = f"requested_frame={requested} measured_frame={requested} status=PASS"
        else:
            suffix = (f"requested_frame={requested} measured_frame={requested} "
                      f"index={index} offset={offset} y_offset={y_offset} status=PASS")
        lines.append(
            f"METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route={MATRIX_ROUTE_ID} "
            f"action={action} {suffix}"
        )
        if event_index in recovery_after:
            recovery, first_frame, latency_class = recovery_after[event_index]
            terrain_suffix = (
                "terrain_submit_before=100 terrain_submit_after=101 "
                "terrain_device_before=1 terrain_device_after=1 current_device=1 "
                "terrain_field_before=200 terrain_field_after=200 current_field=200 "
                "terrain_source_before=300 terrain_source_after=300 current_source=300 "
                "bind_status=1 carrier_safe=true frame_compatible=true "
                "exact_mask_nonzero=true ready_mask=7 "
                "build_in_flight=false status=PASS"
            )
            if latency_class == "TERRAIN_BIND":
                recovery_suffix = terrain_suffix
            else:
                baseline = 100 + event_index
                sample_before = event_index
                recovery_suffix = (
                    f"baseline_generation={baseline} field_generation={baseline + 1} "
                    f"latency_class={latency_class} sample_before={sample_before} "
                    f"sample_after={sample_before + 1} "
                )
                if recovery == "RESOURCE_RELOAD":
                    resource_terrain = terrain_suffix.replace(
                        "terrain_field_before=200 terrain_field_after=200 current_field=200",
                        f"terrain_field_before={baseline} terrain_field_after={baseline + 1} "
                        f"current_field={baseline + 1}",
                    )
                    recovery_suffix += resource_terrain
                else:
                    ready_mask = 1 if latency_class == "SCROLL" else 7
                    build_in_flight = "true" if latency_class == "SCROLL" else "false"
                    recovery_suffix += (
                        f"ready_mask={ready_mask} build_in_flight={build_in_flight} status=PASS"
                    )
            lines.append(
                f"METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_RECOVERY route={MATRIX_ROUTE_ID} "
                f"action={recovery} requested_frame={first_frame} measured_frame={requested} "
                f"{recovery_suffix}"
            )
    lines.extend([
        "METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode=OFF frames=3600",
        "METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_FINAL "
        f"route={MATRIX_ROUTE_ID} receipts=511 orbit_field_stable=true "
        "queue_converged=true accounted_delta=0 status=PASS contract=6",
        "METALLUM_BENCHMARK EVENT=GI_G6_FINAL state=READY device_generation=1 "
        "admission_emitted=true admission_device_generation=1 ready_mask=7 "
        "build_in_flight=false field_generation=900 source_tick=3600 stale=0 rejected=0 "
        "latest_terrain_device_generation=1 latest_terrain_submit=3600 "
        "latest_bind_status=1 latest_carrier_safe=true latest_frame_compatible=true "
        "latest_ready_mask=7 latest_exact_mask_nonzero=true latest_field_generation=900 "
        "latest_source_tick=3600 combined_accounted_bytes=24000000 cap_bytes=25165824 "
        "block_samples=3 block_p95_submits=1 block_p99_submits=2 block_sla=true "
        "static_samples=6 static_p95_submits=2 static_p99_submits=3 "
        "static_sla=true scroll_samples=8 scroll_p95_submits=8 "
        "scroll_p99_submits=12 scroll_sla=true reset_samples=4 "
        "reset_p95_submits=20 reset_p99_submits=30 reset_sla=true "
        "queue_queued=100 queue_completed=90 queue_discarded=10 "
        "queue_pending=0 queue_in_flight=0 queue_algebra=true "
        "measurement_start_bytes=24000000 accounted_delta=0 readback_bytes=0 "
        "status=PASS contract=6",
        "METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=3600 "
        "framebuffer=3024x1964",
    ])
    return "\n".join(lines) + "\n", receipt


def _expect_failure(callable_value: Any, token: str) -> None:
    try:
        callable_value()
    except ContractError as error:
        require(token in str(error),
                f"self-test expected {token!r}, got {str(error)!r}")
    else:
        raise ContractError(f"self-test unexpectedly accepted {token}")


def run_self_test() -> None:
    _expect_failure(lambda: strict_json_text('{"a":1,"a":2}', "duplicate"), "duplicate")
    _expect_failure(lambda: strict_json_text('{"a":NaN}', "nonfinite"), "non-finite")
    _expect_failure(
        lambda: require_manifest_schema({"schema_version": 3}), "schema_version must be 1 or 2")
    require_manifest_schema({"schema_version": 1})
    require_manifest_schema({"schema_version": 2})
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        bundle = root / EVIDENCE_BUNDLE
        bundle.mkdir(parents=True)
        artifact = bundle / "proof.log"
        artifact.write_text("proof\n", encoding="utf-8")
        descriptor = {
            "path": artifact.relative_to(root).as_posix(), "sha256": sha256(artifact),
        }
        safe_artifact(root, descriptor, "self-test artifact")
        _expect_failure(lambda: safe_artifact(root, {
            "path": "../escape", "sha256": "0" * 64,
        }, "escape"), "unsafe")
        wrong = dict(descriptor)
        wrong["sha256"] = "0" * 64
        _expect_failure(lambda: safe_artifact(root, wrong, "wrong hash"), "differs")
        if hasattr(os, "symlink"):
            link = bundle / "link.log"
            link.symlink_to(artifact)
            _expect_failure(lambda: safe_artifact(root, {
                "path": link.relative_to(root).as_posix(), "sha256": sha256(artifact),
            }, "symlink"), "symlink")

    supplied = {"report": "/relocated/run.raw.jsonl", "metric": 1}
    canonical = {"report": "/tmp/run.raw.jsonl", "metric": 1}
    with tempfile.TemporaryDirectory() as directory:
        raw = Path(directory) / "run.raw.jsonl"
        raw.write_text("{}\n", encoding="utf-8")
        verify_raw_jsonl(raw, "self-test raw")
        canonical["report"] = str(raw.resolve())
        supplied["report"] = "/relocated/run.raw.jsonl"
        compare_summary(supplied, canonical, raw, "self-test summary")
        forged = copy.deepcopy(supplied)
        forged["metric"] = 2
        _expect_failure(
            lambda: compare_summary(forged, canonical, raw, "forged summary"),
            "differs",
        )
        raw.write_text('{"a":1,"a":2}\n', encoding="utf-8")
        _expect_failure(lambda: verify_raw_jsonl(raw, "duplicate raw"), "duplicate")

    log, receipt = _synthetic_matrix_log()
    transcript = (
        "GI_G6_REQUEST mode=g6_live persistent=true dynamic=true receiver=true "
        f"diagnostic_flags=false route={MATRIX_ROUTE_ID} status=REQUESTED\n"
        + log
        + "Benchmark validated: COMPLETE present, no FAIL/screenshots, "
        "dropped timing events = 0\n"
    )
    verify_matrix_log(log, log, transcript, receipt, "self-test matrix")
    short_matrix = log.replace(
        "EVENT=COMPLETE segments=1 measured_frames=3600",
        "EVENT=COMPLETE segments=1 measured_frames=3000",
        1,
    )
    _expect_failure(
        lambda: verify_matrix_log(
            short_matrix, short_matrix, transcript, receipt, "short matrix profile"),
        "COMPLETE differs",
    )
    scroll_recovery_line = next(
        line for line in _event_lines(log, "GI_G6_MATRIX_RECOVERY")
        if " action=STREAM_STEP_0 " in line
    )
    for partial_ready_mask in (3, 5, 7):
        partial_recovery_line = scroll_recovery_line.replace(
            "ready_mask=1", f"ready_mask={partial_ready_mask}"
        )
        scroll_with_near = log.replace(
            scroll_recovery_line, partial_recovery_line, 1
        )
        transcript_with_near = transcript.replace(
            scroll_recovery_line, partial_recovery_line, 1
        )
        verify_matrix_log(
            scroll_with_near, scroll_with_near, transcript_with_near, receipt,
            f"scroll near coverage mask {partial_ready_mask}",
        )
    scroll_without_near = log.replace(
        scroll_recovery_line, scroll_recovery_line.replace("ready_mask=1", "ready_mask=2"), 1
    )
    _expect_failure(
        lambda: verify_matrix_log(
            scroll_without_near, scroll_without_near, transcript, receipt,
            "scroll without near coverage",
        ),
        "matrix recovery STREAM_STEP_0 differs",
    )
    lava_ready_line = next(
        line for line in _event_lines(log, "GI_G6_MATRIX_RECOVERY")
        if " action=LAVA_APPLY " in line
    )
    non_scroll_partial = log.replace(
        lava_ready_line, lava_ready_line.replace("ready_mask=7", "ready_mask=1"), 1
    )
    _expect_failure(
        lambda: verify_matrix_log(
            non_scroll_partial, non_scroll_partial, transcript, receipt,
            "non-scroll partial coverage",
        ),
        "matrix recovery LAVA_APPLY differs",
    )
    teleport_recovery_line = next(
        line for line in _event_lines(log, "GI_G6_MATRIX_RECOVERY")
        if " action=TELEPORT_OUT " in line
    )
    teleport_in_flight = log.replace(
        teleport_recovery_line,
        teleport_recovery_line.replace("build_in_flight=false", "build_in_flight=true"),
        1,
    )
    _expect_failure(
        lambda: verify_matrix_log(
            teleport_in_flight, teleport_in_flight, transcript, receipt,
            "teleport recovery in flight",
        ),
        "matrix recovery TELEPORT_OUT differs",
    )
    missing = log.replace(_event_lines(log, "GI_G6_MATRIX_EVENT")[5] + "\n", "")
    _expect_failure(
        lambda: verify_matrix_log(missing, missing, transcript, receipt, "missing event"),
        "exactly 22",
    )
    reordered_lines = log.splitlines()
    first = reordered_lines.index(_event_lines(log, "GI_G6_MATRIX_EVENT")[0])
    reordered_lines[first], reordered_lines[first + 1] = (
        reordered_lines[first + 1], reordered_lines[first])
    reordered = "\n".join(reordered_lines) + "\n"
    _expect_failure(
        lambda: verify_matrix_log(reordered, reordered, transcript, receipt, "reordered"),
        "matrix event ORBIT_BEGIN fields differ",
    )
    bad_sla = log.replace("scroll_p99_submits=12", "scroll_p99_submits=33")
    _expect_failure(
        lambda: verify_matrix_log(bad_sla, bad_sla, transcript, receipt, "bad SLA"),
        "scroll recovery SLA",
    )
    bad_algebra = log.replace("queue_discarded=10", "queue_discarded=9")
    _expect_failure(
        lambda: verify_matrix_log(bad_algebra, bad_algebra, transcript, receipt, "bad algebra"),
        "scheduler algebra",
    )
    late_event = log.replace(
        "action=LAVA_APPLIED requested_frame=300 measured_frame=300",
        "action=LAVA_APPLIED requested_frame=300 measured_frame=420",
    )
    _expect_failure(
        lambda: verify_matrix_log(late_event, late_event, transcript, receipt, "late event"),
        "deterministic completion window",
    )
    lava_recovery_line = next(
        line for line in _event_lines(log, "GI_G6_MATRIX_RECOVERY")
        if " action=LAVA_APPLY " in line
    )
    late_numeric_recovery = log.replace(
        lava_recovery_line,
        lava_recovery_line.replace("measured_frame=300", "measured_frame=2999"),
        1,
    )
    _expect_failure(
        lambda: verify_matrix_log(
            late_numeric_recovery, late_numeric_recovery, transcript, receipt,
            "late numeric recovery",
        ),
        "numeric recovery window",
    )
    missing_prepare = log.replace(
        _event_lines(log, "GI_G6_MATRIX_NETHER_PREPARE")[0] + "\n", "")
    _expect_failure(
        lambda: verify_matrix_log(
            missing_prepare, missing_prepare, transcript, receipt, "missing prepare"),
        "exactly one GI_G6_MATRIX_NETHER_PREPARE",
    )
    orbit_churn = log.replace(
        "action=ORBIT_END measured_frame=270 baseline_generation=50 field_generation=50",
        "action=ORBIT_END measured_frame=270 baseline_generation=50 field_generation=51",
    )
    _expect_failure(
        lambda: verify_matrix_log(orbit_churn, orbit_churn, transcript, receipt, "orbit churn"),
        "camera orbit changed field/latency identity",
    )
    reused_transition = log.replace(
        "latency_class=STATIC_SOURCE sample_before=2 sample_after=3",
        "latency_class=STATIC_SOURCE sample_before=2 sample_after=4",
    )
    _expect_failure(
        lambda: verify_matrix_log(
            reused_transition, reused_transition, transcript, receipt, "reused transition"),
        "reused another mutation's evidence",
    )
    stale_terrain_bind = log.replace(
        "terrain_submit_before=100 terrain_submit_after=101",
        "terrain_submit_before=100 terrain_submit_after=100",
    )
    _expect_failure(
        lambda: verify_matrix_log(
            stale_terrain_bind, stale_terrain_bind, transcript, receipt,
            "stale terrain bind"),
        "newer current exact terrain bind",
    )
    mismatched_current_field = log.replace(
        "terrain_field_after=200 current_field=200",
        "terrain_field_after=200 current_field=201",
    )
    _expect_failure(
        lambda: verify_matrix_log(
            mismatched_current_field, mismatched_current_field, transcript, receipt,
            "mismatched current field"),
        "newer current exact terrain bind",
    )
    resource_recovery_line = next(
        line for line in _event_lines(log, "GI_G6_MATRIX_RECOVERY")
        if " action=RESOURCE_RELOAD " in line
    )
    resource_fields = _event_fields(
        resource_recovery_line, "GI_G6_MATRIX_RECOVERY", "resource recovery self-test")
    resource_bad_sample_line = resource_recovery_line.replace(
        f"sample_after={resource_fields['sample_after']}",
        f"sample_after={int(resource_fields['sample_after']) + 1}",
        1,
    )
    resource_bad_sample = log.replace(
        resource_recovery_line, resource_bad_sample_line, 1)
    _expect_failure(
        lambda: verify_matrix_log(
            resource_bad_sample, resource_bad_sample, transcript, receipt,
            "resource reload mutation evidence"),
        "reused another mutation's evidence",
    )
    resource_stale_terrain_line = resource_recovery_line.replace(
        f"terrain_submit_after={resource_fields['terrain_submit_after']}",
        f"terrain_submit_after={resource_fields['terrain_submit_before']}",
        1,
    )
    resource_stale_terrain = log.replace(
        resource_recovery_line, resource_stale_terrain_line, 1)
    _expect_failure(
        lambda: verify_matrix_log(
            resource_stale_terrain, resource_stale_terrain, transcript, receipt,
            "resource reload terrain evidence"),
        "newer current exact terrain bind",
    )
    optional = log.replace(
        "static_samples=6 static_p95_submits=2 static_p99_submits=3 static_sla=true",
        "static_samples=0 static_p95_submits=-1 static_p99_submits=-1 static_sla=false",
    ).replace(
        "scroll_samples=8 scroll_p95_submits=8 scroll_p99_submits=12 scroll_sla=true",
        "scroll_samples=0 scroll_p95_submits=-1 scroll_p99_submits=-1 scroll_sla=false",
    ).replace(
        "reset_samples=4 reset_p95_submits=20 reset_p99_submits=30 reset_sla=true",
        "reset_samples=0 reset_p95_submits=-1 reset_p99_submits=-1 reset_sla=false",
    )
    _verify_base_final(optional, "optional classes", require_all_classes=False)
    _expect_failure(
        lambda: _verify_base_final(optional, "matrix optional", require_all_classes=True),
        "unused static",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument("--manifest", type=Path, default=MANIFEST_RELATIVE_PATH)
    parser.add_argument("--self-test", action="store_true")
    artifact_mode = parser.add_mutually_exclusive_group()
    artifact_mode.add_argument(
        "--current-artifact", action="store_true",
        help="verify current compiled outputs match G6 evidence digest",
    )
    artifact_mode.add_argument(
        "--evidence-only", action="store_true",
        help="skip comparison with current compiled outputs (never use for the release Gradle gate)",
    )
    arguments = parser.parse_args()
    try:
        if arguments.self_test:
            run_self_test()
            print("GI G6 evidence verifier self-test passed")
            return
        root = arguments.root.resolve()
        manifest = arguments.manifest
        if not manifest.is_absolute():
            manifest = root / manifest
        verify_specs(root)
        verify_manifest(root, manifest, current_artifact=arguments.current_artifact)
        print("GI G6 immutable live/matrix evidence contract passed")
    except ContractError as error:
        print(f"GI G6 evidence contract FAILED: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
