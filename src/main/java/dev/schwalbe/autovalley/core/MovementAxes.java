package dev.schwalbe.autovalley.core;

/** Converts a world-space walking heading into input relative to the current camera. */
public record MovementAxes(float forward, float left) {
    public static final MovementAxes STOPPED=new MovementAxes(0,0);
    public static MovementAxes from(Movement intent,float viewingYaw) {
        if (intent==null || !intent.forward() || !Float.isFinite(intent.yaw()) || !Float.isFinite(viewingYaw)) return STOPPED;
        double delta=Math.toRadians(Math.IEEEremainder((double)intent.yaw()-viewingYaw,360));
        return new MovementAxes(clean(Math.cos(delta)*intent.inputScale()),clean(-Math.sin(delta)*intent.inputScale()));
    }
    private static float clean(double value) { return Math.abs(value)<1.0e-6 ? 0 : (float)Math.max(-1,Math.min(1,value)); }
}
