#!/bin/bash

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUNNER="$ROOT/scripts/run_metal_benchmark.sh"
ROUTE="$ROOT/benchmark/routes/hdrtest-torch-toggle-v1.json"
MATRIX_ROUTE="$ROOT/benchmark/routes/hdrtest-gi-g6-matrix-v1.json"
SETTINGS="$ROOT/benchmark/settings/native-hdr-fancy-gi-live-v1.json"
CONTROLLER="$ROOT/src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java"

fail() {
    echo "GI G6 runner contract FAILED: $*" >&2
    exit 1
}

bash -n "$RUNNER" || fail "benchmark runner has invalid shell syntax"
python3 "$ROOT/tools/metal_benchmark_fixture.py" self-test \
    || fail "benchmark fixture helper rejected dynamic GI settings"

[ -f "$ROUTE" ] || fail "tracked torch-toggle route is missing"
[ -f "$MATRIX_ROUTE" ] || fail "tracked G6 matrix route is missing"
[ -f "$SETTINGS" ] || fail "tracked live-GI settings are missing"

route_values=$(python3 "$ROOT/tools/metal_benchmark_fixture.py" route-values "$ROUTE") \
    || fail "G6 route does not satisfy the canonical route parser"
route_field_count=$(printf '%s\n' "$route_values" | awk -F '\t' '{ print NF; exit }')
[ "$route_field_count" -eq 28 ] || fail "G6 route is not a schema-3 torch-toggle route"
IFS=$'\t' read -r route_id route_sha fixture_id _fixture_sha \
    _player_name _player_uuid _dimension \
    _position_x _position_y _position_z _yaw _pitch \
    _ticks _weather _frozen stable_frames timeout_frames \
    _position_epsilon _angle_epsilon route_kind \
    _torch_x _torch_y _torch_z _initial _support apply_frames observe_frames remove_frames \
    <<< "$route_values"
[ "$route_id" = "hdrtest-torch-toggle-v1" ] || fail "G6 route id differs"
[ "$route_sha" = "7f0a03058371964e81ef95002644a1744793def24958ccebb8c808fb91e46cc8" ] \
    || fail "G6 route digest differs"
[ "$fixture_id" = "hdrtest-static-v1" ] || fail "G6 fixture differs"
[ "$route_kind" = "TORCH_TOGGLE" ] || fail "G6 route does not mutate a live source"
[ "$stable_frames" = "120" ] || fail "G6 stable-frame contract differs"
[ "$timeout_frames" = "1200" ] || fail "G6 timeout contract differs"
[ "$apply_frames" = "300" ] && [ "$remove_frames" = "450" ] \
    && [ "$observe_frames" = "300" ] \
    || fail "G6 torch-toggle epoch window differs"

matrix_values=$(python3 "$ROOT/tools/metal_benchmark_fixture.py" route-values "$MATRIX_ROUTE") \
    || fail "G6 matrix route does not satisfy the canonical route parser"
matrix_field_count=$(printf '%s\n' "$matrix_values" | awk -F '\t' '{ print NF; exit }')
[ "$matrix_field_count" -eq 72 ] || fail "G6 matrix route is not a schema-5 72-field route"
matrix_id=$(printf '%s\n' "$matrix_values" | cut -f1)
matrix_sha=$(printf '%s\n' "$matrix_values" | cut -f2)
matrix_fixture=$(printf '%s\n' "$matrix_values" | cut -f3)
matrix_tail=$(printf '%s\n' "$matrix_values" | cut -f20-)
expected_matrix_tail=$'GI_G6_MATRIX\tminecraft:torch\tminecraft:torch\t83.5\t75.0\t-99.0\t80\t75\t-112\tminecraft:air\t300\t420\t30\t270\t30.0\t10.0\t120\t490\t760\t1030\t1000\t1230\t13000\t1430\t1630\t1830\t40\t8\t16\t24\t32\t24\t16\t8\t0\t1\t0\t-1\t-1\t-1\t0\t1\t0\t2170\t320\t32\t0\t2430\t2620\t0\t96\t0\t3090'
[ "$matrix_id" = "hdrtest-gi-g6-matrix-v1" ] || fail "G6 matrix route id differs"
[ "$matrix_sha" = "e7bc60c8082ef1bf98c487b6158f0c08b8595fc55deb1290f97d06fa412e3934" ] \
    || fail "G6 matrix route digest differs"
[ "$matrix_fixture" = "hdrtest-static-v1" ] || fail "G6 matrix fixture differs"
[ "$matrix_tail" = "$expected_matrix_tail" ] || fail "G6 matrix event schedule differs"

python3 - "$SETTINGS" <<'PY' || exit 1
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
if payload.get("id") != "native-hdr-fancy-gi-live-v1":
    raise SystemExit("GI G6 runner contract FAILED: settings id differs")
renderer = payload.get("renderer_properties", {})
if renderer != {
    "improvedLighting": "true",
    "lightingPreset": "balanced",
    "globalIllumination": "dynamic",
}:
    raise SystemExit("GI G6 runner contract FAILED: persistent dynamic GI is not exact")
PY

for token in \
    '--gi-live)' \
    'GI_LIVE_ROUTE_SPEC="benchmark/routes/hdrtest-torch-toggle-v1.json"' \
    'GI_LIVE_SETTINGS_SPEC="benchmark/settings/native-hdr-fancy-gi-live-v1.json"' \
    'require_value "$RENDERER_GI_MODE" "dynamic" "G6 renderer globalIllumination"' \
    'G6 live mode rejects explicit G2/G3/G4/G5 diagnostic flags' \
    'RUNTIME_GI_MODE=g6_live' \
    'hdrtest-gi-g6-matrix-v1)' \
    'e7bc60c8082ef1bf98c487b6158f0c08b8595fc55deb1290f97d06fa412e3934' \
    'require_value "$ROUTE_KIND" "GI_G6_MATRIX" "G6 matrix route workload"' \
    'G6 matrix route must use the schema-5 72-field contract' \
    'G6_MATRIX_RESOURCE_RELOAD_FRAME - G6_MATRIX_CHUNK_RELOAD_FRAME' \
    'G6 matrix reload recovery gaps must be at least 270 measured frames' \
    'G6_MATRIX_STREAM_START_FRAME - G6_MATRIX_CLEAR_FRAME' \
    'G6 matrix static recovery gaps must be at least 200 measured frames' \
    'G6_MATRIX_STREAM_STEP_FRAMES" -ge 40' \
    'G6_MATRIX_STREAM_STEP_FRAMES" -le 60' \
    'G6 matrix stream steps must be between 40 and 60 measured frames' \
    'G6_MATRIX_TELEPORT_FRAME - G6_MATRIX_STREAM_START_FRAME' \
    '7 * G6_MATRIX_STREAM_STEP_FRAMES' \
    'G6 matrix final stream recovery gap must be at least 60 measured frames' \
    'G6_MATRIX_TELEPORT_RETURN_FRAME - G6_MATRIX_TELEPORT_FRAME' \
    'G6 matrix teleport stabilization gap must be at least 260 measured frames' \
    'G6_MATRIX_NETHER_ENTER_FRAME - G6_MATRIX_TELEPORT_RETURN_FRAME' \
    'G6 matrix reset handoff gap must be at least 190 measured frames' \
    'G6_MATRIX_NETHER_RETURN_FRAME - G6_MATRIX_NETHER_ENTER_FRAME' \
    'MEASURE_FRAMES - G6_MATRIX_NETHER_RETURN_FRAME' \
    'G6 matrix dimension recovery gaps and final tail must be at least 470 measured frames' \
    'require_value "$WARMUP_FRAMES" "1800" "G6 warmup frames"' \
    'require_value "$MEASURE_FRAMES" "3000" "G6 live/capture measurement frames"' \
    'require_value "$MEASURE_FRAMES" "3600" "G6 matrix measurement frames"' \
    '&& [ "$GI_LIVE" -eq 0 ]; then' \
    '&& [ "$GI_LIVE" -eq 0 ]; then' \
    'GI_G6_REQUEST mode=g6_live persistent=true dynamic=true receiver=true diagnostic_flags=false' \
    'g6_admission_prefix="METALLUM_BENCHMARK EVENT=GI_G6_ADMISSION "' \
    'requested=g6_live resolved=g6_live contract=6 state=READY' \
    'device_generation=[1-9][0-9]* presented_frame=[0-9]+' \
    'ready_mask=[1-7]' \
    'stale=0 rejected=0 status=PASS' \
    'G6 admission memory census exceeds the diffuse-GI cap' \
    'g6_final=' \
    'state=READY device_generation=[1-9][0-9]* admission_emitted=true admission_device_generation=[1-9][0-9]*' \
    'ready_mask=7 build_in_flight=false' \
    'latest_terrain_device_generation=[1-9][0-9]*' \
    'latest_bind_status=1 latest_carrier_safe=true latest_frame_compatible=true' \
    'latest_ready_mask=7 latest_exact_mask_nonzero=true' \
    'G6 admission, final census and terrain bind span different device generations' \
    'G6 final terrain bind predates the admitted terrain draw' \
    'G6 final terrain bind does not use the terminal field/source generation' \
    'block_samples=[1-9][0-9]*' \
    'block_p95_submits=[0-8]' \
    'block_p99_submits=([0-9]|1[0-6])' \
    'block_sla=true static_samples=[0-9]+' \
    'static_p95_submits=(-1|[0-9]+)' \
    'scroll_p95_submits=(-1|[0-9]+)' \
    'reset_p95_submits=(-1|[0-9]+)' \
    'queue_pending=0 queue_in_flight=0 queue_algebra=true' \
    'measurement_start_bytes=[1-9][0-9]* accounted_delta=0 readback_bytes=0' \
    'G6 unused $class_name class must report 0/-1/-1/false' \
    'G6 used $class_name class violated its p95/p99 SLA' \
    'G6 final queue census violates queued=completed+discarded' \
    'G6 final accounted_delta=0 disagrees with measurement-start bytes' \
    'G6 final census must follow MEASURE_END' \
    'G6 final census must precede COMPLETE' \
    'phase=TORCH_ON measured_frame=400' \
    'G6 torch-on screenshot marker is outside the confirmed torch epoch' \
    'matrix_event_count' \
    'G6 matrix must emit exactly 22 event receipts' \
    'G6 orbit changed field generation or latency-class samples' \
    'static_source_samples=[0-9]+ scroll_samples=[0-9]+' \
    'action=STREAM_STEP requested_frame=$matrix_frame measured_frame=[0-9]+' \
    'y_offset=$matrix_stream_y_offset status=PASS' \
    'G6 matrix event $matrix_action missed its deterministic completion window' \
    'matrix_event_actual_frames[$matrix_event_index]=$matrix_actual_frame' \
    'matrix_recovery_count' \
    'G6 matrix must emit exactly 20 independent recovery receipts' \
    'STATIC_SOURCE STATIC_SOURCE TERRAIN_BIND FULL_RESET' \
    'FULL_RESET FULL_RESET FULL_RESET FULL_RESET' \
    'baseline_generation=[1-9][0-9]*' \
    'latency_class=$matrix_recovery_class' \
    'sample_before=[0-9]+ sample_after=[1-9][0-9]*' \
    'matrix_recovery_ready_mask=7' \
    'matrix_recovery_build_in_flight=false' \
    "matrix_recovery_ready_mask='[1357]'" \
    "matrix_recovery_build_in_flight='(true|false)'" \
    'build_in_flight=$matrix_recovery_build_in_flight status=PASS' \
    'G6 matrix recovery $matrix_recovery_action reused another mutation' \
    '[ "$matrix_recovery_frame" -lt "$matrix_next_action_frame" ]' \
    'G6 matrix recovery $matrix_recovery_action lies outside its recovery window' \
    'terrain_submit_before=[0-9]+ terrain_submit_after=[0-9]+' \
    'terrain_field_after=[1-9][0-9]* current_field=[1-9][0-9]*' \
    'terrain_source_after=[0-9]+ current_source=[0-9]+' \
    '[ "$matrix_recovery_action" = "RESOURCE_RELOAD" ]' \
    'G6 matrix terrain reload did not prove a newer current same-device bind' \
    'G6 matrix recovery $matrix_recovery_action did not close before the next mutation' \
    'G6 STREAM_STEP_7 near receipt did not close before TELEPORT_OUT' \
    'G6 TELEPORT_OUT FULL_RESET +1/ready7/!inflight did not close before RETURN' \
    'GI_G6_MATRIX_NETHER_PREPARE' \
    'G6 Nether target chunk was not prepared before MEASURE_START' \
    'G6 NETHER_ENTER did not follow its pre-measure prepared-chunk receipt' \
    'receipts=511 orbit_field_stable=true queue_converged=true accounted_delta=0 status=PASS contract=6'; do
    grep -Fq -- "$token" "$RUNNER" || fail "runner wiring token is missing: $token"
done

for matrix_env in \
    HELD_ITEM ENTITY_ITEM ENTITY_POSITION_X ENTITY_POSITION_Y ENTITY_POSITION_Z \
    LAVA_POSITION_X LAVA_POSITION_Y LAVA_POSITION_Z LAVA_INITIAL_BLOCK \
    LAVA_APPLY_FRAME LAVA_REMOVE_FRAME ORBIT_START_FRAME ORBIT_END_FRAME \
    ORBIT_YAW_AMPLITUDE_DEGREES ORBIT_PITCH_AMPLITUDE_DEGREES ORBIT_PERIOD_FRAMES \
    CHUNK_RELOAD_FRAME RESOURCE_RELOAD_FRAME DAY_FRAME DAY_TICKS NIGHT_FRAME NIGHT_TICKS \
    RAIN_FRAME CLEAR_FRAME STREAM_START_FRAME STREAM_STEP_FRAMES \
    STREAM_OFFSET_0 STREAM_OFFSET_1 STREAM_OFFSET_2 STREAM_OFFSET_3 \
    STREAM_OFFSET_4 STREAM_OFFSET_5 STREAM_OFFSET_6 STREAM_OFFSET_7 \
    STREAM_Y_OFFSET_0 STREAM_Y_OFFSET_1 STREAM_Y_OFFSET_2 STREAM_Y_OFFSET_3 \
    STREAM_Y_OFFSET_4 STREAM_Y_OFFSET_5 STREAM_Y_OFFSET_6 STREAM_Y_OFFSET_7 \
    TELEPORT_FRAME TELEPORT_OFFSET_X TELEPORT_OFFSET_Y TELEPORT_OFFSET_Z \
    TELEPORT_RETURN_FRAME NETHER_ENTER_FRAME NETHER_POSITION_X NETHER_POSITION_Y \
    NETHER_POSITION_Z NETHER_RETURN_FRAME; do
    grep -Fq -- "METALLUM_BENCHMARK_G6_MATRIX_${matrix_env}=\"\$G6_MATRIX_${matrix_env}\"" \
        "$RUNNER" || fail "runner does not export G6 matrix field: $matrix_env"
    case "$matrix_env" in
        STREAM_OFFSET_*) controller_matrix_env="METALLUM_BENCHMARK_G6_MATRIX_STREAM_OFFSET_" ;;
        STREAM_Y_OFFSET_*) controller_matrix_env="METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_" ;;
        *) controller_matrix_env="METALLUM_BENCHMARK_G6_MATRIX_${matrix_env}" ;;
    esac
    grep -Fq -- "$controller_matrix_env" "$CONTROLLER" \
        || fail "controller does not parse G6 matrix field: $matrix_env"
done

python3 - "$RUNNER" <<'PY' || exit 1
from pathlib import Path
import sys

runner = Path(sys.argv[1]).read_text(encoding="utf-8")
start = runner.index('if [ "$GI_LIVE" -eq 1 ]; then\n'
                     '    [ "$GI_G2_CAPTURE_ENV" -eq 0 ]')
end = runner.index('elif [ "$GI_G5_RECEIVER_ENV" -eq 1 ]; then', start)
g6_profile = runner[start:end]
if 'require_value "$CAPTURE_REFERENCE" "0"' in g6_profile:
    raise SystemExit(
        "GI G6 runner contract FAILED: --capture-reference is blocked for G6"
    )
recovery_loop = runner.index(
    'for matrix_recovery_index in "${!matrix_recovery_actions[@]}"; do'
)
recovery_loop_end = runner.index(
    '    [ "$g6_nether_prepare_line" -lt "${matrix_event_lines[20]}" ]',
    recovery_loop,
)
recovery_body = runner[recovery_loop:recovery_loop_end]
event_index_assignment = recovery_body.index(
    'matrix_event_index=${matrix_recovery_event_indices[$matrix_recovery_index]}'
)
numeric_window_lookup = recovery_body.index(
    'matrix_action_frame=${matrix_event_actual_frames[$matrix_event_index]}'
)
if event_index_assignment >= numeric_window_lookup:
    raise SystemExit(
        "GI G6 runner contract FAILED: recovery event index follows numeric window lookup"
    )
if recovery_body.count(
        'matrix_event_index=${matrix_recovery_event_indices[$matrix_recovery_index]}'
        ) != 1:
    raise SystemExit(
        "GI G6 runner contract FAILED: recovery event index mapping is not unique"
    )
ready_default = recovery_body.index('matrix_recovery_ready_mask=7')
build_default = recovery_body.index('matrix_recovery_build_in_flight=false', ready_default)
scroll_condition = recovery_body.index(
    'if [ "$matrix_recovery_class" = "SCROLL" ]; then', build_default
)
near_mask = recovery_body.index(
    "matrix_recovery_ready_mask='[1357]'", scroll_condition
)
near_build = recovery_body.index(
    "matrix_recovery_build_in_flight='(true|false)'", near_mask
)
generic_mask = recovery_body.index(
    'build_in_flight=$matrix_recovery_build_in_flight status=PASS$', near_build
)
if not ready_default < build_default < scroll_condition < near_mask < near_build < generic_mask:
    raise SystemExit(
        "GI G6 runner contract FAILED: scroll near-ready mask/in-flight selection is out of order"
    )
if recovery_body.count("matrix_recovery_ready_mask='[1357]'") != 1 \
        or recovery_body.count("matrix_recovery_build_in_flight='(true|false)'") != 1 \
        or recovery_body.count('ready_mask=7 build_in_flight=false status=PASS$') != 2:
    raise SystemExit(
        "GI G6 runner contract FAILED: scroll/non-scroll recovery coverage is not exact"
    )
PY

for token in \
    'GiLiveRuntime.admissionState() == GiLiveRuntime.AdmissionState.INVALID' \
    'G6_TORCH_ON_SCREENSHOT_MEASURED_FRAME = 400' \
    '&& GiLiveRuntime.isRequested()' \
    '!this.torchEpochAppliedLogged || this.torchEpochRemovalRequested' \
    'phase=TORCH_ON measured_frame={}' \
    'GiLiveRuntime.FinalSnapshot snapshot = GiLiveRuntime.finalSnapshot();' \
    'verifyGiLiveWarmupAdmission()' \
    'G6 warmup ended without a current admission receipt' \
    'GiLiveRuntime.finalReceiptIsCurrent(' \
    'snapshot.readyMask() != 7' \
    'snapshot.buildInFlight()' \
    'snapshot.staleRejects() != 0L' \
    'snapshot.rejectedCount() != 0L' \
    'snapshot.accountedBytes() > 25_165_824L' \
    'snapshot.blockSamples() <= 0L' \
    'snapshot.blockP95Submits() > 8' \
    'snapshot.blockP99Submits() > 16' \
    '!snapshot.blockSla()' \
    'snapshot.schedulerPending() != 0' \
    'snapshot.schedulerInFlight() != 0' \
    '!snapshot.schedulerAlgebraExact()' \
    'snapshot.schedulerQueued()' \
    'snapshot.measurementStartAccountedBytes() <= 0L' \
    'static_samples={} static_p95_submits={}' \
    'scroll_samples={} scroll_p95_submits={}' \
    'reset_samples={} reset_p95_submits={}' \
    'queue_queued={} queue_completed={} queue_discarded={}' \
    'queue_pending=0 queue_in_flight=0 queue_algebra=true' \
    'measurement_start_bytes={} accounted_delta=0' \
    'readback_bytes=0' \
    'METALLUM_BENCHMARK EVENT=GI_G6_FINAL '; do
    grep -Fq -- "$token" "$CONTROLLER" \
        || fail "controller final contract token is missing: $token"
done

for token in \
    'GI_G6_MATRIX;' \
    'G6_MATRIX_RECEIPT_ALL = (1 << 9) - 1' \
    'G6_MATRIX_RELOAD_RECOVERY_GAP_FRAMES = 270' \
    'G6_MATRIX_STATIC_RECOVERY_GAP_FRAMES = 200' \
    'G6_MATRIX_TELEPORT_STABILIZATION_TIMEOUT_FRAMES = 260' \
    'G6_MATRIX_DIMENSION_STABILIZATION_TIMEOUT_FRAMES = 470' \
    'G6_MATRIX_TELEPORT_RECOVERY_GAP_FRAMES = 260' \
    'G6_MATRIX_RESET_RECOVERY_GAP_FRAMES = 190' \
    'G6_MATRIX_DIMENSION_RECOVERY_GAP_FRAMES = 470' \
    'g6MatrixAllCascadeStabilizationTimeoutFrames(' \
    'g6MatrixRecoveryWindowsSufficient(' \
    'g6MatrixTerrainQueueGateSatisfied(' \
    'return !requiresTerrainBinding || terrainQueueEmpty;' \
    'GI_G6_MATRIX recovery gaps are below the exact 270/200/260/190/470-frame floors' \
    'METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_READY route={}' \
    'action=ORBIT_BEGIN measured_frame={} field_generation={}' \
    'action=ORBIT_END measured_frame={} baseline_generation={}' \
    'block_samples={} static_source_samples={}' \
    'METALLUM_BENCHMARK_G6_MATRIX_STREAM_Y_OFFSET_' \
    'this.g6MatrixStreamTargetYOffset = config.streamYOffsets()[index];' \
    'this.route.y() + this.g6MatrixStreamTargetYOffset' \
    'requested_frame={} measured_frame={} index={} offset={}' \
    'y_offset={} status=PASS' \
    'requested_frame={} measured_frame={} status=PASS' \
    'METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_RECOVERY route={} action={}' \
    'baseline_generation={}' \
    'latency_class={} sample_before={}' \
    'captureG6MatrixTerrainRecoveryBaseline(snapshot);' \
    'case LAVA_APPLY, LAVA_REMOVE -> G6_MATRIX_LATENCY_STATIC_SOURCE;' \
    'case DAY, NIGHT, RAIN, CLEAR -> G6_MATRIX_LATENCY_FULL_RESET;' \
    'case TELEPORT, TELEPORT_RETURN, NETHER_ENTER, NETHER_RETURN ->' \
    'terrain_submit_before={}' \
    'terrain_device_after={} current_device={}' \
    'terrain_field_after={} current_field={}' \
    'terrain_source_after={} current_source={}' \
    'bind_status=1 carrier_safe=true frame_compatible=true' \
    'METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_NETHER_PREPARE route={}' \
    'getChunkNow(' \
    'releaseG6MatrixNetherChunk(server);' \
    'finally {' \
    'server.tickRateManager().setFrozen(frozen);' \
    'build_in_flight=false status=PASS' \
    'sample_after={} ready_mask={} build_in_flight={} status=PASS' \
    'METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_FINAL_CENSUS route={}' \
    'queue_pending={} queue_in_flight={} queue_queued={}' \
    'queue_completed={} queue_discarded={} queue_algebra={}' \
    'queue_converged={} accounted_current={} accounted_start={}' \
    'accounted_expected_start={} accounted_delta={} accounting_stable={}' \
    'block_change_samples={} block_change_p95_submits={}' \
    'block_change_p99_submits={} block_change_sla={}' \
    'static_source_samples={} static_source_p95_submits={}' \
    'static_source_p99_submits={} static_source_sla={}' \
    'scroll_samples={} scroll_p95_submits={} scroll_p99_submits={}' \
    'scroll_sla={} full_reset_samples={} full_reset_p95_submits={}' \
    'full_reset_p99_submits={} full_reset_sla={}' \
    'latency_census_complete={} status=FAIL contract=6' \
    'see GI_G6_MATRIX_FINAL_CENSUS' \
    'METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_FINAL route={}' \
    'receipts=511 orbit_field_stable=true' \
    'queue_converged=true accounted_delta=0' \
    'status=PASS contract=6'; do
    grep -Fq -- "$token" "$CONTROLLER" \
        || fail "controller matrix contract token is missing: $token"
done

python3 - "$CONTROLLER" <<'PY' || exit 1
from pathlib import Path
import sys

controller = Path(sys.argv[1]).read_text(encoding="utf-8")
teleport_out = controller.index('} else if (frame == config.teleportFrame()) {')
teleport_return = controller.index(
    '} else if (frame == config.teleportReturnFrame()) {', teleport_out
)
nether_enter = controller.index(
    '} else if (frame == config.netherEnterFrame()) {', teleport_return
)
nether_return_driver = controller.index(
    '} else if (frame == config.netherReturnFrame()) {', nether_enter
)
driver_end = controller.index(
    '    private GiLiveRuntime.FinalSnapshot requireCleanG6MatrixSnapshot(',
    nether_return_driver,
)
teleport_out_body = controller[teleport_out:teleport_return]
if teleport_out_body.count('requireNearReadyG6MatrixSnapshot(') != 1 \
        or 'requireCleanG6MatrixSnapshot(' in teleport_out_body:
    raise SystemExit(
        "GI G6 runner contract FAILED: TELEPORT_OUT lost its exact-near scroll handoff"
    )
for action, body in (
        ("TELEPORT_RETURN", controller[teleport_return:nether_enter]),
        ("NETHER_ENTER", controller[nether_enter:nether_return_driver]),
        ("NETHER_RETURN", controller[nether_return_driver:driver_end])):
    if body.count('requireCleanG6MatrixSnapshot(') != 1 \
            or 'requireNearReadyG6MatrixSnapshot(' in body:
        raise SystemExit(
            f"GI G6 runner contract FAILED: {action} accepted a near-only field"
        )
near_gate = controller.index(
    'private GiLiveRuntime.FinalSnapshot requireNearReadyG6MatrixSnapshot('
)
near_gate_end = controller.index(
    'private void logG6MatrixPendingRecovery(', near_gate
)
near_gate_body = controller[near_gate:near_gate_end]
if 'this.g6MatrixAwaitingRecovery != null' not in near_gate_body \
        or 'snapshot.buildInFlight(), true' not in near_gate_body:
    raise SystemExit(
        "GI G6 runner contract FAILED: near handoff does not require a closed receipt/exact bit0"
    )
nether_return = controller.index('case NETHER_RETURN -> {')
nether_return_end = controller.index('\n            }\n        }', nether_return)
nether_return_body = controller[nether_return:nether_return_end]
teleport = nether_return_body.index('teleportG6MatrixPlayer(')
unforce = nether_return_body.index('releaseG6MatrixNetherChunk(server);')
if unforce <= teleport:
    raise SystemExit(
        "GI G6 runner contract FAILED: measured Nether cleanup does not follow return teleport"
    )
complete = controller.index('private String completeG6Matrix()')
complete_end = controller.index('private void driveNetherLavaStress', complete)
if '|| this.g6MatrixNetherChunkForcedByBenchmark' not in controller[complete:complete_end]:
    raise SystemExit(
        "GI G6 runner contract FAILED: matrix final does not require measured Nether unforce"
    )
start = controller.index('"METALLUM_BENCHMARK EVENT=GI_G6_MATRIX_FINAL route={}')
end = controller.index('this.route.routeId()', start)
if "readback_bytes" in controller[start:end]:
    raise SystemExit(
        "GI G6 runner contract FAILED: matrix final duplicates the G6 readback census"
    )
PY

echo "GI G6 benchmark runner contract passed"
