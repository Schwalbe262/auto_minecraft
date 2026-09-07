package dev.schwalbe.autovalley.core;

import java.util.List;

/** Presentation metadata only: actions and production deadlines still use each registered POI. */
public record MachineGroup(String name, PoiKind kind, List<Pos> members) {
    public MachineGroup { members=List.copyOf(members); }
}
