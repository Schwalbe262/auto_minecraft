# Wine production rack expansion: recognition versus registration

In response to the user's question, a read-only native diagnostic at
**2026-09-09 08:02:23 UTC** scanned around `(659,66,1557)` with horizontal radius
32 and vertical radius 16. It returned **445 wine-keg candidates**, without
per-kind truncation. All **384 registered** wine-keg positions were present,
with **61 additional unregistered** positions:

| Area | Coordinates | Unregistered kegs |
| --- | --- | --- |
| Existing underground rack bounds | X=651 or 654; Y=66..69; Z=1532..1537 | 48 |
| Separate upper location | X=660; Y=72..73; Z=1576..1581 | 12 |
| Separate single position | X=679; Y=72; Z=1582 | 1 |

The observation establishes existing blocks and registration differences, not
when every block was built or whether all 61 belong to this requested expansion.
No registration, production action, item movement or schedule change was made.

`MachineModule.prepareWineRun` builds an inactive new batch from registered
`WINE_KEG` POIs only. An already active batch uses its saved remaining members.
Nearby discovery does not automatically append registrations. `MachineGroup`
is presentation metadata, not a spatial auto-expansion policy. Explicit nearby
bulk registration can add confirmed candidates; registration alone does not
reset the global six-day clock or silently alter an active batch's membership.
