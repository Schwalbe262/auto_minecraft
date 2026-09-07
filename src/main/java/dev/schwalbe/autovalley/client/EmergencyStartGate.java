package dev.schwalbe.autovalley.client;

/** Tick-bounded stop priority; stale external start files must be consumed, not deferred. */
public final class EmergencyStartGate {
    private long blockedThrough=Long.MIN_VALUE;
    private boolean requestUnresolved;
    public void stopAt(long tick) { blockedThrough=Math.max(blockedThrough,tick==Long.MAX_VALUE ? tick : tick+1); }
    public boolean blockedAt(long tick) { return requestUnresolved || tick<=blockedThrough; }
    /** An unreadable/locked request must not outlive stop priority and execute later. */
    public void controlChecked(long tick,boolean settled) {
        if (settled) requestUnresolved=false;
        else if (blockedAt(tick)) requestUnresolved=true;
    }
    public static boolean rejectsCommand(boolean blocked,String command) {
        return blocked && ("start".equals(command) || "once".equals(command));
    }
    public static boolean shouldPoll(boolean blocked,long now,long nextPoll) { return blocked || now>=nextPoll; }
}
