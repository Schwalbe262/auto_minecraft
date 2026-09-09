# Recovery from an already-supported block edge

Native logging acceptance exposed a second issue after the blocked-tree sweep
was fixed. The player was grounded and collision-free, with one edge of the body
overlapping an adjacent stone floor. The block below the body's centre was air;
the existing raw/above grid-cell resolver could not provide a valid A* start.

Normal terrain requests now have one bounded recenter attempt before searching
from such an unsupported grid cell. This does not substitute a neighbouring cell
for the player's actual location. The player first walks inward using ordinary
reduced movement input, then must be observed quietly on the real valid cell
before the original path search begins.

The native opt-in proof requires a full-footprint floor that the actual body
already overlaps, the same physical height, matching default/player collision
shapes, normal player physics and dimensions, and a target centre within 1.25
blocks. The whole enclosing body sweep must be loaded, inside the world border,
hazard-free and free of native block/entity collisions. Moving toward the centre
of the already-overlapped full rectangle preserves support continuously. A gap,
another height, an unknown adapter or a closed door is not covered by this proof.

The controller requires initial and final quiet observations, rechecks current
support on each tick, uses neither sprint/sneak/jump nor interaction actions, and
has a 60-tick deadline and a shorter no-progress limit. Reset, a changed context,
airborne state, cursor/menu changes or native action uncertainty stop its input.
Harvest lookahead and ongoing jump/descent controllers cannot use this recovery.

Validation includes seven pure geometry tests and nine controller/navigation
tests. The controller tests use detached response models, not a live Minecraft
physics simulator. Native acceptance remains a separate deployment check.
The combined offline Java 17 build passed 1,825 tests across 138 suites, with
zero failures, errors or skips, including a rerun after the final test edit.
