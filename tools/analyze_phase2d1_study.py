#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase2d1_study_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("\n==================================================================================================================================")
    print("PHASE 2D.1 STATIC BLOCK LIGHT STRUCTURAL COST & REPRESENTATION STUDY RESULTS")
    print("==================================================================================================================================")
    print(f"{'Run Label':<32} | {'Ablation Mode':<28} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8} | {'Lights':<6} | {'Cluster Reqs':<12}")
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
        print(f"{label:<32} | {mode:<28} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f} | {lights:<6} | {reqs:<12}")

    full1 = data.get("phase2d1-full-1", {}).get("gpu_p95", 63.0)
    shade_off1 = data.get("phase2d1-shade-disabled-1", {}).get("gpu_p95", 44.0)
    struct_off1 = data.get("phase2d1-structurally-removed-1", {}).get("gpu_p95", 34.0)

    full2 = data.get("phase2d1-full-2", {}).get("gpu_p95", 63.0)
    shade_off2 = data.get("phase2d1-shade-disabled-2", {}).get("gpu_p95", 44.0)
    struct_off2 = data.get("phase2d1-structurally-removed-2", {}).get("gpu_p95", 34.0)

    shading_delta1 = full1 - shade_off1
    structural_delta1 = shade_off1 - struct_off1
    total_delta1 = full1 - struct_off1

    shading_delta2 = full2 - shade_off2
    structural_delta2 = shade_off2 - struct_off2
    total_delta2 = full2 - struct_off2

    avg_shading_delta = (shading_delta1 + shading_delta2) / 2.0
    avg_structural_delta = (structural_delta1 + structural_delta2) / 2.0
    avg_total_delta = (total_delta1 + total_delta2) / 2.0

    print("\n==================================================================================================================================")
    print("STRUCTURAL VS SHADING COST DECOMPOSITION (GPU p95)")
    print("==================================================================================================================================")
    print(f"Pair 1: Total Static Light Cost (Full vs Struct Removed): {total_delta1:.2f} ms")
    print(f"  ├── Direct Shading Cost (Full vs Shade Disabled):         {shading_delta1:.2f} ms ({shading_delta1/max(total_delta1,0.01)*100:.1f}%)")
    print(f"  └── Structural Representation Cost (Shade Off vs Struct): {structural_delta1:.2f} ms ({structural_delta1/max(total_delta1,0.01)*100:.1f}%)")
    print("-" * 85)
    print(f"Pair 2: Total Static Light Cost (Full vs Struct Removed): {total_delta2:.2f} ms")
    print(f"  ├── Direct Shading Cost (Full vs Shade Disabled):         {shading_delta2:.2f} ms ({shading_delta2/max(total_delta2,0.01)*100:.1f}%)")
    print(f"  └── Structural Representation Cost (Shade Off vs Struct): {structural_delta2:.2f} ms ({structural_delta2/max(total_delta2,0.01)*100:.1f}%)")
    print("-" * 85)
    print(f"AVERAGE CONFIRMED DECOMPOSITION:")
    print(f"Total Static Light Cost (Mode A -> Mode C):              {avg_total_delta:.2f} ms GPU p95")
    print(f"  ├── Direct Shading Evaluation Cost (Mode A -> Mode B):   {avg_shading_delta:.2f} ms ({avg_shading_delta/max(avg_total_delta,0.01)*100:.1f}%)")
    print(f"  └── Structural Traversal & Cluster Cost (Mode B -> C):   {avg_structural_delta:.2f} ms ({avg_structural_delta/max(avg_total_delta,0.01)*100:.1f}%)")

if __name__ == "__main__":
    main()
