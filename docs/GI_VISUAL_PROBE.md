# GI visual probe

`--gi-visual-probe on|off` is a disposable-world visual diagnostic. It is not G6 evidence,
does not update historical receipts or manifests, and never creates a performance attestation.

The runner first takes a strict APFS CoW clone of the immutable fixture. Only inside that clone,
the controller builds `red-reflector-occluded-v1`: a black enclosure, a torch, a red reflector,
a one-cell black direct-light baffle, and a white receiver. The field's exact rounded direct DDA
from the torch to the white receiver hits `(81, 76, -111)` and is blocked; its DDA to the red
reflector is clear. The receiver then samples the red surface along the exact axis lattice ray
`(+1, 0, 0)` with `k=2`; `(83, 77, -110)` is the required known-empty intervening cell. This is
the stronger physical fixture, within the G4 `k<=8` transport bound.

Run the two identical-geometry arms separately:

```bash
scripts/run_metal_benchmark.sh --gi-visual-probe off --label gi-visual-off
scripts/run_metal_benchmark.sh --gi-visual-probe on --label gi-visual-on
```

The torch is placed at measurement frame 300. Both arms reserve the same fixed 260-frame B16
all-cascade stabilization bound before motion capture; the torch remains present until frame 690
and the observation closes at frame 750. Each arm captures absolute frames 570, 600, 630, and 660
while the camera moves from frame 540 through 689. In the ON arm the controller requires a current
authoritative G6 receipt before the deadline: all three cascades ready, no build in flight, an exact
terrain bind, and a source or field advance relative to the pre-torch tuple. It logs
`GI_VISUAL_PROBE_READY` with the exact tuple. Every individual ON capture then rechecks the same
current post-torch all-cascade predicate, so a later dirty epoch cannot silently reuse the early
marker. Motion is applied before rendering, after the frozen-route pose lock: the client camera
translates on a tracked `0.75`-block orbit while yaw and pitch change. This crosses the block-scale
C0 recenter boundary. `GI_VISUAL_PROBE_MOTION_BEGIN/COMPLETE` bracket the real post-present terrain
binding counters; any zero binding, stale tuple, or missing field bind during the interval fails the
run. Each screenshot records its exact camera pose and the zero/field counter pair.

Before any ON screenshot the runner also enables the benchmark-only
`METALLUM_GI_G6_DEBUG_PROBE=1` path. It first requires one asynchronous
`GI_VISUAL_PROBE_GPU_DIRECT` receipt within 60 presented frames from the existing G3 C0 direct-field
probe. The raw geometry enum is strict: `EMPTY=1`, `CONTENT=2`; `UNKNOWN=0` and `FALLBACK=3` reject
the run. The red reflector must have finite, nonzero red-dominant direct irradiance and positive
support alpha; the white receiver behind the baffle must have exactly zero direct RGBA. This separates
an upstream direct-light or geometry failure from transport rather than letting a bright direct term
masquerade as a bounce.

Only after that receipt does the runner require one asynchronous `GI_VISUAL_PROBE_GPU_FIELD` receipt.
It reads exactly seven immutable G6 texels: three C0 white-receiver voxels, the C0 red reflector, the
baffle diagnostic, and the same white point in C1/C2. The receipt preserves raw FP16-decoded RGB L1
SH coefficients, confidence, coverage, and the native identity/origin tuple. It passes only when every
requested sample is usable, the C0 white and red values are nonzero, and the red reflector's raw L0
red term exceeds green and blue. It does not apply a gain or infer a visual result from the baffle. The
OFF arm forces this flag off and rejects either GPU receipt.

The OFF arm intentionally has no G6 field and therefore emits no readiness or G6 motion marker; it
preserves the same world, torch epoch, pre-render camera motion, and capture frames. The runner
verifies one readiness marker, one zero-free motion interval, four distinct camera poses and four
current receipts in ON, rejects G6 markers in OFF, then stores
four PNGs below `run/lighting-reference/l0/<run-stem>/`. It does not compare pixels or declare the
result visually accepted: a reviewer must compare matching ON/OFF frames for a stable, warm red
contribution on the white receiver and must reject temporal flicker, missing bounce, or geometry
mismatch.
