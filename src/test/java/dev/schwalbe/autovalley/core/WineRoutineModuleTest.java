package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.modules.WineRoutineModule;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WineRoutineModuleTest {
    @Test void oneShotTraversesBothChildrenAndRetainsTheFirstFailureAfterTheOtherRuns() {
        Fixture f=new Fixture();f.first.result=WorkResult.blocked("legacy full");
        f.profile.enabled.put(f.feature,false);f.session.oneShotFeature=f.feature;
        assertEquals(WorkResult.State.BUSY,f.step().state());assertEquals(0,f.second.calls);
        WorkResult result=f.step();assertEquals(1,f.second.calls);assertEquals(WorkResult.State.BLOCKED,result.state());
        assertTrue(result.message().contains("legacy full"));assertEquals(1,f.first.resets);assertEquals(0,f.second.resets);
    }
    @Test void busyChildRetainsItsPhaseAndPreventsTheOtherChildFromStarting() {
        Fixture f=new Fixture();f.first.result=WorkResult.busy("pending");
        for(int i=0;i<3;i++)assertEquals(WorkResult.State.BUSY,f.step().state());
        assertEquals(3,f.first.calls);assertEquals(0,f.second.calls);assertEquals(0,f.first.resets);
        f.first.result=WorkResult.idle();f.step();assertEquals(WorkResult.State.IDLE,f.step().state());assertEquals(1,f.second.calls);
    }
    @Test void idleChildrenKeepTheirSweepYieldStateAcrossCompositePasses() {
        Fixture f=new Fixture();for(int i=0;i<6;i++)f.step();
        assertEquals(2,f.first.calls);assertEquals(2,f.second.calls);assertEquals(0,f.first.resets);assertEquals(0,f.second.resets);
        f.module.reset();assertEquals(1,f.first.resets);assertEquals(1,f.second.resets);
    }
    @Test void deferredAndCooldownChildrenStillAllowTheOtherChildAtASafeBoundary() {
        for(WorkResult wait:List.of(WorkResult.deferred("route waiting"),WorkResult.cooldown("production waiting"))) {
            Fixture f=new Fixture();f.first.result=wait;f.step();WorkResult result=f.step();
            assertEquals(WorkResult.State.DEFERRED,result.state());assertTrue(result.message().contains(wait.message()));
            assertEquals(1,f.second.calls);assertEquals(1,f.first.resets);
        }
    }
    @Test void twoFailuresAreBothReportedRatherThanReplacingTheFirstWithTheSecond() {
        Fixture f=new Fixture();f.first.result=WorkResult.blocked("legacy full");f.second.result=WorkResult.blocked("custom mixed");
        f.step();WorkResult result=f.step();assertEquals(WorkResult.State.BLOCKED,result.state());
        assertTrue(result.message().contains("legacy full"));assertTrue(result.message().contains("custom mixed"));
    }
    @Test void ownedMenuMustCloseWithItsRealAcknowledgementBeforeTheNextChildStarts() {
        Fixture f=new Fixture();f.first.result=WorkResult.blocked("full");f.first.effect=()->{f.open=true;f.owned=true;};
        f.step();assertEquals(1,f.sent.size());assertTrue(f.sent.get(0) instanceof Action.CloseContainer);
        f.step();assertEquals(0,f.second.calls);assertEquals(0,f.first.resets);
        f.closeOutcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"closed");f.open=false;f.inFlight=false;
        f.step();assertEquals(1,f.first.resets);assertEquals(0,f.second.calls);
        assertEquals(WorkResult.State.BLOCKED,f.step().state());assertEquals(1,f.second.calls);
    }
    @Test void failedCloseNeverRetriesOrStartsAnotherChildWithoutExplicitReset() {
        Fixture f=new Fixture();f.first.result=WorkResult.blocked("full");f.first.effect=()->{f.open=true;f.owned=true;};f.step();
        f.closeOutcome=new ActionOutcome(ActionOutcome.State.FAILED,"not confirmed");f.inFlight=false;
        for(int i=0;i<4;i++)assertEquals(WorkResult.State.BLOCKED,f.step().state());
        assertEquals(1,f.sent.size());assertEquals(0,f.second.calls);assertEquals(0,f.first.resets);
    }
    @Test void foreignMenuCursorAndLateFenceNeverAuthorizeCrossingTheChildBoundary() {
        for(int obstruction=0;obstruction<3;obstruction++) {
            Fixture f=new Fixture();int selected=obstruction;
            f.first.effect=()->{if(selected==0)f.open=true;else if(selected==1)f.cursor=true;else f.fence="uncertain swap";};
            assertEquals(WorkResult.State.BLOCKED,f.step().state());assertEquals(0,f.second.calls);
            assertTrue(f.sent.isEmpty());assertEquals(0,f.first.resets);
        }
    }
    @Test void terminalChildWithAnUnconsumedActionCannotInventACompletionAndAdvance() {
        Fixture f=new Fixture();f.first.effect=()->f.inFlight=true;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());f.inFlight=false;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());assertEquals(0,f.second.calls);assertEquals(0,f.first.resets);
    }
    @Test void wrongFeatureCannotBorrowTheEnabledNeighborOrRunEitherChild() {
        Fixture f=new Fixture();f.session.oneShotFeature=Feature.WINE;
        assertEquals(WorkResult.State.BLOCKED,f.step().state());assertEquals(0,f.first.calls);assertEquals(0,f.second.calls);
        assertThrows(IllegalArgumentException.class,()->new WineRoutineModule(Feature.WINE));
    }
    @Test void actualEngineReachesHarvestProductionAndSleepBelowAnEmptyStorageOrSaleSweep() {
        for(Feature feature:List.of(Feature.WINE_STORAGE,Feature.WINE_SURPLUS_SHIPPING)) {
            Fixture f=new Fixture(feature);List<Feature> reached=new ArrayList<>();
            AutomationEngine engine=engineWithLowerMarkers(f,reached);engine.start(f.context);
            for(int i=0;i<5;i++){engine.tick(f.context);f.ticks++;}
            assertEquals(List.of(Feature.HARVEST,Feature.WINE,Feature.SLEEP),reached);
            assertEquals(AutomationEngine.State.WAITING,engine.state());
            assertEquals(1,f.first.calls);assertEquals(1,f.second.calls);
        }
    }
    @Test void actualEngineReachesLowerWorkAfterABusyChildFinallySettles() {
        Fixture f=new Fixture();List<Feature> reached=new ArrayList<>();
        AutomationEngine engine=engineWithLowerMarkers(f,reached);engine.start(f.context);
        f.first.result=WorkResult.busy("real action waiting");engine.tick(f.context);f.ticks++;
        assertTrue(reached.isEmpty());assertEquals(0,f.second.calls);
        f.first.result=WorkResult.idle();
        for(int i=0;i<5;i++){engine.tick(f.context);f.ticks++;}
        assertEquals(List.of(Feature.HARVEST,Feature.WINE,Feature.SLEEP),reached);
        assertEquals(AutomationEngine.State.WAITING,engine.state());
    }
    @Test void actualOneShotEngineVisitsBothStorageOrSaleChildrenAndNeverRunsNeighbors() {
        for(Feature feature:List.of(Feature.WINE_STORAGE,Feature.WINE_SURPLUS_SHIPPING)) {
            Fixture f=new Fixture(feature);List<Feature> reached=new ArrayList<>();
            f.profile.enabled.put(feature,false);AutomationEngine engine=engineWithLowerMarkers(f,reached);
            engine.startOnce(f.context,feature);
            for(int i=0;i<5 && engine.running();i++){engine.tick(f.context);f.ticks++;}
            assertEquals(AutomationEngine.State.COMPLETE,engine.state());assertTrue(reached.isEmpty());
            assertEquals(1,f.first.calls);assertEquals(1,f.second.calls);assertFalse(f.profile.enabled(feature));
        }
    }
    private static AutomationEngine engineWithLowerMarkers(Fixture f,List<Feature> reached) {
        List<AutomationModule> modules=new ArrayList<>();modules.add(f.module);
        for(Feature feature:List.of(Feature.HARVEST,Feature.WINE,Feature.SLEEP)) {
            final int priority=40+modules.size();
            modules.add(new AutomationModule() {
                public Feature feature(){return feature;}public int priority(){return priority;}
                public WorkResult tick(Context c){reached.add(feature);return WorkResult.idle();}
                public void reset(){}
            });
        }
        return new AutomationEngine(modules);
    }
    private static final class Child implements AutomationModule {
        final Feature feature;WorkResult result=WorkResult.idle();Runnable effect=()->{};int calls,resets;
        Child(Feature feature){this.feature=feature;}public Feature feature(){return feature;}public int priority(){return 0;}
        public WorkResult tick(Context c){calls++;effect.run();return result;}public void reset(){resets++;}
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Feature feature;final Profile profile=new Profile();final SessionState session=new SessionState();
        final Child first,second;
        final WineRoutineModule module;
        final Context context=new Context(this,this,this,profile,session);final List<Action> sent=new ArrayList<>();
        boolean open,owned,cursor,inFlight;String fence;long ticks;
        ActionOutcome closeOutcome=new ActionOutcome(ActionOutcome.State.PENDING,"pending");
        Fixture(){this(Feature.WINE_STORAGE);}
        Fixture(Feature feature){this.feature=feature;first=new Child(feature);second=new Child(feature);module=new WineRoutineModule(feature,List.of(first,second));}
        WorkResult step(){return module.tick(context);}
        public long tick(){return ticks;}public long dayTime(){return 0;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,4,true,true);}
        public BlockData block(Pos pos){return new BlockData(pos,"minecraft:barrel",Map.of());}
        public boolean loaded(Pos pos){return true;}public boolean canStand(Pos pos){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos pos,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(open?1:0,0,List.of(),cursor?new ItemData(ItemData.WINE,1,0,0,false,999):ItemData.EMPTY,open);}
        public boolean mayPlace(int slot,ItemData item){return true;}public boolean busy(){return inFlight;}public boolean ownsContainer(){return owned&&open;}
        public String pauseReason(){return fence;}public long submit(Action action){sent.add(action);inFlight=true;return sent.size();}
        public ActionOutcome outcome(long ticket){return closeOutcome;}public void move(Movement movement){throw new AssertionError();}
        public void stopMovement(){}public void cancel(){}public Navigation.Result moveTo(Pos pos,double reach,Context c){return Navigation.Result.ARRIVED;}
        public void reset(){}
    }
}
