# Shared crops, commodity stores and artisan jobs

These are development-build additions, not claims about the public 0.1.2 JAR.
The tested candidate was installed on 2026-09-09. Ancient-fruit harvest/storage,
crystal-copy, seed-maker and one nearby starfruit one-shots have been exercised.
Long-running acceptance remains open. See the [runtime checkpoint](RUNTIME-RESUME-20260909.md).

## Model and workflow

- `CropDefinition` separates item identity, allowed blocks, exact maturity
  predicates and game-day interval from `Farm` coordinates. Legacy three-argument
  farms remain tomatoes and retain their schedule keys. Tomato and ancient-fruit
  farms run the same optimized `HarvestModule` and native area-safety checks.
- `CommodityStore` names an allowed item set and a group of ordinary containers.
  It does not split storage by quality. Native stack identities and conservation
  checks still control each merge; incompatible NBT is never forcibly combined.
- `cropStores` explicitly links a crop to its destination. The harvest wrapper
  includes only those linked commodities in its one-shot cleanup. Existing
  tomato/wine storage and wine-cohort rules are preserved.
- `ArtisanRecipe` contains the exact machine, input cost, output identity/count,
  and day interval. `ArtisanJob` binds that recipe to machine positions and input /
  output groups. The current recipes are ancient-fruit seeds and jade crystals.
  Ancient-fruit wine is not implemented.
- `FruitPatch` registers exact starfruit blocks. `StarfruitModule` selects one
  ripe registered fruit within six blocks and stores it. It can run at a normal
  scheduler boundary or during an explicitly safe production/bed approach in
  continuous mode, as described below. It does not patrol every fruit.

Default intervals are ancient-fruit harvest 10 days, seeds 1 day, jade 5 days.
The jade interval comes from the installed recipe, not the tentative three-day
estimate. At execution time, all routines still verify actual maturity / working
state rather than clicking solely because a timer elapsed. The recording of
ancient fruit contained immature clicks, not a demonstrated ripe harvest.

The four additional feature switches default OFF and each supports one-shot
execution. Existing switches are not silently enabled or disabled. Inventory
ingredients are used before another source survey; a survey chooses the largest
available quality batch rather than continuously rebalancing stock levels.

## Native authority and acknowledgements

Harvest remains hoe / right-click only. Fruit collection allows empty hand or its
own fruit, never arbitrary tool/food borrowing. Artisan input must match the
registered recipe and its per-click cost. All operations remain loaded, reachable,
native-menu-owned and confined to the current feature / one-shot scope.

An ordinary artisan feed requires the latest target block receipt and the latest relevant
selected-slot server receipt after dispatch. Native identity compatibility remains
mandatory for any ingredient remainder. Same-ID jade output can arrive with input
consumption, so an explicit bounded net change plus machine state is used; hidden
consumption is not claimed as directly observed. When the last ingredient is used,
a known previous-batch output with different NBT may legitimately occupy the slot.

A separate per-ticket `ARTISAN_CYCLE_ADVANCED` proof handles a batch that has
already ripened before its reply is processed. It requires retained, same-channel,
post-dispatch native target evidence progressing from working to mature, plus the
same exact selected-slot proof. Intermediate idle, changed block/properties,
unknown values, or a return to working cannot grant this advanced proof. Initial
maturity alone and missing/evicted working evidence are insufficient. The module
also checks the current same machine is mature and the input count is compatible.
No second use is sent for that target in the current pass. Ordinary latest-working
confirmation retains its original behavior; this is not permission to ignore
contradictory newer state to grant advanced evidence.

Sent artisan attempts have a bounded target-specific RAM fence. Timeout or cancel
does not permit another send to that target in the same connection generation.
Fresh matching server evidence releases the fence; ending the connection discards
old-channel uncertainty without marking the old attempt successful. This is not a
new persistent output-debt counter and does not globally stop other routines.

A target-specific uncertainty now skips that machine within the current pass
instead of starving every later machine and job. Independent registered machines
continue, all held outputs/inputs are cleaned up, and the pass then reports its
unresolved jobs as deferred. Global action uncertainty, menus/cursor, airborne
state, a borrowed slot, or pending output still prevent that skip. The uncertain
target is never clicked again merely because a new pass or day started.

The next recipe check is anchored to the dispatch day, but saved only after native
confirmation and module verification succeed. A reply crossing midnight therefore
does not delay daily seed work by an extra day. This date is a reinspection time,
not proof that output is ready; an observed working machine still gets a one-day
recheck without another use. Checkpoint failure restores the previous schedule.

After its held items are stored and actions/menu/cursor are clean, an artisan
material or target-receipt wait explicitly allows the scheduler to sleep. The
default for other modules and navigation deferrals remains sleep-blocking; the
current clean boundary is rechecked before sleep. A one-shot never acquires
permission to run the sleep module or another feature.

Generic container access cannot override reserved legacy roles, shipping bins or
shops. Actual reciprocal double-chest halves are both checked at OPEN and transfer;
registering only the other half cannot evade a wine-cohort restriction. Generic
storage is deposit-only. Ingredient withdrawal is separately restricted to a
registered artisan job's exact input item and source group.

## Add-only bulk import

`WorkRegistrationImport` accepts only crops, farms, stores, crop/store links,
artisan jobs, fruit patches and a limited explicit enable set. It deep-copies and
validates the candidate before saving or changing the live context. Conflicting
definitions fail as a whole; identical imports are idempotent. Schedules and
unrelated flags cannot be injected through an import.

The runtime accepts only its fixed `config/autovalley/work-import.json`, with
bounded strict UTF-8 reads and no symlink target. Automation / recording must be
OFF with no unresolved start fence. The settings button and strict local
`import_work` command use the same path. This imports reviewed definitions, not
raw recordings or click replay. Profile schema 5 protects these fields from older
clients that would otherwise lose them on save.

A private candidate extracted from the user's recordings was validated against
a read-only snapshot of the real profile: it adds one ancient-fruit farm, four
storage groups, two artisan jobs and one fruit patch; enables no switches; retains
all existing registrations, flags and schedules; and passes repeat-import equality.
Only the detached candidate upgrades schema 4 to 5. The real profile is byte-
identical after this check. Candidate coordinates and recordings remain private.

A later private V3 candidate connects the seed maker's input to the same ancient
warehouse used by harvest, while retaining the seed-stock barrel as output. This
closes a supply gap in the earlier draft, which read only a separate barrel and
could not withdraw newly harvested fruit. The revised detached import is again
idempotent and preserves the actual profile. No old seed-stock fruit is moved or
consumed by this change. With a future broad commodity-storage switch, other
explicitly compatible groups can still receive that item; the scoped crop/artisan
routes are not a globally exclusive item-routing rule.

`enable: []` changes no switches, but the existing HARVEST switch is already ON.
Importing a new ancient field would make it eligible under that existing switch.
This is why actual import must remain paused and separately coordinated; an empty
enable list does not mean the new farm is disabled.

## Delayed starfruit delivery

### Optional harvest during flat travel

Continuous wine/preserves work and bed approaches may yield only their actual
MOVING stage, with no submitted/unconsumed action, container, cursor item, output
operation or borrowed logging slot. The navigator independently verifies a fresh
same-request route, loaded full-footprint flat support and normal traversal. A
stair's high tread alone is not full-floor proof. Ascents, descents, doors,
endpoint settling and search/frontier states cannot be interrupted this way.

After one fruit and its registered deposit, the engine resumes the exact original
module instance/stage before a new priority scan. A 1,200-tick side-trip cooldown
ensures progress on the original task. One-shots never run another feature.
An already approved LOGGING-OFF suspension remains valid without deleting its
unfinished plots; active logging ownership or a borrowed slot prevents a detour.
OFF, session/profile changes or ownership changes cancel the suspension normally.

Pre-use unreachable/timeout skips also have a bounded, per-target 1,200-tick RAM
cooldown, revalidated against day, clock, profile and registration. This prevents
the normal scheduler repeatedly approaching an unreachable fruit and starving
sleep. It never releases an uncertain submitted use. If a detour's deposit fails,
ordinary safe travel can resume, but a suspended sleep approach returns through
the existing blocked/deferred scheduler rules instead of forcing sleep.

The combined source passed **1,527 tests / 111 suites**, with no failures, errors
or skips. A later native sleep-approach detour confirmed a fruit use, one-item
deposit, resumption of the original sleep task and the following day's work.
See the [runtime checkpoint](RUNTIME-RESUME-20260909.md). Native production-travel
detours still need a separate live exercise; the engine/module regressions cover
those permitted stages.

### Late pickup cleanup

The one-second pickup observation remains bounded. After a successfully confirmed
fruit use and its actual age reset, a later safe boundary may store fruit that is
now really in inventory, even if the player has since left the tree. The fruit is
not clicked again. Only the same profile, fruit-patch definition and exact stored
commodity definition can reuse that destination; OFF, manual menus, unrelated
pending actions, or changed registrations do not authorize a cleanup action.
The remembered destination is RAM-only, not an expected count or a pickup debt.
It survives scheduler/one-shot reset but is discarded for a different profile.
This does not make a finished one-shot start itself again; another active scheduler
boundary or explicit run is still necessary.

## Native acceptance still required

### Clean waits in an interrupted wine batch

The initial wine-rack preflight may find a still-unconfirmed member already
working after a restart or another actor's changes. Its source is unknown; a
working block is not a refill acknowledgement. Keep the exact remaining members
and original batch dates, do not visit an early ready subset, and defer until
all remaining members are ready for a normal, newly verified service pass.

At a clean START boundary this observation wait permits other routines. It may
grant the existing deferred-sleep permission only with a closed ordinary menu,
empty cursor, grounded connected player, no pending action/output/borrowed slot
or native action fence, and no carried tomato/wine/preserves awaiting cleanup.
This is a grant-time check under the existing scheduler contract, not a claim
that the inventory stays frozen during the wait. Normal module priorities and
the scheduler's sleep gates still apply. The existing bounded retry backoff is
used; neither calendar dates nor native receipts are fabricated to unblock work.
Mid-run unexpected-machine and uncertain-interaction failures remain unchanged.
One-shot mode stays WAITING during this condition; it must not report completion.

### Remaining runtime checks

After an authorized same-instance restart, import the reviewed definitions with
automation OFF. Check each one-shot for actual target acknowledgement, inventory
receipt and correct destination, then enable continuous routines as desired.
Verify a mature ancient-fruit harvest, seed collect/refill, jade collect/refill,
nearby starfruit collection and a safe handoff to bedtime. Test separate-day
eligibility without clearing existing schedules or replaying unconfirmed clicks.
