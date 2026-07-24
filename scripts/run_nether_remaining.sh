#!/bin/bash
ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME="/Library/Java/JavaVirtualMachines/openjdk-25.jdk/Contents/Home"
export METALLUM_L2_WARMUP_FRAMES=1800
export METALLUM_L2_MEASURE_FRAMES=3000

chmod +x "$ROOT/scripts/run_metal_benchmark.sh"

echo "--- Running Nether Spatial Performance ---"
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx PERFORMANCE --label nether-spatial-p || true

echo "--- Running Nether Temporal Quality ---"
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx TEMPORAL_QUALITY --label nether-temporal-q || true

echo "--- Running Nether Temporal Performance ---"
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx TEMPORAL_PERFORMANCE --label nether-temporal-p || true

echo "=== NETHER REMAINING RUNS FINISHED ==="
