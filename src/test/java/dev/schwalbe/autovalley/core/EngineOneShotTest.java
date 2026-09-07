package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.modules.MachineModule;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class EngineOneShotTest {
    private static final Pos MACHINE=new Pos(1,64,0);

    @Test void explicitDisabledJobRunsAloneAndCompletesWithoutChangingSavedToggles() {
        Fixture f=new Fixture();
        f.profile.enabled.put(Feature.WINE,false);
        Map<Feature,Boolean> saved=new EnumMap<>(f.profile.enabled);
        List<Module> jobs=Arrays.stream(Feature.values()).map(feature -> new Module(feature,WorkResult.idle())).toList();
        AutomationEngine engine=new AutomationEngine(new ArrayList<>(jobs));

        engine.startOnce(f.context(),Feature.WINE);
        assertTrue(engine.running());
        assertEquals(RunMode.ONCE,engine.mode());
        assertEquals(Feature.WINE,engine.oneShotFeature());
        assertEquals(Feature.WINE,f.session.oneShotFeature);
        engine.tick(f.context());

        assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertFalse(engine.running());
        assertEquals(Feature.WINE,engine.oneShotFeature());
        assertNull(f.session.oneShotFeature,"completed runs must revoke the temporary permission");
        assertEquals(saved,f.profile.enabled);
        for (Module job:jobs) assertEquals(job.feature()==Feature.WINE ? 1 : 0,job.calls,job.feature().name());
    }

    @Test void busyProgressesUntilIdleThenNeverRestartsAtNextTickOrDay() {
        Fixture f=new Fixture();
        Module wine=new Module(Feature.WINE,WorkResult.busy("machine 1"));
        Module sleep=new Module(Feature.SLEEP,WorkResult.busy("sleep"));
        AutomationEngine engine=new AutomationEngine(List.of(sleep,wine));
        engine.startOnce(f.context(),Feature.WINE);
        engine.tick(f.context());
        wine.result=WorkResult.busy("machine 2");
        engine.tick(f.context());
        assertEquals(2,wine.calls);
        assertTrue(engine.running());
        assertTrue(engine.status().contains("machine 2"));
        wine.result=WorkResult.idle();
        int cancellations=f.cancelCalls;
        engine.tick(f.context());
        assertEquals(cancellations+1,f.cancelCalls);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        wine.result=WorkResult.busy("next batch");
        for (int i=0;i<4;i++) { f.ticks+=24000; f.dayTime+=24000; engine.tick(f.context()); }
        assertEquals(3,wine.calls);
        assertEquals(0,sleep.calls);
        assertFalse(engine.running());
    }

    @Test void blockedJobPausesWithoutRetryingOrInvokingStorageToResolveIt() {
        Fixture f=new Fixture();
        Module wine=new Module(Feature.WINE,WorkResult.busy("approaching"));
        Module storage=new Module(Feature.TOMATO_STORAGE,WorkResult.busy("deposit"));
        AutomationEngine engine=new AutomationEngine(List.of(storage,wine));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        wine.result=WorkResult.blocked("inventory full"); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state());
        assertTrue(engine.status().contains("inventory full"));
        assertNull(f.session.oneShotFeature);
        wine.result=WorkResult.busy("would retry");
        f.ticks+=100000; f.dayTime+=24000; engine.tick(f.context());
        assertEquals(2,wine.calls); assertEquals(0,storage.calls); assertFalse(engine.running());
    }

    @Test void explicitStopThenNormalStartSwitchesToContinuousAndHonoursSavedToggles() {
        Fixture f=new Fixture(); f.profile.enabled.put(Feature.WINE,false);
        Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
        Module harvest=new Module(Feature.HARVEST,WorkResult.busy("harvest"));
        AutomationEngine engine=new AutomationEngine(List.of(wine,harvest));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        engine.stop(f.context(),AutomationEngine.State.PAUSED,"F8"); engine.tick(f.context());
        assertEquals(1,wine.calls); assertFalse(engine.running()); assertNull(f.session.oneShotFeature);
        engine.start(f.context()); engine.tick(f.context());
        assertEquals(RunMode.CONTINUOUS,engine.mode()); assertNull(engine.oneShotFeature());
        assertEquals(1,harvest.calls); assertEquals(1,wine.calls); assertTrue(engine.running());
        assertFalse(f.profile.enabled(Feature.WINE));
    }

    @Test void selectingAnotherOneShotCancelsOldJobBeforeNewJobRuns() {
        Fixture f=new Fixture();
        Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
        Module jars=new Module(Feature.PRESERVES,WorkResult.busy("jars"));
        AutomationEngine engine=new AutomationEngine(List.of(wine,jars));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        int cancellations=f.cancelCalls;
        engine.startOnce(f.context(),Feature.PRESERVES);
        assertEquals(cancellations+1,f.cancelCalls);
        assertEquals(Feature.PRESERVES,f.session.oneShotFeature);
        engine.tick(f.context()); engine.tick(f.context());
        assertEquals(1,wine.calls); assertEquals(2,jars.calls);
    }

    @Test void startingOneShotFromContinuousNeverTicksTheFormerActiveModuleAgain() {
        Fixture f=new Fixture();
        Module harvest=new Module(Feature.HARVEST,WorkResult.busy("harvest"));
        Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
        AutomationEngine engine=new AutomationEngine(List.of(harvest,wine));
        engine.start(f.context()); engine.tick(f.context());
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context()); engine.tick(f.context());
        assertEquals(1,harvest.calls); assertEquals(2,wine.calls); assertEquals(RunMode.ONCE,engine.mode());
    }

    @Test void changedSavedToggleDoesNotCancelExplicitOneShot() {
        Fixture f=new Fixture(); Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        f.profile.enabled.put(Feature.WINE,false); engine.tick(f.context());
        assertEquals(2,wine.calls); assertTrue(engine.running());
    }

    @Test void selectedDisabledMachineGetsOnlyTemporarySafetyPermission() {
        Fixture f=new Fixture(); f.profile.enabled.put(Feature.WINE,false);
        f.profile.pois.add(new Poi(MACHINE,PoiKind.WINE_KEG,"keg",null));
        Action use=new Action.UseBlock(MACHINE,Action.Use.MACHINE);
        Module wine=new Module(Feature.WINE,WorkResult.idle());
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        assertNotNull(SafetyPolicy.rejection(use,f.context()));
        engine.startOnce(f.context(),Feature.WINE);
        assertNull(SafetyPolicy.rejection(use,f.context()),"explicit selection must pass the real action safety gate");
        assertFalse(f.session.allows(f.profile,Feature.HARVEST),"another enabled feature must not inherit one-shot permission");
        engine.tick(f.context());
        assertNotNull(SafetyPolicy.rejection(use,f.context()));
        assertFalse(f.profile.enabled(Feature.WINE));
    }

    @Test void oneShotRespectsActualMachineDayDeadlineAndDoesNotWaitForIt() {
        Fixture f=new Fixture();
        f.profile.pois.add(new Poi(MACHINE,PoiKind.WINE_KEG,"keg",null));
        f.profile.nextEligibleDay.put("wine:1:64:0",6L);
        f.dayTime=2*24000+1000;
        AutomationEngine engine=new AutomationEngine(List.of(new MachineModule(Feature.WINE)));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertEquals(6L,f.profile.nextEligibleDay.get("wine:1:64:0"));
        assertEquals(0,f.submissions); assertEquals(0,f.navigationCalls);
        f.dayTime=6*24000+1000; f.ticks+=4*24000; engine.tick(f.context());
        assertFalse(engine.running()); assertEquals(0,f.navigationCalls);
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context()); engine.tick(f.context());
        assertTrue(engine.running()); assertEquals(1,f.navigationCalls,"only an explicit new run services the now-due machine");
    }





    @Test void disconnectedUnfocusedCursorAndOpenContainerAllRejectStart() {
        for (int reason=0;reason<4;reason++) {
            Fixture f=new Fixture();
            switch (reason) {
                case 0 -> f.connected=false;
                case 1 -> { f.profile.allowBackground=false; f.focused=false; }
                case 2 -> f.carried=new ItemData(ItemData.TOMATO,1,0,null,false,0);
                case 3 -> f.container=true;
            }
            Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
            AutomationEngine engine=new AutomationEngine(List.of(wine));
            engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
            assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(0,wine.calls);
            assertNull(f.session.oneShotFeature); assertFalse(engine.running());
        }
    }

    @Test void lowHealthHungerDisconnectAndRequiredFocusStopBusyOneShot() {
        for (int reason=0;reason<4;reason++) {
            Fixture f=new Fixture(); Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
            AutomationEngine engine=new AutomationEngine(List.of(wine));
            engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
            switch (reason) {
                case 0 -> f.health=4;
                case 1 -> f.food=4;
                case 2 -> f.connected=false;
                case 3 -> { f.profile.allowBackground=false; f.focused=false; }
            }
            engine.tick(f.context());
            assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,wine.calls);
            assertNull(f.session.oneShotFeature);
        }
    }

    @Test void backgroundOneShotRemainsAllowedWhenUnfocused() {
        Fixture f=new Fixture(); f.focused=false;
        Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context()); engine.tick(f.context());
        assertTrue(engine.running()); assertEquals(2,wine.calls);
    }

    @Test void unknownOrNullSelectionNeverFallsBackToContinuousWork() {
        for (Feature feature:new Feature[]{null,Feature.WINE}) {
            Fixture f=new Fixture(); Module harvest=new Module(Feature.HARVEST,WorkResult.busy("harvest"));
            AutomationEngine engine=new AutomationEngine(List.of(harvest));
            engine.startOnce(f.context(),feature); engine.tick(f.context());
            assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(0,harvest.calls);
            assertNull(f.session.oneShotFeature);
        }
    }

    @Test void exceptionStopsAndRevokesTemporaryPermission() {
        Fixture f=new Fixture(); Module wine=new Module(Feature.WINE,WorkResult.busy("wine"));
        wine.onTick=c -> { throw new IllegalStateException("test failure"); };
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        engine.startOnce(f.context(),Feature.WINE);
        assertThrows(IllegalStateException.class,() -> engine.tick(f.context()));
        assertEquals(AutomationEngine.State.ERROR,engine.state()); assertFalse(engine.running());
        assertNull(f.session.oneShotFeature);
    }

    private static final class Module implements AutomationModule {
        private final Feature feature;
        private WorkResult result;
        private int calls;
        private Consumer<Context> onTick=c -> { };
        Module(Feature feature,WorkResult result) { this.feature=feature; this.result=result; }
        @Override public Feature feature() { return feature; }
        @Override public int priority() { return feature.ordinal(); }
        @Override public WorkResult tick(Context c) { calls++; onTick.accept(c); return result; }
        @Override public void reset() { }
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();
        final SessionState session=new SessionState();
        boolean focused=true,connected=true,container;
        float health=20;
        int food=20,cancelCalls,submissions,navigationCalls;
        long ticks,dayTime=13000;
        ItemData carried=ItemData.EMPTY;
        Context context() { return new Context(this,this,this,profile,session); }
        @Override public long tick() { return ticks; }
        @Override public long dayTime() { return dayTime; }
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,health,food,0,connected,focused); }
        @Override public BlockData block(Pos p) { return new BlockData(p,"society:wine_keg",Map.of("working","true","mature","true")); }
        @Override public boolean loaded(Pos p) { return true; }
        @Override public boolean canStand(Pos p) { return true; }
        @Override public boolean canTraverse(Pos from,Pos to) { return true; }
        @Override public List<BlockData> scan(Pos center,int radius,int vertical) { return List.of(); }
        @Override public List<ItemSlot> inventory() { return List.of(); }
        @Override public MenuData menu() { return new MenuData(0,0,inventory(),carried,container); }
        @Override public boolean mayPlace(int slot,ItemData item) { return true; }
        @Override public boolean busy() { return false; }
        @Override public long submit(Action action) { return ++submissions; }
        @Override public ActionOutcome outcome(long ticket) { return new ActionOutcome(ActionOutcome.State.SUCCEEDED,""); }
        @Override public void move(Movement movement) { }
        @Override public void stopMovement() { }
        @Override public void cancel() { cancelCalls++; }
        @Override public Navigation.Result moveTo(Pos target,double reach,Context c) { navigationCalls++; return Navigation.Result.ARRIVED; }
        @Override public void reset() { }
    }
}
