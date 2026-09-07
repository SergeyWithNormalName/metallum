#!/bin/bash
# Reproducible, console-only Metallum benchmark on the MacBook's built-in panel.

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
ANALYZER="$ROOT/tools/metal_benchmark_report.py"
FIXTURE_HELPER="$ROOT/tools/metal_benchmark_fixture.py"
GI_RELEASE_GUARD="$ROOT/tools/gi_release_contract_guard.sh"
RUN_DIR="$ROOT/run"
OUTPUT_DIR="$RUN_DIR/logs/metallum-benchmarks"
REFERENCE_OUTPUT_DIR=${METALLUM_L2_REFERENCE_OUTPUT_DIR:-"$RUN_DIR/lighting-reference/l0"}
DEFAULT_ROUTE_SPEC="benchmark/routes/hdrtest-static-v1.json"
DEFAULT_SETTINGS_SPEC="benchmark/settings/native-hdr-fancy-v1.json"
FI_SETTINGS_SPEC="benchmark/settings/fi-hdr-temporal-ultra-performance-v1.json"
GI_LIVE_ROUTE_SPEC="benchmark/routes/hdrtest-torch-toggle-v1.json"
GI_LIVE_SETTINGS_SPEC="benchmark/settings/native-hdr-fancy-gi-live-v1.json"
GI_VISUAL_PROBE_ROUTE_SPEC="benchmark/routes/hdrtest-gi-visual-probe-v1.json"
ARTIFACT_CLASSES="build/classes/java/main"
ARTIFACT_RESOURCES="build/resources/main"
ARTIFACT_NATIVE="build/generated/metallum/natives/macos/libmetallum.dylib"

MONITOR_NAME="Built-in Retina Display"
WIDTH=${METALLUM_L2_WIDTH:-3024}
HEIGHT=${METALLUM_L2_HEIGHT:-1964}
REFRESH_HZ=${METALLUM_L2_REFRESH_HZ:-120}
WARMUP_FRAMES=${METALLUM_L2_WARMUP_FRAMES:-1800}
MEASURE_FRAMES=${METALLUM_L2_MEASURE_FRAMES:-3000}
MEASURE_FRAMES_EXPLICIT=0
[ "${METALLUM_L2_MEASURE_FRAMES+x}" = x ] && MEASURE_FRAMES_EXPLICIT=1
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
TEMPORARY_SETTINGS=0
SETTINGS_SPEC_EXPLICIT=0
ROUTE_SPEC_EXPLICIT=0
METALFX_MODE_EXPLICIT=0
GI_LIVE=0
GI_VISUAL_PROBE=0
GI_VISUAL_PROBE_ARM=""
# The G6 atlas probe is benchmark-only evidence. The launcher owns the gate so
# an inherited shell setting can never enable a GPU readback in a normal run.
GI_G6_DEBUG_PROBE=0
VERTEX_REFLECTION_EXPERIMENT=0
WATER_REFLECTION_QUALITY=refined
GI_G5_RECEIVER_ARM=control
GI_G5_FIELD_KIND=zero
GI_G5_RECEIVER_ACTIVE=false

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
TRANSCRIPT_ACTIVE=0
TRANSCRIPT_TEE_PID=""
TRANSCRIPT_PIPE=""

usage() {
    cat <<'EOF'
Usage: scripts/run_metal_benchmark.sh [options]

Runs Minecraft without GUI automation or screenshots, moves it to the built-in
Retina panel at 3024x1964 fullscreen by default, warms up for 1800 presented
frames, and measures 3000 frames into a unique ignored JSONL report.

Options:
  --route FILE       tracked deterministic route specification
                     (default: benchmark/routes/hdrtest-static-v1.json)
  --settings FILE    tracked performance/quality settings specification
                     (default: benchmark/settings/native-hdr-fancy-v1.json)
  --metalfx MODE     OFF, QUALITY, PERFORMANCE, TEMPORAL,
                     TEMPORAL_QUALITY, TEMPORAL_PERFORMANCE, or
                     TEMPORAL_ULTRA_PERFORMANCE
                     (default: OFF)
  --lighting-preset PRESET
                     performance, balanced, or ultra (default: balanced)
  --label LABEL      short artifact label (default: baseline)
  --vertex-reflection-experiment
                     opt in to the quarantined frozen vertex-reflection
                     experiment for a diagnostic comparison; ordinary runs
                     remain forced OFF
  --water-reflection-quality MODE
                     explicitly specialize the vertex-reflection receiver as
                     refined, legacy, face-off, first-surface-off, or
                     confidence-off (default: refined)
  --preflight-only   validate route/config/release settings contract/immutable fixture
                     without cloning
  --capture-reference capture one ignored screenshot; this run is not attested
  --gi-live          validate production G6 live GI using the persistent
                     globalIllumination=dynamic renderer setting; defaults to
                     the tracked torch-toggle route and GI-live settings profile
  --gi-visual-probe ARM
                     create the tracked disposable red-reflector visual rig and
                     capture four scheduled motion frames; ARM is on or off.
                     This is non-attested visual evidence, not G6 acceptance.
  --fi-validation    run the opt-in HDR Temporal Ultra Performance + Frame Interpolation
                     validation profile; temporarily applies and then restores
                     options, HDR, renderer, MetalFX, and Temporal settings
                     byte-for-byte
  -h, --help         show this help

L2 diagnostic environment:
  METALLUM_L2_WIDTH / METALLUM_L2_HEIGHT / METALLUM_L2_REFRESH_HZ
      override the exact fullscreen video mode (default: 3024x1964@120)
  METALLUM_L2_WARMUP_FRAMES / METALLUM_L2_MEASURE_FRAMES
      override the 300-frame-aligned run lengths
  METALLUM_L2_TIMING_DETAIL=1
      include per-stage timing for zero-work validation
  METALLUM_L2_METAL_VALIDATION=1
      retain caller-provided Metal API/shader validation variables
  METALLUM_L2_REFERENCE_OUTPUT_DIR=DIR
      place ignored L2 captures outside the default L0 reference directory
  METALLUM_GI_G4_TRANSPORT=1
      request frozen near-cascade G4 diagnostics; this forces the G2/G3
      prerequisites and can never produce a release attestation
  METALLUM_GI_G5_RECEIVER=1
      request the G5 vertex receiver diagnostic; requires an explicit
      METALLUM_GI_G5_RECEIVER_ARM=control|candidate|field, inherits private
      G2/G3/G4 resources without making a standalone G4 request, and can never
      produce a release attestation

Compare completed reports with:
  python3 tools/metal_benchmark_report.py compare BASELINE.jsonl CANDIDATE.jsonl
EOF
}

die() {
    echo "ERROR: $*" >&2
    exit 2
}

finish_transcript() {
    local transcript_status=0
    if [ "${TRANSCRIPT_ACTIVE:-0}" -eq 1 ]; then
        TRANSCRIPT_ACTIVE=0
        exec 1>&3 2>&4
        exec 3>&- 4>&-
        if wait "$TRANSCRIPT_TEE_PID"; then
            :
        else
            transcript_status=$?
        fi
        if [ -n "${TRANSCRIPT_PIPE:-}" ]; then
            rm -f "$TRANSCRIPT_PIPE" || transcript_status=1
        fi
        TRANSCRIPT_TEE_PID=""
        TRANSCRIPT_PIPE=""
    fi
    return "$transcript_status"
}

transcript_only_cleanup() {
    local original_status=$1
    trap - EXIT HUP INT TERM
    set +e
    finish_transcript
    if [ "$?" -ne 0 ] && [ "$original_status" -eq 0 ]; then
        original_status=2
    fi
    exit "$original_status"
}

[ -f "$GI_RELEASE_GUARD" ] || die "GI release-contract guard is missing: $GI_RELEASE_GUARD"
# shellcheck source=tools/gi_release_contract_guard.sh
. "$GI_RELEASE_GUARD"

need_value() {
    [ "$#" -ge 2 ] || die "$1 requires a value"
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --route)
            need_value "$@"
            ROUTE_SPEC_ARGUMENT=$2
            ROUTE_SPEC_EXPLICIT=1
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
        --vertex-reflection-experiment)
            VERTEX_REFLECTION_EXPERIMENT=1
            shift
            ;;
        --water-reflection-quality)
            need_value "$@"
            WATER_REFLECTION_QUALITY=$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')
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
        --gi-live)
            GI_LIVE=1
            shift
            ;;
        --gi-visual-probe)
            need_value "$@"
            GI_VISUAL_PROBE=1
            GI_VISUAL_PROBE_ARM=$2
            shift 2
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

if [ "$GI_LIVE" -eq 1 ]; then
    [ "$FI_VALIDATION" -eq 0 ] || die "--gi-live cannot be combined with --fi-validation"
    [ "$VERTEX_REFLECTION_EXPERIMENT" -eq 0 ] \
        || die "--gi-live cannot be combined with --vertex-reflection-experiment"
    if [ "$ROUTE_SPEC_EXPLICIT" -eq 0 ]; then
        ROUTE_SPEC_ARGUMENT=$GI_LIVE_ROUTE_SPEC
    fi
    if [ "$SETTINGS_SPEC_EXPLICIT" -eq 0 ]; then
        SETTINGS_SPEC_ARGUMENT=$GI_LIVE_SETTINGS_SPEC
    fi
fi

if [ "$GI_VISUAL_PROBE" -eq 1 ]; then
    [ "$GI_LIVE" -eq 0 ] || die "--gi-visual-probe cannot be combined with --gi-live"
    [ "$FI_VALIDATION" -eq 0 ] || die "--gi-visual-probe cannot be combined with --fi-validation"
    [ "$VERTEX_REFLECTION_EXPERIMENT" -eq 0 ] \
        || die "--gi-visual-probe cannot be combined with --vertex-reflection-experiment"
    case "$GI_VISUAL_PROBE_ARM" in on|off) ;; *) die "--gi-visual-probe ARM must be on or off" ;; esac
    if [ "$ROUTE_SPEC_EXPLICIT" -eq 0 ]; then
        ROUTE_SPEC_ARGUMENT=$GI_VISUAL_PROBE_ROUTE_SPEC
    fi
    if [ "$SETTINGS_SPEC_EXPLICIT" -eq 0 ] && [ "$GI_VISUAL_PROBE_ARM" = "on" ]; then
        SETTINGS_SPEC_ARGUMENT=$GI_LIVE_SETTINGS_SPEC
    fi
    CAPTURE_REFERENCE=1
    if [ "$MEASURE_FRAMES_EXPLICIT" -eq 0 ]; then
        MEASURE_FRAMES=900
    fi
    if [ "$GI_VISUAL_PROBE_ARM" = "on" ]; then
        GI_G6_DEBUG_PROBE=1
    fi
fi

if [ "$FI_VALIDATION" -eq 1 ]; then
    [ "$SETTINGS_SPEC_EXPLICIT" -eq 0 ] \
        || die "--fi-validation selects its own settings profile; do not combine it with --settings"
    [ "$METALFX_MODE_EXPLICIT" -eq 0 ] \
        || die "--fi-validation selects TEMPORAL_ULTRA_PERFORMANCE; do not combine it with --metalfx"
    SETTINGS_SPEC_ARGUMENT=$FI_SETTINGS_SPEC
    EXPECTED_VSYNC=true
fi

case "$METALFX_MODE" in
    OFF|QUALITY|PERFORMANCE|TEMPORAL|TEMPORAL_QUALITY|TEMPORAL_PERFORMANCE|TEMPORAL_ULTRA_PERFORMANCE) ;;
    *) die "--metalfx must be OFF, QUALITY, PERFORMANCE, TEMPORAL, TEMPORAL_QUALITY, TEMPORAL_PERFORMANCE, or TEMPORAL_ULTRA_PERFORMANCE" ;;
esac
case "$LIGHTING_PRESET" in
    performance|balanced|ultra) ;;
    *) die "--lighting-preset must be performance, balanced, or ultra" ;;
esac
case "$WATER_REFLECTION_QUALITY" in
    refined)
        WATER_REFLECTION_FACE_AWARE=true
        WATER_REFLECTION_FIRST_SURFACE=true
        WATER_REFLECTION_CONFIDENCE=true
        ;;
    legacy)
        WATER_REFLECTION_FACE_AWARE=false
        WATER_REFLECTION_FIRST_SURFACE=false
        WATER_REFLECTION_CONFIDENCE=false
        ;;
    face-off)
        WATER_REFLECTION_FACE_AWARE=false
        WATER_REFLECTION_FIRST_SURFACE=true
        WATER_REFLECTION_CONFIDENCE=true
        ;;
    first-surface-off)
        WATER_REFLECTION_FACE_AWARE=true
        WATER_REFLECTION_FIRST_SURFACE=false
        WATER_REFLECTION_CONFIDENCE=true
        ;;
    confidence-off)
        WATER_REFLECTION_FACE_AWARE=true
        WATER_REFLECTION_FIRST_SURFACE=true
        WATER_REFLECTION_CONFIDENCE=false
        ;;
    *) die "--water-reflection-quality must be refined, legacy, face-off, first-surface-off, or confidence-off" ;;
esac

case "$WARMUP_FRAMES:$MEASURE_FRAMES:$TIMING_DETAIL:$METAL_VALIDATION" in
    *[!0-9:]*|::*|:*:|*::* ) die "L2 frame/detail/validation overrides must be non-negative integers" ;;
esac
case "$WIDTH" in ''|*[!0-9]*) die "METALLUM_L2_WIDTH must be a positive integer" ;; esac
case "$HEIGHT" in ''|*[!0-9]*) die "METALLUM_L2_HEIGHT must be a positive integer" ;; esac
case "$REFRESH_HZ" in ''|*[!0-9]*) die "METALLUM_L2_REFRESH_HZ must be a positive integer" ;; esac
[ "$WIDTH" -gt 0 ] || die "METALLUM_L2_WIDTH must be a positive integer"
[ "$HEIGHT" -gt 0 ] || die "METALLUM_L2_HEIGHT must be a positive integer"
[ "$REFRESH_HZ" -gt 0 ] || die "METALLUM_L2_REFRESH_HZ must be a positive integer"
[ "$WARMUP_FRAMES" -ge 300 ] && [ $((WARMUP_FRAMES % 300)) -eq 0 ] \
    || die "METALLUM_L2_WARMUP_FRAMES must be a multiple of 300 and at least 300"
[ "$MEASURE_FRAMES" -ge 300 ] && [ $((MEASURE_FRAMES % 300)) -eq 0 ] \
    || die "METALLUM_L2_MEASURE_FRAMES must be a multiple of 300 and at least 300"
case "$TIMING_DETAIL" in 0|1) ;; *) die "METALLUM_L2_TIMING_DETAIL must be 0 or 1" ;; esac
case "$METAL_VALIDATION" in 0|1) ;; *) die "METALLUM_L2_METAL_VALIDATION must be 0 or 1" ;; esac

command -v python3 >/dev/null 2>&1 || die "python3 is required for report validation"
command -v pgrep >/dev/null 2>&1 || die "pgrep is required for process isolation"
command -v mktemp >/dev/null 2>&1 || die "mktemp is required for isolated benchmark worlds"
command -v mkfifo >/dev/null 2>&1 || die "mkfifo is required for benchmark transcripts"
command -v uuidgen >/dev/null 2>&1 || die "uuidgen is required for isolated benchmark worlds"
if [ "$FI_VALIDATION" -eq 1 ] || [ "$GI_VISUAL_PROBE" -eq 1 ]; then
    command -v cmp >/dev/null 2>&1 \
        || die "cmp is required to verify temporary runtime-settings restoration"
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

    [ "${TEMPORARY_SETTINGS:-0}" -eq 1 ] || return 0
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
    if ! finish_transcript && [ "$original_status" -eq 0 ]; then
        original_status=2
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
G6_MATRIX_HELD_ITEM="minecraft:air"
G6_MATRIX_ENTITY_ITEM="minecraft:air"
G6_MATRIX_ENTITY_POSITION_X=0
G6_MATRIX_ENTITY_POSITION_Y=0
G6_MATRIX_ENTITY_POSITION_Z=0
G6_MATRIX_LAVA_POSITION_X=0
G6_MATRIX_LAVA_POSITION_Y=0
G6_MATRIX_LAVA_POSITION_Z=0
G6_MATRIX_LAVA_INITIAL_BLOCK="minecraft:air"
G6_MATRIX_LAVA_APPLY_FRAME=0
G6_MATRIX_LAVA_REMOVE_FRAME=0
G6_MATRIX_ORBIT_START_FRAME=0
G6_MATRIX_ORBIT_END_FRAME=0
G6_MATRIX_ORBIT_YAW_AMPLITUDE_DEGREES=0
G6_MATRIX_ORBIT_PITCH_AMPLITUDE_DEGREES=0
G6_MATRIX_ORBIT_PERIOD_FRAMES=0
G6_MATRIX_CHUNK_RELOAD_FRAME=0
G6_MATRIX_RESOURCE_RELOAD_FRAME=0
G6_MATRIX_DAY_FRAME=0
G6_MATRIX_DAY_TICKS=0
G6_MATRIX_NIGHT_FRAME=0
G6_MATRIX_NIGHT_TICKS=0
G6_MATRIX_RAIN_FRAME=0
G6_MATRIX_CLEAR_FRAME=0
G6_MATRIX_STREAM_START_FRAME=0
G6_MATRIX_STREAM_STEP_FRAMES=0
G6_MATRIX_STREAM_OFFSET_0=0
G6_MATRIX_STREAM_OFFSET_1=0
G6_MATRIX_STREAM_OFFSET_2=0
G6_MATRIX_STREAM_OFFSET_3=0
G6_MATRIX_STREAM_OFFSET_4=0
G6_MATRIX_STREAM_OFFSET_5=0
G6_MATRIX_STREAM_OFFSET_6=0
G6_MATRIX_STREAM_OFFSET_7=0
G6_MATRIX_STREAM_Y_OFFSET_0=0
G6_MATRIX_STREAM_Y_OFFSET_1=0
G6_MATRIX_STREAM_Y_OFFSET_2=0
G6_MATRIX_STREAM_Y_OFFSET_3=0
G6_MATRIX_STREAM_Y_OFFSET_4=0
G6_MATRIX_STREAM_Y_OFFSET_5=0
G6_MATRIX_STREAM_Y_OFFSET_6=0
G6_MATRIX_STREAM_Y_OFFSET_7=0
G6_MATRIX_TELEPORT_FRAME=0
G6_MATRIX_TELEPORT_OFFSET_X=0
G6_MATRIX_TELEPORT_OFFSET_Y=0
G6_MATRIX_TELEPORT_OFFSET_Z=0
G6_MATRIX_TELEPORT_RETURN_FRAME=0
G6_MATRIX_NETHER_ENTER_FRAME=0
G6_MATRIX_NETHER_POSITION_X=0
G6_MATRIX_NETHER_POSITION_Y=0
G6_MATRIX_NETHER_POSITION_Z=0
G6_MATRIX_NETHER_RETURN_FRAME=0
GI_VISUAL_PROBE_RIG_ID=""
GI_VISUAL_PROBE_TORCH_POSITION_X=0
GI_VISUAL_PROBE_TORCH_POSITION_Y=0
GI_VISUAL_PROBE_TORCH_POSITION_Z=0
GI_VISUAL_PROBE_ORBIT_START_FRAME=0
GI_VISUAL_PROBE_ORBIT_END_FRAME=0
GI_VISUAL_PROBE_ORBIT_TRANSLATION_RADIUS_BLOCKS=0
GI_VISUAL_PROBE_ORBIT_YAW_AMPLITUDE_DEGREES=0
GI_VISUAL_PROBE_ORBIT_PITCH_AMPLITUDE_DEGREES=0
GI_VISUAL_PROBE_ORBIT_PERIOD_FRAMES=0
GI_VISUAL_PROBE_CAPTURE_FRAME_0=0
GI_VISUAL_PROBE_CAPTURE_FRAME_1=0
GI_VISUAL_PROBE_CAPTURE_FRAME_2=0
GI_VISUAL_PROBE_CAPTURE_FRAME_3=0
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
    37)
        IFS=$'\t' read -r \
            ROUTE_ID ROUTE_SHA256 FIXTURE_ID FIXTURE_SHA256 \
            PLAYER_NAME PLAYER_UUID DIMENSION \
            POSITION_X POSITION_Y POSITION_Z YAW PITCH \
            CLOCK_TICKS CLEAR_WEATHER_TICKS SIMULATION_FROZEN \
            ROUTE_STABLE_FRAMES ROUTE_TIMEOUT_FRAMES \
            POSITION_EPSILON ANGLE_EPSILON \
            ROUTE_KIND GI_VISUAL_PROBE_RIG_ID \
            GI_VISUAL_PROBE_TORCH_POSITION_X GI_VISUAL_PROBE_TORCH_POSITION_Y \
            GI_VISUAL_PROBE_TORCH_POSITION_Z \
            TORCH_APPLY_AFTER_MEASURED_FRAMES TORCH_OBSERVATION_FRAMES \
            TORCH_REMOVE_AFTER_MEASURED_FRAMES GI_VISUAL_PROBE_ORBIT_START_FRAME \
            GI_VISUAL_PROBE_ORBIT_END_FRAME GI_VISUAL_PROBE_ORBIT_TRANSLATION_RADIUS_BLOCKS \
            GI_VISUAL_PROBE_ORBIT_YAW_AMPLITUDE_DEGREES \
            GI_VISUAL_PROBE_ORBIT_PITCH_AMPLITUDE_DEGREES GI_VISUAL_PROBE_ORBIT_PERIOD_FRAMES \
            GI_VISUAL_PROBE_CAPTURE_FRAME_0 GI_VISUAL_PROBE_CAPTURE_FRAME_1 \
            GI_VISUAL_PROBE_CAPTURE_FRAME_2 GI_VISUAL_PROBE_CAPTURE_FRAME_3 \
            <<< "$route_values"
        TORCH_POSITION_X=$GI_VISUAL_PROBE_TORCH_POSITION_X
        TORCH_POSITION_Y=$GI_VISUAL_PROBE_TORCH_POSITION_Y
        TORCH_POSITION_Z=$GI_VISUAL_PROBE_TORCH_POSITION_Z
        TORCH_INITIAL_BLOCK="minecraft:air"
        TORCH_SUPPORT_BLOCK="minecraft:grass_block"
        ;;
    72)
        IFS=$'\t' read -r \
            ROUTE_ID ROUTE_SHA256 FIXTURE_ID FIXTURE_SHA256 \
            PLAYER_NAME PLAYER_UUID DIMENSION \
            POSITION_X POSITION_Y POSITION_Z YAW PITCH \
            CLOCK_TICKS CLEAR_WEATHER_TICKS SIMULATION_FROZEN \
            ROUTE_STABLE_FRAMES ROUTE_TIMEOUT_FRAMES \
            POSITION_EPSILON ANGLE_EPSILON \
            ROUTE_KIND G6_MATRIX_HELD_ITEM G6_MATRIX_ENTITY_ITEM \
            G6_MATRIX_ENTITY_POSITION_X G6_MATRIX_ENTITY_POSITION_Y \
            G6_MATRIX_ENTITY_POSITION_Z G6_MATRIX_LAVA_POSITION_X \
            G6_MATRIX_LAVA_POSITION_Y G6_MATRIX_LAVA_POSITION_Z \
            G6_MATRIX_LAVA_INITIAL_BLOCK G6_MATRIX_LAVA_APPLY_FRAME \
            G6_MATRIX_LAVA_REMOVE_FRAME G6_MATRIX_ORBIT_START_FRAME \
            G6_MATRIX_ORBIT_END_FRAME G6_MATRIX_ORBIT_YAW_AMPLITUDE_DEGREES \
            G6_MATRIX_ORBIT_PITCH_AMPLITUDE_DEGREES G6_MATRIX_ORBIT_PERIOD_FRAMES \
            G6_MATRIX_CHUNK_RELOAD_FRAME G6_MATRIX_RESOURCE_RELOAD_FRAME \
            G6_MATRIX_DAY_FRAME G6_MATRIX_DAY_TICKS G6_MATRIX_NIGHT_FRAME \
            G6_MATRIX_NIGHT_TICKS G6_MATRIX_RAIN_FRAME G6_MATRIX_CLEAR_FRAME \
            G6_MATRIX_STREAM_START_FRAME G6_MATRIX_STREAM_STEP_FRAMES \
            G6_MATRIX_STREAM_OFFSET_0 G6_MATRIX_STREAM_OFFSET_1 \
            G6_MATRIX_STREAM_OFFSET_2 G6_MATRIX_STREAM_OFFSET_3 \
            G6_MATRIX_STREAM_OFFSET_4 G6_MATRIX_STREAM_OFFSET_5 \
            G6_MATRIX_STREAM_OFFSET_6 G6_MATRIX_STREAM_OFFSET_7 \
            G6_MATRIX_STREAM_Y_OFFSET_0 G6_MATRIX_STREAM_Y_OFFSET_1 \
            G6_MATRIX_STREAM_Y_OFFSET_2 G6_MATRIX_STREAM_Y_OFFSET_3 \
            G6_MATRIX_STREAM_Y_OFFSET_4 G6_MATRIX_STREAM_Y_OFFSET_5 \
            G6_MATRIX_STREAM_Y_OFFSET_6 G6_MATRIX_STREAM_Y_OFFSET_7 \
            G6_MATRIX_TELEPORT_FRAME G6_MATRIX_TELEPORT_OFFSET_X \
            G6_MATRIX_TELEPORT_OFFSET_Y G6_MATRIX_TELEPORT_OFFSET_Z \
            G6_MATRIX_TELEPORT_RETURN_FRAME G6_MATRIX_NETHER_ENTER_FRAME \
            G6_MATRIX_NETHER_POSITION_X G6_MATRIX_NETHER_POSITION_Y \
            G6_MATRIX_NETHER_POSITION_Z G6_MATRIX_NETHER_RETURN_FRAME \
            <<< "$route_values"
        ;;
    *)
        die "route helper returned $route_field_count fields instead of 19, 27, 28, 32, 37, or 72"
        ;;
esac

if [ "$ROUTE_ID" = "hdrtest-gi-g6-matrix-v1" ] \
        && [ "$MEASURE_FRAMES_EXPLICIT" -eq 0 ]; then
    MEASURE_FRAMES=3600
fi

case "$ROUTE_ID" in
    gi-g0-*)
        [ "$VERTEX_REFLECTION_EXPERIMENT" -eq 0 ] \
            || die "GI G0 routes forbid the separate vertex-reflection experiment"
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
    GI_VISUAL_PROBE)
        [ "$route_field_count" -eq 37 ] \
            || die "GI visual probe must use the schema-6 37-field contract"
        require_value "$GI_VISUAL_PROBE_RIG_ID" "red-reflector-occluded-v1" "GI visual probe rig"
        require_value "$GI_VISUAL_PROBE_TORCH_POSITION_X" "80" "GI visual probe torch x"
        require_value "$GI_VISUAL_PROBE_TORCH_POSITION_Y" "75" "GI visual probe torch y"
        require_value "$GI_VISUAL_PROBE_TORCH_POSITION_Z" "-112" "GI visual probe torch z"
        require_value "$TORCH_APPLY_AFTER_MEASURED_FRAMES" "300" "GI visual probe torch apply"
        require_value "$TORCH_OBSERVATION_FRAMES" "450" "GI visual probe torch observation"
        require_value "$TORCH_REMOVE_AFTER_MEASURED_FRAMES" "690" "GI visual probe torch removal"
        require_value "$GI_VISUAL_PROBE_ORBIT_START_FRAME" "540" "GI visual probe orbit start"
        require_value "$GI_VISUAL_PROBE_ORBIT_END_FRAME" "690" "GI visual probe orbit end"
        require_value "$GI_VISUAL_PROBE_ORBIT_TRANSLATION_RADIUS_BLOCKS" "0.75" "GI visual probe translation radius"
        require_value "$GI_VISUAL_PROBE_ORBIT_YAW_AMPLITUDE_DEGREES" "12.0" "GI visual probe yaw amplitude"
        require_value "$GI_VISUAL_PROBE_ORBIT_PITCH_AMPLITUDE_DEGREES" "3.0" "GI visual probe pitch amplitude"
        require_value "$GI_VISUAL_PROBE_ORBIT_PERIOD_FRAMES" "120" "GI visual probe orbit period"
        require_value "$GI_VISUAL_PROBE_CAPTURE_FRAME_0" "570" "GI visual probe capture frame 0"
        require_value "$GI_VISUAL_PROBE_CAPTURE_FRAME_1" "600" "GI visual probe capture frame 1"
        require_value "$GI_VISUAL_PROBE_CAPTURE_FRAME_2" "630" "GI visual probe capture frame 2"
        require_value "$GI_VISUAL_PROBE_CAPTURE_FRAME_3" "660" "GI visual probe capture frame 3"
        [ "$MEASURE_FRAMES" -gt 750 ] \
            || die "GI visual probe measurement must extend past its 750-frame torch epoch"
        ;;
    GI_G6_MATRIX)
        [ "$route_field_count" -eq 72 ] \
            || die "G6 matrix route must use the schema-5 72-field contract"
        require_value "$G6_MATRIX_HELD_ITEM" "minecraft:torch" "G6 matrix held source"
        require_value "$G6_MATRIX_ENTITY_ITEM" "minecraft:torch" "G6 matrix entity source"
        require_value "$G6_MATRIX_LAVA_INITIAL_BLOCK" "minecraft:air" "G6 matrix lava initial block"
        [ "$((G6_MATRIX_RESOURCE_RELOAD_FRAME - G6_MATRIX_CHUNK_RELOAD_FRAME))" -ge 270 ] \
            && [ "$((G6_MATRIX_DAY_FRAME - G6_MATRIX_RESOURCE_RELOAD_FRAME))" -ge 270 ] \
            || die "G6 matrix reload recovery gaps must be at least 270 measured frames"
        [ "$((G6_MATRIX_NIGHT_FRAME - G6_MATRIX_DAY_FRAME))" -ge 200 ] \
            && [ "$((G6_MATRIX_RAIN_FRAME - G6_MATRIX_NIGHT_FRAME))" -ge 200 ] \
            && [ "$((G6_MATRIX_CLEAR_FRAME - G6_MATRIX_RAIN_FRAME))" -ge 200 ] \
            && [ "$((G6_MATRIX_STREAM_START_FRAME - G6_MATRIX_CLEAR_FRAME))" -ge 200 ] \
            || die "G6 matrix static recovery gaps must be at least 200 measured frames"
        [ "$G6_MATRIX_STREAM_STEP_FRAMES" -ge 40 ] \
            && [ "$G6_MATRIX_STREAM_STEP_FRAMES" -le 60 ] \
            || die "G6 matrix stream steps must be between 40 and 60 measured frames"
        [ "$((G6_MATRIX_TELEPORT_FRAME - G6_MATRIX_STREAM_START_FRAME \
            - 7 * G6_MATRIX_STREAM_STEP_FRAMES))" -ge 60 ] \
            || die "G6 matrix final stream recovery gap must be at least 60 measured frames"
        [ "$((G6_MATRIX_TELEPORT_RETURN_FRAME - G6_MATRIX_TELEPORT_FRAME))" -ge 260 ] \
            || die "G6 matrix teleport stabilization gap must be at least 260 measured frames"
        [ "$((G6_MATRIX_NETHER_ENTER_FRAME - G6_MATRIX_TELEPORT_RETURN_FRAME))" -ge 190 ] \
            || die "G6 matrix reset handoff gap must be at least 190 measured frames"
        [ "$((G6_MATRIX_NETHER_RETURN_FRAME - G6_MATRIX_NETHER_ENTER_FRAME))" -ge 470 ] \
            && [ "$((MEASURE_FRAMES - G6_MATRIX_NETHER_RETURN_FRAME))" -ge 470 ] \
            || die "G6 matrix dimension recovery gaps and final tail must be at least 470 measured frames"
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

if [ "$FI_VALIDATION" -eq 1 ] || [ "$GI_VISUAL_PROBE" -eq 1 ]; then
    TEMPORARY_SETTINGS=1
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
        die "failed to back up temporary runtime settings"
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
        || die "failed to apply temporary benchmark runtime settings"
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
    34)
        [ "$FI_VALIDATION" -eq 0 ] \
            || die "schema-v3 GI_OFF settings cannot be used for FI validation"
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
            FI_MINIMUM_GENERATED_PERCENT BENCHMARK_RENDERER_IMPROVED_LIGHTING \
            BENCHMARK_RENDERER_LIGHTING_PRESET BENCHMARK_RENDERER_GI_MODE <<< "$settings_values"
        ;;
    *)
        die "settings helper returned $settings_field_count fields instead of 27, 31, 33, or 34"
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
    && [ "$FI_VALIDATION" -eq 0 ] \
    && [ "$GI_LIVE" -eq 0 ]; then
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
RENDERER_SCHEMA=$(renderer_value schemaVersion)
RENDERER_LIGHTING=$(renderer_value improvedLighting)
RENDERER_PRESET=$(renderer_value lightingPreset)
RENDERER_INTERPOLATION=$(renderer_value frameInterpolation)
RENDERER_VOXEL_DEBUG=$(renderer_value voxelDebugChecksum)
RENDERER_GI_MODE=$(renderer_value globalIllumination)
[ -n "$RENDERER_VOXEL_DEBUG" ] || RENDERER_VOXEL_DEBUG=false
require_value "$RENDERER_SCHEMA" "5" "renderer schemaVersion"
case "$RENDERER_LIGHTING" in true|false) ;; *) die "renderer improvedLighting must be true or false" ;; esac
require_value "$RENDERER_PRESET" "$LIGHTING_PRESET" "renderer lightingPreset"
if [ "$FI_VALIDATION" -eq 1 ]; then
    EXPECTED_LIGHTING_MODEL=vanilla
    require_value "$RENDERER_LIGHTING" "$FI_RENDERER_IMPROVED_LIGHTING" \
        "FI validation renderer improvedLighting"
    require_value "$RENDERER_PRESET" "$FI_RENDERER_LIGHTING_PRESET" \
        "FI validation renderer lightingPreset"
    require_value "$RENDERER_INTERPOLATION" "true" "renderer frameInterpolation"
else
    EXPECTED_LIGHTING_MODEL=advanced
    require_value "$RENDERER_LIGHTING" "true" \
        "benchmark renderer improvedLighting"
    require_value "$RENDERER_INTERPOLATION" "false" "renderer frameInterpolation"
fi
require_value "$RENDERER_VOXEL_DEBUG" "false" "renderer voxelDebugChecksum"
if [ "$GI_LIVE" -eq 1 ] || { [ "$GI_VISUAL_PROBE" -eq 1 ] && [ "$GI_VISUAL_PROBE_ARM" = "on" ]; }; then
    require_value "$RENDERER_GI_MODE" "dynamic" "G6 renderer globalIllumination"
else
    require_value "$RENDERER_GI_MODE" "off" "renderer globalIllumination"
fi
GI_G2_CAPTURE_ENV=0
GI_G3_INJECT_ENV=0
GI_G4_TRANSPORT_ENV=0
GI_G5_RECEIVER_ENV=0
case "${METALLUM_GI_G2_CAPTURE:-}" in
    '') ;;
    0|false|FALSE|no|NO|off|OFF) ;;
    1|true|TRUE|yes|YES|on|ON) GI_G2_CAPTURE_ENV=1 ;;
    *) die "METALLUM_GI_G2_CAPTURE must be a boolean diagnostic flag" ;;
esac
case "${METALLUM_GI_G3_INJECT:-}" in
    '') ;;
    0|false|FALSE|no|NO|off|OFF) ;;
    1|true|TRUE|yes|YES|on|ON) GI_G3_INJECT_ENV=1 ;;
    *) die "METALLUM_GI_G3_INJECT must be a boolean diagnostic flag" ;;
esac
case "${METALLUM_GI_G4_TRANSPORT:-}" in
    '') ;;
    0|false|FALSE|no|NO|off|OFF) ;;
    1|true|TRUE|yes|YES|on|ON) GI_G4_TRANSPORT_ENV=1 ;;
    *) die "METALLUM_GI_G4_TRANSPORT must be a boolean diagnostic flag" ;;
esac
case "${METALLUM_GI_G5_RECEIVER:-}" in
    '') ;;
    0|false|FALSE|no|NO|off|OFF) ;;
    1|true|TRUE|yes|YES|on|ON) GI_G5_RECEIVER_ENV=1 ;;
    *) die "METALLUM_GI_G5_RECEIVER must be a boolean diagnostic flag" ;;
esac
if [ "$GI_G5_RECEIVER_ENV" -eq 1 ]; then
    case "${METALLUM_GI_G5_RECEIVER_ARM:-}" in
        control)
            GI_G5_RECEIVER_ARM=control
            GI_G5_FIELD_KIND=zero
            GI_G5_RECEIVER_ACTIVE=false
            ;;
        candidate)
            GI_G5_RECEIVER_ARM=candidate
            GI_G5_FIELD_KIND=zero
            GI_G5_RECEIVER_ACTIVE=true
            ;;
        field)
            GI_G5_RECEIVER_ARM=field
            GI_G5_FIELD_KIND=g4
            GI_G5_RECEIVER_ACTIVE=true
            ;;
        *)
            die "METALLUM_GI_G5_RECEIVER_ARM must be control, candidate, or field when G5 is requested"
            ;;
    esac
elif [ -n "${METALLUM_GI_G5_RECEIVER_ARM:-}" ]; then
    die "METALLUM_GI_G5_RECEIVER_ARM requires METALLUM_GI_G5_RECEIVER=1"
fi
RUNTIME_GI_MODE="$RENDERER_GI_MODE"
if [ "$GI_VISUAL_PROBE" -eq 1 ]; then
    [ "$GI_G2_CAPTURE_ENV" -eq 0 ] \
        && [ "$GI_G3_INJECT_ENV" -eq 0 ] \
        && [ "$GI_G4_TRANSPORT_ENV" -eq 0 ] \
        && [ "$GI_G5_RECEIVER_ENV" -eq 0 ] \
        || die "GI visual probe rejects explicit G2/G3/G4/G5 diagnostic flags"
    require_value "$ROUTE_ID" "hdrtest-gi-visual-probe-v1" "GI visual probe route"
    require_value "$ROUTE_SHA256" "dfd3c447d95ac9334a56e1bbb156f53ffa2fc5142280e8fe9be06076e910b2e1" "GI visual probe route digest"
    require_value "$ROUTE_KIND" "GI_VISUAL_PROBE" "GI visual probe workload"
    require_value "$MEASURE_FRAMES" "900" "GI visual probe measurement frames"
    require_value "$WIDTH" "3024" "GI visual probe render width"
    require_value "$HEIGHT" "1964" "GI visual probe render height"
    require_value "$REFRESH_HZ" "120" "GI visual probe refresh rate"
    require_value "$GRAPHICS_PRESET" "fancy" "GI visual probe graphics preset"
    require_value "$HDR_MODE" "scene" "GI visual probe HDR output mode"
    require_value "$WARMUP_FRAMES" "1800" "GI visual probe warmup frames"
    require_value "$TIMING_DETAIL" "0" "GI visual probe timing detail"
    require_value "$METAL_VALIDATION" "0" "GI visual probe Metal Validation mode"
    require_value "$METALFX_MODE" "OFF" "GI visual probe MetalFX mode"
    if [ "$GI_VISUAL_PROBE_ARM" = "on" ]; then
        RUNTIME_GI_MODE=g6_live
        require_value "$SETTINGS_ID" "native-hdr-fancy-gi-live-v1" "GI visual probe ON settings"
        require_value "$SETTINGS_SPEC_SHA256" \
            "8bf845b207048cc442620b2ef0e8bc05e6ca6721bc27018ab6121eba8ebd1817" \
            "GI visual probe ON settings specification digest"
    else
        RUNTIME_GI_MODE=off
        require_value "$SETTINGS_ID" "native-hdr-fancy-v1" "GI visual probe OFF settings"
        require_value "$SETTINGS_SPEC_SHA256" \
            "92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e" \
            "GI visual probe OFF settings specification digest"
    fi
elif [ "$GI_LIVE" -eq 1 ]; then
    [ "$GI_G2_CAPTURE_ENV" -eq 0 ] \
        && [ "$GI_G3_INJECT_ENV" -eq 0 ] \
        && [ "$GI_G4_TRANSPORT_ENV" -eq 0 ] \
        && [ "$GI_G5_RECEIVER_ENV" -eq 0 ] \
        || die "G6 live mode rejects explicit G2/G3/G4/G5 diagnostic flags"
    RUNTIME_GI_MODE=g6_live
    case "$ROUTE_ID" in
        hdrtest-torch-toggle-v1)
            require_value "$ROUTE_SHA256" \
                "7f0a03058371964e81ef95002644a1744793def24958ccebb8c808fb91e46cc8" \
                "G6 torch-toggle benchmark route digest"
            require_value "$ROUTE_KIND" "TORCH_TOGGLE" "G6 torch-toggle route workload"
            require_value "$MEASURE_FRAMES" "3000" "G6 live/capture measurement frames"
            ;;
        hdrtest-gi-g6-matrix-v1)
            require_value "$ROUTE_SHA256" \
                "e7bc60c8082ef1bf98c487b6158f0c08b8595fc55deb1290f97d06fa412e3934" \
                "G6 matrix benchmark route digest"
            require_value "$ROUTE_KIND" "GI_G6_MATRIX" "G6 matrix route workload"
            require_value "$MEASURE_FRAMES" "3600" "G6 matrix measurement frames"
            ;;
        *)
            die "--gi-live requires the tracked torch-toggle or G6 matrix route"
            ;;
    esac
    require_value "$SETTINGS_ID" "native-hdr-fancy-gi-live-v1" "G6 settings profile"
    require_value "$SETTINGS_SPEC_SHA256" \
        "8bf845b207048cc442620b2ef0e8bc05e6ca6721bc27018ab6121eba8ebd1817" \
        "G6 settings specification digest"
    require_value "$SETTINGS_SHA256" \
        "46bda4e1537db4ce145d4322ec985a034a30dd3b371dc918e6f4d60b36960c6c" \
        "G6 resolved settings digest"
    require_value "$WIDTH" "3024" "G6 render width"
    require_value "$HEIGHT" "1964" "G6 render height"
    require_value "$REFRESH_HZ" "120" "G6 refresh rate"
    require_value "$GRAPHICS_PRESET" "fancy" "G6 graphics preset"
    require_value "$HDR_MODE" "scene" "G6 HDR output mode"
    require_value "$WARMUP_FRAMES" "1800" "G6 warmup frames"
    require_value "$TIMING_DETAIL" "0" "G6 timing detail"
    require_value "$METAL_VALIDATION" "0" "G6 Metal Validation mode"
    require_value "$METALFX_MODE" "OFF" "G6 MetalFX mode"
elif [ "$GI_G5_RECEIVER_ENV" -eq 1 ]; then
    # G5 owns the upstream private-resource lifecycle through GiRuntimeStages.
    # Keeping the standalone diagnostic flags OFF is essential: control and
    # candidate need create-time-zero G4 textures, not a populated G4 field or
    # a misleading standalone G4 READY admission.
    [ "$GI_G2_CAPTURE_ENV" -eq 0 ] \
        && [ "$GI_G3_INJECT_ENV" -eq 0 ] \
        && [ "$GI_G4_TRANSPORT_ENV" -eq 0 ] \
        || die "G5 receiver arms reject explicit G2/G3/G4 diagnostic flags"
    RUNTIME_GI_MODE=g5_vertex_receiver
    require_value "$ROUTE_ID" "gi-g4-overworld-v1" "G5 benchmark route"
    require_value "$ROUTE_SHA256" \
        "d321131b314bb22cee354e3cf48606712d414d44a84e6ed00230e70d9c65839d" \
        "G5 benchmark route digest"
    require_value "$SETTINGS_ID" "native-hdr-fancy-v1" "G5 settings profile"
    require_value "$SETTINGS_SPEC_SHA256" \
        "92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e" \
        "G5 settings specification digest"
    require_value "$SETTINGS_SHA256" \
        "fcf752aebd45a576e13cc19b446b954014b66e46a78c79e435289314d3b4ebb3" \
        "G5 resolved settings digest"
    require_value "$WIDTH" "3024" "G5 render width"
    require_value "$HEIGHT" "1964" "G5 render height"
    require_value "$REFRESH_HZ" "120" "G5 refresh rate"
    require_value "$GRAPHICS_PRESET" "fancy" "G5 graphics preset"
    require_value "$HDR_MODE" "scene" "G5 HDR output mode"
    require_value "$WARMUP_FRAMES" "600" "G5 warmup frames"
    require_value "$MEASURE_FRAMES" "600" "G5 measurement frames"
    require_value "$TIMING_DETAIL" "1" "G5 timing detail"
    require_value "$METAL_VALIDATION" "0" "G5 Metal Validation mode"
    require_value "$METALFX_MODE" "OFF" "G5 MetalFX mode"
    require_value "$CAPTURE_REFERENCE" "0" "G5 reference capture mode"
    require_value "$FI_VALIDATION" "0" "G5 frame interpolation validation mode"
    require_value "$VERTEX_REFLECTION_EXPERIMENT" "0" \
        "G5 vertex-reflection experiment mode"
elif [ "$GI_G4_TRANSPORT_ENV" -eq 1 ]; then
    # G4 consumes accepted G2 cells and the completed G3 field. Make both
    # prerequisites explicit in the launched process instead of relying on a
    # caller to discover and set transitive diagnostic flags.
    GI_G2_CAPTURE_ENV=1
    GI_G3_INJECT_ENV=1
    RUNTIME_GI_MODE=g4_transport
    require_value "$ROUTE_ID" "gi-g4-overworld-v1" "G4 benchmark route"
    require_value "$ROUTE_SHA256" \
        "d321131b314bb22cee354e3cf48606712d414d44a84e6ed00230e70d9c65839d" \
        "G4 benchmark route digest"
    require_value "$SETTINGS_ID" "native-hdr-fancy-v1" "G4 settings profile"
    require_value "$SETTINGS_SPEC_SHA256" \
        "92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e" \
        "G4 settings specification digest"
    require_value "$SETTINGS_SHA256" \
        "fcf752aebd45a576e13cc19b446b954014b66e46a78c79e435289314d3b4ebb3" \
        "G4 resolved settings digest"
    require_value "$WIDTH" "3024" "G4 render width"
    require_value "$HEIGHT" "1964" "G4 render height"
    require_value "$REFRESH_HZ" "120" "G4 refresh rate"
    require_value "$GRAPHICS_PRESET" "fancy" "G4 graphics preset"
    require_value "$HDR_MODE" "scene" "G4 HDR output mode"
    require_value "$WARMUP_FRAMES" "600" "G4 warmup frames"
    require_value "$MEASURE_FRAMES" "600" "G4 measurement frames"
    require_value "$TIMING_DETAIL" "1" "G4 timing detail"
    require_value "$METAL_VALIDATION" "0" "G4 Metal Validation mode"
    require_value "$METALFX_MODE" "OFF" "G4 MetalFX mode"
    require_value "$CAPTURE_REFERENCE" "0" "G4 reference capture mode"
    require_value "$FI_VALIDATION" "0" "G4 frame interpolation validation mode"
    require_value "$VERTEX_REFLECTION_EXPERIMENT" "0" \
        "G4 vertex-reflection experiment mode"
elif [ "$GI_G3_INJECT_ENV" -eq 1 ]; then
    GI_G2_CAPTURE_ENV=1
    RUNTIME_GI_MODE=g3_inject
fi
RELEASE_PROFILE_CANDIDATE=0
if [ "$TIMING_DETAIL" -eq 0 ] \
    && [ "$METAL_VALIDATION" -eq 0 ] \
    && [ "$CAPTURE_REFERENCE" -eq 0 ] \
    && [ "$FI_VALIDATION" -eq 0 ] \
    && [ "$WARMUP_FRAMES" -eq 1800 ] \
    && [ "$MEASURE_FRAMES" -eq 3000 ] \
    && [ "$GI_LIVE" -eq 0 ]; then
    RELEASE_PROFILE_CANDIDATE=1
fi
metallum_require_release_gi_off \
    "$RELEASE_PROFILE_CANDIDATE" \
    "$GI_G2_CAPTURE_ENV" "$GI_G3_INJECT_ENV" "$GI_G4_TRANSPORT_ENV" \
    "$GI_G5_RECEIVER_ENV" \
    || die "G2/G3/G4/G5 diagnostics cannot run under the release-contract profile"
if [ "$settings_field_count" -eq 34 ]; then
    require_value "$RENDERER_LIGHTING" "$BENCHMARK_RENDERER_IMPROVED_LIGHTING" \
        "tracked renderer improvedLighting"
    require_value "$RENDERER_PRESET" "$BENCHMARK_RENDERER_LIGHTING_PRESET" \
        "tracked renderer lightingPreset"
    require_value "$RENDERER_GI_MODE" "$BENCHMARK_RENDERER_GI_MODE" \
        "tracked renderer globalIllumination"
fi
if [ "$ROUTE_KIND" = "L6_DYNAMIC_SHADOW" ]; then
    require_value "$RENDERER_LIGHTING" "true" "L6 dynamic route renderer improvedLighting"
fi
RENDERER_VALUES_BEFORE="$RENDERER_SCHEMA/$RENDERER_LIGHTING/$RENDERER_PRESET/$RENDERER_INTERPOLATION/$RENDERER_VOXEL_DEBUG/$RENDERER_GI_MODE"

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
if [ "$RUNTIME_GI_MODE" = "g4_transport" ] && [ "$dirty_flag" -ne 0 ]; then
    die "G4 Tier B evidence requires a clean worktree"
fi
if [ "$RUNTIME_GI_MODE" = "g5_vertex_receiver" ] && [ "$dirty_flag" -ne 0 ]; then
    die "G5 Tier B evidence requires a clean worktree"
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
TRANSCRIPT_LOG="$OUTPUT_DIR/$stem.transcript.log"
SUMMARY_JSON="$OUTPUT_DIR/$stem.summary.json"
ACCEPTED_JSON="$OUTPUT_DIR/$stem.accepted.json"

: > "$TRANSCRIPT_LOG" || die "failed to create benchmark transcript: $TRANSCRIPT_LOG"
exec 3>&1 4>&2
TRANSCRIPT_PIPE="$OUTPUT_DIR/.$stem.transcript.pipe"
[ ! -e "$TRANSCRIPT_PIPE" ] \
    || die "benchmark transcript pipe already exists: $TRANSCRIPT_PIPE"
mkfifo "$TRANSCRIPT_PIPE" \
    || die "failed to create benchmark transcript pipe: $TRANSCRIPT_PIPE"
tee -a "$TRANSCRIPT_LOG" < "$TRANSCRIPT_PIPE" >&3 &
TRANSCRIPT_TEE_PID=$!
exec > "$TRANSCRIPT_PIPE" 2>&1
rm -f "$TRANSCRIPT_PIPE"
TRANSCRIPT_ACTIVE=1
if [ "$FI_VALIDATION" -eq 0 ] && [ "$GI_VISUAL_PROBE" -eq 0 ]; then
    trap 'transcript_only_cleanup $?' EXIT
    trap 'exit 129' HUP
    trap 'exit 130' INT
    trap 'exit 143' TERM
fi

echo "Metallum benchmark preflight passed"
echo "  display: $MONITOR_NAME, ${WIDTH}x${HEIGHT}@${REFRESH_HZ}, exclusive fullscreen"
if [ "$FI_VALIDATION" -eq 1 ]; then
    echo "  pacing: VSync on, maxFps=$MAX_FPS"
else
    echo "  pacing: VSync off, maxFps=$MAX_FPS"
fi
echo "  scene: output=$HDR_MODE, source=sRGB, lighting=$EXPECTED_LIGHTING_MODEL ($RENDERER_LIGHTING/$LIGHTING_PRESET), renderer-schema=$RENDERER_SCHEMA, bloom=$HDR_BLOOM_STRENGTH, strength=$HDR_STRENGTH"
if [ "$GI_VISUAL_PROBE" -eq 1 ]; then
    echo "GI_VISUAL_PROBE_REQUEST arm=$GI_VISUAL_PROBE_ARM runtime=$RUNTIME_GI_MODE clone_only=true non_attested=true route=$ROUTE_ID status=REQUESTED"
elif [ "$RUNTIME_GI_MODE" = "g6_live" ]; then
    echo "GI_G6_REQUEST mode=g6_live persistent=true dynamic=true receiver=true diagnostic_flags=false route=$ROUTE_ID status=REQUESTED"
elif [ "$RUNTIME_GI_MODE" = "g5_vertex_receiver" ]; then
    echo "GI_G5_RECEIVER_REQUEST mode=g5_vertex_receiver arm=$GI_G5_RECEIVER_ARM receiver=$GI_G5_RECEIVER_ACTIVE field=$GI_G5_FIELD_KIND g2_resources=true g3_resources=true shared_g4_resources=true explicit_g4_request=false vertex_stage=true fragment_receiver=false diagnostic_only=true release=false status=REQUESTED"
elif [ "$RUNTIME_GI_MODE" = "g4_transport" ]; then
    echo "GI_G4_TRANSPORT_REQUEST mode=g4_transport g2_capture=true g3_inject=true frozen_near_cascade=true jacobi_iterations=1 field_only=true receiver=false image_binding=false diagnostic_only=true release=false status=REQUESTED"
elif [ "$RUNTIME_GI_MODE" = "g3_inject" ]; then
    echo "GI_G3_ADMISSION mode=g3_inject field_only=true bounce=false image_binding=false status=REQUESTED"
else
    echo "GI_OFF_ADMISSION mode=$RENDERER_GI_MODE resources=0 passes=0 bindings=0 status=PASS"
fi
echo "  settings: $SETTINGS_ID ($SETTINGS_SHA256; spec $SETTINGS_SPEC_SHA256)"
echo "  workload: preset=$GRAPHICS_PRESET, render/simulation=${RENDER_DISTANCE}/${SIMULATION_DISTANCE}, entities=$ENTITY_DISTANCE_SCALING, particles=$PARTICLE_SETTING, mipmaps=$MIPMAP_LEVELS"
echo "  runtime contract: GUI scale=auto, Sodium workers=$SODIUM_WORKER_THREADS, packs=$ACTIVE_RESOURCE_PACK_IDS"
echo "  MetalFX: $METALFX_MODE (persistent config remains off)"
if [ "$VERTEX_REFLECTION_EXPERIMENT" -eq 1 ]; then
    echo "  water reflection: $WATER_REFLECTION_QUALITY (face=$WATER_REFLECTION_FACE_AWARE, first-surface=$WATER_REFLECTION_FIRST_SURFACE, confidence=$WATER_REFLECTION_CONFIDENCE)"
fi
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
    GI_G6_MATRIX)
        echo "  G6 matrix: orbit=$G6_MATRIX_ORBIT_START_FRAME..$G6_MATRIX_ORBIT_END_FRAME, lava=$G6_MATRIX_LAVA_APPLY_FRAME..$G6_MATRIX_LAVA_REMOVE_FRAME, reloads=$G6_MATRIX_CHUNK_RELOAD_FRAME/$G6_MATRIX_RESOURCE_RELOAD_FRAME, stream=$G6_MATRIX_STREAM_START_FRAME, teleport=$G6_MATRIX_TELEPORT_FRAME..$G6_MATRIX_TELEPORT_RETURN_FRAME, Nether=$G6_MATRIX_NETHER_ENTER_FRAME..$G6_MATRIX_NETHER_RETURN_FRAME"
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
echo "  transcript: $TRANSCRIPT_LOG"
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
    if [ "${TEMPORARY_SETTINGS:-0}" -eq 1 ]; then
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
    if [ "${TEMPORARY_SETTINGS:-0}" -eq 0 ] \
        && [ -n "${OPTIONS_FILE_BACKUP:-}" ] \
        && [ -f "$OPTIONS_FILE_BACKUP" ]; then
        cp "$OPTIONS_FILE_BACKUP" "$OPTIONS_FILE" || cleanup_status=2
    fi

    if [ "${TEMPORARY_SETTINGS:-0}" -eq 0 ]; then
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
        renderer_after="$(renderer_value schemaVersion)/$(renderer_value improvedLighting)/$(renderer_value lightingPreset)/$(renderer_value frameInterpolation)/$renderer_debug_after/$(renderer_value globalIllumination)"
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

    if [ "${TEMPORARY_SETTINGS:-0}" -eq 1 ]; then
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
    if ! finish_transcript && [ "$original_status" -eq 0 ]; then
        original_status=2
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
VERTEX_REFLECTION_JAVA_TOOL_OPTIONS=${JAVA_TOOL_OPTIONS:-}
if [ "$VERTEX_REFLECTION_EXPERIMENT" -eq 1 ]; then
    VERTEX_REFLECTION_JAVA_TOOL_OPTIONS="${VERTEX_REFLECTION_JAVA_TOOL_OPTIONS} -Dmetallum.vertex.reflection.runtime=true -Dmetallum.waterReflection.faceAwareAppearance=${WATER_REFLECTION_FACE_AWARE} -Dmetallum.waterReflection.firstSurfaceBiasedIntegration=${WATER_REFLECTION_FIRST_SURFACE} -Dmetallum.waterReflection.representationConfidence=${WATER_REFLECTION_CONFIDENCE}"
fi
if [ "$GI_G5_RECEIVER_ENV" -eq 1 ]; then
    # Reflected terrain is outside the single-raster G5 cost contract.  The JVM property
    # overrides any persisted live option, while runtime captureMode also keeps later UI
    # toggles dormant for the rest of this diagnostic process.
    VERTEX_REFLECTION_JAVA_TOOL_OPTIONS="${VERTEX_REFLECTION_JAVA_TOOL_OPTIONS} -Dmetallum.planar_reflections=false"
fi
set +e
METALLUM_GI_G2_CAPTURE="$GI_G2_CAPTURE_ENV" \
METALLUM_GI_G3_INJECT="$GI_G3_INJECT_ENV" \
METALLUM_GI_G4_TRANSPORT="$GI_G4_TRANSPORT_ENV" \
METALLUM_GI_G5_RECEIVER="$GI_G5_RECEIVER_ENV" \
METALLUM_GI_G5_RECEIVER_ARM="$GI_G5_RECEIVER_ARM" \
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
METALLUM_BENCHMARK_EXPECTED_LIGHTING_MODEL="$EXPECTED_LIGHTING_MODEL" \
METALLUM_BENCHMARK_GI_MODE="$RUNTIME_GI_MODE" \
METALLUM_VERTEX_REFLECTION_EXPERIMENT="$VERTEX_REFLECTION_EXPERIMENT" \
METALLUM_BENCHMARK_WATER_REFLECTION_FACE_AWARE="$WATER_REFLECTION_FACE_AWARE" \
METALLUM_BENCHMARK_WATER_REFLECTION_FIRST_SURFACE="$WATER_REFLECTION_FIRST_SURFACE" \
METALLUM_BENCHMARK_WATER_REFLECTION_CONFIDENCE="$WATER_REFLECTION_CONFIDENCE" \
JAVA_TOOL_OPTIONS="$VERTEX_REFLECTION_JAVA_TOOL_OPTIONS" \
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
METALLUM_BENCHMARK_G6_MATRIX_HELD_ITEM="$G6_MATRIX_HELD_ITEM" \
METALLUM_BENCHMARK_G6_MATRIX_ENTITY_ITEM="$G6_MATRIX_ENTITY_ITEM" \
METALLUM_BENCHMARK_G6_MATRIX_ENTITY_POSITION_X="$G6_MATRIX_ENTITY_POSITION_X" \
METALLUM_BENCHMARK_G6_MATRIX_ENTITY_POSITION_Y="$G6_MATRIX_ENTITY_POSITION_Y" \
METALLUM_BENCHMARK_G6_MATRIX_ENTITY_POSITION_Z="$G6_MATRIX_ENTITY_POSITION_Z" \
METALLUM_BENCHMARK_G6_MATRIX_LAVA_POSITION_X="$G6_MATRIX_LAVA_POSITION_X" \
METALLUM_BENCHMARK_G6_MATRIX_LAVA_POSITION_Y="$G6_MATRIX_LAVA_POSITION_Y" \
METALLUM_BENCHMARK_G6_MATRIX_LAVA_POSITION_Z="$G6_MATRIX_LAVA_POSITION_Z" \
METALLUM_BENCHMARK_G6_MATRIX_LAVA_INITIAL_BLOCK="$G6_MATRIX_LAVA_INITIAL_BLOCK" \
METALLUM_BENCHMARK_G6_MATRIX_LAVA_APPLY_FRAME="$G6_MATRIX_LAVA_APPLY_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_LAVA_REMOVE_FRAME="$G6_MATRIX_LAVA_REMOVE_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_ORBIT_START_FRAME="$G6_MATRIX_ORBIT_START_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_ORBIT_END_FRAME="$G6_MATRIX_ORBIT_END_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_ORBIT_YAW_AMPLITUDE_DEGREES="$G6_MATRIX_ORBIT_YAW_AMPLITUDE_DEGREES" \
METALLUM_BENCHMARK_G6_MATRIX_ORBIT_PITCH_AMPLITUDE_DEGREES="$G6_MATRIX_ORBIT_PITCH_AMPLITUDE_DEGREES" \
METALLUM_BENCHMARK_G6_MATRIX_ORBIT_PERIOD_FRAMES="$G6_MATRIX_ORBIT_PERIOD_FRAMES" \
METALLUM_BENCHMARK_G6_MATRIX_CHUNK_RELOAD_FRAME="$G6_MATRIX_CHUNK_RELOAD_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_RESOURCE_RELOAD_FRAME="$G6_MATRIX_RESOURCE_RELOAD_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_DAY_FRAME="$G6_MATRIX_DAY_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_DAY_TICKS="$G6_MATRIX_DAY_TICKS" \
METALLUM_BENCHMARK_G6_MATRIX_NIGHT_FRAME="$G6_MATRIX_NIGHT_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_NIGHT_TICKS="$G6_MATRIX_NIGHT_TICKS" \
METALLUM_BENCHMARK_G6_MATRIX_RAIN_FRAME="$G6_MATRIX_RAIN_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_CLEAR_FRAME="$G6_MATRIX_CLEAR_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_START_FRAME="$G6_MATRIX_STREAM_START_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_STEP_FRAMES="$G6_MATRIX_STREAM_STEP_FRAMES" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_0="$G6_MATRIX_STREAM_OFFSET_0" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_1="$G6_MATRIX_STREAM_OFFSET_1" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_2="$G6_MATRIX_STREAM_OFFSET_2" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_3="$G6_MATRIX_STREAM_OFFSET_3" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_4="$G6_MATRIX_STREAM_OFFSET_4" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_5="$G6_MATRIX_STREAM_OFFSET_5" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_6="$G6_MATRIX_STREAM_OFFSET_6" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_7="$G6_MATRIX_STREAM_OFFSET_7" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_0="$G6_MATRIX_STREAM_Y_OFFSET_0" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_1="$G6_MATRIX_STREAM_Y_OFFSET_1" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_2="$G6_MATRIX_STREAM_Y_OFFSET_2" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_3="$G6_MATRIX_STREAM_Y_OFFSET_3" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_4="$G6_MATRIX_STREAM_Y_OFFSET_4" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_5="$G6_MATRIX_STREAM_Y_OFFSET_5" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_6="$G6_MATRIX_STREAM_Y_OFFSET_6" \
METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_7="$G6_MATRIX_STREAM_Y_OFFSET_7" \
METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_FRAME="$G6_MATRIX_TELEPORT_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_OFFSET_X="$G6_MATRIX_TELEPORT_OFFSET_X" \
METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_OFFSET_Y="$G6_MATRIX_TELEPORT_OFFSET_Y" \
METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_OFFSET_Z="$G6_MATRIX_TELEPORT_OFFSET_Z" \
METALLUM_BENCHMARK_G6_MATRIX_TELEPORT_RETURN_FRAME="$G6_MATRIX_TELEPORT_RETURN_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_NETHER_ENTER_FRAME="$G6_MATRIX_NETHER_ENTER_FRAME" \
METALLUM_BENCHMARK_G6_MATRIX_NETHER_POSITION_X="$G6_MATRIX_NETHER_POSITION_X" \
METALLUM_BENCHMARK_G6_MATRIX_NETHER_POSITION_Y="$G6_MATRIX_NETHER_POSITION_Y" \
METALLUM_BENCHMARK_G6_MATRIX_NETHER_POSITION_Z="$G6_MATRIX_NETHER_POSITION_Z" \
METALLUM_BENCHMARK_G6_MATRIX_NETHER_RETURN_FRAME="$G6_MATRIX_NETHER_RETURN_FRAME" \
METALLUM_BENCHMARK_VISUAL_PROBE_RIG_ID="$GI_VISUAL_PROBE_RIG_ID" \
METALLUM_BENCHMARK_VISUAL_PROBE_TORCH_POSITION_X="$GI_VISUAL_PROBE_TORCH_POSITION_X" \
METALLUM_BENCHMARK_VISUAL_PROBE_TORCH_POSITION_Y="$GI_VISUAL_PROBE_TORCH_POSITION_Y" \
METALLUM_BENCHMARK_VISUAL_PROBE_TORCH_POSITION_Z="$GI_VISUAL_PROBE_TORCH_POSITION_Z" \
METALLUM_BENCHMARK_VISUAL_PROBE_ORBIT_START_FRAME="$GI_VISUAL_PROBE_ORBIT_START_FRAME" \
METALLUM_BENCHMARK_VISUAL_PROBE_ORBIT_END_FRAME="$GI_VISUAL_PROBE_ORBIT_END_FRAME" \
METALLUM_BENCHMARK_VISUAL_PROBE_ORBIT_TRANSLATION_RADIUS_BLOCKS="$GI_VISUAL_PROBE_ORBIT_TRANSLATION_RADIUS_BLOCKS" \
METALLUM_BENCHMARK_VISUAL_PROBE_ORBIT_YAW_AMPLITUDE_DEGREES="$GI_VISUAL_PROBE_ORBIT_YAW_AMPLITUDE_DEGREES" \
METALLUM_BENCHMARK_VISUAL_PROBE_ORBIT_PITCH_AMPLITUDE_DEGREES="$GI_VISUAL_PROBE_ORBIT_PITCH_AMPLITUDE_DEGREES" \
METALLUM_BENCHMARK_VISUAL_PROBE_ORBIT_PERIOD_FRAMES="$GI_VISUAL_PROBE_ORBIT_PERIOD_FRAMES" \
METALLUM_BENCHMARK_VISUAL_PROBE_CAPTURE_FRAME_0="$GI_VISUAL_PROBE_CAPTURE_FRAME_0" \
METALLUM_BENCHMARK_VISUAL_PROBE_CAPTURE_FRAME_1="$GI_VISUAL_PROBE_CAPTURE_FRAME_1" \
METALLUM_BENCHMARK_VISUAL_PROBE_CAPTURE_FRAME_2="$GI_VISUAL_PROBE_CAPTURE_FRAME_2" \
METALLUM_BENCHMARK_VISUAL_PROBE_CAPTURE_FRAME_3="$GI_VISUAL_PROBE_CAPTURE_FRAME_3" \
METALLUM_GI_G6_DEBUG_PROBE="$GI_G6_DEBUG_PROBE" \
METALLUM_GPU_TIMING="$GPU_TIMING_ENV" \
METALLUM_GPU_TIMING_DETAIL="$TIMING_DETAIL" \
METALLUM_GPU_TIMING_REPORT="$RAW_REPORT" \
    ./gradlew --no-daemon runClient --console=plain \
        "--args=--username $PLAYER_NAME --uuid $PLAYER_UUID --quickPlaySingleplayer $RUN_WORLD_NAME" \
        2>&1 | tee "$CONSOLE_LOG"
pipeline_status=("${PIPESTATUS[@]}")
gradle_status=${pipeline_status[0]}
console_tee_status=${pipeline_status[1]}
set -e

LATEST_LOG="$RUN_DIR/logs/latest.log"
if [ -f "$LATEST_LOG" ]; then
    latest_mtime=$(stat -f %m "$LATEST_LOG")
    cp "$LATEST_LOG" "$MINECRAFT_LOG"
fi
[ "$gradle_status" -eq 0 ] || die "runClient exited with status $gradle_status (console: $CONSOLE_LOG)"
[ "$console_tee_status" -eq 0 ] \
    || die "failed to preserve Gradle output in console/transcript artifacts"
[ -s "$MINECRAFT_LOG" ] || die "Minecraft did not produce a fresh log"
[ "$latest_mtime" -ge "$start_epoch" ] || die "Minecraft log predates this benchmark run"

if grep -Fq "METALLUM_BENCHMARK EVENT=FAIL" "$MINECRAFT_LOG"; then
    grep -F "METALLUM_BENCHMARK EVENT=FAIL" "$MINECRAFT_LOG" >&2 || true
    die "benchmark controller reported failure"
fi
screenshot_request_count=$(grep -Fc "METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED" \
    "$MINECRAFT_LOG" || true)
if [ "$GI_VISUAL_PROBE" -eq 1 ]; then
    [ "$screenshot_request_count" -eq 0 ] \
        || die "GI visual probe must use only its four dedicated screenshot markers"
elif [ "$CAPTURE_REFERENCE" -eq 0 ]; then
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
segment_start="METALLUM_BENCHMARK EVENT=SEGMENT_START index=1 total=1 mode=$METALFX_MODE warmup=$WARMUP_FRAMES measure=$MEASURE_FRAMES"
measure_start="METALLUM_BENCHMARK EVENT=MEASURE_START index=1 mode=$METALFX_MODE presented_frame=$WARMUP_FRAMES"
final_presented=$((WARMUP_FRAMES + MEASURE_FRAMES))
measure_end="METALLUM_BENCHMARK EVENT=MEASURE_END index=1 mode=$METALFX_MODE presented_frame=$final_presented"

route_apply_count=$(grep -Fc "$route_apply" "$MINECRAFT_LOG" || true)
server_frozen_count=$(grep -Fc "$server_frozen" "$MINECRAFT_LOG" || true)
route_ready_count=$(grep -Fc "$route_ready" "$MINECRAFT_LOG" || true)
route_measure_start_count=$(grep -Fc "$route_measure_start" "$MINECRAFT_LOG" || true)
route_measure_end_count=$(grep -Fc "$route_measure_end" "$MINECRAFT_LOG" || true)
segment_start_count=$(grep -Fc "$segment_start" "$MINECRAFT_LOG" || true)
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
[ "$segment_start_count" -eq 1 ] \
    || die "expected exactly one matching SEGMENT_START marker (found $segment_start_count)"
[ "$measure_start_count" -eq 1 ] \
    || die "expected exactly one matching MEASURE_START marker (found $measure_start_count)"
[ "$measure_end_count" -eq 1 ] \
    || die "expected exactly one matching MEASURE_END marker (found $measure_end_count)"

route_apply_line=$(grep -nF "$route_apply" "$MINECRAFT_LOG" | cut -d: -f1)
server_frozen_line=$(grep -nF "$server_frozen" "$MINECRAFT_LOG" | cut -d: -f1)
route_ready_line=$(grep -nF "$route_ready" "$MINECRAFT_LOG" | cut -d: -f1)
route_measure_start_line=$(grep -nF "$route_measure_start" "$MINECRAFT_LOG" | cut -d: -f1)
route_measure_end_line=$(grep -nF "$route_measure_end" "$MINECRAFT_LOG" | cut -d: -f1)
segment_start_line=$(grep -nF "$segment_start" "$MINECRAFT_LOG" | cut -d: -f1)
measure_start_line=$(grep -nF "$measure_start" "$MINECRAFT_LOG" | cut -d: -f1)
measure_end_line=$(grep -nF "$measure_end" "$MINECRAFT_LOG" | cut -d: -f1)
[ "$route_apply_line" -lt "$server_frozen_line" ] \
    && [ "$server_frozen_line" -lt "$route_ready_line" ] \
    && [ "$route_ready_line" -lt "$segment_start_line" ] \
    && [ "$route_ready_line" -lt "$route_measure_start_line" ] \
    && [ "$route_measure_start_line" -lt "$measure_start_line" ] \
    && [ "$measure_start_line" -lt "$measure_end_line" ] \
    && [ "$measure_end_line" -lt "$route_measure_end_line" ] \
    || die "deterministic route markers are out of order"

if [ "$GI_VISUAL_PROBE" -eq 1 ]; then
    probe_rig="METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_RIG_APPLIED route=$ROUTE_ID rig=$GI_VISUAL_PROBE_RIG_ID clone_only=true direct_path=OCCLUDED bounce_path=OPEN status=PASS"
    probe_rig_count=$(grep -Fc "$probe_rig" "$MINECRAFT_LOG" || true)
    [ "$probe_rig_count" -eq 1 ] \
        || die "expected exactly one applied GI visual probe rig marker (found $probe_rig_count)"
    probe_rig_line=$(grep -nF "$probe_rig" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$probe_rig_line" -lt "$route_apply_line" ] \
        || die "GI visual probe rig must be applied before route readiness"
    probe_ready=$(grep -E \
        "METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_READY route=$ROUTE_ID rig=$GI_VISUAL_PROBE_RIG_ID measured_frame=[0-9]+ field_generation=[1-9][0-9]* source_tick=[0-9]+ ready_mask=7 exact_bind=true all_cascades=true status=PASS$" \
        "$MINECRAFT_LOG" || true)
    probe_ready_count=$(printf '%s\n' "$probe_ready" | grep -Fc 'GI_VISUAL_PROBE_READY' || true)
    probe_ready_line=0
    if [ "$GI_VISUAL_PROBE_ARM" = "on" ]; then
        [ "$probe_ready_count" -eq 1 ] \
            || die "GI visual probe ON arm did not emit exactly one current all-cascade readiness marker"
        probe_ready_line=$(grep -nF "$probe_ready" "$MINECRAFT_LOG" | cut -d: -f1)
        probe_gpu_direct=$(grep -E \
            "METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_GPU_DIRECT route=$ROUTE_ID rig=$GI_VISUAL_PROBE_RIG_ID measured_frame=[0-9]+ requested_frame=[0-9]+ latency_frames=([0-9]|[1-5][0-9]|60) identity=.* red_direct=true red_dominates=true white=OCCLUDED baffle=SOLID empty=AIR .* status=PASS$" \
            "$MINECRAFT_LOG" || true)
        probe_gpu_direct_count=$(printf '%s\\n' "$probe_gpu_direct" \
            | grep -Fc 'GI_VISUAL_PROBE_GPU_DIRECT' || true)
        [ "$probe_gpu_direct_count" -eq 1 ] \
            || die "GI visual probe ON arm did not emit exactly one passing G3 GPU direct probe"
        probe_gpu_direct_line=$(grep -nF "$probe_gpu_direct" "$MINECRAFT_LOG" | cut -d: -f1)
        [ "$probe_ready_line" -lt "$probe_gpu_direct_line" ] \
            || die "GI visual probe G3 GPU direct probe preceded its current post-torch readiness receipt"
        probe_gpu_field=$(grep -E \
            "METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_GPU_FIELD route=$ROUTE_ID rig=$GI_VISUAL_PROBE_RIG_ID measured_frame=[0-9]+ requested_frame=[0-9]+ latency_frames=([0-9]|[1-5][0-9]|60) identity=.* samples=7 c0_white_nonzero=true c0_red_nonzero=true red_dominates=true baffle=DIAGNOSTIC sampleable=true .* status=PASS$" \
            "$MINECRAFT_LOG" || true)
        probe_gpu_field_count=$(printf '%s\n' "$probe_gpu_field" \
            | grep -Fc 'GI_VISUAL_PROBE_GPU_FIELD' || true)
        [ "$probe_gpu_field_count" -eq 1 ] \
            || die "GI visual probe ON arm did not emit exactly one passing G6 GPU field probe"
        probe_gpu_field_line=$(grep -nF "$probe_gpu_field" "$MINECRAFT_LOG" | cut -d: -f1)
        [ "$probe_gpu_direct_line" -lt "$probe_gpu_field_line" ] \
            || die "GI visual probe G6 GPU field probe preceded its G3 direct evidence"
        probe_motion_begin=$(grep -E \
            "METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_MOTION_BEGIN route=$ROUTE_ID rig=$GI_VISUAL_PROBE_RIG_ID next_measured_frame=540 bindings=[1-9][0-9]* zero_bindings=[0-9]+ field_bindings=[1-9][0-9]* status=PASS$" \
            "$MINECRAFT_LOG" || true)
        [ "$(printf '%s\n' "$probe_motion_begin" | grep -Fc 'GI_VISUAL_PROBE_MOTION_BEGIN' || true)" -eq 1 ] \
            || die "GI visual probe ON arm did not emit one motion baseline"
        probe_motion_complete=$(grep -E \
            "METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_MOTION_COMPLETE route=$ROUTE_ID rig=$GI_VISUAL_PROBE_RIG_ID measured_frame=690 bindings_before=[0-9]+ bindings_now=[1-9][0-9]* zero_before=[0-9]+ zero_now=[0-9]+ field_before=[0-9]+ field_now=[1-9][0-9]* zero_delta=0 field_delta=[1-9][0-9]* status=PASS$" \
            "$MINECRAFT_LOG" || true)
        [ "$(printf '%s\n' "$probe_motion_complete" | grep -Fc 'GI_VISUAL_PROBE_MOTION_COMPLETE' || true)" -eq 1 ] \
            || die "GI visual probe ON arm did not prove a zero-free field-bound motion interval"
    else
        [ "$probe_ready_count" -eq 0 ] \
            || die "GI visual probe OFF arm must not emit a G6 readiness marker"
        [ "$(grep -Fc 'METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_GPU_FIELD ' "$MINECRAFT_LOG" || true)" -eq 0 ] \
            || die "GI visual probe OFF arm unexpectedly emitted a G6 GPU field probe"
        [ "$(grep -Fc 'METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_GPU_DIRECT ' "$MINECRAFT_LOG" || true)" -eq 0 ] \
            || die "GI visual probe OFF arm unexpectedly emitted a G3 GPU direct probe"
        [ "$(grep -Ec 'GI_VISUAL_PROBE_MOTION_(BEGIN|COMPLETE)' "$MINECRAFT_LOG" || true)" -eq 0 ] \
            || die "GI visual probe OFF arm unexpectedly emitted a G6 motion receipt"
    fi
    probe_camera_poses=""
    for probe_index in 1 2 3 4; do
        probe_frame_var="GI_VISUAL_PROBE_CAPTURE_FRAME_$((probe_index - 1))"
        probe_frame=${!probe_frame_var}
        if [ "$GI_VISUAL_PROBE_ARM" = "on" ]; then
            probe_receipt='receipt=G6_(CURRENT|RETAINED) field_generation=[1-9][0-9]* source_tick=[0-9]+ ready_mask=[0-7] zero_before=[0-9]+ zero_now=[0-9]+ field_before=[0-9]+ field_now=[1-9][0-9]*'
        else
            probe_receipt='receipt=GI_DISABLED field_generation=0 source_tick=-1 ready_mask=0 zero_before=0 zero_now=0 field_before=0 field_now=0'
        fi
        probe_screenshot=$(grep -E \
            "METALLUM_BENCHMARK EVENT=GI_VISUAL_PROBE_SCREENSHOT index=$probe_index phase=TORCH_ON measured_frame=$probe_frame ready_frame=[0-9]+ capture_after_ready_frames=[0-9]+ rig=$GI_VISUAL_PROBE_RIG_ID direct_path=OCCLUDED bounce_path=OPEN $probe_receipt camera_pose=-?[0-9.eE]+,-?[0-9.eE]+,-?[0-9.eE]+;-?[0-9.eE]+,-?[0-9.eE]+$" \
            "$MINECRAFT_LOG" || true)
        probe_screenshot_count=$(printf '%s\n' "$probe_screenshot" | grep -Fc 'GI_VISUAL_PROBE_SCREENSHOT' || true)
        [ "$probe_screenshot_count" -eq 1 ] \
            || die "expected exactly one GI visual probe screenshot $probe_index (found $probe_screenshot_count)"
        probe_screenshot_line=$(grep -nF "$probe_screenshot" "$MINECRAFT_LOG" | cut -d: -f1)
        [ "$measure_start_line" -lt "$probe_screenshot_line" ] \
            && [ "$probe_screenshot_line" -lt "$measure_end_line" ] \
            || die "GI visual probe screenshot $probe_index is outside measurement"
        if [ "$GI_VISUAL_PROBE_ARM" = "on" ]; then
            [ "$probe_ready_line" -lt "$probe_screenshot_line" ] \
                || die "GI visual probe ON screenshot $probe_index preceded current G6 readiness"
            [ "$probe_gpu_direct_line" -lt "$probe_screenshot_line" ] \
                || die "GI visual probe ON screenshot $probe_index preceded its G3 GPU direct probe"
            [ "$probe_gpu_field_line" -lt "$probe_screenshot_line" ] \
                || die "GI visual probe ON screenshot $probe_index preceded its G6 GPU field probe"
            probe_zero_before=$(printf '%s\n' "$probe_screenshot" | sed -E 's/.* zero_before=([0-9]+).*/\1/')
            probe_zero_now=$(printf '%s\n' "$probe_screenshot" | sed -E 's/.* zero_now=([0-9]+).*/\1/')
            probe_field_before=$(printf '%s\n' "$probe_screenshot" | sed -E 's/.* field_before=([0-9]+).*/\1/')
            probe_field_now=$(printf '%s\n' "$probe_screenshot" | sed -E 's/.* field_now=([0-9]+).*/\1/')
            [ "$probe_zero_before" -eq "$probe_zero_now" ] \
                && [ "$probe_field_now" -gt "$probe_field_before" ] \
                || die "GI visual probe screenshot $probe_index crossed a zero/stale terrain binding"
        fi
        probe_camera_pose=$(printf '%s\n' "$probe_screenshot" | sed -E 's/.* camera_pose=([^ ]+)$/\1/')
        probe_camera_poses="${probe_camera_poses}${probe_camera_pose}\n"
    done
    [ "$(printf '%b' "$probe_camera_poses" | sort -u | grep -Ec '.' || true)" -eq 4 ] \
        || die "GI visual probe did not capture four distinct rendered camera poses"
fi

if [ "$RUNTIME_GI_MODE" = "g6_live" ] && [ "$GI_VISUAL_PROBE" -eq 0 ]; then
    g6_admission_prefix="METALLUM_BENCHMARK EVENT=GI_G6_ADMISSION "
    g6_admission_count=$(grep -Fc "$g6_admission_prefix" "$MINECRAFT_LOG" || true)
    [ "$g6_admission_count" -eq 1 ] \
        || die "expected exactly one G6 admission marker (found $g6_admission_count)"
    g6_admission=$(grep -E \
        'METALLUM_BENCHMARK EVENT=GI_G6_ADMISSION requested=g6_live resolved=g6_live contract=6 state=READY device_generation=[1-9][0-9]* presented_frame=[0-9]+ ready_mask=[1-7] field_generation=[1-9][0-9]* source_tick=[0-9]+ transport_dispatches=[1-9][0-9]* cascade_builds=[1-9][0-9]*/[0-9]+/[0-9]+ invalidations=[0-9]+ bindings=[1-9][0-9]* zero_bindings=[0-9]+ field_bindings=[1-9][0-9]* resident_bytes=[0-9]+ staging_bytes=[0-9]+ java_packet_bytes=[0-9]+ combined_accounted_bytes=[0-9]+ cap_bytes=25165824 dynamic=true vertex_only=true fragment_texture3d=0 stale=0 rejected=0 status=PASS$' \
        "$MINECRAFT_LOG" || true)
    g6_admission_exact_count=$(printf '%s\n' "$g6_admission" \
        | grep -Fc "$g6_admission_prefix" || true)
    [ "$g6_admission_exact_count" -eq 1 ] \
        || die "G6 admission did not prove the exact resolved READY/PASS contract"
    g6_accounted_bytes=$(printf '%s\n' "$g6_admission" \
        | sed -E 's/.* combined_accounted_bytes=([0-9]+) .*/\1/')
    [ "$g6_accounted_bytes" -le 25165824 ] \
        || die "G6 admission memory census exceeds the diffuse-GI cap"
    g6_admission_line=$(grep -nF "$g6_admission_prefix" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$segment_start_line" -lt "$g6_admission_line" ] \
        && [ "$g6_admission_line" -lt "$measure_start_line" ] \
        || die "G6 admission marker is outside the warmup boundary"
    g6_final='METALLUM_BENCHMARK EVENT=GI_G6_FINAL state=READY device_generation=[1-9][0-9]* admission_emitted=true admission_device_generation=[1-9][0-9]* ready_mask=7 build_in_flight=false field_generation=[1-9][0-9]* source_tick=[0-9]+ stale=0 rejected=0 latest_terrain_device_generation=[1-9][0-9]* latest_terrain_submit=[0-9]+ latest_bind_status=1 latest_carrier_safe=true latest_frame_compatible=true latest_ready_mask=7 latest_exact_mask_nonzero=true latest_field_generation=[1-9][0-9]* latest_source_tick=[0-9]+ combined_accounted_bytes=[0-9]+ cap_bytes=25165824 block_samples=[1-9][0-9]* block_p95_submits=[0-8] block_p99_submits=([0-9]|1[0-6]) block_sla=true static_samples=[0-9]+ static_p95_submits=(-1|[0-9]+) static_p99_submits=(-1|[0-9]+) static_sla=(true|false) scroll_samples=[0-9]+ scroll_p95_submits=(-1|[0-9]+) scroll_p99_submits=(-1|[0-9]+) scroll_sla=(true|false) reset_samples=[0-9]+ reset_p95_submits=(-1|[0-9]+) reset_p99_submits=(-1|[0-9]+) reset_sla=(true|false) queue_queued=[0-9]+ queue_completed=[0-9]+ queue_discarded=[0-9]+ queue_pending=0 queue_in_flight=0 queue_algebra=true measurement_start_bytes=[1-9][0-9]* accounted_delta=0 readback_bytes=0 status=PASS contract=6'
    g6_final_count=$(grep -Ec "${g6_final}$" "$MINECRAFT_LOG" || true)
    [ "$g6_final_count" -eq 1 ] \
        || die "expected exactly one stable G6 final census (found $g6_final_count)"
    g6_final_marker=$(grep -E "${g6_final}$" "$MINECRAFT_LOG")
    g6_final_accounted_bytes=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* combined_accounted_bytes=([0-9]+) .*/\1/')
    [ "$g6_final_accounted_bytes" -le 25165824 ] \
        || die "G6 final memory census exceeds the diffuse-GI cap"
    validate_g6_optional_sla() {
        local class_name=$1
        local samples=$2
        local p95=$3
        local p99=$4
        local sla=$5
        local p95_limit=$6
        local p99_limit=$7
        if [ "$samples" -eq 0 ]; then
            [ "$p95" -eq -1 ] && [ "$p99" -eq -1 ] && [ "$sla" = "false" ] \
                || die "G6 unused $class_name class must report 0/-1/-1/false"
            return
        fi
        [ "$p95" -ge 0 ] && [ "$p95" -le "$p99" ] \
            && [ "$p95" -le "$p95_limit" ] && [ "$p99" -le "$p99_limit" ] \
            && [ "$sla" = "true" ] \
            || die "G6 used $class_name class violated its p95/p99 SLA"
    }
    g6_static_samples=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* static_samples=([0-9]+) .*/\1/')
    g6_static_p95=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* static_p95_submits=(-1|[0-9]+) .*/\1/')
    g6_static_p99=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* static_p99_submits=(-1|[0-9]+) .*/\1/')
    g6_static_sla=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* static_sla=(true|false) .*/\1/')
    g6_scroll_samples=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* scroll_samples=([0-9]+) .*/\1/')
    g6_scroll_p95=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* scroll_p95_submits=(-1|[0-9]+) .*/\1/')
    g6_scroll_p99=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* scroll_p99_submits=(-1|[0-9]+) .*/\1/')
    g6_scroll_sla=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* scroll_sla=(true|false) .*/\1/')
    g6_reset_samples=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* reset_samples=([0-9]+) .*/\1/')
    g6_reset_p95=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* reset_p95_submits=(-1|[0-9]+) .*/\1/')
    g6_reset_p99=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* reset_p99_submits=(-1|[0-9]+) .*/\1/')
    g6_reset_sla=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* reset_sla=(true|false) .*/\1/')
    validate_g6_optional_sla static "$g6_static_samples" "$g6_static_p95" \
        "$g6_static_p99" "$g6_static_sla" 8 16
    validate_g6_optional_sla scroll "$g6_scroll_samples" "$g6_scroll_p95" \
        "$g6_scroll_p99" "$g6_scroll_sla" 16 32
    validate_g6_optional_sla reset "$g6_reset_samples" "$g6_reset_p95" \
        "$g6_reset_p99" "$g6_reset_sla" 32 64
    g6_queue_queued=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* queue_queued=([0-9]+) .*/\1/')
    g6_queue_completed=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* queue_completed=([0-9]+) .*/\1/')
    g6_queue_discarded=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* queue_discarded=([0-9]+) .*/\1/')
    [ "$g6_queue_queued" -eq "$((g6_queue_completed + g6_queue_discarded))" ] \
        || die "G6 final queue census violates queued=completed+discarded"
    g6_measurement_start_bytes=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* measurement_start_bytes=([0-9]+) .*/\1/')
    [ "$g6_measurement_start_bytes" -eq "$g6_final_accounted_bytes" ] \
        || die "G6 final accounted_delta=0 disagrees with measurement-start bytes"
    g6_admission_device_generation=$(printf '%s\n' "$g6_admission" \
        | sed -E 's/.* device_generation=([0-9]+) .*/\1/')
    g6_admission_submit=$(printf '%s\n' "$g6_admission" \
        | sed -E 's/.* presented_frame=([0-9]+) .*/\1/')
    g6_final_device_generation=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* state=READY device_generation=([0-9]+) .*/\1/')
    g6_final_admission_device_generation=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* admission_device_generation=([0-9]+) .*/\1/')
    g6_final_terrain_device_generation=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* latest_terrain_device_generation=([0-9]+) .*/\1/')
    g6_final_terrain_submit=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* latest_terrain_submit=([0-9]+) .*/\1/')
    g6_final_field_generation=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* build_in_flight=false field_generation=([0-9]+) .*/\1/')
    g6_final_terrain_field_generation=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* latest_field_generation=([0-9]+) .*/\1/')
    g6_final_source_tick=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* field_generation=[0-9]+ source_tick=([0-9]+) .*/\1/')
    g6_final_terrain_source_tick=$(printf '%s\n' "$g6_final_marker" \
        | sed -E 's/.* latest_source_tick=([0-9]+) .*/\1/')
    [ "$g6_admission_device_generation" = "$g6_final_device_generation" ] \
        && [ "$g6_final_device_generation" = "$g6_final_admission_device_generation" ] \
        && [ "$g6_final_device_generation" = "$g6_final_terrain_device_generation" ] \
        || die "G6 admission, final census and terrain bind span different device generations"
    [ "$g6_final_terrain_submit" -ge "$g6_admission_submit" ] \
        || die "G6 final terrain bind predates the admitted terrain draw"
    [ "$g6_final_field_generation" = "$g6_final_terrain_field_generation" ] \
        && [ "$g6_final_source_tick" = "$g6_final_terrain_source_tick" ] \
        || die "G6 final terrain bind does not use the terminal field/source generation"
    g6_final_line=$(grep -nE "${g6_final}$" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$measure_end_line" -lt "$g6_final_line" ] \
        || die "G6 final census must follow MEASURE_END"
elif [ "$RUNTIME_GI_MODE" = "g5_vertex_receiver" ]; then
    g5_admission_prefix="METALLUM_BENCHMARK EVENT=GI_G5_ADMISSION "
    g5_admission_count=$(grep -Fc "$g5_admission_prefix" "$MINECRAFT_LOG" || true)
    [ "$g5_admission_count" -eq 1 ] \
        || die "expected exactly one G5 admission marker (found $g5_admission_count)"
    g5_admission_contract="$g5_admission_prefix"\
"requested=g5_vertex_receiver resolved=g5_vertex_receiver contract=4 "\
"state=READY arm=$GI_G5_RECEIVER_ARM field=$GI_G5_FIELD_KIND "\
"phase=WARMUP presented_frame=[0-9]+ resources=5 bindings=5 "\
"allocated_bytes=[0-9]+ g4_accounted_bytes=22917480 "\
"combined_accounted_bytes=[0-9]+ cap_bytes=25165824 shared_texture_bytes=0 "\
"carrier_skips=0 g5_carrier_writes=[1-9][0-9]* "\
"drawn_g5_carrier_slices=[1-9][0-9]* status=PASS vertex_only=true "\
"fragment_texture3d=0 sidecar_bytes=0$"
    g5_admission=$(grep -E "$g5_admission_contract" "$MINECRAFT_LOG" || true)
    g5_admission_exact_count=$(printf '%s\n' "$g5_admission" \
        | grep -Fc "$g5_admission_prefix" || true)
    [ "$g5_admission_exact_count" -eq 1 ] \
        || die "G5 admission did not prove the exact resolved READY/PASS contract"
    g5_allocated_bytes=$(printf '%s\n' "$g5_admission" \
        | sed -E 's/.* allocated_bytes=([0-9]+) .*/\1/')
    g5_combined_reported_bytes=$(printf '%s\n' "$g5_admission" \
        | sed -E 's/.* combined_accounted_bytes=([0-9]+) .*/\1/')
    g5_combined_accounted_bytes=$((22917480 + g5_allocated_bytes))
    [ "$g5_combined_reported_bytes" -eq "$g5_combined_accounted_bytes" ] \
        || die "G5 admission memory census does not equal G4 plus G5 allocation"
    [ "$g5_combined_accounted_bytes" -le 25165824 ] \
        || die "G5 admission memory census exceeds the diffuse-GI cap"
    g5_admission_line=$(grep -nF "$g5_admission_prefix" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$segment_start_line" -lt "$g5_admission_line" ] \
        && [ "$g5_admission_line" -lt "$measure_start_line" ] \
        || die "G5 admission marker is outside the warmup boundary"
    if [ "$GI_G5_RECEIVER_ARM" != "field" ] \
            && grep -Eq 'METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION .*state=READY' \
                "$MINECRAFT_LOG"; then
        die "G5 control/candidate must not claim standalone G4 READY admission"
    fi
    g5_final="METALLUM_BENCHMARK EVENT=GI_G5_FINAL state=READY carrier_skips=0 g5_carrier_writes=[1-9][0-9]* drawn_g5_carrier_slices=[1-9][0-9]* status=PASS arm=$GI_G5_RECEIVER_ARM field=$GI_G5_FIELD_KIND contract=4"
    g5_final_count=$(grep -Ec "${g5_final}$" "$MINECRAFT_LOG" || true)
    [ "$g5_final_count" -eq 1 ] \
        || die "expected exactly one final zero-skip G5 carrier census (found $g5_final_count)"
    g5_final_line=$(grep -nE "${g5_final}$" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$measure_end_line" -lt "$g5_final_line" ] \
        || die "G5 final carrier census must follow MEASURE_END"
elif [ "$RUNTIME_GI_MODE" = "g4_transport" ]; then
    g4_admission_prefix="METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION "
    g4_admission_count=$(grep -Fc "$g4_admission_prefix" "$MINECRAFT_LOG" || true)
    [ "$g4_admission_count" -eq 1 ] \
        || die "expected exactly one G4 admission marker (found $g4_admission_count)"
    g4_admission=$(grep -E \
        'METALLUM_BENCHMARK EVENT=GI_G4_ADMISSION requested=g4_transport resolved=g4_transport contract=3 state=READY phase=WARMUP presented_frame=[0-9]+ resources=11 passes=4 dirty=192/192/0/0 injection_dispatches=192 full_volume_rebuilds=1 transport_dispatches=1 field_epoch=1 stale=0 rejected=0 status=PASS field_only=true receiver=false image_binding=false$' \
        "$MINECRAFT_LOG" || true)
    g4_admission_exact_count=$(printf '%s\n' "$g4_admission" \
        | grep -Fc "$g4_admission_prefix" || true)
    [ "$g4_admission_exact_count" -eq 1 ] \
        || die "G4 admission did not prove the exact resolved READY contract"
    g4_admission_line=$(grep -nF "$g4_admission_prefix" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$segment_start_line" -lt "$g4_admission_line" ] \
        && [ "$g4_admission_line" -lt "$measure_start_line" ] \
        || die "G4 admission marker is outside the warmup boundary"
fi


if [ "$ROUTE_KIND" = "TORCH_EPOCH" ] \
        || [ "$ROUTE_KIND" = "TORCH_TOGGLE" ] \
        || [ "$ROUTE_KIND" = "GI_VISUAL_PROBE" ]; then
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
    if [ "$ROUTE_KIND" = "TORCH_TOGGLE" ] \
            || [ "$ROUTE_KIND" = "GI_VISUAL_PROBE" ]; then
        torch_removed="METALLUM_BENCHMARK EVENT=TORCH_EPOCH_REMOVED route=$ROUTE_ID position=$torch_position measured_frame="
        torch_removed_count=$(grep -F "$torch_removed" "$MINECRAFT_LOG" \
            | grep -Fc " requested_frame=$TORCH_REMOVE_AFTER_MEASURED_FRAMES" || true)
        [ "$torch_removed_count" -eq 1 ] \
            || die "expected exactly one matching TORCH_EPOCH_REMOVED marker (found $torch_removed_count)"
        torch_removed_line=$(grep -nF "$torch_removed" "$MINECRAFT_LOG" \
            | grep -F " requested_frame=$TORCH_REMOVE_AFTER_MEASURED_FRAMES" \
            | cut -d: -f1)
        if [ "$CAPTURE_REFERENCE" -eq 1 ] \
                && [ "$ROUTE_KIND" = "TORCH_TOGGLE" ]; then
            g6_screenshot="METALLUM_BENCHMARK EVENT=SCREENSHOT_REQUESTED index=1 mode=$METALFX_MODE phase=TORCH_ON measured_frame=400"
            g6_screenshot_count=$(grep -Fc "$g6_screenshot" "$MINECRAFT_LOG" || true)
            [ "$g6_screenshot_count" -eq 1 ] \
                || die "torch reference run expected exactly one torch-on screenshot marker (found $g6_screenshot_count)"
            g6_screenshot_line=$(grep -nF "$g6_screenshot" "$MINECRAFT_LOG" | cut -d: -f1)
            [ "$torch_applied_line" -lt "$g6_screenshot_line" ] \
                && [ "$g6_screenshot_line" -lt "$torch_removed_line" ] \
                || die "torch-on screenshot marker is outside the confirmed torch epoch"
        fi
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
    if [ "$ROUTE_ID" = "reflection-house-voxel-motion-v1" ]; then
        l6_coverage_prefix="METALLUM_BENCHMARK EVENT=L6_DYNAMIC_COVERAGE route=$ROUTE_ID frames=$MEASURE_FRAMES held_admitted_frames=$MEASURE_FRAMES held_ready_frames=$MEASURE_FRAMES dispatch_frames=$MEASURE_FRAMES "
        l6_coverage=$(grep -F "$l6_coverage_prefix" "$MINECRAFT_LOG" \
            | grep -F " fallback_total=0 coverage_miss_total=0 failure_total=0 " || true)
        l6_coverage_count=$(printf '%s\n' "$l6_coverage" | grep -Fc "$l6_coverage_prefix" || true)
        [ "$l6_coverage_count" -eq 1 ] \
            || die "expected one healthy reflection-motion L6_DYNAMIC_COVERAGE marker (found $l6_coverage_count)"
        l6_coverage_line=$(grep -nF "$l6_coverage_prefix" "$MINECRAFT_LOG" | cut -d: -f1)
    else
        l6_coverage="METALLUM_BENCHMARK EVENT=L6_DYNAMIC_COVERAGE route=$ROUTE_ID frames=$MEASURE_FRAMES held_admitted_frames=$MEASURE_FRAMES held_ready_frames=$MEASURE_FRAMES dispatch_frames=$MEASURE_FRAMES $expected_coverage_budget fallback_total=0 coverage_miss_total=0 failure_total=0 $expected_coverage_pages"
        l6_coverage_count=$(grep -Fc "$l6_coverage" "$MINECRAFT_LOG" || true)
        [ "$l6_coverage_count" -eq 1 ] \
            || die "expected exactly one matching L6_DYNAMIC_COVERAGE marker (found $l6_coverage_count)"
        l6_coverage_line=$(grep -nF "$l6_coverage" "$MINECRAFT_LOG" | cut -d: -f1)
    fi
    [ "$measure_start_line" -lt "$l6_coverage_line" ] \
        && [ "$l6_coverage_line" -lt "$measure_end_line" ] \
        || die "L6 dynamic coverage marker is out of order"

    l6_samples=$(sed -n "${measure_start_line},${measure_end_line}p" "$MINECRAFT_LOG" \
        | grep -F "METALLUM_L6_DYNAMIC " || true)
    l6_sample_count=$(printf '%s\n' "$l6_samples" | grep -Fc "METALLUM_L6_DYNAMIC " || true)
    [ "$l6_sample_count" -ge 1 ] \
        || die "L6 dynamic route emitted no periodic admission telemetry"
    if [ "$ROUTE_ID" = "reflection-house-voxel-motion-v1" ]; then
        l6_valid_count=$(printf '%s\n' "$l6_samples" \
            | grep -E 'held=true dispatches=1 rays=[1-9][0-9]* ready=[1-9][0-9]* fallback=0 coverage_miss=0 .*failures=0 pages_bytes=[1-9][0-9]*$' \
            | grep -Fc "METALLUM_L6_DYNAMIC " || true)
    else
        l6_valid_count=$(printf '%s\n' "$l6_samples" \
            | grep -F "$expected_dynamic" \
            | grep -Fc "$expected_dynamic_pages" || true)
    fi
    [ "$l6_valid_count" -eq "$l6_sample_count" ] \
        || die "L6 dynamic admission/ready telemetry violated the $LIGHTING_PRESET contract ($l6_valid_count/$l6_sample_count valid)"
fi

if [ "$ROUTE_KIND" = "GI_G6_MATRIX" ]; then
    g6_nether_prepare="METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_NETHER_PREPARE route=$ROUTE_ID target=0,96,0 chunk=0,0 full=true forced=true status=PASS"
    g6_nether_prepare_count=$(grep -Ec "${g6_nether_prepare}$" "$MINECRAFT_LOG" || true)
    [ "$g6_nether_prepare_count" -eq 1 ] \
        || die "expected exactly one prepared G6 Nether target chunk marker (found $g6_nether_prepare_count)"
    g6_nether_prepare_line=$(grep -nE "${g6_nether_prepare}$" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$g6_nether_prepare_line" -lt "$measure_start_line" ] \
        || die "G6 Nether target chunk was not prepared before MEASURE_START"

    g6_matrix_ready="METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_READY route=$ROUTE_ID held=minecraft:torch entity=minecraft:torch entity_id=1999999900 status=PASS"
    g6_matrix_ready_count=$(grep -Ec "${g6_matrix_ready}$" "$MINECRAFT_LOG" || true)
    [ "$g6_matrix_ready_count" -eq 1 ] \
        || die "expected exactly one matching GI_G6_MATRIX_READY marker (found $g6_matrix_ready_count)"
    g6_matrix_ready_line=$(grep -nE "${g6_matrix_ready}$" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$route_apply_line" -lt "$g6_matrix_ready_line" ] \
        && [ "$g6_matrix_ready_line" -lt "$route_ready_line" ] \
        || die "G6 matrix readiness marker is out of order"

    matrix_event_prefix="METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_EVENT route=$ROUTE_ID "
    matrix_event_count=$(grep -Fc "$matrix_event_prefix" "$MINECRAFT_LOG" || true)
    [ "$matrix_event_count" -eq 22 ] \
        || die "G6 matrix must emit exactly 22 event receipts (found $matrix_event_count)"
    matrix_actions=(
        ORBIT_BEGIN ORBIT_END LAVA_APPLIED LAVA_REMOVED CHUNK_RELOAD RESOURCE_RELOAD
        DAY NIGHT RAIN CLEAR STREAM_STEP STREAM_STEP STREAM_STEP STREAM_STEP
        STREAM_STEP STREAM_STEP STREAM_STEP STREAM_STEP TELEPORT_OUT TELEPORT_RETURN
        NETHER_ENTER OVERWORLD_RETURN
    )
    matrix_frames=(
        "$G6_MATRIX_ORBIT_START_FRAME" "$G6_MATRIX_ORBIT_END_FRAME"
        "$G6_MATRIX_LAVA_APPLY_FRAME" "$G6_MATRIX_LAVA_REMOVE_FRAME"
        "$G6_MATRIX_CHUNK_RELOAD_FRAME" "$G6_MATRIX_RESOURCE_RELOAD_FRAME"
        "$G6_MATRIX_DAY_FRAME" "$G6_MATRIX_NIGHT_FRAME"
        "$G6_MATRIX_RAIN_FRAME" "$G6_MATRIX_CLEAR_FRAME"
        "$G6_MATRIX_STREAM_START_FRAME"
        "$((G6_MATRIX_STREAM_START_FRAME + G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 2 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 3 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 4 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 5 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 6 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 7 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$G6_MATRIX_TELEPORT_FRAME" "$G6_MATRIX_TELEPORT_RETURN_FRAME"
        "$G6_MATRIX_NETHER_ENTER_FRAME" "$G6_MATRIX_NETHER_RETURN_FRAME"
    )
    matrix_stream_offsets=(
        "$G6_MATRIX_STREAM_OFFSET_0" "$G6_MATRIX_STREAM_OFFSET_1"
        "$G6_MATRIX_STREAM_OFFSET_2" "$G6_MATRIX_STREAM_OFFSET_3"
        "$G6_MATRIX_STREAM_OFFSET_4" "$G6_MATRIX_STREAM_OFFSET_5"
        "$G6_MATRIX_STREAM_OFFSET_6" "$G6_MATRIX_STREAM_OFFSET_7"
    )
    matrix_stream_y_offsets=(
        "$G6_MATRIX_STREAM_Y_OFFSET_0" "$G6_MATRIX_STREAM_Y_OFFSET_1"
        "$G6_MATRIX_STREAM_Y_OFFSET_2" "$G6_MATRIX_STREAM_Y_OFFSET_3"
        "$G6_MATRIX_STREAM_Y_OFFSET_4" "$G6_MATRIX_STREAM_Y_OFFSET_5"
        "$G6_MATRIX_STREAM_Y_OFFSET_6" "$G6_MATRIX_STREAM_Y_OFFSET_7"
    )
    matrix_previous_line=$measure_start_line
    for matrix_event_index in "${!matrix_actions[@]}"; do
        matrix_action=${matrix_actions[$matrix_event_index]}
        matrix_frame=${matrix_frames[$matrix_event_index]}
        if [ "$matrix_action" = "ORBIT_BEGIN" ]; then
            matrix_event_pattern="$matrix_event_prefix"\
"action=ORBIT_BEGIN measured_frame=$matrix_frame field_generation=[1-9][0-9]* "\
"block_samples=[0-9]+ static_source_samples=[0-9]+ scroll_samples=[0-9]+ "\
"full_reset_samples=[0-9]+ status=PASS$"
        elif [ "$matrix_action" = "ORBIT_END" ]; then
            matrix_event_pattern="$matrix_event_prefix"\
"action=ORBIT_END measured_frame=$matrix_frame baseline_generation=[1-9][0-9]* "\
"field_generation=[1-9][0-9]* block_samples=[0-9]+ static_source_samples=[0-9]+ "\
"scroll_samples=[0-9]+ full_reset_samples=[0-9]+ status=PASS$"
        elif [ "$matrix_action" = "STREAM_STEP" ]; then
            matrix_stream_index=$((matrix_event_index - 10))
            matrix_stream_offset=${matrix_stream_offsets[$matrix_stream_index]}
            matrix_stream_y_offset=${matrix_stream_y_offsets[$matrix_stream_index]}
            matrix_event_pattern="$matrix_event_prefix"\
"action=STREAM_STEP requested_frame=$matrix_frame measured_frame=[0-9]+ "\
"index=$matrix_stream_index offset=$matrix_stream_offset "\
"y_offset=$matrix_stream_y_offset status=PASS$"
        else
            matrix_event_pattern="$matrix_event_prefix"\
"action=$matrix_action requested_frame=$matrix_frame measured_frame=[0-9]+ status=PASS$"
        fi
        matrix_event_exact_count=$(grep -Ec "$matrix_event_pattern" "$MINECRAFT_LOG" || true)
        [ "$matrix_event_exact_count" -eq 1 ] \
            || die "G6 matrix event $matrix_action/$matrix_frame did not match its exact receipt"
        matrix_event_marker=$(grep -E "$matrix_event_pattern" "$MINECRAFT_LOG")
        if [ "$matrix_action" = "ORBIT_BEGIN" ]; then
            matrix_orbit_generation=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* field_generation=([0-9]+) .*/\1/')
            matrix_orbit_block_samples=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* block_samples=([0-9]+) .*/\1/')
            matrix_orbit_static_samples=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* static_source_samples=([0-9]+) .*/\1/')
            matrix_orbit_scroll_samples=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* scroll_samples=([0-9]+) .*/\1/')
            matrix_orbit_reset_samples=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* full_reset_samples=([0-9]+) .*/\1/')
        elif [ "$matrix_action" = "ORBIT_END" ]; then
            matrix_orbit_baseline=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* baseline_generation=([0-9]+) .*/\1/')
            matrix_orbit_generation_after=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* field_generation=([0-9]+) .*/\1/')
            matrix_orbit_block_after=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* block_samples=([0-9]+) .*/\1/')
            matrix_orbit_static_after=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* static_source_samples=([0-9]+) .*/\1/')
            matrix_orbit_scroll_after=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* scroll_samples=([0-9]+) .*/\1/')
            matrix_orbit_reset_after=$(printf '%s\n' "$matrix_event_marker" \
                | sed -E 's/.* full_reset_samples=([0-9]+) .*/\1/')
            [ "$matrix_orbit_baseline" -eq "$matrix_orbit_generation" ] \
                && [ "$matrix_orbit_generation_after" -eq "$matrix_orbit_generation" ] \
                && [ "$matrix_orbit_block_after" -eq "$matrix_orbit_block_samples" ] \
                && [ "$matrix_orbit_static_after" -eq "$matrix_orbit_static_samples" ] \
                && [ "$matrix_orbit_scroll_after" -eq "$matrix_orbit_scroll_samples" ] \
                && [ "$matrix_orbit_reset_after" -eq "$matrix_orbit_reset_samples" ] \
                || die "G6 orbit changed field generation or latency-class samples"
        fi
        matrix_actual_frame=$(printf '%s\n' "$matrix_event_marker" \
            | sed -E 's/.* measured_frame=([0-9]+) .*/\1/')
        matrix_event_actual_frames[$matrix_event_index]=$matrix_actual_frame
        if [ "$((matrix_event_index + 1))" -lt "${#matrix_frames[@]}" ]; then
            matrix_next_frame=${matrix_frames[$((matrix_event_index + 1))]}
        else
            matrix_next_frame=$MEASURE_FRAMES
        fi
        [ "$matrix_actual_frame" -ge "$matrix_frame" ] \
            && [ "$matrix_actual_frame" -lt "$matrix_next_frame" ] \
            || die "G6 matrix event $matrix_action missed its deterministic completion window"
        matrix_event_line=$(grep -nE "$matrix_event_pattern" "$MINECRAFT_LOG" | cut -d: -f1)
        matrix_event_lines[$matrix_event_index]=$matrix_event_line
        [ "$matrix_previous_line" -lt "$matrix_event_line" ] \
            && [ "$matrix_event_line" -lt "$measure_end_line" ] \
            || die "G6 matrix event $matrix_action/$matrix_frame is out of order"
        matrix_previous_line=$matrix_event_line
    done

    matrix_recovery_prefix="METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_RECOVERY route=$ROUTE_ID "
    matrix_recovery_count=$(grep -Fc "$matrix_recovery_prefix" "$MINECRAFT_LOG" || true)
    [ "$matrix_recovery_count" -eq 20 ] \
        || die "G6 matrix must emit exactly 20 independent recovery receipts (found $matrix_recovery_count)"
    matrix_recovery_actions=(
        LAVA_APPLY LAVA_REMOVE CHUNK_RELOAD RESOURCE_RELOAD DAY NIGHT RAIN CLEAR
        STREAM_STEP_0 STREAM_STEP_1 STREAM_STEP_2 STREAM_STEP_3
        STREAM_STEP_4 STREAM_STEP_5 STREAM_STEP_6 STREAM_STEP_7
        TELEPORT_OUT TELEPORT_RETURN NETHER_ENTER NETHER_RETURN
    )
    matrix_recovery_requests=(
        "$G6_MATRIX_LAVA_APPLY_FRAME" "$G6_MATRIX_LAVA_REMOVE_FRAME"
        "$G6_MATRIX_CHUNK_RELOAD_FRAME" "$G6_MATRIX_RESOURCE_RELOAD_FRAME"
        "$G6_MATRIX_DAY_FRAME" "$G6_MATRIX_NIGHT_FRAME"
        "$G6_MATRIX_RAIN_FRAME" "$G6_MATRIX_CLEAR_FRAME"
        "$G6_MATRIX_STREAM_START_FRAME"
        "$((G6_MATRIX_STREAM_START_FRAME + G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 2 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 3 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 4 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 5 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 6 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$((G6_MATRIX_STREAM_START_FRAME + 7 * G6_MATRIX_STREAM_STEP_FRAMES))"
        "$G6_MATRIX_TELEPORT_FRAME" "$G6_MATRIX_TELEPORT_RETURN_FRAME"
        "$G6_MATRIX_NETHER_ENTER_FRAME" "$G6_MATRIX_NETHER_RETURN_FRAME"
    )
    matrix_recovery_classes=(
        STATIC_SOURCE STATIC_SOURCE TERRAIN_BIND FULL_RESET
        FULL_RESET FULL_RESET FULL_RESET FULL_RESET
        SCROLL SCROLL SCROLL SCROLL SCROLL SCROLL SCROLL SCROLL
        FULL_RESET FULL_RESET FULL_RESET FULL_RESET
    )
    matrix_recovery_event_indices=(
        2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21
    )
    matrix_previous_recovery_line=$measure_start_line
    for matrix_recovery_index in "${!matrix_recovery_actions[@]}"; do
        matrix_recovery_action=${matrix_recovery_actions[$matrix_recovery_index]}
        matrix_recovery_request=${matrix_recovery_requests[$matrix_recovery_index]}
        matrix_recovery_class=${matrix_recovery_classes[$matrix_recovery_index]}
        if [ "$matrix_recovery_action" = "CHUNK_RELOAD" ]; then
            matrix_recovery_pattern="$matrix_recovery_prefix"\
"action=CHUNK_RELOAD requested_frame=$matrix_recovery_request measured_frame=[0-9]+ "\
"terrain_submit_before=[0-9]+ terrain_submit_after=[0-9]+ "\
"terrain_device_before=[1-9][0-9]* terrain_device_after=[1-9][0-9]* current_device=[1-9][0-9]* "\
"terrain_field_before=[1-9][0-9]* terrain_field_after=[1-9][0-9]* current_field=[1-9][0-9]* "\
"terrain_source_before=[0-9]+ terrain_source_after=[0-9]+ current_source=[0-9]+ "\
"bind_status=1 carrier_safe=true frame_compatible=true exact_mask_nonzero=true "\
"ready_mask=7 build_in_flight=false status=PASS$"
        elif [ "$matrix_recovery_action" = "RESOURCE_RELOAD" ]; then
            matrix_recovery_pattern="$matrix_recovery_prefix"\
"action=RESOURCE_RELOAD requested_frame=$matrix_recovery_request measured_frame=[0-9]+ "\
"baseline_generation=[1-9][0-9]* field_generation=[1-9][0-9]* latency_class=FULL_RESET "\
"sample_before=[0-9]+ sample_after=[1-9][0-9]* "\
"terrain_submit_before=[0-9]+ terrain_submit_after=[0-9]+ "\
"terrain_device_before=[1-9][0-9]* terrain_device_after=[1-9][0-9]* current_device=[1-9][0-9]* "\
"terrain_field_before=[1-9][0-9]* terrain_field_after=[1-9][0-9]* current_field=[1-9][0-9]* "\
"terrain_source_before=[0-9]+ terrain_source_after=[0-9]+ current_source=[0-9]+ "\
"bind_status=1 carrier_safe=true frame_compatible=true exact_mask_nonzero=true "\
"ready_mask=7 build_in_flight=false status=PASS$"
        else
            matrix_recovery_ready_mask=7
            matrix_recovery_build_in_flight=false
            if [ "$matrix_recovery_class" = "SCROLL" ]; then
                matrix_recovery_ready_mask='[1357]'
                matrix_recovery_build_in_flight='(true|false)'
            fi
            matrix_recovery_pattern="$matrix_recovery_prefix"\
"action=$matrix_recovery_action requested_frame=$matrix_recovery_request "\
"measured_frame=[0-9]+ baseline_generation=[1-9][0-9]* "\
"field_generation=[1-9][0-9]* latency_class=$matrix_recovery_class "\
"sample_before=[0-9]+ sample_after=[1-9][0-9]* "\
"ready_mask=$matrix_recovery_ready_mask "\
"build_in_flight=$matrix_recovery_build_in_flight status=PASS$"
        fi
        matrix_recovery_exact_count=$(grep -Ec "$matrix_recovery_pattern" "$MINECRAFT_LOG" || true)
        [ "$matrix_recovery_exact_count" -eq 1 ] \
            || die "G6 matrix recovery $matrix_recovery_action did not match its exact receipt"
        matrix_recovery_marker=$(grep -E "$matrix_recovery_pattern" "$MINECRAFT_LOG")
        matrix_recovery_frame=$(printf '%s\n' "$matrix_recovery_marker" \
            | sed -E 's/.* measured_frame=([0-9]+) .*/\1/')
        matrix_event_index=${matrix_recovery_event_indices[$matrix_recovery_index]}
        matrix_action_frame=${matrix_event_actual_frames[$matrix_event_index]}
        if [ "$((matrix_event_index + 1))" -lt "${#matrix_event_actual_frames[@]}" ]; then
            matrix_next_action_frame=${matrix_event_actual_frames[$((matrix_event_index + 1))]}
        else
            matrix_next_action_frame=$MEASURE_FRAMES
        fi
        [ "$matrix_recovery_frame" -ge "$matrix_recovery_request" ] \
            && [ "$matrix_recovery_frame" -ge "$matrix_action_frame" ] \
            && [ "$matrix_recovery_frame" -lt "$matrix_next_action_frame" ] \
            || die "G6 matrix recovery $matrix_recovery_action lies outside its recovery window"
        if [ "$matrix_recovery_action" = "CHUNK_RELOAD" ] \
                || [ "$matrix_recovery_action" = "RESOURCE_RELOAD" ]; then
            matrix_submit_before=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* terrain_submit_before=([0-9]+) .*/\1/')
            matrix_submit_after=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* terrain_submit_after=([0-9]+) .*/\1/')
            matrix_device_before=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* terrain_device_before=([0-9]+) .*/\1/')
            matrix_device_after=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* terrain_device_after=([0-9]+) .*/\1/')
            matrix_current_device=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* current_device=([0-9]+) .*/\1/')
            matrix_terrain_field_after=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* terrain_field_after=([0-9]+) .*/\1/')
            matrix_current_field=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* current_field=([0-9]+) .*/\1/')
            matrix_terrain_source_after=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* terrain_source_after=([0-9]+) .*/\1/')
            matrix_current_source=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* current_source=([0-9]+) .*/\1/')
            [ "$matrix_submit_after" -gt "$matrix_submit_before" ] \
                && [ "$matrix_device_after" -eq "$matrix_device_before" ] \
                && [ "$matrix_device_after" -eq "$matrix_current_device" ] \
                && [ "$matrix_terrain_field_after" -eq "$matrix_current_field" ] \
                && [ "$matrix_terrain_source_after" -eq "$matrix_current_source" ] \
                || die "G6 matrix terrain reload did not prove a newer current same-device bind"
        fi
        if [ "$matrix_recovery_class" != "TERRAIN_BIND" ]; then
            matrix_generation_before=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* baseline_generation=([0-9]+) .*/\1/')
            matrix_generation_after=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* field_generation=([0-9]+) .*/\1/')
            matrix_sample_before=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* sample_before=([0-9]+) .*/\1/')
            matrix_sample_after=$(printf '%s\n' "$matrix_recovery_marker" \
                | sed -E 's/.* sample_after=([0-9]+) .*/\1/')
            [ "$matrix_generation_after" -gt "$matrix_generation_before" ] \
                && [ "$matrix_sample_after" -eq "$((matrix_sample_before + 1))" ] \
                || die "G6 matrix recovery $matrix_recovery_action reused another mutation's evidence"
        fi
        matrix_recovery_line=$(grep -nE "$matrix_recovery_pattern" "$MINECRAFT_LOG" \
            | cut -d: -f1)
        if [ "$matrix_recovery_action" = "STREAM_STEP_7" ]; then
            matrix_final_stream_recovery_line=$matrix_recovery_line
        elif [ "$matrix_recovery_action" = "TELEPORT_OUT" ]; then
            matrix_teleport_out_recovery_line=$matrix_recovery_line
        fi
        matrix_action_line=${matrix_event_lines[$matrix_event_index]}
        if [ "$((matrix_event_index + 1))" -lt "${#matrix_event_lines[@]}" ]; then
            matrix_next_action_line=${matrix_event_lines[$((matrix_event_index + 1))]}
        else
            matrix_next_action_line=$measure_end_line
        fi
        [ "$matrix_action_line" -lt "$matrix_recovery_line" ] \
            && [ "$matrix_recovery_line" -lt "$matrix_next_action_line" ] \
            || die "G6 matrix recovery $matrix_recovery_action did not close before the next mutation"
        [ "$matrix_previous_recovery_line" -lt "$matrix_recovery_line" ] \
            && [ "$matrix_recovery_line" -lt "$measure_end_line" ] \
            || die "G6 matrix recovery $matrix_recovery_action is out of order"
        matrix_previous_recovery_line=$matrix_recovery_line
    done
    [ -n "${matrix_final_stream_recovery_line:-}" ] \
        && [ "$matrix_final_stream_recovery_line" -lt "${matrix_event_lines[18]}" ] \
        || die "G6 STREAM_STEP_7 near receipt did not close before TELEPORT_OUT"
    [ -n "${matrix_teleport_out_recovery_line:-}" ] \
        && [ "${matrix_event_lines[18]}" -lt "$matrix_teleport_out_recovery_line" ] \
        && [ "$matrix_teleport_out_recovery_line" -lt "${matrix_event_lines[19]}" ] \
        || die "G6 TELEPORT_OUT FULL_RESET +1/ready7/!inflight did not close before RETURN"
    [ "$g6_nether_prepare_line" -lt "${matrix_event_lines[20]}" ] \
        || die "G6 NETHER_ENTER did not follow its pre-measure prepared-chunk receipt"

    g6_matrix_final="METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_FINAL route=$ROUTE_ID receipts=511 orbit_field_stable=true queue_converged=true accounted_delta=0 status=PASS contract=6"
    g6_matrix_final_count=$(grep -Ec "${g6_matrix_final}$" "$MINECRAFT_LOG" || true)
    [ "$g6_matrix_final_count" -eq 1 ] \
        || die "expected exactly one complete G6 matrix final receipt (found $g6_matrix_final_count)"
    g6_matrix_final_line=$(grep -nE "${g6_matrix_final}$" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$measure_end_line" -lt "$g6_matrix_final_line" ] \
        || die "G6 matrix final receipt must follow MEASURE_END"
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
if [ "$RUNTIME_GI_MODE" = "g6_live" ] && [ "$GI_VISUAL_PROBE" -eq 0 ]; then
    complete_line=$(grep -nF "$complete" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$g6_final_line" -lt "$complete_line" ] \
        || die "G6 final census must precede COMPLETE"
    if [ "$ROUTE_KIND" = "GI_G6_MATRIX" ]; then
        [ "$g6_matrix_final_line" -lt "$complete_line" ] \
            || die "G6 matrix final receipt must precede COMPLETE"
    fi
elif [ "$RUNTIME_GI_MODE" = "g5_vertex_receiver" ]; then
    complete_line=$(grep -nF "$complete" "$MINECRAFT_LOG" | cut -d: -f1)
    [ "$g5_final_line" -lt "$complete_line" ] \
        || die "G5 final carrier census must precede COMPLETE"
fi
if [ "$EXPECTED_LIGHTING_MODEL" = "advanced" ]; then
    admission_health=true
else
    admission_health=false
fi
admission="METALLUM_BENCHMARK EVENT=ADVANCED_ADMISSION expected=$EXPECTED_LIGHTING_MODEL schema=$RENDERER_SCHEMA defaults_used=false requested=$EXPECTED_LIGHTING_MODEL resolved=$EXPECTED_LIGHTING_MODEL l3=$admission_health l5=$admission_health l6=$admission_health status=PASS"
grep -Fq "$admission" "$MINECRAFT_LOG" \
    || die "benchmark lighting admission did not prove the requested $EXPECTED_LIGHTING_MODEL contract"
if [ "$VERTEX_REFLECTION_EXPERIMENT" -eq 1 ]; then
    reflection_admission=$(grep -F "METALLUM_BENCHMARK EVENT=VERTEX_REFLECTION_ADMISSION enabled=true state=READY ready=true" "$MINECRAFT_LOG" | tail -n 1 || true)
    [ -n "$reflection_admission" ] \
        || die "vertex-reflection admission did not prove a READY field"
    reflection_quality="quality_face_aware=$WATER_REFLECTION_FACE_AWARE quality_first_surface=$WATER_REFLECTION_FIRST_SURFACE quality_confidence=$WATER_REFLECTION_CONFIDENCE"
    case "$reflection_admission" in
        *"$reflection_quality"*) ;;
        *) die "vertex-reflection admission did not prove quality mode $WATER_REFLECTION_QUALITY: $reflection_admission" ;;
    esac
fi
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
    echo "  transcript: $TRANSCRIPT_LOG"
    ATTEST_PENDING=0
    exit 0
fi
[ -s "$RAW_REPORT" ] || die "GPU timing JSONL report is missing or empty"

RELEASE_ARG=""
VALIDATION_ARG=""
if [ "$RELEASE_PROFILE_CANDIDATE" -eq 1 ]; then
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
    if [ "$GI_VISUAL_PROBE" -eq 1 ]; then
        captured_count=0
        mkdir -p "$REFERENCE_OUTPUT_DIR/$stem"
        for screenshot in "$RUN_DIR"/screenshots/*.png; do
            [ -f "$screenshot" ] || continue
            screenshot_mtime=$(stat -f %m "$screenshot")
            if [ "$screenshot_mtime" -ge "$start_epoch" ]; then
                captured_count=$((captured_count + 1))
                cp "$screenshot" "$REFERENCE_OUTPUT_DIR/$stem/$(basename "$screenshot")"
            fi
        done
        [ "$captured_count" -eq 4 ] \
            || die "GI visual probe expected exactly four new PNGs (found $captured_count)"
        echo "GI visual probe captures saved (non-attested): $REFERENCE_OUTPUT_DIR/$stem"
        echo "  arm: $GI_VISUAL_PROBE_ARM; inspect ON/OFF pairs manually for warm red receiver bounce and motion stability"
    else
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
    fi
else
    echo "Benchmark validated: COMPLETE present, no FAIL/screenshots, dropped timing events = 0"
fi
echo "  raw: $RAW_REPORT"
echo "  summary: $SUMMARY_JSON"
echo "  Minecraft log: $MINECRAFT_LOG"
echo "  console log: $CONSOLE_LOG"
echo "  transcript: $TRANSCRIPT_LOG"
if [ -n "$RELEASE_ARG" ]; then
    ATTEST_PENDING=1
else
    ATTEST_PENDING=0
fi
