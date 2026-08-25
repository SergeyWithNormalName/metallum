#!/usr/bin/env python3
"""Verify G2 accepted semantic truth, bounded storage, and structurally-off scope."""

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


def optional_artifact(root: Path, relative: str, expected_hash: str) -> Path | None:
    path = root / relative
    if not path.is_file():
        return None
    if sha256(path) != expected_hash:
        raise ContractError(f"G2 runtime artifact digest differs: {relative}")
    return path


def source(root: Path, relative: str) -> str:
    return (root / relative).read_text(encoding="utf-8")


def verify(root: Path) -> None:
    artifact = strict_object(root / "benchmark/gi/g2-semantic-evidence-v1.json")
    if artifact.get("schema_version") != 1 \
            or artifact.get("id") != "gi-g2-semantic-evidence-v1" \
            or artifact.get("stage") != "G2" \
            or artifact.get("decision") != "PASS_STRUCTURALLY_OFF_DIAGNOSTIC" \
            or artifact.get("g3_contract_gate") is not True \
            or artifact.get("g3_started") is not False:
        raise ContractError("G2 decision or next-stage boundary differs")

    implementation = artifact.get("implementation")
    if not isinstance(implementation, dict) \
            or not isinstance(implementation.get("commit"), str) \
            or len(implementation["commit"]) != 40:
        raise ContractError("G2 implementation commit is not full length")
    for key in ("runtime_source_sha256", "runtime_artifact_sha256"):
        if not isinstance(implementation.get(key), str) or len(implementation[key]) != 64:
            raise ContractError(f"G2 {key} is not SHA-256")

    expected_scope = {
        "mode": "accepted_semantic_field_diagnostic",
        "production_receiver_bindings": 0,
        "production_frame_graph_passes": 0,
        "production_gpu_contexts": 0,
        "transport_or_bounce": False,
        "source_or_direct_lighting_injection": False,
        "enabled_environment": "METALLUM_GI_G2_CAPTURE=1",
    }
    if artifact.get("scope") != expected_scope:
        raise ContractError("G2 structurally-off scope widened")

    source_chain = artifact.get("source_chain")
    if not isinstance(source_chain, dict) \
            or source_chain.get("minecraft") != "26.2" \
            or source_chain.get("sodium") != "0.9.1+mc26.2" \
            or source_chain.get("publication_boundary") != "RenderRegionManager.uploadResults_RETURN" \
            or source_chain.get("mutable_live_world_reads_after_publication") != 0 \
            or source_chain.get("camera_dependent_lightmap_inputs") != 0 \
            or source_chain.get("runtime_accepted_snapshots", 0) <= 0 \
            or source_chain.get("runtime_stale_snapshots") != 0 \
            or source_chain.get("runtime_capacity_rejects") != 0:
        raise ContractError("G2 accepted-output source-chain evidence differs")

    topology = artifact.get("topology")
    if not isinstance(topology, dict) \
            or topology.get("cascade_count") != 3 \
            or topology.get("cells_per_axis") != 32 \
            or topology.get("cell_sizes_blocks") != [2, 4, 8] \
            or topology.get("spans_blocks") != [64, 128, 256] \
            or topology.get("mip_levels") != 6 \
            or topology.get("storage_mode") != "Private" \
            or topology.get("planes") != [
                "RGBA16Unorm material", "RGBA16Float emission", "RGBA8Unorm faces0",
                "RG8Unorm faces1", "RGBA8Uint state", "R16Uint palette", "R8Unorm coverage",
            ]:
        raise ContractError("G2 fixed topology or seven-plane format contract differs")

    semantic = artifact.get("semantic_contract")
    if not isinstance(semantic, dict) \
            or semantic.get("palette_reserved_ids") != {"unknown": 0, "air": 1, "fallback": 2} \
            or semantic.get("palette_capacity") != 65_536 \
            or semantic.get("section_cells") != 584 \
            or semantic.get("candidate_payload_bytes") != 15_768 \
            or semantic.get("maximum_concurrent_candidates") != 64 \
            or semantic.get("maximum_resident_section_tags") != 4_913 \
            or semantic.get("synthetic_publications") != 10_000 \
            or semantic.get("steady_active_candidates_after_test") != 0 \
            or semantic.get("steady_resident_tags_after_test") != 1:
        raise ContractError("G2 palette, payload, or bounded-retirement contract differs")
    for key in (
            "unknown_is_black_opaque_zero_emission",
            "fallback_is_black_opaque_zero_emission",
            "emission_rgb_is_nonpremultiplied",
            "accepted_quad_overrides_seed_material_and_medium",
            "scroll_preserves_overlap_and_invalidates_exposed_slabs",
    ):
        if semantic.get(key) is not True:
            raise ContractError(f"G2 semantic invariant is not asserted: {key}")

    memory = artifact.get("memory")
    if not isinstance(memory, dict) \
            or memory.get("private_texture_bytes") != 4_128_768 \
            or memory.get("peak_upload_staging_bytes") != 5_505_024 \
            or memory.get("peak_capture_readback_bytes") != 32_768 \
            or memory.get("native_peak_bytes") != 9_666_560 \
            or memory.get("conservative_end_to_end_bytes") != 18_022_528 \
            or memory.get("diffuse_gi_budget_bytes") != 25_165_824 \
            or memory.get("within_budget") is not True \
            or memory["conservative_end_to_end_bytes"] > memory["diffuse_gi_budget_bytes"]:
        raise ContractError("G2 actual Metal/end-to-end memory census differs or exceeds budget")

    verification = artifact.get("mechanical_verification")
    if not isinstance(verification, dict) or any(
            not isinstance(value, str) or not value.startswith("PASS")
            for value in verification.values()
    ):
        raise ContractError("G2 mechanical verification contains a non-PASS result")

    runtime = artifact.get("runtime_screening")
    if not isinstance(runtime, dict) \
            or runtime.get("classification") != "TIER_B_PROVISIONAL_DIRTY_SOURCE_MATCH" \
            or runtime.get("warmup_frames") != 600 \
            or runtime.get("measure_frames") != 600 \
            or runtime.get("windows") != 2 \
            or runtime.get("thermal_state") != "nominal" \
            or runtime.get("dropped_timing_events") != 0 \
            or runtime.get("source_digest_match") is not True \
            or runtime.get("artifact_digest_match") is not True \
            or runtime.get("user_tolerance_fps") != 2.0 \
            or runtime.get("relative_gate") != "PASS_NO_MEASURED_REGRESSION" \
            or runtime.get("attestation") != "PROVISIONAL_DIRTY_WORKTREE_SCREENING_NOT_TIER_C":
        raise ContractError("G2 runtime screening classification differs")
    control = runtime["control"]
    candidate = runtime["candidate"]
    deltas = runtime["deltas"]
    expected_deltas = {
        "average_fps_percent": (candidate["average_fps"] / control["average_fps"] - 1.0) * 100.0,
        "one_percent_low_percent": (
            candidate["one_percent_low_fps"] / control["one_percent_low_fps"] - 1.0
        ) * 100.0,
        "gpu_p95_percent": (candidate["gpu_p95_ms"] / control["gpu_p95_ms"] - 1.0) * 100.0,
        "gpu_p95_ms": candidate["gpu_p95_ms"] - control["gpu_p95_ms"],
        "cpu_submission_p95_percent": (
            candidate["cpu_submission_p95_ms"] / control["cpu_submission_p95_ms"] - 1.0
        ) * 100.0,
    }
    for key, expected in expected_deltas.items():
        close(deltas.get(key), expected, key)
    if candidate["average_fps"] < control["average_fps"] - runtime["user_tolerance_fps"] \
            or deltas["gpu_p95_ms"] > 0.2:
        raise ContractError("G2 runtime screening exceeds its FPS or GPU p95 gate")

    for role, values in (("control", control), ("candidate", candidate)):
        optional_artifact(root, values["raw"], values["raw_sha256"])
        summary_path = optional_artifact(root, values["summary"], values["summary_sha256"])
        log_path = optional_artifact(root, values["minecraft_log"], values["minecraft_log_sha256"])
        if summary_path is not None:
            summary = strict_object(summary_path)
            close(summary["fps"]["elapsed_weighted"], values["average_fps"], f"{role} FPS")
            close(
                summary["fps_low_window_summaries"]["one_percent"]["window_frame_weighted_mean"],
                values["one_percent_low_fps"], f"{role} 1% low",
            )
            close(
                summary["presenting_command_buffer_gpu_ms"]["percentile_window_summaries"]["p95"]
                ["window_frame_weighted_mean"], values["gpu_p95_ms"], f"{role} GPU p95",
            )
            if summary["metadata"]["source_sha256"] != implementation["runtime_source_sha256"] \
                    or summary["metadata"]["artifact_sha256"] != implementation["runtime_artifact_sha256"]:
                raise ContractError(f"{role} runtime source/artifact identity differs")
        if role == "candidate" and log_path is not None:
            log = log_path.read_text(encoding="utf-8")
            if "[GI_G2] accepted-output semantic capture active" not in log \
                    or "[GI_G2] accepted=768 stale=0 outside=0 capacityRejected=0" not in log \
                    or "activeCandidates=0 peakCandidates=13 peakCandidateBytes=204984" not in log:
                raise ContractError("G2 live source-chain telemetry differs")

    telemetry = artifact.get("runtime_telemetry")
    if not isinstance(telemetry, dict) \
            or telemetry.get("accepted") != 768 \
            or telemetry.get("stale") != 0 \
            or telemetry.get("capacity_rejected") != 0 \
            or telemetry.get("active_candidates_at_shutdown") != 0 \
            or telemetry.get("peak_candidates") != 13 \
            or telemetry.get("peak_candidate_bytes") != 204_984 \
            or any(telemetry.get(key) != 0 for key in (
                "production_gi_resources", "production_gi_passes", "production_gi_bindings",
            )):
        raise ContractError("G2 runtime bounded/publication telemetry differs")

    plugin = source(root, "src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java")
    for token in (
            "METALLUM_GI_G2_CAPTURE", "MINECRAFT_EXACT_VERSION = \"26.2\"",
            "SODIUM_EXACT_VERSION = \"0.9.1+mc26.2\"", "GI_G2_CAPTURE_MIXINS.contains",
    ):
        if token not in plugin:
            raise ContractError(f"G2 structural/version gate is missing: {token}")

    capture_paths = [
        *sorted((root / "src/main/java/com/metallum/client/gi/capture").glob("*.java")),
        *sorted((root / "src/main/java/com/metallum/mixin/gi").glob("*.java")),
    ]
    capture_source = "\n".join(path.read_text(encoding="utf-8") for path in capture_paths)
    for forbidden in ("getBrightness(", "getRawBrightness(", "QuadLightData", "getLight("):
        if forbidden in capture_source:
            raise ContractError(f"G2 source chain reads a camera/pre-lit input: {forbidden}")
    candidate_source = source(root, "src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionCandidate.java")
    if "TextureAtlasSprite" in candidate_source or "NativeImage" in candidate_source:
        raise ContractError("G2 immutable candidate retains an atlas/image object")

    upload = source(root, "src/main/java/com/metallum/mixin/gi/GiSemanticUploadMixin.java")
    if "uploadResults" not in upload or "at = @At(\"RETURN\")" not in upload:
        raise ContractError("G2 publication moved away from accepted Sodium upload RETURN")
    field_wrapper = source(root, "src/main/java/com/metallum/client/gi/field/GiSemanticFieldGpuResources.java")
    if re.search(r"\b(?:bind|bind[A-Z]\w*|textureHandle)\s*\(", field_wrapper):
        raise ContractError("G2 wrapper exposes a production binding or texture handle")

    metal = source(root, "src/main/metal/MetallumGiField.metal")
    for token in (
            "kernel void metallum_gi_semantic_downsample_v1", "half4 parentMaterial = anyFallback",
            "half4(0.0h, 0.0h, 0.0h, 1.0h)", "half4 parentFaces0 = anyFallback",
            "emissionSupport * inverseKnown", "knownSupport * 0.125f",
    ):
        if token not in metal:
            raise ContractError(f"G2 conditional/fallback mip contract is missing: {token}")
    native = source(root, "src/main/native/MetallumNative.swift")
    for token in (
            "MetallumGiSemanticContextV1", ".rgba16Unorm", ".rgba16Float", ".rgba8Uint",
            ".r16Uint", "descriptor.storageMode = .private", "metallum_gi_semantic_capture_slice_once_v1",
    ):
        if token not in native:
            raise ContractError(f"G2 native private seven-plane contract is missing: {token}")

    for production_path in (
            "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java",
            "src/main/java/com/metallum/client/lighting/shader/AdvancedDirectLightingShaderPatcher.java",
    ):
        production = source(root, production_path)
        if "GiSemantic" in production or "metallum_gi_semantic" in production:
            raise ContractError(f"G2 leaked into production rendering: {production_path}")

    print("GI G2 semantic/evidence/structural-off contract passed")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    try:
        verify(arguments.root.resolve())
    except (ContractError, OSError, json.JSONDecodeError, KeyError, TypeError, ZeroDivisionError) as error:
        raise SystemExit(f"GI G2 contract FAILED: {error}") from error


if __name__ == "__main__":
    main()
