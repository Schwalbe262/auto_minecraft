package dev.schwalbe.autovalley.core;

public record Pos(int x, int y, int z) {
    public Pos offset(int dx, int dy, int dz) { return new Pos(x + dx, y + dy, z + dz); }
    public double distanceSquared(Pos p) { return Math.pow(x-p.x,2)+Math.pow(y-p.y,2)+Math.pow(z-p.z,2); }
}
