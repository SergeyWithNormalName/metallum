#!/bin/bash

set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
RUNNER="$ROOT/scripts/run_metal_benchmark.sh"
ROUTE="$ROOT/benchmark/routes/hdrtest-gi-visual-probe-v1.json"
CONTROLLER="$ROOT/src/main/java/com/metallum/client/benchmark/MetalFxBenchmarkController.java"

runtime_settings_fingerprint() {
    shasum -a 256 \
        "$ROOT/run/options.txt" \
        "$ROOT/run/config/metallum-hdr.properties" \
        "$ROOT/run/config/metallum-renderer.properties" \
        "$ROOT/run/config/metallum-metalfx.properties" \
        "$ROOT/run/config/metallum-metalfx-temporal.properties"
}

fail() {
    echo "GI visual-probe runner contract FAILED: $*" >&2
    exit 1
}

bash -n "$RUNNER" || fail "benchmark runner has invalid shell syntax"
values=$(python3 "$ROOT/tools/metal_benchmark_fixture.py" route-values "$ROUTE") \
    || fail "tracked visual-probe route did not parse"
[ "$(printf '%s\n' "$values" | awk -F '\t' '{ print NF; exit }')" -eq 37 ] \
    || fail "visual-probe route must expose exactly 37 fields"
IFS=$'\t' read -r route_id route_sha _fixture_id _fixture_sha \
    _player _uuid _dimension _x _y _z _yaw _pitch _ticks _weather _frozen \
    _stable _timeout _position_epsilon _angle_epsilon route_kind rig_id \
    torch_x torch_y torch_z torch_apply torch_observe torch_remove \
    orbit_start orbit_end orbit_translation_radius orbit_yaw orbit_pitch orbit_period \
    capture_0 capture_1 capture_2 capture_3 <<< "$values"
[ "$route_id" = "hdrtest-gi-visual-probe-v1" ] \
    && [ "$route_sha" = "dfd3c447d95ac9334a56e1bbb156f53ffa2fc5142280e8fe9be06076e910b2e1" ] \
    && [ "$route_kind" = "GI_VISUAL_PROBE" ] \
    && [ "$rig_id" = "red-reflector-occluded-v1" ] \
    && [ "$torch_x,$torch_y,$torch_z" = "80,75,-112" ] \
    && [ "$torch_apply,$torch_observe,$torch_remove" = "300,450,690" ] \
    && [ "$orbit_start,$orbit_end,$orbit_translation_radius,$orbit_yaw,$orbit_pitch,$orbit_period" = "540,690,0.75,12.0,3.0,120" ] \
    && [ "$capture_0,$capture_1,$capture_2,$capture_3" = "570,600,630,660" ] \
    || fail "visual-probe route constants differ"

for token in \
    '--gi-visual-probe)' \
    'GI_VISUAL_PROBE_ROUTE_SPEC="benchmark/routes/hdrtest-gi-visual-probe-v1.json"' \
    'GI visual probe captures saved (non-attested)' \
    'GI_VISUAL_PROBE_RIG_APPLIED' \
    'GI_VISUAL_PROBE_READY' \
    'GI_VISUAL_PROBE_GPU_DIRECT' \
    'GI_VISUAL_PROBE_GPU_FIELD' \
    'GI_VISUAL_PROBE_MOTION_BEGIN' \
    'GI_VISUAL_PROBE_MOTION_COMPLETE' \
    'GI_VISUAL_PROBE_ARM" = "on"' \
    'OFF arm must not emit a G6 readiness marker' \
    'GI_VISUAL_PROBE_SCREENSHOT' \
    'expected exactly four new PNGs' \
    'clone_only=true direct_path=OCCLUDED bounce_path=OPEN' \
    'METALLUM_BENCHMARK_VISUAL_PROBE_RIG_ID="$GI_VISUAL_PROBE_RIG_ID"' \
    'METALLUM_GI_G6_DEBUG_PROBE="$GI_G6_DEBUG_PROBE"' \
    'METALLUM_BENCHMARK_VISUAL_PROBE_ORBIT_TRANSLATION_RADIUS_BLOCKS="$GI_VISUAL_PROBE_ORBIT_TRANSLATION_RADIUS_BLOCKS"' \
    'METALLUM_BENCHMARK_VISUAL_PROBE_CAPTURE_FRAME_3="$GI_VISUAL_PROBE_CAPTURE_FRAME_3"'; do
    grep -Fq -- "$token" "$RUNNER" || fail "runner wiring token is missing: $token"
done

for token in \
    'GI_VISUAL_PROBE;' \
    'visualProbeScheduleIsExact' \
    'visualProbeOneBouncePathIsSeparated' \
    'visualProbeTransportRayIsExact' \
    'visualProbeDirectDdaPathClear' \
    'visualProbeCurrentAllCascadeReceipt' \
    'visualProbeCurrentPostTorchReceipt' \
    'driveVisualProbeGpuDirectProbe' \
    'visualProbeGpuDirectProbePasses' \
    'driveVisualProbeGpuFieldProbe' \
    'visualProbeGpuFieldProbePasses' \
    'visualProbePostTorchContinuityReceipt' \
    'visualProbeReadinessDeadlineExpired' \
    'applyVisualProbePoseForRender' \
    'auditVisualProbeMotion' \
    'installVisualProbeRig' \
    'direct_path=OCCLUDED bounce_path=OPEN'; do
    grep -Fq -- "$token" "$CONTROLLER" || fail "controller wiring token is missing: $token"
done

runtime_settings_before=$(runtime_settings_fingerprint)
for arm in on off; do
    preflight_output=$("$RUNNER" --gi-visual-probe "$arm" --preflight-only \
        --label "gi-visual-probe-contract-$arm" 2>&1) \
        || fail "GI visual probe $arm preflight-only failed"
    printf '%s\n' "$preflight_output" \
        | grep -Fq "GI_VISUAL_PROBE_REQUEST arm=$arm" \
        || fail "GI visual probe $arm preflight did not prove the dedicated non-attested request"
    printf '%s\n' "$preflight_output" \
        | grep -Fq "frames: 1800 warmup + 900 measurement" \
        || fail "GI visual probe $arm preflight changed its exact frame budget"
    printf '%s\n' "$preflight_output" \
        | grep -Fq 'GI_VISUAL_PROBE_RIG_APPLIED' \
        && fail "GI visual probe $arm preflight unexpectedly required a runtime log"
    [ "$(runtime_settings_fingerprint)" = "$runtime_settings_before" ] \
        || fail "GI visual probe $arm preflight did not restore temporary runtime settings"
done

echo "GI visual-probe runner contract passed"
