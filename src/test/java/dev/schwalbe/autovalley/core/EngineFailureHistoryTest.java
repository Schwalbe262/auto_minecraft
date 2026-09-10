package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EngineFailureHistoryTest {
    @Test void repeatingTheSameFailureUpdatesOneEntryEvenWithOtherFailuresBetweenIt() {
        EngineFailureHistory history=new EngineFailureHistory();
        history.recordWork(10,Feature.CRYSTAL_COPY,WorkResult.blocked("No server confirmation"));
        history.recordWork(11,Feature.WINE,WorkResult.deferred("No route"));
        for(int i=0;i<480;i++)history.recordWork(20L+i*20,Feature.CRYSTAL_COPY,WorkResult.blocked("No server confirmation"));
        List<EngineFailureHistory.Entry> entries=history.snapshot();assertEquals(2,entries.size());
        EngineFailureHistory.Entry last=entries.get(1);
        assertEquals(10,last.tick());assertEquals(9600,last.lastTick());assertEquals(481,last.occurrences());
        assertEquals(Feature.CRYSTAL_COPY,last.feature());assertEquals(EngineFailureHistory.Kind.BLOCKED,last.state());
    }
    @Test void onlySixteenDistinctEntriesAreRetainedAndTheSnapshotCannotMutateHistory() {
        EngineFailureHistory history=new EngineFailureHistory();
        for(int i=0;i<40;i++)history.recordWork(i,Feature.CRYSTAL_COPY,WorkResult.blocked("failure "+i));
        List<EngineFailureHistory.Entry> snapshot=history.snapshot();assertEquals(16,snapshot.size());
        assertEquals("failure 24",snapshot.get(0).message());assertEquals("failure 39",snapshot.get(15).message());
        assertThrows(UnsupportedOperationException.class,snapshot::clear);
        history.clear();assertTrue(history.snapshot().isEmpty());assertEquals(16,snapshot.size());
    }
    @Test void successfulNormalWaitAndManualLifecycleMessagesDoNotOccupyFailureHistory() {
        EngineFailureHistory history=new EngineFailureHistory();
        for(WorkResult result:List.of(WorkResult.idle(),WorkResult.busy("working"),WorkResult.cooldown("growing"),WorkResult.resourceWait("saplings")))
            history.recordWork(0,Feature.LOGGING,result);
        for(AutomationEngine.State state:List.of(AutomationEngine.State.OFF,AutomationEngine.State.COMPLETE,AutomationEngine.State.RUNNING,AutomationEngine.State.WAITING))
            history.recordStop(0,null,state,"normal transition");
        for(String message:List.of("Checking current state","단축키로 일시정지","설정 중","한 번 실행 준비","F8","긴급 정지 — 다시 시작하려면 F8"))
            history.recordStop(0,null,AutomationEngine.State.PAUSED,message);
        assertTrue(history.snapshot().isEmpty());
    }
    @Test void meaningfulStopsRetainDistinctKindsAndFeaturesWithoutCollectingWorldObjects() {
        EngineFailureHistory history=new EngineFailureHistory();
        history.recordWork(1,Feature.CRYSTAL_COPY,WorkResult.blocked("failed"));
        history.recordWork(2,Feature.CRYSTAL_COPY,WorkResult.deferred("failed"));
        history.recordWork(3,Feature.SEED_MAKER,WorkResult.blocked("failed"));
        history.recordStop(4,Feature.CRYSTAL_COPY,AutomationEngine.State.PAUSED,"failed");
        history.recordStop(5,null,AutomationEngine.State.ERROR,"Unexpected exception");
        assertEquals(5,history.snapshot().size());assertNull(history.snapshot().get(4).feature());
        assertEquals(EngineFailureHistory.Kind.ERROR,history.snapshot().get(4).state());
    }
    @Test void historyMasksKnownLocationItemAndAccountDetailsAndBoundsUntrustedMessages() {
        EngineFailureHistory history=new EngineFailureHistory();
        history.recordWork(1,Feature.CRYSTAL_COPY,WorkResult.blocked("Failed at Pos[x=-532, y=64, z=902], (-532, 64, 902), -532:64:902; ItemData[id=minecraft:diamond, count=3] society:jade; player@example.com C:\\Users\\private\\report.json https://private.example/path"));
        String message=history.snapshot().get(0).message();
        for(String privateDetail:List.of("-532","902","minecraft:diamond","society:jade","player@example.com","private"))assertFalse(message.contains(privateDetail),message);
        assertTrue(message.contains("Failed at"));
        history.recordStop(2,null,AutomationEngine.State.ERROR,"x".repeat(20000));
        assertEquals(EngineFailureHistory.MAX_MESSAGE_LENGTH,history.snapshot().get(1).message().length());
        history.recordStop(3,null,AutomationEngine.State.ERROR,"\n\t ");
        assertEquals(2,history.snapshot().size());
    }
    @Test void actualCrystalFailureSurvivesManualOffOnWithoutRepeatingGameActions() {
        Fixture f=new Fixture();Job job=new Job(WorkResult.blocked("Original crystal failure"));
        AutomationEngine engine=new AutomationEngine(List.of(job));engine.startOnce(f.context,Feature.CRYSTAL_COPY);engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,engine.state());assertEquals(1,job.calls);
        assertEquals(2,engine.failureHistory().size());assertEquals("Original crystal failure",engine.failureHistory().get(0).message());
        engine.stop(f.context,AutomationEngine.State.PAUSED,"단축키로 일시정지");
        assertEquals(2,engine.failureHistory().size());
        job.result=WorkResult.idle();engine.startOnce(f.context,Feature.CRYSTAL_COPY);engine.tick(f.context);
        assertEquals(AutomationEngine.State.COMPLETE,engine.state());assertEquals(2,job.calls);
        assertEquals(2,engine.failureHistory().size());assertEquals(0,f.submissions);
        engine.clearFailureHistory();assertTrue(engine.failureHistory().isEmpty());
    }
    @Test void continuousFailuresAreObservedBeforeAnotherModulesStatusOrBackoffReplacesThem() {
        for(boolean deferred:List.of(false,true)) {
            Fixture f=new Fixture();f.profile.enabled.put(Feature.CRYSTAL_COPY,true);
            Job job=new Job(deferred?WorkResult.deferred("Crystal route unavailable"):WorkResult.blocked("Crystal recipe unavailable"));
            AutomationEngine engine=new AutomationEngine(List.of(job));engine.start(f.context);engine.tick(f.context);
            assertEquals(AutomationEngine.State.WAITING,engine.state());assertEquals(1,engine.failureHistory().size());
            assertEquals(Feature.CRYSTAL_COPY,engine.failureHistory().get(0).feature());
            assertEquals(deferred?EngineFailureHistory.Kind.DEFERRED:EngineFailureHistory.Kind.BLOCKED,engine.failureHistory().get(0).state());
            assertEquals(job.result.message(),engine.failureHistory().get(0).message());assertEquals(0,f.submissions);
        }
    }
    @Test void exceptionAttributionPreservesOriginalThrowAndUnchangedErrorStop() {
        Fixture f=new Fixture();f.profile.enabled.put(Feature.CRYSTAL_COPY,true);
        Job job=new Job(WorkResult.idle());IllegalStateException original=new IllegalStateException("private native detail");job.failure=original;
        AutomationEngine engine=new AutomationEngine(List.of(job));engine.start(f.context);
        assertSame(original,assertThrows(IllegalStateException.class,()->engine.tick(f.context)));
        assertEquals(AutomationEngine.State.ERROR,engine.state());assertEquals(1,engine.failureHistory().size());
        assertEquals(Feature.CRYSTAL_COPY,engine.failureHistory().get(0).feature());
        assertEquals("Stopped after an unexpected error: IllegalStateException",engine.failureHistory().get(0).message());
    }
    private static final class Job implements AutomationModule {
        WorkResult result;RuntimeException failure;int calls;
        Job(WorkResult result){this.result=result;}
        public Feature feature(){return Feature.CRYSTAL_COPY;}public int priority(){return 74;}
        public WorkResult tick(Context c){calls++;if(failure!=null)throw failure;return result;}public void reset(){}
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final Context context=new Context(this,this,this,profile,session);int submissions;
        public long tick(){return 100;}public long dayTime(){return 0;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,4,true,true);}
        public BlockData block(Pos pos){return new BlockData(pos,"minecraft:air",Map.of());}
        public boolean loaded(Pos pos){return true;}public boolean canStand(Pos pos){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos pos,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return true;}
        public boolean busy(){return false;}public long submit(Action action){submissions++;throw new AssertionError("History must not send actions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("History must not inspect outcomes");}
        public void move(Movement movement){throw new AssertionError();}public void stopMovement(){}public void cancel(){}
        public Navigation.Result moveTo(Pos pos,double reach,Context c){throw new AssertionError();}public void reset(){}
    }
}
