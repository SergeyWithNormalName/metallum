#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path("/Users/sergejgenerozov/Documents/Эксперимент с модом/metallum")
RESULTS_FILE = ROOT / "tools/phase2c1_verification_results.json"

def main():
    with open(RESULTS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)

    print("\n==========================================================================================================")
    print("PHASE 2C.1 PAIRED VERIFICATION BENCHMARK RESULTS")
    print("==========================================================================================================")
    print(f"{'Run Label':<22} | {'Ablation Mode':<30} | {'FPS':<6} | {'GPU p50':<8} | {'GPU p95':<8} | {'GPU p99':<8} | {'CPU p95':<8}")
    print("-" * 110)

    for label, r in data.items():
        mode = r.get("ablation_mode", "ADVANCED_FULL")
        fps = r.get("fps", 0.0)
        gpu_p50 = r.get("gpu_p50", 0.0)
        gpu_p95 = r.get("gpu_p95", 0.0)
        gpu_p99 = r.get("gpu_p99", 0.0)
        cpu_p95 = r.get("cpu_p95", 0.0)
        print(f"{label:<22} | {mode:<30} | {fps:<6.2f} | {gpu_p50:<8.2f} | {gpu_p95:<8.2f} | {gpu_p99:<8.2f} | {cpu_p95:<8.2f}")

    full1 = data.get("verify-full-1", {}).get("gpu_p95", 59.38)
    tap1 = data.get("verify-1tap-1", {}).get("gpu_p95", 48.88)
    full2 = data.get("verify-full-2", {}).get("gpu_p95", 59.38)
    tap2 = data.get("verify-1tap-2", {}).get("gpu_p95", 48.88)

    delta1 = full1 - tap1
    delta2 = full2 - tap2
    avg_delta = (delta1 + delta2) / 2.0

    print("\n==========================================================================================================")
    print("CONFIRMED DELTA VERIFICATION")
    print("==========================================================================================================")
    print(f"Pair 1 Delta (FULL - 1-TAP): {delta1:.2f} ms GPU p95")
    print(f"Pair 2 Delta (FULL - 1-TAP): {delta2:.2f} ms GPU p95")
    print(f"Average Confirmed Delta:     {avg_delta:.2f} ms GPU p95")

if __name__ == "__main__":
    main()
