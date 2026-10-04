# Verification — 2026-10-02

Environment: Windows, Temurin Java 25.0.4.1, Paper 26.2 build 121. Compilation targets Paper API 26.2 build 129.

- Production JAR compilation and packaging: PASS; bundles SQLite and excludes integration harness classes.
- JUnit: 23 tests passed; 0 failures or skips.
- Paper integration: all 14 phases passed across actual server restarts. The editor/guardian phase passed 368 assertions.
- Git whitespace checks: PASS.

## Unavailable world references

Saving validates profile settings independently of whether its selected worlds are currently loaded. Enabled profiles with missing, invalid, or empty world selections can be saved, including while another profile is being edited. Spawn and profile-readiness validation require at least one available selected world; random spawning uses only those worlds. Existing unavailable references remain visible and removable in the Worlds editor.

Paper regression checks save enabled stale-world and empty-world profiles, verify their settings survive SQLite save and editor reopen, reject spawning either without creating an event, exclude missing/null/blank references from spawn candidates, remove `paper_26_2_123456789` through the menu, select `world`, and save that repair while other profiles still contain unavailable worlds.

## Guardian attributes and withers

The attribute editor searches only attributes present in the selected mob type's default attribute set. It shows mob-specific defaults, supports base-value edits and reset, rejects non-finite values and invalid Max health, and retains invalid submissions for retry. Paper checks verify search, cancellation without creating an override, save, and reopen. Configuration validation rejects unknown and unsupported attributes even when profiles are disabled.

Schema version 3 converts legacy positive Health into Max health overrides, preserves explicit overrides, and keeps vanilla defaults for zero. Unit tests cover migration idempotence, the schema-2 upgrade, snapshot isolation, and SQLite reopen. Runtime conversion occurs before world availability checks. Paper verifies native effective-value clamping, potion modifiers, fractional Max health and remaining health, default attributes, replacement health limits, and persisted attributes on an existing guardian after restart.

Withers are selectable and valid guardians; the Ender Dragon remains excluded. Real Paper withers charge, heal to the configured maximum, retain damaged health and attributes on replacement, inherit glow, remain confined, and produce vanilla/custom drops. Direct block changes and both wither/skull explosion events preserve locked pile protection while permitting surrounding terrain damage. A live wither survives the phase-8/phase-9 server restart with its attributes, damaged health, glow, and completed charge intact; forced unlock removes it. The tests make nearby attribute fixtures immune so the normal charge explosion does not kill them before assertions complete.

## Menu icons, resource names, and group immunity

Equipment menus display cloned assigned items with their original appearance and metadata, or representative slot icons when unassigned. Paper checks verify copy and clear actions, preserved source quantities and lore, and display-only slot descriptions. Mob catalogs, filtered results, and existing groups use spawn eggs, with a fallback for types without an egg.

Resource lists and assignments display names. Spawn completion supplies profile names; integration checks exercise case-insensitive name commands and legacy IDs with explicit coordinates. Unit tests verify restricted characters, duplicate rejection, name-over-ID precedence, deterministic upgrade normalization, collision suffixes, unchanged reference keys, and migration idempotence. Internal IDs remain stable; active-event displays include the snapshot profile name and event ID.

Damage immunity defaults OFF, including legacy configurations and event snapshots. Editor checks verify toggle, save, and reopen behavior. The guardian phase uses mixed ON/OFF groups and verifies fire, fall, drowning, explosions, hostile melee/projectiles, player melee/projectiles, and owned-pet attacks. An actual environmental death defeats an OFF guardian. Both immunity values survive SQLite reopen and all subsequent Paper restarts.

## Editor repair

The shared weighted button previously required non-null item lore. Paper returns null for absent lore, so block-entry, item-entry, and guardian-encounter editors threw before opening. Weighted buttons now start with an empty list when needed and retain existing descriptions and weight/chance formatting.

New integration coverage uses real Paper items, inventories, and native dialog callbacks with a synthetic player. It navigates all three affected editor paths; applies weights, numeric fields, guardian names and booleans; copies custom items without consuming or changing the source; preserves invalid numeric/name submissions for retry; cancels input and confirmation dialogs; rejects stale, duplicate, unauthorized, wrong-player, and offline responses; prevents invalid creation from leaving phantom entries; and verifies SQLite persistence plus editor reopening after saving.

Injected dialog-display failures verify pending-state cleanup and restoration of the originating menu. Injected save-preparation failures verify that errors cannot be reported as successful saves. Their `Editor action failed: open dialog` and `Could not prepare editor save` stack traces in the phase-8 log are intentional assertions of diagnostic behavior.

Existing coverage remains passing: all 23 containers, capacity and weighted generation, stale saves, draft warnings, guardian combat/confinement/glow/replacement, extraction/explosion protection, terrain and slopes, descent and display lifecycle, obstruction retries, and event/loot recovery across restarts.

## Reproduction and limits

Run the build and isolated integration commands in README.md. The integration runner now selects the Windows or Unix Gradle wrapper automatically. This run used `C:/Documents/TigerMCE-Test-Server/paper.jar` solely as the server executable; generated data and logs remained under `build/integration-server`. No production server or plugin installation was changed.

JUnit HTML: `build/reports/tests/test/index.html`. Paper logs and pass markers: `build/integration-server/`. Native-dialog appearance and actual client interaction were not verified with a Minecraft client; the automated tests exercise server-side construction, callbacks, scheduling, and persistence.

Production artifact: `build/libs/SupplyDrops-1.0.0.jar`.

SHA-256: `a17c26099834012c539d00cfc001d5343d1471948d662e44830c14562d59ce1a`.
