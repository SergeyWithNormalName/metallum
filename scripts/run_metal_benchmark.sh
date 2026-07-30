#!/bin/bash
# Reproducible, console-only Metallum benchmark on the MacBook's built-in panel.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ANALYZER="$ROOT/tools/metal_benchmark_report.py"
FIXTURE_HELPER="$ROOT/tools/metal_benchmark_fixture.py"
RUN_DIR="$ROOT/run"
OUTPUT_DIR="$RUN_DIR/logs/metallum-benchmarks"
REFERENCE_OUTPUT_DIR=${METALLUM_L2_REFERENCE_OUTPUT_DIR:-"$RUN_DIR/lighting-reference/l0"}
DEFAULT_ROUTE_SPEC="benchmark/routes/hdrtest-static-v1.json"
DEFAULT_SETTINGS_SPEC="benchmark/settings/native-hdr-fancy-v1.json"
FI_SETTINGS_SPEC="benchmark/settings/fi-hdr-temporal-ultra-performance-v1.json"
ARTIFACT_CLASSES="build/classes/java/main"
ARTIFACT_RESOURCES="build/resources/main"
ARTIFACT_NATIVE="build/generated/metallum/natives/macos/libmetallum.dylib"

MONITOR_NAME="Built-in Retina Display"
WIDTH=3024
HEIGHT=1964
REFRESH_HZ=120
WARMUP_FRAMES=${METALLUM_L2_WARMUP_FRAMES:-1800}
MEASURE_FRAMES=${METALLUM_L2_MEASURE_FRAMES:-3000}
TIMING_DETAIL=${METALLUM_L2_TIMING_DETAIL:-0}
METAL_VALIDATION=${METALLUM_L2_METAL_VALIDATION:-0}
MIN_MAX_FPS=240

ROUTE_SPEC_ARGUMENT="$DEFAULT_ROUTE_SPEC"
SETTINGS_SPEC_ARGUMENT="$DEFAULT_SETTINGS_SPEC"
METALFX_MODE="OFF"
LIGHTING_PRESET="balanced"
LABEL="baseline"
PREFLIGHT_ONLY=0
CAPTURE_REFERENCE=0
FI_VALIDATION=0
SETTINGS_SPEC_EXPLICIT=0
METALFX_MODE_EXPLICIT=0

RUN_WORLD_PATH=""
RUN_WORLD_NAME=""
RUN_WORLD_TOKEN=""
RUN_WORLD_IDENTITY=""
FIXTURE_DIGEST_BEFORE=""
SETTINGS_VALUES_BEFORE=""
RENDERER_VALUES_BEFORE=""
ARTIFACT_SHA256=""
ATTEST_PENDING=0
OPTIONS_FILE_BACKUP=""
FI_SETTINGS_BACKUP_DIR=""
FI_ENABLED=false
FI_TEMPORAL_MODE=unchanged
FI_OVERLAY=false
FI_MINIMUM_GENERATED_PERCENT=0
FI_MINIMUM_GENERATED_FRAMES=0
FI_RENDERER_IMPROVED_LIGHTING=unchanged
FI_RENDERER_LIGHTING_PRESET=unchanged
EXPECTED_VSYNC=false

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
  --metalfx MODE     OFF, QUALITY, PERFORMANCE, TEMPORAL_QUALITY,
                     TEMPORAL_PERFORMANCE, or TEMPORAL_ULTRA_PERFORMANCE
                     (default: OFF)
  --lighting-preset PRESET
                     performance, balanced, or ultra (default: balanced)
  --label LABEL      short artifact label (default: baseline)
  --preflight-only   validate route/config/release settings contract/immutable fixture
                     without cloning
  --capture-reference capture one ignored screenshot; this run is not attested
  --fi-validation    run the opt-in HDR Temporal Ultra Performance + Frame Interpolation
                     validation profile; temporarily applies and then restores
                     options, HDR, renderer, MetalFX, and Temporal settings
                     byte-for-byte
  -h, --help         show this help

L2 diagnostic environment:
  METALLUM_L2_WARMUP_FRAMES / METALLUM_L2_MEASURE_FRAMES
      override the 300-frame-aligned run lengths
  METALLUM_L2_TIMING_DETAIL=1
      include per-stage timing for zero-work validation
  METALLUM_L2_METAL_VALIDATION=1
      retain caller-provided Metal API/shader validation variables
  METALLUM_L2_REFERENCE_OUTPUT_DIR=DIR
      place ignored L2 captures outside the default L0 reference directory

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
            METALFX_MODE_EXPLICIT=1
            shift 2
            ;;
        --lighting-preset)
            need_value "$@"
            LIGHTING_PRESET=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')
            shift 2
            ;;
        --settings)
            need_value "$@"
            SETTINGS_SPEC_ARGUMENT=$2
            SETTINGS_SPEC_EXPLICIT=1
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
        --fi-validation)
            FI_VALIDATION=1
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

if [ "$FI_VALIDATION" -eq 1 ]; then
    [ "$SETTINGS_SPEC_EXPLICIT" -eq 0 ] \
        || die "--fi-validation selects its own settings profile; do not combine it with --settings"
    [ "$METALFX_MODE_EXPLICIT" -eq 0 ] \
        || die "--fi-validation selects TEMPORAL_ULTRA_PERFORMANCE; do not combine it with --metalfx"
    SETTINGS_SPEC_ARGUMENT=$FI_SETTINGS_SPEC
    EXPECTED_VSYNC=true
fi

case "$METALFX_MODE" in
    OFF|QUALITY|PERFORMANCE|TEMPORAL_QUALITY|TEMPORAL_PERFORMANCE|TEMPORAL_ULTRA_PERFORMANCE) ;;
    *) die "--metalfx must be OFF, QUALITY, PERFORMANCE, TEMPORAL_QUALITY, TEMPORAL_PERFORMANCE, or TEMPORAL_ULTRA_PERFORMANCE" ;;
esac
case "$LIGHTING_PRESET" in
    performance|balanced|ultra) ;;
    *) die "--lighting-preset must be performance, balanced, or ultra" ;;
esac

case "$WARMUP_FRAMES:$MEASURE_FRAMES:$TIMING_DETAIL:$METAL_VALIDATION" in
    *[!0-9:]*|::*|:*:|*::* ) die "L2 frame/detail/validation overrides must be non-negative integers" ;;
esac
[ "$WARMUP_FRAMES" -ge 300 ] && [ $((WARMUP_FRAMES % 300)) -eq 0 ] \
    || die "METALLUM_L2_WARMUP_FRAMES must be a multiple of 300 and at least 300"
[ "$MEASURE_FRAMES" -ge 300 ] && [ $((MEASURE_FRAMES % 300)) -eq 0 ] \
    || die "METALLUM_L2_MEASURE_FRAMES must be a multiple of 300 and at least 300"
case "$TIMING_DETAIL" in 0|1) ;; *) die "METALLUM_L2_TIMING_DETAIL must be 0 or 1" ;; esac
case "$METAL_VALIDATION" in 0|1) ;; *) die "METALLUM_L2_METAL_VALIDATION must be 0 or 1" ;; esac

command -v python3 >/dev/null 2>&1 || die "python3 is required for report validation"
command -v pgrep >/dev/null 2>&1 || die "pgrep is required for process isolation"
command -v mktemp >/dev/null 2>&1 || die "mktemp is required for isolated benchmark worlds"
command -v uuidgen >/dev/null 2>&1 || die "uuidgen is required for isolated benchmark worlds"
if [ "$FI_VALIDATION" -eq 1 ]; then
    command -v cmp >/dev/null 2>&1 \
        || die "cmp is required to verify FI runtime-settings restoration"
fi
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

discard_fi_settings_backup() {
    [ -n "${FI_SETTINGS_BACKUP_DIR:-}" ] || return 0
    rm -f "$FI_SETTINGS_BACKUP_DIR/options.txt" \
        "$FI_SETTINGS_BACKUP_DIR/metallum-hdr.properties" \
        "$FI_SETTINGS_BACKUP_DIR/metallum-renderer.properties" \
        "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx.properties" \
        "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx-temporal.properties"
    rmdir "$FI_SETTINGS_BACKUP_DIR" 2>/dev/null || return 1
    FI_SETTINGS_BACKUP_DIR=""
}

restore_fi_runtime_settings() {
    local retain_backup=${1:-0}
    local restore_status=0

    [ "${FI_VALIDATION:-0}" -eq 1 ] || return 0
    if [ -z "${FI_SETTINGS_BACKUP_DIR:-}" ] \
        || [ ! -d "$FI_SETTINGS_BACKUP_DIR" ]; then
        echo "ERROR: FI runtime-settings backup is unavailable; restoration cannot be proven" >&2
        return 1
    fi

    cp "$FI_SETTINGS_BACKUP_DIR/options.txt" "$OPTIONS_FILE" || restore_status=1
    cp "$FI_SETTINGS_BACKUP_DIR/metallum-hdr.properties" \
        "$HDR_CONFIG" || restore_status=1
    cp "$FI_SETTINGS_BACKUP_DIR/metallum-renderer.properties" \
        "$RENDERER_CONFIG" || restore_status=1
    cp "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx.properties" \
        "$METALFX_CONFIG" || restore_status=1
    cp "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx-temporal.properties" \
        "$TEMPORAL_CONFIG" || restore_status=1

    cmp -s "$FI_SETTINGS_BACKUP_DIR/options.txt" "$OPTIONS_FILE" \
        || restore_status=1
    cmp -s "$FI_SETTINGS_BACKUP_DIR/metallum-hdr.properties" \
        "$HDR_CONFIG" || restore_status=1
    cmp -s "$FI_SETTINGS_BACKUP_DIR/metallum-renderer.properties" \
        "$RENDERER_CONFIG" || restore_status=1
    cmp -s "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx.properties" \
        "$METALFX_CONFIG" || restore_status=1
    cmp -s "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx-temporal.properties" \
        "$TEMPORAL_CONFIG" || restore_status=1

    if [ "$restore_status" -ne 0 ]; then
        echo "ERROR: failed to restore and byte-verify FI runtime settings" >&2
        echo "  recovery backup preserved at: $FI_SETTINGS_BACKUP_DIR" >&2
        return 1
    fi

    if [ "$retain_backup" -eq 0 ]; then
        discard_fi_settings_backup || {
            echo "ERROR: FI settings were restored, but backup cleanup failed: $FI_SETTINGS_BACKUP_DIR" >&2
            return 1
        }
        echo "FI runtime settings restored and byte-verified"
    else
        echo "FI runtime settings restored and byte-verified; recovery backup retained until process teardown"
    fi
}

early_cleanup() {
    local original_status=$1
    local cleanup_status=0

    trap - EXIT HUP INT TERM
    set +e
    restore_fi_runtime_settings || cleanup_status=2
    if [ -n "${OPTIONS_FILE_BACKUP:-}" ]; then
        rm -f "$OPTIONS_FILE_BACKUP" || cleanup_status=2
    fi
    if [ "$original_status" -eq 0 ] && [ "$cleanup_status" -ne 0 ]; then
        original_status=$cleanup_status
    fi
    exit "$original_status"
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
L6_HELD_ITEM="minecraft:air"
L6_ORBIT_RADIUS=0
L6_ORBIT_YAW_AMPLITUDE_DEGREES=0
L6_ORBIT_PITCH_AMPLITUDE_DEGREES=0
L6_ORBIT_PERIOD_FRAMES=0
L6_PROBE_COUNT=0
L6_PROBE_ORIGIN_X=0
L6_PROBE_ORIGIN_Y=0
L6_PROBE_ORIGIN_Z=0
L6_PROBE_RADIUS=0
L6_PROBE_VERTICAL_AMPLITUDE=0
L6_PROBE_PERIOD_FRAMES=0
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
    32)
        IFS=$'\t' read -r \
            ROUTE_ID ROUTE_SHA256 FIXTURE_ID FIXTURE_SHA256 \
            PLAYER_NAME PLAYER_UUID DIMENSION \
            POSITION_X POSITION_Y POSITION_Z YAW PITCH \
            CLOCK_TICKS CLEAR_WEATHER_TICKS SIMULATION_FROZEN \
            ROUTE_STABLE_FRAMES ROUTE_TIMEOUT_FRAMES \
            POSITION_EPSILON ANGLE_EPSILON \
            ROUTE_KIND L6_HELD_ITEM \
            L6_ORBIT_RADIUS L6_ORBIT_YAW_AMPLITUDE_DEGREES \
            L6_ORBIT_PITCH_AMPLITUDE_DEGREES L6_ORBIT_PERIOD_FRAMES \
            L6_PROBE_COUNT L6_PROBE_ORIGIN_X L6_PROBE_ORIGIN_Y L6_PROBE_ORIGIN_Z \
            L6_PROBE_RADIUS L6_PROBE_VERTICAL_AMPLITUDE L6_PROBE_PERIOD_FRAMES \
            <<< "$route_values"
        ;;
    *)
        die "route helper returned $route_field_count fields instead of 19, 27, 28, or 32"
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
    L6_DYNAMIC_SHADOW)
        [ "$route_field_count" -eq 32 ] \
            || die "L6 dynamic-shadow route must use the schema-4 32-field contract"
        require_value "$L6_HELD_ITEM" "minecraft:torch" "L6 held item"
        [ "$L6_PROBE_COUNT" -eq 4 ] \
            || die "L6 route must provide exactly four dynamic entity probes"
        [ "$L6_ORBIT_PERIOD_FRAMES" -ge 60 ] \
            && [ $((L6_ORBIT_PERIOD_FRAMES % 60)) -eq 0 ] \
            || die "L6 camera orbit period must be a positive 60-frame multiple"
        [ "$L6_PROBE_PERIOD_FRAMES" -ge 60 ] \
            && [ $((L6_PROBE_PERIOD_FRAMES % 60)) -eq 0 ] \
            || die "L6 probe period must be a positive 60-frame multiple"
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
RENDERER_CONFIG="$RUN_DIR/config/metallum-renderer.properties"
METALFX_CONFIG="$RUN_DIR/config/metallum-metalfx.properties"
TEMPORAL_CONFIG="$RUN_DIR/config/metallum-metalfx-temporal.properties"
SODIUM_OPTIONS="$RUN_DIR/config/sodium-options.json"
SODIUM_MIXINS="$RUN_DIR/config/sodium-mixins.properties"
RESOURCEPACKS_DIR="$RUN_DIR/resourcepacks"
FABRIC_DEFAULT_PACKS="$RUN_DIR/data/fabric_default_resource_packs.json"
[ -f "$OPTIONS_FILE" ] || die "missing Minecraft config: run/options.txt"
[ -f "$HDR_CONFIG" ] || die "missing HDR config"
[ -f "$RENDERER_CONFIG" ] || die "missing renderer config"
[ -f "$METALFX_CONFIG" ] || die "missing MetalFX config"
[ -f "$SODIUM_OPTIONS" ] || die "missing Sodium options config"
[ -f "$SODIUM_MIXINS" ] || die "missing Sodium mixin config"
[ -d "$RESOURCEPACKS_DIR" ] || die "missing Minecraft resource-pack directory"
[ -f "$FABRIC_DEFAULT_PACKS" ] || die "missing Fabric default resource-pack config"
[ -d "$RUN_DIR/saves" ] || die "missing Minecraft saves directory: run/saves"

if [ "$FI_VALIDATION" -eq 1 ]; then
    [ -f "$TEMPORAL_CONFIG" ] || die "missing MetalFX temporal config"
    FI_SETTINGS_BACKUP_DIR=$(mktemp -d \
        "${TMPDIR:-/tmp}/metallum-fi-settings.XXXXXX") \
        || die "failed to allocate FI runtime-settings backup"
    if ! cp "$OPTIONS_FILE" "$FI_SETTINGS_BACKUP_DIR/options.txt" \
        || ! cp "$HDR_CONFIG" \
            "$FI_SETTINGS_BACKUP_DIR/metallum-hdr.properties" \
        || ! cp "$RENDERER_CONFIG" \
            "$FI_SETTINGS_BACKUP_DIR/metallum-renderer.properties" \
        || ! cp "$METALFX_CONFIG" \
            "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx.properties" \
        || ! cp "$TEMPORAL_CONFIG" \
            "$FI_SETTINGS_BACKUP_DIR/metallum-metalfx-temporal.properties"; then
        discard_fi_settings_backup || true
        die "failed to back up FI runtime settings"
    fi

    # Install restoration before the first mutation so preflight failures and
    # HUP/INT/TERM all put the user's ignored runtime files back exactly.
    trap 'early_cleanup $?' EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
    python3 "$FIXTURE_HELPER" apply-runtime-settings \
        "$SETTINGS_SPEC" "$OPTIONS_FILE" "$HDR_CONFIG" \
        "$METALFX_CONFIG" "$RENDERER_CONFIG" "$TEMPORAL_CONFIG" \
        || die "failed to apply the FI validation runtime settings"
fi

settings_values=$(python3 "$FIXTURE_HELPER" settings-values \
    "$SETTINGS_SPEC" "$OPTIONS_FILE" "$HDR_CONFIG" "$METALFX_CONFIG" \
    "$SODIUM_OPTIONS" "$SODIUM_MIXINS" "$RESOURCEPACKS_DIR" \
    "$FABRIC_DEFAULT_PACKS" "$RENDERER_CONFIG" "$TEMPORAL_CONFIG") \
    || die "runtime settings do not match the tracked benchmark contract"
settings_field_count=$(printf '%s\n' "$settings_values" | awk -F '\t' '{ print NF; exit }')
case "$settings_field_count" in
    27)
        [ "$FI_VALIDATION" -eq 0 ] \
            || die "FI settings helper returned the legacy 27-field contract"
        IFS=$'\t' read -r \
            SETTINGS_ID SETTINGS_SPEC_SHA256 SETTINGS_SHA256 \
            RENDER_DISTANCE SIMULATION_DISTANCE GRAPHICS_PRESET \
            ENTITY_DISTANCE_SCALING PARTICLE_SETTING MIPMAP_LEVELS \
            BIOME_BLEND_RADIUS MAX_FPS AO_ENABLED CLOUDS_MODE CLOUD_RANGE \
            TEXTURE_FILTERING MAX_ANISOTROPY_BIT IMPROVED_TRANSPARENCY \
            CONFIGURED_GUI_SCALE RESOURCE_PACKS_SHA256 SODIUM_SETTINGS_SHA256 \
            ACTIVE_RESOURCE_PACK_IDS SODIUM_WORKER_THREADS HDR_MODE HDR_SOURCE_ENCODING \
            HDR_BLOOM_STRENGTH HDR_STRENGTH PERSISTENT_METALFX_MODE <<< "$settings_values"
        ;;
    31)
        [ "$FI_VALIDATION" -eq 0 ] \
            || die "FI settings helper returned the old 31-field contract"
        IFS=$'\t' read -r \
            SETTINGS_ID SETTINGS_SPEC_SHA256 SETTINGS_SHA256 \
            RENDER_DISTANCE SIMULATION_DISTANCE GRAPHICS_PRESET \
            ENTITY_DISTANCE_SCALING PARTICLE_SETTING MIPMAP_LEVELS \
            BIOME_BLEND_RADIUS MAX_FPS AO_ENABLED CLOUDS_MODE CLOUD_RANGE \
            TEXTURE_FILTERING MAX_ANISOTROPY_BIT IMPROVED_TRANSPARENCY \
            CONFIGURED_GUI_SCALE RESOURCE_PACKS_SHA256 SODIUM_SETTINGS_SHA256 \
            ACTIVE_RESOURCE_PACK_IDS SODIUM_WORKER_THREADS HDR_MODE HDR_SOURCE_ENCODING \
            HDR_BLOOM_STRENGTH HDR_STRENGTH PERSISTENT_METALFX_MODE \
            FI_ENABLED FI_TEMPORAL_MODE FI_OVERLAY \
            FI_MINIMUM_GENERATED_PERCENT <<< "$settings_values"
        ;;
    33)
        [ "$FI_VALIDATION" -eq 1 ] \
            || die "schema-v2 FI settings require --fi-validation"
        IFS=$'\t' read -r \
            SETTINGS_ID SETTINGS_SPEC_SHA256 SETTINGS_SHA256 \
            RENDER_DISTANCE SIMULATION_DISTANCE GRAPHICS_PRESET \
            ENTITY_DISTANCE_SCALING PARTICLE_SETTING MIPMAP_LEVELS \
            BIOME_BLEND_RADIUS MAX_FPS AO_ENABLED CLOUDS_MODE CLOUD_RANGE \
            TEXTURE_FILTERING MAX_ANISOTROPY_BIT IMPROVED_TRANSPARENCY \
            CONFIGURED_GUI_SCALE RESOURCE_PACKS_SHA256 SODIUM_SETTINGS_SHA256 \
            ACTIVE_RESOURCE_PACK_IDS SODIUM_WORKER_THREADS HDR_MODE HDR_SOURCE_ENCODING \
            HDR_BLOOM_STRENGTH HDR_STRENGTH PERSISTENT_METALFX_MODE \
            FI_ENABLED FI_TEMPORAL_MODE FI_OVERLAY \
            FI_MINIMUM_GENERATED_PERCENT FI_RENDERER_IMPROVED_LIGHTING \
            FI_RENDERER_LIGHTING_PRESET <<< "$settings_values"
        ;;
    *)
        die "settings helper returned $settings_field_count fields instead of 27, 31, or 33"
        ;;
esac
SETTINGS_VALUES_BEFORE=$settings_values

case "$HDR_MODE" in
    off|scene) ;;
    *) die "HDR mode must be off or scene for the L2 matrix (found ${HDR_MODE:-<missing>})" ;;
esac
require_value "$HDR_SOURCE_ENCODING" "srgb" "HDR sourceEncoding"
require_value "$PERSISTENT_METALFX_MODE" "off" "persistent MetalFX mode"
if [ "$TIMING_DETAIL" -eq 0 ] \
    && [ "$METAL_VALIDATION" -eq 0 ] \
    && [ "$CAPTURE_REFERENCE" -eq 0 ] \
    && [ "$FI_VALIDATION" -eq 0 ]; then
    python3 "$ANALYZER" release-settings-contract "$SETTINGS_ID" \
        --hdr-mode "$HDR_MODE" \
        --configured-source-encoding "$HDR_SOURCE_ENCODING" \
        || die "release settings contract is incompatible with this benchmark profile"
fi
case "$MAX_FPS" in
    ''|*[!0-9]*) die "maxFps must be an integer (found ${MAX_FPS:-<missing>})" ;;
esac
if [ "$FI_VALIDATION" -eq 1 ]; then
    require_value "$MAX_FPS" "30" "FI validation maxFps"
    require_value "$FI_ENABLED" "true" "FI validation frame-interpolation setting"
    require_value "$FI_TEMPORAL_MODE" "ultra_performance" "FI validation temporal mode"
    case "$FI_TEMPORAL_MODE" in
        quality) METALFX_MODE=TEMPORAL_QUALITY ;;
        performance) METALFX_MODE=TEMPORAL_PERFORMANCE ;;
        ultra_performance) METALFX_MODE=TEMPORAL_ULTRA_PERFORMANCE ;;
        *) die "unsupported FI validation temporal mode: $FI_TEMPORAL_MODE" ;;
    esac
    require_value "$FI_OVERLAY" "true" "FI validation overlay setting"
    require_value "$FI_RENDERER_IMPROVED_LIGHTING" "false" \
        "FI validation renderer improvedLighting"
    require_value "$FI_RENDERER_LIGHTING_PRESET" "balanced" \
        "FI validation renderer lightingPreset"
    require_value "$LIGHTING_PRESET" "$FI_RENDERER_LIGHTING_PRESET" \
        "FI validation lighting preset argument"
    case "$FI_MINIMUM_GENERATED_PERCENT" in
        ''|*[!0-9]*|0) die "FI minimum generated percent must be an integer from 1 to 100" ;;
    esac
    [ "$FI_MINIMUM_GENERATED_PERCENT" -le 100 ] \
        || die "FI minimum generated percent must be an integer from 1 to 100"
    FI_MINIMUM_GENERATED_FRAMES=$((
        (MEASURE_FRAMES * FI_MINIMUM_GENERATED_PERCENT + 99) / 100
    ))
    actual_vsync=$(awk -F: '$1 == "enableVsync" { print $2 }' "$OPTIONS_FILE")
    require_value "$actual_vsync" "true" "FI validation VSync"
else
    [ "$MAX_FPS" -ge "$MIN_MAX_FPS" ] \
        || die "maxFps must be an integer >= $MIN_MAX_FPS (found $MAX_FPS)"
    require_value "$MAX_FPS" "260" "maxFps"
fi

renderer_value() {
    local key=$1
    awk -F= -v key="$key" '$1 == key { print $2 }' "$RENDERER_CONFIG"
}
RENDERER_LIGHTING=$(renderer_value improvedLighting)
RENDERER_PRESET=$(renderer_value lightingPreset)
RENDERER_INTERPOLATION=$(renderer_value frameInterpolation)
RENDERER_VOXEL_DEBUG=$(renderer_value voxelDebugChecksum)
[ -n "$RENDERER_VOXEL_DEBUG" ] || RENDERER_VOXEL_DEBUG=false
case "$RENDERER_LIGHTING" in true|false) ;; *) die "renderer improvedLighting must be true or false" ;; esac
require_value "$RENDERER_PRESET" "$LIGHTING_PRESET" "renderer lightingPreset"
if [ "$FI_VALIDATION" -eq 1 ]; then
    require_value "$RENDERER_LIGHTING" "$FI_RENDERER_IMPROVED_LIGHTING" \
        "FI validation renderer improvedLighting"
    require_value "$RENDERER_PRESET" "$FI_RENDERER_LIGHTING_PRESET" \
        "FI validation renderer lightingPreset"
    require_value "$RENDERER_INTERPOLATION" "true" "renderer frameInterpolation"
else
    require_value "$RENDERER_INTERPOLATION" "false" "renderer frameInterpolation"
fi
require_value "$RENDERER_VOXEL_DEBUG" "false" "renderer voxelDebugChecksum"
if [ "$ROUTE_KIND" = "L6_DYNAMIC_SHADOW" ]; then
    require_value "$RENDERER_LIGHTING" "true" "L6 dynamic route renderer improvedLighting"
fi
RENDERER_VALUES_BEFORE="$RENDERER_LIGHTING/$RENDERER_PRESET/$RENDERER_INTERPOLATION/$RENDERER_VOXEL_DEBUG"

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
if [ "$FI_VALIDATION" -eq 1 ]; then
    echo "  pacing: VSync on, maxFps=$MAX_FPS"
else
    echo "  pacing: VSync off, maxFps=$MAX_FPS"
fi
echo "  scene: output=$HDR_MODE, source=sRGB, lighting=$RENDERER_LIGHTING/$LIGHTING_PRESET, bloom=$HDR_BLOOM_STRENGTH, strength=$HDR_STRENGTH"
echo "  settings: $SETTINGS_ID ($SETTINGS_SHA256; spec $SETTINGS_SPEC_SHA256)"
echo "  workload: preset=$GRAPHICS_PRESET, render/simulation=${RENDER_DISTANCE}/${SIMULATION_DISTANCE}, entities=$ENTITY_DISTANCE_SCALING, particles=$PARTICLE_SETTING, mipmaps=$MIPMAP_LEVELS"
echo "  runtime contract: GUI scale=auto, Sodium workers=$SODIUM_WORKER_THREADS, packs=$ACTIVE_RESOURCE_PACK_IDS"
echo "  MetalFX: $METALFX_MODE (persistent config remains off)"
if [ "$FI_VALIDATION" -eq 1 ]; then
    echo "  Frame Interpolation: required, overlay=$FI_OVERLAY, minimum generated=$FI_MINIMUM_GENERATED_FRAMES/$MEASURE_FRAMES ($FI_MINIMUM_GENERATED_PERCENT%)"
fi
echo "  route: $ROUTE_ID ($ROUTE_SHA256)"
case "$ROUTE_KIND" in
    TORCH_EPOCH)
        echo "  torch epoch: position=[$TORCH_POSITION_X,$TORCH_POSITION_Y,$TORCH_POSITION_Z], initial=$TORCH_INITIAL_BLOCK, support=$TORCH_SUPPORT_BLOCK, apply=$TORCH_APPLY_AFTER_MEASURED_FRAMES, observe=$TORCH_OBSERVATION_FRAMES"
        ;;
    TORCH_TOGGLE)
        echo "  torch toggle epoch: position=[$TORCH_POSITION_X,$TORCH_POSITION_Y,$TORCH_POSITION_Z], initial=$TORCH_INITIAL_BLOCK, support=$TORCH_SUPPORT_BLOCK, apply=$TORCH_APPLY_AFTER_MEASURED_FRAMES, remove=$TORCH_REMOVE_AFTER_MEASURED_FRAMES, observe=$TORCH_OBSERVATION_FRAMES"
        ;;
    L6_DYNAMIC_SHADOW)
        echo "  L6 dynamic shadow: held=$L6_HELD_ITEM, camera orbit radius=$L6_ORBIT_RADIUS period=$L6_ORBIT_PERIOD_FRAMES, probes=$L6_PROBE_COUNT origin=[$L6_PROBE_ORIGIN_X,$L6_PROBE_ORIGIN_Y,$L6_PROBE_ORIGIN_Z] period=$L6_PROBE_PERIOD_FRAMES"
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

OPTIONS_FILE_BACKUP=$(mktemp "${TMPDIR:-/tmp}/metallum-benchmark-options.XXXXXX") \
    || die "failed to create benchmark options backup"
cp "$OPTIONS_FILE" "$OPTIONS_FILE_BACKUP" \
    || die "failed to back up benchmark options"

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
    local fi_process_quiescent=1
    local fixture_after=""
    local actual_token=""
    local actual_identity=""
    local run_parent=""
    local saves_parent=""
    local active_processes=""
    local source_after=""
    local settings_after=""
    local renderer_after=""
    local artifact_after=""

    trap - EXIT
    # A second supervisor signal must not interrupt restoration. The original
    # status is preserved and returned after teardown finishes.
    trap '' HUP INT TERM
    set +e

    # Restore before any slow digest validation. Keep the recovery copy until
    # every client process is gone, then restore+verify once more before it is
    # discarded so a late options.txt write cannot win the teardown race.
    if [ "${FI_VALIDATION:-0}" -eq 1 ]; then
        restore_fi_runtime_settings 1 || cleanup_status=2
        for _attempt in $(seq 1 100); do
            active_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
            [ -n "$active_processes" ] || break
            sleep 0.1
        done
        active_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
        if [ -n "$active_processes" ]; then
            fi_process_quiescent=0
            echo "ERROR: preserving the FI recovery backup because a client process remains:" >&2
            echo "$active_processes" >&2
            echo "  recovery backup: $FI_SETTINGS_BACKUP_DIR" >&2
            cleanup_status=2
        fi
    fi

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

    # Vanilla resets this ignored launch preference on orderly fullscreen exit.
    # Restore the exact launcher snapshot before validating benchmark quality
    # settings; all other tracked runtime contracts remain independently checked.
    if [ "${FI_VALIDATION:-0}" -eq 0 ] \
        && [ -n "${OPTIONS_FILE_BACKUP:-}" ] \
        && [ -f "$OPTIONS_FILE_BACKUP" ]; then
        cp "$OPTIONS_FILE_BACKUP" "$OPTIONS_FILE" || cleanup_status=2
    fi

    if [ "${FI_VALIDATION:-0}" -eq 0 ]; then
        settings_after=$(python3 "$FIXTURE_HELPER" settings-values \
            "$SETTINGS_SPEC" "$OPTIONS_FILE" "$HDR_CONFIG" "$METALFX_CONFIG" \
            "$SODIUM_OPTIONS" "$SODIUM_MIXINS" "$RESOURCEPACKS_DIR" \
            "$FABRIC_DEFAULT_PACKS" "$RENDERER_CONFIG" "$TEMPORAL_CONFIG")
        if [ "$?" -ne 0 ] || [ "$settings_after" != "${SETTINGS_VALUES_BEFORE:-}" ]; then
            echo "ERROR: benchmark performance/quality settings changed during the run" >&2
            cleanup_status=2
        fi

        renderer_debug_after=$(renderer_value voxelDebugChecksum)
        [ -n "$renderer_debug_after" ] || renderer_debug_after=false
        renderer_after="$(renderer_value improvedLighting)/$(renderer_value lightingPreset)/$(renderer_value frameInterpolation)/$renderer_debug_after"
        if [ "$renderer_after" != "${RENDERER_VALUES_BEFORE:-}" ]; then
            echo "ERROR: renderer generation settings changed during the run" >&2
            cleanup_status=2
        fi
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

    if [ "${FI_VALIDATION:-0}" -eq 1 ]; then
        active_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
        if [ -n "$active_processes" ]; then
            fi_process_quiescent=0
            cleanup_status=2
        fi
        if [ "$fi_process_quiescent" -eq 1 ]; then
            restore_fi_runtime_settings || cleanup_status=2
        else
            # Re-copy the original bytes, but retain the only recovery copy
            # because the still-live client may write its options again.
            restore_fi_runtime_settings 1 || cleanup_status=2
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
    [ -z "${OPTIONS_FILE_BACKUP:-}" ] || rm -f "$OPTIONS_FILE_BACKUP"
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
if [ "$METAL_VALIDATION" -eq 0 ]; then
    unset MTL_DEBUG_LAYER
    unset MTL_SHADER_VALIDATION
    unset MTL_SHADER_VALIDATION_REPORT_TO_STDERR
fi
unset MTL_CAPTURE_ENABLED
unset MTL_HUD_ENABLED
unset MTL_HUD_LOG_ENABLED
unset METAL_DEVICE_WRAPPER_TYPE

start_epoch=$(date +%s)
cd "$ROOT"
echo "Running Minecraft benchmark quietly (live output would perturb frame pacing)"
echo "  console log: $CONSOLE_LOG"

FI_REQUIRED_ENV=0
FI_OVERLAY_ENV=false
GPU_TIMING_ENV=1
if [ "$FI_VALIDATION" -eq 1 ]; then
    FI_REQUIRED_ENV=1
    FI_OVERLAY_ENV=$FI_OVERLAY
    # This is an on-glass functional gate, not a renderer timing run.
    # Coordinator-owned generated/real command buffers deliberately do not
    # masquerade as ordinary drawable timing samples.
    GPU_TIMING_ENV=0
fi
set +e
METALLUM_BENCHMARK_FI_REQUIRED="$FI_REQUIRED_ENV" \
METALLUM_BENCHMARK_FI_OVERLAY="$FI_OVERLAY_ENV" \
METALLUM_BENCHMARK_FI_MIN_GENERATED="$FI_MINIMUM_GENERATED_FRAMES" \
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
METALLUM_BENCHMARK_EXPECTED_VSYNC="$EXPECTED_VSYNC" \
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
METALLUM_BENCHMARK_L6_HELD_ITEM="$L6_HELD_ITEM" \
METALLUM_BENCHMARK_L6_ORBIT_RADIUS="$L6_ORBIT_RADIUS" \
METALLUM_BENCHMARK_L6_ORBIT_YAW_AMPLITUDE_DEGREES="$L6_ORBIT_YAW_AMPLITUDE_DEGREES" \
METALLUM_BENCHMARK_L6_ORBIT_PITCH_AMPLITUDE_DEGREES="$L6_ORBIT_PITCH_AMPLITUDE_DEGREES" \
METALLUM_BENCHMARK_L6_ORBIT_PERIOD_FRAMES="$L6_ORBIT_PERIOD_FRAMES" \
METALLUM_BENCHMARK_L6_PROBE_COUNT="$L6_PROBE_COUNT" \
METALLUM_BENCHMARK_L6_PROBE_ORIGIN_X="$L6_PROBE_ORIGIN_X" \
METALLUM_BENCHMARK_L6_PROBE_ORIGIN_Y="$L6_PROBE_ORIGIN_Y" \
METALLUM_BENCHMARK_L6_PROBE_ORIGIN_Z="$L6_PROBE_ORIGIN_Z" \
METALLUM_BENCHMARK_L6_PROBE_RADIUS="$L6_PROBE_RADIUS" \
METALLUM_BENCHMARK_L6_PROBE_VERTICAL_AMPLITUDE="$L6_PROBE_VERTICAL_AMPLITUDE" \
METALLUM_BENCHMARK_L6_PROBE_PERIOD_FRAMES="$L6_PROBE_PERIOD_FRAMES" \
METALLUM_GPU_TIMING="$GPU_TIMING_ENV" \
METALLUM_GPU_TIMING_DETAIL="$TIMING_DETAIL" \
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
if [[ "$DIMENSION" == *nether* ]]; then
    [ "$route_apply_line" -lt "$server_frozen_line" ] \
        && [ "$server_frozen_line" -lt "$route_ready_line" ] \
        && [ "$route_ready_line" -lt "$route_measure_start_line" ] \
        && [ "$route_measure_start_line" -lt "$measure_start_line" ] \
        && [ "$measure_start_line" -lt "$measure_end_line" ] \
        && [ "$measure_end_line" -lt "$route_measure_end_line" ] \
        || die "deterministic route markers are out of order"
else
    [ "$server_frozen_line" -lt "$route_apply_line" ] \
        && [ "$route_apply_line" -lt "$route_ready_line" ] \
        && [ "$route_ready_line" -lt "$route_measure_start_line" ] \
        && [ "$route_measure_start_line" -lt "$measure_start_line" ] \
        && [ "$measure_start_line" -lt "$measure_end_line" ] \
        && [ "$measure_end_line" -lt "$route_measure_end_line" ] \
        || die "deterministic route markers are out of order"
fi


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

if [ "$ROUTE_KIND" = "L6_DYNAMIC_SHADOW" ]; then
    l6_ready="METALLUM_BENCHMARK EVENT=L6_DYNAMIC_READY route=$ROUTE_ID held=$L6_HELD_ITEM probes=$L6_PROBE_COUNT orbit_period=$L6_ORBIT_PERIOD_FRAMES probe_period=$L6_PROBE_PERIOD_FRAMES"
    l6_ready_count=$(grep -Fc "$l6_ready" "$MINECRAFT_LOG" || true)
    [ "$l6_ready_count" -eq 1 ] \
        || die "expected exactly one matching L6_DYNAMIC_READY marker (found $l6_ready_count)"
    l6_ready_line=$(grep -nF "$l6_ready" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$route_apply_line" -lt "$l6_ready_line" ] \
        && [ "$l6_ready_line" -lt "$route_ready_line" ] \
        || die "L6 dynamic route readiness marker is out of order"

    case "$LIGHTING_PRESET" in
        performance)
            expected_dynamic="candidates=5 selected=1 dropped=4 held=true dispatches=1 rays=1536 ready=1 fallback=0 coverage_miss=0"
            expected_dynamic_pages="failures=0 pages_bytes=49152"
            expected_coverage_budget="candidates_min=5 candidates_max=5 selected_min=1 selected_max=1 dropped_min=4 dropped_max=4 rays_min=1536 rays_max=1536 ready_min=1 ready_max=1"
            expected_coverage_pages="pages_bytes_min=49152 pages_bytes_max=49152"
            ;;
        balanced)
            expected_dynamic="candidates=5 selected=2 dropped=3 held=true dispatches=1 rays=12288 ready=2 fallback=0 coverage_miss=0"
            expected_dynamic_pages="failures=0 pages_bytes=393216"
            expected_coverage_budget="candidates_min=5 candidates_max=5 selected_min=2 selected_max=2 dropped_min=3 dropped_max=3 rays_min=12288 rays_max=12288 ready_min=2 ready_max=2"
            expected_coverage_pages="pages_bytes_min=393216 pages_bytes_max=393216"
            ;;
        ultra)
            expected_dynamic="candidates=5 selected=4 dropped=1 held=true dispatches=1 rays=24576 ready=4 fallback=0 coverage_miss=0"
            expected_dynamic_pages="failures=0 pages_bytes=786432"
            expected_coverage_budget="candidates_min=5 candidates_max=5 selected_min=4 selected_max=4 dropped_min=1 dropped_max=1 rays_min=24576 rays_max=24576 ready_min=4 ready_max=4"
            expected_coverage_pages="pages_bytes_min=786432 pages_bytes_max=786432"
            ;;
    esac
    l6_coverage="METALLUM_BENCHMARK EVENT=L6_DYNAMIC_COVERAGE route=$ROUTE_ID frames=$MEASURE_FRAMES held_admitted_frames=$MEASURE_FRAMES held_ready_frames=$MEASURE_FRAMES dispatch_frames=$MEASURE_FRAMES $expected_coverage_budget fallback_total=0 coverage_miss_total=0 failure_total=0 $expected_coverage_pages"
    l6_coverage_count=$(grep -Fc "$l6_coverage" "$MINECRAFT_LOG" || true)
    [ "$l6_coverage_count" -eq 1 ] \
        || die "expected exactly one matching L6_DYNAMIC_COVERAGE marker (found $l6_coverage_count)"
    l6_coverage_line=$(grep -nF "$l6_coverage" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$measure_start_line" -lt "$l6_coverage_line" ] \
        && [ "$l6_coverage_line" -lt "$measure_end_line" ] \
        || die "L6 dynamic coverage marker is out of order"

    l6_samples=$(sed -n "${measure_start_line},${measure_end_line}p" "$MINECRAFT_LOG" \
        | grep -F "METALLUM_L6_DYNAMIC " || true)
    l6_sample_count=$(printf '%s\n' "$l6_samples" | grep -Fc "METALLUM_L6_DYNAMIC " || true)
    [ "$l6_sample_count" -ge 1 ] \
        || die "L6 dynamic route emitted no periodic admission telemetry"
    l6_valid_count=$(printf '%s\n' "$l6_samples" \
        | grep -F "$expected_dynamic" \
        | grep -Fc "$expected_dynamic_pages" || true)
    [ "$l6_valid_count" -eq "$l6_sample_count" ] \
        || die "L6 dynamic admission/ready telemetry violated the $LIGHTING_PRESET contract ($l6_valid_count/$l6_sample_count valid)"
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
if [ "$FI_VALIDATION" -eq 1 ]; then
    fi_generated_prefix="METALLUM_BENCHMARK EVENT=FI_GENERATED_COMPLETE generated_delta="
    fi_generated_count=$(grep -Fc "$fi_generated_prefix" "$MINECRAFT_LOG" || true)
    [ "$fi_generated_count" -eq 1 ] \
        || die "expected exactly one FI_GENERATED_COMPLETE marker (found $fi_generated_count)"
    fi_generated_marker=$(grep -F "$fi_generated_prefix" "$MINECRAFT_LOG")
    fi_generated_delta=$(printf '%s\n' "$fi_generated_marker" \
        | sed -E 's/.* generated_delta=([0-9]+) minimum=.*/\1/')
    case "$fi_generated_delta" in
        ''|*[!0-9]*) die "FI_GENERATED_COMPLETE marker has an invalid generated_delta: $fi_generated_marker" ;;
    esac
    case "$fi_generated_marker" in
        *" minimum=$FI_MINIMUM_GENERATED_FRAMES") ;;
        *) die "FI_GENERATED_COMPLETE marker has the wrong minimum: $fi_generated_marker" ;;
    esac
    [ "$fi_generated_delta" -ge "$FI_MINIMUM_GENERATED_FRAMES" ] \
        || die "FI generated-frame delta $fi_generated_delta is below $FI_MINIMUM_GENERATED_FRAMES"

    fi_generated_line=$(grep -nF "$fi_generated_prefix" "$MINECRAFT_LOG" | cut -d: -f1)
    complete_line=$(grep -nF "$complete" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$measure_start_line" -lt "$fi_generated_line" ] \
        && [ "$fi_generated_line" -lt "$complete_line" ] \
        || die "FI generated-frame completion marker is out of order"

    remaining_processes=$(pgrep -fl "$PROCESS_PATTERN" || true)
    [ -z "$remaining_processes" ] \
        || die "FI validation returned but a Minecraft/runClient process remains:\n$remaining_processes"
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
            || die "FI reference run expected exactly one new PNG (found $captured_count)"
        mkdir -p "$REFERENCE_OUTPUT_DIR"
        reference_screenshot="$REFERENCE_OUTPUT_DIR/$stem.png"
        cp "$captured_screenshot" "$reference_screenshot"
        echo "FI reference capture validated: $reference_screenshot"
        echo "  sha256: $(shasum -a 256 "$reference_screenshot" | awk '{print $1}')"
    fi
    echo "FI validation passed: $fi_generated_delta generated frames reached the display"
    echo "  evidence: exact CAMetalDrawable presented-handler counter in $MINECRAFT_LOG"
    echo "  console log: $CONSOLE_LOG"
    ATTEST_PENDING=0
    exit 0
fi
[ -s "$RAW_REPORT" ] || die "GPU timing JSONL report is missing or empty"

RELEASE_ARG=""
VALIDATION_ARG=""
if [ "$TIMING_DETAIL" -eq 0 ] \
    && [ "$METAL_VALIDATION" -eq 0 ] \
    && [ "$CAPTURE_REFERENCE" -eq 0 ] \
    && [ "$FI_VALIDATION" -eq 0 ]; then
    RELEASE_ARG=--release-contract
fi
if [ "$METAL_VALIDATION" -eq 1 ]; then
    VALIDATION_ARG=--metal-validation-contract
fi

python3 "$ANALYZER" summarize "$RAW_REPORT" \
    --measure-frames "$MEASURE_FRAMES" \
    --segment 0 \
    --scaler-mode "$METALFX_MODE" \
    ${RELEASE_ARG:+$RELEASE_ARG} \
    ${VALIDATION_ARG:+$VALIDATION_ARG} \
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
    ${RELEASE_ARG:+$RELEASE_ARG} \
    ${VALIDATION_ARG:+$VALIDATION_ARG} \
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
if [ -n "$RELEASE_ARG" ]; then
    ATTEST_PENDING=1
else
    ATTEST_PENDING=0
fi
