# Ancient-fruit field timing and A/B edits

**Historical policy:** superseded by [strict configured cycles](STRICT-HARVEST-CYCLES-20260911.md)
at the user's request. Early ripe-crop overrides and remaining-age forecasts are no longer used.

## Diagnosed state

The saved ancient field was A `(716,72,1587)` to B `(725,72,1607)`.
The complete per-kind local diagnostic on game day 603 contained 191 ancient
plants inside that registration: 78 at age 10, 109 at age 2 and 4 at age 0.
Its next harvest date was 611. The field-selection filter rejected that future
date before scanning the registered area. This evidence does not establish
whether those 78 plants were already ripe during the previous harvest.

## Changes

- `AncientHarvestTiming` forecasts the earliest next observation from the
  remaining plants' actual age, for the exact built-in 10-stage/10-day ancient
  crop only. Age 8 remaining after harvesting another cohort means checking in
  two days, not delaying everything for ten more days. A fully replanted age-0
  field still forecasts ten days. Unknown observations check again sooner;
  the forecast is never a claim that a harvest succeeded.
- A future harvest date no longer hides a loaded, demonstrably ripe ancient
  crop. The passive check stays inside A/B, performs at most 512 cell checks
  per call, and round-robins farms so an unloaded large field cannot starve
  later fields. It does not walk to fields, load chunks, click or infer native
  success. Complete negative observations are reused for the same game day;
  incomplete observations can retry when chunks become available.
- The ordinary full-field observation, registered crop mask, hoe preparation,
  native right-click safety and server acknowledgement remain mandatory.
  Tomato harvest/deposit still finishes before ancient harvest/deposit.
  No wine interval, schedule or behavior was changed.
- A/B or crop changes invalidate only that farm's harvest calendar entry.
  Renaming preserves its date under the new name; swapping A/B without
  changing covered cells preserves it. Failed persistence rolls back both the
  farm list and calendar. A new field cannot inherit an orphaned date by name.
- Explicit custom ancient intervals and other crop definitions retain their
  existing timing policy; no user interval value is rewritten.

## Verification

Offline `test build`: **2,434 tests, zero failures/errors**. New regressions cover
mixed-age cohorts, a legacy future date with loaded mature fruit, strict A/B,
tomato-first storage, pending native acknowledgement, unloaded fields,
per-call budget and multi-field fairness, same-day cache and changed bounds,
custom intervals, and transactional A/B calendar updates.

Final installed JAR SHA-256:
`82CE26255B6B9A4BC8C0D290E24C3E30FC6474F62DF6678473F0E14B9B2EB678`.
The same modpack instance was normally paused at a clean boundary, backed up,
updated and restarted.

## Live acceptance

On September 10 at 22:34 KST, the final build reconnected to the same server
and profile. Before the ordinary continuous-mode start, game day was 605 and
the untouched saved ancient date was still 611. The complete loaded A/B field
contained 191 plants: age 10 = 78, age 4 = 109, age 2 = 4.

The normal routine admitted the observed ripe cohort without any helper editing
the due date. By 22:34:58 all 78 ripe plants were age 0, all 113 immature plants
retained their prior ages, and 156 ancient-fruit items were in inventory. It
then followed the linked storage route. The routine completed that deposit,
closed the container, and returned to normal continuous idle: no ancient fruit
remained in inventory, the cursor was empty, no action was pending, and there
were no retained failed actions. The passively sampled trace is retained locally.

The resulting next check remains day 611 because the 109 age-4 plants need six
more days from day 605; the newly harvested cohort is not used to postpone those
plants until day 615. This date is readiness forecasting, not a fabricated
server harvest acknowledgement. Wine cycles and both wine schedule structures,
and the saved A/B registrations, compared equal across the restart.
