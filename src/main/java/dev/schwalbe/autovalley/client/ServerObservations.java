package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import dev.schwalbe.autovalley.core.ItemData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.*;
import java.util.function.UnaryOperator;

/** Records only server-originated confirmations, never client inventory prediction. */
public final class ServerObservations {
    public record FullMenuSnapshot(long seq,List<ItemData> items) {
        public FullMenuSnapshot { items=List.copyOf(items); }
    }
    /**
     * RAM-only, client-visible native ACK. A server may filter its NBT through a share tag,
     * so these stacks are not proof of all server-side metadata or of permission to merge.
     * Native equality is only a candidate check; the server's inventory primitive is authoritative.
     */
    public record NativeMenuSnapshot(long seq,List<ItemStack> items,ItemStack carried) {
        public NativeMenuSnapshot {
            items=copySnapshotItems(items,ServerObservations::copyNativeStack);
            carried=copyNativeStack(carried);
        }
        // An immutable list alone does not protect the mutable ItemStacks (including their tags).
        @Override public List<ItemStack> items() { return copySnapshotItems(items,ServerObservations::copyNativeStack); }
        @Override public ItemStack carried() { return copyNativeStack(carried); }
    }
    private static final int MAX_MENU_SNAPSHOTS=256;
    private static final int MAX_SNAPSHOTS_PER_MENU=8;
    private long sequence;
    private long generation;
    private final Map<Integer,Long> menus=new HashMap<>();
    private final Map<Integer,Long> fullMenus=new HashMap<>();
    private final LinkedHashMap<Integer,ArrayDeque<FullMenuSnapshot>> fullMenuSnapshots=new LinkedHashMap<>();
    private final Map<Integer,ArrayDeque<NativeMenuSnapshot>> fullNativeMenuSnapshots=new HashMap<>();
    private final Map<Pos,Long> blocks=new HashMap<>();
    public long sequence() { return sequence; }
    public long generation() { return generation; }
    public void menu(int id) { menus.put(id,++sequence); }
    public void fullMenu(int id) {
        markFullMenu(id);
        // A marker without slot data cannot lend older slot contents to a newer ACK.
        fullMenuSnapshots.remove(id);
        fullNativeMenuSnapshots.remove(id);
    }
    public void fullMenu(int id,List<ItemData> items) {
        FullMenuSnapshot snapshot=new FullMenuSnapshot(sequence+1,items);
        markFullMenu(id);
        // Reduced-only evidence must never make an older native snapshot look like this ACK.
        fullNativeMenuSnapshots.remove(id);
        appendFullMenu(id,snapshot);
    }
    /** Captures reduced and native representations of one packet at exactly one sequence. */
    public void fullMenu(int id,List<ItemData> items,List<ItemStack> nativeItems,ItemStack carried) {
        if (items.size()!=nativeItems.size()) throw new IllegalArgumentException("Mismatched menu snapshot sizes");
        FullMenuSnapshot reduced=new FullMenuSnapshot(sequence+1,items);
        NativeMenuSnapshot nativeSnapshot=new NativeMenuSnapshot(sequence+1,nativeItems,carried);
        markFullMenu(id);
        appendFullMenu(id,reduced);
        ArrayDeque<NativeMenuSnapshot> history=fullNativeMenuSnapshots.computeIfAbsent(id,ignored -> new ArrayDeque<>());
        history.addLast(nativeSnapshot);
        if (history.size()>MAX_SNAPSHOTS_PER_MENU) history.removeFirst();
    }
    public void fullNativeMenu(int id,List<ItemStack> items,ItemStack carried) {
        NativeMenuSnapshot detached=new NativeMenuSnapshot(sequence+1,items,carried);
        fullMenu(id,detached.items.stream().map(MinecraftWorld::item).toList(),detached.items,detached.carried);
    }
    private void appendFullMenu(int id,FullMenuSnapshot snapshot) {
        // A later refresh may already contain magnet-refilled inventory. Keep the first
        // authoritative click reply until the action adapter has had a client tick to inspect it.
        ArrayDeque<FullMenuSnapshot> history=fullMenuSnapshots.remove(id);
        if (history==null) history=new ArrayDeque<>();
        history.addLast(snapshot);
        if (history.size()>MAX_SNAPSHOTS_PER_MENU) history.removeFirst();
        fullMenuSnapshots.put(id,history);
        if (fullMenuSnapshots.size()>MAX_MENU_SNAPSHOTS) {
            Integer oldest=fullMenuSnapshots.keySet().iterator().next();
            fullMenuSnapshots.remove(oldest);
            fullNativeMenuSnapshots.remove(oldest);
        }
    }
    private void markFullMenu(int id) { fullMenus.put(id,++sequence); menus.put(id,sequence); }
    public boolean fullMenuSince(int id,long before) { return fullMenus.getOrDefault(id,0L)>before; }
    public FullMenuSnapshot fullMenuSnapshotSince(int id,long before) {
        ArrayDeque<FullMenuSnapshot> history=fullMenuSnapshots.get(id);
        FullMenuSnapshot snapshot=history==null ? null : history.peekLast();
        return snapshot!=null && snapshot.seq()>before ? snapshot : null;
    }
    /** Oldest first, detached from subsequent packet arrivals and bounded to this menu ID. */
    public List<FullMenuSnapshot> fullMenuSnapshotsSince(int id,long before) {
        ArrayDeque<FullMenuSnapshot> history=fullMenuSnapshots.get(id);
        return history==null ? List.of() : history.stream().filter(s -> s.seq()>before).toList();
    }
    /** Oldest first, same sequence as the corresponding reduced ACK; never persisted or recorded. */
    public List<NativeMenuSnapshot> fullNativeMenuSnapshotsSince(int id,long before) {
        ArrayDeque<NativeMenuSnapshot> history=fullNativeMenuSnapshots.get(id);
        return history==null ? List.of() : history.stream().filter(s -> s.seq()>before).toList();
    }
    static <T> List<T> copySnapshotItems(List<T> items,UnaryOperator<T> copy) {
        Objects.requireNonNull(copy);
        return items.stream().map(item -> Objects.requireNonNull(copy.apply(Objects.requireNonNull(item)))).toList();
    }
    private static ItemStack copyNativeStack(ItemStack stack) {
        Objects.requireNonNull(stack);
        // ItemStack.copy() returns the shared EMPTY singleton for empty slots. Do not expose
        // even that mutable sentinel through a snapshot accessor.
        return stack.isEmpty() ? new ItemStack(Items.AIR,0) : stack.copy();
    }
    public void block(Pos pos) {
        if (blocks.size()>8192) blocks.clear();
        blocks.put(pos,++sequence);
    }
    public boolean menuSince(int id,long before) { return menus.getOrDefault(id,0L)>before || menus.getOrDefault(-2,0L)>before; }
    public boolean blockSince(Pos pos,long before) { return blocks.getOrDefault(pos,0L)>before; }
    public void clear() { sequence=0; generation++; menus.clear(); fullMenus.clear(); fullMenuSnapshots.clear(); fullNativeMenuSnapshots.clear(); blocks.clear(); }
}
