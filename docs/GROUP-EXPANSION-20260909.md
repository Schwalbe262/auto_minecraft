# Adding new machines and storage to a saved group

After installing the updated client:

1. Stand near the new blocks, open Ctrl+F8 and choose the existing group on the Saved page.
2. Open the group's expansion/rescan screen. It reads loaded blocks around the player (configured radius capped at 32 horizontally, 16 vertically); it does not move, click machines or open containers.
3. Select the intended newly discovered connected batches. All candidates start unselected; existing registrations are excluded. Switch to individual rows if a connected batch also includes unwanted blocks.
4. Review the exact selected positions and confirm once. Storage additionally requires confirmation that its contents belong to the target store's permitted items; closed-container contents are not claimed to have been read automatically.

`R` rescans while the expansion screen is open; it is not a global registration hotkey. Repeated Ctrl+F8 per block is unnecessary for a batch. Nothing is automatically registered merely because it is nearby.

Supported targets are named wine-keg/preserves groups, legacy tomato storage, and named commodity storage such as ancient fruit. An unnamed legacy machine group must first be given a saved name. Wine **storage** retains its separate age/cohort ordering and is not added through this generic item-store screen.

Before saving, selected blocks are read again. Changed/unloaded blocks, stale profile registrations, already reserved positions and overlapping physical double-chest halves are rejected. Only the selected new members are appended. Existing members, production dates, pending outputs, wine batches, item filters and wine-storage cohorts are not rewritten. A save exception restores the original registration collections in memory.

The connected batch is a selection convenience, not evidence of the user's ownership or intent: separately placed unrelated machines remain unselected unless the user explicitly includes them.

The complete integration build passed 1,800 tests across 136 suites with no failures or errors, including 12 expansion regressions. The packaged JAR SHA-256 is `9CB589A5B5A19F7DD1435C50F731C4EF7968DBA3B736F40FE7DCFE381C49194A`. The new expansion screen has not yet been exercised in the live game: deployment/restart is waiting for the protected TrashSlot recovery item to be recovered or explicitly discarded. Only the requested existing-384 metadata merge is already live.
