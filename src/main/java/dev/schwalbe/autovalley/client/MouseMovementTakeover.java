package dev.schwalbe.autovalley.client;

/** Samples the game's cursor only; camera rotations and keys are not input evidence. */
final class MouseMovementTakeover {
    static final int SETTLE_TICKS=4;
    private boolean valid,grabbed;
    private Object screen;
    private int width,height;
    private long lastTick,settleSince;
    private double lastX,lastY;

    boolean moved(long tick,boolean running,boolean focused,boolean mouseGrabbed,boolean sleeping,
                  Object currentScreen,int windowWidth,int windowHeight,double x,double y) {
        if(!running || !focused || sleeping || windowWidth<=0 || windowHeight<=0
            || !Double.isFinite(x) || !Double.isFinite(y)) { reset();return false; }
        boolean changed=!valid || tick<lastTick || grabbed!=mouseGrabbed || screen!=currentScreen
            || width!=windowWidth || height!=windowHeight;
        if(changed) {
            valid=true;grabbed=mouseGrabbed;screen=currentScreen;width=windowWidth;height=windowHeight;
            settleSince=tick;lastTick=tick;lastX=x;lastY=y;return false;
        }
        // Opening a menu, regaining focus or grabbing the cursor can warp its
        // coordinates. Establish a new baseline during a short bounded grace.
        boolean moved=tick-settleSince>=SETTLE_TICKS && (x!=lastX || y!=lastY);
        lastTick=tick;lastX=x;lastY=y;
        return moved;
    }
    void reset() {valid=false;screen=null;}
}
