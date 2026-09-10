# Retained logging retries and facility visibility

## Observed failure

The live stop was a storage approach failure after all tree and planting
obligations had finished, not another unacknowledged chop. The profile still
had an active cleanup batch, empty remaining/replanting lists, and no borrowed
hotbar item. The scheduler treated every ordinary non-busy logging failure as
an exclusive unfinished operation and paused all automation.

The underlying descent rejection occurred immediately at an upper-cell radius
of approximately 0.45336, just outside the unchanged 0.45 preparation limit.
It was not a long descent timeout.

## Recovery boundaries

- Unsent storage/crafting/tree/leaf/planting approaches and unloaded or changed
  read-only geometry can enter a retained local wait. Only that job is deferred;
  continuous scheduling proceeds with other enabled jobs and retries after
  1,200 client ticks (about one minute at 20 TPS).
- Missing usable axes, missing crafting/storage registrations, and unsettled
  preflight observations use the same clean-boundary retry. A wrong tool is
  never substituted and no cut, trash, restoration, or craft is acknowledged.
- Full/incompatible logging storage closes through its existing owned-menu
  action and a real successful close response before yielding. A failed or
  still-pending close cannot become a wait grant. Other deposit modules retain
  their existing behavior; logging explicitly opts into availability waits.
- The retained snapshot includes the original profile, stage, plot definitions,
  remaining/replanting obligations, logging deadline and relevant facilities.
  A retry rebuilds an unsent approach from current position without clearing
  obligations, advancing the completion date, or resetting another job.
- Busy actions, native failure fences, borrowed hotbar custody, occupied cursors,
  open/manual menus, airborne states, output debt and changed registrations still
  reject a grant. Sent chop/plant/craft/transfer/swap failures remain blocked.
  No native acknowledgement, generation, clock or item count is fabricated.
  Uncertain jumps and unconsumed navigator door tickets cannot enter this retry.
  SAFETY/INVALID_START additionally require a read-only current native ground
  proof; their failure label alone never authorizes a reset.
- Local navigation/availability retries do not claim that all work is done for
  sleeping. Previously verified sapling/visibility resource waits explicitly opt
  into their existing sleep behavior. F8 OFF and feature OFF remain authoritative.

## Descent correction

Terrain navigation now uses the existing bounded ground-recentering controller
when the player is on the exact initial upper cell but outside the preparation
radius. Native full-flat support and swept-body/hazard proofs are required.
Quiet centering discards the old route and rechecks the descent on the next tick.
The 0.45 bound, safe drop, landing, hazard and traversal rules were not relaxed.
Waypoint-only and harvest-lookahead movement do not gain this permission.

## Ancient wine facilities

`Ctrl+F8 → 저장 위치` now includes each custom wine line as one named machine-count
row, ahead of ordinary storage entries. Selecting the ancient-fruit row opens
that exact line's details and its existing expansion preview/selection workflow.
The row does not enter the legacy tomato POI editor or deletion path. Stable ID
and profile/snapshot checks prevent editing a different line after reordering.
Neither displaying nor expanding this row resets either wine schedule.

## Verification

Regression coverage includes 480 consecutive actual LoggingModule/engine retry
cycles, more than eight simulated hours at 20 TPS, with ordinary neighbours still
scheduled, no repeated native mutation, no premature sleep and successful recovery
once the route becomes available. This is a deterministic simulation, not a claim
that eight real unattended hours have already elapsed.

Additional tests retain native-failure/lease/cursor barriers, check real close
ownership before full-storage waits, exercise wrong-axe recovery, and reproduce
the slightly off-centre descent origin with thirteen focused navigation tests. Five
facility-list tests cover grouped visibility, ID selection, stale rows, disabled
definitions, expansion isolation and unchanged calendars.

Final offline `test build` passed: **2,227 tests in 167 suites**, with zero
failures, errors or skipped tests. The existing no-hotbar test now expects the
explicit deferred state and additionally checks unchanged inventory and dates;
its original no-borrow/no-use assertions remain intact.

The same Society instance was normally closed and restarted, and the installed
JAR matches SHA-256
`C55176C14709091EFFAFD3E40BA3D349DA99158527AF67BE50EE35DEB120C26F`.
The preceding profile and diagnostics were backed up privately; the installer
also retained the previous JAR. The game reconnected to the same server and
the normal start request confirmed continuous mode at 07:31:22 UTC. A passive,
bounded client-thread watcher then observed a real `Entered bed` success,
`sleeping=true`, a natural day transition from 578 to 579, and the next day's
HarvestAndStorageModule becoming active. No gameplay clock or due date was
manually changed to trigger this test.

Farm, POI, commodity-store, artisan-job, custom-wine and logging-plot definitions
match the private pre-restart backup. Tomato wine remains due on day 579, ancient
wine on day 581; the ancient line still has 64 machines and eight output barrels.
The newly loaded diagnostic `failureHistory` field was present and empty before
resumption. The original crystal error was not recreated by filling the user's
hotbar, changing machine states or resetting cooldowns. Its capacity branches
were verified by six added deterministic artisan tests, while eight failure-
history tests cover bounded retention and unchanged scheduling/action behavior.

The passive window finished with 180 one-second samples, including 163 samples
after continuous mode started. Inventory tomatoes rose from zero to 1,534 and
the final status was tomato storage 27/32, still running. No sampled retained
native failure or late-action fence appeared. This does **not** mean there were
no transient errors: the new history recorded one HARVEST stair landing/stop
timeout at client tick 4409. Automation subsequently continued into storage
without a manual restart. That timeout remains a navigation limitation; this
short test is not evidence of an error-free eight-hour run or a new completed
crystal production cycle.

## Crystal copier investigation

The reported crystal error was already replaced by a manual-pause status when
inspected. The ten registered crystal machines remain configured. No retained
native action failure was present in the subsequent passive snapshot, so the
original error must not be attributed to the old logging navigation failure.

A concrete independent preflight defect was found: all nine live hotbar slots
contained non-artisan items, while the main inventory had room. The mature
crystal bootstrap requires an empty hand; its no-slot branch threw a generic
IllegalStateException instead of describing an unavailable working slot.
This is a verified code defect and matches the observed inventory, not proof of
the erased original message. Slot shortages now defer only that artisan job at
a verified no-action boundary; tools, food and other hotbar contents are not
discarded or borrowed. Native receipt failures are not reclassified.

A bounded diagnostic failure history retains repeated-error evidence across
manual pause/start within the same connection, so later status messages do not
erase the original cause. It does not change action acknowledgements or retry
permissions.
