# Verification — 2026-09-30

Environment: Windows, Temurin Java 25.0.4.1, Paper 26.2 build 121. Compilation targets Paper API 26.2 build 129.

- Production JAR compilation and packaging: PASS; bundles SQLite and excludes both integration harness classes.
- JUnit: 15 tests passed; 0 failures or skips.
- Paper integration: all 14 phases passed across actual server restarts. The editor/guardian phase passed 109 assertions.
- Git whitespace checks: PASS.

## Editor repair

The shared weighted button previously required non-null item lore. Paper returns null for absent lore, so block-entry, item-entry, and guardian-encounter editors threw before opening. Weighted buttons now start with an empty list when needed and retain existing descriptions and weight/chance formatting.

New integration coverage uses real Paper items, inventories, and native dialog callbacks with a synthetic player. It navigates all three affected editor paths; applies weights, numeric fields, guardian names and booleans; copies custom items without consuming or changing the source; preserves invalid numeric/name submissions for retry; cancels input and confirmation dialogs; rejects stale, duplicate, unauthorized, wrong-player, and offline responses; prevents invalid creation from leaving phantom entries; and verifies SQLite persistence plus editor reopening after saving.

Injected dialog-display failures verify pending-state cleanup and restoration of the originating menu. Injected save-preparation failures verify that errors cannot be reported as successful saves. Their `Editor action failed: open dialog` and `Could not prepare editor save` stack traces in the phase-8 log are intentional assertions of diagnostic behavior.

Existing coverage remains passing: all 23 containers, capacity and weighted generation, stale saves, draft warnings, guardian combat/confinement/glow/replacement, extraction/explosion protection, terrain and slopes, descent and display lifecycle, obstruction retries, and event/loot recovery across restarts.

## Reproduction and limits

Run the build and isolated integration commands in README.md. The integration runner now selects the Windows or Unix Gradle wrapper automatically. This run used `C:/Documents/TigerMCE-Test-Server/paper.jar` solely as the server executable; generated data and logs remained under `build/integration-server`. No production server or plugin installation was changed.

JUnit HTML: `build/reports/tests/test/index.html`. Paper logs and pass markers: `build/integration-server/`. Native-dialog appearance and actual client interaction were not verified with a Minecraft client; the automated tests exercise server-side construction, callbacks, scheduling, and persistence.

Production artifact: `build/libs/SupplyDrops-1.0.0.jar`.

SHA-256: `e2c7ebdb9d961cedb94d09a19fc3d3f417d61d67712d64623665ae1dd2ded9ff`.
