package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.modules.LoggingModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingEngineResumeTest {
    @Test void continuousRestoresParkedTomatoesWineAndShippingProductsBeforeAnyConsumerCanTakeThem() {
        for (ItemData original:List.of(item(ItemData.TOMATO,52),new ItemData(ItemData.WINE,9,0,10,false,1000),
            item(ItemData.PRESERVES,6),item(ItemData.PINE_TAR,20))) {
            Fixture f=new Fixture(original); f.engine.start(f.context);
            assertTrue(f.engine.running());
            f.runUntil(() -> f.consumerTicks>0);
            assertEquals(original.count(),f.consumed,original.id());
            assertFalse(f.profile.loggingRunActive); assertNull(f.profile.loggingHotbarLease);
            assertEquals(1,f.swaps); assertEquals(List.of("restore","restore_saved","logging_finished","consumer"),f.events);
            assertEquals(LoggingRules.AXE,f.inventory[2].id()); assertTrue(f.inventory[4].hoe());
        }
    }

    @Test void disabledLoggingBlocksContinuousStartWithoutTouchingItsParkedItem() {
        Fixture f=new Fixture(item(ItemData.TOMATO,52)); f.profile.enabled.put(Feature.LOGGING,false);
        f.engine.start(f.context); f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertTrue(f.engine.status().contains("미완료 벌목"));
        assertEquals(0,f.consumerTicks); assertEquals(0,f.swaps); assertNotNull(f.profile.loggingHotbarLease);
        assertEquals(item(ItemData.TOMATO,52),f.inventory[9]);
    }

    @Test void everyOtherOneShotIsRejectedUntilLoggingHasFinished() {
        for (Feature feature:Feature.values()) if (feature!=Feature.LOGGING) {
            Fixture f=new Fixture(item(ItemData.TOMATO,52)); f.engine.startOnce(f.context,feature); f.engine.tick(f.context);
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state(),feature.name());
            assertTrue(f.engine.status().contains("미완료 벌목")); assertEquals(0,f.consumerTicks); assertEquals(0,f.swaps);
            assertEquals(item(ItemData.TOMATO,52),f.inventory[9]);
        }
    }

    @Test void explicitLoggingOneShotCanRestoreWhileTheContinuousToggleIsOff() {
        Fixture f=new Fixture(item(ItemData.WINE,9)); f.profile.enabled.put(Feature.LOGGING,false);
        f.engine.startOnce(f.context,Feature.LOGGING); f.runUntil(() -> !f.engine.running());
        assertEquals(AutomationEngine.State.COMPLETE,f.engine.state()); assertEquals(0,f.consumerTicks);
        assertEquals(item(ItemData.WINE,9),f.inventory[0]); assertEquals(1,f.swaps);
        assertFalse(f.profile.loggingRunActive); assertNull(f.profile.loggingHotbarLease);
    }

    @Test void missingLoggingImplementationFailsClosedInBothExecutionModes() {
        Fixture f=new Fixture(item(ItemData.TOMATO,52)); f.engine=new AutomationEngine(List.of(f.consumer));
        f.engine.start(f.context); assertEquals(AutomationEngine.State.PAUSED,f.engine.state());
        assertTrue(f.engine.status().contains("실행 모듈"));
        f.engine.startOnce(f.context,Feature.LOGGING); assertEquals(AutomationEngine.State.PAUSED,f.engine.state());
        assertEquals(0,f.consumerTicks); assertEquals(0,f.swaps);
    }

    @Test void changedParkedItemPausesTheActualLoggingModuleWithoutYieldingToStorageOrProduction() {
        Fixture f=new Fixture(item(ItemData.TOMATO,52)); f.inventory[9]=item("minecraft:diamond",1);
        f.engine.start(f.context); f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertTrue(f.engine.status().contains("미완료 벌목"));
        assertTrue(f.engine.status().contains("아이템을 확인")); assertEquals(0,f.consumerTicks); assertEquals(0,f.swaps);
        assertTrue(f.profile.loggingRunActive); assertNotNull(f.profile.loggingHotbarLease); assertEquals("minecraft:diamond",f.inventory[9].id());
    }

    @Test void disablingAnActiveLoggingResumeDoesNotFallThroughToOtherEnabledJobs() {
        Fixture f=new Fixture(item(ItemData.TOMATO,52)); f.engine.start(f.context); f.engine.tick(f.context);
        assertTrue(f.engine.running()); f.profile.enabled.put(Feature.LOGGING,false); f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertEquals(0,f.consumerTicks);
        assertTrue(f.profile.loggingRunActive); assertNotNull(f.profile.loggingHotbarLease);
    }

    @Test void aNewBatchThatBecomesBlockedInTheSchedulerStopsBeforeLowerPriorityConsumers() {
        Fixture f=new Fixture(item(ItemData.TOMATO,52)); f.clearLogging();
        AutomationModule logging=stub(Feature.LOGGING,80,c -> { c.profile().loggingRunActive=true; return WorkResult.blocked("replant blocked"); });
        f.engine=new AutomationEngine(List.of(logging,stub(Feature.PRESERVES,90,c -> { f.consumerTicks++; return WorkResult.idle(); })));
        f.engine.start(f.context); f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertTrue(f.engine.status().contains("replant blocked"));
        assertEquals(0,f.consumerTicks); assertTrue(f.profile.loggingRunActive);
    }

    @Test void ordinaryInactiveLoggingBlockKeepsTheExistingSchedulerSweepBehaviour() {
        Fixture f=new Fixture(item(ItemData.TOMATO,52)); f.clearLogging();
        f.engine=new AutomationEngine(List.of(stub(Feature.LOGGING,80,c -> WorkResult.blocked("not configured")),
            stub(Feature.PRESERVES,90,c -> { f.consumerTicks++; return WorkResult.idle(); })));
        f.engine.start(f.context); f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.WAITING,f.engine.state()); assertEquals(1,f.consumerTicks);
        assertFalse(f.profile.loggingRunActive);
    }

    @Test void idleResultCannotClaimCompletionWhileTheDurableBatchStillExists() {
        Fixture f=new Fixture(item(ItemData.TOMATO,52));
        f.engine=new AutomationEngine(List.of(stub(Feature.LOGGING,80,c -> WorkResult.idle()),f.consumer));
        f.engine.startOnce(f.context,Feature.LOGGING); f.engine.tick(f.context);
        assertEquals(AutomationEngine.State.PAUSED,f.engine.state()); assertTrue(f.profile.loggingRunActive);
        assertEquals(0,f.consumerTicks); assertNotNull(f.profile.loggingHotbarLease);
    }

    private static AutomationModule stub(Feature feature,int priority,java.util.function.Function<Context,WorkResult> work) {
        return new AutomationModule() {
            public Feature feature() { return feature; }
            public int priority() { return priority; }
            public WorkResult tick(Context c) { return work.apply(c); }
            public void reset() { }
        };
    }
    private static ItemData item(String id,int count) { return new ItemData(id,count,0,null,false,1000); }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final ItemData[] inventory=new ItemData[36]; final List<String> events=new ArrayList<>();
        final ItemData original; final AutomationModule consumer; final Context context;
        AutomationEngine engine; Action pending; ActionOutcome outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        long ticks,sequence; int swaps,consumerTicks,consumed;
        Fixture(ItemData original) {
            this.original=original; Arrays.fill(inventory,ItemData.EMPTY); inventory[9]=original;
            inventory[2]=item(LoggingRules.AXE,1); inventory[4]=new ItemData("minecraft:golden_hoe",1,0,null,true,1000);
            profile.hoeHotbarSlot=4; profile.loggingAxeHotbarSlot=2; profile.enabled.put(Feature.LOGGING,true);
            profile.loggingPlots.add(new LoggingPlot("test plot",new Pos(2,64,0))); profile.loggingRunActive=true;
            profile.loggingHotbarLease=new LoggingHotbarLease(9,0,original,loggingItemFingerprint(9),LoggingHotbarLease.Stage.PARKED);
            context=new Context(this,this,this,profile,session,() -> {
                if (!profile.loggingRunActive) events.add("logging_finished");
                else if (profile.loggingHotbarLease==null) events.add("restore_saved");
            });
            Feature consumerFeature=original.is(ItemData.TOMATO) ? Feature.TOMATO_STORAGE : original.is(ItemData.WINE) ? Feature.WINE_STORAGE : Feature.SHIPPING;
            consumer=stub(consumerFeature,10,c -> {
                consumerTicks++; events.add("consumer");
                for (int i=0;i<36;i++) if (inventory[i].is(original.id())) { consumed+=inventory[i].count(); inventory[i]=ItemData.EMPTY; }
                return WorkResult.idle();
            });
            engine=new AutomationEngine(List.of(consumer,new LoggingModule()));
        }
        void clearLogging() { profile.loggingRunActive=false; profile.loggingHotbarLease=null; }
        void runUntil(java.util.function.BooleanSupplier done) {
            for (int i=0;i<100 && !done.getAsBoolean();i++) {
                engine.tick(context);
                if (pending instanceof Action.SwapHotbar swap) {
                    ItemData old=inventory[swap.hotbarSlot()]; inventory[swap.hotbarSlot()]=inventory[swap.inventoryIndex()]; inventory[swap.inventoryIndex()]=old;
                    swaps++; events.add("restore"); pending=null; outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"exact swap acknowledged");
                }
                ticks++;
            }
            assertTrue(done.getAsBoolean(),engine.status());
        }
        public long tick() { return ticks; }
        public long dayTime() { return 241000; }
        public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,4,true,true); }
        public BlockData block(Pos p) { return new BlockData(p,LoggingRules.SAPLING,Map.of()); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return true; }
        public boolean canTraverse(Pos from,Pos to) { return true; }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { List<ItemSlot> result=new ArrayList<>(); for(int i=0;i<36;i++) result.add(new ItemSlot(i,i,true,inventory[i])); return result; }
        public MenuData menu() { return new MenuData(0,0,inventory(),ItemData.EMPTY,false); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
        public String loggingItemFingerprint(int index) {
            try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(inventory[index].toString().getBytes(StandardCharsets.UTF_8))); }
            catch (Exception e) { throw new AssertionError(e); }
        }
        public boolean busy() { return pending!=null; }
        public long submit(Action action) {
            assertNull(pending); assertInstanceOf(Action.SwapHotbar.class,action);
            pending=action; outcome=new ActionOutcome(ActionOutcome.State.PENDING,""); return ++sequence;
        }
        public ActionOutcome outcome(long ticket) { return outcome; }
        public void move(Movement intent) { throw new AssertionError("Cleanup resume must not navigate"); }
        public void stopMovement() { }
        public void cancel() { pending=null; }
        public Result moveTo(Pos p,double reach,Context c) { throw new AssertionError("Replanted plots must not be recut"); }
        public void reset() { }
    }
}
