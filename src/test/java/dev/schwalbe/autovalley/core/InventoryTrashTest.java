package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.modules.DisposalModule;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InventoryTrashTest {
    private static ItemData rotten(int count) { return new ItemData(ItemData.ROTTEN,count,2,null,false,Integer.MAX_VALUE); }
    private static final ItemData TOOL=new ItemData("minecraft:golden_hoe",1,0,null,true,100);

    @Test void deletionNeedsNoWorldPointAndWaitsForExactQuantityAcknowledgement() {
        Fixture f=new Fixture(); f.put(16,rotten(10));
        DisposalModule module=new DisposalModule();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context).state());
        assertEquals(List.of(new Action.TrashRotten(16,rotten(10))),f.sent);
        assertNull(SafetyPolicy.rejection(f.sent.get(0),f.context));
        for (int i=0;i<10;i++) assertEquals(WorkResult.State.BUSY,module.tick(f.context).state());
        assertEquals(1,f.sent.size()); assertEquals(0,f.navigationCalls);
        f.put(16,ItemData.EMPTY);
        assertEquals(WorkResult.State.BUSY,module.tick(f.context).state(),"Local inventory disappearance is not an ACK");
        f.reply=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"server",10);
        assertEquals(WorkResult.State.IDLE,module.tick(f.context).state());
    }

    @Test void aSuccessWithoutConfirmedQuantityDoesNotConfirmDeletion() {
        Fixture f=new Fixture(); f.put(1,rotten(5));
        DisposalModule module=new DisposalModule(); module.tick(f.context);
        f.put(1,ItemData.EMPTY); f.reply=new ActionOutcome(ActionOutcome.State.SUCCEEDED,"unproven",0);
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context).state());
    }

    @Test void timeoutOrCancellationCannotBeReportedAsDeletion() {
        for (ActionOutcome.State state:List.of(ActionOutcome.State.FAILED,ActionOutcome.State.CANCELLED)) {
            Fixture f=new Fixture(); f.put(1,rotten(5));
            DisposalModule module=new DisposalModule(); module.tick(f.context);
            f.reply=new ActionOutcome(state,"not confirmed");
            assertEquals(WorkResult.State.BLOCKED,module.tick(f.context).state());
        }
    }

    @Test void unavailableTrashReportsItsRequirementRatherThanDemandingAWorldPointUnconditionally() {
        Fixture f=new Fixture(); f.trashAvailable=false; f.put(2,rotten(1));
        WorkResult result=new DisposalModule().tick(f.context);
        assertEquals(WorkResult.State.BLOCKED,result.state());
        assertTrue(result.message().contains("TrashSlot")); assertTrue(f.sent.isEmpty());
    }

    @Test void disabledFeatureAndOtherOneShotCannotDeleteButExplicitDisposalCan() {
        Fixture f=new Fixture(); f.put(2,rotten(1)); Action action=new Action.TrashRotten(2,rotten(1));
        f.profile.enabled.put(Feature.DISPOSAL,false); assertNotNull(SafetyPolicy.rejection(action,f.context));
        f.session.oneShotFeature=Feature.WINE; assertNotNull(SafetyPolicy.rejection(action,f.context));
        f.session.oneShotFeature=Feature.STORAGE_SURVEY; assertNotNull(SafetyPolicy.rejection(action,f.context));
        f.session.oneShotFeature=Feature.DISPOSAL; assertNull(SafetyPolicy.rejection(action,f.context));
        assertFalse(f.profile.enabled(Feature.DISPOSAL));
    }

    @Test void wrongItemCursorOffhandStaleSnapshotAndDuplicateMappingAreRejected() {
        Fixture f=new Fixture(); f.put(2,rotten(1));
        for (Action action:List.of(new Action.TrashRotten(2,TOOL),new Action.TrashRotten(2,rotten(2)),
            new Action.TrashRotten(-1,rotten(1)),new Action.TrashRotten(36,rotten(1)),new Action.TrashRotten(40,rotten(1))))
            assertNotNull(SafetyPolicy.rejection(action,f.context));
        assertNotNull(SafetyPolicy.rejection(new Action.TrashRotten(2,null),f.context));
        Action valid=new Action.TrashRotten(2,rotten(1));
        f.cursor=rotten(1); assertNotNull(SafetyPolicy.rejection(valid,f.context)); f.cursor=ItemData.EMPTY;
        f.container=true; assertNotNull(SafetyPolicy.rejection(valid,f.context)); f.container=false;
        f.items.add(new ItemSlot(100,2,true,rotten(1))); assertNotNull(SafetyPolicy.rejection(valid,f.context));
    }

    @Test void pendingDurableOutputDebtBlocksDeletionEvenForTheSelectedJob() {
        Fixture f=new Fixture(); f.put(2,rotten(1));
        String id=UUID.randomUUID().toString();
        f.profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.PRESERVES,new Pos(0,0,0),0,null,1,
            PendingMachineOutput.Phase.AWAITING_PICKUP));
        f.session.oneShotFeature=Feature.DISPOSAL;
        assertNotNull(SafetyPolicy.rejection(new Action.TrashRotten(2,rotten(1)),f.context));
        assertEquals(WorkResult.State.BLOCKED,new DisposalModule().tick(f.context).state());
        assertTrue(f.sent.isEmpty());
    }

    @Test void exactNativeOneStackRemovalConservesEveryOtherSlotIncludingMetadata() {
        var source=new InventoryConsolidation.Stack("rotten:{quality:2}",10,64);
        var tool=new InventoryConsolidation.Stack("hoe:{Damage:123}",1,1);
        List<InventoryConsolidation.Stack> before=List.of(tool,source,InventoryConsolidation.Stack.EMPTY);
        List<InventoryConsolidation.Stack> after=List.of(tool,InventoryConsolidation.Stack.EMPTY,InventoryConsolidation.Stack.EMPTY);
        assertEquals(10,InventoryTrashAcknowledgement.confirmed(before,after,1,true,true));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after,1,false,true));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after,1,true,false));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,before,1,true,true));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,List.of(tool,InventoryConsolidation.Stack.EMPTY,source),1,true,true),"A move is not a deletion");
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,List.of(new InventoryConsolidation.Stack("hoe:{Damage:124}",1,1),
            InventoryConsolidation.Stack.EMPTY,InventoryConsolidation.Stack.EMPTY),1,true,true));
        assertEquals(0,InventoryTrashAcknowledgement.confirmed(before,after.subList(0,2),1,true,true));
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final Context context=new Context(this,this,this,profile,session);
        final List<ItemSlot> items=new ArrayList<>(); final List<Action> sent=new ArrayList<>();
        ActionOutcome reply=new ActionOutcome(ActionOutcome.State.PENDING,"");
        ItemData cursor=ItemData.EMPTY; boolean container,trashAvailable=true; int navigationCalls;
        Fixture() { for(int i=0;i<36;i++) put(i,ItemData.EMPTY); put(4,TOOL); }
        void put(int index,ItemData item) { ItemSlot slot=new ItemSlot(index,index,true,item); if(index<items.size()) items.set(index,slot); else items.add(slot); }
        public boolean supportsInventoryTrash() { return trashAvailable; }
        public long tick() { return 10; } public long dayTime() { return 5000; }
        public PlayerState player() { return new PlayerState(.5,0,.5,0,0,true,false,20,20,4,true,true); }
        public BlockData block(Pos pos) { return new BlockData(pos,"minecraft:air",Map.of()); }
        public boolean loaded(Pos pos) { return true; } public boolean canStand(Pos pos) { return true; }
        public boolean canTraverse(Pos from,Pos to) { return false; }
        public List<BlockData> scan(Pos center,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.copyOf(items); }
        public MenuData menu() { return new MenuData(0,0,List.copyOf(items),cursor,container); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
        public boolean busy() { return !sent.isEmpty() && !reply.done(); }
        public long submit(Action action) { sent.add(action); return sent.size(); }
        public ActionOutcome outcome(long ticket) { return reply; }
        public void move(Movement movement) { throw new AssertionError(); }
        public void stopMovement() { } public void cancel() { }
        public Result moveTo(Pos target,double reach,Context context) { navigationCalls++; throw new AssertionError("No world navigation for inventory trash"); }
        public void reset() { }
    }
}
