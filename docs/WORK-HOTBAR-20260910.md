# Automatic working hotbar slot — 2026-09-10

## Behavior

Seed makers, crystal copiers and starfruit harvesting now prepare their own temporary working slot when all hotbar slots are occupied. This replaces a hotbar-only capacity deferral when a safe item and a free main-inventory slot are available.

1. Choose one non-working hotbar item and an empty main-inventory slot. Configured hoe/axe slots, hoes, vintage items and the owner's input/output/bonus items are excluded.
2. Persist the original item identity, native fingerprint and both slot indices before sending the normal inventory swap.
3. Wait for the existing server-confirmed swap ticket; do not resubmit pending or uncertain clicks.
4. Run the normal feature with the prepared slot. The parked original item is reserved against unrelated transfers and disposal.
5. Restore the original hotbar item before yielding to another job or completing a one-shot task. Any remaining working item returns to the temporary main-inventory slot.

F8 OFF stops immediately, without performing a surprise restoration click after OFF. The restoration record survives OFF and restart. The next explicit start restores first, even if the original feature has since been disabled. A requested one-shot starts only after restoration and its normal startup checks.

Wine and preserves retain their existing ingredient/hotbar management. This change does not modify production intervals, harvest routing, recipe acknowledgments or sale rules.

## Integrity and limits

- No item is discarded or overwritten to create space. A truly full main inventory still needs capacity relief.
- Readiness queries never move items. One owner and one temporary slot are permitted at a time; logging custody cannot overlap.
- Original-item restoration requires a matching server inventory observation and live endpoint identity/fingerprint, not only a visually matching client slot.
- A non-mutating inventory refresh may obtain current evidence. An interrupted RESTORING swap is never blindly replayed.
- If custody cannot be proven, retain the record and stop safely; this is distinct from the ordinary full-hotbar condition now handled automatically.
- Profiles remain schema 6 until a work-slot record is first persisted. That upgrades the profile to schema 7, retained after restoration, so an older client cannot silently drop the obligation. Keep a profile backup when rolling back the mod.
- Local diagnostics expose `workHotbar` owner/stage/slot indices, or an empty object when no restoration is owed.

## Verification

Offline `test build` initially passed 2,284 tests across 171 suites. After the logging follow-up below, the final build passed **2,297 tests across 172 suites; 0 failures, errors or skips** (2026-09-10 17:12 KST). New coverage includes persistent lease validation, native restoration evidence, action isolation, long pending ACKs without duplicate swaps, complete artisan and starfruit lifecycles, manual OFF/resume and disabled-owner restoration before an unrelated one-shot. Module fixtures exercise the normal safety policy; mocked receipts are not claimed as live network validation.

Changes are split into persistence, native custody protection, artisan/controller integration and orchard integration commits.

Built/installed artifact SHA-256:

`1D759542E43DC1F6BF967738D6393ECD833E259D9DC17BC9585A5A2203C08197`

The existing Society Sunlit Valley 4.1.4 instance was normally closed and restarted with this artifact. The previous mod and profile were backed up locally. No extra game instance or global keyboard hooks were introduced.

## Additional logging case found during the live check

The first live check progressed through ancient-fruit wine, orchard checking and logging, then exposed a separate pre-existing logging reservation failure. The original sword was still in its parked inventory slot, but spruce logs had naturally filled the working hotbar slot after the saplings were consumed. The old check accepted only saplings or an empty slot and incorrectly treated the logs as lost custody.

This is not evidence that the newly added work-slot allocator ran in the live session: `workHotbar` remained empty during that check. Full-hotbar allocation and restoration are covered by the automated tests; live validation is reported separately.

The logging follow-up accepts only the existing logging items (saplings, spruce logs, fire logs, twigs and mossberries, at most 64 per nonempty stack) in the working endpoint. Exact original-item data/fingerprint and native inventory evidence remain required. If fresh saplings need a full working slot, the existing restoration path first returns the original item, waits for its acknowledgment, then prepares saplings normally. Unknown items, changed originals and uncertain inverse clicks remain blocked. Thirteen additional tests cover this reproduced case and its rejection boundaries.

The follow-up build was installed by another normal restart; server reconnection and OFF state were confirmed at 17:15 KST. The user had resumed manual play, so automation is left OFF rather than automatically taking control again. Live execution of this final logging fix still needs the next explicit F8 start.

## Wine expansion button diagnosis

**Follow-up:** the unnecessary logging-completion requirement described below has now been removed; see [the wine edit-gate fix and live button verification](WINE-EDIT-GATE-20260910.md). The paragraph below records the earlier diagnosis, not a current requirement to finish logging first.

The custom wine-line expansion button requires `wineLineSettingsEditable()`. That currently rejects an active unfinished logging batch even while automation is OFF. A retained logging run therefore disables ancient-fruit wine expansion too; moving nearer the kegs or restarting alone does not clear that batch.

After restoring and completing the logging work, reopen **Ctrl+F8 → saved locations → ancient-fruit wine → expand this wine region** and select the new nearby kegs. The legacy tomato-wine entry is not the custom-line expansion screen. No facility coordinates or expansion choices were changed on the user's behalf, and the edit safety gates were not bypassed.
