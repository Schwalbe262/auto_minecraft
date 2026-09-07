package dev.schwalbe.autovalley.core;
public record Context(WorldAccess world, ActionPort actions, Navigation navigation, Profile profile, SessionState session) {
    public Context(WorldAccess world, ActionPort actions, Navigation navigation, Profile profile) {
        this(world,actions,navigation,profile,new SessionState());
    }
}
