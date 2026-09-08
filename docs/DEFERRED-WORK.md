# Deferred work

Complete and verify the existing wine/preserves production, storage, sale and sleep
workflow before beginning these user-requested improvements:

- Further wine-rack optimization and interaction-latency measurement. Basic
  current-position/aisle-aware visit selection is now being handled at the user's
  renewed request, together with grouped storage-wall visits. Keep one outstanding
  native action and server acknowledgement, without desktop input interception.
- Tomato harvesting: continuous tractor-like passes along farm rows.
- Fruit-tree harvesting and commodity storage, using the supplied demonstration
  as evidence rather than replaying every main/off-hand event or incidental click.
- General storage groups: a name, accepted commodity and container members;
  convenient bulk/range registration, and cross-container partial-stack packing.
  Tomato-specific commodity-only permissions and actual per-grade input counting
  are being handled now because the old grade restrictions blocked production.
- Waypoint visibility on or integration with the map opened by J. Coordinate
  lists alone are hard to identify in the world.
- Configurable surplus-tomato sales after the existing workflow is stable.
  Use a storage-group fill threshold, initially 90%, editable in settings
  (for example 80% or 90%), with a separate feature ON/OFF switch. Retain the
  configured reserve and sell only the excess through the registered smart
  shipping bin; reaching the threshold must not sell the entire tomato stock.
  Define capacity from the group's actual supported containers and stack limits,
  accounting for partial stacks and non-stackable grades. Unknown/stale capacity
  must not authorize a sale. This is a deferred request, not an enabled sale rule.

These are recorded requests, not claims of implemented features. Keep private
world positions, storage inventories and demonstration recordings out of Git.
