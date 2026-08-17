#!/usr/bin/env python3
"""
Orchestration script for Phase 2A Volumetric Cost Decomposition & Architecture Study.
Executes real Minecraft benchmarks for:
1. Pass-Level GPU Timing Breakdown (with METALLUM_GPU_TIMING=1 METALLUM_GPU_TIMING_DETAIL=1)
2. Froxel Grid Scaling Test (100%, 75%, 50%)
3. Light-Count Scaling Test (2048, 1024, 512)
Saves results to tools/phase2a_study_results.json.
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

def set_renderer_config():
    path = ROOT / "run/config/metallum-renderer.properties"
    content = " #Metallum renderer settings (schema 3)\nframeInterpolation=false\nimprovedLighting=true\nlightingPreset=balanced\nschemaVersion=3\nvoxelDebugChecksum=false\n"
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

def set_hdr_config():
    path = ROOT / "run/config/metallum-hdr.properties"
    content = " #Metallum HDR settings\nbloomStrength=0.18\ndiagnosticPattern=false\nexperimentalFp16=false\nhdrStrength=1.0\nmode=scene\nsourceEncoding=srgb\n"
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

def run_benchmark(label, froxel_scale="1.0", light_cap="2048", detail_timing=True):
    print(f"\n=======================================================")
    print(f"LAUNCHING PHASE 2A BENCHMARK: {label} (scale={froxel_scale}, cap={light_cap})")
    print(f"=======================================================")
    
    env = os.environ.copy()
    env["METALLUM_BENCHMARK_FROXEL_SCALE"] = froxel_scale
    env["METALLUM_BENCHMARK_VOLUMETRIC_LIGHT_CAP"] = light_cap
    if detail_timing:
        env["METALLUM_GPU_TIMING"] = "1"
        env["METALLUM_GPU_TIMING_DETAIL"] = "1"
    
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
    stages = last.get("stages", {})
    cl = last.get("clustered_lighting", {}) or {}
    
    fps = safe_float(last.get("fps"))
    low_1 = safe_float(last.get("fps_1_percent_low"))
    low_01 = safe_float(last.get("fps_0_1_percent_low"))
    
    gpu = last.get("presenting_command_buffer_gpu_ms", {})
    cpu = last.get("cpu_render_submission_ms", {})
    
    return {
        "filepath": filepath,
        "filename": Path(filepath).name,
        "artifact_sha256": meta.get("artifact_sha256", "unknown"),
        "commit": meta.get("commit", "unknown"),
        "froxel_scale": meta.get("froxel_scale", "1.0"),
        "volumetric_light_cap": meta.get("volumetric_light_cap", "default"),
        "fps": fps,
        "low_1": low_1,
        "low_01": low_01,
        "gpu_p50": safe_float(gpu.get("p50")) if isinstance(gpu, dict) else 0.0,
        "gpu_p95": safe_float(gpu.get("p95")) if isinstance(gpu, dict) else 0.0,
        "gpu_p99": safe_float(gpu.get("p99")) if isinstance(gpu, dict) else 0.0,
        "gpu_max": safe_float(gpu.get("maximum")) if isinstance(gpu, dict) else 0.0,
        "cpu_p50": safe_float(cpu.get("p50")) if isinstance(cpu, dict) else 0.0,
        "cpu_p95": safe_float(cpu.get("p95")) if isinstance(cpu, dict) else 0.0,
        "cpu_p99": safe_float(cpu.get("p99")) if isinstance(cpu, dict) else 0.0,
        "light_count": cl.get("light_count", 0),
        "requested_indices": cl.get("cluster_requested_indices", 0),
        "accepted_indices": cl.get("cluster_accepted_indices", 0),
        "occ_p95": cl.get("cluster_occupancy_p95", 0),
        "occ_max": cl.get("cluster_occupancy_max", 0),
        "stages": stages
    }

def main():
    print("Starting Phase 2A Volumetric Cost Decomposition Study...")
    
    set_renderer_config()
    set_hdr_config()
    
    runs_spec = [
        # Froxel Grid Scaling Runs
        ("phase2a-grid-100", "1.0", "2048"),
        ("phase2a-grid-75", "0.75", "2048"),
        ("phase2a-grid-50", "0.50", "2048"),
        
        # Light Count Scaling Runs
        ("phase2a-lights-2048", "1.0", "2048"),
        ("phase2a-lights-1024", "1.0", "1024"),
        ("phase2a-lights-512", "1.0", "512"),
    ]
    
    results = {}
    
    for label, froxel_scale, light_cap in runs_spec:
        raw_path = run_benchmark(label, froxel_scale, light_cap, detail_timing=True)
        if raw_path:
            parsed = parse_report(raw_path)
            if parsed:
                results[label] = parsed
                
    out_json = ROOT / "tools/phase2a_study_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nPhase 2A Volumetric Study complete! Results saved to {out_json}")

if __name__ == "__main__":
    main()
