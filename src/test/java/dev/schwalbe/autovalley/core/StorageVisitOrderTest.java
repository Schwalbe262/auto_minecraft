package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageVisitOrderTest {
    private static Poi tomato(int x,int y,int z) { return new Poi(new Pos(x,y,z),PoiKind.TOMATO_CHEST,"tomato",null); }
    private static PlayerState player(double x,double y,double z) {
        return new PlayerState(x,y,z,0,0,true,false,20,20,0,true,true);
    }
    @Test void eachSixteenBarrelWallIsFinishedBeforeCrossingTheAisle() {
        List<Poi> input=new ArrayList<>();
        for(int y=0;y<4;y++)for(int z=0;z<4;z++) { input.add(tomato(4,y,z)); input.add(tomato(0,y,z)); }
        List<Poi> result=StorageVisitOrder.order(input,player(2.5,0,.5));
        assertEquals(32,result.size());
        assertTrue(result.subList(0,16).stream().allMatch(p->p.pos().x()==0));
        assertTrue(result.subList(16,32).stream().allMatch(p->p.pos().x()==4));
        assertEquals(new HashSet<>(input),new HashSet<>(result));
        for(int n=1;n<16;n++) assertEquals(1,result.get(n-1).pos().distanceSquared(result.get(n).pos()),"Greedy steps remain adjacent in this complete rectangular wall");
    }
    @Test void nextComponentUsesTheLastVisitCursorNotTheOriginalPlayerPosition() {
        List<Poi> input=new ArrayList<>(); for(int x=0;x<=10;x++)input.add(tomato(x,0,0));
        Poi behind=tomato(-2,0,0),ahead=tomato(12,0,0); input.add(behind); input.add(ahead);
        List<Poi> result=StorageVisitOrder.order(input,player(.5,0,.5));
        assertEquals(ahead,result.get(11)); assertEquals(behind,result.get(12));
    }
    @Test void componentEntranceIsItsNearestMemberEvenIfItWasRegisteredLast() {
        Poi low=tomato(0,0,0),middle=tomato(0,1,0),high=tomato(0,2,0);
        assertEquals(List.of(high,middle,low),StorageVisitOrder.order(List.of(low,middle,high),player(.5,3,.5)));
    }
    @Test void touchingDifferentKindsAndDiagonalCellsDoNotJoinAComponent() {
        List<Poi> input=new ArrayList<>(); for(int z=0;z<=5;z++)input.add(tomato(0,0,z));
        Poi touchingWine=new Poi(new Pos(1,0,0),PoiKind.WINE_CHEST,"wine",9);
        Poi diagonal=tomato(1,1,0); input.add(touchingWine); input.add(diagonal);
        List<Poi> result=StorageVisitOrder.order(input,player(.5,0,.5));
        assertTrue(result.subList(0,6).stream().allMatch(p->p.kind()==PoiKind.TOMATO_CHEST && p.pos().x()==0));
        assertTrue(result.subList(6,8).containsAll(List.of(touchingWine,diagonal)));
    }
    @Test void irregularComponentsMissingCellsAndEveryDuplicateArePreserved() {
        Poi first=tomato(0,0,0),corner=tomato(0,0,1),last=tomato(1,0,1),distant=tomato(9,0,9);
        List<Poi> input=new ArrayList<>(List.of(last,distant,first,corner,first)); List<Poi> before=List.copyOf(input);
        List<Poi> result=StorageVisitOrder.order(input,player(.5,0,.5));
        assertEquals(List.of(first,first,corner,last,distant),result); assertEquals(before,input);
        assertThrows(UnsupportedOperationException.class,()->result.clear());
    }
    @Test void tiedCoordinatesHaveAnInputOrderIndependentDeterministicResult() {
        List<Poi> original=List.of(tomato(-2,0,0),tomato(2,0,0),tomato(0,0,-2),tomato(0,0,2));
        List<Poi> expected=StorageVisitOrder.order(original,player(.5,0,.5));
        for(int seed=0;seed<12;seed++) {
            List<Poi> shuffled=new ArrayList<>(original); Collections.shuffle(shuffled,new Random(seed));
            assertEquals(expected,StorageVisitOrder.order(shuffled,player(.5,0,.5)));
        }
    }
    @Test void gradesDoNotSplitAnAdjacentCommodityWallAndInputsRemainUnchanged() {
        Poi legacy=new Poi(new Pos(0,0,0),PoiKind.TOMATO_CHEST,"legacy",3);
        Poi unclassified=new Poi(new Pos(0,1,0),PoiKind.TOMATO_CHEST,"new",null);
        Poi remote=tomato(2,0,0);
        assertEquals(List.of(legacy,unclassified,remote),StorageVisitOrder.order(List.of(remote,unclassified,legacy),player(.5,0,.5)));
    }
    @Test void emptyAndMaximumSizeWorkButMalformedOrOversizedRequestsAreRejected() {
        PlayerState origin=player(.5,0,.5);
        assertTrue(StorageVisitOrder.order(List.of(),origin).isEmpty());
        List<Poi> maximum=new ArrayList<>(); for(int n=0;n<4096;n++)maximum.add(tomato(n,0,0));
        assertEquals(maximum,StorageVisitOrder.order(maximum,origin));
        maximum.add(tomato(4096,0,0));
        assertThrows(IllegalArgumentException.class,()->StorageVisitOrder.order(maximum,origin));
        assertThrows(IllegalArgumentException.class,()->StorageVisitOrder.order(Arrays.asList((Poi)null),origin));
        assertThrows(IllegalArgumentException.class,()->StorageVisitOrder.order(List.of(),player(Double.NaN,0,0)));
    }
}
