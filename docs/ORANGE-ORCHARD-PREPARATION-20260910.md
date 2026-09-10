# Orange orchard preparation — 2026-09-10

## Requested scope

The operator recorded orange harvesting with the Cornucopia and requested that the work be added in preparation only, because no orange store exists yet. This change adds an explicitly non-executable orchard draft. It does not add a runnable harvesting module, item-use permission, storage role, selling rule or automatic feature activation.

Open **Ctrl+F8 → 실행·기록 → 과수원 준비 항목** to inspect the draft and its observed fruit cells. A future storage registration alone does not silently turn it into an executable routine; the native item-use and collection/storage workflow still needs to be connected and verified.

## Evidence

The local `오렌지수확` recording contains 63 distinct observed `pamhc2trees:pamorange` cells and an observed orange inventory increase to 63. The tool is `society:cornucopia`; the collected item is `atmospheric:orange`.

The installed pack's `kubejs/server_scripts/itemEvents/cornucopia.js` handles main-hand item right-use and applies a 40-tick cooldown. `kubejs/startup_scripts/powerfulMachines/drumCornucopia.js` scans the inclusive volume x/z ±10 and y −2..+10 around the player's native on-position plus one. It resets supported mature fruit blocks and spawns their drops. The same use can harvest other supported species, including starfruit, so an orange-only single-block click is not an equivalent implementation.

The old recorder did not record `ServerboundUseItemPacket`. Fruit updates and pickup observations therefore do **not** prove an exact activation origin or click count. Imported positions remain observations, not harvesting stations, reach exemptions or permission to harvest an entire surrounding forest.

## Implementation boundaries

- `Profile.orchardDrafts` contains typed, bounded observations. Validation checks item IDs, coordinates, duplicate positions and identifiers, and metadata limits. Draft positions are deliberately excluded from navigation/work-area authorization.
- `WorkRegistrationImport` supports explicit add-only drafts, rejects conflicting replacements and unexpected draft fields, and cannot combine a nonempty draft import with feature activation. Existing schedules, running batch checkpoints, reserves and feature switches remain unchanged.
- The preparation screen is read-only, paginated, and explicitly shows no storage and no automatic execution. It has no ON, start, promotion or guessed-store operation.
- The passive recorder now captures block-use and air/item-use intents separately, including hand and native sequence. Item/position observations are collected on the client thread and explicitly marked as unverified, later observations rather than send-time truth or server-success evidence. Chat, commands, custom payloads, identity and raw NBT remain excluded.

No automatic orange harvest is performed as part of this preparation.
