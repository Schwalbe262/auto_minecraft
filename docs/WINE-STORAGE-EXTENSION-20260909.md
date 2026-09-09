# Additional wine-storage wall: inspection and approved registration

The user added 16 barrels near X=658, Z=1570..1573 and requested a check.
A bounded read-only native inspection at **2026-09-09 07:47:01 UTC** confirmed
exactly 16 `minecraft:barrel` blocks at **X=659, Y=63..66, Z=1570..1573**.
All face west. X=658 is the front aisle, not the barrel-block coordinate.

The 180-cell inspection window was fully loaded. Every new barrel had at least
11 verified standing cells at Y=63 from which the native adapter found a visible
interaction point within four blocks. This proves local standing/reach geometry,
not an executed navigation route, container opening, contents or empty capacity.
No inventory, closed-container NBT, control input or profile mutation was used.

The saved profile and live profile both still contain only the existing 16 wine
storage POIs at X=659, Y=63..66, Z=1565..1568. None of the new wall is registered.
Current native wine year was 14. The existing classifiers are **production
cohorts 1..16**, not current wine ages: cohort 14 is the current zero-year wine
destination, while 15 and 16 are future production cohorts.

If the intended extension preserves the existing top-to-bottom, increasing-Z
order, the following is the proposed mapping, **not an applied registration**:

| Y (X=659 throughout) | Z=1570 | Z=1571 | Z=1572 | Z=1573 |
| --- | --- | --- | --- | --- |
| 66 | cohort 17 | cohort 18 | cohort 19 | cohort 20 |
| 65 | cohort 21 | cohort 22 | cohort 23 | cohort 24 |
| 64 | cohort 25 | cohort 26 | cohort 27 | cohort 28 |
| 63 | cohort 29 | cohort 30 | cohort 31 | cohort 32 |

That would reserve another 16 production years without changing current-cohort
surplus-sale thresholds. Registering these as additional capacity for cohort 14
would be a different allocation and would change the current reserve-fullness
requirement. The check request did not perform either mutation. The existing
game session and its autonomous work were left untouched.

## Registration applied after explicit approval

After the user approved registration, the mapping above was applied at
**07:54:47 UTC** through the installed mod's normal validated profile-save path.
The server, player and overworld profile binding were checked privately. The
player had returned from another dimension before the save, so the update was
applied to the active, paused overworld profile; no other dimension was changed.
All 16 new barrel block identities were rechecked before the mutation.

The existing 712 POIs were preserved in their original order. Exactly 16 wine
POIs were appended, giving **728 total POIs and 32 wine storage barrels**. A
separate comparison of the original backup against the persisted file found
only `pois` changed; all existing schedules, feature settings, farms and storage
definitions remained unchanged. A normal profile reload matched the expected
result. Fresh native telemetry at 07:54:57 UTC independently reported 32 wine
barrels without restarting the client.

The original file has a private recoverable backup. No items were moved, no
container was opened, and automation's ON/OFF state was not changed. These are
future production-cohort destinations, not additional capacity for current
cohort 14. Actual future-year deposits are not claimed to have been exercised.
