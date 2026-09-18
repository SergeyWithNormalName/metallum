#!/usr/bin/env python3
"""Verify the immutable Stage G0 fixture, route, and GI_OFF settings contract."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any


class ContractError(ValueError):
    pass


REQUIRED_SCENARIOS = {
    "white-room-no-source", "red-emitter", "green-emitter",
    "skylight-aperture", "lava-landmark", "thin-geometry",
    "chunk-boundary", "cascade-boundary", "scroll", "teleport",
}
EXPECTED_ROUTE_DIMENSIONS = {
    "overworld": "minecraft:overworld",
    "sealed_cave": "minecraft:overworld",
    "nether": "minecraft:the_nether",
}
SETTINGS = (
    "benchmark/settings/native-hdr-fancy-v1.json",
    "benchmark/settings/native-sdr-fancy-v1.json",
    "benchmark/settings/nether-lava-stress-v1.json",
)
DECISION_ARTIFACT = Path("benchmark/gi/g0-acceptance-v1.json")
RUN_KEYS = {
    "id", "receipt", "receipt_sha256", "average_fps",
    "one_percent_low_fps", "gpu_p95_ms", "thermal_state", "windows",
    "presented_frames", "gi_off_verified",
}


def _strict_object(path: Path) -> dict[str, Any]:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ContractError(f"duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result

    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs)
    except (OSError, json.JSONDecodeError) as error:
        raise ContractError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise ContractError(f"{path} must contain a JSON object")
    return value


def _sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as error:
        raise ContractError(f"cannot hash {path}: {error}") from error


def _exact_keys(value: dict[str, Any], expected: set[str], label: str) -> None:
    if set(value) != expected:
        missing = sorted(expected - set(value))
        extra = sorted(set(value) - expected)
        raise ContractError(f"{label} keys differ; missing={missing}, extra={extra}")


def _number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{label} must be numeric")
    return float(value)


def _close(actual: Any, expected: Any, label: str) -> None:
    if abs(_number(actual, label) - _number(expected, label)) > 1e-9:
        raise ContractError(f"{label} differs: {actual} != {expected}")


def _all_numeric_leaves_zero(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        return value == 0
    if isinstance(value, dict):
        return all(_all_numeric_leaves_zero(item) for item in value.values())
    return True


def _verify_decision_artifact(root: Path, manifest: dict[str, Any]) -> None:
    artifact_file = root / DECISION_ARTIFACT
    artifact = _strict_object(artifact_file)
    _exact_keys(artifact, {
        "schema_version", "id", "stage", "decision", "g1_allowed", "reason",
        "evidence_interval_utc", "baseline_source", "profile", "settings",
        "fixture", "gates", "routes",
    }, "G0 decision artifact")
    if (artifact["schema_version"], artifact["id"], artifact["stage"]) != (
            1, "gi-g0-acceptance-v1", "G0"):
        raise ContractError("unsupported G0 decision artifact identity")
    if artifact["decision"] != "REJECTED_BASELINE_FLOOR" \
            or artifact["g1_allowed"] is not False \
            or artifact["reason"] != "overworld_below_absolute_floor":
        raise ContractError("G0 decision must preserve the measured baseline rejection")
    interval = artifact["evidence_interval_utc"]
    if not isinstance(interval, list) or len(interval) != 2 \
            or not all(isinstance(value, str) and value.endswith("Z") for value in interval):
        raise ContractError("G0 evidence interval must contain two UTC timestamps")

    baseline = artifact["baseline_source"]
    if not isinstance(baseline, dict):
        raise ContractError("baseline_source must be an object")
    _exact_keys(baseline, {"commit", "source_sha256", "artifact_sha256"}, "baseline_source")
    for key in ("source_sha256", "artifact_sha256"):
        if not isinstance(baseline[key], str) or len(baseline[key]) != 64:
            raise ContractError(f"baseline_source.{key} is not SHA-256")

    expected_profile = {
        "device": "Apple M1 Pro", "monitor": "Built-in Retina Display",
        "width": 3024, "height": 1964, "refresh_hz": 120,
        "exclusive_fullscreen": True, "output": "native_hdr_scene",
        "source_encoding": "linear", "graphics": "fancy",
        "render_distance": 16, "simulation_distance": 12, "max_fps": 260,
        "vsync": False, "metalfx": "OFF", "lighting": "advanced",
        "lighting_preset": "balanced", "timing_detail": False,
        "metal_validation": False, "warmup_frames": 1800,
        "measure_frames": 3000, "windows": 10,
        "thermal_requirement": "nominal_or_fair",
    }
    if artifact["profile"] != expected_profile:
        raise ContractError("G0 M1 Pro profile differs from the approved exact contract")

    settings = artifact["settings"]
    if not isinstance(settings, dict):
        raise ContractError("G0 settings must be an object")
    _exact_keys(settings, {
        "path", "id", "spec_sha256", "runtime_sha256", "renderer_schema",
        "global_illumination",
    }, "G0 settings")
    if settings["path"] != SETTINGS[0] or settings["id"] != "native-hdr-fancy-v1" \
            or settings["renderer_schema"] != 5 \
            or settings["global_illumination"] != "off" \
            or not isinstance(settings["runtime_sha256"], str) \
            or len(settings["runtime_sha256"]) != 64 \
            or settings["spec_sha256"] != _sha256(root / SETTINGS[0]):
        raise ContractError("G0 settings identity/digest/GI_OFF contract differs")

    fixture = artifact["fixture"]
    if fixture != {
        "id": manifest["baseline_world"]["fixture_id"],
        "sha256": manifest["baseline_world"]["fixture_sha256"],
    }:
        raise ContractError("G0 decision artifact fixture differs from manifest")
    if artifact["gates"] != {
        "relative_gpu_p95_regression_max_percent": 8.0,
        "relative_one_percent_low_regression_max_percent": 10.0,
        "absolute_policy": "every_run_must_meet_both_route_floors",
    }:
        raise ContractError("G0 relative or absolute gates differ")

    manifest_routes = {entry["role"]: entry for entry in manifest["baseline_routes"]}
    routes = artifact["routes"]
    if not isinstance(routes, list) or len(routes) != 3:
        raise ContractError("G0 decision artifact must contain exactly three routes")
    expected_floors = {
        "overworld": (30.0, 20.0), "sealed_cave": (20.0, 10.0),
        "nether": (20.0, 10.0),
    }
    seen_roles: set[str] = set()
    failing_roles: set[str] = set()
    for route in routes:
        if not isinstance(route, dict):
            raise ContractError("G0 route result must be an object")
        _exact_keys(route, {
            "role", "route", "route_sha256", "average_fps_floor",
            "one_percent_low_fps_floor", "mean_average_fps",
            "mean_one_percent_low_fps", "mean_gpu_p95_ms", "decision", "runs",
        }, "G0 route result")
        role = route["role"]
        if role not in expected_floors or role in seen_roles:
            raise ContractError(f"unknown or duplicate G0 route role: {role}")
        seen_roles.add(role)
        manifest_route = manifest_routes[role]
        route_spec = _strict_object(root / manifest_route["path"])
        if route["route"] != route_spec["id"] \
                or route["route_sha256"] != manifest_route["route_sha256"]:
            raise ContractError(f"G0 route identity differs for {role}")
        average_floor, low_floor = expected_floors[role]
        _close(route["average_fps_floor"], average_floor, f"{role} average floor")
        _close(route["one_percent_low_fps_floor"], low_floor, f"{role} low floor")
        runs = route["runs"]
        if not isinstance(runs, list) or len(runs) != 2:
            raise ContractError(f"{role} requires exactly two independent Tier C runs")
        run_passes: list[bool] = []
        for run in runs:
            if not isinstance(run, dict):
                raise ContractError(f"{role} run must be an object")
            _exact_keys(run, RUN_KEYS, f"{role} run")
            if run["thermal_state"] not in {"nominal", "fair"} \
                    or run["windows"] != 10 or run["presented_frames"] != 3000 \
                    or run["gi_off_verified"] is not True:
                raise ContractError(f"{role} run violates Tier C/GI_OFF contract")
            run_passes.append(
                _number(run["average_fps"], "average FPS") >= average_floor
                and _number(run["one_percent_low_fps"], "1% low FPS") >= low_floor
            )
            receipt_relative = run["receipt"]
            if not isinstance(receipt_relative, str):
                raise ContractError(f"{role} receipt path must be a string")
            if not isinstance(run["receipt_sha256"], str) \
                    or len(run["receipt_sha256"]) != 64:
                raise ContractError(f"{role} receipt digest is not SHA-256")
            receipt_parts = Path(receipt_relative).parts
            if receipt_parts[:3] != ("run", "logs", "metallum-benchmarks") \
                    or not receipt_relative.endswith(".accepted.json"):
                raise ContractError(f"unsafe G0 receipt path: {receipt_relative}")
            receipt_path = root / receipt_relative
            if receipt_path.is_file():
                if _sha256(receipt_path) != run["receipt_sha256"]:
                    raise ContractError(f"receipt digest mismatch: {receipt_path}")
                receipt = _strict_object(receipt_path)
                metadata = receipt.get("metadata")
                measurement = receipt.get("measurement")
                if receipt.get("accepted") is not True or receipt.get("schema_version") != 6 \
                        or receipt.get("presented_frames") != 3000 \
                        or not isinstance(metadata, dict) or not isinstance(measurement, dict) \
                        or metadata.get("source_sha256") != baseline["source_sha256"] \
                        or metadata.get("artifact_sha256") != baseline["artifact_sha256"] \
                        or metadata.get("route") != route["route"] \
                        or metadata.get("route_sha256") != route["route_sha256"] \
                        or metadata.get("settings_sha256") != settings["runtime_sha256"] \
                        or metadata.get("fixture_sha256") != fixture["sha256"] \
                        or metadata.get("global_illumination_mode") != "off" \
                        or measurement.get("measure_frames") != 3000 \
                        or measurement.get("scaler_mode") != "OFF":
                    raise ContractError(f"receipt contract mismatch: {receipt_path}")
                summary_path = receipt_path.with_name(
                    receipt_path.name.replace(".accepted.json", ".summary.json")
                )
                if summary_path.is_file():
                    summary = _strict_object(summary_path)
                    _close(summary["fps"]["elapsed_weighted"], run["average_fps"], "receipt average FPS")
                    _close(
                        summary["fps_low_window_summaries"]["one_percent"]["window_frame_weighted_mean"],
                        run["one_percent_low_fps"], "receipt 1% low FPS",
                    )
                    _close(
                        summary["presenting_command_buffer_gpu_ms"]["percentile_window_summaries"]["p95"]["window_frame_weighted_mean"],
                        run["gpu_p95_ms"], "receipt GPU p95",
                    )
                    gi = summary.get("global_illumination")
                    if not isinstance(gi, dict) or gi.get("mode") != "off" \
                            or not _all_numeric_leaves_zero(gi.get("counters", {})) \
                            or not _all_numeric_leaves_zero(gi.get("reset_reasons", {})) \
                            or not _all_numeric_leaves_zero(gi.get("fallback_reasons", {})):
                        raise ContractError(f"summary GI_OFF telemetry is not zero: {summary_path}")
        _close(
            route["mean_average_fps"],
            sum(_number(run["average_fps"], "average FPS") for run in runs) / 2.0,
            f"{role} mean average FPS",
        )
        _close(
            route["mean_one_percent_low_fps"],
            sum(_number(run["one_percent_low_fps"], "1% low FPS") for run in runs) / 2.0,
            f"{role} mean low FPS",
        )
        _close(
            route["mean_gpu_p95_ms"],
            sum(_number(run["gpu_p95_ms"], "GPU p95") for run in runs) / 2.0,
            f"{role} mean GPU p95",
        )
        expected_decision = "PASS" if all(run_passes) else "FAIL"
        if route["decision"] != expected_decision:
            raise ContractError(f"{role} floor decision differs from measured runs")
        if expected_decision == "FAIL":
            failing_roles.add(role)
    if seen_roles != set(expected_floors) or failing_roles != {"overworld"}:
        raise ContractError(f"G0 failing route set differs: {sorted(failing_roles)}")


def verify(root: Path, manifest_path: Path) -> None:
    root = root.resolve()
    manifest_file = (root / manifest_path).resolve()
    manifest = _strict_object(manifest_file)
    _exact_keys(manifest, {
        "schema_version", "id", "coordinate_space", "authoring_contract",
        "baseline_world", "scenarios", "baseline_routes",
    }, "manifest")
    if manifest["schema_version"] != 1 or manifest["id"] != "gi-g0-fixtures-v1":
        raise ContractError("unsupported G0 fixture manifest identity")
    authoring = manifest["authoring_contract"]
    if not isinstance(authoring, dict):
        raise ContractError("authoring_contract must be an object")
    _exact_keys(authoring, {
        "kind", "deterministic", "random_commands_forbidden", "source_root",
    }, "authoring_contract")
    if authoring != {
        "kind": "tracked_mcfunction_sources",
        "deterministic": True,
        "random_commands_forbidden": True,
        "source_root": "benchmark/gi/fixtures/g0-v1",
    }:
        raise ContractError("G0 authoring contract is not deterministic and immutable")

    baseline_world = manifest["baseline_world"]
    if not isinstance(baseline_world, dict):
        raise ContractError("baseline_world must be an object")
    _exact_keys(baseline_world, {"fixture_id", "fixture_sha256"}, "baseline_world")
    fixture_sha = baseline_world["fixture_sha256"]
    if not isinstance(fixture_sha, str) or len(fixture_sha) != 64:
        raise ContractError("baseline fixture digest is not SHA-256")

    scenarios = manifest["scenarios"]
    if not isinstance(scenarios, list):
        raise ContractError("scenarios must be an array")
    ids = {scenario.get("id") for scenario in scenarios if isinstance(scenario, dict)}
    if ids != REQUIRED_SCENARIOS or len(scenarios) != len(REQUIRED_SCENARIOS):
        raise ContractError(f"G0 scenarios differ: {sorted(str(value) for value in ids)}")
    source_root = root / authoring["source_root"]
    for scenario in scenarios:
        if not isinstance(scenario, dict):
            raise ContractError("scenario must be an object")
        required = {"id", "dimension", "anchor", "source", "source_sha256", "invariant"}
        if scenario["id"] in {"scroll", "teleport"}:
            required.add("target")
        _exact_keys(scenario, required, f"scenario {scenario.get('id')}")
        source = source_root / scenario["source"]
        if source.parent.resolve() != source_root.resolve() or source.suffix != ".mcfunction":
            raise ContractError(f"unsafe fixture source path: {source}")
        if _sha256(source) != scenario["source_sha256"]:
            raise ContractError(f"fixture source digest mismatch: {source}")
        text = source.read_text(encoding="utf-8")
        if any(token in text.lower() for token in ("random", "spreadplayers", "summon", " loot ")):
            raise ContractError(f"non-deterministic fixture command in {source}")

    by_id = {scenario["id"]: scenario for scenario in scenarios}
    if by_id["chunk-boundary"]["anchor"][0] % 16 != 0:
        raise ContractError("chunk boundary anchor is not on a 16-block boundary")
    if by_id["cascade-boundary"]["anchor"][0] % 64 != 0:
        raise ContractError("cascade boundary anchor is not on the G0 test lattice")
    scroll = by_id["scroll"]
    if abs(scroll["target"][0] - scroll["anchor"][0]) != 64:
        raise ContractError("scroll fixture is not one test interval")
    teleport = by_id["teleport"]
    if abs(teleport["target"][0] - teleport["anchor"][0]) <= 256:
        raise ContractError("teleport target remains inside proposed outer coverage")

    routes = manifest["baseline_routes"]
    if not isinstance(routes, list) or {route.get("role") for route in routes} \
            != set(EXPECTED_ROUTE_DIMENSIONS):
        raise ContractError("baseline route roles differ from G0")
    for route_entry in routes:
        if not isinstance(route_entry, dict):
            raise ContractError("baseline route entry must be an object")
        _exact_keys(route_entry, {"role", "path", "route_sha256"}, "baseline route")
        route_path = root / route_entry["path"]
        if _sha256(route_path) != route_entry["route_sha256"]:
            raise ContractError(f"route digest mismatch: {route_path}")
        route = _strict_object(route_path)
        if route.get("dimension") != EXPECTED_ROUTE_DIMENSIONS[route_entry["role"]]:
            raise ContractError(f"route dimension mismatch: {route_path}")
        if route.get("fixture") != {
            "id": baseline_world["fixture_id"], "sha256": fixture_sha,
        }:
            raise ContractError(f"route fixture mismatch: {route_path}")
        if route_entry["role"] == "sealed_cave" and route.get("position") == [0.0, 32.0, 0.0]:
            raise ContractError("sealed cave aliases the historical Nether route")

    for relative in SETTINGS:
        settings = _strict_object(root / relative)
        if settings.get("schema_version") != 3:
            raise ContractError(f"{relative} does not bind renderer properties")
        renderer = settings.get("renderer_properties")
        if not isinstance(renderer, dict) or renderer.get("globalIllumination") != "off":
            raise ContractError(f"{relative} does not attest GI_OFF")

    fixture_path = root / "run/benchmark-fixtures" / baseline_world["fixture_id"] / "world"
    if fixture_path.is_dir():
        # The canonical helper's digest includes paths, modes and bytes. Import
        # lazily so this verifier remains dependency-free.
        import sys
        sys.path.insert(0, str(root / "tools"))
        from metal_benchmark_fixture import tree_digest  # type: ignore
        if tree_digest(fixture_path) != fixture_sha:
            raise ContractError("provisioned immutable baseline fixture digest changed")

    _verify_decision_artifact(root, manifest)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--manifest", type=Path, default=Path("benchmark/gi/g0-fixtures-v1.json")
    )
    args = parser.parse_args()
    try:
        verify(args.root, args.manifest)
    except ContractError as error:
        parser.error(str(error))
    print("GI G0 fixture/settings/decision contract passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
