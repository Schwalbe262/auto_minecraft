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

- Date is `floor(dayTime / 24000)`; UI shows date + 1.
- Successful harvest stores a per-farm next date. Successful refill stores a per-machine next date. Start/stop and reconnect retain these dates.
- Harvest checks wait until day tick 20 for Dew Drop's dawn growth. Busy machines wait through day tick 219 before being declared unfinished for that day.
- Machine defaults: wine 6 days, preserves 3 days. Harvest 1 day is a conservative observation interval; actual growth depends on crop stage and fertilizer.
- Resource shortage is ordinary waiting. Uncertain mutation or missing output remains blocked until manual review. A blocked wine task suppresses preserves; any unresolved work suppresses automatic sleep.
- A backwards game date clears obsolete deadlines and pauses for review.

## Verification

### One-shot work and manual recording

`AutomationEngine.startOnce` selects exactly one module. It grants only that feature a temporary session permission, never changes saved feature toggles, respects day deadlines, and stops on completion or blocking rather than scheduling another day. Stops revoke the temporary permission. Runtime refuses work with no registered target or while a manual recording is unsaved.

The passive recorder uses an explicit outbound packet allow-list and sampled client state, not OS hooks or raw packet dumps. Intent and server-observed state have distinct event types. A recent use-block target is only an unverified temporal hint for menu ownership. Connection and recording generations fence queued callbacks. Local UTF-8 JSONL journals flush each second and require a user-supplied name; no replay or upload is included. Gameplay coordinates/inventory are private and ignored by version control.

### Storage, magnet and scale

Up to eight immutable full-menu ACK snapshots per container preserve an earlier successful transfer even if magnet pickup refills the player slot before the next tick. Confirmed source removal and destination increase, not a later live count alone, establish deposit quantity. A session ledger retains undelivered overflow until confirmed storage/disposal removes it; disappearance is not delivery. Production and sleep wait for the ledger to clear.

Vinery's native `WineYears.getYear(Level)` is the aging clock. It is not the sleep-skipping day calendar. The UI accepts current wine age and stores the stable raw production cohort: existing wine stays in its container as its age increases. Selling held surplus requires a fresh full-capacity check of every registered reserve for that cohort; no reserve wine is withdrawn. Permissions expire at 1,200 ticks and on date/registration changes.

Machine runs reuse acknowledged source stock counts while consuming held ingredients and recount before new hauls, date changes, and stale batches. Farms are an arbitrary list, not two fixed slots. Per-field volume and profile-file size protections remain. Bulk machine registration previews connected same-ID blocks, rejects partial/unloaded changes, and never infers storage contents.

JUnit tests cover classification, source recounts, grade ties, priority, action cancellation, server-state waits, production/pickup failures, game-day deadlines, dawn races, sleep rejection, path constraints, door acknowledgement, real upper tomato vines, calibration, profile round trips and malformed-file preservation, and registration validation.

Build the distributable with `gradlew test build`; `reobfJar` produces the mapped release JAR. A separate title-screen-only UI preview is available with `gradlew runClient -PuiPreview`. This property opens settings in a development client without loading a world.

These checks do not substitute for actual registration and first-cycle verification on the user's server. No unattended production-server run is claimed by the build tests.

## Development references

- [Forge one-sided mods](https://docs.minecraftforge.net/en/1.20.x/concepts/sides/#writing-one-sided-mods)
- [Society source](https://github.com/Chakyl/society-sunlit-valley)
- [Farmer's Delight](https://github.com/vectorwing/FarmersDelight)

Installed Society 4.1.4 scripts and the installed Farmer's Delight 1.3.2, Dew Drop 9.0, and Vinery 1.4.41 JARs were used to verify exact local behavior. Moving upstream branches can differ. Third-party game/mod binaries are not redistributed in this repository.
