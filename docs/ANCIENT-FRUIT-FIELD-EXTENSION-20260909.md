# Ancient-fruit field extension

The user requested registration of the expanded ancient-fruit growing area.
A native scan at 08:05:12 UTC identified a complete connected cluster at
**X=716..721, Y=72, Z=1588..1607**, containing **98 crop blocks**. Separate
western ancient-fruit clusters were not included.

At **08:11:59 UTC** the existing `고대과일` farm's bounds were expanded from
X=716..718, Z=1596..1607 to that observed rectangle. The bounded native preflight
rechecked all crop cells and a two-block surrounding margin before saving.
The same farm name and crop ID were retained, preserving its schedule key.

The normal profile-save path and subsequent reload matched the expected update.
Independent comparison against a recoverable private backup found only `farms`
changed. The ten-day crop interval, linked `ancient_warehouse`, per-job deadlines,
other farms and all registered wine-storage cohorts were preserved.

No harvest, transfer, inventory click, restart or ON/OFF change was performed by
this registration. The unrelated live action-failure/custody objects were kept
unchanged. This verifies configuration, not a completed expanded-field harvest.
