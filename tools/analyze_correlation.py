#!/usr/bin/env python3
"""
Correlation analysis script for Metallum performance telemetry.
Calculates Pearson correlation between frame cost (GPU/Present max time)
and key workload metrics across all raw JSONL benchmark runs.
"""

import glob
import json
import math
from pathlib import Path

def safe_float(val):
    if isinstance(val, (int, float)):
        return float(val)
    return 0.0

def pearson_r(x_list, y_list):
    n = len(x_list)
    if n < 2:
        return 0.0
    mean_x = sum(x_list) / n
    mean_y = sum(y_list) / n
    cov = sum((x - mean_x) * (y - mean_y) for x, y in zip(x_list, y_list))
    var_x = sum((x - mean_x) ** 2 for x in x_list)
    var_y = sum((y - mean_y) ** 2 for y in y_list)
    if var_x <= 0 or var_y <= 0:
        return 0.0
    return cov / math.sqrt(var_x * var_y)

def run_correlation():
    reports = glob.glob("run/logs/metallum-benchmarks/*.raw.jsonl")
    print(f"Analyzing {len(reports)} raw JSONL reports for correlation analysis...")

    dataset = []

    for p in reports:
        with open(p, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    d = json.loads(line)
                except Exception:
                    continue

                gpu_data = d.get("presenting_command_buffer_gpu_ms", {})
                gpu_max = safe_float(gpu_data.get("maximum") if isinstance(gpu_data, dict) else 0)
                gpu_p95 = safe_float(gpu_data.get("p95") if isinstance(gpu_data, dict) else 0)
                
                pres_data = d.get("present_interval_ms", {})
                pres_max = safe_float(pres_data.get("maximum") if isinstance(pres_data, dict) else 0)
                
                cpu_data = d.get("cpu_render_submission_ms", {})
                cpu_max = safe_float(cpu_data.get("maximum") if isinstance(cpu_data, dict) else 0)

                frame_cost = max(gpu_max, pres_max, cpu_max)
                
                # Metrics
                cl = d.get("clustered_lighting", {}) or {}
                vc = d.get("voxel_clipmaps", {}) or {}
                wl = d.get("workload", {}) or {}
                copy_b = wl.get("copy_bytes", {}) or {} if isinstance(wl, dict) else {}
                trans_m = wl.get("transient_memory", {}) or {} if isinstance(wl, dict) else {}
                
                light_count = safe_float(cl.get("light_count"))
                cluster_requested_idx = safe_float(cl.get("cluster_requested_indices"))
                cluster_accepted_idx = safe_float(cl.get("cluster_accepted_indices"))
                cluster_occ_p95 = safe_float(cl.get("cluster_occupancy_p95"))
                cluster_occ_max = safe_float(cl.get("cluster_occupancy_max"))
                cluster_overflow = safe_float(cl.get("cluster_overflow_clusters"))
                
                dirty_submitted = safe_float(vc.get("dirty_bricks_submitted"))
                dirty_completed = safe_float(vc.get("dirty_bricks_completed"))
                heap_used = safe_float(vc.get("heap_used_bytes"))
                
                cpu_to_shared_mb = safe_float(copy_b.get("cpu_to_shared")) / (1024.0 * 1024.0)
                shared_to_private_mb = safe_float(copy_b.get("shared_to_private")) / (1024.0 * 1024.0)
                gpu_internal_mb = safe_float(copy_b.get("gpu_internal")) / (1024.0 * 1024.0)
                
                dataset.append({
                    "frame_cost": frame_cost,
                    "gpu_p95": gpu_p95,
                    "light_count": light_count,
                    "cluster_requested_idx": cluster_requested_idx,
                    "cluster_accepted_idx": cluster_accepted_idx,
                    "cluster_occ_p95": cluster_occ_p95,
                    "cluster_occ_max": cluster_occ_max,
                    "cluster_overflow": cluster_overflow,
                    "dirty_submitted": dirty_submitted,
                    "dirty_completed": dirty_completed,
                    "heap_used": heap_used,
                    "cpu_to_shared_mb": cpu_to_shared_mb,
                    "shared_to_private_mb": shared_to_private_mb,
                    "gpu_internal_mb": gpu_internal_mb,
                })

    print(f"Total dataset samples: {len(dataset)}")
    if not dataset:
        return

    frame_costs = [s["frame_cost"] for s in dataset]
    gpu_p95s = [s["gpu_p95"] for s in dataset]

    metrics = [
        ("light_count", "Visible Light Count"),
        ("cluster_requested_idx", "Cluster Requested Index Count"),
        ("cluster_accepted_idx", "Cluster Accepted Index Count"),
        ("cluster_occ_p95", "Cluster Occupancy p95"),
        ("cluster_occ_max", "Cluster Occupancy Max"),
        ("cluster_overflow", "Cluster Overflow Clusters"),
        ("dirty_submitted", "L5 Brick Invalidations / Submitted"),
        ("dirty_completed", "L5 Brick Completed"),
        ("heap_used", "L5 Voxel Heap Used Bytes"),
        ("cpu_to_shared_mb", "CPU-to-Shared Allocation MB"),
        ("shared_to_private_mb", "Shared-to-Private Copy MB"),
        ("gpu_internal_mb", "GPU Internal Copy MB"),
    ]

    print("\n--- CORRELATION WITH MAX FRAME COST (ms) ---")
    results = []
    for key, name in metrics:
        vals = [s[key] for s in dataset]
        r_cost = pearson_r(vals, frame_costs)
        r_gpu = pearson_r(vals, gpu_p95s)
        results.append((abs(r_cost), name, r_cost, r_gpu))

    results.sort(reverse=True)
    for _, name, r_cost, r_gpu in results:
        print(f"  {name:38s}: r(Max Frame Cost) = {r_cost:+.4f} | r(GPU p95) = {r_gpu:+.4f}")

if __name__ == "__main__":
    run_correlation()
