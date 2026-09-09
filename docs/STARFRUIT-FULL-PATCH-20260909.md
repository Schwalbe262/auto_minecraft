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

## Native acceptance

The artifact was installed after normal shutdown at 07:26:44 UTC in client
51508. The same Society instance reconnected normally. Ordinary coordinate
navigation placed the player at a verified standing cell near the tree; no
position/velocity injection or OS input was used.

Immediately before the one-shot start at **07:30:20.737 UTC**, a read-only probe
observed six mature fruit among the registered eight and zero held fruit. The
player was at `(674.5234,72,1569.1423)`, leaving some left-side members beyond the
initial six-block radius. Native tickets **1–6 each confirmed fruit interaction**.
Only then was the store opened: ticket **8 confirmed six fruit deposited**, with
one open (7) and one close (9). A fresh post-pass read observed **zero mature
fruit and zero held fruit**. Some harvested blocks had already advanced a growth
stage; the acceptance is all six original ripe fruit handled, not every final
age being exactly zero.

The missing eighth registration participated in this same completed pass.
Unripe members were not forcibly harvested. This accepts the full activated
patch one-shot on the actual tree. Exact original-job resumption is additionally
covered by the detached regression; this one-shot does not independently prove
that continuous interruption/resumption branch or multi-hour uptime.
