package dev.schwalbe.autovalley.core;

/** Small bounded overlap for moving harvest; every other pending action excludes movement. */
public final class HarvestMovementRules {
    private HarvestMovementRules() { }
    public static boolean mayOverlap(Action pending,Movement intent,PlayerState before,PlayerState current,long elapsed,boolean enabled) {
        if (!(pending instanceof Action.UseBlock use) || use.purpose()!=Action.Use.HARVEST || !enabled || intent==null
            || intent.jump() || intent.sneak() || !Float.isFinite(intent.yaw()) || !Float.isFinite(intent.pitch())
            || before==null || current==null || !current.connected() || !current.onGround() || elapsed<0 || elapsed>10)
            return false;
        double distance=Math.hypot(current.x()-before.x(),current.z()-before.z());
        double height=Math.abs(current.y()-before.y());
        return Double.isFinite(distance) && Double.isFinite(height) && distance<=3 && height<=.5;
    }
}
