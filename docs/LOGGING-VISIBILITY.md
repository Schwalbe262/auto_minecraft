# Logging visibility recovery (development build)

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
preflight: at most 64 stance checks, 16 native rays, and a soft 2 ms slice per
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
