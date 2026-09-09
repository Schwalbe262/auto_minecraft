# Wine inventory stability

## Confirmed hotbar-swap failure

The installed client stopped at ticket 1186 during wine production. A suspended
logging queue caused the old exact logging-swap validator to handle the normal
tomato refill, so the UI misleadingly reported a logging failure.

Read-only inspection on September 9, 2026 established:

- Dispatch generation 5, normal menu 0, sequence 11878, source 27 / hotbar menu slot 41.
- The actual subsequent server FULL packet 11879 exchanged tomato x4 and wine x1.
- The tomato endpoint and the other 44 slots were exact; both cursors were empty.
- The wine endpoint differed only by the installed Vinery implementation's exact,
  reproducible passive initialization/cache update, evaluated on a detached copy.
- At 08:24:55 UTC, the current native 46-slot inventory exactly matched that FULL.

No click was repeated and no old failure outcome or custody fence was rewritten.
The correction accepts this narrow native metadata change, not arbitrary changes
to item counts, quality, vintage, custom tags, or unrelated slots. All hotbar swaps
now use the native receipt tracker independently of suspended logging state.
Messages distinguish hotbar exchange from an actual logging operation.

Eleven adversarial metadata/endpoint tests were added. The first integrated
offline `test build` passed **1,716 tests in 128 suites**, zero failures/errors/skips.
That build also contained the crop-order and pressure-aware output-merge changes.
This is not yet a claim of post-install live acceptance or hours of stability.

## Optional inventory rearrangement

The earlier consolidation timeout at ticket 1035 was observed with substantial
free inventory space. Its exact native baseline was no longer retained, so its
precise rejected comparison is unknown. It must not be described as conclusively
the same bug as ticket 1186.

Wine output rearrangement is now planned only at two or fewer actual empty
normal-inventory slots. Three or more empty slots skip the optional planning step.
Partial stacks do not masquerade as guaranteed native capacity. Input merging,
preserves processing, native ACK rules and six-day wine scheduling are unchanged.

A second, explicit opt-in handles a changed baseline for the next **unsent**
optional wine-output rearrangement. It terminates as `SKIPPED` with a dedicated
proof and zero confirmed progress, never as a successful merge. The action owner
must establish the same context/profile/session/world and connection generation,
normal 46-slot inventory/menu 0, empty cursor, current permissions and protected
hoe configuration, no borrowed-item restoration, no output debt or other failure
fence, and an uncancelled/unexpired transaction budget.

An earlier outward swap to an empty scratch slot may already have an exact ACK;
abandoning its next unsent merge leaves the product in that normal inventory
slot. An actual borrowed scratch item, in-flight click, timeout, settings change
or disconnect cannot use this path. No timeout is reset and no previous click is
resent. The machine rejects the current layout for the rest of that output step
and continues from observed inventory. Inputs and preserves cannot consume this
wine-only skip result.

Seventeen guard/contract/module regressions cover these boundaries, including
continuous and one-shot continuation through normal wine storage. The final
integrated offline build passed **1,735 tests in 130 suites**, with no failures,
errors or skipped tests. Artifact SHA-256:
`AEE1C2F8DC7096255017ABD3AB76D40723A973B95A895904BE5018B68028A0E6`.
This does not retroactively resolve ticket 1035 or claim a live skip was observed.

The historical TrashSlot failure at ticket 683 also lacks a retained exception
cause/buffer snapshot. The later current buffer contained disposable rotten
tomatoes; that later observation does not establish the historical cause.
