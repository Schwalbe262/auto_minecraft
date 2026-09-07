package dev.schwalbe.autovalley.core;
public record Farm(String name, Pos first, Pos second) {
    public boolean contains(Pos p) {
        return p.x() >= Math.min(first.x(),second.x()) && p.x() <= Math.max(first.x(),second.x())
            && p.y() >= Math.min(first.y(),second.y()) && p.y() <= Math.max(first.y(),second.y())
            && p.z() >= Math.min(first.z(),second.z()) && p.z() <= Math.max(first.z(),second.z());
    }
    public long volume() { return (1L+Math.abs(first.x()-second.x()))*(1L+Math.abs(first.y()-second.y()))*(1L+Math.abs(first.z()-second.z())); }
}
