#!/usr/bin/env python3
"""
Orchestration script for Phase 1C Component-Level Advanced Lighting Ablation Study.
Launches real Minecraft benchmarks sequentially for paired categories (A, B, C, D, E),
verifies telemetry contracts and advanced_ablation_mode in raw JSONL metadata, and gathers empirical metrics.
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

def run_benchmark(label, ablation_mode):
    print(f"\n=======================================================")
    print(f"LAUNCHING REAL MINECRAFT BENCHMARK: {label} (ablation={ablation_mode})")
    print(f"=======================================================")
    
    env = os.environ.copy()
    env["METALLUM_BENCHMARK_ADVANCED_ABLATION"] = ablation_mode
    
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
    
    # Find matching raw jsonl
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
        "advanced_ablation_mode": meta.get("advanced_ablation_mode", "unknown"),
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
    print("Starting Phase 1C Component-Level Advanced Lighting Ablation Study...")
    
    set_renderer_config()
    set_hdr_config()
    
    runs_spec = [
        # Pair 1
        ("phase1c-A1-full-advanced", "ADVANCED_FULL"),
        ("phase1c-B1-no-l6-shadows", "ADVANCED_NO_L6"),
        ("phase1c-C1-no-volumetrics", "ADVANCED_NO_VOLUMETRICS"),
        ("phase1c-D1-no-pbr-extras", "ADVANCED_NO_PBR_EXTRAS"),
        ("phase1c-E1-l3-shade-disabled", "ADVANCED_L3_SHADE_DISABLED"),
        # Pair 2
        ("phase1c-A2-full-advanced", "ADVANCED_FULL"),
        ("phase1c-B2-no-l6-shadows", "ADVANCED_NO_L6"),
        ("phase1c-C2-no-volumetrics", "ADVANCED_NO_VOLUMETRICS"),
        ("phase1c-D2-no-pbr-extras", "ADVANCED_NO_PBR_EXTRAS"),
        ("phase1c-E2-l3-shade-disabled", "ADVANCED_L3_SHADE_DISABLED"),
    ]
    
    results = {}
    
    for label, ablation_mode in runs_spec:
        raw_path = run_benchmark(label, ablation_mode)
        if raw_path:
            parsed = parse_fresh_report(raw_path)
            if parsed:
                results[label] = parsed
                
    out_json = ROOT / "tools/phase1c_ablation_results.json"
    with open(out_json, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    print(f"\nPhase 1C Component Ablation Matrix complete! Results saved to {out_json}")

if __name__ == "__main__":
    main()
