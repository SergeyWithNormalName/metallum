#!/bin/bash
# Reproducible, console-only Metallum benchmark on the MacBook's built-in panel.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ANALYZER="$ROOT/tools/metal_benchmark_report.py"
RUN_DIR="$ROOT/run"
OUTPUT_DIR="$RUN_DIR/logs/metallum-benchmarks"

MONITOR_NAME="Built-in Retina Display"
WIDTH=3024
HEIGHT=1964
REFRESH_HZ=120
WARMUP_FRAMES=1800
MEASURE_FRAMES=3000
MIN_MAX_FPS=240

WORLD="HDRTest"
ROUTE="static-heavy"
METALFX_MODE="OFF"
LABEL="baseline"
PREFLIGHT_ONLY=0

usage() {
    cat <<'EOF'
Usage: scripts/run_metal_benchmark.sh [options]

Runs Minecraft without GUI automation or screenshots, moves it to the built-in
Retina panel at 3024x1964 fullscreen, warms up for 1800 presented frames, and
measures 3000 frames into a unique ignored JSONL report.

Options:
  --world NAME       quick-play world directory under run/saves (default: HDRTest)
  --metalfx MODE     OFF, QUALITY, or PERFORMANCE (default: OFF)
  --label LABEL      short artifact label (default: baseline)
  --preflight-only   validate process/config/world state without launching
  -h, --help         show this help

Compare completed reports with:
  python3 tools/metal_benchmark_report.py compare BASELINE.jsonl CANDIDATE.jsonl
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

need_value() {
    [ "$#" -ge 2 ] || die "$1 requires a value"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --world)
            need_value "$@"
            WORLD=$2
            shift 2
            ;;
        --metalfx)
            need_value "$@"
            METALFX_MODE=$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')
            shift 2
            ;;
        --label)
            need_value "$@"
            LABEL=$2
            shift 2
            ;;
        --preflight-only)
            PREFLIGHT_ONLY=1
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die "unknown option: $1"
            ;;
    esac
done

case "$METALFX_MODE" in
    OFF|QUALITY|PERFORMANCE) ;;
    *) die "--metalfx must be OFF, QUALITY, or PERFORMANCE (AUTO is not reproducible)" ;;
esac
case "$WORLD" in
    ""|.|..|*/*) die "--world must be one directory name under run/saves" ;;
esac

command -v python3 >/dev/null 2>&1 || die "python3 is required for report validation"
command -v pgrep >/dev/null 2>&1 || die "pgrep is required for process isolation"
[ -x "$ROOT/gradlew" ] || die "Gradle wrapper is missing or not executable"
[ -f "$ANALYZER" ] || die "report analyzer is missing: $ANALYZER"
[ "$(uname -s)" = "Darwin" ] || die "the Metal benchmark launcher requires macOS"
case "$(uname -m)" in
    arm64|aarch64) ;;
    *) die "the Metal benchmark launcher requires Apple Silicon" ;;
esac

PROCESS_PATTERN='[n]et\.minecraft\.client\.main\.Main|[n]et\.fabricmc\.devlaunchinjector|[K]notClient|[G]radleWrapperMain.*runClient'
running_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
[ -z "$running_processes" ] || die "another Minecraft/runClient process is active:\n$running_processes"

option_value() {
    awk -v key="$1" 'index($0, key ":") == 1 { print substr($0, length(key) + 2); exit }' "$RUN_DIR/options.txt"
}

property_value() {
    awk -F= -v key="$1" '$0 !~ /^[[:space:]]*#/ && $1 == key { print substr($0, length(key) + 2); exit }' "$2"
}

require_value() {
    local actual=$1
    local expected=$2
    local description=$3
    [ "$actual" = "$expected" ] || die "$description must be $expected (found ${actual:-<missing>})"
}

[ -f "$RUN_DIR/options.txt" ] || die "missing Minecraft config: run/options.txt"
[ -f "$RUN_DIR/config/metallum-hdr.properties" ] || die "missing HDR config"
[ -f "$RUN_DIR/config/metallum-metalfx.properties" ] || die "missing MetalFX config"
[ -f "$RUN_DIR/saves/$WORLD/level.dat" ] || die "world run/saves/$WORLD is missing or invalid"

require_value "$(option_value enableVsync)" "false" "enableVsync"
require_value "$(option_value fullscreen)" "true" "fullscreen"
require_value "$(option_value exclusiveFullscreen)" "true" "exclusiveFullscreen"
require_value "$(option_value preferredGraphicsBackend)" '"default"' "preferredGraphicsBackend"

max_fps=$(option_value maxFps)
case "$max_fps" in
    ''|*[!0-9]*) die "maxFps must be an integer >= $MIN_MAX_FPS (found ${max_fps:-<missing>})" ;;
esac
[ "$max_fps" -ge "$MIN_MAX_FPS" ] || die "maxFps must be >= $MIN_MAX_FPS (found $max_fps)"

HDR_CONFIG="$RUN_DIR/config/metallum-hdr.properties"
METALFX_CONFIG="$RUN_DIR/config/metallum-metalfx.properties"
require_value "$(property_value mode "$HDR_CONFIG")" "scene" "HDR mode"
require_value "$(property_value sourceEncoding "$HDR_CONFIG")" "srgb" "HDR sourceEncoding"
require_value "$(property_value diagnosticPattern "$HDR_CONFIG")" "false" "HDR diagnosticPattern"
require_value "$(property_value mode "$METALFX_CONFIG")" "off" "persistent MetalFX mode"

mkdir -p "$OUTPUT_DIR"
git -C "$ROOT" check-ignore -q "$OUTPUT_DIR/.metallum-benchmark-probe" \
    || die "benchmark output directory must be ignored by git: $OUTPUT_DIR"

commit=$(git -C "$ROOT" rev-parse --short=12 HEAD)
worktree_state="clean"
dirty_flag=0
if [ -n "$(git -C "$ROOT" status --porcelain=v1 --untracked-files=normal)" ]; then
    worktree_state="dirty"
    dirty_flag=1
fi
safe_label=$(printf '%s' "$LABEL" | tr -cs '[:alnum:]._' '-' | sed 's/^-*//; s/-*$//')
[ -n "$safe_label" ] || safe_label="run"
mode_label=$(printf '%s' "$METALFX_MODE" | tr '[:upper:]' '[:lower:]')
stamp=$(date -u +%Y%m%dT%H%M%SZ)
stem="${stamp}-g${commit}-${worktree_state}-${safe_label}-${mode_label}"
suffix=1
while [ -e "$OUTPUT_DIR/$stem.raw.jsonl" ]; do
    stem="${stamp}-g${commit}-${worktree_state}-${safe_label}-${mode_label}-${suffix}"
    suffix=$((suffix + 1))
done

RAW_REPORT="$OUTPUT_DIR/$stem.raw.jsonl"
MINECRAFT_LOG="$OUTPUT_DIR/$stem.minecraft.log"
CONSOLE_LOG="$OUTPUT_DIR/$stem.console.log"
SUMMARY_JSON="$OUTPUT_DIR/$stem.summary.json"

echo "Metallum benchmark preflight passed"
echo "  display: $MONITOR_NAME, ${WIDTH}x${HEIGHT}@${REFRESH_HZ}, exclusive fullscreen"
echo "  pacing: VSync off, maxFps=$max_fps"
echo "  scene: HDR scene/sRGB source, diagnostic pattern off"
echo "  MetalFX: $METALFX_MODE (persistent config remains off)"
echo "  world/route: $WORLD / $ROUTE"
echo "  frames: $WARMUP_FRAMES warmup + $MEASURE_FRAMES measurement"
echo "  commit: $commit ($worktree_state worktree state)"
echo "  raw report: $RAW_REPORT"

if [ "$PREFLIGHT_ONLY" -eq 1 ]; then
    exit 0
fi

# Performance runs must not inherit debug/capture/HUD instrumentation. The
# built-in timestamp report remains enabled with non-intrusive detail disabled.
unset MTL_DEBUG_LAYER
unset MTL_SHADER_VALIDATION
unset MTL_SHADER_VALIDATION_REPORT_TO_STDERR
unset MTL_CAPTURE_ENABLED
unset MTL_HUD_ENABLED
unset MTL_HUD_LOG_ENABLED
unset METAL_DEVICE_WRAPPER_TYPE

start_epoch=$(date +%s)
cd "$ROOT"
set +e
METALLUM_BENCHMARK=1 \
METALLUM_BENCHMARK_MONITOR="$MONITOR_NAME" \
METALLUM_BENCHMARK_WIDTH="$WIDTH" \
METALLUM_BENCHMARK_HEIGHT="$HEIGHT" \
METALLUM_BENCHMARK_REFRESH_HZ="$REFRESH_HZ" \
METALLUM_BENCHMARK_WARMUP_FRAMES="$WARMUP_FRAMES" \
METALLUM_BENCHMARK_MEASURE_FRAMES="$MEASURE_FRAMES" \
METALLUM_BENCHMARK_SEQUENCE="$METALFX_MODE" \
METALLUM_BENCHMARK_CURRENT_WINDOW=0 \
METALLUM_BENCHMARK_SCREENSHOTS=0 \
METALLUM_BENCHMARK_COMMIT="$commit" \
METALLUM_BENCHMARK_DIRTY="$dirty_flag" \
METALLUM_BENCHMARK_WORLD="$WORLD" \
METALLUM_BENCHMARK_ROUTE="$ROUTE" \
METALLUM_GPU_TIMING=1 \
METALLUM_GPU_TIMING_DETAIL=0 \
METALLUM_GPU_TIMING_REPORT="$RAW_REPORT" \
    ./gradlew --no-daemon runClient --console=plain \
        "--args=--quickPlaySingleplayer $WORLD" 2>&1 | tee "$CONSOLE_LOG"
gradle_status=${PIPESTATUS[0]}
set -e

LATEST_LOG="$RUN_DIR/logs/latest.log"
if [ -f "$LATEST_LOG" ]; then
    latest_mtime=$(stat -f %m "$LATEST_LOG")
    cp "$LATEST_LOG" "$MINECRAFT_LOG"
fi
[ "$gradle_status" -eq 0 ] || die "runClient exited with status $gradle_status (console: $CONSOLE_LOG)"
[ -s "$MINECRAFT_LOG" ] || die "Minecraft did not produce a fresh log"
[ "$latest_mtime" -ge "$start_epoch" ] || die "Minecraft log predates this benchmark run"

if grep -Fq "METALLUM_BENCHMARK EVENT=FAIL" "$MINECRAFT_LOG"; then
    grep -F "METALLUM_BENCHMARK EVENT=FAIL" "$MINECRAFT_LOG" >&2 || true
    die "benchmark controller reported failure"
fi
if grep -Fq "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED" "$MINECRAFT_LOG"; then
    die "benchmark unexpectedly requested a screenshot"
fi
if grep -Eq '\[metallum\] (Metal command buffer failed|GPU timing sample invalid)' "$CONSOLE_LOG"; then
    grep -E '\[metallum\] (Metal command buffer failed|GPU timing sample invalid)' "$CONSOLE_LOG" >&2 || true
    die "Metal reported an invalid command buffer or GPU timing sample"
fi

armed="METALLUM_BENCHMARK EVENT=ARMED scope=$MONITOR_NAME target=${WIDTH}x${HEIGHT} warmup=$WARMUP_FRAMES measure=$MEASURE_FRAMES sequence=[$METALFX_MODE]"
grep -Fq "$armed" "$MINECRAFT_LOG" || die "benchmark ARMED marker does not match the requested contract"

window_ready=$(grep -F "METALLUM_BENCHMARK EVENT=WINDOW_READY monitor=$MONITOR_NAME" "$MINECRAFT_LOG" | tail -n 1 || true)
[ -n "$window_ready" ] || die "built-in display WINDOW_READY marker is missing"
case "$window_ready" in
    *"video_mode=${WIDTH}x${HEIGHT}@${REFRESH_HZ} "*"framebuffer=${WIDTH}x${HEIGHT} window=${WIDTH}x${HEIGHT} screen=${WIDTH}x${HEIGHT}"*) ;;
    *) die "WINDOW_READY did not prove exact fullscreen dimensions: $window_ready" ;;
esac

grep -Fq "METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=$METALFX_MODE presented_frame=$WARMUP_FRAMES" "$MINECRAFT_LOG" \
    || die "MEASURE_START marker is missing or inconsistent"
final_presented=$((WARMUP_FRAMES + MEASURE_FRAMES))
grep -Fq "METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode=$METALFX_MODE presented_frame=$final_presented" "$MINECRAFT_LOG" \
    || die "MEASURE_END marker is missing or inconsistent"

complete="METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=$MEASURE_FRAMES framebuffer=${WIDTH}x${HEIGHT}"
complete_count=$(grep -Fc "$complete" "$MINECRAFT_LOG" || true)
[ "$complete_count" -eq 1 ] || die "expected exactly one matching COMPLETE marker (found $complete_count)"
[ -s "$RAW_REPORT" ] || die "GPU timing JSONL report is missing or empty"

python3 "$ANALYZER" summarize "$RAW_REPORT" \
    --measure-frames "$MEASURE_FRAMES" \
    --segment 0 \
    --scaler-mode "$METALFX_MODE" \
    --release-contract \
    --world "$WORLD" \
    --route "$ROUTE" \
    --json > "$SUMMARY_JSON"
python3 "$ANALYZER" summarize "$RAW_REPORT" \
    --measure-frames "$MEASURE_FRAMES" \
    --segment 0 \
    --scaler-mode "$METALFX_MODE" \
    --release-contract \
    --world "$WORLD" \
    --route "$ROUTE"

remaining_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
[ -z "$remaining_processes" ] || die "benchmark returned but a Minecraft/runClient process remains:\n$remaining_processes"

echo "Benchmark accepted: COMPLETE present, no FAIL/screenshots, dropped timing events = 0"
echo "  raw: $RAW_REPORT"
echo "  summary: $SUMMARY_JSON"
echo "  Minecraft log: $MINECRAFT_LOG"
echo "  console log: $CONSOLE_LOG"
