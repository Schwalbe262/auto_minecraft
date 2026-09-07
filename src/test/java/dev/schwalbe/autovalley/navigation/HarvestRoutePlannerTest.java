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

    private static List<Pos> square(int width) {
        List<Pos> result = new ArrayList<>();
        for (int z=0;z<width;z++) for (int x=0;x<width;x++) result.add(new Pos(x,0,z));
        return result;
    }
}
