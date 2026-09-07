package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogisticsTest {
    private static ItemData tomato(int grade, int count) { return new ItemData(ItemData.TOMATO,count,grade,null,false,999); }
    private static ItemData wine(Integer year) { return new ItemData(ItemData.WINE,1,0,year,false,999); }

    @Test void largestGradeWinsAndTiesPreferLowerGrade() {
        assertEquals(2,MachineModule.chooseGrade(new int[]{20,3,21,0},3));
        assertEquals(0,MachineModule.chooseGrade(new int[]{20,20,20,0},3));
        assertEquals(-1,MachineModule.chooseGrade(new int[]{2,2,2,2},3));
        assertTrue(new MachineModule(Feature.WINE).priority() < new MachineModule(Feature.PRESERVES).priority());
    }

    @Test void disposalUsesOnlyExactRottenItemAndWaitsForAcknowledgement() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(0,7); f.inventory[1] = new ItemData(ItemData.ROTTEN,4,0,null,false,999);
        f.poi(PoiKind.DISPOSAL,0,null);
        DisposalModule module = new DisposalModule();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        assertInstanceOf(Action.ThrowRotten.class,f.action);
        module.tick(f.context());
        assertEquals(1,f.history.size(),"pending throw must not be resubmitted");
        f.advance();
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(7,f.inventory[0].count()); assertTrue(f.inventory[1].empty());
    }

    @Test void menuSelectionNeverUsesOffhandOrArmorSlots() {
        Fixture f = new Fixture(); f.inventory[3] = tomato(0,5);
        f.extraMenuSlot = new ItemSlot(100,40,true,tomato(0,20));
        assertEquals(3,ModuleSupport.menuPlayerItem(f.context(),i -> i.is(ItemData.TOMATO)).inventoryIndex());
    }

    @Test void tomatoStorageDepositsOnlyItsRegisteredGrade() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(2,15); f.inventory[1] = tomato(1,9);
        Pos first = f.chest(PoiKind.TOMATO_CHEST,0,2,ItemData.EMPTY);
        Pos second = f.chest(PoiKind.TOMATO_CHEST,1,1,ItemData.EMPTY);
        WorkResult result = f.run(new TomatoStorageModule(),80);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(15,f.chests.get(first)[0].count()); assertEquals(2,f.chests.get(first)[0].quality());
        assertEquals(9,f.chests.get(second)[0].count()); assertEquals(1,f.chests.get(second)[0].quality());
    }

    @Test void storageBlocksConflictingChestAndNeverMovesItem() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(2,15);
        f.chest(PoiKind.TOMATO_CHEST,0,2,tomato(1,3));
        WorkResult result = f.run(new TomatoStorageModule(),20);
        assertEquals(WorkResult.State.BLOCKED,result.state());
        assertFalse(f.history.stream().anyMatch(Action.QuickMove.class::isInstance));
    }

    @Test void wineWaitsForActualYearAndUsesThatYearOnly() {
        Fixture f = new Fixture(); f.inventory[0] = wine(null);
        WineStorageModule module = new WineStorageModule();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        f.ticks = 101;
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        assertTrue(f.history.isEmpty());
        f.inventory[0] = wine(12);
        Pos chest = f.chest(PoiKind.WINE_CHEST,0,12,ItemData.EMPTY);
        assertEquals(WorkResult.State.IDLE,f.run(module,30).state());
        assertEquals(12,f.chests.get(chest)[0].year());
    }

    @Test void unregisteredWineYearDoesNotEnterDifferentYearChest() {
        Fixture f = new Fixture(); f.inventory[0] = wine(12);
        f.chest(PoiKind.WINE_CHEST,0,11,ItemData.EMPTY);
        assertEquals(WorkResult.State.BLOCKED,f.run(new WineStorageModule(),10).state());
        assertTrue(f.history.isEmpty());
    }

    @Test void shippingMovesPreservesOnly() {
        Fixture f = new Fixture(); f.inventory[0] = wine(4); f.inventory[1] = tomato(3,5);
        f.inventory[2] = new ItemData(ItemData.PRESERVES,3,0,null,false,999);
        Pos bin = f.chest(PoiKind.SHIPPING_BIN,0,null,ItemData.EMPTY);
        assertEquals(WorkResult.State.IDLE,f.run(new ShippingModule(),30).state());
        assertEquals(ItemData.PRESERVES,f.chests.get(bin)[0].id());
        assertEquals(ItemData.WINE,f.inventory[0].id()); assertEquals(ItemData.TOMATO,f.inventory[1].id());
    }

    @Test void changedMenuCancelsDepositBeforeNextClick() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,8);
        f.chest(PoiKind.TOMATO_CHEST,0,0,ItemData.EMPTY);
        TomatoStorageModule module = new TomatoStorageModule();
        module.tick(f.context()); module.tick(f.context()); f.advance();
        module.tick(f.context()); // first quick move submitted
        f.open = null; f.advance();
        assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state());
        assertEquals(1,f.history.stream().filter(Action.QuickMove.class::isInstance).count());
    }

    @Test void productionCountsChestAndInventoryThenReopensBeforeWithdrawal() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(0,10); f.inventory[8] = new ItemData("minecraft:diamond_hoe",1,0,null,true,100);
        f.profile.hoeHotbarSlot = 8;
        Pos grade0 = f.chest(PoiKind.TOMATO_CHEST,1,0,tomato(0,10));
        Pos grade2 = f.chest(PoiKind.TOMATO_CHEST,2,2,tomato(2,30));
        Pos keg = f.machine(PoiKind.WINE_KEG,3,false,false,false);
        WorkResult result = f.run(new MachineModule(Feature.WINE),120);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(2,f.usedGrades.get(0)); assertEquals(3,f.consumed);
        assertEquals(10,f.chests.get(grade0)[0].count());
        assertTrue(f.chests.get(grade2)[0].empty());
        long selectedOpens = f.history.stream().filter(a -> a instanceof Action.UseBlock u && u.purpose() == Action.Use.OPEN_CONTAINER && u.pos().equals(grade2)).count();
        assertEquals(2,selectedOpens,"source must be opened once to count and again before withdrawing");
        assertTrue(f.blocks.get(keg).flag("working")); assertTrue(f.inventory[8].hoe());
    }

    @Test void stockChangedOnReopenIsRecountedBeforeWithdrawal() {
        Fixture f = new Fixture();
        f.inventory[0] = tomato(0,6);
        Pos selected = f.chest(PoiKind.TOMATO_CHEST,1,2,tomato(2,30));
        f.machine(PoiKind.WINE_KEG,2,false,false,false);
        f.emptyOnSecondOpen = selected;
        WorkResult result = f.run(new MachineModule(Feature.WINE),120);
        assertEquals(WorkResult.State.IDLE,result.state(),result.message());
        assertEquals(List.of(0),f.usedGrades);
        assertFalse(f.history.stream().anyMatch(Action.QuickMove.class::isInstance));
    }

    @Test void readyMachineCollectsAndRefillsOnceThenWaitsForPickup() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.WINE_KEG,0,true,true,false);
        f.pickup = false;
        MachineModule module = new MachineModule(Feature.WINE);
        for (int i = 0; i < 30; i++) { assertNotEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state()); f.advance(); }
        assertEquals(1,f.machineClicks()); assertEquals(3,f.consumed);
        assertEquals(0,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
        f.pickup = true; f.advance();
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state());
        assertEquals(1,f.machineClicks());
    }

    @Test void uncollectedOutputKeepsBlockingAcrossSchedulerPasses() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.WINE_KEG,0,true,true,false); f.pickup = false;
        MachineModule module = new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.BLOCKED,f.run(module,150).state());
        int actions = f.history.size();
        for (int i = 0; i < 5; i++) {
            f.ticks += 20;
            assertEquals(WorkResult.State.BLOCKED,module.tick(f.context()).state(),"next-day schedule must not hide a missing product");
        }
        assertEquals(actions,f.history.size()); assertEquals(1,f.machineClicks());
        module.reset(); // Explicit takeover/restart acknowledges the need to inspect the dropped product.
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
    }

    @Test void noIngredientsAndProcessingMachinesAreIdle() {
        Fixture f = new Fixture(); f.machine(PoiKind.WINE_KEG,0,false,false,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),20).state());
        Fixture processing = new Fixture(); processing.machine(PoiKind.PRESERVES_JAR,0,true,false,false);
        assertEquals(WorkResult.State.IDLE,processing.run(new MachineModule(Feature.PRESERVES),20).state());
        assertEquals(0,processing.machineClicks());
        assertEquals(0,processing.navigationCalls,"a loaded working machine must not cause repeated visits");
    }

    @Test void resourcesRetryNextDayAndProductionScheduleSurvivesReset() {
        Fixture f = new Fixture(); Pos keg = f.machine(PoiKind.WINE_KEG,0,false,false,false);
        MachineModule module = new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.IDLE,f.run(module,20).state());
        int visits = f.navigationCalls;
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(visits,f.navigationCalls);
        f.inventory[0] = tomato(0,9); f.dayTime = 24000;
        assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
        assertEquals(1,f.machineClicks());
        assertEquals(7L,f.profile.nextEligibleDay.get("wine:0:64:0"));
        visits = f.navigationCalls;
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(visits,f.navigationCalls);
        f.blocks.put(keg,new BlockData(keg,"society:wine_keg",Map.of("working","true","mature","true","upgraded","false","facing","north")));
        module.reset();
        for (int day = 2; day < 7; day++) {
            f.dayTime = day*24000L;
            assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
            assertEquals(visits,f.navigationCalls,"no travel in intervening production days");
        }
        f.dayTime = 7*24000L;
        assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
        assertEquals(2,f.machineClicks(),"mature target is serviced on its scheduled game day");
        assertEquals(13L,f.profile.nextEligibleDay.get("wine:0:64:0"));
    }

    @Test void dueButStillWorkingReschedulesOneDayWithoutVisiting() {
        Fixture f = new Fixture(); f.machine(PoiKind.WINE_KEG,0,true,false,false);
        f.profile.nextEligibleDay.put("wine:0:64:0",6L); f.dayTime = 6*24000L+220;
        assertEquals(WorkResult.State.IDLE,new MachineModule(Feature.WINE).tick(f.context()).state());
        assertEquals(7L,f.profile.nextEligibleDay.get("wine:0:64:0"));
        assertEquals(0,f.navigationCalls);
    }

    @Test void dueMachineWaitsForMorningProcessingWithoutSkippingItsDueDay() {
        Fixture f = new Fixture(); Pos keg=f.machine(PoiKind.WINE_KEG,0,true,false,false);
        f.profile.nextEligibleDay.put("wine:0:64:0",6L); f.dayTime=6*24000L;
        MachineModule module=new MachineModule(Feature.WINE);
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(6L,f.profile.nextEligibleDay.get("wine:0:64:0")); assertEquals(0,f.navigationCalls);
        f.dayTime+=220;
        f.blocks.put(keg,new BlockData(keg,"society:wine_keg",Map.of("working","true","mature","true","upgraded","false","facing","north")));
        assertEquals(WorkResult.State.IDLE,f.run(module,40).state());
        assertEquals(1,f.machineClicks());
    }

    @Test void matureMachineCanCollectWithEmptyHandWithoutIngredients() {
        Fixture f = new Fixture(); f.machine(PoiKind.WINE_KEG,0,true,true,false);
        assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.WINE),40).state());
        assertEquals(0,f.consumed); assertEquals(1,ModuleSupport.count(f.context(),i -> i.is(ItemData.WINE)));
    }

    @Test void preservesRecipeUsesFiveOrThreeWhenUpgraded() {
        for (boolean upgrade : new boolean[]{false,true}) {
            Fixture f = new Fixture(); f.inventory[0] = tomato(0,10);
            f.machine(PoiKind.PRESERVES_JAR,0,false,false,upgrade);
            assertEquals(WorkResult.State.IDLE,f.run(new MachineModule(Feature.PRESERVES),40).state());
            assertEquals(upgrade ? 3 : 5,f.consumed);
        }
    }

    @Test void cancellationProducesNoCleanupClickAndRestartObservesState() {
        Fixture f = new Fixture(); f.inventory[0] = tomato(0,9);
        f.machine(PoiKind.WINE_KEG,0,false,false,false);
        MachineModule module = new MachineModule(Feature.WINE);
        while (f.machineClicks() == 0) { module.tick(f.context()); f.advance(); }
        int actions = f.history.size();
        module.reset(); f.cancel();
        assertEquals(actions,f.history.size());
        assertEquals(WorkResult.State.IDLE,f.run(module,10).state());
        assertEquals(1,f.machineClicks());
    }

    @Test void sleepRequiresThresholdActualSleepingAndNextDay() {
        Fixture f = new Fixture(); f.poi(PoiKind.BED,0,null); f.dayTime = 12583;
        SleepModule module = new SleepModule();
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state()); assertTrue(f.history.isEmpty());
        f.dayTime = 12584;
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state()); f.advance();
        assertEquals(WorkResult.State.BUSY,module.tick(f.context()).state());
        f.dayTime = 24000; f.sleeping = false;
        assertEquals(WorkResult.State.IDLE,module.tick(f.context()).state());
        assertEquals(1,f.history.size());
    }

    @Test void rejectedSleepHasThreeAttemptLimit() {
        Fixture f = new Fixture(); f.poi(PoiKind.BED,0,null); f.dayTime = 13000; f.rejectSleep = true;
        WorkResult result = f.run(new SleepModule(),500);
        assertEquals(WorkResult.State.BLOCKED,result.state());
        assertEquals(3,f.history.stream().filter(a -> a instanceof Action.UseBlock u && u.purpose() == Action.Use.SLEEP).count());
    }

    /** Models server changes only on advance(), so polling twice cannot fabricate acknowledgement. */
    private static final class Fixture implements WorldAccess, ActionPort, Navigation {
        final Profile profile = new Profile();
        final ItemData[] inventory = new ItemData[36];
        final Map<Pos,ItemData[]> chests = new HashMap<>();
        final Map<Pos,BlockData> blocks = new HashMap<>();
        final Map<Pos,Integer> opens = new HashMap<>();
        final List<Action> history = new ArrayList<>();
        final List<Integer> usedGrades = new ArrayList<>();
        long ticks, dayTime, sequence;
        int selected, consumed, navigationCalls;
        Pos open, emptyOnSecondOpen;
        boolean sleeping, rejectSleep, pickup = true;
        ItemData dropped;
        ItemSlot extraMenuSlot;
        Action action;
        ActionOutcome outcome = new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
        Fixture() { Arrays.fill(inventory,ItemData.EMPTY); }
        Context context() { return new Context(this,this,this,profile); }
        Pos poi(PoiKind kind,int x,Integer group) { Pos p = new Pos(x,64,0); profile.pois.add(new Poi(p,kind,"test",group)); return p; }
        Pos chest(PoiKind kind,int x,Integer group,ItemData initial) { Pos p = poi(kind,x,group); chests.put(p,new ItemData[]{initial,ItemData.EMPTY}); return p; }
        Pos machine(PoiKind kind,int x,boolean working,boolean mature,boolean upgraded) {
            Pos p = poi(kind,x,null);
            blocks.put(p,new BlockData(p,kind == PoiKind.WINE_KEG ? "society:wine_keg" : "society:preserves_jar",
                Map.of("working",""+working,"mature",""+mature,"upgraded",""+upgraded,"facing","north")));
            return p;
        }
        WorkResult run(AutomationModule module,int limit) {
            WorkResult result = null;
            for (int i = 0; i < limit; i++) { result = module.tick(context()); if (result.state() != WorkResult.State.BUSY) return result; advance(); }
            fail("Module did not finish within " + limit + " ticks: " + result); return result;
        }
        int machineClicks() { return (int)history.stream().filter(a -> a instanceof Action.UseBlock u && u.purpose() == Action.Use.MACHINE).count(); }
        void advance() {
            ticks++;
            if (action != null) {
                Action next = action; action = null;
                outcome = new ActionOutcome(ActionOutcome.State.SUCCEEDED,"");
                if (next instanceof Action.UseBlock use) {
                    if (use.purpose() == Action.Use.OPEN_CONTAINER) {
                        open = use.pos(); opens.merge(open,1,Integer::sum);
                        if (open.equals(emptyOnSecondOpen) && opens.get(open) == 2) Arrays.fill(chests.get(open),ItemData.EMPTY);
                    } else if (use.purpose() == Action.Use.MACHINE) {
                        BlockData block = blocks.get(use.pos());
                        boolean wineMachine = block.id().equals("society:wine_keg");
                        int batch = wineMachine || block.flag("upgraded") ? 3 : 5;
                        if (block.flag("mature")) dropped = wineMachine ? wine(2) : new ItemData(ItemData.PRESERVES,1,0,null,false,99);
                        boolean feed = inventory[selected].is(ItemData.TOMATO) && inventory[selected].count() >= batch;
                        if (feed) { consumed += batch; usedGrades.add(inventory[selected].quality()); inventory[selected] = tomato(inventory[selected].quality(),inventory[selected].count()-batch); }
                        blocks.put(use.pos(),new BlockData(block.pos(),block.id(),Map.of("working",""+feed,"mature","false","upgraded",""+block.flag("upgraded"),"facing","north")));
                    } else if (use.purpose() == Action.Use.SLEEP) {
                        if (rejectSleep) outcome = new ActionOutcome(ActionOutcome.State.FAILED,"server rejected sleep");
                        else sleeping = true;
                    }
                } else if (next instanceof Action.CloseContainer) open = null;
                else if (next instanceof Action.SelectHotbar select) selected = select.slot();
                else if (next instanceof Action.SwapHotbar swap) { ItemData prior = inventory[swap.hotbarSlot()]; inventory[swap.hotbarSlot()] = inventory[swap.inventoryIndex()]; inventory[swap.inventoryIndex()] = prior; }
                else if (next instanceof Action.ThrowRotten drop) {
                    ItemSlot slot = menu().slot(drop.slot());
                    if (slot != null && slot.item().is(ItemData.ROTTEN)) inventory[slot.inventoryIndex()] = ItemData.EMPTY;
                    else outcome = new ActionOutcome(ActionOutcome.State.FAILED,"not rotten");
                } else if (next instanceof Action.QuickMove move) {
                    if (open == null || move.containerId() != menu().id()) outcome = new ActionOutcome(ActionOutcome.State.FAILED,"menu changed");
                    else {
                        ItemSlot slot = menu().slot(move.slot());
                        if (slot.player()) {
                            if (add(chests.get(open),slot.item())) inventory[slot.inventoryIndex()] = ItemData.EMPTY;
                            else outcome = new ActionOutcome(ActionOutcome.State.FAILED,"full chest");
                        } else if (add(inventory,slot.item())) chests.get(open)[slot.index()] = ItemData.EMPTY;
                        else outcome = new ActionOutcome(ActionOutcome.State.FAILED,"full inventory");
                    }
                }
            }
            if (pickup && dropped != null && add(inventory,dropped)) dropped = null;
        }
        private static boolean add(ItemData[] slots,ItemData item) {
            for (int i = 0; i < slots.length; i++) if (slots[i].empty() || ModuleSupport.same(slots[i],item) && slots[i].count()+item.count() <= 64) {
                int count = (slots[i].empty() ? 0 : slots[i].count()) + item.count();
                slots[i] = new ItemData(item.id(),count,item.quality(),item.year(),item.hoe(),item.durability()); return true;
            }
            return false;
        }
        @Override public long tick() { return ticks; }
        @Override public long dayTime() { return dayTime; }
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,sleeping,20,20,selected,true,true); }
        @Override public BlockData block(Pos pos) { return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of())); }
        @Override public boolean loaded(Pos pos) { return true; }
        @Override public boolean canStand(Pos feet) { return true; }
        @Override public boolean canTraverse(Pos from,Pos to) { return true; }
        @Override public List<BlockData> scan(Pos center,int horizontalRadius,int verticalRadius) { return List.of(); }
        @Override public List<ItemSlot> inventory() { List<ItemSlot> list = new ArrayList<>(); for (int i = 0; i < 36; i++) list.add(new ItemSlot(i,i,true,inventory[i])); return list; }
        @Override public MenuData menu() {
            List<ItemSlot> slots = new ArrayList<>(); int offset = 0;
            if (extraMenuSlot != null) slots.add(extraMenuSlot);
            if (open != null) { ItemData[] chest = chests.get(open); for (int i = 0; i < chest.length; i++) slots.add(new ItemSlot(i,-1,false,chest[i])); offset = chest.length; }
            for (int i = 0; i < 36; i++) slots.add(new ItemSlot(offset+i,i,true,inventory[i]));
            return new MenuData(open == null ? 0 : open.x()+10,(int)ticks,slots,ItemData.EMPTY,open != null);
        }
        @Override public boolean mayPlace(int menuSlot,ItemData item) { return open != null && menuSlot < chests.get(open).length; }
        @Override public boolean busy() { return action != null; }
        @Override public long submit(Action next) { assertNull(action,"one action in flight"); history.add(next); action = next; outcome = new ActionOutcome(ActionOutcome.State.PENDING,""); return ++sequence; }
        @Override public ActionOutcome outcome(long ticket) { return outcome; }
        @Override public void move(Movement movement) { }
        @Override public void stopMovement() { }
        @Override public void cancel() { action = null; outcome = new ActionOutcome(ActionOutcome.State.CANCELLED,""); }
        @Override public Navigation.Result moveTo(Pos target,double reach,Context context) { navigationCalls++; return Navigation.Result.ARRIVED; }
        @Override public void reset() { }
    }
}
