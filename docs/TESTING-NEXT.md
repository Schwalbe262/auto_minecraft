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
