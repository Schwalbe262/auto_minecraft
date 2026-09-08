# Additional workflow stabilization checkpoint

This checkpoint continues the full stair / ancient-fruit / seed / crystal /
starfruit objective. It is not completion of native execution or long-running
acceptance. No new artifact, registration import or gameplay command has been
applied during these checks.

## Stair-to-platform boundary

The controller now selects the longest independently verified stair prefix from
the existing at-most-four-node preview. A later ordinary platform no longer
invalidates that prefix. On a real full-tread landing, it may continue onto the
ordinary suffix only through the existing native continuation and conservative
handoff checks, including the original 0.12 speed ceiling. It does not export
the faster stair-corridor speed into an unverified edge.

Synthetic timings (previous flow → updated flow): three stairs to a solid
platform 74→70 ticks, six 104→90, eight 120→106, twelve 150→136. A six-step run
ending on an actual stair remains 74 ticks versus the original non-flow 123.
These results are not live measurements and do not establish the requested 2×
speed gain. Final landing, center reserve, two quiet samples, and no airborne
input remain mandatory.

## Generic crop discovery

Execution already shared the harvest module, but native candidate scanning and
the settings filter still excluded ancient fruit. The scanner now includes native
crop blocks and explicit crop-definition IDs. The UI groups connected rows by
crop ID, retains that ID in the draft, labels it for confirmation, and does not
merge touching different crop types. Custom IDs and ambiguous definitions have
regression coverage. Suggestions still require review and keep partial-chunk
warnings; discovery never writes a registration or resets a schedule.

Local diagnostics now expose crop-specific field suggestions and counts for
commodity stores, artisan jobs and fruit patches. Legacy tomato cluster fields
remain available. The read-only scan also recognizes seed makers, crystalariums
and starfruit.

## Installed artisan edge cases

Installed seed-maker logic permits an idle partially filled batch. The automation
continues to require at least three fruits in hand, but a full raw working-state
and selected-slot acknowledgement can prove completion with a decrease of one
through three fruits. A previously mature machine resets the stage on collection
and still requires exactly three. An unchanged slot or working-state prediction
does not count as confirmation.

A previously mature **and upgraded** seed maker may produce two seeds, not just
one. An upgraded mature crystalarium may additionally produce one pristine jade;
only that exact item/count is an allowed replacement for the last consumed input.
The pristine bonus is left in inventory, not automatically sold or granted new
storage authority. No bonus allowance applies to an ordinary machine.

Both machine definitions leave KubeJS block-entity synchronization disabled.
Read-only inspection of all seven registered candidates confirmed `sync=false`
and absent recipe/stage data. The client therefore cannot treat local zero/default
values as server evidence. A foreign partial recipe rejected by the server keeps
the target-specific uncertainty fence; the click is not replayed.

## Exact outward SWAP with a simultaneous pickup

Fresh retained native evidence from the old running client identified a stopped
inventory transaction: three tomatoes moved from a main slot to an empty hotbar
slot exactly, while one wine arrived in the newly emptied source. The full server
reply changed only those two inventory slots and had an empty cursor. The former
verifier rejected any source change outside an exact empty-slot SWAP.

The new exception is limited to an outward SWAP whose scratch was actually empty,
with no borrowed-item restoration obligation. The moved stack must reach the
scratch with exact native identity, count and limit. The vacated source may then
contain an independently whitelisted positive pickup of a different native
identity, proved by the same genuine full reply. Partial/wrong moves, same-identity
extra copies, arbitrary items, occupied scratch, passive-only evidence and
unrelated losses remain rejected. The actual pickup becomes the next baseline;
the subsequent QUICK_MOVE must conserve the tomatoes and preserve the wine.

No live fence was cleared, no live transaction acknowledged, and no click resent
by the diagnostic probes. The currently running old verifier has not been patched
in memory. Restart / native receipt acceptance and continuous operation remain
required before this issue can be reported resolved in the game.

## Logging search follow-up

The original logging stop remains an exhausted search, not an established JVM
crash. Read-only review found no matching crash report; the old running artifact
does not contain the bounded visibility preflight. The latest retained navigation
failure instead belongs to a preserves-machine one-block ascent. Its controller
now says ordinary ascent rather than logging ascent, without changing motion,
timeout or retry rules. A single final pose cannot establish the cause of that
alignment timeout.

Logging outline checks now reject genuinely out-of-range target bounds without
native rays, stop boolean visibility on the first verified hit, and avoid repeating
four actual-eye checks on every stopped preflight tick. Invalid eyes are rejected
before the ordinary native ray path as well. Query limits are now named accurately:
a visibility query may contain multiple outline rays, and the two-millisecond
slice is soft. Passable foliage does not grant permission to ignore a blocking
OUTLINE hit. See [logging visibility recovery](LOGGING-VISIBILITY.md).

## Combined verification

Offline Java 17 / ForgeGradle `test build` passed **1,403 tests in 107 suites**,
with zero failures, errors or skips. This includes the twelve outward-SWAP pickup
cases, generic crop registration, partial/upgraded artisan receipts, stair-prefix
handoffs, logging visibility costs and twenty paired ascent-label cases.

Candidate SHA-256:
`DCCBB82496F31895AAFD29744ADCFA6A0A7CC4D539A2F2190CB1E1FF098AF41D`.
It is built, not installed. No live inventory fence was cleared, restart or work
command sent, registration imported, or profile changed during this checkpoint.
Real execution and continuous multi-cycle acceptance remain open.

A second private read-only ancient-field observation closed the missing boundary:
all 27 observed plants, including immature ones, have native footprints inside the
revised draft. Existing registrations did not conflict with that observed mask.
The original draft was preserved, and the revised draft still enables no features.
Neither draft has been applied to the live profile; geometry readiness is not a
harvest acknowledgement or a future guarantee.
