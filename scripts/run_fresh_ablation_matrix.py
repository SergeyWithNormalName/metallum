#!/usr/bin/env python3
"""
Orchestration script for Phase 1B Fresh Controlled Ablation Study.
Launches real Minecraft benchmarks sequentially for paired categories (A, D, F, G),
verifies telemetry contracts in raw JSONL reports, and gathers empirical metrics.
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

def set_renderer_config(improved_lighting=True):
    path = ROOT / "run/config/metallum-renderer.properties"
    content = f"#Metallum renderer settings (schema 3)\nframeInterpolation=false\nimprovedLighting={str(improved_lighting).lower()}\nlightingPreset=balanced\nschemaVersion=3\nvoxelDebugChecksum=false\n"
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

def set_hdr_config(bloom_strength=0.18, mode="scene"):
    path = ROOT / "run/config/metallum-hdr.properties"
    content = f"#Metallum HDR settings\nbloomStrength={bloom_strength}\ndiagnosticPattern=false\nexperimentalFp16=false\nhdrStrength=1.0\nmode={mode}\nsourceEncoding=srgb\n"
    with open(path, "w", encoding="utf-8") as f:
        f.write(content)

def run_benchmark(label):
    print(f"\n=======================================================")
    print(f"LAUNCHING REAL MINECRAFT BENCHMARK: {label}")
    print(f"=======================================================")
    
    cmd = [
        "./scripts/run_metal_benchmark.sh",
        "--apply-settings",
        "--route", "benchmark/routes/nether-lava-stress-v1.json",
        "--label", label
    ]
    
    start_time = time.time()
    # Run process and wait for completion
    res = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    elapsed = time.time() - start_time
    print(f"Run completed in {elapsed:.1f}s (returncode: {res.returncode})")
    
    # Find matching raw jsonl
    raw_files = sorted(glob.glob(str(LOG_DIR / f"*{label}*.raw.jsonl")))
    if not raw_files:
        # Fallback to newest raw.jsonl created after start_time
        all_raw = sorted(glob.glob(str(LOG_DIR / "*.raw.jsonl")), key=os.path.getmtime)
        if all_raw and os.path.getmtime(all_raw[-1]) >= start_time - 10:
            raw_files = [all_raw[-1]]
            
    if not raw_files:
        print(f"ERROR: No raw.jsonl file found for label {label}")
        return None
        
    latest_file = raw_files[-1]
    print(f"Found report: {Path(latest_file).name}")
    return latest_file

def parse_fresh_report(filepath):
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
    vc = last.get("voxel_clipmaps", {}) or {}
    
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
        "dirty": meta.get("dirty_worktree", False),
        "fps": fps,
        "low_1": low_1,
        "low_01": low_01,
        "gpu_p50": safe_float(gpu.get("p50")) if isinstance(gpu, dict) else 0.0,
        "gpu_p95": safe_float(gpu.get("p95")) if isinstance(gpu, dict) else 0.0,
        "gpu_p99": safe_float(gpu.get("p99")) if isinstance(gpu, dict) else 0.0,
        "cpu_p50": safe_float(cpu.get("p50")) if isinstance(cpu, dict) else 0.0,
        "cpu_p95": safe_float(cpu.get("p95")) if isinstance(cpu, dict) else 0.0,
        "cpu_p99": safe_float(cpu.get("p99")) if isinstance(cpu, dict) else 0.0,
        "resolved_lighting_model": gen.get("resolved_lighting_model", "unknown"),
        "resolved_output_mode": gen.get("resolved_output_mode", "unknown"),
        "resolved_upscale_mode": gen.get("resolved_upscale_mode", "unknown"),
        "display_width": gen.get("display_width", meta.get("display_width")),
        "display_height": gen.get("display_height", meta.get("display_height")),
        "light_count": cl.get("light_count", 0),
        "requested_indices": cl.get("cluster_requested_indices", 0),
        "accepted_indices": cl.get("cluster_accepted_indices", 0),
        "occ_p95": cl.get("cluster_occupancy_p95", 0),
        "occ_max": cl.get("cluster_occupancy_max", 0),
        "stages": stages
    }

def main():
    print("Starting Fresh Controlled Ablation Matrix Collection (Phase 1B)...")
    
    # We run paired benchmarks:
    # A1 (Full Advanced) -> D1 (Vanilla L3 OFF) -> F1 (Bloom OFF) -> G1 (HDR OFF)
    # A2 (Full Advanced) -> D2 (Vanilla L3 OFF) -> F2 (Bloom OFF) -> G2 (HDR OFF)
    
    runs_spec = [
        # Pair 1
        ("fresh-phase1b-A1-full-advanced", True, 0.18, "scene"),
        ("fresh-phase1b-D1-l3-off-vanilla", False, 0.18, "scene"),
        ("fresh-phase1b-F1-bloom-off", True, 0.0, "scene"),
        ("fresh-phase1b-G1-hdr-off-sdr", True, 0.18, "sdr"),
        # Pair 2
        ("fresh-phase1b-A2-full-advanced", True, 0.18, "scene"),
        ("fresh-phase1b-D2-l3-off-vanilla", False, 0.18, "scene"),
        ("fresh-phase1b-F2-bloom-off", True, 0.0, "scene"),
        ("fresh-phase1b-G2-hdr-off-sdr", True, 0.18, "sdr"),
    ]
    
    results = {}
    
    for label, improved_lighting, bloom, mode in runs_spec:
        # Check if run already exists from earlier in session
        existing = glob.glob(str(LOG_DIR / f"*{label}*.raw.jsonl"))
        if existing:
            print(f"Found existing raw file for {label}: {existing[-1]}")
            raw_path = existing[-1]
        else:
            set_renderer_config(improved_lighting=improved_lighting)
            set_hdr_config(bloom_strength=bloom, mode=mode)
            raw_path = run_benchmark(label)
            
        if raw_path:
            parsed = parse_fresh_report(raw_path)
            if parsed:
                results[label] = parsed
                
    # Restore defaults
    set_renderer_config(improved_lighting=True)
    set_hdr_config(bloom_strength=0.18, mode="scene")
    
    # Save parsed fresh results
    out_json = ROOT / "tools/fresh_ablation_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nFresh ablation matrix collection complete! Results saved to {out_json}")

if __name__ == "__main__":
    main()
