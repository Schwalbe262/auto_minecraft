# Independent wine production lines

Wine production now shares one machine engine while keeping each recipe's input
warehouse, machine group, output reserve and calendar independent.

The implicit tomato line preserves its existing machine registrations, cached
tomato stock, `wineBatchSchedule`, per-machine deadlines and vintage reserves.
Additional lines use `wineProductionLines`, `wineProductionSchedules` and
`wine-line:<id>:<position>` keys. Their dates never derive from the tomato batch.
Currently verified recipes are tomato → Stal wine and ancient fruit → ancient
vespertine; similar item names do not authorize additional recipes.

## Ancient-fruit line

The installed Society 4.1.4 KubeJS `wineKeg.js` defines
`society:ancient_fruit` → `society:ancient_vespertine`, three ingredients per
machine and six game days. Read-only loaded-block discovery found 64 new kegs
beside the existing 32-container ancient-fruit warehouse and eight separate
ordinary barrels. They do not overlap the existing 432 tomato kegs.

All 64 new kegs were already working, not mature. The player does not remember
the initial fill day. The chosen first boundary is six game days after the first
registered inspection, without collecting or refilling working machines.

Unlike Stal wine, the installed ancient output is a plain `BlockItem`, not
Vinery's `DrinkBlockItem`. A missing Vinery year is therefore valid. Native
quality/tag compatibility still controls stacking; the Stal-only passive aging
exception was not broadened to this item.

## Boundaries and behavior

- Stock is counted by grade; the largest available grade starts a supply batch.
  Carried ingredients are used before another warehouse survey. Ancient stock
  does not read or write the tomato stock cache.
- The native feed receipt binds the exact registered recipe's selected item,
  native identity, quantity and target block reply. One recipe cannot reuse
  another recipe's acknowledgement.
- Unused ancient ingredients return to their designated source. Recipe changes
  retain in-flight ownership and yield for ordinary cleanup between lines.
- Ancient wine uses all eight reserve barrels, without reserving one barrel per
  year. Every carried compatible group gets a chance to fill available space.
- Surplus shipping needs fresh server-confirmed fullness of every registered
  reserve: all ordinary slots contain 64 of that line's exact output. One free
  slot or any other item prevents a permit. Permits are line-specific, expire,
  and shrink only on acknowledged delivery quantities. Tomato vintage permits
  are never reused.
- Normal and one-shot storage/shipping visit both legacy and additional lines.
  Idle wrappers must yield so that harvesting, production and sleeping continue.
- Existing F8/manual takeover rules remain. No synthetic inventory acknowledgement,
  time advancement or unsupported replay was introduced.

## Settings and expansion

`Ctrl+F8 → 실행·기록 → 와인 생산 구역` shows each line's recipe, storage counts,
cycle and next date. Each line has its own ON/OFF; the common wine feature switch
still controls production globally. Changing a cycle does not erase a saved pass.

The additional line's expansion button scans nearby machine candidates, starts
with no selection, and saves only the chosen additions. It never puts those kegs
into the legacy tomato POI list. Active batch membership and other schedules stay
unchanged; new machines join a later scheduled pass. Storage expansion uses the
existing named commodity-group expansion screen under saved locations.

Profile schema 6 prevents older clients from silently dropping these definitions.
Loading an old profile migrates only in memory; explicit saves retain backups.
The add-only work import cannot overwrite registrations or inject schedules.

## Verification

Tests cover independent schedules and rollback, recipe/facility separation,
grade-aware consolidation with a protected ingredient hand, year-less output,
full-reserve permits, positive native delivery acknowledgements, explicit expansion,
profile round-trips, and real engine progression past idle composite routines.

The final combined `test build` passed: 2,175 tests in 163 suites, with zero
failures, errors or skipped tests. A detached import preflight against a copy of
the actual profile also passed: the 64-machine line, 32-container input and
eight-container output were added without changing the 432 tomato machines,
their calendar, farms, artisan jobs, or the actual profile file.

Built JAR SHA-256:
`5D36D3E9753EC2178BBC7EA6CE174C34A9853EFF64D374A762A6F62024312DCA`.
The existing Society client was normally closed, the backed-up JAR replaced,
and the same instance restarted. Its installed hash matches the tested build.
The final build includes small-screen settings feedback and the machine-specific
expansion help text.

### Live registration and first inspection

The normal local import was initially refused because automation had been
switched on between inspection and import. It was paused through the normal
control path; a second import was acknowledged at 2026-09-10 06:42:06 UTC.
The resulting profile contains the additional 64 machines, existing 32-container
source, and eight-container reserve. The legacy POI list and wine batch schedule
were compared with the pre-install backup and were unchanged.

A normal one-shot WINE run was then acknowledged. It inspected all 64 already
working machines, skipped them as still producing, and completed with no eligible
work remaining. On game day 575 it persisted the ancient line's next due day as
581, while tomato wine remained due on day 579. The new schedule is inactive with
zero remaining machines and 64 documented still-producing skips. This establishes
the agreed first six-day boundary for this installation; it is not an inferred
historical fill date and no feed acknowledgement was synthesized.

Live collection/refill, full-reserve shipping and a complete six-day cycle have
not yet been observed: these machines were already in cooldown. Automated tests
cover those transitions, but are not presented as a completed live production run.
