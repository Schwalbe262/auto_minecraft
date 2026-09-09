# Relocated crystalarium group registration

The operator moved the jade crystalariums, expanded them to ten machines, and placed a barrel beside the new installation. The old artisan job still referenced three removed machines and the old barrel.

On 2026-09-10 KST the live client confirmed all ten new `society:crystalarium` blocks in a five-by-two arrangement and the adjacent `minecraft:barrel`. All ten machines were working and none was mature. The three old machine cells were loaded air.

After pausing at the normal settings boundary and creating private profile backups, a reviewed one-use registration operation changed exactly two fields through the normal profile saver:

- `artisanJobs.jade_crystalariums.machines`: the new ten positions.
- `commodityStores.jade_stock.containers`: the adjacent new barrel, shared for jade ingredients and outputs.

The existing job ID, recipe, accepted jade item, store name, input/output links, enabled features, every existing schedule, other work definitions and pending-state fields were preserved. New machine positions did not receive fabricated old completion dates. The existing workflow checks actual working/mature state and records the recipe's five-day deadline only after a confirmed feed. This registration did not collect or refill any machine, open a container, or move inventory items.

The normal save returned successfully. The operation verified live profile/action/session preservation, a separate disk comparison found all fields except the two requested position lists equal, and the existing passive runtime observer reported ten registered, loaded, working crystalariums. Continuous mode was resumed through the normal runtime start workflow at 16:44:54 UTC on 2026-09-09. This confirms registration and resumed mode, not completion of a fresh crystal production cycle.

No client restart or Java-code change was needed. Private coordinates, full profiles, backups and helper artifacts are not committed. The general saved-facilities UI currently does not enumerate `artisanJobs`; that presentation limitation is distinct from the actual ten-machine runtime registration and is not changed by this configuration operation. The earlier stair-recovery code build still awaits its separate restart/application.
