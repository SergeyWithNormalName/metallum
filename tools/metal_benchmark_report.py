#!/usr/bin/env python3
"""Validate, summarize, and compare Metallum timing JSONL reports.

Reports contain percentiles for independent 300-frame windows, not raw frame
samples.  This tool therefore reports weighted summaries of window metrics and
never presents them as an exact percentile over the whole run.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import sys
import tempfile
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence


DEFAULT_MEASURE_FRAMES = 3000
DEFAULT_WARMUP_FRAMES = 1800
DEFAULT_P95_GATE_MS = 0.2
DEFAULT_P99_GATE_MS = 1.5
DEFAULT_GPU_WORST_GATE_MS = 5.0
DEFAULT_FPS_REGRESSION_FRACTION = 0.03
DEFAULT_ONE_PERCENT_LOW_REGRESSION_FRACTION = 0.07
DEFAULT_ZERO_POINT_ONE_LOW_REGRESSION_FRACTION = 0.12
DEFAULT_PRESENT_P95_REGRESSION_FRACTION = 0.10
DEFAULT_PRESENT_P99_REGRESSION_FRACTION = 0.15
DEFAULT_PRESENT_WORST_GATE_MS = 5.0
BUILT_IN_MONITOR = "Built-in Retina Display"
BUILT_IN_WIDTH = 3024
BUILT_IN_HEIGHT = 1964
SHA256_RE = re.compile(r"[0-9a-f]{64}")
PLAYER_RE = re.compile(r"[A-Za-z0-9_]{3,16}")
DIMENSION_RE = re.compile(r"[a-z0-9_.-]+:[a-z0-9/._-]+")
SAFE_ID_RE = re.compile(r"[a-z0-9][a-z0-9._-]*")
WORKLOAD_BASE_KEYS = frozenset({
    "command_buffers", "encoders", "copy_bytes", "resource_allocations",
})
WORKLOAD_EXPANDED_KEYS = WORKLOAD_BASE_KEYS | {"transient_memory"}
WORKLOAD_PRIVATE_GEOMETRY_HEAP_KEYS = WORKLOAD_EXPANDED_KEYS | {
    "private_geometry_heap",
}
WORKLOAD_ENCODER_KEYS = frozenset({
    "render", "compute", "blit", "pass_boundaries",
})
WORKLOAD_COPY_KEYS = frozenset({
    "cpu_to_shared",
    "shared_to_private",
    "gpu_to_cpu",
    "gpu_internal",
    "unclassified",
    "cpu_to_shared_commands",
    "shared_to_private_commands",
    "gpu_to_cpu_commands",
    "gpu_internal_commands",
    "unclassified_commands",
    "byte_count_unknown_commands",
    "direct_write_observed",
})
WORKLOAD_COPY_BYTE_KEYS = (
    "cpu_to_shared", "shared_to_private", "gpu_to_cpu", "gpu_internal",
    "unclassified",
)
WORKLOAD_COPY_COMMAND_KEYS = (
    "cpu_to_shared_commands", "shared_to_private_commands",
    "gpu_to_cpu_commands", "gpu_internal_commands", "unclassified_commands",
    "byte_count_unknown_commands",
)
WORKLOAD_RESOURCE_KINDS = ("buffers", "textures")
WORKLOAD_RESOURCE_KEYS = frozenset(WORKLOAD_RESOURCE_KINDS)
WORKLOAD_ALLOCATION_KEYS = frozenset({"count", "bytes"})
WORKLOAD_TRANSIENT_KINDS = ("cpu", "gpu_shared")
WORKLOAD_TRANSIENT_KEYS = frozenset(WORKLOAD_TRANSIENT_KINDS)
WORKLOAD_TRANSIENT_HIGH_WATER_KEYS = frozenset({
    "requested_high_water_bytes", "reserved_high_water_bytes",
})
WORKLOAD_HEAP_TOTAL_KEYS = (
    "pages_created_total",
    "pages_retired_total",
    "requests_total",
    "requested_bytes_total",
    "heap_allocations_total",
    "heap_query_bytes_total",
    "page_reuse_hits_total",
    "fallback_allocations_total",
    "fallback_requested_bytes_total",
    "fallback_disabled_total",
    "fallback_oversize_total",
    "fallback_invalid_query_total",
    "fallback_capacity_total",
    "fallback_heap_create_total",
    "fallback_heap_allocate_total",
    "allocation_failures_total",
    "backing_allocations_total",
    "device_teardown_with_live_allocations_total",
)
WORKLOAD_HEAP_GAUGE_KEYS = (
    "pools_current",
    "pages_current",
    "pages_peak",
    "retire_pending_pages",
    "heap_size_bytes_current",
    "heap_current_allocated_bytes",
    "heap_used_bytes_current",
    "fragmentation_probe_alignment",
    "heap_largest_available_bytes",
    "heap_fragmentation_estimate_bytes",
    "live_allocations",
    "live_requested_bytes",
    "live_query_bytes",
)
WORKLOAD_HEAP_CONFIGURATION_KEYS = (
    "page_size_bytes",
    "page_limit_per_device",
)
WORKLOAD_HEAP_KEYS = frozenset({
    "enabled",
    *WORKLOAD_HEAP_CONFIGURATION_KEYS,
    *WORKLOAD_HEAP_GAUGE_KEYS,
    *WORKLOAD_HEAP_TOTAL_KEYS,
})
WORKLOAD_CONTRACT_NONE = "none"
WORKLOAD_CONTRACT_BASE = "base"
WORKLOAD_CONTRACT_EXPANDED = "expanded"
WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP = "private_geometry_heap"
WORKLOAD_CONTRACTS = frozenset({
    WORKLOAD_CONTRACT_NONE,
    WORKLOAD_CONTRACT_BASE,
    WORKLOAD_CONTRACT_EXPANDED,
    WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP,
})
STABLE_METADATA_KEYS = (
    "commit", "dirty_worktree", "source_sha256", "artifact_sha256",
    "settings_id", "settings_spec_sha256", "settings_sha256",
    "render_distance", "simulation_distance", "graphics_preset",
    "entity_distance_scaling", "particles", "mipmap_levels",
    "biome_blend_radius", "max_fps", "ambient_occlusion", "clouds_mode",
    "cloud_range", "texture_filtering", "max_anisotropy_bit",
    "improved_transparency", "resource_packs_sha256",
    "sodium_settings_sha256", "configured_gui_scale",
    "active_resource_pack_ids", "sodium_chunk_builder_threads",
    "hdr_bloom_strength", "hdr_strength", "persistent_metalfx_mode",
    "world", "fixture", "fixture_sha256", "route", "route_sha256",
    "benchmark_player_name", "benchmark_player_uuid", "benchmark_dimension",
    "benchmark_simulation_frozen", "monitor", "os_version", "thermal_state",
    "device_name", "registry_id", "executor", "refresh_hz", "render_width",
    "render_height", "display_width", "display_height", "scaler_active",
    "hdr_output_mode", "source_encoding", "diagnostic_pattern",
    "bloom_strength", "current_edr_headroom",
    "display_sync_enabled", "static_geometry_heaps_enabled",
)
COMPARISON_METADATA_KEYS = tuple(
    key for key in STABLE_METADATA_KEYS
    if key not in {"commit", "dirty_worktree"}
)


class ReportError(ValueError):
    """The input cannot support an honest comparison."""


def _offline_player_uuid(name: str) -> str:
    digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(digest)))


@dataclass(frozen=True)
class TimingWindow:
    line: int
    schema: int
    timestamp_ms: int
    detail: bool
    frames: int
    fps: float
    average_ms: float
    p50_ms: float
    p95_ms: float
    p99_ms: float
    maximum_ms: float
    dropped: int
    phase: str | None
    generation: int | None
    segment: int | None
    scaler: str | None
    low_1: float | None
    low_01: float | None
    present_interval: dict[str, float] | None
    workload: dict[str, Any] | None
    metadata: dict[str, Any]


def _integer(value: Any, field: str, line: int, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ReportError(f"line {line}: {field} must be an integer >= {minimum}")
    return value


def _number(
    value: Any,
    field: str,
    line: int,
    *,
    positive: bool = False,
) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ReportError(f"line {line}: {field} must be a number")
    result = float(value)
    if not math.isfinite(result) or result < 0.0 or (positive and result <= 0.0):
        qualifier = "> 0" if positive else ">= 0"
        raise ReportError(f"line {line}: {field} must be finite and {qualifier}")
    return result


def _optional_number(value: Any, field: str, line: int) -> float | None:
    return None if value is None else _number(value, field, line)


def _metric_object(payload: dict[str, Any], schema: int, line: int) -> dict[str, Any]:
    key = "frame_ms" if schema == 1 else "presenting_command_buffer_gpu_ms"
    value = payload.get(key)
    if not isinstance(value, dict):
        raise ReportError(f"line {line}: {key} must be an object")
    return value


def _parse_present_interval(value: Any, line: int) -> dict[str, float] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ReportError(f"line {line}: present_interval_ms must be an object or null")
    samples = _integer(value.get("samples"), "present_interval_ms.samples", line, 1)
    result: dict[str, float] = {"samples": float(samples)}
    for key in ("average", "p50", "p95", "p99", "maximum"):
        result[key] = _number(value.get(key), f"present_interval_ms.{key}", line)
    if not result["p50"] <= result["p95"] <= result["p99"] <= result["maximum"]:
        raise ReportError(f"line {line}: present-interval percentiles are not monotonic")
    return result


def _exact_object(
    value: Any,
    field: str,
    line: int,
    expected_keys: frozenset[str],
) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ReportError(f"line {line}: {field} must be an object")
    actual_keys = set(value)
    if actual_keys != expected_keys:
        missing = sorted(expected_keys - actual_keys)
        unknown = sorted(actual_keys - expected_keys)
        details: list[str] = []
        if missing:
            details.append("missing " + ", ".join(missing))
        if unknown:
            details.append("unknown " + ", ".join(unknown))
        raise ReportError(
            f"line {line}: {field} must have exact keys ({'; '.join(details)})"
        )
    return value


def _parse_private_geometry_heap(value: Any, line: int) -> dict[str, Any]:
    field = "workload.private_geometry_heap"
    raw = _exact_object(value, field, line, WORKLOAD_HEAP_KEYS)
    enabled = raw["enabled"]
    if not isinstance(enabled, bool):
        raise ReportError(f"line {line}: {field}.enabled must be a boolean")

    result: dict[str, Any] = {"enabled": enabled}
    for key in (*WORKLOAD_HEAP_CONFIGURATION_KEYS, *WORKLOAD_HEAP_GAUGE_KEYS,
                *WORKLOAD_HEAP_TOTAL_KEYS):
        minimum = 1 if key in {
            "page_size_bytes", "page_limit_per_device",
            "fragmentation_probe_alignment",
        } else 0
        result[key] = _integer(raw[key], f"{field}.{key}", line, minimum)

    for key in ("page_size_bytes", "fragmentation_probe_alignment"):
        if result[key] & (result[key] - 1) != 0:
            raise ReportError(f"line {line}: {field}.{key} must be a power of two")

    def require_equal(left: str, expected: int, expression: str) -> None:
        if result[left] != expected:
            raise ReportError(
                f"line {line}: {field}.{left} must equal {expression}"
            )

    require_equal(
        "backing_allocations_total",
        result["pages_created_total"] + result["fallback_allocations_total"],
        "pages_created_total + fallback_allocations_total",
    )
    require_equal(
        "heap_allocations_total",
        result["pages_created_total"] + result["page_reuse_hits_total"],
        "pages_created_total + page_reuse_hits_total",
    )
    require_equal(
        "requests_total",
        result["heap_allocations_total"]
        + result["fallback_allocations_total"]
        + result["allocation_failures_total"],
        "heap_allocations_total + fallback_allocations_total + "
        "allocation_failures_total",
    )
    fallback_reasons = sum(
        result[key]
        for key in (
            "fallback_disabled_total",
            "fallback_oversize_total",
            "fallback_invalid_query_total",
            "fallback_capacity_total",
            "fallback_heap_create_total",
            "fallback_heap_allocate_total",
        )
    )
    if fallback_reasons != (
        result["fallback_allocations_total"] + result["allocation_failures_total"]
    ):
        raise ReportError(
            f"line {line}: {field} fallback reason totals must equal "
            "fallback_allocations_total + allocation_failures_total"
        )
    require_equal(
        "pages_current",
        result["pages_created_total"] - result["pages_retired_total"],
        "pages_created_total - pages_retired_total",
    )

    if result["pages_peak"] < result["pages_current"]:
        raise ReportError(f"line {line}: {field}.pages_peak is below pages_current")
    if result["pages_peak"] > result["pages_created_total"]:
        raise ReportError(
            f"line {line}: {field}.pages_peak exceeds pages_created_total"
        )
    if result["retire_pending_pages"] > result["pages_current"]:
        raise ReportError(
            f"line {line}: {field}.retire_pending_pages exceeds pages_current"
        )
    if result["pages_current"] > (
        result["pools_current"] * result["page_limit_per_device"]
    ):
        raise ReportError(
            f"line {line}: {field}.pages_current exceeds the per-device page limit"
        )
    if result["pages_current"] > 0 and result["pools_current"] == 0:
        raise ReportError(f"line {line}: {field} has heap pages but no device pool")
    if result["requests_total"] > 0 and result["pools_current"] == 0:
        raise ReportError(f"line {line}: {field} has requests but no device pool")
    if result["live_allocations"] > (
        result["heap_allocations_total"] + result["fallback_allocations_total"]
    ):
        raise ReportError(
            f"line {line}: {field}.live_allocations exceeds successful allocations"
        )
    if result["live_requested_bytes"] > result["requested_bytes_total"]:
        raise ReportError(
            f"line {line}: {field}.live_requested_bytes exceeds requested_bytes_total"
        )
    if result["fallback_requested_bytes_total"] > result["requested_bytes_total"]:
        raise ReportError(
            f"line {line}: {field}.fallback_requested_bytes_total exceeds "
            "requested_bytes_total"
        )

    heap_size = result["heap_size_bytes_current"]
    heap_used = result["heap_used_bytes_current"]
    if heap_size < result["pages_current"] * result["page_size_bytes"]:
        raise ReportError(
            f"line {line}: {field}.heap_size_bytes_current is too small for pages_current"
        )
    for key in (
        "heap_current_allocated_bytes",
        "heap_used_bytes_current",
        "heap_largest_available_bytes",
        "heap_fragmentation_estimate_bytes",
    ):
        if result[key] > heap_size:
            raise ReportError(f"line {line}: {field}.{key} exceeds heap_size_bytes_current")
    if result["heap_largest_available_bytes"] > heap_size - heap_used:
        raise ReportError(
            f"line {line}: {field}.heap_largest_available_bytes exceeds free heap bytes"
        )
    if result["heap_fragmentation_estimate_bytes"] > heap_size - heap_used:
        raise ReportError(
            f"line {line}: {field}.heap_fragmentation_estimate_bytes exceeds free "
            "heap bytes"
        )
    if result["pages_current"] == 0 and any(
        result[key] != 0
        for key in (
            "retire_pending_pages",
            "heap_size_bytes_current",
            "heap_current_allocated_bytes",
            "heap_used_bytes_current",
            "heap_largest_available_bytes",
            "heap_fragmentation_estimate_bytes",
        )
    ):
        raise ReportError(f"line {line}: {field} has heap gauges without current pages")

    if enabled:
        if result["fallback_disabled_total"] != 0:
            raise ReportError(
                f"line {line}: {field}.fallback_disabled_total must be zero when enabled"
            )
    else:
        forbidden = (
            "pages_current",
            "pages_peak",
            "pages_created_total",
            "pages_retired_total",
            "heap_allocations_total",
            "heap_query_bytes_total",
            "page_reuse_hits_total",
            "fallback_oversize_total",
            "fallback_invalid_query_total",
            "fallback_capacity_total",
            "fallback_heap_create_total",
            "fallback_heap_allocate_total",
        )
        if any(result[key] != 0 for key in forbidden):
            raise ReportError(
                f"line {line}: {field} contains heap activity while disabled"
            )
    return result


def _parse_workload(value: Any, line: int) -> dict[str, Any] | None:
    if value is None:
        return None
    if not isinstance(value, dict):
        raise ReportError(f"line {line}: workload must be an object")
    workload_keys = set(value)
    if workload_keys == WORKLOAD_BASE_KEYS:
        has_transient_memory = False
        has_private_geometry_heap = False
        raw = value
    elif workload_keys == WORKLOAD_EXPANDED_KEYS:
        has_transient_memory = True
        has_private_geometry_heap = False
        raw = value
    elif workload_keys == WORKLOAD_PRIVATE_GEOMETRY_HEAP_KEYS:
        has_transient_memory = True
        has_private_geometry_heap = True
        raw = value
    else:
        # The heap-extended shape is the current contract. This also reports
        # unknown keys while both legacy shapes still match exactly.
        _exact_object(value, "workload", line, WORKLOAD_PRIVATE_GEOMETRY_HEAP_KEYS)
        raise AssertionError("unreachable exact workload validation")
    encoders_raw = _exact_object(
        raw["encoders"], "workload.encoders", line, WORKLOAD_ENCODER_KEYS
    )
    encoders = {
        key: _integer(encoders_raw[key], f"workload.encoders.{key}", line)
        for key in ("render", "compute", "blit", "pass_boundaries")
    }
    expected_boundaries = encoders["render"] + encoders["compute"] + encoders["blit"]
    if encoders["pass_boundaries"] != expected_boundaries:
        raise ReportError(
            f"line {line}: workload.encoders.pass_boundaries must equal "
            "render + compute + blit"
        )

    copy_raw = _exact_object(
        raw["copy_bytes"], "workload.copy_bytes", line, WORKLOAD_COPY_KEYS
    )
    copy_bytes: dict[str, Any] = {
        key: _integer(copy_raw[key], f"workload.copy_bytes.{key}", line)
        for key in (*WORKLOAD_COPY_BYTE_KEYS, *WORKLOAD_COPY_COMMAND_KEYS)
    }
    direct_write = copy_raw["direct_write_observed"]
    if not isinstance(direct_write, bool):
        raise ReportError(
            f"line {line}: workload.copy_bytes.direct_write_observed must be a boolean"
        )
    copy_bytes["direct_write_observed"] = direct_write
    for byte_key in WORKLOAD_COPY_BYTE_KEYS:
        command_key = f"{byte_key}_commands"
        if copy_bytes[byte_key] > 0 and copy_bytes[command_key] == 0:
            raise ReportError(
                f"line {line}: workload.copy_bytes.{byte_key} is positive but "
                f"{command_key} is zero"
            )
    classified_copy_commands = sum(
        copy_bytes[key]
        for key in WORKLOAD_COPY_COMMAND_KEYS
        if key != "byte_count_unknown_commands"
    )
    if copy_bytes["byte_count_unknown_commands"] > classified_copy_commands:
        raise ReportError(
            f"line {line}: workload.copy_bytes.byte_count_unknown_commands exceeds "
            "classified copy commands"
        )

    allocations_raw = _exact_object(
        raw["resource_allocations"],
        "workload.resource_allocations",
        line,
        WORKLOAD_RESOURCE_KEYS,
    )
    allocations: dict[str, dict[str, int]] = {}
    for kind in WORKLOAD_RESOURCE_KINDS:
        allocation_raw = _exact_object(
            allocations_raw[kind],
            f"workload.resource_allocations.{kind}",
            line,
            WORKLOAD_ALLOCATION_KEYS,
        )
        allocations[kind] = {
            key: _integer(
                allocation_raw[key],
                f"workload.resource_allocations.{kind}.{key}",
                line,
            )
            for key in ("count", "bytes")
        }

    result = {
        "command_buffers": _integer(
            raw["command_buffers"], "workload.command_buffers", line
        ),
        "encoders": encoders,
        "copy_bytes": copy_bytes,
        "resource_allocations": allocations,
    }
    if has_transient_memory:
        transient_raw = _exact_object(
            raw["transient_memory"],
            "workload.transient_memory",
            line,
            WORKLOAD_TRANSIENT_KEYS,
        )
        transient: dict[str, dict[str, int]] = {}
        for kind in WORKLOAD_TRANSIENT_KINDS:
            kind_raw = _exact_object(
                transient_raw[kind],
                f"workload.transient_memory.{kind}",
                line,
                WORKLOAD_TRANSIENT_HIGH_WATER_KEYS,
            )
            requested = _integer(
                kind_raw["requested_high_water_bytes"],
                f"workload.transient_memory.{kind}.requested_high_water_bytes",
                line,
            )
            reserved = _integer(
                kind_raw["reserved_high_water_bytes"],
                f"workload.transient_memory.{kind}.reserved_high_water_bytes",
                line,
            )
            if reserved < requested:
                raise ReportError(
                    f"line {line}: workload.transient_memory.{kind}."
                    "reserved_high_water_bytes must be >= requested_high_water_bytes"
                )
            transient[kind] = {
                "requested_high_water_bytes": requested,
                "reserved_high_water_bytes": reserved,
            }
        result["transient_memory"] = transient
    if has_private_geometry_heap:
        result["private_geometry_heap"] = _parse_private_geometry_heap(
            raw["private_geometry_heap"],
            line,
        )
    return result


def _parse_window(payload: Any, line: int) -> TimingWindow:
    if not isinstance(payload, dict):
        raise ReportError(f"line {line}: JSON value must be an object")
    schema = _integer(payload.get("schema_version"), "schema_version", line, 1)
    if schema not in (1, 2):
        raise ReportError(f"line {line}: unsupported schema_version {schema}")
    detail = payload.get("detail_enabled")
    if not isinstance(detail, bool):
        raise ReportError(f"line {line}: detail_enabled must be a boolean")
    metric = _metric_object(payload, schema, line)

    benchmark: dict[str, Any] = {}
    metadata: dict[str, Any] = {}
    if schema == 2:
        if not isinstance(payload.get("benchmark"), dict):
            raise ReportError(f"line {line}: benchmark must be an object")
        if not isinstance(payload.get("metadata"), dict):
            raise ReportError(f"line {line}: metadata must be an object")
        benchmark = payload["benchmark"]
        metadata = payload["metadata"]

    phase = benchmark.get("phase") if schema == 2 else None
    segment: int | None = None
    if schema == 2:
        raw_segment = benchmark.get("segment_index")
        if phase == "startup":
            if isinstance(raw_segment, bool) or raw_segment != -1:
                raise ReportError(
                    f"line {line}: startup benchmark.segment_index must be -1"
                )
            segment = -1
        else:
            segment = _integer(raw_segment, "benchmark.segment_index", line)

    window = TimingWindow(
        line=line,
        schema=schema,
        timestamp_ms=_integer(payload.get("timestamp_unix_ms"), "timestamp_unix_ms", line),
        detail=detail,
        frames=_integer(payload.get("presented_frames"), "presented_frames", line, 1),
        fps=_number(payload.get("fps"), "fps", line, positive=True),
        average_ms=_number(metric.get("average"), "gpu.average", line),
        p50_ms=_number(metric.get("p50"), "gpu.p50", line),
        p95_ms=_number(metric.get("p95"), "gpu.p95", line),
        p99_ms=_number(metric.get("p99"), "gpu.p99", line),
        maximum_ms=_number(metric.get("maximum"), "gpu.maximum", line),
        dropped=_integer(payload.get("dropped_timing_events"), "dropped_timing_events", line),
        phase=phase,
        generation=(
            _integer(benchmark.get("generation"), "benchmark.generation", line)
            if schema == 2 else None
        ),
        segment=segment,
        scaler=benchmark.get("scaler_mode") if schema == 2 else None,
        low_1=_optional_number(payload.get("fps_1_percent_low"), "fps_1_percent_low", line),
        low_01=_optional_number(payload.get("fps_0_1_percent_low"), "fps_0_1_percent_low", line),
        present_interval=(
            _parse_present_interval(payload.get("present_interval_ms"), line)
            if schema == 2 else None
        ),
        workload=(
            _parse_workload(payload.get("workload"), line)
            if schema == 2 else None
        ),
        metadata=metadata,
    )
    if not window.p50_ms <= window.p95_ms <= window.p99_ms <= window.maximum_ms:
        raise ReportError(f"line {line}: GPU percentiles/maximum are not monotonic")
    if window.average_ms > window.maximum_ms:
        raise ReportError(f"line {line}: GPU average exceeds maximum")
    if schema == 2:
        for field, value in (("phase", window.phase), ("scaler_mode", window.scaler)):
            if not isinstance(value, str) or not value:
                raise ReportError(f"line {line}: benchmark.{field} must be a string")
        if window.workload is not None and "private_geometry_heap" in window.workload:
            metadata_mode = window.metadata.get("static_geometry_heaps_enabled")
            if not isinstance(metadata_mode, bool):
                raise ReportError(
                    f"line {line}: metadata.static_geometry_heaps_enabled must be "
                    "a boolean for private geometry heap telemetry"
                )
            if metadata_mode != window.workload["private_geometry_heap"]["enabled"]:
                raise ReportError(
                    f"line {line}: metadata.static_geometry_heaps_enabled differs "
                    "from workload.private_geometry_heap.enabled"
                )
    return window


def load_report(path: Path) -> list[TimingWindow]:
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ReportError(f"cannot read {path}: {error}") from error
    windows: list[TimingWindow] = []
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError as error:
            raise ReportError(f"line {line_number}: invalid JSON: {error.msg}") from error
        windows.append(_parse_window(payload, line_number))
    if not windows:
        raise ReportError(f"{path} contains no timing windows")
    for previous, current in zip(windows, windows[1:]):
        if current.timestamp_ms < previous.timestamp_ms:
            raise ReportError(
                f"line {current.line}: timestamp precedes line {previous.line}"
            )
    return windows


def _tail_exact(windows: Sequence[TimingWindow], frames: int) -> list[TimingWindow]:
    selected: list[TimingWindow] = []
    total = 0
    for window in reversed(windows):
        selected.append(window)
        total += window.frames
        if total >= frames:
            break
    if total != frames:
        raise ReportError(
            f"cannot select exactly {frames} tail frames from complete windows (got {total})"
        )
    return list(reversed(selected))


def select_measurement(
    windows: Sequence[TimingWindow],
    frames: int,
    segment: int,
    scaler: str,
) -> tuple[list[TimingWindow], str]:
    if frames <= 0:
        raise ReportError("measurement frame count must be > 0")
    if not any(window.schema == 2 for window in windows):
        return _tail_exact(windows, frames), f"schema-v1 tail of exactly {frames} frames"

    candidates = [
        window for window in windows
        if window.schema == 2
        and window.phase == "measure"
        and window.segment == segment
        and window.scaler == scaler
    ]
    if not candidates:
        raise ReportError(
            f"no schema-v2 measure windows for segment {segment}, scaler {scaler}"
        )
    generations = {window.generation for window in candidates}
    if len(generations) != 1:
        raise ReportError(f"measurement selection mixes generations: {sorted(generations)}")
    total = sum(window.frames for window in candidates)
    if total != frames:
        raise ReportError(
            f"schema-v2 measurement contains {total} complete frames, expected {frames}"
        )
    return candidates, (
        f"schema-v2 phase=measure segment={segment} scaler={scaler} generation="
        f"{next(iter(generations))}"
    )


def _weighted(windows: Sequence[TimingWindow], field: str) -> float:
    total = sum(window.frames for window in windows)
    return sum(getattr(window, field) * window.frames for window in windows) / total


def _series(values: Sequence[float], windows: Sequence[TimingWindow]) -> dict[str, float]:
    total = sum(window.frames for window in windows)
    weighted = sum(value * window.frames for value, window in zip(values, windows)) / total
    return {
        "window_frame_weighted_mean": weighted,
        "window_median": statistics.median(values),
        "window_minimum": min(values),
        "window_maximum": max(values),
    }


def _aggregate_workload(
    windows: Sequence[TimingWindow],
) -> dict[str, Any] | None:
    present = [window.workload is not None for window in windows]
    if not any(present):
        return None
    if not all(present):
        missing_lines = [str(window.line) for window in windows if window.workload is None]
        raise ReportError(
            "selected windows mix workload telemetry presence; missing at line(s) "
            + ", ".join(missing_lines)
        )

    workload_windows = [window.workload for window in windows]
    expanded = ["transient_memory" in workload for workload in workload_windows]
    if any(expanded) and not all(expanded):
        raise ReportError(
            "selected windows mix base and expanded workload telemetry shapes"
        )
    heap_extended = [
        "private_geometry_heap" in workload for workload in workload_windows
    ]
    if any(heap_extended) and not all(heap_extended):
        raise ReportError(
            "selected windows mix legacy and private-geometry-heap workload shapes"
        )
    total_frames = sum(window.frames for window in windows)

    def summed(path: tuple[str, ...]) -> int:
        result = 0
        for workload in workload_windows:
            value: Any = workload
            for key in path:
                value = value[key]
            result += value
        return result

    totals: dict[str, Any] = {
        "command_buffers": summed(("command_buffers",)),
        "encoders": {
            key: summed(("encoders", key))
            for key in ("render", "compute", "blit", "pass_boundaries")
        },
        "copy_bytes": {
            key: summed(("copy_bytes", key))
            for key in (*WORKLOAD_COPY_BYTE_KEYS, *WORKLOAD_COPY_COMMAND_KEYS)
        },
        "resource_allocations": {
            kind: {
                key: summed(("resource_allocations", kind, key))
                for key in ("count", "bytes")
            }
            for kind in WORKLOAD_RESOURCE_KINDS
        },
    }

    def per_frame(value: Any) -> Any:
        if isinstance(value, dict):
            return {key: per_frame(child) for key, child in value.items()}
        return value / total_frames

    expanded_shape = all(expanded)
    aggregation = (
        "Counters are per-report-window totals. aggregate_totals sums selected "
        "windows; per_presented_frame divides those totals by selected frames."
    )
    if expanded_shape:
        aggregation += (
            " Transient memory values are high-water marks and are aggregated only "
            "with maxima."
        )
        observability = (
            "GPU copy counters cover instrumented Metal encoder wrappers. CPU-to-"
            "shared bytes cover known upload/multiUpload paths and dynamic orphan "
            "writes including preserved ranges; generic externally mapped writes "
            "are excluded, so zero does not prove absence. Resource allocation "
            "counters cover successful exported metallum_create_buffer/"
            "metallum_create_texture_2d calls only; internal workspace, telemetry, "
            "and view allocations are outside coverage."
        )
    else:
        observability = (
            "Copy counters cover instrumented Metal encoder wrappers. CPU direct/"
            "shared writes outside those wrappers are not observable; zero byte "
            "counts and a false direct-write flag do not prove absence. Resource "
            "allocation counters cover successful exported metallum_create_buffer/"
            "metallum_create_texture_2d calls only; internal workspace, telemetry, "
            "and view allocations are outside coverage."
        )

    result = {
        "aggregation": aggregation,
        "observability": observability,
        "aggregate_totals": totals,
        "per_presented_frame": per_frame(totals),
        "direct_write_observed_any_window": any(
            workload["copy_bytes"]["direct_write_observed"]
            for workload in workload_windows
        ),
        "selected_window_totals": [
            {
                "source_line": window.line,
                "presented_frames": window.frames,
                "workload": window.workload,
            }
            for window in windows
        ],
    }
    if expanded_shape:
        result["transient_memory_high_water"] = {
            kind: {
                key: max(
                    workload["transient_memory"][kind][key]
                    for workload in workload_windows
                )
                for key in (
                    "requested_high_water_bytes",
                    "reserved_high_water_bytes",
                )
            }
            for kind in WORKLOAD_TRANSIENT_KINDS
        }
    if all(heap_extended):
        heaps = [workload["private_geometry_heap"] for workload in workload_windows]
        configuration_keys = ("enabled", *WORKLOAD_HEAP_CONFIGURATION_KEYS)
        for key in configuration_keys:
            values = [heap[key] for heap in heaps]
            if len(set(values)) != 1:
                raise ReportError(
                    "selected windows change private geometry heap configuration: "
                    + key
                )
        for key in WORKLOAD_HEAP_TOTAL_KEYS:
            values = [heap[key] for heap in heaps]
            if any(current < previous for previous, current in zip(values, values[1:])):
                raise ReportError(
                    "selected windows decrease private geometry heap cumulative total: "
                    + key
                )
        first_heap = heaps[0]
        last_heap = heaps[-1]
        result["private_geometry_heap"] = {
            "aggregation": (
                "Heap telemetry is a process snapshot, never a per-window total. "
                "Cumulative totals use the last snapshot for process-lifetime proof "
                "and an explicitly named last-minus-first delta; current gauges use "
                "the last snapshot and maxima across selected snapshots."
            ),
            "configuration": {
                key: last_heap[key] for key in configuration_keys
            },
            "process_lifetime_totals_at_last_window": {
                key: last_heap[key] for key in WORKLOAD_HEAP_TOTAL_KEYS
            },
            "deltas_between_first_and_last_snapshot": {
                key: last_heap[key] - first_heap[key]
                for key in WORKLOAD_HEAP_TOTAL_KEYS
            },
            "gauges_at_last_window": {
                key: last_heap[key] for key in WORKLOAD_HEAP_GAUGE_KEYS
            },
            "gauge_maxima_across_selected_windows": {
                key: max(heap[key] for heap in heaps)
                for key in WORKLOAD_HEAP_GAUGE_KEYS
            },
        }
    return result


def validate_selected_metadata_consistency(windows: Sequence[TimingWindow]) -> None:
    reference = windows[0].metadata
    for window in windows[1:]:
        mismatches = [
            key for key in STABLE_METADATA_KEYS
            if window.metadata.get(key) != reference.get(key)
        ]
        if mismatches:
            raise ReportError(
                f"line {window.line}: selected-window metadata changed: "
                + ", ".join(mismatches)
            )


def validate_release_contract(
    windows: Sequence[TimingWindow],
    *,
    workload_contract: str = WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP,
    scaler: str,
    source_sha256: str,
    artifact_sha256: str,
    settings_id: str,
    settings_spec_sha256: str,
    settings_sha256: str,
    world: str,
    fixture: str,
    fixture_sha256: str,
    route: str,
    route_sha256: str,
    player_name: str,
    player_uuid: str,
    dimension: str,
    simulation_frozen: bool,
) -> None:
    if workload_contract not in WORKLOAD_CONTRACTS:
        raise ReportError(f"unknown release workload contract {workload_contract!r}")
    if not SHA256_RE.fullmatch(source_sha256):
        raise ReportError("release source digest must be lowercase SHA-256")
    if not SHA256_RE.fullmatch(artifact_sha256):
        raise ReportError("release build-artifact digest must be lowercase SHA-256")
    if not SAFE_ID_RE.fullmatch(settings_id):
        raise ReportError("release settings ID is invalid")
    if not SHA256_RE.fullmatch(settings_spec_sha256):
        raise ReportError("release settings spec digest must be lowercase SHA-256")
    if not SHA256_RE.fullmatch(settings_sha256):
        raise ReportError("release settings digest must be lowercase SHA-256")
    if fixture != world:
        raise ReportError("release benchmark world and fixture identifiers must match")
    if not SHA256_RE.fullmatch(fixture_sha256):
        raise ReportError("release fixture digest must be lowercase SHA-256")
    if not SHA256_RE.fullmatch(route_sha256):
        raise ReportError("release route digest must be lowercase SHA-256")
    if not PLAYER_RE.fullmatch(player_name):
        raise ReportError("release player name must be a valid offline profile name")
    try:
        normalized_player_uuid = str(uuid.UUID(player_uuid))
    except ValueError as error:
        raise ReportError("release player UUID is invalid") from error
    if normalized_player_uuid != player_uuid:
        raise ReportError("release player UUID must use canonical lowercase form")
    expected_player_uuid = _offline_player_uuid(player_name)
    if player_uuid != expected_player_uuid:
        raise ReportError(
            f"release player UUID must be offline UUID {expected_player_uuid} for {player_name}"
        )
    if not DIMENSION_RE.fullmatch(dimension):
        raise ReportError("release benchmark dimension is invalid")
    if not simulation_frozen:
        raise ReportError("release benchmark simulation must be frozen")
    presented_frames = sum(window.frames for window in windows)
    if presented_frames != DEFAULT_MEASURE_FRAMES:
        raise ReportError(
            f"release benchmark requires exactly {DEFAULT_MEASURE_FRAMES} measured "
            f"frames (found {presented_frames})"
        )
    if len(windows) != 10 or any(window.frames != 300 for window in windows):
        raise ReportError(
            "release benchmark requires exactly ten complete 300-frame timing windows"
        )

    expected_scaling = scaler != "OFF"
    for window in windows:
        if window.schema != 2:
            raise ReportError(f"line {window.line}: release contract requires schema v2")
        if window.detail:
            raise ReportError(f"line {window.line}: intrusive detail timing must be disabled")
        if window.low_1 is None or window.low_01 is None:
            raise ReportError(
                f"line {window.line}: release contract requires 1% and 0.1% FPS lows"
            )
        if window.present_interval is None:
            raise ReportError(
                f"line {window.line}: release contract requires present-interval timing"
            )
        if workload_contract == WORKLOAD_CONTRACT_NONE:
            if window.workload is not None:
                raise ReportError(
                    f"line {window.line}: legacy no-workload contract rejects "
                    "workload telemetry"
                )
        elif window.workload is None:
            raise ReportError(
                f"line {window.line}: release contract requires workload telemetry"
            )
        elif workload_contract == WORKLOAD_CONTRACT_BASE:
            if "transient_memory" in window.workload:
                raise ReportError(
                    f"line {window.line}: legacy base workload contract rejects "
                    "expanded transient-memory telemetry"
                )
        elif workload_contract == WORKLOAD_CONTRACT_EXPANDED:
            if "transient_memory" not in window.workload:
                raise ReportError(
                    f"line {window.line}: release contract requires expanded workload "
                    "telemetry with transient_memory"
                )
            if "private_geometry_heap" in window.workload:
                raise ReportError(
                    f"line {window.line}: legacy expanded workload contract rejects "
                    "private geometry heap telemetry"
                )
        else:
            if "transient_memory" not in window.workload:
                raise ReportError(
                    f"line {window.line}: release contract requires expanded workload "
                    "telemetry with transient_memory"
                )
            if "private_geometry_heap" not in window.workload:
                raise ReportError(
                    f"line {window.line}: release contract requires exact "
                    "workload.private_geometry_heap telemetry"
                )
        metadata = window.metadata
        expected = {
            "monitor": BUILT_IN_MONITOR,
            "device_name": "Apple M1 Pro",
            "display_width": BUILT_IN_WIDTH,
            "display_height": BUILT_IN_HEIGHT,
            "refresh_hz": 120,
            "display_sync_enabled": False,
            "hdr_output_mode": "ENHANCED",
            "source_encoding": "LINEAR",
            "executor": "METAL3",
            "scaler_active": expected_scaling,
            "source_sha256": source_sha256,
            "artifact_sha256": artifact_sha256,
            "settings_id": settings_id,
            "settings_spec_sha256": settings_spec_sha256,
            "settings_sha256": settings_sha256,
            "world": world,
            "fixture": fixture,
            "fixture_sha256": fixture_sha256,
            "route": route,
            "route_sha256": route_sha256,
            "benchmark_player_name": player_name,
            "benchmark_player_uuid": player_uuid,
            "benchmark_dimension": dimension,
            "benchmark_simulation_frozen": True,
        }
        if workload_contract == WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP:
            expected["static_geometry_heaps_enabled"] = window.workload[
                "private_geometry_heap"
            ]["enabled"]
        for key, value in expected.items():
            if metadata.get(key) != value:
                raise ReportError(
                    f"line {window.line}: metadata.{key} must be {value!r} "
                    f"(found {metadata.get(key)!r})"
                )
        for key in (
            "resource_packs_sha256",
            "sodium_settings_sha256",
        ):
            value = metadata.get(key)
            if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
                raise ReportError(
                    f"line {window.line}: metadata.{key} must be lowercase SHA-256"
                )
        numeric_expectations = {
            "max_fps": 260,
            "configured_gui_scale": 0,
            "sodium_chunk_builder_threads": 4,
        }
        for key, value in numeric_expectations.items():
            if metadata.get(key) != value:
                raise ReportError(
                    f"line {window.line}: metadata.{key} must be {value!r} "
                    f"(found {metadata.get(key)!r})"
                )
        if metadata.get("diagnostic_pattern") is not False:
            raise ReportError(
                f"line {window.line}: HDR diagnostic pattern must be disabled"
            )
        if metadata.get("persistent_metalfx_mode") != "off":
            raise ReportError(
                f"line {window.line}: persistent MetalFX mode must remain off"
            )
        for key, expected_value in (
            ("hdr_strength", 1.0),
            ("bloom_strength", 0.18),
            ("hdr_bloom_strength", 0.18),
        ):
            actual = metadata.get(key)
            if isinstance(actual, bool) or not isinstance(actual, (int, float)) \
                    or not math.isfinite(float(actual)) \
                    or abs(float(actual) - expected_value) > 1e-6:
                raise ReportError(
                    f"line {window.line}: metadata.{key} must be {expected_value} "
                    f"(found {actual!r})"
                )
        if scaler == "OFF" and (
            metadata.get("render_width") != BUILT_IN_WIDTH
            or metadata.get("render_height") != BUILT_IN_HEIGHT
        ):
            raise ReportError(f"line {window.line}: native render size is not 3024x1964")
        thermal = metadata.get("thermal_state")
        if thermal != "nominal":
            raise ReportError(
                f"line {window.line}: release benchmark requires nominal thermal state "
                f"(found {thermal!r})"
            )
        headroom = metadata.get("current_edr_headroom")
        if isinstance(headroom, bool) or not isinstance(headroom, (int, float)) \
                or not math.isfinite(float(headroom)) or float(headroom) <= 1.0:
            raise ReportError(
                f"line {window.line}: release HDR requires current EDR headroom > 1.0 "
                f"(found {headroom!r})"
            )


def summarize(
    path: Path,
    frames: int,
    segment: int,
    scaler: str,
    *,
    release_contract: bool = False,
    workload_contract: str = WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP,
    source_sha256: str = "unknown",
    artifact_sha256: str = "unknown",
    settings_id: str = "unknown",
    settings_spec_sha256: str = "unknown",
    settings_sha256: str = "unknown",
    world: str = "HDRTest",
    fixture: str = "unknown",
    fixture_sha256: str = "unknown",
    route: str = "static-heavy",
    route_sha256: str = "unknown",
    player_name: str = "unknown",
    player_uuid: str = "unknown",
    dimension: str = "unknown",
    simulation_frozen: bool = False,
) -> dict[str, Any]:
    all_windows = load_report(path)
    selected, selection = select_measurement(all_windows, frames, segment, scaler)
    if len({window.detail for window in selected}) != 1:
        raise ReportError("selected windows mix detailed and basic instrumentation")
    if selected[0].schema == 2:
        validate_selected_metadata_consistency(selected)
    dropped = sum(window.dropped for window in selected)
    if dropped:
        raise ReportError(f"selected windows contain {dropped} dropped timing events")
    if release_contract:
        validate_release_contract(
            selected,
            workload_contract=workload_contract,
            scaler=scaler,
            source_sha256=source_sha256,
            artifact_sha256=artifact_sha256,
            settings_id=settings_id,
            settings_spec_sha256=settings_spec_sha256,
            settings_sha256=settings_sha256,
            world=world,
            fixture=fixture,
            fixture_sha256=fixture_sha256,
            route=route,
            route_sha256=route_sha256,
            player_name=player_name,
            player_uuid=player_uuid,
            dimension=dimension,
            simulation_frozen=simulation_frozen,
        )

    elapsed = sum(window.frames / window.fps for window in selected)
    gpu_percentiles = {
        key: _series([getattr(window, f"{key}_ms") for window in selected], selected)
        for key in ("p50", "p95", "p99")
    }
    result: dict[str, Any] = {
        "report": str(path),
        "selection": selection,
        "window_count": len(selected),
        "presented_frames": sum(window.frames for window in selected),
        "source_lines": [selected[0].line, selected[-1].line],
        "schema_versions": sorted({window.schema for window in selected}),
        "detail_enabled": selected[0].detail,
        "dropped_timing_events": dropped,
        "fps": {
            "elapsed_weighted": sum(window.frames for window in selected) / elapsed,
            "window_median": statistics.median(window.fps for window in selected),
            "window_minimum": min(window.fps for window in selected),
            "window_maximum": max(window.fps for window in selected),
        },
        "presenting_command_buffer_gpu_ms": {
            "window_frame_weighted_average": _weighted(selected, "average_ms"),
            "percentile_window_summaries": gpu_percentiles,
            "maximum_observed_in_any_window": max(window.maximum_ms for window in selected),
        },
        "exact_aggregate_percentiles_available": False,
        "interpretation": (
            "Percentiles and lows are summaries of independent complete report windows; "
            "raw samples are not available for an exact merged percentile."
        ),
    }
    if all(window.low_1 is not None and window.low_01 is not None for window in selected):
        result["fps_low_window_summaries"] = {
            "one_percent": _series([window.low_1 for window in selected], selected),  # type: ignore[list-item]
            "zero_point_one_percent": _series(
                [window.low_01 for window in selected], selected  # type: ignore[list-item]
            ),
        }
    intervals = [window.present_interval for window in selected]
    if all(value is not None for value in intervals):
        result["present_interval_ms"] = {
            key: _series([value[key] for value in intervals], selected)  # type: ignore[index]
            for key in ("average", "p50", "p95", "p99", "maximum")
        }
    workload = _aggregate_workload(selected)
    if workload is not None:
        result["workload"] = workload
    if selected[0].schema == 2:
        result["metadata"] = selected[-1].metadata
    return result


def compare(
    baseline: dict[str, Any],
    candidate: dict[str, Any],
    gate_ms: float,
    *,
    allow_source_change: bool = False,
    allow_static_geometry_heap_mode_change: bool = False,
    require_stability: bool = True,
) -> dict[str, Any]:
    if not math.isfinite(gate_ms) or gate_ms < 0.0:
        raise ReportError("p95 gate must be finite and >= 0")

    def required_field(summary: dict[str, Any], key: str, label: str) -> Any:
        if key not in summary:
            raise ReportError(f"{label} lacks {key} required by compare")
        return summary[key]

    baseline_frames = required_field(baseline, "presented_frames", "baseline")
    candidate_frames = required_field(candidate, "presented_frames", "candidate")
    if baseline_frames != candidate_frames:
        raise ReportError("baseline and candidate cover different frame counts")
    baseline_windows = required_field(baseline, "window_count", "baseline")
    candidate_windows = required_field(candidate, "window_count", "candidate")
    if baseline_windows != candidate_windows:
        raise ReportError("baseline and candidate use different timing-window counts")
    baseline_detail = required_field(baseline, "detail_enabled", "baseline")
    candidate_detail = required_field(candidate, "detail_enabled", "candidate")
    if baseline_detail != candidate_detail:
        raise ReportError("baseline and candidate use different detail instrumentation")
    if require_stability and (
        not isinstance(baseline.get("metadata"), dict)
        or not isinstance(candidate.get("metadata"), dict)
    ):
        raise ReportError("strict compare requires release metadata for both reports")
    if allow_static_geometry_heap_mode_change:
        if not require_stability:
            raise ReportError(
                "static geometry heap mode override requires attested reports"
            )
        baseline_mode = baseline["metadata"].get("static_geometry_heaps_enabled")
        candidate_mode = candidate["metadata"].get("static_geometry_heaps_enabled")
        if not isinstance(baseline_mode, bool) or not isinstance(candidate_mode, bool) \
                or baseline_mode == candidate_mode:
            raise ReportError(
                "static geometry heap mode override requires an explicit OFF-vs-ON pair"
            )
        for summary, label, mode in (
            (baseline, "baseline", baseline_mode),
            (candidate, "candidate", candidate_mode),
        ):
            try:
                workload_mode = summary["workload"]["private_geometry_heap"][
                    "configuration"
                ]["enabled"]
            except (KeyError, TypeError) as error:
                raise ReportError(
                    f"{label} lacks private geometry heap workload proof"
                ) from error
            if workload_mode != mode:
                raise ReportError(
                    f"{label} static geometry heap metadata/workload modes differ"
                )
    if isinstance(baseline.get("metadata"), dict) \
            and isinstance(candidate.get("metadata"), dict):
        comparison_keys = tuple(
            key for key in COMPARISON_METADATA_KEYS
            if not (
                allow_source_change
                and key in {"source_sha256", "artifact_sha256"}
            )
            and not (
                allow_static_geometry_heap_mode_change
                and key == "static_geometry_heaps_enabled"
            )
        )
        mismatches = [
            key for key in comparison_keys
            if baseline["metadata"].get(key) != candidate["metadata"].get(key)
        ]
        if mismatches:
            raise ReportError(
                "baseline and candidate metadata differ: " + ", ".join(mismatches)
            )
    baseline_runtime = baseline.get("attested_runtime")
    candidate_runtime = candidate.get("attested_runtime")
    if require_stability and (
        not isinstance(baseline_runtime, dict)
        or not isinstance(candidate_runtime, dict)
    ):
        raise ReportError("strict compare requires attested runtime contracts")
    if baseline_runtime != candidate_runtime:
        raise ReportError("baseline and candidate attested runtime contracts differ")

    def metric(summary: dict[str, Any], key: str) -> float:
        try:
            value = summary["presenting_command_buffer_gpu_ms"][
                "percentile_window_summaries"
            ][key]["window_frame_weighted_mean"]
        except (KeyError, TypeError) as error:
            raise ReportError(f"compare input lacks GPU {key} summary") from error
        if isinstance(value, bool) or not isinstance(value, (int, float)) \
                or not math.isfinite(float(value)):
            raise ReportError(f"compare input has invalid GPU {key} summary")
        return float(value)

    base_p95 = metric(baseline, "p95")
    candidate_p95 = metric(candidate, "p95")
    delta = candidate_p95 - base_p95
    regressions: list[dict[str, Any]] = []

    def absolute_upper_gate(
        name: str,
        baseline_value: float,
        candidate_value: float,
        threshold: float,
    ) -> None:
        if candidate_value - baseline_value > threshold + 1e-12:
            regressions.append({
                "metric": name,
                "baseline": baseline_value,
                "candidate": candidate_value,
                "delta": candidate_value - baseline_value,
                "limit": threshold,
                "limit_kind": "absolute increase",
            })

    def fractional_upper_gate(
        name: str,
        baseline_value: float,
        candidate_value: float,
        fraction: float,
    ) -> None:
        if candidate_value > baseline_value * (1.0 + fraction) + 1e-12:
            regressions.append({
                "metric": name,
                "baseline": baseline_value,
                "candidate": candidate_value,
                "delta_fraction": (
                    candidate_value / baseline_value - 1.0
                    if baseline_value != 0.0 else None
                ),
                "limit": fraction,
                "limit_kind": "fractional increase",
            })

    def fractional_lower_gate(
        name: str,
        baseline_value: float,
        candidate_value: float,
        fraction: float,
    ) -> None:
        if candidate_value < baseline_value * (1.0 - fraction) - 1e-12:
            regressions.append({
                "metric": name,
                "baseline": baseline_value,
                "candidate": candidate_value,
                "delta_fraction": (
                    candidate_value / baseline_value - 1.0
                    if baseline_value != 0.0 else None
                ),
                "limit": fraction,
                "limit_kind": "fractional decrease",
            })

    absolute_upper_gate("GPU p95", base_p95, candidate_p95, gate_ms)
    if require_stability:
        for summary, label in ((baseline, "baseline"), (candidate, "candidate")):
            if "fps_low_window_summaries" not in summary:
                raise ReportError(f"{label} lacks FPS low summaries required by strict compare")
            if "present_interval_ms" not in summary:
                raise ReportError(
                    f"{label} lacks present-interval summaries required by strict compare"
                )

        absolute_upper_gate(
            "GPU p99",
            metric(baseline, "p99"),
            metric(candidate, "p99"),
            DEFAULT_P99_GATE_MS,
        )
        absolute_upper_gate(
            "GPU worst window maximum",
            baseline["presenting_command_buffer_gpu_ms"]["maximum_observed_in_any_window"],
            candidate["presenting_command_buffer_gpu_ms"]["maximum_observed_in_any_window"],
            DEFAULT_GPU_WORST_GATE_MS,
        )
        fractional_lower_gate(
            "FPS elapsed-weighted",
            baseline["fps"]["elapsed_weighted"],
            candidate["fps"]["elapsed_weighted"],
            DEFAULT_FPS_REGRESSION_FRACTION,
        )
        fractional_lower_gate(
            "FPS minimum window",
            baseline["fps"]["window_minimum"],
            candidate["fps"]["window_minimum"],
            DEFAULT_FPS_REGRESSION_FRACTION,
        )
        for key, name, fraction in (
            ("one_percent", "FPS 1% low", DEFAULT_ONE_PERCENT_LOW_REGRESSION_FRACTION),
            (
                "zero_point_one_percent",
                "FPS 0.1% low",
                DEFAULT_ZERO_POINT_ONE_LOW_REGRESSION_FRACTION,
            ),
        ):
            fractional_lower_gate(
                name,
                baseline["fps_low_window_summaries"][key]["window_frame_weighted_mean"],
                candidate["fps_low_window_summaries"][key]["window_frame_weighted_mean"],
                fraction,
            )
        for key, name, fraction in (
            ("p95", "present interval p95", DEFAULT_PRESENT_P95_REGRESSION_FRACTION),
            ("p99", "present interval p99", DEFAULT_PRESENT_P99_REGRESSION_FRACTION),
        ):
            fractional_upper_gate(
                name,
                baseline["present_interval_ms"][key]["window_frame_weighted_mean"],
                candidate["present_interval_ms"][key]["window_frame_weighted_mean"],
                fraction,
            )
        absolute_upper_gate(
            "present interval worst window maximum",
            baseline["present_interval_ms"]["maximum"]["window_maximum"],
            candidate["present_interval_ms"]["maximum"]["window_maximum"],
            DEFAULT_PRESENT_WORST_GATE_MS,
        )

    if regressions:
        verdict = "REGRESSION"
    elif delta < -gate_ms - 1e-12:
        verdict = "IMPROVEMENT"
    else:
        verdict = "WITHIN_THRESHOLD"
    return {
        "verdict": verdict,
        "gate": {
            "metric": "GPU p95 window-frame-weighted mean",
            "threshold_ms": gate_ms,
            "baseline_ms": base_p95,
            "candidate_ms": candidate_p95,
            "delta_ms": delta,
            "regressions": regressions,
        },
        "metrics": {
            key: {
                "baseline": metric(baseline, key),
                "candidate": metric(candidate, key),
                "delta": metric(candidate, key) - metric(baseline, key),
            }
            for key in ("p50", "p95", "p99")
        },
        "baseline": baseline,
        "candidate": candidate,
    }


def _file_sha256(path: Path) -> str:
    hasher = hashlib.sha256()
    try:
        with path.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                hasher.update(chunk)
    except OSError as error:
        raise ReportError(f"cannot hash {path}: {error}") from error
    return hasher.hexdigest()


def _existing_file(path: Path, label: str) -> Path:
    try:
        resolved = path.resolve(strict=True)
    except OSError as error:
        raise ReportError(f"{label} does not exist: {path}: {error}") from error
    if not resolved.is_file():
        raise ReportError(f"{label} is not a regular file: {resolved}")
    return resolved


def _artifact_paths(raw_report: Path) -> dict[str, Path]:
    suffix = ".raw.jsonl"
    if not raw_report.name.endswith(suffix):
        raise ReportError("benchmark acceptance requires a *.raw.jsonl report")
    stem = raw_report.name[:-len(suffix)]
    return {
        "raw_report": raw_report,
        "summary": raw_report.with_name(stem + ".summary.json"),
        "minecraft_log": raw_report.with_name(stem + ".minecraft.log"),
        "console_log": raw_report.with_name(stem + ".console.log"),
        "attestation": raw_report.with_name(stem + ".accepted.json"),
    }


def _validated_bundle_paths(
    raw_report: Path,
    summary_path: Path,
    minecraft_log: Path,
    console_log: Path,
    output: Path | None = None,
) -> dict[str, Path]:
    raw = _existing_file(raw_report, "raw report")
    expected = _artifact_paths(raw)
    supplied = {
        "summary": _existing_file(summary_path, "benchmark summary"),
        "minecraft_log": _existing_file(minecraft_log, "Minecraft log"),
        "console_log": _existing_file(console_log, "console log"),
    }
    for key, value in supplied.items():
        if value != expected[key]:
            raise ReportError(
                f"{key.replace('_', ' ')} path is inconsistent with raw report: "
                f"expected {expected[key]}, found {value}"
            )
    if output is not None and output.resolve() != expected["attestation"]:
        raise ReportError(
            "attestation path is inconsistent with raw report: "
            f"expected {expected['attestation']}, found {output.resolve()}"
        )
    return {"raw_report": raw, **supplied, "attestation": expected["attestation"]}


def _load_json_object(path: Path, label: str) -> dict[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ReportError(f"cannot read {label} {path}: {error}") from error
    if not isinstance(payload, dict):
        raise ReportError(f"{label} must contain a JSON object: {path}")
    return payload


def _derive_release_summary(
    raw_report: Path,
    *,
    workload_contract: str = WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP,
) -> tuple[dict[str, Any], dict[str, Any]]:
    windows = load_report(raw_report)
    if any(window.schema != 2 for window in windows):
        raise ReportError("accepted raw report must contain schema-v2 windows only")
    measure_windows = [window for window in windows if window.phase == "measure"]
    if not measure_windows:
        raise ReportError("accepted raw report contains no measurement windows")
    selections = {
        (window.segment, window.scaler, window.generation)
        for window in measure_windows
    }
    if len(selections) != 1:
        raise ReportError(
            "accepted raw report must contain exactly one measurement selection"
        )
    segment, scaler, generation = next(iter(selections))
    if not isinstance(segment, int) or segment < 0:
        raise ReportError("accepted raw report has an invalid measurement segment")
    if scaler not in {"OFF", "QUALITY", "PERFORMANCE"}:
        raise ReportError("accepted raw report has an invalid scaler mode")
    frames = sum(window.frames for window in measure_windows)
    preliminary = summarize(raw_report, frames, segment, scaler)
    metadata = preliminary.get("metadata")
    if not isinstance(metadata, dict):
        raise ReportError("accepted raw report has no stable release metadata")
    release = summarize(
        raw_report,
        frames,
        segment,
        scaler,
        release_contract=True,
        workload_contract=workload_contract,
        source_sha256=_metadata_string(metadata, "source_sha256"),
        artifact_sha256=_metadata_string(metadata, "artifact_sha256"),
        settings_id=_metadata_string(metadata, "settings_id"),
        settings_spec_sha256=_metadata_string(metadata, "settings_spec_sha256"),
        settings_sha256=_metadata_string(metadata, "settings_sha256"),
        world=_metadata_string(metadata, "world"),
        fixture=_metadata_string(metadata, "fixture"),
        fixture_sha256=_metadata_string(metadata, "fixture_sha256"),
        route=_metadata_string(metadata, "route"),
        route_sha256=_metadata_string(metadata, "route_sha256"),
        player_name=_metadata_string(metadata, "benchmark_player_name"),
        player_uuid=_metadata_string(metadata, "benchmark_player_uuid"),
        dimension=_metadata_string(metadata, "benchmark_dimension"),
        simulation_frozen=metadata.get("benchmark_simulation_frozen") is True,
    )
    return release, {
        "measure_frames": frames,
        "segment": segment,
        "scaler_mode": scaler,
        "generation": generation,
    }


def _event_records(text: str, event: str) -> list[tuple[int, str]]:
    token = f"METALLUM_BENCHMARK EVENT={event}"
    records: list[tuple[int, str]] = []
    for line_number, line in enumerate(text.splitlines(), 1):
        position = line.find(token)
        if position >= 0:
            records.append((line_number, line[position:].strip()))
    return records


def _single_event(
    text: str,
    event: str,
    pattern: str,
) -> tuple[int, re.Match[str]]:
    records = _event_records(text, event)
    if len(records) != 1:
        raise ReportError(
            f"Minecraft log must contain exactly one {event} event (found {len(records)})"
        )
    line_number, record = records[0]
    match = re.fullmatch(pattern, record)
    if match is None:
        raise ReportError(f"Minecraft log line {line_number}: malformed {event} event")
    return line_number, match


def _validate_log_evidence(
    minecraft_log: Path,
    console_log: Path,
    summary: dict[str, Any],
    measurement: dict[str, Any],
) -> None:
    try:
        minecraft_text = minecraft_log.read_text(encoding="utf-8")
        console_text = console_log.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as error:
        raise ReportError(f"cannot read benchmark evidence logs: {error}") from error
    if not minecraft_text.strip() or not console_text.strip():
        raise ReportError("benchmark evidence logs must not be empty")
    for text, label in ((minecraft_text, "Minecraft log"), (console_text, "console log")):
        if "METALLUM_BENCHMARK EVENT=FAIL" in text:
            raise ReportError(f"{label} contains a benchmark FAIL event")
    if "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED" in minecraft_text:
        raise ReportError("Minecraft log contains an unexpected screenshot event")
    if re.search(
        r"\[metallum\] (?:Metal command buffer failed|GPU timing sample invalid|"
        r"GPU timing workload window mismatch|Java workload telemetry invalid|"
        r"Static geometry heap teardown exceeded Sodium cache bound|"
        r"Static geometry buffer release was not registered)",
        console_text,
    ):
        raise ReportError(
            "console log contains a Metal timing/command-buffer failure or "
            "resource-lifecycle failure"
        )

    metadata = summary["metadata"]
    route = re.escape(_metadata_string(metadata, "route"))
    fixture = re.escape(_metadata_string(metadata, "fixture"))
    player_name = re.escape(_metadata_string(metadata, "benchmark_player_name"))
    player_uuid = re.escape(_metadata_string(metadata, "benchmark_player_uuid"))
    dimension = re.escape(_metadata_string(metadata, "benchmark_dimension"))
    scaler = re.escape(str(measurement["scaler_mode"]))
    frames = measurement["measure_frames"]
    width = metadata.get("display_width")
    height = metadata.get("display_height")
    if not isinstance(width, int) or not isinstance(height, int):
        raise ReportError("release metadata lacks integer display dimensions")
    if frames != DEFAULT_MEASURE_FRAMES:
        raise ReportError(
            f"strict benchmark evidence requires {DEFAULT_MEASURE_FRAMES} measured frames"
        )

    frozen_line, _ = _single_event(
        minecraft_text,
        "SERVER_TICKS_FROZEN",
        r"METALLUM_BENCHMARK EVENT=SERVER_TICKS_FROZEN",
    )
    armed_line, _ = _single_event(
        minecraft_text,
        "ARMED",
        rf"METALLUM_BENCHMARK EVENT=ARMED scope={re.escape(BUILT_IN_MONITOR)} "
        rf"target={BUILT_IN_WIDTH}x{BUILT_IN_HEIGHT} "
        rf"warmup={DEFAULT_WARMUP_FRAMES} measure={DEFAULT_MEASURE_FRAMES} "
        rf"sequence=\[{scaler}\] route={route}",
    )
    window_ready_line, _ = _single_event(
        minecraft_text,
        "WINDOW_READY",
        rf"METALLUM_BENCHMARK EVENT=WINDOW_READY "
        rf"monitor={re.escape(BUILT_IN_MONITOR)} "
        rf"video_mode={BUILT_IN_WIDTH}x{BUILT_IN_HEIGHT}@120 \(24bit\) "
        rf"framebuffer={BUILT_IN_WIDTH}x{BUILT_IN_HEIGHT} "
        rf"window={BUILT_IN_WIDTH}x{BUILT_IN_HEIGHT} "
        rf"screen={BUILT_IN_WIDTH}x{BUILT_IN_HEIGHT}",
    )
    apply_line, _ = _single_event(
        minecraft_text,
        "ROUTE_APPLY",
        rf"METALLUM_BENCHMARK EVENT=ROUTE_APPLY route={route} fixture={fixture} "
        rf"player={player_name}/{player_uuid} dimension={dimension}",
    )
    ready_line, ready = _single_event(
        minecraft_text,
        "ROUTE_READY",
        rf"METALLUM_BENCHMARK EVENT=ROUTE_READY route={route} "
        r"stable_frames=[1-9][0-9]* pose=\[[^\]\r\n]+\] "
        r"max_fps=([1-9][0-9]*) resolved_gui_scale=([1-9][0-9]*) "
        r"resource_packs=([A-Za-z0-9_.:/,-]+)",
    )
    expected_max_fps = metadata.get("max_fps")
    expected_resource_packs = metadata.get("active_resource_pack_ids")
    if int(ready.group(1)) != expected_max_fps:
        raise ReportError("ROUTE_READY effective max FPS differs from timing metadata")
    if ready.group(3) != expected_resource_packs:
        raise ReportError("ROUTE_READY active resource packs differ from timing metadata")
    resolved_gui_scale = int(ready.group(2))
    if resolved_gui_scale != 8:
        raise ReportError(
            "ROUTE_READY resolved GUI scale must be 8 for the strict built-in-display route"
        )

    worker_counts = [
        int(value)
        for value in re.findall(
            r"\(ChunkBuilder\) Started ([1-9][0-9]*) worker threads",
            minecraft_text,
        )
    ]
    expected_worker_count = metadata.get("sodium_chunk_builder_threads")
    if not worker_counts or any(value != expected_worker_count for value in worker_counts):
        raise ReportError(
            "Minecraft log Sodium worker count differs from timing metadata"
        )

    route_checks = _event_records(minecraft_text, "ROUTE_CHECK")
    if len(route_checks) != 2:
        raise ReportError(
            "Minecraft log must contain exactly two ROUTE_CHECK events "
            f"(found {len(route_checks)})"
        )
    route_check_lines: dict[str, int] = {}
    route_check_pattern = re.compile(
        rf"METALLUM_BENCHMARK EVENT=ROUTE_CHECK "
        rf"event=(MEASURE_START|MEASURE_END) route={route} status=ready"
    )
    for line_number, record in route_checks:
        match = route_check_pattern.fullmatch(record)
        if match is None or match.group(1) in route_check_lines:
            raise ReportError(
                f"Minecraft log line {line_number}: invalid or duplicate ROUTE_CHECK event"
            )
        route_check_lines[match.group(1)] = line_number
    if set(route_check_lines) != {"MEASURE_START", "MEASURE_END"}:
        raise ReportError("Minecraft log lacks ready route checks at both boundaries")

    measure_start_line, measure_start = _single_event(
        minecraft_text,
        "MEASURE_START",
        rf"METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode={scaler} "
        r"presented_frame=([0-9]+)",
    )
    measure_end_line, measure_end = _single_event(
        minecraft_text,
        "MEASURE_END",
        rf"METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode={scaler} "
        r"presented_frame=([0-9]+)",
    )
    start_frame = int(measure_start.group(1))
    end_frame = int(measure_end.group(1))
    if (
        start_frame != DEFAULT_WARMUP_FRAMES
        or end_frame != DEFAULT_WARMUP_FRAMES + DEFAULT_MEASURE_FRAMES
        or end_frame - start_frame != frames
    ):
        raise ReportError(
            "Minecraft log measurement boundary must be exactly "
            f"{DEFAULT_WARMUP_FRAMES}->{DEFAULT_WARMUP_FRAMES + DEFAULT_MEASURE_FRAMES} "
            f"and cover the raw report's {frames} frames"
        )
    complete_line, _ = _single_event(
        minecraft_text,
        "COMPLETE",
        rf"METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames={frames} "
        rf"framebuffer={width}x{height}",
    )
    ordered_lines = (
        frozen_line,
        armed_line,
        window_ready_line,
        apply_line,
        ready_line,
        route_check_lines["MEASURE_START"],
        measure_start_line,
        measure_end_line,
        route_check_lines["MEASURE_END"],
        complete_line,
    )
    if any(current >= following for current, following in zip(ordered_lines, ordered_lines[1:])):
        raise ReportError("benchmark evidence events are out of order")
    measurement["runtime"] = {
        "resolved_gui_scale": resolved_gui_scale,
        "sodium_chunk_builder_threads": expected_worker_count,
        "active_resource_pack_ids": expected_resource_packs,
    }


def _validate_release_bundle(
    raw_report: Path,
    summary_path: Path,
    minecraft_log: Path,
    console_log: Path,
    *,
    workload_contract: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    supplied_summary = _load_json_object(summary_path, "benchmark summary")
    recomputed_summary, measurement = _derive_release_summary(
        raw_report,
        workload_contract=workload_contract,
    )
    report_value = supplied_summary.get("report")
    if not isinstance(report_value, str):
        raise ReportError("benchmark summary has no raw report path")
    try:
        supplied_report = Path(report_value).resolve(strict=True)
    except OSError as error:
        raise ReportError(f"benchmark summary raw report path is invalid: {error}") from error
    if supplied_report != raw_report:
        raise ReportError("benchmark summary is bound to a different raw report")
    normalized_summary = dict(supplied_summary)
    normalized_summary["report"] = str(supplied_report)
    expected_summary = dict(recomputed_summary)
    expected_summary["report"] = str(raw_report)
    if normalized_summary != expected_summary:
        raise ReportError(
            "benchmark summary does not match an independent release-contract "
            "recalculation of the raw report"
        )
    _validate_log_evidence(
        minecraft_log,
        console_log,
        recomputed_summary,
        measurement,
    )
    return recomputed_summary, measurement


def create_attestation(
    raw_report: Path,
    summary_path: Path,
    minecraft_log: Path,
    console_log: Path,
    output: Path,
) -> None:
    paths = _validated_bundle_paths(
        raw_report,
        summary_path,
        minecraft_log,
        console_log,
        output,
    )
    summary, measurement = _validate_release_bundle(
        paths["raw_report"],
        paths["summary"],
        paths["minecraft_log"],
        paths["console_log"],
        workload_contract=WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP,
    )
    payload = {
        "schema_version": 5,
        "accepted": True,
        "raw_report": str(paths["raw_report"]),
        "raw_sha256": _file_sha256(paths["raw_report"]),
        "summary": str(paths["summary"]),
        "summary_sha256": _file_sha256(paths["summary"]),
        "minecraft_log": str(paths["minecraft_log"]),
        "minecraft_log_sha256": _file_sha256(paths["minecraft_log"]),
        "console_log": str(paths["console_log"]),
        "console_log_sha256": _file_sha256(paths["console_log"]),
        "measurement": measurement,
        "presented_frames": summary["presented_frames"],
        "workload": summary["workload"],
        "metadata": summary["metadata"],
    }
    paths["attestation"].parent.mkdir(parents=True, exist_ok=True)
    temporary = paths["attestation"].with_name(
        f".{paths['attestation'].name}.tmp-{uuid.uuid4()}"
    )
    try:
        temporary.write_text(
            json.dumps(payload, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        temporary.replace(paths["attestation"])
    except OSError as error:
        try:
            temporary.unlink(missing_ok=True)
        except OSError:
            pass
        raise ReportError(
            f"cannot write benchmark attestation {paths['attestation']}: {error}"
        ) from error


def _attestation_path(raw_report: Path) -> Path:
    return _artifact_paths(raw_report)["attestation"]


def _attestation_workload_contract(schema_version: int) -> str:
    return {
        2: WORKLOAD_CONTRACT_NONE,
        3: WORKLOAD_CONTRACT_BASE,
        4: WORKLOAD_CONTRACT_EXPANDED,
        5: WORKLOAD_CONTRACT_PRIVATE_GEOMETRY_HEAP,
    }[schema_version]


def verify_attestation(raw_report: Path) -> dict[str, Any]:
    raw = _existing_file(raw_report, "raw report")
    path = _attestation_path(raw)
    payload = _load_json_object(path, "benchmark attestation")
    schema_version = payload.get("schema_version")
    if schema_version not in (2, 3, 4, 5) or payload.get("accepted") is not True:
        raise ReportError(f"invalid benchmark attestation: {path}")
    expected = _artifact_paths(raw)
    artifact_keys = ("raw_report", "summary", "minecraft_log", "console_log")
    artifacts: dict[str, Path] = {}
    for key in artifact_keys:
        value = payload.get(key)
        if not isinstance(value, str) or not Path(value).is_absolute():
            raise ReportError(f"attestation {key} path is invalid: {path}")
        artifact = _existing_file(Path(value), key.replace("_", " "))
        if artifact != expected[key] or value != str(artifact):
            raise ReportError(f"attestation {key} path is inconsistent: {path}")
        digest_key = "raw_sha256" if key == "raw_report" else f"{key}_sha256"
        recorded_digest = payload.get(digest_key)
        if not isinstance(recorded_digest, str) or not SHA256_RE.fullmatch(recorded_digest):
            raise ReportError(f"attestation {digest_key} is invalid: {path}")
        if _file_sha256(artifact) != recorded_digest:
            raise ReportError(f"attested {key.replace('_', ' ')} changed: {artifact}")
        artifacts[key] = artifact

    summary, measurement = _validate_release_bundle(
        artifacts["raw_report"],
        artifacts["summary"],
        artifacts["minecraft_log"],
        artifacts["console_log"],
        workload_contract=_attestation_workload_contract(schema_version),
    )
    if schema_version == 2 and ("workload" in summary or "workload" in payload):
        raise ReportError(
            f"legacy schema-v2 attestation cannot attest workload telemetry: {path}"
        )
    if payload.get("measurement") != measurement:
        raise ReportError(f"attestation measurement contract is inconsistent: {path}")
    if payload.get("presented_frames") != summary["presented_frames"]:
        raise ReportError(f"attestation frame count is inconsistent: {path}")
    if schema_version in (3, 4, 5):
        if payload.get("workload") != summary["workload"]:
            raise ReportError(f"attestation workload is inconsistent: {path}")
    if payload.get("metadata") != summary["metadata"]:
        raise ReportError(f"attestation metadata is inconsistent: {path}")
    return payload


def _metadata_string(metadata: dict[str, Any], key: str) -> str:
    value = metadata.get(key)
    if not isinstance(value, str) or not value:
        raise ReportError(f"strict compare requires metadata.{key}")
    return value


def summarize_attested_release(
    path: Path,
    frames: int,
    segment: int,
    scaler: str,
) -> dict[str, Any]:
    attestation = verify_attestation(path)
    preliminary = summarize(path, frames, segment, scaler)
    metadata = preliminary.get("metadata")
    if not isinstance(metadata, dict) or metadata != attestation["metadata"]:
        raise ReportError("attestation metadata differs from the selected timing windows")
    result = summarize(
        path,
        frames,
        segment,
        scaler,
        release_contract=True,
        workload_contract=_attestation_workload_contract(
            attestation["schema_version"]
        ),
        source_sha256=_metadata_string(metadata, "source_sha256"),
        artifact_sha256=_metadata_string(metadata, "artifact_sha256"),
        settings_id=_metadata_string(metadata, "settings_id"),
        settings_spec_sha256=_metadata_string(metadata, "settings_spec_sha256"),
        settings_sha256=_metadata_string(metadata, "settings_sha256"),
        world=_metadata_string(metadata, "world"),
        fixture=_metadata_string(metadata, "fixture"),
        fixture_sha256=_metadata_string(metadata, "fixture_sha256"),
        route=_metadata_string(metadata, "route"),
        route_sha256=_metadata_string(metadata, "route_sha256"),
        player_name=_metadata_string(metadata, "benchmark_player_name"),
        player_uuid=_metadata_string(metadata, "benchmark_player_uuid"),
        dimension=_metadata_string(metadata, "benchmark_dimension"),
        simulation_frozen=metadata.get("benchmark_simulation_frozen") is True,
    )
    measurement = attestation.get("measurement")
    if isinstance(measurement, dict) and isinstance(measurement.get("runtime"), dict):
        result["attested_runtime"] = measurement["runtime"]
    return result


def _print_summary(summary: dict[str, Any]) -> None:
    gpu = summary["presenting_command_buffer_gpu_ms"]
    percentile = gpu["percentile_window_summaries"]
    print(f"report: {summary['report']}")
    print(f"selection: {summary['selection']}")
    print(f"frames/windows: {summary['presented_frames']}/{summary['window_count']}")
    print(
        "FPS elapsed-weighted/min-window: "
        f"{summary['fps']['elapsed_weighted']:.3f}/{summary['fps']['window_minimum']:.3f}"
    )
    print(
        "presenting CB GPU ms (window-weighted p50/p95/p99, worst): "
        f"{percentile['p50']['window_frame_weighted_mean']:.4f}/"
        f"{percentile['p95']['window_frame_weighted_mean']:.4f}/"
        f"{percentile['p99']['window_frame_weighted_mean']:.4f}, "
        f"{gpu['maximum_observed_in_any_window']:.4f}"
    )
    if "fps_low_window_summaries" in summary:
        lows = summary["fps_low_window_summaries"]
        print(
            "FPS lows (window-weighted 1%/0.1%): "
            f"{lows['one_percent']['window_frame_weighted_mean']:.3f}/"
            f"{lows['zero_point_one_percent']['window_frame_weighted_mean']:.3f}"
        )
    if "workload" in summary:
        workload = summary["workload"]
        totals = workload["aggregate_totals"]
        per_frame = workload["per_presented_frame"]
        encoders = per_frame["encoders"]
        print(
            "workload/frame (command buffers; render/compute/blit passes): "
            f"{per_frame['command_buffers']:.3f}; "
            f"{encoders['render']:.3f}/{encoders['compute']:.3f}/"
            f"{encoders['blit']:.3f}"
        )
        copies = totals["copy_bytes"]
        print(
            "copy bytes total (CPU->shared/shared->private/GPU->CPU/GPU internal/"
            "unclassified): "
            f"{copies['cpu_to_shared']}/{copies['shared_to_private']}/"
            f"{copies['gpu_to_cpu']}/{copies['gpu_internal']}/"
            f"{copies['unclassified']}; unknown-byte commands "
            f"{copies['byte_count_unknown_commands']}; direct write "
            f"{workload['direct_write_observed_any_window']} "
            "(instrumented paths only)"
        )
        allocations = totals["resource_allocations"]
        print(
            "resource allocations total (count/bytes, buffers; textures): "
            f"{allocations['buffers']['count']}/{allocations['buffers']['bytes']}; "
            f"{allocations['textures']['count']}/{allocations['textures']['bytes']}"
        )
        if "transient_memory_high_water" in workload:
            transient = workload["transient_memory_high_water"]
            print(
                "transient high-water bytes (requested/reserved, CPU; GPU shared): "
                f"{transient['cpu']['requested_high_water_bytes']}/"
                f"{transient['cpu']['reserved_high_water_bytes']}; "
                f"{transient['gpu_shared']['requested_high_water_bytes']}/"
                f"{transient['gpu_shared']['reserved_high_water_bytes']}"
            )
        if "private_geometry_heap" in workload:
            heap = workload["private_geometry_heap"]
            configuration = heap["configuration"]
            gauges = heap["gauges_at_last_window"]
            totals = heap["process_lifetime_totals_at_last_window"]
            print(
                "private geometry heap (enabled; current/peak pages; backing "
                "allocations at last window): "
                f"{configuration['enabled']}; {gauges['pages_current']}/"
                f"{gauges['pages_peak']}; {totals['backing_allocations_total']}"
            )
        print("workload coverage: " + workload["observability"])
    print("note: " + summary["interpretation"])


def _print_comparison(result: dict[str, Any]) -> None:
    gate = result["gate"]
    print(
        f"{result['verdict']}: GPU p95 {gate['baseline_ms']:.4f} -> "
        f"{gate['candidate_ms']:.4f} ms (delta {gate['delta_ms']:+.4f} ms, "
        f"gate {gate['threshold_ms']:.4f} ms)"
    )
    for key, values in result["metrics"].items():
        print(
            f"  {key}: {values['baseline']:.4f} -> {values['candidate']:.4f} ms "
            f"({values['delta']:+.4f})"
        )
    for regression in gate["regressions"]:
        if regression["limit_kind"] == "absolute increase":
            detail = (
                f"increase {regression['delta']:+.4f}, "
                f"limit +{regression['limit']:.4f}"
            )
        else:
            delta_fraction = regression["delta_fraction"]
            delta_text = (
                "undefined from zero baseline"
                if delta_fraction is None
                else f"{delta_fraction:+.1%}"
            )
            direction = "increase" if regression["limit_kind"] == "fractional increase" \
                else "decrease"
            detail = f"change {delta_text}, {direction} limit {regression['limit']:.1%}"
        print(
            f"  REGRESSION {regression['metric']}: "
            f"{regression['baseline']:.4f} -> {regression['candidate']:.4f} "
            f"({detail})"
        )


def self_test() -> None:
    def expect_error(action: Any, expected_text: str) -> None:
        try:
            action()
        except ReportError as error:
            assert expected_text in str(error), (expected_text, str(error))
        else:
            raise AssertionError(f"expected ReportError containing {expected_text!r}")

    def heap_snapshot(index: int, enabled: bool) -> dict[str, Any]:
        page_size = 64 * 1024 * 1024
        requests = 10 + index
        if enabled:
            heap_allocations = 8 + index
            fallback_allocations = 2
            pages_created = 1
            page_reuse_hits = heap_allocations - pages_created
            return {
                "enabled": True,
                "pools_current": 1,
                "page_size_bytes": page_size,
                "page_limit_per_device": 8,
                "pages_current": 1,
                "pages_peak": 1,
                "pages_created_total": pages_created,
                "pages_retired_total": 0,
                "retire_pending_pages": 0,
                "heap_size_bytes_current": page_size,
                "heap_current_allocated_bytes": 4096,
                "heap_used_bytes_current": 4096,
                "fragmentation_probe_alignment": 256,
                "heap_largest_available_bytes": page_size - 4096,
                "heap_fragmentation_estimate_bytes": 0,
                "live_allocations": 1,
                "live_requested_bytes": 1024,
                "live_query_bytes": 1024,
                "requests_total": requests,
                "requested_bytes_total": requests * 1024,
                "heap_allocations_total": heap_allocations,
                "heap_query_bytes_total": heap_allocations * 1024,
                "page_reuse_hits_total": page_reuse_hits,
                "fallback_allocations_total": fallback_allocations,
                "fallback_requested_bytes_total": fallback_allocations * 1024,
                "fallback_disabled_total": 0,
                "fallback_oversize_total": fallback_allocations,
                "fallback_invalid_query_total": 0,
                "fallback_capacity_total": 0,
                "fallback_heap_create_total": 0,
                "fallback_heap_allocate_total": 0,
                "allocation_failures_total": 0,
                "backing_allocations_total": pages_created + fallback_allocations,
                "device_teardown_with_live_allocations_total": 0,
            }
        return {
            "enabled": False,
            "pools_current": 1,
            "page_size_bytes": page_size,
            "page_limit_per_device": 8,
            "pages_current": 0,
            "pages_peak": 0,
            "pages_created_total": 0,
            "pages_retired_total": 0,
            "retire_pending_pages": 0,
            "heap_size_bytes_current": 0,
            "heap_current_allocated_bytes": 0,
            "heap_used_bytes_current": 0,
            "fragmentation_probe_alignment": 256,
            "heap_largest_available_bytes": 0,
            "heap_fragmentation_estimate_bytes": 0,
            "live_allocations": 0,
            "live_requested_bytes": 0,
            "live_query_bytes": 0,
            "requests_total": requests,
            "requested_bytes_total": requests * 1024,
            "heap_allocations_total": 0,
            "heap_query_bytes_total": 0,
            "page_reuse_hits_total": 0,
            "fallback_allocations_total": requests,
            "fallback_requested_bytes_total": requests * 1024,
            "fallback_disabled_total": requests,
            "fallback_oversize_total": 0,
            "fallback_invalid_query_total": 0,
            "fallback_capacity_total": 0,
            "fallback_heap_create_total": 0,
            "fallback_heap_allocate_total": 0,
            "allocation_failures_total": 0,
            "backing_allocations_total": requests,
            "device_teardown_with_live_allocations_total": 0,
        }

    def line(
        schema: int,
        index: int,
        source_sha256: str = "c" * 64,
        artifact_sha256: str = "5" * 64,
        heap_enabled: bool = True,
    ) -> dict[str, Any]:
        metric = {"average": 7.0, "p50": 7.1, "p95": 7.8, "p99": 8.1, "maximum": 9.0}
        payload: dict[str, Any] = {
            "schema_version": schema,
            "timestamp_unix_ms": index,
            "detail_enabled": False,
            "presented_frames": 300,
            "fps": 130.0,
            "dropped_timing_events": 0,
        }
        if schema == 1:
            payload["frame_ms"] = metric
        else:
            payload.update({
                "presenting_command_buffer_gpu_ms": metric,
                "fps_1_percent_low": 60.0,
                "fps_0_1_percent_low": 50.0,
                "present_interval_ms": {
                    "samples": 300, "average": 7.7, "p50": 7.6,
                    "p95": 9.0, "p99": 11.0, "maximum": 15.0,
                },
                "workload": {
                    "command_buffers": 300,
                    "encoders": {
                        "render": 300, "compute": 1, "blit": 2,
                        "pass_boundaries": 303,
                    },
                    "copy_bytes": {
                        "cpu_to_shared": 0,
                        "shared_to_private": 1024,
                        "gpu_to_cpu": 512,
                        "gpu_internal": 256,
                        "unclassified": 0,
                        "cpu_to_shared_commands": 0,
                        "shared_to_private_commands": 2,
                        "gpu_to_cpu_commands": 1,
                        "gpu_internal_commands": 1,
                        "unclassified_commands": 0,
                        "byte_count_unknown_commands": 1,
                        "direct_write_observed": False,
                    },
                    "resource_allocations": {
                        "buffers": {"count": 2, "bytes": 4096},
                        "textures": {"count": 1, "bytes": 8192},
                    },
                    "transient_memory": {
                        "cpu": {
                            "requested_high_water_bytes": 1000 + index,
                            "reserved_high_water_bytes": 2000 + index,
                        },
                        "gpu_shared": {
                            "requested_high_water_bytes": 3000 + index * 2,
                            "reserved_high_water_bytes": 5000 + index * 3,
                        },
                    },
                    "private_geometry_heap": heap_snapshot(index, heap_enabled),
                },
                "benchmark": {
                    "enabled": True, "generation": 2, "segment_index": 0,
                    "phase": "measure", "scaler_mode": "OFF",
                },
                "metadata": {
                    "monitor": BUILT_IN_MONITOR, "display_width": BUILT_IN_WIDTH,
                    "display_height": BUILT_IN_HEIGHT, "refresh_hz": 120,
                    "render_width": BUILT_IN_WIDTH,
                    "render_height": BUILT_IN_HEIGHT, "display_sync_enabled": False,
                    "hdr_output_mode": "ENHANCED", "source_encoding": "LINEAR",
                    "executor": "METAL3", "scaler_active": False,
                    "source_sha256": source_sha256,
                    "artifact_sha256": artifact_sha256,
                    "settings_id": "native-hdr-fancy-v1",
                    "settings_spec_sha256": "1" * 64,
                    "settings_sha256": "2" * 64,
                    "render_distance": 16, "simulation_distance": 12,
                    "graphics_preset": "fancy", "entity_distance_scaling": 1.0,
                    "particles": 0, "mipmap_levels": 4,
                    "biome_blend_radius": 2, "max_fps": 260,
                    "ambient_occlusion": True, "clouds_mode": "true",
                    "cloud_range": 64, "texture_filtering": 1,
                    "max_anisotropy_bit": 1, "improved_transparency": False,
                    "resource_packs_sha256": "3" * 64,
                    "sodium_settings_sha256": "4" * 64,
                    "configured_gui_scale": 0,
                    "active_resource_pack_ids": "vanilla,metallum,sodium",
                    "sodium_chunk_builder_threads": 4,
                    "hdr_bloom_strength": 0.18, "hdr_strength": 1.0,
                    "persistent_metalfx_mode": "off",
                    "diagnostic_pattern": False, "bloom_strength": 0.18,
                    "current_edr_headroom": 1.2,
                    "world": "hdrtest-static-v1", "fixture": "hdrtest-static-v1",
                    "fixture_sha256": "a" * 64,
                    "route": "hdrtest-static-v1", "route_sha256": "b" * 64,
                    "benchmark_player_name": "MetallumBench",
                    "benchmark_player_uuid": "b07a402a-d8ea-354f-9398-aaf208a798b9",
                    "benchmark_dimension": "minecraft:overworld",
                    "benchmark_simulation_frozen": True,
                    "thermal_state": "nominal", "device_name": "Apple M1 Pro",
                    "static_geometry_heaps_enabled": heap_enabled,
                },
            })
        return payload

    def report_text(
        source_sha256: str,
        artifact_sha256: str = "5" * 64,
        heap_enabled: bool = True,
    ) -> str:
        startup = line(2, 0, source_sha256, artifact_sha256, heap_enabled)
        startup["benchmark"].update({
            "generation": 0,
            "segment_index": -1,
            "phase": "startup",
            "scaler_mode": "UNKNOWN",
        })
        return (
            json.dumps(startup) + "\n"
            + "\n".join(
                json.dumps(
                    line(2, index + 1, source_sha256, artifact_sha256, heap_enabled)
                )
                for index in range(10)
            )
            + "\n"
        )

    minecraft_evidence = "\n".join((
        "[main/INFO] METALLUM_BENCHMARK EVENT=SERVER_TICKS_FROZEN",
        "[render/INFO] METALLUM_BENCHMARK EVENT=ARMED "
        "scope=Built-in Retina Display target=3024x1964 warmup=1800 "
        "measure=3000 sequence=[OFF] route=hdrtest-static-v1",
        "[render/INFO] METALLUM_BENCHMARK EVENT=WINDOW_READY "
        "monitor=Built-in Retina Display video_mode=3024x1964@120 (24bit) "
        "framebuffer=3024x1964 window=3024x1964 screen=3024x1964",
        "[render/INFO] METALLUM_BENCHMARK EVENT=ROUTE_APPLY "
        "route=hdrtest-static-v1 fixture=hdrtest-static-v1 "
        "player=MetallumBench/b07a402a-d8ea-354f-9398-aaf208a798b9 "
        "dimension=minecraft:overworld",
        "[render/INFO] METALLUM_BENCHMARK EVENT=ROUTE_READY "
        "route=hdrtest-static-v1 stable_frames=120 "
        "pose=[86.1,74.0,-95.5;155.4,13.2] max_fps=260 "
        "resolved_gui_scale=8 resource_packs=vanilla,metallum,sodium",
        "[render/INFO] (ChunkBuilder) Started 4 worker threads",
        "[render/INFO] METALLUM_BENCHMARK EVENT=ROUTE_CHECK "
        "event=MEASURE_START route=hdrtest-static-v1 status=ready",
        "[render/INFO] METALLUM_BENCHMARK EVENT=MEASURE_START "
        "index=1 mode=OFF presented_frame=1800",
        "[render/INFO] METALLUM_BENCHMARK EVENT=MEASURE_END "
        "index=1 mode=OFF presented_frame=4800",
        "[render/INFO] METALLUM_BENCHMARK EVENT=ROUTE_CHECK "
        "event=MEASURE_END route=hdrtest-static-v1 status=ready",
        "[render/INFO] METALLUM_BENCHMARK EVENT=COMPLETE "
        "segments=1 measured_frames=3000 framebuffer=3024x1964",
    )) + "\n"

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        old = root / "old.jsonl"
        old.write_text("\n".join(json.dumps(line(1, i)) for i in range(10)) + "\n")
        old_summary = summarize(old, 3000, 0, "OFF")

        def make_bundle(
            stem: str,
            source_sha256: str,
            artifact_sha256: str = "5" * 64,
            heap_enabled: bool = True,
        ) -> tuple[Path, dict[str, Any]]:
            raw = root / f"{stem}.raw.jsonl"
            summary_path = root / f"{stem}.summary.json"
            minecraft_log = root / f"{stem}.minecraft.log"
            console_log = root / f"{stem}.console.log"
            accepted = root / f"{stem}.accepted.json"
            raw.write_text(
                report_text(source_sha256, artifact_sha256, heap_enabled),
                encoding="utf-8",
            )
            release, _ = _derive_release_summary(raw)
            summary_path.write_text(
                json.dumps(release, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            minecraft_log.write_text(minecraft_evidence, encoding="utf-8")
            console_log.write_text(
                "Gradle/Minecraft console completed normally\n",
                encoding="utf-8",
            )
            create_attestation(raw, summary_path, minecraft_log, console_log, accepted)
            return raw, release

        def make_legacy_v2_bundle(
            stem: str,
            source_sha256: str,
            artifact_sha256: str = "5" * 64,
        ) -> tuple[Path, dict[str, Any]]:
            raw = root / f"{stem}.raw.jsonl"
            paths = _artifact_paths(raw)
            payloads = [
                json.loads(value)
                for value in report_text(source_sha256, artifact_sha256).splitlines()
            ]
            for payload in payloads:
                payload.pop("workload")
                payload["metadata"].pop("static_geometry_heaps_enabled")
            raw.write_text(
                "\n".join(json.dumps(payload) for payload in payloads) + "\n",
                encoding="utf-8",
            )
            release, _ = _derive_release_summary(
                raw,
                workload_contract=WORKLOAD_CONTRACT_NONE,
            )
            paths["summary"].write_text(
                json.dumps(release, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            paths["minecraft_log"].write_text(minecraft_evidence, encoding="utf-8")
            paths["console_log"].write_text(
                "Gradle/Minecraft console completed normally\n",
                encoding="utf-8",
            )
            recomputed, measurement = _validate_release_bundle(
                raw.resolve(),
                paths["summary"].resolve(),
                paths["minecraft_log"].resolve(),
                paths["console_log"].resolve(),
                workload_contract=WORKLOAD_CONTRACT_NONE,
            )
            recomputed["report"] = release["report"]
            assert recomputed == release
            accepted_payload = {
                "schema_version": 2,
                "accepted": True,
                "raw_report": str(raw.resolve()),
                "raw_sha256": _file_sha256(raw),
                "summary": str(paths["summary"].resolve()),
                "summary_sha256": _file_sha256(paths["summary"]),
                "minecraft_log": str(paths["minecraft_log"].resolve()),
                "minecraft_log_sha256": _file_sha256(paths["minecraft_log"]),
                "console_log": str(paths["console_log"].resolve()),
                "console_log_sha256": _file_sha256(paths["console_log"]),
                "measurement": measurement,
                "presented_frames": release["presented_frames"],
                "metadata": release["metadata"],
            }
            paths["attestation"].write_text(
                json.dumps(accepted_payload, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            return raw, release

        def make_legacy_v3_bundle(
            stem: str,
            source_sha256: str,
            artifact_sha256: str = "5" * 64,
        ) -> tuple[Path, dict[str, Any]]:
            raw = root / f"{stem}.raw.jsonl"
            paths = _artifact_paths(raw)
            payloads = [
                json.loads(value)
                for value in report_text(source_sha256, artifact_sha256).splitlines()
            ]
            for payload in payloads:
                payload["workload"].pop("transient_memory")
                payload["workload"].pop("private_geometry_heap")
                payload["metadata"].pop("static_geometry_heaps_enabled")
            raw.write_text(
                "\n".join(json.dumps(payload) for payload in payloads) + "\n",
                encoding="utf-8",
            )
            release, _ = _derive_release_summary(
                raw,
                workload_contract=WORKLOAD_CONTRACT_BASE,
            )
            paths["summary"].write_text(
                json.dumps(release, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            paths["minecraft_log"].write_text(minecraft_evidence, encoding="utf-8")
            paths["console_log"].write_text(
                "Gradle/Minecraft console completed normally\n",
                encoding="utf-8",
            )
            recomputed, measurement = _validate_release_bundle(
                raw.resolve(),
                paths["summary"].resolve(),
                paths["minecraft_log"].resolve(),
                paths["console_log"].resolve(),
                workload_contract=WORKLOAD_CONTRACT_BASE,
            )
            recomputed["report"] = release["report"]
            assert recomputed == release
            accepted_payload = {
                "schema_version": 3,
                "accepted": True,
                "raw_report": str(raw.resolve()),
                "raw_sha256": _file_sha256(raw),
                "summary": str(paths["summary"].resolve()),
                "summary_sha256": _file_sha256(paths["summary"]),
                "minecraft_log": str(paths["minecraft_log"].resolve()),
                "minecraft_log_sha256": _file_sha256(paths["minecraft_log"]),
                "console_log": str(paths["console_log"].resolve()),
                "console_log_sha256": _file_sha256(paths["console_log"]),
                "measurement": measurement,
                "presented_frames": release["presented_frames"],
                "workload": release["workload"],
                "metadata": release["metadata"],
            }
            paths["attestation"].write_text(
                json.dumps(accepted_payload, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            return raw, release

        def make_legacy_v4_bundle(
            stem: str,
            source_sha256: str,
            artifact_sha256: str = "5" * 64,
        ) -> tuple[Path, dict[str, Any]]:
            raw = root / f"{stem}.raw.jsonl"
            paths = _artifact_paths(raw)
            payloads = [
                json.loads(value)
                for value in report_text(source_sha256, artifact_sha256).splitlines()
            ]
            for payload in payloads:
                payload["workload"].pop("private_geometry_heap")
                payload["metadata"].pop("static_geometry_heaps_enabled")
            raw.write_text(
                "\n".join(json.dumps(payload) for payload in payloads) + "\n",
                encoding="utf-8",
            )
            release, _ = _derive_release_summary(
                raw,
                workload_contract=WORKLOAD_CONTRACT_EXPANDED,
            )
            paths["summary"].write_text(
                json.dumps(release, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            paths["minecraft_log"].write_text(minecraft_evidence, encoding="utf-8")
            paths["console_log"].write_text(
                "Gradle/Minecraft console completed normally\n",
                encoding="utf-8",
            )
            recomputed, measurement = _validate_release_bundle(
                raw.resolve(),
                paths["summary"].resolve(),
                paths["minecraft_log"].resolve(),
                paths["console_log"].resolve(),
                workload_contract=WORKLOAD_CONTRACT_EXPANDED,
            )
            recomputed["report"] = release["report"]
            assert recomputed == release
            accepted_payload = {
                "schema_version": 4,
                "accepted": True,
                "raw_report": str(raw.resolve()),
                "raw_sha256": _file_sha256(raw),
                "summary": str(paths["summary"].resolve()),
                "summary_sha256": _file_sha256(paths["summary"]),
                "minecraft_log": str(paths["minecraft_log"].resolve()),
                "minecraft_log_sha256": _file_sha256(paths["minecraft_log"]),
                "console_log": str(paths["console_log"].resolve()),
                "console_log_sha256": _file_sha256(paths["console_log"]),
                "measurement": measurement,
                "presented_frames": release["presented_frames"],
                "workload": release["workload"],
                "metadata": release["metadata"],
            }
            paths["attestation"].write_text(
                json.dumps(accepted_payload, indent=2, sort_keys=True) + "\n",
                encoding="utf-8",
            )
            return raw, release

        new, new_summary = make_bundle("new", "c" * 64)
        assert old_summary["presented_frames"] == 3000
        assert new_summary["presented_frames"] == 3000
        workload_summary = new_summary["workload"]
        assert workload_summary["aggregate_totals"]["command_buffers"] == 3000
        assert workload_summary["aggregate_totals"]["encoders"]["pass_boundaries"] == 3030
        assert workload_summary["aggregate_totals"]["copy_bytes"][
            "shared_to_private"
        ] == 10240
        assert workload_summary["per_presented_frame"]["command_buffers"] == 1.0
        transient_high_water = workload_summary["transient_memory_high_water"]
        assert transient_high_water == {
            "cpu": {
                "requested_high_water_bytes": 1010,
                "reserved_high_water_bytes": 2010,
            },
            "gpu_shared": {
                "requested_high_water_bytes": 3020,
                "reserved_high_water_bytes": 5030,
            },
        }
        assert "transient_memory" not in workload_summary["aggregate_totals"]
        assert "transient_memory" not in workload_summary["per_presented_frame"]
        assert "private_geometry_heap" not in workload_summary["aggregate_totals"]
        assert "private_geometry_heap" not in workload_summary["per_presented_frame"]
        heap_summary = workload_summary["private_geometry_heap"]
        assert heap_summary["configuration"] == {
            "enabled": True,
            "page_size_bytes": 64 * 1024 * 1024,
            "page_limit_per_device": 8,
        }
        assert heap_summary["process_lifetime_totals_at_last_window"][
            "requests_total"
        ] == 20
        assert heap_summary["process_lifetime_totals_at_last_window"][
            "backing_allocations_total"
        ] == 3
        assert heap_summary["deltas_between_first_and_last_snapshot"][
            "requests_total"
        ] == 9
        assert heap_summary["deltas_between_first_and_last_snapshot"][
            "backing_allocations_total"
        ] == 0
        assert heap_summary["gauges_at_last_window"]["pages_current"] == 1
        assert heap_summary["gauge_maxima_across_selected_windows"][
            "live_allocations"
        ] == 1
        assert len(workload_summary["selected_window_totals"]) == 10
        assert workload_summary["selected_window_totals"][0]["workload"] == line(
            2, 1
        )["workload"]
        assert compare(
            old_summary,
            new_summary,
            0.2,
            require_stability=False,
        )["verdict"] == "WITHIN_THRESHOLD"

        verified = verify_attestation(new)
        assert verified["schema_version"] == 5
        assert verified["presented_frames"] == 3000
        assert verified["workload"] == new_summary["workload"]
        new_attested = summarize_attested_release(new, 3000, 0, "OFF")
        assert new_attested["attested_runtime"] == verified["measurement"]["runtime"]
        new_without_runtime = dict(new_attested)
        new_without_runtime.pop("attested_runtime")
        assert new_without_runtime == new_summary

        # Schema v4 remains valid for the exact pre-heap expanded workload.
        # Its heap mode is unknown, so it cannot be silently mixed with v5.
        legacy_v4, legacy_v4_summary = make_legacy_v4_bundle(
            "legacy-v4",
            "c" * 64,
        )
        legacy_v4_verified = verify_attestation(legacy_v4)
        assert legacy_v4_verified["schema_version"] == 4
        legacy_v4_attested = summarize_attested_release(
            legacy_v4,
            3000,
            0,
            "OFF",
        )
        assert "transient_memory_high_water" in legacy_v4_attested["workload"]
        assert "private_geometry_heap" not in legacy_v4_attested["workload"]
        expect_error(
            lambda: compare(new_attested, legacy_v4_attested, 0.2),
            "static_geometry_heaps_enabled",
        )
        legacy_v4_without_runtime = dict(legacy_v4_attested)
        legacy_v4_without_runtime.pop("attested_runtime")
        assert legacy_v4_without_runtime == legacy_v4_summary

        # Schema v3 remains the strict contract for the exact pre-transient
        # workload shape and can still be compared with schema v4.
        legacy_v3, legacy_v3_summary = make_legacy_v3_bundle(
            "legacy-v3",
            "c" * 64,
        )
        legacy_v3_verified = verify_attestation(legacy_v3)
        assert legacy_v3_verified["schema_version"] == 3
        legacy_v3_attested = summarize_attested_release(
            legacy_v3,
            3000,
            0,
            "OFF",
        )
        assert "workload" in legacy_v3_attested
        assert "transient_memory_high_water" not in legacy_v3_attested["workload"]
        assert compare(legacy_v4_attested, legacy_v3_attested, 0.2)[
            "verdict"
        ] == "WITHIN_THRESHOLD"
        legacy_v3_without_runtime = dict(legacy_v3_attested)
        legacy_v3_without_runtime.pop("attested_runtime")
        assert legacy_v3_without_runtime == legacy_v3_summary

        legacy_v3_paths = _artifact_paths(legacy_v3)
        legacy_v3_attestation_text = legacy_v3_paths["attestation"].read_text(
            encoding="utf-8"
        )
        v4_missing_transient = json.loads(legacy_v3_attestation_text)
        v4_missing_transient["schema_version"] = 4
        legacy_v3_paths["attestation"].write_text(
            json.dumps(v4_missing_transient),
            encoding="utf-8",
        )
        expect_error(
            lambda: verify_attestation(legacy_v3),
            "requires expanded workload telemetry with transient_memory",
        )
        legacy_v3_paths["attestation"].write_text(
            legacy_v3_attestation_text,
            encoding="utf-8",
        )

        # Existing accepted schema-v2 bundles predate workload telemetry. They
        # remain strict, fully revalidated comparison inputs, but only when the
        # raw report and summary truly contain no workload object.
        legacy_v2, legacy_v2_summary = make_legacy_v2_bundle(
            "legacy-v2",
            "c" * 64,
        )
        legacy_verified = verify_attestation(legacy_v2)
        assert legacy_verified["schema_version"] == 2
        legacy_attested = summarize_attested_release(legacy_v2, 3000, 0, "OFF")
        assert "workload" not in legacy_attested
        assert compare(legacy_v3_attested, legacy_attested, 0.2)[
            "verdict"
        ] == "WITHIN_THRESHOLD"
        legacy_without_runtime = dict(legacy_attested)
        legacy_without_runtime.pop("attested_runtime")
        assert legacy_without_runtime == legacy_v2_summary

        legacy_paths = _artifact_paths(legacy_v2)
        legacy_attestation_text = legacy_paths["attestation"].read_text(
            encoding="utf-8"
        )
        v3_missing_workload = json.loads(legacy_attestation_text)
        v3_missing_workload["schema_version"] = 3
        legacy_paths["attestation"].write_text(
            json.dumps(v3_missing_workload),
            encoding="utf-8",
        )
        expect_error(
            lambda: verify_attestation(legacy_v2),
            "requires workload telemetry",
        )
        legacy_paths["attestation"].write_text(
            legacy_attestation_text,
            encoding="utf-8",
        )
        legacy_payload_with_workload = json.loads(legacy_attestation_text)
        legacy_payload_with_workload["workload"] = {}
        legacy_paths["attestation"].write_text(
            json.dumps(legacy_payload_with_workload),
            encoding="utf-8",
        )
        expect_error(
            lambda: verify_attestation(legacy_v2),
            "schema-v2 attestation cannot attest workload telemetry",
        )
        legacy_paths["attestation"].write_text(
            legacy_attestation_text,
            encoding="utf-8",
        )

        paths = _artifact_paths(new)
        accepted_text = paths["attestation"].read_text(encoding="utf-8")
        summary_text = paths["summary"].read_text(encoding="utf-8")
        raw_text = new.read_text(encoding="utf-8")
        minecraft_text = paths["minecraft_log"].read_text(encoding="utf-8")

        # create_attestation must independently reject a caller-supplied summary
        # whose metrics do not match a release-contract recalculation of the raw data.
        forged_summary = json.loads(summary_text)
        forged_summary["fps"]["elapsed_weighted"] += 1.0
        paths["summary"].write_text(json.dumps(forged_summary), encoding="utf-8")
        expect_error(
            lambda: create_attestation(
                new,
                paths["summary"],
                paths["minecraft_log"],
                paths["console_log"],
                paths["attestation"],
            ),
            "independent release-contract recalculation",
        )
        paths["summary"].write_text(summary_text, encoding="utf-8")

        forged_summary = json.loads(summary_text)
        forged_summary["workload"]["selected_window_totals"][0]["workload"][
            "command_buffers"
        ] += 1
        paths["summary"].write_text(json.dumps(forged_summary), encoding="utf-8")
        expect_error(
            lambda: create_attestation(
                new,
                paths["summary"],
                paths["minecraft_log"],
                paths["console_log"],
                paths["attestation"],
            ),
            "independent release-contract recalculation",
        )
        paths["summary"].write_text(summary_text, encoding="utf-8")

        # Log evidence is part of acceptance: route, display, frame boundaries,
        # and event order must all match the strict fixture contract exactly.
        def expect_log_error(log_text: str, expected_text: str) -> None:
            paths["minecraft_log"].write_text(log_text, encoding="utf-8")
            expect_error(
                lambda: create_attestation(
                    new,
                    paths["summary"],
                    paths["minecraft_log"],
                    paths["console_log"],
                    paths["attestation"],
                ),
                expected_text,
            )

        expect_log_error(
            minecraft_text.replace("presented_frame=4800", "presented_frame=4799"),
            "measurement boundary",
        )
        expect_log_error(
            minecraft_text.replace("warmup=1800", "warmup=1799"),
            "malformed ARMED event",
        )
        expect_log_error(
            minecraft_text.replace(
                "video_mode=3024x1964@120 (24bit)",
                "video_mode=3024x1964@60 (24bit)",
            ),
            "malformed WINDOW_READY event",
        )
        reordered_lines = minecraft_text.splitlines()
        armed_index = next(
            index for index, value in enumerate(reordered_lines)
            if "EVENT=ARMED" in value
        )
        window_index = next(
            index for index, value in enumerate(reordered_lines)
            if "EVENT=WINDOW_READY" in value
        )
        reordered_lines[armed_index], reordered_lines[window_index] = (
            reordered_lines[window_index],
            reordered_lines[armed_index],
        )
        expect_log_error(
            "\n".join(reordered_lines) + "\n",
            "events are out of order",
        )
        paths["minecraft_log"].write_text(minecraft_text, encoding="utf-8")
        console_text = paths["console_log"].read_text(encoding="utf-8")
        paths["console_log"].write_text(
            console_text + "[metallum] GPU timing workload window mismatch\n",
            encoding="utf-8",
        )
        expect_error(
            lambda: create_attestation(
                new,
                paths["summary"],
                paths["minecraft_log"],
                paths["console_log"],
                paths["attestation"],
            ),
            "Metal timing/command-buffer failure",
        )
        paths["console_log"].write_text(console_text, encoding="utf-8")
        paths["console_log"].write_text(
            console_text + "[metallum] Java workload telemetry invalid\n",
            encoding="utf-8",
        )
        expect_error(
            lambda: create_attestation(
                new,
                paths["summary"],
                paths["minecraft_log"],
                paths["console_log"],
                paths["attestation"],
            ),
            "Metal timing/command-buffer failure",
        )
        paths["console_log"].write_text(console_text, encoding="utf-8")
        create_attestation(
            new,
            paths["summary"],
            paths["minecraft_log"],
            paths["console_log"],
            paths["attestation"],
        )
        accepted_text = paths["attestation"].read_text(encoding="utf-8")

        # Every attested artifact is re-hashed during verification.
        new.write_text(raw_text + "\n", encoding="utf-8")
        expect_error(lambda: verify_attestation(new), "raw report changed")
        new.write_text(raw_text, encoding="utf-8")
        paths["summary"].write_text(summary_text + " ", encoding="utf-8")
        expect_error(lambda: verify_attestation(new), "attested summary changed")
        paths["summary"].write_text(summary_text, encoding="utf-8")

        # Sidecar paths and copied metadata cannot be redirected or rewritten.
        forged_attestation = json.loads(accepted_text)
        forged_attestation["summary"] = str(paths["console_log"])
        paths["attestation"].write_text(json.dumps(forged_attestation), encoding="utf-8")
        expect_error(lambda: verify_attestation(new), "summary path is inconsistent")
        forged_attestation = json.loads(accepted_text)
        forged_attestation["metadata"]["source_sha256"] = "e" * 64
        paths["attestation"].write_text(json.dumps(forged_attestation), encoding="utf-8")
        expect_error(lambda: verify_attestation(new), "metadata is inconsistent")
        forged_attestation = json.loads(accepted_text)
        forged_attestation["workload"]["aggregate_totals"]["command_buffers"] += 1
        paths["attestation"].write_text(json.dumps(forged_attestation), encoding="utf-8")
        expect_error(lambda: verify_attestation(new), "workload is inconsistent")
        paths["attestation"].write_text(accepted_text, encoding="utf-8")

        # A heap-aware schema-v5 bundle cannot be downgraded to a legacy
        # workload contract. Pre-attestation schema versions remain invalid.
        legacy_attestation = json.loads(accepted_text)
        legacy_attestation["schema_version"] = 4
        paths["attestation"].write_text(
            json.dumps(legacy_attestation),
            encoding="utf-8",
        )
        expect_error(
            lambda: verify_attestation(new),
            "expanded workload contract rejects private geometry heap telemetry",
        )
        legacy_attestation = json.loads(accepted_text)
        legacy_attestation["schema_version"] = 3
        paths["attestation"].write_text(
            json.dumps(legacy_attestation),
            encoding="utf-8",
        )
        expect_error(
            lambda: verify_attestation(new),
            "base workload contract rejects expanded transient-memory telemetry",
        )
        legacy_attestation = json.loads(accepted_text)
        legacy_attestation["schema_version"] = 2
        legacy_attestation.pop("workload")
        paths["attestation"].write_text(
            json.dumps(legacy_attestation),
            encoding="utf-8",
        )
        expect_error(
            lambda: verify_attestation(new),
            "no-workload contract rejects workload telemetry",
        )
        legacy_attestation = json.loads(accepted_text)
        legacy_attestation["schema_version"] = 1
        paths["attestation"].write_text(
            json.dumps(legacy_attestation),
            encoding="utf-8",
        )
        expect_error(lambda: verify_attestation(new), "invalid benchmark attestation")
        paths["attestation"].write_text(accepted_text, encoding="utf-8")

        paths["attestation"].unlink()
        expect_error(lambda: verify_attestation(new), "cannot read benchmark attestation")
        paths["attestation"].write_text(accepted_text, encoding="utf-8")

        # A selected-window metadata change invalidates the raw report itself.
        mismatch = root / "mismatch.raw.jsonl"
        mismatch_payloads = [line(2, index + 1) for index in range(10)]
        mismatch_payloads[-1]["metadata"]["monitor"] = "External Display"
        mismatch.write_text(
            "\n".join(json.dumps(payload) for payload in mismatch_payloads) + "\n",
            encoding="utf-8",
        )
        expect_error(
            lambda: summarize(mismatch, 3000, 0, "OFF"),
            "selected-window metadata changed",
        )

        def mutated_raw(stem: str, mutate: Any) -> Path:
            payloads = [json.loads(value) for value in report_text("c" * 64).splitlines()]
            mutate(payloads)
            path = root / f"{stem}.raw.jsonl"
            path.write_text(
                "\n".join(json.dumps(payload) for payload in payloads) + "\n",
                encoding="utf-8",
            )
            return path

        missing_low = mutated_raw(
            "missing-low",
            lambda payloads: payloads[1].pop("fps_1_percent_low"),
        )
        expect_error(
            lambda: _derive_release_summary(missing_low),
            "requires 1% and 0.1% FPS lows",
        )
        missing_present = mutated_raw(
            "missing-present",
            lambda payloads: payloads[1].pop("present_interval_ms"),
        )
        expect_error(
            lambda: _derive_release_summary(missing_present),
            "requires present-interval timing",
        )
        def remove_workload(payloads: list[dict[str, Any]]) -> None:
            for payload in payloads:
                payload.pop("workload")

        missing_workload = mutated_raw("missing-workload", remove_workload)
        expect_error(
            lambda: _derive_release_summary(missing_workload),
            "requires workload telemetry",
        )

        unknown_workload_key = mutated_raw(
            "unknown-workload-key",
            lambda payloads: payloads[1]["workload"].__setitem__("total", 1),
        )
        expect_error(
            lambda: _derive_release_summary(unknown_workload_key),
            "workload must have exact keys",
        )

        def remove_private_geometry_heap(payloads: list[dict[str, Any]]) -> None:
            for payload in payloads:
                payload["workload"].pop("private_geometry_heap")

        missing_heap = mutated_raw(
            "missing-private-geometry-heap",
            remove_private_geometry_heap,
        )
        expect_error(
            lambda: _derive_release_summary(missing_heap),
            "requires exact workload.private_geometry_heap telemetry",
        )

        extra_heap_key = mutated_raw(
            "extra-private-geometry-heap-key",
            lambda payloads: payloads[1]["workload"][
                "private_geometry_heap"
            ].__setitem__("unknown", 1),
        )
        expect_error(
            lambda: _derive_release_summary(extra_heap_key),
            "workload.private_geometry_heap must have exact keys",
        )

        def break_heap_backing_algebra(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["private_geometry_heap"][
                "backing_allocations_total"
            ] = 4

        invalid_backing_algebra = mutated_raw(
            "invalid-heap-backing-algebra",
            break_heap_backing_algebra,
        )
        expect_error(
            lambda: _derive_release_summary(invalid_backing_algebra),
            "backing_allocations_total must equal pages_created_total + "
            "fallback_allocations_total",
        )

        decreasing_heap_total = mutated_raw(
            "decreasing-heap-total",
            lambda payloads: payloads[1]["workload"].__setitem__(
                "private_geometry_heap",
                heap_snapshot(100, True),
            ),
        )
        expect_error(
            lambda: _derive_release_summary(decreasing_heap_total),
            "decrease private geometry heap cumulative total",
        )

        mismatched_heap_mode = mutated_raw(
            "mismatched-heap-mode",
            lambda payloads: payloads[1]["metadata"].__setitem__(
                "static_geometry_heaps_enabled",
                False,
            ),
        )
        expect_error(
            lambda: _derive_release_summary(mismatched_heap_mode),
            "static_geometry_heaps_enabled differs from "
            "workload.private_geometry_heap.enabled",
        )

        def make_transient_negative(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["transient_memory"]["cpu"][
                "requested_high_water_bytes"
            ] = -1

        negative_transient = mutated_raw(
            "negative-transient",
            make_transient_negative,
        )
        expect_error(
            lambda: _derive_release_summary(negative_transient),
            "requested_high_water_bytes must be an integer >= 0",
        )

        def make_transient_boolean(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["transient_memory"]["gpu_shared"][
                "reserved_high_water_bytes"
            ] = True

        boolean_transient = mutated_raw(
            "boolean-transient",
            make_transient_boolean,
        )
        expect_error(
            lambda: _derive_release_summary(boolean_transient),
            "reserved_high_water_bytes must be an integer >= 0",
        )

        def invert_transient_high_water(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["transient_memory"]["cpu"].update({
                "requested_high_water_bytes": 2048,
                "reserved_high_water_bytes": 1024,
            })

        inverted_transient = mutated_raw(
            "inverted-transient",
            invert_transient_high_water,
        )
        expect_error(
            lambda: _derive_release_summary(inverted_transient),
            "reserved_high_water_bytes must be >= requested_high_water_bytes",
        )

        def add_transient_extra(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["transient_memory"]["cpu"]["peak"] = 1

        extra_transient = mutated_raw("extra-transient", add_transient_extra)
        expect_error(
            lambda: _derive_release_summary(extra_transient),
            "workload.transient_memory.cpu must have exact keys",
        )

        def break_pass_boundary(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["encoders"]["pass_boundaries"] += 1

        mismatched_passes = mutated_raw("mismatched-passes", break_pass_boundary)
        expect_error(
            lambda: _derive_release_summary(mismatched_passes),
            "pass_boundaries must equal render + compute + blit",
        )

        def make_copy_bytes_negative(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["copy_bytes"]["gpu_internal"] = -1

        negative_workload = mutated_raw("negative-workload", make_copy_bytes_negative)
        expect_error(
            lambda: _derive_release_summary(negative_workload),
            "workload.copy_bytes.gpu_internal must be an integer >= 0",
        )

        def exceed_copy_command_count(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["copy_bytes"][
                "byte_count_unknown_commands"
            ] = 99

        impossible_unknown_count = mutated_raw(
            "impossible-unknown-count",
            exceed_copy_command_count,
        )
        expect_error(
            lambda: _derive_release_summary(impossible_unknown_count),
            "byte_count_unknown_commands exceeds classified copy commands",
        )

        def make_command_buffers_boolean(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["command_buffers"] = True

        boolean_integer = mutated_raw("boolean-integer", make_command_buffers_boolean)
        expect_error(
            lambda: _derive_release_summary(boolean_integer),
            "workload.command_buffers must be an integer >= 0",
        )

        def make_direct_write_integer(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["workload"]["copy_bytes"]["direct_write_observed"] = 0

        non_boolean_direct_write = mutated_raw(
            "non-boolean-direct-write",
            make_direct_write_integer,
        )
        expect_error(
            lambda: _derive_release_summary(non_boolean_direct_write),
            "direct_write_observed must be a boolean",
        )

        def remove_headroom(payloads: list[dict[str, Any]]) -> None:
            for payload in payloads:
                payload["metadata"]["current_edr_headroom"] = 1.0

        no_headroom = mutated_raw("no-headroom", remove_headroom)
        expect_error(
            lambda: _derive_release_summary(no_headroom),
            "current EDR headroom > 1.0",
        )
        short_report = mutated_raw("short", lambda payloads: payloads.pop())
        expect_error(
            lambda: _derive_release_summary(short_report),
            "requires exactly 3000 measured frames",
        )

        def collapse_window_count(payloads: list[dict[str, Any]]) -> None:
            payloads[1]["presented_frames"] = 600
            payloads.pop()

        wrong_window_count = mutated_raw("wrong-window-count", collapse_window_count)
        expect_error(
            lambda: _derive_release_summary(wrong_window_count),
            "ten complete 300-frame timing windows",
        )

        for missing_key, expected_text in (
            ("window_count", "lacks window_count"),
            ("fps_low_window_summaries", "lacks FPS low summaries"),
            ("present_interval_ms", "lacks present-interval summaries"),
        ):
            incomplete = json.loads(json.dumps(new_attested))
            incomplete.pop(missing_key)
            expect_error(
                lambda incomplete=incomplete: compare(new_attested, incomplete, 0.2),
                expected_text,
            )

        unstable = json.loads(json.dumps(new_attested))
        unstable["fps_low_window_summaries"]["one_percent"][
            "window_frame_weighted_mean"
        ] *= 0.5
        unstable_result = compare(new_attested, unstable, 0.2)
        assert unstable_result["verdict"] == "REGRESSION"
        assert any(
            item["metric"] == "FPS 1% low"
            for item in unstable_result["gate"]["regressions"]
        )

        # Heap mode is part of the strict comparison contract. Only the narrow,
        # attested OFF-vs-ON override may exclude that single metadata key.
        heap_off, _ = make_bundle(
            "heap-off",
            "c" * 64,
            "5" * 64,
            heap_enabled=False,
        )
        heap_off_attested = summarize_attested_release(heap_off, 3000, 0, "OFF")
        expect_error(
            lambda: compare(heap_off_attested, new_attested, 0.2),
            "static_geometry_heaps_enabled",
        )
        assert compare(
            heap_off_attested,
            new_attested,
            0.2,
            allow_static_geometry_heap_mode_change=True,
        )["verdict"] == "WITHIN_THRESHOLD"
        expect_error(
            lambda: compare(
                new_attested,
                new_attested,
                0.2,
                allow_static_geometry_heap_mode_change=True,
            ),
            "requires an explicit OFF-vs-ON pair",
        )
        expect_error(
            lambda: compare(
                new_summary,
                heap_off_attested,
                0.2,
                allow_static_geometry_heap_mode_change=True,
                require_stability=False,
            ),
            "requires attested reports",
        )

        # Same source and build artifact are the strict default. The explicit
        # attested-only override permits both to change for an optimization.
        changed_source, changed_summary = make_bundle(
            "changed",
            "d" * 64,
            "6" * 64,
        )
        changed_attested = summarize_attested_release(changed_source, 3000, 0, "OFF")
        expect_error(
            lambda: compare(new_attested, changed_attested, 0.2),
            "source_sha256",
        )
        assert compare(
            new_attested,
            changed_attested,
            0.2,
            allow_source_change=True,
        )["verdict"] == "WITHIN_THRESHOLD"
        changed_without_runtime = dict(changed_attested)
        changed_without_runtime.pop("attested_runtime")
        assert changed_summary == changed_without_runtime

        artifact_change, _ = make_bundle("artifact-change", "c" * 64, "7" * 64)
        artifact_changed_attested = summarize_attested_release(
            artifact_change,
            3000,
            0,
            "OFF",
        )
        expect_error(
            lambda: compare(new_attested, artifact_changed_attested, 0.2),
            "artifact_sha256",
        )
    print("metal benchmark report self-test passed")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    sub = result.add_subparsers(dest="command", required=True)
    summary = sub.add_parser("summarize")
    summary.add_argument("report", type=Path)
    summary.add_argument("--measure-frames", type=int, default=DEFAULT_MEASURE_FRAMES)
    summary.add_argument("--segment", type=int, default=0)
    summary.add_argument("--scaler-mode", choices=("OFF", "QUALITY", "PERFORMANCE"), default="OFF")
    summary.add_argument("--release-contract", action="store_true")
    summary.add_argument("--source-sha256", default="unknown")
    summary.add_argument("--artifact-sha256", default="unknown")
    summary.add_argument("--settings-id", default="unknown")
    summary.add_argument("--settings-spec-sha256", default="unknown")
    summary.add_argument("--settings-sha256", default="unknown")
    summary.add_argument("--world", default="HDRTest")
    summary.add_argument("--fixture", default="unknown")
    summary.add_argument("--fixture-sha256", default="unknown")
    summary.add_argument("--route", default="static-heavy")
    summary.add_argument("--route-sha256", default="unknown")
    summary.add_argument("--player-name", default="unknown")
    summary.add_argument("--player-uuid", default="unknown")
    summary.add_argument("--dimension", default="unknown")
    summary.add_argument("--simulation-frozen", action="store_true")
    summary.add_argument("--json", action="store_true")

    comparison = sub.add_parser("compare")
    comparison.add_argument("baseline", type=Path)
    comparison.add_argument("candidate", type=Path)
    comparison.add_argument("--measure-frames", type=int, default=DEFAULT_MEASURE_FRAMES)
    comparison.add_argument("--segment", type=int, default=0)
    comparison.add_argument("--scaler-mode", choices=("OFF", "QUALITY", "PERFORMANCE"), default="OFF")
    comparison.add_argument("--p95-regression-ms", type=float, default=DEFAULT_P95_GATE_MS)
    comparison.add_argument(
        "--provisional",
        action="store_true",
        help="allow unattested or legacy reports; never use for a release gate",
    )
    comparison.add_argument(
        "--allow-source-change",
        action="store_true",
        help=(
            "allow different attested source and build-artifact digests for an "
            "intentional optimization comparison; incompatible with --provisional"
        ),
    )
    comparison.add_argument(
        "--allow-static-geometry-heap-mode-change",
        action="store_true",
        help=(
            "allow one intentional attested OFF-vs-ON static geometry heap "
            "comparison; incompatible with --provisional"
        ),
    )
    comparison.add_argument("--json", action="store_true")
    attest = sub.add_parser("attest")
    attest.add_argument("raw_report", type=Path)
    attest.add_argument("summary", type=Path)
    attest.add_argument("minecraft_log", type=Path)
    attest.add_argument("console_log", type=Path)
    attest.add_argument("output", type=Path)
    sub.add_parser("self-test")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "self-test":
            self_test()
            return 0
        if args.command == "attest":
            create_attestation(
                args.raw_report,
                args.summary,
                args.minecraft_log,
                args.console_log,
                args.output,
            )
            return 0
        if args.command == "summarize":
            summary = summarize(
                args.report,
                args.measure_frames,
                args.segment,
                args.scaler_mode,
                release_contract=args.release_contract,
                source_sha256=args.source_sha256,
                artifact_sha256=args.artifact_sha256,
                settings_id=args.settings_id,
                settings_spec_sha256=args.settings_spec_sha256,
                settings_sha256=args.settings_sha256,
                world=args.world,
                fixture=args.fixture,
                fixture_sha256=args.fixture_sha256,
                route=args.route,
                route_sha256=args.route_sha256,
                player_name=args.player_name,
                player_uuid=args.player_uuid,
                dimension=args.dimension,
                simulation_frozen=args.simulation_frozen,
            )
            if args.json:
                json.dump(summary, sys.stdout, indent=2, sort_keys=True)
                print()
            else:
                _print_summary(summary)
            return 0
        if args.provisional and (
            args.allow_source_change
            or args.allow_static_geometry_heap_mode_change
        ):
            raise ReportError(
                "comparison overrides require attested reports and cannot be used "
                "with --provisional"
            )
        summary_loader = summarize if args.provisional else summarize_attested_release
        baseline = summary_loader(
            args.baseline, args.measure_frames, args.segment, args.scaler_mode
        )
        candidate = summary_loader(
            args.candidate, args.measure_frames, args.segment, args.scaler_mode
        )
        result = compare(
            baseline,
            candidate,
            args.p95_regression_ms,
            allow_source_change=args.allow_source_change,
            allow_static_geometry_heap_mode_change=(
                args.allow_static_geometry_heap_mode_change
            ),
            require_stability=not args.provisional,
        )
        if args.json:
            json.dump(result, sys.stdout, indent=2, sort_keys=True)
            print()
        else:
            _print_comparison(result)
        return 3 if result["verdict"] == "REGRESSION" else 0
    except ReportError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
