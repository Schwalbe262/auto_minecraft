package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainNavigationTest {
    private static final Pos START=new Pos(0,64,0);
    @Test void twoWorkplacesNeedNoWaypointsOrFarmRectangle() {
        Fixture f=new Fixture(18);Pos target=new Pos(18,64,0);
        assertTrue(f.profile.pois.isEmpty());assertFalse(new ProfileBounds(f.profile).contains(START));
        assertEquals(Navigation.Result.ARRIVED,f.run(target,.2,500));
        assertEquals(0,f.submissions);assertFalse(f.navigation.retryableFailure());
    }
    @Test void terrainCanDetourAroundAWallWithoutGrantingAnUnverifiedDiagonal() {
        Fixture f=new Fixture(5);f.standing.remove(new Pos(2,64,0));
        for (int x=1;x<=3;x++) f.standing.add(new Pos(x,64,1));
        var s=f.search(new Pos(5,64,0),TerrainPathSearch.Goal.POSITION,false);
        finish(s,f);
        assertEquals(TerrainPathSearch.Status.FOUND,s.status());assertFalse(s.path().contains(new Pos(2,64,0)));
        assertTrue(s.path().contains(new Pos(2,64,1)));assertEquals(0,f.submissions);
    }
    @Test void literalPositionAndObserveNeverMasqueradeAsInteractionArrival() {
        Fixture f=new Fixture(5);Pos target=new Pos(5,64,0);f.interaction=false;
        Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<250 && result==Navigation.Result.MOVING;i++) { result=f.navigation.moveToPosition(target,.2,f.context);f.advance(); }
        assertEquals(Navigation.Result.ARRIVED,result);assertEquals(0,f.rays);
        f.navigation.reset();assertEquals(Navigation.Result.ARRIVED,f.navigation.moveToObserve(target,8,f.context));
        assertEquals(0,f.rays);assertEquals(0,f.submissions);
        assertNotEquals(Navigation.Result.ARRIVED,f.navigation.moveTo(target,.2,f.context));
    }
    @Test void observationRequiresLoadedTargetAndGroundedVerifiedStance() {
        Fixture f=new Fixture(8);Pos target=new Pos(8,64,0);f.unloadedFromX=5;
        assertEquals(Navigation.Result.MOVING,f.navigation.moveToObserve(target,16,f.context));
        assertEquals(0,f.submissions);f.grounded=false;
        assertNotEquals(Navigation.Result.ARRIVED,f.navigation.moveToObserve(START,16,f.context));
    }
    @Test void loggingPositionDoesNotStopMerelyBecauseTheObstructingLeafIsWithinReach() {
        Fixture f=new Fixture(5);f.profile.loggingRunActive=true;f.context.session().oneShotFeature=Feature.LOGGING;
        Pos nearbyLeaf=new Pos(3,64,0),provenStance=new Pos(5,64,0);
        assertEquals(Navigation.Result.ARRIVED,f.navigation.moveToLogging(nearbyLeaf,4,f.context));
        assertEquals(.5,f.x);f.navigation.reset();f.rays=0;f.now++;
        Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<250 && result==Navigation.Result.MOVING;i++) {
            result=f.navigation.moveToLoggingPosition(provenStance,.1,f.context);f.advance();
        }
        assertEquals(Navigation.Result.ARRIVED,result);assertTrue(Math.abs(f.x-5.5)<=.1);
        assertEquals(0,f.rays,"A literal stance is not a ray to the leaf or the block occupying the feet cell");
        assertEquals(0,f.submissions);assertNull(f.movement);
    }
    @Test void loggingPositionUsesTheActualFractionalSurfaceRatherThanIntegerCellY() {
        Fixture f=new Fixture(5);f.profile.loggingRunActive=true;f.context.session().oneShotFeature=Feature.LOGGING;
        f.y=63.5;f.standing.forEach(p -> f.heights.put(p,63.5));f.interaction=false;Pos target=new Pos(5,64,0);
        Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<250 && result==Navigation.Result.MOVING;i++) {
            result=f.navigation.moveToLoggingPosition(target,.1,f.context);f.advance();
        }
        assertEquals(Navigation.Result.ARRIVED,result,f.navigation.failureReason());
        assertEquals(63.5,f.y);assertTrue(Math.abs(f.x-5.5)<=.1);assertTrue(f.player().distance(target)>.49);
        assertEquals(target,NavigationFeet.resolve(f,f.player()));assertEquals(0,f.rays);assertEquals(0,f.submissions);
    }
    @Test void loggingPositionRetainsRunPermissionAndResetBoundaries() {
        Fixture f=new Fixture(5);Pos target=new Pos(5,64,0);
        assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToLoggingPosition(target,.1,f.context));
        f.profile.loggingRunActive=true;assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToLoggingPosition(target,.1,f.context));
        assertNull(f.movement);f.context.session().oneShotFeature=Feature.LOGGING;f.now++;
        for(int i=0;i<20 && f.movement==null;i++){f.navigation.moveToLoggingPosition(target,.1,f.context);f.now++;}
        assertNotNull(f.movement);assertTrue(f.navigation.permitsTransit(START,f.context));
        f.navigation.reset();assertNull(f.movement);assertFalse(f.navigation.permitsTransit(START,f.context));
        f.profile.loggingRunActive=false;assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToLoggingPosition(target,.1,f.context));
        assertEquals(0,f.submissions);
    }
    @Test void loggingPositionKeepsTheExistingVerifiedLoggingAscentAuthority() {
        Fixture f=new Fixture(0);Pos end=new Pos(1,65,0);f.standing.add(end);f.nativeLoggingJump=true;
        f.profile.loggingRunActive=true;f.context.session().oneShotFeature=Feature.LOGGING;
        f.profile.loggingPlots.add(new LoggingPlot("nearby",new Pos(0,64,3)));
        for(int i=0;i<30 && f.loggingLaunches==0;i++) {
            assertEquals(Navigation.Result.MOVING,f.navigation.moveToLoggingPosition(end,.1,f.context),f.navigation.failureReason());f.now++;
        }
        assertEquals(1,f.loggingLaunches);assertEquals(0,f.launches);assertEquals(0,f.submissions);
        assertFalse(f.navigation.permitsStepUp(new LoggingJumpEdge(START,end),f.context),"Logging authority is not general step-up authority");
        f.navigation.reset();assertNull(f.movement);
    }
    @Test void unloadedDestinationFollowsOnlySafePrefixThenWaitsAndResumesWhenLoaded() {
        Fixture f=new Fixture(12);Pos target=new Pos(12,64,0);f.unloadedFromX=5;
        for(int i=0;i<180 && !f.navigation.diagnosticStatus().equals("WAITING_CHUNKS");i++) {
            assertEquals(Navigation.Result.MOVING,f.navigation.moveTo(target,.2,f.context));f.advance();
        }
        assertEquals("WAITING_CHUNKS",f.navigation.diagnosticStatus());assertTrue(f.x<5);assertNull(f.movement);
        assertFalse(f.navigation.retryableFailure());assertEquals(0,f.submissions);
        f.unloadedFromX=Integer.MAX_VALUE;f.now++;
        assertEquals(Navigation.Result.ARRIVED,f.run(target,.2,300));assertEquals(0,f.submissions);
    }
    @Test void unchangedFrontiersWaitOneHundredTicksAndStopAfterThreeAttempts() {
        Fixture f=new Fixture(12);f.unloadedFromX=1;Pos target=new Pos(12,64,0);
        Navigation.Result result=Navigation.Result.MOVING;long firstWait=-1;
        for(int i=0;i<500 && result==Navigation.Result.MOVING;i++) {
            result=f.navigation.moveToObserve(target,1,f.context);
            if(f.navigation.diagnosticStatus().equals("WAITING_CHUNKS") && firstWait<0) firstWait=f.now;
            if(firstWait>=0 && f.now-firstWait<100) assertEquals(0,f.navigation.diagnostics().get("frontierAttempts"));
            f.now++;
        }
        assertEquals(Navigation.Result.BLOCKED,result);assertEquals(Navigation.Failure.UNLOADED,f.navigation.failureKind());
        assertTrue(f.navigation.retryableFailure());assertEquals(target,f.navigation.failureDestination());
        assertEquals(3,f.navigation.diagnostics().get("frontierAttempts"));assertEquals(.5,f.x);assertEquals(0,f.submissions);
    }
    @Test void frontierIdentityIncludesDirectionAndTheUpperFloor() {
        var low=new TerrainPathSearch.Frontier(START,new Pos(1,64,0),false);
        var upper=new TerrainPathSearch.Frontier(START.offset(0,2,0),new Pos(1,66,0),false);
        var otherSide=new TerrainPathSearch.Frontier(START,new Pos(-1,64,0),false);
        assertEquals(3,Set.of(low,upper,otherSide).size());
        Fixture f=new Fixture(2);f.standing.addAll(List.of(new Pos(2,65,1),new Pos(1,66,1),new Pos(0,66,1),new Pos(0,66,0)));
        f.standing.add(new Pos(2,64,1));f.allowStairs=true;
        var s=f.search(new Pos(0,66,0),TerrainPathSearch.Goal.POSITION,false);finish(s,f);
        assertEquals(TerrainPathSearch.Status.FOUND,s.status());assertEquals(START,s.path().get(0));
        assertEquals(new Pos(0,66,0),s.path().get(s.path().size()-1));
        assertTrue(s.path().stream().anyMatch(p -> p.x()==2),"A temporary move away is necessary to reach the stairs");
    }
    @Test void optionalHintsNeverMakeAPathMandatoryOrCrossABlockedCell() {
        Fixture f=new Fixture(6);Pos target=new Pos(6,64,0);
        f.profile.pois.add(new Poi(new Pos(200,90,200),PoiKind.WAYPOINT,"irrelevant",null));
        var hinted=f.search(target,TerrainPathSearch.Goal.POSITION,false);finish(hinted,f);
        f.profile.useWaypointHints=false;var plain=f.search(target,TerrainPathSearch.Goal.POSITION,false);finish(plain,f);
        assertEquals(plain.path(),hinted.path());assertEquals(7,plain.path().size());
    }
    @Test void aDistantGoalDoesNotStretchTheFiniteTravelDomain() {
        Profile p=new Profile();TravelDomain d=new TravelDomain(p,START,new Pos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE));
        assertTrue(d.contains(START.offset(256,64,-256)));assertFalse(d.contains(START.offset(257,0,0)));
        assertFalse(d.contains(START.offset(0,65,0)));assertFalse(d.contains(new Pos(Integer.MIN_VALUE,64,0)));
        p.navigationMode=NavigationMode.WAYPOINTS;assertFalse(new TravelDomain(p,START,START).contains(START));
    }
    @Test void aThreeHundredBlockRouteReanchorsItsDomainAndReachesTheLiteralDestination() {
        Fixture f=new Fixture(300);Pos target=new Pos(300,64,0);
        Navigation.Result result=Navigation.Result.MOVING;boolean reanchored=false;
        for(int ticks=0;ticks<3000 && result==Navigation.Result.MOVING;ticks++) {
            result=f.navigation.moveToPosition(target,.2,f.context);
            if(f.navigation.permitsTransit(target,f.context)) {
                reanchored=true;
                assertTrue(f.x>=255,"The far goal only enters the domain after reaching its verified boundary");
                assertFalse(f.navigation.permitsTransit(START.offset(-256,0,0),f.context),"The new window is not the union of all past windows");
            }
            f.advance();
        }
        assertTrue(reanchored);assertEquals(Navigation.Result.ARRIVED,result);
        assertTrue(Math.abs(f.x-target.x()-.5)<=.2);assertNull(f.movement);
        assertEquals(0,f.navigation.diagnostics().get("frontierAttempts"));
        assertEquals(0,f.submissions);assertEquals(0,f.launches);assertFalse(f.navigation.retryableFailure());
    }
    @Test void everySliceHonoursNodeAndRayBudgetsIncludingSameTickNavigatorCalls() {
        Fixture f=new Fixture(200);Pos target=new Pos(200,64,0);
        var s=f.search(target,TerrainPathSearch.Goal.INTERACTION,false);
        while(s.status()==TerrainPathSearch.Status.SEARCHING) {
            s.advance(f,3,1,Long.MAX_VALUE);assertTrue(s.lastExpanded()<=3);assertTrue(s.lastLosChecks()<=1);
        }
        assertEquals(TerrainPathSearch.Status.FOUND,s.status());
        for(int i=0;i<100;i++) f.navigation.moveTo(target,.2,f.context);
        assertTrue((int)f.navigation.diagnostics().get("sliceNodes")<=128);
        assertTrue((int)f.navigation.diagnostics().get("sliceLos")<=32);
    }
    @Test void aFinalFrontierDescentReanchorsOnlyAfterItsObservedQuietLanding() {
        assertControlledFrontierReanchors(false);
    }
    @Test void aFinalFrontierAscentReanchorsOnlyAfterItsObservedQuietLanding() {
        assertControlledFrontierReanchors(true);
    }
    private static void assertControlledFrontierReanchors(boolean ascending) {
        Fixture f=new Fixture(255);int landingY=ascending ? 65 : 63;
        for(int x=256;x<=300;x++) f.standing.add(new Pos(x,landingY,0));
        f.nativeStepUp=ascending;f.allowStairs=!ascending;
        Pos target=new Pos(300,landingY,0),landing=new Pos(256,landingY,0);
        boolean controlled=false,reanchored=false;int poseStage=0,quietGrounded=0;
        for(int i=0;i<2000;i++) {
            assertEquals(Navigation.Result.MOVING,f.navigation.moveToPosition(target,.2,f.context),f.navigation.failureReason());
            if(f.navigation.permitsTransit(target,f.context)) {
                reanchored=true;assertTrue(controlled);assertTrue(quietGrounded>=2);
                assertTrue(f.grounded);assertEquals(landingY,f.y);assertEquals(256.5,f.x);
                assertFalse(f.navigation.permitsTransit(START.offset(-256,0,0),f.context));
                assertNull(f.movement);break;
            }
            String state=f.navigation.diagnosticStatus();
            if(!controlled && (state.equals("STEP_UP") || state.startsWith("DESCENT_"))) {
                controlled=true;f.x=255.5;f.y=64;f.z=.5;f.grounded=true;
            }
            if(!controlled) { f.advance();continue; }
            // Script observed, distinct native poses, not predicted controller
            // completion. Search/domain permission must remain unchanged through
            // flight and the first grounded landing observation.
            if(poseStage==0 && (ascending ? f.launches>0 : state.equals("DESCENT_DESCEND"))) {
                f.x=ascending ? 255.5 : 256.2;f.y=ascending ? 64.42 : 63.6;f.grounded=false;poseStage=1;
            } else if(poseStage==1) {
                assertFalse(f.navigation.permitsTransit(target,f.context));
                f.x=256.5;f.y=landingY;f.grounded=true;poseStage=2;
            } else if(poseStage==2) {
                quietGrounded++;
                if(quietGrounded==1) assertFalse(f.navigation.permitsTransit(target,f.context));
            }
            f.now++;
        }
        assertTrue(reanchored,"A settled controlled edge must not discard its terminal frontier before reanchoring");
        assertEquals(0,f.submissions);assertEquals(ascending ? 1 : 0,f.launches);
        assertEquals(0,f.loggingLaunches);assertFalse(f.navigation.retryableFailure());
        assertTrue(f.navigation.permitsTransit(landing,f.context));
    }
    @Test void searchExpandsItsBudgetWithoutRestartingAndStopsAt65536() {
        Fixture f=new Fixture(0);f.largeFloor=true;
        var s=f.search(new Pos(1000,64,1000),TerrainPathSearch.Goal.POSITION,false);
        int previous=0,slices=0;
        while(s.status()==TerrainPathSearch.Status.SEARCHING && slices++<20) {
            s.advance(f,65536,0,Long.MAX_VALUE);assertTrue(s.expanded()>=previous);previous=s.expanded();
        }
        assertEquals(TerrainPathSearch.Status.SEARCH_LIMIT,s.status());assertEquals(65536,s.expanded());
        assertEquals(65536,s.nodeLimit());assertEquals(0,f.rays);
    }
    @Test void threeReplansDoNotBecomeAnEndlessUnchangedStallLoop() {
        Fixture f=new Fixture(8);Pos target=new Pos(8,64,0);
        Navigation.Result result=Navigation.Result.MOVING;
        for(int n=0;n<4;n++) {
            for(int t=0;t<30 && f.movement==null;t++) { result=f.navigation.moveTo(target,.2,f.context);f.now++; }
            assertNotNull(f.movement);f.now+=61;result=f.navigation.moveTo(target,.2,f.context);
        }
        assertEquals(Navigation.Result.BLOCKED,result);assertEquals(Navigation.Failure.STALLED,f.navigation.failureKind());
        assertTrue(f.navigation.retryableFailure());assertEquals(3,f.navigation.diagnostics().get("replans"));assertNull(f.movement);
    }
    @Test void resetImmediatelySilencesMovementAndRevokesTransitPermission() {
        Fixture f=new Fixture(6);Pos target=new Pos(6,64,0);
        for(int i=0;i<20 && f.movement==null;i++) { f.navigation.moveTo(target,.2,f.context);f.now++; }
        assertNotNull(f.movement);assertTrue(f.navigation.permitsTransit(START,f.context));
        f.navigation.reset();assertNull(f.movement);assertFalse(f.navigation.permitsTransit(START,f.context));
        assertEquals("IDLE",f.navigation.diagnosticStatus());
    }
    @Test void terrainJumpNeedsNoLoggingAuthorityButOnlyTheExactActiveEdgeMayLaunch() {
        Fixture f=new Fixture(0);Pos end=new Pos(1,65,0);f.standing.add(end);f.nativeStepUp=true;
        assertFalse(f.profile.loggingRunActive);assertNull(f.context.session().oneShotFeature);
        assertFalse(f.navigation.permitsStepUp(new LoggingJumpEdge(START,end),f.context));
        for(int i=0;i<30 && f.launches==0;i++) { assertEquals(Navigation.Result.MOVING,f.navigation.moveToPosition(end,.2,f.context));f.now++; }
        assertEquals(1,f.launches);assertEquals(0,f.loggingLaunches);
        assertTrue(f.navigation.permitsStepUp(new LoggingJumpEdge(START,end),f.context));
        assertFalse(f.navigation.permitsStepUp(new LoggingJumpEdge(START,START.offset(0,1,1)),f.context));
        f.grounded=false;f.y=64.42;f.now++;f.navigation.moveToPosition(end,.2,f.context);
        f.navigation.reset();assertFalse(f.navigation.permitsStepUp(new LoggingJumpEdge(START,end),f.context));
        assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToPosition(end,.2,f.context));assertEquals(1,f.launches);
        assertFalse(f.navigation.retryableFailure());assertEquals(0,f.submissions);
    }
    @Test void farmlandStillCannotBecomeAGeneralJumpRoute() {
        Fixture f=new Fixture(0);Pos end=new Pos(1,65,0);f.standing.add(end);f.nativeStepUp=true;
        f.profile.farms.add(new Farm("protected",START,end));
        var s=f.search(end,TerrainPathSearch.Goal.POSITION,false);finish(s,f);
        assertEquals(TerrainPathSearch.Status.NO_PATH,s.status());assertEquals(0,f.launches);
    }
    @Test void harvestingLookaheadNeverPlansOrLaunchesAStepUpEvenWithoutAPendingAction() {
        Fixture f=new Fixture(0);Pos end=new Pos(1,65,0);f.standing.add(end);f.nativeStepUp=true;
        Navigation.Result result=Navigation.Result.MOVING;
        for(int i=0;i<30 && result==Navigation.Result.MOVING;i++) { result=f.navigation.moveToWithoutInteraction(end,.1,f.context);f.now++; }
        assertEquals(Navigation.Result.BLOCKED,result);assertEquals(0,f.launches);assertEquals(0,f.submissions);
        assertFalse(f.navigation.permitsStepUp(new LoggingJumpEdge(START,end),f.context));
    }
    @Test void changingTheSameGoalToLookaheadCancelsAnActiveAscentAndKeepsItsLandingFence() {
        Fixture f=new Fixture(0);Pos end=new Pos(1,65,0);f.standing.add(end);f.nativeStepUp=true;
        for(int i=0;i<30 && f.launches==0;i++) { f.navigation.moveTo(end,.1,f.context);f.now++; }
        assertEquals(1,f.launches);f.grounded=false;f.y=64.42;
        assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToWithoutInteraction(end,.1,f.context));
        assertFalse(f.navigation.permitsStepUp(new LoggingJumpEdge(START,end),f.context));
        assertFalse(f.navigation.retryableFailure());assertEquals(1,f.launches);
        f.navigation.reset();f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.navigation.moveTo(end,.1,f.context));assertEquals(1,f.launches);
    }
    @Test void aChangedEdgeReplansThroughObservedTerrainBeforeContinuing() {
        Fixture f=new Fixture(5);Pos target=new Pos(5,64,0);
        for(int i=0;i<30 && f.movement==null;i++) { f.navigation.moveTo(target,.2,f.context);f.now++; }
        assertNotNull(f.movement);f.standing.remove(new Pos(2,64,0));
        for(int x=1;x<=3;x++) f.standing.add(new Pos(x,64,1));
        assertEquals(Navigation.Result.ARRIVED,f.run(target,.2,300));
        assertEquals(1,f.navigation.diagnostics().get("replans"));assertEquals(0,f.submissions);
    }
    @Test void aClosedDoorWaitsForItsAuthoritativeOutcomeAndLookaheadCannotOpenIt() {
        Fixture f=new Fixture(3);f.door=new Pos(1,64,0);Pos target=new Pos(3,64,0);
        assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToWithoutInteraction(target,.2,f.context));
        assertEquals(0,f.submissions);assertNull(f.movement);f.navigation.reset();f.now++;
        for(int i=0;i<30 && f.submissions==0;i++) { assertEquals(Navigation.Result.MOVING,f.navigation.moveTo(target,.2,f.context));f.now++; }
        assertEquals(1,f.submissions);assertNull(f.movement);
        for(int i=0;i<5;i++) { f.now++;assertEquals(Navigation.Result.MOVING,f.navigation.moveTo(target,.2,f.context));assertNull(f.movement); }
        assertEquals(1,f.submissions);f.doorAcknowledged=true;
        assertEquals(Navigation.Result.ARRIVED,f.run(target,.2,200));assertEquals(1,f.submissions);
    }

    private static void finish(TerrainPathSearch s,WorldAccess world) {
        int steps=0;
        while(s.status()==TerrainPathSearch.Status.SEARCHING && steps++<2000) s.advance(world,128,32,Long.MAX_VALUE);
        assertNotEquals(TerrainPathSearch.Status.SEARCHING,s.status());
    }
    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final LocalNavigator navigation=new LocalNavigator();
        final Context context=new Context(this,this,navigation,profile,new SessionState());
        final Set<Pos> standing=new HashSet<>();
        final Map<Pos,Double> heights=new HashMap<>();
        long now;double x=.5,y=64,z=.5;boolean grounded=true,interaction=true,allowStairs,largeFloor,nativeStepUp,nativeLoggingJump;
        int unloadedFromX=Integer.MAX_VALUE,rays,submissions,launches,loggingLaunches;Movement movement;
        Pos door;boolean doorAcknowledged;
        Fixture(int length) { for(int i=0;i<=length;i++) standing.add(new Pos(i,64,0)); }
        TerrainPathSearch search(Pos target,TerrainPathSearch.Goal goal,boolean frontiers) {
            return new TerrainPathSearch(START,target,.1,this,profile,new TravelDomain(profile,START,target),Set.of(),Set.of(),false,goal,List.of(),frontiers);
        }
        Navigation.Result run(Pos target,double reach,int ticks) {
            Navigation.Result result=Navigation.Result.MOVING;
            for(int i=0;i<ticks && result==Navigation.Result.MOVING;i++) { result=navigation.moveTo(target,reach,context);advance(); }
            return result;
        }
        void advance() { if(movement!=null && movement.forward()) { double a=Math.toRadians(movement.yaw());x-=Math.sin(a)*.18;z+=Math.cos(a)*.18; }now++; }
        public long tick(){return now;} public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,grounded,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return p.x()<unloadedFromX;}
        public boolean canStand(Pos p){return loaded(p) && (standing.contains(p) || largeFloor && p.y()==64 && p.x()>=0 && p.z()>=0 && p.x()<256 && p.z()<256);}
        public double standingY(Pos p){return canStand(p)?heights.getOrDefault(p,(double)p.y()):Double.NaN;}
        public boolean canTraverse(Pos from,Pos to){return canStand(from) && canStand(to)
            && Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())==1 && (from.y()==to.y() || allowStairs && Math.abs(from.y()-to.y())<=1);}
        public boolean canStepUp(LoggingJumpEdge edge,Profile profile){return nativeStepUp;}
        public boolean canLoggingJump(LoggingJumpEdge edge,Profile profile){return nativeLoggingJump;}
        public BlockData block(Pos p){assertTrue(loaded(p),"No reads into unloaded terrain");return p.equals(door)
            ? new BlockData(p,"minecraft:oak_door",Map.of("open",Boolean.toString(doorAcknowledged))) : new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public boolean canInteract(Pos p,double reach){rays++;return loaded(p) && interaction && player().distance(p)<=reach;}
        public boolean canInteractFrom(Pos feet,Pos p,double reach){rays++;return loaded(p) && interaction && feet.distanceSquared(p)<=reach*reach;}
        public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}
        public boolean mayPlace(int i,ItemData item){return false;}
        public boolean busy(){return false;}public long submit(Action action){
            if(door!=null) { assertEquals(new Action.UseBlock(door,Action.Use.DOOR),action);return ++submissions; }
            submissions++;throw new AssertionError("No work action during terrain search");
        }
        public ActionOutcome outcome(long id){return new ActionOutcome(doorAcknowledged ? ActionOutcome.State.SUCCEEDED : ActionOutcome.State.PENDING,"Door proof");}
        public void move(Movement m){assertFalse(m.jump());movement=m;}
        public boolean moveStepUp(LoggingJumpEdge edge,boolean launch){assertTrue(navigation.permitsStepUp(edge,context));if(launch)launches++;return true;}
        public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch){if(launch)loggingLaunches++;return true;}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
