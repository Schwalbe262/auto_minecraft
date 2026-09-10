# Independent orchard routine — 2026-09-10

## Delivered behavior

The saved `STARFRUIT` feature is now displayed as **Orchard harvest (starfruit)** / **과수원 수확 (스타프루트)**. The existing toggle, one-shot entry, fruit masks and commodity stores are retained. Starfruit is currently the supported species; the explicit patch-and-store model can be extended without treating future orchards as travel detours.

The routine runs at priority 80 after production and before ordinary sleep. It does not interrupt another busy job. It considers every due registered patch regardless of player distance, harvests only native ripe fruit, and finishes each patch's storage before starting the next. A one-shot covers the due patch queue, not merely one nearby tree.

Clean inspection/storage saves `orchard:<patch-id>` for the next game day. This daily inspection interval is not a claim about the fruit's native growth period. Already loaded, known immature fruits can be checked without a needless journey. Unloaded registered fruits are approached for observation; they are not treated as absent and no unregistered forest is scanned or harvested.

## Safety and progress

- Only native successful fruit use followed by the same fruit's valid age reset confirms a harvest. Inventory growth alone does not establish an acknowledged use or a cleanup destination.
- Every store remains the frozen registered commodity destination. Actual storage actions and container closing retain their own acknowledgements.
- Unknown/malformed fruit observations, changed registration or store identity, unrelated busy actions and cursor changes do not authorize further harvesting.
- Each approach has a 2,400-actual-tick bound. Incomplete targets/patches retain scoped 1,200-tick retry backoff, not a fabricated daily completion. Several incomplete patches cannot keep reacquiring the scheduler ahead of sleep.
- A long route's navigator-owned door reply is awaited without treating it as an unrelated action or forgetting it on a maturity/day/registration change. `pendingInteractionOutcome` is read-only, context-scoped and does not authorize movement or another click.
- Completed daily state is checkpointed with rollback on save failure. Same-day confirmed fruit evidence survives a module reset; no forced maturation, ground-item counting or synthetic acknowledgement was added.

## Verification

Java 17, offline Gradle `test build`: **2,062 tests across 152 suites, zero failures/errors/skips**. Coverage includes distant and unloaded fruit, multiple patches/stores, no production preemption, daily inspection, restart-style resets, checkpoint rollback, fruit/storage/door receipts, registration edits and sleep progress. The first full run exposed an invalid uppercase store ID in a new test fixture; it was corrected to a validated ID before the successful rerun. Production safeguards were not weakened to accommodate the fixture.

Artifact: `autovalley-0.1.3-SNAPSHOT.jar`

SHA-256: `9E7E409872E90A8B6305556094DA15133E5EE1E16D769A9702B4E66F4335DB32`

This build also contains the previously committed fixed-six-day wine pass and stair-hit recovery patches.

## Deployment checkpoint

The existing Society instance was normally stopped after a planned pause, with no pending machine-output obligations or borrowed logging hotbar lease recorded. The old mod JAR was backed up locally, the verified new artifact was installed, and the same modpack was relaunched with its existing connection workflow. Server reconnection and live routine checks were still in progress at this documentation checkpoint.

Private profiles, traces, registration coordinates, server/account identifiers and launch credentials are not included.
