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
# The immutable manifest was assembled after the final G4 debug/admission fixes. Its
# implementation.base_commit records the stage lineage base (G3), not the commit whose tree
# supplied source_files_sha256. Keep that distinction explicit for successor verification.
G4_SOURCE_ATTESTATION_COMMIT = "c7a9a8adc6fb133641b2821c0cea12687dc6d792"

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
    "LOGICAL_ACTUAL_STATIC_EPOCH_DOMAIN_SEPARATION",
    "PRE_ROUTE_MODE_FREEZE",
    "TELEMETRY_ATTACH_WRONG_THREAD_REJECTED",
    "TELEMETRY_ATTACH_FORGED_G3_REJECTED",
    "TELEMETRY_ATTACH_IDEMPOTENT_SAME_PAIR",
    "TELEMETRY_ATTACH_SECOND_G4_REJECTED",
    "TELEMETRY_ATTACH_RELEASED_G3_REJECTED",
    "TELEMETRY_ATTACH_DIFFERENT_QUEUE_REJECTED",
    "TELEMETRY_ATTACH_RETAINS_G3_UNTIL_G4_RELEASE",
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
    "benchmark_runner_contract",
}

REQUIRED_SOURCE_MANIFEST = frozenset({
    "benchmark/routes/gi-g4-overworld-v1.json",
    "build.gradle",
    "docs/GI_G4.md",
    "scripts/run_metal_benchmark.sh",
    "tools/gi_g3_contract.py",
    "tools/gi_g4_contract.py",
    "tools/gi_release_contract_guard.sh",
    "tools/metal_benchmark_report.py",
    "tools/test_gi_g4_runner_contract.sh",
    "tools/test_gi_release_contract_guard.sh",
    "src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java",
    "src/main/java/com/metallum/client/gi/field/GiFieldCandidateBudget.java",
    "src/main/java/com/metallum/client/gi/debug/GiTransportDebugHud.java",
    "src/main/java/com/metallum/client/gi/debug/GiTransportDebugSettings.java",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticController.java",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticFieldAssembler.java",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticRuntime.java",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticTransportFieldView.java",
    "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java",
    "src/main/java/com/metallum/client/gi/source/GiDirectDirtyQueue.java",
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
    "src/main/java/com/metallum/client/gui/MetallumSodiumConfig.java",
    "src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java",
    "src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java",
    "src/main/java/com/metallum/mixin/render/GameRendererMetalFxMixin.java",
    "src/main/java/com/metallum/mixin/render/GuiVoxelPreviewMixin.java",
    "src/main/metal/MetallumGiTransport.metal",
    "src/main/native/MetallumNative.swift",
    "src/main/resources/assets/metallum/lang/en_us.json",
    "src/main/resources/assets/metallum/lang/ru_ru.json",
    "src/test/java/com/metallum/client/benchmark/BenchmarkWindowContractTests.java",
    "src/test/java/com/metallum/client/gi/semantic/GiSemanticTransportFieldViewTests.java",
    "src/test/java/com/metallum/client/gi/semantic/GiSemanticCpuTests.java",
    "src/test/java/com/metallum/client/gi/debug/GiTransportDebugSettingsTests.java",
    "src/test/java/com/metallum/client/gi/source/GiDirectSourceCpuTests.java",
    "src/test/java/com/metallum/client/gi/source/GiDirectSourceSourceChainTests.java",
    "src/test/java/com/metallum/client/gi/source/GiTransportGpuValidation.java",
    "src/test/java/com/metallum/client/gi/transport/GiTransportCpuTests.java",
    "src/test/java/com/metallum/client/gi/transport/GiTransportSourceChainTests.java",
    "src/test/java/com/metallum/client/metal/render/MetalRuntimeTests.java",
    "src/test/java/com/metallum/client/metal/render/framegraph/FrameGraphTests.java",
})

# A completed stage keeps its immutable receipt. The following G5/G6 stages may change only
# these exact, review-pinned successor file contents. Combined with each historical digest in
# the immutable G4 evidence this identifies the complete allowed diff, rather than granting a
# whole-file pathname exemption. Values are pinned after the accepted G6 tree is final.
G6_SUCCESSOR_FILE_SHA256 = {
    "build.gradle": "64e337af33bf847f9b6116be0c599245a64b38723b7a5e9cfbcac9544e5b722c",
    "scripts/run_metal_benchmark.sh": "137b54ecd65317a600fa66e05edc6ba7eaf7c56d00fe7e1fb20035c934ee01db",
    "src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java": "a1b0af6ac08e5575ea7b6e6cdcec07e5a90435ef30a18e15c480ced16f740577",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticController.java": "edd29d56bb05769f03404e4257a05ab95ccd6a3aa07f8905575560d8ef20de4f",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticFieldAssembler.java": "fe28e6efe6a850fc5a4c44c2e9587833dd5e25cf4d05199ab828233d1f43d6f6",
    "src/main/java/com/metallum/client/gi/semantic/GiSemanticTransportFieldView.java": "506c1da34fc439109db6fc1d95078a29615ae4cb4c19d07917d06e89abc241cb",
    "src/main/java/com/metallum/client/gi/source/GiDirectDirtyQueue.java": "424d1cabeca507e14efdac5d30f9c4cbf1e1f973de39d3e9f451f77c0c24cee5",
    "src/main/java/com/metallum/client/gi/source/GiDirectSourceCoordinator.java": "aeb15c06492056f0611f6b64177f95e230dad7c1b41a4e477e737bea801daa3d",
    "src/main/java/com/metallum/client/gi/source/GiDirectSourceGpuResources.java": "b99f284cea03d8273d312ca57522557245f57937a42efeba8d3722ab10024912",
    "src/main/java/com/metallum/client/gi/transport/GiTransportCoordinator.java": "3e4f421efb614a9e99fd19faa9bd2e7c123f890981d34cfc65a582649a377051",
    "src/main/java/com/metallum/client/gi/transport/GiTransportGpuResources.java": "6c33f400f7471ba7e4b71d78cbd4c708ed1321af434a0a961252c4b4796701a1",
    "src/main/java/com/metallum/client/gi/transport/GiTransportRuntime.java": "c501fa915e93f809227c37c4bc926763b0260cbbc3414ed86f8201f44a48d8d6",
    "src/main/java/com/metallum/client/gui/MetallumSodiumConfig.java": "ed61d924826362e3e58b65eb87ba0592ea5cfd31a2d062985c49f8f40e6dcc09",
    "src/main/java/com/metallum/client/lighting/AdvancedLightRegistry.java": "420e0dd8369dcd90a6cc91f8b8daa7d21d49db88c4837782e94726e31a6568a4",
    "src/main/java/com/metallum/client/metal/render/MetalCommandEncoder.java": "7c8b317744a4674115d078c13198abe03cb5ed35cfc86953d32afa122d881973",
    "src/main/java/com/metallum/client/metal/render/MetalDevice.java": "1fb8fdcd6085db7a907059660ded6ea00ef30ab44ad0daf93c61c822d97bc52f",
    "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java": "7a849905f1607986e86449fc182d3fda5a344928310d8b987a71a3d58718d8b3",
    "src/main/java/com/metallum/mixin/MetallumMixinConfigPlugin.java": "0d647c058734a18f8a57e3ce47483c5b0d45097e7abe4fe26c2b4506f3ca8d27",
    "src/main/java/com/metallum/mixin/render/GameRendererMetalFxMixin.java": "228d1bac4fb08f4581c38c803e550aade53c5daddc8dbd8d2013638c62199611",
    "src/main/metal/MetallumGiTransport.metal": "e5f4fe468bf7f450b860e9ae5c9844f04eb7ef4d2b3169fdeab2a4e316151806",
    "src/main/native/MetallumNative.swift": "018acbd2763f4be97536c6958a6ac00f20ec303a36d1d6ea8b707ed39cbe67a3",
    "src/main/resources/assets/metallum/lang/en_us.json": "bc2d4f73d1ba4ec07fbd559253caa00bc89fc62d6ca7f8afd6c33a29e9257a51",
    "src/main/resources/assets/metallum/lang/ru_ru.json": "cd0b83dc13ab750febe475f973bc4e70b39b1df40f098e8431c73d2e28d3d032",
    "src/test/java/com/metallum/client/benchmark/BenchmarkWindowContractTests.java": "aad4b4e9d459aaed52d7a848022c7cb75f7e8935b9f8d57fe11ac29d74e1246e",
    "src/test/java/com/metallum/client/gi/semantic/GiSemanticCpuTests.java": "1a063a55698324b690fee52286c91910088387e038f66f9b100e18ad1342ced9",
    "src/test/java/com/metallum/client/gi/source/GiDirectSourceCpuTests.java": "d26c00be19a226aa6c0760997c7141c947e6e6150b268b9747362724bd77360f",
    "src/test/java/com/metallum/client/gi/source/GiDirectSourceSourceChainTests.java": "9311c50ed1bc6fa3881678f0b615af7ab8d1e14c323217268ab244ad9b727225",
    "src/test/java/com/metallum/client/gi/source/GiTransportGpuValidation.java": "6f047f0de8dcf0d648747e7561fc955148e5bbace2209a2e66487154d46affb1",
    "src/test/java/com/metallum/client/gi/transport/GiTransportCpuTests.java": "2ec4f0a64f8f6225fb25ce3392947a6eba4a04cbab3cd50a05e434c5239f9029",
    "src/test/java/com/metallum/client/gi/transport/GiTransportSourceChainTests.java": "57f5c77303471a14a12444d7d554603ed397f4243c09c3e35d09ba6666798e58",
    "src/test/java/com/metallum/client/metal/render/MetalRuntimeTests.java": "61eedb5395de72903a51647d5786c9a27ac397e7b1c9609a8430a640d0a86b1a",
    "tools/gi_release_contract_guard.sh": "c8e924de80c1ada8df1bb17a08fa0607f930c9842806b6e1ce2f7a0317377ba9",
    "tools/metal_benchmark_report.py": "affb2e60e7b8e6e770cf030602781d17e205b0f14a64050278f22d1f06c1d4f8",
    "tools/test_gi_release_contract_guard.sh": "cb3c3888a6ab9e7ab5f4c4d90722873b2f0a729de26fa0de6693af1bdb73a941",
}
G4_CONTRACT_SELF_PATH = "tools/gi_g4_contract.py"
# The value-bearing line is normalized before hashing, avoiding a circular digest while still
# making every other byte of this verifier part of the exact successor seam.
G4_CONTRACT_SELF_NORMALIZED_SHA256 = "26064fcd5ad6369d4a06cbfc5f4ccd3c680f8044a788939a6ea171c4dc449997"

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

G4_NATIVE_ALLOCATED_BYTES = 4_020_576
G4_NATIVE_RESIDENT_BYTES = 1_966_080
G4_REPORT_FRAMES = 300
G4_INJECT_ACTIVE_FRAMES = 24
G4_ROUTE_SHA256 = "d321131b314bb22cee354e3cf48606712d414d44a84e6ed00230e70d9c65839d"
G4_FIXTURE_SHA256 = "a4a7e4fa34bed9e335856bc88f7ad1035ae1ba68e28851906ccaf9a65911e3c5"
G4_SETTINGS_SPEC_SHA256 = "92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e"
G4_SETTINGS_SHA256 = "fcf752aebd45a576e13cc19b446b954014b66e46a78c79e435289314d3b4ebb3"

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
    "dirty_worktree": False,
    "route": "gi-g4-overworld-v1",
    "route_sha256": G4_ROUTE_SHA256,
    "fixture": "hdrtest-static-v1",
    "fixture_sha256": G4_FIXTURE_SHA256,
    "settings_id": "native-hdr-fancy-v1",
    "settings_spec_sha256": G4_SETTINGS_SPEC_SHA256,
    "settings_sha256": G4_SETTINGS_SHA256,
    "benchmark_dimension": "minecraft:overworld",
    "benchmark_player_name": "MetallumBench",
    "benchmark_player_uuid": "b07a402a-d8ea-354f-9398-aaf208a798b9",
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

G4_REQUEST_TOKEN = (
    "GI_G4_TRANSPORT_REQUEST mode=g4_transport g2_capture=true g3_inject=true "
    "frozen_near_cascade=true jacobi_iterations=1 field_only=true receiver=false "
    "image_binding=false diagnostic_only=true release=false status=REQUESTED"
)
G4_VALIDATED_TOKEN = (
    "Benchmark validated: COMPLETE present, no FAIL/screenshots, dropped timing events = 0"
)

G4_TRANSCRIPT_TOKENS = (
    "Metallum benchmark preflight passed",
    "display: Built-in Retina Display, 3024x1964@120, exclusive fullscreen",
    "pacing: VSync off, maxFps=260",
    "scene: output=scene, source=sRGB, lighting=advanced (true/balanced)",
    G4_REQUEST_TOKEN,
    "MetalFX: OFF (persistent config remains off)",
    "frames: 600 warmup + 600 measurement",
    G4_VALIDATED_TOKEN,
)

G4_ADMISSION_PATTERN = re.compile(
    r"METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION "
    r"requested=g4_transport resolved=g4_transport contract=3 state=READY "
    r"phase=WARMUP presented_frame=[0-9]+ resources=11 passes=4 "
    r"dirty=192/192/0/0 injection_dispatches=192 full_volume_rebuilds=1 "
    r"transport_dispatches=1 field_epoch=1 stale=0 rejected=0 status=PASS "
    r"field_only=true receiver=false image_binding=false(?=\r?$)",
    re.MULTILINE,
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


def normalized_contract_self_sha256(path: Path) -> str:
    raw = path.read_text(encoding="utf-8")
    pattern = re.compile(
        r'(?m)^G4_CONTRACT_SELF_NORMALIZED_SHA256 = (?:"[0-9a-f]{64}"|"0" \* 64)$'
    )
    normalized, replacements = pattern.subn(
        'G4_CONTRACT_SELF_NORMALIZED_SHA256 = "<NORMALIZED>"', raw
    )
    if replacements != 1:
        raise ContractError("G4 verifier self-digest sentinel differs")
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


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
    drifted: list[tuple[str, str, str]] = []
    for relative, expected in manifests.items():
        if not isinstance(relative, str) or relative.startswith(("/", "../")) \
                or "/../" in relative or "\\" in relative:
            raise ContractError(f"unsafe G4 source digest path: {relative!r}")
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise ContractError(f"invalid G4 SHA-256 digest: {relative}")
        path = root / relative
        if not path.is_file():
            raise ContractError(f"required G4 source is missing: {relative}")
        actual = sha256(path)
        if actual != expected:
            drifted.append((relative, expected, actual))
    if not drifted:
        return

    drifted_paths = {relative for relative, _, _ in drifted}
    exact_successor_paths = set(G6_SUCCESSOR_FILE_SHA256) | {G4_CONTRACT_SELF_PATH}
    missing = sorted(exact_successor_paths - drifted_paths)
    unsupported = sorted(drifted_paths - exact_successor_paths)
    if missing or unsupported:
        raise ContractError(
            "G4 exact G6 successor diff path set differs; "
            f"missing={missing}, unsupported={unsupported}"
        )
    base_commit = implementation.get("base_commit")
    if not isinstance(base_commit, str) \
            or re.fullmatch(r"[0-9a-f]{40}", base_commit) is None:
        raise ContractError("G4 base commit is invalid for successor provenance")
    for ancestor, descendant, label in (
            (base_commit, G4_SOURCE_ATTESTATION_COMMIT, "G4 lineage base"),
            (G4_SOURCE_ATTESTATION_COMMIT, "HEAD", "G4 source attestation"),
    ):
        relation = subprocess.run(
            ["git", "-c", "core.fsmonitor=false", "merge-base", "--is-ancestor",
             ancestor, descendant],
            cwd=root,
            check=False,
            capture_output=True,
            text=True,
        )
        if relation.returncode != 0:
            raise ContractError(f"{label} is not an ancestor of {descendant}")
    for relative, expected, actual in drifted:
        historical = subprocess.run(
            ["git", "-c", "core.fsmonitor=false", "show",
             f"{G4_SOURCE_ATTESTATION_COMMIT}:{relative}"],
            cwd=root,
            check=False,
            capture_output=True,
        )
        if historical.returncode != 0 \
                or hashlib.sha256(historical.stdout).hexdigest() != expected:
            raise ContractError(
                "G4 historical source provenance differs at "
                f"{G4_SOURCE_ATTESTATION_COMMIT}: {relative}"
            )
        if relative == G4_CONTRACT_SELF_PATH:
            self_digest = normalized_contract_self_sha256(root / relative)
            if self_digest != G4_CONTRACT_SELF_NORMALIZED_SHA256:
                raise ContractError(
                    "G4 verifier differs outside its exact reviewed successor seam"
                )
        elif actual != G6_SUCCESSOR_FILE_SHA256[relative]:
            raise ContractError(
                "G4 source differs from its exact reviewed G6 successor seam: "
                f"{relative}"
            )

    # Structural G5/G6 verification is supplemental. It cannot authorize any source drift: every
    # drifted G4-manifest file has already matched the exact content digest above.
    g5_contract = root / "tools/gi_g5_contract.py"
    g5_document = root / "docs/GI_G5.md"
    g6_document = root / "docs/GI_G6.md"
    g6_evidence = root / "benchmark/gi/g6-live-evidence-v1.json"
    if not g5_contract.is_file() or not g5_document.is_file() \
            or "PASSED_BY_EXPLICIT_USER_DECISION" not in g5_document.read_text(encoding="utf-8") \
            or not g6_document.is_file() or not g6_evidence.is_file() \
            or "PASS_LIVE_DYNAMIC_GI" not in g6_document.read_text(encoding="utf-8") \
            or strict_object(g6_evidence).get("decision") != "PASS_LIVE_DYNAMIC_GI":
        raise ContractError("G4 source drift is not owned by declared G5/G6 successors")
    successor = subprocess.run(
        [sys.executable, str(g5_contract), "--root", str(root)],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if successor.returncode != 0:
        detail = successor.stderr.strip() or successor.stdout.strip() or "unknown failure"
        raise ContractError(f"G5 successor contract failed: {detail}")


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


def verify_runtime_identity(metadata: Any, label: str) -> dict[str, str]:
    value = require_object(metadata, f"{label}.metadata")
    for key, expected in {
        "dirty_worktree": False,
        "route": "gi-g4-overworld-v1",
        "route_sha256": G4_ROUTE_SHA256,
        "fixture": "hdrtest-static-v1",
        "fixture_sha256": G4_FIXTURE_SHA256,
        "settings_id": "native-hdr-fancy-v1",
        "settings_spec_sha256": G4_SETTINGS_SPEC_SHA256,
        "settings_sha256": G4_SETTINGS_SHA256,
        "benchmark_dimension": "minecraft:overworld",
        "benchmark_player_name": "MetallumBench",
        "benchmark_player_uuid": "b07a402a-d8ea-354f-9398-aaf208a798b9",
    }.items():
        require_exact_scalar(value, key, expected, f"{label}.metadata")
    identity: dict[str, str] = {}
    for key, pattern in {
        "commit": r"[0-9a-f]{12}",
        "source_sha256": r"[0-9a-f]{64}",
        "artifact_sha256": r"[0-9a-f]{64}",
    }.items():
        actual = value.get(key)
        if not isinstance(actual, str) or re.fullmatch(pattern, actual) is None:
            raise ContractError(f"{label}.metadata.{key} is invalid")
        identity[key] = actual
    identity.update({
        "route_sha256": G4_ROUTE_SHA256,
        "fixture_sha256": G4_FIXTURE_SHA256,
        "settings_spec_sha256": G4_SETTINGS_SPEC_SHA256,
        "settings_sha256": G4_SETTINGS_SHA256,
    })
    return identity


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


def verify_startup_profile(metadata: Any, generation: Any, label: str) -> None:
    metadata_object = require_object(metadata, f"{label}.metadata")
    generation_object = require_object(generation, f"{label}.renderer_generation")
    for key, expected in {
        "device_name": "Apple M1 Pro",
        "monitor": "Built-in Retina Display",
        "display_sync_enabled": False,
        "scaler_active": False,
        "persistent_metalfx_mode": "off",
        "global_illumination_mode": "g4_transport",
        "graphics_preset": "fancy",
        "benchmark_simulation_frozen": True,
        "native_shader_library_mode": "PRECOMPILED",
        "native_shader_source_compile_count": 0,
        "native_pipeline_failure_count": 0,
    }.items():
        require_exact_scalar(metadata_object, key, expected, f"{label}.metadata")
    for key, expected in G4_PROFILE_GENERATION.items():
        require_exact_scalar(
            generation_object, key, expected, f"{label}.renderer_generation"
        )


def verify_zero_reason_counters(gi: dict[str, Any], label: str) -> None:
    for reason_kind in ("reset_reasons", "fallback_reasons"):
        reasons = require_object(
            gi.get(reason_kind), f"{label}.global_illumination.{reason_kind}"
        )
        if not reasons or any(
            isinstance(count, bool) or not isinstance(count, int) or count != 0
            for count in reasons.values()
        ):
            raise ContractError(f"{label} contains a G4 {reason_kind[:-1]}")


def verify_gi_shape(value: Any, label: str) -> dict[str, Any]:
    gi = require_object(value, f"{label}.global_illumination")
    require_exact_scalar(gi, "mode", "active", f"{label}.global_illumination")
    for key, expected in {
        "contract_version": 3,
        "resource_count": 11,
        "pass_count": 4,
        "binding_count": 0,
        "shader_symbol_count": 0,
        "dirty_discarded_total": 0,
        "stale_cell_rejects": 0,
    }.items():
        require_exact_scalar(gi, key, expected, f"{label}.global_illumination")
    require_exact_scalar(
        gi, "allocated_bytes", G4_NATIVE_ALLOCATED_BYTES,
        f"{label}.global_illumination",
    )
    require_exact_scalar(
        gi, "resident_bytes", G4_NATIVE_RESIDENT_BYTES,
        f"{label}.global_illumination",
    )
    verify_zero_reason_counters(gi, label)
    return gi


def verify_startup_gi_window(value: Any, label: str) -> dict[str, Any]:
    gi = verify_gi_shape(value, label)
    queued = gi.get("dirty_queued_total")
    completed = gi.get("dirty_completed_total")
    pending = gi.get("dirty_pending")
    injection = gi.get("injection_dispatches")
    rebuilds = gi.get("full_volume_rebuilds")
    if type(queued) is not int or queued not in (0, 192) \
            or type(completed) is not int or not 0 <= completed <= queued \
            or type(pending) is not int or pending != queued - completed \
            or type(injection) is not int or injection != completed \
            or type(rebuilds) is not int or rebuilds != (1 if queued else 0) \
            or gi.get("transport_dispatches") != 0 \
            or gi.get("field_epoch") != 0 \
            or gi.get("valid_probes") != 0:
        raise ContractError(f"{label} contains invalid G4 startup progression")
    for key in ("source_epoch", "probe_epoch"):
        epoch = gi.get(key)
        if isinstance(epoch, bool) or not isinstance(epoch, int) or epoch < 0:
            raise ContractError(
                f"{label}.global_illumination.{key} must be an integer >= 0"
            )
    if queued == 0 and (gi["source_epoch"] != 0 or gi["probe_epoch"] != 0):
        raise ContractError(f"{label} published epochs before G3 population")
    return gi


def verify_gi_window(value: Any, label: str) -> dict[str, Any]:
    gi = verify_gi_shape(value, label)
    for key, expected in G4_EXACT_GI_COUNTERS.items():
        require_exact_scalar(gi, key, expected, f"{label}.global_illumination")
    for key in ("source_epoch", "probe_epoch"):
        epoch = gi.get(key)
        if isinstance(epoch, bool) or not isinstance(epoch, int) or epoch <= 0:
            raise ContractError(f"{label}.global_illumination.{key} must be positive")
    valid = gi.get("valid_probes")
    if type(valid) is not int or not 0 < valid <= 32 * 32 * 32:
        raise ContractError(f"{label}.global_illumination contains no valid surface probes")
    return gi


def verify_inject_stage(value: Any, label: str) -> int:
    stage = require_object(value, label)
    expected_keys = {
        "frames", "average_ms", "p50_ms", "p95_ms", "p99_ms", "maximum_ms",
    }
    frames = stage.get("frames")
    if set(stage) != expected_keys or type(frames) is not int or frames <= 0:
        raise ContractError(f"{label} has an invalid active-frame count")
    metrics = {
        key: finite_nonnegative(stage.get(key), f"{label}.{key}")
        for key in ("average_ms", "p50_ms", "p95_ms", "p99_ms", "maximum_ms")
    }
    if metrics["average_ms"] > metrics["maximum_ms"] or not (
        metrics["p50_ms"] <= metrics["p95_ms"]
        <= metrics["p99_ms"] <= metrics["maximum_ms"]
    ):
        raise ContractError(f"{label} timing distribution is not monotonic")
    return frames


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
    startup: list[dict[str, Any]] = []
    warmup: list[dict[str, Any]] = []
    measured: list[dict[str, Any]] = []
    transport_timings: list[dict[str, float]] = []
    injection_timed_frames = 0
    measured_gi: list[dict[str, Any]] = []
    final_gi: list[dict[str, Any]] = []
    previous_startup: dict[str, Any] | None = None
    frozen_startup_epochs: tuple[int, int] | None = None
    runtime_identity: dict[str, str] | None = None
    previous_phase = -1
    phase_rank = {"startup": 0, "warmup": 1, "measure": 2}

    for index, window in enumerate(windows, 1):
        label = f"G4 raw window {index}"
        require_exact_scalar(window, "schema_version", 6, label)
        require_exact_scalar(window, "detail_enabled", True, label)
        require_exact_scalar(window, "dropped_timing_events", 0, label)
        benchmark = require_object(window.get("benchmark"), f"{label}.benchmark")
        phase = benchmark.get("phase")
        if phase not in {"startup", "warmup", "measure"}:
            raise ContractError(f"{label}.benchmark.phase is not a recognized phase")
        if phase_rank[phase] < previous_phase:
            raise ContractError(f"{label}.benchmark phases are out of order")
        previous_phase = phase_rank[phase]
        require_exact_scalar(benchmark, "enabled", True, f"{label}.benchmark")
        require_exact_scalar(
            benchmark, "scaler_mode", "UNKNOWN" if phase == "startup" else "OFF",
            f"{label}.benchmark",
        )
        require_exact_scalar(
            benchmark, "generation", {"startup": 0, "warmup": 1, "measure": 2}[phase],
            f"{label}.benchmark",
        )
        require_exact_scalar(
            benchmark, "segment_index", -1 if phase == "startup" else 0,
            f"{label}.benchmark",
        )
        stages = require_object(window.get("stages"), f"{label}.stages")
        inject_stage = stages.get("GI_INJECT")
        transport_stage = stages.get("GI_TRANSPORT")

        if phase == "startup":
            verify_startup_profile(
                window.get("metadata"), window.get("renderer_generation"), label
            )
            gi = verify_startup_gi_window(window.get("global_illumination"), label)
            startup.append(window)
            if transport_stage is not None:
                raise ContractError("GI_TRANSPORT timing must occur only during warmup")
            if previous_startup is not None:
                for key in (
                    "dirty_queued_total", "dirty_completed_total",
                    "injection_dispatches", "full_volume_rebuilds",
                    "source_epoch", "probe_epoch",
                ):
                    if gi[key] < previous_startup[key]:
                        raise ContractError(
                            f"G4 startup progression regressed: {key}"
                        )
            previous_startup = gi
            if gi["dirty_completed_total"] > 0 \
                    or gi["source_epoch"] > 0 or gi["probe_epoch"] > 0:
                epochs = (gi["source_epoch"], gi["probe_epoch"])
                if min(epochs) <= 0:
                    raise ContractError("G4 populated startup epochs are not positive")
                if frozen_startup_epochs is None:
                    frozen_startup_epochs = epochs
                elif epochs != frozen_startup_epochs:
                    raise ContractError("G4 startup source/probe epoch drifted")
        else:
            verify_profile(window.get("metadata"), window.get("renderer_generation"), label)
            gi = verify_gi_window(window.get("global_illumination"), label)
            if inject_stage is not None:
                raise ContractError("GI_INJECT timing must occur only during G4 startup")
            final_gi.append(gi)
            frames = window.get("presented_frames")
            if isinstance(frames, bool) or not isinstance(frames, int) or frames <= 0:
                raise ContractError(f"{label}.presented_frames must be a positive integer")
            (warmup if phase == "warmup" else measured).append(window)
            if phase == "measure":
                measured_gi.append(gi)

        identity = verify_runtime_identity(window.get("metadata"), label)
        if runtime_identity is None:
            runtime_identity = identity
        elif identity != runtime_identity:
            raise ContractError("G4 source/route/settings identity drifted between windows")

        if inject_stage is not None:
            if phase != "startup":
                raise ContractError("GI_INJECT timing must occur only during G4 startup")
            injection_timed_frames += verify_inject_stage(
                inject_stage, f"{label}.stages.GI_INJECT"
            )

        if transport_stage is not None:
            if phase != "warmup":
                raise ContractError("GI_TRANSPORT timing must occur only during warmup")
            transport_timings.append(
                verify_transport_stage(transport_stage, f"{label}.stages.GI_TRANSPORT")
            )

    if len(startup) < 2 \
            or any(window.get("presented_frames") != G4_REPORT_FRAMES for window in startup) \
            or sum(window["presented_frames"] for window in startup) < 600:
        raise ContractError(
            "G4 raw receipt must contain at least 600 startup frames in 300-frame windows"
        )
    if len(warmup) != 2 \
            or any(window.get("presented_frames") != G4_REPORT_FRAMES for window in warmup):
        raise ContractError("G4 raw receipt must contain exactly two 300-frame warmup windows")
    if len(measured) != 2 \
            or any(window.get("presented_frames") != G4_REPORT_FRAMES for window in measured):
        raise ContractError("G4 raw receipt must contain exactly two 300-frame measure windows")
    if injection_timed_frames != G4_INJECT_ACTIVE_FRAMES:
        raise ContractError("G4 raw receipt must contain exactly 24 startup GI_INJECT active frames")
    if len(transport_timings) != 1:
        raise ContractError("G4 raw receipt must contain one warmup-only GI_TRANSPORT timing")
    if not measured_gi:
        raise ContractError("G4 raw receipt contains no measured GI telemetry")

    for key in ("source_epoch", "probe_epoch"):
        values = [gi[key] for gi in final_gi]
        if min(values) != max(values):
            raise ContractError(f"G4 final epoch drifted after warmup: {key}")
    if frozen_startup_epochs is None:
        raise ContractError("G4 raw receipt never exposed its frozen startup epochs")
    if any(
        (gi["source_epoch"], gi["probe_epoch"]) != frozen_startup_epochs
        for gi in final_gi
    ):
        raise ContractError("G4 final epochs differ from frozen startup")

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
    if runtime_identity is None:
        raise ContractError("G4 raw receipt contains no runtime identity")
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
        **runtime_identity,
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
    summary_identity = verify_runtime_identity(summary.get("metadata"), label)
    for key, expected in summary_identity.items():
        require_exact_scalar(results, key, expected, "G4 raw-derived results")

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
    for key, expected in {
        "allocated_bytes": G4_NATIVE_ALLOCATED_BYTES,
        "resident_bytes": G4_NATIVE_RESIDENT_BYTES,
    }.items():
        aggregate = require_object(
            counters.get(key), f"{label}.global_illumination.counters.{key}"
        )
        for aggregate_key in ("window_minimum", "window_maximum", "last_window"):
            require_exact_scalar(
                aggregate, aggregate_key, expected,
                f"{label}.global_illumination.counters.{key}",
            )
    valid_probes = require_object(
        counters.get("valid_probes"),
        f"{label}.global_illumination.counters.valid_probes",
    )
    if type(valid_probes.get("window_minimum")) is not int \
            or valid_probes["window_minimum"] <= 0:
        raise ContractError("G4 summary contains no valid surface probes")
    fallbacks = require_object(
        gi.get("fallback_reasons"), f"{label}.global_illumination.fallback_reasons"
    )
    if not fallbacks or any(type(count) is not int or count != 0 for count in fallbacks.values()):
        raise ContractError("G4 summary contains a renderer/GI fallback")
    stages = summary.get("stages", {})
    if not isinstance(stages, dict) \
            or stages.get("GI_INJECT") is not None \
            or stages.get("GI_TRANSPORT") is not None:
        raise ContractError("G4 measured summary contains GI injection/transport work")
    if results["measured_dispatch_growth"] != 0:
        raise ContractError("G4 measured dispatch growth is not zero")


def verify_console_receipt(console_text: str) -> None:
    if not console_text.strip():
        raise ContractError("G4 console artifact is empty")
    for token in (
        "METALLUM_BENCHMARK EVENT=FAIL",
        "Metal command buffer failed",
        "GPU timing sample invalid",
        "BUILD FAILED",
    ):
        if token in console_text:
            raise ContractError(f"G4 console contains a failure token: {token}")


def verify_transcript_receipt(
    transcript_text: str,
    identity: dict[str, Any] | None = None,
    stem: str | None = None,
) -> None:
    if not transcript_text.strip():
        raise ContractError("G4 transcript artifact is empty")
    for token in G4_TRANSCRIPT_TOKENS:
        if token not in transcript_text:
            raise ContractError(f"G4 transcript token is missing: {token}")
    for token, label in (
        (G4_REQUEST_TOKEN, "G4 request"),
        (G4_VALIDATED_TOKEN, "validated COMPLETE"),
    ):
        if transcript_text.count(token) != 1:
            raise ContractError(f"G4 transcript must contain one exact {label} marker")
    if "GI_G4_TRANSPORT_ADMISSION" in transcript_text:
        raise ContractError("G4 transcript contains the obsolete launcher request token")
    if "METALLUM_BENCHMARK EVENT=FAIL" in transcript_text:
        raise ContractError("G4 transcript contains a benchmark FAIL event")
    positions = [transcript_text.index(token) for token in G4_TRANSCRIPT_TOKENS]
    if positions != sorted(positions) or len(set(positions)) != len(positions):
        raise ContractError("G4 transcript markers are out of order")
    if identity is not None:
        for token in (
            f"settings: native-hdr-fancy-v1 ({identity['settings_sha256']}; "
            f"spec {identity['settings_spec_sha256']})",
            f"route: gi-g4-overworld-v1 ({identity['route_sha256']})",
            f"fixture: hdrtest-static-v1 ({identity['fixture_sha256']}, read-only)",
            f"commit: {identity['commit']} (clean worktree state)",
            f"source: {identity['source_sha256']}",
        ):
            if transcript_text.count(token) != 1:
                raise ContractError(f"G4 transcript identity token differs: {token}")
    if stem is not None:
        validated_position = transcript_text.index(G4_VALIDATED_TOKEN)
        binding_positions: dict[str, list[int]] = {}
        for suffix, label, expected_count in (
            (".raw.jsonl", "raw report", 1),
            (".raw.jsonl", "raw", 1),
            (".summary.json", "summary", 1),
            (".minecraft.log", "Minecraft log", 1),
            (".console.log", "console log", 2),
            (".transcript.log", "transcript", 2),
        ):
            pattern = re.compile(
                rf"(?:^|\n)(?:  )?{re.escape(label)}: [^\r\n]*/"
                rf"{re.escape(stem + suffix)}(?:\r?$)",
                re.MULTILINE,
            )
            matches = list(pattern.finditer(transcript_text))
            if len(matches) != expected_count:
                raise ContractError(
                    f"G4 transcript does not bind the exact {label} artifact stem"
                )
            binding_positions[label] = [match.start() for match in matches]
            before_validated = sum(
                match.start() < validated_position for match in matches
            )
            if label == "raw report" and before_validated != 1 \
                    or label in {"raw", "summary", "Minecraft log"} \
                    and before_validated != 0 \
                    or label in {"console log", "transcript"} \
                    and before_validated != 1:
                raise ContractError(
                    f"G4 transcript {label} binding is outside its launch/final block"
                )
        if not (
            binding_positions["raw report"][0]
            < binding_positions["transcript"][0]
            < binding_positions["console log"][0]
            < validated_position
            < binding_positions["raw"][0]
            < binding_positions["summary"][0]
            < binding_positions["Minecraft log"][0]
            < binding_positions["console log"][1]
            < binding_positions["transcript"][1]
        ):
            raise ContractError("G4 transcript artifact blocks are out of order")


def verify_minecraft_receipt(minecraft_text: str) -> None:
    if not minecraft_text.strip():
        raise ContractError("G4 Minecraft log artifact is empty")
    if "METALLUM_BENCHMARK EVENT=FAIL" in minecraft_text:
        raise ContractError("G4 Minecraft log contains a benchmark FAIL event")
    admission_pattern = re.compile(
        r"METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION "
        r"expected=advanced schema=5 defaults_used=false requested=advanced "
        r"resolved=advanced l3=true l5=true l6=true status=PASS "
        r"generation=[1-9][0-9]* shader_epoch=[1-9][0-9]* reason=none(?=\r?$)",
        re.MULTILINE,
    )
    if minecraft_text.count("METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION ") != 1 \
            or len(admission_pattern.findall(minecraft_text)) != 1:
        raise ContractError("G4 Minecraft log must contain one exact Advanced admission PASS")

    admission_prefix = "METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION "
    if minecraft_text.count(admission_prefix) != 1 \
            or len(G4_ADMISSION_PATTERN.findall(minecraft_text)) != 1:
        raise ContractError("G4 Minecraft log must contain one exact G4 admission PASS")
    mode_frozen = (
        "METALLUM_BENCHMARK EVENT=GI_G4_MODE_FROZEN "
        "mode=OFF phase=PRE_ROUTE status=PASS"
    )
    prepare_pattern = re.compile(
        r"METALLUM_BENCHMARK EVENT=GI_G3_PREPARE_BEGIN "
        r"route=gi-g4-overworld-v1 stable_frames=[1-9][0-9]* "
        r"candidates=0 status=PASS(?=\r?$)",
        re.MULTILINE,
    )
    prepare_prefix = "METALLUM_BENCHMARK EVENT=GI_G3_PREPARE_BEGIN "
    source_receipt = (
        "METALLUM_BENCHMARK EVENT=GI_G3_STARTUP_RECEIPT "
        "active_frames=24 drain_frames=300 status=PASS"
    )
    if minecraft_text.count(mode_frozen) != 1 \
            or minecraft_text.count(prepare_prefix) != 1 \
            or len(prepare_pattern.findall(minecraft_text)) != 1 \
            or minecraft_text.count(source_receipt) != 1:
        raise ContractError(
            "G4 Minecraft log does not prove pre-route mode freeze and G3 startup admission"
        )
    segment = (
        "METALLUM_BENCHMARK EVENT=SEGMENT_START index=1 total=1 mode=OFF "
        "warmup=600 measure=600"
    )
    measure = (
        "METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=OFF "
        "presented_frame=600"
    )
    complete = (
        "METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=600 "
        "framebuffer=3024x1964"
    )
    for token, label in (
        (segment, "SEGMENT_START"),
        (measure, "MEASURE_START"),
        (complete, "COMPLETE"),
    ):
        if minecraft_text.count(token) != 1:
            raise ContractError(f"G4 Minecraft log must contain one exact {label} marker")
    if not (
        minecraft_text.index(mode_frozen)
        < minecraft_text.index(prepare_prefix)
        < minecraft_text.index(source_receipt)
        < minecraft_text.index(segment)
        < minecraft_text.index(admission_prefix)
        < minecraft_text.index(measure)
        < minecraft_text.index(complete)
    ):
        raise ContractError("G4 admission marker is outside the warmup boundary")


def recompute_summary(root: Path, raw_path: Path) -> dict[str, Any]:
    historical_reporter = subprocess.run(
        ["git", "-c", "core.fsmonitor=false", "show",
         f"{G4_SOURCE_ATTESTATION_COMMIT}:tools/metal_benchmark_report.py"],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    )
    if historical_reporter.returncode != 0:
        raise ContractError("G4 attested canonical reporter source is unavailable")
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
    result = subprocess.run(
        command,
        cwd=root,
        input=historical_reporter.stdout,
        text=True,
        capture_output=True,
        check=False,
    )
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
    minecraft_path: Path,
    console_path: Path,
    transcript_path: Path,
    *,
    canonical_report_validation: bool = True,
) -> dict[str, Any]:
    if not raw_path.name.endswith(".raw.jsonl"):
        raise ContractError("G4 raw artifact must use the .raw.jsonl suffix")
    stem = raw_path.name[:-len(".raw.jsonl")]
    if summary_path != raw_path.with_name(stem + ".summary.json") \
            or minecraft_path != raw_path.with_name(stem + ".minecraft.log") \
            or console_path != raw_path.with_name(stem + ".console.log") \
            or transcript_path != raw_path.with_name(stem + ".transcript.log"):
        raise ContractError("G4 runtime artifacts must share one exact stem")

    windows = strict_json_lines(raw_path)
    summary = strict_object(summary_path)
    results, measured_window_count = derive_runtime_results(windows)
    verify_summary_receipt(summary, results, measured_window_count)
    verify_minecraft_receipt(minecraft_path.read_text(encoding="utf-8"))
    verify_console_receipt(console_path.read_text(encoding="utf-8"))
    verify_transcript_receipt(
        transcript_path.read_text(encoding="utf-8"), results, stem,
    )

    if canonical_report_validation:
        recomputed = recompute_summary(root, raw_path)
        supplied_report = summary.get("report")
        recomputed_report = recomputed.get("report")
        if not isinstance(supplied_report, str) or not isinstance(recomputed_report, str) \
                or Path(supplied_report).name != raw_path.name \
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
        if not isinstance(artifacts, dict) or set(artifacts) != {
            "raw", "summary", "minecraft_log", "console_log", "transcript_log",
        }:
            raise ContractError("G4 runtime artifact set differs")
        raw_path = required_artifact(root, artifacts["raw"], "raw")
        summary_path = required_artifact(root, artifacts["summary"], "summary")
        minecraft_path = required_artifact(
            root, artifacts["minecraft_log"], "minecraft_log"
        )
        console_path = required_artifact(root, artifacts["console_log"], "console_log")
        transcript_path = required_artifact(
            root, artifacts["transcript_log"], "transcript_log"
        )
        derived = verify_runtime_receipt(
            root, raw_path, summary_path, minecraft_path, console_path,
            transcript_path,
        )
        verify_declared_runtime_results(tier.get("results"), derived)

    limitations = evidence.get("limitations")
    if not isinstance(limitations, list) \
            or not any("not visual or product acceptance" in value for value in limitations) \
            or not any("not Tier C" in value for value in limitations):
        raise ContractError("G4 evidence limitations hide the field-only/Tier-B boundary")


def verify_g4_stale_receiver_transition(native: str, gpu_validation: str) -> None:
    try:
        transport = native[
            native.index("private final class MetallumGiTransportContextV1") :
            native.index("// MARK: - G5 vertex-only irradiance receiver")
        ]
        snapshot = transport[
            transport.index("fileprivate func receiverFieldSnapshot()") :
            transport.index("private func persistentBytes()")
        ]
        report_stale = transport[
            transport.index("func reportStale() -> Int32") :
            transport.index("func captureVolumeOnce(")
        ]
    except ValueError as error:
        raise ContractError("G4 stale receiver native declarations are missing") from error

    if transport.count("private var stale = false") != 1:
        raise ContractError("G4 native stale latch declaration differs")
    if snapshot.count("ready: ready && !stale)") != 1:
        raise ContractError("G4 receiver snapshot does not fail closed after stale")
    if report_stale.count("stale = true") != 1 \
            or report_stale.index("stale = true") > report_stale.index("rejectLocked("):
        raise ContractError("G4 reportStale does not latch stale before publication")

    try:
        validation = gpu_validation[
            gpu_validation.index("private static void validateReceiverReadyThenStale(") :
            gpu_validation.index("private static TransportInput transportInput(")
        ]
    except ValueError as error:
        raise ContractError("G4 READY-to-stale receiver validation is missing") from error
    require_tokens(validation, (
        '"G5 FIELD did not bind the READY G4 snapshot"',
        "metallum_gi_transport_report_stale_v1(",
        '"G4 explicit stale transition was not published"',
        '"G5 FIELD remained READY after its G4 owner became stale"',
        "GiReceiverLayout.STATUS_OK",
        "GiReceiverLayout.STATUS_ZERO_READY",
    ), "G4 READY-to-stale receiver validation")
    if validation.count("metallum_gi_receiver_bind_vertex_v1(") != 2:
        raise ContractError("G4 READY-to-stale validation must perform exactly two FIELD binds")
    if not (
        validation.index('"G5 FIELD did not bind the READY G4 snapshot"')
        < validation.index("metallum_gi_transport_report_stale_v1(")
        < validation.index('"G5 FIELD remained READY after its G4 owner became stale"')
    ):
        raise ContractError("G4 READY-to-stale validation order differs")


def self_test_g4_stale_receiver_guard(root: Path) -> None:
    native = source(root, "src/main/native/MetallumNative.swift")
    gpu_validation = source(
        root, "src/test/java/com/metallum/client/gi/source/GiTransportGpuValidation.java"
    )
    verify_g4_stale_receiver_transition(native, gpu_validation)
    mutations = (
        (
            native.replace("ready: ready && !stale)", "ready: ready)", 1),
            "G4 receiver snapshot does not fail closed after stale",
        ),
        (
            native.replace("        stale = true\n", "", 1),
            "G4 reportStale does not latch stale before publication",
        ),
    )
    for mutated_native, expected in mutations:
        try:
            verify_g4_stale_receiver_transition(mutated_native, gpu_validation)
        except ContractError as error:
            if expected not in str(error):
                raise AssertionError(
                    f"G4 stale mutation failed for the wrong reason: {error}"
                ) from error
        else:
            raise AssertionError(f"G4 stale mutation was admitted: {expected}")


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
    benchmark_controller = source(
        root, "src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java"
    )
    benchmark_tests = source(
        root, "src/test/java/com/metallum/client/benchmark/BenchmarkWindowContractTests.java"
    )
    timing = source(root, "src/main/java/com/metallum/client/metal/render/MetalGpuTimingStage.java")
    bridge = source(root, "src/main/java/com/metallum/client/metal/render/bridge/MetalNativeBridge.java")
    native = source(root, "src/main/native/MetallumNative.swift")
    metal = source(root, "src/main/metal/MetallumGiTransport.metal")
    gradle = source(root, "build.gradle")
    launcher = source(root, "scripts/run_metal_benchmark.sh")
    release_guard = source(root, "tools/gi_release_contract_guard.sh")
    runner_test = source(root, "tools/test_gi_g4_runner_contract.sh")
    release_guard_test = source(root, "tools/test_gi_release_contract_guard.sh")
    reporter = source(root, "tools/metal_benchmark_report.py")
    gpu_validation = source(
        root, "src/test/java/com/metallum/client/gi/source/GiTransportGpuValidation.java"
    )
    frame_graph_tests = source(
        root, "src/test/java/com/metallum/client/metal/render/framegraph/FrameGraphTests.java"
    )
    verify_g4_stale_receiver_transition(native, gpu_validation)

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
        "GiDirectDirtyQueue.EpochTelemetry epochQueue",
        "isSettledTransportSource(epochQueue)",
        "queue.completed() == GiDirectSourceLayout.TOTAL_BRICKS",
        "queue.pending() == 0", "queue.discarded() == 0L",
        "!stats.ready()", "stats.buildInFlight()", "transportContextHandle()",
        "public static final class TelemetrySource", "attachTransportTelemetry",
        "metallum_gi_transport_attach_telemetry_v1",
        "STATUS_FROZEN_INPUT_DRIFT", "FROZEN_INPUT_SETTLE_FRAMES",
    ), "G4 completed-G3 admission")
    require_tokens(direct_resources, (
        "public static final long JAVA_PERSISTENT_PACKET_BYTES = HEADER_BYTES",
        "GiDirectSourceLayout.MAX_DRAIN_PER_FRAME", "STATS_BYTES",
    ), "G3 persistent Java packet owner")
    require_tokens(gpu_validation, (
        "GiDirectSourceGpuResources.JAVA_PERSISTENT_PACKET_BYTES",
        "== 70_216L", "GiTransportLayout.JAVA_PERSISTENT_PACKET_BYTES == 524_608L",
        "validateAttachmentLifecycle", "g4-attach-wrong-thread",
        "forged G3 telemetry capability was admitted",
        "idempotent owner G4 telemetry attachment failed",
        "second live G4 attachment was admitted",
        "released G3 telemetry capability was admitted",
        "G4 admitted a G3 owner from a different command queue",
        "G4 pre-dispatch release attachment failed",
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
        "METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION ",
        "requested=g4_transport resolved=g4_transport",
        "status=PASS", "GiTransportRuntime.isBenchmarkWarmup()",
    ), "G4 MetalDevice structural admission")
    require_tokens(benchmark_controller, (
        "isFrozenG4Sequence(parsed)", "this.sequence.getFirst().apply()",
        "this.g4ModePreapplied = true", "this.routeStableFrames = 0",
        "METALLUM_BENCHMARK EVENT=GI_G4_MODE_FROZEN",
        "if (!this.g4ModePreapplied)",
        "GiTransportRuntime.beginSourcePreparation()",
        "GiTransportRuntime.beginBenchmarkWarmup()",
        "GiTransportRuntime.beginBenchmarkMeasurement()",
        "GiTransportRuntime.isResolvedReady()",
        "G4_SOURCE_RECEIPT_FRAMES = 300",
        "METALLUM_BENCHMARK EVENT=GI_G3_STARTUP_RECEIPT",
        "G4 transport did not resolve READY before measurement",
    ), "G4 benchmark phase admission")
    require_tokens(benchmark_tests, (
        "acceptsOnlyOneFrozenOffModeForG4",
        "MetalFxBenchmarkController.isFrozenG4Sequence",
        "List.of(BenchmarkScalingMode.OFF)",
        "BenchmarkScalingMode.QUALITY",
    ), "G4 pre-route benchmark mode test")

    require_tokens(timing, ("GI_TRANSPORT(21)", "PROFILED_STAGE_COUNT = 22"),
                   "G4 Java timing ABI")
    require_tokens(launcher, (
        "METALLUM_GI_G4_TRANSPORT", "RELEASE_PROFILE_CANDIDATE",
        "metallum_require_release_gi_off",
        'RELEASE_ARG=--release-contract',
        "GI_G4_TRANSPORT_REQUEST", 'TRANSCRIPT_LOG="$OUTPUT_DIR/$stem.transcript.log"',
        'pipeline_status=("${PIPESTATUS[@]}")', "g4_admission_prefix",
        'require_value "$ROUTE_ID" "gi-g4-overworld-v1"',
        G4_ROUTE_SHA256, G4_SETTINGS_SPEC_SHA256, G4_SETTINGS_SHA256,
        "G4 Tier B evidence requires a clean worktree",
    ), "G4 benchmark launcher marker")
    reject_tokens(launcher, (
        "GI_G4_TRANSPORT_ADMISSION mode=g4_transport",
    ), "G4 benchmark launcher request marker")
    require_tokens(runner_test, (
        "bash -n", "gi-g4-overworld-v1", "timeout_frames", "PIPESTATUS",
        "segment_start_line", "g4_admission_line", "measure_start_line",
        "G4 Tier B evidence requires a clean worktree",
    ), "G4 benchmark launcher contract test")
    require_tokens(release_guard, (
        "metallum_require_release_gi_off", '"$release_candidate" -eq 1',
        '"$gi_g2" -ne 0', '"$gi_g3" -ne 0', '"$gi_g4" -ne 0',
    ), "G4 release-contract environment guard")
    require_tokens(release_guard_test, (
        "expect_reject 1 1 0 0", "expect_reject 1 0 1 0",
        "expect_reject 1 0 0 1", "expect_accept 1 0 0 0",
    ), "G4 release-contract guard test")
    require_tokens(reporter, (
        'GI_TRANSPORT_STAGE = "GI_TRANSPORT"', "_validate_g4_window_phase",
        "_validate_g4_report", "G4_FINAL_COUNTERS", "G4_INJECT_ACTIVE_FRAMES",
    ),
                   "G4 benchmark report marker")
    require_tokens(frame_graph_tests, (
        'init.accesses().equals(List.of(', 'transport.accesses().equals(List.of(',
        'resourceId(2, "gi_g3_direct_irradiance")',
        'resourceId(7, "gi_transport_confidence")',
    ), "G4 exact frame-graph access-set test")
    require_tokens(gradle, (
        'GiTransport: layout.projectDirectory.file("src/main/metal/MetallumGiTransport.metal")',
        'tasks.register("giG4TransportContractTest", Exec)',
        'tasks.register("giG4RunnerContractTest", Exec)',
        'tasks.register("giSemanticTransportFieldUnitTest", JavaExec)',
        'tasks.register("giTransportCpuUnitTest", JavaExec)',
        'tasks.register("giTransportSourceChainUnitTest", JavaExec)',
        'tasks.register("giTransportGpuValidationSource", JavaExec)',
        'tasks.register("giTransportGpuValidationBundled", JavaExec)',
        'tasks.register("giReleaseContractGuardTest", Exec)',
        'dependsOn(tasks.named("giG4RunnerContractTest"))',
        'mainClass.set("com.metallum.client.gi.source.GiTransportGpuValidation")',
    ), "G4 shader build input")

    for symbol in (
            "metallum_gi_transport_abi_version_v1", "metallum_gi_transport_layout_v1",
            "metallum_gi_transport_create_context_v1", "metallum_gi_transport_encode_frozen_v1",
            "metallum_gi_transport_attach_telemetry_v1",
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
    if metal_code.count("rho * (1.0f / metallumGiTransportPi) * direct") != 2 \
            or metal_code.count("kernel void metallum_gi_live_jacobi_sh_v1") != 1:
        raise ContractError("G4 frozen and G6 live transport must each apply source rho/pi once")
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
    metadata = {
        **G4_PROFILE_METADATA,
        "current_edr_headroom": 8.0,
        "commit": "1" * 12,
        "source_sha256": "2" * 64,
        "artifact_sha256": "3" * 64,
    }
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
    injection_timing = {
        "frames": G4_INJECT_ACTIVE_FRAMES,
        "average_ms": 0.08,
        "p50_ms": 0.07,
        "p95_ms": 0.11,
        "p99_ms": 0.12,
        "maximum_ms": 0.13,
    }

    def startup_gi(completed: int) -> dict[str, Any]:
        value = copy.deepcopy(gi)
        queued = 0 if completed == 0 else 192
        value.update({
            "source_epoch": 0 if queued == 0 else 17,
            "probe_epoch": 0 if queued == 0 else 19,
            "dirty_queued_total": queued,
            "dirty_completed_total": completed,
            "dirty_discarded_total": 0,
            "dirty_pending": queued - completed,
            "injection_dispatches": completed,
            "full_volume_rebuilds": 0 if queued == 0 else 1,
            "transport_dispatches": 0,
            "field_epoch": 0,
            "valid_probes": 0,
            "unknown_probes": 0,
        })
        return value

    def window(
        phase: str,
        stage: dict[str, Any] | None = None,
        inject_stage: dict[str, Any] | None = None,
        gi_value: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return {
            "schema_version": 6,
            "detail_enabled": True,
            "presented_frames": 300,
            "dropped_timing_events": 0,
            "benchmark": {
                "enabled": True,
                "generation": {"startup": 0, "warmup": 1, "measure": 2}[phase],
                "phase": phase,
                "segment_index": -1 if phase == "startup" else 0,
                "scaler_mode": "UNKNOWN" if phase == "startup" else "OFF",
            },
            "metadata": copy.deepcopy(metadata),
            "renderer_generation": copy.deepcopy(generation),
            "global_illumination": copy.deepcopy(gi_value or gi),
            "stages": {
                "GI_INJECT": copy.deepcopy(inject_stage),
                "GI_TRANSPORT": copy.deepcopy(stage),
            },
        }

    windows = [
        window("startup", gi_value=startup_gi(0)),
        window("startup", gi_value=startup_gi(0)),
        window(
            "startup", inject_stage=injection_timing,
            gi_value=startup_gi(G4_EXACT_GI_COUNTERS["dirty_completed_total"]),
        ),
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
    for key, expected in {
        "allocated_bytes": G4_NATIVE_ALLOCATED_BYTES,
        "resident_bytes": G4_NATIVE_RESIDENT_BYTES,
        "valid_probes": gi["valid_probes"],
    }.items():
        counters[key] = {
            "window_minimum": expected,
            "window_maximum": expected,
            "last_window": expected,
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
    console = "Gradle 9\nBUILD SUCCESSFUL in 1m\n"
    transcript = "\n".join((
        G4_TRANSCRIPT_TOKENS[0],
        G4_TRANSCRIPT_TOKENS[1],
        G4_TRANSCRIPT_TOKENS[2],
        G4_TRANSCRIPT_TOKENS[3],
        G4_REQUEST_TOKEN,
        f"  settings: native-hdr-fancy-v1 ({G4_SETTINGS_SHA256}; "
        f"spec {G4_SETTINGS_SPEC_SHA256})",
        G4_TRANSCRIPT_TOKENS[5],
        f"  route: gi-g4-overworld-v1 ({G4_ROUTE_SHA256})",
        f"  fixture: hdrtest-static-v1 ({G4_FIXTURE_SHA256}, read-only)",
        G4_TRANSCRIPT_TOKENS[6],
        f"  commit: {metadata['commit']} (clean worktree state)",
        f"  source: {metadata['source_sha256']}",
        "  raw report: /tmp/g4.raw.jsonl",
        "  transcript: /tmp/g4.transcript.log",
        "  console log: /tmp/g4.console.log",
        G4_VALIDATED_TOKEN,
        "  raw: /tmp/g4.raw.jsonl",
        "  summary: /tmp/g4.summary.json",
        "  Minecraft log: /tmp/g4.minecraft.log",
        "  console log: /tmp/g4.console.log",
        "  transcript: /tmp/g4.transcript.log",
    )) + "\n"
    minecraft = "\n".join((
        "METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION expected=advanced schema=5 "
        "defaults_used=false requested=advanced resolved=advanced l3=true l5=true "
        "l6=true status=PASS generation=6 shader_epoch=5 reason=none",
        "METALLUM_BENCHMARK EVENT=GI_G4_MODE_FROZEN "
        "mode=OFF phase=PRE_ROUTE status=PASS",
        "METALLUM_BENCHMARK EVENT=GI_G3_PREPARE_BEGIN "
        "route=gi-g4-overworld-v1 stable_frames=120 candidates=0 status=PASS",
        "METALLUM_BENCHMARK EVENT=GI_G3_STARTUP_RECEIPT "
        "active_frames=24 drain_frames=300 status=PASS",
        "METALLUM_BENCHMARK EVENT=SEGMENT_START index=1 total=1 mode=OFF "
        "warmup=600 measure=600",
        "METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION requested=g4_transport "
        "resolved=g4_transport contract=3 state=READY phase=WARMUP "
        "presented_frame=81 resources=11 passes=4 dirty=192/192/0/0 "
        "injection_dispatches=192 full_volume_rebuilds=1 transport_dispatches=1 "
        "field_epoch=1 stale=0 rejected=0 status=PASS field_only=true "
        "receiver=false image_binding=false",
        "METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=OFF presented_frame=600",
        "METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=600 "
        "framebuffer=3024x1964",
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
    verify_transcript_receipt(transcript)
    verify_minecraft_receipt(minecraft)
    verify_declared_runtime_results(copy.deepcopy(derived), derived)

    expect_failure(
        lambda: verify_minecraft_receipt(minecraft.replace(
            "METALLUM_BENCHMARK EVENT=GI_G4_MODE_FROZEN "
            "mode=OFF phase=PRE_ROUTE status=PASS\n", ""
        )),
        "pre-route mode freeze",
    )

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
    invalid_timing[3]["stages"]["GI_TRANSPORT"]["p95_ms"] = math.nan
    expect_failure(lambda: derive_runtime_results(invalid_timing), "finite and >= 0")

    measured_stage = copy.deepcopy(windows)
    measured_stage[5]["stages"]["GI_TRANSPORT"] = copy.deepcopy(timing)
    expect_failure(lambda: derive_runtime_results(measured_stage), "only during warmup")

    startup_stage = copy.deepcopy(windows)
    startup_stage[1]["stages"]["GI_TRANSPORT"] = copy.deepcopy(timing)
    expect_failure(lambda: derive_runtime_results(startup_stage), "only during warmup")

    startup_regression = copy.deepcopy(windows)
    startup_regression[0]["global_illumination"] = startup_gi(96)
    expect_failure(
        lambda: derive_runtime_results(startup_regression),
        "startup progression regressed",
    )

    partial_warmup = copy.deepcopy(windows)
    partial_warmup[3]["global_illumination"].update({
        "dirty_completed_total": 191,
        "dirty_pending": 1,
        "injection_dispatches": 191,
    })
    expect_failure(
        lambda: derive_runtime_results(partial_warmup),
        "dirty_completed_total differs",
    )

    warmup_inject = copy.deepcopy(windows)
    warmup_inject[3]["stages"]["GI_INJECT"] = copy.deepcopy(timing)
    expect_failure(
        lambda: derive_runtime_results(warmup_inject),
        "GI_INJECT timing must occur only during G4 startup",
    )

    measured_inject = copy.deepcopy(windows)
    measured_inject[5]["stages"]["GI_INJECT"] = copy.deepcopy(timing)
    expect_failure(
        lambda: derive_runtime_results(measured_inject),
        "GI_INJECT timing must occur only during G4 startup",
    )

    duplicate_timing = copy.deepcopy(windows)
    duplicate_timing[4]["stages"]["GI_TRANSPORT"] = copy.deepcopy(timing)
    expect_failure(
        lambda: derive_runtime_results(duplicate_timing),
        "one warmup-only GI_TRANSPORT timing",
    )

    measured_growth = copy.deepcopy(windows)
    measured_growth[5]["global_illumination"]["source_epoch"] += 1
    expect_failure(lambda: derive_runtime_results(measured_growth), "final epoch drifted")

    no_startup = copy.deepcopy(windows[3:])
    expect_failure(
        lambda: derive_runtime_results(no_startup),
        "at least 600 startup frames",
    )

    short_windows = copy.deepcopy(windows)
    for short_window in short_windows[3:]:
        short_window["presented_frames"] = 100
    expect_failure(
        lambda: derive_runtime_results(short_windows),
        "two 300-frame warmup windows",
    )

    missing_inject = copy.deepcopy(windows)
    missing_inject[2]["stages"]["GI_INJECT"] = None
    expect_failure(
        lambda: derive_runtime_results(missing_inject),
        "exactly 24 startup GI_INJECT active frames",
    )

    startup_epoch_drift = copy.deepcopy(windows)
    for final_window in startup_epoch_drift[3:]:
        final_window["global_illumination"]["source_epoch"] += 1
    expect_failure(
        lambda: derive_runtime_results(startup_epoch_drift),
        "final epochs differ from frozen startup",
    )

    cross_source = copy.deepcopy(windows)
    cross_source[3]["metadata"]["source_sha256"] = "4" * 64
    expect_failure(
        lambda: derive_runtime_results(cross_source),
        "source/route/settings identity drifted",
    )

    empty_field = copy.deepcopy(windows)
    empty_field[3]["global_illumination"]["valid_probes"] = 0
    expect_failure(
        lambda: derive_runtime_results(empty_field),
        "no valid surface probes",
    )

    wrong_bytes = copy.deepcopy(windows)
    wrong_bytes[0]["global_illumination"].update({
        "allocated_bytes": 1, "resident_bytes": 1,
    })
    expect_failure(
        lambda: derive_runtime_results(wrong_bytes),
        "allocated_bytes differs",
    )

    wrong_summary = copy.deepcopy(summary)
    wrong_summary["global_illumination"]["counters"]["resource_count"][
        "last_window"
    ] = 10
    expect_failure(
        lambda: verify_summary_receipt(wrong_summary, derived, measured_count),
        "resource_count.last_window differs",
    )
    summary_with_inject = copy.deepcopy(summary)
    summary_with_inject["stages"]["GI_INJECT"] = copy.deepcopy(timing)
    expect_failure(
        lambda: verify_summary_receipt(summary_with_inject, derived, measured_count),
        "GI injection/transport work",
    )

    wrong_results = copy.deepcopy(derived)
    wrong_results["transport_dispatches"] = 2
    expect_failure(
        lambda: verify_declared_runtime_results(wrong_results, derived),
        "handwritten result differs",
    )
    expect_failure(
        lambda: verify_minecraft_receipt(minecraft.replace(
            "resolved=g4_transport contract=3",
            "resolved=g3_inject contract=3",
        )),
        "one exact G4 admission PASS",
    )
    expect_failure(
        lambda: verify_minecraft_receipt(minecraft.replace(
            "METALLUM_BENCHMARK EVENT=SEGMENT_START",
            "METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION requested=g4_transport "
            "resolved=g4_transport contract=3 state=READY phase=WARMUP "
            "presented_frame=80 resources=11 passes=4 dirty=192/192/0/0 "
            "injection_dispatches=192 full_volume_rebuilds=1 "
            "transport_dispatches=1 field_epoch=1 stale=0 rejected=0 status=PASS "
            "field_only=true receiver=false image_binding=false\n"
            "METALLUM_BENCHMARK EVENT=SEGMENT_START",
        )),
        "one exact G4 admission PASS",
    )
    expect_failure(
        lambda: verify_transcript_receipt(
            transcript.replace("GI_G4_TRANSPORT_REQUEST", "GI_G4_TRANSPORT_ADMISSION")
        ),
        "transcript token is missing",
    )
    expect_failure(
        lambda: verify_transcript_receipt(
            transcript.replace(
                "  raw: /tmp/g4.raw.jsonl",
                "  raw: /tmp/contradictory.raw.jsonl",
            ),
            derived,
            "g4",
        ),
        "exact raw artifact stem",
    )
    expect_failure(
        lambda: verify_transcript_receipt(transcript + G4_REQUEST_TOKEN + "\n"),
        "one exact G4 request marker",
    )
    expect_failure(
        lambda: verify_transcript_receipt(
            transcript.replace("  console log: /tmp/g4.console.log\n", "", 1),
            derived,
            "g4",
        ),
        "exact console log artifact stem",
    )
    expect_failure(
        lambda: verify_transcript_receipt(
            transcript.replace(
                "  console log: /tmp/g4.console.log\n" + G4_VALIDATED_TOKEN,
                G4_VALIDATED_TOKEN + "\n  console log: /tmp/g4.console.log",
                1,
            ),
            derived,
            "g4",
        ),
        "outside its launch/final block",
    )
    expect_failure(
        lambda: verify_transcript_receipt(
            transcript.replace(
                "  raw: /tmp/g4.raw.jsonl\n  summary: /tmp/g4.summary.json",
                "  summary: /tmp/g4.summary.json\n  raw: /tmp/g4.raw.jsonl",
                1,
            ),
            derived,
            "g4",
        ),
        "artifact blocks are out of order",
    )

    with tempfile.TemporaryDirectory(prefix="metallum-g4-receipt-") as temporary:
        root = Path(temporary)
        raw_path = root / "g4.raw.jsonl"
        summary_path = root / "g4.summary.json"
        minecraft_path = root / "g4.minecraft.log"
        console_path = root / "g4.console.log"
        transcript_path = root / "g4.transcript.log"
        raw_path.write_text(
            "\n".join(json.dumps(value) for value in windows) + "\n",
            encoding="utf-8",
        )
        summary_path.write_text(json.dumps(summary), encoding="utf-8")
        minecraft_path.write_text(minecraft, encoding="utf-8")
        console_path.write_text(console, encoding="utf-8")
        transcript_path.write_text(transcript, encoding="utf-8")
        actual = verify_runtime_receipt(
            root, raw_path, summary_path, minecraft_path, console_path,
            transcript_path,
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
    self_test_g4_stale_receiver_guard(root)
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
