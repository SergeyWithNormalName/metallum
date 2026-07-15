#!/bin/bash
# Reproducible, console-only Metallum benchmark on the MacBook's built-in panel.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ANALYZER="$ROOT/tools/metal_benchmark_report.py"
FIXTURE_HELPER="$ROOT/tools/metal_benchmark_fixture.py"
RUN_DIR="$ROOT/run"
OUTPUT_DIR="$RUN_DIR/logs/metallum-benchmarks"
REFERENCE_OUTPUT_DIR="$RUN_DIR/lighting-reference/l0"
DEFAULT_ROUTE_SPEC="benchmark/routes/hdrtest-static-v1.json"
DEFAULT_SETTINGS_SPEC="benchmark/settings/native-hdr-fancy-v1.json"
ARTIFACT_CLASSES="build/classes/java/main"
ARTIFACT_RESOURCES="build/resources/main"
ARTIFACT_NATIVE="build/generated/metallum/natives/macos/libmetallum.dylib"

MONITOR_NAME="Built-in Retina Display"
WIDTH=3024
HEIGHT=1964
REFRESH_HZ=120
WARMUP_FRAMES=1800
MEASURE_FRAMES=3000
MIN_MAX_FPS=240

ROUTE_SPEC_ARGUMENT="$DEFAULT_ROUTE_SPEC"
SETTINGS_SPEC_ARGUMENT="$DEFAULT_SETTINGS_SPEC"
METALFX_MODE="OFF"
LABEL="baseline"
PREFLIGHT_ONLY=0
CAPTURE_REFERENCE=0

RUN_WORLD_PATH=""
RUN_WORLD_NAME=""
RUN_WORLD_TOKEN=""
RUN_WORLD_IDENTITY=""
FIXTURE_DIGEST_BEFORE=""
SETTINGS_VALUES_BEFORE=""
ARTIFACT_SHA256=""
ATTEST_PENDING=0

usage() {
    cat <<'EOF'
Usage: scripts/run_metal_benchmark.sh [options]

Runs Minecraft without GUI automation or screenshots, moves it to the built-in
Retina panel at 3024x1964 fullscreen, warms up for 1800 presented frames, and
measures 3000 frames into a unique ignored JSONL report.

Options:
  --route FILE       tracked deterministic route specification
                     (default: benchmark/routes/hdrtest-static-v1.json)
  --settings FILE    tracked performance/quality settings specification
                     (default: benchmark/settings/native-hdr-fancy-v1.json)
  --metalfx MODE     OFF, QUALITY, or PERFORMANCE (default: OFF)
  --label LABEL      short artifact label (default: baseline)
  --preflight-only   validate route/config/immutable fixture without cloning
  --capture-reference capture one ignored screenshot; this run is not attested
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
        --route)
            need_value "$@"
            ROUTE_SPEC_ARGUMENT=$2
            shift 2
            ;;
        --metalfx)
            need_value "$@"
            METALFX_MODE=$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')
            shift 2
            ;;
        --settings)
            need_value "$@"
            SETTINGS_SPEC_ARGUMENT=$2
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
        --capture-reference)
            CAPTURE_REFERENCE=1
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

command -v python3 >/dev/null 2>&1 || die "python3 is required for report validation"
command -v pgrep >/dev/null 2>&1 || die "pgrep is required for process isolation"
command -v mktemp >/dev/null 2>&1 || die "mktemp is required for isolated benchmark worlds"
command -v uuidgen >/dev/null 2>&1 || die "uuidgen is required for isolated benchmark worlds"
[ -x "$ROOT/gradlew" ] || die "Gradle wrapper is missing or not executable"
[ -f "$ANALYZER" ] || die "report analyzer is missing: $ANALYZER"
[ -f "$FIXTURE_HELPER" ] || die "fixture helper is missing: $FIXTURE_HELPER"
[ "$(uname -s)" = "Darwin" ] || die "the Metal benchmark launcher requires macOS"
case "$(uname -m)" in
    arm64|aarch64) ;;
    *) die "the Metal benchmark launcher requires Apple Silicon" ;;
esac

PROCESS_PATTERN='[n]et\.minecraft\.client\.main\.Main|[n]et\.fabricmc\.devlaunchinjector|[K]notClient|[G]radleWrapperMain.*runClient'
running_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
[ -z "$running_processes" ] || die "another Minecraft/runClient process is active:\n$running_processes"

require_value() {
    local actual=$1
    local expected=$2
    local description=$3
    [ "$actual" = "$expected" ] || die "$description must be $expected (found ${actual:-<missing>})"
}

benchmark_artifact_digest() {
    python3 "$FIXTURE_HELPER" artifact-digest "$ROOT" \
        "$ARTIFACT_CLASSES" "$ARTIFACT_RESOURCES" "$ARTIFACT_NATIVE"
}

case "$ROUTE_SPEC_ARGUMENT" in
    /*) ROUTE_SPEC="$ROUTE_SPEC_ARGUMENT" ;;
    *) ROUTE_SPEC="$ROOT/$ROUTE_SPEC_ARGUMENT" ;;
esac
[ -f "$ROUTE_SPEC" ] || die "route specification is missing: $ROUTE_SPEC"
case "$SETTINGS_SPEC_ARGUMENT" in
    /*) SETTINGS_SPEC="$SETTINGS_SPEC_ARGUMENT" ;;
    *) SETTINGS_SPEC="$ROOT/$SETTINGS_SPEC_ARGUMENT" ;;
esac
[ -f "$SETTINGS_SPEC" ] || die "settings specification is missing: $SETTINGS_SPEC"

route_values=$(python3 "$FIXTURE_HELPER" route-values "$ROUTE_SPEC") \
    || die "route specification failed validation: $ROUTE_SPEC"
route_field_count=$(printf '%s\n' "$route_values" | awk -F '\t' '{ print NF; exit }')
ROUTE_KIND="STATIC"
TORCH_POSITION_X=0
TORCH_POSITION_Y=0
TORCH_POSITION_Z=0
TORCH_INITIAL_BLOCK="minecraft:air"
TORCH_SUPPORT_BLOCK="minecraft:air"
TORCH_APPLY_AFTER_MEASURED_FRAMES=0
TORCH_OBSERVATION_FRAMES=0
TORCH_REMOVE_AFTER_MEASURED_FRAMES=0
case "$route_field_count" in
    19)
        IFS=$'\t' read -r \
            ROUTE_ID ROUTE_SHA256 FIXTURE_ID FIXTURE_SHA256 \
            PLAYER_NAME PLAYER_UUID DIMENSION \
            POSITION_X POSITION_Y POSITION_Z YAW PITCH \
            CLOCK_TICKS CLEAR_WEATHER_TICKS SIMULATION_FROZEN \
            ROUTE_STABLE_FRAMES ROUTE_TIMEOUT_FRAMES \
            POSITION_EPSILON ANGLE_EPSILON <<< "$route_values"
        ;;
    27)
        IFS=$'\t' read -r \
            ROUTE_ID ROUTE_SHA256 FIXTURE_ID FIXTURE_SHA256 \
            PLAYER_NAME PLAYER_UUID DIMENSION \
            POSITION_X POSITION_Y POSITION_Z YAW PITCH \
            CLOCK_TICKS CLEAR_WEATHER_TICKS SIMULATION_FROZEN \
            ROUTE_STABLE_FRAMES ROUTE_TIMEOUT_FRAMES \
            POSITION_EPSILON ANGLE_EPSILON \
            ROUTE_KIND TORCH_POSITION_X TORCH_POSITION_Y TORCH_POSITION_Z \
            TORCH_INITIAL_BLOCK TORCH_SUPPORT_BLOCK \
            TORCH_APPLY_AFTER_MEASURED_FRAMES TORCH_OBSERVATION_FRAMES \
            <<< "$route_values"
        ;;
    28)
        IFS=$'\t' read -r \
            ROUTE_ID ROUTE_SHA256 FIXTURE_ID FIXTURE_SHA256 \
            PLAYER_NAME PLAYER_UUID DIMENSION \
            POSITION_X POSITION_Y POSITION_Z YAW PITCH \
            CLOCK_TICKS CLEAR_WEATHER_TICKS SIMULATION_FROZEN \
            ROUTE_STABLE_FRAMES ROUTE_TIMEOUT_FRAMES \
            POSITION_EPSILON ANGLE_EPSILON \
            ROUTE_KIND TORCH_POSITION_X TORCH_POSITION_Y TORCH_POSITION_Z \
            TORCH_INITIAL_BLOCK TORCH_SUPPORT_BLOCK \
            TORCH_APPLY_AFTER_MEASURED_FRAMES TORCH_OBSERVATION_FRAMES \
            TORCH_REMOVE_AFTER_MEASURED_FRAMES \
            <<< "$route_values"
        ;;
    *)
        die "route helper returned $route_field_count fields instead of 19, 27, or 28"
        ;;
esac

require_value "$PLAYER_NAME" "MetallumBench" "benchmark player name"
require_value "$PLAYER_UUID" "b07a402a-d8ea-354f-9398-aaf208a798b9" "benchmark player UUID"
require_value "$SIMULATION_FROZEN" "1" "benchmark simulation freeze"
case "$ROUTE_KIND" in
    STATIC)
        [ "$route_field_count" -eq 19 ] \
            || die "static route must use the schema-1 19-field contract"
        ;;
    TORCH_EPOCH)
        [ "$route_field_count" -eq 27 ] \
            || die "torch route must use the schema-2 27-field contract"
        require_value "$TORCH_INITIAL_BLOCK" "minecraft:air" "torch initial block"
        require_value "$TORCH_SUPPORT_BLOCK" "minecraft:grass_block" "torch support block"
        [ "$TORCH_APPLY_AFTER_MEASURED_FRAMES" -eq 300 ] \
            || die "torch epoch must start after exactly 300 measured frames"
        [ "$TORCH_OBSERVATION_FRAMES" -eq 300 ] \
            || die "torch epoch must observe exactly 300 frames"
        [ "$((TORCH_APPLY_AFTER_MEASURED_FRAMES + TORCH_OBSERVATION_FRAMES))" \
            -le "$MEASURE_FRAMES" ] \
            || die "torch epoch exceeds the measurement frame budget"
        ;;
    TORCH_TOGGLE)
        [ "$route_field_count" -eq 28 ] \
            || die "torch toggle route must use the schema-3 28-field contract"
        require_value "$TORCH_INITIAL_BLOCK" "minecraft:air" "torch initial block"
        require_value "$TORCH_SUPPORT_BLOCK" "minecraft:grass_block" "torch support block"
        [ "$TORCH_APPLY_AFTER_MEASURED_FRAMES" -eq 300 ] \
            || die "torch toggle must start after exactly 300 measured frames"
        [ "$TORCH_REMOVE_AFTER_MEASURED_FRAMES" -eq 450 ] \
            || die "torch toggle must remove after exactly 450 measured frames"
        [ "$TORCH_OBSERVATION_FRAMES" -eq 300 ] \
            || die "torch toggle epoch must observe exactly 300 frames"
        [ "$TORCH_APPLY_AFTER_MEASURED_FRAMES" \
            -lt "$TORCH_REMOVE_AFTER_MEASURED_FRAMES" ] \
            && [ "$TORCH_REMOVE_AFTER_MEASURED_FRAMES" \
            -lt "$((TORCH_APPLY_AFTER_MEASURED_FRAMES + TORCH_OBSERVATION_FRAMES))" ] \
            || die "torch removal must lie strictly inside the observation window"
        [ "$((TORCH_APPLY_AFTER_MEASURED_FRAMES + TORCH_OBSERVATION_FRAMES))" \
            -le "$MEASURE_FRAMES" ] \
            || die "torch toggle epoch exceeds the measurement frame budget"
        ;;
    *)
        die "unsupported route workload kind: $ROUTE_KIND"
        ;;
esac

FIXTURE_WORLD="$RUN_DIR/benchmark-fixtures/$FIXTURE_ID/world"
[ -f "$FIXTURE_WORLD/level.dat" ] \
    || die "immutable fixture is missing or invalid: $FIXTURE_WORLD"
FIXTURE_DIGEST_BEFORE=$(python3 "$FIXTURE_HELPER" verify-fixture \
    "$FIXTURE_WORLD" "$FIXTURE_SHA256") \
    || die "immutable fixture failed validation: $FIXTURE_WORLD"
require_value "$FIXTURE_DIGEST_BEFORE" "$FIXTURE_SHA256" "fixture digest"

OPTIONS_FILE="$RUN_DIR/options.txt"
HDR_CONFIG="$RUN_DIR/config/metallum-hdr.properties"
METALFX_CONFIG="$RUN_DIR/config/metallum-metalfx.properties"
SODIUM_OPTIONS="$RUN_DIR/config/sodium-options.json"
SODIUM_MIXINS="$RUN_DIR/config/sodium-mixins.properties"
RESOURCEPACKS_DIR="$RUN_DIR/resourcepacks"
FABRIC_DEFAULT_PACKS="$RUN_DIR/data/fabric_default_resource_packs.json"
[ -f "$OPTIONS_FILE" ] || die "missing Minecraft config: run/options.txt"
[ -f "$HDR_CONFIG" ] || die "missing HDR config"
[ -f "$METALFX_CONFIG" ] || die "missing MetalFX config"
[ -f "$SODIUM_OPTIONS" ] || die "missing Sodium options config"
[ -f "$SODIUM_MIXINS" ] || die "missing Sodium mixin config"
[ -d "$RESOURCEPACKS_DIR" ] || die "missing Minecraft resource-pack directory"
[ -f "$FABRIC_DEFAULT_PACKS" ] || die "missing Fabric default resource-pack config"
[ -d "$RUN_DIR/saves" ] || die "missing Minecraft saves directory: run/saves"

settings_values=$(python3 "$FIXTURE_HELPER" settings-values \
    "$SETTINGS_SPEC" "$OPTIONS_FILE" "$HDR_CONFIG" "$METALFX_CONFIG" \
    "$SODIUM_OPTIONS" "$SODIUM_MIXINS" "$RESOURCEPACKS_DIR" \
    "$FABRIC_DEFAULT_PACKS") \
    || die "runtime settings do not match the tracked benchmark contract"
settings_field_count=$(printf '%s\n' "$settings_values" | awk -F '\t' '{ print NF; exit }')
[ "$settings_field_count" -eq 27 ] \
    || die "settings helper returned $settings_field_count fields instead of 27"
IFS=$'\t' read -r \
    SETTINGS_ID SETTINGS_SPEC_SHA256 SETTINGS_SHA256 \
    RENDER_DISTANCE SIMULATION_DISTANCE GRAPHICS_PRESET \
    ENTITY_DISTANCE_SCALING PARTICLE_SETTING MIPMAP_LEVELS \
    BIOME_BLEND_RADIUS MAX_FPS AO_ENABLED CLOUDS_MODE CLOUD_RANGE \
    TEXTURE_FILTERING MAX_ANISOTROPY_BIT IMPROVED_TRANSPARENCY \
    CONFIGURED_GUI_SCALE RESOURCE_PACKS_SHA256 SODIUM_SETTINGS_SHA256 \
    ACTIVE_RESOURCE_PACK_IDS SODIUM_WORKER_THREADS HDR_MODE HDR_SOURCE_ENCODING \
    HDR_BLOOM_STRENGTH HDR_STRENGTH PERSISTENT_METALFX_MODE <<< "$settings_values"
SETTINGS_VALUES_BEFORE=$settings_values

require_value "$HDR_MODE" "scene" "HDR mode"
require_value "$HDR_SOURCE_ENCODING" "srgb" "HDR sourceEncoding"
require_value "$PERSISTENT_METALFX_MODE" "off" "persistent MetalFX mode"
case "$MAX_FPS" in
    ''|*[!0-9]*) die "maxFps must be an integer >= $MIN_MAX_FPS (found ${MAX_FPS:-<missing>})" ;;
esac
require_value "$MAX_FPS" "260" "maxFps"

mkdir -p "$OUTPUT_DIR"
git -C "$ROOT" check-ignore -q "$OUTPUT_DIR/.metallum-benchmark-probe" \
    || die "benchmark output directory must be ignored by git: $OUTPUT_DIR"
git -C "$ROOT" check-ignore -q "$FIXTURE_WORLD/.metallum-benchmark-probe" \
    || die "fixture directory must be ignored by git: $FIXTURE_WORLD"

commit=$(git -C "$ROOT" rev-parse --short=12 HEAD)
SOURCE_SHA256=$(python3 "$FIXTURE_HELPER" source-digest "$ROOT") \
    || die "failed to fingerprint the benchmark source tree"
worktree_state="clean"
dirty_flag=0
benchmark_status=$(git -C "$ROOT" status --porcelain=v1 --untracked-files=normal \
    | sed '/^?? net\/$/d')
if [ -n "$benchmark_status" ]; then
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
ACCEPTED_JSON="$OUTPUT_DIR/$stem.accepted.json"

echo "Metallum benchmark preflight passed"
echo "  display: $MONITOR_NAME, ${WIDTH}x${HEIGHT}@${REFRESH_HZ}, exclusive fullscreen"
echo "  pacing: VSync off, maxFps=$MAX_FPS"
echo "  scene: HDR scene/sRGB source, bloom=$HDR_BLOOM_STRENGTH, strength=$HDR_STRENGTH"
echo "  settings: $SETTINGS_ID ($SETTINGS_SHA256; spec $SETTINGS_SPEC_SHA256)"
echo "  workload: preset=$GRAPHICS_PRESET, render/simulation=${RENDER_DISTANCE}/${SIMULATION_DISTANCE}, entities=$ENTITY_DISTANCE_SCALING, particles=$PARTICLE_SETTING, mipmaps=$MIPMAP_LEVELS"
echo "  runtime contract: GUI scale=auto, Sodium workers=$SODIUM_WORKER_THREADS, packs=$ACTIVE_RESOURCE_PACK_IDS"
echo "  MetalFX: $METALFX_MODE (persistent config remains off)"
echo "  route: $ROUTE_ID ($ROUTE_SHA256)"
case "$ROUTE_KIND" in
    TORCH_EPOCH)
        echo "  torch epoch: position=[$TORCH_POSITION_X,$TORCH_POSITION_Y,$TORCH_POSITION_Z], initial=$TORCH_INITIAL_BLOCK, support=$TORCH_SUPPORT_BLOCK, apply=$TORCH_APPLY_AFTER_MEASURED_FRAMES, observe=$TORCH_OBSERVATION_FRAMES"
        ;;
    TORCH_TOGGLE)
        echo "  torch toggle epoch: position=[$TORCH_POSITION_X,$TORCH_POSITION_Y,$TORCH_POSITION_Z], initial=$TORCH_INITIAL_BLOCK, support=$TORCH_SUPPORT_BLOCK, apply=$TORCH_APPLY_AFTER_MEASURED_FRAMES, remove=$TORCH_REMOVE_AFTER_MEASURED_FRAMES, observe=$TORCH_OBSERVATION_FRAMES"
        ;;
esac
echo "  fixture: $FIXTURE_ID ($FIXTURE_SHA256, read-only)"
echo "  player: $PLAYER_NAME / $PLAYER_UUID"
echo "  pose: $DIMENSION [$POSITION_X, $POSITION_Y, $POSITION_Z] yaw=$YAW pitch=$PITCH"
echo "  clock/weather/simulation: tick=$CLOCK_TICKS paused, clear=$CLEAR_WEATHER_TICKS ticks, frozen"
echo "  frames: $WARMUP_FRAMES warmup + $MEASURE_FRAMES measurement"
echo "  commit: $commit ($worktree_state worktree state)"
echo "  source: $SOURCE_SHA256"
echo "  raw report: $RAW_REPORT"
if [ "$CAPTURE_REFERENCE" -eq 1 ]; then
    echo "  reference capture: enabled (performance result will not be attested)"
fi

if [ "$PREFLIGHT_ONLY" -eq 1 ]; then
    exit 0
fi

echo "Building the exact Java, resource, and native benchmark artifacts"
(
    cd "$ROOT"
    ./gradlew --no-daemon classes buildMacNative --console=plain
) || die "failed to build benchmark artifacts"
source_after_build=$(python3 "$FIXTURE_HELPER" source-digest "$ROOT") \
    || die "failed to recheck the source tree after the benchmark build"
require_value "$source_after_build" "$SOURCE_SHA256" \
    "source digest after the benchmark build"
ARTIFACT_SHA256=$(benchmark_artifact_digest) \
    || die "failed to fingerprint the built benchmark artifacts"
echo "  artifact: $ARTIFACT_SHA256"

cleanup() {
    local original_status=$1
    local cleanup_status=0
    local fixture_after=""
    local actual_token=""
    local actual_identity=""
    local run_parent=""
    local saves_parent=""
    local active_processes=""
    local source_after=""
    local settings_after=""
    local artifact_after=""

    trap - EXIT HUP INT TERM
    set +e

    if [ -n "${FIXTURE_DIGEST_BEFORE:-}" ]; then
        fixture_after=$(python3 "$FIXTURE_HELPER" verify-fixture \
            "$FIXTURE_WORLD" "$FIXTURE_SHA256")
        if [ "$?" -ne 0 ] || [ "$fixture_after" != "$FIXTURE_DIGEST_BEFORE" ]; then
            echo "ERROR: immutable benchmark fixture changed during the run" >&2
            cleanup_status=2
        fi
    fi

    source_after=$(python3 "$FIXTURE_HELPER" source-digest "$ROOT")
    if [ "$?" -ne 0 ] || [ "$source_after" != "${SOURCE_SHA256:-}" ]; then
        echo "ERROR: benchmark source tree changed during the run" >&2
        echo "  before: ${SOURCE_SHA256:-<unavailable>}" >&2
        echo "  after:  ${source_after:-<unavailable>}" >&2
        cleanup_status=2
    fi

    settings_after=$(python3 "$FIXTURE_HELPER" settings-values \
        "$SETTINGS_SPEC" "$OPTIONS_FILE" "$HDR_CONFIG" "$METALFX_CONFIG" \
        "$SODIUM_OPTIONS" "$SODIUM_MIXINS" "$RESOURCEPACKS_DIR" \
        "$FABRIC_DEFAULT_PACKS")
    if [ "$?" -ne 0 ] || [ "$settings_after" != "${SETTINGS_VALUES_BEFORE:-}" ]; then
        echo "ERROR: benchmark performance/quality settings changed during the run" >&2
        cleanup_status=2
    fi

    artifact_after=$(benchmark_artifact_digest)
    if [ "$?" -ne 0 ] || [ "$artifact_after" != "${ARTIFACT_SHA256:-}" ]; then
        echo "ERROR: built benchmark artifacts changed during the run" >&2
        echo "  before: ${ARTIFACT_SHA256:-<unavailable>}" >&2
        echo "  after:  ${artifact_after:-<unavailable>}" >&2
        cleanup_status=2
    fi

    if [ -n "${RUN_WORLD_PATH:-}" ] \
        && { [ -e "$RUN_WORLD_PATH" ] || [ -L "$RUN_WORLD_PATH" ]; }; then
        if [ ! -L "$RUN_WORLD_PATH" ]; then
            actual_identity=$(stat -f '%d:%i' "$RUN_WORLD_PATH" 2>/dev/null || true)
        fi
        if [ ! -L "$RUN_WORLD_PATH" ] \
            && [ -f "$RUN_WORLD_PATH/.metallum-benchmark-owner" ]; then
            IFS= read -r actual_token < "$RUN_WORLD_PATH/.metallum-benchmark-owner" || true
        fi
        run_parent=$(cd "$(dirname "$RUN_WORLD_PATH")" 2>/dev/null && pwd -P)
        saves_parent=$(cd "$RUN_DIR/saves" 2>/dev/null && pwd -P)

        case "${RUN_WORLD_PATH##*/}" in
            MetallumBenchmark-*) ;;
            *) run_parent="" ;;
        esac

        if [ -z "$run_parent" ] \
            || [ "$run_parent" != "$saves_parent" ] \
            || [ -L "$RUN_WORLD_PATH" ] \
            || [ -z "${RUN_WORLD_IDENTITY:-}" ] \
            || [ "$actual_identity" != "$RUN_WORLD_IDENTITY" ] \
            || [ -z "${RUN_WORLD_TOKEN:-}" ] \
            || [ "$actual_token" != "$RUN_WORLD_TOKEN" ]; then
            echo "ERROR: refusing unsafe temporary-world cleanup: $RUN_WORLD_PATH" >&2
            cleanup_status=2
        else
            active_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
            if [ -n "$active_processes" ]; then
                echo "ERROR: preserving temporary world because Minecraft/runClient is still active:" >&2
                echo "$active_processes" >&2
                echo "  world: $RUN_WORLD_PATH" >&2
                cleanup_status=2
            else
                chmod -R u+w "$RUN_WORLD_PATH" 2>/dev/null || true
                rm -rf "$RUN_WORLD_PATH"
                if [ -e "$RUN_WORLD_PATH" ]; then
                    echo "ERROR: failed to remove temporary benchmark world: $RUN_WORLD_PATH" >&2
                    cleanup_status=2
                fi
            fi
        fi
    fi

    if [ "$original_status" -eq 0 ] && [ "$cleanup_status" -ne 0 ]; then
        original_status=$cleanup_status
    fi
    if [ "$original_status" -eq 0 ] \
        && [ "$cleanup_status" -eq 0 ] \
        && [ "${ATTEST_PENDING:-0}" -eq 1 ]; then
        if python3 "$ANALYZER" attest \
            "$RAW_REPORT" "$SUMMARY_JSON" "$MINECRAFT_LOG" "$CONSOLE_LOG" "$ACCEPTED_JSON"; then
            echo "Benchmark accepted and attested: $ACCEPTED_JSON"
        else
            echo "ERROR: failed to create benchmark acceptance attestation" >&2
            original_status=2
        fi
    fi
    exit "$original_status"
}

RUN_WORLD_TOKEN=$(uuidgen)
RUN_WORLD_PATH=$(mktemp -d "$RUN_DIR/saves/MetallumBenchmark-${stamp}.XXXXXX") \
    || die "failed to allocate a temporary benchmark world"
RUN_WORLD_NAME=${RUN_WORLD_PATH##*/}
if ! RUN_WORLD_IDENTITY=$(stat -f '%d:%i' "$RUN_WORLD_PATH"); then
    rmdir "$RUN_WORLD_PATH" 2>/dev/null || true
    RUN_WORLD_PATH=""
    die "failed to record the temporary-world identity"
fi
if ! printf '%s\n' "$RUN_WORLD_TOKEN" > "$RUN_WORLD_PATH/.metallum-benchmark-owner"; then
    rmdir "$RUN_WORLD_PATH" 2>/dev/null || true
    RUN_WORLD_PATH=""
    die "failed to create the temporary-world ownership marker"
fi
trap 'cleanup $?' EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

git -C "$ROOT" check-ignore -q "$RUN_WORLD_PATH/.metallum-benchmark-probe" \
    || die "temporary benchmark worlds must be ignored by git: $RUN_WORLD_PATH"
cloned_digest=$(python3 "$FIXTURE_HELPER" clone-run \
    "$FIXTURE_WORLD" "$RUN_WORLD_PATH" "$FIXTURE_SHA256") \
    || die "failed to create a strict APFS CoW benchmark world"
require_value "$cloned_digest" "$FIXTURE_DIGEST_BEFORE" "temporary world digest"
chmod -R u+w "$RUN_WORLD_PATH" \
    || die "failed to make the temporary benchmark world writable"
echo "  temporary world: $RUN_WORLD_NAME (strict APFS CoW clone)"

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
echo "Running Minecraft benchmark quietly (live output would perturb frame pacing)"
echo "  console log: $CONSOLE_LOG"
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
METALLUM_BENCHMARK_SCREENSHOTS="$CAPTURE_REFERENCE" \
METALLUM_BENCHMARK_COMMIT="$commit" \
METALLUM_BENCHMARK_DIRTY="$dirty_flag" \
METALLUM_BENCHMARK_SOURCE_SHA256="$SOURCE_SHA256" \
METALLUM_BENCHMARK_ARTIFACT_SHA256="$ARTIFACT_SHA256" \
METALLUM_BENCHMARK_SETTINGS_ID="$SETTINGS_ID" \
METALLUM_BENCHMARK_SETTINGS_SPEC_SHA256="$SETTINGS_SPEC_SHA256" \
METALLUM_BENCHMARK_SETTINGS_SHA256="$SETTINGS_SHA256" \
METALLUM_BENCHMARK_RENDER_DISTANCE="$RENDER_DISTANCE" \
METALLUM_BENCHMARK_SIMULATION_DISTANCE="$SIMULATION_DISTANCE" \
METALLUM_BENCHMARK_GRAPHICS_PRESET="$GRAPHICS_PRESET" \
METALLUM_BENCHMARK_ENTITY_DISTANCE_SCALING="$ENTITY_DISTANCE_SCALING" \
METALLUM_BENCHMARK_PARTICLES="$PARTICLE_SETTING" \
METALLUM_BENCHMARK_MIPMAP_LEVELS="$MIPMAP_LEVELS" \
METALLUM_BENCHMARK_BIOME_BLEND_RADIUS="$BIOME_BLEND_RADIUS" \
METALLUM_BENCHMARK_MAX_FPS="$MAX_FPS" \
METALLUM_BENCHMARK_AO="$AO_ENABLED" \
METALLUM_BENCHMARK_CLOUDS_MODE="$CLOUDS_MODE" \
METALLUM_BENCHMARK_CLOUD_RANGE="$CLOUD_RANGE" \
METALLUM_BENCHMARK_TEXTURE_FILTERING="$TEXTURE_FILTERING" \
METALLUM_BENCHMARK_MAX_ANISOTROPY_BIT="$MAX_ANISOTROPY_BIT" \
METALLUM_BENCHMARK_IMPROVED_TRANSPARENCY="$IMPROVED_TRANSPARENCY" \
METALLUM_BENCHMARK_RESOURCE_PACKS_SHA256="$RESOURCE_PACKS_SHA256" \
METALLUM_BENCHMARK_SODIUM_SETTINGS_SHA256="$SODIUM_SETTINGS_SHA256" \
METALLUM_BENCHMARK_CONFIGURED_GUI_SCALE="$CONFIGURED_GUI_SCALE" \
METALLUM_BENCHMARK_ACTIVE_RESOURCE_PACKS="$ACTIVE_RESOURCE_PACK_IDS" \
METALLUM_BENCHMARK_SODIUM_WORKER_THREADS="$SODIUM_WORKER_THREADS" \
METALLUM_BENCHMARK_HDR_BLOOM_STRENGTH="$HDR_BLOOM_STRENGTH" \
METALLUM_BENCHMARK_HDR_STRENGTH="$HDR_STRENGTH" \
METALLUM_BENCHMARK_PERSISTENT_METALFX_MODE="$PERSISTENT_METALFX_MODE" \
METALLUM_BENCHMARK_WORLD="$FIXTURE_ID" \
METALLUM_BENCHMARK_ROUTE="$ROUTE_ID" \
METALLUM_BENCHMARK_ROUTE_ID="$ROUTE_ID" \
METALLUM_BENCHMARK_ROUTE_SHA256="$ROUTE_SHA256" \
METALLUM_BENCHMARK_FIXTURE_ID="$FIXTURE_ID" \
METALLUM_BENCHMARK_FIXTURE_SHA256="$FIXTURE_SHA256" \
METALLUM_BENCHMARK_PLAYER_NAME="$PLAYER_NAME" \
METALLUM_BENCHMARK_PLAYER_UUID="$PLAYER_UUID" \
METALLUM_BENCHMARK_DIMENSION="$DIMENSION" \
METALLUM_BENCHMARK_POSITION_X="$POSITION_X" \
METALLUM_BENCHMARK_POSITION_Y="$POSITION_Y" \
METALLUM_BENCHMARK_POSITION_Z="$POSITION_Z" \
METALLUM_BENCHMARK_YAW="$YAW" \
METALLUM_BENCHMARK_PITCH="$PITCH" \
METALLUM_BENCHMARK_CLOCK_TICKS="$CLOCK_TICKS" \
METALLUM_BENCHMARK_CLEAR_WEATHER_TICKS="$CLEAR_WEATHER_TICKS" \
METALLUM_BENCHMARK_SIMULATION_FROZEN="$SIMULATION_FROZEN" \
METALLUM_BENCHMARK_ROUTE_STABLE_FRAMES="$ROUTE_STABLE_FRAMES" \
METALLUM_BENCHMARK_ROUTE_TIMEOUT_FRAMES="$ROUTE_TIMEOUT_FRAMES" \
METALLUM_BENCHMARK_POSITION_EPSILON="$POSITION_EPSILON" \
METALLUM_BENCHMARK_ANGLE_EPSILON="$ANGLE_EPSILON" \
METALLUM_BENCHMARK_ROUTE_KIND="$ROUTE_KIND" \
METALLUM_BENCHMARK_TORCH_POSITION_X="$TORCH_POSITION_X" \
METALLUM_BENCHMARK_TORCH_POSITION_Y="$TORCH_POSITION_Y" \
METALLUM_BENCHMARK_TORCH_POSITION_Z="$TORCH_POSITION_Z" \
METALLUM_BENCHMARK_TORCH_INITIAL_BLOCK="$TORCH_INITIAL_BLOCK" \
METALLUM_BENCHMARK_TORCH_SUPPORT_BLOCK="$TORCH_SUPPORT_BLOCK" \
METALLUM_BENCHMARK_TORCH_APPLY_AFTER_MEASURED_FRAMES="$TORCH_APPLY_AFTER_MEASURED_FRAMES" \
METALLUM_BENCHMARK_TORCH_OBSERVATION_FRAMES="$TORCH_OBSERVATION_FRAMES" \
METALLUM_BENCHMARK_TORCH_REMOVE_AFTER_MEASURED_FRAMES="$TORCH_REMOVE_AFTER_MEASURED_FRAMES" \
METALLUM_GPU_TIMING=1 \
METALLUM_GPU_TIMING_DETAIL=0 \
METALLUM_GPU_TIMING_REPORT="$RAW_REPORT" \
    ./gradlew --no-daemon runClient --console=plain \
        "--args=--username $PLAYER_NAME --uuid $PLAYER_UUID --quickPlaySingleplayer $RUN_WORLD_NAME" \
        > "$CONSOLE_LOG" 2>&1
gradle_status=$?
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
screenshot_request_count=$(grep -Fc "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED" \
    "$MINECRAFT_LOG" || true)
if [ "$CAPTURE_REFERENCE" -eq 0 ]; then
    [ "$screenshot_request_count" -eq 0 ] || die "benchmark unexpectedly requested a screenshot"
else
    [ "$screenshot_request_count" -eq 1 ] \
        || die "reference run expected exactly one screenshot request (found $screenshot_request_count)"
fi
if grep -Eq '\[metallum\] (Metal command buffer failed|GPU timing sample invalid|Static geometry heap teardown exceeded Sodium cache bound|Static geometry buffer release was not registered)' "$CONSOLE_LOG"; then
    grep -E '\[metallum\] (Metal command buffer failed|GPU timing sample invalid|Static geometry heap teardown exceeded Sodium cache bound|Static geometry buffer release was not registered)' "$CONSOLE_LOG" >&2 || true
    die "Metal reported a command-buffer, timing, or static-geometry lifecycle failure"
fi
sodium_worker_markers=$(grep -E '\(ChunkBuilder\) Started [1-9][0-9]* worker threads$' \
    "$MINECRAFT_LOG" || true)
[ -n "$sodium_worker_markers" ] \
    || die "Minecraft log does not prove the resolved Sodium worker count"
unexpected_sodium_workers=$(printf '%s\n' "$sodium_worker_markers" \
    | grep -Ev "\\(ChunkBuilder\\) Started ${SODIUM_WORKER_THREADS} worker threads$" || true)
[ -z "$unexpected_sodium_workers" ] \
    || die "resolved Sodium worker count differs from $SODIUM_WORKER_THREADS:\n$unexpected_sodium_workers"

route_apply="METALLUM_BENCHMARK EVENT=ROUTE_APPLY route=$ROUTE_ID fixture=$FIXTURE_ID player=$PLAYER_NAME/$PLAYER_UUID dimension=$DIMENSION"
server_frozen="METALLUM_BENCHMARK EVENT=SERVER_TICKS_FROZEN"
route_ready="METALLUM_BENCHMARK EVENT=ROUTE_READY route=$ROUTE_ID stable_frames=$ROUTE_STABLE_FRAMES "
route_measure_start="METALLUM_BENCHMARK EVENT=ROUTE_CHECK event=MEASURE_START route=$ROUTE_ID status=ready"
route_measure_end="METALLUM_BENCHMARK EVENT=ROUTE_CHECK event=MEASURE_END route=$ROUTE_ID status=ready"
measure_start="METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=$METALFX_MODE presented_frame=$WARMUP_FRAMES"
final_presented=$((WARMUP_FRAMES + MEASURE_FRAMES))
measure_end="METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode=$METALFX_MODE presented_frame=$final_presented"

route_apply_count=$(grep -Fc "$route_apply" "$MINECRAFT_LOG" || true)
server_frozen_count=$(grep -Fc "$server_frozen" "$MINECRAFT_LOG" || true)
route_ready_count=$(grep -Fc "$route_ready" "$MINECRAFT_LOG" || true)
route_measure_start_count=$(grep -Fc "$route_measure_start" "$MINECRAFT_LOG" || true)
route_measure_end_count=$(grep -Fc "$route_measure_end" "$MINECRAFT_LOG" || true)
measure_start_count=$(grep -Fc "$measure_start" "$MINECRAFT_LOG" || true)
measure_end_count=$(grep -Fc "$measure_end" "$MINECRAFT_LOG" || true)
[ "$route_apply_count" -eq 1 ] \
    || die "expected exactly one matching ROUTE_APPLY marker (found $route_apply_count)"
[ "$server_frozen_count" -eq 1 ] \
    || die "expected exactly one server simulation freeze marker (found $server_frozen_count)"
[ "$route_ready_count" -eq 1 ] \
    || die "expected exactly one matching ROUTE_READY marker (found $route_ready_count)"
[ "$route_measure_start_count" -eq 1 ] \
    || die "expected exactly one ready ROUTE_CHECK for MEASURE_START (found $route_measure_start_count)"
[ "$route_measure_end_count" -eq 1 ] \
    || die "expected exactly one ready ROUTE_CHECK for MEASURE_END (found $route_measure_end_count)"
[ "$measure_start_count" -eq 1 ] \
    || die "expected exactly one matching MEASURE_START marker (found $measure_start_count)"
[ "$measure_end_count" -eq 1 ] \
    || die "expected exactly one matching MEASURE_END marker (found $measure_end_count)"

route_apply_line=$(grep -nF "$route_apply" "$MINECRAFT_LOG" | cut -d: -f1)
server_frozen_line=$(grep -nF "$server_frozen" "$MINECRAFT_LOG" | cut -d: -f1)
route_ready_line=$(grep -nF "$route_ready" "$MINECRAFT_LOG" | cut -d: -f1)
route_measure_start_line=$(grep -nF "$route_measure_start" "$MINECRAFT_LOG" | cut -d: -f1)
route_measure_end_line=$(grep -nF "$route_measure_end" "$MINECRAFT_LOG" | cut -d: -f1)
measure_start_line=$(grep -nF "$measure_start" "$MINECRAFT_LOG" | cut -d: -f1)
measure_end_line=$(grep -nF "$measure_end" "$MINECRAFT_LOG" | cut -d: -f1)
[ "$server_frozen_line" -lt "$route_apply_line" ] \
    && [ "$route_apply_line" -lt "$route_ready_line" ] \
    && [ "$route_ready_line" -lt "$route_measure_start_line" ] \
    && [ "$route_measure_start_line" -lt "$measure_start_line" ] \
    && [ "$measure_start_line" -lt "$measure_end_line" ] \
    && [ "$measure_end_line" -lt "$route_measure_end_line" ] \
    || die "deterministic route markers are out of order"

if [ "$ROUTE_KIND" = "TORCH_EPOCH" ] || [ "$ROUTE_KIND" = "TORCH_TOGGLE" ]; then
    torch_end_frame=$((TORCH_APPLY_AFTER_MEASURED_FRAMES + TORCH_OBSERVATION_FRAMES))
    torch_position="$TORCH_POSITION_X,$TORCH_POSITION_Y,$TORCH_POSITION_Z"
    torch_begin="METALLUM_BENCHMARK EVENT=TORCH_EPOCH_BEGIN route=$ROUTE_ID position=$torch_position measured_frame=$TORCH_APPLY_AFTER_MEASURED_FRAMES observation_frames=$TORCH_OBSERVATION_FRAMES"
    torch_applied="METALLUM_BENCHMARK EVENT=TORCH_EPOCH_APPLIED route=$ROUTE_ID position=$torch_position measured_frame="
    torch_end="METALLUM_BENCHMARK EVENT=TORCH_EPOCH_END route=$ROUTE_ID position=$torch_position measured_frame=$torch_end_frame"

    torch_begin_count=$(grep -Fc "$torch_begin" "$MINECRAFT_LOG" || true)
    torch_applied_count=$(grep -Fc "$torch_applied" "$MINECRAFT_LOG" || true)
    torch_end_count=$(grep -Fc "$torch_end" "$MINECRAFT_LOG" || true)
    [ "$torch_begin_count" -eq 1 ] \
        || die "expected exactly one matching TORCH_EPOCH_BEGIN marker (found $torch_begin_count)"
    [ "$torch_applied_count" -eq 1 ] \
        || die "expected exactly one matching TORCH_EPOCH_APPLIED marker (found $torch_applied_count)"
    [ "$torch_end_count" -eq 1 ] \
        || die "expected exactly one matching TORCH_EPOCH_END marker (found $torch_end_count)"

    torch_begin_line=$(grep -nF "$torch_begin" "$MINECRAFT_LOG" | cut -d: -f1)
    torch_applied_line=$(grep -nF "$torch_applied" "$MINECRAFT_LOG" | cut -d: -f1)
    torch_end_line=$(grep -nF "$torch_end" "$MINECRAFT_LOG" | cut -d: -f1)
    if [ "$ROUTE_KIND" = "TORCH_TOGGLE" ]; then
        torch_removed="METALLUM_BENCHMARK EVENT=TORCH_EPOCH_REMOVED route=$ROUTE_ID position=$torch_position measured_frame="
        torch_removed_count=$(grep -F "$torch_removed" "$MINECRAFT_LOG" \
            | grep -Fc " requested_frame=$TORCH_REMOVE_AFTER_MEASURED_FRAMES" || true)
        [ "$torch_removed_count" -eq 1 ] \
            || die "expected exactly one matching TORCH_EPOCH_REMOVED marker (found $torch_removed_count)"
        torch_removed_line=$(grep -nF "$torch_removed" "$MINECRAFT_LOG" \
            | grep -F " requested_frame=$TORCH_REMOVE_AFTER_MEASURED_FRAMES" \
            | cut -d: -f1)
        [ "$measure_start_line" -lt "$torch_begin_line" ] \
            && [ "$torch_begin_line" -lt "$torch_applied_line" ] \
            && [ "$torch_applied_line" -lt "$torch_removed_line" ] \
            && [ "$torch_removed_line" -lt "$torch_end_line" ] \
            && [ "$torch_end_line" -lt "$measure_end_line" ] \
            || die "deterministic torch toggle epoch markers are out of order"
    else
        unexpected_removed_count=$(grep -Fc \
            "METALLUM_BENCHMARK EVENT=TORCH_EPOCH_REMOVED route=$ROUTE_ID" \
            "$MINECRAFT_LOG" || true)
        [ "$unexpected_removed_count" -eq 0 ] \
            || die "schema-2 torch epoch unexpectedly emitted TORCH_EPOCH_REMOVED"
        [ "$measure_start_line" -lt "$torch_begin_line" ] \
            && [ "$torch_begin_line" -lt "$torch_applied_line" ] \
            && [ "$torch_applied_line" -lt "$torch_end_line" ] \
            && [ "$torch_end_line" -lt "$measure_end_line" ] \
            || die "deterministic torch epoch markers are out of order"
    fi
fi

armed="METALLUM_BENCHMARK EVENT=ARMED scope=$MONITOR_NAME target=${WIDTH}x${HEIGHT} warmup=$WARMUP_FRAMES measure=$MEASURE_FRAMES sequence=[$METALFX_MODE]"
grep -Fq "$armed" "$MINECRAFT_LOG" || die "benchmark ARMED marker does not match the requested contract"

window_ready=$(grep -F "METALLUM_BENCHMARK EVENT=WINDOW_READY monitor=$MONITOR_NAME" "$MINECRAFT_LOG" | tail -n 1 || true)
[ -n "$window_ready" ] || die "built-in display WINDOW_READY marker is missing"
case "$window_ready" in
    *"video_mode=${WIDTH}x${HEIGHT}@${REFRESH_HZ} "*"framebuffer=${WIDTH}x${HEIGHT} window=${WIDTH}x${HEIGHT} screen=${WIDTH}x${HEIGHT}"*) ;;
    *) die "WINDOW_READY did not prove exact fullscreen dimensions: $window_ready" ;;
esac

complete="METALLUM_BENCHMARK EVENT=COMPLETE segments=1 measured_frames=$MEASURE_FRAMES framebuffer=${WIDTH}x${HEIGHT}"
complete_count=$(grep -Fc "$complete" "$MINECRAFT_LOG" || true)
[ "$complete_count" -eq 1 ] || die "expected exactly one matching COMPLETE marker (found $complete_count)"
[ -s "$RAW_REPORT" ] || die "GPU timing JSONL report is missing or empty"

python3 "$ANALYZER" summarize "$RAW_REPORT" \
    --measure-frames "$MEASURE_FRAMES" \
    --segment 0 \
    --scaler-mode "$METALFX_MODE" \
    --release-contract \
    --source-sha256 "$SOURCE_SHA256" \
    --artifact-sha256 "$ARTIFACT_SHA256" \
    --settings-id "$SETTINGS_ID" \
    --settings-spec-sha256 "$SETTINGS_SPEC_SHA256" \
    --settings-sha256 "$SETTINGS_SHA256" \
    --world "$FIXTURE_ID" \
    --fixture "$FIXTURE_ID" \
    --fixture-sha256 "$FIXTURE_SHA256" \
    --route "$ROUTE_ID" \
    --route-sha256 "$ROUTE_SHA256" \
    --player-name "$PLAYER_NAME" \
    --player-uuid "$PLAYER_UUID" \
    --dimension "$DIMENSION" \
    --simulation-frozen \
    --json > "$SUMMARY_JSON"
python3 "$ANALYZER" summarize "$RAW_REPORT" \
    --measure-frames "$MEASURE_FRAMES" \
    --segment 0 \
    --scaler-mode "$METALFX_MODE" \
    --release-contract \
    --source-sha256 "$SOURCE_SHA256" \
    --artifact-sha256 "$ARTIFACT_SHA256" \
    --settings-id "$SETTINGS_ID" \
    --settings-spec-sha256 "$SETTINGS_SPEC_SHA256" \
    --settings-sha256 "$SETTINGS_SHA256" \
    --world "$FIXTURE_ID" \
    --fixture "$FIXTURE_ID" \
    --fixture-sha256 "$FIXTURE_SHA256" \
    --route "$ROUTE_ID" \
    --route-sha256 "$ROUTE_SHA256" \
    --player-name "$PLAYER_NAME" \
    --player-uuid "$PLAYER_UUID" \
    --dimension "$DIMENSION" \
    --simulation-frozen

remaining_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
[ -z "$remaining_processes" ] || die "benchmark returned but a Minecraft/runClient process remains:\n$remaining_processes"

if [ "$CAPTURE_REFERENCE" -eq 1 ]; then
    captured_screenshot=""
    captured_count=0
    for screenshot in "$RUN_DIR"/screenshots/*.png; do
        [ -f "$screenshot" ] || continue
        screenshot_mtime=$(stat -f %m "$screenshot")
        if [ "$screenshot_mtime" -ge "$start_epoch" ]; then
            captured_screenshot=$screenshot
            captured_count=$((captured_count + 1))
        fi
    done
    [ "$captured_count" -eq 1 ] \
        || die "reference run expected exactly one new PNG (found $captured_count)"
    mkdir -p "$REFERENCE_OUTPUT_DIR"
    reference_screenshot="$REFERENCE_OUTPUT_DIR/$stem.png"
    cp "$captured_screenshot" "$reference_screenshot"
    echo "Reference capture validated (not performance-attested): $reference_screenshot"
    echo "  sha256: $(shasum -a 256 "$reference_screenshot" | awk '{print $1}')"
else
    echo "Benchmark validated: COMPLETE present, no FAIL/screenshots, dropped timing events = 0"
fi
echo "  raw: $RAW_REPORT"
echo "  summary: $SUMMARY_JSON"
echo "  Minecraft log: $MINECRAFT_LOG"
echo "  console log: $CONSOLE_LOG"
ATTEST_PENDING=$((1 - CAPTURE_REFERENCE))
