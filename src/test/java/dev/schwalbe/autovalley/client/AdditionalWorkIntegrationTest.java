package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Registration and authority checks only; no game, disk profile, or recording is opened. */
class AdditionalWorkIntegrationTest {
    private static final Gson JSON=new Gson();
    private static final Pos INPUT=new Pos(1,64,0),OUTPUT=new Pos(2,64,0),MACHINE=new Pos(4,64,0),OTHER=new Pos(8,64,0);
    private static final ArtisanRecipe SEED=ArtisanRecipe.ANCIENT_SEED;
    private static ItemData item(String id) { return new ItemData(id,6,0,null,false,99); }
    private static CommodityStore store(String id,Pos pos,String... items) { return new CommodityStore(id,id,Set.of(items),List.of(pos)); }
    private static Profile profile() {
        Profile p=new Profile();
        p.commodityStores.put("input",store("input",INPUT,SEED.inputId(),"minecraft:egg"));
        p.commodityStores.put("output",store("output",OUTPUT,SEED.outputId()));
        p.artisanJobs.put("seeds",new ArtisanJob("seeds",SEED.id(),List.of(MACHINE),"input","output"));
        return p;
    }
    private static Context context(Profile p,Feature once) { SessionState session=new SessionState();session.oneShotFeature=once;return new Context(null,null,null,p,session); }
    private static String importJson(Profile p,Set<Feature> enable) {
        return JSON.toJson(new WorkRegistrationImport.Data(Map.of(),List.of(),p.commodityStores,Map.of(),p.artisanJobs,List.of(),enable));
    }
    @Test void newFeaturesStayOffInNewAndLegacyMissingSwitchProfiles() {
        for(int schema:List.of(1,2,3,4,5)) {
            Profile p=new Profile();p.schemaVersion=schema;p.enabled.clear();ProfileStore.validate(p);
            for(Feature feature:List.of(Feature.COMMODITY_STORAGE,Feature.SEED_MAKER,Feature.CRYSTAL_COPY,Feature.STARFRUIT))
                assertFalse(p.enabled(feature),feature.toString());
            assertEquals(5,p.schemaVersion);assertTrue(p.enabled(Feature.HARVEST));
        }
    }
    @Test void oneShotJobOnlyOpensItsDeclaredInputAndOutputGroups() {
        Profile p=profile();p.commodityStores.put("other",store("other",OTHER,SEED.inputId()));
        Context c=context(p,Feature.SEED_MAKER);
        assertTrue(CommodityStorageRules.openAllowed(c,INPUT));assertTrue(CommodityStorageRules.openAllowed(c,OUTPUT));
        assertFalse(CommodityStorageRules.openAllowed(c,OTHER));
        assertFalse(CommodityStorageRules.openAllowed(context(p,Feature.CRYSTAL_COPY),INPUT));
    }
    @Test void mixedStoreNeverAuthorizesItsNonIngredientAsAWithdrawal() {
        Context c=context(profile(),Feature.SEED_MAKER);
        assertTrue(CommodityStorageRules.withdrawalAllowed(c,INPUT,item(SEED.inputId())));
        assertFalse(CommodityStorageRules.withdrawalAllowed(c,INPUT,item("minecraft:egg")));
        assertFalse(CommodityStorageRules.withdrawalAllowed(c,OUTPUT,item(SEED.inputId())));
        assertFalse(CommodityStorageRules.withdrawalAllowed(c,OTHER,item(SEED.inputId())));
    }
    @Test void genericStorageFeatureIsDepositOnlyNotAnIngredientWithdrawalGrant() {
        Context c=context(profile(),Feature.COMMODITY_STORAGE);
        assertTrue(CommodityStorageRules.depositAllowed(c,INPUT,item(SEED.inputId())));
        assertFalse(CommodityStorageRules.withdrawalAllowed(c,INPUT,item(SEED.inputId())));
        assertFalse(CommodityStorageRules.depositAllowed(c,OUTPUT,item(SEED.inputId())));
    }
    @Test void disabledFeaturesAndUnrelatedOnceCannotBorrowContinuousAuthority() {
        Profile p=profile();assertFalse(CommodityStorageRules.openAllowed(context(p,null),INPUT));
        p.enabled.put(Feature.SEED_MAKER,true);p.enabled.put(Feature.COMMODITY_STORAGE,true);
        assertTrue(CommodityStorageRules.openAllowed(context(p,null),INPUT));
        Context once=context(p,Feature.WINE);
        assertFalse(CommodityStorageRules.openAllowed(once,INPUT));
        assertFalse(CommodityStorageRules.withdrawalAllowed(once,INPUT,item(SEED.inputId())));
    }
    @Test void unknownItemQualityDoesNotBroadenCommodityIdentity() {
        Context c=context(profile(),Feature.SEED_MAKER);
        assertTrue(CommodityStorageRules.depositAllowed(c,INPUT,new ItemData(SEED.inputId(),3,-1,null,false,99)));
        assertFalse(CommodityStorageRules.depositAllowed(c,INPUT,item("other:ancient_fruit")));
        assertFalse(CommodityStorageRules.depositAllowed(c,INPUT,ItemData.EMPTY));
    }
    @Test void keyMismatchOrInvalidGroupCannotGrantContainerAuthority() {
        Profile p=profile();p.commodityStores.put("input",store("wrong_key",INPUT,SEED.inputId()));
        assertNull(CommodityStorageRules.store(p,"input"));
        assertFalse(CommodityStorageRules.openAllowed(context(p,Feature.SEED_MAKER),INPUT));
        assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(p));
    }
    @Test void unsupportedRecipeAndAmbiguousMachinesCannotBeRegistered() {
        assertThrows(IllegalArgumentException.class,()->new ArtisanJob("bad","not_a_recipe",List.of(MACHINE),"input","output"));
        Profile p=profile();p.artisanJobs.put("second",new ArtisanJob("second",SEED.id(),List.of(MACHINE),"input","output"));
        assertNull(ArtisanRules.at(p,MACHINE));assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(p));
    }
    @Test void recipeInputAndOutputGroupsMustAcceptTheirExactItems() {
        for(boolean badInput:List.of(false,true)) {
            Profile p=profile();String id=badInput ? "input" : "output";
            p.commodityStores.put(id,store(id,badInput ? INPUT : OUTPUT,"minecraft:apple"));
            assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(p));
        }
    }
    @Test void malformedCoordinatesAndNamespacedIdsCannotPassValidation() {
        Profile p=profile();p.commodityStores.put("input",store("input",new Pos(Integer.MAX_VALUE,64,0),SEED.inputId()));
        assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(p));
        Profile invalid=profile();invalid.commodityStores.put("input",store("input",INPUT,"Minecraft:Apple"));
        assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(invalid));
    }
    @Test void cropStoreRegistrationRequiresTheCropsCommodity() {
        Profile p=profile();p.cropStores.put("tomato","output");
        assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(p));
    }
    @Test void nullLegacyAdditionalCollectionsReceiveSafeEmptyDefaults() {
        Profile p=new Profile();p.crops=null;p.commodityStores=null;p.cropStores=null;p.artisanJobs=null;p.fruitPatches=null;
        AdditionalWorkRules.validate(p);assertFalse(p.crops.isEmpty());assertTrue(p.commodityStores.isEmpty());
        assertTrue(p.artisanJobs.isEmpty());assertTrue(p.fruitPatches.isEmpty());
    }
    @Test void importIsIdempotentAndKeepsTheLiveScheduleAndWineBatchUntouched() {
        Profile current=new Profile();current.nextEligibleDay.put("wine_existing",470L);
        current.pois.add(new Poi(OTHER,PoiKind.TOMATO_CHEST,"Legacy",3));
        String before=JSON.toJson(current),json=importJson(profile(),Set.of(Feature.SEED_MAKER));
        Profile first=WorkRegistrationImport.merge(current,json),second=WorkRegistrationImport.merge(first,json);
        assertEquals(before,JSON.toJson(current));assertEquals(JSON.toJson(first),JSON.toJson(second));
        assertEquals(current.nextEligibleDay,second.nextEligibleDay);assertEquals(current.pois,second.pois);
        assertEquals(current.wineBatchSchedule,second.wineBatchSchedule);
        assertEquals(1,second.artisanJobs.size());assertEquals(2,second.commodityStores.size());
        assertTrue(second.enabled(Feature.SEED_MAKER));assertFalse(second.enabled(Feature.CRYSTAL_COPY));
    }
    @Test void importCannotOverwriteExistingGroupsOrJobsAndOriginalRemainsUnchanged() {
        Profile current=profile();String before=JSON.toJson(current);
        Profile changed=profile();changed.commodityStores.put("input",store("input",OTHER,SEED.inputId()));
        assertThrows(IllegalArgumentException.class,()->WorkRegistrationImport.merge(current,importJson(changed,Set.of())));
        Profile changedJob=profile();changedJob.artisanJobs.put("seeds",new ArtisanJob("seeds",SEED.id(),List.of(OTHER),"input","output"));
        String conflict=importJson(changedJob,Set.of());
        assertThrows(IllegalArgumentException.class,()->WorkRegistrationImport.merge(current,conflict));
        assertEquals(before,JSON.toJson(current));
    }
    @Test void importCannotInjectScheduleFieldsOrChangeUnrelatedFeatureSwitches() {
        Profile p=new Profile();String before=JSON.toJson(p);
        for(String invalid:List.of("{\"nextEligibleDay\":{}}","{\"schemaVersion\":1}","{\"enable\":[\"LOGGING\"]}","{\"enable\":[\"WINE\"]}","[]"))
            assertThrows(RuntimeException.class,()->WorkRegistrationImport.merge(p,invalid));
        assertEquals(before,JSON.toJson(p));
    }
    @Test void failedCrossJobImportRollsBackAllCandidateAdditions() {
        Profile p=profile(),addition=profile();String before=JSON.toJson(p);
        addition.artisanJobs.clear();addition.artisanJobs.put("duplicate",new ArtisanJob("duplicate",SEED.id(),List.of(MACHINE),"input","output"));
        assertThrows(IllegalArgumentException.class,()->WorkRegistrationImport.merge(p,importJson(addition,Set.of(Feature.SEED_MAKER))));
        assertEquals(before,JSON.toJson(p));assertFalse(p.enabled(Feature.SEED_MAKER));
    }
    @Test void artisanUseRequiresItsRegisteredExactMachineIngredientAndBatchCost() {
        SafetyWorld w=new SafetyWorld();Context c=w.context(Feature.SEED_MAKER);
        assertNull(SafetyPolicy.rejection(new Action.UseBlock(MACHINE,Action.Use.ARTISAN),c));
        w.held=new ItemData(SEED.inputId(),2,0,null,false,99);
        assertNotNull(SafetyPolicy.rejection(new Action.UseBlock(MACHINE,Action.Use.ARTISAN),c));
        w.held=item("minecraft:apple");
        assertNotNull(SafetyPolicy.rejection(new Action.UseBlock(MACHINE,Action.Use.ARTISAN),c));
        w.held=item(SEED.inputId());w.machineId="society:crystalarium";
        assertNotNull(SafetyPolicy.rejection(new Action.UseBlock(MACHINE,Action.Use.ARTISAN),c));
        w.machineId=SEED.machineId();
        assertNotNull(SafetyPolicy.rejection(new Action.UseBlock(OTHER,Action.Use.ARTISAN),c));
    }
    @Test void emptyHandCollectIsOnlyPermittedForAnActuallyMatureRegisteredMachine() {
        SafetyWorld w=new SafetyWorld();w.held=ItemData.EMPTY;
        Context c=w.context(Feature.SEED_MAKER);Action use=new Action.UseBlock(MACHINE,Action.Use.ARTISAN);
        assertNull(SafetyPolicy.rejection(use,c));w.mature=false;
        assertNotNull(SafetyPolicy.rejection(use,c));w.working=true;w.held=item(SEED.inputId());
        assertNotNull(SafetyPolicy.rejection(use,c));
    }
    @Test void artisanNativeDispatchIsStillExcludedByMissingChunkReachMenuAndCursor() {
        SafetyWorld w=new SafetyWorld();Context c=w.context(Feature.SEED_MAKER);Action use=new Action.UseBlock(MACHINE,Action.Use.ARTISAN);
        w.loaded=false;assertNotNull(SafetyPolicy.rejection(use,c));w.loaded=true;
        w.reachable=false;assertNotNull(SafetyPolicy.rejection(use,c));w.reachable=true;
        w.container=true;assertNotNull(SafetyPolicy.rejection(use,c));w.container=false;
        w.cursor=item(SEED.inputId());assertNotNull(SafetyPolicy.rejection(use,c));
    }
    @Test void oneShotScopeNeverGrantsAnotherArtisanRecipesMachineAction() {
        SafetyWorld w=new SafetyWorld();w.profile.enabled.put(Feature.SEED_MAKER,true);
        assertNotNull(SafetyPolicy.rejection(new Action.UseBlock(MACHINE,Action.Use.ARTISAN),w.context(Feature.CRYSTAL_COPY)));
        assertNotNull(SafetyPolicy.rejection(new Action.UseBlock(MACHINE,Action.Use.ARTISAN),w.context(Feature.STORAGE_SURVEY)));
    }
    private static final class SafetyWorld implements WorldAccess {
        final Profile profile=profile();ItemData held=item(SEED.inputId()),cursor=ItemData.EMPTY;
        boolean mature=true,working,loaded=true,reachable=true,container;String machineId=SEED.machineId();
        Context context(Feature feature) { SessionState s=new SessionState();s.oneShotFeature=feature;return new Context(this,null,null,profile,s); }
        public long tick(){return 100;}public long dayTime(){return 435*24000L+5000;}
        public PlayerState player(){return new PlayerState(4.5,64,.5,0,0,true,false,20,20,1,true,true);}
        public BlockData block(Pos pos){return new BlockData(pos,machineId,Map.of("mature",Boolean.toString(mature),"working",Boolean.toString(working)));}
        public boolean loaded(Pos p){return loaded;}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public boolean canInteract(Pos p,double reach){return reachable;}public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){return List.of(new ItemSlot(1,1,true,held));}
        public MenuData menu(){return new MenuData(container?1:0,0,inventory(),cursor,container);}
        public boolean mayPlace(int slot,ItemData item){return false;}
    }
}
