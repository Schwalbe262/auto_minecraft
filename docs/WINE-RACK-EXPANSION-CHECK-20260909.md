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

## User-confirmed target and current self-service workflow

The user subsequently confirmed that the underground 48 are the intended
extension and the other 13 are unrelated. The read-only check did not register
any of them.

For the currently unnamed rack, aim at a new underground keg, open Ctrl+F8,
select **시설 등록 → 조준한 블록 선택 → 주변 같은 설비 묶음 등록…**.
Review the preview's **새로 등록할 설비** count and coordinate bounds before
saving. Existing registered kegs can be included as group members, so the save
button's total group count is not necessarily 48. Unrelated locations must not
be accepted merely because they share the same block type. Nearby scan coverage
is bounded; a partial-scan warning does not establish a complete extension.

Current limitation: once a named group exists, this screen does not yet offer
rescan/append. A scan containing an already named group is rejected, not silently
merged. The existing fallback is individual new-keg registration; deleting an
old group is not required or recommended. Automatic future expansion is not
implemented by this stability patch.
