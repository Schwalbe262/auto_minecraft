package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkHotbarLeasePolicyTest {
    private static final Pos INPUT=new Pos(1,64,0),OUTPUT=new Pos(2,64,0),MACHINE=new Pos(3,64,0),OTHER_MACHINE=new Pos(4,64,0);
    private static final ItemData ORIGINAL=item("minecraft:torch",17),JADE=item("society:jade",3);
    private static final String HASH="a".repeat(64);
    private static ItemData item(String id,int count){return new ItemData(id,count,0,null,false,999);}
    @Test void preparedParkingRequiresTheExactPairOriginalFingerprintAndEmptyMainSlot() {
        Fixture f=new Fixture();f.stage(HotbarLease.Stage.PREPARED);f.items.set(9,ItemData.EMPTY);f.items.set(1,ORIGINAL);
        assertNull(SafetyPolicy.rejection(new Action.SwapHotbar(9,1),f.context));
        for(Action wrong:List.of(new Action.SwapHotbar(9,2),new Action.SwapHotbar(10,1),new Action.SelectHotbar(1)))
            assertNotNull(SafetyPolicy.rejection(wrong,f.context));
        f.items.set(9,JADE);assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(9,1),f.context));
        f.items.set(9,ItemData.EMPTY);f.fingerprintChanged=true;
        assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(9,1),f.context));
    }
    @Test void parkedOwnerMayEquipOnlyItsWorkingItemsAndCannotMoveItsOriginalSource() {
        Fixture f=new Fixture();f.items.set(10,JADE);
        assertNull(SafetyPolicy.rejection(new Action.SwapHotbar(10,1),f.context));
        f.items.set(2,JADE);assertNull(SafetyPolicy.rejection(new Action.SwapHotbar(2,1),f.context));
        for(Action wrong:List.of(new Action.SwapHotbar(9,1),new Action.SwapHotbar(10,2),new Action.SwapHotbar(0,1)))
            assertNotNull(SafetyPolicy.rejection(wrong,f.context));
        f.items.set(10,item("minecraft:diamond",1));assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(10,1),f.context));
    }
    @Test void restorationWorksWhenOwnerIsOffOrJobRemovedButOnlyFromExactParkedCustody() {
        Fixture f=new Fixture();f.stage(HotbarLease.Stage.RESTORING);f.items.set(1,JADE);
        f.profile.enabled.put(Feature.CRYSTAL_COPY,false);f.session.oneShotFeature=null;f.profile.artisanJobs.clear();
        assertNull(SafetyPolicy.rejection(new Action.SwapHotbar(9,1),f.context));
        assertNull(SafetyPolicy.rejection(new Action.RefreshInventory(),f.context));
        assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(10,1),f.context));
        assertNotNull(SafetyPolicy.rejection(new Action.SelectHotbar(1),f.context));
        f.items.set(9,item(ORIGINAL.id(),16));assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(9,1),f.context));
    }
    @Test void everyStageRequiresTheExactSessionOwnerAndCannotBorrowAnotherOneShotPermission() {
        for(HotbarLease.Stage stage:HotbarLease.Stage.values()) {
            Fixture f=new Fixture();f.stage(stage);f.session.workHotbarOwner=Feature.SEED_MAKER;
            assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(9,1),f.context));
            assertNotNull(SafetyPolicy.rejection(new Action.RefreshInventory(),f.context));
        }
        Fixture f=new Fixture();f.items.set(10,JADE);f.session.oneShotFeature=Feature.SEED_MAKER;
        assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(10,1),f.context));
    }
    @Test void parkedOriginalCannotBeQuickMovedTrashedThrownOrConsolidatedByItsOwner() {
        Fixture f=new Fixture();f.open=true;f.items.set(10,JADE);
        assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(1,27+9),f.context));
        assertNull(SafetyPolicy.rejection(new Action.QuickMove(1,27+10),f.context));
        f.open=false;
        for(Action forbidden:List.of(new Action.TrashRotten(9,ORIGINAL),new Action.TrashLogging(9,ORIGINAL),
                new Action.ThrowRotten(0,9,INPUT),new Action.ConsolidateInventory(null,false),new Action.CraftFireLogs(INPUT)))
            assertNotNull(SafetyPolicy.rejection(forbidden,f.context));
    }
    @Test void aContainerWithdrawalCannotMergeIntoTheParkedOriginalEvenWhenItIsAnOwnerIngredient() {
        Fixture f=new Fixture();f.original(JADE);f.open=true;f.stored=JADE;
        assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(1,0),f.context));
        f.original(ORIGINAL);assertNull(SafetyPolicy.rejection(new Action.QuickMove(1,0),f.context));
    }
    @Test void onlyOwnConfiguredContainersAndMachinePurposeAreAdmittedWhileOtherFeaturesAreEnabled() {
        Fixture f=new Fixture();f.items.set(1,JADE);f.profile.enabled.put(Feature.SEED_MAKER,true);f.session.oneShotFeature=null;
        f.profile.artisanJobs.put("seed",new ArtisanJob("seed",ArtisanRecipe.ANCIENT_SEED.id(),List.of(OTHER_MACHINE),"seed_in","seed_out"));
        assertTrue(WorkHotbarLeasePolicy.containerAllowed(f.context,INPUT));assertTrue(WorkHotbarLeasePolicy.containerAllowed(f.context,OUTPUT));
        assertFalse(WorkHotbarLeasePolicy.containerAllowed(f.context,new Pos(99,64,99)));
        assertNull(WorkHotbarLeasePolicy.rejection(new Action.UseBlock(MACHINE,Action.Use.ARTISAN),f.context));
        assertNotNull(WorkHotbarLeasePolicy.rejection(new Action.UseBlock(OTHER_MACHINE,Action.Use.ARTISAN),f.context));
        for(Action.Use purpose:List.of(Action.Use.FRUIT,Action.Use.HARVEST,Action.Use.MACHINE,Action.Use.SLEEP,Action.Use.OPEN_CRAFTING))
            assertNotNull(WorkHotbarLeasePolicy.rejection(new Action.UseBlock(MACHINE,purpose),f.context));
    }
    @Test void cursorChangedOriginalDuplicateOrCompetingLoggingLeaseRejectsAllNewWork() {
        for(int obstruction=0;obstruction<4;obstruction++) {
            Fixture f=new Fixture();f.items.set(10,JADE);
            switch(obstruction) {
                case 0 -> f.cursor=JADE;
                case 1 -> f.fingerprintChanged=true;
                case 2 -> f.items.set(1,item(ORIGINAL.id(),1));
                case 3 -> f.profile.loggingHotbarLease=new LoggingHotbarLease(12,2,ORIGINAL,HASH);
            }
            assertNotNull(SafetyPolicy.rejection(new Action.SwapHotbar(10,1),f.context));
        }
    }
    @Test void temporaryWorkMayCloseOnlyAnOwnedMenuAndRefreshOnlyOrdinaryClosedInventory() {
        Fixture f=new Fixture();f.open=true;assertNull(SafetyPolicy.rejection(new Action.CloseContainer(1),f.context));
        f.owned=false;assertNotNull(SafetyPolicy.rejection(new Action.CloseContainer(1),f.context));
        assertNotNull(SafetyPolicy.rejection(new Action.RefreshInventory(),f.context));
        f.open=false;assertNull(SafetyPolicy.rejection(new Action.RefreshInventory(),f.context));
        f.refreshSupported=false;assertNotNull(SafetyPolicy.rejection(new Action.RefreshInventory(),f.context));
    }
    @Test void noWorkLeaseLeavesThePreexistingPolicyAuthorityUnchangedAndUnknownAdaptersCannotProveCustody() {
        Fixture f=new Fixture();HotbarLease lease=f.profile.workHotbarLease;f.profile.workHotbarLease=null;f.session.workHotbarOwner=null;
        assertNull(SafetyPolicy.rejection(new Action.SwapHotbar(10,2),f.context));
        assertFalse(f.workHotbarRestored(lease));assertFalse(f.workHotbarParked(lease));
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final Context context=new Context(this,this,this,profile,session);
        final List<ItemData> items=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));
        boolean open,owned=true,fingerprintChanged,refreshSupported=true;ItemData cursor=ItemData.EMPTY,stored=ItemData.EMPTY;
        Fixture(){
            profile.enabled.put(Feature.CRYSTAL_COPY,true);session.oneShotFeature=Feature.CRYSTAL_COPY;session.workHotbarOwner=Feature.CRYSTAL_COPY;
            profile.commodityStores.put("in",new CommodityStore("in","input",Set.of(JADE.id()),List.of(INPUT)));
            profile.commodityStores.put("out",new CommodityStore("out","output",Set.of(JADE.id()),List.of(OUTPUT)));
            profile.artisanJobs.put("jade",new ArtisanJob("jade",ArtisanRecipe.JADE_CRYSTAL.id(),List.of(MACHINE),"in","out"));
            original(ORIGINAL);
        }
        void original(ItemData item){profile.workHotbarLease=new HotbarLease(Feature.CRYSTAL_COPY,9,1,item,HASH,HotbarLease.Stage.PARKED);items.set(9,item);}
        void stage(HotbarLease.Stage stage){profile.workHotbarLease=profile.workHotbarLease.withStage(stage);}
        public long tick(){return 100;}public long dayTime(){return 0;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,1,true,true);}
        public BlockData block(Pos pos){return new BlockData(pos,MACHINE.equals(pos)?ArtisanRecipe.JADE_CRYSTAL.machineId():"minecraft:barrel",Map.of("container","true"));}
        public boolean loaded(Pos pos){return true;}public boolean canStand(Pos pos){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos pos,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){List<ItemSlot> slots=new ArrayList<>();for(int i=0;i<36;i++)slots.add(new ItemSlot(i,i,true,items.get(i)));return slots;}
        public MenuData menu(){if(!open)return new MenuData(0,0,inventory(),cursor,false);List<ItemSlot> slots=new ArrayList<>();
            for(int i=0;i<27;i++)slots.add(new ItemSlot(i,-1,false,i==0?stored:ItemData.EMPTY));
            for(int i=0;i<36;i++)slots.add(new ItemSlot(27+i,i,true,items.get(i)));return new MenuData(1,0,slots,cursor,true);}
        public String loggingItemFingerprint(int index){return fingerprintChanged?"b".repeat(64):HASH;}
        public boolean mayPlace(int slot,ItemData item){return true;}public boolean busy(){return false;}public boolean ownsContainer(){return open&&owned;}
        public boolean supportsInventoryRefresh(){return refreshSupported;}public long submit(Action action){throw new AssertionError("Policy is read-only");}
        public ActionOutcome outcome(long ticket){throw new AssertionError();}public void move(Movement movement){throw new AssertionError();}
        public void stopMovement(){}public void cancel(){}public Navigation.Result moveTo(Pos pos,double reach,Context c){throw new AssertionError();}public void reset(){}
    }
}
