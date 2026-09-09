# Starfruit: finish the activated registered patch

The user observed one fruit being picked while ripe fruit remained on the same
tree. The old module selected only fruits within six blocks at activation and
also removed remaining fruits whenever movement put them outside six blocks.
That contradicted the requested behavior even for one compact tree. Current
native observation additionally found one omitted fruit registration: seven
registered cells versus eight actual fruit blocks in the same 3x3 ring.

Six blocks now controls activation only. The module freezes all initially loaded,
ripe members of that selected registered patch, rechecks their identity/ripeness,
and visits them before one storage pass. It does not add another patch, newly
loaded or newly ripe fruit. Actual four-block interaction/visibility, safe hand,
inventory room, native use confirmation and per-target approach bounds remain.
Day changes stop new uses but settle an already sent fruit use before storage.
The original interrupted job resumes after the storage pass.

At 07:25:00 UTC the same observed tree's missing eighth cell was added through
the loaded profile's normal validation/save path while automation was cleanly
paused. All eight native block types were checked first. Saved-profile comparison
showed only `fruitPatches` changed; schedules, storage definitions and other
settings were preserved. No whole-tree/forest discovery or automatic registration
permission was introduced into the production module.

The revised tests model movement that leaves the opposite side more than six
blocks away, initial distant registered fruit, exact interaction reach, excluded
unregistered/unloaded/unripe fruit, bounded failures, one deposit and exact
origin resumption. Date-boundary tests include pre-select, pending select and
pending fruit acknowledgements. All **1,695 tests / 127 suites** passed, including
54 starfruit-module tests. Artifact SHA256:
`E2FA3510F8D1FE1F944CC5BD11CEBE5AA6C8E00E16618FCF59700040D217A3D0`.

Live installation and full-patch harvest acceptance are pending. A completed pass
can still leave an unreachable fruit; completion text does not claim otherwise.
