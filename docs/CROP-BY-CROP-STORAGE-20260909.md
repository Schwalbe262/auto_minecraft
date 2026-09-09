# Crop-by-crop harvest and storage

The shared harvest job now completes all registered tomato fields and their
existing warehouse deposit before starting ancient fruit or another crop.
It then completes each crop's fields and linked storage as a unit.

The wrapper reuses the existing fast harvest engine and deposit modules. It does
not count magnet-held items. A crop boundary requires the preceding deposit to
finish, its action and chest-close confirmation to settle, an empty cursor, and
no remaining inventory stack of that crop. A storage failure cannot admit the
next crop. Restarted work deposits remaining tomatoes without inventing harvest
completion or advancing the next crop's dates.

Field selection has crop-scoped cooldowns. A tomato check does not suppress the
ancient-fruit check. Profile/session/world and relevant registration edits cannot
replace an in-flight action; a date change during storage finishes that deposit
before checking tomato priority again.

Nine regressions cover full inventory, multiple fields per crop, pending
transfer/close, failure/resume, registration edits, date rollover and independent
crop cooldowns. A missing storage link does not suppress sleep when every field
of that crop has an explicit future date and no produce is held; unknown/due
dates or held produce still require usable storage. The final integrated offline
build passed **1,735 tests in 130 suites**, zero failures/errors/skips.
Expanded-field configuration is separately documented in
[the ancient-fruit extension](ANCIENT-FRUIT-FIELD-EXTENSION-20260909.md).
Live crop-order acceptance remains to be observed at its normal due date.
