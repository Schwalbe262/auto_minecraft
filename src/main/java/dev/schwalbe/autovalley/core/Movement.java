package dev.schwalbe.autovalley.core;
/** Native input strength only; it never assigns a player position or velocity. */
public record Movement(float yaw, float pitch, boolean forward, boolean sprint, boolean jump, boolean sneak, float inputScale) {
    public Movement(float yaw,float pitch,boolean forward,boolean sprint,boolean jump,boolean sneak) {
        this(yaw,pitch,forward,sprint,jump,sneak,1f);
    }
    public Movement {
        if (!Float.isFinite(inputScale) || inputScale<0 || inputScale>1)
            throw new IllegalArgumentException("Movement input scale must be finite and between zero and one");
    }
}
