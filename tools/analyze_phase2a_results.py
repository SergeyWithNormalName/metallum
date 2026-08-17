#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase2a_study_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("\n==========================================================================================================")
    print("PHASE 2A VOLUMETRIC COST DECOMPOSITION & SCALING SUMMARY")
    print("==========================================================================================================")
    print(f"{'Run Label':<22} | {'Scale':<6} | {'Cap':<6} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8} | {'Froxels':<10}")
    print("-" * 110)

    # Froxel dimensions for rendering width=3024, height=1964
    # Scale 1.0: div=8 (378x246x56 = 5,206,272)
    # Scale 0.75: div=11 (275x179x42 = 2,067,450)
    # Scale 0.50: div=16 (189x123x28 = 650,886)
    froxel_counts = {
        "1.0": 5206272,
        "0.75": 2067450,
        "0.50": 650886
    }

    for label, r in data.items():
        scale = r.get("froxel_scale", "1.0")
        cap = r.get("volumetric_light_cap", "default")
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        gpu_p99 = r.get("gpu_p99", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        cnt = froxel_counts.get(scale, 0)
        print(f"{label:<22} | {scale:<6} | {cap:<6} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f} | {cnt:<10,d}")

    print("\n==========================================================================================================")
    print("DETAILED STAGE GPU TIMINGS (STAGE BREAKDOWN)")
    print("==========================================================================================================")
    for label, r in data.items():
        print(f"\n--- {label} ---")
        stages = r.get("stages", {})
        if stages:
            for st_name, st_val in sorted(stages.items()):
                if isinstance(st_val, dict) and "p95" in st_val:
                    print(f"  - {st_name:<32}: p50={st_val.get('p50',0):.2f}ms | p95={st_val.get('p95',0):.2f}ms | max={st_val.get('maximum',0):.2f}ms")
        else:
            print("  No stage detail object")

if __name__ == "__main__":
    main()
