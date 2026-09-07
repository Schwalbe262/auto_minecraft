package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineApproachTest {
    @Test void lostReachDuringEquipReapproachesWithoutCreatingDebtOrSendingUse() {
        Fixture f=new Fixture(); f.loseReachOnEquip=true;
        for (int n=0;n<20;n++) { f.step(); f.advance(); }
        assertEquals(0,f.uses);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
        assertEquals(0,f.checkpoints);
        assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.repairOnNavigation=true;
        for (int n=0;n<15 && f.uses==0;n++) { f.step(); f.advance(); }
        assertEquals(1,f.uses);
        assertEquals(1,f.profile.pendingMachineOutputs.size());
        assertEquals(1,f.checkpoints,"Exactly one write-ahead checkpoint, only after settled reach was rechecked");
        for (int n=0;n<10;n++) { f.step(); f.advance(); }
        assertEquals(1,f.uses,"Pending machine acknowledgement never causes another use");
    }

    @Test void alreadyEquippedMachineWaitsTwoWorldTicksNotTwoPolls() {
        Fixture f=new Fixture(); f.equipped();
        WorkResult result=null;
        for (int n=0;n<15;n++) {
            result=f.step();
            if (result.message().contains("정지 안정화")) break;
            f.advance();
        }
        assertNotNull(result); assertTrue(result.message().contains("정지 안정화"));
        for (int n=0;n<10;n++) f.step();
        assertEquals(0,f.uses); assertTrue(f.profile.pendingMachineOutputs.isEmpty());
        f.advance(); f.step(); assertEquals(0,f.uses);
        f.advance(); f.step(); assertEquals(1,f.uses);
        assertTrue(f.stopCalls>0);
    }

    @Test void reachLostWhileSettlingIsCheckedAgainBeforeWriteAheadRecord() {
        Fixture f=new Fixture(); f.equipped();
        for (int n=0;n<15;n++) { if (f.step().message().contains("정지 안정화")) break; f.advance(); }
        f.reachable=false;
        f.advance(); f.step(); f.advance();
        WorkResult result=f.step();
        assertEquals(0,f.uses); assertEquals(0,f.checkpoints);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
        assertTrue(result.message().contains("상호작용 위치 재접근"));
    }

    @Test void highRackStillUsesTheFullNativeFourBlockReach() {
        Fixture f=new Fixture(Feature.WINE); f.equipped(); f.minimumReach=4;
        for (int n=0;n<20 && f.uses==0;n++) { f.step(); f.advance(); }
        assertEquals(1,f.uses);
        assertTrue(f.navigationReaches.stream().allMatch(r -> r==4.0));
        assertFalse(f.interactionReaches.isEmpty());
        assertTrue(f.interactionReaches.stream().allMatch(r -> r==4.0));
        assertTrue(f.profile.pendingMachineOutputs.isEmpty(),"Wine pickup tracking is disabled by user request");
    }

    @Test void unloadedTargetCannotCreateDebtOrSendAUse() {
        Fixture f=new Fixture(); f.equipped();
        for (int n=0;n<15;n++) { if (f.step().message().contains("정지 안정화")) break; f.advance(); }
        f.loaded=false;
        for (int n=0;n<5;n++) { f.advance(); f.step(); }
        assertEquals(0,f.uses); assertEquals(0,f.checkpoints);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
    }

    @Test void wineDispatchHasNoCapacityGroundProductOrNativeYearPickupGate() {
        Fixture f=new Fixture(Feature.WINE); f.equipped(); f.wineYear=null;
        for(int n=0;n<36;n++) if(n!=4 && n!=5)
            f.inventory[n]=new ItemData("minecraft:cobblestone",64,0,null,false,999);
        f.ground.add(new GroundItem(1,2,66,0,new ItemData(ItemData.WINE,64,0,7,false,999)));
        for(int n=0;n<20 && f.uses==0;n++) { f.step(); f.advance(); }
        assertEquals(1,f.uses); assertEquals(0,f.checkpoints);
        assertTrue(f.profile.pendingMachineOutputs.isEmpty());
        assertTrue(f.navigationReaches.stream().allMatch(r -> r==4.0));
    }

    @Test void disablingWinePickupTrackingDoesNotIgnoreFailedMachineAcknowledgement() {
        Fixture f=new Fixture(Feature.WINE); f.equipped();
        for(int n=0;n<20 && f.uses==0;n++) { f.step(); f.advance(); }
        assertEquals(1,f.uses);
        f.pending=null; f.outcome=new ActionOutcome(ActionOutcome.State.FAILED,"native use failed");
        WorkResult result=f.step();
        assertEquals(WorkResult.State.BLOCKED,result.state());
        assertTrue(result.message().contains("native use failed"));
        for(int n=0;n<5;n++) { assertEquals(WorkResult.State.BLOCKED,f.step().state()); f.advance(); }
        assertEquals(1,f.uses);
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Pos machine=new Pos(2,67,0);
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final Feature feature;
        final MachineModule module;
        final Context context=new Context(this,this,this,profile,session,() -> this.checkpoints++);
        final ItemData[] inventory=new ItemData[36];
        final List<Double> navigationReaches=new ArrayList<>(),interactionReaches=new ArrayList<>();
        final List<GroundItem> ground=new ArrayList<>();
        Integer wineYear=9;
        long ticks,ticket; int selected=4,uses,checkpoints,stopCalls;
        boolean reachable=true,loaded=true,loseReachOnEquip,repairOnNavigation;
        double minimumReach;
        Action pending; ActionOutcome outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture() { this(Feature.PRESERVES); }
        Fixture(Feature feature) {
            this.feature=feature; module=new MachineModule(feature);
            Arrays.fill(inventory,ItemData.EMPTY); profile.hoeHotbarSlot=4;
            inventory[4]=new ItemData("minecraft:golden_hoe",1,0,null,true,100);
            inventory[9]=new ItemData(ItemData.TOMATO,30,0,null,false,999);
            profile.pois.add(new Poi(machine,feature==Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR,"rack",null));
        }
        void equipped() { inventory[5]=inventory[9]; inventory[9]=ItemData.EMPTY; selected=5; }
        WorkResult step() { return module.tick(context); }
        void advance() {
            ticks++;
            if (pending instanceof Action.SwapHotbar swap) {
                ItemData previous=inventory[swap.hotbarSlot()]; inventory[swap.hotbarSlot()]=inventory[swap.inventoryIndex()]; inventory[swap.inventoryIndex()]=previous;
                pending=null; outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,""); if(loseReachOnEquip) reachable=false;
            } else if (pending instanceof Action.SelectHotbar select) {
                selected=select.slot(); pending=null; outcome=new ActionOutcome(ActionOutcome.State.SUCCEEDED,""); if(loseReachOnEquip) reachable=false;
            }
        }
        public long tick() { return ticks; }
        public long dayTime() { return 5000; }
        public Integer wineYear() { return wineYear; }
        public List<GroundItem> groundItems() { return List.copyOf(ground); }
        public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,selected,true,true); }
        public BlockData block(Pos pos) { return new BlockData(pos,feature==Feature.WINE ? "society:wine_keg" : "society:preserves_jar",Map.of("mature","true","working","true","facing","west")); }
        public boolean loaded(Pos pos) { return loaded; }
        public boolean canStand(Pos pos) { return true; }
        public boolean canTraverse(Pos from,Pos to) { return true; }
        public boolean canInteract(Pos pos,double reach) { interactionReaches.add(reach); return loaded && reachable && reach>=minimumReach; }
        public List<BlockData> scan(Pos pos,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { List<ItemSlot> result=new ArrayList<>(); for(int i=0;i<36;i++) result.add(new ItemSlot(i,i,true,inventory[i])); return result; }
        public MenuData menu() { return new MenuData(0,0,inventory(),ItemData.EMPTY,false); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
        public boolean busy() { return pending!=null; }
        public long submit(Action action) {
            assertNull(pending); pending=action; outcome=new ActionOutcome(ActionOutcome.State.PENDING,"");
            if (action instanceof Action.UseBlock use && use.purpose()==Action.Use.MACHINE) {
                uses++;
                assertTrue(canInteract(machine,4),"The native use must remain within current reach");
                assertEquals(feature==Feature.WINE ? 0 : 1,profile.pendingMachineOutputs.size(),"Preserves retain write-ahead output; wine pickup tracking is disabled");
            }
            return ++ticket;
        }
        public ActionOutcome outcome(long ticket) { return outcome; }
        public void move(Movement movement) { throw new AssertionError(); }
        public void stopMovement() { stopCalls++; }
        public void cancel() { pending=null; }
        public Result moveTo(Pos target,double reach,Context context) {
            navigationReaches.add(reach); if(repairOnNavigation) reachable=true;
            return loaded && reach>=minimumReach ? Result.ARRIVED : Result.BLOCKED;
        }
        public void reset() { }
    }
}
