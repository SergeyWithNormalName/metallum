#!/usr/bin/env python3
"""
Orchestration script for Phase 2D.1-S Cross-Pipeline Static-Light Isolation Sanity Check.
Executes 3 paired real Minecraft benchmarks (B1 -> C1 -> B2 -> C2 -> B3 -> C3) on nether-lava-stress-v1 with VOLUMETRICS 100% OFF:
- Mode B_L3 (L3_NO_NON_LAVA_STATIC + METALLUM_BENCHMARK_VOLUMETRICS_DISABLED=1): Exact 2048-light batch uploaded, cluster refs built (~354k refs), direct shading skipped in fragment shader, Volumetrics OFF.
- Mode C_L3 (STATIC_STRUCTURALLY_BYPASSED + METALLUM_BENCHMARK_VOLUMETRICS_DISABLED=1): Exact SAME 2048-light batch uploaded (zero CPU refill, identical light IDs/hash), GPU cluster build bypasses static non-lava lights (0 static cluster refs, ~12.4k total refs), Volumetrics OFF.

Saves results to tools/phase2d1s_study_results.json.
"""

import os
import sys
import glob
import json
import time
import subprocess
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
LOG_DIR = ROOT / "run/logs/metallum-benchmarks"

def safe_float(val):
    if isinstance(val, (int, float)):
        return float(val)
    return 0.0

def run_benchmark(label, mode_name, volumetrics_disabled=True):
    print(f"\n=======================================================")
    print(f"LAUNCHING PHASE 2D.1-S BENCHMARK: {label} (mode={mode_name}, vol_disabled={volumetrics_disabled})")
    print(f"=======================================================")
    
    env = os.environ.copy()
    env["METALLUM_BENCHMARK_ADVANCED_ABLATION"] = mode_name
    if volumetrics_disabled:
        env["METALLUM_BENCHMARK_VOLUMETRICS_DISABLED"] = "1"
    
    cmd = [
        "./scripts/run_metal_benchmark.sh",
        "--apply-settings",
        "--route", "benchmark/routes/nether-lava-stress-v1.json",
        "--label", label
    ]
    
    start_time = time.time()
    res = subprocess.run(cmd, cwd=ROOT, env=env, capture_output=True, text=True)
    elapsed = time.time() - start_time
    print(f"Run completed in {elapsed:.1f}s (returncode: {res.returncode})")
    
    raw_files = sorted(glob.glob(str(LOG_DIR / f"*{label}*.raw.jsonl")))
    if not raw_files:
        all_raw = sorted(glob.glob(str(LOG_DIR / "*.raw.jsonl")), key=os.path.getmtime)
        if all_raw and os.path.getmtime(all_raw[-1]) >= start_time - 10:
            raw_files = [all_raw[-1]]
            
    if not raw_files:
        print(f"ERROR: No raw.jsonl file found for label {label}")
        return None
        
    latest_file = raw_files[-1]
    print(f"Found report: {Path(latest_file).name}")
    return latest_file

def parse_report(filepath):
    with open(filepath, "r", encoding="utf-8") as f:
        lines = [line.strip() for line in f if line.strip()]
    if not lines:
        return None
        
    data_lines = []
    for line in lines:
        try:
            data_lines.append(json.loads(line))
        except Exception:
            pass
    if not data_lines:
        return None
        
    last = data_lines[-1]
    meta = last.get("metadata", {})
    cl = last.get("clustered_lighting", {}) or {}
    
    gpu = last.get("presenting_command_buffer_gpu_ms", {})
    cpu = last.get("cpu_render_submission_ms", {})
    
    return {
        "filepath": filepath,
        "filename": Path(filepath).name,
        "ablation_mode": meta.get("advanced_ablation_mode", "ADVANCED_FULL"),
        "fps": safe_float(last.get("fps")),
        "gpu_p50": safe_float(gpu.get("p50")) if isinstance(gpu, dict) else 0.0,
        "gpu_p95": safe_float(gpu.get("p95")) if isinstance(gpu, dict) else 0.0,
        "gpu_p99": safe_float(gpu.get("p99")) if isinstance(gpu, dict) else 0.0,
        "cpu_p95": safe_float(cpu.get("p95")) if isinstance(cpu, dict) else 0.0,
        "light_count": cl.get("light_count", 0),
        "requested_indices": cl.get("cluster_requested_indices", 0),
        "accepted_indices": cl.get("cluster_accepted_indices", 0),
        "occ_p95": cl.get("cluster_occupancy_p95", 0),
        "occ_max": cl.get("cluster_occupancy_max", 0)
    }

def main():
    print("Starting Phase 2D.1-S Cross-Pipeline Isolation Sanity Check...")
    
    runs = [
        ("phase2d1s-b1-vol-off", "L3_NO_NON_LAVA_STATIC", True),
        ("phase2d1s-c1-vol-off", "STATIC_STRUCTURALLY_REMOVED", True),
        ("phase2d1s-b2-vol-off", "L3_NO_NON_LAVA_STATIC", True),
        ("phase2d1s-c2-vol-off", "STATIC_STRUCTURALLY_REMOVED", True),
        ("phase2d1s-b3-vol-off", "L3_NO_NON_LAVA_STATIC", True),
        ("phase2d1s-c3-vol-off", "STATIC_STRUCTURALLY_REMOVED", True),
    ]
    
    results = {}
    for label, mode, vol_off in runs:
        raw_path = run_benchmark(label, mode, vol_off)
        if raw_path:
            parsed = parse_report(raw_path)
            if parsed:
                results[label] = parsed
                
    out_json = ROOT / "tools/phase2d1s_study_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nPhase 2D.1-S Study complete! Results saved to {out_json}")

if __name__ == "__main__":
    main()
