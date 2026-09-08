# Unreleased validation after 0.1.2

This document describes development source and a limited real-client smoke test of 0.1.3-SNAPSHOT, not the published 0.1.2 JAR. Do not replace a published release asset with these changed classes under the old version number.

Later dated sections supersede earlier behavior and acceptance notes. In particular,
wine pickup tracking is now disabled at the user's request; preserves tracking remains.

Java 17 / ForgeGradle `test build` passed: **411 tests, zero failures, errors or skipped tests**, including 95 logistics tests, 39 navigation/harvest tests, 18 harvest-safety tests, 4 native-area geometry tests, 7 area-route tests, 8 movement-rule/axis tests, 13 emergency-key/start-priority tests, 33 merge-planner tests, 12 native-snapshot transaction tests, 12 consolidation safety tests, 21 output-ledger tests and 8 scheduler/output integration tests. These numbers describe fixture coverage; limited real-client checks are described separately below.

## Manual demonstration evidence

Local journals from actual manual crop, wine and preserves work were analyzed without replaying them. Private journals, quantities, coordinates, routes and inventories are not published. The demonstrations verify the recorder's utility; they are not completed automation acceptance tests.

- Golden-hoe harvesting uses Quark's native 3-by-3 harvest area in the installed configuration. Main/off-hand fallback packets, unripe clicks and incidental travel must not become additional automation actions. Bounded moving harvest now has implementation and fixture coverage; live verification remains open.
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
- Validate every nonempty source-container slot before accepting a tomato stock snapshot. Non-tomato items and invalid/mismatched grades stop production before withdrawal, even if held ingredients would already fund a recipe. Empty slots and unrelated player-inventory tools remain valid. Reopening a previously counted source repeats the check; a successful earlier scan is not permission to withdraw from a subsequently contaminated barrel.
- Cursor-free native inventory consolidation combines leftover tomato fragments and already-confirmed products. Direct merges, bounded scratch swaps and tomato-only hotbar repositioning are separate from storage/sales. Native tag/capacity checks, exact changed-ACK conservation and live-state checks precede continuation. Cancellation never sends cleanup; unresolved replies block all new actions. A refused/no-op click can require reconnection rather than an automatic retry.
- Large synthetic logistics fixtures cover 144 normal jars and 384 kegs. The latter uses a late-tagging pickup fixture that initially consumes a free slot per bottle and only merges through explicit consolidation actions; it is not a claim of real-client network or native-tag acceptance.
- The first product-consolidation plan prefers an actual newly received/increased product slot, with wine cohort matching the resolved output obligation. Very tight multi-grade inventories can still pause safely; fill-only jobs never add automatic storage/sales to force completion.
- Area-first harvest ordering retains every original crop as a fallback. Only observed maturity changes skip covered crops. Native inspection checks potentially interactive plants within the configured native range and the upper fallback layer; all such plants must be registered tomatoes. Unknown/unloaded footprints block the click. The earlier maximum-9-by-9 check was corrected after live preflight found unrelated plants outside the demonstrated golden hoe's 3-by-3 area falsely blocking harvest.
- Magnet harvest can overlap movement with a single outstanding harvest use for at most ten ticks. Movement follows the planned world heading while the camera remains aimed at the clicked crop. Continuation cannot open doors or issue another use; long replies stop movement. Calibration and adapters without this capability keep the stopped behavior. These tests do not establish real-client speed, stamina use or network acceptance.
- Emergency stop is handled before GUI key consumption, with mouse-bound stop support even when another handler canceled the mouse event. Stop wins over queued toggles and consumes/rejects existing external start/once requests during its short priority window. A locked/unreadable or non-regular request keeps the start fence active until confirmed consumption or absence; it cannot become a delayed restart after the tick window. Actual Forge GUI dispatch and a server-delayed inventory reply still require client acceptance; the pure latch/gate tests are not substitutes.
- One surplus-sale run checks every initially held wine cohort. A known not-full reserve retains that cohort and allows the next cohort to be checked; unknown age, unloaded reserves and invalid contents still stop the run. Fresh permissions and confirmed shipping increments are required separately for each cohort. The completion summary distinguishes sold and retained bottles, and continuous mode yields to lower-priority work after a completed sweep.

## Harvest model boundary

The native inspector supports installed Society 4.1.4 / Quark 4.0-462 default harvest rules with matching client/server range settings, not arbitrary server-only range changes, harvest mappings or third-party event handlers. Native Zeta flags do not synchronize numeric ranges or crop lists. The manual golden-hoe demonstration matches the installed 3-by-3 range: recorded post-use age-zero changes stayed inside that area, and nearby subsequent targets outside it remained mature. These observations are not raw per-packet proof of every neighbor or a guarantee after future server configuration changes. A known footprint identifies the supported model, not every possible server configuration. Revalidate the range if the server settings change.

The within-range plant check can reject an edge crop because another crop or an upper vine lies outside the registered tomato mask. That is a registration/review condition, not permission to expand the farm automatically. Plants outside the actual range and inert building blocks that are not potential native crop targets do not require farm registration.

## Still open before unattended acceptance

The earlier refill-deadline/reset gap is covered by the durable ledger and scheduler regression tests. Installation and limited passive runtime checks passed as described below; real-client production and interrupted-output recovery remain unverified.

Exact destination classifications, route registration, live moving-harvest verification and a complete real-client production-to-delivery cycle remain open. Bulk material hauling and native-ACK inventory consolidation now have code and fixture coverage, but still require real-client acceptance, including hidden server tags, cancellation/late-reply timing and interrupted layouts. Native Vinery assigns Year/effect tags after a fresh output enters inventory; automated fixture success does not prove that a large real keg sweep preserves free slots. Installation is not acceptance of those workflows.

## Limited real-client smoke test — 2026-09-07

- Normally closed the existing Society 4.1.4 client, backed up its local profile and old Auto Valley JAR, and loaded 0.1.3-SNAPSHOT in the same instance. Exactly one Society game process remained. The initial smoke-test build had SHA-256 `88A90BFC67AB839663C3BB64DFE3F759DB187EFC169398F46C8F1DF3AF1048AD`; it was subsequently replaced by the separately tested area-correction build below. These are unpublished development snapshots, not replacements for a published release asset.
- Initial Quick Play failed. A probe in the same JVM then confirmed address resolution and TCP reachability; one normal in-client rejoin succeeded. A fresh snapshot confirmed an actual world connection, automation OFF, a non-null native wine calendar, and unchanged registered farms/destinations. This does not establish the initial Quick Play failure's cause.
- Passive recording started, refused a one-shot request while capturing, and saved a Korean-named UTF-8 journal successfully. The journal contained only recording lifecycle, state/inventory/menu observations and clock events, not a demonstrated interaction route. Recording and automation were OFF afterward.
- With recording stopped, an explicit wine one-shot request was refused because wine machines were not registered. The result acknowledged receipt but reported no execution, and the runtime reported the missing registration. No registration or production operation was performed by this smoke test.
- The initial read-only native probe passed one representative crop, but a complete registered-farm scan found targets blocked by unrelated plants outside the configured golden-hoe area. The maximum-9-by-9 footprint was over-conservative, so the implementation and geometry tests were corrected to use the configured range. The probes sent no game input, packets, screen changes, profile writes or interactions. Private probe source and reports are not published.
- Rebuilt and reloaded the correction in the same instance. Installed JAR SHA-256 matched `9D3D20C8BF0F7DF25A75451BDF34DD53921278F4B4AE3882AC8F21ED9608CB8C`. The game reconnected with automation OFF; the profile file remained byte-for-byte unchanged from the original backup. A DNS/TCP probe also ran during this launch; that does not prove why the earlier Quick Play attempt failed.
- Final preflight used the actual configured inventory hoe through the production adapter's read-only `inspectTool` path without changing the selected slot or stack. A separate read-only context supplied that footprint to the real safety classifier. Every observed tomato in the currently registered farm was checked: no skipped targets, unknown areas, unloaded registered cells or safety rejections remained, and every inspected half-span was one. This validates the current range/boundary preflight, not native harvest dispatch, movement, pickup or future server configuration changes.

Actual GUI emergency-key dispatch, moving harvest, bulk machine collection, native inventory consolidation and output-loss recovery still require controlled real-client tests. Unregistered warehouse classifications and route candidates have not been inferred into the live profile.

## Non-interfering readiness checks — 2026-09-08

The operator resumed manual play. No automated movement, screen dismissal, container opening, transfer, production, sale, profile registration or game restart was performed during these checks. The new source-validation build is prepared locally, not installed into the running client.

- Extracted only continuous work-related transit from the manual demonstrations. Source-to-jar and wine-to-shipping segments share an observed staircase cell, allowing a continuous storage-to-yard candidate without adding shop or bank travel. Record provenance was checked locally; private routes and reports are not published.
- Constructed a separate preview profile, preserving the live runtime profile. Candidate tomato containers and preserves jars matched their loaded live block IDs. This does **not** establish their current contents or grades: those still require synchronized menu inspection before transfer.
- The production pathfinder found a no-jump approach to each candidate tomato source, plus outward and return paths between storage and a demonstrated jar. This checks current collision/step-height geometry, not actual movement reliability, door dispatch, network acknowledgements or every production-machine approach.
- A bounded, read-only observer recorded inventory/product totals, loaded machine states, runtime progress and the synchronized bank-meter balance without performing game actions. Balance changes during manual play are not attributed to automation. The smart-bin payout account was not authoritatively matched, so the observer does not claim earnings.
- Installed pack scripts distinguish smart and basic shipping. Smart shipping processes on a loaded server-tick interval; basic shipping processes at dawn. Account/card routing, debt repayment, cash fallback and the bank meter's refresh delay mean that deposit acknowledgement alone cannot prove a particular HUD balance increase.

Those earlier passive checks were not end-to-end automation acceptance; unchanged inventory and money at that stage were not a sales-only failure.

## Inventory disposal and configuration update — 2026-09-08

- The user subsequently confirmed that real automated tomato harvesting and storage worked. A later live observation found no remaining inventory/nearby-ground harvest but a stale ephemeral rotten-tomato haul record after manual disposal. Neither observation establishes production, sales, or sleep acceptance.
- TrashSlot 15.1.3 integration sends its native single-slot deletion request, never delete-all, cursor deletion, or an OS input. Only a matching normal-inventory rotten-tomato stack is permitted. A non-rotten recovery-buffer item prevents overwriting that buffer. Confirmed deletion updates the harvest ledger; missing, cancelled, or late replies cannot cause automatic resends.
- Full server-menu replies remain separate from single-slot server evidence plus a detached post-application native menu. Exact source deletion, native preservation of every other stack, and an empty cursor are required. A projected count change alone is insufficient.
- Explicit manual-haul confirmation remains paused and refuses visible harvest, occupied cursor/container, or a separate durable machine-output obligation. It records operator reconciliation, not successful automatic delivery. Merely losing sight of an item still does not settle a haul.
- Named machine groups preserve exact registered members, individual deadlines, and durable output records. Legacy registrations collapse visually without expanding permissions. Registration removal requires confirmation and does not destroy blocks.
- Desired tomato grades are separate from actual stocked grades. Production still selects the largest actual grade total, prioritizing old-layout stock within that grade. Only a verified empty barrel changes classification; incompatible or unrelated contents are left untouched. Failed persistence rolls back the metadata and prevents transfer.
- Java 17 `test build` passed with 482 tests; the subsequent test run including the additional group/profile tests passed **496 tests, zero failures, errors, or skips**. These are fixture results, not real-game acceptance of all new actions.
- The existing Society instance was normally closed and reloaded with snapshot SHA-256 `C17B1252DFB9776A77B4114D9E821F292A25902295AF2D00A272DA4AC2E476C9`. The initial Quick Play attempt failed. A same-client normal join then returned an explicit invalid-session login error despite successful DNS/TCP checks. No new production action or warehouse installation was performed while disconnected; authenticated reconnection is required to continue the real workflow test.

## Follow-up workflow and requested stop-policy changes — 2026-09-08

- The operator reconnected successfully. In that connected session the native
  storage survey completed all 48 registered tomato/wine containers. Native
  TrashSlot acknowledgements confirmed deletion of 56 rotten tomatoes in five
  single-stack operations. Neither observation is an inference from disappearing
  items or a claim that all production/sales/sleep stages passed.
- A real wine one-shot collected 24 bottles and restarted 24 kegs before a later
  use was rejected by the reach/line-of-sight preflight. The previous write-ahead
  output record then blocked resuming, even though that request had not reached
  native use dispatch. The full rack was not completed in that run.
- At the user's explicit request, wine no longer creates a pickup obligation,
  waits for a counted bottle pickup, or chases ground bottles. Existing wine
  obligations are archived as `WINE_PICKUP_TRACKING_DISABLED`, not falsely marked
  recovered or lost. Preserves obligations, native action/input/state confirmation,
  reserve-wine classification and surplus-sale authorization remain separate.
- Immediately before a machine use, release movement, settle for two ticks and
  recheck actual native reach/line of sight. Reapproach before creating an output
  obligation if this check fails; do not lower the four-block limit for tall racks.
- An initial twenty-tick inventory-capacity rule released stale magnet counts.
  The user subsequently rejected magnet-count gating altogether: remove those
  counts from harvest/production/storage/sleep start and completion conditions.
  Actual native inventory transfers remain acknowledged separately; no magnet
  count is a prerequisite for proceeding and no ground absence proves a sale.
- The user's later request to drop fixed-grade tomato storage and add general
  storage groups is deferred. The private desired-layout map was disabled to
  allow the existing registered same-grade stores to continue working meanwhile.
  No stored item was moved by that metadata change.

- Java 17 `test build` passed **520 tests, zero failures, errors or skips**.
  The new snapshot has SHA-256
  `1D05AC8B385F46CCCD8C53C26843A50D29BFAE0E1C0CFAC941C6443F93899414`.
  The same existing Society instance was normally restarted to load that JAR;
  the previous JAR and private profile were backed up locally.

Routing, click-speed improvements and map waypoint integration are tracked in
[Deferred work](DEFERRED-WORK.md). Live acceptance of the new stop-policy build
and the remaining production-to-storage/sale/sleep flow is still pending.

## Count-free harvesting and smart-bin correction — 2026-09-08

- Removed the transport counter, recovery API/command/UI, capacity-settling
  policy and their obsolete tests entirely. The production and test source tree
  no longer contains those feature names. `continueHarvestWhenFull` is now the
  ordinary opt-in setting; native use confirmation and changed crop state finish
  a harvest without reading ground items. Inventory transfer/deletion checks remain.
- An approach whose predicted ray differs from the stopped player's actual ray
  now settles before failure and can try at most four alternative safe endpoints.
  The actual native ray, same reach, registered bounds and collision checks remain
  authoritative. This is a correctness fix, not the deferred route optimization.
- The operator's sale recording confirms a smart-bin menu with 54 storage slots
  and 36 player slots, and a successful quick-move of pine tar into storage slot 0.
  Earlier automation failed before opening the bin: the scripted block entity is
  not itself a native Container/MenuProvider. No automated quick-move or earning
  occurred in that failed attempt.
- A narrow optional KubeJS bridge recognizes only the exact installed smart-bin
  ID, entity/attachment types, owner identity and 54-slot capacity. It reads no
  closed inventory contents. The subsequent full menu must match the 54+36 slot
  layout before any transfer. Other scripted blocks, shops or banks are not admitted.
- Smart-bin deposit and later server processing are distinct. Installed scripts
  process eligible inventory periodically; a completed deposit is not proof of an
  immediate HUD balance change.
- Java 17 `test build` passed **516 tests, zero failures, errors or skips**. Removed
  counter tests explain the lower total; nine smart-bin tests were added. Snapshot
  SHA-256: `F389933632ADDE969CDEB51F7C68E47F04FAB619A81B626EC9B6642F6F94EDBE`.
  The same Society instance was normally restarted with the backed-up replacement.
  Full live wine/preserves/storage/sale/sleep acceptance remains open.

## Production-hand refill and interrupted consolidation — 2026-09-08

- Production now prepares another ingredient stack before the last recipe would
  empty its hand while further work and ingredients remain. A final exact-size
  recipe is still allowed; this is not an extra stock requirement. Progress text
  distinguishes the complete source-stock survey, withdrawal, and machine work
  with counters, so surveying storage is not mislabeled as servicing a keg.
- Java 17 `test build` passed **525 tests, zero failures, errors or skips** for
  that change. This build was not installed while the operator resumed manual play.
- A prior live wine run confirmed four additional collection/refill interactions,
  then stopped during output-stack consolidation. A private read-only examination
  of retained native replies proved that a SWAP had moved wine into the ingredient
  hotbar slot, before its inverse restoration. Tomatoes had not been exhausted.
  An unchanged-count wine in another inventory slot also changed native metadata
  in the reply, causing the strict whole-inventory acknowledgement to reject it.
- A subsequent keys/types-only inspection identified the passive change as an
  initially untagged `vinery:stal_wine` receiving integer `Year`, `EffectAmplifier`
  and `EffectDuration` fields. Installed Vinery code initializes those fields on
  inventory ticks. No private tag values were exported, and no passive metadata
  change was treated as proof that an inventory click succeeded.
- Output consolidation now protects both the configured hoe and the ingredient
  hand from source/destination/scratch selection, including implicit native
  QUICK_MOVE fallback. It prefers a separate empty scratch; a suitable occupied
  scratch requires exact restoration. No safe scratch skips optional merging,
  not machine work. Independent planner/safety/logistics regression runs passed
  **163 tests**, including a full-hotbar three-ACK restore with 52 ingredients
  unchanged at every step. Full integrated build results are recorded separately.
- No failed click was resent and no unresolved acknowledgement was manually
  cleared. The operator continued manual play while the cause was inspected.
  Full rack completion, preserves, automated delivery and sleep are still unverified.
- The passive-metadata exception now requires the installed Vinery initialization
  or refresh function's exact result on a detached stack, in the same native wine
  calendar year. Only unchanged-count nonparticipant wine slots qualify. Existing
  cohort, quality, capabilities and unrelated tags remain exact; metadata-only
  replies do not advance a click. Participants and count-changing receivers still
  require exact identities and conserved quantities. This does not cover a wine
  that initializes and simultaneously becomes a transfer participant.
- Integrated Java 17 `test build` passed **550 tests, zero failures, errors or
  skips**. Snapshot SHA-256:
  `158E705D74DF5BC75ABBAEEC7576DF0DDDA4CE7D8DEED7BD8BDB1747A8993971`.
  These are build/fixture results; real-client acceptance follows separately.

## Tomato commodity-only storage correction — 2026-09-08

- The hotbar/metadata build reconnected successfully in the same Society instance.
  Wine production then stopped during source inspection, before ingredient
  withdrawal: an ordinary barrel's actual normal-grade tomatoes differed from its
  obsolete highest-grade registration. This run did not validate the repaired
  output-consolidation path because it never reached production.
- At the user's renewed explicit request, tomato storage now uses commodity
  identity only. Legacy grade labels and desired-layout maps do not select or
  reject tomato sources/destinations. Mixed tomato grades may share one storage
  container; non-tomato contents are not admitted for automatic deposit/withdrawal.
- Production still counts the actual grades separately across synchronized
  storage contents and held inventory, chooses the largest total, and withdraws
  only that selected grade. Reopening a source refreshes its actual contents.
  This does not rewrite item qualities or combine incompatible native stacks.
- Ordinary native container transfer fills compatible partial stacks in the
  opened container before empty slots. Cross-container compaction and generic
  named commodity groups remain deferred; no global packing claim is made.
- Wine production-cohort registration, reserve capacity and surplus-sale rules
  are unchanged. Tomato registration no longer asks for a grade, and surveys
  retain per-grade observations without using them as warehouse restrictions.
- Integrated Java 17 `test build` passed **569 tests, zero failures, errors or
  skips** for the commodity-only correction. Snapshot SHA-256:
  `67C75F3E1B74E5CFE5F973074E5D1B4102832A78498D7ADF01AFB97929FBFB41`.
  This build was not installed; the operator requested batch-level stock
  selection before the next restart.
- Meanwhile, the still-installed hotbar/metadata build resumed in the real game.
  A fresh observation showed 102 carried wines; retained receipts included 87
  successful native inventory consolidations and continued machine interactions,
  with no consolidation failure fence. This confirms progress beyond the earlier
  four-bottle failure, not completion of the whole rack or sales.

## Production batch and visit-order correction — 2026-09-08

- A carried tomato batch is consumed before another warehouse count. Elapsed
  ticks, a calendar change while carrying usable ingredients, and another grade
  overtaking the selected total do not trigger a stock-balancing trip. Only a
  needed refill counts all registered sources and allocates the largest grade.
  Sources are still freshly synchronized before each native withdrawal.
- Touching same-kind storage containers form visit components: finish one wall
  before choosing the next wall from the last visited position. No inventory
  contents, access permissions or registered members are inferred from adjacency.
- At each completed machine boundary, prefer currently reachable machines, then
  a bounded shared walking-cost search over nearby candidates. Active targets
  remain fixed during navigation, supply trips and native acknowledgements. A
  search limit never silently removes a pending machine or claims completion.
- Integrated Java 17 `test build` passed **593 tests, zero failures, errors or
  skips**. Snapshot SHA-256:
  `2C85AACA86197FB8814090096FE061478A9430E46A5AB3950FF9AE0FF0F4C052`.
  This is build evidence; installation and real-client acceptance follow below.
- The previous live build was paused after a reported repeated-work stall. Its
  last sample was source count 22/32, with successful container acknowledgements
  and no native failure fence. The final cancelled request was caused by the
  explicit pause. There was no complete trace of the preceding repetition, so
  that snapshot does not establish the exact cause of the reported stall.
- The same instance was normally restarted with this build. Quick Play stopped
  at its failure screen; a normal in-client connection to the configured server
  then succeeded. No second client or desktop-input automation was used.
- The live WINE one-shot completed all **133 remaining mature kegs**. Retained
  native receipts confirmed **133 machine interactions** and **128 inventory
  consolidations**, all successful. Inventory changed from zero wine to 133;
  448 withdrawn tomatoes became 49 remaining (399 = 133 x 3 consumed). All 384
  registered kegs were subsequently observed working, with zero mature kegs.
- The one-second trace shows one 32-container source survey, followed by a single
  supply trip and uninterrupted carried-batch use to completion. There was no
  return to stock counting during production, including beyond the former
  1,200-tick refresh threshold. The run took less than two minutes including
  its initial navigation and supply work; this is one observed run, not a general
  throughput guarantee. A subsequent TOMATO_STORAGE one-shot returned all 49
  leftover tomatoes and completed with none remaining in inventory.
- A native surplus-reserve check found room for the current wine cohort, so it
  correctly retained all 133 bottles and authorized no sale. WINE_STORAGE then
  deposited all 133 bottles: server-confirmed quantities were 1, 4, 64 and 64.
  The tomato return likewise confirmed six one-item transfers and one of 43.
  Both jobs completed with empty cursor and no corresponding items left in the
  player's inventory. This verifies reserve storage, not smart-bin payout.

## Usable recipe-stack fallback after incompatible fragments — 2026-09-08

- Independent review found a separate liveness corner: a same-grade 3+1 wine
  ingredient layout could reject the usable three-item stack if native tags
  prevented merging the fragments. After a safely completed zero-progress merge
  or absence of a safe plan, production may now use a sufficiently large real
  single stack or another usable carried grade. A position-independent state
  fingerprint prevents repeating the same optional merge. An actual failed or
  uncertain native transaction still pauses; no acknowledgement is fabricated.
- Integrated Java 17 `test build` passed **596 tests, zero failures, errors or
  skips**, including wine 3+1, preserves 5+1, alternate carried-grade and no-safe-
  scratch regressions. Snapshot SHA-256:
  `6FE02D186C2A66CB5B5B5802EA2107E5D18B80FF30288457BABD76EADA42FCCD`.
  The earlier live 133-keg run used the batch/route build above; these added
  incompatible-fragment cases are fixture evidence, not fabricated live items.
  After the wine and storage one-shots completed, the same instance was normally
  restarted again to install this final fallback build, preserving its profile.

## Common whole-rack wine cycle — 2026-09-08

- Wine now persists one common rack deadline and an unfinished-member list.
  Migration uses the latest deadline among registered kegs; a few earlier mature
  kegs cannot trigger a separate supply/production visit. The complete registered
  rack must be ready before a new batch opens. Only confirmed native refills
  remove members; interruption retains unfinished work. The next cycle is six
  configured game days after the last confirmed feed, not the first early keg.
- Java 17 `test build` passed **620 tests, zero failures or errors**. Snapshot
  SHA-256: `D116776FB261C7D307AD803EA124770CB13B69FF75A4B622603157EECB4BF0F6`.
  This is build evidence; the new common-cycle behavior still requires live
  installation and a real production boundary. No cooldown was advanced for tests.
- A recorded route exposed a missing outdoor connection to the second farm.
  A bounded, read-only preview verified the existing route failed and the recorded
  connection allowed both outbound and return paths. Only revalidated route
  waypoints were added to the private profile. The actual client then reached
  that farm and harvested before a separate crop-footprint protection stopped it.
- The wine-cellar exit recording was checked against live collision geometry.
  The original registered corridor has valid 20-node paths in both directions
  from its actual half-block standing level. A higher, non-standable cell is not
  evidence of a missing exit corridor. This check is path-planning evidence,
  not a claim of a fresh end-to-end movement test.
- A continuous run also deposited 34 pine tar into the registered shipping bin;
  native inventory acknowledgements confirmed the transfer. Later monetary
  payout was not independently attributed.

## Vanilla-grass harvest false positive — 2026-09-08

- A live read-only footprint check found 15 rejected mature targets, all caused
  by ordinary vanilla grass being inferred as an area-harvest crop. No offending
  unregistered tomato or other crop was observed. Installed Quark bytecode skips
  the `minecraft` namespace during automatic crop discovery; this adapter now
  mirrors that exclusion. Explicit native crop/click mappings still take
  precedence, including vanilla crops and any explicitly mapped custom grass.
  Registered farm boundaries and other-crop protections were not relaxed.
- Java 17 `test build`: **628 tests, zero failures or errors**. Snapshot SHA-256:
  `3AAD931A2C272051C9DB8BE47E471A3577D357B40F64D7E84FA22C30ACF2840C`.
  The same existing client was normally restarted to install this build together
  with the common wine cycle. Live multi-hour acceptance is still in progress.

## Farmland movement-gate mismatch — 2026-09-08

- The first soak attempt moved about 20 blocks, then stalled at a corridor edge.
  A read-only native snapshot confirmed no horizontal collision and a valid
  108-node path, but raw feet failed the movement permission gate while the
  planner's verified standing cell passed. Farmland's 1/16-block lower surface
  put raw feet one integer Y below the cell used by pathfinding. That extra
  squared-distance unit incorrectly rejected a position on the corridor boundary.
- `NavigationFeet.resolve` now supplies the same existing raw-first, verified-
  above fallback to both the path follower and the native movement bounds gate.
  This does not widen registered corridors or add jump/escape permissions.
- Integrated Java 17 `test build` passed **634 tests, zero failures or errors**.
  Snapshot SHA-256:
  `D71E625EB986F4789360B1705A76226292768C2DE9A3DCE5129066DFC3980826`.
  The interrupted attempt is not counted as stable runtime. Movement from the
  original stopped position and a new multi-hour run require live verification.

### Live movement, sleep and early-wine-boundary evidence

- After normal restart with the standing-feet fix, the tomato-storage one-shot
  moved approximately 71 blocks from the original stopped position and deposited
  all 148 carried tomatoes. Native confirmations were 46, 46 and 56 items, with
  no failed receipts and no tomatoes left in inventory.
- Continuous observation restarted at **2026-09-07 19:11:38 UTC**. The earlier
  stalled attempt is excluded. The requested three-hour duration is **not yet
  complete** in this checkpoint.
- On game day 329, idle automation began its normal bedtime transition. The
  server acknowledged `Entered bed`; five consecutive one-second samples showed
  actual sleeping. The game then advanced to day 330 and automation resumed
  harvesting. No sleep one-shot, clock change, or cooldown override was used.
- On day 330, 247 kegs were mature while the common rack date remained 332.
  Automation harvested, stored tomatoes, shipped pine tar and returned to idle;
  it did not start an early partial wine batch. This verifies the waiting boundary,
  not completion of the next full wine batch or a new preserves production cycle.
- Private one-second state and native-receipt observers preserve ongoing evidence.
  Receipt observation timestamps are not fabricated server-completion timestamps.
  Final long-duration production and stability acceptance remains in progress.

### Live full preserves cycle: 144 jars, game day 331

- The continuous run completed one 32-container source survey, withdrew 768
  tomatoes of the largest observed grade, and used 720 to refill 144 jars.
  It collected 144 preserves, returned the remaining 48 tomatoes, and shipped
  all preserves with native-confirmed transfers of **64 + 64 + 16**.
- The scoped native receipt interval contains **253 consecutive successful
  tickets with none missing**: one survey, acquisition, 144 confirmed production
  interactions, hand replacements, ingredient return, and shipping. The 144
  generic interaction receipts are corroborated by machine progress, all jars
  changing from mature to working, and matching ingredient/product counts;
  the generic message alone is not an independent per-position machine census.
- First sampled preserves activity through post-shipping idle took **167.10
  seconds** in this run. There was no repeated inventory survey during production,
  OFF/error sample, failed native acknowledgement, or unresolved final output.
  The state samples' maximum adjacent gap was 1.0122 seconds. Timestamps are
  observation times, not invented exact server action times.
- The engine returned to normal waiting with no tomatoes or preserves remaining
  in inventory. This confirms shipping-box deposit, not independent monetary
  payout verification. The minimum-three-hour observation is still underway.

### Interrupted wine run: late pickup before ingredient restoration

- The second observation window stopped after approximately 40 minutes, not
  three stable hours. It had confirmed 378 of 384 wine refills; the durable
  active batch retained the six unfinished targets.
- A read-only native history audit found a completed ingredient transaction's
  first two primitives: tomato fragments 2 + 1 became 3 after temporarily
  borrowing a hotbar slot occupied by torches. Before the inverse restoration,
  a separate empty material slot received one wine bottle and then its native
  year initialization. Both swap partners were unchanged. A strict whole-menu
  comparison nevertheless rejected the remaining restoration.
- Baseline refresh now requires each changed slot's exact post-ACK server
  packet item and a narrowly permitted addition/metadata change. It excludes
  transaction participants, protected tools and implicit QUICK_MOVE receivers.
  It never advances a primitive or substitutes live client state for an ACK.
- Twelve new regressions cover the observed restoration race, missing/wrong
  packet evidence, native identity/count changes, first-click and completed
  states, protected partners, implicit receivers and exact subsequent ACKs.
  Integrated Java 17 `test build`: **646 tests, zero failures or errors**.
  Snapshot SHA-256:
  `51B12D125F4F9F10C1BABE922A14117D417F2784A9929B5B4229481E3C9880CE`.
- At this checkpoint the fix is built, not yet installed. The interrupted
  layout needs its audited remaining restoration before normal restart/resume.
  Live recovery and the requested long-duration stability check remain open.

### Live recovery after the slot-refresh fix

- The interrupted transaction's one remaining inverse SWAP was independently
  audited and sent exactly once. Its exact full-menu server ACK restored all 55
  torches to their original hotbar position with every other native slot
  unchanged. The original failed action was not relabeled successful or replayed.
- After normal restart into the 646-test build, a wine-production one-shot used
  the remaining 18 tomatoes to finish exactly six remaining kegs. Its 33 native
  tickets all succeeded, without a new warehouse inventory survey. All 384
  registered kegs are working; the common deadline is six days after the final
  confirmed feed. The interrupted batch's earlier 378 refills were not repeated.
- A separate wine-storage one-shot deposited all 384 same-cohort bottles with
  confirmed transfers of five times 64, then 63 and 1. Its nine tickets all
  succeeded; no wine or tomatoes remained in inventory. Production-only and
  storage-only one-shots are distinct operations.
- A new continuous observation window began at **2026-09-07 20:14:56 UTC**.
  Earlier interruption/setup time is excluded. At this checkpoint the requested
  three-hour continuous window remains incomplete; later cycles still need
  verification. Private raw inventory identities and coordinates are not published.

### Live common-cycle wine pass: all 384 kegs, game day 339

- On day 338, 378 mature kegs waited for common due day 339 without an early
  wine visit. Once all 384 were mature, the continuous run completed one source
  survey, withdrew 1,152 grade-1 tomatoes, and consumed all of them in 384 refills.
- All 384 new bottles were observed with raw production cohort 10 before
  storage; confirmed transfers of five times 64, then 63 and 1 deposited all 384.
  The engine returned to normal waiting with no ingredients or products left.
  The common next date became 345, exactly six days after the final feed.
- The scoped 895 consecutive action IDs all reached success with none missing:
  32 source open/close pairs, acquisition, 384 production interactions, hotbar/
  stack organization, and storage. Full-rack state changes and ingredient/product
  conservation corroborate the generic interaction receipts. Temporary PENDING
  observations were subsequently confirmed, not counted as additional actions.
- First sampled wine activity through post-storage waiting took **254.128
  seconds (4 minutes 14 seconds)**. No OFF state, native failure fence, navigation
  error, missing output obligation or observer write failure occurred in this
  scoped pass. It was followed by normal automatic sleep and next-day harvesting.
- Briefly uninitialized wine metadata resolved before storage without stopping
  production. The trace does not independently prove every transient race absent,
  reconstruct the entire warehouse grade-total table, identify the final physical
  storage barrel, or verify its rotating UI label. It is not evidence of surplus
  wine sales: storage had accepted all bottles. The three-hour soak remains open.

### Third observation interruption: unrelated pickups during TrashSlot deletion

- The third continuous window ran from 20:14:56 UTC to the first OFF sample at
  22:04:57 UTC, approximately 1 hour 50 minutes. It did **not** meet three hours.
  Excluding preflight, 2,700 consecutive action IDs succeeded before one failed
  disposal receipt. Eleven normal bed-entry receipts and sleeping transitions
  were observed. There were no sample gaps above 2.5 seconds in the audited
  interval and no disconnect or navigation-failure indication.
- A read-only native packet audit proved the final two rotten tomatoes were
  deleted: the exact requested source slot received an empty server packet.
  Immediately before it, two other slots independently received two normal
  tomatoes each. Those slots retained their exact native identities. The old
  strict all-other-slots-unchanged comparison rejected this confirmed deletion.
- The source was empty, the cursor empty, and all other native slots unchanged
  apart from those independently confirmed additions. The failed action was
  not relabeled successful; no second deletion or recovery click was sent.
  A correction must distinguish these unrelated server-proven pickups while
  preserving source, cursor, menu, generation and exact identity safeguards.
- The prior observer's consolidation-specific fence flag remained false for
  this TrashSlot failure. OFF/PAUSED state and terminal failed receipts must also
  be checked; a false fence flag by itself is not a stability verdict.
- The correction retains the strict API and adds independently proven positive
  production-item additions in normal inventory slots only. Raw-slot evidence
  must lie after the request and no later than the deletion reply; an actual
  full-menu server packet may prove its own additions. Source changes, cursor
  changes, other rotten stacks, replacements and count losses remain rejected.
  Pine tar uses the same bounded exception only in this disposal adapter.
- Twenty-two new regression cases cover the observed packet ordering and its
  unsafe variants. Integrated Java 17 `test build`: **668 tests, zero failures or
  errors**. One invalid over-limit test fixture was corrected to assert the
  existing value constructor's rejection before the successful full rerun.
  Snapshot SHA-256:
  `355C4AF78E1BBB93F5F60D87C8F8ABD5F5743F6DC5E9BEC92B10C0D1B448DFC1`.
  This checkpoint establishes build/test success, not yet post-installation
  stability. Original failed receipts and private native evidence are retained.

### Post-installation recovery: first full wine batch and next-day disposal

- After normal restart, the interrupted inventory was preserved: 556 normal
  tomatoes and 20 pine tar, with no rotten tomatoes or carried cursor stack.
  The new continuous run started at **2026-09-07 22:24:11 UTC**. It first stored
  those 556 tomatoes and deposited the existing 20 pine tar into shipping.
  A subsequent harvest/storage pass deposited another 148 tomatoes and 4 tar.
- One 32-container source survey was followed by 1,182 grade-2 tomatoes acquired
  in two source visits. All 384 mature kegs were serviced without another source
  survey during production. The remaining 30 tomatoes were returned: consumption
  was exactly **1,152 = 384 x 3**. All 384 collected bottles were then stored with
  confirmed transfers of **1 + 63 + five times 64**.
- The first 975 consecutive action IDs all reached success, with no missing ID
  or observed failed/cancelled receipt. The first-to-last keg interaction receipt
  span was 247.195 seconds; this excludes survey, travel and final storage. The
  common next wine date became 351 after the last feed on day 345. No products
  or ingredients remained in inventory when normal waiting resumed.
- The engine subsequently entered bed normally, advanced to day 346, harvested,
  and confirmed three rotten-tomato deletions totaling **16 + 10 + 6 = 32** before
  continuing to storage. This proves the installed adapter's ordinary live path;
  it does not assert that the exact rare concurrent-pickup race recurred live.
- The observer now records both inventory and TrashSlot failure/late-reply
  fields without calling reconciliation or control methods. All remain clear at
  this checkpoint. The requested three-hour uninterrupted window is still open;
  the earlier 1-hour-50-minute interruption is not counted toward it.

### Fourth observation interruption: pickup inside a full-menu merge reply

- The fourth continuous window stopped after approximately **1 hour 8 minutes**
  during ingredient consolidation. It did **not** meet the requested three
  uninterrupted hours; earlier interrupted windows are not added to this run.
- A read-only native audit found that the server had completed the planned
  tomato merge: a two-item source joined a one-item receiver to make three.
  The same full-menu reply also included one newly received wine bottle in a
  separate empty hotbar slot. The previous strict comparison rejected the
  combined reply despite the completed merge.
- The correction narrowly accepts independently verified unrelated production-
  item additions carried by the actual full-menu acknowledgement. The planned
  primitive, participating slots and every implicit QUICK_MOVE receiver remain
  strictly validated. It does not permit arbitrary inventory changes, manufacture
  an acknowledgement, or replay a merge that the server already completed.
- Before recovery, the interrupted inventory matched all 46 expected native
  slots. The one remaining inverse SWAP was sent once and received an exact
  full-menu acknowledgement, restoring all 55 borrowed torches with every other
  slot unchanged. The original failed receipt and fence were retained; no
  QUICK_MOVE replay or automatic retry was performed.
- Thirteen new core/native regression tests cover the full-reply exception and
  its safety boundaries. Integrated Java 17 `test build`: **681 tests, zero
  failures or errors**. Snapshot SHA-256:
  `6D7E0389AE99F2F58346011D1ED9C3732138E8A4308856504F7D354CC245C8B5`.
  Build success and the bounded restoration do not establish post-installation
  stability. A new uninterrupted observation window must be verified separately.

### User-started continuous cycle after the final F8 enable

- After manual play, the operator confirmed deliberately enabling automation
  with F8. The new window starts at its first final-ON sample,
  **2026-09-07 23:54:24 UTC**. Earlier manual/off and preflight activity is
  excluded; resetting this observation anchor is not a newly diagnosed bug.
- The 681-test build waited normally until bedtime, received a bed-entry
  acknowledgement, and showed four consecutive sleeping samples before day 353.
  It then harvested and stored 556 tomatoes, confirmed deletion of 26 rotten
  tomatoes, and deposited 4 pine tar into shipping.
- One 32-container survey and two source visits acquired 766 grade-2 tomatoes.
  All 144 preserves jars were processed: 720 tomatoes consumed, 46 returned, and
  all 144 preserves shipped as **64 + 64 + 16**. Full-rack mature→working changes
  and ingredient/product conservation corroborate 144 production acknowledgements.
  First preserves activity through post-shipping waiting took **172.096 seconds**.
- Through **2026-09-08 00:03:31 UTC**, all 350 consecutive post-preflight actions
  reached success with none missing. All 548 state samples remained ON/connected,
  with no native inventory/TrashSlot failure or late-reply flag, navigation error,
  or trace-write failure. The largest sample gap was 1.010911 seconds. Final
  inventory had no tracked ingredients/products, empty cursor and no pending output.
- This verifies an early automatic cycle, not a per-position audit from generic
  receipts, monetary payout, or recurrence of the prior rare merge race. The
  requested three hours remain incomplete; the earliest qualifying end for this
  window is **2026-09-08 02:54:25 UTC**, with earlier interrupted time excluded.

### Manual handoff boundary found during the continuous observation

Ordinary manual pause followed by F8 uses fresh module/navigation state while
retaining registered locations and persisted production deadlines. The operator's
recent manual movement and F8 restart succeeded, but that is not an inventory-
transaction interruption test. Read-only review found a remaining limitation:
late consolidation acknowledgement handling can release a cancelled transaction
after an intermediate primitive, without preserving its remaining borrowed-slot
restoration. Cancellation sends no cleanup input. Do not claim that arbitrary
mid-transaction handoff is fully supported, or silently replay/clear an uncertain
operation. Safe pause/resume restoration still needs implementation and dedicated
verification. No new inventory interruption was induced in the running soak test.
