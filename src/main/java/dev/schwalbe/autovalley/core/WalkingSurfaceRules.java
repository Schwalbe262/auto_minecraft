package dev.schwalbe.autovalley.core;

/** Shared numeric checks for sampled walking surfaces; never requests a jump. */
public final class WalkingSurfaceRules {
    private WalkingSurfaceRules() { }
    public static boolean canStep(double from, double to, double stepHeight, boolean farmland) {
        if (!Double.isFinite(from) || !Double.isFinite(to) || !Double.isFinite(stepHeight) || stepHeight<0) return false;
        double delta=to-from;
        if (delta>Math.min(0.6,stepHeight)+1.0e-5 || delta < -1.00001) return false;
        // The normal 1/16-block farmland depression is walkable; do not fall onto crops.
        return !farmland || delta>=-0.10001;
    }
    public static double positionalDistance(Pos feet, double standingY, Pos target) {
        return Math.sqrt(Math.pow(feet.x()-target.x(),2)+Math.pow(standingY-target.y(),2)+Math.pow(feet.z()-target.z(),2));
    }
}
