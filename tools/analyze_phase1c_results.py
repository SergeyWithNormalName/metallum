#!/usr/bin/env python3
import json
import statistics
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase1c_ablation_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)
        
    categories = {
        "A (FULL ADVANCED)": ["phase1c-A1-full-advanced", "phase1c-A2-full-advanced"],
        "B (NO L6 SHADOWS)": ["phase1c-B1-no-l6-shadows", "phase1c-B2-no-l6-shadows"],
        "C (NO VOLUMETRICS)": ["phase1c-C1-no-volumetrics", "phase1c-C2-no-volumetrics"],
        "D (NO PBR EXTRAS)": ["phase1c-D1-no-pbr-extras", "phase1c-D2-no-pbr-extras"],
        "E (L3 SHADE DISABLED)": ["phase1c-E1-l3-shade-disabled", "phase1c-E2-l3-shade-disabled"],
    }
    
    summary = {}
    
    for cat_name, labels in categories.items():
        cat_runs = [data[lbl] for lbl in labels if lbl in data]
        if not cat_runs:
            continue
            
        fps_vals = [r["fps"] for r in cat_runs]
        low1_vals = [r["low_1"] for r in cat_runs]
        low01_vals = [r["low_01"] for r in cat_runs]
        gpu_p50_vals = [r["gpu_p50"] for r in cat_runs]
        gpu_p95_vals = [r["gpu_p95"] for r in cat_runs]
        gpu_p99_vals = [r["gpu_p99"] for r in cat_runs]
        gpu_max_vals = [r["gpu_max"] for r in cat_runs]
        cpu_p50_vals = [r["cpu_p50"] for r in cat_runs]
        cpu_p95_vals = [r["cpu_p95"] for r in cat_runs]
        cpu_p99_vals = [r["cpu_p99"] for r in cat_runs]
        lights_vals = [r["light_count"] for r in cat_runs]
        indices_vals = [r["requested_indices"] for r in cat_runs]
        occ_p95_vals = [r["occ_p95"] for r in cat_runs]
        occ_max_vals = [r["occ_max"] for r in cat_runs]
        ablation_modes = [r["advanced_ablation_mode"] for r in cat_runs]
        
        summary[cat_name] = {
            "ablation_mode": ablation_modes[0],
            "fps_med": statistics.median(fps_vals),
            "low1_med": statistics.median(low1_vals),
            "low01_med": statistics.median(low01_vals),
            "gpu_p50_med": statistics.median(gpu_p50_vals),
            "gpu_p95_med": statistics.median(gpu_p95_vals),
            "gpu_p99_med": statistics.median(gpu_p99_vals),
            "gpu_max_med": statistics.median(gpu_max_vals),
            "cpu_p50_med": statistics.median(cpu_p50_vals),
            "cpu_p95_med": statistics.median(cpu_p95_vals),
            "cpu_p99_med": statistics.median(cpu_p99_vals),
            "lights_med": statistics.median(lights_vals),
            "indices_med": statistics.median(indices_vals),
            "occ_p95_med": statistics.median(occ_p95_vals),
            "occ_max_med": statistics.median(occ_max_vals),
            "runs": cat_runs
        }

    full_gpu_p95 = summary["A (FULL ADVANCED)"]["gpu_p95_med"]
    
    print("\n==========================================================================================================")
    print("PHASE 1C COMPONENT-LEVEL ABLATION SUMMARY METRICS")
    print("==========================================================================================================")
    print(f"{'Category':<22} | {'Ablation Mode':<26} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'Δ GPU p95':<10} | {'CPU p95':<8}")
    print("-" * 106)
    
    for cat_name, s in summary.items():
        delta_gpu_p95 = s["gpu_p95_med"] - full_gpu_p95
        sign = "+" if delta_gpu_p95 > 0 else ""
        print(f"{cat_name:<22} | {s['ablation_mode']:<26} | {s['fps_med']:<6.2f} | {s['gpu_p50_med']:<8.2f} | {s['gpu_p95_med']:<8.2f} | {sign}{delta_gpu_p95:<9.2f} | {s['cpu_p95_med']:<8.2f}")

    print("\n==========================================================================================================")
    print("DETAILED WORKLOAD & STAGE TIMINGS")
    print("==========================================================================================================")
    for cat_name, s in summary.items():
        print(f"\n--- {cat_name} (Mode: {s['ablation_mode']}) ---")
        print(f"  FPS: {s['fps_med']:.2f} (1% low: {s['low1_med']:.2f}, 0.1% low: {s['low01_med']:.2f})")
        print(f"  GPU ms: p50={s['gpu_p50_med']:.2f}, p95={s['gpu_p95_med']:.2f}, p99={s['gpu_p99_med']:.2f}, max={s['gpu_max_med']:.2f}")
        print(f"  CPU ms: p50={s['cpu_p50_med']:.2f}, p95={s['cpu_p95_med']:.2f}, p99={s['cpu_p99_med']:.2f}")
        print(f"  Lights: {s['lights_med']:.0f}, Cluster Requested Indices: {s['indices_med']:.0f}, Occupancy p95: {s['occ_p95_med']:.0f}, Max: {s['occ_max_med']:.0f}")

        # Check stage breakdown if present in last run
        stages = s["runs"][0].get("stages", {})
        if stages:
            print("  Stage GPU Timings:")
            for st_name, st_val in stages.items():
                if isinstance(st_val, dict) and "p95" in st_val:
                    print(f"    - {st_name}: p50={st_val.get('p50',0):.2f}ms, p95={st_val.get('p95',0):.2f}ms")

if __name__ == "__main__":
    main()
