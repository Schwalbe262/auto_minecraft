# Tomato warehouse cache — 2026-09-09

The complete, confirmed 27-slot contents of registered tomato barrels/single
chests are retained in the current session. Confirmed native opens and transfers
replace one snapshot; they do not repeatedly subtract estimated withdrawals or
extend the complete survey's age. Machine refills choose the largest remembered
grade and verify the actual source menu before withdrawing.

The default refresh interval is three game days, configurable from 1 to 28 in
tomato storage settings. Expiry creates no trip by itself: a full check joins the
next real deposit/refill. F8 pause/resume and unloaded chunks preserve the hint.
Manual storage interactions, changed registrations/block shapes, time rollback,
reconnection, restart and dimension changes invalidate relevant evidence. This is
RAM-only, not a persistent or remotely synchronized inventory database. Other
players' changes to closed storage remain unknown until the next observation.

Surplus shipping can reuse fresh stock. A carried stack enlarged by later pickup
may receive a replacement quantity allowance during the same bounded batch,
without resetting the original date, 1,200-tick expiry, warehouse identity,
capacity threshold or invalidation epoch. At most 36 replacements are allowed.
Remembered contents can guide travel while warehouse chunks are unloaded; native
OPEN/transfer authorization still requires the existing loaded-state checks and
server receipts. No dropped-item counting or magnet gate was added.

## Validation

Offline Java 17 `test build`: **1,968 tests / 147 suites**, zero failures, errors
or skipped tests. This includes 59 new cache/settings/refill/overflow regressions.
These are code-level results; this change was not yet installed or accepted in
the live game at this checkpoint. The separate logging investigation continues.
