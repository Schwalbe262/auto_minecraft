package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ConsolidationSafetyTest {
    private static final int HOE = 4, MATERIAL = 5, SCRATCH = 6;
    private static final ItemData STONE = new ItemData("minecraft:stone", 64, 0, null, false, Integer.MAX_VALUE);
    private static final ItemData TOOL = new ItemData("minecraft:diamond_pickaxe", 1, 0, null, false, 1000);
    private static final ItemData GOLDEN_HOE = new ItemData("minecraft:golden_hoe", 1, 0, null, true, 200);

    @Test void forgedOutputScratchCannotBorrowTheActiveTomatoMaterialSlot() {
        Fixture f=new Fixture(); f.put(9,wine(1)); f.put(10,wine(50)); f.put(MATERIAL,tomato(52,0)); f.put(SCRATCH,ItemData.EMPTY);
        ProductionMergePlanner.Plan safe=f.productPlan(9); assertNull(f.rejection(safe));
        ProductionMergePlanner.Plan forged=new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,MATERIAL,List.of(10),f.snapshot());
        assertTrue(ProductionMergePlanner.matchesSnapshot(forged,f.items));
        assertFalse(ProductionMergePlanner.protectsProductionSlots(forged,HOE)); assertNotNull(f.rejection(forged));
        f.put(SCRATCH,tomato(52,0));
        ProductionMergePlanner.Plan occupied=new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,SCRATCH,List.of(10),f.snapshot());
        assertNotNull(f.rejection(occupied),"no occupied alternate ingredient or tool may become output scratch either");
    }

    @Test void canonicalProductionPlanPassesOnlyItsOwnPermissionGate() {
        Fixture f = winePair(); ProductionMergePlanner.Plan plan = f.productPlan(0);
        assertNull(f.rejection(plan));
        f.profile.enabled.put(Feature.WINE, false);
        assertNotNull(f.rejection(plan));
        f.session.oneShotFeature = Feature.WINE;
        assertNull(f.rejection(plan), "an explicitly selected disabled job retains its narrow temporary permission");
        f.session.oneShotFeature = Feature.PRESERVES;
        assertNotNull(f.rejection(plan));
        assertFalse(f.profile.enabled(Feature.WINE));
    }

    @Test void selectedGradeFallbackRemainsValidEvenWhenAnotherGradeHasAnEasierDirectMerge() {
        Fixture f = new Fixture();
        f.put(0, tomato(1, 0)); f.put(9, tomato(2, 0));
        f.put(20, tomato(2, 2)); f.put(21, tomato(2, 2)); f.put(MATERIAL, tomato(64, 1)); f.put(SCRATCH, ItemData.EMPTY);
        ProductionMergePlanner.Plan selected = ProductionMergePlanner.planTomatoes(f.items, Feature.WINE, HOE, 2, 20).orElseThrow();
        assertFalse(selected.direct()); assertEquals(2, selected.expectedItems().get(selected.sourceIndex()).quality());
        ProductionMergePlanner.Plan unrestricted = ProductionMergePlanner.planTomatoes(f.items, Feature.WINE, HOE, 20).orElseThrow();
        assertTrue(unrestricted.direct()); assertEquals(0, unrestricted.expectedItems().get(unrestricted.sourceIndex()).quality());
        assertNotEquals(selected, unrestricted, "this fixture reproduces the old unqualified revalidation bug");
        assertNull(f.rejection(selected));
    }

    @Test void forgedItemIdsAndProductionFeaturesCannotBroadenTheAction() {
        Fixture f = winePair(); ProductionMergePlanner.Plan valid = f.productPlan(0);
        for (String id : new String[]{ItemData.TOMATO, ItemData.PRESERVES, ItemData.ROTTEN, "minecraft:diamond", null}) {
            ProductionMergePlanner.Plan forged = new ProductionMergePlanner.Plan(valid.feature(), id, valid.sourceIndex(),
                    valid.scratchHotbar(), valid.destinations(), valid.expectedItems());
            assertNotNull(f.rejection(forged));
        }
        for (Feature feature : new Feature[]{Feature.HARVEST, Feature.SHIPPING, Feature.PRESERVES, null}) {
            ProductionMergePlanner.Plan forged = new ProductionMergePlanner.Plan(feature, valid.itemId(), valid.sourceIndex(),
                    valid.scratchHotbar(), valid.destinations(), valid.expectedItems());
            assertNotNull(f.rejection(forged));
        }
        assertNotNull(f.rejection(null));
    }

    @Test void forgedSourceDestinationScratchAndProductRepositionPlansAreRejected() {
        Fixture f = winePair(); f.put(9, ItemData.EMPTY); ProductionMergePlanner.Plan valid = f.productPlan(0);
        for (int source : new int[]{-1, 36, HOE}) {
            ProductionMergePlanner.Plan forged = new ProductionMergePlanner.Plan(Feature.WINE, ItemData.WINE, source,
                    -1, valid.destinations(), valid.expectedItems());
            assertNotNull(f.rejection(forged));
        }
        ProductionMergePlanner.Plan forgedScratch = new ProductionMergePlanner.Plan(Feature.WINE, ItemData.WINE, 0,
                SCRATCH, List.of(10), valid.expectedItems());
        assertNotNull(f.rejection(forgedScratch));
        ProductionMergePlanner.Plan protectedDestination = new ProductionMergePlanner.Plan(Feature.WINE, ItemData.WINE, 0,
                -1, List.of(HOE), valid.expectedItems());
        assertNotNull(f.rejection(protectedDestination));
        ProductionMergePlanner.Plan wineReposition = new ProductionMergePlanner.Plan(Feature.WINE, ItemData.WINE, 0,
                -1, List.of(9), valid.expectedItems());
        assertTrue(wineReposition.reposition()); assertNotNull(f.rejection(wineReposition));
    }

    @Test void staleOrIncompleteThirtySixSlotSnapshotNeverPasses() {
        Fixture f = winePair(); ProductionMergePlanner.Plan plan = f.productPlan(0);
        f.put(0, wine(11)); assertNotNull(f.rejection(plan));
        f.put(0, wine(10)); assertNull(f.rejection(plan));
        f.items.remove(35); assertNotNull(f.rejection(plan));
        f.items.add(new ItemSlot(35, 0, true, STONE)); assertNotNull(f.rejection(plan));
        f.items.set(35, new ItemSlot(35, 35, false, STONE)); assertNotNull(f.rejection(plan));
        f.items.set(35, new ItemSlot(35, 35, true, STONE)); f.items.add(new ItemSlot(40, 40, true, ItemData.EMPTY));
        assertNotNull(f.rejection(plan));
    }

    @Test void consolidationDoesNotMakeGeneralInventoryQuickMoveAvailable() {
        Fixture f = winePair(); assertNull(f.rejection(f.productPlan(0)));
        assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(0, 0), f.context()));
        f.container = true; f.menuId = 7;
        assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(0, 0), f.context()));
        f.menuSlots = List.of(new ItemSlot(0, -1, false, wine(10)));
        assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(7, 0), f.context()), "wine withdrawal remains forbidden");
    }

    @Test void aChangedEmptyOrCompatiblePartialHoeCannotBeHiddenInAFreshForgedSnapshot() {
        Fixture f = new Fixture(); f.put(20, wine(50));
        for (int index : List.of(0, 1, 2)) f.put(index, wine(40));
        ProductionMergePlanner.Plan original = f.productPlan(20); assertNull(f.rejection(original));
        for (ItemData protectedItem : List.of(ItemData.EMPTY, wine(10))) {
            f.put(HOE, protectedItem);
            ProductionMergePlanner.Plan forged = new ProductionMergePlanner.Plan(Feature.WINE, ItemData.WINE, original.sourceIndex(),
                    -1, original.destinations(), f.snapshot());
            assertTrue(ProductionMergePlanner.matchesSnapshot(forged, f.items));
            assertNotNull(f.rejection(forged), "current visible snapshots cannot grant permission to fill the hoe slot");
        }
    }

    @Test void changingTheProtectedHoeSettingInvalidatesAnOtherwiseCurrentPlan() {
        Fixture f = winePair(); ProductionMergePlanner.Plan plan = f.productPlan(0);
        f.profile.hoeHotbarSlot = 0;
        assertTrue(ProductionMergePlanner.matchesSnapshot(plan, f.items)); assertNotNull(f.rejection(plan));
    }

    @Test void ingredientScratchIsRejectedEvenWhenTheForgedPlanHasTheFreshSnapshot() {
        Fixture f = new Fixture(); f.put(9, wine(10)); f.put(10, wine(50)); f.put(MATERIAL, tomato(12, 0)); f.put(SCRATCH, ItemData.EMPTY);
        ProductionMergePlanner.Plan original = f.productPlan(9); assertFalse(original.direct()); assertNull(f.rejection(original));
        f.put(SCRATCH, tomato(52,0));
        ProductionMergePlanner.Plan forged = new ProductionMergePlanner.Plan(original.feature(), original.itemId(), original.sourceIndex(),
                original.scratchHotbar(), original.destinations(), f.snapshot());
        assertTrue(ProductionMergePlanner.matchesSnapshot(forged, f.items)); assertNotNull(f.rejection(forged));
    }

    @Test void cursorContainerDisconnectionAndRequiredFocusStillBlockConsolidation() {
        for (int reason = 0; reason < 4; reason++) {
            Fixture f = winePair(); ProductionMergePlanner.Plan plan = f.productPlan(0);
            switch (reason) {
                case 0 -> f.carried = wine(1);
                case 1 -> f.container = true;
                case 2 -> f.connected = false;
                case 3 -> { f.profile.allowBackground = false; f.focused = false; }
            }
            assertNotNull(f.rejection(plan));
        }
    }

    @Test void pendingOutputBlocksEvenAnOtherwiseValidActiveOwnersConsolidation() {
        Fixture f = winePair(); ProductionMergePlanner.Plan plan = f.productPlan(0);
        PendingMachineOutput output = new PendingMachineOutput(UUID.randomUUID().toString(), Feature.WINE,
                new Pos(1, 64, 2), 3, 12, 11, PendingMachineOutput.Phase.AWAITING_PICKUP);
        f.profile.pendingMachineOutputs.put(output.id(), output);
        f.session.liveMachineOutputs.add(output.id()); f.session.activeMachineOutputId = output.id();
        assertTrue(MachineOutputLedger.ownsActive(f.context(), Feature.WINE));
        assertNotNull(f.rejection(plan));
    }

    @Test void narrowTomatoBridgeIsAcceptedWithoutGrantingAProductBridge() {
        Fixture f = new Fixture();
        for (int index = 9; index < 36; index++) f.put(index, ItemData.EMPTY);
        f.put(1, tomato(2, 0)); f.put(2, tomato(1, 0));
        ProductionMergePlanner.Plan bridge = ProductionMergePlanner.planTomatoes(f.items, Feature.PRESERVES, HOE, 0, 1).orElseThrow();
        assertTrue(bridge.reposition()); assertNull(f.rejection(bridge));
        f.profile.enabled.put(Feature.PRESERVES, false); assertNotNull(f.rejection(bridge));
    }

    private static Fixture winePair() { Fixture f = new Fixture(); f.put(0, wine(10)); f.put(10, wine(50)); return f; }
    private static ItemData wine(int count) { return new ItemData(ItemData.WINE, count, 0, 12, false, 0); }
    private static ItemData tomato(int count, int quality) { return new ItemData(ItemData.TOMATO, count, quality, null, false, 0); }

    private static final class Fixture implements WorldAccess {
        final Profile profile = new Profile();
        final SessionState session = new SessionState();
        final List<ItemSlot> items = new ArrayList<>();
        List<ItemSlot> menuSlots;
        ItemData carried = ItemData.EMPTY;
        boolean connected = true, focused = true, container;
        int menuId;
        Fixture() {
            profile.hoeHotbarSlot = HOE;
            for (int index = 0; index < 36; index++) items.add(new ItemSlot(index, index, true, index == HOE ? GOLDEN_HOE : STONE));
        }
        void put(int index, ItemData item) { items.set(index, new ItemSlot(index, index, true, item)); }
        List<ItemData> snapshot() { return items.stream().map(ItemSlot::item).toList(); }
        ProductionMergePlanner.Plan productPlan(int preferred) { return ProductionMergePlanner.plan(items, Feature.WINE, profile.hoeHotbarSlot, preferred).orElseThrow(); }
        Context context() { return new Context(this, null, null, profile, session); }
        String rejection(ProductionMergePlanner.Plan plan) { return SafetyPolicy.rejection(new Action.ConsolidateInventory(plan), context()); }
        @Override public long tick() { return 100; }
        @Override public long dayTime() { return 3 * 24000 + 1000; }
        @Override public PlayerState player() { return new PlayerState(.5, 64, .5, 0, 0, true, false, 20, 20, 0, connected, focused); }
        @Override public BlockData block(Pos pos) { return new BlockData(pos, "minecraft:air", Map.of()); }
        @Override public boolean loaded(Pos pos) { return true; }
        @Override public boolean canStand(Pos feet) { return true; }
        @Override public boolean canTraverse(Pos from, Pos to) { return true; }
        @Override public List<BlockData> scan(Pos center, int radius, int vertical) { return List.of(); }
        @Override public List<ItemSlot> inventory() { return items; }
        @Override public MenuData menu() { return new MenuData(menuId, 0, menuSlots == null ? items : menuSlots, carried, container); }
        @Override public boolean mayPlace(int slot, ItemData item) { return true; }
    }
}
