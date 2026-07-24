#!/bin/bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
export JAVA_HOME="/Library/Java/JavaVirtualMachines/openjdk-25.jdk/Contents/Home"
export METALLUM_L2_WARMUP_FRAMES=1800
export METALLUM_L2_MEASURE_FRAMES=3000

chmod +x "$ROOT/scripts/run_metal_benchmark.sh"

echo "=== STARTING METALLUM REAL-WORLD METALFX BENCHMARK SUITE ==="

# 1. Overworld Benchmarks
echo "--- Running Overworld Benchmarks ---"
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/hdrtest-static-v1.json --settings benchmark/settings/native-hdr-fancy-v1.json --metalfx OFF --label ow-off
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/hdrtest-static-v1.json --settings benchmark/settings/native-hdr-fancy-v1.json --metalfx QUALITY --label ow-spatial-q
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/hdrtest-static-v1.json --settings benchmark/settings/native-hdr-fancy-v1.json --metalfx PERFORMANCE --label ow-spatial-p
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/hdrtest-static-v1.json --settings benchmark/settings/native-hdr-fancy-v1.json --metalfx TEMPORAL_QUALITY --label ow-temporal-q
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/hdrtest-static-v1.json --settings benchmark/settings/native-hdr-fancy-v1.json --metalfx TEMPORAL_PERFORMANCE --label ow-temporal-p

# 2. Nether Benchmarks
echo "--- Running Nether Benchmarks ---"
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx OFF --label nether-off
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx QUALITY --label nether-spatial-q
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx PERFORMANCE --label nether-spatial-p
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx TEMPORAL_QUALITY --label nether-temporal-q
"$ROOT/scripts/run_metal_benchmark.sh" --route benchmark/routes/nether-lava-stress-v1.json --settings benchmark/settings/nether-lava-stress-v1.json --metalfx TEMPORAL_PERFORMANCE --label nether-temporal-p

echo "=== BENCHMARK SUITE COMPLETED SUCCESSFULLY ==="
