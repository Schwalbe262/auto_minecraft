# Runtime resume checkpoint — 2026-09-09

The existing Society Sunlit Valley 4.1.4 client was normally closed and restarted
with the tested `84FB3425…` candidate. The previous Auto Valley JAR was backed up;
no extra game instance was launched and no OS key/mouse injection was used.
The initial quick-play connection failed with `Unknown host`. A later system and
in-client DNS/TCP check succeeded, followed by a normal reconnect in the same
client. No global DNS/hosts setting was changed.

## Applied registration

The reviewed private V3 definitions were imported while automation and recording
were OFF, with no open container or cursor item. The runtime acknowledged the
import and upgraded the profile to schema 5. It now has three farms, four commodity
storage groups, two artisan jobs and one fruit patch. The existing 712 POIs and
all pre-import scheduled dates were preserved. Existing feature choices were
preserved; newly added features still default OFF until explicitly enabled.

The import file, recordings, profiles and runtime evidence contain private
coordinates and are not published.

## Observed native execution

- **Crystal copy:** the one-shot completed; all three registered crystalariums
  were observed working, with five-day eligibility entries saved for day 462
  after the day-457 run. No repeat use was sent for a cooldown test.
- **Ancient fruit:** the common harvest workflow obtained 54 fruit from the
  registered 27-plant field; all 27 plants were then observed at age 0. The next
  harvest date was saved as day 467, ten days after this run. The one-shot later
  finished its linked warehouse deposit with no ancient fruit left in inventory.
- **Tomato storage:** after the combined harvest, the standalone storage run
  completed with no tomatoes left in inventory, a closed menu and empty cursor.
- **Seed makers:** the first run cleanly deferred because the warehouse lacked
  ingredients. After harvest, another run deferred because the inventory was
  full. Following tomato storage, a run withdrew 52 ancient fruit, then stopped
  on an interrupted one-block ascent at the warehouse exit. No seed-making
  completion is claimed at this checkpoint; the fruit was retained.

The old inventory acknowledgement stop did not recur during these observed
storage operations. This does not establish that every inventory race is fixed.

## Additional transit issues found

The stored ascent failure was an interrupted directed edge, not a fresh logging
operation or JVM crash. General ascent shares the logging controller's retained
no-relaunch set. The subsequent guard overwrote the original failure detail, so
the original launch rejection, flight failure or cancellation cannot be determined
from that last snapshot alone. The native observed alternate corridor is not
permission to erase an uncertain launch and replay it.

A separate coordinate-only move was rejected because `loggingRunActive` remained
true even though LOGGING was OFF and its queue was intentionally suspended.
Coordinate movement is now aligned with that suspension policy: preserve the
queue, retain all borrowed-slot and uncertain-action restrictions, but do not
make an OFF logging queue globally prohibit ordinary movement.

Terrain search now snapshots the retained set of interrupted directed jump edges
and excludes those edges when they require a jump. A genuinely walkable edge and
the opposite direction remain eligible. No launch fence is cleared, no uncertain
jump is resent, and an absent alternative remains a failed route. A separate
bounded `lastAscentFailure` retains the controller phase, original reason, edge,
authority, attempted flag and tick across later generic failure messages.

The combined follow-up `test build` passed **1,458 tests / 109 suites**, with no
failures, errors or skips. Candidate SHA-256:
`07A7877187FCDF24F1D65563980F7C734FE6D537218BE3216A631B5DEF6494E3`.
It was installed through a second normal same-instance restart; old JARs remain
recoverable in the instance's timestamped Auto Valley backup directories.

Starfruit collection, complete seed-maker operation, logging recovery, native
stair timing and continuous multi-cycle operation remain to be verified. No
twofold stair-speed or hours-long stability claim follows from this checkpoint.
