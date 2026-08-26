# Rejected G4 static-epoch run

Status: `REJECTED`, not G4 completion evidence.

The clean `44dae31cbfc4` run reached the exact 24-frame G3 startup receipt, then
failed before G4 admission with:

```text
METALLUM_BENCHMARK EVENT=FAIL reason=G4 transport became invalid: frozen G3/G2 source drifted
```

The source had not actually changed. The check compared G3's logical native
`staticSourceEpoch=1` with the process-local `AdvancedLightRegistry` epoch,
which had already advanced during startup. Commit `77f8938` separated those
domains and compares the captured registry identity only against later registry
identity; its regression test also proves that a real post-freeze registry
mutation is still rejected. Commit `460d294` then froze the sole `OFF` mode
before source capture and made the receipt order explicit.

This directory deliberately has no summary: the benchmark terminated on the
fail-closed event and did not complete measurement. These logs are retained so
the false-stale failure and its repair cannot be mistaken for a passing run or
silently rediscovered.
