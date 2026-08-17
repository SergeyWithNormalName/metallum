#!/usr/bin/env python3
"""
Orchestration script for Phase 2C L6 Local Shadow Cost Decomposition & Reuse Study.
Executes real Minecraft benchmarks on nether-lava-stress-v1 across L6 diagnostic ablation modes:
1. phase2c-full (ADVANCED_FULL)
2. phase2c-no-l6 (ADVANCED_NO_L6)
3. phase2c-descriptor-only (ADVANCED_L6_DESCRIPTOR_ONLY)
4. phase2c-1tap-no-pcf (ADVANCED_L6_1TAP_NO_PCF)
5. phase2c-no-dynamic-dda (ADVANCED_L6_DYNAMIC_DDA_DISABLED)

Saves results to tools/phase2c_study_results.json.
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
    print(f"LAUNCHING PHASE 2C L6 BENCHMARK: {label} (mode={mode_name})")
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
    gen = last.get("renderer_generation", {})
    cl = last.get("clustered_lighting", {}) or {}
    
    gpu = last.get("presenting_command_buffer_gpu_ms", {})
    cpu = last.get("cpu_render_submission_ms", {})
    
    return {
        "filepath": filepath,
        "filename": Path(filepath).name,
        "artifact_sha256": meta.get("artifact_sha256", "unknown"),
        "commit": meta.get("commit", "unknown"),
        "ablation_mode": meta.get("advanced_ablation_mode", "ADVANCED_FULL"),
        "fps": safe_float(last.get("fps")),
        "low_1": safe_float(last.get("fps_1_percent_low")),
        "low_01": safe_float(last.get("fps_0_1_percent_low")),
        "gpu_p50": safe_float(gpu.get("p50")) if isinstance(gpu, dict) else 0.0,
        "gpu_p95": safe_float(gpu.get("p95")) if isinstance(gpu, dict) else 0.0,
        "gpu_p99": safe_float(gpu.get("p99")) if isinstance(gpu, dict) else 0.0,
        "cpu_p50": safe_float(cpu.get("p50")) if isinstance(cpu, dict) else 0.0,
        "cpu_p95": safe_float(cpu.get("p95")) if isinstance(cpu, dict) else 0.0,
        "light_count": cl.get("light_count", 0),
        "requested_indices": cl.get("cluster_requested_indices", 0),
        "accepted_indices": cl.get("cluster_accepted_indices", 0),
        "occ_p95": cl.get("cluster_occupancy_p95", 0),
        "occ_max": cl.get("cluster_occupancy_max", 0)
    }

def main():
    print("Starting Phase 2C L6 Local Shadow Cost Decomposition Study...")
    
    runs = [
        ("phase2c-full", "ADVANCED_FULL"),
        ("phase2c-no-l6", "ADVANCED_NO_L6"),
        ("phase2c-descriptor-only", "ADVANCED_L6_DESCRIPTOR_ONLY"),
        ("phase2c-1tap-no-pcf", "ADVANCED_L6_1TAP_NO_PCF"),
        ("phase2c-no-dynamic-dda", "ADVANCED_L6_DYNAMIC_DDA_DISABLED"),
    ]
    
    results = {}
    for label, mode in runs:
        raw_path = run_benchmark(label, mode)
        if raw_path:
            parsed = parse_report(raw_path)
            if parsed:
                results[label] = parsed
                
    out_json = ROOT / "tools/phase2c_study_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nPhase 2C L6 Study complete! Results saved to {out_json}")

if __name__ == "__main__":
    main()
