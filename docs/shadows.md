# Shadows

Metallum has two complementary shadow systems.

## Solar shadows

Sun/moon light uses cascaded shadow maps. `SunShadowLayout` chooses the cascade
layout and `SunShadowStabilizer` preserves camera-relative texel stability.
Resets are required on discontinuities such as world changes and teleports.

## Local shadows

L5 stores static block occupancy in GPU clipmaps. L6 uses that occupancy to
produce and sample bounded local-light shadow data; publication, residency and
fallback state must stay synchronized with the lighting generation.

The local system is bounded by `LocalVoxelShadowLayout`, not by an unbounded
per-light render pass. Existing pages may be retained only while their geometry
and generation remain valid. Any failure must choose a defined safe fallback
and be visible in telemetry.

## Correctness rules

- Never free a page, buffer or texture while a submitted command buffer can
  still use it.
- Do not hide leaks through thin geometry by increasing self-hit tolerance:
  the production bound is `0.08` block, with receiver-plane correction handled
  independently.
- Treat a change to filtering, atlas resolution or update cadence as a visual
  change until reference scenes and live acceptance prove otherwise.
