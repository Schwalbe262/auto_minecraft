package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainTraversalCostTest {
    private static final Pos START=new Pos(0,64,0), GOAL=new Pos(4,64,0), DIP=new Pos(2,64,0);

    @Test void actualPartialHeightDipPrefersShortLevelDetourInEveryDirection() {
        for(int rotation=0;rotation<4;rotation++) {
            Fixture f=new Fixture(rotation);f.levelDetour(1);f.heights.put(f.turn(DIP),63.5625);
            List<Pos> path=f.search(f.turn(GOAL));
            assertFalse(path.contains(f.turn(DIP)));
            assertTrue(path.contains(f.turn(new Pos(2,64,1))));
            assertEquals(f.turn(START),path.get(0));assertEquals(f.turn(GOAL),path.get(path.size()-1));
        }
    }
    @Test void unavoidableDipIsStillTraversable() {
        Fixture f=new Fixture(0);f.heights.put(DIP,63.5625);
        assertEquals(List.of(START,new Pos(1,64,0),DIP,new Pos(3,64,0),GOAL),f.search(GOAL));
    }
    @Test void exactDestinationInDipRemainsReachable() {
        Fixture f=new Fixture(0);f.levelDetour(1);f.heights.put(DIP,63.5625);
        List<Pos> path=f.search(DIP);assertEquals(DIP,path.get(path.size()-1));
    }
    @Test void preferenceDoesNotForceAnExcessivelyLongDetour() {
        Fixture f=new Fixture(0);f.levelDetour(4);f.heights.put(DIP,63.5625);
        assertTrue(f.search(GOAL).contains(DIP));
    }
    @Test void ordinaryFarmlandLipDoesNotPenalizeStraightWalking() {
        Fixture f=new Fixture(0);f.levelDetour(1);f.heights.put(DIP,64.0);
        assertEquals(5,f.search(GOAL).size());assertTrue(f.search(GOAL).contains(DIP));
        assertEquals(0,TerrainPathSearch.surfaceTransitionPenalty(64,63.9375));
        assertEquals(0,TerrainPathSearch.surfaceTransitionPenalty(63.9375,64));
    }
    @Test void blockedFlatShortcutIsNeverGrantedByThePreference() {
        Fixture f=new Fixture(0);f.levelDetour(1);f.heights.put(DIP,63.5625);
        f.heights.remove(new Pos(2,64,1));
        assertTrue(f.search(GOAL).contains(DIP));
    }
    @Test void legacyWaypointCostIsUnchanged() {
        Fixture f=new Fixture(0);f.levelDetour(1);f.heights.put(DIP,63.5625);
        f.profile.navigationMode=NavigationMode.WAYPOINTS;
        f.profile.pois.add(new Poi(START,PoiKind.WAYPOINT,"legacy",null));
        assertEquals(5,f.search(GOAL).size());assertTrue(f.search(GOAL).contains(DIP));
    }
    @Test void penaltyIsBoundedFiniteAndDoesNotInventUnknownHeights() {
        assertEquals(2.75,TerrainPathSearch.surfaceTransitionPenalty(63.9375,63.5625));
        assertEquals(.75,TerrainPathSearch.surfaceTransitionPenalty(63.5625,63.9375));
        assertEquals(4,TerrainPathSearch.surfaceTransitionPenalty(64,63));
        assertEquals(4,TerrainPathSearch.surfaceTransitionPenalty(Double.MAX_VALUE,-Double.MAX_VALUE));
        assertEquals(0,TerrainPathSearch.surfaceTransitionPenalty(Double.NaN,64));
        assertEquals(0,TerrainPathSearch.surfaceTransitionPenalty(64,Double.POSITIVE_INFINITY));
    }

    private static final class Fixture implements WorldAccess {
        final Profile profile=new Profile();final Map<Pos,Double> heights=new HashMap<>();final int rotation;
        Fixture(int rotation) { this.rotation=rotation;for(int x=0;x<=4;x++) heights.put(turn(new Pos(x,64,0)),63.9375); }
        Pos turn(Pos p) {
            int x=p.x(),z=p.z();for(int i=0;i<rotation;i++) { int next=-z;z=x;x=next; }
            return new Pos(x,p.y(),z);
        }
        void levelDetour(int depth) {
            for(int z=1;z<=depth;z++) { heights.put(turn(new Pos(0,64,z)),63.9375);heights.put(turn(new Pos(4,64,z)),63.9375); }
            for(int x=0;x<=4;x++) heights.put(turn(new Pos(x,64,depth)),63.9375);
        }
        List<Pos> search(Pos target) {
            Pos start=turn(START);
            var search=new TerrainPathSearch(start,target,.01,this,profile,new TravelDomain(profile,start,target),
                Set.of(),Set.of(),false,TerrainPathSearch.Goal.POSITION,List.of(),false,false);
            for(int i=0;i<100 && search.status()==TerrainPathSearch.Status.SEARCHING;i++) {
                search.advance(this,3,0,Long.MAX_VALUE);assertTrue(search.lastExpanded()<=3);
            }
            assertEquals(TerrainPathSearch.Status.FOUND,search.status());return search.path();
        }
        public long tick() { return 0; } public long dayTime() { return 0; }
        public PlayerState player() { return null; }
        public BlockData block(Pos p) { return new BlockData(p,"minecraft:air",Map.of()); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return heights.containsKey(p); }
        public double standingY(Pos p) { return heights.getOrDefault(p,Double.NaN); }
        public boolean canTraverse(Pos from,Pos to) {
            return canStand(from) && canStand(to) && from.y()==to.y()
                && Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())==1;
        }
        public List<BlockData> scan(Pos p,int h,int v) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        public boolean mayPlace(int index,ItemData item) { return false; }
    }
}
