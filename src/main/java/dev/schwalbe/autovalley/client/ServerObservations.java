package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.util.*;

/** Records only server-originated confirmations, never client inventory prediction. */
public final class ServerObservations {
    private long sequence;
    private long generation;
    private final Map<Integer,Long> menus=new HashMap<>();
    private final Map<Integer,Long> fullMenus=new HashMap<>();
    private final Map<Pos,Long> blocks=new HashMap<>();
    public long sequence() { return sequence; }
    public long generation() { return generation; }
    public void menu(int id) { menus.put(id,++sequence); }
    public void fullMenu(int id) { fullMenus.put(id,++sequence); menus.put(id,sequence); }
    public boolean fullMenuSince(int id,long before) { return fullMenus.getOrDefault(id,0L)>before; }
    public void block(Pos pos) {
        if (blocks.size()>8192) blocks.clear();
        blocks.put(pos,++sequence);
    }
    public boolean menuSince(int id,long before) { return menus.getOrDefault(id,0L)>before || menus.getOrDefault(-2,0L)>before; }
    public boolean blockSince(Pos pos,long before) { return blocks.getOrDefault(pos,0L)>before; }
    public void clear() { sequence=0; generation++; menus.clear(); fullMenus.clear(); blocks.clear(); }
}
