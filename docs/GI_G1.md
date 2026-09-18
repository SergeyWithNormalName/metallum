# GI Stage G1: isolated field infrastructure

Status: `PASS_ABSOLUTE_FLOOR_REVALIDATED`. Commit `88f9dda` remains the original
mechanically validated field-only implementation, and
`benchmark/gi/g1-field-evidence-v1.json` preserves its historical
`SUPPORTED_PENDING_ABSOLUTE_FLOOR` result. Two fresh current-source Tier C
Overworld receipts now clear the sole outstanding `28/20 FPS` gate;
`benchmark/gi/gi-stage-gates-2026-08-26-v1.json` records `g2_allowed=true`.

## What G1 contains

- Three world-snapped `32^3` cascades at `2/4/8` blocks per cell, covering
  `64/128/256` blocks.
- Six mip levels from `32^3` through `1^3`.
- Private `RGBA16Float` mechanical payload and `R8Unorm` coverage textures.
- A coverage-aware compute reduction whose four payload channels deliberately
  have no material, emission, irradiance, or bounce semantics.
- Versioned Java/FFM/Swift ABI with exact layout validation.
- Render-thread ownership, generation reset, stale snapshot rejection, bounded
  worker-candidate accounting, and deferred native release injection.
- One-shot diagnostic capture only. It is synchronous by design and has no
  frame-loop caller.

G1 exposes no texture handle or bind method, adds no frame-graph pass, and is
not constructed by the production renderer. The existing quarantined frozen
reflection prototype remains separate; G1 does not call its appearance model,
Sodium extractor, directional probe, or receiver paths.

## Mechanical evidence

The real M1 Pro Metal validation reported:

| Item | Result |
| --- | ---: |
| Arithmetic texture payload | `1,011,123 bytes` |
| Actual Metal allocated size | `1,277,952 bytes` |
| Diffuse GI budget | `25,165,824 bytes` |
| Mip dispatches per build | `15` (`3 cascades × 5`) |
| Full project check | `98 tasks PASS` |

Forced-source Metal API/GPU validation verifies private upload, numerical mip
output, reset and stale rejection, one-shot capture, and release while an upload
is still in flight. Repeated bounded candidate publication returns active count
and bytes to zero. Existing Advanced/generated-shader tests prove that ordinary
production terrain output contains no G1 symbol or resource binding.

## Runtime boundary

A contemporaneous 600+600 control/candidate pair measured:

| Source | FPS | 1% low | GPU p95 |
| --- | ---: | ---: | ---: |
| Recovery control `c94ae9a` | `27.390` | `21.705` | `40.445 ms` |
| G1 field-only `88f9dda` | `27.337` | `20.749` | `40.033 ms` |

The candidate deltas are `-0.20%` FPS, `-4.40%` 1% low, and `-1.02%` GPU p95,
so the relative no-regression gate passes. Both sources were below the 28 FPS
conditional absolute floor in that interval, which shows that the drop is not
caused by G1 but does not waive the absolute gate.

The exact `1800+3000` G1 Tier C Overworld receipt is valid and attested:
`27.430 FPS`, `22.473` 1% low, `39.483 ms` GPU p95, nominal thermal state,
zero timing drops, Advanced admission PASS, and all-zero GI_OFF resources,
passes, bindings, shader symbols, epochs, and work counters. It fails only the
28 FPS average floor. `.accepted.json` means the receipt passed attestation; it
does not mean the product floor passed.

## Current floor revalidation

The clean current source `6a52f909d985` includes the same structurally-off G1
field infrastructure and produced two independent 1800+3000 Overworld runs:

| Run | FPS | 1% low | GPU p95 | Decision |
| --- | ---: | ---: | ---: | --- |
| 1 | `44.104` | `31.833` | `24.583 ms` | PASS |
| 2 | `44.058` | `32.060` | `24.557 ms` | PASS |

Both pass the unchanged conditional `28/20 FPS` floor. The historical
contemporaneous G1 control/candidate screening already passed the relative
no-regression gate; the new receipts resolve only the absolute-floor blocker
and do not convert the isolated field into production rendering work.

## Stop gate

G1 code is retained because its mechanical, relative, and current absolute
gates pass while it adds no production work. The opt-in G2 semantic diagnostic
still does not authorize a receiver, transport, or any production GI path.
G3 remains a separately requested stage even after G2 completion.

No quality setting, receiver, native resolution, Advanced lighting stage,
shadow path, or MetalFX policy may be changed to satisfy this gate.
