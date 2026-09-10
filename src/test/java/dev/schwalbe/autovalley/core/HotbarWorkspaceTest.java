package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure controller tests: proof flags are explicit adapter contracts, not simulated native packet validation. */
class HotbarWorkspaceTest {
    private static final Feature OWNER=Feature.CRYSTAL_COPY;
    private static final ItemData ORIGINAL=item("minecraft:diamond_sword",1);
    private static final ItemData WORK=item(ArtisanRecipe.JADE_CRYSTAL.inputId(),2);
    private static final String ORIGINAL_FINGERPRINT="a".repeat(64);
    private static ItemData item(String id,int count){return new ItemData(id,count,0,null,false,99);}

    @Test void readinessIsReadOnlyAndParkingSavesPreparedBeforeOneExactSwap() {
        Fixture f=new Fixture();HotbarWorkspace workspace=new HotbarWorkspace(OWNER);
        ItemData[] before=f.items.clone();String[] fingerprints=f.fingerprints.clone();
        for(int i=0;i<10;i++)assertTrue(workspace.canPrepare(f.context));
        assertArrayEquals(before,f.items);assertArrayEquals(fingerprints,f.fingerprints);assertTrue(f.history.isEmpty());assertEquals(0,f.checkpoints);
        assertEquals(WorkResult.State.BUSY,workspace.prepare(f.context).state());
        HotbarLease prepared=f.profile.workHotbarLease;
        assertEquals(new HotbarLease(OWNER,9,1,ORIGINAL,ORIGINAL_FINGERPRINT),prepared);
        assertEquals(List.of(prepared),f.saved);assertEquals(List.of(0),f.savedActionCounts);
        assertEquals(new Action.SwapHotbar(9,1),f.pendingAction);assertEquals(OWNER,f.session.workHotbarOwner);
        assertArrayEquals(before,f.items,"Submission alone is not a server-confirmed slot move");
        f.succeedSwap();assertNull(workspace.pending(f.context));
        assertEquals(HotbarLease.Stage.PARKED,f.profile.workHotbarLease.stage());assertEquals(ORIGINAL,f.items[9]);assertTrue(f.items[1].empty());
        assertEquals(ORIGINAL_FINGERPRINT,f.fingerprints[9]);assertEquals(1,f.history.size());
        assertEquals(List.of(0,1),f.savedActionCounts);assertTrue(workspace.owns(f.context));assertFalse(workspace.canPrepare(f.context));
    }

    @Test void longParkingWaitRetainsTheSamePendingTicketAndNeverResendsOrAdvancesItsStage() {
        Fixture f=new Fixture();HotbarWorkspace workspace=new HotbarWorkspace(OWNER);workspace.prepare(f.context);
        long ticket=f.lastTicket;HotbarLease prepared=f.profile.workHotbarLease;Action action=f.pendingAction;
        for(int i=0;i<1600;i++){f.tick++;assertEquals(WorkResult.State.BUSY,workspace.prepare(f.context).state());}
        assertEquals(ticket,f.lastTicket);assertSame(action,f.pendingAction);assertSame(prepared,f.profile.workHotbarLease);
        assertEquals(1,f.history.size());assertEquals(1,f.checkpoints);assertEquals(ORIGINAL,f.items[1]);assertTrue(f.items[9].empty());
        f.succeedSwap();assertNull(workspace.pending(f.context));assertEquals(HotbarLease.Stage.PARKED,f.profile.workHotbarLease.stage());
    }

    @Test void restoringMovesOnlyTheOriginalAndWorkingItemAndClearsOnlyWithExplicitFinalCustodyProof() {
        Fixture f=new Fixture();HotbarWorkspace workspace=f.park();
        f.put(1,WORK,"b".repeat(64));f.parkedProof=true;
        ItemData[] before=f.items.clone();
        assertEquals(WorkResult.State.BUSY,workspace.restore(f.context).state());
        assertEquals(HotbarLease.Stage.RESTORING,f.profile.workHotbarLease.stage());assertEquals(new Action.SwapHotbar(9,1),f.pendingAction);
        assertEquals(1,f.savedActionCounts.get(2));assertArrayEquals(before,f.items);assertEquals(2,f.history.size());
        for(int i=0;i<1600;i++){f.tick++;assertEquals(WorkResult.State.BUSY,workspace.restore(f.context).state());}
        assertEquals(2,f.history.size());assertEquals(HotbarLease.Stage.RESTORING,f.profile.workHotbarLease.stage());
        f.succeedSwap();f.restoredProof=true;assertNull(workspace.restore(f.context));
        assertNull(f.profile.workHotbarLease);assertNull(f.session.workHotbarOwner);assertEquals(ORIGINAL,f.items[1]);assertEquals(WORK,f.items[9]);
        assertEquals(ORIGINAL_FINGERPRINT,f.fingerprints[1]);assertEquals(2,f.history.size());assertEquals(4,f.checkpoints);
    }

    @Test void restoredAppearanceAndSuccessfulTicketWithoutTheRequiredNativeProofCannotClearTheLease() {
        Fixture f=new Fixture();HotbarWorkspace workspace=f.park();f.parkedProof=true;workspace.restore(f.context);
        HotbarLease restoring=f.profile.workHotbarLease;f.succeedSwap();
        assertEquals(WorkResult.State.BLOCKED,workspace.restore(f.context).state());assertSame(restoring,f.profile.workHotbarLease);
        for(int i=0;i<10;i++)assertEquals(WorkResult.State.BLOCKED,workspace.restore(f.context).state());
        assertEquals(2,f.history.size());assertEquals(ORIGINAL,f.items[1]);assertTrue(f.items[9].empty());
        workspace.reset();f.restoredProof=true;assertNull(workspace.restore(f.context));assertNull(f.profile.workHotbarLease);
        assertEquals(2,f.history.size(),"Fresh final custody proof does not resend the old inverse swap");
    }

    @Test void missingMainInventoryCapacityDefersWithoutChangingSlotsOrSavingAnything() {
        Fixture f=new Fixture();for(int i=9;i<36;i++)f.put(i,item("minecraft:stone",64),"c".repeat(64));
        ItemData[] before=f.items.clone();HotbarWorkspace workspace=new HotbarWorkspace(OWNER);
        assertFalse(workspace.canPrepare(f.context));assertEquals(WorkResult.State.DEFERRED,workspace.prepare(f.context).state());
        assertArrayEquals(before,f.items);assertNull(f.profile.workHotbarLease);assertNull(f.session.workHotbarOwner);
        assertEquals(0,f.checkpoints);assertTrue(f.history.isEmpty());
    }

    @Test void failedPreparedCheckpointNeverDispatchesOrChangesTheOriginalInventory() {
        Fixture f=new Fixture();f.failCheckpoint=true;HotbarWorkspace workspace=new HotbarWorkspace(OWNER);ItemData[] before=f.items.clone();
        assertThrows(IllegalStateException.class,()->workspace.prepare(f.context));
        assertNull(f.profile.workHotbarLease);assertNull(f.session.workHotbarOwner);
        assertArrayEquals(before,f.items);assertTrue(f.history.isEmpty());assertNull(f.pendingAction);
    }

    @Test void aFailedRestoreCheckpointPreservesParkedCustodyWithoutSendingTheInverse() {
        Fixture f=new Fixture();HotbarWorkspace workspace=f.park();HotbarLease parked=f.profile.workHotbarLease;
        f.parkedProof=true;f.failCheckpoint=true;ItemData[] before=f.items.clone();
        assertThrows(IllegalStateException.class,()->workspace.restore(f.context));assertSame(parked,f.profile.workHotbarLease);
        assertArrayEquals(before,f.items);assertEquals(1,f.history.size());assertNull(f.pendingAction);
    }

    @Test void failedParkingAndRestoreTicketsLatchWithoutReplayingOrClearingTheirRecords() {
        for(boolean restoring:new boolean[]{false,true}) {
            Fixture f=new Fixture();HotbarWorkspace workspace=restoring?f.park():new HotbarWorkspace(OWNER);
            if(restoring){f.parkedProof=true;workspace.restore(f.context);}else workspace.prepare(f.context);
            HotbarLease before=f.profile.workHotbarLease;long ticket=f.lastTicket;int sent=f.history.size();
            f.failTicket();
            for(int i=0;i<20;i++)assertEquals(WorkResult.State.BLOCKED,workspace.restore(f.context).state());
            assertSame(before,f.profile.workHotbarLease);assertEquals(sent,f.history.size());assertEquals(ticket,f.lastTicket);
            assertEquals(ActionOutcome.State.FAILED,f.outcomes.get(ticket).state());
        }
    }

    @Test void manualResetKeepsParkedLeaseAndRecoveryRequestsOneRefreshBeforeOneProvenInverse() {
        Fixture f=new Fixture();HotbarWorkspace workspace=f.park();HotbarLease parked=f.profile.workHotbarLease;
        workspace.reset();f.session.workHotbarOwner=null;f.profile.enabled.put(OWNER,false);f.session.oneShotFeature=Feature.STARFRUIT;
        assertSame(parked,f.profile.workHotbarLease);assertEquals(1,f.history.size());
        assertEquals(WorkResult.State.BUSY,workspace.restore(f.context).state());assertInstanceOf(Action.RefreshInventory.class,f.pendingAction);
        long refreshTicket=f.lastTicket;
        for(int i=0;i<1400;i++){f.tick++;assertEquals(WorkResult.State.BUSY,workspace.restore(f.context).state());}
        assertEquals(refreshTicket,f.lastTicket);assertEquals(2,f.history.size());assertSame(parked,f.profile.workHotbarLease);
        f.succeedRefresh();f.parkedProof=true;
        assertEquals(WorkResult.State.BUSY,workspace.restore(f.context).state());assertEquals(new Action.SwapHotbar(9,1),f.pendingAction);
        f.succeedSwap();f.restoredProof=true;assertNull(workspace.restore(f.context));
        assertNull(f.profile.workHotbarLease);assertEquals(ORIGINAL,f.items[1]);assertEquals(3,f.history.size());
        assertEquals(Feature.STARFRUIT,f.session.oneShotFeature,"Restoration does not rewrite the user's selected job permission");
        assertFalse(f.profile.enabled(OWNER));
    }

    @Test void aRefreshWithoutCustodyEvidenceNeverLoopsOrManufacturesAnInverse() {
        Fixture f=new Fixture();HotbarWorkspace workspace=f.park();workspace.reset();HotbarLease lease=f.profile.workHotbarLease;
        workspace.restore(f.context);assertInstanceOf(Action.RefreshInventory.class,f.pendingAction);f.succeedRefresh();
        for(int i=0;i<20;i++)assertEquals(WorkResult.State.BLOCKED,workspace.restore(f.context).state());
        assertEquals(2,f.history.size());assertSame(lease,f.profile.workHotbarLease);assertEquals(ORIGINAL,f.items[9]);
    }

    @Test void interruptedRestoringNeverReplaysEvenWhenFreshProofShowsTheOriginalStillParked() {
        Fixture f=new Fixture();HotbarWorkspace workspace=f.park();f.parkedProof=true;workspace.restore(f.context);
        long inverse=f.lastTicket;HotbarLease lease=f.profile.workHotbarLease;Action sent=f.pendingAction;
        f.cancel();workspace.reset();assertEquals(ActionOutcome.State.CANCELLED,f.outcomes.get(inverse).state());
        workspace.restore(f.context);assertInstanceOf(Action.RefreshInventory.class,f.pendingAction);f.succeedRefresh();
        assertEquals(WorkResult.State.BLOCKED,workspace.restore(f.context).state());assertSame(lease,f.profile.workHotbarLease);
        assertEquals(3,f.history.size());assertEquals(2,f.history.stream().filter(Action.SwapHotbar.class::isInstance).count());
        // The old server request may later take effect, but a fresh current-state proof
        // settles custody only. Its cancelled historical ticket remains cancelled.
        f.applySwap((Action.SwapHotbar)sent);f.restoredProof=true;workspace.reset();
        assertNull(workspace.restore(f.context));assertNull(f.profile.workHotbarLease);assertEquals(3,f.history.size());
        assertEquals(ActionOutcome.State.CANCELLED,f.outcomes.get(inverse).state());assertEquals(ORIGINAL,f.items[1]);
    }

    @Test void changedOriginalFingerprintCannotBeParkedOrRestoredUsingMatchingVisibleItemData() {
        Fixture afterPark=new Fixture();HotbarWorkspace workspace=new HotbarWorkspace(OWNER);workspace.prepare(afterPark.context);afterPark.succeedSwap();
        afterPark.fingerprints[9]="d".repeat(64);HotbarLease prepared=afterPark.profile.workHotbarLease;
        assertEquals(WorkResult.State.BLOCKED,workspace.pending(afterPark.context).state());assertSame(prepared,afterPark.profile.workHotbarLease);
        assertEquals(1,afterPark.history.size());assertEquals(ORIGINAL,afterPark.items[9]);
        Fixture beforeRestore=new Fixture();HotbarWorkspace restored=beforeRestore.park();beforeRestore.supportsRefresh=false;
        beforeRestore.parkedProof=true;beforeRestore.fingerprints[9]="d".repeat(64);HotbarLease parked=beforeRestore.profile.workHotbarLease;
        assertEquals(WorkResult.State.BLOCKED,restored.restore(beforeRestore.context).state());assertSame(parked,beforeRestore.profile.workHotbarLease);
        assertEquals(1,beforeRestore.history.size());assertEquals(ORIGINAL,beforeRestore.items[9]);
    }

    @Test void anotherOwnerOrProfileCannotAcquireOrPollTheExistingCustodyTicket() {
        Fixture f=new Fixture();HotbarWorkspace owner=new HotbarWorkspace(OWNER);owner.prepare(f.context);HotbarLease lease=f.profile.workHotbarLease;
        HotbarWorkspace other=new HotbarWorkspace(Feature.SEED_MAKER);assertFalse(other.owns(f.context));
        assertEquals(WorkResult.State.BLOCKED,other.prepare(f.context).state());assertEquals(WorkResult.State.BLOCKED,other.restore(f.context).state());
        Profile changed=new Profile();changed.workHotbarLease=lease;
        Context changedContext=new Context(f,f,f,changed,f.session);
        assertEquals(WorkResult.State.BLOCKED,owner.pending(changedContext).state());assertEquals(0,f.outcomeQueries);
        assertSame(lease,f.profile.workHotbarLease);assertSame(lease,changed.workHotbarLease);assertEquals(1,f.history.size());
    }

    @Test void configuredToolsRecipeItemsBonusItemsAndVintageItemsAreNeverBorrowCandidates() {
        Fixture f=new Fixture();f.put(1,WORK,"b".repeat(64));
        HotbarWorkspace workspace=new HotbarWorkspace(OWNER);workspace.prepare(f.context);
        assertEquals(3,f.profile.workHotbarLease.hotbarSlot(),"Skip own jade, configured axe and hoe before selecting bread");
        assertEquals(item("minecraft:bread",8),f.profile.workHotbarLease.original());assertEquals(WORK,f.items[1]);
        Fixture none=new Fixture();none.put(1,WORK,"b".repeat(64));none.put(3,item("society:pristine_jade",1),"c".repeat(64));
        none.put(4,new ItemData("minecraft:iron_hoe",1,0,null,true,90),"d".repeat(64));
        none.put(5,new ItemData(ItemData.WINE,1,0,10,false,99),"e".repeat(64));
        for(int i=6;i<9;i++)none.put(i,WORK,"b".repeat(64));
        HotbarWorkspace unavailable=new HotbarWorkspace(OWNER);assertFalse(unavailable.canPrepare(none.context));
        assertEquals(WorkResult.State.DEFERRED,unavailable.prepare(none.context).state());assertTrue(none.history.isEmpty());assertNull(none.profile.workHotbarLease);
        Fixture fruit=new Fixture(Feature.STARFRUIT);fruit.put(1,item(FruitRules.ITEM,8),"b".repeat(64));
        new HotbarWorkspace(Feature.STARFRUIT).prepare(fruit.context);assertEquals(3,fruit.profile.workHotbarLease.hotbarSlot());
    }

    @Test void unknownFingerprintOrMalformedInventoryNeverAuthorizesAParkingClick() {
        for(boolean duplicate:new boolean[]{false,true}) {
            Fixture f=new Fixture();if(duplicate)f.duplicateInventory=true;else Arrays.fill(f.fingerprints,null);
            HotbarWorkspace workspace=new HotbarWorkspace(OWNER);assertFalse(workspace.canPrepare(f.context));
            assertEquals(WorkResult.State.DEFERRED,workspace.prepare(f.context).state());assertTrue(f.history.isEmpty());assertEquals(0,f.checkpoints);
        }
    }

    @Test void everyCrystalOutputIsExcludedFromBorrowingForLegacyAndCollectionOnlyJobs() {
        assertEquals(112,CrystalCollection.OUTPUT_IDS.size());
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.JADE_CRYSTAL,ArtisanRecipe.CRYSTAL_COLLECTION)) {
            for(String id:CrystalCollection.OUTPUT_IDS) {
                Fixture f=new Fixture();
                f.profile.artisanJobs.put("job",new ArtisanJob("job",recipe.id(),List.of(new Pos(10,64,0)),"input","output"));
                f.put(1,item(id,8),"b".repeat(64));ItemData[] before=f.items.clone();
                HotbarWorkspace workspace=new HotbarWorkspace(OWNER);
                assertTrue(workspace.canPrepare(f.context),id);
                assertEquals(WorkResult.State.BUSY,workspace.prepare(f.context).state(),id);
                assertEquals(3,f.profile.workHotbarLease.hotbarSlot(),id);
                assertEquals(item("minecraft:bread",8),f.profile.workHotbarLease.original(),id);
                assertArrayEquals(before,f.items,"An accepted parking request is not a slot ACK: "+id);
            }
        }
    }

    @Test void crystalOnlyHotbarDefersAndNonJadePickupStillRequiresExactRestorationProof() {
        Fixture full=new Fixture();
        for(int i=1;i<9;i++)if(i!=full.profile.loggingAxeHotbarSlot)
            full.put(i,item(i%2==0?"society:ruby":"society:pristine_quartz",4),"b".repeat(64));
        HotbarWorkspace unavailable=new HotbarWorkspace(OWNER);
        assertFalse(unavailable.canPrepare(full.context));
        assertEquals(WorkResult.State.DEFERRED,unavailable.prepare(full.context).state());
        assertNull(full.profile.workHotbarLease);assertTrue(full.history.isEmpty());

        Fixture f=new Fixture();HotbarWorkspace workspace=f.park();
        ItemData ruby=item("society:ruby",2);f.put(1,ruby,"b".repeat(64));f.parkedProof=true;
        assertEquals(WorkResult.State.BUSY,workspace.restore(f.context).state());
        f.succeedSwap();HotbarLease restoring=f.profile.workHotbarLease;
        assertEquals(WorkResult.State.BLOCKED,workspace.restore(f.context).state());
        assertSame(restoring,f.profile.workHotbarLease);
        workspace.reset();f.restoredProof=true;assertNull(workspace.restore(f.context));
        assertEquals(ORIGINAL,f.items[1]);assertEquals(ruby,f.items[9]);assertEquals(2,f.history.size());
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final ItemData[] items=new ItemData[36];final String[] fingerprints=new String[36];
        final List<Action> history=new ArrayList<>();final Map<Long,ActionOutcome> outcomes=new LinkedHashMap<>();
        final List<HotbarLease> saved=new ArrayList<>();final List<Integer> savedActionCounts=new ArrayList<>();
        long tick,lastTicket;int checkpoints,outcomeQueries;Action pendingAction;
        boolean failCheckpoint,parkedProof,restoredProof,supportsRefresh=true,duplicateInventory;
        final Context context=new Context(this,this,this,profile,session,()->{
            checkpoints++;if(failCheckpoint)throw new IllegalStateException("checkpoint failed");
            saved.add(profile.workHotbarLease);savedActionCounts.add(history.size());
        });
        Fixture(){this(OWNER);}
        Fixture(Feature owner){
            Arrays.fill(items,ItemData.EMPTY);Arrays.fill(fingerprints,"0".repeat(64));profile.enabled.put(owner,true);
            profile.hoeHotbarSlot=0;profile.loggingAxeHotbarSlot=2;
            put(0,new ItemData("minecraft:golden_hoe",1,0,null,true,99),"1".repeat(64));put(1,ORIGINAL,ORIGINAL_FINGERPRINT);
            put(2,item(LoggingRules.AXE,1),"2".repeat(64));put(3,item("minecraft:bread",8),"3".repeat(64));
            for(int i=4;i<9;i++)put(i,item("minecraft:bricks",i),Integer.toHexString(i).repeat(64));
            if(owner!=Feature.STARFRUIT)profile.artisanJobs.put("job",new ArtisanJob("job",owner==Feature.CRYSTAL_COPY?ArtisanRecipe.JADE_CRYSTAL.id():ArtisanRecipe.ANCIENT_SEED.id(),List.of(new Pos(10,64,0)),"input","output"));
        }
        HotbarWorkspace park(){HotbarWorkspace workspace=new HotbarWorkspace(OWNER);assertEquals(WorkResult.State.BUSY,workspace.prepare(context).state());succeedSwap();assertNull(workspace.pending(context));return workspace;}
        void put(int index,ItemData item,String fingerprint){items[index]=item;fingerprints[index]=fingerprint;}
        void applySwap(Action.SwapHotbar swap){
            ItemData old=items[swap.hotbarSlot()];String hash=fingerprints[swap.hotbarSlot()];
            items[swap.hotbarSlot()]=items[swap.inventoryIndex()];fingerprints[swap.hotbarSlot()]=fingerprints[swap.inventoryIndex()];
            items[swap.inventoryIndex()]=old;fingerprints[swap.inventoryIndex()]=hash;
        }
        void succeedSwap(){applySwap(assertInstanceOf(Action.SwapHotbar.class,pendingAction));complete(ActionOutcome.State.SUCCEEDED);}
        void succeedRefresh(){assertInstanceOf(Action.RefreshInventory.class,pendingAction);complete(ActionOutcome.State.SUCCEEDED);}
        void failTicket(){assertNotNull(pendingAction);complete(ActionOutcome.State.FAILED);}
        void complete(ActionOutcome.State state){outcomes.put(lastTicket,new ActionOutcome(state,"explicit test adapter outcome"));pendingAction=null;}
        boolean exactAt(int index,HotbarLease lease){return lease.original().equals(items[index]) && lease.fingerprint().equals(fingerprints[index]);}
        boolean workingOrEmpty(int index,HotbarLease lease){
            String id=items[index].id();return items[index].empty() || lease.owner()==Feature.STARFRUIT && FruitRules.ITEM.equals(id)
                || lease.owner()==Feature.CRYSTAL_COPY && CrystalCollection.OUTPUT_IDS.contains(id)
                || lease.owner()==Feature.SEED_MAKER && (ArtisanRecipe.ANCIENT_SEED.inputId().equals(id) || ArtisanRecipe.ANCIENT_SEED.outputId().equals(id));
        }
        public boolean workHotbarParked(HotbarLease lease){return parkedProof && !busy() && Objects.equals(profile.workHotbarLease,lease)
            && exactAt(lease.sourceIndex(),lease) && !exactAt(lease.hotbarSlot(),lease) && workingOrEmpty(lease.hotbarSlot(),lease);}
        public boolean workHotbarRestored(HotbarLease lease){return restoredProof && !busy() && Objects.equals(profile.workHotbarLease,lease)
            && exactAt(lease.hotbarSlot(),lease) && !exactAt(lease.sourceIndex(),lease) && workingOrEmpty(lease.sourceIndex(),lease);}
        public boolean supportsInventoryRefresh(){return supportsRefresh;}
        public String loggingItemFingerprint(int index){return fingerprints[index];}
        public long tick(){return tick;}public long dayTime(){return 241000;}
        public PlayerState player(){return new PlayerState(.5,64,.5,0,0,true,false,20,20,1,true,true);}
        public BlockData block(Pos p){throw new AssertionError("Working-slot controller must not inspect terrain");}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos p,int h,int v){throw new AssertionError("Working-slot controller must not scan terrain");}
        public List<ItemSlot> inventory(){List<ItemSlot> inventory=new ArrayList<>();for(int i=0;i<36;i++)inventory.add(new ItemSlot(i,duplicateInventory&&i==35?34:i,true,items[i]));return inventory;}
        public MenuData menu(){return new MenuData(0,0,inventory(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return true;}
        public boolean busy(){return pendingAction!=null;}
        public long submit(Action action){assertNull(pendingAction);pendingAction=action;history.add(action);outcomes.put(++lastTicket,new ActionOutcome(ActionOutcome.State.PENDING,"not yet acknowledged"));return lastTicket;}
        public ActionOutcome outcome(long ticket){outcomeQueries++;return outcomes.get(ticket);}
        public void move(Movement movement){throw new AssertionError("Working-slot controller must not walk");}public void stopMovement(){}
        public void cancel(){if(pendingAction!=null)complete(ActionOutcome.State.CANCELLED);}
        public Result moveTo(Pos p,double reach,Context c){throw new AssertionError("Working-slot controller must not navigate");}public void reset(){}
    }
}
