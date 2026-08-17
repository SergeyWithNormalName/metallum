#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase2b_spatial_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("\n==========================================================================================================")
    print("PHASE 2B VOLUMETRIC SPATIAL CORRECTNESS AUDIT RESULTS SUMMARY")
    print("==========================================================================================================")
    print(f"{'Run Label':<35} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'CPU p95':<8} | {'Light Count':<12}")
    print("-" * 95)

    for label, r in data.items():
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        lights = r.get("light_count", 0)
        print(f"{label:<35} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {cpu_p95:<8.2f} | {lights:<12}")

if __name__ == "__main__":
    main()
