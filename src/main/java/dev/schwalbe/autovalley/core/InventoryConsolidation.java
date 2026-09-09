package dev.schwalbe.autovalley.core;

import java.util.*;

/**
 * Bounded cursor-free transaction, independent of Minecraft classes for testing.
 * Fingerprints are opaque client-visible native stack identities, NEVER public
 * quality/year summaries. The server's native click still decides compatibility.
 * No state from this transaction is exported or persisted.
 */
public final class InventoryConsolidation {
    public record Stack(String identity, int count, int limit) {
        public static final Stack EMPTY = new Stack("",0,64);
        public Stack {
            if (identity==null || count<0 || limit<1 || count>limit || (count==0)!=identity.isEmpty())
                throw new IllegalArgumentException("Invalid native stack snapshot");
        }
        public boolean empty() { return count==0; }
        boolean sameKind(Stack other) { return !empty() && !other.empty() && identity.equals(other.identity) && limit==other.limit; }
    }
    public record Snapshot(List<Stack> items) {
        public Snapshot { items=List.copyOf(items); if (items.size()!=36) throw new IllegalArgumentException("Expected 36 inventory slots"); }
        public int emptySlots() { return (int) items.stream().filter(Stack::empty).count(); }
    }
    public enum Type { SWAP, QUICK_MOVE }
    public record Click(Type type,int sourceIndex,int hotbar) { }
    public enum Confirmation { WAIT, NEXT, COMPLETE }
    private enum Stage { SWAP_OUT, MERGE, RESTORE, DONE }
    private final ProductionMergePlanner.Plan plan;
    private final Snapshot initial;
    private Snapshot before;
    private Stage stage;
    private int acknowledgedPrimitives;

    public InventoryConsolidation(ProductionMergePlanner.Plan plan, Snapshot initial) {
        this.plan=Objects.requireNonNull(plan); this.initial=initial; this.before=initial;
        if (plan.sourceIndex()<0 || plan.sourceIndex()>=36 || initial.items().get(plan.sourceIndex()).empty()
            || plan.scratchHotbar() < -1 || plan.scratchHotbar()>8
            || !plan.direct() && plan.sourceIndex()<9) throw new IllegalArgumentException("Invalid consolidation plan");
        stage=plan.direct() ? Stage.MERGE : Stage.SWAP_OUT;
    }
    public Snapshot expectedLive() { return before; }
    public boolean complete() { return stage==Stage.DONE; }
    /** A confirmed outward SWAP keeps its borrowed item owed until exact restoration. */
    public boolean requiresRestoration() {
        return !plan.direct() && plan.requiresRestore() && acknowledgedPrimitives>0 && stage!=Stage.DONE;
    }
    public int freedSlots() { return Math.max(0,before.emptySlots()-initial.emptySlots()); }
    public Click click() {
        return switch(stage) {
            case SWAP_OUT, RESTORE -> new Click(Type.SWAP,plan.sourceIndex(),plan.scratchHotbar());
            case MERGE -> new Click(Type.QUICK_MOVE,plan.quickMoveIndex(),-1);
            case DONE -> throw new IllegalStateException("Transaction finished");
        };
    }

    /** Read-only structural eligibility; never proves packet origin or item type. */
    public boolean allowsConcurrentAddition(int index) {
        if (!nonparticipant(index)) return false;
        return stage!=Stage.MERGE || index!=plan.quickMoveIndex()
                && (index<9)==(plan.quickMoveIndex()<9);
    }

    /**
     * A different native identity cannot be a receiver of this QUICK_MOVE, even
     * in its destination region. It may therefore be separated from the exact
     * conserved move when an actual server reply proves a positive addition.
     * The native caller still proves the item whitelist and packet origin.
     */
    public boolean allowsConcurrentAddition(int index,Stack after) {
        if (!nonparticipant(index) || after==null || after.empty()) return false;
        Stack old=before.items().get(index);
        return after.count()>old.count() && (old.empty() || old.sameKind(after))
                && !after.identity().equals(initial.items().get(plan.sourceIndex()).identity());
    }

    /** The outgoing exact SWAP can empty its source before a distinct pickup arrives there.
     * Only an originally empty scratch is eligible: no borrowed item may be hidden.
     * This is structural eligibility, not evidence of either packet origin or item identity. */
    public boolean allowsPostSwapSourceAddition(int index,Stack after) {
        return stage==Stage.SWAP_OUT && !plan.direct() && !plan.requiresRestore()
            && index==plan.sourceIndex() && before.items().get(plan.scratchHotbar()).empty()
            && after!=null && !after.empty()
            && !after.identity().equals(before.items().get(plan.sourceIndex()).identity());
    }

    /** Structural candidate only. Native code must prove the receiver's exact metadata refresh. */
    public boolean allowsReceiverMetadataUpdate(int index,Stack after) {
        if (stage!=Stage.MERGE || !nonparticipant(index) || after==null || after.empty()
                || (index<9)==(plan.quickMoveIndex()<9)) return false;
        Stack old=before.items().get(index),source=before.items().get(plan.quickMoveIndex());
        return !old.empty() && after.count()>old.count() && old.limit()==after.limit() && after.limit()<=64
            && !old.sameKind(after) && source.sameKind(after);
    }

    private boolean nonparticipant(int index) {
        return stage!=Stage.DONE && index>=0 && index<36
                && index!=plan.sourceIndex() && index!=plan.scratchHotbar()
                && (stage!=Stage.MERGE || index!=plan.quickMoveIndex());
    }

    /**
     * Refreshes a completed primitive's baseline without acknowledging or sending
     * another click. The native caller must prove every supplied change using
     * authoritative slot packets and independently restrict the allowed item
     * deltas; this pure transaction has no packet or item-type information.
     *
     * The original source and borrowed hotbar slot remain exact throughout the
     * transaction. Before QUICK_MOVE, its implicit destination region also remains
     * exact unless a separately verified positive production pickup has a native
     * identity that cannot receive that move. The legacy overload grants no such
     * exception. Before
     * RESTORE, only the two exact swap partners participate in the remaining click.
     * A rejected refresh leaves both the baseline and progress untouched.
     */
    public boolean rebaseVerifiedUpdates(Snapshot after,Set<Integer> verifiedUpdates) {
        return rebaseVerifiedUpdates(after,verifiedUpdates,Set.of());
    }

    /**
     * In addition to ordinary untouched-slot refreshes, a separately proven
     * production pickup may fill an EMPTY borrowed slot after the merge ACK.
     * Both earlier primitives must already be confirmed, and the original source
     * must still contain the exact borrowed item. The next click remains the same
     * exact inverse SWAP, returning that item and retaining the new pickup in the
     * original source slot. No in-flight click is acknowledged by this method.
     * Native callers must prove the positive additions' item whitelist and raw
     * packet provenance; the legacy overload never permits this scratch exception.
     */
    public boolean rebaseVerifiedUpdates(Snapshot after,Set<Integer> verifiedUpdates,
                                         Set<Integer> verifiedProductionAdditions) {
        return rebaseVerifiedUpdates(after,verifiedUpdates,verifiedProductionAdditions,Set.of());
    }

    /**
     * A server-authored pickup after the outward SWAP ACK and BEFORE sending
     * QUICK_MOVE may fill one of that still-unsent move's implicit receivers.
     * Unlike a concurrent addition inside a move ACK, the same native identity
     * is safe here: the refreshed count is part of the next conservation baseline.
     * The native caller must separately prove post-ACK packet origin and must
     * never call this to acknowledge or rebase an in-flight primitive.
     * The legacy overloads and all move-ACK rules remain unchanged.
     */
    public boolean rebaseVerifiedUpdates(Snapshot after,Set<Integer> verifiedUpdates,
                                         Set<Integer> verifiedProductionAdditions,
                                         Set<Integer> verifiedPreMergeReceiverAdditions) {
        if (after==null || verifiedUpdates==null || verifiedProductionAdditions==null
                || verifiedPreMergeReceiverAdditions==null || acknowledgedPrimitives==0
                || stage!=Stage.MERGE && stage!=Stage.RESTORE) return false;
        for (Integer index:verifiedPreMergeReceiverAdditions) {
            if (index==null || index<0 || index>=36 || stage!=Stage.MERGE || acknowledgedPrimitives!=1
                    || plan.direct() || !nonparticipant(index)
                    || (index<9)==(plan.quickMoveIndex()<9)
                    || !verifiedUpdates.contains(index) || !verifiedProductionAdditions.contains(index)) return false;
            Stack old=before.items().get(index),now=after.items().get(index);
            if (now.empty() || now.limit()>64 || now.count()<=old.count()
                    || !old.empty() && !old.sameKind(now)
                    || !before.items().get(plan.quickMoveIndex()).sameKind(now)) return false;
        }
        for (Integer index:verifiedProductionAdditions) {
            if (index==null || index<0 || index>=36 || !verifiedUpdates.contains(index)) return false;
            Stack old=before.items().get(index),now=after.items().get(index);
            if (now.empty() || now.count()<=old.count() || !old.empty() && !old.sameKind(now)) return false;
        }
        for (Integer index:verifiedUpdates) {
            if (index==null || index<0 || index>=36
                    || index==plan.sourceIndex()) return false;
            if (index==plan.scratchHotbar() && (!allowsRestoreScratchPickup()
                    || !verifiedProductionAdditions.contains(index) || after.items().get(index).limit()>64)) return false;
            if (stage==Stage.MERGE && (index==plan.quickMoveIndex()
                    || (index<9)!=(plan.quickMoveIndex()<9)
                        && (!verifiedProductionAdditions.contains(index)
                            || !allowsConcurrentAddition(index,after.items().get(index))
                                && !verifiedPreMergeReceiverAdditions.contains(index)))) return false;
        }
        for (int index=0;index<36;index++) {
            if (!before.items().get(index).equals(after.items().get(index))
                    && !verifiedUpdates.contains(index)) return false;
        }
        before=after;
        return true;
    }

    private boolean allowsRestoreScratchPickup() {
        return stage==Stage.RESTORE && acknowledgedPrimitives>=2 && !plan.direct()
                && !initial.items().get(plan.scratchHotbar()).empty()
                && before.items().get(plan.scratchHotbar()).empty()
                && before.items().get(plan.sourceIndex()).equals(initial.items().get(plan.scratchHotbar()));
    }

    /**
     * An already-sent inverse SWAP may follow a server-proven pickup into its
     * previously EMPTY scratch. The native caller must prove that packet after
     * dispatch and before the full SWAP reply, including any exact native cache
     * refresh. verifiedPickup is the final, fully proven item in the original
     * source, not permission to infer a pickup from the final inventory alone.
     * This opt-in never rebases an in-flight primitive or acknowledges a MERGE.
     */
    public Confirmation acknowledgeRestorePickup(Snapshot after,Stack verifiedPickup) {
        if (!allowsRestoreScratchPickup() || after==null || verifiedPickup==null
                || verifiedPickup.empty() || verifiedPickup.limit()>64) return Confirmation.WAIT;
        List<Stack> expected=new ArrayList<>(before.items());
        expected.set(plan.scratchHotbar(),verifiedPickup);
        Collections.swap(expected,plan.sourceIndex(),plan.scratchHotbar());
        if (!expected.equals(after.items())) return Confirmation.WAIT;
        before=after;stage=Stage.DONE;acknowledgedPrimitives++;
        return Confirmation.COMPLETE;
    }

    /**
     * Resolves only a cancelled transaction's confirmed borrowed item using an
     * independently observed exact inverse SWAP. This does not prove a merge,
     * retry an operation, or change the failed/cancelled action's outcome.
     */
    public boolean acknowledgeCancelledRestoration(Snapshot after) {
        if (!requiresRestoration() || after==null || before.equals(after)) return false;
        List<Stack> restored=new ArrayList<>(before.items());
        Collections.swap(restored,plan.sourceIndex(),plan.scratchHotbar());
        if (!restored.equals(after.items())) return false;
        before=after;stage=Stage.DONE;
        return true;
    }

    /** An unrelated/unchanged refresh never authorizes a subsequent primitive. */
    public Confirmation acknowledge(Snapshot after) {
        return acknowledge(after,Set.of());
    }

    /**
     * The native adapter may prove a passive metadata update using the installed
     * item's own initialization/cache functions. Such proof is valid only for
     * untouched slots, never the source, swapped partner or a receiving stack.
     * This method still requires the actual native permutation/conserved move;
     * metadata alone cannot advance the transaction. Raw ACK identities become
     * the next baseline only after the complete primitive is verified.
     */
    public Confirmation acknowledge(Snapshot after,Set<Integer> verifiedPassiveUpdates) {
        return acknowledge(after,verifiedPassiveUpdates,Set.of());
    }

    /**
     * A genuine full server ACK may contain an unrelated production-item pickup.
     * The native caller verifies that item's whitelist and authoritative origin;
     * only nonparticipant slots or the proven emptied outward-SWAP source with
     * positive additions of a different native identity are eligible here. A temporary comparison baseline removes
     * those additions from the click proof, but the actual permutation/conserved
     * move must still be present.
     * Failed/no-op ACKs never change the baseline, stage or completed-click count.
     */
    public Confirmation acknowledge(Snapshot after,Set<Integer> verifiedPassiveUpdates,
                                    Set<Integer> verifiedProductionAdditions) {
        return acknowledge(after,verifiedPassiveUpdates,verifiedProductionAdditions,Set.of());
    }

    /**
     * A full native reply may show a receiver initialized/refreshed immediately
     * before this QUICK_MOVE. The native adapter must reproduce the exact item's
     * metadata operation at the OLD receiver count, independently of the move.
     * Only that proven receiver identity is normalized for comparison: its old
     * count stays unchanged, and validNativeMove must still prove every unit
     * moved/received. Source, borrowed partners and every legacy overload remain
     * strict. No ACK is inferred from metadata alone.
     */
    public Confirmation acknowledge(Snapshot after,Set<Integer> verifiedPassiveUpdates,
                                    Set<Integer> verifiedProductionAdditions,Set<Integer> verifiedReceiverMetadataUpdates) {
        if (stage==Stage.DONE || after==null || before.equals(after)) return Confirmation.WAIT;
        if (verifiedPassiveUpdates==null || verifiedProductionAdditions==null || verifiedReceiverMetadataUpdates==null)
            return Confirmation.WAIT;
        List<Stack> comparisonItems=new ArrayList<>(before.items());
        for (Integer index:verifiedReceiverMetadataUpdates) {
            if (index==null || index<0 || index>=36 || verifiedPassiveUpdates.contains(index)
                    || verifiedProductionAdditions.contains(index) || !allowsReceiverMetadataUpdate(index,after.items().get(index)))
                return Confirmation.WAIT;
            Stack old=before.items().get(index),now=after.items().get(index);
            comparisonItems.set(index,new Stack(now.identity(),old.count(),old.limit()));
        }
        for (Integer index:verifiedPassiveUpdates) {
            if (index==null || index<0 || index>=36) return Confirmation.WAIT;
            if (stage==Stage.SWAP_OUT || stage==Stage.RESTORE) {
                if (index==plan.sourceIndex() || index==plan.scratchHotbar()) return Confirmation.WAIT;
            } else if (index==plan.quickMoveIndex()) return Confirmation.WAIT;
            Stack old=before.items().get(index),now=after.items().get(index);
            if (old.empty() || now.empty() || old.count()!=now.count() || old.limit()!=now.limit()) return Confirmation.WAIT;
            comparisonItems.set(index,now);
        }
        for (Integer index:verifiedProductionAdditions) {
            if (index==null || index<0 || index>=36 || verifiedPassiveUpdates.contains(index)) return Confirmation.WAIT;
            Stack now=after.items().get(index);
            if (allowsPostSwapSourceAddition(index,now)) continue; // Verify against the post-SWAP empty slot below.
            if (!allowsConcurrentAddition(index,now)) return Confirmation.WAIT;
            comparisonItems.set(index,now);
        }
        Snapshot comparisonBefore=new Snapshot(comparisonItems);
        if (comparisonBefore.equals(after)) return Confirmation.WAIT;
        if (stage==Stage.SWAP_OUT || stage==Stage.RESTORE) {
            List<Stack> expected=new ArrayList<>(comparisonBefore.items());
            Collections.swap(expected,plan.sourceIndex(),plan.scratchHotbar());
            if (verifiedProductionAdditions.contains(plan.sourceIndex())
                && allowsPostSwapSourceAddition(plan.sourceIndex(),after.items().get(plan.sourceIndex()))) {
                if(!expected.get(plan.sourceIndex()).empty())return Confirmation.WAIT;
                expected.set(plan.sourceIndex(),after.items().get(plan.sourceIndex()));
            }
            if (!expected.equals(after.items())) return Confirmation.WAIT;
            before=after;
            stage=stage==Stage.SWAP_OUT ? Stage.MERGE : Stage.DONE;
        } else {
            if (!validNativeMove(comparisonBefore,after,plan.quickMoveIndex())) return Confirmation.WAIT;
            before=after;
            // Original EMPTY scratch needs no inverse swap: native QUICK_MOVE may
            // have placed the item into the now-empty original source slot itself.
            stage=plan.requiresRestore() ? Stage.RESTORE : Stage.DONE;
        }
        acknowledgedPrimitives++;
        return complete() ? Confirmation.COMPLETE : Confirmation.NEXT;
    }

    private static boolean validNativeMove(Snapshot before,Snapshot after,int source) {
        Stack from=before.items().get(source), remaining=after.items().get(source);
        if (from.empty() || !remaining.empty() && !from.sameKind(remaining) || remaining.count()>=from.count()) return false;
        int moved=from.count()-remaining.count(), received=0;
        for (int index=0; index<36; index++) {
            if (index==source) continue;
            Stack old=before.items().get(index), now=after.items().get(index);
            if (old.equals(now)) continue;
            // InventoryMenu QUICK_MOVE can only target the opposite inventory region.
            if ((source<9)==(index<9) || !from.sameKind(now) || !old.empty() && !from.sameKind(old)
                || now.count()<old.count()) return false;
            received+=now.count()-old.count();
        }
        return moved==received;
    }
}
