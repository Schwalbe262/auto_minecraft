package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Generic groups add ordinary storage authority, never shipping, shops or reserved chest roles. */
class CommodityAuthorityTest {
    private static final Pos FIRST=new Pos(0,64,0),SECOND=new Pos(1,64,0),OTHER=new Pos(8,64,0);
    private static final String FRUIT="society:ancient_fruit",SEED="society:ancient_fruit_seed",JADE="society:jade";

    @Test void genericGroupCannotReplaceAnyExistingReservedPoiRole() {
        for(PoiKind kind:PoiKind.values()) {
            Fixture f=new Fixture(); f.store("fruit",Set.of(FRUIT),FIRST);
            f.profile.pois.add(new Poi(FIRST,kind,"existing",kind==PoiKind.WINE_CHEST ? 7 : null));
            if(kind==PoiKind.STORAGE_CANDIDATE) assertDoesNotThrow(()->AdditionalWorkRules.validate(f.profile));
            else assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(f.profile),kind.toString());
        }
    }
    @Test void ordinaryUnreservedOrCandidateContainersCanBeExplicitlyGrouped() {
        Fixture f=new Fixture(); f.store("fruit",Set.of(FRUIT),FIRST,SECOND);
        assertDoesNotThrow(()->AdditionalWorkRules.validate(f.profile));
        f.profile.pois.add(new Poi(FIRST,PoiKind.STORAGE_CANDIDATE,"candidate",null));
        assertDoesNotThrow(()->AdditionalWorkRules.validate(f.profile));
        assertTrue(CommodityStorageRules.unreservedContainer(f.profile,List.of(FIRST,SECOND)));
    }
    @Test void oppositeReservedHalfOfADoubleChestCannotBorrowTheGenericCanonicalHalfsAuthority() {
        Fixture f=new Fixture(); f.store("fruit",Set.of(FRUIT,ItemData.WINE),FIRST);
        f.profile.pois.add(new Poi(SECOND,PoiKind.WINE_CHEST,"existing single wine chest",7));
        // Static registrations cannot know that the native world later paired these cells.
        assertDoesNotThrow(()->AdditionalWorkRules.validate(f.profile));
        f.enable(Feature.COMMODITY_STORAGE);
        assertTrue(CommodityStorageRules.openAllowed(f.context,FIRST));
        assertTrue(CommodityStorageRules.depositAllowed(f.context,FIRST,new ItemData(ItemData.WINE,1,0,99,false,100)));
        assertFalse(CommodityStorageRules.unreservedContainer(f.profile,List.of(FIRST,SECOND)),
            "Native OPEN and QUICK_MOVE must check the entire reciprocal chest shape, not only its canonical cell");
        assertTrue(CommodityStorageRules.unreservedContainer(f.profile,List.of(FIRST)),"An unrelated physical barrel stays independent");
    }
    @Test void aCandidateCanonicalHalfDoesNotMaskTheOtherHalfsReservedRole() {
        for(PoiKind kind:PoiKind.values()) if(kind!=PoiKind.STORAGE_CANDIDATE) {
            Fixture f=new Fixture();
            f.profile.pois.add(new Poi(FIRST,PoiKind.STORAGE_CANDIDATE,"canonical",null));
            f.profile.pois.add(new Poi(SECOND,kind,"reserved",kind==PoiKind.WINE_CHEST ? 7 : null));
            assertFalse(CommodityStorageRules.unreservedContainer(f.profile,List.of(FIRST,SECOND)),kind.toString());
            assertFalse(CommodityStorageRules.unreservedContainer(f.profile,List.of(SECOND,FIRST)),"Half order cannot change authority");
        }
    }
    @Test void missingMalformedOrOverbroadNativeShapeCannotClaimAnUnreservedContainer() {
        Fixture f=new Fixture();
        assertFalse(CommodityStorageRules.unreservedContainer(f.profile,null));
        assertFalse(CommodityStorageRules.unreservedContainer(f.profile,List.of()));
        assertFalse(CommodityStorageRules.unreservedContainer(f.profile,Arrays.asList(FIRST,null)));
        assertFalse(CommodityStorageRules.unreservedContainer(f.profile,List.of(FIRST,SECOND,OTHER)));
        assertFalse(CommodityStorageRules.unreservedContainer(f.profile,List.of(new Pos(Integer.MAX_VALUE,64,0))));
        assertFalse(CommodityStorageRules.unreservedContainer(null,List.of(FIRST)));
        f.profile.pois.add(null); assertFalse(CommodityStorageRules.unreservedContainer(f.profile,List.of(FIRST)));
    }
    @Test void genericOpenAcceptsOnlyNativeOrdinaryStorageNotShippingOrShopOrMachineBlocks() {
        Fixture f=new Fixture(); f.store("fruit",Set.of(FRUIT),FIRST); f.enable(Feature.COMMODITY_STORAGE);
        for(String id:List.of("minecraft:barrel","minecraft:chest","minecraft:trapped_chest")) {
            f.blockId=id; assertNull(f.rejectOpen(),id);
        }
        for(String id:List.of("shippingbin:smart_shipping_bin","lightmanscurrency:trader","example:shop",
            "minecraft:hopper","society:seed_maker","minecraft:crafting_table")) {
            f.blockId=id; assertNotNull(f.rejectOpen(),id);
        }
        f.blockId="minecraft:barrel"; f.containerFlag=false; assertNotNull(f.rejectOpen());
    }
    @Test void genericFeatureAndOneShotScopeRemainExplicit() {
        Fixture f=new Fixture(); f.store("fruit",Set.of(FRUIT),FIRST);
        assertFalse(CommodityStorageRules.openAllowed(f.context,FIRST)); assertNotNull(f.rejectOpen());
        f.enable(Feature.COMMODITY_STORAGE); assertNull(f.rejectOpen());
        f.session.oneShotFeature=Feature.WINE; assertNotNull(f.rejectOpen());
        assertFalse(CommodityStorageRules.knownItem(f.context,item(FRUIT)));
        f.profile.enabled.put(Feature.COMMODITY_STORAGE,false); f.session.oneShotFeature=Feature.COMMODITY_STORAGE;
        assertNull(f.rejectOpen()); assertFalse(f.profile.enabled(Feature.COMMODITY_STORAGE));
    }
    @Test void aMismatchedGroupMapKeyCannotAuthorizeItsContainer() {
        Fixture f=new Fixture(); f.store("fruit",Set.of(FRUIT),FIRST);
        CommodityStore store=f.profile.commodityStores.remove("fruit"); f.profile.commodityStores.put("different",store);
        f.enable(Feature.COMMODITY_STORAGE);
        assertThrows(IllegalArgumentException.class,()->AdditionalWorkRules.validate(f.profile));
        assertFalse(CommodityStorageRules.openAllowed(f.context,FIRST));
        assertFalse(CommodityStorageRules.depositAllowed(f.context,FIRST,item(FRUIT)));
    }
    @Test void genericDepositNeverGrantsWithdrawalAndArtisanWithdrawsOnlyItsConfiguredIngredient() {
        Fixture f=new Fixture(); f.seedJob(); f.enable(Feature.COMMODITY_STORAGE);
        assertTrue(CommodityStorageRules.depositAllowed(f.context,FIRST,item(FRUIT)));
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,FIRST,item(FRUIT)));
        f.enable(Feature.SEED_MAKER);
        assertTrue(CommodityStorageRules.withdrawalAllowed(f.context,FIRST,item(FRUIT)));
        for(String unrelated:List.of(SEED,JADE,ItemData.WINE,"minecraft:diamond"))
            assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,FIRST,item(unrelated)),unrelated);
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,OTHER,item(FRUIT)),"An output group cannot become an input source");
        f.session.oneShotFeature=Feature.COMMODITY_STORAGE;
        assertFalse(CommodityStorageRules.withdrawalAllowed(f.context,FIRST,item(FRUIT)),"An enabled neighbour cannot leak into a one-shot");
        f.session.oneShotFeature=Feature.SEED_MAKER; f.profile.enabled.put(Feature.SEED_MAKER,false);
        assertTrue(CommodityStorageRules.withdrawalAllowed(f.context,FIRST,item(FRUIT)));
    }
    @Test void seedMakerScopeCannotOpenAnUnrelatedCommodityGroup() {
        Fixture f=new Fixture(); f.seedJob(); f.store("jade",Set.of(JADE),SECOND); f.session.oneShotFeature=Feature.SEED_MAKER;
        assertTrue(CommodityStorageRules.openAllowed(f.context,FIRST)); assertTrue(CommodityStorageRules.openAllowed(f.context,OTHER));
        assertFalse(CommodityStorageRules.openAllowed(f.context,SECOND));
        assertFalse(CommodityStorageRules.depositAllowed(f.context,SECOND,item(JADE)));
    }
    @Test void shippingAndLoggingItemGatesAreNotBypassedByListingTheirIdsAsGenericCommodities() {
        Fixture f=new Fixture(); f.store("products",Set.of(ItemData.PINE_TAR,ItemData.PRESERVES,LoggingRules.LOG,LoggingRules.BERRY),FIRST);
        f.enable(Feature.COMMODITY_STORAGE); f.menuOpen=true;
        for(String id:List.of(ItemData.PINE_TAR,ItemData.PRESERVES,LoggingRules.LOG,LoggingRules.BERRY)) {
            f.source=new ItemSlot(0,9,true,item(id)); assertNotNull(f.rejectMove(),id);
        }
        f.enable(Feature.SHIPPING); f.source=new ItemSlot(0,9,true,item(ItemData.PINE_TAR)); assertNull(f.rejectMove());
        f.session.oneShotFeature=Feature.COMMODITY_STORAGE; assertNotNull(f.rejectMove());
        f.session.oneShotFeature=Feature.STORAGE_SURVEY; assertNotNull(f.rejectMove());
    }
    @Test void genericStorageStillRejectsUnknownItemsAndSurveyTransfers() {
        Fixture f=new Fixture(); f.store("fruit",Set.of(FRUIT),FIRST); f.enable(Feature.COMMODITY_STORAGE); f.menuOpen=true;
        f.source=new ItemSlot(0,9,true,item("minecraft:diamond")); assertNotNull(f.rejectMove());
        f.source=new ItemSlot(0,9,true,item(FRUIT)); assertNull(f.rejectMove());
        f.session.oneShotFeature=Feature.STORAGE_SURVEY; assertNotNull(f.rejectMove());
    }
    private static ItemData item(String id) { return new ItemData(id,1,0,null,false,100); }
    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final Context context=new Context(this,this,null,profile,session);
        String blockId="minecraft:barrel"; boolean containerFlag=true,menuOpen;
        ItemSlot source=new ItemSlot(0,9,true,item(FRUIT));
        Fixture() { for(Feature f:Feature.values()) profile.enabled.put(f,false); }
        void enable(Feature feature) { profile.enabled.put(feature,true); }
        void store(String id,Set<String> items,Pos...containers) { profile.commodityStores.put(id,new CommodityStore(id,id,items,List.of(containers))); }
        void seedJob() {
            store("input",Set.of(FRUIT,SEED,JADE,ItemData.WINE,"minecraft:diamond"),FIRST);
            store("output",Set.of(SEED,FRUIT),OTHER);
            profile.artisanJobs.put("seed",new ArtisanJob("seed",ArtisanRecipe.ANCIENT_SEED.id(),List.of(new Pos(4,64,0)),"input","output"));
            AdditionalWorkRules.validate(profile);
        }
        String rejectOpen() { return SafetyPolicy.rejection(new Action.UseBlock(FIRST,Action.Use.OPEN_CONTAINER),context); }
        String rejectMove() { return SafetyPolicy.rejection(new Action.QuickMove(1,0),context); }
        public long tick() { return 0; } public long dayTime() { return 0; }
        public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,4,true,true); }
        public BlockData block(Pos pos) { return new BlockData(pos,blockId,Map.of("container",Boolean.toString(containerFlag))); }
        public boolean loaded(Pos p) { return true; } public boolean canStand(Pos p) { return true; }
        public boolean canTraverse(Pos a,Pos b) { return false; } public boolean canInteract(Pos p,double reach) { return true; }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(new ItemSlot(0,4,true,ItemData.EMPTY)); }
        public MenuData menu() { return new MenuData(menuOpen?1:0,0,List.of(source),ItemData.EMPTY,menuOpen); }
        public boolean mayPlace(int slot,ItemData item) { return true; } public boolean busy() { return false; }
        public long submit(Action action) { throw new AssertionError("Read-only authority test"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError("No invented receipt"); }
        public void move(Movement movement) { throw new AssertionError("No game movement"); }
        public void stopMovement() { } public void cancel() { }
    }
}
