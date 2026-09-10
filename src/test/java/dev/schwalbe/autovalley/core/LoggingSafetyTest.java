package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure safety predicates only: no mining, packet dispatch, inventory changes or native client. */
class LoggingSafetyTest {
    private static final Pos BASE = new Pos(0, 64, 0), TABLE = new Pos(3, 64, 0);

    @Test void defaultOffNeverGrantsLoggingPermission() {
        Fixture f = new Fixture(); f.approveTree(); f.profile.enabled.put(Feature.LOGGING, false);
        assertNotNull(f.reject(new Action.ChopTree(BASE)));
        assertNotNull(f.reject(new Action.PlantSapling(BASE)));
        f.world.container = true; f.world.crafting = true;
        assertNotNull(f.reject(new Action.CraftFireLogs(TABLE)));
    }

    @Test void otherOneShotCannotBorrowEnabledLoggingButExplicitLoggingCanRunWhileToggleOff() {
        Fixture f = new Fixture(); f.approveTree();
        f.session.oneShotFeature = Feature.WINE;
        assertNotNull(f.reject(new Action.ChopTree(BASE)));
        f.profile.enabled.put(Feature.LOGGING, false); f.session.oneShotFeature = Feature.LOGGING;
        assertNull(f.reject(new Action.ChopTree(BASE)));
        assertFalse(f.profile.enabled(Feature.LOGGING), "temporary permission must not change saved settings");
    }

    @Test void nativeWorldDefaultsFailClosedEvenForARegisteredSpruceAndCorrectAxe() {
        Fixture f = new Fixture();
        BasicWorld unknown = new BasicWorld(); unknown.put(2, item(LoggingRules.AXE, 1, 100)); unknown.blockAt(BASE, LoggingRules.LOG);
        Context c = new Context(unknown, f.actions, null, f.profile, f.session);
        assertNotNull(SafetyPolicy.rejection(new Action.ChopTree(BASE), c));
        assertFalse(unknown.loggingAxe(2));
        assertNotNull(unknown.loggingTreeRejection(BASE, f.profile.loggingPlots));
        assertFalse(unknown.canPlantLoggingSapling(BASE)); assertFalse(unknown.loggingCraftingMenu());
    }

    @Test void approvedSpruceOrChoppedStumpRequiresBothNativeAxeAndWholeTreeProof() {
        Fixture f = new Fixture();
        assertNotNull(f.reject(new Action.ChopTree(BASE)));
        f.world.axe = true;
        assertNotNull(f.reject(new Action.ChopTree(BASE)), "an exact item ID is not whole-tree proof");
        f.world.treeError = null;
        assertNull(f.reject(new Action.ChopTree(BASE)));
        assertEquals(BASE, f.world.proofTarget); assertEquals(f.profile.loggingPlots, f.world.proofPlots);
        f.world.blockAt(BASE, LoggingRules.CHOPPED_LOG);
        assertNull(f.reject(new Action.ChopTree(BASE)));
        f.world.treeError = "Native proof found a building or another tree outside the registered trunk";
        assertNotNull(f.reject(new Action.ChopTree(BASE)), "building logs must not inherit tree authority");
    }

    @Test void onlyRegisteredBaseCellsCanStartAChopAndCropsOrSaplingsNeverQualify() {
        Fixture f = new Fixture(); f.approveTree();
        for (Pos outside : List.of(BASE.offset(0, 1, 0), BASE.offset(2, 0, 0), BASE.offset(-1, 0, 0))) {
            f.world.blockAt(outside, LoggingRules.LOG);
            assertNotNull(f.reject(new Action.ChopTree(outside)));
        }
        for (String id : List.of(LoggingRules.SAPLING, "farmersdelight:tomatoes", "minecraft:oak_log", "minecraft:stone")) {
            f.world.blockAt(BASE, id); assertNotNull(f.reject(new Action.ChopTree(BASE)));
        }
        f.world.blocks.put(BASE, null); assertNotNull(f.reject(new Action.ChopTree(BASE)));
        assertNotNull(f.reject(new Action.ChopTree(null)));
    }

    @Test void wrongToolWrongSlotAndNearBrokenAxeCannotChop() {
        Fixture f = new Fixture(); f.approveTree();
        for (String id : List.of("minecraft:diamond_axe", "minecraft:netherite_hoe", LoggingRules.LOG)) {
            f.world.put(2, item(id, 1, 100)); assertNotNull(f.reject(new Action.ChopTree(BASE)));
        }
        for (int durability : new int[] {0, 1}) {
            f.world.put(2, item(LoggingRules.AXE, 1, durability)); assertNotNull(f.reject(new Action.ChopTree(BASE)));
        }
        f.world.put(2, item(LoggingRules.AXE, 1, 2)); assertNull(f.reject(new Action.ChopTree(BASE)));
        f.profile.loggingAxeHotbarSlot = -1; assertNotNull(f.reject(new Action.ChopTree(BASE)));
        f.profile.loggingAxeHotbarSlot = 2; f.world.selected = 3; f.world.put(3, item(LoggingRules.AXE, 1, 100));
        assertNotNull(f.reject(new Action.ChopTree(BASE)));
    }

    @Test void unloadedUnreachableOpenMenuCursorAndDisconnectAllBlockChop() {
        Fixture f = new Fixture(); f.approveTree();
        f.world.unloaded.add(BASE); assertNotNull(f.reject(new Action.ChopTree(BASE))); f.world.unloaded.clear();
        f.world.reachable = false; assertNotNull(f.reject(new Action.ChopTree(BASE))); f.world.reachable = true;
        f.world.container = true; assertNotNull(f.reject(new Action.ChopTree(BASE))); f.world.container = false;
        f.world.carried = item(LoggingRules.LOG, 1, 0); assertNotNull(f.reject(new Action.ChopTree(BASE))); f.world.carried = ItemData.EMPTY;
        f.world.connected = false; assertNotNull(f.reject(new Action.ChopTree(BASE)));
    }

    @Test void plantingNeedsApprovedBaseSpruceHandAndNativePlacementProof() {
        Fixture f = new Fixture(); f.world.blockAt(BASE, "minecraft:air");
        f.world.put(2, item(LoggingRules.SAPLING, 4, 0));
        assertNotNull(f.reject(new Action.PlantSapling(BASE)));
        f.world.plantable = true; assertNull(f.reject(new Action.PlantSapling(BASE)));
        assertNotNull(f.reject(new Action.PlantSapling(BASE.offset(0, 1, 0))));
        f.world.put(2, item("minecraft:oak_sapling", 4, 0)); assertNotNull(f.reject(new Action.PlantSapling(BASE)));
        f.world.put(2, item(LoggingRules.SAPLING, 4, 0)); f.world.unloaded.add(BASE);
        assertNotNull(f.reject(new Action.PlantSapling(BASE)));
    }

    @Test void oneSnowLayerAllowsDirectPlantingOnlyWithTheExistingNativeProof() {
        Fixture f=new Fixture(); f.world.put(2,item(LoggingRules.SAPLING,4,0));
        f.world.blocks.put(BASE,new BlockData(BASE,"minecraft:snow",Map.of("layers","1")));
        assertNotNull(f.reject(new Action.PlantSapling(BASE)),"Replaceability must not replace native placement proof");
        f.world.plantable=true; assertNull(f.reject(new Action.PlantSapling(BASE)));
        f.world.unloaded.add(BASE); assertNotNull(f.reject(new Action.PlantSapling(BASE)));
    }

    @Test void genericNativeApprovalCannotMakeForeignBlocksOrMultipleSnowLayersPlantable() {
        Fixture f=new Fixture(); f.world.put(2,item(LoggingRules.SAPLING,4,0)); f.world.plantable=true;
        for(String id:List.of("minecraft:snow_block","minecraft:stone_bricks","minecraft:spruce_leaves",LoggingRules.SAPLING,LoggingRules.LOG)) {
            f.world.blockAt(BASE,id); assertNotNull(f.reject(new Action.PlantSapling(BASE)),id);
        }
        f.world.blocks.put(BASE,new BlockData(BASE,"minecraft:snow",Map.of("layers","2")));
        assertNotNull(f.reject(new Action.PlantSapling(BASE)));
        f.world.blockAt(BASE,"minecraft:snow"); assertNotNull(f.reject(new Action.PlantSapling(BASE)));
    }

    @Test void trashAllowsExactTwigButNotVanillaStickWoodOrBerries() {
        Fixture f = new Fixture(); f.actions.trashSupported = true;
        ItemData twig = item(LoggingRules.TWIG, 10, 0); f.world.put(9, twig);
        assertNotNull(f.reject(new Action.TrashLogging(9, twig)),"Even twig disposal waits for a coherent completed 2x2 pattern");
        f.planted();
        assertNull(f.reject(new Action.TrashLogging(9, twig)));
        for (String id : List.of("minecraft:stick", LoggingRules.LOG, LoggingRules.FIRE_LOG, LoggingRules.BERRY, ItemData.ROTTEN)) {
            ItemData other = item(id, 10, 0); f.world.put(9, other);
            assertNotNull(f.reject(new Action.TrashLogging(9, other)));
        }
        f.world.put(9, twig); f.actions.trashSupported = false;
        assertNotNull(f.reject(new Action.TrashLogging(9, twig)));
    }

    @Test void spareSaplingsRequireAllPlotsPlantedAndPreserveConfiguredReserve() {
        Fixture f = new Fixture(); f.actions.trashSupported = true; f.planted();
        ItemData deleting = item(LoggingRules.SAPLING, 6, 0); f.world.put(9, deleting); f.world.put(10, item(LoggingRules.SAPLING, 4, 0));
        f.profile.loggingSaplingReserve = 4; assertNull(f.reject(new Action.TrashLogging(9, deleting)));
        f.profile.loggingSaplingReserve = 5; assertNotNull(f.reject(new Action.TrashLogging(9, deleting)));
        f.profile.loggingSaplingReserve = 0;
        Pos missing = BASE.offset(1, 0, 1); f.world.blockAt(missing, "minecraft:air");
        assertNotNull(f.reject(new Action.TrashLogging(9, deleting)));
        f.world.blockAt(missing, LoggingRules.SAPLING); f.world.unloaded.add(missing);
        assertNotNull(f.reject(new Action.TrashLogging(9, deleting)));
        f.world.unloaded.clear(); f.profile.loggingPlots.clear();
        assertNotNull(f.reject(new Action.TrashLogging(9, deleting)));
    }

    @Test void trashRejectsStaleDuplicateArmorAndMismatchedMenuSnapshots() {
        Fixture f = new Fixture(); f.actions.trashSupported = true;
        ItemData twig = item(LoggingRules.TWIG, 10, 0); f.world.put(9, twig);
        assertNotNull(f.reject(new Action.TrashLogging(9, item(LoggingRules.TWIG, 9, 0))));
        for (int index : new int[] {-1, 36, 40}) assertNotNull(f.reject(new Action.TrashLogging(index, twig)));
        f.world.inventory.add(new ItemSlot(80, 9, true, twig));
        assertNotNull(f.reject(new Action.TrashLogging(9, twig))); f.world.inventory.remove(f.world.inventory.size() - 1);
        f.world.menuItems = new ArrayList<>(f.world.inventory); f.world.menuItems.set(9, new ItemSlot(9, 9, true, ItemData.EMPTY));
        assertNotNull(f.reject(new Action.TrashLogging(9, twig)));
        f.world.menuItems = new ArrayList<>(f.world.inventory); f.world.menuItems.add(new ItemSlot(81, 9, true, twig));
        assertNotNull(f.reject(new Action.TrashLogging(9, twig)));
        assertNotNull(f.reject(new Action.TrashLogging(9, null)));
    }

    @Test void unresolvedPreservesOutputAlsoBlocksLoggingTrash() {
        Fixture f = new Fixture(); f.actions.trashSupported = true;
        ItemData twig = item(LoggingRules.TWIG, 10, 0); f.world.put(9, twig);
        PendingMachineOutput output = new PendingMachineOutput("pending", Feature.PRESERVES, TABLE, 1, null, 1,
                PendingMachineOutput.Phase.AWAITING_PICKUP);
        f.profile.pendingMachineOutputs.put(output.id(), output);
        assertNotNull(f.reject(new Action.TrashLogging(9, twig)));
    }

    @Test void craftingOpenRequiresExactRegisteredTableAndLoggingPermission() {
        Fixture f = new Fixture(); f.world.blockAt(TABLE, "minecraft:crafting_table");
        Action open = new Action.UseBlock(TABLE, Action.Use.OPEN_CRAFTING);
        assertNotNull(f.reject(open)); f.profile.pois.add(new Poi(TABLE, PoiKind.LOGGING_CRAFTING_TABLE, "table", null));
        assertNull(f.reject(open)); f.world.blockAt(TABLE, "minecraft:stone"); assertNotNull(f.reject(open));
        f.world.blockAt(TABLE, "minecraft:crafting_table"); f.session.oneShotFeature = Feature.HARVEST;
        assertNotNull(f.reject(open));
    }

    @Test void craftingNeedsNativeTableMenuAndSixNormalInventorySpruceLogs() {
        Fixture f = new Fixture(); f.profile.pois.add(new Poi(TABLE, PoiKind.LOGGING_CRAFTING_TABLE, "table", null));
        f.world.container = true; f.world.put(9, item(LoggingRules.LOG, 6, 0));
        Action craft = new Action.CraftFireLogs(TABLE);
        assertNotNull(f.reject(craft), "default menu proof must fail closed");
        f.world.crafting = true; assertNull(f.reject(craft));
        f.world.put(9, item(LoggingRules.LOG, 5, 0)); assertNotNull(f.reject(craft));
        f.world.inventory.add(new ItemSlot(50, 36, true, item(LoggingRules.LOG, 64, 0)));
        assertNotNull(f.reject(craft), "armor/offhand cannot supply the recipe");
        f.world.put(9, item("minecraft:oak_log", 64, 0)); assertNotNull(f.reject(craft));
        f.world.put(9, item(LoggingRules.LOG, 6, 0));
        assertNotNull(f.reject(new Action.CraftFireLogs(TABLE.offset(1, 0, 0))));
    }

    @Test void closingCraftingTableRequiresEmptyGridAndCursor() {
        Fixture f = new Fixture(); f.world.container = true; f.world.crafting = true;
        Action close = new Action.CloseContainer(f.world.menuId);
        assertNotNull(f.reject(close)); f.world.gridEmpty = true; assertNull(f.reject(close));
        f.world.carried = item(LoggingRules.LOG, 1, 0); assertNotNull(f.reject(close));
        f.world.carried = ItemData.EMPTY; assertNotNull(f.reject(new Action.CloseContainer(999)));
    }

    @Test void woodAndMossberryTransfersRequireLoggingAndNormalPlayerSlots() {
        Fixture f = new Fixture(); f.world.container = true;
        for (String id : List.of(LoggingRules.LOG, LoggingRules.FIRE_LOG, LoggingRules.BERRY)) {
            ItemData output = item(id, 6, 0); f.world.put(9, output);
            assertNull(f.reject(new Action.QuickMove(f.world.menuId, 9)));
            f.session.oneShotFeature = Feature.WINE;
            assertNotNull(f.reject(new Action.QuickMove(f.world.menuId, 9))); f.session.oneShotFeature = Feature.LOGGING;
            f.world.menuItems = List.of(new ItemSlot(0, -1, false, output));
            assertNotNull(f.reject(new Action.QuickMove(f.world.menuId, 0)));
            f.world.menuItems = List.of(new ItemSlot(50, 36, true, output));
            assertNotNull(f.reject(new Action.QuickMove(f.world.menuId, 50))); f.world.menuItems = null;
        }
        f.world.put(9, item("minecraft:spruce_planks", 64, 0));
        assertNotNull(f.reject(new Action.QuickMove(f.world.menuId, 9)));
        f.world.container = false; f.world.blockAt(TABLE, "minecraft:chest");
        f.profile.pois.add(new Poi(TABLE, PoiKind.WOOD_CHEST, "wood", null));
        assertNull(f.reject(new Action.UseBlock(TABLE, Action.Use.OPEN_CONTAINER)));
        f.session.oneShotFeature = Feature.WINE;
        assertNotNull(f.reject(new Action.UseBlock(TABLE, Action.Use.OPEN_CONTAINER)));
    }

    @Test void storageSurveyRejectsEveryNewLoggingMutation() {
        Fixture f = new Fixture(); f.approveTree(); f.session.oneShotFeature = Feature.STORAGE_SURVEY;
        for (Action action : List.of(new Action.ChopTree(BASE), new Action.PlantSapling(BASE),
                new Action.CraftFireLogs(TABLE), new Action.TrashLogging(9, item(LoggingRules.TWIG, 1, 0))))
            assertNotNull(f.reject(action));
    }

    private static ItemData item(String id, int count, int durability) { return new ItemData(id, count, 0, null, false, durability); }
    private static final class Fixture {
        final Profile profile = new Profile(); final SessionState session = new SessionState();
        final ProofWorld world = new ProofWorld(); final NoActions actions = new NoActions();
        final Context context = new Context(world, actions, null, profile, session);
        Fixture() {
            profile.enabled.put(Feature.LOGGING, true); profile.loggingAxeHotbarSlot = 2;
            profile.loggingPlots.add(new LoggingPlot("test tree", BASE));
            world.put(2, item(LoggingRules.AXE, 1, 100)); world.blockAt(BASE, LoggingRules.LOG);
        }
        void approveTree() { world.axe = true; world.treeError = null; }
        void planted() { for (Pos pos : profile.loggingPlots.get(0).plantingPositions()) world.blockAt(pos, LoggingRules.SAPLING); }
        String reject(Action action) { return SafetyPolicy.rejection(action, context); }
    }

    /** Intentionally inherits all WorldAccess logging defaults. */
    private static class BasicWorld implements WorldAccess {
        final List<ItemSlot> inventory = new ArrayList<>(); final Map<Pos,BlockData> blocks = new HashMap<>();
        final Set<Pos> unloaded = new HashSet<>(); List<ItemSlot> menuItems;
        boolean container, connected = true, reachable = true; int selected = 2, menuId = 42;
        ItemData carried = ItemData.EMPTY;
        BasicWorld() { for (int index = 0; index < 36; index++) inventory.add(new ItemSlot(index, index, true, ItemData.EMPTY)); }
        void put(int index, ItemData item) { inventory.set(index, new ItemSlot(index, index, true, item)); }
        void blockAt(Pos pos, String id) { blocks.put(pos, new BlockData(pos, id, Map.of())); }
        @Override public long tick() { return 100; }
        @Override public long dayTime() { return 24000; }
        @Override public PlayerState player() { return new PlayerState(0.5, 64, 0.5, 0, 0, true, false, 20, 20, selected, connected, true); }
        @Override public BlockData block(Pos pos) { return blocks.containsKey(pos) ? blocks.get(pos) : new BlockData(pos, "minecraft:air", Map.of()); }
        @Override public boolean loaded(Pos pos) { return pos != null && !unloaded.contains(pos); }
        @Override public boolean canStand(Pos pos) { return false; }
        @Override public boolean canTraverse(Pos from, Pos to) { return false; }
        @Override public boolean canInteract(Pos pos, double reach) { return reachable; }
        @Override public List<BlockData> scan(Pos center, int horizontal, int vertical) { return List.of(); }
        @Override public List<ItemSlot> inventory() { return List.copyOf(inventory); }
        @Override public MenuData menu() { return new MenuData(container ? menuId : 0, 1, menuItems == null ? List.copyOf(inventory) : menuItems, carried, container); }
        @Override public boolean mayPlace(int slot, ItemData item) { return false; }
    }
    private static final class ProofWorld extends BasicWorld {
        boolean axe, plantable, crafting, gridEmpty; String treeError = "Whole-tree proof was not supplied";
        Pos proofTarget; List<LoggingPlot> proofPlots;
        @Override public boolean loggingAxe(int index) { return axe; }
        @Override public String loggingTreeRejection(Pos target, List<LoggingPlot> plots) {
            proofTarget = target; proofPlots = List.copyOf(plots); return treeError;
        }
        @Override public boolean canPlantLoggingSapling(Pos target) { return plantable; }
        @Override public boolean loggingCraftingMenu() { return crafting; }
        @Override public boolean loggingCraftingGridEmpty() { return gridEmpty; }
    }
    private static final class NoActions implements ActionPort {
        boolean trashSupported;
        @Override public boolean busy() { return false; }
        @Override public long submit(Action action) { throw new AssertionError("Safety predicates must not dispatch actions"); }
        @Override public ActionOutcome outcome(long ticket) { throw new AssertionError("No action to poll"); }
        @Override public void move(Movement movement) { throw new AssertionError("No movement"); }
        @Override public void stopMovement() { throw new AssertionError("No movement"); }
        @Override public void cancel() { throw new AssertionError("No action to cancel"); }
        @Override public boolean supportsInventoryTrash() { return trashSupported; }
    }
}
