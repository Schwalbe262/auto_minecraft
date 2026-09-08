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

### First complete wine cycle in the user-started observation window

- On game day 358, one 32-container survey and two source visits acquired
  exactly 1,152 grade-2 tomatoes. All 384 mature kegs were processed without
  another stock survey. All ingredients were consumed, so no leftover-material
  return was needed.
- The wine workflow's 897 consecutive actions all succeeded, including 384
  production interactions and 394 inventory consolidations. All 384 bottles
  were observed as raw production cohort 10 before storage; confirmed transfers
  of **five times 64, then 63 and 1** stored them all.
- First wine activity through post-storage waiting took **256.133 seconds**.
  All 384 registered kegs were working, none mature; the common batch became
  inactive with no remaining members and next due day 364, six days after the
  final feed. No tracked ingredients/products remained in player inventory.
- All 376 state samples in the scoped **00:51–00:57:16 UTC** interval remained
  ON/connected, without a native failure/late-reply flag, navigation error or
  trace-write failure. Briefly unknown wine metadata resolved before storage.
  Quantity conservation and full-rack changes corroborate the generic receipts;
  they do not independently identify every physical target or the final barrel.
- This is not raw-packet proof that the prior rare full-menu race recurred, nor
  a test of the unresolved mid-transaction handoff limitation. The existing
  three-hour window remains incomplete, with earliest qualifying end still
  **2026-09-08 02:54:25 UTC**; this successful cycle does not reset that clock.

### Fifth observation interruption: pickup before borrowed-slot restoration

- The user-started window stopped after **2 hours 5 minutes 47.918 seconds**,
  before the required three uninterrupted hours. This supersedes the earlier
  positive two-hour checkpoint: **3,630 actions succeeded and one failed**.
  Earlier interrupted windows and the subsequent OFF period do not count toward
  uninterrupted operation.
- Before this stop, the run completed one full 384-keg wine batch, four full
  144-jar preserves cycles and 12 observed bed-entry/sleep transitions. The
  second wine batch had 324 confirmed refills and 60 remaining. Player inventory
  contained **323 wine bottles, not 324**. A nearby bottle with unknown cohort
  does not by itself prove its origin or explain the interruption.
- A separate read-only native audit established the actual failure sequence.
  The server completed the exact planned wine merge, changing only its source
  and receiver and leaving the borrowed temporary slot empty. A later server
  update placed a newly received wine bottle into that empty slot, followed by
  initialization of its production cohort, before the remaining inverse SWAP.
  This was a **post-MERGE, pre-RESTORE pickup**, distinct from the earlier
  same-full-reply pickup case.
- The previous inventory-rebase guard rejected changes to the borrowed slot
  regardless of transaction stage. It therefore stopped before sending the
  restoration step, despite the already confirmed merge. The correction permits
  only an exact raw-server-proven, whitelisted positive pickup into a previously
  EMPTY borrowed slot after both the initial SWAP and MERGE are acknowledged.
  The original borrowed item must remain exact and the next action is still the
  exact inverse SWAP. Earlier stages, source changes, implicit receivers, full-
  reply validation and menu/cursor safeguards retain their existing restrictions.
  This is not permission to accept arbitrary inventory changes,
  blindly swap a newly received product or replay a completed merge.
- The corrected build passes **694 tests**, including eight new core and five
  detached-native regressions. Missing/stale packets, non-production items,
  changed source identity/count, earlier stages and inexact restoration still
  fail closed. A newly filled slot is not falsely reported as freed space.
- The stopped live inventory was separately recovered using one guarded inverse
  SWAP, after all 46 native slots matched the audited evidence. A genuine full
  server reply confirmed exact restoration and preservation of every other slot;
  the completed merge was not replayed and the original failure fence remained.
  This is recovery evidence, **not a post-fix endurance pass**. The 681-test build
  failed this live window. The separate mid-transaction manual-handoff limitation
  above remains unverified; the corrected build needs a new uninterrupted run.

### Sixth build: remaining-batch preflight and new continuous start

- Source `d985e04`, **694 passing tests**, was installed by a normal restart.
  Snapshot SHA-256:
  `36F735C975806F3263D4D1A634B55F3362A4FAFA1C16D86D4EDD24B11F289D4E`.
  The guarded recovery above precedes this new observation window; it is not
  counted as uninterrupted operation.
- A wine-only preflight completed the remaining **60 refills** with all 136
  actions successful, including 70 consolidations, without a source survey.
  Tomatoes fell from 188 to 8 and wine rose from 323 to **383, not 384**.
  All 384 kegs were then working; the common batch became inactive, with no
  remaining members and next due day 371, six days after the final feed on 365.
- Continuous operation then stored the eight leftover tomatoes. Of the 383
  wine bottles, **192 were stored and 191 shipped as verified surplus**;
  confirmed shipping transfers were **64 + 64 + 62 + 1**. Player inventory
  contained no remaining wine when harvesting began. This is item-transfer
  evidence, not monetary payout or an independent audit of the whole reserve.
- The new first ON sample is **2026-09-08 02:23:47.6879135 UTC**. Through the
  fixed **02:26:00 UTC** bound, 133 samples remained ON/connected and all 132
  post-preflight actions succeeded, with no missing action, native failure or
  late-reply flag, navigation error or trace loss. Harvesting was still active;
  no full harvest cycle or three-hour endurance pass is claimed.
- All earlier anchors and preflight time are excluded. The earliest qualifying
  three-hour end is conservatively **2026-09-08 05:23:48 UTC**. Build/preflight
  success and this initial two-minute interval do not establish long-run safety.

### Sixth observation ended by manual-input pause

- The last ON sample was **2026-09-08 03:07:57.0679383 UTC** in normal waiting.
  At **03:07:58.065492 UTC**, the runtime reported a direct-input pause. This
  ends the window after **44 minutes 10.378 seconds**; it is not an automation
  failure or a three-hour pass. The adjacent samples remained connected, with
  no inventory/TrashSlot failure or late-reply flag, navigation error, trace
  failure or pending output.
- The previous **05:23:48 UTC** acceptance target is cancelled. Manual play,
  the upcoming restart and any later run require a new observation anchor;
  interrupted windows must not be added together.
- Daily harvest activity was observed on days 365–368, but confirmed tomato
  intake alternated **1,452 / 148 / 1,452 / 148**. This does not mean every plant
  was harvested daily. The mature-only check admits recognized tomatoes at
  age 3 and excludes unripe targets; daily eligibility does not establish the
  native regrowth period. Generic interaction receipts and screenshots cannot
  independently establish every plant's pre/post-harvest age.

### Harvest movement optimization: tests passed, live verification pending

- The updated build passes **716 tests**: nine added harvest-route regressions,
  eight diagonal-movement regressions and five camera regressions, in addition
  to the preceding 694-test suite.
- Harvest routing anchors straight strips to the observed tomato layout,
  including unripe plants. All originally mature targets remain in the primary
  or cleanup pass, including upper vines. Only observed state changes may skip
  a target; predicted area coverage is not completion evidence. Movement while
  awaiting a harvest acknowledgement is limited to the immediate next center
  in the same field and lane, without overtaking cleanup or turning early.
- The operator authorized a restart and test. **This optimization has not yet
  been verified in the live client at this checkpoint.** Passing tests do not
  establish actual camera behavior, route efficiency, throughput or endurance;
  those need separate post-restart observations. No gameplay safety checks or
  native acknowledgement requirements are waived by these optimizations.

### First post-restart harvest and production results

- The optimized source was installed in the existing Society instance by normal
  restart. All registrations were retained. A normal in-client reconnect resolved
  the initial Quick Play unknown-host screen; no second game, OS input or server
  state adjustment was used. Continuous START uses the same path as F8.
- One completed harvest stored **1,452 tomatoes**, as in each of the two earlier
  large-harvest observations. Using the same first-HARVEST to TOMATO_STORAGE
  boundary, elapsed time fell from **214.114 / 220.110 to 193.110 seconds**:
  **9.8% / 12.3% shorter**. Harvest-associated interactions fell from 177 to 170.
  This is one observed improvement, not a controlled or averaged benchmark;
  starting inventory, grade rolls and server timing were not held constant.
- Observed movement included sustained two-axis diagonal travel with the view
  settling at approximately -45 degrees during harvest and 135 degrees toward
  storage. One-second sampling confirms these intervals, not frame-perfect
  camera smoothness or an isolated contribution to the time improvement.
- The first complete wine batch consumed 1,152 tomatoes for 384 refills.
  **No new wine was stored**: after reserve verification, all 384 carried bottles
  were shipped as surplus. Preserves consumed 720 of 768 acquired tomatoes,
  returned the other 48 and shipped 144 preserves. Each production module surveyed
  the 32 tomato containers once, without another survey during its funded batch.
  These are confirmed item transfers, not independently attributed money income.
- All **1,407 consecutive actions** through the subsequent bed entry succeeded.
  Five actual sleeping samples preceded the next game day. Through the fixed
  **2026-09-08 03:39:20 UTC** checkpoint, 706 samples stayed ON/connected without
  native failure/late-reply flags, navigation errors or trace loss. This initial
  approximately twelve-minute interval is not a three-hour endurance pass.

### Two-day harvest alignment for the observed fields

- A separate read-only, fully loaded field inspection found weak fertilized soil
  beneath all 800 registered tomato plants. After the harvest, the first field
  had 512 plants at age 0; the second had **214 at age 0 and 74 at age 2**. The
  next morning the 74 were ripe while the other 726 remained at age 2. This
  explains the alternating large/small harvests: two out-of-phase cohorts, not
  indiscriminate harvesting of unripe targets or different soil between fields.
- Installed Dew Drop logic advances a weak-fertilized crop from age 0 to 2 on
  one eligible daily growth and to 3 on the next. The observed profile was set
  to a two-day harvest interval, with both next dates aligned to the same day,
  using normal profile persistence after confirming harvest was inactive. Wine
  and preserves intervals and all registrations were unchanged. This is a
  profile-specific setting, not a universal two-day default for every fertilizer.
- The intermediate day was observed waiting despite the 74 ripe plants. The
  following synchronized harvest still needs its own completion evidence at
  this checkpoint. Crop age returns to an unripe state after successful harvest;
  seeing unripe tomatoes then is expected.
- Three additional cadence regressions pass: no early visit/click despite a
  ripe subset, unchanged deadlines across module reset, full due-day harvest
  advancing both field dates, and next-morning checks when no crop was ripe.
  **719 tests pass**, with no failures, errors or skipped tests. The rebuilt
  production JAR is byte-identical to the already running optimized artifact;
  these test/documentation additions do not require a second restart.

### Synchronized due-day harvest completed

- The entire intermediate day has **635 state samples, no HARVEST activity and
  no inventory action**. Its only action is a successful bed entry. The next day
  resumed HARVEST automatically. A fully loaded inspection before the first crop
  use found **all 800 tomatoes mature**, with no unripe plants in either field.
- That due-day harvest made 183 confirmed harvest-associated interactions and
  stored exactly **1,600 tomatoes in 28 confirmed transfers**. It also deleted
  80 rotten tomatoes and shipped 38 pine tar. These quantities are confirmed
  separately from the earlier 1,452-tomato timing comparison; the larger harvest
  must not be used as an identical-workload speed benchmark.
- Afterward, all 800 registered plants were observed at age 0, with zero mature
  leftovers; both persisted next dates advanced by another two days. No tracked
  ingredients/products remained in inventory when the engine returned to waiting.
  This closes the previously pending next-harvest verification above.
- Through **2026-09-08 03:54:40 UTC**, the optimized client remained ON/connected
  for **27 minutes 5.854 seconds**, with **1,678 consecutive actions successful**,
  no missing or unresolved action, no native failure/late-reply flag, navigation
  error or trace loss. Three actual sleep transitions were observed. The local
  harvest-setting adjustment occurred during this interval; this is workflow
  validation, not an unchanged-configuration or three-hour endurance claim.

### Dawn preserves batching follow-up

- On the next preserves date, the client selected four mature jars while the
  other 140 were still receiving the native morning update. All 144 appeared
  mature by observed day tick 233, but the first list was already fixed at four.
  This caused two complete 32-container stock surveys and two production trips.
  Both batches did finish: 768 tomatoes withdrawn, 720 consumed, 48 returned,
  and **4 + 140 preserves shipped**. All 331 consecutive receipts including
  the preceding bed succeeded, with no missing ID or terminal pending action.
- New PRESERVES runs with a date-due registration wait until day tick 240
  before taking their eligibility snapshot: the native ticks 20..219 update
  window plus a short client-update margin. The wait happens before eligibility
  can postpone an apparently working jar. It returns BUSY so one-shot execution
  does not falsely finish. Future-only/empty registrations remain idle, and
  already active runs, native acknowledgements and wine scheduling are unchanged.
- Four regressions cover staggered 4-to-144 maturity through tick 233, exactly
  one use per jar from tick 240, no early navigation or deadline changes,
  future-only/empty runs, reset/recreation, the bounded end of the wait when a
  jar is still working, and an active transaction crossing midnight.
  The full build passes **723 tests**, with no failures, errors or skipped tests.
- This margin addresses the observed morning split; it is not a guarantee
  against arbitrary network delays. Deployment and a new actual dawn run must
  be checked separately. Earlier observation windows cannot establish endurance
  for the changed build.

### Safe restart with the dawn batching fix

- The previous optimized build ended by an intentional idle-state pause after
  **48 minutes 20.518 seconds** of observed ON operation: 2,900 connected/running
  samples, **2,284 consecutive successful actions**, no missing or unresolved
  action, no observed failure fence, and five actual sleep/day transitions.
  Its final due-day harvest stored 1,600 tomatoes, deleted 76 rotten tomatoes
  and shipped 30 pine tar before reaching a clean inventory and closed menu.
  This interval is not a three-hour endurance pass.
- The existing Society client was normally closed and restarted with the
  723-test dawn-fix artifact; no second client was launched. The installed JAR
  hash matched the tested build. Fresh in-world diagnostics confirmed all
  registrations, the two-day harvest setting, six-day wine and three-day
  preserves schedules, an empty cursor, and no pending production obligation.
- Continuous automation resumed through the normal F8 start path at
  **2026-09-08 04:18:16 UTC**. The first observed ON sample is at
  **04:18:16.904 UTC**. Post-restart cycles and uninterrupted endurance must be
  assessed from this new window, not concatenated with the prior run.

### Post-restart full cycle, harvest handoff and new wine year

- The next due harvest initially contained all 800 ripe tomatoes. Four later
  one-second samples reported a direct-input pause before continuous mode
  resumed. Existing harvest outputs were stored/shipped first, then the 719
  remaining ripe plants were rescanned. Across both portions, **1,600 tomatoes
  were stored, 90 rotten tomatoes deleted and 34 pine tar shipped**. A subsequent
  complete field inspection found all 800 plants at age 0 and both next harvest
  dates two days later. No action failed in this observed handoff. The trace
  does not identify the input sender or exact sub-second toggle count; this is
  not a guarantee about arbitrary manual inventory edits during consolidation.
- The full **384-keg wine batch** consumed 1,152 tomatoes of the selected grade
  after one 32-container survey. All 384 new wines were then stored through
  seven confirmed transfers totaling 384, not sold. The actual opened menu and
  navigation destination matched the registration for the new native wine year.
  The preceding audited batch used native year 10; this batch used year 11 and
  a different registered destination. The exact year rollover instant and an
  initially empty pre-transfer menu were not directly captured. The common next
  wine date advanced by six days, with no unfinished machines.
- The following **144-jar preserves batch** consumed 720 tomatoes, returned 48
  unused ingredients, and shipped all 144 products. It surveyed the 32 sources
  once. Its next dates advanced by three days. With work complete, the engine
  entered bed, five sleeping samples were observed, and the next day appeared
  with no tracked products/ingredients or unresolved production obligation.
- The latest uninterrupted ON window begins **04:32:44.354 UTC**, after the
  direct-input handoff. Through **04:45:57.793 UTC**, its 794 samples and 1,483
  consecutive action receipts show no OFF/disconnect, missing/unresolved action,
  failure fence, navigation failure or trace loss. This is only **13 minutes
  13.439 seconds**, not three hours. Earlier ON intervals are not added to it.
- This preserves pass started after harvest and wine, well past dawn. It proves
  full-cycle operation on the new build, but the new dawn snapshot wait still
  requires a live due morning without those preceding jobs. Its unit regressions
  and that future in-game verification are separate evidence.

### Repeated harvest and direct-dawn preserves verification

- The next two-day harvest stored **1,600 tomatoes**, deleted 74 rotten tomatoes
  and shipped 34 pine tar. All 263 consecutive actions including the preceding
  bed entry succeeded. No tracked ingredient or product remained when waiting
  resumed. The 203.102-second HARVEST-to-storage interval is approximately the
  same as the preceding 204.104-second, 1,600-tomato comparison at one-Hz sample
  resolution: this proves repetition, not an additional speed improvement.
- On the subsequent preserves-only due morning, no harvest or wine job delayed
  startup. Eleven samples spanning day ticks **23..224** showed the new START
  guard: no movement, inventory change, action or target-list construction.
  The observed mature-jar count rose from **1 to all 144** during that interval.
  At tick **243**, the first post-threshold sample showed one 144-target batch.
  There was no early four-jar batch or later 140-jar remainder.
- That batch surveyed all **32 tomato containers once**, acquired exactly
  **720 tomatoes**, and successfully collected/refilled all **144 jars** without
  another survey during production. The engine shipped all 144 preserves in
  transfers of **64 + 64 + 16**, closed the shipping container and returned to
  waiting with no tracked products/ingredients or pending production obligation.
  Every jar's persisted next date advanced by three days.
- From the preceding bed entry through the final shipping close, **264
  consecutive actions succeeded**, with no missing or unresolved action. The
  fixed **2026-09-08 05:05:19.380..05:08:30 UTC** observation window contains
  191 ON/connected samples, no native failure/late reply, navigation failure
  or trace loss. First WAITING was observed at **05:08:25.488 UTC**.
- This closes the previously pending direct-dawn **continuous-mode** test.
  It does not establish arbitrary-lag behavior, live one-shot acceptance,
  independently attributed currency payouts or three-hour uninterrupted
  endurance. Those scopes must not be inferred from one completed batch.

### Registered spruce logging: development scope and setup

This section describes newly implemented development behavior, not a published
release or live acceptance result. No new test-pass count or completed logging
run is claimed here. Earlier crop/production observations do not validate logging.

- `LOGGING` defaults OFF in both new profiles and older profiles that omit it.
  The ordinary feature toggle controls repeated operation; **Ctrl+F8 → 실행·기록
  → 한 번 실행 → 벌목·장작** runs only the logging routine without changing that
  saved toggle. This routine includes replanting, waste handling, fire-log
  crafting, wood storage and mossberry shipping before completion. It does not
  enable unrelated harvest, wine or sleep jobs.
- Register named plots under **밭·벌목 구역 → 벌목 구역…**. Each plot starts at
  the aimed northwest sapling/trunk base and covers four same-height planting
  cells to its east and south. Registration requires loaded spruce saplings or
  logs in those cells and rejects overlapping planting cells or tomato fields.
  Multiple plots can be listed, renamed and removed; registration removal does
  not remove world blocks. **도끼·벌목 설정…** captures an equipped netherite axe
  with remaining durability at least two, in a different hotbar slot from the hoe.
- Register an ordinary wood-only chest/barrel, a vanilla crafting table and a
  smart shipping bin through the existing facility UI, with safe connecting
  waypoints. The wood destination accepts only `minecraft:spruce_log` and
  `meadow:fire_log`; its contents must be checked before registration. The
  native crafting adapter requires the known, unlocked six-ingredient recipe:
  **six spruce logs produce one `meadow:fire_log`, not planks**. Competing raw-log
  species in the inventory are rejected. Remaining spruce logs and crafted
  fire logs are stored; carried `society:mossberry` is sent to the smart shipping
  bin. A shipping transfer is not an independently verified monetary payout.
- Tomato harvesting remains hoe/right-click only. The new destroy-packet
  exception is limited to the active logging action, an approved registered
  spruce base, the registered usable axe, native tree proof and current loaded
  reach/line-of-sight checks. It is not a general attack, building-demolition or
  other-tree capability. Replanting likewise targets only registered empty
  planting cells with spruce saplings. Unknown or unsafe tree proof fails closed.
- Repetition modes are **all registered trees grown** (default), **due game day
  plus observed growth**, and **one-shot only**. The date-mode interval is 1–28
  game days, default one. Explicit one-shot checks current readiness instead of
  waiting for the repeated-mode schedule. The readiness poll defaults to 60
  seconds and is configurable from 1 to 1,200 seconds in 0.05-second increments;
  it is not a predicted growth timer. Repairs and leftover product handling may
  be needed even when not all trees are ready for a new cut.
- The optional spare-sapling setting is a minimum retained quantity after all
  planting cells have been restored: default zero, supported range 0–2,304.
  Whole-stack TrashSlot operations may leave more than this minimum. Only
  approved surplus spruce saplings and `twigs:twig` are logging waste; ordinary
  sticks, logs and berries are not discarded. Temporary sapling hotbar borrowing
  excludes the hoe and logging-axe slots and must restore the original item with
  exact native state/response checks. Ambiguous replies do not authorize replay.
- Unfinished plot membership, replanting progress and a borrowed hotbar item are
  durable. Pause, reconnect and schedule reset do not erase them; UI plot/tool/
  logging-setting edits are locked while a batch remains active. **Profile
  schema 3 supersedes the earlier schema-2 note**: validated schemas 1 and 2
  migrate in memory without rewriting the old file until an actual save.
  Clients that only accept schemas 1/2 refuse schema 3 rather than silently
  ignoring pending logging or restoration state. Do not lower a schema number
  or erase checkpoints to bypass this protection.

Separate acceptance still needs the actual logging UI and a registered grown
tree, complete four-cell replanting, reserve-aware waste handling, native crafting
and destination transfers. Pause/reconnect with unfinished work or a borrowed
hotbar item, blocked paths, changed/missing tree proof, insufficient saplings,
unknown recipes and full destinations also need scoped verification. Build
results and any later live observations must be recorded separately; no existing
endurance window is extended by this feature description.

Development verification, 2026-09-08: Java 17 with the repository's Gradle 8.8
completed `test build`; 67 suites / 823 tests passed, with zero failures,
errors or skips. The 100 added tests cover logging geometry and safety gates,
profile/UI rules, native recipe and packet-permit boundaries, the complete
module state machine, and scheduler resume ordering. Native-boundary tests are
not a substitute for live server acknowledgement tests.

An unfinished logging batch resumes before ordinary inventory consumers. If
logging is disabled, unavailable or blocked, it pauses with its checkpoint
intact instead of allowing storage or production to consume the borrowed
hotbar item. An explicit logging one-shot may finish it without changing the
saved toggle; other one-shots are rejected until it is finished. Ordinary
inactive logging checks retain the existing scheduler sweep behavior.

TrashSlot's retained recovery buffer accepts the authorized waste union
(empty, rotten tomato, spruce sapling or twig) across both disposal workflows.
The source-item restrictions are unchanged: rotten-tomato disposal cannot
delete logging products, and logging disposal cannot delete wood, berries,
tools or unrelated items. A valuable recovery-buffer item still blocks the
operation. This compatibility change does not prove the contents of a past
failed live buffer that was not captured.

### Logging-only verified one-block ascent: not yet live-accepted

The development navigation change after the logging baseline above adds an
explicit logging-only path API. A candidate edge must move one cardinal cell
and exactly one integer Y cell upward, with native proof that the actual
support-height difference is also exactly one block. Registered bounds, loaded
supports and the body-clearance envelope remain mandatory; this is not a general
jump, diagonal ascent, farmland shortcut or block-removal permission. Ordinary
navigation and tomato harvesting retain their no-jump behavior.

`findLogging` is a read-only geometry query and may be used while automation is
OFF. Actual movement separately requires an active durable logging run and a
session that permits `LOGGING`; an enabled feature flag alone is insufficient.
The controller aligns on the source, issues one launch pulse, then steers without
sprinting. Interaction proximity cannot complete an airborne move. Landing
requires the verified height and centered position on two distinct grounded
ticks; the remaining approach is replanned from that landing, not by relaunching
the completed edge. Cancellation, timeout or changed proof stops the move.
Attempted interrupted edges remain non-replayable across navigator resets in
that navigator instance; pressing F8 is not permission to retry an uncertain jump.

The focused regressions cover the opt-in boundary, missing native/support proof,
single launch, airborne early-arrival rejection, landing and centering, and
timeout/cancellation without another launch. They do not establish real-player
physics or successful outward/return logging routes on the server. Live ascent,
landing and complete-routine acceptance are still pending. Java 17 / Gradle 8.8
`test build` passed after integration: 69 suites, 861 tests, zero failures,
errors or skips. This includes the native timing arithmetic regression and
settled-source, final-landing replan and interrupted-airborne guards; it is not
live-server acceptance.

#### First live ascent and grounded-centering correction

The first supervised logging run produced one launch, an observed airborne
arc, and native ground contact one block higher. It then stopped before any
mining: residual grounded motion crossed the old flight-only limit during
centering. No tree was chopped and the unfinished six-plot checkpoint remained.
This proves that one ascent reached ground, not that the logging routine passed.

The correction leaves airborne bounds unchanged. Only after verified ground
contact at the destination height may centering use the original corridor or
the same destination cell within 0.45 blocks of its center. Native geometry and
permission checks remain active. Observed motion chooses ordinary walking,
coasting or braking; completion requires two distinct centered, grounded
samples with displacement at most 0.002 blocks per tick.

Java 17 / Gradle 8.8 `test build` passed: 70 suites, 868 tests, zero failures,
errors or skips. The new tests cover the translated live trajectory and bounded
ground-motion models. The correction still requires deployment and live retest;
neither these models nor the earlier ascent establish full-routine acceptance.

#### Shrinking TreeChop stump aiming after six confirmed chops

The next supervised run completed two separate one-block ascents and selected
the configured axe. Six native chopping actions were acknowledged before the
remaining stump approach failed; the tree had not fallen and the six-plot
checkpoint was preserved. This is partial progress, not logging acceptance.

Installed TreeChop changes the chopped log's outline size and offset as cutting
progresses. Fixed quarter/center rays can miss that remaining shape. Only
`treechop:chopped_log` now uses candidates strictly inside its current native
outline boxes and chooses the closest validated native OUTLINE hit. The first
world hit must still be that block, within native reach and the four-block cap;
walls, invalid shapes and inside hits grant no interaction permission. Ordinary
block targeting is unchanged.

Java 17 / Gradle 8.8 `test build` passed: 71 suites, 880 tests, zero failures,
errors or skips. Coverage includes a corner-offset shape missed by the old
sampler, occlusion and reach rejection, shape changes, and module restart after
six of twenty-four acknowledgements. Another fixture needs thirty-one chops:
completion follows the observed fallen tree, not a fixed count of twenty-four.
These are code/fixture results; the new aiming change still needs live retest.

#### Live stump retest and incomplete 2x2 planting follow-up

After normal same-instance deployment, Quick Play reported an unknown host.
A subsequent normal in-client reconnect succeeded. The resumed partial tree
received seventeen additional successful native chopping acknowledgements and
actually fell. Receipt totals are not the TreeChop tooltip's absolute progress
counter; do not infer that counter by adding action acknowledgements.

The next stop exposed a separate timing problem: the fixed eighty-tick settling
window ended around the arrival of saplings. The one-second observer saw the
stop and the first nine-sapling inventory in the same interval, so their exact
ordering cannot be reconstructed. A later explicit retry successfully planted
two saplings before the next planting precondition rejected the attempt. No
native failure or unresolved action reply was observed. Replanting and the
borrowed-hotbar checkpoint remained active; no crafting or delivery completed.

The operator reported that one of the two saplings grew into a small tree and
chose to repair that plot manually. Later native inspection found all four
bases occupied by spruce logs; that is manual-repair/growth evidence, not an
automated four-sapling success. A current read-only probe cannot reconstruct
the earlier obstruction. Code inspection establishes that generic soil
visibility and the former single-point soil-UP planting test were different
conditions; all-face approach and delayed-sapling handling are being tested
separately before another routine run.

The follow-up keeps the eighty-tick fall delay and waits up to another four
hundred client ticks from the first insufficient-sapling observation. All missing
cells must have usable inventory saplings available before any planting starts;
partial arrivals do not extend that plot's deadline. The planting-only navigator
requires every remaining soil UP face to be reachable from one stance, using
matching native geometry for planned and actual eyes. The module keeps a
far-to-near order while the remaining faces stay visible and confirms one
native use at a time. Current terrain still wins: this is not an atomic four-use
operation or permission to ignore a new obstruction. Explicit pause/reconnect
preserves the replant obligation and never resends a confirmed planting.

Java 17 / Gradle 8.8 integrated `test build` passed: 73 suites, 907 tests,
zero failures, errors or skips. Native UP-face clipping, all-goal navigation,
delayed/partial inventory arrivals and fixed planting order have regression
coverage. Full live 2x2 replanting and the rest of the logging routine still
require supervised acceptance after deployment.

#### Placement ACK during falling-tree pickups

The deployed joint-stance run skipped the operator-repaired plot, felled the
next tree, then timed out on its first planting confirmation. A read-only
inspection of the existing failure object found a five-sapling starting stack,
two subsequent raw server replies confirming a spruce sapling at the exact
requested cell, and that sapling still present with the other three cells empty.
Retained later slot replies showed increasing pickup counts. Earlier slot
entries had been evicted from the bounded ring, so their absence is not proof
that a transient count-decrement packet was never sent. No four-at-once
planting behavior was established, and the unfinished operation was not resent.

The correction confirms placement from the dispatched AIR-to-spruce request's
same-generation, latest exact-target raw block reply. It no longer demands an
exact inventory decrement while falling-tree drops are being collected. A
missing, stale, other-target or other-generation reply cannot confirm planting;
a later AIR or unrelated block invalidates an earlier positive reply. Client
prediction and inventory changes alone still provide no placement confirmation.

Replant completion now requires four spruce saplings or four spruce trunk bases,
not a mixture of small grown trees and remaining saplings. A partially grown
one-to-three-trunk plot retains its obligation and stops before further planting
or disposal; automatic corrective cutting is not added. The same coherent
pattern is checked before waste disposal and final completion persistence.
This base pattern is not a claim to identify a particular generated canopy.

Java 17 / Gradle 8.8 `test build` passed after integration: 74 suites, 920
tests, zero failures, errors or skips. The new native ACK and coherent-pattern
regressions do not replace the pending full-routine live retest.

The next supervised same-instance run resumed the three missing cells and
completed the remaining registered felling/replanting batch. Both durable
remaining-plot and replant-obligation lists reached zero after acknowledged
placements, without recutting the operator-repaired plot. Cleanup then stopped
on an unconfirmed single-stack sapling TrashSlot request. The last tree's
inventory arrivals overlapped that cleanup transition. The source stack was
subsequently empty, but that alone is not an exact deletion receipt; the request
was not resent or marked successful. The borrowed-hotbar lease remained
protected. Crafting, storage and byproduct shipping are not yet established by
this run, and this short test is not multi-hour acceptance.

A bounded read-only inspection subsequently identified the specific mismatch:
the trash request began with 28 saplings, retained raw source-slot replies rose
to 34 before an EMPTY reply, and the current TrashSlot recovery buffer held 34
saplings. Other changed slots were independently server-proven log/twig pickups.
The original exact-28 quantity proof was therefore correctly rejected; the
source-empty reply was present. Earlier ring entries had been evicted, so no
claim is made about the missing intermediate history. Destructive acknowledgement
rules are not relaxed to turn this into an exact-28 success.

Logging cleanup now waits for twenty consecutive unchanged client inventory
ticks before waste disposal, borrowed-hotbar restoration and crafting. Changed
counts or missed samples restart the quiet count without extending that wait's
four-hundred-tick deadline. Normal inventory mapping must contain exactly slots
0 through 35; available native fingerprints are compared at window endpoints,
not hashed every tick. A continuously changing inventory pauses without sending
a destructive request and retains the unfinished run and hotbar lease. An
explicit resume establishes a fresh quiet window from the current inventory.
This is an inventory settling delay, not dropped-entity tracking, and does not
resend or retroactively acknowledge the earlier failed trash request.

Java 17 / Gradle 8.8 integrated `test build`: 74 suites, 928 tests, zero
failures, errors or skips. Regressions cover the observed 28-to-34 pickup,
unextended timeout, duplicate/gapped samples, interrupted cleanup, subsequent
inventory changes, fingerprint endpoints and malformed normal-slot mappings.

On deployment, cleanup resumed without further felling/planting: the server
confirmed disposal of 45 twigs and restoration of the borrowed hotbar item.
The registered crafting table opened with an empty grid and cursor, but recipe
construction failed before any material placement. Read-only native inspection
found the exact six-spruce-compatible shapeless recipe, one fire-log result,
correct inventory mapping and no competing ingredients. Its client recipe-book
entry was absent. No ingredients were consumed during that rejection.

Only the client-book precheck is removed. Normal native recipe placement still
uses the server's own recipe-book authorization, and an unchanged/self-swap
snapshot cannot satisfy the existing exact six-slot placement proof. Output is
never taken without that proof. No recipe unlock, alternate crafting primitive
or server permission override is introduced. A server-side refusal can still
time out safely and requires diagnosis, not a claim of completed crafting.
The shared trash receipt now names inventory waste rather than mislabeling
logging twigs as rotten tomatoes.

Java 17 / Gradle 8.8 integrated `test build` passed: 74 suites, 931 tests,
zero failures, errors or skips. The three added regressions retain all recipe
data guards and reject unchanged/pickup-only menus after server refusal.

The live normal recipe-book request did not place ingredients. A later read-only
probe found a same-generation full server menu after the request, with all 46
slots exactly unchanged and the cursor/grid empty; no output click was sent.
This identifies non-placement, not the remote server's recipe-book contents or
its precise reason. The failed request was not retried or silently converted to
a different operation.

When the client has no book entry, a new crafting action now chooses ordinary
manual inventory clicks up front. A native-verified spruce stack is distributed
evenly over six grid cells with vanilla left-drag; any remainder returns to its
exact source. If only small fragments remain, ordinary single-item placement
collects the six ingredients across those source stacks. The detached planner
models all 46 slots and the cursor after each click, including the temporary
plank result exposed by a single log. It never takes an intermediate result or
uses an outside-slot PICKUP. The live adapter requires a fresh full server
reply for each primitive and exact live state before the next one.

Only the current manual crafting primitive's exact before/after cursor remains
owned while nonempty. Other cursor, menu and manual-input protections are
unchanged. A cancelled intermediate ACK cannot clear the unfinished crafting
fence or start cleanup. Final six-cell placement, empty cursor and fire-log
material conservation remain mandatory before/after taking the result.
The module's finite crafting-batch limit now covers fragmented one-output
batches; a regression uses real output space and a subsequent inventory pickup
to complete 65 batches producing 66 fire logs.

Java 17 / Gradle 8.8 integrated `test build`: 75 suites, 947 tests, zero
failures, errors or skips. Manual planner conservation, cursor ownership,
sequence-bound intermediate ACKs and fragmented-batch coverage passed; live
manual crafting and downstream storage/shipping still require deployment tests.

#### Supervised manual-crafting and downstream completion

The deployed manual-placement build resumed the saved cleanup-only logging run
without new felling or planting. Fourteen native-acknowledged crafting batches
produced 88 fire logs from 528 spruce logs, including both bulk and fragmented
placement. The initial 533 logs left five raw logs; the 28 existing fire logs
plus 88 crafted fire logs gave 116 for storage.

Subsequent registered-storage transfers confirmed 116 fire logs and five raw
logs deposited. Ten mossberries were inserted into the smart shipping box;
this is delivery-input confirmation, not a claim that money had already been
paid. All 28 observed action receipts in this resumed run finished successfully.
The one-shot ended normally with no pending action/fence, empty cursor and
normal inventory menu. Tracked logging products were zero in player inventory.
The persisted active-run flag, remaining/replant lists and borrowed-hotbar
lease were all cleared by normal completion. Existing farm/POI registrations
were retained. LOGGING remains default-OFF and one-shot execution stopped OFF.

Together with the earlier supervised felling and coherent four-cell replant
checks, the interrupted logging routine has now completed through disposal,
hotbar restoration, crafting, storage and byproduct delivery. These are staged
live acceptance results across fixes/restarts, not a claim of one uninterrupted
full-cycle run or several hours of endurance on the latest build.

## Dense production-rack visit preference — 2026-09-08

A synthetic two-aisle fixture exposed a bounded-search quality failure: a
16-wide, four-high rack hidden behind a wall consumed the entire 512 visibility
checks, returning the nearest occluded machine instead of a slightly farther
machine in the current aisle. Removing only the origin's nearest-64 target cap
did not fix the exhausted visibility budget.

The preference now considers all loaded pending machines from each reached
standing cell, checking at most the nearest eight there before exploring onward.
The total 512 visibility-check and 512 visited-node limits remain. No pending
machine is removed, and normal navigation, registered bounds, loaded geometry,
reach and native clipping remain authoritative before an action. This is a
bounded next-visit heuristic, not a globally shortest route guarantee.

The regression selects the same-aisle machine with 170 checks rather than the
old 512-check occluded fallback. Counts refer to interaction-check calls, not
individual native raycasts. Reflection, shuffled registration and a modeled
four-block-reach upper-rack case pass; the existing simple wall and immediately
usable-machine cases retain their check counts. These are fixture measurements,
not native clipping equivalence, live travel-distance or elapsed-time savings.
Java 17 / Gradle 8.8 integrated
`test build`: 75 suites, 951 tests, zero failures, errors or skips. Deployment
and supervised route retesting follow separately.

Before that route deployment, the previous manual-crafting build also completed
a supervised continuous farming cycle through crop harvest, disposal, tomato
storage, tar delivery, all registered wine and preserves machines, wine storage,
preserves delivery and actual sleep/day transition. All 1,467 final action
receipts in the audited eleven-minute window succeeded, with no missing IDs,
unresolved actions, unexpected OFF state or failure fence. Tracked products
were absent from the final player inventory and the next wine batch was six
game days later. Shipping confirms inputs, not money payout. A deliberate pause
in the subsequent idle state allowed normal same-instance route deployment;
neither this short cycle nor the restart is multi-hour uninterrupted acceptance.

On the deployed dense-rack build, the first 32-minute continuous observation
covered two due harvest/storage/delivery cycles, one full preserves cycle and
three actual sleep/day transitions. The 1,920 one-second samples contained no
OFF state, disconnect, navigation failure, failure fence or trace-write failure.
All 832 observed final receipts succeeded, with contiguous IDs; the final
inventory had no tracked work products, normal menu/cursor and full health/food.
The preserves cycle retained its ordinary three-day deadline. Wine correctly
remained pending until its six-day common batch deadline; this interval does
not yet establish live wine routing on the changed build. No restart or manual
control occurred within this window. Multi-hour acceptance remains open.

The same process then passed an initial one-hour observation through the next
full wine batch and a second preserves batch, followed by actual sleep into the
next day. All 2,308 observed final receipts succeeded with contiguous IDs.
The 3,605 one-second samples had no OFF, disconnect, navigation failure, failure
fence or trace-write failure; their largest interval was 1.045 seconds. Six bed
receipts corresponded to thirty sleeping samples. The window included 3,349
samples with the game window inactive. Final work products were absent, the
cursor/menu were normal and health/food remained full. Wine's next common batch
deadline was exactly six game days after the completed batch.

A non-controlled comparison of two complete wine batches found 384 successful
machine interactions and working machines in each. The earlier build's first-to-
last interaction receipt interval was 229.156 seconds; this build's was 242.154
seconds. Corresponding navigation movement was about 66.16 versus 58.45 metres
using nearest one-second boundaries. Both runs surveyed all 32 source barrels
once and made two withdrawal visits, with no source revisit after servicing
started. Different input grades, starting poses and observation boundaries mean
this is not a causal speedup or regression result: the shorter observed movement
does not establish a shorter overall production time. Multi-hour acceptance
remains in progress, and no additional route or timing changes were deployed
during this observation.

## Waypoint-free terrain navigation — 2026-09-08

The initial terrain candidate passed Java 17 `test build`: 1,023 tests, no
failures, errors or skips. Coverage includes schema-4 migration, separate
unverified coordinate drafts, incremental search limits, a 300-block route that
reanchors its finite travel domain, unloaded-frontier waits, door replies,
harvest lookahead's no-jump restriction, and deferred work without advancing
completion dates. These are fixture results, not live acceptance.

The same existing Society instance was normally restarted with the candidate.
Its installed SHA-256 was
`4B158DD1469C37DDB8F29486EA4CF8AC19BCD224C6CE7DFCC0F1DD8AF5CAEC34`.
The original server connection and registered workplaces were retained.
Waypoint hints were explicitly disabled without deleting saved waypoints or
changing work schedules. No alternate Minecraft instance was left running.

A controlled coordinate-only trip moved toward the cellar but stopped during
a one-block descent. It submitted no inventory or block-interaction actions
and returned to OFF. The stopped pose and a subsequent detached, read-only
geometry check identified a landing overshoot; a safe resting pose does not
prove the preceding in-flight pose was valid. The regression model reproduces
overshoot when input is merely released after a high-speed fall begins.

The correction adds pre-descent braking, bounded reduced input on the same
verified edge, no airborne acceleration, and a settled landing before the next
turn. Input strength affects native walking inputs only: position and velocity
are never assigned. An invalid or overshot corridor still fails closed. A
bounded last-failure snapshot survives navigator reset as diagnostic evidence,
not as a movement permission.

The corrected source passed Java 17 `test build`: **1,035 tests across 82
suites, zero failures, errors or skips**. The prepared, not-yet-installed JAR
has SHA-256
`6CEC3E5D806C12E71E8AA07EA33243FB7B24FFBCDDB7765444D7017CDB97C171`.

The operator resumed manual play, so the correction is not yet installed or
live-retested. New-build stair, coordinate-UI and at-least-three-hour continuous
acceptance remain open. Earlier old-build observations, operator-controlled
F8 sessions and simulated gravity/drag tests must not be reported as that
acceptance window.

### Supervised descent retest — 2026-09-08

This dated result supersedes the preceding not-yet-installed status. With the
operator's approval, the same Society instance was normally restarted with the
corrected JAR, SHA-256
`6CEC3E5D806C12E71E8AA07EA33243FB7B24FFBCDDB7765444D7017CDB97C171`.
Terrain navigation retained disabled waypoint hints and existing registrations.

The previously failing descent passed twice, and two return trips also completed.
These were movement-only tests: no inventory work or interaction action tickets
were issued. The second downward trip took 21.118 seconds and completed without
a last-failure record. A bounded high-frequency observer captured 415 of its
423 client ticks. All 34 observed airborne ticks had no movement intent; active
descent intents used the reduced 0.08 input strength. Six landing sequences
completed. Five directly showed the final two quiet ticks; one had an unobserved
tick at that boundary, so complete per-tick proof is not claimed.

A separate explicit local-control pause during movement returned automation to
OFF, with position stable for fourteen seconds afterward. This checks the local
pause/control path, not physical F8 key dispatch through the game UI.

A separate continuous observation began at 13:24:14 UTC and was still in progress
at this retest checkpoint. It has not passed the three-hour acceptance criterion.
Coordinate-UI acceptance also remains open; the supervised trips do not close
either requirement.

### Shallow-dip slowdown correction — 2026-09-08

The operator reported crawling during apparently level crop work. A two-minute
native posture observation found no crouch/Shift intent or crouching pose in
599 samples. Actual support-height changes of 0.375 blocks were using the same
fixed 0.08 descent input as full-block stairs. Fifteen shallow descents averaged
2.82 seconds each in the 200 ms observation; these are sampled timings, not
complete event tracing or a controlled harvest throughput benchmark.

Terrain A* now gives bounded extra cost to actual support-height transitions,
including dips hidden by equal integer feet Y. It prefers short level detours
but keeps a required descent and does not force an excessively long detour.
Ordinary farmland lips, legacy waypoint cost and native collision/permission
checks are unchanged. The weights are a route preference, not measured travel
time or a guarantee of the fastest path.

Descent steering now uses observed ground damping and available landing distance
instead of fixed crawl input over the whole cell. The shorter fall-time bound
requires the existing native normal-physics gate; unknown gravity uses a complete
passive horizontal-coast bound. Airborne input remains zero. A changed native
physics condition, unsupported slippery damping or changed geometry can still
stop the move; the controller does not assign position/velocity or recover an
overshoot by widening the corridor. Simulated normal shallow/full descents took
39/43 ticks versus the previous 51/52; these are fixture results, not live speedups.

Java 17 / Gradle `test build` passed **1,049 tests across 84 suites**, without
failures, errors or skips. The new candidate SHA-256 is
`0571C60E4EB7BCF19A1479DD1675CC0F4733DECE5A8400BF83FE75E4F5EBECCD`.
Installation and matched real-client movement tests are still pending here.

The preceding continuous run had a manual interruption and later stopped on an
unconfirmed preserves pickup, so it is not uninterrupted multi-hour acceptance.
The existing live ledger subsequently reconciled the pickup without a manual
loss acknowledgement. The private trace stopped appending after a write error;
fresh latest snapshots continued, but missing history is not reconstructed.

### Deployed adaptive movement retest — 2026-09-08

This result supersedes the adaptive candidate's pending-installation status.
The same Society instance was normally restarted with the above
`0571C60E…F5EBECCD` JAR. Coordinate-only retests completed and returned to OFF
without interaction or inventory action tickets.

The cellar descent took 17.068 seconds versus the preceding 21.118-second trip.
Both began with a 22-node path and completed six one-block descents; their starting
positions differed by about four centimetres. This single pair is not a general
or causal speed benchmark. Of 343 client ticks, 333 were observed, with ten missing
ticks and five duplicate samples. All 33 observed airborne samples had no movement
intent, all six landings directly showed two quiet ticks, and the native
normal-physics gate was true throughout the captured samples.

A required 0.375-block dip completed its descent controller in approximately
2.20 seconds. The earlier fifteen-dip average of 2.82 seconds used different
starting poses and work contexts, so no improvement percentage is inferred.
A separate level detour completed in approximately 0.80 seconds: sampled native
height stayed constant while the path moved sideways by about one block. The
two-minute posture recording contained 599 samples with no crouching or Shift;
its 200 ms sampling does not reconstruct every intervening physics frame.

Continuous automation resumed at 13:57:34 UTC and remains under observation.
These movement retests do not establish three-hour uninterrupted acceptance or
close the separate coordinate-UI acceptance requirement.

The subsequent continuous window ended on detected manual input at 14:02:24 UTC,
after about four minutes fifty seconds. Its 290 one-second samples had no missing
sample indices, trace-write errors, navigation failure, native failure fence or
pending output; the maximum observation interval was 1.010 seconds. Final action
receipts were contiguous: 366 succeeded and the last was cancelled, with none
failed or unresolved. No automated wine/preserves production or actual sleep was
observed in this window. Machine-state changes outside the active work are not
credited to automation. The client remained OFF for manual control, and the
three-hour uninterrupted acceptance requirement remains unverified.

### Rolling three-edge stair preview — 2026-09-08

The descent controller now retains its measured braking and finite path through
straight stair runs. A rolling preview covers the active edge plus at most two
following edges. Only an observed grounded landing at the current destination
support advances one edge. Intermediate half-height treads do not advance the
path. Turns, the final landing, unavailable proof or a missing tick retain the
single-edge settling behavior; no airborne acceleration or jump is introduced.

The native read-only proof checks loaded geometry, full swept headroom, world
border, entities, normal surfaces and current native physics. Full-footprint
supports and ordinary bottom/straight stairs facing upstream are supported.
Stair collision shapes must exactly match the six required half-block octants;
corners, reversed/sideways/upside-down stairs and unknown partial shapes do not
gain this optimization. Doors, climbable blocks, fluids, hazards and protected
planting cells also reject the preview. Default adapters remain opt-out.

Java 17 / Gradle `test build` passed **1,073 tests across 86 suites**, with no
failures, errors or skips. This includes 13 native geometry tests and 11 rolling
descent tests. Four-direction, three-acceleration fixtures verify every physical
half-tread and destination landing, cancellation, changed support, lost proof
and observation gaps. A six-step full-block fixture took 174 versus 263 ticks;
the half-tread fixture took 162 versus 256 ticks. These are model comparisons,
not real-client speed measurements.

Candidate SHA-256:
`07AE3E54DC06D3806A49F08AA647D6161C09FA57B83E1A4CCC6C886F55696B64`.
The current client was manually paused and has not been restarted for this
candidate. Installation and a matched real-cellar retest remain pending operator
coordination. Earlier live timings belong to the previous artifact, not this
rolling-preview implementation. No registrations, cooldowns or action ledgers
were edited, and no live movement was initiated during this change.

### Deployed rolling stair retest — 2026-09-08

This supersedes the candidate's pending-installation status. The same existing
Society instance was normally closed, backed up and restarted with
`07AE3E54…55696B64`. Quick Play initially failed hostname resolution; a DNS/TCP
check and normal reconnect in that same client succeeded. No second game client,
system input hook, global DNS change or registration/cooldown reset was used.

Two cellar descents completed in **13.828 and 13.882 seconds**. The second used
the same initial 22-node path as the previous 17.068-second result, with starting
positions approximately four centimetres apart. This is a matched single-pair
observation, not a general throughput or causal benchmark. Both return ascents
also completed. Movement-only testing ended with zero interaction/inventory
action tickets, no navigation failure and unchanged full health.

The second descent directly observed all four handoffs between the five straight
stairs, each grounded at the planned support height with consecutive-tick
evidence. Intermediate half-height treads did not advance the path. All 33
observed airborne ticks had no movement intent. The final landing directly
showed two quiet ticks; two missing observations at the earlier turning landing
prevent the same complete claim there. Overall, 266 of 279 ticks were observed,
with 13 missing ticks and nine duplicate samples. The native normal-physics gate
was true throughout the 275 captured samples, with no reported failure.

Normal continuous mode was resumed at 14:59:04 UTC without altering module
schedules. Subsequent fresh observations showed tomato harvesting, advancing
action tickets and no navigation failure, action fence or pending output. This
short resumed observation does not establish uninterrupted multi-hour acceptance
or newly verify wine/preserves production and sleep.

### Half-tread speed and queued spruce replanting — 2026-09-09 KST

The stair controller now uses a freshly verified native half-tread fall horizon
and observed grounded damping on ordinary straight bottom stairs. Verified onward
continuation removes an intermediate center reserve without widening the active
corridor. Unknown shapes, observation gaps, changed physics, turns and final
landings retain conservative behavior. Airborne movement intent remains zero.
The six-stair Y-first collision-order fixture improved from 165 to 126 ticks;
this is a model comparison, not an installed-client timing.

The stopped logging run was diagnosed as an actual shortage: two spruce saplings
for four empty planting cells after the existing falling-tree and additional
arrival waits. No unconfirmed action, borrowed hotbar or output obligation was
cleared to resume it. The routine now retains replanting obligations in durable
FIFO order, works another unprocessed registered tree from the current batch
when necessary, and returns to the oldest empty plot when all of its missing
cells can be supplied. Manually emptied current-batch plots are checkpointed as
obligations, not action successes. Completed trees and new registrations outside
that batch are not enrolled to obtain more saplings.

If all such trees are exhausted, a validated inventory-only resource wait may
yield to other modules and sleep in continuous mode. It does not complete the
logging batch or waive uncertain actions, output ledgers, borrowed hotbar state,
partial growth or changed blocks. One-shot mode waits only for its selected job.
Re-supplied saplings regain priority at a safe module boundary, not during an
active operation. Stop/restart discards the transient permission to yield and
revalidates the durable unfinished work.

Java 17 / Gradle `test build` passed **1,096 tests across 86 suites**, with zero
failures, errors or skips. The focused logging/engine suite passed 118 tests.
Regressions cover two saplings followed by six from another tree, exhausted
trees with unfinished plots, strict oldest-first allocation, restart recovery,
manual felling, checkpoint rollback and refusal of partially grown or changed
plots. Stair fixtures include native Y-first collision order and opt-out cases.

Candidate SHA-256:
`4E91EF84BE4A1F197ED8D5FB73EEA88215FCBBAAE82F5117459910BB412D2950`.
At this checkpoint the player is manually controlling the existing client, which
still runs the preceding rolling-stair artifact. Restart coordination, native
queued-replant acceptance and a matched new stair timing remain pending. Manual
movement and inventory changes during development are not automation results.

### Live-discovered logging transit boundary failure — 2026-09-09 KST

The preceding queued-replant candidate was installed in the same Society client
after a normal shutdown and recoverable JAR backup. Registrations, schedules and
unfinished work were preserved. Initial hostname resolution failed; verified DNS
and TCP followed by a normal reconnect in the same client restored the session.

Continuous mode resumed, validated one manually repaired planting as complete,
then failed while approaching the next tree. It selected an 82-node route to an
irrelevant lower terrain-domain boundary before exhausting its cumulative search
budget. No chopping, planting, inventory actions or receipts occurred. Health,
inventory and the five remaining plots were preserved. This run did not reach
the queued-sapling behavior and is not a successful logging acceptance test.

The terrain search now permits domain-boundary travel only for a destination
outside that domain, with both the actual boundary stance and crossing making
verified geometric progress. Budget exhaustion no longer promotes a recorded
frontier into movement. Missing-chunk boundaries retain their separate behavior.
Logging transit may fall back to the existing independently verified general
one-block ascent outside legacy waypoint bounds. The planner and controller use
matching checks; neither protected planting nor an in-flight authority change
is allowed. A final ascent/descent frontier reanchors only after its actual quiet
landing instead of discarding the boundary and searching the old window again.

Java 17 / Gradle `test build` passed **1,113 tests across 88 suites**, zero
failures, errors or skips. The isolated navigation suite passed 210 tests,
including 17 new frontier, logging-approach and final-landing regressions.
Candidate SHA-256:
`0DFD52E2DCED6122B3E23C2B3D2829A14D12A2C839ED0F5CD6E5953077990EDE`.
Native recovery and repeat logging/stair acceptance are pending for this artifact.

### Recovery result and disabled-logging isolation — 2026-09-09 KST

The `0DFD52E2…77990EDE` artifact was normally installed and the same client
automatically reconnected. All 12 compared profile groups remained unchanged.
The native 65-node ascent returned from the lower boundary to the surface in
12.123 seconds from the first observed ON sample. No wrong-way descent occurred.
The subsequent surface-to-tree search still exhausted 65,536 nodes after another
42.893 seconds. No chopping, planting or work action receipt was observed. Thus
boundary recovery is verified, but access to the actual logging goal remains
unresolved; neither the new sapling queue nor matched stair descent speed has
passed native acceptance in this session. The high-frequency interval observed
1,055 of 1,101 ticks, with 46 missing ticks.

Later manual movement, settings changes and F8 activation are outside that test.
The user disabled logging, revealing a separate engine guard that treated its
five remaining plots as a global lock despite no replanting, borrowed slot or
unconfirmed action. The engine now grants a transient disabled-feature suspension
only at a clean native boundary. Durable lists, schedules and the saved OFF toggle
are not cleared or rewritten. Other modules may work and retry ordinary navigation;
re-enabling logging waits until their active work yields. A different one-shot
remains isolated. Action uncertainty, hotbar leases and output obligations remain
blocking conditions. An OFF transition from an existing sapling resource wait
also takes effect at the other module's clean DEFERRED boundary without querying
the disabled logging plot to approve that unrelated retry.

The user is now playing manually, so no further live control or restart is being
performed. A private read-only goal probe correctly refused the open settings
screen before querying geometry. Native goal/entrance inspection and installation
of the disabled-feature change remain pending; do not credit manual activity or
the older installed artifact as verification of this new engine behavior.

Final Java 17 / Gradle `test build` passed **1,127 tests across 89 suites** with
zero failures, errors or skips. The focused logging/engine suite passed 132 tests,
including 14 disabled-feature suspension regressions. Final candidate SHA-256:
`BECBCA6F1347E698D6928A1EEB3C83F603FBDF7F52E781A58A9227D6DCF619C7`.
This engine candidate is built but intentionally not installed while the user
plays; the running client remains on `0DFD52E2…77990EDE`.
