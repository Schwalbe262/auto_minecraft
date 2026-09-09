# Fixed wine rack cadence — 2026-09-10

## Behavior

The registered wine rack now runs on its common configured cadence (six game days in the current profile), without waiting for every keg to become ready. Still-producing, physically missing or changed-type members are explicitly recorded as skipped for that pass. Removed registrations in a paused pass are recorded likewise. Ready/empty members still require normal native input/state acknowledgements.

The next common date stays on the original scheduled cadence, not six days after the latest individual feed. Completion advances to the first future cadence boundary, including after an entirely skipped pass. There are no next-day small catch-up visits for skipped members. Their actual feed audit and individual deadlines are not fabricated or overwritten.

`WineBatchSchedule.skipped` stores bounded position/reason/game-day records. Existing four-argument callers and JSON profiles without the field remain compatible. Checkpoint failures restore the original schedule; a failed skip does not authorize the next target.

## Important boundaries

- Wait until dawn tick 240 before opening a new due pass, because native artisan updates arrive during ticks 20–219. This short grace does not wait for the entire rack to finish production.
- Unloaded targets are not assumed missing. Normal navigation loads/observes them. A loaded block that disappears during travel can be skipped at a proven safe travel-yield boundary or after an ordinary reach/no-path failure; navigation safety/uncertain-jump failures remain protected.
- Skips happen only before native machine use, with no pending native action, uncertain target-scoped use, carried cursor, borrowed inventory lease or pending output obligation. Sent-but-unconfirmed use and VERIFY failures remain unresolved, not converted to skips.
- A full tomato warehouse can yield with its synchronized menu open. Wine closes that menu through the normal receipt path before using carried ingredients; it does not turn a normal storage handoff into a permanent production block.
- Wine storage age classification, sale reserves and preserves pickup proofs are unchanged.

## Verification and deployment

Java 17 / offline Gradle `test build`: **2,047 tests across 152 suites; zero failures, errors or skipped tests**. The first integration run found the open-full-warehouse handoff regression described above; it was fixed and the complete suite passed on the second run. `git diff --check` passed.

Built artifact: `autovalley-0.1.3-SNAPSHOT.jar`

SHA-256: `C55104CA2038BB16117598AAF6D7FBD1CE27D6FBC7C81E6F9239100F14EC3046`

At this checkpoint the existing game still runs the older JAR; no restart, live schedule reset or forced wine cycle was performed. Installation requires a normal game restart. Therefore this report establishes implementation and regression verification, not a completed live six-day production pass or multi-hour stability acceptance. The build also includes the previously committed stair-hit recovery patch that was awaiting installation.

No private profile, raw observation trace, registration coordinates, credentials or account identifiers are included in this change.
