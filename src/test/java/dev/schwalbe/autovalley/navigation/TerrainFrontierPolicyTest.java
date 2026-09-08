package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainFrontierPolicyTest {
    @Test void nearbySurfaceGoalNeverUsesTheMineshaftAtTheLowerDomainBoundary() {
        Fixture f=new Fixture(new Pos(0,72,0));
        f.stairs(-1,1);
        TerrainPathSearch search=f.search(new Pos(-5,75,0));
        finish(search,f);
        assertEquals(TerrainPathSearch.Status.NO_PATH,search.status());
        assertTrue(search.path().isEmpty()); assertNull(search.frontier());
    }

    @Test void anOutsideGoalStillCannotAuthorizeAnOppositeDirectionBoundary() {
        Fixture f=new Fixture(new Pos(0,72,0)); f.stairs(-1,1);
        TerrainPathSearch search=f.search(new Pos(300,72,0)); finish(search,f);
        assertEquals(TerrainPathSearch.Status.NO_PATH,search.status());
        assertTrue(search.path().isEmpty()); assertNull(search.frontier());
    }

    @Test void aDistantUpperGoalRetainsTheVerifiedUpwardPrefix() {
        Fixture f=new Fixture(new Pos(0,8,0)); f.stairs(1,-1); f.stairs(-1,1);
        TerrainPathSearch search=f.search(new Pos(0,75,-67)); finish(search,f);
        assertEquals(TerrainPathSearch.Status.FRONTIER,search.status());
        assertTrue(search.frontier().domainEdge());
        assertEquals(new Pos(0,72,-64),search.frontier().standing());
        assertEquals(f.start,search.path().get(0));
        assertTrue(search.frontier().crossing().y()>search.frontier().standing().y());
    }

    @Test void aDistantHorizontalGoalRetainsItsFiniteSafePrefix() {
        Fixture f=new Fixture(new Pos(0,72,0));
        for(int x=0;x<=300;x++) f.standing.add(new Pos(x,72,0));
        TerrainPathSearch search=f.search(new Pos(300,72,0)); finish(search,f);
        assertEquals(TerrainPathSearch.Status.FRONTIER,search.status());
        assertTrue(search.frontier().domainEdge());
        assertEquals(new Pos(256,72,0),search.frontier().standing());
        assertTrue(search.frontier().crossing().x()>256);
    }

    @Test void theActualBoundaryStanceMustProgressNotJustItsUntraversedCrossing() {
        Fixture f=new Fixture(new Pos(0,64,0)); f.standing.add(new Pos(1,64,0));
        for(int i=1;i<=64;i++) f.standing.add(new Pos(i+1,64+i,0));
        Pos target=new Pos(0,129,0),boundary=new Pos(65,128,0),crossing=new Pos(64,129,0);
        assertTrue(TravelDomain.distanceSquared(boundary,target)>TravelDomain.distanceSquared(f.start,target));
        assertTrue(TravelDomain.distanceSquared(crossing,target)<TravelDomain.distanceSquared(f.start,target));
        TerrainPathSearch search=f.search(target); finish(search,f);
        assertEquals(TerrainPathSearch.Status.NO_PATH,search.status());
        assertTrue(search.path().isEmpty()); assertNull(search.frontier());
    }

    @Test void missingChunksKeepTheSafePrefixAndAreNotTreatedAsDomainEdges() {
        Fixture f=new Fixture(new Pos(0,72,0));
        for(int x=0;x<=8;x++) f.standing.add(new Pos(x,72,0)); f.unloadedFromX=5;
        TerrainPathSearch search=f.search(new Pos(8,72,0)); finish(search,f);
        assertEquals(TerrainPathSearch.Status.FRONTIER,search.status());
        assertFalse(search.frontier().domainEdge());
        assertEquals(new Pos(4,72,0),search.frontier().standing());
        assertTrue(search.path().stream().allMatch(f::loaded));
    }

    @Test void aLoadedGoalMayStillNeedExplorationAtAMissingChunkBoundary() {
        Fixture f=new Fixture(new Pos(0,72,0));
        for(int x=0;x<=4;x++) f.standing.add(new Pos(x,72,0)); f.unloadedFromX=5;
        TerrainPathSearch search=f.search(new Pos(-5,72,0)); finish(search,f);
        assertTrue(f.loaded(new Pos(-5,72,0)));
        assertEquals(TerrainPathSearch.Status.FRONTIER,search.status());
        assertFalse(search.frontier().domainEdge());
    }

    @Test void exhaustedNodeBudgetCannotPromoteAnObservedChunkFrontierIntoMovement() {
        Fixture f=new Fixture(new Pos(0,72,0)); f.largeFloor=true; f.unloadedFromX=256;
        TerrainPathSearch search=f.search(new Pos(5,75,5)); finish(search,f);
        assertNotNull(search.frontier(),"An unloaded boundary was observed during the search");
        assertEquals(TerrainPathSearch.Status.SEARCH_LIMIT,search.status());
        assertEquals(TerrainPathSearch.MAX_VISITED,search.expanded());
        assertTrue(search.path().isEmpty(),"Exhaustion is not a route approval");
    }

    private static void finish(TerrainPathSearch search,Fixture f) {
        int iterations=0;
        while(search.status()==TerrainPathSearch.Status.SEARCHING && iterations++<1000)
            search.advance(f,128,32,Long.MAX_VALUE);
        assertNotEquals(TerrainPathSearch.Status.SEARCHING,search.status());
    }

    private static final class Fixture implements WorldAccess {
        final Pos start; final Profile profile=new Profile(); final Set<Pos> standing=new HashSet<>();
        int unloadedFromX=Integer.MAX_VALUE; boolean largeFloor;
        Fixture(Pos start) { this.start=start; standing.add(start); }
        void stairs(int rise,int dz) { for(int i=1;i<=65;i++) standing.add(start.offset(0,rise*i,dz*i)); }
        TerrainPathSearch search(Pos target) {
            return new TerrainPathSearch(start,target,.1,this,profile,new TravelDomain(profile,start,target),
                Set.of(),Set.of(),false,TerrainPathSearch.Goal.POSITION,List.of(),true);
        }
        public long tick(){return 0;} public long dayTime(){return 0;}
        public PlayerState player(){return new PlayerState(start.x()+.5,start.y(),start.z()+.5,0,0,true,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return p.x()<unloadedFromX;}
        public boolean canStand(Pos p){return loaded(p) && (standing.contains(p) || largeFloor && p.y()==72 && p.x()>=0 && p.x()<256 && p.z()>=0 && p.z()<256);}
        public double standingY(Pos p){return canStand(p) ? p.y() : Double.NaN;}
        public boolean canTraverse(Pos from,Pos to){return canStand(from) && canStand(to)
            && Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())==1 && Math.abs(from.y()-to.y())<=1;}
        public BlockData block(Pos p){assertTrue(loaded(p)); return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public boolean canInteract(Pos p,double reach){return false;}
        public boolean canInteractFrom(Pos from,Pos p,double reach){return false;}
        public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}
        public boolean mayPlace(int slot,ItemData item){return false;}
    }
}
