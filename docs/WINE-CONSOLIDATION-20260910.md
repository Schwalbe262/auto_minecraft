# Wine output consolidation interruption, 2026-09-10 KST

The installed client stopped after the 289th of 384 wine machines, not while
logging. Ticket 1204 had a retained successful `WINE_FULL_FEED` receipt for three
tomatoes. Ticket 1205 was the subsequent optional output-inventory consolidation.
It entered the same-ticket recovery wait and timed out approximately 35 seconds
after dispatch. Both the consolidation failure and late inventory reply remained
retained. The wine batch still had 95 registered machines outstanding.

This is not evidence that no wine was made: preceding feed and consolidation
receipts were successful. Nor does the timeout prove that the inventory click
failed on the server. Its retained phase and authoritative slot deltas must be
inspected before any recovery. No old click is to be blindly replayed or declared
successful.

The earlier logging continuation artifact had not yet been installed. It does
not address this separate inventory transaction failure.

## Retained native evidence

The read-only inspection found ticket 1205 in `RESTORE`, with two acknowledged
primitives and the inverse SWAP in flight. Its original wine source was inventory
slot 9 and its borrowed hotbar slot was 6. After the merge, a newly received wine
had occupied the borrowed slot and was included in the verified pre-RESTORE
baseline. The source held the borrowed 13 torches.

The actual post-dispatch FULL reply changed only those two slots: the 13 exact
torches returned to hotbar 6, and one wine moved to inventory slot 9. The strict
comparison still returned WAIT. Cross-position wine metadata must be verified
separately; same-position item differences in the report alone cannot prove a
normal metadata update.

The subsequent cross-position check confirmed that exact cause. The original
scratch wine and final source wine had equal counts and stack limits, and
`NativeWineMetadata.passiveChange` reproduced the final stack exactly using the
installed mod's metadata operation on a detached copy. Transaction/current wine
years matched. The returned torch stack was exact, and every other menu slot was
unchanged. The read-only check did not acknowledge or mutate the live transaction.

The fix permits only this actual FULL-reply, final inverse-SWAP case after both
earlier primitives were acknowledged. It normalizes the independently proven
moved-wine metadata in a temporary comparison, requires exact borrowed-item
restoration and unchanged other slots, then accepts the real packet snapshot.
It does not loosen arbitrary tags, counts, outward swaps, connection boundaries,
cursor checks, or uncertain click replay.

A proposed blanket ban on occupied-hotbar output compaction was rejected before
deployment: the existing 384-machine regression still fed every machine but
collected only 18 of 384 bottles. Its expected full-collection assertion was not
weakened, and the proposed MachineModule change was reverted.

## Validation

The complete offline `test build` passed 2,001 tests across 149 suites, with zero
failures, errors or skipped tests. This includes 14 new focused regression tests
and the unchanged 384-machine full-output collection regression. Independent
read-only review found no blocker. Artifact SHA-256:
`CCBC9C9B5F414691A8E2DF7A6FCE2702A70FAC51574C35C2AA58503BCD0A3BC6`.

These tests and the retained real-packet diagnosis establish the specific fix;
they do not establish multi-hour unattended stability. Native restart/resume
verification is recorded separately below when completed.
