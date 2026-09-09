package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InterruptedAscentRoutingTest {
    private static final Pos FROM=new Pos(0,0,0),TO=new Pos(1,1,0),SIDE=new Pos(0,0,1),UPPER_SIDE=new Pos(1,1,1);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(FROM,TO);

    @Test void excludedJumpIsNotQueriedOrRetriedAndNoAlternativeStaysBlocked() {
        Fixture f=new Fixture();TerrainPathSearch search=f.search(FROM,TO,Set.of(EDGE));finish(search,f);
        assertEquals(TerrainPathSearch.Status.NO_PATH,search.status());assertFalse(f.jumpQueries.contains(EDGE));
        assertTrue(f.launches.isEmpty());assertEquals(0,f.interactions);
    }

    @Test void theDirectedExclusionIsAnImmutableSnapshotAndAValidAlternativeRemainsAvailable() {
        Fixture f=new Fixture();f.detour();Set<LoggingJumpEdge> exclusions=new HashSet<>(Set.of(EDGE));
        TerrainPathSearch search=f.search(FROM,TO,exclusions);exclusions.clear();finish(search,f);
        assertEquals(TerrainPathSearch.Status.FOUND,search.status());
        assertEquals(List.of(FROM,SIDE,UPPER_SIDE,TO),search.path());assertFalse(f.jumpQueries.contains(EDGE));
        assertTrue(f.jumpQueries.contains(new LoggingJumpEdge(SIDE,UPPER_SIDE)));
        assertTrue(f.launches.isEmpty());assertEquals(0,f.interactions);
        TerrainPathSearch legacy=new TerrainPathSearch(FROM,TO,.01,f,f.profile,
            new TravelDomain(f.profile,FROM,TO),Set.of(),Set.of(),false,TerrainPathSearch.Goal.POSITION,List.of(),false);
        finish(legacy,f);assertEquals(TerrainPathSearch.Status.FOUND,legacy.status());assertEquals(List.of(FROM,TO),legacy.path());
    }

    @Test void exclusionsAreDirectedAndDoNotForbidReverseOrdinaryDescent() {
        Fixture f=new Fixture();TerrainPathSearch search=f.search(FROM,TO,Set.of(new LoggingJumpEdge(TO,FROM)));finish(search,f);
        assertEquals(TerrainPathSearch.Status.FOUND,search.status());assertEquals(List.of(FROM,TO),search.path());
        assertTrue(f.jumpQueries.contains(EDGE));
        f=new Fixture();search=f.search(TO,FROM,Set.of(EDGE));finish(search,f);
        assertEquals(TerrainPathSearch.Status.FOUND,search.status());assertEquals(List.of(TO,FROM),search.path());
        assertTrue(f.jumpQueries.isEmpty());
    }

    @Test void anEdgeWhichHasBecomeNativelyWalkableDoesNotInheritTheJumpBan() {
        Fixture f=new Fixture();f.walkableAscent=true;
        TerrainPathSearch search=f.search(FROM,TO,Set.of(EDGE));finish(search,f);
        assertEquals(TerrainPathSearch.Status.FOUND,search.status());assertEquals(List.of(FROM,TO),search.path());
        assertTrue(f.jumpQueries.isEmpty());assertTrue(f.launches.isEmpty());
    }

    @Test void resetPreservesTheLaunchFenceAndTheOriginalFailureAfterNoPathOverwritesLastFailure() {
        Fixture f=new Fixture();f.failLaunch();Map<?,?> original=f.ascentFailure();
        assertEquals("TRANSIT",original.get("authority"));assertEquals("PREPARE",original.get("phase"));
        assertEquals(EDGE,original.get("edge"));assertEquals(true,original.get("attempted"));
        assertTrue(original.get("reason").toString().contains("시작이 거절"));
        assertThrows(UnsupportedOperationException.class,()->original.clear());
        f.nav.reset();f.now++;assertEquals(Navigation.Result.BLOCKED,f.untilTerminal());
        assertEquals(Navigation.Failure.NO_PATH,f.nav.failureKind());assertEquals(List.of(EDGE),f.launches);
        assertEquals(original,f.ascentFailure());assertEquals(0,f.interactions);
        Fixture accepted=new Fixture();accepted.accept=true;
        for(int tick=0;tick<30&&accepted.launches.isEmpty();tick++)assertEquals(Navigation.Result.MOVING,accepted.step());
        assertEquals(List.of(EDGE),accepted.launches);accepted.nav.reset();Map<?,?> cancelled=accepted.ascentFailure();
        assertEquals("LIFT",cancelled.get("phase"));assertEquals(true,cancelled.get("attempted"));
        assertTrue(cancelled.get("reason").toString().contains("취소"));
        accepted.grounded=false;accepted.y=.42;assertEquals(Navigation.Result.BLOCKED,accepted.step());
        assertEquals(cancelled,accepted.ascentFailure());assertEquals(List.of(EDGE),accepted.launches);assertNull(accepted.movement);
    }

    @Test void resetCanFindTheDetourWithoutIssuingTheCancelledDirectedJumpAgain() {
        Fixture f=new Fixture();f.failLaunch();Map<?,?> original=f.ascentFailure();
        f.detour();f.nav.reset();f.now++;
        for(int tick=0;tick<30&&f.movement==null;tick++) {
            assertEquals(Navigation.Result.MOVING,f.step());
        }
        assertNotNull(f.movement,"The safe alternative must start with ordinary walking toward the side cell");
        assertEquals(0,f.movement.yaw(),.00001);assertFalse(f.movement.jump());
        assertEquals(List.of(EDGE),f.launches);assertEquals(original,f.ascentFailure());assertEquals(0,f.interactions);
    }

    private static void finish(TerrainPathSearch search,WorldAccess world) {
        for(int tick=0;tick<30&&search.status()==TerrainPathSearch.Status.SEARCHING;tick++)search.advance(world,128,32,Long.MAX_VALUE);
        assertNotEquals(TerrainPathSearch.Status.SEARCHING,search.status());
    }
    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final LocalNavigator nav=new LocalNavigator();
        final Context context=new Context(this,this,nav,profile,new SessionState());
        final Set<Pos> stands=new HashSet<>(Set.of(FROM,TO));final Set<LoggingJumpEdge> jumpQueries=new HashSet<>();
        final List<LoggingJumpEdge> launches=new ArrayList<>();long now;double y;boolean grounded=true,walkableAscent,accept;
        int interactions;Movement movement;
        Fixture(){profile.navigationMode=NavigationMode.TERRAIN;}
        void detour(){stands.addAll(List.of(SIDE,UPPER_SIDE));}
        TerrainPathSearch search(Pos from,Pos to,Set<LoggingJumpEdge> exclusions){return new TerrainPathSearch(from,to,.01,this,profile,
            new TravelDomain(profile,from,to),Set.of(),Set.of(),false,TerrainPathSearch.Goal.POSITION,List.of(),false,true,exclusions);}
        Navigation.Result step(){Navigation.Result result=nav.moveToPosition(TO,.01,context);now++;return result;}
        Navigation.Result untilTerminal(){Navigation.Result result=Navigation.Result.MOVING;for(int i=0;i<30&&result==Navigation.Result.MOVING;i++)result=step();return result;}
        void failLaunch(){assertEquals(Navigation.Result.BLOCKED,untilTerminal());assertEquals(List.of(EDGE),launches);assertEquals(Navigation.Failure.JUMP_UNCERTAIN,nav.failureKind());}
        Map<?,?> ascentFailure(){return (Map<?,?>)nav.diagnostics().get("lastAscentFailure");}
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(.5,y,.5,0,0,grounded,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}public boolean canStand(Pos p){return stands.contains(p);}
        public double standingY(Pos p){return canStand(p)?p.y():Double.NaN;}
        public boolean canTraverse(Pos from,Pos to){return canStand(from)&&canStand(to)
            &&Math.abs(from.x()-to.x())+Math.abs(from.z()-to.z())==1
            &&(from.y()==to.y()||from.y()-to.y()==1||walkableAscent&&from.equals(FROM)&&to.equals(TO));}
        public boolean canStepUp(LoggingJumpEdge edge,Profile ignored){jumpQueries.add(edge);return LoggingJumpRules.validShape(edge)&&canStand(edge.from())&&canStand(edge.to());}
        public BlockData block(Pos p){return new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos center,int h,int v){return List.of();}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return false;}public long submit(Action action){interactions++;throw new AssertionError("No interaction permission");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("No action ticket");}
        public void move(Movement intent){assertFalse(intent.jump());assertFalse(intent.sprint());movement=intent;}
        public boolean moveStepUp(LoggingJumpEdge edge,boolean launch){assertTrue(nav.permitsStepUp(edge,context));if(launch)launches.add(edge);return accept;}
        public void stopMovement(){movement=null;}public void cancel(){stopMovement();}
    }
}
