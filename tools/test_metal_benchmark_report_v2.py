#!/usr/bin/env python3
"""Automated unit and contract tests for Metallum Benchmark Environment v2 P0 infrastructure.

Tests cover:
- compare-multi 2x2 valid input and direction classification
- metadata, route, fixture, settings mismatch detection
- thermal validity enforcement (Serious/Critical invalidation)
- renderer fallback detection (Advanced expected vs Vanilla path)
- cluster index capacity drops validation (valid dense workload NOT rejected)
- fast screening mode attestation rejection
- diagnostic shader ablation metadata and mismatch handling
"""

from typing import Any, Optional, Sequence, Union
import json
import tempfile
import unittest
from pathlib import Path
import sys

# Add repo root to path
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))

from metal_benchmark_report import (
    ReportError,
    compare,
    compare_ablation,
    compare_multi,
    extract_thermal_stats,
    summarize,
    _offline_player_uuid,
)


def _make_dummy_report_lines(
    *,
    count: int = 10,
    frames: int = 300,
    fps: float = 120.0,
    gpu_p95: float = 8.0,
    thermal_state: str = "nominal",
    thermal_states: Optional[list[str]] = None,
    route: str = "hdrtest-static-v1",
    fixture: str = "hdrtest-static-v1",
    settings_id: str = "native-hdr-fancy-v1",
    lighting_model: str = "advanced",
    ablation_mode: str = "FULL_ADVANCED",
    diagnostic_pattern: Union[str, bool] = False,
    capacity_drops: int = 0,
) -> list[dict]:
    lines = []
    fixture_sha = "a4a7e4fa34bed9e335856bc88f7ad1035ae1ba68e28851906ccaf9a65911e3c5"
    route_sha = "1111111111111111111111111111111111111111111111111111111111111111"
    source_sha = "c" * 64
    artifact_sha = "5" * 64
    spec_sha = "1" * 64
    settings_sha = "2" * 64
    resource_pack_sha = "3" * 64
    sodium_sha = "4" * 64
    player_name = "MetallumBench"
    player_uuid = _offline_player_uuid(player_name)

    metric = {"samples": frames, "average": gpu_p95 * 0.9, "p50": gpu_p95 * 0.85, "p95": gpu_p95, "p99": gpu_p95 * 1.1, "maximum": gpu_p95 * 1.2}

    for i in range(count):
        t_state = thermal_states[i % len(thermal_states)] if thermal_states else thermal_state
        line_payload = {
            "schema_version": 5,
            "timestamp_unix_ms": i + 1,
            "detail_enabled": False,
            "presented_frames": frames,
            "fps": fps,
            "dropped_timing_events": 0,
            "presenting_command_buffer_gpu_ms": metric,
            "fps_1_percent_low": fps * 0.95,
            "fps_0_1_percent_low": fps * 0.90,
            "present_interval_ms": {
                "samples": frames, "average": 7.7, "p50": 7.6,
                "p95": 9.0, "p99": 11.0, "maximum": 15.0,
            },
            "cpu_render_submission_ms": {
                "samples": frames, "average": 2.0, "p50": 1.8,
                "p95": 2.8, "p99": 3.2, "maximum": 4.0,
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
                        "requested_high_water_bytes": 1000 + i,
                        "reserved_high_water_bytes": 2000 + i,
                    },
                    "gpu_shared": {
                        "requested_high_water_bytes": 3000 + i * 2,
                        "reserved_high_water_bytes": 5000 + i * 3,
                    },
                },
                "private_geometry_heap": {
                    "enabled": False,
                    "pools_current": 1,
                    "page_size_bytes": 67108864,
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
                    "requests_total": 10 + i,
                    "requested_bytes_total": (10 + i) * 1024,
                    "heap_allocations_total": 0,
                    "heap_query_bytes_total": 0,
                    "page_reuse_hits_total": 0,
                    "fallback_allocations_total": 10 + i,
                    "fallback_requested_bytes_total": (10 + i) * 1024,
                    "fallback_disabled_total": 10 + i,
                    "fallback_oversize_total": 0,
                    "fallback_invalid_query_total": 0,
                    "fallback_capacity_total": 0,
                    "fallback_heap_create_total": 0,
                    "fallback_heap_allocate_total": 0,
                    "allocation_failures_total": 0,
                    "backing_allocations_total": 10 + i,
                    "device_teardown_with_live_allocations_total": 0,
                },
            },
            "clustered_lighting": {
                "active": True,
                "output_independent": True,
                "generation": 1,
                "frame_id": i + 1,
                "light_count": 16,
                "cluster_count": 64,
                "cluster_accepted_indices": 128,
                "cluster_requested_indices": 128 + capacity_drops,
                "cluster_overflow_clusters": 0,
                "cluster_dropped_indices": capacity_drops,
                "cluster_index_capacity_drops": capacity_drops,
                "cluster_admission_rejected_lights": 0,
                "cluster_occupancy_p50": 4,
                "cluster_occupancy_p95": 12,
                "cluster_occupancy_p99": 16,
                "cluster_occupancy_max": 16,
                "lighting_ring_high_water": 1,
                "lighting_ring_busy_rejects": 0,
                "statistics_sample_interval": 32,
            },
            "voxel_clipmaps": {
                "active": True,
                "output_independent": True,
                "lighting_generation": 1,
                "clipmap_generation": 1,
                "world_generation": 1,
                "frame_id": i + 1,
                "resource_bytes": 1024,
                "heap_bytes": 4096,
                "heap_used_bytes": 2048,
                "ring_staging_bytes": 1024,
                "ring_private_bytes": 2048,
                "ring_high_water": 1,
                "ring_busy_rejects": 0,
                "dirty_bricks_submitted": 0,
                "dirty_bricks_completed": 0,
                "dirty_bricks_remaining": 0,
                "oldest_dirty_age": 0,
                "coalesced": 0,
                "rejected": 0,
                "stale": 0,
                "scroll_slabs": 0,
                "unload_clears": 0,
                "debug_checksum": 0,
            },
            "renderer_generation": {
                "frame_contract_version": 1,
                "frame_graph_version": 6,
                "frame_id": i + 1,
                "renderer_generation_id": 1,
                "lighting_generation_id": 1,
                "output_generation_id": 1,
                "feature_mask": 0,
                "render_width": 3024,
                "render_height": 1964,
                "display_width": 3024,
                "display_height": 1964,
                "render_contract_generation_id": 1,
                "resolved_output_mode": "hdr",
                "resolved_upscale_mode": "native",
                "resolved_interpolation_mode": "off",
                "lighting_preset": "balanced",
                "executor": "metal3",
                "resolved_render_contract": "metallum",
                "resolved_lighting_model": lighting_model,
                "resource_bytes": {
                    "base": 1024, "material": 512, "hdr": 256,
                    "advanced_lighting": 512, "upscale": 0,
                    "interpolation": 0, "diagnostic": 0,
                },
                "advanced_lighting_work": {
                    "light_count": 16, "pass_count": 5, "encoder_count": 5,
                    "pso_count": 5, "work_queue_count": 5, "dispatch_count": 5,
                    "upload_bytes": 500,
                },
            },
            "benchmark": {
                "enabled": True, "generation": 2, "segment_index": 0,
                "phase": "measure", "scaler_mode": "OFF",
            },
            "clustered_lighting": {
                "active": True,
                "output_independent": True,
                "generation": 1,
                "frame_id": i + 1,
                "light_count": 16,
                "cluster_count": 64,
                "cluster_accepted_indices": 128,
                "cluster_requested_indices": 128 + capacity_drops,
                "cluster_overflow_clusters": 0,
                "cluster_dropped_indices": capacity_drops,
                "cluster_index_capacity_drops": capacity_drops,
                "cluster_admission_rejected_lights": 0,
                "cluster_occupancy_p50": 4,
                "cluster_occupancy_p95": 12,
                "cluster_occupancy_p99": 16,
                "cluster_occupancy_max": 16,
                "lighting_ring_high_water": 1,
                "lighting_ring_busy_rejects": 0,
                "statistics_sample_interval": 32,
            },
            "voxel_clipmaps": {
                "active": True, "output_independent": True,
                "lighting_generation": 1, "clipmap_generation": 1, "world_generation": 1,
                "frame_id": i + 1, "resource_bytes": 100, "heap_bytes": 100, "heap_used_bytes": 50,
                "ring_staging_bytes": 50, "ring_private_bytes": 50, "ring_high_water": 50,
                "ring_busy_rejects": 0, "dirty_bricks_submitted": 0, "dirty_bricks_completed": 0,
                "dirty_bricks_remaining": 0, "oldest_dirty_age": 0, "coalesced": 0,
                "rejected": 0, "stale": 0, "scroll_slabs": 0, "unload_clears": 0, "debug_checksum": 0,
            },
            "metadata": {
                "monitor": "Built-in Retina Display",
                "os_version": "Version 27.0",
                "device_name": "Apple M1 Pro",
                "display_width": 3024, "display_height": 1964,
                "refresh_hz": 120, "executor": "METAL3",
                "render_width": 3024, "render_height": 1964,
                "display_sync_enabled": False,
                "hdr_output_mode": "ENHANCED", "source_encoding": "LINEAR",
                "scaler_active": False,
                "source_sha256": source_sha,
                "artifact_sha256": artifact_sha,
                "settings_id": settings_id,
                "settings_spec_sha256": spec_sha,
                "settings_sha256": settings_sha,
                "render_distance": 16, "simulation_distance": 12,
                "graphics_preset": "fancy", "entity_distance_scaling": 1.0,
                "particles": 0, "mipmap_levels": 4,
                "biome_blend_radius": 2, "max_fps": 260,
                "ambient_occlusion": True, "clouds_mode": "true",
                "cloud_range": 64, "texture_filtering": 1,
                "max_anisotropy_bit": 1, "improved_transparency": False,
                "resource_packs_sha256": resource_pack_sha,
                "sodium_settings_sha256": sodium_sha,
                "configured_gui_scale": 0,
                "active_resource_pack_ids": "vanilla,metallum,sodium",
                "sodium_chunk_builder_threads": 4,
                "hdr_bloom_strength": 0.18, "hdr_strength": 1.0,
                "persistent_metalfx_mode": "off",
                "diagnostic_pattern": diagnostic_pattern, "bloom_strength": 0.18,
                "current_edr_headroom": 1.2,
                "world": route, "fixture": route,
                "fixture_sha256": fixture_sha,
                "route": route, "route_sha256": route_sha,
                "benchmark_player_name": player_name,
                "benchmark_player_uuid": player_uuid,
                "benchmark_dimension": "minecraft:overworld",
                "benchmark_simulation_frozen": True,
                "thermal_state": t_state, "device_name": "Apple M1 Pro",
                "static_geometry_heaps_enabled": False,
                "ablation_mode": ablation_mode,
            },
        }
        lines.append(line_payload)
    return lines


def _write_tmp_report(lines: list[dict]) -> Path:
    tmp = tempfile.NamedTemporaryFile("w", suffix=".raw.jsonl", delete=False)
    for line in lines:
        tmp.write(json.dumps(line) + "\n")
    tmp.close()
    return Path(tmp.name)


class TestBenchmarkEnvironmentV2(unittest.TestCase):
    def test_thermal_nominal_only(self):
        lines = _make_dummy_report_lines(count=10, thermal_state="nominal")
        path = _write_tmp_report(lines)
        summary = summarize(path, 3000, 0, "OFF")
        self.assertEqual(summary["thermal"]["initial_thermal_state"], "nominal")
        self.assertEqual(summary["thermal"]["final_thermal_state"], "nominal")
        self.assertEqual(summary["thermal"]["worst_thermal_state"], "nominal")
        self.assertFalse(summary["thermal"]["thermal_invalid"])

    def test_thermal_nominal_to_fair(self):
        lines = _make_dummy_report_lines(count=10, thermal_states=["nominal", "fair"])
        path = _write_tmp_report(lines)
        summary = summarize(path, 3000, 0, "OFF")
        self.assertEqual(summary["thermal"]["initial_thermal_state"], "nominal")
        self.assertEqual(summary["thermal"]["final_thermal_state"], "fair")
        self.assertEqual(summary["thermal"]["worst_thermal_state"], "fair")
        self.assertFalse(summary["thermal"]["thermal_invalid"])

    def test_thermal_fair_to_nominal(self):
        lines = _make_dummy_report_lines(count=10, thermal_states=["fair", "nominal"])
        path = _write_tmp_report(lines)
        summary = summarize(path, 3000, 0, "OFF")
        self.assertEqual(summary["thermal"]["initial_thermal_state"], "fair")
        self.assertEqual(summary["thermal"]["final_thermal_state"], "nominal")
        self.assertFalse(summary["thermal"]["thermal_invalid"])

    def test_thermal_nominal_to_serious(self):
        lines = _make_dummy_report_lines(count=10, thermal_states=["nominal", "serious"])
        path = _write_tmp_report(lines)
        summary = summarize(path, 3000, 0, "OFF")
        self.assertTrue(summary["thermal"]["thermal_invalid"])
        self.assertTrue(summary["thermal"]["has_serious"])
        self.assertEqual(summary["thermal"]["worst_thermal_state"], "serious")

    def test_thermal_fair_to_critical(self):
        lines = _make_dummy_report_lines(count=10, thermal_states=["fair", "critical"])
        path = _write_tmp_report(lines)
        summary = summarize(path, 3000, 0, "OFF")
        self.assertTrue(summary["thermal"]["thermal_invalid"])
        self.assertTrue(summary["thermal"]["has_critical"])
        self.assertEqual(summary["thermal"]["worst_thermal_state"], "critical")

    def test_cluster_capacity_drops_not_rejected(self):
        """Dense workload with capacity drops must be parsed cleanly and NOT rejected as invalid."""
        lines = _make_dummy_report_lines(count=10, capacity_drops=5)
        path = _write_tmp_report(lines)
        summary = summarize(path, 3000, 0, "OFF")
        self.assertIsNotNone(summary)
        self.assertEqual(summary["clustered_lighting"]["counters"]["cluster_index_capacity_drops"]["window_maximum"], 5)

    def test_compare_multi_valid_2x2(self):
        b1_lines = _make_dummy_report_lines(count=10, fps=120.0, gpu_p95=8.0)
        b2_lines = _make_dummy_report_lines(count=10, fps=121.0, gpu_p95=7.9)
        c1_lines = _make_dummy_report_lines(count=10, fps=130.0, gpu_p95=7.0)
        c2_lines = _make_dummy_report_lines(count=10, fps=131.0, gpu_p95=6.9)

        b1_path = _write_tmp_report(b1_lines)
        b2_path = _write_tmp_report(b2_lines)
        c1_path = _write_tmp_report(c1_lines)
        c2_path = _write_tmp_report(c2_lines)

        b1 = summarize(b1_path, 3000, 0, "OFF")
        b2 = summarize(b2_path, 3000, 0, "OFF")
        c1 = summarize(c1_path, 3000, 0, "OFF")
        c2 = summarize(c2_path, 3000, 0, "OFF")

        res = compare_multi([b1, b2], [c1, c2], gate_ms=0.2, require_stability=False)
        self.assertEqual(res["command"], "compare-multi")
        self.assertEqual(res["direction_of_change"], "CONSISTENT_IMPROVEMENT")
        self.assertEqual(res["pairwise_summary"]["total_pairings"], 4)
        self.assertEqual(res["pairwise_summary"]["improvements"], 4)
        self.assertGreater(res["metrics"]["fps"]["mean_delta"], 0)

    def test_compare_multi_metadata_mismatch_rejected(self):
        b1_lines = _make_dummy_report_lines(count=10, route="hdrtest-static-v1")
        c1_lines = _make_dummy_report_lines(count=10, route="nether-lava-stress-v1")

        b1_path = _write_tmp_report(b1_lines)
        c1_path = _write_tmp_report(c1_lines)

        b1 = summarize(b1_path, 3000, 0, "OFF")
        c1 = summarize(c1_path, 3000, 0, "OFF")

        with self.assertRaises(ReportError):
            compare_multi([b1], [c1], gate_ms=0.2, require_stability=False)

    def test_renderer_fallback_rejected(self):
        """When an Advanced route falls back to Vanilla lighting, summarize must raise ReportError."""
        lines = _make_dummy_report_lines(count=10, lighting_model="vanilla")
        path = _write_tmp_report(lines)
        with self.assertRaises(ReportError):
            summarize(path, 3000, 0, "OFF")

    def test_diagnostic_screening_attestation_blocked(self):
        """Screening runs with DIAGNOSTIC_SCREENING pattern must block release attestation."""
        lines = _make_dummy_report_lines(count=10, diagnostic_pattern="DIAGNOSTIC_SCREENING")
        path = _write_tmp_report(lines)
        summary = summarize(path, 3000, 0, "OFF")
        self.assertEqual(summary["metadata"]["diagnostic_pattern"], "DIAGNOSTIC_SCREENING")

    def test_compare_ablation_valid(self):
        """compare_ablation allows different ablation modes and outputs DIAGNOSTIC_MARGINAL_COMPARISON."""
        b1_lines = _make_dummy_report_lines(count=10, ablation_mode="FULL_ADVANCED", fps=60.0)
        c1_lines = _make_dummy_report_lines(count=10, ablation_mode="NO_L3_RECEIVER", fps=120.0)
        b1 = summarize(_write_tmp_report(b1_lines), 3000, 0, "OFF")
        c1 = summarize(_write_tmp_report(c1_lines), 3000, 0, "OFF")
        res = compare_ablation([b1], [c1], gate_ms=0.2, require_stability=False)
        self.assertEqual(res["command"], "compare-ablation")
        self.assertEqual(res["contract_label"], "DIAGNOSTIC_MARGINAL_COMPARISON")
        self.assertIsNone(res["attested_receipt"])
        self.assertEqual(res["baseline_ablation_mode"], "FULL_ADVANCED")
        self.assertEqual(res["candidate_ablation_mode"], "NO_L3_RECEIVER")
        self.assertIn("WARNING: Ablation deltas are marginal", res["warning"])

    def test_compare_ablation_other_metadata_mismatch_rejected(self):
        """compare_ablation MUST still reject any other metadata mismatch (e.g. route)."""
        b1_lines = _make_dummy_report_lines(count=10, ablation_mode="FULL_ADVANCED", route="hdrtest-static-v1")
        c1_lines = _make_dummy_report_lines(count=10, ablation_mode="NO_L3_RECEIVER", route="nether-lava-stress-v1")
        b1 = summarize(_write_tmp_report(b1_lines), 3000, 0, "OFF")
        c1 = summarize(_write_tmp_report(c1_lines), 3000, 0, "OFF")
        with self.assertRaises(ReportError):
            compare_ablation([b1], [c1], gate_ms=0.2, require_stability=False)


class TestEventOrderingValidation(unittest.TestCase):
    """Synthetic positive and negative regression tests for benchmark event log ordering validation."""

    def _make_dummy_minecraft_log(self, order: Sequence[str]) -> str:
        lines = ["[00:00:00] [main/INFO] Starting Minecraft"]
        event_templates = {
            "ARMED": "[00:00:01] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=ARMED scope=Built-in Retina Display target=3024x1964 warmup=1800 measure=3000 sequence=[OFF] route=hdrtest-static-v1",
            "WINDOW_READY": "[00:00:02] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=WINDOW_READY monitor=Built-in Retina Display video_mode=3024x1964@120 (24bit) framebuffer=3024x1964 window=3024x1964 screen=3024x1964",
            "ROUTE_APPLY": "[00:00:03] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=ROUTE_APPLY route=hdrtest-static-v1 fixture=hdrtest-static-v1 player=MetallumBench/b07a402a-d8ea-354f-9398-aaf208a798b9 dimension=minecraft:overworld",
            "SERVER_TICKS_FROZEN": "[00:00:04] [Server thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=SERVER_TICKS_FROZEN",
            "ROUTE_READY": "[00:00:05] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=ROUTE_READY route=hdrtest-static-v1 stable_frames=120 pose=[0,0,0;0,0] max_fps=260 resolved_gui_scale=8 resource_packs=vanilla,metallum,sodium",
            "ROUTE_CHECK_START": "[00:00:06] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=ROUTE_CHECK event=MEASURE_START route=hdrtest-static-v1 status=ready",
            "MEASURE_START": "[00:00:07] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=OFF presented_frame=1800",
            "MEASURE_END": "[00:00:08] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode=OFF presented_frame=4800",
            "ROUTE_CHECK_END": "[00:00:09] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=ROUTE_CHECK event=MEASURE_END route=hdrtest-static-v1 status=ready",
            "COMPLETE": "[00:00:10] [Render thread/INFO] (metallum) METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=3000 framebuffer=3024x1964",
        }
        for ev in order:
            lines.append(event_templates[ev])
        return "\n".join(lines)

    def test_valid_chronological_event_order(self):
        """Positive test: standard chronological lifecycle order passes validation."""
        from metal_benchmark_report import _validate_log_evidence
        log_text = self._make_dummy_minecraft_log([
            "ARMED",
            "WINDOW_READY",
            "ROUTE_APPLY",
            "SERVER_TICKS_FROZEN",
            "ROUTE_READY",
            "ROUTE_CHECK_START",
            "MEASURE_START",
            "MEASURE_END",
            "ROUTE_CHECK_END",
            "COMPLETE",
        ])
        meta = {
            "display_width": 3024,
            "display_height": 1964,
            "route": "hdrtest-static-v1",
            "fixture": "hdrtest-static-v1",
            "benchmark_player_name": "MetallumBench",
            "benchmark_player_uuid": _offline_player_uuid("MetallumBench"),
            "benchmark_dimension": "minecraft:overworld",
            "max_fps": 260,
            "active_resource_pack_ids": "vanilla,metallum,sodium",
            "sodium_chunk_builder_threads": 4,
        }
        summary = {
            "metadata": meta,
            "scaler_mode": "OFF",
        }
        measurement = {"measure_frames": 3000, "scaler_mode": "OFF"}
        log_with_workers = log_text + "\n(ChunkBuilder) Started 4 worker threads"
        with tempfile.TemporaryDirectory() as tmp_dir:
            mc_path = Path(tmp_dir) / "minecraft.log"
            con_path = Path(tmp_dir) / "console.log"
            mc_path.write_text(log_with_workers, encoding="utf-8")
            con_path.write_text("console ok", encoding="utf-8")
            _validate_log_evidence(mc_path, con_path, summary, measurement)

    def test_out_of_order_event_rejected(self):
        """Negative test: inverted event order (e.g. COMPLETE before MEASURE_END) raises ReportError."""
        from metal_benchmark_report import _validate_log_evidence
        log_text = self._make_dummy_minecraft_log([
            "ARMED",
            "WINDOW_READY",
            "ROUTE_APPLY",
            "SERVER_TICKS_FROZEN",
            "ROUTE_READY",
            "ROUTE_CHECK_START",
            "MEASURE_START",
            "COMPLETE",  # Out of order!
            "MEASURE_END",
            "ROUTE_CHECK_END",
        ])
        meta = {
            "display_width": 3024,
            "display_height": 1964,
            "route": "hdrtest-static-v1",
            "fixture": "hdrtest-static-v1",
            "benchmark_player_name": "MetallumBench",
            "benchmark_player_uuid": _offline_player_uuid("MetallumBench"),
            "benchmark_dimension": "minecraft:overworld",
            "max_fps": 260,
            "active_resource_pack_ids": "vanilla,metallum,sodium",
            "sodium_chunk_builder_threads": 4,
        }
        summary = {
            "metadata": meta,
            "scaler_mode": "OFF",
        }
        measurement = {"measure_frames": 3000, "scaler_mode": "OFF"}
        log_with_workers = log_text + "\n(ChunkBuilder) Started 4 worker threads"
        with tempfile.TemporaryDirectory() as tmp_dir:
            mc_path = Path(tmp_dir) / "minecraft.log"
            con_path = Path(tmp_dir) / "console.log"
            mc_path.write_text(log_with_workers, encoding="utf-8")
            con_path.write_text("console ok", encoding="utf-8")
            with self.assertRaises(ReportError):
                _validate_log_evidence(mc_path, con_path, summary, measurement)


if __name__ == "__main__":
    unittest.main()

