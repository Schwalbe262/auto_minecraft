# Grounded recovery after interrupted stair descent

## Observed incident

The user reported that another player hit them and automation stopped. The private runtime trace confirms a descent landing followed by displacement onto a half-height tread. It does not independently identify the attacker or prove the source of that displacement. Health remained at 20 in the incident samples; there was no pending native action or inventory failure fence.

The engine remained running but waiting: `interruptedDescent` repeatedly rejected the actual grounded half-tread height because the resolved grid cell's `standingY` was the stair's full height. This guard ran before the existing flat-floor recenter controller, preventing that controller from helping even when an independently safe flat support could have been available.

## Change

- A fresh native actual-body proof may start grounded recovery before the old interrupted-descent guard. It does not reuse the interrupted route or substitute a fictional neighbouring A* origin.
- A verified, already-overlapped flat support can use the existing ground recenter controller.
- A separate stair recenter controller permits ordinary inward walking and the native half-step ascent to the centre of the same vanilla bottom/straight stair. It never jumps, sprints, sneaks, attacks, teleports, or submits an interaction.
- Native admission verifies the actual normal-sized body, standard physics and step capability, exact default/player stair collision shapes, actual tread support, the full movement/lift envelope, loaded bounded collision halo, world border, hazards, friction, and entity/block collisions. Touching an ordinary side wall is distinguished from penetrating it.
- Initial and final completion require two consecutive quiet observations. A vertical auto-step is not counted as a quiet landing. Geometry and action authority are rechecked each tick, and execution has both no-progress and total-time bounds.
- If stair alignment itself is interrupted, movement stops for 10 client ticks before a fresh proof and fresh quiet observations. Three consecutive failed alignment attempts exhaust the local recovery budget. No unconfirmed action fence, native receipt, inventory ownership, or module checkpoint is discarded.
- Successful observed alignment retires the descent latch and stale path; A* starts from the actual supported cell on a later tick. A subsequent independent interruption gets a new recovery budget.
- Starting at the same half-tread pose after OFF/ON or a normal restart also requires the new proof. Restarting is not treated as evidence that the player has landed.

Diagnostics expose `STAIR_RECENTER`, `STAIR_RECENTER_RETRY_WAIT`, `STAIR_RECENTER_COMPLETE`, the current anchor, attempt count, completed-recovery count and interrupted-descent latch.

## Verification and deployment boundary

Final offline `test build`: **2,028 tests, 152 suites, zero failures/errors/skips**. This includes 27 new tests across native geometry, controller behaviour, and public-navigation integration. Coverage includes the incident half-tread/wall-contact geometry, four stair directions, varied input response, quiet-sample rules, public descent interruption, bounded retries, flat fallback, restart/reset, changed authority, and preservation of a fence appearing during recovery.

Build SHA-256: `A7F7D30E82772963B9550678331B7EDB6033641FF581A39C37588FF1B9CF5E56`.

At this checkpoint the user was manually playing with automation OFF. The running game was not stopped, modified in memory, or replaced by another instance. The new build has **not yet been installed or live-tested**; normal restart/application and an in-game recovery observation remain pending. The private read-only incident probe was prepared but not attached after the user moved to a different context. These tests are not a claim of two-hour unattended stability or universal recovery from unsafe terrain/action failures.
