# Verification — 2026-09-25

Environment: Java 25.0.4.1, Paper 26.2 build 129, macOS arm64.

- Production JAR compilation: PASS.
- JUnit: 10 tests passed; 0 failures or skips.
- Paper integration: 12 phases passed across actual server restarts.
- Production JAR startup, console commands, and clean shutdown: PASS.
- Production JAR excludes the integration test harness.

Coverage: all 23 container types; custom item serialization and copy safety; inventory capacity; weighted generation and chance formatting; connected/supporting pile layouts and opening clearance across gentle slopes; SQLite snapshots and legacy global-settings migration; stale editor saves; native dialog creation; GUI click cancellation and dirty-close warning; descent exceeding 30 seconds; recovery during announcement, descent, partial placement, combat, and after unlocking; guardian replacement, glow threshold, health, effects, confinement, and nine example mob types; hopper and copper-golem protection; explosion protection; empty encounters; cancellation; obstruction pause/retry; sand, gravel, vegetation, entities, water, world borders, and unavailable-world rejection. A 200-candidate loaded mixed-terrain sample accepted 82 sand and 89 inland sites in 21 ms.

Client-side appearance and full multiplayer combat have not been visually tested with a Minecraft client. Abrupt hardware failure is subject to Minecraft world-save durability; the restart suite exercises normal server restarts and partial-placement recovery.

Reproduce with the commands in README.md. Detailed logs are in `build/integration-server/`; JUnit HTML is in `build/reports/tests/test/index.html`.

Production JAR SHA-256: `5fc506e07afb177276888d4f71ac43d53b6a72cdfc6f8432852fcb306b0d2ecc`
