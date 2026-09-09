# Exact wine collect-and-refill confirmation

The live mature-keg path previously finished its native interaction on a generic block-or-inventory change. The production module then required the *whole inventory's same-grade tomato total* to decrease by exactly three. Concurrent pickups elsewhere could mask that decrease, causing a real interaction to be deferred instead of allowing the production batch to progress. The historical ambiguous interaction was not retrospectively marked successful.

The installed keg script harvests mature output first and then inserts the next input batch. A mature keg therefore needs exactly three tomatoes; the existing idle-keg partial-input exception does not apply to mature kegs.

`WINE_FULL_FEED` now requires a dispatch-local, same-generation participating-slot server receipt proving exactly three inputs consumed, together with the same target keg's latest server state changing to `working=true`, `mature=false`. Native item identity, unchanged non-production block properties, chronology and contradiction checks remain in place. No aggregate inventory delta or unrelated pickup can substitute for this evidence.

The production module accepts that exact proof only for its own current WINE use/target, then rechecks the current target state. New uses and resets clear the proof. Idle one/two-input completion remains separately guarded. Mature one/two-input consumption, generic success messages, wrong/stale slots, changed item identity and a different target are not full-feed proof.

Seven new regression tests cover native and module evidence, including extra tomatoes arriving elsewhere in inventory. Included in the 1,765-test successful integrated build described in `TOMATO-SURPLUS-SHIPPING-20260909.md`. Existing batch remaining targets and six-day schedules were preserved; no historical acknowledgement or schedule was rewritten.
