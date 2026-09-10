# Wine region expansion edit gate — 2026-09-10

The ancient-fruit wine screen disabled all edit controls when any unfinished logging batch existed. That was an unrelated configuration dependency: changing a paused wine region does not move a borrowed logging item or complete logging work.

## Fix

- Wine-only settings and custom-line expansion no longer require logging completion. Retained logging work and temporary-slot records are preserved.
- Automation/recording must still be stopped. Profile identity, persistence, emergency-stop processing, connection, normal inventory, empty cursor, native in-flight/uncertain actions and pending machine-output checks still gate editing.
- The expansion screen rechecks that boundary before scanning or confirming a wine-line change, not only when opened. Existing selected-subset, membership, ownership and geometry validation remains intact.
- Buttons update every screen tick when a temporary restriction clears. A blocked edit displays its reason, with a tooltip for the full text.
- Diagnostics expose `wineLineEditRejection`; null means the local edit boundary is clear. No query resolves an old action or starts gameplay.

## Verification and deployment

Offline `test build`: **2,301 tests / 173 suites / 0 failures or errors**. Four new tests cover the ten boundary conditions, pending-output rejection, and wine expansion planning while a PARKED logging lease and unfinished planting remain unchanged.

Installed artifact SHA-256:

`25AB51DA18BECFC7C8895C52FDF8C71475C70732B8EFE8F7FDEF997D415BAC21`

The same Society instance was normally restarted with a recoverable mod backup. At 17:32 KST, the connected client had no wine edit rejection. Opening its actual `ancient_wine` settings screen confirmed **the expansion button is active**, with 64 existing machines. Automation remained OFF; the logging record was unchanged. No candidates were selected and no facility changes were saved.

The screen is left open for the user. Select **expand this wine region → nearby candidates → preview → confirm** to add the desired machines. Existing production dates are not reset, and an already active pass retains its existing membership until the next pass.
