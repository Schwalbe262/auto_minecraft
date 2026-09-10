# Strict configured crop cycles

This supersedes the early-readiness and remaining-growth policy documented in
`ANCIENT-FRUIT-HARVEST-TIMING-20260910.md`, at the user's explicit request.

## Behavior

- Remove `LoadedAncientHarvestProbe` and `AncientHarvestTiming`. A ripe crop
  never overrides a future field date, including a one-shot harvest request.
- Use each crop's configured interval: tomatoes use `harvestCycleDays` and
  ancient fruit uses its crop definition's cycle. Both a harvested pass and a
  complete but unripe/empty inspection wait the full configured interval.
- Reserve/checkpoint the next full cycle after safety validation but before
  the first click is dispatched. This is a cycle-attempt reservation, never a
  successful-harvest receipt; the native acknowledgement is still required.
  The already admitted pass can finish its full A/B
  sweep; stopping and restarting cannot admit a new early pass. If the user
  stops partway through (including between dispatch and acknowledgement),
  remaining crops wait until the next scheduled cycle. An unconfirmed or failed
  attempt is not reported as harvested and cannot start an early new cycle.
- A/B edits, crop edits and renaming cannot erase or advance a saved date.
  Renaming merges to the later existing date. Save failure still restores the
  entire farm/calendar transaction.
- A native area effect cannot harvest a separate not-yet-due registered field.
  Current-pass permission does not apply to another field's calendar.

## Legacy transition

The old ancient-fruit date may be a growth forecast, not ten days after an
actual harvest. No saved value reliably reconstructs that historical harvest
day. On the next normal harvest entry, legacy profiles therefore receive one
conservative transition wait: each already scheduled default 10-day ancient
field gets `max(existing date, current day + 10)`. This is explicitly a policy
transition, not an invented harvest receipt. Tomato and wine dates and explicit
custom ancient intervals are preserved.

The persistent `strictHarvestTimingVersion` prevents another wait being added
on every restart. Old files are detected without rewriting during read-only
load. Fresh profiles start with the strict marker. Transition persistence
failure restores both the marker and the dates. Normal integer validation of
all existing settings is preserved.

## Verification

Regression coverage includes ripe-but-future fields, exact due-day admission,
mixed growth stages, full-interval empty inspections, partial-pass OFF/restart,
tomato-first storage, actual native ACK gating, cross-field area effects,
transactional registration updates, one-time transition/save/reload and
unchanged unrelated schedules.

Final offline `test build`: **2,439 tests, zero failures/errors/skips**.
Install compatibility validation also passed. JAR SHA-256:
`629D6BE24B9BA2A14C28FDA89020B885F21475D7EE92662AE5E584EF0201FFB6`.

## Deployment status

**Built and validated, not yet installed/restarted.** The normal maintenance
preflight refused because the user was no longer in the registered overworld
profile. Read-only diagnostics/logs confirmed manual play in `society:skull_cavern`
with automation OFF. No game was closed, no profile guard was bypassed, and no
automation was started. Restart/install awaits direction so the user's active
dungeon play is not interrupted. Consequently, no live strict-cycle acceptance
or legacy-calendar transition is claimed yet; those are verified by tests.
