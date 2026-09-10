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

Full offline `test build` passed **2,382 tests**, with zero failures/errors. This includes 17 additional tests for one-layer classification, blocked thicker/foreign cells, all-four-cell material reservation, no completion before the final server reply, snow/air native hit geometry, exact native replacement destination, and navigation without terrain mutation. Previous obstruction regressions now explicitly use nonreplaceable two-layer snow. Native block-placement behavior itself still requires live verification; pure tests are not a claim of a completed in-game planting run.
