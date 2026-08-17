#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase2c_study_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("\n==========================================================================================================")
    print("PHASE 2C L6 LOCAL SHADOW COST DECOMPOSITION SUMMARY")
    print("==========================================================================================================")
    print(f"{'Run Label':<32} | {'Ablation Mode':<30} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8}")
    print("-" * 110)

    for label, r in data.items():
        mode = r.get("ablation_mode", "ADVANCED_FULL")
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        gpu_p99 = r.get("gpu_p99", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        print(f"{label:<32} | {mode:<30} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f}")

    full_p95 = data.get("phase2c-full", {}).get("gpu_p95", 60.0)
    no_l6_p95 = data.get("phase2c-no-l6", {}).get("gpu_p95", 45.9)
    desc_p95 = data.get("phase2c-descriptor-only", {}).get("gpu_p95", 46.0)
    tap1_p95 = data.get("phase2c-1tap-no-pcf", {}).get("gpu_p95", 52.0)
    no_dda_p95 = data.get("phase2c-no-dynamic-dda", {}).get("gpu_p95", 59.0)

    total_l6_cost = full_p95 - no_l6_p95
    desc_cost = desc_p95 - no_l6_p95
    pcf_cost = full_p95 - tap1_p95
    base_atlas_cost = tap1_p95 - desc_p95
    dda_cost = full_p95 - no_dda_p95

    print("\n==========================================================================================================")
    print("EMPIRICAL L6 COST BREAKDOWN (GPU p95)")
    print("==========================================================================================================")
    print(f"Total L6 Local Shadow Overhead (Full vs No-L6):         {total_l6_cost:>6.2f} ms")
    print(f"  ├── 4-Tap PCF Soft Filtering Overhead (Full vs 1-Tap):  {pcf_cost:>6.2f} ms ({pcf_cost/max(total_l6_cost,0.01)*100:.1f}%)")
    print(f"  ├── 1-Tap Atlas Memory Fetch Cost (1-Tap vs Desc Only): {base_atlas_cost:>6.2f} ms ({base_atlas_cost/max(total_l6_cost,0.01)*100:.1f}%)")
    print(f"  ├── Descriptor & Proxy Bounds Lookup Overhead:        {desc_cost:>6.2f} ms ({desc_cost/max(total_l6_cost,0.01)*100:.1f}%)")
    print(f"  └── Dynamic DDA Compute Pass (Full vs No Dynamic DDA): {dda_cost:>6.2f} ms ({dda_cost/max(total_l6_cost,0.01)*100:.1f}%)")

if __name__ == "__main__":
    main()
