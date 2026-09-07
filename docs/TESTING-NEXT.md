# Unreleased validation after 0.1.2

This document describes development source, not the installed or published 0.1.2 JAR. Do not replace a published release asset with these changed classes under the old version number.

Java 17 / ForgeGradle `test build` passed: **337 tests, zero failures, errors or skipped tests**, including 79 logistics tests, 33 merge-planner tests, 12 native-snapshot transaction tests, 12 consolidation safety tests, 21 output-ledger tests and 8 scheduler/output integration tests. This is automated fixture coverage, not real-client execution of these new fixes.

## Manual demonstration evidence

Local journals from actual manual crop, wine and preserves work were analyzed without replaying them. Private journals, quantities, coordinates, routes and inventories are not published. The demonstrations verify the recorder's utility; they are not completed automation acceptance tests.

- Golden-hoe harvesting uses Quark's native 3-by-3 harvest area in the installed configuration. Main/off-hand fallback packets, unripe clicks and incidental travel must not become additional automation actions. Continuous moving harvest remains a separate implementation/verification task.
- Machine harvest and refill normally share one main-hand tomato use. Installed Society 4.1.4 scripts require three tomatoes for wine, five for normal preserves jars and three for upgraded jars. A below-cost held stack can collect a mature output without refilling: such a later refill is not a redundant working-machine click. The existing held-stack cost guard must remain.
- Full player inventories cause product pickup delays during manual bulk production. The journals do not record ground entities or raw NBT, so exact falling/merging behavior cannot be inferred from inventory timing alone. Reserve a real output slot for automated machine collection.
- Shop, bank, unrelated inventory rearrangement and mistaken block uses are excluded. Recording a purchase does not authorize automated spending. The 0.1.2 pose does not contain health/hunger/stamina or air-use events; these demonstrations do not verify stamina recovery.

## Changes under test

- Pause an unfunded one-shot refill instead of reporting completion or scheduling the unfunded target for tomorrow. Replenishment can retry on the same day; confirmed refills retain their ordinary game-day deadline.
- Approach machine interaction faces within a four-block limit, with the existing native reach and line-of-sight guard still authoritative. Source-container approach distances are unchanged.
- Give dropped output a short settling interval, then navigate only to an observed product on verified standing terrain. Temporarily unreachable or falling outputs continue bounded waiting without another machine click. Partial-height support requires matching observed item height, not a guessed floor at rack height.
- Existing same-product ground items prevent a new mature-machine collection. This avoids deliberately collecting an old bottle as confirmation of the new output.
- Write-ahead output obligations survive pause, reset, feature changes and reconnect. Pickup confirmation is persisted before subsequent consumers run; manual inventory interactions invalidate live count evidence and do not clear the durable obligation. Explicit per-item recovery/loss confirmation requires two UI steps and remains paused.
- Profile schema 2 prevents older clients from silently ignoring unresolved output. Valid schema 1 profiles migrate in memory without rewriting the original until an actual save; malformed or unknown schemas remain preserved and blocked.
- Batch ingredient hauls fund remaining eligible machines while reserving two real output slots. Hauls are capped near a grade-priority crossover so a soon-to-be superseded grade cannot monopolize spare slots. Every source reply refreshes stock and grade selection; normal/upgraded costs, limited source stacks, multiple sources and bounded final-stack overfetch are covered.
- Cursor-free native inventory consolidation combines leftover tomato fragments and already-confirmed products. Direct merges, bounded scratch swaps and tomato-only hotbar repositioning are separate from storage/sales. Native tag/capacity checks, exact changed-ACK conservation and live-state checks precede continuation. Cancellation never sends cleanup; unresolved replies block all new actions. A refused/no-op click can require reconnection rather than an automatic retry.
- Large synthetic logistics fixtures cover 144 normal jars and 384 kegs. The latter uses a late-tagging pickup fixture that initially consumes a free slot per bottle and only merges through explicit consolidation actions; it is not a claim of real-client network or native-tag acceptance.
- The first product-consolidation plan prefers an actual newly received/increased product slot, with wine cohort matching the resolved output obligation. Very tight multi-grade inventories can still pause safely; fill-only jobs never add automatic storage/sales to force completion.

## Still open before unattended acceptance

The earlier refill-deadline/reset gap is covered by the durable ledger and scheduler regression tests. Real-client installation and failure/recovery acceptance remain unverified for this development build.

Exact destination classifications, route registration, continuous area-safe moving harvest and a complete real-client production-to-delivery cycle remain open. Bulk material hauling and native-ACK inventory consolidation now have code and fixture coverage, but still require real-client acceptance, including hidden server tags, cancellation/late-reply timing and interrupted layouts. Native Vinery assigns Year/effect tags after a fresh output enters inventory; automated fixture success does not prove that a large real keg sweep preserves free slots. These development changes have not been installed into the user's running game.
