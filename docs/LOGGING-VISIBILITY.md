# Logging visibility recovery (development build)

Deployment update: the candidate described below was installed during the
[2026-09-09 runtime resume](RUNTIME-RESUME-20260909.md). LOGGING remained OFF, so
its search-recovery behavior has not yet passed native chopping acceptance.
The uninstalled-build statements below describe their earlier checkpoints.

The reported terrain-search stop was reproduced as `SEARCH_LIMIT`, not a JVM
crash. No matching Auto Valley exception or crash report was found. Installed
Passable Foliage removes the player-body collision of leaves, but native outline
rays used for interaction still hit leaves. Passing through foliage does not
therefore prove that a registered stump can be clicked from that stance.

A private read-only native probe found 128 standable cells in the current
registered tree's goal envelope and no normal interaction ray to its four bases.
The old recording's higher/jumping eye position does not prove the same geometry
can be used by the current grounded approach planner.

`LoggingApproachSearch` now checks the existing four bases with a bounded
preflight: at most 64 stance checks, 16 visibility queries, and a soft 2 ms slice per
world tick. Repeated polls cannot replenish that tick's budget. It uses the same
finite interaction envelope as terrain A*, distinguishing unloaded or changed
geometry from a fully observed absence of visible stances.

When all candidates are observed and no visible stance exists, a clean logging
boundary may retain the cut/replant queues and yield through the existing
`RESOURCE_WAIT` scheduler path. Other routines and sleep may proceed. After
1,200 ticks the negative cache is discarded and visibility is checked again.
Unconfirmed actions, borrowed hotbar items, cursor/menu changes, airborne state,
unloaded or changed bases cannot authorize this yield.

This prevents a futile large terrain search from stopping the whole routine. It
does **not** make the presently occluded tree harvestable, remove leaves, ignore
native rays, or authorize other tree/terrain destruction. Actual chopping from a
valid current approach still requires native verification after deployment.

The focused standalone suite passed 135 tests, including real logging-module /
scheduler handoff, sleep, retry, preserved queues, and non-preemption of another
module's pending acknowledgement. The first combined Gradle build passed 1,314
tests across 102 suites. This change has not yet been installed in the game.

## Follow-up cost and diagnostic correction

The limit above counts visibility queries, not individual native outline rays.
One query may check several outline samples; the two-millisecond limit is checked
between queries and is not a hard render-thread deadline. No truncated query is
ever treated as proof that a tree is hidden.

Spruce/logging outline checks now reject an out-of-reach unit bounding box before
performing any native ray. This uses the closest possible surface, not endpoint
distance, so a reachable near face is not rejected because a sample is farther
away. Boolean visibility stops at the first verified hit; actual aim selection
retains the nearest-hit behavior. Both retain real OUTLINE, shape and reach checks.
The initial actual-eye check is no longer repeated outside the slice on each
stopped SEARCHING tick. Restart or fresh geometry still starts a new check.

A later saved `JUMP_UNCERTAIN` belongs to a preserves-machine approach, not the
original logging goal: ordinary one-block ascent reused a logging-labelled
controller. The label is now specific to ordinary ascent, without changing its
timeout or movement. Its last sample alone does not establish why it failed to
obtain two quiet alignment samples. This is distinct from the currently retained
inventory acknowledgement fence. Neither is a new JVM crash diagnosis.

The combined follow-up build passed **1,403 tests / 107 suites**, zero failures,
errors or skips, including the native distance/visibility and duplicate-query
regressions. The newly built artifact has not been installed; these results are
not evidence of live chopping through the presently observed canopy.
