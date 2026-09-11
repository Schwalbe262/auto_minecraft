package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EngineSafetyTest {
    private static final Pos TARGET = new Pos(0,64,0);
    private static final ItemData TOMATO = new ItemData(ItemData.TOMATO,6,0,null,false,999);
    private static final ItemData ROTTEN = new ItemData(ItemData.ROTTEN,6,0,null,false,999);
    private static final ItemData HOE = new ItemData("minecraft:diamond_hoe",1,0,null,true,100);

    @Test void actionAllowListHasNoGenericAttackAndAddsOnlyScopedLoggingMutations() {
        assertTrue(Action.class.isSealed());
        assertEquals(Set.of(Action.UseBlock.class,Action.InspectCrystal.class,Action.SelectHotbar.class,Action.SwapHotbar.class,Action.RefreshInventory.class,
            Action.QuickMove.class,Action.ThrowRotten.class,Action.TrashRotten.class,Action.CloseContainer.class,Action.ConsolidateInventory.class,
            Action.ChopTree.class,Action.ClearLoggingLeaf.class,Action.PlantSapling.class,Action.CraftFireLogs.class,Action.TrashLogging.class),Set.of(Action.class.getPermittedSubclasses()));
        assertEquals(Set.of("HARVEST","MACHINE","ARTISAN","FRUIT","OPEN_CONTAINER","OPEN_CRAFTING","SLEEP","DOOR"),
            new HashSet<>(Arrays.stream(Action.Use.values()).map(Enum::name).toList()));
    }

    @Test void harvestAcceptsOnlyMatureTomatoBlocksAndSelectedHoe() {
        Fixture f = new Fixture(); f.profile.farms.add(new Farm("farm",TARGET,TARGET)); f.held = HOE;
        Action harvest = new Action.UseBlock(TARGET,Action.Use.HARVEST);
        f.block = new BlockData(TARGET,"farmersdelight:tomatoes",Map.of("age","3"));
        assertNull(SafetyPolicy.rejection(harvest,f.context()));
        f.block = new BlockData(TARGET,"farmersdelight:tomatoes",Map.of("age","2"));
        assertNotNull(SafetyPolicy.rejection(harvest,f.context()));
        f.block = new BlockData(TARGET,"farmersdelight:budding_tomatoes",Map.of("age","3"));
        assertNotNull(SafetyPolicy.rejection(harvest,f.context()),"budding age3 is vegetative growth, not fruit maturity");
        f.block = new BlockData(TARGET,"farmersdelight:tomatoes_on_rope",Map.of("age","3"));
        assertNull(SafetyPolicy.rejection(harvest,f.context()));
        f.held = TOMATO;
        assertNotNull(SafetyPolicy.rejection(harvest,f.context()));
    }

    @Test void matureWorkingMachineAllowsCollectionAndSingleClickRefill() {
        Fixture f = new Fixture(); f.profile.pois.add(new Poi(TARGET,PoiKind.WINE_KEG,"keg",null));
        f.block = new BlockData(TARGET,"society:wine_keg",Map.of("working","true","mature","true"));
        Action use = new Action.UseBlock(TARGET,Action.Use.MACHINE);
        f.held = TOMATO; assertNull(SafetyPolicy.rejection(use,f.context()));
        f.held = ItemData.EMPTY; assertNull(SafetyPolicy.rejection(use,f.context()));
        f.held = HOE; assertNotNull(SafetyPolicy.rejection(use,f.context()));
        f.held = TOMATO; f.block = new BlockData(TARGET,"society:wine_keg",Map.of("working","true","mature","false"));
        assertNotNull(SafetyPolicy.rejection(use,f.context()));
    }

    @Test void disposalRequiresExactRottenStackRegisteredPointAndDirection() {
        Fixture f = new Fixture(); f.profile.pois.add(new Poi(TARGET,PoiKind.DISPOSAL,"drop",null));
        f.profile.disposalDirections.put("0:64:0",new Look(90,20));
        Action drop = new Action.ThrowRotten(0,0,TARGET);
        f.held = TOMATO; assertNotNull(SafetyPolicy.rejection(drop,f.context()));
        f.held = ROTTEN; assertNull(SafetyPolicy.rejection(drop,f.context()));
        f.profile.disposalDirections.clear(); assertNotNull(SafetyPolicy.rejection(drop,f.context()));
    }

    @Test void focusCursorAndChangedContainerPreventInventoryActions() {
        Fixture f = new Fixture(); f.profile.allowBackground=false;
        f.focused = false; assertNotNull(SafetyPolicy.rejection(new Action.SelectHotbar(1),f.context()));
        f.focused = true; f.carried = TOMATO; assertNotNull(SafetyPolicy.rejection(new Action.SelectHotbar(1),f.context()));
        f.carried = ItemData.EMPTY; f.container = true; f.menuId = 7; f.held = TOMATO;
        assertNotNull(SafetyPolicy.rejection(new Action.QuickMove(6,0),f.context()));
        assertNull(SafetyPolicy.rejection(new Action.QuickMove(7,0),f.context()));
    }

    @Test void engineStartsOffAndCancellationStopsActionsImmediately() {
        Fixture f = new Fixture(); Module module = new Module(Feature.HARVEST,50,WorkResult.busy("moving"));
        AutomationEngine engine = new AutomationEngine(List.of(module));
        assertEquals(AutomationEngine.State.OFF,engine.state()); engine.tick(f.context()); assertEquals(0,module.calls);
        engine.start(f.context()); engine.tick(f.context()); assertEquals(1,module.calls);
        int cancellations = f.cancelCalls;
        engine.stop(f.context(),AutomationEngine.State.PAUSED,"manual takeover");
        assertEquals(cancellations+1,f.cancelCalls); assertFalse(engine.running());
        engine.tick(f.context()); assertEquals(1,module.calls);
    }

    @Test void backgroundEnabledByDefaultAllowsUnfocusedStartAndContinuedWork() {
        Fixture f=new Fixture(); assertTrue(f.profile.allowBackground); f.focused=false;
        Module module=new Module(Feature.WINE,60,WorkResult.busy("production"));
        AutomationEngine engine=new AutomationEngine(List.of(module));
        engine.start(f.context()); assertTrue(engine.running());
        engine.tick(f.context()); engine.tick(f.context());
        assertEquals(2,module.calls); assertTrue(engine.running());
    }

    @Test void foregroundRequiredModeRejectsUnfocusedStartAndPausesOnFocusLoss() {
        Fixture f=new Fixture(); f.profile.allowBackground=false; f.focused=false;
        Module module=new Module(Feature.WINE,60,WorkResult.busy("production"));
        AutomationEngine engine=new AutomationEngine(List.of(module));
        engine.start(f.context()); assertFalse(engine.running()); engine.tick(f.context()); assertEquals(0,module.calls);
        f.focused=true; engine.start(f.context()); engine.tick(f.context()); assertEquals(1,module.calls);
        f.focused=false; int cancellations=f.cancelCalls; engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(cancellations+1,f.cancelCalls); assertEquals(1,module.calls);
    }

    @Test void disconnectAlwaysPreventsStartAndStopsWorkRegardlessOfBackgroundSetting() {
        for (boolean allowBackground:new boolean[]{false,true}) {
            Fixture f=new Fixture(); f.profile.allowBackground=allowBackground; f.connected=false;
            Module module=new Module(Feature.WINE,60,WorkResult.busy("production"));
            AutomationEngine engine=new AutomationEngine(List.of(module));
            engine.start(f.context()); assertFalse(engine.running()); engine.tick(f.context()); assertEquals(0,module.calls);
            f.connected=true; engine.start(f.context()); engine.tick(f.context()); assertEquals(1,module.calls);
            f.connected=false; int cancellations=f.cancelCalls; engine.tick(f.context());
            assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(cancellations+1,f.cancelCalls); assertEquals(1,module.calls);
        }
    }

    @Test void disabledFeaturesAreNeverTickedAndDisablingActiveFeatureCancels() {
        Fixture f = new Fixture(); Module module = new Module(Feature.HARVEST,50,WorkResult.busy("moving"));
        AutomationEngine engine = new AutomationEngine(List.of(module));
        f.profile.enabled.put(Feature.HARVEST,false); engine.start(f.context()); engine.tick(f.context()); assertEquals(0,module.calls);
        f.profile.enabled.put(Feature.HARVEST,true); f.ticks += 21; engine.tick(f.context()); assertEquals(1,module.calls);
        f.profile.enabled.put(Feature.HARVEST,false); int cancels = f.cancelCalls; engine.tick(f.context());
        assertFalse(engine.running()); assertEquals(cancels+1,f.cancelCalls); assertEquals(1,module.calls);
    }

    @Test void blockedWineSuppressesPreservesAndSleep() {
        Fixture f = new Fixture();
        Module wine = new Module(Feature.WINE,60,WorkResult.blocked("inspect output"));
        Module jars = new Module(Feature.PRESERVES,70,WorkResult.busy("fill"));
        Module sleep = new Module(Feature.SLEEP,100,WorkResult.busy("sleep"));
        AutomationEngine engine = new AutomationEngine(List.of(sleep,jars,wine));
        engine.start(f.context()); engine.tick(f.context());
        assertEquals(1,wine.calls); assertEquals(0,jars.calls); assertEquals(0,sleep.calls);
        assertEquals(AutomationEngine.State.WAITING,engine.state());
    }

    @Test void fullInventoryCanBeRelievedByStorageAfterActiveProductionBlocks() {
        Fixture f = new Fixture();
        Module storage = new Module(Feature.TOMATO_STORAGE,20,WorkResult.idle());
        Module wine = new Module(Feature.WINE,60,WorkResult.busy("production"));
        AutomationEngine engine = new AutomationEngine(List.of(wine,storage));
        engine.start(f.context()); engine.tick(f.context());
        assertEquals(1,wine.calls);
        wine.result = WorkResult.blocked("inventory full"); engine.tick(f.context());
        int priorStorageChecks=storage.calls;
        storage.result = WorkResult.busy("deposit tomatoes"); f.ticks += 21; engine.tick(f.context());
        assertEquals(priorStorageChecks+1,storage.calls); assertEquals("deposit tomatoes",engine.status());
    }

    @Test void unresolvedInventoryClickBlocksManualRestartUntilItsFenceClears() {
        Fixture f=new Fixture(); Module work=new Module(Feature.WINE,60,WorkResult.busy("work"));
        AutomationEngine engine=new AutomationEngine(List.of(work));
        f.actionPause="Late inventory reply"; engine.start(f.context());
        assertFalse(engine.running()); assertEquals("Late inventory reply",engine.status());
        engine.tick(f.context()); assertEquals(0,work.calls);
        f.actionPause=null; engine.start(f.context()); engine.tick(f.context()); assertEquals(1,work.calls);
    }

    @Test void newlyRaisedInventoryFencePreventsOtherContinuousConsumersInTheSameTick() {
        Fixture f=new Fixture(); Module wine=new Module(Feature.WINE,60,WorkResult.blocked("merge failed"));
        Module preserves=new Module(Feature.PRESERVES,70,WorkResult.busy("preserves"));
        Module sleep=new Module(Feature.SLEEP,100,WorkResult.busy("sleep"));
        wine.onTick=() -> f.actionPause="Inspect interrupted inventory consolidation";
        AutomationEngine engine=new AutomationEngine(List.of(wine,preserves,sleep));
        engine.start(f.context()); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,wine.calls);
        assertEquals(0,preserves.calls); assertEquals(0,sleep.calls);
    }

    @Test void inventoryFailureCannotBecomeOneShotCompletion() {
        Fixture f=new Fixture(); Module wine=new Module(Feature.WINE,60,WorkResult.idle());
        wine.onTick=() -> f.actionPause="Inventory uncertain";
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        engine.startOnce(f.context(),Feature.WINE); engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals("Inventory uncertain",engine.status());
    }

    @Test void inventoryFenceCancelsAnAlreadyActiveJobBeforeAnotherTick() {
        Fixture f=new Fixture(); Module wine=new Module(Feature.WINE,60,WorkResult.busy("work"));
        AutomationEngine engine=new AutomationEngine(List.of(wine));
        engine.start(f.context()); engine.tick(f.context()); assertEquals(1,wine.calls);
        f.actionPause="Late inventory reply"; engine.tick(f.context());
        assertEquals(AutomationEngine.State.PAUSED,engine.state()); assertEquals(1,wine.calls);
    }

    private static final class Module implements AutomationModule {
        final Feature feature; final int priority; WorkResult result; int calls; Runnable onTick=() -> {};
        Module(Feature feature,int priority,WorkResult result) { this.feature=feature; this.priority=priority; this.result=result; }
        @Override public Feature feature() { return feature; }
        @Override public int priority() { return priority; }
        @Override public WorkResult tick(Context c) { calls++; onTick.run(); return result; }
        @Override public void reset() { }
    }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile = new Profile(); boolean focused=true, connected=true, container; int menuId, cancelCalls; long ticks;
        ItemData held=ItemData.EMPTY, carried=ItemData.EMPTY;
        String actionPause;
        BlockData block=new BlockData(TARGET,"minecraft:air",Map.of());
        Context context() { return new Context(this,this,this,profile); }
        @Override public long tick() { return ticks; }
        @Override public long dayTime() { return 13000; }
        @Override public HarvestFootprint harvestFootprint(Pos target) { return HarvestFootprint.single(target); }
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,0,connected,focused); }
        @Override public BlockData block(Pos p) { return block; }
        @Override public boolean loaded(Pos p) { return true; }
        @Override public boolean canStand(Pos p) { return true; }
        @Override public boolean canTraverse(Pos a,Pos b) { return true; }
        @Override public List<BlockData> scan(Pos p,int r,int v) { return List.of(); }
        @Override public List<ItemSlot> inventory() { return List.of(new ItemSlot(0,0,true,held)); }
        @Override public MenuData menu() { return new MenuData(menuId,0,inventory(),carried,container); }
        @Override public boolean mayPlace(int slot,ItemData item) { return true; }
        @Override public boolean busy() { return false; }
        @Override public long submit(Action a) { return 1; }
        @Override public ActionOutcome outcome(long ticket) { return new ActionOutcome(ActionOutcome.State.SUCCEEDED,""); }
        @Override public void move(Movement m) { }
        @Override public void stopMovement() { }
        @Override public void cancel() { cancelCalls++; }
        @Override public String pauseReason() { return actionPause; }
        @Override public Navigation.Result moveTo(Pos p,double r,Context c) { return Navigation.Result.ARRIVED; }
        @Override public void reset() { }
    }
}
