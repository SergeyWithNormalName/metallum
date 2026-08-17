#!/usr/bin/env python3
import json
import glob
from pathlib import Path

LOG_DIR = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum/run/logs/metallum-benchmarks")

def analyze_stages(pattern, label):
    files = sorted(glob.glob(str(LOG_DIR / f"*{pattern}*.raw.jsonl")))
    if not files:
        print(f"No files for {label}")
        return
    
    filepath = files[-1]
    with open(filepath, "r", encoding="utf-8") as f:
        lines = [l.strip() for l in f if l.strip()]
    if not lines:
        return
        
    last = json.loads(lines[-1])
    meta = last.get("metadata", {})
    gpu = last.get("presenting_command_buffer_gpu_ms", {})
    stages = last.get("stages", {})
    
    print(f"\n=======================================================")
    print(f"REPORT: {label} ({Path(filepath).name})")
    print(f"Ablation Mode: {meta.get('advanced_ablation_mode', 'unknown')}")
    print(f"Total GPU p95: {gpu.get('p95', 0):.2f} ms")
    print("Stage GPU Timings Breakdown (p50 / p95 / max):")
    
    for st_name, st_val in sorted(stages.items()):
        if isinstance(st_val, dict):
            p50 = st_val.get("p50", 0.0)
            p95 = st_val.get("p95", 0.0)
            p99 = st_val.get("p99", 0.0)
            mx = st_val.get("maximum", 0.0)
            print(f"  - {st_name:<32}: p50={p50:6.2f}ms | p95={p95:6.2f}ms | max={mx:6.2f}ms")

def main():
    analyze_stages("phase1c-A1-full-advanced", "Mode A (FULL ADVANCED)")
    analyze_stages("phase1c-C1-no-volumetrics", "Mode C (NO VOLUMETRICS)")

if __name__ == "__main__":
    main()
