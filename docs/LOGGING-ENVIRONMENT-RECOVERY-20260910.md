# Logging environmental recovery — 2026-09-10

## Reproduced cause

The live client stopped with an unfinished logging batch after a registered planting cell contained an unexpected block. A later read showed that obstruction had already disappeared, but the module retained a sticky failure and never retried. A normal resume reproduced the failure: one-layer `minecraft:snow` had accumulated on three planting cells. This second failure also held a verified PARKED original sword; no native action or late acknowledgment was pending.

## Change

- Observed planting obstructions have a typed environmental result, separate from native action, custody and persistence failures. The diagnostic includes the block ID and coordinates.
- Active logging records and unfinished 2x2 planting remain intact. At a clean boundary, logging yields an existing typed resource wait and rechecks after 1,200 client ticks (about one minute at 20 TPS).
- A verified parked item is restored through the existing checkpoint/inverse-swap/server-ACK path before yielding. Other consumers cannot run during that swap; uncertain or failed restoration is not replayed or declared successful.
- Other enabled routines and sleep can run during a safe environmental wait. A terrain change during a material wait is reclassified by the normal logging tick rather than turned into a sticky failure.
- Pre-run environmental deferrals explicitly permit sleep at a clean boundary; they do not start or complete a logging batch.
- Snow and unknown blocks are not destroyed. No new mining, placement, packet or recipe behavior is introduced.

The scheduler's existing resource-wait contract is reused; arbitrary BLOCKED messages are not reinterpreted as permission to continue. Native uncertainty, changed protected inventory, invalid registration/checkpoints and persistence failures remain guarded. Manual OFF stays OFF.

## Verification

Offline `test build` passed **2,314 tests / 174 suites / 0 failures, errors or skips**. Added actual-module scheduler tests cover persistent and disappearing obstructions, other work/sleep, pre-run sleep/backoff, material-wait terrain changes, one-shot/manual OFF, disabled logging, busy other jobs and known PARKED restoration with long pending, failed and successful ACK paths.

Artifact SHA-256:

`802B4FDE0EC56E3C78F5AA9C38822D6F8B92628FFF6EA1592E6E8A0ED0908ADE`

The profile and previous mod were backed up locally before a normal same-instance restart. Live observations and their limits are recorded below after verification; automated test results are not a claim of overnight live stability.

## Live reproduction after installation

At 20:13 KST, the same three snow-covered planting cells were still present. A normal start restored the original sword from inventory slot 11 to hotbar slot 0, with a server-confirmed swap. The PARKED obligation cleared only after that normal restoration; the remaining logging/planting records stayed active.

Within seconds, continuous automation advanced to tomato harvesting while those snow cells remained unchanged. No terrain edits, forced production dates, fake acknowledgments, cleared obligations or manual inventory swaps were used to produce this result.

The subsequent live sequence included harvesting/storage, shipping, crystal-copier processing, wine/orchard checks and sleep. At approximately 20:15 KST the player slept; the observed game day then advanced from 593 to 594 and automation continued into the next day's preserves work. The saved logging remaining/replanting lists and logging due day were unchanged during the environmental deferral. This verifies that the retained snow obstruction did not block other work or sleep across that day boundary.

The observed continuous run lasted 354 seconds (20:13:04–20:18:59 KST), with no non-manual PAUSED/ERROR samples or retained native failures. The preserves pass reached 141/144 at 20:18:19 and transitioned to tomato storage at 20:18:22, then preserves shipping at 20:18:36 and crystal-copier work at 20:18:44. The same three snow cells remained throughout.

At 20:18:56 the scheduler was running in WAITING with no active module and displayed the logging resource-wait message. At 20:18:57 it ran WineRoutineModule, then returned to WAITING at 20:18:58. The message is an idle scheduling status, not a global pause or a lock holding the next routine. At 20:18:59 direct input caused the expected manual pause; automation was not restarted over the operator. A fresh read at 20:23:28 showed automation OFF in recording preparation, with a settings-screen pause in its recent history, not another logging obstruction failure.
