package dev.schwalbe.autovalley.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Manual originals stay outside automation authority, including legacy jade registrations. */
class CrystalCollectOnlyRulesTest {
    private static final Pos MACHINE=new Pos(4,64,0),SOURCE=new Pos(1,64,0),OUTPUT=new Pos(2,64,0);
    private static final String JADE="society:jade";
    private static ItemData item(String id) {return new ItemData(id,64,0,null,false,100);}
    private static Context context(Feature once) {
        Profile profile=new Profile();
        profile.artisanJobs.put("crystal",new ArtisanJob("crystal",ArtisanRecipe.JADE_CRYSTAL.id(),List.of(MACHINE),"input","output"));
        profile.commodityStores.put("input",new CommodityStore("input","Input",Set.of(JADE,"society:ruby"),List.of(SOURCE)));
        profile.commodityStores.put("output",new CommodityStore("output","Output",Set.of(JADE,"society:ruby"),List.of(OUTPUT)));
        SessionState session=new SessionState();session.oneShotFeature=once;
        return new Context(null,null,null,profile,session);
    }
    private static BlockData block(Map<String,String> properties) {
        return new BlockData(MACHINE,"society:crystalarium",properties);
    }
    private static BlockData mature() {return block(Map.of("mature","true","working","false"));}

    @Test void matureLegacyJadeMachineAllowsOnlyEmptyHandCollection() {
        Context c=context(Feature.CRYSTAL_COPY);
        assertNull(ArtisanRules.rejection(c,MACHINE,mature(),ItemData.EMPTY));
        for(String id:List.of(JADE,"society:ruby","society:pristine_jade","minecraft:diamond","minecraft:netherite_axe"))
            assertNotNull(ArtisanRules.rejection(c,MACHINE,mature(),item(id)),id);
    }

    @Test void idleWorkingInconsistentOrUnknownStateCannotAuthorizeACollection() {
        Context c=context(Feature.CRYSTAL_COPY);
        for(Map<String,String> states:List.of(Map.<String,String>of(),Map.of("mature","true"),Map.of("working","false"),
            Map.of("mature","false","working","false"),Map.of("mature","false","working","true"),
            Map.of("mature","true","working","true"),Map.of("mature","true","working","unknown"),
            Map.of("mature","TRUE","working","false"))) {
            assertNotNull(ArtisanRules.rejection(c,MACHINE,block(states),ItemData.EMPTY),states.toString());
            assertNotNull(ArtisanRules.rejection(c,MACHINE,block(states),item(JADE)),states.toString());
        }
        assertNotNull(ArtisanRules.rejection(c,MACHINE,block(null),ItemData.EMPTY));
        assertNotNull(ArtisanRules.rejection(c,MACHINE,null,ItemData.EMPTY));
        assertNotNull(ArtisanRules.rejection(c,MACHINE,mature(),null));
    }

    @Test void emptyHandDoesNotBypassRegistrationFeatureOrExactMachineGuards() {
        Context c=context(null);
        assertNotNull(ArtisanRules.rejection(c,MACHINE,mature(),ItemData.EMPTY));
        c.profile().enabled.put(Feature.CRYSTAL_COPY,true);
        assertNull(ArtisanRules.rejection(c,MACHINE,mature(),ItemData.EMPTY));
        assertNotNull(ArtisanRules.rejection(c,OUTPUT,mature(),ItemData.EMPTY));
        assertNotNull(ArtisanRules.rejection(c,MACHINE,new BlockData(MACHINE,"society:seed_maker",mature().properties()),ItemData.EMPTY));
        c.session().oneShotFeature=Feature.SEED_MAKER;
        assertNotNull(ArtisanRules.rejection(c,MACHINE,mature(),ItemData.EMPTY));
    }

    @Test void legacyCrystalInputStoreNeverAuthorizesAutomaticOriginalWithdrawal() {
        for(Feature once:java.util.Arrays.asList(null,Feature.CRYSTAL_COPY,Feature.COMMODITY_STORAGE)) {
            Context c=context(once);c.profile().enabled.put(Feature.CRYSTAL_COPY,true);c.profile().enabled.put(Feature.COMMODITY_STORAGE,true);
            Map<String,Long> dates=new HashMap<>(c.profile().nextEligibleDay);
            for(String id:List.of(JADE,"society:ruby","society:pristine_jade")) {
                assertFalse(CommodityStorageRules.withdrawalAllowed(c,SOURCE,item(id)),id);
                assertFalse(CommodityStorageRules.withdrawalAllowed(c,OUTPUT,item(id)),id);
            }
            assertEquals(dates,c.profile().nextEligibleDay);
        }
    }

    @Test void crystalCollectionRetainsItsExistingDeclaredDepositAuthority() {
        Context c=context(Feature.CRYSTAL_COPY);
        assertTrue(CommodityStorageRules.depositAllowed(c,OUTPUT,item(JADE)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(c,OUTPUT,item(JADE)));
        assertFalse(CommodityStorageRules.depositAllowed(c,OUTPUT,item("minecraft:diamond")));
    }

    @Test void crystalJobAloneCannotOpenOrDepositIntoItsLegacyInputOnlyStore() {
        for(ArtisanRecipe recipe:List.of(ArtisanRecipe.JADE_CRYSTAL,ArtisanRecipe.CRYSTAL_COLLECTION)) {
            Context c=context(Feature.CRYSTAL_COPY);
            c.profile().artisanJobs.put("crystal",new ArtisanJob("crystal",recipe.id(),List.of(MACHINE),"input","output"));
            assertFalse(CommodityStorageRules.openAllowed(c,SOURCE),recipe.id());
            assertFalse(CommodityStorageRules.depositAllowed(c,SOURCE,item(JADE)),recipe.id());
            assertTrue(CommodityStorageRules.openAllowed(c,OUTPUT),recipe.id());
            assertTrue(CommodityStorageRules.depositAllowed(c,OUTPUT,item("society:ruby")),recipe.id());
            c.profile().enabled.put(Feature.CRYSTAL_COPY,true);c.session().oneShotFeature=null;
            assertFalse(CommodityStorageRules.openAllowed(c,SOURCE),recipe.id());
            c.profile().enabled.put(Feature.COMMODITY_STORAGE,true);
            assertTrue(CommodityStorageRules.openAllowed(c,SOURCE),"An independently enabled storage routine keeps its authority");
            assertTrue(CommodityStorageRules.depositAllowed(c,SOURCE,item(JADE)));
            assertFalse(CommodityStorageRules.withdrawalAllowed(c,SOURCE,item(JADE)));
            c.session().oneShotFeature=Feature.CRYSTAL_COPY;
            assertFalse(CommodityStorageRules.openAllowed(c,SOURCE),"One-shot collection cannot borrow storage's independent scope");
            c.profile().artisanJobs.put("crystal",new ArtisanJob("crystal",recipe.id(),List.of(MACHINE),"input","input"));
            assertTrue(CommodityStorageRules.openAllowed(c,SOURCE),"A shared input/output store is allowed by its output role");
            assertFalse(CommodityStorageRules.withdrawalAllowed(c,SOURCE,item(JADE)));
        }
    }

    @Test void seedMakerStillAllowsItsRecipeFeedAndIngredientWithdrawal() {
        Context c=context(Feature.SEED_MAKER);ArtisanRecipe seed=ArtisanRecipe.ANCIENT_SEED;
        c.profile().artisanJobs.clear();
        c.profile().artisanJobs.put("seed",new ArtisanJob("seed",seed.id(),List.of(MACHINE),"input","output"));
        c.profile().commodityStores.put("input",new CommodityStore("input","Input",Set.of(seed.inputId()),List.of(SOURCE)));
        BlockData idle=new BlockData(MACHINE,seed.machineId(),Map.of("mature","false","working","false"));
        assertNull(ArtisanRules.rejection(c,MACHINE,idle,item(seed.inputId())));
        assertTrue(CommodityStorageRules.openAllowed(c,SOURCE));
        assertTrue(CommodityStorageRules.openAllowed(c,OUTPUT));
        assertTrue(CommodityStorageRules.withdrawalAllowed(c,SOURCE,item(seed.inputId())));
        c.session().oneShotFeature=Feature.CRYSTAL_COPY;
        assertNotNull(ArtisanRules.rejection(c,MACHINE,idle,item(seed.inputId())));
        assertFalse(CommodityStorageRules.withdrawalAllowed(c,SOURCE,item(seed.inputId())));
    }
}
