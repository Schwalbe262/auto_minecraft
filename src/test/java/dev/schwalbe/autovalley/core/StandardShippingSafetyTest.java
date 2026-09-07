package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class StandardShippingSafetyTest {
    private static ItemData product(String id) { return new ItemData(id,4,0,null,false,999); }

    @Test void exactPreservesAndPineTarHaveTheSameEnabledAndOneShotPermission() {
        for (String id:List.of(ItemData.PRESERVES,ItemData.PINE_TAR)) {
            Fixture f=new Fixture(); f.item=product(id); Action move=new Action.QuickMove(7,0);
            assertTrue(f.item.standardShippingProduct()); assertNull(SafetyPolicy.rejection(move,f.context()));
            f.profile.enabled.put(Feature.SHIPPING,false);
            assertNotNull(SafetyPolicy.rejection(move,f.context()));
            f.session.oneShotFeature=Feature.SHIPPING;
            assertNull(SafetyPolicy.rejection(move,f.context()));
            assertFalse(f.profile.enabled(Feature.SHIPPING),"temporary permission must not enable the saved feature");
            f.session.oneShotFeature=Feature.WINE;
            assertNotNull(SafetyPolicy.rejection(move,f.context()));
            f.session.oneShotFeature=Feature.WINE_SURPLUS_SHIPPING;
            assertNotNull(SafetyPolicy.rejection(move,f.context()));
        }
    }

    @Test void standardProductsCannotBeWithdrawnFromContainersOrTakenFromOffhandAndArmor() {
        for (String id:List.of(ItemData.PRESERVES,ItemData.PINE_TAR)) {
            Fixture f=new Fixture(); f.item=product(id); Action move=new Action.QuickMove(7,0);
            f.playerSlot=false; assertNotNull(SafetyPolicy.rejection(move,f.context()));
            f.playerSlot=true;
            for (int index:new int[]{-1,36,40}) {
                f.inventoryIndex=index; assertNotNull(SafetyPolicy.rejection(move,f.context()));
            }
            f.inventoryIndex=35; assertNull(SafetyPolicy.rejection(move,f.context()));
        }
    }

    @Test void treeProductTagsNameMatchesAndBucketsDoNotExpandTheExplicitShippingAllowList() {
        for (String id:List.of("society:oak_resin","society:maple_syrup","society:mystic_syrup",
            "society:pine_tar_bucket","othermod:pine_tar")) {
            Fixture f=new Fixture(); f.item=product(id);
            assertFalse(f.item.standardShippingProduct());
            assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(7,0),f.context()));
        }
        assertFalse(ItemData.EMPTY.standardShippingProduct());
    }

    @Test void standardShippingDoesNotGiveWineAReservePermit() {
        Fixture f=new Fixture(); f.item=new ItemData(ItemData.WINE,4,0,8,false,999);
        f.session.oneShotFeature=Feature.SHIPPING;
        assertFalse(f.item.standardShippingProduct());
        assertFalse(WineSaleRules.permitted(f.item,f.context()));
        assertTrue(f.session.wineSalePermits.isEmpty());
    }

    @Test void surveyCanOpenRegisteredCandidatesOnlyWhileSelectedAndNeverMoveInventory() {
        Fixture f=new Fixture(); Pos candidate=new Pos(0,64,1);
        f.profile.pois.add(new Poi(candidate,PoiKind.STORAGE_CANDIDATE,"Candidate",null));
        f.container=false;
        Action open=new Action.UseBlock(candidate,Action.Use.OPEN_CONTAINER);
        assertNotNull(SafetyPolicy.rejection(open,f.context()));
        f.session.oneShotFeature=Feature.SHIPPING;
        assertNotNull(SafetyPolicy.rejection(open,f.context()));
        f.session.oneShotFeature=Feature.STORAGE_SURVEY;
        assertNull(SafetyPolicy.rejection(open,f.context()));
        f.container=true; f.item=product(ItemData.PINE_TAR);
        for (Action mutation:List.of(new Action.QuickMove(7,0),new Action.SwapHotbar(10,8),
            new Action.ConsolidateInventory(null),new Action.ThrowRotten(7,0,candidate)))
            assertEquals("Storage survey is read-only; inventory changes are not permitted",SafetyPolicy.rejection(mutation,f.context()));
        assertNull(SafetyPolicy.rejection(new Action.CloseContainer(7),f.context()));
        f.item=product(ItemData.TOMATO);
        assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(7,0),f.context()));
    }

    @Test void surveyStillOpensExistingRegisteredStorageButNotAnUnregisteredContainer() {
        Fixture f=new Fixture(); f.container=false; f.session.oneShotFeature=Feature.STORAGE_SURVEY;
        Pos target=new Pos(0,64,1);
        Action open=new Action.UseBlock(target,Action.Use.OPEN_CONTAINER);
        assertNotNull(SafetyPolicy.rejection(open,f.context()));
        for (PoiKind kind:List.of(PoiKind.TOMATO_CHEST,PoiKind.WINE_CHEST)) {
            f.profile.pois.clear(); f.profile.pois.add(new Poi(target,kind,"Known storage",0));
            assertNull(SafetyPolicy.rejection(open,f.context()));
        }
    }

    @Test void surveyNeverOpensBanksShopsOrShippingMachinesEvenIfTheyWereMisregisteredAsStorage() {
        Fixture f=new Fixture(); f.container=false; f.session.oneShotFeature=Feature.STORAGE_SURVEY;
        Pos target=new Pos(0,64,1); Action open=new Action.UseBlock(target,Action.Use.OPEN_CONTAINER);
        for (PoiKind kind:List.of(PoiKind.STORAGE_CANDIDATE,PoiKind.TOMATO_CHEST,PoiKind.WINE_CHEST,PoiKind.SHIPPING_BIN)) {
            f.profile.pois.clear(); f.profile.pois.add(new Poi(target,kind,"Target",0));
            for (String id:List.of("numismatics:bank_terminal","numismatics:vendor","shippingbin:smart_shipping_bin",
                "society:preserves_jar","minecraft:ender_chest","othermod:barrel")) {
                f.blockId=id;
                assertEquals("Storage survey opens only ordinary barrels and chests",SafetyPolicy.rejection(open,f.context()));
            }
        }
        f.profile.pois.clear(); f.profile.pois.add(new Poi(target,PoiKind.STORAGE_CANDIDATE,"Target",null));
        for (String id:List.of("minecraft:barrel","minecraft:chest","minecraft:trapped_chest")) {
            f.blockId=id; assertNull(SafetyPolicy.rejection(open,f.context()));
        }
        f.blockContainer=false; assertNotNull(SafetyPolicy.rejection(open,f.context()));
    }

    @Test void surveyDoesNotOpenAShippingRegistrationEvenWhenItPointsAtAnOrdinaryBarrel() {
        Fixture f=new Fixture(); f.container=false; f.session.oneShotFeature=Feature.STORAGE_SURVEY;
        Pos target=new Pos(0,64,1);
        f.profile.pois.add(new Poi(target,PoiKind.SHIPPING_BIN,"Not a survey target",null));
        assertNotNull(SafetyPolicy.rejection(new Action.UseBlock(target,Action.Use.OPEN_CONTAINER),f.context()));
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();
        final SessionState session=new SessionState();
        ItemData item=ItemData.EMPTY;
        boolean playerSlot=true;
        boolean container=true;
        String blockId="minecraft:barrel";
        boolean blockContainer=true;
        int inventoryIndex=0;
        Context context() { return new Context(this,this,this,profile,session); }
        @Override public long tick() { return 100; }
        @Override public long dayTime() { return 13000; }
        @Override public Integer wineYear() { return 8; }
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,true,true); }
        @Override public BlockData block(Pos p) { return new BlockData(p,blockId,Map.of("container",Boolean.toString(blockContainer))); }
        @Override public boolean loaded(Pos p) { return true; }
        @Override public boolean canStand(Pos p) { return true; }
        @Override public boolean canTraverse(Pos from,Pos to) { return true; }
        @Override public List<BlockData> scan(Pos center,int horizontal,int vertical) { return List.of(); }
        @Override public List<ItemSlot> inventory() { return List.of(new ItemSlot(0,0,true,item)); }
        @Override public MenuData menu() { return new MenuData(7,0,List.of(new ItemSlot(0,inventoryIndex,playerSlot,item)),ItemData.EMPTY,container); }
        @Override public boolean mayPlace(int slot,ItemData candidate) { return true; }
        @Override public boolean busy() { return false; }
        @Override public long submit(Action action) { throw new AssertionError("Safety tests must not dispatch actions"); }
        @Override public ActionOutcome outcome(long ticket) { throw new AssertionError("No action was dispatched"); }
        @Override public void move(Movement movement) { throw new AssertionError("No movement is permitted"); }
        @Override public void stopMovement() { }
        @Override public void cancel() { }
        @Override public Result moveTo(Pos pos,double reach,Context context) { throw new AssertionError("No navigation is permitted"); }
        @Override public void reset() { }
    }
}
