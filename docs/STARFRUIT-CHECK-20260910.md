# Starfruit activation check — 2026-09-10

This was a read-only diagnosis, not a scheduling or registration change.

## Current behavior

- There is no periodic trip to the trees. `StarfruitModule` starts when a registered, loaded, ripe fruit is within six blocks.
- After activation, it services the registered patch's initially loaded ripe cohort, not only the triggering fruit, then deposits it.
- Native ripeness is `age=7`. A confirmed fruit is not harvested again that game day. Approach failures have a 1,200-tick retry backoff.
- Opportunistic interruption of another module's travel currently applies to wine, preserves and sleep, not every feature. The normal safety, inventory and registration conditions still apply.

## Local observations

The feature was enabled with one registered eight-fruit patch and its storage. During the reviewed ten-minute trace window, no starfruit automation run was recorded. While automation was waiting, the player was approximately 27 blocks from the nearest registered fruit, outside the activation distance.

After direct control paused automation, the player approached the patch. Held starfruit increased from zero to eight, then decreased to zero after a container was opened. A subsequent native read observed six registered fruits at age zero and two at age one. This is consistent with manual harvesting/storage, but per-fruit before/after receipts were not captured. The later immature states must not be presented as proof that the fruits were immature when the user first noticed the omission.

The supported explanation is the nearby-only activation limit: ripe fruit can remain untouched when other work never takes the player near the patch. The traces do not establish a multi-day cooldown or a failed automatic harvest attempt in this window. Periodic dedicated visits would be a separate behavior change.

Evidence was read locally from the existing passive watcher and one existing read-only diagnostics request. No gameplay input, feature toggles, location edits or harvest clicks were sent. Raw traces, player/account identifiers and private registration coordinates are not included here.
