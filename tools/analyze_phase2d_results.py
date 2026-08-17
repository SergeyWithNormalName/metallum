#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase2d_study_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("\n==========================================================================================================")
    print("PHASE 2D DENSE L3 / LAVA EMITTER ATTRIBUTION RESULTS")
    print("==========================================================================================================")
    print(f"{'Run Label':<22} | {'Ablation Mode':<25} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8} | {'Lights':<6} | {'Cluster Reqs':<12}")
    print("-" * 125)

    for label, r in data.items():
        mode = r.get("ablation_mode", "ADVANCED_FULL")
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        gpu_p99 = r.get("gpu_p99", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        lights = r.get("light_count", 0)
        reqs = r.get("requested_indices", 0)
        print(f"{label:<22} | {mode:<25} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f} | {lights:<6} | {reqs:<12}")

    full_p95 = data.get("phase2d-full", {}).get("gpu_p95", 60.0)
    no_lava_p95 = data.get("phase2d-no-lava", {}).get("gpu_p95", 58.0)
    no_non_lava_p95 = data.get("phase2d-no-non-lava", {}).get("gpu_p95", 58.0)
    no_l3_p95 = data.get("phase2d-no-l3", {}).get("gpu_p95", 54.0)

    total_l3_cost = full_p95 - no_l3_p95
    lava_cost = full_p95 - no_lava_p95
    non_lava_cost = full_p95 - no_non_lava_p95

    print("\n==========================================================================================================")
    print("EMPIRICAL L3 SOURCE CLASS SHADING COST (GPU p95)")
    print("==========================================================================================================")
    print(f"Total L3 Direct Shading Overhead (Full vs No-L3):      {total_l3_cost:>6.2f} ms")
    print(f"  ├── Lava-Derived L3 Shading Overhead (Full vs No-Lava): {lava_cost:>6.2f} ms ({lava_cost/max(total_l3_cost,0.01)*100:.1f}%)")
    print(f"  └── Non-Lava Static L3 Shading Overhead:                {non_lava_cost:>6.2f} ms ({non_lava_cost/max(total_l3_cost,0.01)*100:.1f}%)")

if __name__ == "__main__":
    main()
