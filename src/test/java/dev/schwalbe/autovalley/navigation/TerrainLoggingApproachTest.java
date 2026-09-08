package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainLoggingApproachTest {
    private static final Pos START=new Pos(0,64,0),TOP=new Pos(1,65,0);

    @Test void loggingTerrainApproachOutsideLegacyBoundsUsesIndependentlyVerifiedStepUp() {
        Fixture f=new Fixture();f.generalProof=true;
        assertFalse(ProfileBounds.contains(f.profile,START));
        assertEquals(List.of(START,TOP),new LocalPathfinder().findLogging(START,TOP,.1,f,f.profile));
        assertEquals(TerrainPathSearch.Status.FOUND,f.search());
        f.untilLaunch();assertEquals(1,f.generalLaunches);assertEquals(0,f.loggingLaunches);
        assertTrue(f.nav.permitsStepUp(new LoggingJumpEdge(START,TOP),f.context));
        assertEquals(0,f.workActions);
    }
    @Test void neitherNativeOptOutNorProtectedPlantingCanBorrowGeneralTransitPermission() {
        for(int variant=0;variant<3;variant++) {
            Fixture f=new Fixture();f.generalProof=variant!=0;
            if(variant==1) f.profile.loggingPlots.add(new LoggingPlot("protected",TOP));
            if(variant==2) f.profile.farms.add(new Farm("protected",START,TOP));
            assertEquals(TerrainPathSearch.Status.NO_PATH,f.search());
            assertTrue(new LocalPathfinder().findLogging(START,TOP,.1,f,f.profile).isEmpty());
            assertEquals(Navigation.Result.BLOCKED,f.untilTerminal());
            assertEquals(0,f.generalLaunches);assertEquals(0,f.loggingLaunches);
        }
    }
    @Test void legacyModeNeverFallsBackToGeneralStepUp() {
        Fixture f=new Fixture();f.profile.navigationMode=NavigationMode.WAYPOINTS;f.generalProof=true;
        f.registerCorridor();
        assertEquals(TerrainPathSearch.Status.NO_PATH,f.search());
        assertTrue(new LocalPathfinder().findLogging(START,TOP,.1,f,f.profile).isEmpty());
        assertEquals(Navigation.Result.BLOCKED,f.untilTerminal());assertEquals(0,f.generalLaunches);
    }
    @Test void legacyLoggingProofRemainsPreferredInEitherMode() {
        for(NavigationMode mode:NavigationMode.values()) {
            Fixture f=new Fixture();f.profile.navigationMode=mode;f.registerCorridor();
            f.generalProof=true;f.loggingProof=true;
            f.untilLaunch();assertEquals(1,f.loggingLaunches);assertEquals(0,f.generalLaunches);
            assertFalse(f.nav.permitsStepUp(new LoggingJumpEdge(START,TOP),f.context));
        }
    }
    @Test void selectedGeneralControllerCannotSwitchAuthorityWhenItsProofDisappearsInFlight() {
        Fixture f=new Fixture();f.generalProof=true;f.untilLaunch();
        f.grounded=false;f.y=64.42;f.now++;
        f.generalProof=false;f.loggingProof=true;f.registerCorridor();
        assertEquals(Navigation.Result.BLOCKED,f.navigate());
        assertEquals(Navigation.Failure.JUMP_UNCERTAIN,f.nav.failureKind());
        assertEquals(1,f.generalLaunches);assertEquals(0,f.loggingLaunches);
        f.nav.reset();f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.navigate());assertEquals(0,f.loggingLaunches);
        assertFalse(f.nav.retryableFailure());
    }
    @Test void selectedLoggingControllerCannotSwitchToGeneralAuthorityWhenItsProofDisappears() {
        Fixture f=new Fixture();f.registerCorridor();f.generalProof=true;f.loggingProof=true;f.untilLaunch();
        f.loggingProof=false;f.grounded=false;f.y=64.42;f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.navigate());assertEquals(1,f.loggingLaunches);
        assertEquals(0,f.generalLaunches);assertFalse(f.nav.retryableFailure());
    }
    @Test void terrainFallbackDoesNotBypassLoggingRunOrFeatureAuthority() {
        for(boolean inactiveRun:new boolean[]{true,false}) {
            Fixture f=new Fixture();f.generalProof=true;
            if(inactiveRun) f.profile.loggingRunActive=false;
            else f.profile.enabled.put(Feature.LOGGING,false);
            assertEquals(Navigation.Result.BLOCKED,f.navigate());
            assertEquals(0,f.generalLaunches);assertEquals(0,f.workActions);
        }
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final LocalNavigator nav=new LocalNavigator();
        final Context context=new Context(this,this,nav,profile,new SessionState());
        long now;double y=64;boolean grounded=true,generalProof,loggingProof;
        int generalLaunches,loggingLaunches,workActions;Movement movement;
        Fixture(){profile.loggingRunActive=true;profile.enabled.put(Feature.LOGGING,true);}
        void registerCorridor(){profile.pois.add(new Poi(START,PoiKind.WAYPOINT,"corridor",null));}
        Navigation.Result navigate(){return nav.moveToLogging(TOP,.1,context);}
        void untilLaunch(){
            for(int i=0;i<30 && generalLaunches+loggingLaunches==0;i++,now++) assertEquals(Navigation.Result.MOVING,navigate());
            assertEquals(1,generalLaunches+loggingLaunches);
        }
        Navigation.Result untilTerminal(){Navigation.Result r=Navigation.Result.MOVING;
            for(int i=0;i<30 && r==Navigation.Result.MOVING;i++,now++) r=navigate();return r;}
        TerrainPathSearch.Status search(){
            var s=new TerrainPathSearch(START,TOP,.1,this,profile,new TravelDomain(profile,START,TOP),
                Set.of(),Set.of(),true,TerrainPathSearch.Goal.INTERACTION,List.of(),true);
            for(int i=0;i<30 && s.status()==TerrainPathSearch.Status.SEARCHING;i++) s.advance(this,128,32,Long.MAX_VALUE);
            return s.status();
        }
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(.5,y,.5,0,0,grounded,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}
        public boolean canStand(Pos p){return p.equals(START)||p.equals(TOP);}
        public double standingY(Pos p){return canStand(p)?p.y():Double.NaN;}
        public boolean canTraverse(Pos from,Pos to){return false;}
        public boolean canStepUp(LoggingJumpEdge e,Profile p){return generalProof;}
        public boolean canLoggingJump(LoggingJumpEdge e,Profile p){return loggingProof;}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public boolean canInteract(Pos p,double r){return false;}
        public boolean canInteractFrom(Pos feet,Pos p,double r){return feet.equals(p);}
        public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}
        public boolean mayPlace(int i,ItemData item){return false;}
        public boolean busy(){return false;}public long submit(Action a){workActions++;throw new AssertionError("No work action");}
        public ActionOutcome outcome(long id){return new ActionOutcome(ActionOutcome.State.PENDING,"");}
        public void move(Movement m){assertFalse(m.jump());movement=m;}
        public boolean moveStepUp(LoggingJumpEdge e,boolean launch){assertTrue(nav.permitsStepUp(e,context));if(launch)generalLaunches++;return true;}
        public boolean moveLoggingJump(LoggingJumpEdge e,boolean launch){if(launch)loggingLaunches++;return true;}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
