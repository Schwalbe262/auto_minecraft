package dev.schwalbe.autovalley.core;

/** Shared standing-cell interpretation; it does not expand work bounds or invent a floor. */
public final class NavigationFeet {
    private NavigationFeet() { }

    public static Pos resolve(WorldAccess world, PlayerState player) {
        Pos raw = player.feet();
        // Prefer the real raw cell. Farmland/slab/stair support can instead require
        // the cell above, but only when the world explicitly verifies that cell.
        return !world.canStand(raw) && world.canStand(raw.offset(0,1,0)) ? raw.offset(0,1,0) : raw;
    }
}
