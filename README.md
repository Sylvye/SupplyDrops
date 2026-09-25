# SupplyDrops

Supply-drop events for **Paper 26.2 / Java 25**, configured entirely in-game. No resource pack or configuration-file editing is required.

## Install

1. Put `build/libs/SupplyDrops-1.0.0.jar` in the server's `plugins` directory.
2. Restart Paper and run `/sd` as an operator.
3. Open **Profiles → Example supply drop**. Choose worlds, review the tables and container assignments, enable the profile, and select **Save changes**.
4. Start an event from **Active Events → Spawn profile**, or enable its schedule and save.

The example profile and its schedule start disabled. Data is automatically stored in `plugins/SupplyDrops/supplydrops.db`. Back up the plugin directory with the worlds while the server is stopped. Never install the `-integration.jar` on a real server: it is a destructive test harness.

## In-game editors

The dashboard contains **Profiles**, **Block Tables**, **Item Tables**, **Guardian Encounters**, **Active Events**, and **Settings**. Menu edits stay in an isolated draft until **Save changes**. **Discard & close** abandons the entire draft. Closing a menu with unsaved changes sends a chat warning and retains the draft until disconnect. Conflicting saves are rejected; discard and reopen to load the latest settings.

- **Profiles:** enabled worlds, block count range, block/guardian tables, individual container assignments, interval, and minimum players.
- **Block tables:** searchable table entries and material catalog, plus an inventory picker that copies a held block's material without consuming it. Copper chest selections automatically become their waxed variant. Solid, non-gravity, single-block materials are supported; unsafe or unmineable administrative blocks are rejected.
- **Item tables:** copy any item from your inventory, including held items, without consuming it. Metadata, enchantments, custom components, and persistent data are retained. Set entry weights, quantity ranges, and table roll ranges. Worst-case contents must fit 27 slots. Each roll selects with replacement, then fills randomly selected slots without exceeding stack limits.
- **Container assignments:** normal chests, barrels, all four waxed copper chest variants, and uncolored plus all 16 colored shulkers each have an independent mapping. Multiple mappings can share a table.
- **Guardian encounters:** weighted groups of mobs, including empty encounters. Edit counts, names, health (`0` means vanilla), equipment copied from inventory, effects, vanilla drops, custom item-table drops, and confinement radius. An amplifier of `0` is potion level I.
- **Settings:** global radius, fall speed, launch distance above the destination, barrel width, announcement text and delay, sound, title and boss-bar toggles, guardian glow timer and activation radius, and maximum concurrent unfinished events per world.

Each block, item, and encounter entry shows its weight in gold and its effective single-roll chance in pink. Search filters do not change the chance denominator.

Profiles and tables can be renamed, duplicated, or deleted. Referenced tables cannot be deleted until reassigned. Duplicated profiles start disabled. Active events retain their generated blocks, loot, guardian settings, and timing even when their source tables change.

Announcement text accepts Adventure MiniMessage formatting and `{world}`, `{x}`, `{y}`, `{z}`, `{eta}` placeholders. For example:

```text
<aqua><bold>Supply drop</bold></aqua> <gray>• {world} • {x}, {y}, {z} • arriving in {eta}s</gray>
```

Sound uses a Minecraft sound key such as `minecraft:block.note_block.bell`; an empty value disables it.

## Commands and permissions

`/supplydrops` and `/sd` are equivalent. Profile IDs appear in the profile list and command completion.

| Command | Permission | Purpose |
| --- | --- | --- |
| `/sd` | edit or view | Admin dashboard, or active events for players |
| `/sd edit` | `supplydrops.edit` | Open the editor |
| `/sd events` | `supplydrops.view` | View event locations |
| `/sd spawn <profile-id>` | `supplydrops.spawn` | Random safe location |
| `/sd spawn <profile-id> <world> <x> <y> <z>` | `supplydrops.spawn` | Explicit pile base position; must pass location checks |
| `/sd inspect <event-id>` | `supplydrops.view` | Stage, location, remaining guardians, and errors |
| `/sd retry <event-id>` | `supplydrops.manage` | Retry a paused event after fixing its obstruction |
| `/sd unlock <event-id>` | `supplydrops.manage` | Unlock a landed event and remove its remaining guardians |
| `/sd cancel <event-id>` | `supplydrops.manage` | Remove an unfinished event and its locked blocks |

Editing, spawning, and management default to operators. `supplydrops.admin` grants all three. Viewing defaults to everyone. Event IDs accept an unambiguous prefix; tab completion provides short IDs. Console supports all event commands, but editing requires a player.

## Event rules

Defaults: global radius **1,000**, **20–40** blocks, **60s** announcement delay, **3 blocks/second** descent from **64 blocks above the destination**, a **4-block-wide barrel**, guardian glow **180s after the first nearby player arrives** or when at most **25%** remain, **60-minute** schedule, at least **one online player**, and **one unfinished event per world**. Default descent takes about 21.3 seconds, plus the 60-second announcement delay. Animation advances with server ticks; low TPS lengthens descent.

- Random positions are sampled uniformly within the radius and constrained by the world border. Location searches check up to eight candidates per tick, stop after 100, and report the leading rejection reasons. Sand and gravel provide valid dry support. Soft plants can be replaced at landing; liquids, tile entities, and solid blocks cannot. Terrain may vary by up to two blocks when the adjusted pile stays supported and connected. Entities do not obstruct placement. No claim-plugin integration is performed.
- Piles have connected, supported layouts. Chests remain single, with no event blocks above them. Shulkers face upward with opening clearance. Consecutive identical layouts are retried; small/constrained tables cannot guarantee infinite unique layouts.
- A single upright, closed barrel display descends smoothly using client interpolation. Its tracking position stays at the destination while the visual moves above it, avoiding altitude-based tracking loss. It has no collision or loot and is replaced by the saved pile on landing. Launch distance is independent of terrain elevation, including near build height; normal client and server view-distance limits still apply.
- Landing columns and support are reserved. Mining, placement, interaction, explosions, pistons, fluid flow, and inventory extraction are blocked while locked. Direct world changes by another plugin can pause landing; admins receive the location and can clear it and retry.
- Guardians spawn at landing. The glow timer starts only after a Survival or Adventure player comes within the global activation radius (default 32 blocks, measured in three dimensions) of the landed pile. It then continues after players leave and across restarts, including offline time. The independent 25%-remaining trigger still reveals survivors immediately. Replacements inherit revealed glow. Natural disappearance is reconciled by respawning the missing guardian. Environmental damage and friendly fire do not defeat them; player combat and player-owned pets do. Confinement, conversion prevention, and portal protection keep encounters local.
- EMPTY encounters unlock immediately. Once unlocked, the blocks become ordinary permanent world blocks. SupplyDrops does not clean them up, protect them, or refill their containers.
- Schedules do not accumulate missed events. If player counts or concurrency prevent a scheduled drop, the next attempt is the next interval.
- Announced, descending, landing, and guarded events recover after restarts without rerolling. SQLite checkpoints precede transitions; landing is replayable while locked. World saves at landing/unlock coordinate world and plugin state. Filesystem/hardware failures remain subject to the server's own world durability.

On upgrade, the former global default speed of 0.5 becomes 3 blocks/second; other saved speeds remain unchanged. Active events retain their saved timing and remaining distance, with the barrel replacing their old visual. Existing guarded drops wait for a nearby player before starting the new timer; already revealed glow is retained. Settings changes apply to future events only.

## Build and verification

Install Java 25 and run:

```sh
./gradlew clean test jar
```

The Gradle wrapper is included. The production JAR bundles SQLite; Paper supplies Adventure and Gson. No network download is needed by the plugin itself at startup.

Unit tests cover weighted distributions, chance formatting, glow thresholds and player proximity, interpolation endpoints and landing timing, invalid weights/ranges, scheduling conditions, pile connectivity/support/clearance, global-settings migration, snapshot isolation, and SQLite reopen behavior.

Run the isolated Paper integration suite with a Paper 26.2 server JAR:

```sh
JAVA_HOME=/path/to/jdk-25 \
PAPER_JAR=/absolute/path/to/paper-26.2.jar \
EULA=true python3 scripts/integration.py
```

`EULA=true` confirms acceptance of the Minecraft EULA for that local test server. Tests use only `build/integration-server`, bind to `127.0.0.1:25579`, and restart the server through lifecycle stages. Logs remain in that directory. They exercise all 23 containers, copied metadata, capacity checks, stale saves, descent past 30 seconds, guardian reconciliation and glowing, extraction/explosion protection, partial landing recovery, empty encounters, cancellation, permanent unlocked loot across restarts, GUI copy/click safety and draft warnings, native dialog creation, guardian customization, copper-golem protection, terrain classification, slopes, relative launch at low and high elevations, display replacement and cleanup, proximity activation and restart recovery, and obstruction retries.

Before a public rollout, also check menu appearance, native dialogs, item copying, and combat with a real Minecraft 26.2 client. Automated server tests cannot judge client-side presentation.
