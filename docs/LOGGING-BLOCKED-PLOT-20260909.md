# Logging blocked-plot scheduling

The reported stop was reproduced in a one-shot logging run. No new native
failure fence or in-flight action was present. The first retained tree had no
visible grounded grid stance; the module repeatedly waited on that same tree.
The earlier visibility recovery prevented a futile navigation search, but did
not make this tree harvestable or try the other retained trees first.

A private, read-only probe independently searched all five retained plots.
Only the first returned `NO_VISIBLE_STANCE`; the other four returned `FOUND`.
The first exhausted 1,476 stance candidates. These are current native visibility
observations, not proof of a connected route, a safe whole-tree footprint, or a
successful chop. No leaves or other terrain were removed by the probes.

The module now keeps temporary negative visibility observations for one sweep
and tries the other unprocessed registered plots before waiting. Saved remaining
and replant queues are not reordered or completed by a visibility result.
Oldest-first 2x2 replanting still takes priority when enough saplings exist.

When all remaining trees are hidden, the existing resource-wait scheduler retains
a real blocked-tree anchor. It can yield to other enabled routines in continuous
mode; one-shot mode remains isolated. A 1,200-tick retry starts a fresh sweep.
Actual disappearance of all stumps invalidates the sweep because felling can
expose a neighbouring tree. A selected tool, visible stance or partial chop ACK
does not count as a felled tree. Native reach, outline, axe, complete-tree proof,
inventory/cursor and unresolved-action checks are retained.

Validation: the focused logging suite passed 89 tests. The combined offline
Java 17 test/build passed 1,809 tests across 136 suites, with no failures or
errors. This includes all-hidden bounded sweeps, a hidden first tree with four
processable neighbours, oldest seed-short replanting, one-shot isolation,
whole-tree rejection, timed retry and non-current cached-chunk unloading.
The build was installed with a recoverable previous-JAR backup and a normal
single-client restart. The resumed native one-shot selected a different tree,
confirming that the original first-tree scheduling block was passed. No chop was
sent: terrain navigation then rejected the player's starting grid cell.

A second read-only native probe found the player grounded and collision-free,
but supported only by the edge of an adjacent stone floor. The block below the
player's centre was air. Neither the raw grid cell nor the cell above was a valid
navigation stance. This is a distinct start-position recovery issue, not evidence
that any logging action succeeded. Its correction and further native acceptance
remain in progress; no grid-cell substitution, teleport or terrain clearing was
used to bypass the finding.
