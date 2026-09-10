# Hotbar pickup receipt stabilization — 2026-09-10

## Observed stop

The running Society client remained connected; automation had stopped on a late
hotbar-swap acknowledgement fence. This was not a client crash. A retained raw
server FULL inventory reply proved that the borrowed sword moved to the correct
inventory slot with its complete native fingerprint and count unchanged. The
saplings moved to the hotbar with the same native identity, but increased from
11 to 13 during pickup. Concurrent twig and log pickups were also present.
The old exact-count predicate rejected the completed exchange.

The retained earlier stair-navigation failure was historical, not the evidence
for this stop. No navigation rule was weakened for this incident.

## Changes

- Recognize pickup growth at one swap endpoint only when the distinct other
  item is relocated exactly, including native metadata, count and stack limit.
  Reject quantity loss, changed metadata/limit, ambiguous identical partners,
  growth at both endpoints, invalid menu shape and non-inventory endpoints.
  Other slots do not prove or invalidate this two-endpoint operation, as with
  the existing exact-swap predicate. Their changes authorize no other action.
- Require the same connection generation, a later raw FULL reply for the exact
  menu and an empty packet cursor. Existing exact and native wine-metadata
  confirmation paths remain intact.
- Latch genuine confirmation when native FULL packets are recorded, before
  subsequent packets can evict it from the eight-snapshot history. This callback
  records proof only; it does not send input, complete cancelled tickets or start
  automation. Reduced/marker/single-slot evidence cannot trigger it.
- A nominal swap response timeout now keeps the same pending ticket and module
  phase instead of failing/resetting the whole routine. Movement stops while
  waiting; a genuine late reply completes that original request normally. No
  click replay, automatic reconnect or restart-from-scratch is used.
- F8, manual takeover, settings interruption and disconnect retain their normal
  cancellation authority. A late reply cannot turn an explicit OFF back ON.

## Verification

`gradle --offline test build`: **2,082 tests / 153 suites**, zero failures,
errors or skips. Added endpoint-growth and adversarial receipt tests, proof-latch
tests, non-native observation rejection, and actual logging-module/engine tests
for long pending waits and cancellation in continuous and one-shot modes.

These tests and the retained real server reply establish the specific fix. They
do not establish multi-hour stability of all production, navigation and logging
paths. Live installation and post-restart results will be recorded separately.

## Manual restoration custody

The player subsequently returned the borrowed item to its original hotbar slot
while the old PREPARED lease still existed. A separate recovery path now clears
that custody obligation only after the latest raw native inventory FULL confirms
both current endpoints, the exact saved original fingerprint/count, and an empty
cursor. Same-connection proof must follow the last automatic swap or manual slot
input. A new connection needs its own real FULL; old-generation snapshots cannot
be carried over. Older matching views cannot override a newer differing FULL.

No pending/late action or failure can be bypassed by this query. It sends no input,
does not change a cancelled/failed outcome, and does not mark logging complete.
Checkpoint failure rolls back the lease. The previous no-observed-swap/no-replay
regression remains unchanged with an unknown adapter defaulting to no proof.

Final combined `test build`: **2,094 tests / 154 suites**, zero failures/errors/skips.

Artifact SHA-256:
`7841D85E6DB1CEFCC7298DDB87026881A6A86F0FAC428D8AFB244020A59E50EF`.

## Live-discovered bootstrap gap

The first deployment reconnected, but the next continuous run correctly refused
custody settlement: a native inspection found zero retained FULL inventory
packets after observer installation. Live fingerprints matched, but that alone
was not promoted to server proof. This is an additional bootstrap gap, not a
successful live recovery.

Added a single logging-restoration `RefreshInventory` request. The inspected
Minecraft 1.20.1 / Forge 47.4 handler accepts PICKUP slot `-1` and returns before
slot/item access (distinct from the `-999` drop branch); a mismatched state ID
then produces the ordinary server FULL response. Relevant installed inventory
hooks were also inspected. There is no client inventory prediction or item click
replay. Refresh capability defaults to false for unknown adapters.

The native adapter requires the ordinary closed 46-slot inventory and empty
cursor. It owns the same pending ticket until a newer same-generation raw FULL
arrives, even when the inventory did not change. The module requests at most one
refresh per recovery attempt and must then verify the same lease and exact
restored custody. Missing proof, changed/replaced custody or rejection cannot
loop into another request or inverse swap; manual OFF still revokes continuation.

Combined verification: **2,101 tests / 155 suites**, zero failures/errors/skips.
Final artifact SHA-256:
`5E7002F0325024C638742661BDE8AFCE57E3EC35EFF34100A126B9DDC8800044`.
