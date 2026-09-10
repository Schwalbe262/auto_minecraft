package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import dev.schwalbe.autovalley.core.ItemData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
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
    /** Only packetItem is server-authored; appliedMenu is the detached post-application client state. */
    public record NativeSlotSnapshot(long seq,int menuId,int slot,ItemStack packetItem,NativeMenuSnapshot appliedMenu) {
        public NativeSlotSnapshot { packetItem=copyNativeStack(packetItem); Objects.requireNonNull(appliedMenu); }
        @Override public ItemStack packetItem() { return copyNativeStack(packetItem); }
    }
    private static final int MAX_MENU_SNAPSHOTS=256;
    private static final int MAX_SNAPSHOTS_PER_MENU=8;
    private long sequence;
    private volatile long generation;
    private Runnable nativeFullMenuObserver;
    /** One adapter-owned passive observer, called on the client thread before a later packet can evict this proof. */
    void observeNativeFullMenus(Runnable observer) {
        Objects.requireNonNull(observer);
        if (nativeFullMenuObserver!=null) throw new IllegalStateException("Native receipt observer already installed");
        nativeFullMenuObserver=observer;
    }
    public record NativeBlockSnapshot(long seq,Pos pos,BlockState state) { }
    /** Reduced from an actual block-entity packet before application; never from a live predicted entity. */
    public record NativeSnowPlantSnapshot(long seq,Pos pos,boolean spruceSapling) { }
    public record NativeChopSnapshot(long seq,Pos pos,int chops,int originalState) { }
    private final ArrayDeque<NativeBlockSnapshot> nativeBlocks=new ArrayDeque<>();
    private final ArrayDeque<NativeSnowPlantSnapshot> nativeSnowPlants=new ArrayDeque<>();
    private final ArrayDeque<NativeChopSnapshot> nativeChops=new ArrayDeque<>();
    private final NativeDestroyPermits loggingPermits=new NativeDestroyPermits();
    private final Map<Integer,Long> menus=new HashMap<>();
    private final Map<Integer,Long> fullMenus=new HashMap<>();
    private final LinkedHashMap<Integer,ArrayDeque<FullMenuSnapshot>> fullMenuSnapshots=new LinkedHashMap<>();
    private final Map<Integer,ArrayDeque<NativeMenuSnapshot>> fullNativeMenuSnapshots=new HashMap<>();
    private final Map<Pos,Long> blocks=new HashMap<>();
    private final ArrayDeque<NativeSlotSnapshot> nativeSlots=new ArrayDeque<>();
    public long sequence() { return sequence; }
    public long generation() { return generation; }
    void permitLoggingPacket(Object packet) { loggingPermits.grant(packet,generation,System.nanoTime()); }
    boolean consumeLoggingPacket(Object packet,long observerGeneration) {
        return observerGeneration==generation && loggingPermits.consume(packet,generation,System.nanoTime());
    }
    public void block(Pos pos,BlockState state) {
        block(pos);
        nativeBlocks.addLast(new NativeBlockSnapshot(sequence,pos,Objects.requireNonNull(state)));
        if (nativeBlocks.size()>4096) nativeBlocks.removeFirst();
    }
    public List<NativeBlockSnapshot> nativeBlocksSince(long before) { return nativeBlocks.stream().filter(s -> s.seq()>before).toList(); }
    public void snowPlant(Pos pos,boolean spruceSapling) {
        Objects.requireNonNull(pos);
        nativeSnowPlants.addLast(new NativeSnowPlantSnapshot(++sequence,pos,spruceSapling));
        if(nativeSnowPlants.size()>1024)nativeSnowPlants.removeFirst();
    }
    public List<NativeSnowPlantSnapshot> nativeSnowPlantsSince(long before) {
        return nativeSnowPlants.stream().filter(s -> s.seq()>before).toList();
    }
    public void chop(Pos pos,int chops,int originalState) {
        if (chops<0 || chops>1024 || originalState<0) return;
        nativeChops.addLast(new NativeChopSnapshot(++sequence,pos,chops,originalState));
        if (nativeChops.size()>1024) nativeChops.removeFirst();
    }
    public List<NativeChopSnapshot> nativeChopsSince(long before) { return nativeChops.stream().filter(s -> s.seq()>before).toList(); }
    public void menu(int id) { menus.put(id,++sequence); }
    public void nativeSlot(int id,int slot,ItemStack packetItem,List<ItemStack> appliedMenu,ItemStack carried) {
        NativeSlotSnapshot snapshot=new NativeSlotSnapshot(sequence+1,id,slot,packetItem,new NativeMenuSnapshot(sequence+1,appliedMenu,carried));
        menu(id);
        nativeSlots.addLast(snapshot);
        if (nativeSlots.size()>64) nativeSlots.removeFirst();
    }
    public List<NativeSlotSnapshot> nativeSlotSnapshotsSince(int id,long before) {
        return nativeSlots.stream().filter(s -> s.menuId()==id && s.seq()>before).toList();
    }
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
        // Only this overload holds a detached, server-authored native FULL. Markers,
        // reduced views, single-slot updates and current live menus cannot notify it.
        if (nativeFullMenuObserver!=null) nativeFullMenuObserver.run();
    }
    public void fullNativeMenu(int id,List<ItemStack> items,ItemStack carried) {
        NativeMenuSnapshot detached=new NativeMenuSnapshot(sequence+1,items,carried);
        fullMenu(id,detached.items.stream().map(MinecraftWorld::item).toList(),detached.items,detached.carried);
    }
    private void appendFullMenu(int id,FullMenuSnapshot snapshot) {
        // A later refresh may already contain refilled inventory. Keep the first
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
    public void clear() { sequence=0; generation++; menus.clear(); fullMenus.clear(); fullMenuSnapshots.clear(); fullNativeMenuSnapshots.clear(); blocks.clear(); nativeSlots.clear(); nativeBlocks.clear(); nativeSnowPlants.clear(); nativeChops.clear(); loggingPermits.clear(); }
}
