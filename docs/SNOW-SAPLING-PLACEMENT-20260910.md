# Direct spruce planting through one snow layer

The requested change is limited to snow-covered registered logging plots. No wine production code, calendar, interval, or user setting is changed.

## Implementation

- Treat exactly `minecraft:snow` with `layers=1` as an unplanted, directly replaceable cell alongside the three air variants. Initial plot snapshots preserve block properties, and the same classification is used for repair selection, missing-cell counts, retry readiness, and the final planting gate.
- Keep the existing requirement to have enough saplings for every missing cell of the registered 2x2 before beginning. Existing saplings are retained; no completion is recorded while a planting reply remains unconfirmed.
- Use the real native outline hit on the snow's upper surface, not a ray through snow to the hidden soil. Validate native `BlockPlaceContext` replacement and exact destination before calling the ordinary right-click placement. Normal air placement continues to use the supporting soil face.
- Confirm the exact original cell becomes spruce sapling/log through a newer raw server block reply in the same connection. Snow merely disappearing or client-predicted placement is not success.
- No snow-breaking/mining action, shovel workflow, arbitrary-block removal, or expanded reach is added. Multi-layer snow, snow blocks, powder snow, other plants and unrelated blocks remain excluded.

The installed mapped Minecraft 1.20.1 implementation was inspected locally: `SnowLayerBlock.canBeReplaced` permits a non-snow item only at one layer; `BlockPlaceContext` then places in that clicked cell. The one-layer outline is 1/8 block high despite its empty collision shape, so outline ray geometry is required.

## Automated verification

The initial offline `test build` passed **2,382 tests**, with zero failures/errors. This included 17 additional tests for one-layer classification, blocked thicker/foreign cells, all-four-cell material reservation, no completion before the final server reply, snow/air native hit geometry, exact native replacement destination, and navigation without terrain mutation. Previous obstruction regressions now explicitly use nonreplaceable two-layer snow.

## Installed mod compatibility discovered in the first live test

The first live run successfully dispatched ordinary sapling placement on snow, but the installed SnowRealMagic 10.7.0 preserves the snow as `snowrealmagic:snow` containing the plant in a block entity. The plain sapling/log block-only acknowledgement therefore timed out. This was a real test failure, not a successful planting acceptance result.

The follow-up fix recognizes only the exact installed snow block/entity type and contained spruce sapling. Current-world observations retain the original snow block ID and expose a narrow occupancy property; they never make a wrapper replaceable or fabricate an in-flight acknowledgement. Packet evidence is reduced from the actual vanilla block-entity update before application. Completion requires a fresh exact-target snow wrapper block plus its contained-sapling packet, with connection, ordering, negative-update and intervening-block invalidation checks. A duplicate identical wrapper update after the entity packet does not erase valid evidence, but changing away from the wrapper does. Existing vanilla sapling/log confirmation remains unchanged.

The installed Kiwi `ModBlockEntity` implementation uses ordinary `ClientboundBlockEntityDataPacket`. SnowRealMagic encodes default contained states with `Block`, or non-default states with `State`; these fields are parsed strictly without treating unrelated or malformed contents as spruce.

## Follow-up verification and deployment

- The final offline `test build` passed **2,399 tests**, with zero failures, errors or skipped tests. The 17 follow-up tests cover the installed contained-state schema, packet ordering and invalidation, bounded observation history, wrapped-sapling occupancy, remaining-cell reservation and restart behavior.
- Installed JAR SHA-256: `5D6BD0B909D7E9C85E8CBF23CFBB86D0A145075A0F0F1BC38F1BF31A6903BDD7`. The same modpack instance was normally closed, updated and restarted; no parallel game instance was launched. Previous JAR and profile backups are retained locally.
- Reconnected to the same server/profile and resumed the existing continuous routine. Passive observations confirmed the logging hotbar lease was restored normally and other crop/storage automation continued without a retained action failure.
- **Live direct-planting acceptance remains incomplete.** During the first-test failure and follow-up build/restart, the single planted sapling at `(647,75,1597)` became a spruce log while the other three cells remained snow. The existing partial-growth safeguard correctly defers that unfinished 2x2 and prevents a new logging planting pass. This is not evidence that the follow-up wrapper acknowledgement has passed a live test. No obligation, custody field or failed-action fence was cleared by a helper, and that tree was not automatically removed. Clearing this one prematurely grown tree is required before the normal routine can perform the remaining live snow-planting check.
- No wine production code, user-configured wine interval or wine due-date was edited.
