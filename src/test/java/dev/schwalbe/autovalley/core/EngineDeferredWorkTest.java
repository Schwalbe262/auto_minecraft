package dev.schwalbe.autovalley.core;

import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineDeferredWorkTest {
    @Test void continuousDeferralRunsOtherWorkButNeitherRetriesEarlyNorSleepsPastUnfinishedWork() {
        Fixture f=new Fixture(); Job harvest=new Job(Feature.HARVEST,10),storage=new Job(Feature.TOMATO_STORAGE,20),sleep=new Job(Feature.SLEEP,100);
        harvest.result=WorkResult.deferred("field A destination (10,64,0)");
        AutomationEngine engine=f.engine(harvest,storage,sleep); engine.start(f.context); engine.tick(f.context);
        assertEquals(1,harvest.calls); assertEquals(1,storage.calls); assertEquals(0,sleep.calls);
        assertEquals(AutomationEngine.State.WAITING,engine.state()); assertTrue(engine.status().contains("HARVEST"));
        for(int tick=20;tick<1200;tick+=20) { f.ticks=tick; engine.tick(f.context); }
        assertEquals(1,harvest.calls); assertTrue(storage.calls>1); assertEquals(0,sleep.calls);
        f.ticks=1200; engine.tick(f.context); assertEquals(2,harvest.calls); assertTrue(engine.status().contains("2400"));
        assertEquals(Map.of("harvest:field",7L),f.profile.nextEligibleDay);
    }

    @Test void oneShotDeferralRemainsWaitingAndKeepsOnlyItsPermissionUntilActualIdle() {
        Fixture f=new Fixture(); Job wine=new Job(Feature.WINE,10),storage=new Job(Feature.TOMATO_STORAGE,20);
        wine.result=WorkResult.deferred("keg destination"); AutomationEngine engine=f.engine(wine,storage);
        engine.startOnce(f.context,Feature.WINE); engine.tick(f.context);
        assertEquals(AutomationEngine.State.WAITING,engine.state()); assertEquals(Feature.WINE,f.session.oneShotFeature);
        assertEquals(0,storage.calls); wine.result=WorkResult.idle(); f.ticks=1199; engine.tick(f.context);
        assertEquals(1,wine.calls); assertTrue(engine.running());
        f.ticks=1200; engine.tick(f.context); assertEquals(AutomationEngine.State.COMPLETE,engine.state());
        assertEquals(2,wine.calls); assertEquals(0,storage.calls); assertNull(f.session.oneShotFeature);
    }

    @Test void aWineRouteDeferralDoesNotBlockIndependentlyRunnablePreserves() {
        Fixture f=new Fixture(); Job wine=new Job(Feature.WINE,60),jars=new Job(Feature.PRESERVES,70);
        wine.result=WorkResult.deferred("wine path"); jars.result=WorkResult.busy("jar processing");
        AutomationEngine engine=f.engine(wine,jars); engine.start(f.context); engine.tick(f.context);
        assertEquals(1,wine.calls); assertEquals(1,jars.calls); assertEquals(AutomationEngine.State.RUNNING,engine.state());
        assertEquals("jar processing",engine.status());
    }
    @Test void disablingAnAlreadyDeferredFeatureReleasesItsSchedulerHoldWithoutRetryingIt() {
        Fixture f=new Fixture(); Job harvest=new Job(Feature.HARVEST,10),sleep=new Job(Feature.SLEEP,100);
        harvest.result=WorkResult.deferred("field"); AutomationEngine engine=f.engine(harvest,sleep);
        engine.start(f.context);engine.tick(f.context);assertEquals(0,sleep.calls);
        f.profile.enabled.put(Feature.HARVEST,false);f.ticks=20;engine.tick(f.context);
        assertEquals(1,harvest.calls);assertEquals(1,sleep.calls);
    }

    @Test void repeatedOrdinaryFailuresBackOffToSixThousandTicksAndResetTransientState() {
        Fixture f=new Fixture(); Job job=new Job(Feature.HARVEST,10); job.result=WorkResult.deferred("field");
        AutomationEngine engine=f.engine(job); engine.startOnce(f.context,Feature.HARVEST);
        int resetAtStart=job.resets;
        for(int failure=1;failure<=8;failure++) {
            engine.tick(f.context); assertEquals(failure,job.calls); assertEquals(resetAtStart+failure,job.resets);
            long delay=Math.min(5,failure)*1200L;
            assertTrue(engine.status().contains(Long.toString(delay))); f.ticks+=delay-1; engine.tick(f.context);
            assertEquals(failure,job.calls); f.ticks++;
        }
        assertTrue(engine.running()); assertEquals(Map.of("harvest:field",7L),f.profile.nextEligibleDay);
    }

    @Test void anActiveMultiTickJobYieldsOnSafeDeferralWithoutImmediatelyRestartingIt() {
        Fixture f=new Fixture(); Job first=new Job(Feature.HARVEST,10),second=new Job(Feature.TOMATO_STORAGE,20);
        first.result=WorkResult.busy("approach"); AutomationEngine engine=f.engine(first,second);
        engine.start(f.context); engine.tick(f.context); first.result=WorkResult.deferred("route exhausted");
        f.ticks++; engine.tick(f.context); assertEquals(2,first.calls); assertEquals(1,second.calls);
        f.ticks+=20; engine.tick(f.context); assertEquals(2,first.calls); assertEquals(2,second.calls);
    }

    @Test void unfinishedLoggingIsPausedInsteadOfYieldingItsParkedItemsOrReplantObligation() {
        Fixture f=new Fixture(); Job logging=new Job(Feature.LOGGING,10),consumer=new Job(Feature.SHIPPING,20);
        logging.hook=c -> { c.profile().loggingRunActive=true; c.profile().loggingRemainingPlots.add(new Pos(0,64,0)); };
        logging.result=WorkResult.deferred("replant path"); AutomationEngine engine=f.engine(logging,consumer);
        engine.start(f.context); engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(0,consumer.calls);
        assertTrue(f.profile.loggingRunActive); assertEquals(1,f.profile.loggingRemainingPlots.size());
    }

    @Test void cursorOpenContainerPendingInventoryActionOrAirborneStateNeverBecomeOrdinaryBackoff() {
        for(int cause=0;cause<4;cause++) {
            Fixture f=new Fixture(); Job job=new Job(Feature.HARVEST,10),consumer=new Job(Feature.SHIPPING,20);
            final int selected=cause;
            job.hook=c -> { if(selected==0) f.carried=new ItemData(ItemData.TOMATO,1,0,null,false,0); else if(selected==1) f.container=true; else if(selected==2) f.pending=true; else f.grounded=false; };
            job.result=WorkResult.deferred("unsafe to yield"); AutomationEngine engine=f.engine(job,consumer);
            engine.start(f.context); engine.tick(f.context); assertEquals(AutomationEngine.State.PAUSED,engine.state());
            assertEquals(0,consumer.calls); f.ticks+=10000; engine.tick(f.context); assertEquals(1,job.calls);
        }
    }

    @Test void nativeFailureFenceStopsBeforeDeferredRetryWithoutClearingIt() {
        Fixture f=new Fixture(); Job job=new Job(Feature.WINE,10); job.result=WorkResult.deferred("ordinary path");
        AutomationEngine engine=f.engine(job); engine.startOnce(f.context,Feature.WINE); engine.tick(f.context);
        f.fence="uncertain native inventory reply"; f.ticks=1200; engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,job.calls); assertEquals(f.fence,engine.status());
    }

    @Test void clockRollbackDoesNotLeaveADeferredJobWaitingOnAnOldTickEpoch() {
        Fixture f=new Fixture(); f.ticks=5000; Job job=new Job(Feature.HARVEST,10); job.result=WorkResult.deferred("field");
        AutomationEngine engine=f.engine(job); engine.startOnce(f.context,Feature.HARVEST); engine.tick(f.context);
        f.ticks=5; engine.tick(f.context); assertEquals(2,job.calls); assertTrue(engine.status().contains("1200"));
        assertEquals(Map.of("harvest:field",7L),f.profile.nextEligibleDay);
    }

    private static class Job implements AutomationModule {
        final Feature feature; final int priority; int calls,resets; WorkResult result=WorkResult.idle(); Consumer<Context> hook=c -> {};
        Job(Feature feature,int priority) { this.feature=feature; this.priority=priority; }
        public Feature feature(){return feature;} public int priority(){return priority;}
        public WorkResult tick(Context c){calls++; hook.accept(c); return result;} public void reset(){resets++;}
    }
    private static class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); final SessionState session=new SessionState(); final Context context=new Context(this,this,this,profile,session);
        long ticks; boolean container,pending,grounded=true; ItemData carried=ItemData.EMPTY; String fence;
        Fixture(){ for(Feature feature:Feature.values()) profile.enabled.put(feature,false); profile.nextEligibleDay.put("harvest:field",7L); }
        AutomationEngine engine(Job... jobs){for(Job job:jobs)profile.enabled.put(job.feature,true);return new AutomationEngine(Arrays.asList(jobs));}
        public long tick(){return ticks;}public long dayTime(){return 6*24000+13000;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,grounded,false,20,20,0,true,true);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public boolean loaded(Pos p){return true;} public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),carried,container);}public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return pending;}public long submit(Action action){throw new AssertionError("No actions in scheduler fixture");}
        public ActionOutcome outcome(long ticket){throw new AssertionError();}public void move(Movement movement){}public void stopMovement(){}public void cancel(){}
        public String pauseReason(){return fence;} public Result moveTo(Pos p,double reach,Context c){return Result.BLOCKED;}public void reset(){}
    }
}
