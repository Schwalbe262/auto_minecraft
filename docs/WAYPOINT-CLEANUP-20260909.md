# Requested waypoint backup and removal

On 2026-09-09 at 10:09:56 UTC, the user-authorized cleanup removed exactly 132 `WAYPOINT` POIs from the active Society profile. The other 596 POIs retained their original values and order. All non-POI profile fields were compared and preserved, including farms, commodity stores, the 384-member wine-production group, wine cohorts, production dates and unfinished-work records.

Before deletion, three independent private backups were created and forced to disk, then byte-for-byte verified:

- `profile-live-before.json`: full live profile immediately before removal.
- `profile-disk-before.json`: original saved file, byte-for-byte.
- `waypoints-only.json`: the 132 removed entries.

They are in the Society instance's `local/autovalley-backups/waypoints-20260909-c72761dc-5490-40c9-adb7-f8eb900132a9/` directory. These backups contain private configuration and were not uploaded to GitHub. Unlike the rolling `.json.bak`, this distinct backup directory will not be replaced by the next normal profile save.

The cleanup ran on the existing paused client's thread using its normal profile-save method. It did not replace the active profile/list object, restart or reconnect Minecraft, change inventories, send game actions, clear any action failure, or resume automation. Detached validation checked that schema/default normalization would not introduce unrelated changes. Save failure would restore the original POI list; a successful save with a failed post-check would stop without blindly repeating the operation.

After saving, independent disk/backup comparison confirmed zero remaining waypoints, 596 unchanged non-waypoint POIs and no other changed profile fields. Navigation remains `TERRAIN` with waypoint hints off. An already-open settings page must be reopened to refresh its cached rows; no game restart is needed.

This removes saved waypoint metadata, not world blocks or worksite registrations. Restoring the entire older profile later would also roll back newer schedules/settings; restoration should normally merge only the backed-up waypoint entries after checking for newly occupied positions and obtaining approval.
