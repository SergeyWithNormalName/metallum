#!/bin/bash

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUNNER="$ROOT/scripts/run_metal_benchmark.sh"
ROUTE="$ROOT/benchmark/routes/gi-g4-overworld-v1.json"

fail() {
    echo "GI G4 runner contract FAILED: $*" >&2
    exit 1
}

bash -n "$RUNNER" || fail "benchmark runner has invalid shell syntax"
[ -f "$ROUTE" ] || fail "tracked G4 route is missing"

route_values=$(python3 "$ROOT/tools/metal_benchmark_fixture.py" route-values "$ROUTE") \
    || fail "G4 route does not satisfy the canonical route parser"
route_field_count=$(printf '%s\n' "$route_values" | awk -F '\t' '{ print NF; exit }')
[ "$route_field_count" -eq 19 ] || fail "G4 route is not a schema-1 static route"
IFS=$'\t' read -r route_id _route_sha fixture_id _fixture_sha \
    _player_name _player_uuid _dimension \
    _position_x _position_y _position_z _yaw _pitch \
    _ticks _weather _frozen stable_frames timeout_frames \
    _position_epsilon _angle_epsilon <<< "$route_values"
[ "$route_id" = "gi-g4-overworld-v1" ] || fail "G4 route id differs"
[ "$fixture_id" = "hdrtest-static-v1" ] || fail "G4 route fixture differs"
[ "$stable_frames" = "120" ] || fail "G4 route stable-frame contract differs"
[ "$timeout_frames" = "3000" ] || fail "G4 route timeout must be 3000 frames"

request='GI_G4_TRANSPORT_REQUEST mode=g4_transport g2_capture=true g3_inject=true frozen_near_cascade=true jacobi_iterations=1 field_only=true receiver=false image_binding=false diagnostic_only=true release=false status=REQUESTED'
[ "$(grep -Fc "$request" "$RUNNER")" -eq 1 ] \
    || fail "runner must emit one exact G4 request marker"
if grep -Fq 'GI_G4_TRANSPORT_ADMISSION mode=g4_transport' "$RUNNER"; then
    fail "runner still emits the obsolete request marker"
fi

for token in \
    'TRANSCRIPT_LOG="$OUTPUT_DIR/$stem.transcript.log"' \
    'mkfifo "$TRANSCRIPT_PIPE"' \
    'TRANSCRIPT_TEE_PID=$!' \
    'wait "$TRANSCRIPT_TEE_PID"' \
    '2>&1 | tee "$CONSOLE_LOG"' \
    'pipeline_status=("${PIPESTATUS[@]}")' \
    'gradle_status=${pipeline_status[0]}' \
    'console_tee_status=${pipeline_status[1]}' \
    'g4_admission_count=$(grep -Fc "$g4_admission_prefix" "$MINECRAFT_LOG" || true)' \
    '[ "$segment_start_line" -lt "$g4_admission_line" ]' \
    '&& [ "$g4_admission_line" -lt "$measure_start_line" ]'; do
    grep -Fq "$token" "$RUNNER" || fail "runner wiring token is missing: $token"
done

grep -Fq 'G4 Tier B evidence requires a clean worktree' "$RUNNER" \
    || fail "runner does not reject dirty G4 evidence before launch"

for exact_profile in \
    'require_value "$ROUTE_ID" "gi-g4-overworld-v1" "G4 benchmark route"' \
    '"d321131b314bb22cee354e3cf48606712d414d44a84e6ed00230e70d9c65839d"' \
    '"92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e"' \
    '"fcf752aebd45a576e13cc19b446b954014b66e46a78c79e435289314d3b4ebb3"' \
    'require_value "$WIDTH" "3024" "G4 render width"' \
    'require_value "$HEIGHT" "1964" "G4 render height"' \
    'require_value "$REFRESH_HZ" "120" "G4 refresh rate"' \
    'require_value "$WARMUP_FRAMES" "600" "G4 warmup frames"' \
    'require_value "$MEASURE_FRAMES" "600" "G4 measurement frames"' \
    'require_value "$TIMING_DETAIL" "1" "G4 timing detail"' \
    'require_value "$METAL_VALIDATION" "0" "G4 Metal Validation mode"' \
    'require_value "$METALFX_MODE" "OFF" "G4 MetalFX mode"'; do
    grep -Fq "$exact_profile" "$RUNNER" \
        || fail "runner does not fail closed on the G4 diagnostic profile"
done

echo "GI G4 benchmark runner contract passed"
