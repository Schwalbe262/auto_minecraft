package dev.schwalbe.autovalley.core;
public interface Navigation {
    enum Result { MOVING, ARRIVED, BLOCKED }
    Result moveTo(Pos target, double reach, Context context);
    void reset();
}
