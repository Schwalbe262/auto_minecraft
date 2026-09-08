package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.Pos;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class HarvestRoutePlannerTest {
    @Test void threeByThreeStartsAtCenterButKeepsEveryOriginalAsFallback() {
        List<Pos> original = square(3);
        List<Pos> ordered = HarvestRoutePlanner.order(original,1);
        assertEquals(new Pos(1,0,1),ordered.get(0));
        assertEquals(9,ordered.size());
        assertEquals(new HashSet<>(original),new HashSet<>(ordered));
        assertEquals(square(3),original,"Planning must not mutate or consume observed crops");
    }

    @Test void centersSnakeAcrossNonOverlappingCoverageStrips() {
        List<Pos> ordered = HarvestRoutePlanner.order(square(6),1);
        assertEquals(List.of(new Pos(1,0,1),new Pos(4,0,1),new Pos(4,0,4),new Pos(1,0,4)),ordered.subList(0,4));
        assertEquals(36,ordered.size());
    }

    @Test void highTierHintChoosesFiveByFiveCenter() {
        assertEquals(new Pos(2,0,2),HarvestRoutePlanner.order(square(5),2).get(0));
    }

    @Test void holesAndSeparatedSectorsNeverCreateNonexistentClickTargets() {
        List<Pos> original = List.of(new Pos(-30,2,-10),new Pos(-29,2,-10),new Pos(20,1,20),new Pos(21,1,20));
        List<Pos> ordered = HarvestRoutePlanner.order(original,1);
        assertEquals(new HashSet<>(original),new HashSet<>(ordered));
        assertEquals(original.size(),ordered.size());
    }

    @Test void UpperFallbackIsOnlyPredictedAndStillRetained() {
        List<Pos> original = List.of(new Pos(0,0,0),new Pos(0,1,0),new Pos(1,1,0));
        List<Pos> ordered = HarvestRoutePlanner.order(original,1);
        assertEquals(new Pos(0,0,0),ordered.get(0));
        assertEquals(new HashSet<>(original),new HashSet<>(ordered));
        assertEquals(3,ordered.size());
    }

    @Test void noHintRetainsStableSerpentineInputAndDeduplicates() {
        List<Pos> original = List.of(new Pos(2,0,1),new Pos(1,0,1),new Pos(2,0,1));
        List<Pos> expected = original.subList(0,2);
        assertEquals(expected,HarvestRoutePlanner.order(original,0));
        assertEquals(expected,HarvestRoutePlanner.order(original,-1));
        assertEquals(expected,HarvestRoutePlanner.order(original,5));
        assertTrue(HarvestRoutePlanner.order(List.of(),1).isEmpty());
    }

    @Test void footprintIsSquareAndIncludesOnlySameLevelOrUpperFallback() {
        Pos center = new Pos(0,10,0);
        assertTrue(HarvestRoutePlanner.withinFootprint(center,new Pos(1,11,-1),1));
        assertFalse(HarvestRoutePlanner.withinFootprint(center,new Pos(2,10,0),1));
        assertFalse(HarvestRoutePlanner.withinFootprint(center,new Pos(0,9,0),1));
        assertFalse(HarvestRoutePlanner.withinFootprint(center,new Pos(0,12,0),1));
    }

    @Test void unevenMaturityCannotMovePrimaryCentersOffTheFixedLayoutRow() {
        List<Pos> layout=new ArrayList<>();
        for (int z=0;z<3;z++) for (int x=0;x<6;x++) layout.add(new Pos(x,0,z));
        List<Pos> mature=List.of(new Pos(1,0,1),new Pos(4,0,0),new Pos(4,0,2),new Pos(5,0,2),new Pos(5,0,1));
        var route=HarvestRoutePlanner.plan(mature,layout,1);
        assertEquals(List.of(new Pos(1,0,1),new Pos(5,0,1)),route.primary());
        assertEquals(new HashSet<>(mature),new HashSet<>(route.ordered()));
        assertEquals(3,route.cleanup().size(),"Off-row ripe crops are deferred, never omitted");
    }

    @Test void removedMatureBorderDoesNotReanchorRowsOrChangeTheLayoutAxis() {
        List<Pos> layout=square(6);
        List<Pos> mature=layout.stream().filter(p -> p.x()>=1 && p.z()>=1).toList();
        var route=HarvestRoutePlanner.plan(mature,layout,1);
        assertTrue(route.alongX());
        assertEquals(List.of(1,1,4,4),route.primary().stream().map(Pos::z).toList());
        assertEquals(new HashSet<>(mature),new HashSet<>(route.ordered()));
    }

    @Test void longNarrowLayoutSweepsItsLongAxisAndTurnsOnlyAtStripEnds() {
        List<Pos> layout=new ArrayList<>();
        for (int z=0;z<9;z++) for (int x=0;x<6;x++) layout.add(new Pos(x,0,z));
        var route=HarvestRoutePlanner.plan(layout,layout,1);
        assertFalse(route.alongX());
        assertEquals(List.of(new Pos(1,0,1),new Pos(1,0,4),new Pos(1,0,7),
            new Pos(4,0,7),new Pos(4,0,4),new Pos(4,0,1)),route.primary());
    }

    @Test void twentyByTwentyMultiLevelCropKeepsEveryUpperVineForObservedCleanupAtBothNativeRadii() {
        List<Pos> layout=new ArrayList<>();
        for (int y=0;y<2;y++) for (int z=0;z<20;z++) for (int x=0;x<20;x++) layout.add(new Pos(x,y,z));
        for (int radius:List.of(1,2)) {
            var route=HarvestRoutePlanner.plan(layout,layout,radius);
            int rowCount=(20+radius*2)/(radius*2+1);
            assertEquals(rowCount*rowCount,route.primary().size());
            assertTrue(route.primary().stream().allMatch(p -> p.y()==0));
            assertEquals(800,route.ordered().size());
            assertEquals(new HashSet<>(layout),new HashSet<>(route.ordered()));
            assertEquals(400,route.cleanup().stream().filter(p -> p.y()==1).count());
            var uncovered=new HashSet<>(layout);
            for (Pos center:route.primary()) uncovered.removeIf(p -> HarvestRoutePlanner.withinFootprint(center,p,radius));
            assertTrue(uncovered.isEmpty(),"Coverage is a routing hint; the module must still observe every change");
            assertThrows(UnsupportedOperationException.class,() -> route.primary().clear());
            assertThrows(UnsupportedOperationException.class,() -> route.cleanup().clear());
        }
    }

    @Test void cleanupIsItsOwnDeterministicRowSweepRegardlessOfInputOrder() {
        List<Pos> original=square(6);
        var expected=HarvestRoutePlanner.plan(original,original,1);
        List<Pos> shuffled=new ArrayList<>(original); java.util.Collections.reverse(shuffled);
        assertEquals(expected,HarvestRoutePlanner.plan(shuffled,original,1));
        assertTrue(java.util.Collections.disjoint(expected.primary(),expected.cleanup()));
        assertEquals(original.size(),expected.primary().size()+expected.cleanup().size());
    }

    private static List<Pos> square(int width) {
        List<Pos> result = new ArrayList<>();
        for (int z=0;z<width;z++) for (int x=0;x<width;x++) result.add(new Pos(x,0,z));
        return result;
    }
}
