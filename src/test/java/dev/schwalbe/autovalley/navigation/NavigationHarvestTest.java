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

    @Test void verticalPathHonorsWorldGeometryForCustomSupports() {
        FakeWorld world = new FakeWorld();
        Pos raised = new Pos(1,1,0);
        world.extraStandable.add(raised);
        world.obstacles.add(raised.offset(0,-1,0));
        world.blocks.put(raised.offset(0,-1,0),new BlockData(raised.offset(0,-1,0),"example:custom_ramp",Map.of()));
        Profile profile = farmProfile(raised);
        LocalPathfinder pathfinder = new LocalPathfinder();
        assertFalse(pathfinder.find(ORIGIN,raised,.2,world,profile).isEmpty());
        world.allowAscent = false;
        assertTrue(pathfinder.find(ORIGIN,raised,.2,world,profile).isEmpty(),"A blocked step must not become a jump route");
    }

    @Test void allowedVerticalSteeringNeverRequestsAJump() {
        FakeWorld world = new FakeWorld();
        Pos raised = new Pos(1,1,0);
        world.extraStandable.add(raised);
        world.obstacles.add(raised.offset(0,-1,0));
        FakeActions actions = new FakeActions();
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world,actions,navigation,farmProfile(raised));
        assertEquals(Navigation.Result.MOVING,navigation.moveTo(raised,.2,context));
        assertNotNull(actions.movement);
        assertFalse(actions.movement.jump());
    }

    @Test void invalidInteractionAtFinalApproachCenterSettlesWithoutForwardDrift() {
        FakeWorld world = new FakeWorld();
        world.allowCurrentInteraction = false;
        FakeActions actions = new FakeActions();
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world,actions,navigation,farmProfile(ORIGIN));
        assertEquals(Navigation.Result.MOVING,navigation.moveTo(ORIGIN,2.15,context));
        assertNull(actions.movement);
        assertTrue(actions.submitted.isEmpty());
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

    @Test void unfocusedNavigationContinuesWhenBackgroundOperationIsAllowed() {
        FakeWorld world = new FakeWorld();
        world.focused = false;
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(new Pos(6,0,0));
        profile.allowBackground = true;
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world,actions,navigation,profile);
        assertEquals(Navigation.Result.MOVING,navigation.moveTo(new Pos(6,0,0),.4,context));
        assertNotNull(actions.movement);
        assertTrue(actions.movement.forward());
    }

    @Test void unfocusedNavigationStopsWhenBackgroundOperationIsDisabled() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(new Pos(6,0,0));
        profile.allowBackground = false;
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world,actions,navigation,profile);
        assertEquals(Navigation.Result.MOVING,navigation.moveTo(new Pos(6,0,0),.4,context));
        world.focused = false;
        assertEquals(Navigation.Result.BLOCKED,navigation.moveTo(new Pos(6,0,0),.4,context));
        assertNull(actions.movement);
    }

    @Test void disconnectedNavigationStillStopsWithBackgroundOperationAllowed() {
        FakeWorld world = new FakeWorld();
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(new Pos(6,0,0));
        profile.allowBackground = true;
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world,actions,navigation,profile);
        assertEquals(Navigation.Result.MOVING,navigation.moveTo(new Pos(6,0,0),.4,context));
        world.connected = false;
        world.focused = false;
        assertEquals(Navigation.Result.BLOCKED,navigation.moveTo(new Pos(6,0,0),.4,context));
        assertNull(actions.movement);
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

    @Test void harvestWaitsForAcknowledgementAndCropStateButNotInventoryPickup() {
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
        assertEquals(WorkResult.State.BUSY, module.tick(context).state(), "Completing the acknowledged crop transition does not depend on item pickup");
        world.now++;
        module.tick(context);
        world.now++;
        assertEquals(WorkResult.State.IDLE, module.tick(context).state());
        assertEquals(1, actions.submitted.size(), "Immature tomatoes are never clicked");
    }

    @Test void areaHarvestUsesSeparatedCentersAndSkipsOnlyObservedNeighbourChanges() {
        FakeWorld world = areaWorld(6,3);
        FakeActions actions = new FakeActions();
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new ArrivedNavigation(),farmProfile(new Pos(5,0,2)));
        WorkResult result = null;
        for (int tick=0;tick<100;tick++) {
            result = module.tick(context);
            assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());
            if (actions.busy()) {
                Pos clicked = ((Action.UseBlock)actions.submitted.get(actions.submitted.size()-1)).pos();
                for (Pos crop:List.copyOf(world.blocks.keySet()))
                    if (HarvestRoutePlanner.withinFootprint(clicked,crop,1)) world.tomato(crop,0);
                world.put(1,new ItemData(ItemData.TOMATO,actions.submitted.size()*9,0,null,false,0));
                actions.complete(true);
            }
            world.now++;
            if (result.state()==WorkResult.State.IDLE) break;
        }
        assertNotNull(result);
        assertEquals(WorkResult.State.IDLE,result.state());
        assertEquals(List.of(new Pos(1,0,1),new Pos(4,0,1)),actions.submitted.stream().map(a -> ((Action.UseBlock)a).pos()).toList());
        assertEquals(1L,context.profile().nextEligibleDay.get("harvest:first"));
    }

    @Test void nativeCoverageHintWithoutNeighbourChangesRetainsEveryFallbackClick() {
        FakeWorld world = areaWorld(3,3);
        FakeActions actions = new FakeActions();
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new ArrivedNavigation(),farmProfile(new Pos(2,0,2)));
        Set<Pos> clicked = new HashSet<>();
        for (int tick=0;tick<200;tick++) {
            WorkResult result = module.tick(context);
            assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());
            if (actions.busy()) {
                Pos crop = ((Action.UseBlock)actions.submitted.get(actions.submitted.size()-1)).pos();
                clicked.add(crop);
                world.tomato(crop,0); // Server applied ONLY the selected crop, not the local area hint.
                world.put(1,new ItemData(ItemData.TOMATO,actions.submitted.size(),0,null,false,0));
                actions.complete(true);
            }
            world.now++;
            if (result.state()==WorkResult.State.IDLE) break;
        }
        assertEquals(new Pos(1,0,1),((Action.UseBlock)actions.submitted.get(0)).pos());
        assertEquals(9,clicked.size());
        assertEquals(9,actions.submitted.size());
    }

    @Test void unknownNativeAreaOrForeignCropBlocksBeforeAnyUse() {
        FakeWorld world = areaWorld(3,3);
        world.footprintKnown = false;
        FakeActions actions = new FakeActions();
        Context context = new Context(world,actions,new ArrivedNavigation(),farmProfile(new Pos(2,0,2)));
        assertEquals(WorkResult.State.BLOCKED,new HarvestModule().tick(context).state());
        assertTrue(actions.submitted.isEmpty());
        world.footprintKnown = true;
        Pos foreign = new Pos(1,1,1);
        world.blocks.put(foreign,new BlockData(foreign,"minecraft:wheat",Map.of("age","7")));
        assertEquals(WorkResult.State.BLOCKED,new HarvestModule().tick(context).state());
        assertTrue(actions.submitted.isEmpty(),"An upper fallback crop must not receive the area use");
    }

    @Test void movingHarvestMaintainsSteeringThroughPendingAckAndOverflowOutputSettle() {
        FakeWorld world = areaWorld(6,3);
        FakeActions actions = new FakeActions();
        actions.movingHarvest = true;
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new LocalNavigator(),farmProfile(new Pos(5,0,2)));
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        assertEquals(1,actions.submitted.size());
        assertNotNull(actions.movement,"A separated next center can be approached in the same tick as use");
        assertFalse(actions.movement.jump());
        world.now = 1;
        module.tick(context);
        assertNotNull(actions.movement);
        assertEquals(1,actions.submitted.size(),"Moving must not create a second pending click");
        Pos clicked = ((Action.UseBlock)actions.submitted.get(0)).pos();
        for (Pos crop:List.copyOf(world.blocks.keySet()))
            if (HarvestRoutePlanner.withinFootprint(clicked,crop,1)) world.tomato(crop,0);
        world.put(1,new ItemData(ItemData.TOMATO,9,0,null,false,0));
        actions.complete(true);
        world.now = 2;
        module.tick(context);
        assertNotNull(actions.movement,"ACK alone should not force a stop while output settles");
        world.now = 4;
        module.tick(context);
        assertNotNull(actions.movement,"Confirmed output leaves safe lookahead steering for the next tick");
        assertEquals(1,actions.submitted.size());
    }

    @Test void longHarvestAckStopsLookaheadWithoutRetryingOrClaimingCompletion() {
        FakeWorld world = areaWorld(6,3);
        FakeActions actions = new FakeActions();
        actions.movingHarvest = true;
        HarvestModule module = new HarvestModule();
        Context context = new Context(world,actions,new LocalNavigator(),farmProfile(new Pos(5,0,2)));
        module.tick(context);
        assertNotNull(actions.movement);
        world.now = 10;
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        assertNull(actions.movement);
        world.now = 15;
        module.tick(context);
        assertNull(actions.movement);
        assertEquals(1,actions.submitted.size());
        assertTrue(context.profile().nextEligibleDay.isEmpty());
    }

    @Test void tractorContinuationKeepsOnlyTheImmediateSameRowCenterAndStopsAtTheTurn() {
        FakeWorld world=areaWorld(6,6);
        FakeActions actions=new FakeActions(); actions.movingHarvest=true;
        SteeringNavigation navigation=new SteeringNavigation();
        HarvestModule module=new HarvestModule();
        Context context=new Context(world,actions,navigation,farmProfile(new Pos(5,0,5)));
        module.tick(context);
        assertEquals(List.of(new Pos(4,0,1)),navigation.steered);
        assertEquals(1,actions.submitted.size());
        // The first area ACK changes only its observed footprint.
        for (Pos crop:List.copyOf(world.blocks.keySet()))
            if (HarvestRoutePlanner.withinFootprint(new Pos(1,0,1),crop,1)) world.tomato(crop,0);
        actions.complete(true);
        for (int i=0;i<10 && actions.submitted.size()<2;i++) { world.now++; module.tick(context); }
        assertEquals(new Action.UseBlock(new Pos(4,0,1),Action.Use.HARVEST),actions.submitted.get(1));
        assertFalse(navigation.steered.isEmpty());
        assertTrue(navigation.steered.stream().allMatch(new Pos(4,0,1)::equals),
            "The next row must wait for the current native ACK; no diagonal turn during use");
        assertTrue(actions.busy());
    }

    @Test void distantImmediateCenterCannotBeOvertakenByNearbyCleanupTargets() {
        FakeWorld world=areaWorld(12,3);
        for (Pos crop:List.copyOf(world.blocks.keySet())) world.tomato(crop,0);
        for (Pos crop:List.of(new Pos(1,0,1),new Pos(10,0,1),new Pos(3,0,0))) world.tomato(crop,3);
        FakeActions actions=new FakeActions(); actions.movingHarvest=true;
        SteeringNavigation navigation=new SteeringNavigation();
        HarvestModule module=new HarvestModule();
        Context context=new Context(world,actions,navigation,farmProfile(new Pos(11,0,2)));
        module.tick(context);
        assertEquals(new Action.UseBlock(new Pos(1,0,1),Action.Use.HARVEST),actions.submitted.get(0));
        for (int i=0;i<5;i++) { world.now++; module.tick(context); }
        assertTrue(navigation.steered.isEmpty(),"Do not scan past the distant next center for an off-row crop");
        assertEquals(1,actions.submitted.size(),"No repeated or overlapping right-click while waiting");
    }

    @Test void separateFarmEvenOnTheSameGeometricRowCannotBecomePendingUseContinuation() {
        FakeWorld world=areaWorld(6,3);
        FakeActions actions=new FakeActions(); actions.movingHarvest=true;
        SteeringNavigation navigation=new SteeringNavigation();
        Profile profile=farmProfile(new Pos(2,0,2));
        profile.farms.add(new Farm("second",new Pos(3,0,0),new Pos(5,0,2)));
        HarvestModule module=new HarvestModule();
        Context context=new Context(world,actions,navigation,profile);
        module.tick(context);
        assertTrue(navigation.steered.isEmpty());
        assertEquals(1,actions.submitted.size());
    }

    @Test void fixedStripAndCleanupPassStillHarvestEveryUnchangedUpperVineOnlyAfterItsOwnAck() {
        FakeWorld world=areaWorld(6,3);
        for (int z=0;z<3;z++) for (int x=0;x<6;x++) {
            Pos upper=new Pos(x,1,z);
            world.blocks.put(upper,new BlockData(upper,"farmersdelight:tomatoes_on_rope",Map.of("age","3")));
        }
        FakeActions actions=new FakeActions(); actions.movingHarvest=true;
        SteeringNavigation navigation=new SteeringNavigation();
        HarvestModule module=new HarvestModule();
        Context context=new Context(world,actions,navigation,farmProfile(new Pos(5,1,2)));
        WorkResult result=null;
        for (int i=0;i<300;i++) {
            result=module.tick(context);
            assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());
            if (actions.busy()) {
                Action.UseBlock use=(Action.UseBlock)actions.submitted.get(actions.submitted.size()-1);
                assertEquals(Action.Use.HARVEST,use.purpose());
                assertTrue(world.block(use.pos()).matureTomato());
                world.tomato(use.pos(),0); // Deliberately no adjacent or upper-vine effect.
                actions.complete(true);
            }
            world.now++;
            if (result.state()==WorkResult.State.IDLE) break;
        }
        assertEquals(WorkResult.State.IDLE,result.state());
        assertEquals(36,actions.submitted.size());
        assertEquals(36,actions.submitted.stream().map(a -> ((Action.UseBlock)a).pos()).distinct().count());
        assertEquals(List.of(new Pos(1,0,1),new Pos(4,0,1)),
            actions.submitted.subList(0,2).stream().map(a -> ((Action.UseBlock)a).pos()).toList());
        assertTrue(navigation.steered.stream().allMatch(new Pos(4,0,1)::equals),"Cleanup cannot borrow primary-row lookahead");
    }

    @Test void movingHarvestOptInAndOverflowAreBothRequired() {
        for (boolean overflow:List.of(false,true)) {
            FakeWorld world = areaWorld(6,3);
            FakeActions actions = new FakeActions();
            actions.movingHarvest = !overflow;
            Profile profile = farmProfile(new Pos(5,0,2));
            profile.continueHarvestWhenFull = overflow;
            Context context = new Context(world,actions,new LocalNavigator(),profile);
            new HarvestModule().tick(context);
            assertEquals(1,actions.submitted.size());
            assertNull(actions.movement);
        }
    }

    @Test void absentOrDistantContinuationStopsSoftlyWithoutBlockingCurrentHarvest() {
        for (Pos next:List.of(new Pos(1,0,0),new Pos(8,0,0))) {
            FakeWorld world = new FakeWorld();
            world.harvestRadius = 1;
            world.tomato(ORIGIN,3);
            world.tomato(next,3);
            FakeActions actions = new FakeActions();
            actions.movingHarvest = true;
            Context context = new Context(world,actions,new LocalNavigator(),farmProfile(next));
            assertEquals(WorkResult.State.BUSY,new HarvestModule().tick(context).state());
            assertEquals(1,actions.submitted.size());
            assertNull(actions.movement);
        }
    }

    @Test void noInteractionNavigatorNeverOpensDoorBeforeOrAfterHarvestAck() {
        FakeWorld world = new FakeWorld();
        Pos door = new Pos(1,0,0);
        world.blocks.put(door,new BlockData(door,"minecraft:oak_door",Map.of("open","false")));
        FakeActions actions = new FakeActions();
        LocalNavigator navigation = new LocalNavigator();
        Context context = new Context(world,actions,navigation,farmProfile(new Pos(5,0,0)));
        actions.submit(new Action.UseBlock(ORIGIN,Action.Use.HARVEST));
        assertEquals(Navigation.Result.BLOCKED,navigation.moveToWithoutInteraction(new Pos(5,0,0),.4,context));
        assertEquals(1,actions.submitted.size());
        assertNull(actions.movement);
        actions.complete(true);
        navigation.reset();
        assertEquals(Navigation.Result.BLOCKED,navigation.moveToWithoutInteraction(new Pos(5,0,0),.4,context));
        assertEquals(1,actions.submitted.size());
        assertNull(actions.movement);
    }

    private static FakeWorld areaWorld(int width,int depth) {
        FakeWorld world = new FakeWorld();
        world.harvestRadius = 1;
        for (int x=0;x<width;x++) for (int z=0;z<depth;z++) world.tomato(new Pos(x,0,z),3);
        return world;
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

    @Test void twoDayCadenceSkipsPhasedRipeCropsOnDay372AndSurvivesModuleReset() {
        FakeWorld world=new FakeWorld();world.day=372L*24000+5000;
        world.tomato(ORIGIN,2);world.tomato(new Pos(1,0,0),2);
        world.tomato(new Pos(4,0,0),2);world.tomato(new Pos(5,0,0),3);
        Profile profile=farmProfile(new Pos(1,0,0));profile.harvestCycleDays=2;
        profile.farms.add(new Farm("second",new Pos(4,0,0),new Pos(5,0,0)));
        profile.nextEligibleDay.put("harvest:first",373L);profile.nextEligibleDay.put("harvest:second",373L);
        FakeActions actions=new FakeActions();RecordingNavigation navigation=new RecordingNavigation();
        Context context=new Context(world,actions,navigation,profile);HarvestModule module=new HarvestModule();
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        module.reset();world.now+=200;
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        assertEquals(WorkResult.State.IDLE,new HarvestModule().tick(context).state(),"A recreated module uses the persisted day gates too");
        assertEquals(Map.of("harvest:first",373L,"harvest:second",373L),profile.nextEligibleDay);
        assertTrue(actions.submitted.isEmpty(),"The independently ripe second-farm crop must not trigger an early partial harvest");
        assertTrue(navigation.reaches.isEmpty(),"A future farm deadline must not cause a visit");
        assertNull(actions.movement);
    }

    @Test void alignedDay373HarvestConfirmsEveryCropOnceAndSetsBothFarmDeadlinesTo375() {
        FakeWorld world=new FakeWorld();world.day=373L*24000+5000;world.rejectGroundReads=true;
        List<Pos> crops=List.of(ORIGIN,new Pos(1,0,0),new Pos(4,0,0),new Pos(5,0,0));
        for (Pos crop:crops) world.tomato(crop,3);
        Profile profile=farmProfile(new Pos(1,0,0));profile.harvestCycleDays=2;
        profile.farms.add(new Farm("second",new Pos(4,0,0),new Pos(5,0,0)));
        profile.nextEligibleDay.put("harvest:first",373L);profile.nextEligibleDay.put("harvest:second",373L);
        FakeActions actions=new FakeActions();RecordingNavigation navigation=new RecordingNavigation();
        Context context=new Context(world,actions,navigation,profile);HarvestModule module=new HarvestModule();
        WorkResult result=WorkResult.busy("");int acknowledgements=0;
        for (int tick=0;tick<100;tick++) {
            result=module.tick(context);
            assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());
            if (actions.busy()) {
                Action.UseBlock use=assertInstanceOf(Action.UseBlock.class,actions.submitted.get(actions.submitted.size()-1));
                assertEquals(Action.Use.HARVEST,use.purpose());assertTrue(world.block(use.pos()).matureTomato());
                world.tomato(use.pos(),0);actions.complete(true);acknowledgements++;
            }
            world.now++;
            if (result.state()==WorkResult.State.IDLE) break;
        }
        assertEquals(WorkResult.State.IDLE,result.state());assertEquals(4,acknowledgements);
        assertEquals(crops,actions.submitted.stream().map(action -> ((Action.UseBlock)action).pos()).toList());
        assertEquals(Map.of("harvest:first",375L,"harvest:second",375L),profile.nextEligibleDay);
        module.reset();
        assertEquals(Map.of("harvest:first",375L,"harvest:second",375L),profile.nextEligibleDay);
    }

    @Test void eligibleButUnripeFarmRechecksEachNextMorningInsteadOfRepeatedTwoDayDeferral() {
        FakeWorld world=new FakeWorld();world.tomato(ORIGIN,2);
        Profile profile=farmProfile(ORIGIN);profile.harvestCycleDays=2;
        profile.nextEligibleDay.put("harvest:first",372L);
        FakeActions actions=new FakeActions();RecordingNavigation navigation=new RecordingNavigation();
        Context context=new Context(world,actions,navigation,profile);HarvestModule module=new HarvestModule();
        for (long day:List.of(372L,373L)) {
            world.day=day*24000+5000;
            assertEquals(WorkResult.State.IDLE,module.tick(context).state());
            assertEquals(day+1,profile.nextEligibleDay.get("harvest:first"));
            assertTrue(actions.submitted.isEmpty());assertTrue(navigation.reaches.isEmpty());assertNull(actions.movement);
            module.reset();
        }
        world.day=374L*24000+5000;world.tomato(ORIGIN,3);
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        assertEquals(List.of(new Action.UseBlock(ORIGIN,Action.Use.HARVEST)),actions.submitted);
        world.tomato(ORIGIN,0);actions.complete(true);
        WorkResult result=WorkResult.busy("");
        for (int tick=0;tick<20 && result.state()!=WorkResult.State.IDLE;tick++) {world.now++;result=module.tick(context);}
        assertEquals(WorkResult.State.IDLE,result.state());assertEquals(376L,profile.nextEligibleDay.get("harvest:first"));
    }

    @Test void fullInventoryRelinquishesWorkWithoutClickingCrop() {
        FakeWorld world = new FakeWorld();
        for (int i = 1; i < 36; i++) world.put(i,new ItemData("minecraft:stone",64,0,null,false,0));
        world.tomato(ORIGIN,3);
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(ORIGIN);
        profile.continueHarvestWhenFull = false;
        Context context = new Context(world,actions,new ArrivedNavigation(),profile);
        assertEquals(WorkResult.State.BLOCKED,new HarvestModule().tick(context).state());
        assertTrue(actions.submitted.isEmpty());
    }

    @Test void unreachableRipeFarmStopsBeforeClickOrCompletionDeadline() {
        FakeWorld world = new FakeWorld();
        for (int z=-1;z<=1;z++) world.obstacles.add(new Pos(2,0,z));
        Pos crop = new Pos(5,0,0);
        world.tomato(crop,3);
        FakeActions actions = new FakeActions();
        Profile profile = farmProfile(crop);
        Context context = new Context(world,actions,new LocalNavigator(),profile);
        assertEquals(WorkResult.State.BLOCKED,new HarvestModule().tick(context).state());
        assertTrue(actions.submitted.isEmpty());
        assertNull(actions.movement);
        assertFalse(profile.nextEligibleDay.containsKey("harvest:first"));
    }

    @Test void acknowledgedHarvestFinishesWithoutWalkingToOrCountingDroppedItems() {
        FakeWorld world=new FakeWorld(); world.tomato(ORIGIN,3); world.rejectGroundReads=true;
        FakeActions actions=new FakeActions(); RecordingNavigation navigation=new RecordingNavigation();
        Profile profile=farmProfile(ORIGIN); profile.continueHarvestWhenFull=false;
        Context context=new Context(world,actions,navigation,profile); HarvestModule module=new HarvestModule();
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        world.tomato(ORIGIN,0); actions.complete(true);
        WorkResult result=WorkResult.busy("");
        for (int tick=0;tick<20 && result.state()!=WorkResult.State.IDLE;tick++) { result=module.tick(context); world.now++; }
        assertEquals(WorkResult.State.IDLE,result.state()); assertEquals(1,actions.submitted.size());
        assertEquals(1L,profile.nextEligibleDay.get("harvest:first"));
        assertTrue(navigation.reaches.stream().allMatch(reach -> reach>1.25));
        assertTrue(world.inventory().stream().noneMatch(slot -> slot.item().is(ItemData.TOMATO)));
    }

    @Test void fullInventorySweepFinishesFromCropAcknowledgementsAndMayRunOnTheNextDay() {
        FakeWorld world = new FakeWorld();
        for (int i=1;i<36;i++) world.put(i,new ItemData("minecraft:stone",64,0,null,false,0));
        Pos second = new Pos(1,0,0);
        world.tomato(ORIGIN,3); world.tomato(second,3);
        Profile profile = farmProfile(second);
        profile.continueHarvestWhenFull = true;
        FakeActions actions = new FakeActions();
        RecordingNavigation navigation = new RecordingNavigation();
        SessionState session = new SessionState();
        Context context = new Context(world,actions,navigation,profile,session);
        HarvestModule module = new HarvestModule();
        WorkResult result = WorkResult.busy("");
        for (int i=0;i<40;i++) {
            result = module.tick(context);
            assertNotEquals(WorkResult.State.BLOCKED,result.state(),result.message());
            if (actions.busy()) {
                Action.UseBlock use = (Action.UseBlock)actions.submitted.get(actions.submitted.size()-1);
                world.tomato(use.pos(),0);
                // Existing or newly dropped items do not govern the next crop action.
                world.ground.clear();
                world.ground.add(new GroundItem(7,.5,0,.5,new ItemData(ItemData.TOMATO,actions.submitted.size()*2,0,null,false,0)));
                actions.complete(true);
            }
            world.now++;
            if (result.state()==WorkResult.State.IDLE) break;
        }
        assertEquals(WorkResult.State.IDLE,result.state());
        assertEquals(2,actions.submitted.size());
        assertTrue(navigation.reaches.stream().allMatch(reach -> reach > 1.25),"Overflow output does not request per-crop pickup walks");
        assertTrue(world.inventory().stream().noneMatch(s -> s.item().is(ItemData.TOMATO)));
        assertEquals(1L,profile.nextEligibleDay.get("harvest:first"));
        module.reset();
        world.day = 29000;
        world.tomato(ORIGIN,3);
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        assertEquals(3,actions.submitted.size());
    }

    @Test void fullInventoryAndNoObservedOutputStillFinishAfterNativeAckAndCropChange() {
        FakeWorld world=new FakeWorld(); world.rejectGroundReads=true;
        for (int i=1;i<36;i++) world.put(i,new ItemData("minecraft:stone",64,0,null,false,0));
        world.tomato(ORIGIN,3);
        Profile profile=farmProfile(ORIGIN); profile.continueHarvestWhenFull=true;
        FakeActions actions=new FakeActions(); RecordingNavigation navigation=new RecordingNavigation();
        Context context=new Context(world,actions,navigation,profile,new SessionState()); HarvestModule module=new HarvestModule();
        assertEquals(WorkResult.State.BUSY,module.tick(context).state()); assertEquals(1,actions.submitted.size());
        world.tomato(ORIGIN,0); actions.complete(true);
        WorkResult result=WorkResult.busy("");
        for (int tick=0;tick<20 && result.state()!=WorkResult.State.IDLE;tick++) { result=module.tick(context); world.now++; }
        assertEquals(WorkResult.State.IDLE,result.state()); assertEquals(1L,profile.nextEligibleDay.get("harvest:first"));
        assertEquals(1,actions.submitted.size()); assertTrue(navigation.reaches.stream().allMatch(reach -> reach>1.25));
        assertTrue(world.inventory().stream().noneMatch(slot -> slot.item().is(ItemData.TOMATO)));
    }

    @Test void existingGroundTransferredIntoInventoryDoesNotChangeHarvestCompletionRules() {
        FakeWorld world = new FakeWorld();
        world.tomato(ORIGIN,3);
        world.ground.add(new GroundItem(7,.5,0,.5,new ItemData(ItemData.TOMATO,5,0,null,false,0)));
        Profile profile = farmProfile(ORIGIN);
        profile.continueHarvestWhenFull = true;
        FakeActions actions = new FakeActions();
        Context context = new Context(world,actions,new RecordingNavigation(),profile,new SessionState());
        HarvestModule module = new HarvestModule();
        module.tick(context);
        world.tomato(ORIGIN,0);
        world.ground.clear();
        world.put(1,new ItemData(ItemData.TOMATO,5,0,null,false,0));
        actions.complete(true);
        module.tick(context);
        world.now = profile.interactionTimeoutTicks+1;
        assertEquals(WorkResult.State.BUSY,module.tick(context).state());
        world.now++;
        assertEquals(WorkResult.State.IDLE,module.tick(context).state());
        assertEquals(1L,profile.nextEligibleDay.get("harvest:first"));
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
            WorkResult result = module.tick(context);
            if (result.state()==WorkResult.State.BLOCKED) module.reset(); // Simulated explicit take-over between failed samples.
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

    private static final class RecordingNavigation implements Navigation {
        final List<Double> reaches = new ArrayList<>();
        public Result moveTo(Pos target,double reach,Context context) { reaches.add(reach); return Result.ARRIVED; }
        public void reset() { }
    }

    private static final class SteeringNavigation implements Navigation {
        final List<Pos> steered=new ArrayList<>();
        public Result moveTo(Pos target,double reach,Context context) { return Result.ARRIVED; }
        public Result moveToWithoutInteraction(Pos target,double reach,Context context) {
            steered.add(target); return Result.MOVING;
        }
        public void reset() { }
    }

    private static final class FakeActions implements ActionPort {
        final List<Action> submitted = new ArrayList<>();
        final Map<Long,ActionOutcome> outcomes = new HashMap<>();
        long current;
        Movement movement;
        boolean movingHarvest;
        public boolean supportsMovingHarvest() { return movingHarvest; }
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
        double x = .5, y, z = .5, supportOffset;
        int selected;
        int harvestRadius;
        boolean footprintKnown = true;
        int goalChecks;
        boolean focused = true, connected = true;
        boolean allowAscent = true, allowCurrentInteraction = true;
        final Map<Pos,BlockData> blocks = new HashMap<>();
        final Set<Pos> obstacles = new HashSet<>(), unloaded = new HashSet<>();
        final Set<Pos> extraStandable = new HashSet<>();
        final List<ItemSlot> slots = new ArrayList<>();
        final List<GroundItem> ground = new ArrayList<>();
        boolean rejectGroundReads;
        FakeWorld() {
            for (int i=0;i<36;i++) slots.add(new ItemSlot(i,i,true,ItemData.EMPTY));
            put(0,new ItemData("minecraft:iron_hoe",1,0,null,true,200));
        }
        void put(int i,ItemData item) { slots.set(i,new ItemSlot(i,i,true,item)); }
        void tomato(Pos p,int age) { blocks.put(p,new BlockData(p,"farmersdelight:tomatoes",Map.of("age",String.valueOf(age)))); }
        public long tick() { return now; }
        public long dayTime() { return day; }
        public PlayerState player() { return new PlayerState(x,y,z,0,0,true,false,20,20,selected,connected,focused); }
        public BlockData block(Pos p) { return blocks.getOrDefault(p,new BlockData(p,p.y()<0 ? "minecraft:stone" : "minecraft:air",Map.of())); }
        public boolean loaded(Pos p) { return !unloaded.contains(p); }
        public boolean canStand(Pos p) { return (p.y()==0 || extraStandable.contains(p)) && !obstacles.contains(p); }
        public boolean canTraverse(Pos from,Pos to) { return canStand(to) && (allowAscent || to.y() <= from.y()); }
        public List<BlockData> scan(Pos center,int radius,int vertical) { return List.copyOf(blocks.values()); }
        public List<ItemSlot> inventory() { return slots; }
        public List<GroundItem> groundItems() { if (rejectGroundReads) throw new AssertionError("Harvest must not count ground items"); return ground; }
        public HarvestFootprint harvestFootprint(Pos target) {
            if (!footprintKnown) return HarvestFootprint.UNKNOWN;
            if (harvestRadius==0) return HarvestFootprint.single(target);
            Set<Pos> candidates = new LinkedHashSet<>();
            candidates.add(target);
            for (BlockData block:blocks.values())
                if ((block.tomato() || block.id().equals("minecraft:wheat")) && HarvestRoutePlanner.withinFootprint(target,block.pos(),harvestRadius))
                    candidates.add(block.pos());
            return new HarvestFootprint(true,harvestRadius,List.copyOf(candidates));
        }
        public MenuData menu() { return new MenuData(0,0,slots,ItemData.EMPTY,false); }
        public boolean mayPlace(int slot,ItemData item) { return true; }
        public boolean canInteract(Pos target,double reach) { return allowCurrentInteraction && WorldAccess.super.canInteract(target,reach); }
        public boolean canInteractFrom(Pos feet,Pos target,double reach) {
            goalChecks++;
            if (reach <= 1.25) return Math.pow(feet.x()-target.x(),2) + Math.pow(feet.y()+supportOffset-target.y(),2)
                + Math.pow(feet.z()-target.z(),2) <= reach*reach;
            return WorldAccess.super.canInteractFrom(feet,target,reach);
        }
    }
}
