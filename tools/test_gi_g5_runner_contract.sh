#!/bin/bash

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUNNER="$ROOT/scripts/run_metal_benchmark.sh"
ROUTE="$ROOT/benchmark/routes/gi-g4-overworld-v1.json"

fail() {
    echo "GI G5 runner contract FAILED: $*" >&2
    exit 1
}

bash -n "$RUNNER" || fail "benchmark runner has invalid shell syntax"
[ -f "$ROUTE" ] || fail "tracked frozen-field route is missing"
python3 "$ROOT/tools/gi_g5_contract.py" --self-test \
    || fail "G5 evidence negative self-test failed"

route_values=$(python3 "$ROOT/tools/metal_benchmark_fixture.py" route-values "$ROUTE") \
    || fail "frozen-field route does not satisfy the canonical route parser"
route_field_count=$(printf '%s\n' "$route_values" | awk -F '\t' '{ print NF; exit }')
[ "$route_field_count" -eq 19 ] || fail "G5 route is not a schema-1 static route"
IFS=$'\t' read -r route_id _route_sha fixture_id _fixture_sha \
    _player_name _player_uuid _dimension \
    _position_x _position_y _position_z _yaw _pitch \
    _ticks _weather _frozen stable_frames timeout_frames \
    _position_epsilon _angle_epsilon <<< "$route_values"
[ "$route_id" = "gi-g4-overworld-v1" ] || fail "G5 frozen-field route id differs"
[ "$fixture_id" = "hdrtest-static-v1" ] || fail "G5 route fixture differs"
[ "$stable_frames" = "120" ] || fail "G5 route stable-frame contract differs"
[ "$timeout_frames" = "3000" ] || fail "G5 route timeout must be 3000 frames"

for token in \
    'METALLUM_GI_G5_RECEIVER must be a boolean diagnostic flag' \
    'METALLUM_GI_G5_RECEIVER_ARM must be control, candidate, or field when G5 is requested' \
    'METALLUM_GI_G5_RECEIVER_ARM requires METALLUM_GI_G5_RECEIVER=1' \
    'GI_G5_RECEIVER_ARM=control' \
    'GI_G5_RECEIVER_ARM=candidate' \
    'GI_G5_RECEIVER_ARM=field' \
    'GI_G5_FIELD_KIND=zero' \
    'GI_G5_FIELD_KIND=g4' \
    'RUNTIME_GI_MODE=g5_vertex_receiver' \
    'G5 receiver arms reject explicit G2/G3/G4 diagnostic flags' \
    'METALLUM_GI_G5_RECEIVER="$GI_G5_RECEIVER_ENV"' \
    'METALLUM_GI_G5_RECEIVER_ARM="$GI_G5_RECEIVER_ARM"' \
    '-Dmetallum.planar_reflections=false' \
    '"$GI_G5_RECEIVER_ENV" \' \
    'G2/G3/G4/G5 diagnostics cannot run under the release-contract profile' \
    'G5 Tier B evidence requires a clean worktree' \
    'g5_admission_prefix="METALLUM_BENCHMARK EVENT=GI_G5_ADMISSION "' \
    'requested=g5_vertex_receiver resolved=g5_vertex_receiver contract=4 ' \
    'state=READY arm=$GI_G5_RECEIVER_ARM field=$GI_G5_FIELD_KIND' \
    'phase=WARMUP presented_frame=[0-9]+ resources=5 bindings=5 ' \
    'carrier_skips=0 g5_carrier_writes=[1-9][0-9]* ' \
    'drawn_g5_carrier_slices=[1-9][0-9]* status=PASS vertex_only=true ' \
    'g5_combined_accounted_bytes=$((22637928 + g5_allocated_bytes))' \
    'METALLUM_BENCHMARK EVENT=GI_G5_FINAL state=READY carrier_skips=0 g5_carrier_writes=[1-9][0-9]* drawn_g5_carrier_slices=[1-9][0-9]* status=PASS' \
    'grep -Ec "${g5_final}$"' \
    'grep -nE "${g5_final}$"' \
    'expected exactly one final zero-skip G5 carrier census' \
    'G5 control/candidate must not claim standalone G4 READY admission'; do
    grep -Fq -- "$token" "$RUNNER" || fail "runner wiring token is missing: $token"
done

if grep -Fq 'grep -Fxc "$g5_final"' "$RUNNER" \
        || grep -Fq 'grep -nFx "$g5_final"' "$RUNNER"; then
    fail "runner still requires an impossible unprefixed whole Minecraft log line"
fi

request_token='shared_g4_resources=true explicit_g4_request=false'
[ "$(grep -Fc "$request_token" "$RUNNER")" -eq 1 ] \
    || fail "runner must emit one explicit shared-resource/non-G4 request receipt"

python3 - "$RUNNER" <<'PY' || exit 1
from pathlib import Path
import sys

runner = Path(sys.argv[1]).read_text(encoding="utf-8")
start = runner.index('if [ "$RUNTIME_GI_MODE" = "g5_vertex_receiver" ]; then\n'
                     '    echo "GI_G5_RECEIVER_REQUEST')
end = runner.index('elif [ "$RUNTIME_GI_MODE" = "g4_transport" ]; then', start)
g5_transcript_branch = runner[start:end]
if "GI_G4_TRANSPORT_REQUEST" in g5_transcript_branch:
    raise SystemExit(
        "GI G5 runner contract FAILED: G5 transcript claims a standalone G4 request"
    )
PY

for exact_profile in \
    'require_value "$ROUTE_ID" "gi-g4-overworld-v1" "G5 benchmark route"' \
    '"d321131b314bb22cee354e3cf48606712d414d44a84e6ed00230e70d9c65839d"' \
    '"92f083512f14472312e0f0dbc13a7a033c26af907ccc6318fa2216758a9c0d7e"' \
    '"fcf752aebd45a576e13cc19b446b954014b66e46a78c79e435289314d3b4ebb3"' \
    'require_value "$WIDTH" "3024" "G5 render width"' \
    'require_value "$HEIGHT" "1964" "G5 render height"' \
    'require_value "$REFRESH_HZ" "120" "G5 refresh rate"' \
    'require_value "$WARMUP_FRAMES" "600" "G5 warmup frames"' \
    'require_value "$MEASURE_FRAMES" "600" "G5 measurement frames"' \
    'require_value "$TIMING_DETAIL" "1" "G5 timing detail"' \
    'require_value "$METAL_VALIDATION" "0" "G5 Metal Validation mode"' \
    'require_value "$METALFX_MODE" "OFF" "G5 MetalFX mode"'; do
    grep -Fq "$exact_profile" "$RUNNER" \
        || fail "runner does not fail closed on the G5 diagnostic profile"
done

echo "GI G5 benchmark runner contract passed"
