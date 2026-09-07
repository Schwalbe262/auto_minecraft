package dev.schwalbe.autovalley.core;
public record Context(WorldAccess world, ActionPort actions, Navigation navigation, Profile profile, SessionState session, Runnable checkpoint) {
    public Context { java.util.Objects.requireNonNull(checkpoint,"checkpoint"); }
    public Context(WorldAccess world, ActionPort actions, Navigation navigation, Profile profile, SessionState session) {
        this(world,actions,navigation,profile,session,() -> { });
    }
    public Context(WorldAccess world, ActionPort actions, Navigation navigation, Profile profile) {
        this(world,actions,navigation,profile,new SessionState());
    }
}
