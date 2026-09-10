# Logging deferral and ready wine audit — 2026-09-10

This is a read-only diagnosis, not a deployed fix. No routine was started, schedule changed, block removed, or game restarted during this audit. The operator resumed automation while observations were collected.

## Observed behavior

- At 21:10 KST the client was paused by mouse movement. This is separate from the preceding ON-state deferral report.
- Between 21:13:41 and 21:15:09 KST (45 bounded passive samples), the engine retained `resourceWaiting=LoggingModule` while `HarvestAndStorageModule` harvested and returned tomatoes, then `ArtisanModule` visited the crystal copiers. Other modules really did run during the retained logging wait; the HUD text alone was not used as evidence.
- At game day 599 the ancient-fruit line had no remaining or skipped members, `latestFeedDay=599`, `nextDueDay=605`, and all 96 registered loaded wine kegs were `working=true,mature=false`. This is different from the prior all-skipped day-593 pass. The current batch's feed acknowledgements and loaded block states agree.
- At 21:15:40 KST, game day 599, **all 432 registered tomato wine kegs were loaded and `working=false,mature=true`**, but the schedule was inactive with `nextDueDay=603`, no feed day, and 432 skipped members from day 597. The displayed status was the logging deferral. Thus the operator's report of ready wine left uncollected is valid, despite other routines being able to run.

## Independent causes

### Logging never resolves its environmental obstruction

The registered planting plot contains three one-layer `minecraft:snow` blocks: `(647,75,1597)`, `(647,75,1598)`, and `(648,75,1598)`. `LoggingModule.inspect` rejects these. `nextPlot` inspects all remaining plots before selecting another tree, so one obstructed plot postpones the whole logging routine. The current environmental-wait fix retries after 1,200 client ticks and releases other routines; it does **not** remove/replace snow or skip the obstructed plot to finish the other logging plots. Restarting does not resolve the snow.

Supporting snow replacement requires checking the module, planting navigation/hit selection, native execution, and the server-observed planting acknowledgement together. Merely relaxing the initial block check would leave downstream `isAir` checks and receipt validation inconsistent. Arbitrary blocks must not become removable or treated as successful planting.

### All-skipped wine pass still advances the entire rack by six days

`WineBatchRules.skip` correctly records no feed success. However, `finish` advances the next scheduled boundary even when every member was skipped and no feed occurred. `MachineModule.prepareWineRun` then returns idle until that date without considering currently mature blocks. This explains the tomato rack waiting until day 603 even though all members are ready on day 599. The earlier ancient-fruit delay had the same schedule pattern; that line has now actually run on day 599.

The requested six-day whole-rack cadence must be preserved deliberately when deciding how to recover an all-skipped pass; an audit must not silently change production dates or start an extra batch.

### HUD hides the actual reason wine is waiting

`AutomationEngine` prioritizes its retained logging resource-wait message over the normal next-work-day/sleep status at the end of an idle sweep. Therefore the same message appears both when other jobs are legitimately cooling down and when the wine date gate is leaving mature wine uncollected. It is not proof of successful continuation and should not be presented as such.

## Verification boundary

Evidence was gathered through explicitly bounded passive reads of the current client runtime, registered loaded block states, and persisted schedules. No native gameplay action, acknowledgement evaluation, custody reset, or completion fabrication was used. The ancient-wine feed transition occurred before the first passive sample; the audit verifies its persisted feed record and current block state, not a video of every click. No production code changed and no new test/build/restart claim is made.
