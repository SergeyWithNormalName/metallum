#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
R_RESULTS_FILE = ROOT / "tools/phase2d1r_study_results.json"
S_RESULTS_FILE = ROOT / "tools/phase2d1s_study_results.json"

def main():
    with open(R_RESULTS_FILE, "r", encoding="utf-8") as f:
        r_data = json.load(f)
    with open(S_RESULTS_FILE, "r", encoding="utf-8") as f:
        s_data = json.load(f)

    print("\n==================================================================================================================================")
    print("PHASE 2D.1-S CROSS-PIPELINE ISOLATION SANITY CHECK RESULTS (VOLUMETRICS 100% OFF)")
    print("==================================================================================================================================")
    print(f"{'Run Label':<24} | {'Ablation Mode':<30} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8} | {'Cluster Reqs':<12}")
    print("-" * 130)

    for label, r in s_data.items():
        mode = r.get("ablation_mode", "ADVANCED_FULL")
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        gpu_p99 = r.get("gpu_p99", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        reqs = r.get("requested_indices", 0)
        print(f"{label:<24} | {mode:<30} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f} | {reqs:<12}")

    b1_s = s_data.get("phase2d1s-b1-vol-off", {}).get("gpu_p95", 0.0)
    c1_s = s_data.get("phase2d1s-c1-vol-off", {}).get("gpu_p95", 0.0)
    b2_s = s_data.get("phase2d1s-b2-vol-off", {}).get("gpu_p95", 0.0)
    c2_s = s_data.get("phase2d1s-c2-vol-off", {}).get("gpu_p95", 0.0)
    b3_s = s_data.get("phase2d1s-b3-vol-off", {}).get("gpu_p95", 0.0)
    c3_s = s_data.get("phase2d1s-c3-vol-off", {}).get("gpu_p95", 0.0)

    delta1_s = b1_s - c1_s
    delta2_s = b2_s - c2_s
    delta3_s = b3_s - c3_s
    avg_delta_s = (delta1_s + delta2_s + delta3_s) / 3.0

    b_avg_s = (b1_s + b2_s + b3_s) / 3.0
    c_avg_s = (c1_s + c2_s + c3_s) / 3.0

    # Phase 2D.1-R data (Volumetrics ON)
    b1_r = r_data.get("phase2d1r-b1", {}).get("gpu_p95", 0.0)
    c1_r = r_data.get("phase2d1r-c1", {}).get("gpu_p95", 0.0)
    b2_r = r_data.get("phase2d1r-b2", {}).get("gpu_p95", 0.0)
    c2_r = r_data.get("phase2d1r-c2", {}).get("gpu_p95", 0.0)
    b3_r = r_data.get("phase2d1r-b3", {}).get("gpu_p95", 0.0)
    c3_r = r_data.get("phase2d1r-c3", {}).get("gpu_p95", 0.0)

    avg_delta_r = ((b1_r - c1_r) + (b2_r - c2_r) + (b3_r - c3_r)) / 3.0

    cross_pipeline_saving = avg_delta_r - avg_delta_s

    print("\n==================================================================================================================================")
    print("PURE L3 STRUCTURAL COST VS CROSS-PIPELINE LEVERAGE DECOMPOSITION")
    print("==================================================================================================================================")
    print(f"Volumetrics OFF Mode B Average (Shading Off):   {b_avg_s:.2f} ms GPU p95")
    print(f"Volumetrics OFF Mode C Average (Struct Bypassed): {c_avg_s:.2f} ms GPU p95")
    print(f"  └── 1. PURE L3 STRUCTURAL TRAVERSAL COST:       {avg_delta_s:.2f} ms GPU p95 (Mode B_L3 -> Mode C_L3)")
    print("-" * 90)
    print(f"Volumetrics ON Total Isolation Delta (Phase 2D.1-R): {avg_delta_r:.2f} ms GPU p95")
    print(f"  └── 2. ADDITIONAL L6.7 VOLUMETRIC LEVERAGE:      {cross_pipeline_saving:.2f} ms GPU p95")
    print("-" * 90)
    print(f"3. TOTAL CROSS-PIPELINE LEVERAGE OF STATIC LIGHT REDESIGN: {avg_delta_r:.2f} ms GPU p95")

if __name__ == "__main__":
    main()
