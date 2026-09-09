# Additional wine-storage wall: inspection only

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
