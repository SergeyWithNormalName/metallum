#!/usr/bin/env python3
"""Verify the isolated G1 field evidence, topology, and no-production-binding contract."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from typing import Any


class ContractError(ValueError):
    pass


def strict_object(path: Path) -> dict[str, Any]:
    def pairs(values: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in values:
            if key in result:
                raise ContractError(f"duplicate JSON key {key!r} in {path}")
            result[key] = value
        return result

    value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=pairs)
    if not isinstance(value, dict):
        raise ContractError(f"{path} must contain an object")
    return value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def close(actual: Any, expected: Any, label: str) -> None:
    if isinstance(actual, bool) or isinstance(expected, bool) \
            or not isinstance(actual, (int, float)) or not isinstance(expected, (int, float)) \
            or not math.isclose(float(actual), float(expected), rel_tol=0.0, abs_tol=1e-9):
        raise ContractError(f"{label} differs: {actual!r} != {expected!r}")


def optional_receipt(root: Path, relative: str, expected_hash: str) -> dict[str, Any] | None:
    path = root / relative
    if not path.is_file():
        return None
    if sha256(path) != expected_hash:
        raise ContractError(f"G1 evidence digest differs: {relative}")
    return strict_object(path)


def verify(root: Path) -> None:
    artifact = strict_object(root / "benchmark/gi/g1-field-evidence-v1.json")
    if artifact.get("schema_version") != 1 \
            or artifact.get("id") != "gi-g1-field-evidence-v1" \
            or artifact.get("stage") != "G1" \
            or artifact.get("decision") != "SUPPORTED_PENDING_ABSOLUTE_FLOOR" \
            or artifact.get("g2_allowed") is not False:
        raise ContractError("G1 evidence decision must preserve the outstanding absolute floor")

    implementation = artifact.get("implementation")
    if not isinstance(implementation, dict) \
            or not isinstance(implementation.get("commit"), str) \
            or len(implementation["commit"]) != 40:
        raise ContractError("G1 implementation must identify a full commit")
    for key in ("source_sha256", "artifact_sha256"):
        if not isinstance(implementation.get(key), str) or len(implementation[key]) != 64:
            raise ContractError(f"G1 implementation {key} is not SHA-256")

    if artifact.get("scope") != {
        "mode": "field_infrastructure_only",
        "production_receiver_bindings": 0,
        "production_frame_graph_passes": 0,
        "production_runtime_allocations": 0,
        "material_or_emission_semantics": False,
        "transport_or_bounce": False,
    }:
        raise ContractError("G1 field-only scope widened")
    if artifact.get("topology") != {
        "cascade_count": 3,
        "cells_per_axis": 32,
        "cell_sizes_blocks": [2, 4, 8],
        "spans_blocks": [64, 128, 256],
        "mip_levels": 6,
        "field_format": "RGBA16Float",
        "coverage_format": "R8Unorm",
        "storage_mode": "Private",
    }:
        raise ContractError("G1 fixed topology or private texture contract differs")

    memory = artifact.get("memory")
    if not isinstance(memory, dict) \
            or memory.get("arithmetic_bytes") != 1_011_123 \
            or memory.get("actual_metal_allocated_bytes") != 1_277_952 \
            or memory.get("diffuse_gi_budget_bytes") != 25_165_824 \
            or memory.get("within_budget") is not True \
            or not (memory["arithmetic_bytes"] <= memory["actual_metal_allocated_bytes"]
                    <= memory["diffuse_gi_budget_bytes"]):
        raise ContractError("G1 actual Metal memory census differs or exceeds budget")

    verification = artifact.get("mechanical_verification")
    if not isinstance(verification, dict) or any(
            not isinstance(value, str) or not value.startswith("PASS")
            for value in verification.values()
    ):
        raise ContractError("G1 mechanical verification contains a non-PASS result")

    runtime = artifact.get("contemporaneous_runtime_ab")
    if not isinstance(runtime, dict) \
            or runtime.get("classification") != "TIER_B_SCREENING" \
            or runtime.get("relative_gate") != "PASS" \
            or runtime.get("absolute_28_fps_gate") != "FAIL_BOTH_CONTROL_AND_CANDIDATE":
        raise ContractError("G1 runtime A/B classification differs")
    expected_deltas = {
        "fps_delta_percent": (runtime["candidate_average_fps"] / runtime["control_average_fps"] - 1.0) * 100.0,
        "one_percent_low_delta_percent": (
            runtime["candidate_one_percent_low_fps"] / runtime["control_one_percent_low_fps"] - 1.0
        ) * 100.0,
        "gpu_p95_delta_percent": (
            runtime["candidate_gpu_p95_ms"] / runtime["control_gpu_p95_ms"] - 1.0
        ) * 100.0,
    }
    for key, expected in expected_deltas.items():
        close(runtime.get(key), expected, key)
    if runtime["fps_delta_percent"] < -8.0 \
            or runtime["one_percent_low_delta_percent"] < -10.0 \
            or runtime["gpu_p95_delta_percent"] > 8.0:
        raise ContractError("G1 relative runtime gate does not pass")
    for role in ("control", "candidate"):
        summary = optional_receipt(
            root, runtime[f"{role}_summary"], runtime[f"{role}_summary_sha256"]
        )
        if summary is not None:
            close(summary["fps"]["elapsed_weighted"], runtime[f"{role}_average_fps"], f"{role} FPS")
            close(
                summary["fps_low_window_summaries"]["one_percent"]["window_frame_weighted_mean"],
                runtime[f"{role}_one_percent_low_fps"], f"{role} 1% low",
            )
            close(
                summary["presenting_command_buffer_gpu_ms"]["percentile_window_summaries"]["p95"]["window_frame_weighted_mean"],
                runtime[f"{role}_gpu_p95_ms"], f"{role} GPU p95",
            )

    tier_c = artifact.get("tier_c_overworld")
    if not isinstance(tier_c, dict) \
            or tier_c.get("classification") != "VALID_ATTESTATION_PRODUCT_FLOOR_FAIL" \
            or tier_c.get("presented_frames") != 3000 \
            or tier_c.get("windows") != 10 \
            or tier_c.get("one_percent_low_fps", 0.0) < 20.0 \
            or tier_c.get("average_fps", 28.0) >= 28.0 \
            or tier_c.get("floor_decision") != "FAIL_AVERAGE_ONLY" \
            or tier_c.get("advanced_admission") != "PASS" \
            or tier_c.get("gi_off_admission") != "PASS" \
            or tier_c.get("dropped_timing_events") != 0:
        raise ContractError("G1 Tier C floor failure is hidden or inconsistent")
    summary = optional_receipt(root, tier_c["summary"], tier_c["summary_sha256"])
    optional_receipt(root, tier_c["receipt"], tier_c["receipt_sha256"])
    if summary is not None:
        if summary.get("presented_frames") != 3000 or summary.get("window_count") != 10:
            raise ContractError("G1 Tier C summary is not 3000 frames / 10 windows")
        close(summary["fps"]["elapsed_weighted"], tier_c["average_fps"], "Tier C FPS")
        close(
            summary["fps_low_window_summaries"]["one_percent"]["window_frame_weighted_mean"],
            tier_c["one_percent_low_fps"], "Tier C 1% low",
        )

    gi_root = root / "src/main/java/com/metallum/client/gi/field"
    gi_source = "\n".join(path.read_text(encoding="utf-8") for path in sorted(gi_root.glob("*.java")))
    for forbidden in (
        "RadianceAppearanceModel", "SodiumRadianceSectionExtractor",
        "DirectionalProbe", "bindVertex", "bindFragment", "textureHandle",
    ):
        if forbidden in gi_source:
            raise ContractError(f"G1 field source contains rejected path: {forbidden}")
    wrapper = (gi_root / "GiFieldGpuResources.java").read_text(encoding="utf-8")
    if re.search(r"\b(?:bind|bind[A-Z]\w*)\s*\(", wrapper):
        raise ContractError("G1 wrapper exposes a production bind method")
    metal = (root / "src/main/metal/MetallumGiField.metal").read_text(encoding="utf-8")
    if "kernel void metallum_gi_field_downsample_v1" not in metal:
        raise ContractError("G1 coverage-aware Metal kernel is missing")
    for production_path in (
        "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java",
        "src/main/java/com/metallum/client/lighting/shader/AdvancedDirectLightingShaderPatcher.java",
    ):
        if "metallum_gi_field" in (root / production_path).read_text(encoding="utf-8"):
            raise ContractError(f"G1 field leaked into production path: {production_path}")

    print("GI G1 field/evidence/no-binding contract passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    try:
        verify(arguments.root.resolve())
    except (ContractError, OSError, json.JSONDecodeError, KeyError, TypeError, ZeroDivisionError) as error:
        raise SystemExit(f"GI G1 contract FAILED: {error}") from error


if __name__ == "__main__":
    main()
