#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
T_RESULTS_FILE = ROOT / "tools/phase2d1t_study_results.json"
S_RESULTS_FILE = ROOT / "tools/phase2d1s_study_results.json"
R_RESULTS_FILE = ROOT / "tools/phase2d1r_study_results.json"

def main():
    with open(T_RESULTS_FILE, "r", encoding="utf-8") as f:
        t_data = json.load(f)
    with open(S_RESULTS_FILE, "r", encoding="utf-8") as f:
        s_data = json.load(f)
    with open(R_RESULTS_FILE, "r", encoding="utf-8") as f:
        r_data = json.load(f)

    print("\n==================================================================================================================================")
    print("PHASE 2D.1-T TRUE L3-ONLY STRUCTURAL ISOLATION RESULTS (VOLUMETRICS = OFF, L6 LOCAL SHADOWS = OFF)")
    print("==================================================================================================================================")
    print(f"{'Run Label':<24} | {'Ablation Mode':<35} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8} | {'Cluster Reqs':<12}")
    print("-" * 135)

    for label, r in t_data.items():
        mode = r.get("ablation_mode", "ADVANCED_FULL")
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        gpu_p99 = r.get("gpu_p99", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        reqs = r.get("requested_indices", 0)
        print(f"{label:<24} | {mode:<35} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f} | {reqs:<12}")

    b1 = t_data.get("phase2d1t-b1-true", {}).get("gpu_p95", 0.0)
    c1 = t_data.get("phase2d1t-c1-true", {}).get("gpu_p95", 0.0)
    b2 = t_data.get("phase2d1t-b2-true", {}).get("gpu_p95", 0.0)
    c2 = t_data.get("phase2d1t-c2-true", {}).get("gpu_p95", 0.0)
    b3 = t_data.get("phase2d1t-b3-true", {}).get("gpu_p95", 0.0)
    c3 = t_data.get("phase2d1t-c3-true", {}).get("gpu_p95", 0.0)

    delta1 = b1 - c1
    delta2 = b2 - c2
    delta3 = b3 - c3
    avg_delta_t = (delta1 + delta2 + delta3) / 3.0

    b_avg_t = (b1 + b2 + b3) / 3.0
    c_avg_t = (c1 + c2 + c3) / 3.0

    # Cross-pipeline leverage comparison
    avg_delta_s = 8.27  # Phase 2D.1-S (L6.7 OFF, L6 1-tap active)
    avg_delta_r = 28.36 # Phase 2D.1-R (L6.7 ON, L6 1-tap active)

    l6_leverage = avg_delta_s - avg_delta_t
    l67_leverage = avg_delta_r - avg_delta_s

    print("\n==================================================================================================================================")
    print("TRUE PURE L3 STRUCTURAL COST & FULL CROSS-PIPELINE LEVERAGE DECOMPOSITION")
    print("==================================================================================================================================")
    print(f"Mode B_TRUE Average (Shading OFF, L6 OFF, Vol OFF):   {b_avg_t:.2f} ms GPU p95 ({1000.0/max(b_avg_t,0.01):.2f} FPS)")
    print(f"Mode C_TRUE Average (Struct Bypassed, L6 OFF, Vol OFF): {c_avg_t:.2f} ms GPU p95 ({1000.0/max(c_avg_t,0.01):.2f} FPS)")
    print(f"  └── 1. TRUE PURE L3 STRUCTURAL TRAVERSAL COST:       {avg_delta_t:.2f} ms GPU p95 (Mode B_TRUE -> Mode C_TRUE)")
    print("-" * 100)
    print(f"  └── 2. L6 LOCAL SHADOW TRAVERSAL LEVERAGE:           {l6_leverage:.2f} ms GPU p95 (Saved L6 atlas lookup work)")
    print(f"  └── 3. L6.7 VOLUMETRIC FROXEL INJECTION LEVERAGE:      {l67_leverage:.2f} ms GPU p95 (Saved L6.7 ray-marching work)")
    print("-" * 100)
    print(f"4. TOTAL CROSS-PIPELINE LEVERAGE OF STATIC LIGHT REDESIGN: {avg_delta_r:.2f} ms GPU p95 (From 45.07 ms down to 16.71 ms)")

if __name__ == "__main__":
    main()
