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
The restart preflight was refused because automation was running again after its
normal pause request. No new build was installed, no game process was stopped,
and no leaf was removed at this checkpoint. A safe handoff and live logging test
are still required; the multi-hour acceptance goal remains unfulfilled.
