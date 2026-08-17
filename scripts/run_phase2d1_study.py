#!/usr/bin/env python3
"""
Orchestration script for Phase 2D.1-R Static Light Isolation Validation.
Executes 3 paired real Minecraft benchmarks (B1 -> C1 -> B2 -> C2 -> B3 -> C3) on nether-lava-stress-v1:
- Mode B (L3_NO_NON_LAVA_STATIC): Exact 2048-light batch uploaded, cluster refs built (~354k refs), direct shading skipped in fragment shader.
- Mode C (STATIC_STRUCTURALLY_BYPASSED_NO_REFILL): Exact SAME 2048-light batch uploaded (zero CPU refill, identical light IDs/hash), GPU cluster build bypasses static non-lava lights (0 static cluster refs, ~12.4k total refs).

Saves results to tools/phase2d1r_study_results.json.
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

def run_benchmark(label, mode_name):
    print(f"\n=======================================================")
    print(f"LAUNCHING PHASE 2D.1-R BENCHMARK: {label} (mode={mode_name})")
    print(f"=======================================================")
    
    env = os.environ.copy()
    env["METALLUM_BENCHMARK_ADVANCED_ABLATION"] = mode_name
    
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
    print("Starting Phase 2D.1-R Frozen Survivor Set Structural Cost Study...")
    
    runs = [
        ("phase2d1r-b1", "L3_NO_NON_LAVA_STATIC"),
        ("phase2d1r-c1", "STATIC_STRUCTURALLY_REMOVED"),
        ("phase2d1r-b2", "L3_NO_NON_LAVA_STATIC"),
        ("phase2d1r-c2", "STATIC_STRUCTURALLY_REMOVED"),
        ("phase2d1r-b3", "L3_NO_NON_LAVA_STATIC"),
        ("phase2d1r-c3", "STATIC_STRUCTURALLY_REMOVED"),
    ]
    
    results = {}
    for label, mode in runs:
        raw_path = run_benchmark(label, mode)
        if raw_path:
            parsed = parse_report(raw_path)
            if parsed:
                results[label] = parsed
                
    out_json = ROOT / "tools/phase2d1r_study_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nPhase 2D.1-R Study complete! Results saved to {out_json}")

if __name__ == "__main__":
    main()
