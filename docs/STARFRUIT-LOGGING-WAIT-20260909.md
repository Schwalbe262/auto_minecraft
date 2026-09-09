# Starfruit detours during a granted logging wait

During the two-hour stability investigation, native observations showed no
starfruit execution or held fruit in the first twenty minutes, despite the
feature, eight registered fruit positions and its storage being configured.
Some sampled production/sleep routes passed within the six-block trigger.
A later passive native sample found all eight fruits loaded and age 7, an empty
usable hotbar slot and eighteen free inventory slots. This does not establish
the fruits' maturity or hotbar state at every earlier pass-by moment.

The scheduler independently had a definite exclusion: `loggingAllowsNearby`
rejected every non-null resource wait, even an already-granted clean logging wait
which was permitting ordinary production to run. One leaf-obstructed remaining
tree therefore also suppressed the intended production/sleep-travel fruit detour.

The correction permits a detour only during an already-granted logging wait at
the existing clean travel boundary. It retains the exact wait owner and grant,
registered plots, remaining/replant obligations and logging due-date snapshot.
An owned fruit operation or container must not be mistaken for unsafe logging:
while fruit holds that operation, only the retained ownership is checked. At a
clean return, logging readiness is checked again before returning to the same
original production/sleep module instance. The original wait timer is retained.

Active logging, an ungranted wait, a loaned hotbar item, unresolved native action,
changed ownership, unsafe travel and one-shot execution do not gain this permission.
No retained tree is removed or marked complete by the detour.

Focused starfruit tests passed 60/60, including six new integration regressions.
Adjacent standalone engine/logging/logistics regression classes passed 393/393.
The combined offline Java 17 test/build passed 1,839 tests across 138 suites,
with no failures, errors or skips. The build was installed with a recoverable
previous-JAR backup and a normal single-client restart; native acceptance remains
to be observed at that build step. Subsequent native acceptance is below. This
change does not itself make the leaf-obstructed logging plot harvestable.

## Native acceptance

After restart, continuous mode resumed, slept from game day 505 to 506, and
entered the preserves routine. Its source-travel phase detoured to starfruit,
while the existing hidden-logging resource wait remained retained. Native trace
samples captured fruit count increasing to eight and entry to the registered
storage phase, followed by return to preserves with no failure fence.

A separate passive inspection found all eight registered fruits at age 0 with
eight stored same-day confirmations, no remaining patch members and no fruit
left in inventory. This confirms the real fruit pass, not merely an ON flag or
a detached unit test. Item-specific transfer receipt evidence is a separate
check; this short acceptance does not prove two-hour stability.
