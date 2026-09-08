package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationDiagonalTest {
    private static final Pos START=new Pos(0,64,0),DIAGONAL=new Pos(1,64,1);

    @Test void openFlatAreaUsesRealDiagonalEdgesInsteadOfAnAlternatingStaircase() {
        Fixture f=new Fixture(); Pos end=new Pos(8,64,8);
        List<Pos> path=new LocalPathfinder().find(START,end,.1,f,f.profile);
        assertEquals(9,path.size());
        for(int i=0;i<path.size();i++) assertEquals(new Pos(i,64,i),path.get(i));
        assertTrue(path.stream().allMatch(p -> ProfileBounds.contains(f.profile,p)));
    }

    @Test void unsupportedAdaptersStayCardinalAndPreserveExistingRoutes() {
        Fixture f=new Fixture(); f.nativeDiagonal=false;
        List<Pos> path=new LocalPathfinder().find(START,new Pos(3,64,3),.1,f,f.profile);
        assertEquals(7,path.size());
        for(int i=1;i<path.size();i++) assertEquals(1,horizontalSteps(path.get(i-1),path.get(i)));
    }

    @Test void eitherBlockedCornerOrEitherUnsafeCardinalLegPreventsTheDiagonal() {
        for(Pos side:List.of(new Pos(1,64,0),new Pos(0,64,1))) {
            Fixture f=new Fixture(); f.blocked.add(side);
            assertFalse(f.diagonal(START,DIAGONAL));
            List<Pos> path=new LocalPathfinder().find(START,DIAGONAL,.1,f,f.profile);
            assertEquals(3,path.size(),"Use the remaining ordinary two-edge corner, not its diagonal");
        }
        for(String leg:List.of(edge(START,new Pos(1,64,0)),edge(new Pos(1,64,0),DIAGONAL),
                edge(START,new Pos(0,64,1)),edge(new Pos(0,64,1),DIAGONAL))) {
            Fixture f=new Fixture(); f.blockedEdges.add(leg);
            assertFalse(f.diagonal(START,DIAGONAL));
        }
        Fixture enclosed=new Fixture(); enclosed.blocked.add(new Pos(1,64,0)); enclosed.blocked.add(new Pos(0,64,1));
        assertTrue(new LocalPathfinder().find(START,DIAGONAL,.1,enclosed,enclosed.profile).isEmpty());
    }

    @Test void everySideCellMustBeRegisteredAndLoadedIncludingItsFloorAndHead() {
        Fixture boundary=new Fixture(); boundary.profile.farms.clear(); boundary.profile.corridorRadius=2;
        boundary.profile.pois.add(new Poi(new Pos(0,64,1),PoiKind.WAYPOINT,"edge",null));
        Pos from=new Pos(1,64,0),to=new Pos(2,64,1),outsideSide=new Pos(2,64,0);
        assertTrue(ProfileBounds.contains(boundary.profile,from)); assertTrue(ProfileBounds.contains(boundary.profile,to));
        assertFalse(ProfileBounds.contains(boundary.profile,outsideSide));
        assertFalse(boundary.diagonal(from,to));
        for(int dy:new int[]{-1,0,1}) {
            Fixture f=new Fixture(); f.unloaded.add(new Pos(1,64+dy,0));
            assertFalse(f.diagonal(START,DIAGONAL));
        }
    }

    @Test void doorsChangingHeightsUnknownSupportAndNativeCollisionKeepTheCardinalFallback() {
        for(boolean open:new boolean[]{false,true}) for(int dy:new int[]{0,1}) {
            Fixture f=new Fixture(); f.doors.put(new Pos(1,64+dy,0),open);
            assertFalse(f.diagonal(START,DIAGONAL),"An open door still requires a cardinal approach");
        }
        for(double height:new double[]{64.5,63.9375,Double.NaN}) {
            Fixture f=new Fixture(); f.heights.put(new Pos(1,64,0),height);
            assertFalse(f.diagonal(START,DIAGONAL));
        }
        Fixture f=new Fixture(); f.nativeDiagonal=false;
        assertFalse(f.diagonal(START,DIAGONAL));
        assertFalse(f.diagonal(START,DIAGONAL.offset(0,1,0)));
        assertFalse(f.diagonal(START,new Pos(3,64,3)),"Never infer an unvalidated long shortcut");
    }

    @Test void normalizedFarmlandAndUniformBottomSlabsCanUseVerifiedFlatDiagonalsWithoutJumping() {
        for(double floor:new double[]{63.9375,63.5}) {
            Fixture f=new Fixture(); f.floor=floor; f.y=floor;
            assertFalse(f.canStand(f.player().feet()));
            assertEquals(START,NavigationFeet.resolve(f,f.player()));
            assertTrue(f.diagonal(START,DIAGONAL));
            assertEquals(Navigation.Result.MOVING,f.navigator.moveTo(new Pos(8,64,8),.15,f.context));
            assertNotNull(f.movement); assertEquals(-45,f.movement.yaw(),1e-5);
            assertFalse(f.movement.jump()); assertFalse(f.movement.sneak());
        }
    }

    @Test void aLongOpenDiagonalMaintainsOneWorldHeadingAndActuallyArrives() {
        Fixture f=new Fixture(); Pos target=new Pos(8,64,8); Navigation.Result result=Navigation.Result.MOVING;
        int ticks=0;
        for(;ticks<160 && result==Navigation.Result.MOVING;ticks++) {
            result=f.navigator.moveTo(target,.2,f.context);
            if(f.movement!=null) {
                assertEquals(-45,f.movement.yaw(),.001);
                assertFalse(f.movement.jump());
                double radians=Math.toRadians(f.movement.yaw());
                f.x-=Math.sin(radians)*.18; f.z+=Math.cos(radians)*.18;
            }
            f.now++;
        }
        assertEquals(Navigation.Result.ARRIVED,result);
        assertTrue(ticks<90); assertEquals(0,f.submissions); assertNull(f.movement);
    }

    @Test void changedDiagonalCornerStopsBeforeMoreMovementOrInteraction() {
        Fixture f=new Fixture(); Pos target=new Pos(8,64,8);
        assertEquals(Navigation.Result.MOVING,f.navigator.moveTo(target,.2,f.context));
        f.blocked.add(new Pos(1,64,0)); f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.navigator.moveTo(target,.2,f.context));
        assertNull(f.movement); assertEquals(0,f.submissions);
    }

    private static int horizontalSteps(Pos a,Pos b) { return Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z()); }
    private static String edge(Pos a,Pos b) { return a+"->"+b; }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile(); final LocalNavigator navigator=new LocalNavigator(); final Context context;
        final Set<Pos> blocked=new HashSet<>(),unloaded=new HashSet<>(); final Set<String> blockedEdges=new HashSet<>();
        final Map<Pos,Double> heights=new HashMap<>(); final Map<Pos,Boolean> doors=new HashMap<>();
        long now; double x=.5,y=64,z=.5,floor=64; boolean nativeDiagonal=true; Movement movement; int submissions;
        Fixture() {
            profile.farms.add(new Farm("registered",START,new Pos(8,64,8)));
            context=new Context(this,this,navigator,profile);
        }
        boolean diagonal(Pos from,Pos to) { return DiagonalTraversal.canTraverse(from,to,this,new ProfileBounds(profile)); }
        public long tick() { return now; }
        public long dayTime() { return 5000; }
        public PlayerState player() { return new PlayerState(x,y,z,0,0,true,false,20,20,4,true,true); }
        public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        public boolean canStand(Pos pos) { return pos.y()==64 && pos.x()>=0 && pos.z()>=0 && pos.x()<=8 && pos.z()<=8 && !blocked.contains(pos); }
        public double standingY(Pos pos) { return canStand(pos) ? heights.getOrDefault(pos,floor) : Double.NaN; }
        public boolean canTraverse(Pos from,Pos to) { return canStand(from) && canStand(to) && horizontalSteps(from,to)==1 && !blockedEdges.contains(edge(from,to)); }
        public boolean canTraverseDiagonal(Pos from,Pos to) { return nativeDiagonal; }
        public BlockData block(Pos pos) { return doors.containsKey(pos) ? new BlockData(pos,"minecraft:oak_door",Map.of("open",doors.get(pos).toString())) : new BlockData(pos,"minecraft:air",Map.of()); }
        public List<BlockData> scan(Pos center,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        public boolean mayPlace(int slot,ItemData item) { return false; }
        public long submit(Action action) { submissions++; throw new AssertionError("Open flat traversal must not interact"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError(); }
        public boolean busy() { return false; }
        public void move(Movement movement) { this.movement=movement; }
        public void stopMovement() { movement=null; }
        public void cancel() { stopMovement(); }
    }
}
