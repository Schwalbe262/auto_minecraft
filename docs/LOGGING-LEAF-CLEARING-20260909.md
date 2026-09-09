# Explicit logging leaf clearance

The operator authorized removing spruce leaves obstructing the registered logging
bases. Passing through foliage still does not make it transparent to native
outline rays. This change adds a separate, default-OFF permission rather than
changing collision, ignoring rays or widening ordinary block destruction.

After ordinary base visibility and other retained plots have been checked, a
bounded search may find an actual first-hit spruce leaf around an unfinished
registered 2x2 base. The permitted envelope extends one cell horizontally beyond
the base and from base height through two cells above it. Farm cells, registered
facilities, other block kinds and replanting plots are excluded. A directly
visible base takes priority over clearing.

The routine moves to the verified standing surface, not merely within four blocks
of the near leaf. It then checks current-eye base/leaf rays, registered axe and
whole-tree safety again. Each leaf is its own native mining action. Only the latest
raw server AIR reply for that exact cell in the same connection generation confirms
it. Inventory pickups, another cell's change, predictions and chop counts cannot.
The existing packet permits, abort and uncertain-action fences remain in place.

A successful leaf receipt triggers a fresh base search; it is not a chop, felling,
planting or routine-completion receipt. Each module execution permits at most 16
attempts per plot. Changed/protected candidates fall back to bounded visibility
wait instead of rediscovering the same forbidden leaf forever.

The logging list has a separate permission toggle, also available during a retained
run while automation is OFF and native/menu/cursor/loan/output state is clean. The
explicit local `logging_leaves` request accepts only an `enabled` string of `true`
or `false` and uses the same validated runtime setter. Save failure restores the
prior setting; no queue, loan, deadline or other module setting is changed.

At a verified visibility or seed-short wait, already-collected wood and berries
may also run through ordinary crafting, storage and shipping. This partial cleanup
does not discard saplings/twigs, clear remaining plots or advance the batch date.

## Validation checkpoint

Code commit: `3443616`. Offline Java 17 `test build` passed **1,909 tests across
142 suites**, with zero failures, errors or skipped tests. The artifact SHA-256 is
`DB79519DE10636FC54DE9AFD938988AA6E902909A9C8E77F1C6C350C42163B08`.

This checkpoint is code/regression validation, not native leaf-clearance acceptance.
An initial restart preflight was refused because automation was running again after
its normal pause request. After a new explicit handoff and separate permission to
discard the two ice items retained in TrashSlot, normal shutdown/restart installed
the hash above with a recoverable previous-JAR backup. No duplicate client was
launched. The original overworld profile loaded, and the new runtime setter
successfully saved the leaf permission without clearing the retained plot.

The native one-shot test found a leaf candidate and travelled toward its verified
stance, including the existing ascent path. It then returned to visibility wait:
no leaf action, axe selection or chop was dispatched. Therefore the reported
logging blockage is **not resolved by this live test**. A one-second trace cannot
distinguish endpoint revalidation failure from a changed actual-eye obstruction or
the arrival tolerance/residual motion. The operator subsequently resumed manual
play in a different dimension, so the target's current block geometry is not
available for the next direct comparison. Retained cut/replant obligations were
not erased; the multi-hour acceptance goal remains unfulfilled.

## Later native run and bounded retry correction

On returning to the farm, the operator started the installed `3443616` build
again. This approach succeeded: stored native outcomes confirmed two individual
obstructing leaves removed, 22 remaining chop strokes and four saplings planted.
The run then stopped at waste cleanup because TrashSlot retained one icicle.
After the operator handled that buffer and resumed, native outcomes confirmed
nine fire logs crafted, 11 fire logs and three remaining spruce logs stored, and
three mossberries transferred to shipping. The retained logging run, replant
queue and hotbar loan completed normally; the scheduler continued to preserves.
These observations prove that particular run, not uninterrupted multi-hour
stability or that every approach works.

The earlier failure also exposed a code-level liveness defect: rejecting one
predicted leaf stance retained a `FOUND` search and prevented inspection of other
stances until the 1,200-tick retry. The correction resumes the same finite search
after the rejected stance, preserving its cursor, query budget and unknown-cell
evidence. Changed endpoint geometry, changed actual-eye obstruction and forbidden
leaf candidates have distinct diagnostic messages. Exhausting all candidates
still waits; no reach, actual-ray, axe, leaf-boundary or server-ACK check is waived.

The user subsequently clarified that *all items already placed in TrashSlot*
are intended for disposal. That separate correction removes the retained-buffer
item gate, not the allowlist for selecting new inventory items to discard.
