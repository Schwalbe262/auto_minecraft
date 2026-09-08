package dev.schwalbe.autovalley.core;

import java.util.List;

/** Explicit north-west planting cell of one 2x2 spruce tree, never a tomato farm. */
public record LoggingPlot(String name, Pos corner) {
    public List<Pos> plantingPositions() {
        return List.of(corner,corner.offset(1,0,0),corner.offset(0,0,1),corner.offset(1,0,1));
    }
    public boolean containsTrunk(Pos p) {
        return corner!=null && p!=null && between(p.x(),corner.x(),0,1)
            && between(p.z(),corner.z(),0,1) && between(p.y(),corner.y(),0,63);
    }
    /** Read/navigation envelope. This is NOT permission to destroy every block inside it. */
    public boolean containsEnvelope(Pos p) {
        return corner!=null && p!=null && between(p.x(),corner.x(),-7,8)
            && between(p.z(),corner.z(),-7,8) && between(p.y(),corner.y(),-1,64);
    }
    private static boolean between(int value,int base,int low,int high) {
        return (long)value>=(long)base+low && (long)value<=(long)base+high;
    }
}
