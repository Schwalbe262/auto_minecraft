# Logging blocked-plot scheduling

The reported stop was reproduced in a one-shot logging run. No new native
failure fence or in-flight action was present. The first retained tree had no
visible grounded grid stance; the module repeatedly waited on that same tree.
The earlier visibility recovery prevented a futile navigation search, but did
not make this tree harvestable or try the other retained trees first.

A private, read-only probe independently searched all five retained plots.
Only the first returned `NO_VISIBLE_STANCE`; the other four returned `FOUND`.
The first exhausted 1,476 stance candidates. These are current native visibility
observations, not proof of a connected route, a safe whole-tree footprint, or a
successful chop. No leaves or other terrain were removed by the probes.

The module now keeps temporary negative visibility observations for one sweep
and tries the other unprocessed registered plots before waiting. Saved remaining
and replant queues are not reordered or completed by a visibility result.
Oldest-first 2x2 replanting still takes priority when enough saplings exist.

When all remaining trees are hidden, the existing resource-wait scheduler retains
a real blocked-tree anchor. It can yield to other enabled routines in continuous
mode; one-shot mode remains isolated. A 1,200-tick retry starts a fresh sweep.
Actual disappearance of all stumps invalidates the sweep because felling can
expose a neighbouring tree. A selected tool, visible stance or partial chop ACK
does not count as a felled tree. Native reach, outline, axe, complete-tree proof,
inventory/cursor and unresolved-action checks are retained.

Initial validation: the focused logging suite passed 89 tests. The combined offline
Java 17 test/build passed 1,809 tests across 136 suites, with no failures or
errors. This includes all-hidden bounded sweeps, a hidden first tree with four
processable neighbours, oldest seed-short replanting, one-shot isolation,
whole-tree rejection, timed retry and non-current cached-chunk unloading.
The build was installed with a recoverable previous-JAR backup and a normal
single-client restart. The resumed native one-shot selected a different tree,
confirming that the original first-tree scheduling block was passed. No chop was
sent: terrain navigation then rejected the player's starting grid cell.

A second read-only native probe found the player grounded and collision-free,
but supported only by the edge of an adjacent stone floor. The block below the
player's centre was air. Neither the raw grid cell nor the cell above was a valid
navigation stance. This was a distinct start-position recovery issue. Its later
correction verifies existing foot overlap with a full, same-height support and
the collision-free swept body before ordinary recentering input; no grid-cell
substitution, teleport or terrain clearing bypasses the proof.

## Subsequent native progress and borrowed-slot restoration

With recentering installed, a subsequent native run completed two tree fellings
and eight sapling plantings. The remaining queue decreased from five plots to
three, with no replanting plots left pending. It then reached a hidden tree again
and paused because a borrowed hotbar slot was still `PARKED`. The visibility-wait
boundary correctly refused to yield while that original item needed restoration.
This was not completion of the remaining logging routine.

Before skipping or waiting on a hidden plot, the module now restores a verified
`PARKED` loan through the existing normal path: exact item/fingerprint and clean
native boundary, inventory quiet check, saved `RESTORING` state, one hotbar swap,
then server acknowledgement and exact restored-item verification. Only then is
the lease cleared and execution returned to `PLOT`, not waste disposal or crafting.
Final-cleanup restoration is unchanged. Unconfirmed or failed swaps retain the
restoration obligation and are not replayed.

The updated focused logging suite passed 92 tests, including restore-before-skip,
pending/failed acknowledgement preservation and unsafe-boundary rejection. The
combined offline Java 17 test/build passed 1,828 tests across 138 suites, with no
failures, errors or skips. The restoration build was installed with a recoverable
backup and a normal single-client restart. The next native run confirmed the
inverse hotbar swap and cleared the lease. Twelve subsequent chop actions had
stored successful server acknowledgements before a stale approach caused a
separate stop. No native request or failure fence remained unresolved.

An explicit safe resume then completed another two trees and eight plantings.
Across these runs, four of the original five remaining trees were felled and
replanted; one hidden plot remains, with no replant or borrowed-slot obligation.
The module stayed running in isolated one-shot resource wait rather than marking
that last plot complete. Whole-routine crafting, storage and shipment are not
claimed complete by these observations.

## Outline changes during partial felling

Installed TreeChop changes its block-entity radius as successful chop count
increases. The native outline therefore shrinks without necessarily changing
the `BlockData` projection used by the approach cache. A previously visible
endpoint may legitimately become stale after a confirmed partial chop. Neither
camera yaw nor the cached endpoint is permission to bypass the current native
outline and actual-eye checks.

A stale `FOUND` endpoint now stops movement, resets navigation and starts a fresh
bounded search for the same retained plot. At most two consecutive stale replans
are allowed without confirmed chop progress. A positive native chop receipt
renews this local budget; selecting an axe or finding a theoretical stance does
not. A parked item uses the normal restore-before-plot path first. Repeatedly
unstable geometry, unresolved native actions and unsafe boundaries still retain
the unfinished work and stop. A fresh negative visibility search uses the normal
other-plot/resource-wait path; it is never fabricated from retry exhaustion.

The focused logging suite passed 97 tests, including 12 confirmed partial chops
followed by a changed outline and eventual 24-chop completion, finite oscillating
endpoint rejection, progress-only retry renewal, borrowed-slot restoration and
unsafe-boundary rejection. This is detached regression coverage, not a claim of
native completion for the remaining hidden tree.

The final combined offline Java 17 test/build passed 1,833 tests across 138 suites
with zero failures, errors or skips.

## Continuous-mode acceptance

After the final normal restart, the original profile loaded with the one retained
hidden plot and no replant or borrowed-slot obligation. Continuous mode started
through the normal control interface. Native observations then showed logging
yielding to `HARVEST`, ordinary movement to the crop field, a selected hoe, and
tomatoes increasing from zero to 276 across grades. No native failure fence was
present in that sample. The retained plot was not deleted or marked complete to
obtain that progress. This is a short native integration check, not a multi-hour
stability claim or completion of logging's final crafting/storage/shipment.
