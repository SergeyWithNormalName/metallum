#!/usr/bin/env python3
"""
Analyzer for Bad Frames (>25ms, >33.3ms, >50ms) from Metallum raw JSONL reports.
"""

import sys
import json
import math
from pathlib import Path

def analyze_raw_jsonl(filepath, threshold_ms=25.0):
    path = Path(filepath)
    if not path.exists():
        print(f"File not found: {filepath}")
        return

    print(f"=== BAD FRAME ANALYSIS FOR: {path.name} (Threshold: >{threshold_ms}ms) ===")
    
    total_windows = 0
    bad_25_count = 0
    bad_33_count = 0
    bad_50_count = 0

    bad_windows = []

    with open(path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                data = json.loads(line)
            except Exception as e:
                continue

            total_windows += 1
            
            gpu_data = data.get("presenting_command_buffer_gpu_ms", {})
            gpu_max = gpu_data.get("maximum", 0.0)
            gpu_p95 = gpu_data.get("p95", 0.0)
            
            present_data = data.get("present_interval_ms", {})
            present_max = present_data.get("maximum", 0.0)
            
            cpu_data = data.get("cpu_render_submission_ms", {})
            cpu_max = cpu_data.get("maximum", 0.0)
            
            max_frame_cost = max(gpu_max, present_max, cpu_max)
            
            if max_frame_cost > 25.0:
                bad_25_count += 1
            if max_frame_cost > 33.33:
                bad_33_count += 1
            if max_frame_cost > 50.0:
                bad_50_count += 1

            if max_frame_cost >= threshold_ms:
                bad_windows.append((line_num, data, max_frame_cost, gpu_max, present_max, cpu_max))

    print(f"Total Windows: {total_windows}")
    print(f"Windows with Max > 25.0ms: {bad_25_count} ({bad_25_count/max(1,total_windows)*100:.1f}%)")
    print(f"Windows with Max > 33.3ms: {bad_33_count} ({bad_33_count/max(1,total_windows)*100:.1f}%)")
    print(f"Windows with Max > 50.0ms: {bad_50_count} ({bad_50_count/max(1,total_windows)*100:.1f}%)")
    print("")

    for line_num, data, max_cost, gpu_max, present_max, cpu_max in bad_windows[:10]:
        print(f"--- [Line {line_num}] Max Frame Cost: {max_cost:.2f} ms (GPU Max: {gpu_max:.2f} ms, Present Max: {present_max:.2f} ms, CPU Max: {cpu_max:.2f} ms) ---")
        
        # Clustered lighting info
        cl = data.get("clustered_lighting", {})
        if cl:
            print(f"  Clustered Lighting: active={cl.get('active')}, lights={cl.get('light_count')}, requested_idx={cl.get('cluster_requested_indices')}, accepted_idx={cl.get('cluster_accepted_indices')}, occ_p95={cl.get('cluster_occupancy_p95')}, occ_max={cl.get('cluster_occupancy_max')}")
            
        # Voxel clipmaps info
        vc = data.get("voxel_clipmaps", {})
        if vc:
            print(f"  Voxel Clipmaps: active={vc.get('active')}, dirty_submitted={vc.get('dirty_bricks_submitted')}, dirty_completed={vc.get('dirty_bricks_completed')}, heap_used={vc.get('heap_used_bytes', 0)/(1024*1024):.2f} MB")
            
        # Workload & memory allocations
        wl = data.get("workload", {})
        if wl:
            copy_b = wl.get("copy_bytes", {})
            print(f"  Workload Copies: cpu_to_shared={copy_b.get('cpu_to_shared', 0)/(1024*1024):.2f} MB, shared_to_private={copy_b.get('shared_to_private', 0)/(1024*1024):.2f} MB, gpu_internal={copy_b.get('gpu_internal', 0)/(1024*1024):.2f} MB")
            
        # GPU timing stages
        stages = data.get("stages", {})
        if stages:
            print("  GPU Stages (Max / Avg ms):")
            for stage_name, stage_info in stages.items():
                if stage_info and isinstance(stage_info, dict):
                    avg_ms = stage_info.get("average_ms", 0.0)
                    max_ms = stage_info.get("maximum_ms", 0.0)
                    if max_ms > 1.0 or avg_ms > 0.5:
                        print(f"    - {stage_name}: max={max_ms:.2f} ms, avg={avg_ms:.2f} ms")
        print("")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 analyze_bad_frames.py <path_to_raw.jsonl> [threshold_ms]")
        sys.exit(1)
    thresh = float(sys.argv[2]) if len(sys.argv) > 2 else 25.0
    analyze_raw_jsonl(sys.argv[1], thresh)
