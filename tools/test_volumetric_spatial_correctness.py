#!/usr/bin/env python3
"""
Diagnostic test script for Phase 2B Volumetric Spatial Correctness & Perceptual Audit.
Launches real Minecraft benchmarks on the aperture god-ray world-space fixture routes:
- l6.7-god-rays-world-space-profile-v2.json (Open profile)
- l6.7-god-rays-world-space-front-v2.json (Open front)
- l6.7-god-rays-world-space-profile-sealed-v2.json (Sealed profile)
- l6.7-god-rays-world-space-profile-open-air-v2.json (Open air control)
Tests History ON vs History OFF to isolate temporal reprojection artifacts.
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

def run_fixture_benchmark(label, route_file, no_history=False):
    print(f"\n=======================================================")
    print(f"LAUNCHING SPATIAL FIXTURE BENCHMARK: {label}")
    print(f"Route: {route_file} (no_history={no_history})")
    print(f"=======================================================")
    
    env = os.environ.copy()
    if no_history:
        env["METALLUM_BENCHMARK_VOLUMETRIC_NO_HISTORY"] = "1"
    else:
        env.pop("METALLUM_BENCHMARK_VOLUMETRIC_NO_HISTORY", None)
        
    cmd = [
        "./scripts/run_metal_benchmark.sh",
        "--apply-settings",
        "--route", f"benchmark/routes/{route_file}",
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
        "fps": safe_float(last.get("fps")),
        "gpu_p50": safe_float(gpu.get("p50")) if isinstance(gpu, dict) else 0.0,
        "gpu_p95": safe_float(gpu.get("p95")) if isinstance(gpu, dict) else 0.0,
        "cpu_p95": safe_float(cpu.get("p95")) if isinstance(cpu, dict) else 0.0,
        "light_count": cl.get("light_count", 0),
        "requested_indices": cl.get("cluster_requested_indices", 0),
        "occ_p95": cl.get("cluster_occupancy_p95", 0),
        "occ_max": cl.get("cluster_occupancy_max", 0)
    }

def main():
    print("Starting Phase 2B Volumetric Spatial Correctness & Perceptual Audit...")
    
    runs = [
        ("phase2b-open-profile-hist-on", "l6.7-god-rays-world-space-profile-v2.json", False),
        ("phase2b-open-profile-hist-off", "l6.7-god-rays-world-space-profile-v2.json", True),
        ("phase2b-open-front-hist-on", "l6.7-god-rays-world-space-front-v2.json", False),
        ("phase2b-sealed-profile-hist-on", "l6.7-god-rays-world-space-profile-sealed-v2.json", False),
        ("phase2b-open-air-profile-hist-on", "l6.7-god-rays-world-space-profile-open-air-v2.json", False),
    ]
    
    results = {}
    for label, route, no_hist in runs:
        raw_path = run_fixture_benchmark(label, route, no_hist)
        if raw_path:
            parsed = parse_report(raw_path)
            if parsed:
                results[label] = parsed
                
    out_json = ROOT / "tools/phase2b_spatial_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nPhase 2B Spatial Audit complete! Results saved to {out_json}")

if __name__ == "__main__":
    main()
