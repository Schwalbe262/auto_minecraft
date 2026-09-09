# Tomato reserve and carried-surplus shipping

Implemented on 2026-09-09 after the live client repeatedly opened tomato storage without transferring inventory. The old implementation had wine surplus shipping but **no tomato reserve-percentage shipping path**. The old `Registered destination storage is full` status was a capacity check, not rejection of mixed tomato grades. Initial empty menus before their server OPEN acknowledgement were not evidence of empty warehouses.

## Behavior

- Default: surplus shipping enabled, reserve threshold **90%**. Settings accept **1–100%**, with 80% and 90% presets at **Ctrl+F8 → Modules → Tomato storage / shipping**.
- Survey all registered tomato stores once per haul, using acknowledged container opens and the existing connected-component visit order. Grade counts are retained but grade labels do not restrict storage.
- Below the threshold, deposit into compatible stacks using ordinary server-confirmed quick-move. Try any carried tomato grade that fits the current container, not only the first grade in inventory.
- Whole stacks are retained: the last reserve deposit may exceed the chosen threshold by at most 63 tomatoes. No cursor splitting or warehouse repacking is introduced.
- Once the reserve threshold is met, send only **carried tomatoes** to a registered shipping bin. Never withdraw warehouse reserves to sell them.
- A short-lived RAM-only shipment allowance is consumed solely by positively acknowledged native transfer quantities. It is invalidated by changed configuration, registration, connection/session, date, unloading, expiry, or reset. No persistent sale permission, fabricated acknowledgement, or magnet tracking is added.
- Current capacity verification supports ordinary 27-slot barrels and single chests. It intentionally does not double-count two halves of a double chest, accept foreign contents, or infer contents of closed/unloaded containers.
- If surplus shipping is disabled, legacy deposit behavior remains. The main SHIPPING toggle is also respected; HARVEST/TOMATO_STORAGE one-shots may perform this explicitly enabled dependent shipment.
- Shipping-bin insertion is not proof that money has already been paid. Server settlement occurs later.

The reserve survey is a recent sequence of server observations (at most 1,200 client ticks), not a simultaneous lock against another player withdrawing warehouse stock.

## Verification and live result

Full offline `test build`: **1,765 tests / 133 suites / zero failures or errors**. Tests cover full 32-barrel storage, total capacity across stores, 80/90% thresholds, reserve retention, whole-stack crossing, all grades, foreign contents, full shipping bins, disabled shipping, dependent one-shot scope, stale/changed allowance rejection and pending OPEN non-repetition.

Installed artifact SHA256: `9DF0A8462122B67847D8E0305B602BE94D284AFFD128FDB4D33358D81C6CD50B`.

The existing Society client was normally restarted at approximately **08:59:58 UTC / 17:59:58 KST**, preserving its existing instance and server identity. The replacement client connected successfully; the prepared DNS fallback helper was **not invoked**.

The TOMATO_STORAGE one-shot started at **09:03:26 UTC**. It acknowledged 32 warehouse opens and closes (tickets 1–64), then reached its verified 90% reserve branch. Shipping OPEN was ticket 65. Tickets **66–91** were 26 successful native transfers totaling **1,513 tomatoes**. Ticket 92 closed the shipping container. By **09:03:58 UTC**, the one-shot reported completion, carried tomato count was zero, and no action failure fence was present. Existing warehouse tomatoes were not withdrawn by this one-shot. This proves the configured threshold was reached; it is not a claim that all 32 barrels were individually 100% full.

Continuous mode was restarted after that successful one-shot. At 09:04:59 UTC it had fetched 768 tomatoes and was returning to the preserves machines. Long-running completion of every production batch is a separate observation, not implied by the successful shipment.

Follow-up at **09:08:06 UTC**: all **144 preserves jars** were working with future deadlines, all 144 old products had been collected, and carried preserves were zero. The remaining 48 ingredient tomatoes were shipped in tickets 404–415. Shipping tickets **418–420** then confirmed **64 + 64 + 16 = 144 preserves** transferred into the bin, followed by CLOSE 421. There was no active output obligation or failure fence. The continuous scheduler then advanced to the seed-maker job. Wine was waiting for all unfinished rack members to be ready, as required by its whole-rack timing; its next live full-feed proof was not tested by this preserves run.
