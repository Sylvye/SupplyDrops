# Verification — 2026-09-25

Environment: Java 25.0.4.1, Paper 26.2 build 129, macOS arm64.

- Production JAR compilation and packaging: PASS; includes barrel animation and excludes the integration harness.
- JUnit: 15 tests passed; 0 failures or skips.
- Paper integration: all 14 phases passed across actual server restarts.

New coverage: linear interpolation segments, final-segment completion before landing, relative launch coordinates and ETA, one upright closed barrel with ground-level tracking, four-block scale, disabled frustum culling, increased view range, missing-display replacement, legacy and duplicate entity cleanup, low/high terrain landings, retained barrel during obstruction pauses, and display removal after landing or cancellation. Global speed migration preserves custom values and active snapshots.

Guardian checks cover unarmed unattended drops, the independent 25% threshold, the exact three-dimensional 32-block activation boundary, Survival/Adventure eligibility, Creative/Spectator exclusion, retained activation after players leave, timer progression across restart without players, and glowing replacement guardians. Unit persistence checks include the activation timestamp and reveal flag.

Regression coverage includes all 23 container types, custom items, capacity, weighted generation, terrain and slope checks, SQLite snapshots, stale saves, GUI draft warnings, long descents, announcement/descent/partial-placement/combat/unlocked recovery, guardian customization and confinement, extraction/explosion protection, empty encounters, cancellation, and obstruction retries.

Client visual verification was not completed: the UI tool could not access the running Minecraft game window. Server tests verify interpolation metadata and lifecycle behavior, but smoothness and appearance when joining or approaching mid-descent still require a Minecraft client check. Normal client/server view-distance limits apply.

Reproduce with the commands in README.md. Detailed logs are in `build/integration-server/`; JUnit HTML is in `build/reports/tests/test/index.html`.

Production JAR SHA-256: `c43112bc56f367499edb1c0e0833094d1944ccd3b4e94b35c0db6e1b0278bd44`
