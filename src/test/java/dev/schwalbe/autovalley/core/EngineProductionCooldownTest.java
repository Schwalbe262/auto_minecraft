package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineProductionCooldownTest {
    @Test void normalWaitNeverAccumulatesErrorBackoffOrResetsAndOtherJobsContinue() {
        Fixture f=new Fixture();Job wine=f.job(Feature.WINE,10),other=f.job(Feature.PRESERVES,20);
        wine.result=WorkResult.cooldown("1/272 통 생산 중");AutomationEngine e=f.engine();e.start(f.c);int reset=wine.resets;
        for(int cycle=0;cycle<12;cycle++) {
            f.ticks=cycle*100;e.tick(f.c);assertEquals(cycle+1,wine.calls);assertEquals(reset,wine.resets);
            assertEquals(AutomationEngine.State.WAITING,e.state());assertTrue(e.status().contains("정상 생산 대기"));
            assertFalse(e.status().contains("재료·응답 보류"));assertFalse(e.status().contains("6000"));
            f.ticks+=20;e.tick(f.c);assertEquals(cycle+1,wine.calls);
        }
        assertEquals(24,other.calls);assertEquals(Map.of("kept",99L),f.profile.nextEligibleDay);
    }
    @Test void dayChangeWakesOnlyNormalCooldownBeforeItsPreviousDeadline() {
        Fixture f=new Fixture();Job wine=f.job(Feature.WINE,10),route=f.job(Feature.HARVEST,20);
        wine.result=WorkResult.cooldown("working");route.result=WorkResult.deferred("actual route error");
        AutomationEngine e=f.engine();e.start(f.c);e.tick(f.c);assertEquals(1,wine.calls);assertEquals(1,route.calls);
        f.ticks=1;f.time+=24000;e.tick(f.c);assertEquals(2,wine.calls);assertEquals(1,route.calls);
        f.ticks=20;e.tick(f.c);assertEquals(2,wine.calls);
    }
    @Test void normalCooldownAllowsBedtimeButDoesNotCompleteOneShotOrRunOtherJobs() {
        Fixture f=new Fixture();Job wine=f.job(Feature.WINE,10),bed=f.job(Feature.SLEEP,100);
        wine.result=WorkResult.cooldown("working");AutomationEngine e=f.engine();e.start(f.c);e.tick(f.c);
        assertEquals(1,bed.calls);assertEquals(AutomationEngine.State.WAITING,e.state());
        e.startOnce(f.c,Feature.WINE);e.tick(f.c);int oldBed=bed.calls,oldWine=wine.calls;
        assertEquals(Feature.WINE,f.session.oneShotFeature);f.ticks+=99;e.tick(f.c);
        assertEquals(oldWine,wine.calls);assertEquals(oldBed,bed.calls);assertTrue(e.running());assertTrue(e.status().contains("1초"));
        wine.result=WorkResult.idle();f.ticks++;e.tick(f.c);assertEquals(AutomationEngine.State.COMPLETE,e.state());assertNull(f.session.oneShotFeature);
    }
    @Test void manualOffStaysOffAcrossDatesAndReadinessChanges() {
        Fixture f=new Fixture();Job wine=f.job(Feature.WINE,10);wine.result=WorkResult.cooldown("working");
        AutomationEngine e=f.engine();e.start(f.c);e.tick(f.c);e.stop(f.c,AutomationEngine.State.PAUSED,"manual F8");
        wine.result=WorkResult.busy("ready");f.time+=24000;f.ticks+=10000;e.tick(f.c);
        assertFalse(e.running());assertEquals("manual F8",e.status());assertEquals(1,wine.calls);
    }
    @Test void unsafeUnknownOrNonWineCooldownNeverGrantsSleepOrRetries() {
        for(int cause=0;cause<7;cause++) {
            Fixture f=new Fixture();Job wine=f.job(cause==6?Feature.HARVEST:Feature.WINE,10),bed=f.job(Feature.SLEEP,100);
            wine.result=WorkResult.cooldown("unsafe");AutomationEngine e=f.engine();e.start(f.c);
            if(cause==0)f.container=true;else if(cause==1)f.carried=new ItemData(ItemData.TOMATO,1,0,null,false,1);
            else if(cause==2)f.pending=true;else if(cause==3)f.ground=false;else if(cause==4)wine.safe=false;else if(cause==5)f.fence="unconfirmed native reply";
            e.tick(f.c);assertEquals(AutomationEngine.State.PAUSED,e.state());assertEquals(0,bed.calls);
            int called=wine.calls;f.ticks+=10000;e.tick(f.c);assertEquals(called,wine.calls);
        }
    }
    @Test void ordinaryNavigationFailureStillPreventsSleepDespiteWineNormalCooldown() {
        Fixture f=new Fixture();Job wine=f.job(Feature.WINE,10),route=f.job(Feature.HARVEST,20),bed=f.job(Feature.SLEEP,100);
        wine.result=WorkResult.cooldown("working");route.result=WorkResult.deferred("path");
        AutomationEngine e=f.engine();e.start(f.c);e.tick(f.c);assertEquals(0,bed.calls);assertTrue(e.status().contains("이동 보류"));
        f.time+=24000;f.ticks=1;e.tick(f.c);assertEquals(0,bed.calls);assertEquals(1,route.calls);
    }
    @Test void readyJobStartsAtNextBoundedPollWithoutWaitingMinutes() {
        Fixture f=new Fixture();Job wine=f.job(Feature.WINE,10);wine.result=WorkResult.cooldown("working");
        AutomationEngine e=f.engine();e.start(f.c);e.tick(f.c);wine.result=WorkResult.busy("actual resumed production");
        f.ticks=80;e.tick(f.c);assertEquals(1,wine.calls);f.ticks=100;e.tick(f.c);
        assertEquals(2,wine.calls);assertEquals(AutomationEngine.State.RUNNING,e.state());assertEquals("actual resumed production",e.status());
    }
    @Test void displayedRecheckTimeCountsDownAndClockRollbackStartsFreshObservation() {
        Fixture f=new Fixture();Job wine=f.job(Feature.WINE,10);wine.result=WorkResult.cooldown("working");
        f.ticks=5000;AutomationEngine e=f.engine();e.startOnce(f.c,Feature.WINE);e.tick(f.c);assertTrue(e.status().contains("5초"));
        f.ticks=5060;e.tick(f.c);assertTrue(e.status().contains("2초"));f.ticks=1;e.tick(f.c);assertEquals(2,wine.calls);assertTrue(e.status().contains("5초"));
    }
    private static final class Job implements AutomationModule {
        final Feature feature;final int priority;int calls,resets;boolean safe=true;WorkResult result=WorkResult.idle();
        Job(Feature feature,int priority){this.feature=feature;this.priority=priority;}
        public Feature feature(){return feature;}public int priority(){return priority;}public WorkResult tick(Context c){calls++;return result;}
        public void reset(){resets++;}public boolean sleepSafeDeferred(Context c){return feature==Feature.WINE && safe;}
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();final List<AutomationModule> jobs=new ArrayList<>();
        final Context c=new Context(this,this,this,profile,session);long ticks,time=6*24000+5000;boolean container,pending,ground=true;String fence;ItemData carried=ItemData.EMPTY;
        Fixture(){for(Feature feature:Feature.values())profile.enabled.put(feature,false);profile.nextEligibleDay.put("kept",99L);}
        Job job(Feature feature,int priority){Job job=new Job(feature,priority);jobs.add(job);profile.enabled.put(feature,true);return job;}
        AutomationEngine engine(){return new AutomationEngine(jobs);}
        public long tick(){return ticks;}public long dayTime(){return time;}public PlayerState player(){return new PlayerState(.5,64,.5,0,0,ground,false,20,20,0,true,true);}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}public boolean loaded(Pos p){return true;}
        public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){return List.of();}public MenuData menu(){return new MenuData(0,0,List.of(),carried,container);}public boolean mayPlace(int index,ItemData item){return false;}
        public boolean busy(){return pending;}public long submit(Action action){throw new AssertionError("Scheduler wait must not dispatch actions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError();}public void move(Movement movement){}public void stopMovement(){}public void cancel(){}
        public String pauseReason(){return fence;}public Result moveTo(Pos p,double reach,Context c){throw new AssertionError();}public void reset(){}
    }
}
