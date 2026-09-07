package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.modules.HarvestModule;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NavigationHarvestTest {
    private static final Pos ORIGIN = new Pos(0,0,0);

    @Test void corridorsFollowConsecutiveWaypointsWithoutCuttingAcrossSites() {
        Profile profile = new Profile();
        profile.corridorRadius = 1;
        profile.pois.add(new Poi(ORIGIN, PoiKind.WAYPOINT, "a", null));
        profile.pois.add(new Poi(new Pos(20,0,0), PoiKind.WAYPOINT, "b", null));
        profile.pois.add(new Poi(new Pos(20,0,20), PoiKind.WAYPOINT, "c", null));
        profile.pois.add(new Poi(new Pos(0,0,20), PoiKind.BED, "bed", null));
        ProfileBounds bounds = new ProfileBounds(profile);
        assertTrue(bounds.contains(new Pos(10,0,0)));
        assertTrue(bounds.contains(new Pos(20,0,10)));
        assertFalse(bounds.contains(new Pos(10,0,10)), "Must not cut across the registered corner");
        assertFalse(bounds.contains(new Pos(0,0,10)), "POIs do not create implicit straight corridors");
    }

    @Test void pathStaysInsideApprovedLoadedTerrainAndCanDetour() {
        FakeWorld world = new FakeWorld();
        Profile profile = farmProfile(new Pos(5,0,2));
        world.obstacles.add(new Pos(2,0,0));
        List<Pos> path = new LocalPathfinder().find(ORIGIN, new Pos(5,0,0), .2, world, profile);
        assertFalse(path.isEmpty());
        assertFalse(path.contains(new Pos(2,0,0)));
        assertTrue(path.stream().allMatch(p -> ProfileBounds.contains(profile,p) && world.loaded(p)));
        world.unloaded.add(new Pos(5,0,0));
        assertTrue(new LocalPathfinder().find(ORIGIN, new Pos(5,0,0), .2, world, profile).isEmpty());
    }

    @Test void unregisteredGapBetweenWorkSitesCannotBeTraversed() {
        FakeWorld world = new FakeWorld();
        Profile profile = new Profile();
        profile.corridorRadius = 1;
        profile.pois.add(new Poi(ORIGIN, PoiKind.BED, "a", null));
        profile.pois.add(new Poi(new Pos(10,0,0), PoiKind.BED, "b", null));
        assertTrue(new LocalPathfinder().find(ORIGIN, new Pos(10,0,0), .2, world, profile).isEmpty());
    }

    @Test void distantSearchDoesNotRayTraceEveryVisitedCell() {
        FakeWorld world = new FakeWorld();
        List<Pos> path = new LocalPathfinder().find(ORIGIN,new Pos(100,0,0),.2,world,farmProfile(new Pos(100,0,0)));
        assertFalse(path.isEmpty());
        assertTrue(world.goalChecks < 10,"Only positions near the target should need ray traces");
    }

    @Test void navigatorStopsOnStallAndNeverRequestsJump() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world, actions, navigation, farmProfile(new Pos(6,0,1)));
        assertEquals(Navigation.Result.MOVING, navigation.moveTo(new Pos(6,0,0), .4, context));
        assertNotNull(actions.movement);
        assertFalse(actions.movement.jump());
        world.now = 61;
        assertEquals(Navigation.Result.BLOCKED, navigation.moveTo(new Pos(6,0,0), .4, context));
        assertNull(actions.movement);
        assertTrue(navigation.failureReason().contains("3초"));
    }

    @Test void partialHeightFarmlandUsesAirCellAbovePhysicalFeetForPathfinding() {
        FakeWorld world = new FakeWorld();
        world.y = -.0625;
        FakeActions actions = new FakeActions();
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world,actions,navigation,farmProfile(new Pos(6,0,0)));
        assertEquals(-1,world.player().feet().y());
        assertEquals(Navigation.Result.MOVING,navigation.moveTo(new Pos(6,0,0),.4,context));
        assertFalse(actions.movement.jump());
    }

    @Test void closedDoorMustBeAcknowledgedBeforeContinuing() {
        FakeWorld world = new FakeWorld();
        Pos door = new Pos(1,0,0);
        world.blocks.put(door, new BlockData(door,"minecraft:oak_door",Map.of("open","false")));
        FakeActions actions = new FakeActions();
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world, actions, navigation, farmProfile(new Pos(5,0,0)));
        assertEquals(Navigation.Result.MOVING, navigation.moveTo(new Pos(5,0,0), .4, context));
        assertEquals(new Action.UseBlock(door, Action.Use.DOOR), actions.submitted.get(0));
        world.now++;
        navigation.moveTo(new Pos(5,0,0), .4, context);
        assertEquals(1, actions.submitted.size());
        assertNull(actions.movement);
        actions.complete(true);
        world.blocks.put(door, new BlockData(door,"minecraft:oak_door",Map.of("open","true")));
        world.now++;
        navigation.moveTo(new Pos(5,0,0), .4, context);
        assertNotNull(actions.movement);
        assertFalse(actions.movement.jump());
    }

    @Test void harvestWaitsForGrowthAcknowledgementAndActualPickup() {
        FakeWorld world = new FakeWorld();
        world.tomato(ORIGIN,3);
        world.tomato(new Pos(1,0,0),2);
        FakeActions actions = new FakeActions();
        HarvestModule module = new HarvestModule();
        Context context = new Context(world, actions, new ArrivedNavigation(), farmProfile(new Pos(1,0,0)));
        assertEquals(WorkResult.State.BUSY, module.tick(context).state());
        assertEquals(List.of(new Action.UseBlock(ORIGIN, Action.Use.HARVEST)), actions.submitted);
        for (int i = 0; i < 15; i++) { world.now++; module.tick(context); }
        assertEquals(1, actions.submitted.size(), "No repeated click while acknowledgement is pending");
        world.tomato(ORIGIN,0);
        actions.complete(true);
        module.tick(context);
        world.now += 15;
        assertEquals(WorkResult.State.BUSY, module.tick(context).state(), "State change alone does not prove pickup");
        world.put(1,new ItemData(ItemData.TOMATO,2,0,null,false,0));
        world.now++;
        module.tick(context);
        world.now++;
        assertEquals(WorkResult.State.IDLE, module.tick(context).state());
        assertEquals(1, actions.submitted.size(), "Immature tomatoes are never clicked");
    }

    @Test void unripeFarmWaitsUntilNextGameDayEvenAfterReset() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new ArrivedNavigation(),farmProfile(ORIGIN));
        world.tomato(ORIGIN,0);
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        world.tomato(ORIGIN,3);
        world.now = 300;
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        module.reset();
        world.now = 600;
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        assertEquals(1L,context.profile().nextEligibleDay.get("harvest:first"));
        world.day = 29000;
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        assertEquals(new Action.UseBlock(ORIGIN,Action.Use.HARVEST),actions.submitted.get(0));
    }

    @Test void dawnScanWaitsForDailyGrowthBeforeDeferringFarm() {
        FakeWorld world = new FakeWorld();
        world.day = 24000;
        world.tomato(ORIGIN,2);
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(ORIGIN);
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new ArrivedNavigation(),profile);
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        assertTrue(profile.nextEligibleDay.isEmpty());
        world.tomato(ORIGIN,3);
        world.day = 24020;
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        assertEquals(new Action.UseBlock(ORIGIN,Action.Use.HARVEST),actions.submitted.get(0));
    }

    @Test void completedFarmPersistsConfiguredGameDayDeadline() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(ORIGIN);
        profile.harvestCycleDays = 3;
        world.tomato(ORIGIN,3);
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new ArrivedNavigation(),profile);
        module.tick(context);
        world.tomato(ORIGIN,0);
        world.put(1,new ItemData(ItemData.TOMATO,1,0,null,false,0));
        actions.complete(true);
        module.tick(context);
        world.now = 11;
        module.tick(context);
        assertEquals(3L,profile.nextEligibleDay.get("harvest:first"));
        module = new HarvestModule();
        world.tomato(ORIGIN,3);
        world.day = 53000;
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        assertEquals(1,actions.submitted.size());
        world.day = 77000;
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        assertEquals(2,actions.submitted.size());
    }

    @Test void fullInventoryRelinquishesWorkWithoutClickingCrop() {
        FakeWorld world = new FakeWorld();
        for (int i = 1; i < 36; i++) world.put(i,new ItemData("minecraft:stone",64,0,null,false,0));
        world.tomato(ORIGIN,3);
        FakeActions actions = new FakeActions();
        Context context = new Context(world,actions,new ArrivedNavigation(),farmProfile(ORIGIN));
        assertEquals(WorkResult.State.BLOCKED,new HarvestModule().tick(context).state());
        assertTrue(actions.submitted.isEmpty());
    }

    @Test void buddingTomatoIsNotHarvestableEvenAtAgeThree() {
        FakeWorld world = new FakeWorld();
        world.blocks.put(ORIGIN,new BlockData(ORIGIN,"farmersdelight:budding_tomatoes",Map.of("age","3")));
        FakeActions actions = new FakeActions();
        Context context = new Context(world,actions,new ArrivedNavigation(),farmProfile(ORIGIN));
        assertEquals(WorkResult.State.IDLE,new HarvestModule().tick(context).state());
        assertTrue(actions.submitted.isEmpty());
    }

    @Test void usesConfiguredHoeSlotWithAcknowledgedSwapAndSelection() {
        FakeWorld world = new FakeWorld();
        ItemData hoe = world.slots.get(0).item();
        world.put(0,ItemData.EMPTY);
        world.put(14,hoe);
        world.tomato(ORIGIN,3);
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(ORIGIN);
        profile.hoeHotbarSlot = 2;
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new ArrivedNavigation(),profile);
        module.tick(context);
        assertEquals(new Action.SwapHotbar(14,2),actions.submitted.get(0));
        world.put(14,ItemData.EMPTY);
        world.put(2,hoe);
        actions.complete(true);
        module.tick(context);
        assertEquals(new Action.SelectHotbar(2),actions.submitted.get(1));
        actions.complete(true);
        world.selected = 2;
        module.tick(context);
        assertEquals(new Action.UseBlock(ORIGIN,Action.Use.HARVEST),actions.submitted.get(2));
    }

    @Test void sweepIncludesBothSectorsAndUpperVinesInSerpentineOrder() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(new Pos(1,1,1));
        profile.farms.add(new Farm("second",new Pos(4,0,0),new Pos(4,0,0)));
        List<Pos> expected = List.of(ORIGIN,new Pos(0,1,0),new Pos(1,0,0),new Pos(1,0,1),new Pos(0,0,1),new Pos(4,0,0));
        for (Pos p : expected) world.tomato(p,3);
        Pos upper = new Pos(0,1,0);
        world.blocks.put(upper,new BlockData(upper,"farmersdelight:tomatoes_on_rope",Map.of("age","3","ropelogged","true")));
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new ArrivedNavigation(),profile);
        for (int i = 0; i < 200; i++) {
            module.tick(context);
            if (actions.busy()) {
                Action.UseBlock use = (Action.UseBlock)actions.submitted.get(actions.submitted.size()-1);
                world.tomato(use.pos(),0);
                world.put(1,new ItemData(ItemData.TOMATO,actions.submitted.size(),0,null,false,0));
                actions.complete(true);
            }
            world.now++;
        }
        assertEquals(expected, actions.submitted.stream().map(a -> ((Action.UseBlock)a).pos()).toList());
    }

    @Test void calibrationCannotEnableSprintWithoutActualMovement() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(new Pos(7,0,0));
        for (int i=0;i<8;i++) world.tomato(new Pos(i,0,0),3);
        HarvestModule module = new HarvestModule();
        module.startCalibration();
        Context context = new Context(world,actions,new ArrivedNavigation(),profile);
        for (int i=0;i<300 && module.calibrating();i++) {
            module.tick(context);
            if (actions.busy()) {
                int count = actions.submitted.size();
                if (count <= 4) assertFalse(profile.sprintCalibrated);
                world.now += count <= 4 ? 25 : 2;
                Action.UseBlock use = (Action.UseBlock)actions.submitted.get(count-1);
                world.tomato(use.pos(),0);
                world.put(1,new ItemData(ItemData.TOMATO,count,0,null,false,0));
                actions.complete(true);
            }
            world.now++;
        }
        assertFalse(module.calibrating());
        assertTrue(profile.sprintCalibrated);
        assertFalse(profile.sprintHarvest);
        assertTrue(module.calibrationStatus().contains("이동 거리 부족"));
        assertTrue(module.calibrationStatus().contains("틱/개"));
        assertTrue(module.calibrationStatus().contains("누락 0/4"));
    }

    @Test void calibrationMeasuresBothMovementModesBeforeEnablingFasterSprint() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(new Pos(21,0,0));
        for (int i=0;i<8;i++) world.tomato(new Pos(i*3,0,0),3);
        HarvestModule module = new HarvestModule();
        module.startCalibration();
        Context context = new Context(world,actions,new LocalNavigator(),profile);
        for (int i=0;i<1000 && module.calibrating();i++) {
            WorkResult result = module.tick(context);
            assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());
            if (actions.busy()) {
                Action.UseBlock use = (Action.UseBlock)actions.submitted.get(actions.submitted.size()-1);
                world.tomato(use.pos(),0);
                world.put(1,new ItemData(ItemData.TOMATO,actions.submitted.size(),0,null,false,0));
                actions.complete(true);
            }
            if (actions.movement != null && actions.movement.forward()) {
                double speed = actions.movement.sprint() ? .28 : .10;
                double yaw = Math.toRadians(actions.movement.yaw());
                world.x -= Math.sin(yaw)*speed;
                world.z += Math.cos(yaw)*speed;
            }
            world.now++;
        }
        assertFalse(module.calibrating(),module.calibrationStatus());
        assertTrue(profile.sprintCalibrated);
        assertTrue(profile.sprintHarvest,module.calibrationStatus());
    }

    @Test void calibrationCannotCarryMeasurementsIntoAnotherServerProfile() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        HarvestModule module = new HarvestModule();
        module.startCalibration();
        module.tick(new Context(world,actions,new ArrivedNavigation(),farmProfile(ORIGIN)));
        assertTrue(module.calibrating());
        module.reset();
        module.tick(new Context(world,actions,new ArrivedNavigation(),farmProfile(ORIGIN)));
        assertFalse(module.calibrating());
    }

    @Test void failedHarvestSamplesCannotValidateSprintEvenWhenMovementIsFaster() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(new Pos(21,0,0));
        for (int i=0;i<8;i++) world.tomato(new Pos(i*3,0,0),3);
        HarvestModule module = new HarvestModule();
        module.startCalibration();
        Context context = new Context(world,actions,new LocalNavigator(),profile);
        for (int i=0;i<1000 && module.calibrating();i++) {
            module.tick(context);
            if (actions.busy()) {
                Action.UseBlock use = (Action.UseBlock)actions.submitted.get(actions.submitted.size()-1);
                world.tomato(use.pos(),0);
                actions.complete(false);
            }
            if (actions.movement != null && actions.movement.forward()) {
                double speed = actions.movement.sprint() ? .28 : .10;
                double yaw = Math.toRadians(actions.movement.yaw());
                world.x -= Math.sin(yaw)*speed;
                world.z += Math.cos(yaw)*speed;
            }
            world.now++;
        }
        assertFalse(module.calibrating());
        assertTrue(profile.sprintCalibrated);
        assertFalse(profile.sprintHarvest);
        assertTrue(module.calibrationStatus().contains("누락 4/4"));
    }

    private static Profile farmProfile(Pos end) {
        Profile profile = new Profile();
        profile.farms.add(new Farm("first",ORIGIN,end));
        return profile;
    }

    private static final class ArrivedNavigation implements Navigation {
        public Result moveTo(Pos target,double reach,Context context) { return Result.ARRIVED; }
        public void reset() { }
    }

    private static final class FakeActions implements ActionPort {
        final List<Action> submitted = new ArrayList<>();
        final Map<Long,ActionOutcome> outcomes = new HashMap<>();
        long current;
        Movement movement;
        public boolean busy() { return current > 0 && !outcomes.get(current).done(); }
        public long submit(Action action) {
            if (busy()) throw new AssertionError("Overlapping action");
            submitted.add(action);
            outcomes.put(++current,new ActionOutcome(ActionOutcome.State.PENDING,""));
            return current;
        }
        void complete(boolean success) { outcomes.put(current,new ActionOutcome(success ? ActionOutcome.State.SUCCEEDED : ActionOutcome.State.FAILED,"test")); }
        public ActionOutcome outcome(long ticket) { return outcomes.get(ticket); }
        public void move(Movement request) { movement=request; }
        public void stopMovement() { movement=null; }
        public void cancel() { stopMovement(); if (busy()) outcomes.put(current,new ActionOutcome(ActionOutcome.State.CANCELLED,"")); }
    }

    private static final class FakeWorld implements WorldAccess {
        long now;
        long day = 5000;
        double x = .5, y, z = .5;
        int selected;
        int goalChecks;
        final Map<Pos,BlockData> blocks = new HashMap<>();
        final Set<Pos> obstacles = new HashSet<>(), unloaded = new HashSet<>();
        final List<ItemSlot> slots = new ArrayList<>();
        FakeWorld() {
            for (int i=0;i<36;i++) slots.add(new ItemSlot(i,i,true,ItemData.EMPTY));
            put(0,new ItemData("minecraft:iron_hoe",1,0,null,true,200));
        }
        void put(int i,ItemData item) { slots.set(i,new ItemSlot(i,i,true,item)); }
        void tomato(Pos p,int age) { blocks.put(p,new BlockData(p,"farmersdelight:tomatoes",Map.of("age",String.valueOf(age)))); }
        public long tick() { return now; }
        public long dayTime() { return day; }
        public PlayerState player() { return new PlayerState(x,y,z,0,0,true,false,20,20,selected,true,true); }
        public BlockData block(Pos p) { return blocks.getOrDefault(p,new BlockData(p,p.y()<0 ? "minecraft:stone" : "minecraft:air",Map.of())); }
        public boolean loaded(Pos p) { return !unloaded.contains(p); }
        public boolean canStand(Pos p) { return p.y()==0 && !obstacles.contains(p); }
        public boolean canTraverse(Pos from,Pos to) { return canStand(to); }
        public List<BlockData> scan(Pos center,int radius,int vertical) { return List.copyOf(blocks.values()); }
        public List<ItemSlot> inventory() { return slots; }
        public MenuData menu() { return new MenuData(0,0,slots,ItemData.EMPTY,false); }
        public boolean mayPlace(int slot,ItemData item) { return true; }
        public boolean canInteractFrom(Pos feet,Pos target,double reach) { goalChecks++; return WorldAccess.super.canInteractFrom(feet,target,reach); }
    }
}
