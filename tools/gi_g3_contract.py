#!/usr/bin/env python3
"""Verify the bounded, field-only G3 direct-source implementation and receipt."""

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


def source(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8")


def close(actual: Any, expected: Any, label: str) -> None:
    if isinstance(actual, bool) or not isinstance(actual, (int, float)) \
            or not math.isclose(float(actual), float(expected), rel_tol=0.0, abs_tol=1e-9):
        raise ContractError(f"{label} differs: {actual!r} != {expected!r}")


def optional_artifact(root: Path, relative: str, expected_hash: str) -> Path | None:
    path = root / relative
    if not path.is_file():
        return None
    if sha256(path) != expected_hash:
        raise ContractError(f"G3 runtime artifact digest differs: {relative}")
    return path


def strip_c_comments(value: str) -> str:
    value = re.sub(r"/\*.*?\*/", "", value, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", value)


def verify_runtime(root: Path, evidence: dict[str, Any]) -> None:
    tier = evidence["tier_b"]
    raw_path = optional_artifact(root, tier["raw"], tier["raw_sha256"])
    summary_path = optional_artifact(root, tier["summary"], tier["summary_sha256"])
    optional_artifact(root, tier["console_log"], tier["console_log_sha256"])
    if raw_path is not None:
        payloads = [
            json.loads(line) for line in raw_path.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]
        measure = [value for value in payloads if value["benchmark"]["phase"] == "measure"]
        if len(measure) != 2:
            raise ContractError("G3 Tier B raw report must contain two measured windows")
        first, second = measure
        first_gi = first["global_illumination"]
        second_gi = second["global_illumination"]
        for value in (first_gi, second_gi):
            if value.get("contract_version") != 2 or value.get("mode") != "active" \
                    or value.get("full_volume_rebuilds") != 1 \
                    or value.get("dirty_queued_total") != 192 \
                    or value.get("dirty_completed_total") != 192 \
                    or value.get("dirty_discarded_total") != 0 \
                    or value.get("dirty_pending") != 0 \
                    or value.get("resource_count") != 6 \
                    or value.get("pass_count") != 2 \
                    or value.get("binding_count") != 0 \
                    or value.get("transport_dispatches") != 0:
                raise ContractError("G3 measured scheduler/resource telemetry differs")
        stable_keys = (
            "full_volume_rebuilds", "dirty_queued_total", "dirty_completed_total",
            "dirty_discarded_total", "injection_dispatches", "source_epoch", "probe_epoch",
        )
        if any(first_gi[key] != second_gi[key] for key in stable_keys):
            raise ContractError("G3 steady-state counters changed in the second measured window")
        attributed = [
            value for value in payloads if value["stages"].get("GI_INJECT") is not None
        ]
        if len(attributed) != 1:
            raise ContractError("G3 raw report must contain one attributed population window")
        active_payload = attributed[0]
        active_stage = active_payload["stages"]["GI_INJECT"]
        if not isinstance(active_stage, dict) \
                or active_stage.get("frames") != tier["gi_inject_active_frames"]:
            raise ContractError("G3 bounded population stage attribution differs")
        close(active_stage["average_ms"], tier["gi_inject_average_ms"], "GI_INJECT average")
        close(active_stage["p95_ms"], tier["gi_inject_p95_ms"], "GI_INJECT p95")
        if first["stages"].get("GI_INJECT") is not None \
                or second["stages"].get("GI_INJECT") is not None:
            raise ContractError("G3 measured steady-state unexpectedly encoded GI_INJECT work")
        active_index = payloads.index(active_payload)
        previous = payloads[active_index - 1]["global_illumination"]
        active_gi = active_payload["global_illumination"]
        populated = active_gi["dirty_completed_total"] - previous["dirty_completed_total"]
        if populated != active_stage["frames"] * tier["maximum_observed_bricks_per_active_frame"]:
            raise ContractError("G3 active work does not match its declared dirty-brick cap")

    if summary_path is not None:
        summary = strict_object(summary_path)
        close(summary["fps"]["elapsed_weighted"], tier["average_fps"], "G3 FPS")
        close(
            summary["fps_low_window_summaries"]["one_percent"]
            ["window_frame_weighted_mean"], tier["one_percent_low_fps"], "G3 1% low",
        )
        close(
            summary["presenting_command_buffer_gpu_ms"]["percentile_window_summaries"]
            ["p95"]["window_frame_weighted_mean"], tier["gpu_p95_ms"], "G3 GPU p95",
        )
        if summary["metadata"]["source_sha256"] != evidence["implementation"]["runtime_source_sha256"] \
                or summary["metadata"]["artifact_sha256"] \
                != evidence["implementation"]["runtime_artifact_sha256"]:
            raise ContractError("G3 runtime source/artifact identity differs")
        counters = summary["global_illumination"]["counters"]
        if counters["full_volume_rebuilds"]["window_minimum"] != 1 \
                or counters["full_volume_rebuilds"]["window_maximum"] != 1 \
                or counters["dirty_discarded_total"]["window_maximum"] != 0 \
                or counters["dirty_pending"]["window_maximum"] != 0:
            raise ContractError("G3 summary does not prove a stable drained scheduler")


def verify_source(root: Path) -> None:
    layout = source(root, "src/main/java/com/metallum/client/gi/source/GiDirectSourceLayout.java")
    queue = source(root, "src/main/java/com/metallum/client/gi/source/GiDirectDirtyQueue.java")
    coordinator = source(root, "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java")
    resources = source(root, "src/main/java/com/metallum/client/gi/source/GiDirectSourceGpuResources.java")
    registry = source(root, "src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java")
    frame_graph = source(root, "src/main/java/com/metallum/client/metal/render/framegraph/GiDirectSourceFrameGraph.java")
    command_encoder = source(root, "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java")
    native = source(root, "src/main/native/MetallumNative.swift")
    metal = source(root, "src/main/metal/MetallumGiField.metal")

    for token in (
            "CASCADE_COUNT = GiFieldLayout.CASCADE_COUNT",
            "CELLS_PER_AXIS = GiFieldLayout.CELLS_PER_AXIS", "BRICK_EDGE_CELLS = 8",
            "MAX_DRAIN_PER_FRAME = 16", "MAX_STATIC_SOURCES_PER_BRICK = 16",
    ):
        if token not in layout:
            raise ContractError(f"G3 fixed topology token is missing: {token}")
    for token in (
            "enqueueAll", "fullVolumeRebuilds", "MAX_DRAIN_PER_FRAME",
            "completeBatch", "retryBatch",
    ):
        if token not in queue:
            raise ContractError(f"G3 bounded scheduler token is missing: {token}")
    for token in (
            "STATIC_SOURCE_SETTLE_TICKS = 16L", "staticSourceStateForGi",
            "enqueueChangedBricks", "publishScheduler", "submittedBrickStamps",
    ):
        if token not in coordinator:
            raise ContractError(f"G3 coordinator contract is missing: {token}")
    for token in (
            "private long staticEpoch = 1L", "world.staticEpoch++",
            "new GiStaticSourceState(this.activeWorld.token, this.activeWorld.staticEpoch)",
    ):
        if token not in registry:
            raise ContractError(f"G3 camera-independent static epoch is missing: {token}")
    for token in (
            "RGBA16_FLOAT = 115", "R8_UINT = 13", "HEADER_BYTES = 160",
            "STATS_BYTES = 168", "JAVA_PERSISTENT_PACKET_BYTES", "captureSliceOnce",
            "deferredRelease.accept",
    ):
        if token not in resources:
            raise ContractError(f"G3 Java/native resource contract is missing: {token}")
    if "Arena.allocate" in coordinator or "makeBuffer" in coordinator:
        raise ContractError("G3 render-loop coordinator allocates a native packet or Metal buffer")

    if "resource_count" not in native \
            or '"contract_version": transport == nil ? 2 : 3' not in native \
            or '"full_volume_rebuilds": fullVolumeRebuilds' not in native \
            or "attachGpuTiming(pass, commandBuffer: commandBuffer, stage: .giInject)" not in native:
        raise ContractError("G3 active telemetry or GI_INJECT attribution is missing")
    for token in (
            ".rgba16Float", ".r8Uint", "descriptor.storageMode = .private",
            "MetallumGiDirectSourceContextV1", "maxDirtyBricks = 16",
            "memoryBarrier(scope: .textures)",
            "metallum_gi_direct_source_publish_scheduler_v1",
    ):
        if token not in native:
            raise ContractError(f"G3 native private-field contract is missing: {token}")

    direct_metal = metal[metal.index("// G3 direct-source ABI") :]
    code_only = strip_c_comments(direct_metal).lower()
    for forbidden in ("albedo", "reflectance", "rho", "bounce", "lightmap", "camera"):
        if forbidden in code_only:
            raise ContractError(f"G3 Metal code reads a later/view-stage concept: {forbidden}")
    for token in (
            "metallum_gi_direct_geometry_apply_v1", "metallum_gi_direct_inject_v1",
            "metallumGiDirectDdaSteps = 32u", "memoryBarrier",
    ):
        haystack = direct_metal if token != "memoryBarrier" else native
        if token not in haystack:
            raise ContractError(f"G3 geometry/injection ordering is missing: {token}")

    if "GRAPH_ID = \"gi-g3-direct-source-field-v1\"" not in frame_graph \
            or "gi_geometry_apply" not in frame_graph or "gi_source_inject" not in frame_graph \
            or "ResourceRole.GENERIC" not in frame_graph:
        raise ContractError("G3 field-only frame graph differs")
    for forbidden in (
            "ResourceRole.SCENE_COLOR", "ResourceRole.PRESENT",
            "PipelineStage.FRAGMENT", "EncoderClass.RENDER",
    ):
        if forbidden in frame_graph:
            raise ContractError(f"G3 frame graph exposes an image consumer: {forbidden}")
    if "encodeGiDirectSource" not in command_encoder:
        raise ContractError("G3 is not encoded into the existing Advanced command buffer")


def verify(root: Path) -> None:
    evidence = strict_object(root / "benchmark/gi/g3-direct-source-evidence-v1.json")
    if evidence.get("schema_version") != 1 \
            or evidence.get("id") != "gi-g3-direct-source-evidence-v1" \
            or evidence.get("stage") != "G3" \
            or evidence.get("status") != "G3_COMPLETE_FIELD_ONLY" \
            or evidence.get("decision") != "PASS_FIELD_ONLY_BOUNDED_DIRECT_SOURCE" \
            or evidence.get("g4_contract_gate") is not True \
            or evidence.get("g4_started") is not False:
        raise ContractError("G3 decision or next-stage boundary differs")
    scope = evidence.get("scope")
    if not isinstance(scope, dict) or scope.get("field_only") is not True \
            or scope.get("production_default_enabled") is not False \
            or any(scope.get(key) != 0 for key in (
                "terrain_or_image_bindings", "receiver_passes", "transport_dispatches",
                "bounce_count",
            )) \
            or scope.get("reads_albedo_or_reflectance") is not False \
            or scope.get("reads_camera_or_lightmap") is not False:
        raise ContractError("G3 field-only scope widened")
    topology = evidence.get("topology")
    expected_topology = {
        "cascade_count": 3, "cells_per_axis": 32, "cell_sizes_blocks": [2, 4, 8],
        "brick_edge_cells": 8, "logical_brick_count": 192,
        "maximum_dirty_bricks_per_frame": 8, "maximum_dda_steps": 32,
        "direct_format": "RGBA16Float", "geometry_format": "R8Uint",
        "storage_mode": "Private", "resource_count": 6, "compute_pass_count": 2,
        "staging_ring_slots": 3, "static_source_settle_ticks": 16,
    }
    if topology != expected_topology:
        raise ContractError("G3 fixed topology differs")
    memory = evidence.get("memory")
    if not isinstance(memory, dict) \
            or memory.get("accounted_bytes") != 1_104_096 \
            or memory.get("persistent_private_bytes") != 884_736 \
            or memory.get("staging_ring_bytes") != 210_144 \
            or memory.get("diagnostic_readback_bytes") != 9_216 \
            or memory.get("diffuse_gi_budget_bytes") != 25_165_824 \
            or memory.get("within_budget") is not True \
            or memory["accounted_bytes"] > memory["diffuse_gi_budget_bytes"]:
        raise ContractError("G3 measured memory differs or exceeds the GI budget")
    tier = evidence.get("tier_b")
    if not isinstance(tier, dict) \
            or tier.get("classification") != "TIER_B_BOUNDED_DIRTY_WORK_ATTRIBUTION_NOT_TIER_C" \
            or tier.get("warmup_frames") != 600 or tier.get("measure_frames") != 600 \
            or tier.get("windows") != 2 or tier.get("dropped_timing_events") != 0 \
            or tier.get("dirty_queued") != 192 or tier.get("dirty_completed") != 192 \
            or tier.get("dirty_discarded") != 0 or tier.get("dirty_pending") != 0 \
            or tier.get("full_field_initializations") != 1 \
            or tier.get("steady_state_full_volume_rebuild_delta") != 0 \
            or tier.get("maximum_observed_bricks_per_active_frame") != 8 \
            or tier.get("binding_count") != 0 or tier.get("transport_dispatches") != 0:
        raise ContractError("G3 Tier B bounded-work gate differs")
    if tier["gi_inject_p95_ms"] > 0.2:
        raise ContractError("G3 GI_INJECT p95 exceeds its 0.2 ms screening stop-gate")
    negative = evidence.get("retained_negative_evidence")
    if not isinstance(negative, list) or [item.get("classification") for item in negative] != [
            "REJECTED_DYNAMIC_EPOCH_CHURN", "REJECTED_STARTUP_SOURCE_PUBLICATION_CHURN",
    ]:
        raise ContractError("G3 negative scheduler evidence was removed")
    verification = evidence.get("mechanical_verification")
    if not isinstance(verification, dict) or any(
            not isinstance(value, str) or not value.startswith("PASS")
            for value in verification.values()
    ):
        raise ContractError("G3 mechanical verification contains a non-PASS result")
    verify_source(root)
    verify_runtime(root, evidence)
    print("GI G3 current source contract and retained historical B8 receipt passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    try:
        verify(arguments.root.resolve())
    except (ContractError, OSError, json.JSONDecodeError, KeyError, TypeError) as error:
        raise SystemExit(f"GI G3 contract FAILED: {error}") from error


if __name__ == "__main__":
    main()
