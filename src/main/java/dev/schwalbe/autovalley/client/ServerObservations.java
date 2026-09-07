package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.*;

/** Records only server-originated confirmations, never client inventory prediction. */
public final class ServerObservations {
    public record FullMenuSnapshot(long seq,List<ItemData> items) {
        public FullMenuSnapshot { items=List.copyOf(items); }
    }
    private static final int MAX_MENU_SNAPSHOTS=256;
    private static final int MAX_SNAPSHOTS_PER_MENU=8;
    private long sequence;
    private long generation;
    private final Map<Integer,Long> menus=new HashMap<>();
    private final Map<Integer,Long> fullMenus=new HashMap<>();
    private final LinkedHashMap<Integer,ArrayDeque<FullMenuSnapshot>> fullMenuSnapshots=new LinkedHashMap<>();
    private final Map<Pos,Long> blocks=new HashMap<>();
    public long sequence() { return sequence; }
    public long generation() { return generation; }
    public void menu(int id) { menus.put(id,++sequence); }
    public void fullMenu(int id) {
        markFullMenu(id);
        // A marker without slot data cannot lend older slot contents to a newer ACK.
        fullMenuSnapshots.remove(id);
    }
    public void fullMenu(int id,List<ItemData> items) {
        FullMenuSnapshot snapshot=new FullMenuSnapshot(sequence+1,items);
        markFullMenu(id);
        // A later refresh may already contain magnet-refilled inventory. Keep the first
        // authoritative click reply until the action adapter has had a client tick to inspect it.
        ArrayDeque<FullMenuSnapshot> history=fullMenuSnapshots.remove(id);
        if (history==null) history=new ArrayDeque<>();
        history.addLast(snapshot);
        if (history.size()>MAX_SNAPSHOTS_PER_MENU) history.removeFirst();
        fullMenuSnapshots.put(id,history);
        if (fullMenuSnapshots.size()>MAX_MENU_SNAPSHOTS) fullMenuSnapshots.remove(fullMenuSnapshots.keySet().iterator().next());
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
    public void block(Pos pos) {
        if (blocks.size()>8192) blocks.clear();
        blocks.put(pos,++sequence);
    }
    public boolean menuSince(int id,long before) { return menus.getOrDefault(id,0L)>before || menus.getOrDefault(-2,0L)>before; }
    public boolean blockSince(Pos pos,long before) { return blocks.getOrDefault(pos,0L)>before; }
    public void clear() { sequence=0; generation++; menus.clear(); fullMenus.clear(); fullMenuSnapshots.clear(); blocks.clear(); }
}
