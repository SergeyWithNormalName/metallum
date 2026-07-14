#!/usr/bin/env python3
"""Validate, summarize, and compare Metallum timing JSONL reports.

Reports contain percentiles for independent 300-frame windows, not raw frame
samples.  This tool therefore reports weighted summaries of window metrics and
never presents them as an exact percentile over the whole run.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Sequence


DEFAULT_MEASURE_FRAMES = 3000
DEFAULT_P95_GATE_MS = 0.2
BUILT_IN_MONITOR = "Built-in Retina Display"
BUILT_IN_WIDTH = 3024
BUILT_IN_HEIGHT = 1964


class ReportError(ValueError):
    """The input cannot support an honest comparison."""


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
        phase=benchmark.get("phase") if schema == 2 else None,
        generation=(
            _integer(benchmark.get("generation"), "benchmark.generation", line)
            if schema == 2 else None
        ),
        segment=(
            _integer(benchmark.get("segment_index"), "benchmark.segment_index", line)
            if schema == 2 else None
        ),
        scaler=benchmark.get("scaler_mode") if schema == 2 else None,
        low_1=_optional_number(payload.get("fps_1_percent_low"), "fps_1_percent_low", line),
        low_01=_optional_number(payload.get("fps_0_1_percent_low"), "fps_0_1_percent_low", line),
        present_interval=(
            _parse_present_interval(payload.get("present_interval_ms"), line)
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


def validate_release_contract(
    windows: Sequence[TimingWindow],
    *,
    scaler: str,
    world: str,
    route: str,
) -> None:
    expected_scaling = scaler != "OFF"
    for window in windows:
        if window.schema != 2:
            raise ReportError(f"line {window.line}: release contract requires schema v2")
        if window.detail:
            raise ReportError(f"line {window.line}: intrusive detail timing must be disabled")
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
            "world": world,
            "route": route,
        }
        for key, value in expected.items():
            if metadata.get(key) != value:
                raise ReportError(
                    f"line {window.line}: metadata.{key} must be {value!r} "
                    f"(found {metadata.get(key)!r})"
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


def summarize(
    path: Path,
    frames: int,
    segment: int,
    scaler: str,
    *,
    release_contract: bool = False,
    world: str = "HDRTest",
    route: str = "static-heavy",
) -> dict[str, Any]:
    all_windows = load_report(path)
    selected, selection = select_measurement(all_windows, frames, segment, scaler)
    if len({window.detail for window in selected}) != 1:
        raise ReportError("selected windows mix detailed and basic instrumentation")
    dropped = sum(window.dropped for window in selected)
    if dropped:
        raise ReportError(f"selected windows contain {dropped} dropped timing events")
    if release_contract:
        validate_release_contract(selected, scaler=scaler, world=world, route=route)

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
    if selected[0].schema == 2:
        result["metadata"] = selected[-1].metadata
    return result


def compare(baseline: dict[str, Any], candidate: dict[str, Any], gate_ms: float) -> dict[str, Any]:
    if not math.isfinite(gate_ms) or gate_ms < 0.0:
        raise ReportError("p95 gate must be finite and >= 0")
    if baseline["presented_frames"] != candidate["presented_frames"]:
        raise ReportError("baseline and candidate cover different frame counts")
    if baseline["detail_enabled"] != candidate["detail_enabled"]:
        raise ReportError("baseline and candidate use different detail instrumentation")
    if "metadata" in baseline and "metadata" in candidate:
        stable_metadata = (
            "world", "route", "monitor", "os_version", "thermal_state",
            "device_name", "executor", "refresh_hz", "render_width", "render_height",
            "display_width", "display_height", "scaler_active",
            "hdr_output_mode", "source_encoding", "current_edr_headroom",
            "display_sync_enabled",
        )
        mismatches = [
            key for key in stable_metadata
            if baseline["metadata"].get(key) != candidate["metadata"].get(key)
        ]
        if mismatches:
            raise ReportError(
                "baseline and candidate metadata differ: " + ", ".join(mismatches)
            )

    def metric(summary: dict[str, Any], key: str) -> float:
        return summary["presenting_command_buffer_gpu_ms"][
            "percentile_window_summaries"
        ][key]["window_frame_weighted_mean"]

    base_p95 = metric(baseline, "p95")
    candidate_p95 = metric(candidate, "p95")
    delta = candidate_p95 - base_p95
    if delta > gate_ms + 1e-12:
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


def self_test() -> None:
    def line(schema: int, index: int) -> dict[str, Any]:
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
                    "world": "HDRTest", "route": "static-heavy",
                    "thermal_state": "nominal", "device_name": "Apple M1 Pro",
                },
            })
        return payload

    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        old = root / "old.jsonl"
        new = root / "new.jsonl"
        old.write_text("\n".join(json.dumps(line(1, i)) for i in range(10)) + "\n")
        new.write_text("\n".join(json.dumps(line(2, i)) for i in range(10)) + "\n")
        old_summary = summarize(old, 3000, 0, "OFF")
        new_summary = summarize(
            new, 3000, 0, "OFF", release_contract=True,
        )
        assert old_summary["presented_frames"] == 3000
        assert new_summary["presented_frames"] == 3000
        assert compare(old_summary, new_summary, 0.2)["verdict"] == "WITHIN_THRESHOLD"
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
    summary.add_argument("--world", default="HDRTest")
    summary.add_argument("--route", default="static-heavy")
    summary.add_argument("--json", action="store_true")

    comparison = sub.add_parser("compare")
    comparison.add_argument("baseline", type=Path)
    comparison.add_argument("candidate", type=Path)
    comparison.add_argument("--measure-frames", type=int, default=DEFAULT_MEASURE_FRAMES)
    comparison.add_argument("--segment", type=int, default=0)
    comparison.add_argument("--scaler-mode", choices=("OFF", "QUALITY", "PERFORMANCE"), default="OFF")
    comparison.add_argument("--p95-regression-ms", type=float, default=DEFAULT_P95_GATE_MS)
    comparison.add_argument("--json", action="store_true")
    sub.add_parser("self-test")
    return result


def main(argv: Sequence[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        if args.command == "self-test":
            self_test()
            return 0
        if args.command == "summarize":
            summary = summarize(
                args.report,
                args.measure_frames,
                args.segment,
                args.scaler_mode,
                release_contract=args.release_contract,
                world=args.world,
                route=args.route,
            )
            if args.json:
                json.dump(summary, sys.stdout, indent=2, sort_keys=True)
                print()
            else:
                _print_summary(summary)
            return 0
        baseline = summarize(
            args.baseline, args.measure_frames, args.segment, args.scaler_mode
        )
        candidate = summarize(
            args.candidate, args.measure_frames, args.segment, args.scaler_mode
        )
        result = compare(baseline, candidate, args.p95_regression_ms)
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
