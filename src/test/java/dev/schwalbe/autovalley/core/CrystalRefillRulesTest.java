package dev.schwalbe.autovalley.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class CrystalRefillRulesTest {
    private static final Pos TARGET=new Pos(10,64,10),OTHER=new Pos(11,64,10);
    private static final Pos INPUT=new Pos(12,64,10),OUTPUT=new Pos(13,64,10);
    private static final String FIRE="society:fire_quartz",JADE="society:jade";

    @Test void inspectionRequiresOneExactKnownOriginAndMatchingRawMachineState() {
        for(String input:CrystalCollection.BASE_OUTPUT_IDS) {
            assertTrue(new CrystalInspection(TARGET,input,true,false).matches(block(TARGET,true,false)),input);
            assertTrue(new CrystalInspection(TARGET,input,false,true).matches(block(TARGET,false,true)),input);
        }
        CrystalInspection empty=new CrystalInspection(TARGET,"",false,false);
        assertTrue(empty.valid());assertTrue(empty.empty());assertTrue(empty.matches(block(TARGET,false,false)));
        for(CrystalInspection invalid:List.of(new CrystalInspection(null,FIRE,true,false),
                new CrystalInspection(TARGET,null,true,false),new CrystalInspection(TARGET,"",true,false),
                new CrystalInspection(TARGET,FIRE,true,true),new CrystalInspection(TARGET,FIRE,false,false),
                new CrystalInspection(TARGET,"society:pristine_fire_quartz",true,false),
                new CrystalInspection(TARGET,"society:black_opal",true,false)))assertFalse(invalid.valid(),invalid.toString());
        CrystalInspection mature=new CrystalInspection(TARGET,FIRE,true,false);
        for(BlockData wrong:Arrays.asList(null,block(OTHER,true,false),block(TARGET,false,false),block(TARGET,false,true),
                new BlockData(TARGET,"minecraft:stone",block(TARGET,true,false).properties()),
                new BlockData(TARGET,CrystalCollection.MACHINE_ID,Map.of()),
                new BlockData(TARGET,CrystalCollection.MACHINE_ID,null)))assertFalse(mature.matches(wrong),String.valueOf(wrong));
    }

    @Test void serverOriginalSelectsEachOf56RecipesWithoutConsultingCarriedInventoryOrLegacyJade() {
        Fixture f=new Fixture();
        for(String input:CrystalCollection.BASE_OUTPUT_IDS) {
            f.inspection=new CrystalInspection(TARGET,input,true,false);
            assertEquals(CrystalRecipe.forInput(input),CrystalRefillRules.forAction(f.context,TARGET,f.block));
            assertEquals(CrystalRecipe.forInput(input),ArtisanRules.forAction(f.context,TARGET));
            assertNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(input)));
            assertNull(ArtisanRules.rejection(f.context,TARGET,f.block,ItemData.EMPTY));
            assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(input.equals(JADE)?FIRE:JADE)));
            assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(CrystalRecipe.bonusId(input))));
        }
        assertEquals(0,f.inventoryReads);assertEquals(0,f.checkpoints);assertTrue(f.profile.crystalRefills.isEmpty());
    }

    @Test void absentWrongUnknownAndStateStaleInspectionsNeverAuthorizeUseOrRemember() {
        for(CrystalInspection bad:Arrays.asList(null,new CrystalInspection(OTHER,FIRE,true,false),
                new CrystalInspection(TARGET,"society:unknown",true,false),
                new CrystalInspection(TARGET,"society:pristine_fire_quartz",true,false),
                new CrystalInspection(TARGET,"",false,false),new CrystalInspection(TARGET,FIRE,false,true),
                new CrystalInspection(TARGET,FIRE,true,true))) {
            Fixture f=new Fixture();f.inspection=bad;
            assertNull(CrystalRefillRules.forAction(f.context,TARGET,f.block),String.valueOf(bad));
            assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
            assertThrows(IllegalStateException.class,()->CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE));
            assertEquals(0,f.checkpoints);assertTrue(f.profile.crystalRefills.isEmpty());assertEquals(6,f.profile.schemaVersion);
        }
        Fixture f=new Fixture();
        assertThrows(IllegalStateException.class,()->CrystalRefillRules.remember(f.context,f.job,TARGET,JADE));
        f.block=block(TARGET,false,false); // A once-valid mature tooltip is now stale against the live state.
        assertNull(CrystalRefillRules.forAction(f.context,TARGET,f.block));
        assertThrows(IllegalStateException.class,()->CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE));
        assertEquals(0,f.checkpoints);
    }

    @Test void rememberCheckpointsExactOriginalBeforeAnyHarvestAndDoesNotRepeatAnIdenticalSave() {
        Fixture f=new Fixture();Map<String,Long> dates=Map.copyOf(f.profile.nextEligibleDay);
        CrystalRefill expected=new CrystalRefill(f.job.id(),TARGET,FIRE,321);
        f.onCheckpoint=()->{
            assertEquals(expected,f.profile.crystalRefills.get(key(TARGET)));
            assertEquals(8,f.profile.schemaVersion);assertTrue(f.block.flag("mature"));
            assertEquals(dates,f.profile.nextEligibleDay);
        };
        CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE);
        assertEquals(Map.of(key(TARGET),expected),f.profile.crystalRefills);assertEquals(1,f.checkpoints);
        CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE);assertEquals(1,f.checkpoints);
        assertEquals(0,f.inventoryReads);assertTrue(CrystalRefillRules.hasPending(f.profile,f.job));
    }

    @Test void failedRememberRestoresPreviousRecordAndSchemaWithNoHarvestOrDateMutation() {
        for(boolean previous:new boolean[]{false,true}) {
            Fixture f=new Fixture();CrystalRefill old=new CrystalRefill(f.job.id(),TARGET,JADE,300);
            if(previous){f.profile.crystalRefills.put(key(TARGET),old);f.profile.schemaVersion=7;}
            int schema=f.profile.schemaVersion;Map<String,CrystalRefill> records=Map.copyOf(f.profile.crystalRefills);
            Map<String,Long> dates=Map.copyOf(f.profile.nextEligibleDay);f.failCheckpoint=true;
            assertThrows(IllegalStateException.class,()->CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE));
            assertEquals(records,f.profile.crystalRefills);assertEquals(schema,f.profile.schemaVersion);
            assertEquals(dates,f.profile.nextEligibleDay);assertEquals(1,f.checkpoints);assertTrue(f.block.flag("mature"));
            f.failCheckpoint=false;CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE);
            assertEquals(FIRE,f.profile.crystalRefills.get(key(TARGET)).inputId());assertEquals(2,f.checkpoints);
        }
    }

    @Test void pendingRecipesRequireExactCurrentJobPositionAndUnambiguousOwnership() {
        Fixture f=new Fixture();f.pending(FIRE);
        assertEquals(CrystalRecipe.forInput(FIRE),CrystalRefillRules.pendingRecipe(f.profile,f.job,TARGET));
        assertNull(CrystalRefillRules.pendingRecipe(f.profile,f.job,OTHER));
        ArtisanJob changed=new ArtisanJob(f.job.id(),f.job.recipeId(),f.job.machines(),"other_input",f.job.outputStoreId());
        assertNull(CrystalRefillRules.pendingRecipe(f.profile,changed,TARGET));
        f.profile.crystalRefills.put(key(TARGET),new CrystalRefill("another_job",TARGET,FIRE,321));
        assertNull(CrystalRefillRules.pendingRecipe(f.profile,f.job,TARGET));
        f.profile.crystalRefills.put(key(TARGET),new CrystalRefill(f.job.id(),OTHER,FIRE,321));
        assertNull(CrystalRefillRules.pendingRecipe(f.profile,f.job,TARGET));
        f.pending(FIRE);f.profile.artisanJobs.remove(f.job.id());
        assertNull(CrystalRefillRules.pendingRecipe(f.profile,f.job,TARGET));
        assertDoesNotThrow(()->CrystalRefillRules.validate(f.profile));assertEquals(1,f.profile.crystalRefills.size());
        f.profile.artisanJobs.put(f.job.id(),f.job);
        f.profile.artisanJobs.put("ambiguous",new ArtisanJob("ambiguous",f.job.recipeId(),List.of(TARGET),"input","output"));
        assertNull(CrystalRefillRules.pendingRecipe(f.profile,f.job,TARGET));
        assertThrows(IllegalStateException.class,()->CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE));
        assertEquals(0,f.checkpoints);
    }

    @Test void removedOrUnregisteredTargetsCannotBeRememberedClearedOrCompleted() {
        Fixture f=new Fixture();f.pending(FIRE);Map<String,CrystalRefill> before=Map.copyOf(f.profile.crystalRefills);
        f.profile.artisanJobs.clear();
        assertThrows(IllegalStateException.class,()->CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE));
        assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.complete(f.context,f.job,TARGET,323));
        assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.clear(f.context,f.job,TARGET));
        assertEquals(before,f.profile.crystalRefills);assertEquals(0,f.checkpoints);
    }

    @Test void freshManualReseedingWinsOverSavedJadeWithoutSilentlyDeletingTheContinuation() {
        Fixture f=new Fixture();f.pending(JADE);CrystalRefill old=f.profile.crystalRefills.get(key(TARGET));
        assertEquals(CrystalRecipe.forInput(FIRE),CrystalRefillRules.forAction(f.context,TARGET,f.block));
        assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(JADE)));
        assertNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
        assertSame(old,f.profile.crystalRefills.get(key(TARGET)));assertEquals(0,f.checkpoints);
        CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE);
        assertEquals(FIRE,f.profile.crystalRefills.get(key(TARGET)).inputId());assertEquals(1,f.checkpoints);
        f.inspection=new CrystalInspection(TARGET,"minecraft:diamond",false,true);f.block=block(TARGET,false,true);
        assertNull(CrystalRefillRules.forAction(f.context,TARGET,f.block));
        assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
        assertEquals(FIRE,f.profile.crystalRefills.get(key(TARGET)).inputId());assertEquals(1,f.checkpoints);
    }

    @Test void emptyMachinesRequireBothFreshEmptyInspectionAndTheirOwnPendingOriginal() {
        Fixture f=new Fixture();f.inspection=new CrystalInspection(TARGET,"",false,false);f.block=block(TARGET,false,false);
        assertNull(CrystalRefillRules.forAction(f.context,TARGET,f.block));
        assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
        f.pending(FIRE);
        assertEquals(CrystalRecipe.forInput(FIRE),CrystalRefillRules.forAction(f.context,TARGET,f.block));
        assertNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
        assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,ItemData.EMPTY));
        assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(JADE)));
        f.inspection=null;assertNull(CrystalRefillRules.forAction(f.context,TARGET,f.block));
        assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
        assertEquals(1,f.profile.crystalRefills.size());assertEquals(0,f.checkpoints);
    }

    @Test void withdrawalsUseOnlyTheExplicitInputStoreAndRecordedBaseWithoutJadeOrPristineFallback() {
        Fixture f=new Fixture();
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT,item(FIRE)));
        assertFalse(CommodityStorageRules.openAllowed(f.context,INPUT));
        f.pending(FIRE);assertTrue(CommodityStorageRules.openAllowed(f.context,INPUT));
        assertTrue(CommodityStorageRules.withdrawalAllowed(f.context,INPUT,item(FIRE)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,OUTPUT,item(FIRE)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,OTHER,item(FIRE)));
        for(ItemData wrong:Arrays.asList(null,ItemData.EMPTY,item(JADE),item("society:pristine_fire_quartz"),item("minecraft:diamond"))) {
            assertFalse(CrystalRefillRules.pendingInput(f.profile,f.job,wrong),String.valueOf(wrong));
            assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT,wrong),String.valueOf(wrong));
        }
        f.profile.commodityStores.put("input",new CommodityStore("input","Only jade",Set.of(JADE),List.of(INPUT)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT,item(FIRE)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT,item(JADE)));
        assertEquals(FIRE,f.profile.crystalRefills.get(key(TARGET)).inputId());assertEquals(0,f.checkpoints);
    }

    @Test void offAndDifferentOneShotNeverAuthorizeUseOrWithdrawalButKeepPendingIntent() {
        Fixture f=new Fixture();f.pending(FIRE);f.profile.enabled.put(Feature.CRYSTAL_COPY,false);
        Map<String,CrystalRefill> before=Map.copyOf(f.profile.crystalRefills);
        for(Feature oneShot:Arrays.asList(null,Feature.WINE,Feature.COMMODITY_STORAGE)) {
            f.session.oneShotFeature=oneShot;
            assertNotNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
            assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,INPUT,item(FIRE)));
            assertThrows(IllegalStateException.class,()->CrystalRefillRules.remember(f.context,f.job,TARGET,FIRE));
        }
        assertEquals(before,f.profile.crystalRefills);assertEquals(0,f.checkpoints);
        f.session.oneShotFeature=Feature.CRYSTAL_COPY;
        assertNull(ArtisanRules.rejection(f.context,TARGET,f.block,item(FIRE)));
        assertTrue(CommodityStorageRules.withdrawalAllowed(f.context,INPUT,item(FIRE)));
        assertFalse(f.profile.enabled(Feature.CRYSTAL_COPY));
    }

    @Test void completionClearsOnlyItsOwnRecordAndCheckpointsTheDateAtomically() {
        Fixture f=new Fixture();f.pending(FIRE);
        CrystalRefill other=new CrystalRefill("removed_job",OTHER,JADE,300);f.profile.crystalRefills.put(key(OTHER),other);
        f.profile.nextEligibleDay.put(f.schedule(),999L);
        f.onCheckpoint=()->{
            assertFalse(f.profile.crystalRefills.containsKey(key(TARGET)));
            assertEquals(323L,f.profile.nextEligibleDay.get(f.schedule()).longValue());
            assertSame(other,f.profile.crystalRefills.get(key(OTHER)));
        };
        CrystalRefillRules.complete(f.context,f.job,TARGET,323);
        assertEquals(Map.of(key(OTHER),other),f.profile.crystalRefills);assertEquals(1,f.checkpoints);
        assertEquals(777L,f.profile.nextEligibleDay.get("unrelated").longValue());assertEquals(8,f.profile.schemaVersion);
    }

    @Test void failedCompletionRestoresRecordAndPresentOrAbsentDateTogether() {
        for(boolean hadDate:new boolean[]{false,true}) {
            Fixture f=new Fixture();f.pending(FIRE);
            if(hadDate)f.profile.nextEligibleDay.put(f.schedule(),999L);
            Map<String,CrystalRefill> before=Map.copyOf(f.profile.crystalRefills);
            Map<String,Long> dates=Map.copyOf(f.profile.nextEligibleDay);f.failCheckpoint=true;
            assertThrows(IllegalStateException.class,()->CrystalRefillRules.complete(f.context,f.job,TARGET,323));
            assertEquals(before,f.profile.crystalRefills);assertEquals(dates,f.profile.nextEligibleDay);
            assertEquals(8,f.profile.schemaVersion);assertEquals(1,f.checkpoints);
            f.failCheckpoint=false;CrystalRefillRules.complete(f.context,f.job,TARGET,323);
            assertTrue(f.profile.crystalRefills.isEmpty());assertEquals(323L,f.profile.nextEligibleDay.get(f.schedule()).longValue());
        }
    }

    @Test void clearPreservesDatesRollsBackOnFailureAndNeverRemovesAnotherJobsRecord() {
        Fixture f=new Fixture();f.pending(FIRE);Map<String,Long> dates=Map.copyOf(f.profile.nextEligibleDay);
        CrystalRefill before=f.profile.crystalRefills.get(key(TARGET));f.failCheckpoint=true;
        assertThrows(IllegalStateException.class,()->CrystalRefillRules.clear(f.context,f.job,TARGET));
        assertSame(before,f.profile.crystalRefills.get(key(TARGET)));assertEquals(dates,f.profile.nextEligibleDay);
        f.failCheckpoint=false;CrystalRefillRules.clear(f.context,f.job,TARGET);
        assertTrue(f.profile.crystalRefills.isEmpty());assertEquals(dates,f.profile.nextEligibleDay);assertEquals(2,f.checkpoints);
        CrystalRefillRules.clear(f.context,f.job,TARGET);assertEquals(2,f.checkpoints);
        CrystalRefill foreign=new CrystalRefill("other_job",TARGET,JADE,300);f.profile.crystalRefills.put(key(TARGET),foreign);
        CrystalRefillRules.clear(f.context,f.job,TARGET);assertSame(foreign,f.profile.crystalRefills.get(key(TARGET)));
        assertEquals(2,f.checkpoints);
        assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.complete(f.context,f.job,TARGET,-1));
        assertSame(foreign,f.profile.crystalRefills.get(key(TARGET)));assertEquals(dates,f.profile.nextEligibleDay);
    }

    @Test void invalidRecordsFailClosedWhileRemovedJobRecordsRemainInertAndCountIsBounded() {
        List<CrystalRefill> invalid=new ArrayList<>();
        for(String job:Arrays.asList(null,""," ","x".repeat(65)))invalid.add(new CrystalRefill(job,TARGET,FIRE,1));
        for(Pos pos:Arrays.asList(null,new Pos(30_000_000,64,0),new Pos(0,2048,0),new Pos(0,-2049,0)))
            invalid.add(new CrystalRefill("job",pos,FIRE,1));
        for(String item:Arrays.asList(null,"",JADE+" ","other:jade","society:pristine_fire_quartz","society:black_opal"))
            invalid.add(new CrystalRefill("job",TARGET,item,1));
        invalid.add(new CrystalRefill("job",TARGET,FIRE,-1));
        for(CrystalRefill bad:invalid) {
            assertFalse(bad.valid(),bad.toString());Profile profile=new Profile();profile.crystalRefills.put(key(TARGET),bad);
            assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.validate(profile));
        }
        Profile profile=new Profile();profile.crystalRefills.put(key(TARGET),null);
        assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.validate(profile));
        profile.crystalRefills.clear();profile.crystalRefills.put("wrong_key",new CrystalRefill("removed",TARGET,FIRE,1));
        assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.validate(profile));
        profile.crystalRefills=null;assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.validate(profile));
        assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.validate(null));
        profile.crystalRefills=new LinkedHashMap<>();
        for(int i=0;i<4096;i++){Pos pos=new Pos(i,64,0);profile.crystalRefills.put(key(pos),new CrystalRefill("removed",pos,FIRE,1));}
        assertDoesNotThrow(()->CrystalRefillRules.validate(profile));
        Pos extra=new Pos(4096,64,0);profile.crystalRefills.put(key(extra),new CrystalRefill("removed",extra,FIRE,1));
        assertThrows(IllegalArgumentException.class,()->CrystalRefillRules.validate(profile));
    }

    private static String key(Pos pos){return Profile.positionKey(pos);}
    private static ItemData item(String id){return new ItemData(id,1,0,null,false,Integer.MAX_VALUE);}
    private static BlockData block(Pos pos,boolean mature,boolean working) {
        return new BlockData(pos,CrystalCollection.MACHINE_ID,Map.of("mature",Boolean.toString(mature),"working",Boolean.toString(working),"upgraded","false"));
    }
    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        // A legacy job name is deliberately not evidence that this particular machine still contains jade.
        final ArtisanJob job=new ArtisanJob("crystals",ArtisanRecipe.JADE_CRYSTAL.id(),List.of(TARGET),"input","output");
        BlockData block=CrystalRefillRulesTest.block(TARGET,true,false);CrystalInspection inspection=new CrystalInspection(TARGET,FIRE,true,false);
        int checkpoints,inventoryReads;boolean failCheckpoint;Runnable onCheckpoint=()->{};
        final Context context=new Context(this,this,null,profile,session,()->{
            checkpoints++;onCheckpoint.run();if(failCheckpoint)throw new IllegalStateException("test checkpoint failed");
        });
        Fixture(){
            profile.enabled.put(Feature.CRYSTAL_COPY,true);profile.enabled.put(Feature.COMMODITY_STORAGE,false);
            profile.artisanJobs.put(job.id(),job);profile.nextEligibleDay.put("unrelated",777L);
            Set<String> stored=Set.of(FIRE,JADE,"minecraft:diamond","society:pristine_fire_quartz","society:pristine_jade");
            profile.commodityStores.put("input",new CommodityStore("input","Input",stored,List.of(INPUT)));
            profile.commodityStores.put("output",new CommodityStore("output","Output",stored,List.of(OUTPUT)));
        }
        void pending(String input){profile.crystalRefills.put(key(TARGET),new CrystalRefill(job.id(),TARGET,input,321));profile.schemaVersion=8;}
        String schedule(){return CrystalCollection.scheduleKey(job,TARGET);}
        public CrystalInspection crystalInspection(Pos pos){return inspection;}
        public long tick(){return 10;}public long dayTime(){return 321*24000L+123;}
        public BlockData block(Pos pos){return pos.equals(TARGET)?block:CrystalRefillRulesTest.block(pos,false,false);}
        public PlayerState player(){return new PlayerState(10.5,64,10.5,0,0,true,false,20,20,0,true,true);}
        public boolean loaded(Pos pos){return true;}public boolean canStand(Pos feet){return true;}
        public boolean canTraverse(Pos from,Pos to){return true;}public List<BlockData> scan(Pos center,int horizontal,int vertical){return List.of();}
        public List<ItemSlot> inventory(){inventoryReads++;throw new AssertionError("Carried items cannot identify a machine's original");}
        public MenuData menu(){throw new AssertionError("Recipe rules do not inspect or alter inventory menus");}
        public boolean mayPlace(int slot,ItemData item){throw new AssertionError("Recipe rules never place items");}
        public boolean busy(){return false;}
        public long submit(Action action){throw new AssertionError("Intent and permission checks cannot send game actions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No historical native ACK may be changed");}
        public void move(Movement movement){throw new AssertionError("No movement");}
        public void stopMovement(){throw new AssertionError("No movement mutation");}
        public void cancel(){throw new AssertionError("No native cancellation");}
    }
}
