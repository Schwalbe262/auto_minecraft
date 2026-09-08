package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingCraftPlanTest {
    private static final Stack FIRE=new Stack("native:fire",1,64),PLANKS=new Stack("native:planks",4,64);
    private static Stack logs(int count) { return new Stack("native:spruce",count,64); }
    private static List<Stack> menu() { return new ArrayList<>(Collections.nCopies(46,Stack.EMPTY)); }
    private static Stack recipe(List<Stack> items) {
        long occupied=items.subList(1,10).stream().filter(s -> !s.empty()).count();
        return occupied==6 ? FIRE : occupied==1 ? PLANKS : Stack.EMPTY;
    }
    private static LoggingCraftPlan.Plan create(List<Stack> items,Set<Integer> sources) {
        return LoggingCraftPlan.create(items,Stack.EMPTY,sources,FIRE,LoggingCraftPlanTest::recipe);
    }

    @Test void sixtyFourLogsUseOneBoundedSixCellDragThenReturnFourToTheExactSource() {
        List<Stack> initial=menu(); initial.set(10,logs(64)); initial.set(45,new Stack("native:tool",1,1));
        var plan=create(initial,Set.of(10));
        assertEquals(10,plan.quantity()); assertEquals(10,plan.steps().size());
        assertEquals(new LoggingCraftPlan.Click(10,0,LoggingCraftPlan.Type.PICKUP),plan.steps().get(0).click());
        assertEquals(Stack.EMPTY,plan.steps().get(0).expectedItems().get(10)); assertEquals(logs(64),plan.steps().get(0).expectedCursor());
        for(int i=1;i<=7;i++) {
            var step=plan.steps().get(i); assertEquals(LoggingCraftPlan.Type.QUICK_CRAFT,step.click().type());
            assertEquals(step.before(),step.after(),"Drag begin/add ACKs cannot prove completed placement");
        }
        var distribution=plan.steps().get(8);
        assertEquals(new LoggingCraftPlan.Click(-999,2,LoggingCraftPlan.Type.QUICK_CRAFT),distribution.click());
        assertEquals(logs(4),distribution.expectedCursor());
        assertEquals(logs(4),plan.placed().items().get(10)); assertTrue(plan.placed().carried().empty());
        for(int slot=1;slot<=6;slot++) assertEquals(logs(10),plan.placed().items().get(slot));
        assertEquals(initial.get(45),plan.placed().items().get(45)); assertEquals(FIRE,plan.placed().items().get(0));
        assertConserved(plan);
    }

    @Test void everySingleNativeStackFromSixToSixtyFourConservesRemaindersAndCapsTenOutputs() {
        for(int count=6;count<=64;count++) {
            List<Stack> items=menu(); items.set(25,logs(count)); var plan=create(items,Set.of(25));
            assertEquals(count/6,plan.quantity()); assertEquals(count%6,plan.placed().items().get(25).count());
            assertEquals(count%6==0 ? 9 : 10,plan.steps().size()); assertConserved(plan);
        }
    }

    @Test void largestSourceIsChosenOnceWithDeterministicSlotTieAndOtherStacksUntouched() {
        List<Stack> items=menu(); items.set(10,logs(6)); items.set(20,logs(60)); items.set(21,logs(60));
        var plan=create(items,new HashSet<>(List.of(21,10,20)));
        assertEquals(20,plan.steps().get(0).click().slot()); assertEquals(10,plan.quantity());
        assertEquals(logs(6),plan.placed().items().get(10)); assertEquals(logs(60),plan.placed().items().get(21));
    }

    @Test void sixSingletonsUseTwelveNormalClicksAndNeverTouchAnyIntermediateResult() {
        List<Stack> items=menu(); Set<Integer> sources=new HashSet<>();
        for(int slot=10;slot<16;slot++) { items.set(slot,logs(1)); sources.add(slot); }
        var plan=create(items,sources); assertEquals(1,plan.quantity()); assertEquals(12,plan.steps().size());
        assertEquals(PLANKS,plan.steps().get(1).expectedItems().get(0));
        assertTrue(plan.steps().stream().allMatch(s -> s.click().type()==LoggingCraftPlan.Type.PICKUP && s.click().slot()!=0));
        assertEquals(FIRE,plan.placed().items().get(0)); assertTrue(plan.placed().carried().empty()); assertConserved(plan);
    }

    @Test void fragmentedDifferentNativeIdentitiesKeepEachIdentityAndReturnUnusedSourceRemainder() {
        List<Stack> items=menu(); items.set(10,new Stack("native:spruce-A",5,64)); items.set(11,new Stack("native:spruce-B",5,64));
        var plan=create(items,Set.of(10,11));
        assertEquals(1,plan.quantity()); assertTrue(plan.placed().items().get(10).empty());
        assertEquals(new Stack("native:spruce-B",4,64),plan.placed().items().get(11));
        assertEquals(new Stack("native:spruce-A",1,64),plan.placed().items().get(1));
        assertEquals(new Stack("native:spruce-B",1,64),plan.placed().items().get(6));
        assertTrue(plan.steps().size()<=14); assertConserved(plan);
    }

    @Test void allAcknowledgementsIncludeEveryInventorySlotAndExactCursorEvenAtNoOpDragStages() {
        List<Stack> items=menu(); items.set(10,logs(64)); var plan=create(items,Set.of(10));
        for(var step:plan.steps()) {
            assertTrue(step.matches(step.after()));
            List<Stack> changed=new ArrayList<>(step.expectedItems()); changed.set(45,new Stack("native:pickup",1,64));
            assertFalse(step.matches(new LoggingCraftPlan.Snapshot(changed,step.expectedCursor())));
            Stack cursor=step.expectedCursor().empty() ? logs(1) : Stack.EMPTY;
            assertFalse(step.matches(new LoggingCraftPlan.Snapshot(step.expectedItems(),cursor)));
        }
        assertFalse(plan.steps().get(8).matches(plan.steps().get(7).after()),"Unchanged drag ACK is not the distribution ACK");
        assertFalse(plan.steps().get(9).matches(plan.steps().get(8).after()),"Cursor remainder is not yet restored");
    }

    @Test void dirtyGridCursorNonInventoryAndUnverifiedSourcesAreRejectedBeforeAnyPlan() {
        List<Stack> items=menu(); items.set(10,logs(6));
        assertThrows(IllegalArgumentException.class,() -> LoggingCraftPlan.create(items,logs(1),Set.of(10),FIRE,LoggingCraftPlanTest::recipe));
        assertThrows(IllegalArgumentException.class,() -> create(items,Set.of(9)));
        assertThrows(IllegalArgumentException.class,() -> create(items,Set.of(46)));
        assertThrows(IllegalArgumentException.class,() -> create(items,Set.of(11)));
        assertThrows(IllegalArgumentException.class,() -> create(items,Set.of()));
        items.set(1,logs(1)); assertThrows(IllegalArgumentException.class,() -> create(items,Set.of(10)));
        assertThrows(IllegalArgumentException.class,() -> create(menu().subList(0,45),Set.of(10)));
    }

    @Test void wrongNativeRecipeResultAndMissingOutputCapacityRejectTheEntirePlan() {
        List<Stack> items=menu(); items.set(10,logs(64));
        assertThrows(IllegalArgumentException.class,() -> LoggingCraftPlan.create(items,Stack.EMPTY,Set.of(10),FIRE,i -> PLANKS));
        for(int i=11;i<46;i++) items.set(i,new Stack("native:full",64,64));
        assertThrows(IllegalArgumentException.class,() -> create(items,Set.of(10)));
        items.set(10,logs(60)); var freeingSource=create(items,Set.of(10));
        assertEquals(10,freeingSource.quantity()); assertTrue(freeingSource.placed().items().get(10).empty());
    }

    @Test void outputCapacityAcceptsOnlyNativeCompatibleExistingFireLogSpace() {
        List<Stack> items=menu(); for(int i=10;i<46;i++) items.set(i,new Stack("native:full",64,64));
        items.set(10,logs(64)); items.set(11,new Stack(FIRE.identity(),54,64));
        assertEquals(10,create(items,Set.of(10)).quantity());
        items.set(11,new Stack("native:other-fire-tags",54,64));
        assertThrows(IllegalArgumentException.class,() -> create(items,Set.of(10)));
    }

    @Test void planSnapshotsAndResolverInputsAreImmutableAndDoNotBorrowTheLiveMenu() {
        List<Stack> items=menu(); items.set(10,logs(12));
        var plan=LoggingCraftPlan.create(items,Stack.EMPTY,Set.of(10),FIRE,input -> {
            assertThrows(UnsupportedOperationException.class,() -> input.set(1,Stack.EMPTY)); return recipe(input);
        });
        items.set(10,Stack.EMPTY); assertEquals(logs(12),plan.initial().items().get(10));
        assertThrows(UnsupportedOperationException.class,() -> plan.steps().clear());
        assertThrows(UnsupportedOperationException.class,() -> plan.placed().items().clear());
    }

    private static void assertConserved(LoggingCraftPlan.Plan plan) {
        Map<String,Integer> expected=totals(plan.initial());
        for(var step:plan.steps()) assertEquals(expected,totals(step.after()),"Neither ingredients nor unrelated inventory may disappear during placement");
    }
    private static Map<String,Integer> totals(LoggingCraftPlan.Snapshot snapshot) {
        Map<String,Integer> result=new HashMap<>();
        for(Stack item:snapshot.items().subList(1,46)) if(!item.empty()) result.merge(item.identity(),item.count(),Integer::sum);
        if(!snapshot.carried().empty()) result.merge(snapshot.carried().identity(),snapshot.carried().count(),Integer::sum);
        return result;
    }
}
