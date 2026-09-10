package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.modules.LoggingModule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real logging observations must yield safely without erasing the durable batch. */
class EngineLoggingEnvironmentWaitTest {
    @Test void snowBeforeABatchStartsAllowsOtherWorkAndSleepWhileUsingTheNormalRetryBackoff() {
        Fixture f=new Fixture("minecraft:snow");
        f.profile.loggingRunActive=false;f.profile.loggingRemainingPlots.clear();f.profile.loggingReplantingPlots.clear();
        f.profile.nextEligibleDay.remove(LoggingRules.DUE_KEY);f.start(false);f.untilWait();
        assertEquals(1,f.logging.calls);assertTrue(f.otherCalls>0);assertTrue(f.sleepCalls>0);
        long initial=f.ticks;
        for(int elapsed=20;elapsed<1200;elapsed+=20)f.at(initial+elapsed);
        assertEquals(1,f.logging.calls);assertTrue(f.sleepCalls>1);
        f.at(initial+1200);assertEquals(2,f.logging.calls);int sleep=f.sleepCalls;
        f.at(initial+2380);assertEquals(2,f.logging.calls);assertTrue(f.sleepCalls>sleep);
        f.at(initial+3600);assertEquals(3,f.logging.calls);assertTrue(f.engine.running(),f.engine.status());
        assertFalse(f.profile.loggingRunActive);assertTrue(f.profile.loggingRemainingPlots.isEmpty());
        assertTrue(f.profile.loggingReplantingPlots.isEmpty());assertFalse(f.profile.nextEligibleDay.containsKey(LoggingRules.DUE_KEY));
        assertEquals(0,f.checkpoints);assertEquals(0,f.sent);
    }

    @Test void obstructedRegisteredPlantingKeepsContinuousWorkAndSleepRunningAcrossBoundedRetries() {
        for(String obstruction:List.of("minecraft:spruce_leaves","minecraft:stone_bricks")) {
            Fixture f=new Fixture(obstruction);f.start(false);f.untilWait();
            assertTrue(f.otherCalls>0);assertTrue(f.sleepCalls>0);f.assertPreserved();
            int initialResets=f.logging.resets;
            for(int attempt=0;attempt<4;attempt++) {
                long grantedAt=f.ticks;int calls=f.logging.calls,other=f.otherCalls,sleep=f.sleepCalls;
                for(int elapsed=20;elapsed<1200;elapsed+=20)f.at(grantedAt+elapsed);
                assertEquals(calls,f.logging.calls,"Unchanged terrain must not cause a tight retry loop");
                assertTrue(f.otherCalls>other);assertTrue(f.sleepCalls>sleep);
                f.at(grantedAt+1200);f.untilWait();
                assertTrue(f.logging.calls>calls);assertEquals(initialResets,f.logging.resets);
                assertEquals(AutomationEngine.State.WAITING,f.engine.state(),f.engine.status());f.assertPreserved();
            }
            assertEquals(obstruction,f.block(f.plot.corner()).id());assertEquals(0,f.checkpoints);
        }
    }

    @Test void clearingTheObstructionResumesObservationAndThenWaitsForMissingSaplingsWithoutFalseCompletion() {
        Fixture f=new Fixture("minecraft:spruce_leaves");f.start(false);f.untilWait();
        long grantedAt=f.ticks;int calls=f.logging.calls;
        f.plantingBlock="minecraft:air";f.at(grantedAt+1180);
        assertEquals(calls,f.logging.calls,"A new terrain observation still respects its retained retry deadline");
        f.at(grantedAt+1200);f.untilWait();
        assertTrue(f.logging.calls>calls);assertTrue(f.logging.sleepSafeResourceWait(f.context));
        assertEquals(AutomationModule.ResourceReadiness.WAITING,f.logging.resourceReadiness(f.context));
        assertTrue(f.otherCalls>1);assertTrue(f.sleepCalls>1);f.assertPreserved();assertEquals(0,f.checkpoints);
    }

    @Test void obstructionAppearingDuringAMaterialWaitBecomesATerrainWaitWithoutPausingOtherJobs() {
        for(String obstruction:List.of("minecraft:spruce_leaves","minecraft:stone_bricks",LoggingRules.LOG)) {
            Fixture f=new Fixture("minecraft:air");f.start(false);f.untilWait();
            int before=f.otherCalls;
            f.obstructedCell=f.plot.corner();f.plantingBlock=obstruction;f.at(f.ticks+20);f.untilWait();
            assertTrue(f.engine.running(),obstruction+": "+f.engine.status());assertTrue(f.otherCalls>before);
            assertTrue(f.logging.sleepSafeResourceWait(f.context));f.assertPreserved();
            int calls=f.logging.calls;f.at(f.ticks+1180);assertEquals(calls,f.logging.calls);
        }
    }

    @Test void anExpiredTerrainRetryWaitsForAnotherModulesOwnedTransactionToFinish() {
        Fixture f=new Fixture("minecraft:spruce_leaves");f.otherResult=WorkResult.busy("owned native action");
        f.start(false);f.until(() -> f.otherCalls>0);
        int calls=f.logging.calls;f.nativeBusy=true;f.container=true;f.at(f.ticks+1400);
        assertEquals(calls,f.logging.calls);assertTrue(f.engine.running());assertEquals(0,f.sleepCalls);
        f.nativeBusy=false;f.container=false;f.otherResult=WorkResult.idle();f.at(f.ticks+1);
        assertEquals(calls,f.logging.calls,"Yield grants ownership before the next logging tick");
        f.at(f.ticks+1);f.untilWait();
        assertTrue(f.logging.calls>calls);assertTrue(f.sleepCalls>0);f.assertPreserved();
    }

    @Test void environmentalOneShotAndManualOffKeepTheirExistingExecutionScope() {
        for(boolean once:new boolean[]{false,true}) {
            Fixture f=new Fixture("minecraft:spruce_leaves");
            if(once)f.profile.enabled.put(Feature.LOGGING,false);
            f.start(once);f.untilWait();int calls=f.logging.calls;
            f.at(f.ticks+1200);f.untilWait();assertTrue(f.logging.calls>calls);
            if(once) {
                assertEquals(0,f.otherCalls);assertEquals(0,f.sleepCalls);
                assertFalse(f.profile.enabled(Feature.LOGGING));assertEquals(Feature.LOGGING,f.session.oneShotFeature);
            }
            f.engine.stop(f.context,AutomationEngine.State.OFF,"manual F8 OFF");
            calls=f.logging.calls;int other=f.otherCalls,sleep=f.sleepCalls;
            f.plantingBlock="minecraft:air";f.at(f.ticks+5000);
            assertEquals(AutomationEngine.State.OFF,f.engine.state());assertEquals("manual F8 OFF",f.engine.status());
            assertEquals(calls,f.logging.calls);assertEquals(other,f.otherCalls);assertEquals(sleep,f.sleepCalls);f.assertPreserved();
        }
    }

    @Test void explicitFeatureOffSuspendsEnvironmentalRetryWithoutLosingTheSavedObligations() {
        Fixture f=new Fixture("minecraft:spruce_leaves");f.start(false);f.untilWait();
        int calls=f.logging.calls,other=f.otherCalls,sleep=f.sleepCalls;
        f.profile.enabled.put(Feature.LOGGING,false);f.at(f.ticks+20);f.at(f.ticks+5000);
        assertTrue(f.engine.running());assertEquals(calls,f.logging.calls);
        assertTrue(f.otherCalls>other);assertTrue(f.sleepCalls>sleep);
        assertFalse(f.profile.enabled(Feature.LOGGING));f.assertPreserved();
    }

    @Test void snowObstructionRestoresTheBorrowedSlotThroughOneAcknowledgedInverseBeforeOtherWorkOrSleep() {
        for(String response:List.of("acknowledged","failed","native fence")) {
            Fixture f=new Fixture("minecraft:snow");
            ItemData original=item(ItemData.TOMATO,52),working=item(LoggingRules.SAPLING,3);
            f.items[9]=original;f.items[0]=working;f.allowRestore=true;
            f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,original,f.loggingItemFingerprint(9),LoggingHotbarLease.Stage.PARKED);
            f.start(false);f.until(()->f.pending!=null);
            Action.SwapHotbar inverse=assertInstanceOf(Action.SwapHotbar.class,f.pending);
            assertEquals(9,inverse.inventoryIndex());assertEquals(0,inverse.hotbarSlot());
            LoggingHotbarLease restoring=f.profile.loggingHotbarLease;
            assertEquals(LoggingHotbarLease.Stage.RESTORING,restoring.stage());assertEquals(1,f.checkpoints);
            assertEquals(0,f.otherCalls);assertEquals(0,f.sleepCalls);assertEquals(1,f.sent);
            f.at(f.ticks+f.profile.interactionTimeoutTicks+2400);
            assertTrue(f.engine.running(),f.engine.status());assertSame(inverse,f.pending);
            assertEquals(ActionOutcome.State.PENDING,f.outcome(1).state());assertEquals(1,f.sent);
            assertEquals(0,f.otherCalls);assertEquals(0,f.sleepCalls);assertEquals(original,f.items[9]);
            if(response.equals("acknowledged")) {
                f.respondToInverse(true);f.at(f.ticks+1);f.untilWait();
                assertTrue(f.engine.running(),f.engine.status());assertTrue(f.otherCalls>0);assertTrue(f.sleepCalls>0);
                assertNull(f.profile.loggingHotbarLease);assertEquals(original,f.items[0]);assertEquals(working,f.items[9]);
                assertEquals(2,f.checkpoints,"Only restoring and restored custody checkpoints are written");
                int calls=f.logging.calls;f.at(f.ticks+1180);assertEquals(calls,f.logging.calls);
            } else {
                if(response.equals("failed"))f.respondToInverse(false);
                else f.nativeFence="Unconfirmed native inverse acknowledgement";
                f.at(f.ticks+1);
                assertEquals(AutomationEngine.State.PAUSED,f.engine.state(),response+": "+f.engine.status());
                assertEquals(restoring,f.profile.loggingHotbarLease);assertEquals(0,f.otherCalls);assertEquals(0,f.sleepCalls);
                assertEquals(original,f.items[9]);assertEquals(working,f.items[0]);assertEquals(1,f.checkpoints);
            }
            assertEquals(1,f.sent,"A timeout or terrain retry must never duplicate the inverse");
            assertEquals("minecraft:snow",f.block(f.plot.corner()).id());
            assertEquals("1",f.block(f.plot.corner()).properties().get("layers"));f.assertObligations();
        }
    }

    @Test void terrainWaitCannotHideNativeCursorCustodyOrDurableRecordFailures() {
        for(String failure:List.of("native","busy","cursor","container","logging lease","work lease","registration")) {
            Fixture f=new Fixture("minecraft:spruce_leaves");f.start(false);f.untilWait();
            int other=f.otherCalls,sleep=f.sleepCalls;
            switch(failure) {
                case "native" -> f.nativeFence="Unconfirmed native acknowledgement";
                case "busy" -> f.nativeBusy=true;
                case "cursor" -> f.cursor=item("minecraft:diamond",1);
                case "container" -> f.container=true;
                case "logging lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item("minecraft:torch",2),
                    "a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                case "work lease" -> f.profile.workHotbarLease=new HotbarLease(Feature.STARFRUIT,9,0,item("minecraft:torch",2),
                    "a".repeat(64),HotbarLease.Stage.PARKED);
                case "registration" -> f.profile.loggingPlots.set(0,new LoggingPlot("changed registration",f.plot.corner()));
                default -> throw new AssertionError(failure);
            }
            f.at(f.ticks+20);
            assertEquals(AutomationEngine.State.PAUSED,f.engine.state(),failure+": "+f.engine.status());
            assertEquals(other,f.otherCalls);assertEquals(sleep,f.sleepCalls);f.assertPreserved();
            if(failure.equals("logging lease"))assertNotNull(f.profile.loggingHotbarLease);
            if(failure.equals("work lease"))assertNotNull(f.profile.workHotbarLease);
        }
    }

    private static final class CountingLogging implements AutomationModule {
        final LoggingModule delegate=new LoggingModule();int calls,resets;
        public Feature feature(){return Feature.LOGGING;}public int priority(){return delegate.priority();}
        public WorkResult tick(Context c){calls++;return delegate.tick(c);}
        public ResourceReadiness resourceReadiness(Context c){return delegate.resourceReadiness(c);}
        public boolean sleepSafeResourceWait(Context c){return delegate.sleepSafeResourceWait(c);}
        public boolean sleepSafeDeferred(Context c){return delegate.sleepSafeDeferred(c);}
        public void reset(){resets++;delegate.reset();}
    }

    private static ItemData item(String id,int count){return new ItemData(id,count,0,null,false,1000);}
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final LoggingPlot plot=new LoggingPlot("retained plot",new Pos(2,64,0));
        final CountingLogging logging=new CountingLogging();final Context context;final AutomationEngine engine;
        final ItemData[] items=new ItemData[36];
        String plantingBlock,nativeFence;Pos obstructedCell;ItemData cursor=ItemData.EMPTY;
        long ticks;int otherCalls,sleepCalls,checkpoints,sent;boolean nativeBusy,container,allowRestore;
        Action pending;ActionOutcome result=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        WorkResult otherResult=WorkResult.idle();
        Fixture(String plantingBlock) {
            this.plantingBlock=plantingBlock;
            Arrays.fill(items,ItemData.EMPTY);items[2]=item(LoggingRules.AXE,1);
            items[4]=new ItemData("minecraft:golden_hoe",1,0,null,true,1000);
            for(Feature feature:Feature.values())profile.enabled.put(feature,false);
            for(Feature feature:List.of(Feature.LOGGING,Feature.HARVEST,Feature.SLEEP))profile.enabled.put(feature,true);
            profile.hoeHotbarSlot=4;profile.loggingAxeHotbarSlot=2;profile.loggingRunActive=true;
            profile.loggingPlots.add(plot);profile.loggingRemainingPlots.add(plot.corner());profile.loggingReplantingPlots.add(plot.corner());
            profile.nextEligibleDay.put(LoggingRules.DUE_KEY,99L);
            context=new Context(this,this,this,profile,session,()->checkpoints++);
            AutomationModule other=new AutomationModule(){
                public Feature feature(){return Feature.HARVEST;}public int priority(){return 10;}
                public WorkResult tick(Context c){otherCalls++;return otherResult;}public void reset(){}
            };
            AutomationModule sleep=new AutomationModule(){
                public Feature feature(){return Feature.SLEEP;}public int priority(){return 100;}
                public WorkResult tick(Context c){sleepCalls++;return WorkResult.idle();}public void reset(){}
            };
            engine=new AutomationEngine(List.of(other,logging,sleep));
        }
        void start(boolean once){if(once)engine.startOnce(context,Feature.LOGGING);else engine.start(context);}
        void at(long tick){ticks=tick;engine.tick(context);}
        void untilWait(){until(()->engine.state()==AutomationEngine.State.WAITING);}
        void until(java.util.function.BooleanSupplier done){
            for(int i=0;i<600 && !done.getAsBoolean();i++) {
                at(ticks+1);assertTrue(engine.running(),engine.status());
            }
            assertTrue(done.getAsBoolean(),engine.status());
        }
        void assertPreserved(){
            assertObligations();assertEquals(0,checkpoints);
        }
        void assertObligations(){
            assertTrue(profile.loggingRunActive);assertEquals(List.of(plot.corner()),profile.loggingRemainingPlots);
            assertEquals(List.of(plot.corner()),profile.loggingReplantingPlots);
            assertEquals(99L,profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
        }
        void respondToInverse(boolean succeeded){
            Action.SwapHotbar inverse=assertInstanceOf(Action.SwapHotbar.class,pending);
            if(succeeded) {
                ItemData held=items[inverse.hotbarSlot()];items[inverse.hotbarSlot()]=items[inverse.inventoryIndex()];
                items[inverse.inventoryIndex()]=held;
            }
            pending=null;result=new ActionOutcome(succeeded?ActionOutcome.State.SUCCEEDED:ActionOutcome.State.FAILED,
                succeeded?"exact native inverse receipt":"native inverse rejected",succeeded?1:0);
        }
        public long tick(){return ticks;}public long dayTime(){return 241000;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,2,true,true);}
        public BlockData block(Pos pos){
            String id=pos.y()<64 ? "minecraft:dirt" : plot.plantingPositions().contains(pos)
                && (obstructedCell==null || obstructedCell.equals(pos)) ? plantingBlock : "minecraft:air";
            return new BlockData(pos,id,id.equals("minecraft:snow")?Map.of("layers","1"):Map.of());
        }
        public boolean loaded(Pos pos){return true;}public boolean canStand(Pos pos){return true;}
        public boolean canTraverse(Pos from,Pos to){return true;}
        public List<BlockData> scan(Pos center,int horizontal,int vertical){throw new AssertionError("A blocked planting must not scan unrelated terrain");}
        public List<ItemSlot> inventory(){
            List<ItemSlot> inventory=new ArrayList<>();
            for(int index=0;index<36;index++)inventory.add(new ItemSlot(index,index,true,items[index]));
            return inventory;
        }
        public String loggingItemFingerprint(int index){
            try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(items[index].toString().getBytes(StandardCharsets.UTF_8)));}
            catch(java.security.NoSuchAlgorithmException unavailable){throw new AssertionError(unavailable);}
        }
        public MenuData menu(){return new MenuData(container?1:0,0,inventory(),cursor,container);}
        public boolean mayPlace(int slot,ItemData item){return false;}public boolean busy(){return nativeBusy || pending!=null;}
        public String pauseReason(){return nativeFence;}
        public long submit(Action action){
            assertTrue(allowRestore,"A terrain wait cannot submit native actions: "+action);
            assertInstanceOf(Action.SwapHotbar.class,action);assertNull(pending);
            assertEquals(LoggingHotbarLease.Stage.RESTORING,profile.loggingHotbarLease.stage());
            pending=action;result=new ActionOutcome(ActionOutcome.State.PENDING,"");return ++sent;
        }
        public ActionOutcome outcome(long ticket){assertEquals(sent,ticket);assertTrue(sent>0);return result;}
        public void move(Movement movement){throw new AssertionError("A terrain wait cannot move");}
        public void stopMovement(){}public void cancel(){
            if(pending!=null){result=new ActionOutcome(ActionOutcome.State.CANCELLED,"Cancelled without acknowledgement");pending=null;}
        }
        public Result moveTo(Pos pos,double reach,Context context){throw new AssertionError("A terrain wait cannot navigate");}
        public void reset(){}
    }
}
