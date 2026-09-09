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

The historical TrashSlot failure at ticket 683 also lacks a retained exception
cause/buffer snapshot. The later current buffer contained disposable rotten
tomatoes; that later observation does not establish the historical cause.
