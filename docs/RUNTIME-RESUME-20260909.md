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

## Successful follow-up and continuous restart

After that second restart, coordinate-only movement with LOGGING OFF succeeded
while the suspended logging queue remained present. Native read-only samples
recorded the two ordinary ascents on the adjacent exit row entering flight,
landing and completing without an ascent failure. This was an explicitly chosen
alternate destination, not proof that an automatic detour had already recovered
the original failed attempt in the previous process.

The resumed seed-maker one-shot then completed. All four registered seed makers
were observed working, day-460 eligibility was saved after the day-459 run, and
retained successful outcomes included native artisan state/slot confirmation and
the subsequent inventory transfers. Neither ancient fruit nor seeds remained in
inventory after the output deposit and unused-ingredient return.

A further coordinate-only move traversed the full exit and reached the starfruit
area without a retained ascent failure. One STARFRUIT one-shot picked a registered
ripe fruit, observed its age reset from 7 to 0, and completed the confirmed
container deposit with no starfruit left in inventory. This one-shot handles one
nearby fruit; it did not harvest the entire tree. The draft contains seven exact
fruit positions, while this native observation saw eight nearby fruit blocks.
The unregistered position was not automatically added or harvested.

The reviewed definitions were subsequently imported with exactly SEED_MAKER,
CRYSTAL_COPY and STARFRUIT enabled, preserving their existing scheduled dates.
LOGGING and broad COMMODITY_STORAGE remain OFF. The normal `start` request was
acknowledged in CONTINUOUS mode, equivalent to starting with F8.

Logging recovery, quantitative native stair timing and continuous multi-cycle
operation remain to be verified. No twofold stair-speed or hours-long stability
claim follows from these successful one-shot checks.

## Cooldown scheduler regression found during continuous testing

The resumed continuous run serviced all 144 preserves jars: all were observed
working with later eligibility dates, and no preserves or tomatoes remained in
inventory afterward. It then repeatedly selected a zero-target seed-maker pass
even though those four machines were scheduled for the following day. This was
an actual scheduler starvation, not an inventory acknowledgement or navigation
failure: START returned BUSY for an empty pass, and the engine selected that same
high-priority module again before reaching starfruit or sleep.

Artisan job selection now skips jobs with neither due machines nor currently
held input/output in the same tick. An entirely quiet module returns IDLE
immediately. A later due job, actual item cleanup and accumulated uncertain-target
deferrals retain their existing behavior. No scheduler priorities, machine dates
or action fences were reset to work around the bug.

Seven new regressions include the actual continuous engine with both artisan
modules, lower-priority work, sleep and a following-day sweep. The complete
offline Java 17 `test build` passed **1,465 tests / 109 suites**, zero failures,
errors or skips. The tested JAR has SHA-256
`BB2C80DACDB565E434A98E797A196FBA5E63CC80141CF4B5B12DA1431DC1CF49`.
It was installed through a normal same-instance restart with the previous JAR
and pre-restart profile retained in private backups.

Before that restart, a separate native sleep one-shot reached the registered bed,
was observed actually sleeping on day 459, and completed after day 460 began.
This establishes the bed interaction/day transition, but by itself is not a
continuous-scheduler sleep acceptance of the new cooldown fix.

The new build subsequently reconnected and acknowledged a continuous start.
On day 460 it harvested/stored tomatoes, then serviced all four newly mature seed
makers, stored seeds and returned unused ancient fruit. All four machines were
observed working with day-461 eligibility; neither ingredient nor output remained
in inventory. The engine then reached its normal crops/machines/bedtime WAITING
state instead of reselecting the zero-target artisan pass. The three crystalariums
remained working with unchanged day-462 deadlines.

Without another start request, CONTINUOUS mode subsequently reached the bed and
was observed sleeping on day 460. Day 461 then began with automation still ON.
The four seed makers were serviced again, their eligibility advanced to day 462,
and all held ancient fruit/seeds were stored before normal WAITING resumed.
This is a real continuous work/wait/sleep/next-day recurrence check, not only an
isolated sleep one-shot. It is still not a multi-hour soak pass of every module.

## Native descent measurements

Two supervised runs down the same six-edge cellar route completed in approximately
5.86 and 6.17 seconds from first controller observation through controller removal.
The different initial alignments affected the preparation time; the common lower
five edges took 98 ticks in both runs. Both stopped grounded with zero horizontal
velocity and about 0.065-block final center error, with no observed navigation
failure. These are sampled native observations, not simulated player motion.

An earlier chain-handoff implementation took 137 ticks on the common lower-five-
edge section, versus 98 now (28.5% fewer ticks). The old run started that section
from rest after a turn, whereas the new run retained momentum from a straight
preceding stair. This is useful historical evidence but not an equal-initial-state
benchmark or a new blanket twofold-speed guarantee.

The current controller used its verified flow corridor for the first three edges
and ordinary descent for the remaining three. A separate read-only native probe
confirmed that the middle extended envelope is rejected while a later two-edge
stair corridor is independently valid. The final platform is not a stair. At this
checkpoint, re-entering flow after that observed ordinary handoff is under test;
the rejected middle envelope has not been waived or enlarged.

The follow-up source change now permits that re-entry only after a completed
ordinary handoff, a consecutive actual full-support grounded observation and all
of the existing normal handoff bounds (including its 0.12 speed ceiling). It then
requires a fresh independent native flow proof. Airborne/half-tread/missing samples,
unsafe motion, pending actions and unknown calibration cannot acquire the new
proof. A lost existing proof still fails before any replacement; acquiring a
proof does not advance a path index or mark another landing complete.

Five new tests plus the existing descent selection passed **58/58** standalone.
The gap-and-ceiling physics fixture improved from 112 to 107 ticks, retaining
ordinary handling of the final solid platform and the existing quiet stop. This
is a model measurement; this follow-up has not yet been installed or timed in the
live client at this checkpoint.

## Flat-travel pass-by integration and a disposal race

The uninterrupted continuous run completed a second automatic sleep and reached
day 462. All 144 preserves jars were then observed working again. Later, after
another tomato harvest, a TrashSlot acknowledgement fence stopped the engine.
This ends that uninterrupted run; it is not an hours-long all-feature stability pass.

A read-only projection of retained native replies identified a concrete source
count race. A single-slot request was made for two rotten tomatoes. Before the
server's empty-source reply, a separate server reply increased that same source
to four rotten tomatoes. An unrelated tomato addition also had its own earlier
raw-slot reply. The strict source-proof validator rejects any pre-deletion count
change, so it could not accept this sequence even though the source was empty.
This evidence projects item IDs/counts, not native metadata equality; a future
fix must still require identical native identity/limit, monotonic growth and
ordered authoritative evidence. No acknowledgement was forged or request resent.

The stair-flow re-entry and safe flat-travel starfruit detour were combined and
passed **1,527 tests / 111 suites**, zero failures, errors or skips. Independent
review also found no critical blocker. The combined artifact SHA-256 is
`F37864F77BE00FEB29E0E8E78A720255409B7CBBABDB1CBAEE5B163FC73CE911`.
It was installed through a normal same-instance restart, preserving the previous
JAR, profile and private failure evidence. The disposal-race correction is not
in this artifact. Native timing and detour/resume acceptance are still pending
at this checkpoint.

### Rotten-stack pickup acknowledgement correction

An explicit rotten-only source proof now accepts ordered, server-authored
monotonic count increases with exactly the original native identity and stack
limit, followed by that source becoming empty. The action reports the last proven
quantity (four in the regression), not the original two. Logging disposal still
uses the unchanged strict proof. Identity/limit changes, decreases, ambiguous
sequences and an empty/refill/empty sequence before the candidate are rejected.
Later pickup cannot retroactively invalidate an already proven earlier deletion.

Independent prior raw replies are still required for allowed additions in an
applied-client menu; that surrounding menu is never promoted into a full server
packet. Whole-menu conservation, source-empty, cursor and connection checks remain.
No resend or automatic acknowledgement-fence clearing was added.

The captured sequence was reproduced in detached core/native regression tests.
The complete offline build passed **1,552 tests / 113 suites**, no failures, errors
or skips. The corrected artifact SHA-256 is
`F98D6F29888F241871C9789B8668FC3885329F13C265162E88E21A10CF30AA19`.
It has not yet been installed or exercised in the live client at this checkpoint.

## Stopped-start descent acceptance and latest installation

Two subsequent native coordinate-travel tests started on the same full-height
stair support with zero horizontal velocity. Four preceding samples also showed
each player pose stationary. Both five-edge descents took **99 ticks**:
4.954 seconds and 4.945 seconds, including preparation and final landing.
Both directly showed the new independent lower-corridor proof being acquired
while fully grounded without advancing the completed-edge count. The unchanged
handoff speed bound, rejected middle envelope and ordinary final-platform handling
were retained. Final consecutive observations were grounded, stationary and free
of movement requests or navigation failure. Each trace missed one non-boundary
tick; all 30 observed airborne samples per repeat had no stored movement request.

The historical initial stopped-start record took 282 ticks on the same common
five-edge section: these repeats use 64.9% fewer ticks (2.85x tick ratio). Against
the later 137-tick chain-handoff implementation the reduction is 27.7%, not 2x.
Natural pose offsets, historical observer semantics and unreplayed world conditions
still limit causal comparison. This is a measured route improvement, not a promise
that every stair or the small re-entry change alone is 2.85x faster.

These timing runs used F37864 above. The tested F98D6F disposal-corrected artifact,
with the same navigation changes, was subsequently installed through another
normal same-instance restart. Backups were retained and the server reconnected.
A new continuous start was acknowledged. This installation does not by itself
prove the newly corrected pickup race has recurred and passed in the live game.

## Native starfruit detour, deposit and sleep resumption

After the latest installation, CONTINUOUS mode began approaching the registered
bed on day 463. During actual flat travel it suspended that SleepModule for a
nearby registered starfruit. The observer directly retained SLEEP as nearbyOrigin,
followed by restoration to SLEEP and continued bed travel. There was no new start
request between the detour, actual sleeping and day-464 seed-maker work.

A separate read-only snapshot of retained action results confirmed all five
operations: fruit interaction succeeded; the registered container synchronized;
the inventory transfer succeeded with **one item**; the container closed; and
the bed interaction succeeded. Native target updates retained the fruit's reset
age 0, then age 1 after the next day. The remembered target/patch still matched
the registration and no starfruit remained in the sampled inventory. This closes
the one-second observer's short pickup/deposit gap without inferring a deposit
solely from an empty inventory or a status string.

The disabled logging queue remained preserved and did not block this permitted
detour. No raw input replay or schedule manipulation was used. This acceptance
covers a sleep-approach detour; production-machine detours are unit-tested but
have not yet been separately exercised in the live client.

On day 464 the seed job refilled one machine with remaining material, stored
the unused fruit/seeds, and allowed ordinary work to continue when insufficient
fruit remained for the other three machines. A further tomato harvest/disposal/
storage pass completed without a global action fence in the corrected build.
That is not proof that the exact 2-to-4 pickup race happened a second time.
The continuous run remains under observation; a multi-hour all-feature stability
pass is still outstanding.

## Continued cycle and newly retained inventory-restoration failure

The same continuous run allowed the day-464 seed-material wait to yield to
actual sleep, then entered day 465 without a new start command. All 144 preserves
machines subsequently returned to working state and the sampled inventory
contained 144 preserves before the following storage/shipping work. The whole
384-machine wine batch then started; 160 machines were observed refilled with
160 wines in inventory and 224 batch members still pending.

At 01:57:15 UTC the run paused on a retained inventory-consolidation hotbar loan.
The client remained connected/responding, the cursor was empty, and the batch
and ingredients were preserved. This is an inventory-restoration fence, not a
navigation failure or proof of a completed wine batch. No restart, old-click
resend or forced fence clearing was used at discovery. Diagnosis of the retained
native transaction is in progress; this run does **not** pass multi-hour stability.

### Cause: a verified same-kind pickup before an unsent merge

A bounded read-only native snapshot retained the exact outward SWAP full reply,
one acknowledged primitive, stage MERGE and no in-flight click. Both swap partners
were unchanged. The only difference was a separate receiver slot changing from
empty to one wine; a later authoritative slot packet exactly matched its current
native identity/count. This was not a passive wine-metadata change.

The old pre-click refresh reused the concurrent-ACK rule that excludes the moved
item's native identity. That is necessary when interpreting an in-flight move's
reply, but needlessly rejected the proven pickup before the next move was sent.
A separate explicit pre-MERGE receiver proof now admits this narrow case after
the outward SWAP acknowledgement. Its refreshed count becomes the next exact
conservation baseline. The old overloads, concurrent-ACK classifier, swap partners
and native moved-versus-received verification remain unchanged. An in-flight
primitive cannot enter this pre-click refresh path. The historical failed action
is not converted into success and this change does not clear its retained fence.

Fourteen new detached core/native regression tests cover the captured sequence,
old-overload strictness, exact subsequent merge/restoration, positive partial
receiver pickups, malformed/missing proofs, protected participants and atomic
rejection. The full offline build passed **1,566 tests / 115 suites**, with zero
failures, errors or skips. Artifact SHA-256:
`6E8BDB5E74C05018AC3836F1E1F88B959CC0732098FBEF25FF91E09D559AAC74`.
Installation and native post-fix acceptance are still pending at this checkpoint.
