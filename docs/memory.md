# Memory and resource lifetime

Metallum crosses Java, Swift and Metal ownership domains. Java reachability does
not tell the GPU when a resource is safe to free.

## Java and Panama

Use confined arenas for one synchronous ABI packet and explicitly close
long-lived shared arenas. Do not create direct memory segments in a render-loop
hot path; use the established packet rings and pools.

## Swift and opaque handles

Swift ARC owns native objects retained behind raw handles. Every retained handle
needs one explicit release path, and the native bridge must validate type,
device and bounds before use. Force-unwrapping a Java-provided address is an
open P1 risk documented in [TECH_DEBT.md](../TECH_DEBT.md).

## GPU retirement

`MetalDestructionQueue` retires Java-requested releases only after the relevant
in-flight submissions complete. This rule applies equally to buffers, textures,
pipeline-adjacent workspaces and Temporal/Frame-Interpolation rings.

No production frame path may use a GPU-to-CPU synchronization readback. GPU
blits, private history textures and completion callbacks preserve the required
ordering without stalling the render thread.
