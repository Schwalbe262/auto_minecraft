package dev.schwalbe.autovalley.core;

/** Camera-only easing. MovementAxes continues to use the exact world-space heading. */
public final class MovementLook {
    private MovementLook() { }

    public static float yaw(float current,float desired) {
        finite(current,desired);
        double delta=Math.IEEEremainder((double)desired-current,360);
        return current+step(delta,18);
    }

    public static float pitch(float current,float desired) {
        finite(current,desired);
        float bounded=Math.max(-90,Math.min(90,desired));
        return Math.max(-90,Math.min(90,current+step((double)bounded-current,10)));
    }

    private static float step(double delta,double maximum) {
        if (Math.abs(delta)<=.1) return (float)delta;
        return (float)Math.copySign(Math.min(maximum,Math.abs(delta)*.35),delta);
    }

    private static void finite(float current,float desired) {
        if (!Float.isFinite(current) || !Float.isFinite(desired)) throw new IllegalArgumentException("Non-finite camera angle");
    }
}
