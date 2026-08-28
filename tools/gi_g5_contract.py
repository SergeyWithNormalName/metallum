#!/usr/bin/env python3
"""Verify the default-off G5 vertex receiver and optional real Tier B evidence."""

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
from typing import Any, Callable


GI_BUDGET_BYTES = 25_165_824
G4_ACCOUNTED_BYTES = 22_637_928
WORLD_OPAQUE_P95_GATE_MS = 0.30
WHOLE_GPU_P95_GATE_PERCENT = 2.0
EVIDENCE_RELATIVE_PATH = Path("benchmark/gi/g5-receiver-evidence-v1.json")
G4_EVIDENCE_RELATIVE_PATH = Path("benchmark/gi/g4-transport-evidence-v1.json")
G4_EVIDENCE_SHA256 = "bcb38af01b658f6e3fe923ab56218a2bf4515f49d07705c4eaf9cd8f8d354ae3"
G5_ADMISSION_PREFIX = "METALLUM_BENCHMARK EVENT=GI_G5_ADMISSION "
G5_FINAL_PREFIX = "METALLUM_BENCHMARK EVENT=GI_G5_FINAL "
ADVANCED_ADMISSION_PREFIX = "METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION "
G5_ROUTE_SHA256 = "d321131b314bb22cee354e3cf48606712d414d44a84e6ed00230e70d9c65839d"
G5_FIXTURE_SHA256 = "a4a7e4fa34bed9e335856bc88f7ad1035ae1ba68e28851906ccaf9a65911e3c5"
G5_SETTINGS_SPEC_SHA256 = "92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e"
G5_SETTINGS_SHA256 = "fcf752aebd45a576e13cc19b446b954014b66e46a78c79e435289314d3b4ebb3"
RUN_ARTIFACT_NAMES = (
    "raw", "summary", "minecraft_log", "console_log", "transcript",
)

RECEIVER_FILES = (
    "CompactPositionCarrierSafety.java",
    "GiReceiverCompatibility.java",
    "GiReceiverRuntime.java",
    "GiReceiverLayout.java",
    "GiReceiverBindingAbi.java",
    "GiReceiverMath.java",
    "GiReceiverShaderPatcher.java",
    "GiReceiverGpuResources.java",
)

NATIVE_SYMBOLS = (
    "metallum_gi_receiver_abi_version_v1",
    "metallum_gi_receiver_layout_v1",
    "metallum_gi_receiver_create_context_v1",
    "metallum_gi_receiver_bind_vertex_v1",
    "metallum_gi_receiver_get_stats_v1",
    "metallum_gi_receiver_release_context_v1",
)

SHADER_NAMES = (
    "metallumGiShRed",
    "metallumGiShGreen",
    "metallumGiShBlue",
    "metallumGiConfidence",
    "metallumGiIncomingIrradiance",
    "metallumGiReceiver",
)

POSITION_PAYLOAD_MASK = 0x3FFF_FFFF
POSITION_SPARE_MASK = 0xC000_0000
POSITION_FACE_MASK = 0x7
POSITION_G5_BIT = 0x8
VALID_POSITION_CARRIER_CODES = frozenset(
    {0, *range(1, 7), *(POSITION_G5_BIT | face for face in range(1, 7))}
)


class ContractError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def text(root: Path, relative: str | Path) -> str:
    return (root / relative).read_text(encoding="utf-8")


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


def finite_number(value: Any, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractError(f"{label} must be numeric")
    result = float(value)
    if not math.isfinite(result):
        raise ContractError(f"{label} must be finite")
    return result


def hex_digest(value: Any, length: int, label: str) -> str:
    if not isinstance(value, str) or len(value) != length \
            or re.fullmatch(r"[0-9a-f]+", value) is None:
        raise ContractError(f"{label} is not a lowercase {length}-hex digest")
    return value


def extract_braced_declaration(source: str, token: str) -> str:
    start = source.find(token)
    if start < 0:
        raise ContractError(f"native G5 declaration is missing: {token}")
    opening = source.find("{", start)
    if opening < 0:
        raise ContractError(f"native G5 declaration has no body: {token}")
    depth = 0
    for offset in range(opening, len(source)):
        if source[offset] == "{":
            depth += 1
        elif source[offset] == "}":
            depth -= 1
            if depth == 0:
                return source[start:offset + 1]
    raise ContractError(f"native G5 declaration is unterminated: {token}")


def encode_position_carrier_words(
        position_hi: int,
        position_lo: int,
        carrier_code: int,
) -> tuple[int, int]:
    """Executable model of the pinned Sodium 0.9.1 two-word carrier ABI."""
    for word, label in ((position_hi, "positionHi"), (position_lo, "positionLo")):
        require(type(word) is int and 0 <= word <= 0xFFFF_FFFF,
                f"G5 self-test {label} is not one unsigned 32-bit word")
        require((word & POSITION_SPARE_MASK) == 0,
                f"G5 self-test {label} has a foreign top-bit owner")
    require(type(carrier_code) is int
            and carrier_code in VALID_POSITION_CARRIER_CODES,
            f"G5 self-test carrier code is reserved or invalid: {carrier_code!r}")
    return (
        (position_hi & POSITION_PAYLOAD_MASK) | ((carrier_code & 0x3) << 30),
        (position_lo & POSITION_PAYLOAD_MASK) | (((carrier_code >> 2) & 0x3) << 30),
    )


def decode_position_carrier_words(position_hi: int, position_lo: int) -> int:
    return (position_hi >> 30) | ((position_lo >> 30) << 2)


def verify_document(root: Path) -> None:
    document = text(root, "docs/GI_G5.md")
    for token in (
            "IMPLEMENTED_PENDING_TIER_B",
            "METALLUM_GI_G5_RECEIVER=1",
            "METALLUM_GI_G5_RECEIVER_ARM=control|candidate|field",
            "metallumGiShRed", "metallumGiShGreen", "metallumGiShBlue",
            "metallumGiConfidence", "metallumGiIncomingIrradiance",
            "texture `6`", "texture `7`", "texture `8`", "texture `9`",
            "buffer `25`", "rho_receiver / pi", "confidence == 0",
            "22,637,928", "25,165,824", "control -> candidate -> candidate -> control",
            "65,536", "carrier_skips=0", "g5_carrier_writes=[1-9][0-9]*",
            "drawn_g5_carrier_slices=[1-9][0-9]*",
            "mc26.2-0.9.1-fabric",
            "positionHi = (baseHi & 0x3fffffff) | ((code & 0x3) << 30)",
            "positionLo = (baseLo & 0x3fffffff) | (((code >>> 2) & 0x3) << 30)",
            "L8 dominant-face approximation", "exact authored G5 axis face",
            "GI_G5_FINAL state=READY carrier_skips=0 "
            "g5_carrier_writes=[1-9][0-9]* "
            "drawn_g5_carrier_slices=[1-9][0-9]* status=PASS",
            "0.30 ms", "2%", "--require-evidence",
    ):
        require(token in document, f"G5 document lost fixed contract token {token!r}")


def verify_historical_g4_evidence(root: Path) -> None:
    manifest_path = root / G4_EVIDENCE_RELATIVE_PATH
    require(manifest_path.is_file(), "accepted historical G4 evidence manifest is missing")
    require(sha256(manifest_path) == G4_EVIDENCE_SHA256,
            "accepted historical G4 evidence manifest was edited by G5")
    manifest = strict_object(manifest_path)
    tier_b = manifest.get("tier_b")
    require(isinstance(tier_b, dict), "historical G4 Tier B evidence is malformed")
    artifacts = tier_b.get("artifacts")
    require(isinstance(artifacts, dict), "historical G4 artifact manifest is missing")
    for name in ("raw", "summary", "minecraft_log", "console_log", "transcript_log"):
        descriptor = artifacts.get(name)
        require(isinstance(descriptor, dict)
                and set(descriptor) == {"path", "sha256"},
                f"historical G4 {name} descriptor differs")
        safe_artifact(
            root, descriptor.get("path"), descriptor.get("sha256"),
            f"historical G4 {name}",
        )


def verify_benchmark_runner(root: Path) -> None:
    runner = text(root, "scripts/run_metal_benchmark.sh")
    contract_test_path = root / "tools/test_gi_g5_runner_contract.sh"
    require(contract_test_path.is_file(), "G5 benchmark runner contract test is missing")
    contract_test = contract_test_path.read_text(encoding="utf-8")
    for token in (
            'GI_G5_RECEIVER_ENV=1',
            'METALLUM_GI_G5_RECEIVER="$GI_G5_RECEIVER_ENV"',
            'METALLUM_GI_G5_RECEIVER_ARM="$GI_G5_RECEIVER_ARM"',
            'GI_G5_RECEIVER_ARM=control',
            'GI_G5_RECEIVER_ARM=candidate',
            'GI_G5_RECEIVER_ARM=field',
            'RUNTIME_GI_MODE=g5_vertex_receiver',
            'require_value "$WARMUP_FRAMES" "600" "G5 warmup frames"',
            'require_value "$MEASURE_FRAMES" "600" "G5 measurement frames"',
            'require_value "$TIMING_DETAIL" "1" "G5 timing detail"',
            'g5_admission_prefix="METALLUM_BENCHMARK EVENT=GI_G5_ADMISSION "',
            'phase=WARMUP presented_frame=[0-9]+ resources=5 bindings=5 ',
            'carrier_skips=0 g5_carrier_writes=[1-9][0-9]* ',
            'drawn_g5_carrier_slices=[1-9][0-9]* status=PASS vertex_only=true ',
            'g5_combined_accounted_bytes=$((22637928 + g5_allocated_bytes))',
            'METALLUM_BENCHMARK EVENT=GI_G5_FINAL '
            'state=READY carrier_skips=0 '
            'g5_carrier_writes=[1-9][0-9]* '
            'drawn_g5_carrier_slices=[1-9][0-9]* status=PASS',
            'expected exactly one final zero-skip G5 carrier census',
    ):
        require(token in runner, f"G5 benchmark runner lost fixed token {token}")
        require(token in contract_test or token == 'GI_G5_RECEIVER_ENV=1',
                f"G5 runner self-test does not guard token {token}")
    require('1|true|TRUE|yes|YES|on|ON) GI_G5_RECEIVER_ENV=1' in runner,
            "G5 runner no longer normalizes the diagnostic enable request to exact 1")
    require("control -> candidate -> candidate -> control" in
            text(root, "docs/GI_G5.md"), "G5 ABBA launch order is undocumented")
    reporter = text(root, "tools/metal_benchmark_report.py")
    for token in (
            'G5_RECEIVER_METADATA_MODE = "g5_vertex_receiver"',
            "GI_V3_METADATA_MODES",
            "_validate_g5_zero_receiver_report",
            "G5 zero-ready field contains G4 dispatch timing",
    ):
        require(token in reporter,
                f"canonical benchmark reporter lost G5 v3 reuse guard {token!r}")
    build = text(root, "build.gradle")
    for token in (
            '"build.gradle"',
            '"tools/metal_benchmark_report.py"',
            '"benchmark/gi/g4-transport-evidence-v1.json"',
            '"benchmark/gi/evidence/gi-g4-transport-2026-08-27-v1"',
    ):
        require(token in build,
                f"G5 Gradle contract inputs lost {token}")


def verify_java_sources(root: Path) -> None:
    receiver_root = root / "src/main/java/com/metallum/client/gi/receiver"
    require(receiver_root.is_dir(), "G5 receiver Java package is missing")
    sources: dict[str, str] = {}
    for filename in RECEIVER_FILES:
        path = receiver_root / filename
        require(path.is_file(), f"G5 receiver source is missing: {path.relative_to(root)}")
        sources[filename] = path.read_text(encoding="utf-8")

    runtime = sources["GiReceiverRuntime.java"]
    for token in ("METALLUM_GI_G5_RECEIVER", "METALLUM_GI_G5_RECEIVER_ARM"):
        require(token.lower() in runtime.lower(), f"G5 runtime lost {token}")
    layout = sources["GiReceiverLayout.java"]
    binding = sources["GiReceiverBindingAbi.java"]
    assigned_slots = {
        int(match.group(1))
        for match in re.finditer(
            r"(?:SLOT|BINDING)[A-Z0-9_]*\s*=\s*(\d+)",
            layout + "\n" + binding,
            flags=re.IGNORECASE,
        )
    }
    require({6, 7, 8, 9, 25}.issubset(assigned_slots),
            f"G5 binding ABI slots differ: found {sorted(assigned_slots)}")
    for token in SHADER_NAMES:
        require(token in binding or token in sources["GiReceiverShaderPatcher.java"],
                f"G5 binding/shader source lost {token}")
    for token in ("ABI_VERSION", "PARAM", "BYTES"):
        require(token in layout, f"G5 layout lost {token}")
    require("LIFETIME_OVERHEAD_BYTES = 65_536L" in layout,
            "G5 Java census lost its conservative lifetime charge")

    math_source = sources["GiReceiverMath.java"].lower()
    for token in ("reconstruct", "confidence", "receiveralbedo", "inverse_pi"):
        require(token in math_source, f"G5 CPU math contract lost {token}")
    require(re.search(r"weight\s*==\s*0\.0f[\s\S]{0,160}return\s+approximateambient",
                      math_source) is not None,
            "G5 CPU math does not preserve the exact confidence-zero fallback")

    patcher = sources["GiReceiverShaderPatcher.java"]
    for token in (
            "GiReceiverBindingAbi.SH_RED_SAMPLER",
            "GiReceiverBindingAbi.SH_GREEN_SAMPLER",
            "GiReceiverBindingAbi.SH_BLUE_SAMPLER",
            "GiReceiverBindingAbi.CONFIDENCE_SAMPLER",
            "GiReceiverBindingAbi.PARAMS_BLOCK",
            "GiReceiverBindingAbi.VARYING",
            "metallumGiFallbackAmbient",
            "metallumGiDrySample",
            "metallumGiConfidenceValue = metallumGiSampledConfidence",
            "%s = metallumGiEvaluated",
            "uint metallumGiPositionCarrier = ((a_Position.x >> 30u) & 3u)",
            "| (((a_Position.y >> 30u) & 3u) << 2u);",
            "uint metallumGiPositionFaceCode = metallumGiPositionCarrier & 7u;",
            "(metallumGiPositionCarrier & 8u) != 0u",
            "metallumGiPositionFaceCode >= 1u",
            "metallumGiPositionFaceCode <= 6u",
            "metallumGiSamplesFinite",
            "!any(isnan(metallumGiShRedValue))",
            "!any(isinf(metallumGiShBlueValue))",
            "!isnan(metallumGiConfidenceValue)",
            ": vec4(0.0);",
    ):
        require(token in patcher, f"G5 shader patcher lost structural token {token}")
    require("metallumGiDrySample ? vec4(0.0)" not in patcher
            and "metallumGiDrySample\n                                ? 0.0" not in patcher,
            "G5 candidate explicitly zeroes sampled values and permits read elimination")
    for token in ("VERTEX", "FRAGMENT", "confidence"):
        require(token.lower() in patcher.lower(), f"G5 shader patcher lost {token}")
    require("metallumGiConfidence > 0.0" in patcher
            and ("?" in patcher or "if (" in patcher),
            "G5 fragment does not take an explicit positive-confidence branch")
    require("mix(metallumGiFallbackAmbient" not in patcher,
            "G5 confidence-zero fallback is a zero-weight mix instead of an exact branch")
    for token in (
            "metallumGiLightSignature", "metallumGiLightFaceCode",
            "metallumGiLightCarrier", "metallumGiAlphaCarrier",
            "patchCarrierRestore", "CARRIER_RESTORE_MARKER",
            "_vert_color.a = 1.0", "_vert_tex_light_coord.y =",
    ):
        require(token not in patcher,
                f"G5 shader patcher retained the obsolete light/alpha carrier token {token}")

    # G5 replaces the existing incoming ambient/indirect irradiance variable. The
    # admitted Advanced helper remains the single receiver-albedo / pi site; the
    # G5 patch must not introduce a second reflectance or inverse-pi multiply.
    advanced = text(
        root,
        "src/main/java/com/metallum/client/lighting/shader/"
        "AdvancedDirectLightingShaderPatcher.java",
    )
    require("vec3 diffuse = max(metallumEnvironment.ambientRadiance.rgb, vec3(0.0));"
            in advanced, "G5 ambient composition anchor disappeared upstream")
    require("return albedo * diffuse * 0.31830988618;" in advanced,
            "Advanced receiver-albedo / pi composition site disappeared")
    require("0.31830988618" not in patcher and "INVERSE_PI" not in patcher
            and "receiverAlbedo" not in patcher,
            "G5 shader patcher applies receiver reflectance or inverse pi twice")
    for token in (
            "((a_Position.x >> 30u) & 3u)",
            "(((a_Position.y >> 30u) & 3u) << 2u)",
            "metallumReflectionPositionCarrier & 7u",
            "(metallumReflectionPositionCarrier & 8u) != 0u",
            "metallumReflectionGiAxisEligible",
    ):
        require(token in advanced,
                f"L8/G5 shared position-carrier decode lost token {token}")

    gpu = sources["GiReceiverGpuResources.java"]
    for symbol in NATIVE_SYMBOLS[:-1]:
        require(symbol in gpu, f"G5 Java owner does not use native symbol {symbol}")
    for token in ("Consumer<MemorySegment> deferredRelease", "deferredRelease.accept(stale)"):
        require(token in gpu, f"G5 Java owner lost deferred release token {token}")
    require(gpu.count("transport.nativeContext()") == 1,
            "G5 Java owner performs duplicate read-token handle lookups")
    for forbidden in (
            "setFragmentTexture", "bindFragment", "captureVolume", "readback",
            "makeTexture", "newTexture", "sidecar",
    ):
        require(forbidden.lower() not in gpu.lower(),
                f"G5 Java owner contains forbidden path {forbidden}")

    require('fragmentMsl.contains("texture3d<float> " + name)' in binding
            and 'fragmentMsl.contains(name + ".sample(")' in binding,
            "G5 emitted-MSL guard no longer rejects fragment 3D texture samples")

    test_root = root / "src/test/java/com/metallum/client/gi/receiver"
    cpu_test = text(test_root, "GiReceiverCpuTests.java")
    source_chain_test = text(test_root, "GiReceiverSourceChainTests.java")
    carrier_test = text(test_root, "GiReceiverCarrierCoexistenceTests.java")
    require("Float.NaN" in cpu_test and "confidence zero" in cpu_test,
            "G5 CPU tests do not cover NaN input under exact confidence-zero fallback")
    for token in (
            "textureLod(", "== 4", "metallumGiIncomingIrradiance",
            "fragment 3D texture", "deferredRelease.accept(stale)",
            "a_Position.x", "a_Position.y",
            "(metallumGiPositionCarrier & 8u) != 0u",
            "metallumGiSamplesFinite", "isnan(", "isinf(",
    ):
        require(token in source_chain_test,
                f"G5 source-chain tests lost structural token {token}")
    for token in (
            "private final GiTransportGpuResources owner;",
            "return this.owner.nativeContextFor(this);",
            "token == this.readToken",
    ):
        require(token in source_chain_test,
                f"G5 source-chain test lost read-token lifecycle guard {token}")
    for token in (
            "compactPositionCarrierCode", "writeCompactPositionCarrier",
            "decodeCompactPositionCarrier", "compactPositionPayload",
            "POSITION_CARRIER_G5_BIT", "AdmissionState.INVALID",
            "vertex.color", "vertex.light", "pre-owned code 9",
            "conflictPointer, end, 0", "testBspSplitThroughRealWriteExternal",
            "getDeclaredMethod", "builder.writeExternal",
            "DefaultMaterials.TRANSLUCENT", "propagateBspInterpolatedSemantic",
            "!CompactPositionCarrierSafety.isSafe()",
            "TestVertex destinationB = endpointB",
            "bspVertexSemanticSnapshot(endpointA)",
            "bspVertexSemanticSnapshot(endpointB)",
    ):
        require(token in carrier_test,
                f"G5/L8 coexistence regression lost token {token}")
    require(re.search(
        r"for\s*\(\s*int\s+code\s*=\s*0\s*;\s*"
        r"(?:code\s*<=\s*15|code\s*<\s*16)\s*;",
        carrier_test,
    ) is not None, "G5 carrier regression no longer exercises every code 0..15")

    semantic = text(root, "src/main/java/com/metallum/client/hdr/SodiumHdrSemantic.java")
    obsolete_carrier_scope = "\n".join((
        patcher, advanced, semantic,
    ))
    for token in (
            "metallumGiLightSignature", "metallumGiLightFaceCode",
            "metallumGiLightCarrier", "metallumGiAlphaCarrier",
            "metallumGiFallbackPairedSignature",
            "metallumGiFallbackLightOnlySignature",
            "metallumGiFallbackReflectionOnlySignature",
            "metallumReflectionGiAlphaFaceCode",
            "metallumReflectionGiPairedLightSignature",
            "metallumReflectionGiLightOnlySignature",
            "metallumReflectionLightCarrier", "metallumReflectionAlphaCarrier",
            "metallumReflectionRestoredLightByte",
            "metallumReflectionOnlyAlphaFaceCode",
            "patchCarrierRestore", "CARRIER_RESTORE_MARKER",
            "restoredAlpha", "restoredBlock", "restoredSky",
            "signed-light", "signed light",
    ):
        require(token.lower() not in obsolete_carrier_scope.lower(),
                f"G5 sources/tests retained obsolete light/alpha carrier token {token}")
    combined_carrier_tests = "\n".join((source_chain_test, carrier_test))
    for token in (
            "GiReceiverShaderPatcher.patchCarrierRestore(",
            "GiReceiverShaderPatcher.CARRIER_RESTORE_MARKER",
    ):
        require(token not in combined_carrier_tests,
                f"G5 tests still execute the obsolete decode/restore path {token}")

    real_msl_test = text(
        root,
        "src/test/java/com/metallum/client/metal/render/RealWorldVertexReflectionTests.java",
    )
    for token in (
            "testGiG5GeneratedMslContractProof", "GiReceiverBindingAbi.validateMsl",
            "GI_G5_GENERATED_MSL", "GI_G5_REFLECTION_COEXISTENCE_GENERATED_MSL",
            "countOccurrences(vertexMsl, sampler + \".sample(\") == 1",
            "a_Position.x", "a_Position.y", ">> 30",
            "metallumGiPositionCarrier", "SUN_SHADOW",
            'contains("buffer(14)")', 'contains("buffer(16)")',
            'contains("buffer(27)")',
    ):
        require(token in real_msl_test,
                f"G5 real generated-MSL proof lost token {token}")
    for token in (
            "patchCarrierRestore", "CARRIER_RESTORE_MARKER",
            "metallumReflectionGiAlphaFaceCode",
            "metallumReflectionGiPairedLightSignature",
            "metallumReflectionGiLightOnlySignature",
    ):
        require(token not in real_msl_test,
                f"G5 generated-MSL proof retained obsolete carrier token {token}")
    build = text(root, "build.gradle")
    require('tasks.named("realWorldVertexReflectionUnitTest")' in build
            and 'tasks.named("giReceiverCarrierCoexistenceUnitTest")' in build,
            "G5 contract task no longer depends on real MSL/carrier regressions")

    face_source = text(
        root,
        "src/main/java/com/metallum/client/lighting/reflection/VoxelReflectionFace.java",
    )
    require("forAxisAlignedUnitNormal" in face_source and "AXIS_EPSILON" in face_source,
            "G5 axis-normal admission fell back to dominant-face inference")
    for token in (
            "GI_AXIS_FACE_MASK",
            "COMPACT_VERTEX_STRIDE = 20",
            "COMPACT_QUAD_VERTEX_COUNT = 4",
            "POSITION_CARRIER_G5_BIT = 0x8",
            "POSITION_CARRIER_FACE_MASK = 0x7",
            "POSITION_SPARE_MASK = 0xc000_0000",
            "POSITION_PAYLOAD_MASK = 0x3fff_ffff",
            "GiReceiverRuntime.isRequested()",
            "compactPositionCarrierRequested()",
    ):
        require(token in semantic, f"G5 CPU position carrier lost token {token}")
    carrier_code_body = extract_braced_declaration(
        semantic, "int compactPositionCarrierCode("
    )
    for token in (
            "consistentFaceBit(vertices, REFLECTION_FACE_MASK",
            "consistentFaceBit(vertices, GI_AXIS_FACE_MASK",
            "POSITION_CARRIER_G5_BIT | reflectionFaceCode(giAxisFaceBit)",
            "return reflectionFaceCode(reflectionFaceBit);",
            "g5PositionCarrierEnabled()",
            "reportPositionCarrierConflict(",
    ):
        require(token in carrier_code_body,
                f"G5 quad-level position carrier mapping lost token {token}")
    write_carrier_body = extract_braced_declaration(
        semantic, "boolean writeCompactPositionCarrier("
    )
    for token in (
            "int faceCode = carrierCode & POSITION_CARRIER_FACE_MASK;",
            "boolean validCode = carrierCode == 0",
            "(carrierCode & ~0x0f) != 0",
            "endPointer - pointer != (long) COMPACT_VERTEX_STRIDE * COMPACT_QUAD_VERTEX_COUNT",
            "(positionHi & POSITION_SPARE_MASK) != 0",
            "(positionLo & POSITION_SPARE_MASK) != 0",
            "if (carrierCode == 0)",
            "int positionHiBits = (carrierCode & 0x3) << 30;",
            "int positionLoBits = ((carrierCode >>> 2) & 0x3) << 30;",
            "MemoryIntrinsics.getInt(vertexPointer) | positionHiBits",
            "MemoryIntrinsics.getInt(vertexPointer + Integer.BYTES) | positionLoBits",
            "reportPositionCarrierConflict(",
    ):
        require(token in write_carrier_body,
                f"G5 Sodium 0.9.1 position write lost token {token}")
    require(write_carrier_body.count(
        "for (int vertex = 0; vertex < COMPACT_QUAD_VERTEX_COUNT; vertex++)"
    ) == 2, "G5 position carrier no longer preflights and writes every quad vertex")
    require(write_carrier_body.index("(positionHi & POSITION_SPARE_MASK)")
            < write_carrier_body.index("if (carrierCode == 0)")
            < write_carrier_body.index("int positionHiBits")
            < write_carrier_body.index("| positionHiBits"),
            "G5 zero/no-write path bypasses preflight or carrier writes before preflight")
    decode_carrier_body = extract_braced_declaration(
        semantic, "int decodeCompactPositionCarrier("
    )
    require("return positionHi >>> 30 | (positionLo >>> 30) << 2;"
            in decode_carrier_body,
            "G5 CPU decoder no longer reconstructs the exact 2+2 top-bit layout")
    payload_body = extract_braced_declaration(
        semantic, "int compactPositionPayload("
    )
    require("return positionWord & POSITION_PAYLOAD_MASK;" in payload_body,
            "G5 CPU payload decoder no longer preserves the lower 30 bits")
    conflict_body = extract_braced_declaration(
        semantic, "void reportPositionCarrierConflict("
    )
    require("CompactPositionCarrierSafety.reportCarrierSkip(reason);" in conflict_body
            and "GiReceiverRuntime.admission().reportCarrierSkip(reason);"
            not in conflict_body,
            "G5 position carrier conflict bypasses the atomic shared/admission transition")
    bsp_semantic_body = extract_braced_declaration(
        semantic, "boolean propagateBspInterpolatedSemantic("
    )
    for token in (
            "semanticA == semanticB", "matches ? semanticA : 0",
            "setVertexSemantic(destinationA, inheritedSemantic)",
            "setVertexSemantic(destinationB, inheritedSemantic)",
            "setVertexSemantic(destinationC, inheritedSemantic)",
            "if (!matches && compactPositionCarrierRequested())",
            "reportPositionCarrierConflict(",
    ):
        require(token in bsp_semantic_body,
                f"G5 BSP semantic propagation lost fail-closed token {token}")

    encoder_hook = text(
        root, "src/main/java/com/metallum/mixin/sodium/ChunkMeshBufferBuilderHdrMixin.java"
    )
    for token in (
            "@Mixin(ChunkMeshBufferBuilder.class)",
            "boolean positionCarrier = SodiumHdrSemantic.compactPositionCarrierRequested();",
            "compactPositionCarrierCode(vertices)",
            "long endPointer = encoder.write(",
            "if (positionCarrier",
            "writeCompactPositionCarrier(",
            "this.metallum$hasG5Carrier = false;",
            "this.metallum$hasG5Carrier = true;",
    ):
        require(token in encoder_hook,
                f"Sodium compact encoder hook lost position-carrier token {token}")
    require(encoder_hook.index("compactPositionCarrierCode(vertices)")
            < encoder_hook.index("long endPointer = encoder.write(")
            < encoder_hook.index("writeCompactPositionCarrier("),
            "G5 position sideband is not applied strictly after ordinary Sodium packing")
    require(encoder_hook.index("compactPositionCarrierRequested()")
            < encoder_hook.index("compactPositionCarrierCode(vertices)")
            and "? SodiumHdrSemantic.compactPositionCarrierCode(vertices) : 0"
            in encoder_hook,
            "G5 default-off meshing still scans or reads compact position carrier data")
    bsp_hook = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/"
        "InnerPartitionBSPNodeSemanticMixin.java",
    )
    for token in (
            "InnerPartitionBSPNode", "interpolateAttributes(FLorg/joml/Vector3fc;",
            "@Shadow", "@Redirect(", "ChunkVertexEncoder$Vertex;writeVertex(",
            "copyVertexToMultiple(",
            "require = 3", "allow = 3",
            "bspVertexSemanticSnapshot(endpointA)",
            "bspVertexSemanticSnapshot(endpointB)",
            "ChunkVertexEncoder.Vertex.writeVertex(",
            "SodiumHdrSemantic.propagateBspInterpolatedSemantic(",
            "destinationA,", "destinationB,", "destinationC",
    ):
        require(token in bsp_hook,
                f"G5 exact Sodium translucent BSP hook lost token {token}")
    require(bsp_hook.count("@Redirect(") == 2
            and bsp_hook.count("require = 3") == 2
            and bsp_hook.count("allow = 3") == 2,
            "G5 BSP hook no longer covers all three write and three copy sites exactly")
    bsp_write_hook = extract_braced_declaration(
        bsp_hook, "void metallum$writeWithAgreedSemantic("
    )
    bsp_copy_hook = extract_braced_declaration(
        bsp_hook, "void metallum$copyWithAgreedSemantic("
    )
    for body, original_token in (
            (bsp_write_hook, "ChunkVertexEncoder.Vertex.writeVertex("),
            (bsp_copy_hook, "copyVertexToMultiple("),
    ):
        require(body.index("bspVertexSemanticSnapshot(endpointA)")
                < body.index(original_token)
                < body.index("propagateBspInterpolatedSemantic("),
                "G5 BSP redirect does not snapshot aliasable endpoints before mutation")
    for forbidden in ("Operation<", "original.call(", "CallbackInfo", "@Inject("):
        require(forbidden not in bsp_hook,
                f"G5 BSP hot path reintroduced an allocating callback token {forbidden}")

    block_mixin = text(
        root, "src/main/java/com/metallum/mixin/sodium/BlockRendererHdrMixin.java"
    )
    fluid_mixin = text(
        root, "src/main/java/com/metallum/mixin/sodium/DefaultFluidRendererHdrMixin.java"
    )
    require("GiReceiverRuntime.isRequested() ? metallum$giAxisFace(quad) : 0"
            in block_mixin
            and "GiReceiverRuntime.isRequested() ? giAxisFace(facing) : 0"
            in fluid_mixin,
            "G5 default-off terrain tagging still computes strict axis faces")

    mixin_gate = text(root, "src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java")
    require('GI_G5_RECEIVER_ENV = "METALLUM_GI_G5_RECEIVER"' in mixin_gate
            and "isEnabled(System.getenv(GI_G5_RECEIVER_ENV))" in mixin_gate,
            "G5 request does not enable the accepted G2 capture mixins needed by FIELD")
    require('SODIUM_EXACT_VERSION = "0.9.1+mc26.2"' in mixin_gate,
            "G5 position ABI is not fail-closed on exact Sodium 0.9.1 runtime identity")
    carrier_mixins = (
        "BSPWorkspaceG5CarrierMixin",
        "BuiltSectionInfoBuilderG5CarrierMixin",
        "BuiltSectionInfoG5CarrierMixin",
        "ChunkBuildBuffersG5CarrierMixin",
        "InnerPartitionBSPNodeSemanticMixin",
        "RenderRegionManagerG5CarrierMixin",
        "UpdatedQuadsListG5CarrierMixin",
    )
    require(all(f'"com.metallum.mixin.sodium.{name}"' in mixin_gate
                for name in carrier_mixins)
            and "this.compactPositionCarrierCompatible = exactGiCaptureVersions"
            in mixin_gate
            and "isEnabled(System.getenv(GI_G5_RECEIVER_ENV))" in mixin_gate
            and "VertexReflectionExperiment.isLayoutEnabled()" in mixin_gate
            and "COMPACT_POSITION_CARRIER_MIXINS.contains(mixinClassName)"
            in mixin_gate,
            "G5 BSP hook lacks exact, requested-only compact-position admission")
    mixin_config = text(root, "src/main/resources/metallum.mixins.json")
    require(all(f'"sodium.{name}"' in mixin_config for name in carrier_mixins),
            "G5 exact Sodium carrier chain is missing from mixin config")

    metadata = text(
        root, "src/main/java/com/metallum/client/sodium/SodiumG5CarrierMetadata.java"
    )
    for token in (
            "INFO_SOLID_SHIFT = 8", "INFO_CUTOUT_SHIFT = 15",
            "INFO_TRANSLUCENT_SHIFT = 22", "RESIDENT_SHIFT = 8",
            "RESIDENT_OWNER_BIT = 1 << 7", "STOCK_RESIDENT_MASK = FACE_MASK",
            "carrierFaceMask & ~stockMask", "foreign high-bit owner",
            "hasLiveG5Carrier(final List<?> bspSlots)",
            "for (int index = 0; index < size; index++)",
            "slot instanceof FullTQuad quad", "quad.isInvalid()",
            "compactPositionCarrierCode(quad.getVertices())",
            "CompactPositionCarrierSafety.reportConflict(",
    ):
        require(token in metadata,
                f"G5 zero-storage resident metadata lost token {token}")
    for forbidden in ("new byte[", "byte[]", "ByteBuffer", "MemoryIntrinsics", ".iterator()"):
        require(forbidden not in metadata,
                f"G5 live/resident metadata introduced allocation or stale-buffer token {forbidden}")

    bsp_workspace = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/BSPWorkspaceG5CarrierMixin.java",
    )
    for token in (
            "method = \"getFinalizedUpdatedQuads\"", "at = @At(\"RETURN\")",
            "if (!GiReceiverRuntime.isRequested()", "updatedQuads == null",
            "(List<?>) (Object) this", "hasLiveG5Carrier(",
            "metallum$setLiveHasG5Carrier(",
    ):
        require(token in bsp_workspace,
                f"G5 finalized live-BSP seam lost token {token}")
    require(bsp_workspace.index("if (!GiReceiverRuntime.isRequested()")
            < bsp_workspace.index("hasLiveG5Carrier("),
            "G5 default-off BSP path scans live quad slots")

    updated_quads = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/UpdatedQuadsListG5CarrierMixin.java",
    )
    require("private boolean metallum$liveHasG5Carrier;" in updated_quads
            and "metallum$setLiveHasG5Carrier(" in updated_quads
            and "metallum$liveHasG5Carrier()" in updated_quads
            and "ByteBuffer" not in updated_quads
            and "List<" not in updated_quads,
            "G5 finalized BSP hand-off is not one primitive on the existing result")

    info_builder = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/"
        "BuiltSectionInfoBuilderG5CarrierMixin.java",
    )
    info_result = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/"
        "BuiltSectionInfoG5CarrierMixin.java",
    )
    require("private int metallum$packedG5CarrierMasks;" in info_builder
            and "replaceInfoFaceMask(" in info_builder
            and "method = \"build()" in info_builder
            and "this.flags = SodiumG5CarrierMetadata.mergeInfoFlags(" in info_result,
            "G5 worker mask is not packed into the accepted BuiltSectionInfo primitive")

    build_buffers = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/ChunkBuildBuffersG5CarrierMixin.java",
    )
    for token in (
            "method = \"init\"", "this.metallum$remainingG5Passes =",
            "method = \"createMesh\"", "if (forceUnassigned)",
            "ModelQuadFacing.UNASSIGNED_MASK", "mesh.getVertexSegments()",
            "SodiumG5CarrierUpdatedQuadsAccess",
            "metallum$liveHasG5Carrier()",
            "metallum$setG5CarrierFaceMask(", "metallum$finishG5Pass();",
    ):
        require(token in build_buffers,
                f"G5 finalized worker face census lost token {token}")
    for forbidden in ("new byte[", "getDirectBuffer()", "@Local", "ByteBuffer", "MemoryUtil"):
        require(forbidden not in build_buffers,
                f"G5 worker census retained a stale destination-buffer token {forbidden}")

    resident_upload = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/"
        "RenderRegionManagerG5CarrierMixin.java",
    )
    for token in (
            "uploadResults(Ljava/util/Collection;", "at = @At(\"RETURN\")",
            "METALLUM$SLICE_MASK_OFFSET = 16L", "output.info.flags",
            "withResidentCarrierFaceMask(", "MemoryIntrinsics.putInt(",
    ):
        require(token in resident_upload,
                f"G5 accepted resident publication lost token {token}")

    chunk_renderer = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/DefaultChunkRendererShadowMixin.java",
    )
    fill_census = extract_braced_declaration(
        chunk_renderer, "int metallum$captureDrawnG5CarrierSlices("
    )
    for token in (
            "SectionRenderDataUnsafe.getSliceMask(dataPointer)",
            "if (!GiReceiverRuntime.isRequested())", "return residentMask;",
            "stockResidentFaceMask(residentMask)",
            "residentCarrierFaceMask(residentMask)",
            "stockSlices & carrierSlices & metallum$currentVisibleFaces",
            "metallum$addDrawnG5CarrierSlices(drawnCarrierSlices)",
    ):
        require(token in fill_census,
                f"G5 exact fill-command census lost token {token}")
    require(fill_census.index("if (!GiReceiverRuntime.isRequested())")
            < fill_census.index("residentCarrierFaceMask(residentMask)"),
            "G5 default-off fill-command path decodes resident carrier metadata")

    batch_access = text(
        root,
        "src/main/java/com/metallum/client/sodium/"
        "SodiumIndexedIndirectBatchAccess.java",
    )
    batch_state = text(
        root,
        "src/main/java/com/metallum/mixin/sodium/VKIndirectDrawBatchMixin.java",
    )
    batcher = text(
        root,
        "src/main/java/com/metallum/client/metal/render/"
        "SodiumIndexedIndirectBatcher.java",
    )
    for source, tokens, label in (
        (batch_access, (
            "metallum$resetDrawnG5CarrierSlices()",
            "metallum$addDrawnG5CarrierSlices(int drawnSlices)",
            "metallum$setPreparedSnapshot(",
            "metallum$getPreparedDrawnG5CarrierSlices(",
        ), "batch access"),
        (batch_state, (
            "metallum$preparedSubmitIndex", "metallum$preparedRenderInvocationEpoch",
            "metallum$preparedDrawnG5CarrierSlices", "Math.addExact(",
        ), "batch primitive state"),
        (batcher, (
            "access.metallum$getDrawnG5CarrierSlices()",
            "access.metallum$setPreparedSnapshot(",
            "access.metallum$getPreparedDrawnG5CarrierSlices(",
            "metalContext.drawPreparedIndexedIndirect(",
        ), "private snapshot carry"),
    ):
        for token in tokens:
            require(token in source, f"G5 {label} lost token {token}")
    require("sectionsWithGeometryIterator(" not in batcher,
            "G5 private snapshot recomputes carrier residency after command emission")

    draw_context = text(
        root, "src/main/java/com/metallum/client/metal/render/MetalDrawContext.java"
    )
    require("final long drawnG5CarrierSlices" in draw_context
            and "drawIndexedIndirectOwned(commands, drawCount, drawnG5CarrierSlices)"
            in draw_context,
            "G5 exact drawn-slice count is not carried into the Metal render pass")
    compatibility = text(
        root,
        "src/main/java/com/metallum/client/gi/receiver/GiReceiverCompatibility.java",
    )
    for token in (
            'MINECRAFT_VERSION = "26.2"',
            'SODIUM_VERSION = "0.9.1+mc26.2"',
            'MIXIN_EXTRAS_VERSION = "0.5.4"',
            "supportsInstalledCompactPositionCarrier()",
            "supportsExactVersions(",
    ):
        require(token in compatibility,
                f"G5 installed compact-position compatibility lost token {token}")
    receiver_runtime = sources["GiReceiverRuntime.java"]
    for token in (
            "admitCompactPositionCarrier(",
            "target.reportCarrierConflict(",
            "target.reportCarrierSafe();",
    ):
        require(token in receiver_runtime,
                f"G5 runtime compatibility admission lost token {token}")
    metal_device = text(
        root, "src/main/java/com/metallum/client/metal/render/MetalDevice.java"
    )
    require("GiReceiverRuntime.admitCompactPositionCarrier(" in metal_device
            and "GiReceiverCompatibility.supportsInstalledCompactPositionCarrier()"
            in metal_device,
            "G5 Metal startup no longer aborts an incompatible compact-position layout")
    require(metal_device.count("METALLUM_BENCHMARK EVENT=GI_G5_ADMISSION ") == 1,
            "G5 admission has more than one or no authoritative emission site")
    receiver_bind = extract_braced_declaration(metal_device, "void bindGiReceiver(")
    require("resources.bindVertex(" in receiver_bind
            and ".stats()" not in receiver_bind,
            "G5 per-encoder bind performs diagnostic allocation or omits native bindings")
    terrain_receipt = extract_braced_declaration(
        metal_device, "void reportG5TerrainDraw("
    )
    for token in (
            "if (drawnG5CarrierSlices <= 0L)",
            "reportTerrainDrawEncoded(drawnG5CarrierSlices)",
            "giReceiverBindingProofSubmitIndex == submitIndex",
            "giReceiverBindingProofEncoderAddress == encoder.handle().address()",
            "GiTransportRuntime.isBenchmarkWarmup()",
            "reportBenchmarkReceiptEmitted()",
            "resources.stats()",
            "drawn_g5_carrier_slices={}",
            "METALLUM_BENCHMARK EVENT=GI_G5_ADMISSION ",
    ):
        require(token in terrain_receipt,
                f"G5 actual-terrain receipt lost token {token}")
    render_pass = text(
        root, "src/main/java/com/metallum/client/metal/render/MetalRenderPass.java"
    )
    pinned_draw = extract_braced_declaration(
        render_pass, "void drawIndexedIndirectOwned(\n            final MetalGpuBuffer"
    )
    require("enc.drawIndexedPrimitivesIndirect(" in pinned_draw
            and "CompactPositionCarrierSafety.beginCarrierAwareDraw();" in pinned_draw
            and "prepareCompactPositionCarrierDraw(enc);" in pinned_draw
            and "this.device.reportG5TerrainDraw(enc, drawnG5CarrierSlices);" in pinned_draw
            and "CompactPositionCarrierSafety.endCarrierAwareDraw();" in pinned_draw
            and pinned_draw.index("prepareCompactPositionCarrierDraw(enc);")
            < pinned_draw.index("enc.drawIndexedPrimitivesIndirect(")
            and pinned_draw.index("enc.drawIndexedPrimitivesIndirect(")
            < pinned_draw.index("this.device.reportG5TerrainDraw(enc, drawnG5CarrierSlices);"),
            "G5 pinned Sodium draw lost its pre-draw safety boundary or downstream receipt")
    carrier_prepare = extract_braced_declaration(
        render_pass, "void prepareCompactPositionCarrierDraw("
    )
    for token in (
            "CompactPositionCarrierSafety.revision()",
            "refreshCompactPositionCarrierBindings(",
    ):
        require(token in carrier_prepare,
                f"G5 pre-draw safety refresh lost token {token}")
    alternate_routes = (
        'rejectAlternateCompactPositionCarrierDraw("drawIndexed")',
        'rejectAlternateCompactPositionCarrierDraw("multiDrawIndexed(IntBuffer)")',
        'rejectAlternateCompactPositionCarrierDraw("multiDrawIndexed(PointerBuffer)")',
        'rejectAlternateCompactPositionCarrierDraw("drawMultipleIndexed")',
        'rejectAlternateCompactPositionCarrierDraw("draw")',
        'rejectAlternateCompactPositionCarrierDraw("drawIndirect")',
    )
    for token in alternate_routes:
        require(token in render_pass,
                f"carrier-aware PSO can escape the pinned indirect seam: {token}")
    require(render_pass.count("rejectAlternateCompactPositionCarrierDraw(")
            == len(alternate_routes) + 1,
            "G5 alternate draw-route guard count changed unexpectedly")
    compiled_pipeline = text(
        root,
        "src/main/java/com/metallum/client/metal/render/MetalCompiledRenderPipeline.java",
    )
    compatible_flavor = extract_braced_declaration(
        compiled_pipeline, "boolean isG5CompatibleFlavor("
    )
    require("HdrShaderFlavor.METALLUM_ADVANCED" in compatible_flavor
            and "flavor == HdrShaderFlavor.METALLUM\n" not in compatible_flavor,
            "G5 main terrain can silently select base Metallum without a receiver")
    require("mainMaterialTerrain && !isG5CompatibleFlavor(flavor)" in compiled_pipeline
            and "selectsG5TerrainReceiver(" in compiled_pipeline,
            "G5 target Sodium pipeline selection is not fail-closed")
    require("GiReceiverCompatibility.supportsInstalledCompactPositionCarrier()"
            in extract_braced_declaration(
                sources["CompactPositionCarrierSafety.java"], "boolean isSafe("
            ),
            "G5 shared carrier safety lost exact installed-version admission")
    g5_carrier_gate = extract_braced_declaration(
        semantic, "boolean g5PositionCarrierEnabled("
    )
    require("GiReceiverRuntime.admission().carrierSafe()" in g5_carrier_gate,
            "G5 quad encoder can emit position carriers before runtime admission")
    vertex_reflection = text(
        root,
        "src/main/java/com/metallum/client/lighting/reflection/VertexReflectionExperiment.java",
    )
    layout_gate = extract_braced_declaration(vertex_reflection, "boolean isLayoutEnabled(")
    contribution_gate = extract_braced_declaration(
        vertex_reflection, "boolean isRuntimeEnabled("
    )
    require("GiReceiverCompatibility.supportsInstalledCompactPositionCarrier()"
            in layout_gate and "layoutEnabled" in layout_gate,
            "L8/G5 position layout is not exact-version and restart-stable")
    require("isLayoutEnabled() && CompactPositionCarrierSafety.isSafe()"
            in contribution_gate,
            "L8 contribution no longer fails closed without changing its shader layout")
    safety = sources["CompactPositionCarrierSafety.java"]
    for token in (
            "AtomicReference<String>", "CONFLICT_REASON.compareAndSet(null, resolved)",
            "AtomicLong", "REVISION.incrementAndGet()",
            "CONFLICT_REASON.get() == null", "CONFLICT_REASON.getAndSet(null)",
            "ReentrantReadWriteLock(true)", "DRAW_GATE.writeLock().lock()",
            "DRAW_GATE.readLock().lock()", "DRAW_GATE.readLock().unlock()",
            "GiReceiverRuntime.admission().reportCarrierSkip(resolved)",
            "GiReceiverRuntime.admission().reportCarrierConflict(resolved)",
    ):
        require(token in safety,
                f"shared position safety lost atomic publication token {token}")
    require("CompactPositionCarrierSafety.isSafe()" in receiver_bind
            and "GiReceiverRuntime.admission().carrierSafe()" in receiver_bind,
            "G5 native bind does not derive fail-closed safety from both shared and admission state")
    require("this.giReceiverBindingProofCarrierSafe" in terrain_receipt
            and "CompactPositionCarrierSafety.isSafe()" in terrain_receipt,
            "G5 receipt can qualify a disabled or conflicted carrier binding")
    require("G5 terrain draw rejected:" not in terrain_receipt,
            "G5 post-draw receipt throws instead of preserving ordinary terrain")
    surface_test = text(
        root, "src/test/java/com/metallum/client/lighting/SurfaceMaterialPolicyTests.java"
    )
    for token in (
            "L8-only pre-owned code 1", "writeCompactPositionCarrier(pointer, end, 0)",
            "VertexReflectionExperiment.isLayoutEnabled()",
            "!VertexReflectionExperiment.isRuntimeEnabled()",
    ):
        require(token in surface_test,
                f"L8-only fail-closed carrier regression lost token {token}")
    compiler = text(
        root, "src/main/java/com/metallum/client/metal/render/MetalCrossShaderCompiler.java"
    )
    require("VertexReflectionExperiment.isRuntimeEnabled()" not in compiler
            and "VertexReflectionExperiment.isLayoutEnabled()" in compiler,
            "reflection compiler still changes resource topology on a worker safety latch")
    for relative in (
            "src/main/java/com/metallum/client/metal/render/SunShadowGpuResources.java",
            "src/main/java/com/metallum/client/metal/render/LocalVoxelShadowGpuResources.java",
            "src/main/java/com/metallum/client/metal/render/PlanarReflectionConfig.java",
    ):
        source = text(root, relative)
        require("VertexReflectionExperiment.isLayoutEnabled()" in source,
                f"reflection topology gate is not restart-stable in {relative}")
    gradle_properties = text(root, "gradle.properties")
    require(re.findall(
        r"(?m)^sodium_version=mc26\.2-0\.9\.1-fabric$", gradle_properties
    ) == ["sodium_version=mc26.2-0.9.1-fabric"],
            "G5 position ABI is not pinned to exact Sodium mc26.2-0.9.1-fabric sources")
    benchmark_controller = text(
        root,
        "src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java",
    )
    final_gate = benchmark_controller.index(
        "CompactPositionCarrierSafety.beginCarrierAwareDraw();"
    )
    final_snapshot = benchmark_controller.index(".finalSnapshot(", final_gate)
    final_receipt = benchmark_controller.index(
        "METALLUM_BENCHMARK EVENT=GI_G5_FINAL ", final_snapshot
    )
    complete_receipt = benchmark_controller.index(
        "METALLUM_BENCHMARK EVENT=COMPLETE ", final_receipt
    )
    final_unlock = benchmark_controller.index(
        "CompactPositionCarrierSafety.endCarrierAwareDraw();", complete_receipt
    )
    require(final_gate >= 0
            and final_snapshot > final_gate
            and final_receipt > final_snapshot
            and complete_receipt > final_receipt
            and final_unlock > complete_receipt
            and "snapshot.drawnG5CarrierSlices() <= 0L" in benchmark_controller
            and "!snapshot.terrainDrawEncoded()" in benchmark_controller
            and "!snapshot.benchmarkReceiptEmitted()" in benchmark_controller,
            "G5 FINAL and COMPLETE no longer share one gated immutable positive-draw census")
    runner = text(root, "scripts/run_metal_benchmark.sh")
    require('grep -Ec "${g5_final}$"' in runner
            and 'grep -nE "${g5_final}$"' in runner
            and 'grep -Fxc "$g5_final"' not in runner
            and 'grep -nFx "$g5_final"' not in runner,
            "G5 runner cannot validate the standard prefixed Minecraft final receipt")

    runtime_stages = list((root / "src/main/java").rglob("GiRuntimeStages.java"))
    require(len(runtime_stages) == 1, "G5 requires exactly one GiRuntimeStages.java")
    stage_source = runtime_stages[0].read_text(encoding="utf-8")
    for arm in ("control", "candidate", "field"):
        require(f'case "{arm}"' in stage_source,
                f"G5 runtime stages lost the {arm} arm")
    require("ReceiverArm.FIELD" in stage_source
            and re.search(r"(?:null|isBlank)[\s\S]{0,240}ReceiverArm\.FIELD", stage_source)
            is not None,
            "G5 runtime stages do not default a missing enabled arm to FIELD")
    enabled_body = extract_braced_declaration(stage_source, "boolean enabled(")
    for admitted in ('"1"', '"true"', '"yes"', '"on"'):
        require(admitted in enabled_body,
                f"G5 enable parser lost normalized diagnostic value {admitted}")

    all_java_paths = sorted((root / "src/main/java").rglob("*.java"))
    for token, defining_file in (
            ("GiReceiverShaderPatcher", "GiReceiverShaderPatcher.java"),
            ("GiReceiverGpuResources", "GiReceiverGpuResources.java"),
    ):
        external_sources = "\n".join(
            path.read_text(encoding="utf-8")
            for path in all_java_paths if path.name != defining_file
        )
        require(token in external_sources,
                f"G5 source exists but has no external integration: {token}")

    transport_root = root / "src/main/java/com/metallum/client/gi/transport"
    transport_source = "\n".join(
        path.read_text(encoding="utf-8") for path in sorted(transport_root.glob("*.java"))
    ).lower()
    require("com.metallum.client.gi.receiver" not in transport_source,
            "G4 field producer depends on G5 instead of an opaque native handoff")
    require("private final gitransportgpuresources owner;" in transport_source
            and "return this.owner.nativecontextfor(this);" in transport_source,
            "G4 read token retains a stale raw native context after owner close")


def verify_native_sources(root: Path) -> None:
    bridge = text(root, "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java")
    native = text(root, "src/main/native/MetallumNative.swift")
    for symbol in NATIVE_SYMBOLS:
        require(symbol in bridge, f"Java bridge is missing G5 symbol {symbol}")
        require(symbol in native, f"native implementation is missing G5 symbol {symbol}")
    require("metallumGiReceiverLifetimeOverheadBytesV1: UInt64 = 65_536" in native,
            "native G5 census lost its conservative lifetime charge")
    radiance = text(root, "src/main/java/com/metallum/client/radiance/RadianceGpuResources.java")
    for token in (
            "final boolean contributionAllowed",
            "this.nativeHandle, encoder, contributionAllowed",
    ):
        require(token in radiance,
                f"L8 fail-closed bind lost contribution token {token}")
    for token in (
            "private let disabledParameters: MTLBuffer",
            "contributionReady ? parameters : disabledParameters",
            "encoder.setVertexTexture(sourceRadiance, index: 10)",
            "encoder.setVertexSamplerState(vertexSampler, index: 10)",
            "contributionAllowed: contributionAllowed != 0",
    ):
        require(token in native,
                f"native L8 zero-contribution layout bind lost token {token}")
    require("ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT" in bridge
            and "contributionAllowed ? 1 : 0" in bridge,
            "Java/native L8 contribution bind ABI is not synchronized")

    receiver_context = extract_braced_declaration(native, "MetallumGiReceiverContextV1")
    for token in ("setVertexTexture", "setVertexBuffer", "MetallumGiTransport"):
        require(token in receiver_context, f"native G5 context lost {token}")
    for forbidden in (
            "setFragmentTexture", "setFragmentBuffer", "makeTexture(",
            "MTLTextureDescriptor", "waitUntilCompleted", "getBytes(", "sidecar",
    ):
        require(forbidden.lower() not in receiver_context.lower(),
                f"native G5 context contains forbidden operation {forbidden}")

    native_bindings = {
        "metallumGiReceiverTextureRedV1": 6,
        "metallumGiReceiverTextureGreenV1": 7,
        "metallumGiReceiverTextureBlueV1": 8,
        "metallumGiReceiverTextureConfidenceV1": 9,
        "metallumGiReceiverParamsBufferV1": 25,
    }
    for name, slot in native_bindings.items():
        require(re.search(rf"\b{name}\s*=\s*{slot}\b", native) is not None,
                f"native G5 binding {name} is not slot {slot}")
    for texture, binding_name in (
            ("shRed", "metallumGiReceiverTextureRedV1"),
            ("shGreen", "metallumGiReceiverTextureGreenV1"),
            ("shBlue", "metallumGiReceiverTextureBlueV1"),
            ("confidence", "metallumGiReceiverTextureConfidenceV1"),
    ):
        require(re.search(
            rf"setVertexTexture\(\s*{texture}\s*,\s*index:\s*{binding_name}\s*\)",
            receiver_context,
        ) is not None, f"native G5 context does not bind {texture} at its fixed slot")
    require(re.search(
        r"setVertexBuffer\([^\n]+index:\s*metallumGiReceiverParamsBufferV1\s*\)",
        receiver_context,
    ) is not None, "native G5 context does not bind params buffer slot 25")

    metal_matches = list((root / "src/main/metal").glob("*GiReceiver*.metal"))
    require(not metal_matches,
            "G5 vertex receiver must not add a separate Metal field/pass source")
    transport_metal = text(root, "src/main/metal/MetallumGiTransport.metal")
    for token in (*SHADER_NAMES, "metallum_gi_receiver", "terrain"):
        require(token.lower() not in transport_metal.lower(),
                f"G5 leaked into the field-only G4 transport kernel: {token}")

    native_validation = text(
        root, "src/test/java/com/metallum/client/gi/source/GiTransportGpuValidation.java"
    )
    for token in (
            "GiReceiverGpuResources.validateNativeAbi()",
            "validateReceiverAttachmentLifecycle",
            "G5 did not retain its G4 owner or exact-zero binding census",
            "released G5 native capability remained usable",
    ):
        require(token in native_validation,
                f"G5 native lifecycle validation lost token {token}")


def safe_artifact(root: Path, relative: Any, expected_hash: Any, label: str) -> Path:
    require(isinstance(relative, str) and relative != "", f"{label} path is missing")
    digest = hex_digest(expected_hash, 64, f"{label} SHA-256")
    path = (root / relative).resolve()
    try:
        path.relative_to(root)
    except ValueError as error:
        raise ContractError(f"{label} escapes the repository: {relative}") from error
    require(path.is_file(), f"{label} is missing: {relative}")
    require(sha256(path) == digest, f"{label} digest differs: {relative}")
    return path


def register_artifact_identity(
        path: Path,
        digest: str,
        label: str,
        seen_paths: dict[Path, str],
        seen_hashes: dict[str, str],
) -> None:
    previous_path = seen_paths.get(path)
    require(previous_path is None,
            f"{label} reuses the artifact path already bound by {previous_path}: {path}")
    previous_hash = seen_hashes.get(digest)
    require(previous_hash is None,
            f"{label} reuses the artifact SHA-256 already bound by {previous_hash}: {digest}")
    seen_paths[path] = label
    seen_hashes[digest] = label


def parse_g5_admission(
        log_text: str,
        label: str,
        expected_arm: str,
        expected_allocated_bytes: int,
) -> dict[str, str]:
    admissions = []
    for line in log_text.splitlines():
        marker = line.find(G5_ADMISSION_PREFIX)
        if marker >= 0:
            admissions.append(line[marker:].strip())
    require(len(admissions) == 1,
            f"{label} must contain exactly one G5 admission (found {len(admissions)})")

    fields: dict[str, str] = {}
    for token in admissions[0][len(G5_ADMISSION_PREFIX):].split():
        require("=" in token, f"{label} G5 admission contains a malformed token: {token}")
        key, value = token.split("=", 1)
        require(key != "" and value != "",
                f"{label} G5 admission contains an empty key/value: {token}")
        require(key not in fields, f"{label} G5 admission repeats field {key}")
        fields[key] = value

    expected_keys = {
        "requested", "resolved", "contract", "state", "arm", "field", "phase",
        "presented_frame", "resources", "bindings", "allocated_bytes",
        "g4_accounted_bytes", "combined_accounted_bytes", "cap_bytes",
        "shared_texture_bytes", "carrier_skips", "g5_carrier_writes",
        "drawn_g5_carrier_slices", "status",
        "vertex_only", "fragment_texture3d", "sidecar_bytes",
    }
    require(set(fields) == expected_keys,
            f"{label} G5 admission fields differ: "
            f"missing={sorted(expected_keys - set(fields))}, "
            f"extra={sorted(set(fields) - expected_keys)}")
    expected_fields = {
        "requested": "g5_vertex_receiver",
        "resolved": "g5_vertex_receiver",
        "contract": "4",
        "state": "READY",
        "arm": expected_arm,
        "field": "zero",
        "phase": "WARMUP",
        "resources": "5",
        "bindings": "5",
        "g4_accounted_bytes": str(G4_ACCOUNTED_BYTES),
        "cap_bytes": str(GI_BUDGET_BYTES),
        "shared_texture_bytes": "0",
        "carrier_skips": "0",
        "status": "PASS",
        "vertex_only": "true",
        "fragment_texture3d": "0",
        "sidecar_bytes": "0",
    }
    for key, expected in expected_fields.items():
        require(fields[key] == expected,
                f"{label} G5 admission {key} must be {expected}, got {fields[key]}")
    for key in ("presented_frame", "allocated_bytes", "combined_accounted_bytes"):
        require(re.fullmatch(r"[0-9]+", fields[key]) is not None,
                f"{label} G5 admission {key} is not an unsigned integer")
    require(re.fullmatch(r"[1-9][0-9]*", fields["g5_carrier_writes"]) is not None,
            f"{label} G5 admission g5_carrier_writes is not a positive integer")
    require(re.fullmatch(r"[1-9][0-9]*", fields["drawn_g5_carrier_slices"]) is not None,
            f"{label} G5 admission drawn_g5_carrier_slices is not a positive integer")
    allocated_bytes = int(fields["allocated_bytes"])
    require(allocated_bytes == expected_allocated_bytes,
            f"{label} G5 admission allocated_bytes={allocated_bytes} differs from "
            f"manifest G5 bytes={expected_allocated_bytes}")
    combined_bytes = int(fields["combined_accounted_bytes"])
    require(combined_bytes == G4_ACCOUNTED_BYTES + allocated_bytes,
            f"{label} G5 admission combined_accounted_bytes is inconsistent")
    require(combined_bytes <= GI_BUDGET_BYTES,
            f"{label} G5 admission exceeds the diffuse-GI cap")
    return fields


def parse_g5_final(log_text: str, label: str, expected_arm: str) -> dict[str, str]:
    receipts = []
    for line in log_text.splitlines():
        marker = line.find(G5_FINAL_PREFIX)
        if marker >= 0:
            receipts.append(line[marker:].strip())
    require(len(receipts) == 1,
            f"{label} must contain exactly one G5 final receipt (found {len(receipts)})")

    fields: dict[str, str] = {}
    for token in receipts[0][len(G5_FINAL_PREFIX):].split():
        require("=" in token, f"{label} G5 final receipt has malformed token: {token}")
        key, value = token.split("=", 1)
        require(key != "" and value != "",
                f"{label} G5 final receipt has an empty key/value: {token}")
        require(key not in fields, f"{label} G5 final receipt repeats field {key}")
        fields[key] = value

    expected = {
        "state": "READY",
        "carrier_skips": "0",
        "status": "PASS",
        "arm": expected_arm,
        "field": "g4" if expected_arm == "field" else "zero",
        "contract": "4",
    }
    expected_keys = set(expected) | {
        "g5_carrier_writes", "drawn_g5_carrier_slices",
    }
    require(set(fields) == expected_keys,
            f"{label} G5 final receipt fields differ: "
            f"missing={sorted(expected_keys - set(fields))}, "
            f"extra={sorted(set(fields) - expected_keys)}")
    for key, value in expected.items():
        require(fields[key] == value,
                f"{label} G5 final receipt {key} must be {value}, got {fields[key]}")
    require(re.fullmatch(r"[1-9][0-9]*", fields["g5_carrier_writes"]) is not None,
            f"{label} G5 final receipt g5_carrier_writes is not a positive integer")
    require(re.fullmatch(r"[1-9][0-9]*", fields["drawn_g5_carrier_slices"]) is not None,
            f"{label} G5 final receipt drawn_g5_carrier_slices is not a positive integer")
    return fields


def verify_runtime_log(
        log_text: str,
        label: str,
        expected_arm: str,
        expected_allocated_bytes: int,
) -> None:
    require(log_text.strip() != "", f"{label} is empty")
    for token in (
            "METALLUM_BENCHMARK EVENT=FAIL",
            "Metal command buffer failed",
            "GPU timing sample invalid",
            "BUILD FAILED",
    ):
        require(token not in log_text, f"{label} contains failure token {token}")

    advanced_pattern = re.compile(
        r"METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION "
        r"expected=advanced schema=5 defaults_used=false requested=advanced "
        r"resolved=advanced l3=true l5=true l6=true status=PASS "
        r"generation=[1-9][0-9]* shader_epoch=[1-9][0-9]* reason=none(?=\r?$)",
        re.MULTILINE,
    )
    require(log_text.count(ADVANCED_ADMISSION_PREFIX) == 1
            and len(advanced_pattern.findall(log_text)) == 1,
            f"{label} must contain one exact Advanced admission PASS")

    segment = (
        "METALLUM_BENCHMARK EVENT=SEGMENT_START index=1 total=1 mode=OFF "
        "warmup=600 measure=600"
    )
    measure = (
        "METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=OFF "
        "presented_frame=600"
    )
    measure_end = (
        "METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode=OFF "
        "presented_frame=1200"
    )
    complete = (
        "METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=600 "
        "framebuffer=3024x1964"
    )
    for token, marker in (
            (segment, "SEGMENT_START"),
            (measure, "MEASURE_START"),
            (measure_end, "MEASURE_END"),
            (complete, "COMPLETE"),
    ):
        require(log_text.count(token) == 1,
                f"{label} must contain one exact {marker} marker")

    parse_g5_admission(log_text, label, expected_arm, expected_allocated_bytes)
    parse_g5_final(log_text, label, expected_arm)
    require(
        log_text.index(ADVANCED_ADMISSION_PREFIX)
        < log_text.index(segment)
        < log_text.index(G5_ADMISSION_PREFIX)
        < log_text.index(measure)
        < log_text.index(measure_end)
        < log_text.index(G5_FINAL_PREFIX)
        < log_text.index(complete),
        f"{label} admission/measurement/COMPLETE markers are out of order",
    )


def verify_transcript_receipt(transcript_text: str, label: str, expected_arm: str) -> None:
    receiver_active = "false" if expected_arm == "control" else "true"
    request = (
        "GI_G5_RECEIVER_REQUEST mode=g5_vertex_receiver "
        f"arm={expected_arm} receiver={receiver_active} field=zero "
        "g2_resources=true g3_resources=true shared_g4_resources=true "
        "explicit_g4_request=false vertex_stage=true fragment_receiver=false "
        "diagnostic_only=true release=false status=REQUESTED"
    )
    validated = (
        "Benchmark validated: COMPLETE present, no FAIL/screenshots, "
        "dropped timing events = 0"
    )
    for token, marker in ((request, "G5 request"), (validated, "validated COMPLETE")):
        require(transcript_text.count(token) == 1,
                f"{label} must contain one exact {marker} marker")
    require(transcript_text.index(request) < transcript_text.index(validated),
            f"{label} validates COMPLETE before its G5 request")


def recompute_summary(
        root: Path,
        raw_path: Path,
        implementation_commit: str,
        *,
        self_test_reporter_source: str | None = None,
) -> dict[str, Any]:
    if self_test_reporter_source is None:
        reporter = subprocess.run(
            [
                "git", "-c", "core.fsmonitor=false", "show",
                f"{implementation_commit}:tools/metal_benchmark_report.py",
            ],
            cwd=root,
            text=True,
            capture_output=True,
            check=False,
        )
        if reporter.returncode != 0:
            detail = reporter.stderr.strip() or reporter.stdout.strip()
            raise ContractError(
                "G5 implementation-commit canonical reporter is unavailable: " + detail
            )
        reporter_source = reporter.stdout
    else:
        require(self_test_reporter_source.strip() != "",
                "G5 self-test reporter source is empty")
        reporter_source = self_test_reporter_source
    command = [
        sys.executable,
        "-",
        "summarize",
        str(raw_path),
        "--measure-frames", "600",
        "--segment", "0",
        "--scaler-mode", "OFF",
        "--json",
    ]
    try:
        result = subprocess.run(
            command,
            cwd=root,
            input=reporter_source,
            text=True,
            capture_output=True,
            check=False,
            timeout=60,
        )
    except subprocess.TimeoutExpired as error:
        raise ContractError("G5 canonical reporter timed out") from error
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip()
        raise ContractError(f"G5 raw report fails the canonical parser: {detail}")
    value = strict_json_text(result.stdout, f"canonical G5 summary for {raw_path}")
    require(isinstance(value, dict), "canonical G5 reporter did not return an object")
    return value


def compare_canonical_summary(
        supplied: dict[str, Any],
        recomputed: dict[str, Any],
        raw_path: Path,
        label: str,
) -> None:
    supplied_report = supplied.get("report")
    recomputed_report = recomputed.get("report")
    require(isinstance(supplied_report, str)
            and Path(supplied_report).is_absolute()
            and Path(supplied_report).name == raw_path.name,
            f"{label} does not identify the exact raw artifact by an absolute path")
    require(isinstance(recomputed_report, str)
            and Path(recomputed_report).resolve() == raw_path.resolve(),
            f"{label} canonical reporter resolved a different raw artifact")
    normalized_supplied = copy.deepcopy(supplied)
    normalized_recomputed = copy.deepcopy(recomputed)
    # The evidence bundle may be relocated after capture.  This is the only
    # allowed normalization; every metric, metadata leaf and contract field
    # remains byte-for-byte equal as a parsed JSON value.
    normalized_supplied["report"] = "<verified-raw>"
    normalized_recomputed["report"] = "<verified-raw>"
    require(normalized_supplied == normalized_recomputed,
            f"{label} differs from implementation-commit canonical recomputation")


def verify_summary_profile(
        summary: dict[str, Any],
        implementation: dict[str, Any],
        label: str,
) -> dict[str, Any]:
    require(summary.get("presented_frames") == 600
            and summary.get("window_count") == 2,
            f"{label} is not two measured 300-frame windows")
    require(summary.get("schema_versions") == [6],
            f"{label} is not strict raw-report schema v6")
    require(summary.get("detail_enabled") is True,
            f"{label} lacks detailed WORLD_OPAQUE timing")
    require(summary.get("metal_validation_contract") is False,
            f"{label} is a Metal-validation run, not Tier B screening")
    require(summary.get("dropped_timing_events") == 0,
            f"{label} contains timing drops")
    selection = summary.get("selection")
    require(isinstance(selection, str)
            and "phase=measure segment=0 scaler=OFF" in selection,
            f"{label} selected a different measurement segment")

    metadata = summary.get("metadata")
    require(isinstance(metadata, dict), f"{label} metadata is missing")
    expected_metadata = {
        "commit": implementation["commit"][:12],
        "source_sha256": implementation["source_sha256"],
        "artifact_sha256": implementation["artifact_sha256"],
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
        "global_illumination_mode": "g5_vertex_receiver",
        "graphics_preset": "fancy",
        "benchmark_simulation_frozen": True,
        "native_shader_library_mode": "PRECOMPILED",
        "native_shader_source_compile_count": 0,
        "native_pipeline_failure_count": 0,
        "dirty_worktree": False,
        "route": "gi-g4-overworld-v1",
        "route_sha256": G5_ROUTE_SHA256,
        "fixture": "hdrtest-static-v1",
        "fixture_sha256": G5_FIXTURE_SHA256,
        "settings_id": "native-hdr-fancy-v1",
        "settings_spec_sha256": G5_SETTINGS_SPEC_SHA256,
        "settings_sha256": G5_SETTINGS_SHA256,
    }
    for key, expected in expected_metadata.items():
        require(metadata.get(key) == expected,
                f"{label} metadata.{key} differs: {metadata.get(key)!r} != {expected!r}")

    generation = summary.get("renderer_generation")
    require(isinstance(generation, dict), f"{label} renderer generation is missing")
    expected_generation = {
        "resolved_render_contract": "metallum",
        "resolved_lighting_model": "advanced",
        "resolved_output_mode": "hdr",
        "resolved_upscale_mode": "native",
        "resolved_interpolation_mode": "off",
        "lighting_preset": "balanced",
        "executor": "metal3",
        "render_width": 3024,
        "render_height": 1964,
        "display_width": 3024,
        "display_height": 1964,
    }
    for key, expected in expected_generation.items():
        require(generation.get(key) == expected,
                f"{label} renderer_generation.{key} differs")

    thermal = summary.get("thermal")
    require(isinstance(thermal, dict)
            and thermal.get("thermal_invalid") is False
            and thermal.get("has_serious") is False
            and thermal.get("has_critical") is False,
            f"{label} contains invalid thermal state")
    gi = summary.get("global_illumination")
    require(isinstance(gi, dict), f"{label} GI telemetry is missing")
    require(gi.get("mode") == "active" and gi.get("contract_version") == 3,
            f"{label} does not reuse the active G4 telemetry contract v3")
    fallbacks = gi.get("fallback_reasons")
    require(isinstance(fallbacks, dict) and fallbacks
            and all(type(value) is int and value == 0 for value in fallbacks.values()),
            f"{label} contains a GI fallback")

    stages = summary.get("stages")
    require(isinstance(stages, dict)
            and isinstance(stages.get("world opaque"), dict),
            f"{label} has no WORLD_OPAQUE stage")
    require(stages["world opaque"].get("frames") == 600,
            f"{label} WORLD_OPAQUE does not cover all measured frames")
    world_p95 = weighted_metric(
        stages["world opaque"].get("p95_ms"),
        f"{label} WORLD_OPAQUE p95",
    )
    whole = summary.get("presenting_command_buffer_gpu_ms")
    require(isinstance(whole, dict)
            and isinstance(whole.get("percentile_window_summaries"), dict),
            f"{label} whole-GPU summary is missing")
    whole_p95 = weighted_metric(
        whole["percentile_window_summaries"].get("p95"),
        f"{label} whole-GPU p95",
    )
    require(world_p95 >= 0.0 and whole_p95 > 0.0,
            f"{label} canonical timing is not positive")
    return {
        "world_opaque_p95_ms": world_p95,
        "whole_gpu_p95_ms": whole_p95,
        "identity": {
            key: metadata[key]
            for key in (
                "commit", "source_sha256", "artifact_sha256", "device_name", "monitor",
                "render_width", "render_height", "display_width", "display_height",
                "route", "route_sha256", "fixture", "fixture_sha256", "settings_id",
                "settings_spec_sha256", "settings_sha256", "native_shader_library_mode",
            )
        },
    }


def expect_contract_error(action: Callable[[], None], label: str) -> None:
    try:
        action()
    except ContractError:
        return
    raise ContractError(f"G5 self-test did not reject {label}")


def run_self_test() -> None:
    # Exhaust the complete four-bit carrier namespace. The only admitted values
    # are empty, L8 dominant-face 1..6, and exact G5 axis-face 9..14.
    base_hi = 0x1234_5678
    base_lo = 0x2ABC_DEF0
    for carrier_code in range(16):
        if carrier_code not in VALID_POSITION_CARRIER_CODES:
            expect_contract_error(
                lambda code=carrier_code: encode_position_carrier_words(
                    base_hi, base_lo, code
                ),
                f"reserved position-carrier code {carrier_code}",
            )
            continue
        encoded_hi, encoded_lo = encode_position_carrier_words(
            base_hi, base_lo, carrier_code
        )
        require(decode_position_carrier_words(encoded_hi, encoded_lo) == carrier_code,
                f"G5 self-test did not round-trip carrier code {carrier_code}")
        require((encoded_hi & POSITION_PAYLOAD_MASK) == base_hi
                and (encoded_lo & POSITION_PAYLOAD_MASK) == base_lo,
                f"G5 self-test changed Sodium payload for carrier code {carrier_code}")
        require((encoded_hi >> 30) == (carrier_code & 0x3)
                and (encoded_lo >> 30) == ((carrier_code >> 2) & 0x3),
                f"G5 self-test changed the 2+2 top-bit split for code {carrier_code}")
    for face in range(1, 7):
        require(face in VALID_POSITION_CARRIER_CODES
                and (face & POSITION_G5_BIT) == 0,
                f"G5 self-test mislabeled L8 dominant face {face} as exact")
        exact_code = POSITION_G5_BIT | face
        require(exact_code in VALID_POSITION_CARRIER_CODES
                and (exact_code & POSITION_FACE_MASK) == face,
                f"G5 self-test lost exact G5 axis face {face}")
    for foreign_hi, foreign_lo in (
            (0x4000_0000, 0), (0x8000_0000, 0),
            (0, 0x4000_0000), (0, 0x8000_0000),
    ):
        expect_contract_error(
            lambda hi=foreign_hi, lo=foreign_lo: encode_position_carrier_words(
                hi, lo, POSITION_G5_BIT | 1
            ),
            "a foreign Sodium position top-bit owner",
        )

    seen_paths: dict[Path, str] = {}
    seen_hashes: dict[str, str] = {}
    for index in range(4):
        register_artifact_identity(
            Path(f"/g5-self-test/run-{index}.raw.jsonl"),
            f"{index + 1:064x}",
            f"G5 self-test run {index} raw",
            seen_paths,
            seen_hashes,
        )
    expect_contract_error(
        lambda: register_artifact_identity(
            Path("/g5-self-test/run-0.raw.jsonl"),
            f"{10:064x}",
            "G5 self-test duplicate path",
            seen_paths,
            seen_hashes,
        ),
        "a duplicate ABBA artifact path",
    )
    expect_contract_error(
        lambda: register_artifact_identity(
            Path("/g5-self-test/new.raw.jsonl"),
            f"{1:064x}",
            "G5 self-test duplicate hash",
            seen_paths,
            seen_hashes,
        ),
        "a duplicate ABBA artifact SHA-256",
    )

    admission = (
        G5_ADMISSION_PREFIX
        + "requested=g5_vertex_receiver resolved=g5_vertex_receiver contract=4 "
        + "state=READY arm=candidate field=zero phase=WARMUP presented_frame=1 "
        + "resources=5 bindings=5 allocated_bytes=66560 "
        + f"g4_accounted_bytes={G4_ACCOUNTED_BYTES} "
        + f"combined_accounted_bytes={G4_ACCOUNTED_BYTES + 66560} "
        + f"cap_bytes={GI_BUDGET_BYTES} shared_texture_bytes=0 "
        + "carrier_skips=0 g5_carrier_writes=64 drawn_g5_carrier_slices=12 "
        + "status=PASS vertex_only=true "
        + "fragment_texture3d=0 sidecar_bytes=0"
    )
    parse_g5_admission(admission, "G5 self-test admission", "candidate", 66560)
    expect_contract_error(
        lambda: parse_g5_admission(
            admission.replace("g5_carrier_writes=64", "g5_carrier_writes=0"),
            "G5 self-test zero admission carrier writes",
            "candidate",
            66560,
        ),
        "a G5 admission with zero carrier writes",
    )
    expect_contract_error(
        lambda: parse_g5_admission(
            admission.replace(" g5_carrier_writes=64", ""),
            "G5 self-test missing admission carrier writes",
            "candidate",
            66560,
        ),
        "a G5 admission without carrier writes",
    )
    expect_contract_error(
        lambda: parse_g5_admission(
            admission.replace(
                "drawn_g5_carrier_slices=12", "drawn_g5_carrier_slices=0"
            ),
            "G5 self-test zero admission drawn slices",
            "candidate",
            66560,
        ),
        "a G5 admission with zero drawn carrier slices",
    )
    expect_contract_error(
        lambda: parse_g5_admission(
            admission.replace(" drawn_g5_carrier_slices=12", ""),
            "G5 self-test missing admission drawn slices",
            "candidate",
            66560,
        ),
        "a G5 admission without drawn carrier slices",
    )
    expect_contract_error(
        lambda: parse_g5_admission(
            admission + "\n" + admission,
            "G5 self-test duplicate admission",
            "candidate",
            66560,
        ),
        "duplicate G5 admissions in one receipt log",
    )

    advanced = (
        ADVANCED_ADMISSION_PREFIX
        + "expected=advanced schema=5 defaults_used=false requested=advanced "
        + "resolved=advanced l3=true l5=true l6=true status=PASS "
        + "generation=1 shader_epoch=1 reason=none"
    )
    segment = (
        "METALLUM_BENCHMARK EVENT=SEGMENT_START index=1 total=1 mode=OFF "
        "warmup=600 measure=600"
    )
    measure = (
        "METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=OFF presented_frame=600"
    )
    measure_end = (
        "METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode=OFF presented_frame=1200"
    )
    final_receipt = (
        G5_FINAL_PREFIX
        + "state=READY carrier_skips=0 g5_carrier_writes=4096 "
        + "drawn_g5_carrier_slices=256 status=PASS "
        + "arm=candidate field=zero contract=4"
    )
    complete = (
        "METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=600 "
        "framebuffer=3024x1964"
    )
    runtime_log = "\n".join(
        (advanced, segment, admission, measure, measure_end, final_receipt, complete)
    )
    verify_runtime_log(
        runtime_log, "G5 self-test runtime log", "candidate", 66560
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace(complete, ""),
            "G5 self-test missing COMPLETE", "candidate", 66560,
        ),
        "a runtime log without COMPLETE",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace(final_receipt, ""),
            "G5 self-test missing final receipt", "candidate", 66560,
        ),
        "a runtime log without a final G5 receipt",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace(
                final_receipt,
                final_receipt.replace("carrier_skips=0", "carrier_skips=1"),
            ),
            "G5 self-test final carrier skip", "candidate", 66560,
        ),
        "a final G5 receipt with a carrier skip",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace("g5_carrier_writes=4096", "g5_carrier_writes=0"),
            "G5 self-test zero final carrier writes", "candidate", 66560,
        ),
        "a final G5 receipt with zero carrier writes",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace(" g5_carrier_writes=4096", ""),
            "G5 self-test missing final carrier writes", "candidate", 66560,
        ),
        "a final G5 receipt without carrier writes",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace(
                "drawn_g5_carrier_slices=256", "drawn_g5_carrier_slices=0"
            ),
            "G5 self-test zero final drawn slices", "candidate", 66560,
        ),
        "a final G5 receipt with zero drawn carrier slices",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace(" drawn_g5_carrier_slices=256", ""),
            "G5 self-test missing final drawn slices", "candidate", 66560,
        ),
        "a final G5 receipt without drawn carrier slices",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log.replace("resolved=advanced", "resolved=vanilla"),
            "G5 self-test fallback admission", "candidate", 66560,
        ),
        "a non-Advanced renderer admission",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            runtime_log + "\nMETALLUM_BENCHMARK EVENT=FAIL reason=self-test",
            "G5 self-test failure log", "candidate", 66560,
        ),
        "a runtime failure marker",
    )
    expect_contract_error(
        lambda: verify_runtime_log(
            "\n".join(
                (advanced, segment, measure, admission, measure_end,
                 final_receipt, complete)
            ),
            "G5 self-test late admission", "candidate", 66560,
        ),
        "a G5 admission after measurement begins",
    )

    raw_path = Path("/g5-self-test/run.raw.jsonl")
    supplied = {
        "report": "/captured/location/run.raw.jsonl",
        "metric": {"p95": 1.25},
        "metadata": {"source_sha256": "a" * 64},
    }
    canonical = copy.deepcopy(supplied)
    canonical["report"] = str(raw_path)
    compare_canonical_summary(supplied, canonical, raw_path, "G5 self-test summary")
    wrong_metric = copy.deepcopy(supplied)
    wrong_metric["metric"]["p95"] = 1.26
    expect_contract_error(
        lambda: compare_canonical_summary(
            wrong_metric, canonical, raw_path, "G5 self-test tampered metric"
        ),
        "a handwritten summary metric",
    )
    wrong_metadata = copy.deepcopy(supplied)
    wrong_metadata["metadata"]["source_sha256"] = "b" * 64
    expect_contract_error(
        lambda: compare_canonical_summary(
            wrong_metadata, canonical, raw_path, "G5 self-test tampered metadata"
        ),
        "handwritten summary metadata",
    )
    wrong_report = copy.deepcopy(supplied)
    wrong_report["report"] = "/captured/location/different.raw.jsonl"
    expect_contract_error(
        lambda: compare_canonical_summary(
            wrong_report, canonical, raw_path, "G5 self-test wrong raw binding"
        ),
        "a summary bound to another raw artifact",
    )
    relative_report = copy.deepcopy(supplied)
    relative_report["report"] = raw_path.name
    expect_contract_error(
        lambda: compare_canonical_summary(
            relative_report, canonical, raw_path, "G5 self-test relative raw binding"
        ),
        "a summary with a non-absolute raw path",
    )

    # Exercise the same subprocess recomputation path used for real evidence.
    # A final G5 implementation commit does not exist while this working-tree
    # self-test is running, so only this synthetic branch injects the current
    # reporter source. verify_run_artifacts() never supplies this override and
    # therefore always loads the reporter from evidence.implementation.commit.
    repository_root = Path(__file__).resolve().parents[1]
    historical_manifest = strict_object(repository_root / G4_EVIDENCE_RELATIVE_PATH)
    historical_raw = historical_manifest["tier_b"]["artifacts"]["raw"]
    historical_raw_path = safe_artifact(
        repository_root,
        historical_raw["path"],
        historical_raw["sha256"],
        "G5 self-test historical G4 raw",
    )
    g5_raw_windows = strict_json_lines(historical_raw_path)
    zero_field_keys = (
        "valid_probes", "unknown_probes", "dirty_queued_total",
        "dirty_completed_total", "dirty_discarded_total", "dirty_pending",
        "injection_dispatches", "transport_dispatches", "source_epoch",
        "probe_epoch", "field_epoch", "stale_cell_rejects",
        "full_volume_rebuilds",
    )
    for window in g5_raw_windows:
        metadata = window.get("metadata")
        gi = window.get("global_illumination")
        stages = window.get("stages")
        require(isinstance(metadata, dict) and isinstance(gi, dict)
                and isinstance(stages, dict),
                "G5 self-test source raw lost schema-v6 telemetry")
        metadata["global_illumination_mode"] = "g5_vertex_receiver"
        for key in zero_field_keys:
            gi[key] = 0
        stages["GI_INJECT"] = None
        stages["GI_TRANSPORT"] = None
    reporter_source = text(repository_root, "tools/metal_benchmark_report.py")
    with tempfile.TemporaryDirectory(prefix="metallum-g5-contract-") as directory:
        synthetic_raw = Path(directory) / "g5-candidate.raw.jsonl"
        synthetic_raw.write_text(
            "\n".join(
                json.dumps(window, separators=(",", ":"), sort_keys=True)
                for window in g5_raw_windows
            ) + "\n",
            encoding="utf-8",
        )
        synthetic_summary = recompute_summary(
            repository_root,
            synthetic_raw,
            "WORKTREE_SELF_TEST",
            self_test_reporter_source=reporter_source,
        )
        relocated_summary = copy.deepcopy(synthetic_summary)
        relocated_summary["report"] = "/relocated/g5-candidate.raw.jsonl"
        compare_canonical_summary(
            relocated_summary,
            synthetic_summary,
            synthetic_raw,
            "G5 self-test current-tree canonical summary",
        )
        synthetic_metadata = synthetic_summary.get("metadata")
        require(isinstance(synthetic_metadata, dict),
                "G5 self-test canonical metadata is missing")
        synthetic_implementation = {
            "commit": synthetic_metadata["commit"] + "0" * 28,
            "source_sha256": synthetic_metadata["source_sha256"],
            "artifact_sha256": synthetic_metadata["artifact_sha256"],
        }
        verify_summary_profile(
            synthetic_summary,
            synthetic_implementation,
            "G5 self-test current-tree canonical profile",
        )

        invalid_raw_windows = copy.deepcopy(g5_raw_windows)
        invalid_raw_windows[0]["metadata"]["global_illumination_mode"] = \
            "g5_receiver_typo"
        invalid_raw = Path(directory) / "g5-invalid-mode.raw.jsonl"
        invalid_raw.write_text(
            "\n".join(
                json.dumps(window, separators=(",", ":"), sort_keys=True)
                for window in invalid_raw_windows
            ) + "\n",
            encoding="utf-8",
        )
        expect_contract_error(
            lambda: recompute_summary(
                repository_root,
                invalid_raw,
                "WORKTREE_SELF_TEST",
                self_test_reporter_source=reporter_source,
            ),
            "a G5 raw receipt with an unrecognized GI metadata mode",
        )
    expect_contract_error(
        lambda: strict_json_text('{"a":1,"a":2}', "G5 self-test duplicate JSON"),
        "a duplicate raw JSON key",
    )
    expect_contract_error(
        lambda: strict_json_text('{"value":NaN}', "G5 self-test NaN JSON"),
        "a non-finite raw JSON value",
    )

    implementation = {
        "commit": "1" * 40,
        "source_sha256": "2" * 64,
        "artifact_sha256": "3" * 64,
    }
    profile = {
        "report": "/g5-self-test/run.raw.jsonl",
        "selection": "phase=measure segment=0 scaler=OFF frames=600",
        "window_count": 2,
        "presented_frames": 600,
        "schema_versions": [6],
        "detail_enabled": True,
        "metal_validation_contract": False,
        "dropped_timing_events": 0,
        "metadata": {
            "commit": "1" * 12,
            "source_sha256": "2" * 64,
            "artifact_sha256": "3" * 64,
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
            "global_illumination_mode": "g5_vertex_receiver",
            "graphics_preset": "fancy",
            "benchmark_simulation_frozen": True,
            "native_shader_library_mode": "PRECOMPILED",
            "native_shader_source_compile_count": 0,
            "native_pipeline_failure_count": 0,
            "dirty_worktree": False,
            "route": "gi-g4-overworld-v1",
            "route_sha256": G5_ROUTE_SHA256,
            "fixture": "hdrtest-static-v1",
            "fixture_sha256": G5_FIXTURE_SHA256,
            "settings_id": "native-hdr-fancy-v1",
            "settings_spec_sha256": G5_SETTINGS_SPEC_SHA256,
            "settings_sha256": G5_SETTINGS_SHA256,
        },
        "renderer_generation": {
            "resolved_render_contract": "metallum",
            "resolved_lighting_model": "advanced",
            "resolved_output_mode": "hdr",
            "resolved_upscale_mode": "native",
            "resolved_interpolation_mode": "off",
            "lighting_preset": "balanced",
            "executor": "metal3",
            "render_width": 3024,
            "render_height": 1964,
            "display_width": 3024,
            "display_height": 1964,
        },
        "thermal": {
            "thermal_invalid": False,
            "has_serious": False,
            "has_critical": False,
        },
        "global_illumination": {
            "mode": "active",
            "contract_version": 3,
            "fallback_reasons": {"native_failure": 0, "unavailable": 0},
        },
        "stages": {
            "world opaque": {
                "frames": 600,
                "p95_ms": {"window_frame_weighted_mean": 4.0},
            },
        },
        "presenting_command_buffer_gpu_ms": {
            "percentile_window_summaries": {
                "p95": {"window_frame_weighted_mean": 20.0},
            },
        },
    }
    verify_summary_profile(profile, implementation, "G5 self-test canonical profile")
    fallback_profile = copy.deepcopy(profile)
    fallback_profile["global_illumination"]["fallback_reasons"]["native_failure"] = 1
    expect_contract_error(
        lambda: verify_summary_profile(
            fallback_profile, implementation, "G5 self-test GI fallback profile"
        ),
        "a canonical summary with a GI fallback",
    )
    obsolete_gi_profile = copy.deepcopy(profile)
    obsolete_gi_profile["global_illumination"]["contract_version"] = 2
    expect_contract_error(
        lambda: verify_summary_profile(
            obsolete_gi_profile,
            implementation,
            "G5 self-test obsolete GI profile",
        ),
        "a canonical summary without reused G4 telemetry contract v3",
    )
    pipeline_failure_profile = copy.deepcopy(profile)
    pipeline_failure_profile["metadata"]["native_pipeline_failure_count"] = 1
    expect_contract_error(
        lambda: verify_summary_profile(
            pipeline_failure_profile,
            implementation,
            "G5 self-test pipeline failure profile",
        ),
        "a canonical summary with a native pipeline failure",
    )
    vanilla_profile = copy.deepcopy(profile)
    vanilla_profile["renderer_generation"]["resolved_lighting_model"] = "vanilla"
    expect_contract_error(
        lambda: verify_summary_profile(
            vanilla_profile, implementation, "G5 self-test Vanilla profile"
        ),
        "a canonical summary with Vanilla lighting",
    )


def weighted_metric(value: Any, label: str) -> float:
    if isinstance(value, dict):
        value = value.get("window_frame_weighted_mean")
    return finite_number(value, label)


def verify_run_artifacts(
        root: Path,
        run: dict[str, Any],
        index: int,
        implementation: dict[str, Any],
        expected_arm: str,
        expected_allocated_bytes: int,
        seen_paths: dict[Path, str],
        seen_hashes: dict[str, str],
) -> dict[str, Any]:
    artifacts = run.get("artifacts")
    require(isinstance(artifacts, dict), f"G5 run {index} artifacts are missing")
    expected_artifact_keys = set(RUN_ARTIFACT_NAMES) | {
        f"{name}_sha256" for name in RUN_ARTIFACT_NAMES
    }
    require(set(artifacts) == expected_artifact_keys,
            f"G5 run {index} artifact manifest fields differ")
    resolved: dict[str, Path] = {}
    for name in RUN_ARTIFACT_NAMES:
        resolved[name] = safe_artifact(
            root, artifacts.get(name), artifacts.get(f"{name}_sha256"),
            f"G5 run {index} {name}",
        )
        register_artifact_identity(
            resolved[name],
            artifacts[f"{name}_sha256"],
            f"G5 run {index} {name}",
            seen_paths,
            seen_hashes,
        )

    raw_path = resolved["raw"]
    require(raw_path.name.endswith(".raw.jsonl"),
            f"G5 run {index} raw artifact suffix differs")
    stem = raw_path.name[:-len(".raw.jsonl")]
    expected_siblings = {
        "summary": raw_path.with_name(stem + ".summary.json"),
        "minecraft_log": raw_path.with_name(stem + ".minecraft.log"),
        "console_log": raw_path.with_name(stem + ".console.log"),
        "transcript": raw_path.with_name(stem + ".transcript.log"),
    }
    for name, expected_path in expected_siblings.items():
        require(resolved[name] == expected_path,
                f"G5 run {index} {name} does not share the exact raw artifact stem")

    # The canonical reporter intentionally uses the standard JSON parser.  Parse
    # the complete raw artifact first with duplicate-key and non-finite rejection,
    # then let the attested reporter enforce the timing/window schema.
    strict_json_lines(raw_path)
    for name in ("minecraft_log", "console_log", "transcript"):
        verify_runtime_log(
            resolved[name].read_text(encoding="utf-8"),
            f"G5 run {index} {name}",
            expected_arm,
            expected_allocated_bytes,
        )
    verify_transcript_receipt(
        resolved["transcript"].read_text(encoding="utf-8"),
        f"G5 run {index} transcript",
        expected_arm,
    )

    supplied_summary = strict_object(resolved["summary"])
    canonical_summary = recompute_summary(root, raw_path, implementation["commit"])
    compare_canonical_summary(
        supplied_summary,
        canonical_summary,
        raw_path,
        f"G5 run {index} summary",
    )
    receipt = verify_summary_profile(
        canonical_summary,
        implementation,
        f"G5 run {index} canonical summary",
    )
    require(math.isclose(receipt["world_opaque_p95_ms"],
                         float(run["world_opaque_p95_ms"]),
                         rel_tol=0.0, abs_tol=1e-9),
            f"G5 run {index} WORLD_OPAQUE p95 differs from canonical raw")
    require(math.isclose(receipt["whole_gpu_p95_ms"],
                         float(run["whole_gpu_p95_ms"]),
                         rel_tol=0.0, abs_tol=1e-9),
            f"G5 run {index} whole-GPU p95 differs from canonical raw")
    return receipt


def verify_evidence(root: Path, evidence_path: Path) -> str:
    evidence = strict_object(evidence_path)
    require(evidence.get("schema_version") == 1, "G5 evidence schema differs")
    require(evidence.get("id") == "gi-g5-receiver-evidence-v1", "G5 evidence id differs")
    require(evidence.get("stage") == "G5", "G5 evidence stage differs")

    implementation = evidence.get("implementation")
    require(isinstance(implementation, dict), "G5 implementation identity is missing")
    hex_digest(implementation.get("commit"), 40, "G5 commit")
    hex_digest(implementation.get("source_sha256"), 64, "G5 source SHA-256")
    hex_digest(implementation.get("artifact_sha256"), 64, "G5 artifact SHA-256")

    scope = evidence.get("scope")
    expected_scope = {
        "runtime_environment": "METALLUM_GI_G5_RECEIVER=1",
        "arm_environment": "METALLUM_GI_G5_RECEIVER_ARM",
        "candidate": "vertex_only_reuse_g4",
        "new_3d_textures": 0,
        "sidecar_bytes": 0,
        "fragment_3d_samples": 0,
        "g4_accounted_bytes": G4_ACCOUNTED_BYTES,
        "gi_budget_bytes": GI_BUDGET_BYTES,
    }
    require(scope == expected_scope, "G5 evidence scope widened")

    memory = evidence.get("memory")
    require(isinstance(memory, dict), "G5 memory census is missing")
    g5_bytes = finite_number(memory.get("g5_accounted_bytes"), "G5 accounted bytes")
    combined = finite_number(memory.get("combined_accounted_bytes"), "combined GI bytes")
    require(g5_bytes >= 0 and g5_bytes.is_integer(), "G5 accounted bytes must be integral")
    require(combined == G4_ACCOUNTED_BYTES + g5_bytes,
            "G5 combined memory does not equal G4 base plus G5 accounting")
    require(combined <= GI_BUDGET_BYTES, "G5 combined memory exceeds 24 MiB")

    tier_b = evidence.get("tier_b_dry_abba")
    require(isinstance(tier_b, dict), "G5 Tier B ABBA evidence is missing")
    require(tier_b.get("classification") == "TIER_B_SCREENING_NOT_TIER_C",
            "G5 evidence misclassifies Tier B")
    require(tier_b.get("sequence") == ["control", "candidate", "candidate", "control"],
            "G5 run sequence is not ABBA")
    require(tier_b.get("warmup_frames") == 600 and tier_b.get("measure_frames") == 600,
            "G5 dry runs are not 600+600")
    for key in (
            "source_match", "artifact_match", "fixture_match", "settings_match",
            "shader_library_mode_match", "advanced_admission", "zero_timing_drops",
            "zero_pipeline_failures", "same_shader_flavor", "same_allocations",
    ):
        require(tier_b.get(key) is True, f"G5 Tier B invariant is not proven: {key}")

    runs = tier_b.get("runs")
    require(isinstance(runs, list) and len(runs) == 4, "G5 evidence must bind four runs")
    seen_artifact_paths: dict[Path, str] = {}
    seen_artifact_hashes: dict[str, str] = {}
    receipts: list[dict[str, Any]] = []
    for index, (run, arm) in enumerate(zip(runs, ("control", "candidate", "candidate", "control"))):
        require(isinstance(run, dict) and run.get("arm") == arm,
                f"G5 run {index} arm differs")
        require(run.get("warmup_frames") == 600 and run.get("measured_frames") == 600,
                f"G5 run {index} frame contract differs")
        world_p95 = finite_number(run.get("world_opaque_p95_ms"),
                                  f"G5 run {index} WORLD_OPAQUE p95")
        whole_p95 = finite_number(run.get("whole_gpu_p95_ms"),
                                  f"G5 run {index} whole-GPU p95")
        require(world_p95 >= 0.0 and whole_p95 > 0.0,
                f"G5 run {index} timing is not positive")
        receipts.append(
            verify_run_artifacts(
                root,
                run,
                index,
                implementation,
                arm,
                int(g5_bytes),
                seen_artifact_paths,
                seen_artifact_hashes,
            )
        )

    first_identity = receipts[0]["identity"]
    require(all(receipt["identity"] == first_identity for receipt in receipts[1:]),
            "G5 canonical raw receipts differ in source/artifact/fixture/settings/profile")

    calculated_pairs: list[dict[str, float]] = []
    for candidate_index, control_index in ((1, 0), (2, 3)):
        candidate = receipts[candidate_index]
        control = receipts[control_index]
        world_delta = candidate["world_opaque_p95_ms"] \
            - control["world_opaque_p95_ms"]
        whole_delta_percent = (
            candidate["whole_gpu_p95_ms"] / control["whole_gpu_p95_ms"] - 1.0
        ) * 100.0
        calculated_pairs.append({
            "world_opaque_p95_delta_ms": world_delta,
            "whole_gpu_p95_delta_percent": whole_delta_percent,
        })

    declared_pairs = tier_b.get("pair_deltas")
    require(isinstance(declared_pairs, list) and len(declared_pairs) == 2,
            "G5 evidence must declare two pair deltas")
    for index, (declared, calculated) in enumerate(zip(declared_pairs, calculated_pairs)):
        require(isinstance(declared, dict), f"G5 pair {index} is not an object")
        for key, expected in calculated.items():
            actual = finite_number(declared.get(key), f"G5 pair {index} {key}")
            require(math.isclose(actual, expected, rel_tol=0.0, abs_tol=1e-9),
                    f"G5 pair {index} {key} is not recomputed from raw run values")

    gate_passed = all(
        pair["world_opaque_p95_delta_ms"] <= WORLD_OPAQUE_P95_GATE_MS
        and pair["whole_gpu_p95_delta_percent"] <= WHOLE_GPU_P95_GATE_PERCENT
        for pair in calculated_pairs
    )
    expected_decision = "PASS_RECEIVER_FEASIBILITY" if gate_passed \
        else "REJECT_DRY_PERFORMANCE_GATE"
    require(evidence.get("decision") == expected_decision,
            f"G5 evidence decision must be {expected_decision}")
    require(evidence.get("g6_allowed") is gate_passed,
            "G5 g6_allowed does not match the dry gate")
    return expected_decision


def verify(root: Path, require_evidence: bool) -> None:
    run_self_test()
    verify_document(root)
    verify_historical_g4_evidence(root)
    verify_benchmark_runner(root)
    verify_java_sources(root)
    verify_native_sources(root)

    evidence_path = root / EVIDENCE_RELATIVE_PATH
    if evidence_path.is_file():
        decision = verify_evidence(root, evidence_path)
        print(f"GI G5 receiver/evidence contract passed: {decision}")
        return
    if require_evidence:
        raise ContractError(
            f"real G5 Tier B evidence is required but missing: {EVIDENCE_RELATIVE_PATH}"
        )
    print(
        "GI G5 receiver implementation contract passed: "
        "IMPLEMENTED_PENDING_TIER_B (no live receipt claimed)"
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path("."))
    parser.add_argument(
        "--require-evidence", action="store_true",
        help="fail unless the real four-run G5 Tier B evidence bundle exists",
    )
    parser.add_argument(
        "--self-test", action="store_true",
        help="run carrier, receipt, admission and canonical-evidence negative tests only",
    )
    arguments = parser.parse_args()
    try:
        if arguments.self_test:
            run_self_test()
            print("GI G5 evidence self-test passed")
            return
        verify(arguments.root.resolve(), arguments.require_evidence)
    except (ContractError, OSError, json.JSONDecodeError, KeyError, TypeError,
            ZeroDivisionError) as error:
        raise SystemExit(f"GI G5 contract FAILED: {error}") from error


if __name__ == "__main__":
    main()
