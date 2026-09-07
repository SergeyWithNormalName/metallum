#!/usr/bin/env python3
"""Fail closed on the block-scale C0 topology successor without rewriting G0--G6 evidence.

The completed G1--G6 receipts describe the historical [2, 4, 8] metre grid and
its old form-factor quadrature.  This verifier deliberately does *not* relabel
those receipts as evidence for the current [1, 4, 8] successor.  It only checks
the runtime topology/numerics and records that fresh live/matrix/image evidence
is still required before the successor can be accepted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
from pathlib import Path
from typing import Any


MANIFEST = Path("benchmark/gi/g6-topology-successor-contract-v1.json")


class ContractError(RuntimeError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def strict_object(path: Path) -> dict[str, Any]:
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
    require(isinstance(value, dict), f"{path} must contain an object")
    return value


def source(root: Path, relative: str) -> str:
    path = root / relative
    try:
        return path.read_text(encoding="utf-8")
    except OSError as error:
        raise ContractError(f"cannot read {relative}: {error}") from error


def exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    require(isinstance(value, dict), f"{label} must be an object")
    actual = set(value)
    require(actual == expected,
            f"{label} keys differ; missing={sorted(expected - actual)}, extra={sorted(actual - expected)}")
    return value


def number(value: Any, label: str) -> float:
    require(not isinstance(value, bool) and isinstance(value, (int, float)),
            f"{label} must be numeric")
    result = float(value)
    require(math.isfinite(result), f"{label} must be finite")
    return result


def close(actual: Any, expected: float, label: str) -> None:
    require(math.isclose(number(actual, label), expected, rel_tol=0.0, abs_tol=1.0e-12),
            f"{label} differs: {actual!r} != {expected!r}")


def require_tokens(value: str, tokens: tuple[str, ...], label: str) -> None:
    for token in tokens:
        require(token in value, f"{label} token is missing: {token!r}")


def verify_manifest(root: Path) -> dict[str, Any]:
    manifest = exact_keys(strict_object(root / MANIFEST), {
        "schema_version", "id", "stage", "status", "decision",
        "historical_evidence_sha256", "runtime_source_sha256", "topology", "transport",
        "evidence_boundary",
    }, "topology successor manifest")
    require((manifest["schema_version"], manifest["id"], manifest["stage"]) == (
        1, "gi-g6-topology-successor-contract-v1", "G6_TOPOLOGY_SUCCESSOR",
    ), "topology successor identity differs")
    require(manifest["status"] == "IMPLEMENTED_PENDING_RUNTIME_EVIDENCE"
            and manifest["decision"] == "HISTORICAL_RECEIPTS_RETAINED_NEW_TOPOLOGY_UNATTESTED",
            "topology successor falsely claims acceptance")

    historical = exact_keys(manifest["historical_evidence_sha256"], {
        "benchmark/gi/g0-acceptance-v1.json",
        "benchmark/gi/g1-field-evidence-v1.json",
        "benchmark/gi/g2-semantic-evidence-v1.json",
        "benchmark/gi/g3-direct-source-evidence-v1.json",
        "benchmark/gi/g4-transport-evidence-v1.json",
        "benchmark/gi/g6-live-evidence-v1.json",
    }, "topology successor historical evidence")
    for relative, digest in historical.items():
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
                f"historical digest is invalid: {relative}")
        require(sha256(root / relative) == digest,
                f"historical evidence was rewritten: {relative}")

    runtime_sources = exact_keys(manifest["runtime_source_sha256"], {
        "src/main/java/com/metallum/client/gi/field/GiFieldCandidateBudget.java",
        "src/main/java/com/metallum/client/gi/capture/GiSemanticCaptureScope.java",
        "src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionBuilder.java",
        "src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionReservation.java",
        "src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionSnapshot.java",
        "src/test/java/com/metallum/client/gi/field/GiFieldTests.java",
        "src/test/java/com/metallum/client/gi/field/GiSemanticGpuEncoderTests.java",
        "src/test/java/com/metallum/client/gi/semantic/GiSemanticCpuTests.java",
        "src/test/java/com/metallum/client/gi/source/GiDynamicSourceInjectionTests.java",
    }, "topology successor current runtime sources")
    for relative, digest in runtime_sources.items():
        require(isinstance(digest, str) and re.fullmatch(r"[0-9a-f]{64}", digest) is not None,
                f"runtime source digest is invalid: {relative}")
        require(sha256(root / relative) == digest,
                f"topology successor runtime source drifted: {relative}")

    topology = exact_keys(manifest["topology"], {
        "cascade_count", "cells_per_axis", "cell_sizes_blocks", "origin_snap_blocks",
        "near_span_blocks",
    }, "topology successor topology")
    require(topology == {
        "cascade_count": 3, "cells_per_axis": 32, "cell_sizes_blocks": [1, 4, 8],
        "origin_snap_blocks": [2, 4, 8], "near_span_blocks": 32,
    }, "topology successor is not the declared block-scale C0 grid")

    transport = exact_keys(manifest["transport"], {
        "maximum_distance_cells", "form_quadrature", "axis_bin_solid_angle",
        "edge_bin_solid_angle", "corner_bin_solid_angle", "form_weight_normalization",
        "confidence_weight_normalization",
    }, "topology successor transport")
    require(transport["maximum_distance_cells"] == 8
            and transport["form_quadrature"] == "projected_solid_angle_l1_half_cosine",
            "topology successor transport shape differs")
    for key, expected in (
        ("axis_bin_solid_angle", 0.8054316831613232),
        ("edge_bin_solid_angle", 0.48157053442524556),
        ("corner_bin_solid_angle", 0.24436676278603597),
        ("form_weight_normalization", 5.463719420620535),
        ("confidence_weight_normalization", 29.17999846648958),
    ):
        close(transport[key], expected, f"topology successor transport.{key}")

    boundary = exact_keys(manifest["evidence_boundary"], {
        "historical_receipts_reused_as_current", "current_topology_has_live_matrix_receipt",
        "required_before_acceptance",
    }, "topology successor evidence boundary")
    require(boundary == {
        "historical_receipts_reused_as_current": False,
        "current_topology_has_live_matrix_receipt": False,
        "required_before_acceptance": [
            "source_and_bundled_metal_validation", "fresh_g6_live_receipt",
            "fresh_g6_matrix_receipt", "fresh_static_visual_pair",
        ],
    }, "topology successor evidence boundary was widened or hidden")
    return manifest


def verify_runtime_sources(root: Path, manifest: dict[str, Any]) -> None:
    layout = source(root, "src/main/java/com/metallum/client/gi/field/GiFieldLayout.java")
    direct_layout = source(root, "src/main/java/com/metallum/client/gi/source/GiDirectSourceLayout.java")
    transport = source(root, "src/main/java/com/metallum/client/gi/transport/GiTransportLayout.java")
    live_layout = source(root, "src/main/java/com/metallum/client/gi/live/GiLiveLayout.java")
    live_resources = source(root, "src/main/java/com/metallum/client/gi/live/GiLiveGpuResources.java")
    candidate_budget = source(root, "src/main/java/com/metallum/client/gi/field/GiFieldCandidateBudget.java")
    semantic_capture = source(root, "src/main/java/com/metallum/client/gi/capture/GiSemanticCaptureScope.java")
    semantic_builder = source(root, "src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionBuilder.java")
    semantic_reservation = source(root, "src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionReservation.java")
    semantic_snapshot = source(root, "src/main/java/com/metallum/client/gi/semantic/GiSemanticSectionSnapshot.java")
    metal = source(root, "src/main/metal/MetallumGiTransport.metal")
    native = source(root, "src/main/native/MetallumNative.swift")
    field_tests = source(root, "src/test/java/com/metallum/client/gi/field/GiFieldTests.java")
    transport_tests = source(root, "src/test/java/com/metallum/client/gi/transport/GiTransportCpuTests.java")
    semantic_tests = source(root, "src/test/java/com/metallum/client/gi/semantic/GiSemanticCpuTests.java")

    require_tokens(layout, (
        "CELL_SIZES_BLOCKS = {1, 4, 8}", "ORIGIN_SNAP_BLOCKS = {2, 4, 8}",
        "return Math.multiplyExact(CELLS_PER_AXIS, cellSizeBlocks(cascade))",
        "if (snap % cellSize != 0)",
    ), "block-scale C0 Java layout")
    require_tokens(direct_layout, (
        "return GiFieldLayout.cellSizeBlocks(cascade);",
    ), "G3 topology delegation")

    constants = manifest["transport"]
    require_tokens(transport, (
        "CELL_SIZE_BLOCKS = GiFieldLayout.cellSizeBlocks(0)",
        "MAXIMUM_DISTANCE = 8", "AXIS_BIN_SOLID_ANGLE = 0.8054316831613232",
        "EDGE_BIN_SOLID_ANGLE = 0.48157053442524556",
        "CORNER_BIN_SOLID_ANGLE = 0.24436676278603597",
        "FORM_WEIGHT_NORMALIZATION = 5.463719420620535",
        "CONFIDENCE_WEIGHT_NORMALIZATION = 29.17999846648958",
        "computedFormWeightNormalization", "computedConfidenceWeightNormalization",
    ), "G4 successor transport layout")
    expected = 2.0 * (
        constants["axis_bin_solid_angle"]
        + 4.0 * constants["edge_bin_solid_angle"] / math.sqrt(2.0)
        + 4.0 * constants["corner_bin_solid_angle"] / math.sqrt(3.0)
    )
    close(constants["form_weight_normalization"], expected,
          "projected-solid-angle normalization")
    require_tokens(live_layout, (
        "(float) GiTransportLayout.FORM_WEIGHT_NORMALIZATION",
    ), "G6 successor transport layout")
    require_tokens(live_resources, (
        "GiLiveLayout.HEADER_FORM_WEIGHT_NORMALIZATION_OFFSET",
        "GiLiveLayout.FORM_WEIGHT_NORMALIZATION",
    ), "G6 successor transport upload")

    require_tokens(metal, (
        "metallumGiTransportAxisBinSolidAngle = 0.8054316831613232f",
        "metallumGiTransportEdgeBinSolidAngle = 0.48157053442524556f",
        "metallumGiTransportCornerBinSolidAngle = 0.24436676278603597f",
        "metallum_gi_transport_direction_solid_angle", "formWeight = formDirectionWeight",
        "confidenceWeight = confidenceDirectionWeight",
        "metallumGiTransportPi * formWeight * sourceSupport",
    ), "shared frozen/live Metal form-factor")
    require(metal.count("metallumGiTransportPi * formWeight * sourceSupport") == 2,
            "frozen and live transport do not share the projected form factor")

    require(native.count("cellSizes = [1, 4, 8]") >= 2
            and "cellSizes = [Int32(1), Int32(4), Int32(8)]" in native,
            "Swift semantic/direct/live grids do not agree on [1,4,8]")
    require(native.count("formWeightNormalization = Float(5.463719420620535)") >= 2,
            "Swift frozen/live contexts do not agree on projected normalization")
    require(native.count("let cellSize = Self.cellSizes[cascade]") >= 2
            and "1 << (cascade + 1)" not in native,
            "Swift live remap/receiver scale still assumes historical [2,4,8]")

    require_tokens(field_tests, (
        "int[] expectedCellSizes = {1, 4, 8}",
        "int[] expectedSpans = {32, 128, 256}",
    ), "block-scale C0 unit coverage")
    require_tokens(candidate_budget, (
        "DEFAULT_MAX_CANDIDATES = 64", "DEFAULT_MAX_BYTES = 8L * 1024L * 1024L",
        "bytes > this.maxBytes - this.activeBytes",
    ), "block-scale candidate budget")
    require_tokens(semantic_snapshot, (
        "CASCADE_CELL_EDGES = {16, 4, 2}", "CASCADE_CELL_OFFSETS = {0, 4096, 4160}",
        "CELL_COUNT = 4168", "takeOwnership(", "copyArrays ? albedoRgb.clone() : albedoRgb",
    ), "block-scale immutable section snapshot")
    require_tokens(semantic_builder, (
        "MAX_OBSERVATIONS = 65_536", "ThreadLocal<Accumulators> WORKSPACE",
        "data.acquire();", "finally {\n            data.release();",
        "GiSemanticSectionSnapshot.takeOwnership(", "G2 worker reduction workspace was re-entered",
        "packMaterialContribution(", "Arrays.sort(contributions, 0, count);",
        "reduceMaterialContributions(", "GiFieldLayout.cellSizeBlocks(cascade)",
    ), "block-scale bounded primitive worker reduction")
    require("import java.util.HashMap;" not in semantic_builder
            and "new HashMap" not in semantic_builder
            and "Map<Long, Long>" not in semantic_builder,
            "block-scale worker reduction retained boxed HashMap material weights")
    require_tokens(semantic_capture, (
        "ThreadLocal<State> LOCAL", "GiSemanticSectionBuilder.MAX_OBSERVATIONS",
        "state.observations.size() >= GiSemanticSectionBuilder.MAX_OBSERVATIONS",
        "List.copyOf(this.state.observations)",
    ), "block-scale capture observation bound")
    require_tokens(semantic_reservation, (
        "GiFieldCandidateBudget.Lease", "AtomicInteger", "MAX_OBSERVATIONS",
        "G2 reservation was already consumed", "new GiSemanticSectionCandidate",
    ), "block-scale reservation ownership")
    require_tokens(semantic_tests, (
        "GiSemanticSectionSnapshot.PAYLOAD_BYTES == 112_536L",
        "GiFieldCandidateBudget.DEFAULT_MAX_BYTES >= Math.multiplyExact(",
        "GiSemanticSectionBuilder.MAX_OBSERVATIONS",
        "reused G2 worker workspace mutated a published snapshot",
    ), "block-scale candidate budget unit coverage")
    require_tokens(transport_tests, (
        "GiTransportLayout.CELL_SIZE_BLOCKS == 1",
        "computedFormWeightNormalization()", "computedConfidenceWeightNormalization()",
        "projected-solid-angle quadrature",
    ), "G4 successor numerical unit coverage")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("."))
    arguments = parser.parse_args()
    try:
        root = arguments.root.resolve()
        manifest = verify_manifest(root)
        verify_runtime_sources(root, manifest)
        print("GI G6 topology successor contract passed: historical evidence retained; runtime evidence pending")
    except ContractError as error:
        print(f"GI G6 topology successor contract FAILED: {error}", file=sys.stderr)
        raise SystemExit(1) from error


if __name__ == "__main__":
    main()
