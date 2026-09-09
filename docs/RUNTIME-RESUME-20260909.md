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

### Guarded live custody recovery and installation

Before restarting, a one-use recovery checked the retained outward-SWAP full
reply, exact unchanged partners, the independently proven extra wine and all
46 menu slots. With automation OFF, no pending or in-flight action and an empty
cursor, it sent only the inverse inventory SWAP. A new full server reply proved
the exact inverse across all 46 slots, including the restored **62 torches** and
the separately received wine. One click was sent; no merge was retried, no old
outcome was changed and no acknowledgement/fence was fabricated or cleared.

The corrected 6E8BDB artifact was then installed through a normal same-instance
restart, retaining the previous JAR, profile and private failure/recovery evidence.
Post-install connection and resumed-batch acceptance remain to be observed.

The corrected client reconnected and acknowledged a CONTINUOUS start at
02:17:16 UTC. It returned the carried tomatoes/wine, harvested and stored the next
tomato crops, and processed ordinary work without a new native action fence.
The ancient-fruit field's day-467 inspection found no mature harvest and moved
its reinspection to day 468; this is not a second successful ten-day harvest.

Wine resumption encountered an independently changed rack. In the fresh client,
while automation was still OFF, observed rack counts changed from 202 working /
160 mature / 22 idle to 215 / 158 / 11 before the new start. They subsequently
settled at 249 / 127 / 8 while this client was storing items and harvesting crops,
not servicing wine. The persisted batch still has 224 unconfirmed members and
only its prior 160 acknowledged feeds. These observations do not identify who
or what changed the other machines. The existing preflight correctly did not
invent refill acknowledgements or repeat their inputs; other eligible modules
continue but the working unconfirmed members prevent wine resumption. The user
has been asked whether another player or automation is servicing the rack.
The new inventory fix has passed detached tests but has not yet re-encountered
the same race in a resumed native wine batch. Continuous all-feature acceptance
remains incomplete.

### Safe preflight waiting for an interrupted wine rack

The initial active-batch preflight now defers when an unconfirmed member is
still working, instead of treating that read-only observation as a global work
barrier. The same 224-member obligation and its dates remain authoritative:
no feed is inferred, no ready subset is serviced, and no pending member is
deleted. A clean, fully observed START boundary can grant the existing scheduler
permission to continue other work and later sleep. Unloaded/invalid targets,
cursor items, active inventory transactions, output obligations and native
failure fences cannot grant that permission. Mid-run uncertainty stays blocked.

Nine new regression tests cover unchanged batch/deadlines, continuous preserves
then sleep, one-shot WAITING, all-remaining-ready retries, real feed-day scheduling,
resource fences, invalid observations and ownership reset. Two existing
cancellation/restart expectations now explicitly verify deferral without an
invented refill acknowledgement. The offline full build passed **1,575 tests /
115 suites**, with zero failures, errors or skips. Artifact SHA-256:
`99D0970869E02623760FD943370E504A1813DE6254E62145DB695E3CE16DEBB2`.
Installation and native clean-wait-to-sleep acceptance are pending at this
checkpoint. Before installation, natural day 468 began and all three registered
jade machines became mature; their collection has not yet been observed.

Before the same-instance restart, the day-468 continuous run harvested another
**54 ancient fruit** and entered its registered commodity-storage flow. The
retained native outcome for transfer ticket 375 is SUCCEEDED with confirmedCount
54; ticket 374 confirms container synchronization and 376 confirms closure.
The independent trace identifies that transfer as the ancient-fruit deposit,
so this is confirmed storage, not an inference from inventory becoming empty.
The fruit had been actually unripe at the preceding day-467 inspection, which
correctly scheduled reinspection rather than harvesting early.

The 99D097 artifact was installed through a normal close/restart of the same
Society instance, with the old JAR and current profile/evidence backed up. The
previous process exited before its replacement started; no duplicate game was
launched. Native post-install waiting, jade service and bedtime checks follow.

Review of the final pre-restart trace additionally found the recurring jade
service at 02:40:51–55 UTC: three machines changed from mature to working,
inventory rose from zero through one/two to three jade, and the registered jade
storage routine ran before inventory returned to zero. All three persisted
deadlines advanced from day 468 to day 473; the ancient-fruit deadline is day
478. Exact retained transfer outcomes for the jade tickets were not included in
the pre-restart snapshot, so the trace/deadline evidence is distinguished from
the exact 54-fruit deposit receipt above.

The replacement client's quick-play initially reported an unknown host. A
fresh same-JVM DNS/TCP check succeeded and normal in-client reconnect completed;
no address/hosts override or extra game was used. CONTINUOUS start was acknowledged
at 02:45:40 UTC. The corrected initial wine wait preserved the 224 pending
members and allowed the due preserves run to start, reaching 128/144 machines
without a native action fence. This is partial post-install acceptance; shipping,
seed service, bedtime and extended stability are still being observed.

### Post-install shipping, seed refill and actual sleep

The same uninterrupted CONTINUOUS start completed all 144 preserves machines.
The shipping trace maps native transfer tickets 267/268/269 to the registered
smart-bin visit; their retained SUCCEEDED outcomes confirm **64 + 64 + 16**
items transferred, followed by confirmed container closure. This proves 144
preserves were deposited, not that a particular HUD account was already paid.

The seed job then withdrew 55 ancient fruit (the new 54 plus one existing fruit),
serviced all four machines with native state/participating-slot confirmations
309–312, consumed 12 fruit and observed four seeds. Native transfer 314 confirms
four seeds stored; transfer 317 confirms the unused 43 fruit returned, with both
containers confirmed closed. All four next-eligible dates became day 469.

Without another start command, the engine approached bed. Ticket 319 succeeded
with Entered bed, the read-only trace observed sleeping at 02:50:17 UTC, and
natural day 469 began at 02:50:23 UTC. Running stayed ON. The unconfirmed wine
batch remained active with 224 members and the original day-465 feed/due fields
through these operations; no working member was removed or assigned an invented
refill. This closes native acceptance of clean wine wait → preserves/shipping →
seed service/storage → real sleep/day advance. Extended uninterrupted stability
and later remaining-rack resumption are still pending, not implied by this pass.

### Nearby fruit cohort revision (not yet installed)

Completion review found that one fruit per detour could leave ripe neighbors
behind. The revised module freezes a single patch's initially loaded/mature
nearby members, collects the still-safe members before one final storage pass,
and preserves the exact original task on return. It cannot grow the target set
through later movement, loading or ripening. New regressions cover seven-fruit
collection, skipped targets, changing capacity/hand, delayed pickups and store
registration changes. Integration tests use the actual WINE/PRESERVES modules
at RETURN, partial SOURCE and later MACHINE travel, plus actual SleepModule,
to verify progress, source audits, inputs, deadlines and feed counts survive.

The combined offline build passed **1,587 tests / 115 suites**, with zero failures,
errors or skips. This revision is not installed yet and has no cohort native
acceptance. The running 99D097 client subsequently reached an inventory merge
fence during its resumed wine batch; that retained transaction must be understood
and actual borrowed-item custody resolved before a normal restart.

### Wine receiver initialization during an actual inventory merge

The resumed rack completed another 100 members, leaving 124 unconfirmed, before
a new inventory fence. Retained full native replies show an initialized wine
source decreasing from one to zero while another one-item wine receiver becomes
two items and gains its native initialization metadata. Every other menu slot
is exact and the cursor is empty. The receiving stack differs from the planner's
predicted destination; total wine is conserved. Reproducing the installed
Vinery initialization on a detached receiver at its original count matched the
observed metadata exactly. This is not evidence of arbitrary vintage mixing.

The fix independently proves that exact native metadata operation at the OLD
receiver count, then requires the complete native move's quantity conservation.
Only an existing opposite-region, nonparticipant receiving stack is eligible;
source, borrowed partners, protected production slots and legacy ACK overloads
remain strict. Invalid metadata, count loss/inflation, wrong item/quality,
unrelated edits, no-op replies and overlapping proof sets cannot advance it.

The offline full build passed **1,602 tests / 117 suites**, with zero failures,
errors or skips, including 15 new receiver regressions. Native installation and
repeat-race acceptance remain pending. The old failed ticket and its inventory
fence have not been cleared or relabeled as successful. Recovering the borrowed
hotbar item, then normally restarting the same instance, is a separate operation;
extended all-feature stability is not implied by the detached test result.

The bounded read-only trace records one continuous running interval from
02:45:40.414 UTC through the last running sample at 03:29:28.683 UTC:
**43 minutes 48.269 seconds**. The first paused/fenced sample is 03:29:29.682.
The last wine observation without a pending action is 03:28:52.662; consolidation
ticket 1502 begins at 03:28:54.661 with 124 members remaining. No progress after
that point is inferred from observer uptime.

Four actual sleeping intervals preceded the observed automatic day advances
468 to 469, 469 to 470, 470 to 471 and 471 to 472. Later day advances occurred
while running was false and are not counted as automation acceptance. This
trace shows continuous engine execution, but cannot independently prove that
no person interacted with the game during the interval.

### Paused-session custody diagnosis across a wine-clock rollover

A later read-only inspection explains why an exact historical scratch comparison
no longer passed: the borrowed torch remains exactly 62 items, and scratch still
contains one wine from vintage 13, but the native clock is now 14. Reproducing
the installed passive cache refresh on a detached known-vintage copy exactly
matches the current scratch wine. Ten current menu slots differ from the old
baseline; those unrelated current contents must not be restored to old values.

A separate, private custody procedure was prepared with an exact borrowed-item
check, known-vintage-only cache proof at its own current clock, a fresh full-menu
baseline and an exact new inverse-SWAP acknowledgement requirement. It does not
change the old failed action, its year or its acknowledgement state. The latest
read-only preflight refused because the game screen was open. **No recovery
click or restart was sent.** Native deployment and extended acceptance still
await a user handoff and successful current-state custody checks.

After the user handed over control, the current-state preflight passed. At
04:41:21 UTC a single independent inverse SWAP was sent; fresh full native
sequence 10783 matched all 46 current slots with only the two intended partners
exchanged and an empty cursor. The borrowed 62 torches returned to their hotbar
slot, all 100 wine remained present, and the old failed outcome/fence was left
untouched. This is a new custody-transfer confirmation, not a successful old
merge claim or a retry of its QUICK_MOVE.

The same Society instance then closed normally before its replacement started.
The tested `42A0F2918314C2712FC9D1E4527ED8611B84A16B48296A3CBEA2D25EF6A222FB`
artifact is now installed; previous JAR and profile/evidence backups are retained.
This installs both the receiver fix and nearby fruit cohort revision. Reconnection,
remaining-rack completion, native cohort acceptance and extended stability follow.

### Fractional support at the handoff position

The first resume and a coordinate-only exit attempt found no path from the raised
handoff platform; neither issued movement or inventory actions. A bounded native
read-only geometry probe found a normal five-edge route to ground. Exactly one
edge failed solely because the wine-keg surface is recessed by 1/16 block:
the actual one-cell descent is 1.0625, not 1.0. All ten native surface/body samples
and the final landing height passed when only that numeric limit was changed.

Walking, descent control and the retained navigation edge check now agree on the
1.0625 maximum, still requiring a single grid-height step. Native clearance,
loaded support, farmland protection, actual-height braking and no airborne input
remain unchanged. Fractional landings cannot borrow the integer-stair shortcut.
Full offline verification passed **1,610 tests / 118 suites**, zero failures,
errors or skips, including eight new boundary and landing regressions. The new
artifact SHA256 is `23D06B97707D18917C39A11A603BAD5D83726AD18B55865F868EAB49C616F6BD`.
This is geometry and detached-test evidence; native fractional descent acceptance
is still pending. Before installation the latest client observation was already
back on ground in continuous mode, so the original handoff position must not be
assumed to persist or recreated by teleportation.

The same client restarted normally with the tested fractional-support artifact
at 05:01:49 UTC and connected automatically. Continuous mode was confirmed at
05:04:29.953 UTC. Wine's persisted remaining set is 112; an unfinished working
member prevents starting a partial rack. Its completion was not fabricated, and
the scheduler yielded to other work instead of spending the cooldown in a
wine-only test.

On day 477 all 144 preserves jars were serviced. The inventory reached 144
preserves and then zero at the registered shipping destination. Retained native
outcomes 257, 258 and 259 confirm transfers of 64, 64 and 16 respectively; 256
confirmed the open container and 260 its close. The engine then began seed-maker
work. This proves the deposit, not a later payout or ownership match to the HUD.

### Exact proof for an idle keg's partial refill

The prior runtime's wine ticket 261 returned ordinary success while the same-grade
inventory fell by two and a keg became working. The installed KubeJS implementation
supports completing an idle keg with one or two inputs already present, but the
retained evidence cannot retrospectively prove that this was ticket 261's cause.
Its old result and remaining-batch membership have not been rewritten.

New idle-wine dispatches capture the specific target, prepared tomato hand, menu
slot, sequence and server-observation generation. Partial completion now requires
both the latest raw target working transition and a latest raw selected-slot/full
menu receipt proving exactly one or two identical tomatoes consumed. A different
target, tags, generation, stale/contradicting state, arbitrary inventory delta or
predicted menu cannot provide this proof. The opted-in attempt has no broad
block-or-inventory fallback. Mature collection/refill and preserves keep their
existing full-input rules; the module also rechecks the current target and state.

The full build passed **1,626 tests / 119 suites**, zero failures, errors or skips
(16 new regressions). SHA256:
`F17B15D0B02E7DEDC63D3C3DE3914E3B2BF17CCE2F5B8A0C02C6530B7B1329F2`.
The pure receipt tests cover the projected selected proof, not direct native
slot-versus-full-menu arbitration; that wrapper was code reviewed. Native partial
refill acceptance is pending. No existing production stage was manufactured to
force such a test, and this artifact is not yet installed.

### Center the verified source cell before an ascent

A coordinate-only test stopped before launching a step-up because the player
was about 0.496 blocks from the source center, outside the controller's 0.45
entry gate but still grounded in the correct source cell. The existing inner
centering branch was therefore unreachable. An ordinary coordinate move to that
same cell's center, followed by the original destination, succeeded without any
teleport or inventory action.

Preparation now permits reduced, observed-motion inward walking only in that
same native-verified full flat source cell. It still requires the original 0.06
launch center and two distinct quiet observations. Unknown motion, a tick gap,
source/support/authority loss, air, cell exit, cancellation or the original
60-tick timeout cannot launch. Flight, landing and single-pulse limits are unchanged.
Full verification passed **1,634 tests / 120 suites**, zero failures, errors or
skips, including eight new source-centering regressions. The combined artifact
SHA256 is `68B621FF5BF9E63A0EB19548F5C82D45732F8CC2814529A37C1B3353161DC620`.
Native source-centering acceptance is pending installation.

A separate nearby-fruit one-shot confirmed one fruit use (ticket 377), one
registered-store transfer of one fruit (379), and normal open/close (378/380).
This verifies a single-fruit pass, not yet multi-fruit cohort acceptance. Two
other ripe targets were not used; their ephemeral skip/confirmation state is
being inspected before restarting rather than inferred from one-second samples.

On day 478 a subsequent nearby pass, without any source change, collected four
fruit in sequence. Retained native use outcomes 381–384 succeeded; 385 opened the
registered store, 386 confirmed one transfer of **four fruit**, and 387 closed it.
The trace shows inventory 1, then 2, then 4 before that single deposit. The ripe
unregistered fruit remained untouched. This accepts the finite multi-fruit pass,
but does not by itself prove a suspended production/sleep origin was restored
because this particular test used one-shot mode.

The earlier one-fruit pass is consistent with its temporary approach position:
the other two fruit were 6.606 and 6.020 blocks away in a post-use trace sample.
The module rechecks its original cohort against the current six-block limit and
does not record a removed out-of-range member as a failed target. The precise
decision tick was not captured, so this is a supported explanation, not an exact
historical proof or a reason to expand the opportunistic harvesting radius.

### Combined artifact running again

The combined `68B621...` artifact was installed after normal shutdown of the old
client at 05:28:43 UTC. It connected automatically; continuous mode was confirmed
at **05:30:10.890 UTC**. The latest loop has no inventory action fence.

A two-minute high-rate ascent trace contained no outer-source-centering case, so
it cannot be presented as native acceptance of that new branch. Its first ascent
landed on the upper floor but timed out while waiting for a quiet centered landing
at tick 3365, 41 ticks after launch. It is not counted as successful. Navigation
then continued from the verified upper ground without relaunching that same edge;
six subsequent edges each had one launch and reached grounded controller release,
ultimately reaching the surface. The recovery worked, but the landing timeout
remains an observed limitation for later tuning.

Actual sleeping was observed at 05:33:28–05:33:32 UTC on day 478; day 479 then
resumed automatically. Ancient fruit ripened by day 479 and inventory reached
54. Native transfers 237 and 240 confirmed **52 + 2 fruit** into registered
storage, with opens/closes 236/238 and 239/241. The subsequent shipping transfer
243 confirmed **two pine tar**, with open 242 and close 244. These are retained
server acknowledgements, not inferences from an idle HUD or a later money value.

Multi-hour uninterrupted acceptance, completion of the remaining wine rack after
its real cooldown, and a native occurrence of the new partial-feed branch remain
pending. The current automation stays in continuous mode to continue those checks.

The previously ingredient-starved seed job retried after the harvest deposit.
At 05:37 UTC all four native artisan actions 281–284 succeeded. Withdrawal 278
confirmed 53 fruit, seed transfers 286 and 289 confirmed two each, and transfer
292 returned the remaining 41 fruit. The menus closed normally. Thus the job
resumed from temporary missing ingredients, produced/stored four seeds and
returned unused input without a restart or manually changing its deadlines.

### Day 480 continuous retry and crystal storage acceptance

The same client stayed continuously ON through its next automatic sleep and
day 480. Preserves service initially stopped approaching machine 123 after a
PREPARE ascent-alignment timeout, without switching the entire engine OFF.
Native shipping transfers 523 and 524 confirmed **64 + 58 preserves**. The
engine performed other work, then retried the remaining machines. Transfer 842
confirmed the final **22 preserves**, for **144 total**, with normal menu closes.
This accepts automatic deferred-work recovery, not a failure-free first pass.

The daily seed job again completed: input withdrawal 713 confirmed 41 ancient
fruit, artisan receipts 716–719 succeeded, storage receipt 721 confirmed four
seeds and 724 returned 29 unused fruit. Crystal-job artisan receipts 727–730
succeeded and storage receipt 732 confirmed **three jade**, followed by close
733. Its three registered machines were working again with next due day 485,
consistent with the installed five-day recipe. One-second inventory samples
did not capture every intermediate crystal count; the retained native transfer
provides the deposit count rather than inferring three from that sparse trace.

At 05:53 UTC the same continuous run was still ON, with no inventory action
fence. The wine batch retained 112 pending machines while their actual production
states were not yet all ready. No deadlines, inventory receipts or production
states were manually changed to accelerate that wait.

### Reduce source and landing alignment oscillation

The native preserves deferral retained a PREPARE timeout at tick 21034. Its final
pose was already within the launch radius; this single pose does not establish
the preceding motion. Source review found that inner arrivals still used full
walking pulses outside that radius, unlike outer arrivals. Detached regression
tests also reproduced a strong inherited-motion case that failed the original
60-tick preparation limit.

All off-center preparations now use consecutive measured motion to choose small
ordinary input or coast. Near the narrow launch center the input is reduced once
more. Actual observation gaps reset the quiet streak, and moving inner samples
are not counted as quiet. LAND uses a small last approach only when already
grounded, near the center and slow, after the existing coast/brake branches.
Support and authority proofs, allowed regions, two quiet observations, single
launch and original deadlines are unchanged.

The first targeted run retained the inherited-motion failure rather than weakening
its expected result. After reducing the near-source pulse, all **1,651 tests /
122 suites** passed, zero failures, errors or skips, including 17 new regressions.
The models cover multiple input responses, offsets, inertia, observation gaps,
permission/support changes, cancellation and the original timeout. These are
detached response models, not live performance measurements. Combined artifact
SHA256: `A220ED0B63B72320439F08E791DB1D863CF991F3EAD9CAB0A5DCDC6282E23D77`.
Installation and native acceptance of this latest alignment refinement are pending.

The artifact was installed after normal shutdown at 06:01:08 UTC. Quick Play
showed an unknown-host error; the same client's DNS/TCP check then succeeded and
an ordinary reconnect completed. No duplicate game or OS input was used.
Continuous mode was confirmed at **06:05:06.560 UTC** in client PID 8304.

The subsequent two-minute native alignment watch captured 14 distinct launch
pulses. Each had a preceding quiet grounded source sample followed by another
unchanged grounded position with quiet count two. Maximum launch-center error
was 0.059975 blocks. All 14 reached first native ground after nine ticks and
grounded controller release after 18–23 ticks. No ascent failure was retained.
Release means the last LAND quiet-one sample was followed by unchanged grounded
position with the controller absent; it is not an invented missing COMPLETE
sample.

The reduced inner PREPARE input occurred on 23 distinct ticks. The near-landing
0.2 input occurred three times, at observed center errors 0.121743, 0.125014 and
0.139411, each followed by grounded release six ticks later without another
full-strength input. PREPARE/LAND had no ordinary airborne input. Existing
authorized FLIGHT input is separate and was not counted as a violation.
This accepts the observed inner-source and near-landing refinements; no outer
source-centering case occurred in this trace, and multi-hour stability remains
pending. Automatic sleep and day-482 work continued on the new artifact.

### Native inverse-swap pickup race found and fixed

The next wine pass reduced the pending batch from 112 to 27 machines, then
client 8304 stopped at inventory consolidation ticket 435. It retained 84 wine;
this run is **not** a multi-hour stability acceptance. The failed primitive was
RESTORE, after two independently acknowledged primitives, not a failed MERGE
or a missing SLOT-only acknowledgement.

Retained FULL 2464 proved the merged state: borrowed torch 62 in the original
source and an empty scratch hotbar slot. RAW 2465 then delivered one wine to
the scratch. FULL 2466 showed torch 62 restored to its original hotbar slot
and the wine in the original source. The wine's raw identity was not initially
equal: the installed wine metadata initialization reproduced its final identity
exactly, at the same count and limit. Independent read-only comparisons proved
the complete 46-slot pickup-then-inverse permutation, empty cursor, exact borrowed
item return, and current live inventory equal to FULL 2466. No inverse was sent
again and the old failed outcome/fence was not overwritten.

The fix adds an explicit RESTORE-only opt-in. It requires the prior two ACKs,
an empty scratch in the pre-RESTORE baseline, exact borrowed item, a retained
same-connection FULL reply, and the latest raw scratch packet strictly between
dispatch and that FULL. The final pickup must match exactly or through the
existing exact native wine initialization proof. Every other slot and cursor
remains exact. The active and late-reply paths both use this check. Legacy ACKs,
MERGE conservation, in-flight rebase prohibition, deadlines and click counts
are unchanged.

All **1,669 tests / 124 suites** passed with zero failures, errors or skips,
including 18 new core/receipt regressions. Retained-FULL object provenance is
checked by the native wrapper and was code-reviewed; the detached helper tests
do not themselves constitute a native packet-origin integration test. Artifact
SHA256: `3A0A31C461ABF2732C92B58D661E3881E05F35EFF7391742BCC1A2547845B02D`.
Fresh exact physical-custody proof is required again before normal restart;
successful post-install wine completion is still pending.

The fresh 06:28:12 UTC proof again matched live inventory exactly to FULL 2466.
The old client then exited normally without any inverse, synthetic ACK or fence
write. Its failed ticket remained failed. The `3A0A31...` artifact was installed
at 06:28:44 UTC, client 45692 connected normally and continuous start was
confirmed at **06:31:00.581 UTC**.

The retained 84 wine were stored first: native transfers 16–18 confirmed
**1 + 64 + 19**, followed by close 19. All 27 pending wine machines then completed.
The global batch became inactive with zero remaining, latest feed day 483 and
next due day **489**, preserving the six-day whole-rack schedule. Native transfers
160 and 161 stored the final **1 + 26 wine**, followed by close 162. Thus **111
wine** were deposited across recovery and completion, without claiming any sale
or unverified money increase. The engine continued into preserves service.
This accepts the recovered production/storage sequence on the new artifact;
the observed successful consolidations alone do not prove which internal receipt
branch each used. Multi-hour uninterrupted stability is still pending.

### Profile checkpoint failure and bounded persistence retries

At approximately 06:34:17 UTC, the next preserves pass stopped with 124 preserves
in inventory. Native ticket 381 retained a successful server interaction, but
the profile checkpoint failed and the ledger rolled back to
`AWAITING_MACHINE_CONFIRMATION`. This is a persistence failure, not evidence of
a failed machine click. No native inventory/action fence was present. The old
client discarded the original I/O cause, so a Windows sharing violation is only
a possibility, not an established diagnosis.

A read-only detached validation passed with 712 POIs, 539 scheduled entries,
one pending output and 64 history entries. The profile was well below its size
limit. At 06:49:54 UTC one explicitly scoped invocation of the existing store's
normal save succeeded. The original error latch and pending output were left
unchanged. At 06:50:42 a fresh read-only comparison proved the saved profile
equal to the live profile. No action was repeated, no output phase was promoted,
and no successful transfer or recovery was inferred from the inventory count.

The persistence fix serializes and writes once, then permits at most two shared
additional attempts across backup copy, atomic backup replacement and atomic
primary replacement, with at most 20 ms of requested delay. Validation,
serialization, size errors and structural filesystem errors are not retried.
The old non-atomic fallback is removed. Temporary cleanup failure after a
successful commit is diagnostic only; it no longer reports a failed checkpoint.
Failure stage, exception class and original cause are retained without logging
profile contents. Ledger and automation start guards are unchanged.

All **1,678 tests / 125 suites** passed, zero failures, errors or skips, including
nine isolated persistence fault-injection tests. Artifact SHA256:
`1807DE7F1A68E8841C7DF73682B0D4324DFE59D906F2A80E9A00C0B305FF6746`.
Installation, actual shipment of the retained preserves and renewed continuous
testing are still pending. The paused observer's elapsed time is not continuous
automation uptime.

### Explicit shipment recovery without clearing the production ledger

A separate operator-selected recovery is available for exactly one pending
preserves entry. It freezes the current 36-slot inventory projection and ships
only its preserves lot to an existing registered smart shipping bin. It does not
infer that the historical machine operation succeeded, modify the pending entry,
change schedules, or automatically acknowledge recovery or loss.

The controller accepts only positive retained native transfer quantities, checks
the resulting frozen inventory delta, and requires the entire lot plus normal
container-close completion before reporting success. Close completion is the
client's normal menu returning to inventory, not a separately claimed server
close packet. Native shipment evidence remains the server-confirmed transfer.
An exact per-dispatch allow-list excludes machine use, other products, withdrawal,
sorting, disposal and unrelated jobs. Ordinary pending-output and ascent guards
remain unchanged. Cancellation, profile/session changes, unknown inventory
changes and bounded timeouts stop without replaying the action or clearing debt.

The UI exposes a separately confirmed **Ship current preserves** action; the
bounded local equivalent is `recover_pending_ship` with the selected canonical
UUID in `name`. A read-only bounded shipment report is included in diagnostics.
Any subsequent operator resolution remains a separate existing explicit action.

All **1,692 tests / 127 suites** passed with zero failures, errors or skips,
including 12 new recovery regressions and two command/stop-priority tests.
Artifact SHA256:
`64714008640EBF5812DCA37FCC783A9BBCD812C4B34C1935DDE41E621F12A6DA`.
This is detached test acceptance; live recovery and renewed continuous operation
are still pending.

The old client exited normally and this artifact was installed at 07:07:08 UTC
in client 51892. Quick Play again reported unknown host. The same client's
DNS/TCP check succeeded, followed by an ordinary in-client reconnect; no second
Minecraft or OS keyboard input was used. The original 124 preserves and pending
record survived restart.

Explicit shipment recovery was acknowledged at **07:09:08.169 UTC**. Native
transfer tickets **2 and 3 confirmed 64 + 60 preserves** into the registered
smart shipping bin; close action 4 completed and the normal inventory had zero
preserves. A fresh 07:09:34 read-only operator review independently compared the
controller's receipt list to retained native outcomes and verified the empty
remaining lot. The production ledger was still pending at that point.

Only after that actual shipment was reviewed, the existing explicit operator
resolution API was invoked once at 07:10:01 UTC. The saved profile records
`RECOVERED_AND_HANDLED`, retains the original `AWAITING_MACHINE_CONFIRMATION`
phase in history, and has zero pending entries. The old ticket 381 was not
rewritten, its machine was not clicked again, and no automatic historical
machine success was invented. This was supervised recovery, not unattended
self-recovery from an arbitrary persistence failure.

Continuous F8 mode was acknowledged at **07:10:17.720 UTC** and resumed normal
storage/harvest work. The wine batch remains due on day 489. Shipment is not a
claim that the later sale or its destination account balance was verified.
This accepts the live recovery path; a new multi-hour uninterrupted run still
needs observation.

On that uninterrupted restart, harvest/storage completed and the remaining
20 preserves machines were serviced around 07:13:55–07:14:13 UTC. All 144 jars
then reported working with no mature remainder. Native transfer **437 confirmed
20 preserves**, followed by close 438; the trace binds that transfer interval
to SHIPPING with inventory 20 before and zero afterward. Thus the interrupted
pass's **124 recovered + 20 newly produced = 144 preserves** reached the smart
shipping bin. The engine continued into ancient seed-maker work without another
start command. This short sequence does not yet establish multi-hour stability.

The same continuous run also serviced the three jade crystalariums. Native
artisan tickets 557–560 succeeded, transfer **562 confirmed three jade** stored,
and close 563 completed. Their next due day is **490**, consistent with the
installed five-day recipe. Sleep ticket **564** then reported `Entered bed`;
the native trace observed sleeping on day 485 at 07:17:27 UTC and normal work
on day 486 afterward, without another start request. The engine began the newly
due 124-jar preserves cohort. This accepts another real sleep/day transition,
not multi-hour stability or a claim that every seed maker had enough ingredients.

### Full activated starfruit patch: native one-shot accepted

The latest starfruit revision makes six blocks an activation condition only.
It freezes the selected registered patch's initially loaded ripe members and
does not discard its opposite-side fruit as the player moves. Other patches,
unregistered fruit, later ripening and later-loaded members remain outside that
pass. Native reach/visibility, safe hand, pickup space and bounded approach/use
checks remain unchanged. Day changes prevent new uses while an already submitted
fruit interaction still settles before storage.

All **1,695 tests / 127 suites** passed, including 54 starfruit-module tests.
Artifact SHA256:
`E2FA3510F8D1FE1F944CC5BD11CEBE5AA6C8E00E16618FCF59700040D217A3D0`.
After normal shutdown it was installed at 07:26:44 UTC in client 51508, which
reconnected in the same Society instance. The reviewed missing eighth fruit
registration was already saved without changing schedules or storage settings.

Before the **07:30:20.737 UTC** one-shot, six of the eight registered fruit were
mature and no fruit was held. Native tickets **1–6** confirmed all six fruit
interactions before the store opened. Transfer **8** confirmed **six fruit**
deposited, with open 7 and normal close 9. A fresh post-pass observation found
zero mature and zero held fruit. The added eighth registration participated;
the other initially unripe members were not forcibly harvested. Details and
scope qualifications are in [full-patch acceptance](STARFRUIT-FULL-PATCH-20260909.md).

Continuous F8 mode resumed at **07:31:52 UTC**. The native six-fruit result proves
the full-patch one-shot, not interruption and exact original-job resumption in
continuous mode. That combined branch remains covered by detached regressions
but still needs native acceptance. Multi-hour uninterrupted operation also
remains open; observer elapsed time, paused intervals and supervised recoveries
must not be counted as uninterrupted automation uptime.

The subsequent trace recorded manual pauses at **07:36:04** and **07:38:06 UTC**,
with a resume at **07:36:38 UTC** between them. The 07:38:50 review found the
client manually paused with no native action fence. These short interrupted
intervals are neither multi-hour acceptance nor evidence of a crash; automation
was not restarted over the active manual control.
