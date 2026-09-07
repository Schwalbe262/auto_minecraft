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

    public InventoryConsolidation(ProductionMergePlanner.Plan plan, Snapshot initial) {
        this.plan=Objects.requireNonNull(plan); this.initial=initial; this.before=initial;
        if (plan.sourceIndex()<0 || plan.sourceIndex()>=36 || initial.items().get(plan.sourceIndex()).empty()
            || plan.scratchHotbar() < -1 || plan.scratchHotbar()>8
            || !plan.direct() && plan.sourceIndex()<9) throw new IllegalArgumentException("Invalid consolidation plan");
        stage=plan.direct() ? Stage.MERGE : Stage.SWAP_OUT;
    }
    public Snapshot expectedLive() { return before; }
    public boolean complete() { return stage==Stage.DONE; }
    public int freedSlots() { return Math.max(0,before.emptySlots()-initial.emptySlots()); }
    public Click click() {
        return switch(stage) {
            case SWAP_OUT, RESTORE -> new Click(Type.SWAP,plan.sourceIndex(),plan.scratchHotbar());
            case MERGE -> new Click(Type.QUICK_MOVE,plan.quickMoveIndex(),-1);
            case DONE -> throw new IllegalStateException("Transaction finished");
        };
    }

    /** An unrelated/unchanged refresh never authorizes a subsequent primitive. */
    public Confirmation acknowledge(Snapshot after) {
        if (stage==Stage.DONE || before.equals(after)) return Confirmation.WAIT;
        if (stage==Stage.SWAP_OUT || stage==Stage.RESTORE) {
            List<Stack> expected=new ArrayList<>(before.items());
            Collections.swap(expected,plan.sourceIndex(),plan.scratchHotbar());
            if (!expected.equals(after.items())) return Confirmation.WAIT;
            before=after;
            stage=stage==Stage.SWAP_OUT ? Stage.MERGE : Stage.DONE;
        } else {
            if (!validNativeMove(before,after,plan.quickMoveIndex())) return Confirmation.WAIT;
            before=after;
            // Original EMPTY scratch needs no inverse swap: native QUICK_MOVE may
            // have placed the item into the now-empty original source slot itself.
            stage=plan.requiresRestore() ? Stage.RESTORE : Stage.DONE;
        }
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
