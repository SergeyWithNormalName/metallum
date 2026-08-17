#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase2d1r_study_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("\n==================================================================================================================================")
    print("PHASE 2D.1-R FROZEN SURVIVOR SET STRUCTURAL COST STUDY RESULTS")
    print("==================================================================================================================================")
    print(f"{'Run Label':<20} | {'Ablation Mode':<32} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8} | {'Lights':<6} | {'Cluster Reqs':<12}")
    print("-" * 135)

    for label, r in data.items():
        mode = r.get("ablation_mode", "ADVANCED_FULL")
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        gpu_p99 = r.get("gpu_p99", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        lights = r.get("light_count", 0)
        reqs = r.get("requested_indices", 0)
        print(f"{label:<20} | {mode:<32} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f} | {lights:<6} | {reqs:<12}")

    b1 = data.get("phase2d1r-b1", {}).get("gpu_p95", 44.0)
    c1 = data.get("phase2d1r-c1", {}).get("gpu_p95", 44.0)

    b2 = data.get("phase2d1r-b2", {}).get("gpu_p95", 44.0)
    c2 = data.get("phase2d1r-c2", {}).get("gpu_p95", 44.0)

    b3 = data.get("phase2d1r-b3", {}).get("gpu_p95", 44.0)
    c3 = data.get("phase2d1r-c3", {}).get("gpu_p95", 44.0)

    delta1 = b1 - c1
    delta2 = b2 - c2
    delta3 = b3 - c3
    avg_delta = (delta1 + delta2 + delta3) / 3.0

    print("\n==================================================================================================================================")
    print("PURE STRUCTURAL COST ISOLATION (Mode B vs Mode C)")
    print("==================================================================================================================================")
    print(f"Pair 1 Structural Cost Delta (Mode B - Mode C): {delta1:>6.2f} ms GPU p95 (Mode B: {b1:.2f} ms, Mode C: {c1:.2f} ms)")
    print(f"Pair 2 Structural Cost Delta (Mode B - Mode C): {delta2:>6.2f} ms GPU p95 (Mode B: {b2:.2f} ms, Mode C: {c2:.2f} ms)")
    print(f"Pair 3 Structural Cost Delta (Mode B - Mode C): {delta3:>6.2f} ms GPU p95 (Mode B: {b3:.2f} ms, Mode C: {c3:.2f} ms)")
    print("-" * 85)
    print(f"AVERAGE CONFIRMED PURE STRUCTURAL COST:         {avg_delta:>6.2f} ms GPU p95")

if __name__ == "__main__":
    main()
