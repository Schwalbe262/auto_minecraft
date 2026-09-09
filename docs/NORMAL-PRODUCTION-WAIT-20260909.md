# Normal production waits and status

An earlier apparent stall was a normal wine-production wait, not the later TrashSlot error. The read-only trace showed 258 seconds of closed-menu WAITING with no pending action, navigation or failure fence. Earlier sleep and downstream work had completed. The unfinished whole-rack wine batch must not be marked complete or split into small daily runs.

`WorkResult.COOLDOWN` now represents only a clean, directly observed whole-rack wine wait. The engine polls it at a fixed 100 client ticks (about five seconds at 20 TPS), wakes that normal wait when the game day changes and allows other eligible jobs and safe bedtime sleep. Genuine route/response deferrals retain their existing increasing backoff and safety rules.

The status distinguishes normal production waits, gives the remaining working count and next observation interval, and includes the approximate time until configured bedtime. Tomato harvest remains two days and ancient-fruit harvest remains its crop-specific interval (currently ten days); the settings labels distinguish them.

No-reset normal waits retain observations, so before any active wine-batch readiness decision every remaining member must still be loaded. An unloaded previously mature member cannot authorize a ready subset from cached state. The same-day unload/reload regression test also preserves durable batch membership, native confirmations and individual production deadlines.

Tests cover fixed polling, day boundaries, no premature completion, isolated one-shot waits, manual OFF, route-error backoff, unsafe menus/cursors/airborne state, unfinished ownership and late-action fences.
