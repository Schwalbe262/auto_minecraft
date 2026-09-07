# Architecture and validation

## Boundaries

`core` is independent of Minecraft: immutable observations, a sealed action allow-list, module lifecycle, persistent per-profile game-day schedules, and the single-owner scheduler. `navigation` plans only through loaded, registered farm/waypoint corridors. Each feature in `modules` is independently enabled. `client` adapts these ports to the real local player, observes standard server packets, persists local settings, and handles focus/manual-input cancellation. `ui` handles explicit registration and configuration.

There is no server module, custom networking protocol, fake player, world-state editor, break/place action, or external AI call during operation.

## Authoritative inventory handling

Minecraft 1.20.1 can omit confirmation packets when a predicted inventory click exactly matches the server. Auto Valley therefore sends **one** ordinary container click without client prediction, using state ID `-1` to request vanilla's full resynchronization. The server executes the click once and sends the authoritative container content. The client requires a subsequent full-content update for that exact container ID plus the expected item change. Unrelated slot updates do not confirm a transfer. Timeout never resends a click.

Only cursor-free QUICK_MOVE, SWAP, and exact rotten-item THROW operations are exposed. Deposits are constrained by the opened registered container's grade/year/product classification. The action layer checks the configured focus policy, actual reach, line of sight, current held item, menu identity, and cursor state again before dispatch. Main-hand block interactions have no air-use fallback. Output pickup is verified separately from machine block-state changes.

A Netty handler observes vanilla replies after vanilla queues their application. It sends no custom packets and suppresses world destroy/entity interaction packets while automation owns control. Raw attack input is also fenced when it triggers manual takeover, including remapped attack controls.

## Scheduling

Background operation defaults on. Alt/Tab/Windows task switching is exempt from manual takeover, while actual game movement, mouse input, and remapped attacks still stop automation. Background operation uses game APIs, never OS input or global hooks. Vanilla pause-on-lost-focus is temporarily suppressed only while background automation runs and restored on stop. Foreground-only mode remains available. Disconnection and unsafe health/hunger always stop work.

Explicit local diagnostic/control request files support inspection and start/pause without stealing window focus. They contain no arbitrary input or mutation API; start uses the same runtime checks as F8. Snapshots contain local positions/inventory and must not be published.

Emergency stop also intercepts the configured key before an open GUI consumes it; mouse-bound stop is handled before ordinary mouse dispatch, including canceled events. A stop latch discards queued toggle/settings/waypoint clicks, and a short client-tick gate rejects new starts and consumes pending external start/once files rather than deferring them until after the stop. Unreadable/non-regular requests extend that fence until confirmed consumption or absence, so a temporarily locked file cannot become a delayed restart. Stop never sends inventory cleanup or closes an in-flight transaction; existing late-acknowledgement fences remain authoritative.

- Date is `floor(dayTime / 24000)`; UI shows date + 1.
- Successful harvest stores a per-farm next date. Successful refill stores a per-machine next date. Start/stop and reconnect retain these dates.
- Harvest checks wait until day tick 20 for Dew Drop's dawn growth. Busy machines wait through day tick 219 before being declared unfinished for that day.
- Machine defaults: wine 6 days, preserves 3 days. Harvest 1 day is a conservative observation interval; actual growth depends on crop stage and fertilizer.
- Resource shortage is ordinary waiting in continuous mode. In one-shot production, insufficient refill ingredients pause the selected job without marking the unfunded machine complete or postponing its retry to tomorrow. Uncertain mutation or missing output remains blocked until manual review. A blocked wine task suppresses preserves; any unresolved work suppresses automatic sleep.
- A backwards game date clears obsolete deadlines and pauses for review.

## Verification

### One-shot work and manual recording

`AutomationEngine.startOnce` selects exactly one module. It grants only that feature a temporary session permission, never changes saved feature toggles, respects day deadlines, and stops on completion or blocking rather than scheduling another day. Stops revoke the temporary permission. Runtime refuses work with no registered target or while a manual recording is unsaved.

The passive recorder uses an explicit outbound packet allow-list and sampled client state, not OS hooks or raw packet dumps. Intent and server-observed state have distinct event types. A recent use-block target is only an unverified temporal hint for menu ownership. Connection and recording generations fence queued callbacks. Local UTF-8 JSONL journals flush each second and require a user-supplied name; no replay or upload is included. Gameplay coordinates/inventory are private and ignored by version control.

### Durable machine output

A mature-machine use first saves an immutable output obligation synchronously, before `submit` can send its main-hand use. Confirmed refill state and confirmed pickup are separate checkpoints. Reset, F8, feature disable, day deadlines and reconnect never erase the obligation. The scheduler pauses other work rather than allowing storage, sale or sleep to hide unresolved output; only the active owning machine can finish its confirmation/pickup stage.

Same-connection inventory observations can resolve a confirmed pickup, including while OFF and before a storage consumer runs. Wine counts must match the expected native production year. Manual item interactions or unmanaged screens permanently invalidate that live evidence, so a later manual withdrawal cannot masquerade as the pending bottle. A reconnect starts without live evidence. Ground disappearance alone never resolves an obligation.

The settings UI distinguishes explicit manual recovery/cleanup from confirmed loss, requires a second confirmation for one unchanged operation ID, remains paused and retains the entry if saving fails. Resolution history is bounded to 64 entries. Profile schema 1 is validated and migrated to schema 2 in memory; the next save uses schema 2 so old clients reject it instead of ignoring pending output. Back up the local profile before a deliberate version downgrade.

### Storage acknowledgements and scale

Up to eight immutable full-menu ACK snapshots per container preserve an earlier successful transfer even if a later pickup refills the player slot before the next tick. Confirmed source removal and destination increase, not a later live count alone, establish deposit quantity. No overflow-transport counter controls starting, completing or scheduling work; storage and disposal operate on the actual player inventory.

Vinery's native `WineYears.getYear(Level)` is the aging clock. It is not the sleep-skipping day calendar. The UI accepts current wine age and stores the stable raw production cohort: existing wine stays in its container as its age increases. Selling held surplus requires a fresh full-capacity check of every registered reserve for that cohort; no reserve wine is withdrawn. Permissions expire at 1,200 ticks and on date/registration changes.

One surplus run checks the bounded set of initially held cohorts. A verified not-full reserve retains that cohort without granting a sale permission, then the run checks the next one. Invalid contents, unknown ages and unloaded reserves still block. Per-cohort permissions are revoked before proceeding; sale counts require acknowledged destination increases. New unverified cohorts cannot silently join a finished run. The result reports sold and retained quantities rather than implying that all carried wine was sold.

Machine runs reuse acknowledged source stock counts while consuming held ingredients and recount before new hauls, date changes, and stale batches. Farms are an arbitrary list, not two fixed slots. Per-field volume and profile-file size protections remain. Bulk machine registration previews connected same-ID blocks, warns before accepting a partial scan, rejects changed or unloaded selected blocks, and never infers storage contents.

Production hauls estimate the remaining eligible-machine ingredient demand, including normal/upgraded jar costs, and withdraw cursor-free stacks while reserving two actual empty inventory slots. A haul is additionally bounded by the leading grade's stock advantage plus one recipe, leaving room when another grade becomes the priority. A funded machine can continue with one remaining free output slot. Apparent room in partial stacks is not counted as safe spare capacity because server metadata may prevent merging. Stock classification and largest-total-grade selection are rechecked after each source acknowledgement. An indivisible final source stack can overfetch by at most 63 tomatoes.

### Area harvest and bounded movement

The selected native hoe range defines the inspected area but is not evidence that neighboring crops were harvested. Area-first ordering keeps every original crop queued as a fallback; observed maturity alone skips a covered target. Before each use, a footprint validates potentially interactive plants against the union of registered tomato farms, including native upper-cell fallback. An unknown footprint blocks harvesting. A read-only tool preflight can inspect the same geometry for an inventory stack without selecting or modifying it.

For installed Quark 4.0-462, native range two means a half-span of one (golden hoe: 3-by-3). The maximum permitted configuration value must not replace that actual range: doing so falsely included unrelated crops in live preflight. This supports Society 4.1.4 default crop rules with matching client/server range settings, consistent with the manual golden-hoe demonstration. Local/server booleans do not establish numeric or custom crop-map equality; server-only range changes, custom mappings and third-party handlers require revalidation and are not covered by this model.

Only full-inventory-enabled harvest can opt into bounded movement during one outstanding use. A movement-only navigator cannot open doors, and the native adapter allows at most ten ticks of grounded, short-distance harvest overlap. Camera-relative forward/strafe components preserve the route's world heading while the view stays on the clicked crop, without diagonal speed amplification. Inventory, machine, door and sleep actions still exclude movement. Calibration and non-capable adapters retain stopped harvesting. Production timing and pickup confirmation remain separate from movement, and no world attack or break action is introduced.

### Bounded native inventory consolidation

`ProductionMergePlanner` proposes owned-inventory tomato/product merges; reduced quality/year metadata only selects candidates. `ConsolidateInventory` is a separate allow-listed action and does not relax storage-only `QuickMove`. It prefers one native opposite-region shift move, otherwise uses a tomato/empty production hotbar scratch and at most three cursor-free primitives. A tomato-only hotbar-to-empty-main reposition can enable a subsequent merge when all fragments occupy hotbar slots. Protected hoe slots and arbitrary tool scratch slots are excluded.

The adapter checks client-visible native tags/capabilities and capacity before dispatch. Each subsequent primitive requires an exact raw-stack full-menu acknowledgement and matching current live inventory, including an empty cursor and unchanged crafting/armor/offhand slots. Quantity conservation and actual empty-slot gain, not intent, establish a merge. Native metadata stays in bounded RAM-only snapshots and is not added to recordings, diagnostics or profiles. Server share tags can hide metadata; native server click behavior remains authoritative, and a refused/mere-reposition operation cannot count as successful consolidation.

Cancel sends no restoration or cleanup clicks. A late in-flight inventory reply fences all new automatic actions; an exact changed reply or connection-generation change can resolve the fence. Unchanged/unrelated full snapshots never advance a primitive. A refused/no-op server click can therefore require reconnecting after timeout. The inventory layout may differ after cancellation, but all intermediate operations are cursor-free and scoped to held ingredients/products. Explicit restart re-plans from current slots rather than restoring an old layout. Per-run attempt bounds and position-independent no-progress fingerprints prevent self-shuffling retries. Scheduler action failures pause all consumers, including one-shot completion. Output-ledger obligations must be resolved before consolidation starts.

JUnit tests cover classification, source recounts, grade ties, priority, action cancellation, server-state waits, production/pickup failures, game-day deadlines, dawn races, sleep rejection, path constraints, door acknowledgement, real upper tomato vines, calibration, profile round trips and malformed-file preservation, and registration validation.

Build the distributable with `gradlew test build`; `reobfJar` produces the mapped release JAR. A separate title-screen-only UI preview is available with `gradlew runClient -PuiPreview`. This property opens settings in a development client without loading a world.

These checks do not substitute for actual registration and first-cycle verification on the user's server. No unattended production-server run is claimed by the build tests.

## Development references

- [Forge one-sided mods](https://docs.minecraftforge.net/en/1.20.x/concepts/sides/#writing-one-sided-mods)
- [Society source](https://github.com/Chakyl/society-sunlit-valley)
- [Farmer's Delight](https://github.com/vectorwing/FarmersDelight)

Installed Society 4.1.4 scripts and the installed Farmer's Delight 1.3.2, Dew Drop 9.0, and Vinery 1.4.41 JARs were used to verify exact local behavior. Moving upstream branches can differ. Third-party game/mod binaries are not redistributed in this repository.
