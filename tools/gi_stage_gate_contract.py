#!/usr/bin/env python3
"""Verify the current G0 recovery, G1 revalidation, and G2 completion gate."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path
from typing import Any


class ContractError(ValueError):
    pass


ARTIFACT = Path("benchmark/gi/gi-stage-gates-2026-08-26-v1.json")
EVIDENCE_PREFIX = ("benchmark", "gi", "evidence", "gi-stage-gates-2026-08-26-v1")
EXPECTED_ROUTES = {
    "overworld": ("gi-g0-overworld-v1", 30.0, 20.0),
    "sealed_cave": ("gi-g0-sealed-cave-v1", 20.0, 10.0),
    "nether": ("gi-g0-nether-v1", 20.0, 10.0),
}
EXPECTED_ROUTE_SHA256 = {
    "gi-g0-overworld-v1": "739eb444dcae83b7e7d769021804e88beb4465d211825644183603e0d6009205",
    "gi-g0-sealed-cave-v1": "d70f3fa26151cd334574be641ab007974e11b01a6b1c0b6f24791b0648569166",
    "gi-g0-nether-v1": "8777717930cafa4d54e35df00a12d516c0bcc6be7379131872f2145228556dbb",
}
EXPECTED_PROFILE_DIGESTS = {
    "fixture_sha256": "a4a7e4fa34bed9e335856bc88f7ad1035ae1ba68e28851906ccaf9a65911e3c5",
    "resource_packs_sha256": "7b2614a3fcf6fb05d64d52debd0358c5506c90204442e96fda98b71d462949df",
    "settings_sha256": "fcf752aebd45a576e13cc19b446b954014b66e46a78c79e435289314d3b4ebb3",
    "settings_spec_sha256": "92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e",
    "sodium_settings_sha256": "b39f856e16d85f5bd53f7acf4b89092371451dd23ae9e41d296967960fddda2a",
}


def strict_json_object(text: str, label: str) -> dict[str, Any]:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ContractError(f"duplicate JSON key {key!r} in {label}")
            result[key] = value
        return result

    try:
        value = json.loads(text, object_pairs_hook=pairs)
    except json.JSONDecodeError as error:
        raise ContractError(f"cannot decode {label}: {error}") from error
    if not isinstance(value, dict):
        raise ContractError(f"{label} must contain a JSON object")
    return value


def strict_object(path: Path) -> dict[str, Any]:
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as error:
        raise ContractError(f"cannot read {path}: {error}") from error
    return strict_json_object(text, str(path))


def strict_jsonl(path: Path) -> list[dict[str, Any]]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ContractError(f"cannot read {path}: {error}") from error
    if not lines:
        raise ContractError(f"{path} must not be empty")
    return [
        strict_json_object(line, f"{path}:{line_number}")
        for line_number, line in enumerate(lines, start=1)
        if line.strip()
    ]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{label} must be numeric")
    return float(value)


def close(actual: Any, expected: Any, label: str) -> None:
    if not math.isclose(number(actual, label), number(expected, label), rel_tol=0.0, abs_tol=1e-9):
        raise ContractError(f"{label} differs: {actual!r} != {expected!r}")


def all_numeric_leaves_zero(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        return value == 0
    if isinstance(value, dict):
        return all(all_numeric_leaves_zero(item) for item in value.values())
    return True


def required_artifact(root: Path, relative: str, expected_hash: str) -> Path:
    path = Path(relative)
    if path.is_absolute() or len(path.parts) != 5 or path.parts[:4] != EVIDENCE_PREFIX:
        raise ContractError(f"unsafe or non-versioned evidence path: {relative}")
    if not isinstance(expected_hash, str) or len(expected_hash) != 64 \
            or any(character not in "0123456789abcdef" for character in expected_hash):
        raise ContractError(f"evidence digest is not SHA-256: {relative}")
    resolved = root / path
    if not resolved.is_file() or resolved.is_symlink():
        raise ContractError(f"required evidence is missing: {relative}")
    if sha256(resolved) != expected_hash:
        raise ContractError(f"evidence digest differs: {relative}")
    return resolved


def summary_metrics(summary: dict[str, Any]) -> tuple[float, float, float]:
    return (
        number(summary["fps"]["elapsed_weighted"], "summary FPS"),
        number(
            summary["fps_low_window_summaries"]["one_percent"]["window_frame_weighted_mean"],
            "summary 1% low",
        ),
        number(
            summary["presenting_command_buffer_gpu_ms"]["percentile_window_summaries"]
            ["p95"]["window_frame_weighted_mean"],
            "summary GPU p95",
        ),
    )


def verify_metadata(
        metadata: dict[str, Any], source: dict[str, Any], expected_route: str) -> None:
    expected = {
        "ablation_mode": "FULL_ADVANCED",
        "artifact_sha256": source["artifact_sha256"],
        "benchmark_simulation_frozen": True,
        "commit": source["commit"][:12],
        "device_name": "Apple M1 Pro",
        "dirty_worktree": False,
        "display_height": 1964,
        "display_maximum_fps": 120,
        "display_sync_enabled": False,
        "display_width": 3024,
        "executor": "METAL3",
        "fixture": "hdrtest-static-v1",
        "global_illumination_mode": "off",
        "graphics_preset": "fancy",
        "hdr_output_mode": "ENHANCED",
        "max_fps": 260,
        "monitor": "Built-in Retina Display",
        "persistent_metalfx_mode": "off",
        "refresh_hz": 120,
        "render_distance": 16,
        "render_height": 1964,
        "render_width": 3024,
        "route": expected_route,
        "scaler_active": False,
        "settings_id": "native-hdr-fancy-v1",
        "simulation_distance": 12,
        "source_encoding": "LINEAR",
        "source_sha256": source["source_sha256"],
        "world": "hdrtest-static-v1",
        **EXPECTED_PROFILE_DIGESTS,
        "route_sha256": EXPECTED_ROUTE_SHA256[expected_route],
    }
    for key, value in expected.items():
        if metadata.get(key) != value:
            raise ContractError(
                f"strict profile metadata differs for {expected_route}: {key}="
                f"{metadata.get(key)!r}, expected {value!r}"
            )
    if number(metadata.get("current_edr_headroom"), "current EDR headroom") <= 1.0:
        raise ContractError(f"native HDR/EDR was not admitted for {expected_route}")
    if metadata.get("thermal_state") not in {"nominal", "fair"}:
        raise ContractError(f"thermal state is invalid for {expected_route}")
    scheduler = metadata.get("extended_promotion_scheduler")
    if not isinstance(scheduler, dict) or scheduler.get("fullscreen") is not True:
        raise ContractError(f"exclusive fullscreen is not attested for {expected_route}")


def counter(system: dict[str, Any], name: str) -> Any:
    counters = system.get("counters")
    return counters.get(name) if isinstance(counters, dict) else system.get(name)


def positive_counter(system: dict[str, Any], name: str, label: str) -> None:
    value = counter(system, name)
    if isinstance(value, dict):
        value = value.get("window_minimum")
    if number(value, label) <= 0:
        raise ContractError(f"{label} must be positive")


def zero_counter(system: dict[str, Any], name: str, label: str) -> None:
    value = counter(system, name)
    if value is None or not all_numeric_leaves_zero(value):
        raise ContractError(f"{label} must be present and all-zero")


def verify_renderer_health(payload: dict[str, Any], label: str) -> None:
    generation = payload.get("renderer_generation")
    if not isinstance(generation, dict) \
            or generation.get("frame_graph_version") != 6 \
            or generation.get("resolved_lighting_model") != "advanced" \
            or generation.get("lighting_preset") != "balanced" \
            or generation.get("resolved_output_mode") != "hdr" \
            or generation.get("resolved_render_contract") != "metallum" \
            or generation.get("resolved_upscale_mode") != "native" \
            or generation.get("resolved_interpolation_mode") != "off" \
            or generation.get("display_width") != 3024 \
            or generation.get("display_height") != 1964 \
            or generation.get("render_width") != 3024 \
            or generation.get("render_height") != 1964:
        raise ContractError(f"Advanced/HDR renderer admission differs in {label}")
    work = generation.get("advanced_lighting_work")
    if not isinstance(work, dict):
        raise ContractError(f"Advanced lighting work is missing in {label}")
    for key in (
            "dispatch_count", "encoder_count", "light_count", "pass_count",
            "pso_count", "upload_bytes", "work_queue_count"):
        if number(work.get(key), f"{label} advanced {key}") <= 0:
            raise ContractError(f"Advanced lighting work is inactive in {label}: {key}")

    clustered = payload.get("clustered_lighting")
    if not isinstance(clustered, dict) or clustered.get("active") is not True \
            or clustered.get("output_independent") is not True:
        raise ContractError(f"L3 clustered lighting is not active in {label}")
    positive_counter(clustered, "cluster_accepted_indices", f"{label} L3 accepted indices")
    positive_counter(clustered, "light_count", f"{label} L3 light count")
    # Per-cluster overflow is an expected, reported property of the immutable
    # dense Nether fixture; global index-capacity loss and admission/ring
    # failures are not.
    for key in (
            "cluster_admission_rejected_lights", "cluster_index_capacity_drops",
            "lighting_ring_busy_rejects"):
        zero_counter(clustered, key, f"{label} L3 {key}")

    voxel = payload.get("voxel_clipmaps")
    if not isinstance(voxel, dict) or voxel.get("active") is not True \
            or voxel.get("output_independent") is not True:
        raise ContractError(f"L5 voxel clipmaps are not active in {label}")
    positive_counter(voxel, "heap_used_bytes", f"{label} L5 heap bytes")
    positive_counter(voxel, "dirty_bricks_completed", f"{label} L5 completed bricks")
    for key in ("dirty_bricks_remaining", "rejected", "ring_busy_rejects", "stale"):
        zero_counter(voxel, key, f"{label} L5 {key}")


def verify_gi_off(payload: dict[str, Any], label: str) -> None:
    gi = payload.get("global_illumination")
    if not isinstance(gi, dict) or gi.get("mode") != "off":
        raise ContractError(f"production GI is not OFF in {label}")
    for key in ("counters", "reset_reasons", "fallback_reasons"):
        value = gi.get(key)
        if value is not None and not all_numeric_leaves_zero(value):
            raise ContractError(f"production GI {key} is non-zero in {label}")
    if "counters" not in gi:
        ignored = {"contract_version", "mode", "window_count", "fallback_reasons", "reset_reasons"}
        direct = {key: value for key, value in gi.items() if key not in ignored}
        if not all_numeric_leaves_zero(direct):
            raise ContractError(f"production GI counters are non-zero in {label}")


def verify_summary_contract(
        summary: dict[str, Any], source: dict[str, Any], expected_route: str,
        expected_frames: int, expected_windows: int, detail_enabled: bool,
        label: str) -> None:
    if summary.get("presented_frames") != expected_frames \
            or summary.get("window_count") != expected_windows \
            or summary.get("detail_enabled") is not detail_enabled \
            or summary.get("dropped_timing_events") != 0 \
            or summary.get("schema_versions") != [6] \
            or summary.get("selection") != \
            "schema-v2+ phase=measure segment=0 scaler=OFF generation=2" \
            or summary.get("thermal", {}).get("thermal_invalid") is not False:
        raise ContractError(f"benchmark summary selection differs in {label}")
    verify_metadata(summary.get("metadata", {}), source, expected_route)
    verify_renderer_health(summary, label)
    verify_gi_off(summary, label)


def selected_measure_windows(
        path: Path, source: dict[str, Any], expected_route: str,
        expected_windows: int, detail_enabled: bool) -> list[dict[str, Any]]:
    rows = strict_jsonl(path)
    selected = [
        row for row in rows
        if row.get("benchmark", {}).get("phase") == "measure"
        and row.get("benchmark", {}).get("generation") == 2
        and row.get("benchmark", {}).get("segment_index") == 0
        and row.get("benchmark", {}).get("scaler_mode") == "OFF"
    ]
    if len(selected) != expected_windows:
        raise ContractError(
            f"{path} has {len(selected)} selected windows, expected {expected_windows}"
        )
    for index, row in enumerate(selected, start=1):
        label = f"{path.name} measure window {index}"
        if row.get("schema_version") != 6 or row.get("presented_frames") != 300 \
                or row.get("detail_enabled") is not detail_enabled \
                or row.get("dropped_timing_events") != 0:
            raise ContractError(f"selected raw timing window differs in {label}")
        verify_metadata(row.get("metadata", {}), source, expected_route)
        verify_renderer_health(row, label)
        verify_gi_off(row, label)
    return selected


def raw_summary_metrics(windows: list[dict[str, Any]], label: str) -> tuple[float, float, float]:
    frames = sum(number(window["presented_frames"], f"{label} frames") for window in windows)
    elapsed_seconds = sum(
        number(window["presented_frames"], f"{label} frames")
        / number(window["fps"], f"{label} FPS")
        for window in windows
    )
    low = sum(
        number(window["fps_1_percent_low"], f"{label} 1% low")
        * number(window["presented_frames"], f"{label} frames")
        for window in windows
    ) / frames
    gpu = sum(
        number(window["presenting_command_buffer_gpu_ms"]["p95"], f"{label} GPU p95")
        * number(window["presented_frames"], f"{label} frames")
        for window in windows
    ) / frames
    return frames / elapsed_seconds, low, gpu


def verify_historical(root: Path, historical: dict[str, Any]) -> None:
    expected = {
        "g0": ("REJECTED_BASELINE_FLOOR", None),
        "g1": ("SUPPORTED_PENDING_ABSOLUTE_FLOOR", "PASS"),
        "g2": ("PASS_STRUCTURALLY_OFF_DIAGNOSTIC", None),
    }
    if set(historical) != set(expected):
        raise ContractError("historical GI evidence set differs")
    for stage, (decision, relative_gate) in expected.items():
        entry = historical[stage]
        path = root / entry["path"]
        if entry.get("preserved") is not True or sha256(path) != entry.get("sha256"):
            raise ContractError(f"historical {stage.upper()} evidence was rewritten")
        source = strict_object(path)
        if source.get("decision") != decision or entry.get("decision") != decision:
            raise ContractError(f"historical {stage.upper()} decision differs")
        if relative_gate is not None:
            runtime = source.get("contemporaneous_runtime_ab")
            if not isinstance(runtime, dict) or runtime.get("relative_gate") != relative_gate \
                    or entry.get("relative_gate") != relative_gate:
                raise ContractError("historical G1 relative gate is not PASS")


def verify_g0(root: Path, artifact: dict[str, Any], source: dict[str, Any]) -> dict[str, Any]:
    recovery = artifact.get("g0_recovery")
    if not isinstance(recovery, dict) \
            or recovery.get("classification") != "TIER_C_RELEASE_ATTESTED_CURRENT_BASELINE" \
            or recovery.get("decision") != "PASS" \
            or recovery.get("g1_allowed") is not True \
            or recovery.get("warmup_frames") != 1800 \
            or recovery.get("measure_frames") != 3000 \
            or recovery.get("windows") != 10:
        raise ContractError("current G0 recovery decision is not a strict Tier C PASS")
    routes = recovery.get("routes")
    if not isinstance(routes, list) or len(routes) != 3:
        raise ContractError("current G0 recovery requires exactly three routes")
    seen: dict[str, dict[str, Any]] = {}
    for route in routes:
        if not isinstance(route, dict):
            raise ContractError("G0 route evidence must be an object")
        role = route.get("role")
        if role not in EXPECTED_ROUTES or role in seen:
            raise ContractError(f"unknown or duplicate G0 route: {role}")
        expected_route, average_floor, low_floor = EXPECTED_ROUTES[role]
        if route.get("route") != expected_route:
            raise ContractError(f"G0 route identity differs for {role}")
        close(route.get("average_fps_floor"), average_floor, f"{role} average floor")
        close(route.get("one_percent_low_fps_floor"), low_floor, f"{role} low floor")
        runs = route.get("runs")
        if not isinstance(runs, list) or len(runs) != 2:
            raise ContractError(f"{role} requires two independent runs")
        for run in runs:
            if run.get("thermal_state") not in {"nominal", "fair"} \
                    or run.get("gi_off_verified") is not True \
                    or number(run.get("average_fps"), "average FPS") < average_floor \
                    or number(run.get("one_percent_low_fps"), "1% low") < low_floor:
                raise ContractError(f"{role} does not pass its absolute Tier C floor")
            receipt = required_artifact(root, run["receipt"], run["receipt_sha256"])
            receipt_json = strict_object(receipt)
            metadata = receipt_json.get("metadata", {})
            measurement = receipt_json.get("measurement", {})
            if receipt_json.get("accepted") is not True \
                    or receipt_json.get("schema_version") != 6 \
                    or receipt_json.get("presented_frames") != 3000 \
                    or measurement.get("generation") != 2 \
                    or measurement.get("measure_frames") != 3000 \
                    or measurement.get("segment") != 0 \
                    or measurement.get("scaler_mode") != "OFF":
                raise ContractError(f"Tier C receipt contract differs: {receipt}")
            verify_metadata(metadata, source, expected_route)

            prefix = receipt.name.removesuffix(".accepted.json")
            bundled = {
                "summary": receipt.with_name(f"{prefix}.summary.json"),
                "raw": receipt.with_name(f"{prefix}.raw.jsonl"),
                "console_log": receipt.with_name(f"{prefix}.console.log"),
                "minecraft_log": receipt.with_name(f"{prefix}.minecraft.log"),
            }
            if receipt_json.get("summary_sha256") != run["summary_sha256"]:
                raise ContractError(f"Tier C summary digest was not bound by {receipt}")
            for key, path in bundled.items():
                required_artifact(
                    root, path.relative_to(root).as_posix(), receipt_json[f"{key}_sha256"]
                )

            summary = strict_object(bundled["summary"])
            verify_summary_contract(
                summary, source, expected_route, 3000, 10, False, bundled["summary"].name
            )
            raw_windows = selected_measure_windows(
                bundled["raw"], source, expected_route, 10, False
            )
            fps, low, gpu = summary_metrics(summary)
            raw_fps, raw_low, raw_gpu = raw_summary_metrics(raw_windows, bundled["raw"].name)
            close(fps, raw_fps, "G0 raw-derived FPS")
            close(low, raw_low, "G0 raw-derived 1% low")
            close(gpu, raw_gpu, "G0 raw-derived GPU p95")
            close(fps, run["average_fps"], "G0 receipt FPS")
            close(low, run["one_percent_low_fps"], "G0 receipt 1% low")
            close(gpu, run["gpu_p95_ms"], "G0 receipt GPU p95")
        close(route["mean_average_fps"], sum(run["average_fps"] for run in runs) / 2.0,
              f"{role} mean FPS")
        close(route["mean_one_percent_low_fps"],
              sum(run["one_percent_low_fps"] for run in runs) / 2.0, f"{role} mean 1% low")
        close(route["mean_gpu_p95_ms"], sum(run["gpu_p95_ms"] for run in runs) / 2.0,
              f"{role} mean GPU p95")
        seen[role] = route
    if set(seen) != set(EXPECTED_ROUTES):
        raise ContractError("G0 recovery route set differs")
    return seen


def verify_g1(artifact: dict[str, Any], routes: dict[str, dict[str, Any]]) -> None:
    g1 = artifact.get("g1_revalidation")
    if not isinstance(g1, dict) \
            or g1.get("classification") != "TIER_C_ABSOLUTE_FLOOR_REVALIDATION" \
            or g1.get("decision") != "PASS" or g1.get("g2_allowed") is not True \
            or g1.get("relative_gate") != "PASS" \
            or g1.get("relative_gate_source") != "benchmark/gi/g1-field-evidence-v1.json":
        raise ContractError("current G1 gate is not revalidated")
    average_floor = number(g1.get("conditional_average_fps_floor"), "G1 average floor")
    low_floor = number(g1.get("conditional_one_percent_low_fps_floor"), "G1 low floor")
    if average_floor != 28.0 or low_floor != 20.0:
        raise ContractError("G1 conditional floor was changed")
    overworld = routes["overworld"]["runs"]
    receipts = g1.get("overworld_receipts")
    if receipts != [run["receipt"] for run in overworld]:
        raise ContractError("G1 revalidation does not reuse the two current Overworld receipts")
    if any(run["average_fps"] < average_floor or run["one_percent_low_fps"] < low_floor
           for run in overworld):
        raise ContractError("G1 current absolute floor is not passed by every run")


def verify_summary(
        root: Path, values: dict[str, Any], source: dict[str, Any], expected_route: str) -> None:
    summary_path = required_artifact(root, values["summary"], values["summary_sha256"])
    raw_path = required_artifact(root, values["raw"], values["raw_sha256"])
    summary = strict_object(summary_path)
    verify_summary_contract(
        summary, source, expected_route, 3000, 10, False, summary_path.name
    )
    raw_windows = selected_measure_windows(raw_path, source, expected_route, 10, False)
    fps, low, gpu = summary_metrics(summary)
    raw_fps, raw_low, raw_gpu = raw_summary_metrics(raw_windows, raw_path.name)
    close(fps, raw_fps, "G2 raw-derived FPS")
    close(low, raw_low, "G2 raw-derived 1% low")
    close(gpu, raw_gpu, "G2 raw-derived GPU p95")
    close(fps, values["average_fps"], "G2 summary FPS")
    close(low, values["one_percent_low_fps"], "G2 summary 1% low")
    close(gpu, values["gpu_p95_ms"], "G2 summary GPU p95")


def verify_g2(root: Path, artifact: dict[str, Any], source: dict[str, Any]) -> None:
    g2 = artifact.get("g2_revalidation")
    if not isinstance(g2, dict) \
            or g2.get("classification") != "TIER_B_EXTENDED_DIAGNOSTIC_NOT_RELEASE" \
            or g2.get("decision") != "PASS" \
            or g2.get("enabled_environment") != "METALLUM_GI_G2_CAPTURE=1":
        raise ContractError("current G2 completion is not a structural-off diagnostic PASS")
    gates = g2.get("gates")
    if gates != {
        "maximum_average_fps_loss": 2.0,
        "maximum_one_percent_low_regression_percent": 10.0,
        "maximum_gpu_p95_regression_ms": 0.2,
        "maximum_world_opaque_p95_regression_ms": 0.3,
    }:
        raise ContractError("G2 stop-gate thresholds were changed")

    negative = g2.get("short_screening_negative_evidence")
    if not isinstance(negative, dict) \
            or negative.get("classification") != "TIER_B_2X2_INSUFFICIENT_TWO_WINDOW_REGRESSION" \
            or negative.get("decision") != "FAIL_GPU_P95_THRESHOLD" \
            or negative.get("pairwise_gpu_p95_regressions") != 4 \
            or number(negative.get("mean_gpu_p95_delta_ms"), "short GPU p95 delta") <= 0.2:
        raise ContractError("G2 short-screening negative evidence was hidden")
    short_metrics: dict[str, list[tuple[float, float, float]]] = {}
    for role, paths_key, hashes_key in (
            ("control", "control_summaries", "control_summary_sha256"),
            ("candidate", "candidate_summaries", "candidate_summary_sha256")):
        paths = negative.get(paths_key)
        hashes = negative.get(hashes_key)
        if not isinstance(paths, list) or not isinstance(hashes, list) or len(paths) != 2 or len(hashes) != 2:
            raise ContractError("G2 2x2 screening evidence set differs")
        metrics: list[tuple[float, float, float]] = []
        for path, digest in zip(paths, hashes):
            summary_path = required_artifact(root, path, digest)
            summary = strict_object(summary_path)
            verify_summary_contract(
                summary, source, "gi-g0-overworld-v1", 600, 2, False, summary_path.name
            )
            metrics.append(summary_metrics(summary))
        short_metrics[role] = metrics

    control_means = tuple(
        sum(values[index] for values in short_metrics["control"]) / 2.0
        for index in range(3)
    )
    candidate_means = tuple(
        sum(values[index] for values in short_metrics["candidate"]) / 2.0
        for index in range(3)
    )
    close(
        negative["mean_average_fps_delta_percent"],
        (candidate_means[0] / control_means[0] - 1.0) * 100.0,
        "short 2x2 mean FPS delta",
    )
    close(
        negative["mean_one_percent_low_delta_percent"],
        (candidate_means[1] / control_means[1] - 1.0) * 100.0,
        "short 2x2 mean 1% low delta",
    )
    close(
        negative["mean_gpu_p95_delta_ms"],
        candidate_means[2] - control_means[2],
        "short 2x2 mean GPU p95 delta",
    )
    pairwise_regressions = sum(
        candidate[2] - control[2] > gates["maximum_gpu_p95_regression_ms"]
        for candidate in short_metrics["candidate"]
        for control in short_metrics["control"]
    )
    if negative["pairwise_gpu_p95_regressions"] != pairwise_regressions:
        raise ContractError("G2 short-screening pairwise GPU result was not derived")

    stage = g2.get("stage_attribution")
    if not isinstance(stage, dict) \
            or stage.get("classification") != "TIER_B_DETAILED_MARKERS" \
            or stage.get("decision") != "PASS_NO_WORLD_OPAQUE_REGRESSION":
        raise ContractError("G2 stage attribution is missing")
    control_raw = required_artifact(root, stage["control_raw"], stage["control_raw_sha256"])
    candidate_raw = required_artifact(root, stage["candidate_raw"], stage["candidate_raw_sha256"])
    control_windows = selected_measure_windows(
        control_raw, source, "gi-g0-overworld-v1", 2, True
    )
    candidate_windows = selected_measure_windows(
        candidate_raw, source, "gi-g0-overworld-v1", 2, True
    )
    world_control = sum(
        number(window["stages"]["world opaque"]["p95_ms"], "control WORLD_OPAQUE p95")
        for window in control_windows
    ) / 2.0
    world_candidate = sum(
        number(window["stages"]["world opaque"]["p95_ms"], "candidate WORLD_OPAQUE p95")
        for window in candidate_windows
    ) / 2.0
    gpu_control = sum(
        number(window["presenting_command_buffer_gpu_ms"]["p95"], "control whole GPU p95")
        for window in control_windows
    ) / 2.0
    gpu_candidate = sum(
        number(window["presenting_command_buffer_gpu_ms"]["p95"], "candidate whole GPU p95")
        for window in candidate_windows
    ) / 2.0
    close(stage["world_opaque_control_p95_ms"], world_control, "WORLD_OPAQUE control p95")
    close(stage["world_opaque_candidate_p95_ms"], world_candidate, "WORLD_OPAQUE candidate p95")
    close(stage["world_opaque_p95_delta_ms"], world_candidate - world_control,
          "WORLD_OPAQUE p95 delta")
    close(stage["whole_gpu_p95_delta_ms"], gpu_candidate - gpu_control,
          "detailed whole GPU p95 delta")
    if stage["world_opaque_p95_delta_ms"] > gates["maximum_world_opaque_p95_regression_ms"]:
        raise ContractError("G2 WORLD_OPAQUE diagnostic gate does not pass")

    pair = g2.get("extended_pair")
    if not isinstance(pair, dict) or pair.get("order") != ["candidate", "control"] \
            or pair.get("warmup_frames") != 1800 or pair.get("measure_frames") != 3000 \
            or pair.get("windows") != 10 or pair.get("decision") != "PASS":
        raise ContractError("G2 extended adjacent pair contract differs")
    control = pair["control"]
    candidate = pair["candidate"]
    verify_summary(root, control, source, "gi-g0-overworld-v1")
    verify_summary(root, candidate, source, "gi-g0-overworld-v1")
    deltas = pair["deltas"]
    expected = {
        "average_fps_percent": (candidate["average_fps"] / control["average_fps"] - 1.0) * 100.0,
        "one_percent_low_percent": (
            candidate["one_percent_low_fps"] / control["one_percent_low_fps"] - 1.0
        ) * 100.0,
        "gpu_p95_percent": (candidate["gpu_p95_ms"] / control["gpu_p95_ms"] - 1.0) * 100.0,
        "gpu_p95_ms": candidate["gpu_p95_ms"] - control["gpu_p95_ms"],
    }
    for key, value in expected.items():
        close(deltas.get(key), value, f"G2 {key}")
    if candidate["average_fps"] < control["average_fps"] - gates["maximum_average_fps_loss"] \
            or deltas["one_percent_low_percent"] < -gates["maximum_one_percent_low_regression_percent"] \
            or deltas["gpu_p95_ms"] > gates["maximum_gpu_p95_regression_ms"]:
        raise ContractError("G2 extended adjacent pair does not pass its stop-gate")

    log_path = required_artifact(
        root, candidate["minecraft_log"], candidate["minecraft_log_sha256"]
    )
    semantic = g2.get("semantic_capture")
    if not isinstance(semantic, dict) or semantic.get("accepted", 0) <= 0 \
            or semantic.get("stale") != 0 or semantic.get("outside") != 0 \
            or semantic.get("capacity_rejected") != 0 or semantic.get("discarded") != 0 \
            or semantic.get("active_candidates_at_shutdown") != 0 \
            or semantic.get("peak_candidates", 65) > 64 \
            or semantic.get("peak_candidate_bytes", 1_009_153) > 64 * 15_768:
        raise ContractError("G2 semantic publication or bounded-candidate gate differs")
    expected_line = (
        f"[GI_G2] accepted={semantic['accepted']} stale=0 outside=0 capacityRejected=0 "
        f"discarded=0 residentTags={semantic['resident_tags']} activeCandidates=0 "
        f"peakCandidates={semantic['peak_candidates']} "
        f"peakCandidateBytes={semantic['peak_candidate_bytes']}"
    )
    log_text = log_path.read_text(encoding="utf-8")
    if "[GI_G2] accepted-output semantic capture active for minecraft:overworld" \
            not in log_text or expected_line not in log_text:
        raise ContractError("G2 extended live semantic telemetry differs")
    if any(g2.get(key) != 0 for key in (
            "production_gi_resources", "production_gi_passes", "production_gi_bindings")):
        raise ContractError("G2 completion added production GI work")


def verify(root: Path) -> None:
    artifact = strict_object(root / ARTIFACT)
    for route, digest in EXPECTED_ROUTE_SHA256.items():
        route_path = root / "benchmark" / "routes" / f"{route}.json"
        if sha256(route_path) != digest:
            raise ContractError(f"canonical route digest differs: {route_path}")
    settings_path = root / "benchmark" / "settings" / "native-hdr-fancy-v1.json"
    if sha256(settings_path) != EXPECTED_PROFILE_DIGESTS["settings_spec_sha256"]:
        raise ContractError(f"canonical settings digest differs: {settings_path}")
    if artifact.get("schema_version") != 1 \
            or artifact.get("id") != "gi-stage-gates-2026-08-26-v1" \
            or artifact.get("decision") != "G2_COMPLETE_STRUCTURALLY_OFF" \
            or artifact.get("g0_current") != "PASS_RECOVERED_BASELINE" \
            or artifact.get("g1_current") != "PASS_ABSOLUTE_FLOOR_REVALIDATED" \
            or artifact.get("g2_current") != "PASS_STRUCTURALLY_OFF_DIAGNOSTIC" \
            or artifact.get("g3_allowed") is not True \
            or artifact.get("g3_started") is not False:
        raise ContractError("current GI stage decision differs")
    interval = artifact.get("evidence_interval_utc")
    if not isinstance(interval, list) or len(interval) != 2 \
            or not all(isinstance(value, str) and value.endswith("Z") for value in interval):
        raise ContractError("current GI evidence interval is not two UTC timestamps")
    source = artifact.get("source")
    if not isinstance(source, dict) or len(source.get("commit", "")) != 40 \
            or len(source.get("source_sha256", "")) != 64 \
            or len(source.get("artifact_sha256", "")) != 64 \
            or source.get("dirty_worktree") is not False:
        raise ContractError("current GI source identity is not clean and immutable")
    profile = artifact.get("profile")
    if profile != {
        "device": "Apple M1 Pro", "monitor": "Built-in Retina Display",
        "width": 3024, "height": 1964, "refresh_hz": 120,
        "output": "native_hdr_scene", "graphics": "fancy",
        "render_distance": 16, "simulation_distance": 12, "max_fps": 260,
        "vsync": False, "metalfx": "OFF", "lighting": "advanced",
        "lighting_preset": "balanced", "settings": "native-hdr-fancy-v1",
        "fixture": "hdrtest-static-v1",
    }:
        raise ContractError("current GI quality/profile contract differs")
    verify_historical(root, artifact.get("historical_evidence", {}))
    routes = verify_g0(root, artifact, source)
    verify_g1(artifact, routes)
    verify_g2(root, artifact, source)
    if artifact.get("next_stage") != {
        "stage": "G3", "allowed": True, "started": False,
        "requires_separate_user_request": True,
    }:
        raise ContractError("G3 was started or lost its separate-request boundary")
    print("GI current G0 recovery / G1 revalidation / G2 completion gate passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    try:
        verify(arguments.root.resolve())
    except (ContractError, OSError, json.JSONDecodeError, KeyError, TypeError,
            ZeroDivisionError) as error:
        raise SystemExit(f"GI current stage gate FAILED: {error}") from error


if __name__ == "__main__":
    main()
