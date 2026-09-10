# Mouse-only manual takeover — 2026-09-10

## Change

Manual takeover now samples the Minecraft window's own cursor position, not player yaw/pitch. Ordinary keyboard input, mouse buttons and wheel no longer call automatic pause. While automation owns input, conflicting item/slot inputs are suppressed within Minecraft; no OS/global input hooks are added. Explicit F8, emergency STOP, settings, unexpected menus and native inventory/custody safety checks remain.

The detector resets while inactive, unfocused or sleeping. Start/resume, focus return, mouse grab changes, screen identity changes and window resizing get a four-client-tick (normally 200 ms) rebase window. Movement after that in a focused game or automation-owned menu triggers manual pause. Background-app mouse movement and ordinary automation/server camera rotation are not takeover evidence.

Refocusing by clicking the game performs its normal cursor grab but cancels the conflicting item press. Forge's noncancelable raw key callbacks drain the matching held/queued item mappings. Container press/release/drag/scroll operations are guarded while ON; actual manual inventory interactions while OFF continue to invalidate inventory/custody evidence. Window-switch keys do not invalidate those records.

## Verification

The installed Forge 47.4.0 API and mapped Minecraft 1.20.1 MouseHandler/KeyboardHandler behavior were inspected locally. Offline `test build` passed **2,365 tests, 0 failures/errors**, including 11 cursor-takeover tests and 10 event-ownership/wiring regressions. Tests cover repeated focus switching, background cursor motion, either-axis/subpixel motion, screen/capture transitions, resize, sleep, invalid observations, explicit STOP priority and item-input fencing. Deterministic tests are not a claim that actual desktop Alt+Tab was pressed during this run.

The live profile also had `allowBackground=false`, which independently stops automation on focus loss. As requested, it was set true through the runtime's normal profile save at a clean native/menu/custody boundary. The maintenance pause retained current work; no pending operation was forcibly acknowledged or erased.

## Independent ancient-wine status audit

The prior game-day-593 pass ran while logging was resource-deferred, but performed no wine clicks: all 96 ancient-wine members were skipped as still producing, and latestFeedDay remained null. Finishing the all-skipped scheduled pass advanced nextDueDay to 599. At game day 597, a passive loaded-block read found **all 96 mature**, but the existing whole-rack date gate still waits until 599. Thus the immediate wait is the saved schedule, not logging ownership. The historical cached block observations do not prove when maturity changed. No production dates were edited in response to the status question; one-time realignment was offered separately to the operator.
