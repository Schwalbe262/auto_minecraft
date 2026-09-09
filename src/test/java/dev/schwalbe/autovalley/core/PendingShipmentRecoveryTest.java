package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.PendingShipmentRecovery.Result.*;

/** Detached native-action receipts; shipping the current lot never proves the historical machine output. */
class PendingShipmentRecoveryTest {
    private static final String ID="10000000-0000-0000-0000-000000000001",OTHER_ID="10000000-0000-0000-0000-000000000002";
    private static final Pos SHIPPING=new Pos(2,64,0),OTHER_SHIPPING=new Pos(8,64,0),MACHINE=new Pos(3,64,0);
    private static ItemData preserves(int count,int grade){return new ItemData(ItemData.PRESERVES,count,grade,null,false,0);}
    private static PendingMachineOutput pending(String id,Feature feature,PendingMachineOutput.Phase phase) {
        return new PendingMachineOutput(id,feature,MACHINE,480,feature==Feature.WINE?14:null,124,phase);
    }

    @Test void frozenMixedQualityLotShipsOnlyPreservesThenClosesWithoutResolvingOrRewritingAnything() {
        Fixture f=new Fixture();var before=f.ledger();var enabled=Map.copyOf(f.profile.enabled);List<ItemData> original=List.copyOf(f.items);
        f.start();assertEquals(Feature.SHIPPING,f.session.oneShotFeature);assertFalse(f.profile.enabled(Feature.SHIPPING));
        assertTrue(f.session.allows(f.profile,Feature.SHIPPING));assertFalse(f.session.allows(f.profile,Feature.PRESERVES));
        f.run();assertEquals(COMPLETE,f.result);assertEquals(124,f.number("startQty"));assertEquals(124,f.number("deliveredQty"));
        assertEquals(124,f.physicallyShipped);assertEquals(List.of(64,60),f.receipts().stream().map(PendingShipmentRecovery.TransferReceipt::confirmedCount).toList());
        assertEquals(4,f.submitted.size());assertInstanceOf(Action.UseBlock.class,f.submitted.get(0));
        assertInstanceOf(Action.CloseContainer.class,f.submitted.get(3));assertTrue(f.number("closeTicket")>0);
        assertTrue(f.travel.stream().allMatch(SHIPPING::equals));assertFalse(f.open);assertTrue((Boolean)f.recovery.report().get("complete"));
        for(int index=0;index<36;index++)assertEquals(index==9||index==10?ItemData.EMPTY:original.get(index),f.items.get(index));
        assertEquals(before,f.ledger());assertEquals(enabled,f.profile.enabled);assertNull(f.session.oneShotFeature);assertNull(f.session.pendingShipmentRecovery);
        assertEquals(0,f.checkpoints);assertThrows(UnsupportedOperationException.class,()->f.recovery.report().put("complete",false));
        assertThrows(UnsupportedOperationException.class,()->f.receipts().clear());
    }

    @Test void pendingTransfersAndTheFinalCloseCannotBeReplacedByInventoryCountsOrDuplicatePolling() {
        Fixture f=new Fixture();f.start();f.until(Action.QuickMove.class);int actions=f.submitted.size();long sameTick=f.now;
        for(int repeat=0;repeat<12;repeat++)assertEquals(MOVING,f.recovery.tick(f.context()));
        assertEquals(sameTick,f.now);assertEquals(actions,f.submitted.size());assertEquals(0,f.number("deliveredQty"));
        f.ack();f.step();assertEquals(64,f.number("deliveredQty"));
        for(int repeat=0;repeat<12;repeat++)assertEquals(MOVING,f.recovery.tick(f.context()));
        assertEquals(64,f.number("deliveredQty"));assertEquals(1,f.receipts().size());
        f.ack();f.until(Action.CloseContainer.class);assertEquals(124,f.number("deliveredQty"));assertFalse((Boolean)f.recovery.report().get("complete"));
        actions=f.submitted.size();for(int repeat=0;repeat<12;repeat++)f.step();
        assertEquals(MOVING,f.result);assertEquals(actions,f.submitted.size());assertEquals(-1,f.number("closeTicket"));
        f.ack();assertEquals(COMPLETE,f.step());assertEquals(2,f.receipts().size());f.assertInitialLedger();
    }

    @Test void partialPositiveNativeTransfersAccountOnlyTheirActualUnitsAndRetainTheFrozenTotal() {
        Fixture f=new Fixture();f.start();f.until(Action.QuickMove.class);
        f.complete(new ActionOutcome(ActionOutcome.State.SUCCEEDED,"exact partial native receipt",17),17);
        f.step();assertEquals(17,f.number("deliveredQty"));assertEquals(47,f.items.get(9).count());
        f.run();assertEquals(COMPLETE,f.result);assertEquals(124,f.physicallyShipped);
        assertEquals(List.of(17,47,60),f.receipts().stream().map(PendingShipmentRecovery.TransferReceipt::confirmedCount).toList());
        f.assertInitialLedger();
    }

    @Test void shippingAnAwaitingMachineConfirmationLotPreservesExistingActiveIdentityAndLiveTokens() {
        Fixture f=new Fixture();
        f.profile.pendingMachineOutputs.put(ID,pending(ID,Feature.PRESERVES,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
        f.session.activeMachineOutputId=ID;f.session.liveMachineOutputs.add(ID);var before=f.ledger();
        f.start();f.run();assertEquals(COMPLETE,f.result);assertEquals(124,f.number("deliveredQty"));
        assertEquals(before,f.ledger());assertEquals(ID,f.session.activeMachineOutputId);assertEquals(Set.of(ID),f.session.liveMachineOutputs);
        assertEquals(PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION,f.profile.pendingMachineOutputs.get(ID).phase());
        assertNull(f.session.pendingShipmentRecovery);assertNull(f.session.oneShotFeature);assertEquals(0,f.checkpoints);
    }

    @Test void missingNonpositiveOverlargeFailedOrCancelledTransferReceiptsNeverPermitAnotherDispatch() {
        List<ActionOutcome> invalid=List.of(new ActionOutcome(ActionOutcome.State.SUCCEEDED,"no native count"),
            new ActionOutcome(ActionOutcome.State.SUCCEEDED,"negative native count",-1),
            new ActionOutcome(ActionOutcome.State.SUCCEEDED,"more than the selected stack",65),
            new ActionOutcome(ActionOutcome.State.FAILED,"refused"),new ActionOutcome(ActionOutcome.State.CANCELLED,"cancelled"));
        for(ActionOutcome outcome:invalid) {
            Fixture f=new Fixture();f.start();f.until(Action.QuickMove.class);int actions=f.submitted.size();
            f.complete(outcome,outcome.success()?64:0);assertEquals(BLOCKED,f.step());
            assertEquals(0,f.number("deliveredQty"));assertFalse((Boolean)f.recovery.report().get("complete"));
            for(int repeat=0;repeat<5;repeat++)assertEquals(BLOCKED,f.step());
            assertEquals(actions,f.submitted.size());f.assertInitialLedger();
        }
    }

    @Test void everyFrozenInventoryFieldAndAnyUnplannedPickupOrSlotMovementStopsBeforeUse() {
        for(int changed=0;changed<9;changed++) {
            Fixture f=new Fixture();f.start();
            switch(changed) {
                case 0 -> f.items.set(9,preserves(63,0));
                case 1 -> f.items.set(10,preserves(61,2));
                case 2 -> f.items.set(9,preserves(64,1));
                case 3 -> f.items.set(9,new ItemData(ItemData.PRESERVES,64,0,14,false,0));
                case 4 -> f.items.set(9,new ItemData(ItemData.PRESERVES,64,0,null,false,1));
                case 5 -> f.items.set(9,new ItemData(ItemData.PRESERVES,64,0,null,true,0));
                case 6 -> f.items.set(20,preserves(1,0));
                case 7 -> Collections.swap(f.items,9,20);
                case 8 -> f.items.set(6,new ItemData("minecraft:apple",1,0,null,false,0));
            }
            assertEquals(BLOCKED,f.step(),"changed="+changed);assertTrue(f.submitted.isEmpty());f.assertInitialLedger();
        }
        Fixture absentDelta=new Fixture();absentDelta.start();absentDelta.until(Action.QuickMove.class);int actions=absentDelta.submitted.size();
        absentDelta.complete(new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native count but contradictory live state",64),0);
        assertEquals(BLOCKED,absentDelta.step());assertEquals(actions,absentDelta.submitted.size());assertFalse((Boolean)absentDelta.recovery.report().get("complete"));
        absentDelta.assertInitialLedger();
    }

    @Test void exactlyOneSelectedPreservesRecordAndACleanSafeStartAreMandatory() {
        for(int invalid=0;invalid<15;invalid++) {
            Fixture f=new Fixture();
            switch(invalid) {
                case 0 -> f.profile.pendingMachineOutputs.clear();
                case 1 -> f.profile.pendingMachineOutputs.put(OTHER_ID,pending(OTHER_ID,Feature.PRESERVES,PendingMachineOutput.Phase.AWAITING_PICKUP));
                case 2 -> f.profile.pendingMachineOutputs.put(ID,pending(ID,Feature.WINE,PendingMachineOutput.Phase.AWAITING_PICKUP));
                case 3 -> f.profile.pois.removeIf(p->p.kind()==PoiKind.SHIPPING_BIN);
                case 4 -> f.items.set(9,preserves(64,-1));
                case 5 -> {f.items.set(9,ItemData.EMPTY);f.items.set(10,ItemData.EMPTY);}
                case 6 -> f.food=4;
                case 7 -> f.health=4;
                case 8 -> f.connected=false;
                case 9 -> f.ground=false;
                case 10 -> f.open=true;
                case 11 -> f.cursor=preserves(1,0);
                case 12 -> f.externalBusy=true;
                case 13 -> f.fence="unresolved native inventory action";
                case 14 -> f.profile.loggingHotbarLease=new LoggingHotbarLease(20,6,f.items.get(6),"exact borrowed fingerprint");
            }
            var before=f.ledger();assertThrows(IllegalArgumentException.class,()->PendingShipmentRecovery.start(f.context(),ID),"invalid="+invalid);
            assertEquals(before,f.ledger());assertNull(f.session.pendingShipmentRecovery);assertNull(f.session.oneShotFeature);assertTrue(f.submitted.isEmpty());
        }
        Fixture wrongId=new Fixture();assertThrows(IllegalArgumentException.class,()->PendingShipmentRecovery.start(wrongId.context(),OTHER_ID));
        Fixture selected=new Fixture();selected.session.oneShotFeature=Feature.PRESERVES;
        assertThrows(IllegalArgumentException.class,()->PendingShipmentRecovery.start(selected.context(),ID));assertEquals(Feature.PRESERVES,selected.session.oneShotFeature);
        Fixture active=new Fixture();active.start();assertThrows(IllegalArgumentException.class,()->PendingShipmentRecovery.start(active.context(),ID));
        assertSame(active.recovery,active.session.pendingShipmentRecovery);
    }

    @Test void profileSessionPendingRegistrationAndRuntimeSafetyChangesRevokeOnlyTheOwnedExecution() {
        for(int changed=0;changed<14;changed++) {
            Fixture f=new Fixture();f.start();
            switch(changed) {
                case 0 -> {Profile replacement=new Profile();replacement.pois.addAll(f.profile.pois);replacement.pendingMachineOutputs.putAll(f.profile.pendingMachineOutputs);f.profile=replacement;}
                case 1 -> f.session=new SessionState();
                case 2 -> f.profile.pendingMachineOutputs.put(ID,pending(ID,Feature.PRESERVES,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
                case 3 -> f.profile.pendingMachineOutputs.put(OTHER_ID,pending(OTHER_ID,Feature.PRESERVES,PendingMachineOutput.Phase.AWAITING_PICKUP));
                case 4 -> {f.profile.pois.removeIf(p->p.kind()==PoiKind.SHIPPING_BIN);f.profile.pois.add(new Poi(OTHER_SHIPPING,PoiKind.SHIPPING_BIN,"moved",null));}
                case 5 -> f.session.liveMachineOutputs.add(ID);
                case 6 -> f.session.activeMachineOutputId=ID;
                case 7 -> f.session.oneShotFeature=Feature.WINE;
                case 8 -> f.fence="native action fence";
                case 9 -> f.food=4;
                case 10 -> f.connected=false;
                case 11 -> f.cursor=preserves(1,0);
                case 12 -> f.open=true;
                case 13 -> f.profile.loggingHotbarLease=new LoggingHotbarLease(20,6,f.items.get(6),"borrowed");
            }
            var before=f.ledger();assertEquals(BLOCKED,f.step(),"changed="+changed);assertTrue(f.submitted.isEmpty());assertEquals(before,f.ledger());
            if(changed==7)assertEquals(Feature.WINE,f.session.oneShotFeature,"Cancelling recovery cannot clear a replacement job's permission");
        }
    }

    @Test void cancellationAfterOneConfirmedStackReportsOnlyThatShipmentAndKeepsThePendingRecord() {
        Fixture f=new Fixture();f.start();f.until(Action.QuickMove.class);f.ack();f.step();assertEquals(64,f.number("deliveredQty"));
        int actions=f.submitted.size();f.recovery.cancel(f.context(),"explicit stop");
        assertEquals(BLOCKED,f.step());assertEquals(actions,f.submitted.size());assertEquals(64,f.number("deliveredQty"));
        assertFalse((Boolean)f.recovery.report().get("complete"));assertEquals(1,f.receipts().size());f.assertInitialLedger();
        assertNull(f.session.pendingShipmentRecovery);assertNull(f.session.oneShotFeature);
        f.open=false;f.menuId=0;f.start();assertEquals(60,f.number("startQty"));assertEquals(0,f.number("deliveredQty"));
        f.run();assertEquals(COMPLETE,f.result);assertEquals(60,f.number("deliveredQty"));f.assertInitialLedger();
    }

    @Test void missingFailedOrContradictoryCloseNeverClaimsTheCurrentLotComplete() {
        for(int invalid=0;invalid<3;invalid++) {
            Fixture f=new Fixture();f.start();f.until(Action.CloseContainer.class);int actions=f.submitted.size();
            assertEquals(124,f.number("deliveredQty"));
            if(invalid==0)f.now+=201;
            else if(invalid==1)f.complete(new ActionOutcome(ActionOutcome.State.FAILED,"close refused"),0);
            else f.outcomes.put(f.current,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"close outcome but foreign menu still open"));
            assertEquals(BLOCKED,f.step());assertEquals(actions,f.submitted.size());assertFalse((Boolean)f.recovery.report().get("complete"));
            assertEquals(-1,f.number("closeTicket"));f.assertInitialLedger();
        }
    }

    @Test void pendingAndTotalTimeoutsOrNavigationFailureNeverResendOrEnterAnotherJob() {
        Fixture pending=new Fixture();pending.start();pending.until(Action.QuickMove.class);int actions=pending.submitted.size();pending.now+=201;
        assertEquals(BLOCKED,pending.step());assertEquals(actions,pending.submitted.size());pending.assertInitialLedger();
        Fixture elapsed=new Fixture();elapsed.navigationResult=Navigation.Result.MOVING;elapsed.start();elapsed.step();elapsed.now+=2401;
        assertEquals(BLOCKED,elapsed.step());assertTrue(elapsed.submitted.isEmpty());elapsed.assertInitialLedger();
        for(int invalid=0;invalid<4;invalid++) {
            Fixture f=new Fixture();f.start();
            if(invalid==0)f.navigationResult=Navigation.Result.BLOCKED;
            if(invalid==1)f.loaded=false;
            if(invalid==2)f.shippingBlock="minecraft:barrel";
            if(invalid==3)f.now=-1;
            assertEquals(BLOCKED,f.step());assertTrue(f.submitted.isEmpty());f.assertInitialLedger();
        }
    }

    @Test void scopedPermissionRejectsOtherProductsWithdrawalMachinesAndInventoryRearrangement() {
        Fixture f=new Fixture();f.start();f.until(Action.QuickMove.class);
        List<Action> forbidden=List.of(new Action.QuickMove(77,54+11),new Action.QuickMove(77,54+12),new Action.QuickMove(77,0),
            new Action.SelectHotbar(0),new Action.SwapHotbar(9,0),new Action.ConsolidateInventory(null),
            new Action.UseBlock(MACHINE,Action.Use.MACHINE),new Action.UseBlock(OTHER_SHIPPING,Action.Use.OPEN_CONTAINER),
            new Action.TrashRotten(9,preserves(64,0)),new Action.CloseContainer(78));
        for(Action action:forbidden)assertNotNull(SafetyPolicy.rejection(action,f.context()),action.toString());
        assertFalse(f.session.allows(f.profile,Feature.PRESERVES));assertFalse(f.session.allows(f.profile,Feature.WINE));
        assertFalse(f.session.allows(f.profile,Feature.HARVEST));f.assertInitialLedger();
    }

    private record Ledger(Map<String,PendingMachineOutput> pending,List<MachineOutputLedger.ResolutionEntry> history,
                          Map<String,Long> dates,WineBatchSchedule wine,Set<String> live,String active) { }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        Profile profile=new Profile();SessionState session=new SessionState();PendingShipmentRecovery recovery;
        final List<ItemData> items=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        final List<Action> submitted=new ArrayList<>();final List<Pos> travel=new ArrayList<>();final Map<Long,ActionOutcome> outcomes=new HashMap<>();
        final Ledger originalLedger;long now,current;int menuId,food=20,physicallyShipped,checkpoints;float health=20;
        boolean ground=true,connected=true,open,externalBusy,loaded=true;String fence,shippingBlock=SmartShippingRules.BLOCK_ID;
        ItemData cursor=ItemData.EMPTY;Navigation.Result navigationResult=Navigation.Result.ARRIVED;
        PendingShipmentRecovery.Result result=MOVING;
        Fixture() {
            profile.enabled.put(Feature.SHIPPING,false);profile.hoeHotbarSlot=4;
            profile.pois.add(new Poi(SHIPPING,PoiKind.SHIPPING_BIN,"selected shipping",null));
            profile.pois.add(new Poi(OTHER_SHIPPING,PoiKind.SHIPPING_BIN,"far shipping",null));
            profile.pois.add(new Poi(MACHINE,PoiKind.PRESERVES_JAR,"never service",null));
            profile.pendingMachineOutputs.put(ID,pending(ID,Feature.PRESERVES,PendingMachineOutput.Phase.AWAITING_PICKUP));
            profile.nextEligibleDay.put("preserves:3:64:0",483L);
            items.set(4,new ItemData("minecraft:iron_hoe",1,0,null,true,100));
            items.set(6,new ItemData("farmersdelight:fruit_salad",57,0,null,false,0));
            items.set(9,preserves(64,0));items.set(10,preserves(60,2));
            items.set(11,new ItemData(ItemData.PINE_TAR,5,0,null,false,0));items.set(12,new ItemData(ItemData.WINE,10,0,14,false,0));
            originalLedger=ledger();
        }
        Context context(){return new Context(this,this,this,profile,session,()->{checkpoints++;throw new AssertionError("Recovery must not write or resolve the production ledger");});}
        Ledger ledger(){return new Ledger(Map.copyOf(profile.pendingMachineOutputs),List.copyOf(profile.machineOutputResolutions),Map.copyOf(profile.nextEligibleDay),profile.wineBatchSchedule,Set.copyOf(session.liveMachineOutputs),session.activeMachineOutputId);}
        void assertInitialLedger(){assertEquals(originalLedger,ledger());assertEquals(0,checkpoints);}
        void start(){recovery=PendingShipmentRecovery.start(context(),ID);assertSame(recovery,session.pendingShipmentRecovery);}
        int number(String key){return ((Number)recovery.report().get(key)).intValue();}
        @SuppressWarnings("unchecked") List<PendingShipmentRecovery.TransferReceipt> receipts(){return (List<PendingShipmentRecovery.TransferReceipt>)recovery.report().get("transfers");}
        PendingShipmentRecovery.Result step(){result=recovery.tick(context());now++;return result;}
        void until(Class<? extends Action> type) {
            for(int tick=0;tick<80;tick++) {
                if(busy() && type.isInstance(submitted.get(submitted.size()-1)))return;
                assertEquals(MOVING,step(),recovery.status());
                if(busy()){if(type.isInstance(submitted.get(submitted.size()-1)))return;ack();}
            }
            fail("Expected pending "+type.getSimpleName());
        }
        void run(){for(int tick=0;tick<120;tick++){step();if(result!=MOVING)return;if(busy())ack();}fail("Shipment did not terminate");}
        void ack(){Action action=submitted.get(submitted.size()-1);int count=action instanceof Action.QuickMove move?menu().slot(move.slot()).item().count():0;complete(new ActionOutcome(ActionOutcome.State.SUCCEEDED,"native confirmed",count),count);}
        void complete(ActionOutcome outcome,int physicallyMoved) {
            Action action=submitted.get(submitted.size()-1);
            if(outcome.success()) {
                if(action instanceof Action.UseBlock){open=true;menuId=77;}
                else if(action instanceof Action.QuickMove move) {
                    ItemSlot source=menu().slot(move.slot());ItemData item=items.get(source.inventoryIndex());
                    assertTrue(physicallyMoved>=0 && physicallyMoved<=item.count());physicallyShipped+=physicallyMoved;
                    items.set(source.inventoryIndex(),physicallyMoved==item.count()?ItemData.EMPTY:new ItemData(item.id(),item.count()-physicallyMoved,item.quality(),item.year(),item.hoe(),item.durability()));
                } else if(action instanceof Action.CloseContainer){open=false;menuId=0;}
            }
            outcomes.put(current,outcome);
        }
        public long tick(){return now;}public long dayTime(){return 480L*24000+5000;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,ground,false,health,food,4,connected,true);}
        public BlockData block(Pos pos){return new BlockData(pos,pos.equals(SHIPPING)?shippingBlock:pos.equals(MACHINE)?"society:preserves_jar":"minecraft:air",Map.of("container","true","mature","true","working","false"));}
        public boolean loaded(Pos pos){return loaded;}public boolean canStand(Pos pos){return true;}public boolean canTraverse(Pos from,Pos to){return true;}
        public List<BlockData> scan(Pos center,int horizontal,int vertical){throw new AssertionError("Explicit shipment cannot scan crops or machines");}
        public List<ItemSlot> inventory(){List<ItemSlot> result=new ArrayList<>();for(int index=0;index<36;index++)result.add(new ItemSlot(index,index,true,items.get(index)));return result;}
        public MenuData menu(){
            if(!open)return new MenuData(menuId,0,inventory(),cursor,false);
            List<ItemSlot> result=new ArrayList<>();for(int index=0;index<54;index++)result.add(new ItemSlot(index,-1,false,ItemData.EMPTY));
            for(int index=0;index<36;index++)result.add(new ItemSlot(index+54,index,true,items.get(index)));
            return new MenuData(menuId==0?77:menuId,1,result,cursor,true);
        }
        public boolean mayPlace(int slot,ItemData item){return slot>=0&&slot<54&&item.is(ItemData.PRESERVES);}
        public boolean busy(){return externalBusy||current>0&&!outcomes.get(current).done();}public String pauseReason(){return fence;}
        public long submit(Action action) {
            assertFalse(busy());assertNull(SafetyPolicy.rejection(action,context()),action.toString());
            if(action instanceof Action.UseBlock use){assertEquals(SHIPPING,use.pos());assertEquals(Action.Use.OPEN_CONTAINER,use.purpose());}
            else if(action instanceof Action.QuickMove move){ItemSlot slot=menu().slot(move.slot());assertTrue(slot.player());assertTrue(slot.item().is(ItemData.PRESERVES));assertEquals(77,move.containerId());}
            else if(action instanceof Action.CloseContainer close)assertEquals(77,close.containerId());
            else throw new AssertionError("Recovery dispatched an unrelated action: "+action);
            submitted.add(action);outcomes.put(++current,new ActionOutcome(ActionOutcome.State.PENDING,"waiting"));return current;
        }
        public ActionOutcome outcome(long ticket){return outcomes.get(ticket);}public void move(Movement move){}public void stopMovement(){}
        public void cancel(){if(current>0&&!outcomes.get(current).done())outcomes.put(current,new ActionOutcome(ActionOutcome.State.CANCELLED,"normal cancellation"));}
        public Result moveTo(Pos pos,double reach,Context c){assertEquals(SHIPPING,pos);travel.add(pos);return navigationResult;}public void reset(){}
    }
}
