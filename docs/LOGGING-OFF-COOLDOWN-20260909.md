# Disabled logging at a normal production cooldown

A regression exposed an asymmetry between an error deferral and a normal wine-production cooldown. If logging had yielded at a clean resource-wait boundary, another module was busy, and logging was then disabled, the cooldown path could still consult the disabled logging plot's changed readiness and pause the whole engine.

Both paths now use the same clean-boundary transition. The transition preserves the saved cut/replant queues and grants no logging actions. Borrowed hotbar items, pending/native-uncertain actions, output debt, open menus, a carried cursor item, or airborne state still prevent this transition. The action port's existing fence remains authoritative.

The new positive regression failed before the change and passed afterward. Additional regressions retain rejection for eight unsafe boundary states and for an enabled logging job whose resource grant becomes unsafe. The focused five-suite run passed 59 tests. A subsequent full offline Java 17 `test build` succeeded.

This is distinct from the current live logging visibility issue. A logging-only reproduction found the first retained plot in `NO_VISIBLE_STANCE`, with no pending or failed native action. A separate read-only native probe examined all 1,476 candidate cells: 128 were standable and none offered a valid interaction with the registered bases. Higher logs in the same columns also produced no visible candidate in this inspected envelope. Of the bounded supplementary centre-ray examples, 61 first hit spruce leaves and three hit barrels. Centre-ray samples are illustrative, not substitutes for the complete native visibility predicate.

The cooldown change does not remove those leaves, make an occluded tree clickable, or complete its saved obligation. Deployment and a native test of the actual canopy issue must be reported separately.
