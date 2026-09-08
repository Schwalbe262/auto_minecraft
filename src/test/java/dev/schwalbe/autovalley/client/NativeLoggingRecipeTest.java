package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoggingRecipeTest {
    private static Stack item(String id,int count) { return new Stack("{Count:1b,id:\""+id+"\"}",count,64); }
    private static final Stack OUTPUT=item(LoggingRules.FIRE_LOG,1);
    private static ArrayList<Stack> empty() { return new ArrayList<>(Collections.nCopies(46,Stack.EMPTY)); }
    private static ArrayList<Stack> before(int quantity) {
        var slots=empty(); int left=quantity*6;
        for (int i=10;left>0;i++) { int n=Math.min(64,left); slots.set(i,item(LoggingRules.LOG,n)); left-=n; }
        slots.set(45,item("minecraft:netherite_axe",1)); return slots;
    }
    private static ArrayList<Stack> placed(int quantity) {
        var slots=empty(); slots.set(0,OUTPUT);
        for (int i=1;i<=6;i++) slots.set(i,item(LoggingRules.LOG,quantity));
        slots.set(45,item("minecraft:netherite_axe",1)); return slots;
    }
    private static ArrayList<Stack> done(int quantity) {
        var slots=empty(); slots.set(10,item(LoggingRules.FIRE_LOG,quantity)); slots.set(45,item("minecraft:netherite_axe",1)); return slots;
    }
    @Test void synchronizedKnownRecipeDoesNotRequireAClientRecipeBookEntry() {
        // No client-book flag enters the native recipe-data guard. The server
        // independently accepts or refuses the ordinary place-recipe packet.
        assertTrue(NativeLoggingRecipe.knownRecipe(true,true,LoggingRules.FIRE_LOG,1,Collections.nCopies(6,true)));
        assertTrue(NativeLoggingRecipe.useManualPlacement(false));
        assertFalse(NativeLoggingRecipe.useManualPlacement(true));
    }
    @Test void removingClientBookPrecheckDoesNotBroadenRecipeShapeOutputOrIngredients() {
        var spruce=Collections.nCopies(6,true);
        assertFalse(NativeLoggingRecipe.knownRecipe(false,true,LoggingRules.FIRE_LOG,1,spruce));
        assertFalse(NativeLoggingRecipe.knownRecipe(true,false,LoggingRules.FIRE_LOG,1,spruce));
        assertFalse(NativeLoggingRecipe.knownRecipe(true,true,"minecraft:spruce_planks",1,spruce));
        for (int count:List.of(0,2,64)) assertFalse(NativeLoggingRecipe.knownRecipe(true,true,LoggingRules.FIRE_LOG,count,spruce));
        for (int size:List.of(0,5,7,9))
            assertFalse(NativeLoggingRecipe.knownRecipe(true,true,LoggingRules.FIRE_LOG,1,Collections.nCopies(size,true)));
        var wrongIngredient=new ArrayList<>(spruce); wrongIngredient.set(4,false);
        assertFalse(NativeLoggingRecipe.knownRecipe(true,true,LoggingRules.FIRE_LOG,1,wrongIngredient));
        wrongIngredient.set(4,null);
        assertFalse(NativeLoggingRecipe.knownRecipe(true,true,LoggingRules.FIRE_LOG,1,wrongIngredient));
        assertFalse(NativeLoggingRecipe.knownRecipe(true,true,null,1,spruce));
        assertFalse(NativeLoggingRecipe.knownRecipe(true,true,LoggingRules.FIRE_LOG,1,null));
    }
    @Test void serverRefusingRecipeBookPlacementCannotBeMistakenForPermissionToTakeOutput() {
        var original=before(1);
        assertEquals(0,NativeLoggingRecipe.placement(original,new ArrayList<>(original),OUTPUT),
            "A true full self-swap ACK of the unchanged grid is still not recipe placement");
        var onlyPickup=new ArrayList<>(original); onlyPickup.set(20,item(LoggingRules.FIRE_LOG,2));
        assertEquals(0,NativeLoggingRecipe.placement(original,onlyPickup,OUTPUT));
        assertFalse(NativeLoggingRecipe.crafted(original,onlyPickup,OUTPUT,1));
    }
    @Test void exactSixIngredientNativePlacementAndResultCountAreRequired() {
        for(int n:List.of(1,14,64)) {
            assertEquals(n,NativeLoggingRecipe.placement(before(n),placed(n),OUTPUT));
            assertTrue(NativeLoggingRecipe.crafted(placed(n),done(n),OUTPUT,n));
        }
    }
    @Test void unchangedMenuOrPickupAloneCannotConfirmPlacement() {
        var original=before(1); var pickup=before(1); pickup.set(20,OUTPUT);
        assertEquals(0,NativeLoggingRecipe.placement(original,original,OUTPUT));
        assertEquals(0,NativeLoggingRecipe.placement(original,pickup,OUTPUT));
    }
    @Test void wrongSpeciesAndChangedRecipeAmountAreRejected() {
        var after=placed(1); after.set(6,item("minecraft:oak_log",1));
        assertEquals(0,NativeLoggingRecipe.placement(before(1),after,OUTPUT));
        after=placed(2); after.set(6,item(LoggingRules.LOG,1));
        assertEquals(0,NativeLoggingRecipe.placement(before(2),after,OUTPUT));
        after=placed(1); after.set(7,item(LoggingRules.LOG,1));
        assertEquals(0,NativeLoggingRecipe.placement(before(1),after,OUTPUT));
    }
    @Test void occupiedInitialGridAndWrongVirtualOutputCannotBeBorrowed() {
        var initial=before(1); initial.set(1,item(LoggingRules.LOG,1));
        assertEquals(0,NativeLoggingRecipe.placement(initial,placed(1),OUTPUT));
        var after=placed(1); after.set(0,item("minecraft:spruce_planks",1));
        assertEquals(0,NativeLoggingRecipe.placement(before(1),after,OUTPUT));
    }
    @Test void fireLogPickupWithoutConsumedGridIsNotCraftSuccess() {
        var after=placed(1); after.set(10,OUTPUT);
        assertFalse(NativeLoggingRecipe.crafted(placed(1),after,OUTPUT,1));
        assertFalse(NativeLoggingRecipe.crafted(placed(1),done(2),OUTPUT,1));
        assertFalse(NativeLoggingRecipe.crafted(placed(1),done(1),OUTPUT,2));
    }
    @Test void LostToolsOrUnrelatedChangesCannotCountAsCrafted() {
        var after=done(1); after.set(45,Stack.EMPTY);
        assertFalse(NativeLoggingRecipe.crafted(placed(1),after,OUTPUT,1));
        after=done(1); after.set(44,item("minecraft:torch",1));
        assertFalse(NativeLoggingRecipe.crafted(placed(1),after,OUTPUT,1));
    }
    @Test void FourLeftoverLogsAndExistingFireLogsAreConserved() {
        var initial=before(14); initial.set(20,item(LoggingRules.LOG,4)); initial.set(21,item(LoggingRules.FIRE_LOG,11));
        var staged=placed(14); staged.set(20,item(LoggingRules.LOG,4)); staged.set(21,item(LoggingRules.FIRE_LOG,11));
        assertEquals(14,NativeLoggingRecipe.placement(initial,staged,OUTPUT));
        var after=done(14); after.set(20,item(LoggingRules.LOG,4)); after.set(21,item(LoggingRules.FIRE_LOG,11));
        assertTrue(NativeLoggingRecipe.crafted(staged,after,OUTPUT,14));
        after.set(20,Stack.EMPTY); assertFalse(NativeLoggingRecipe.crafted(staged,after,OUTPUT,14));
    }
    private static LoggingCraftPlan.Plan manualPlan(int count) {
        var initial=empty(); initial.set(10,item(LoggingRules.LOG,count)); initial.set(45,item("minecraft:netherite_axe",1));
        return LoggingCraftPlan.create(initial,Stack.EMPTY,Set.of(10),OUTPUT,items -> {
            int occupied=0; for(int i=1;i<10;i++) if(!items.get(i).empty()) occupied++;
            return occupied==6 ? OUTPUT : occupied==1 ? item("minecraft:spruce_planks",4) : Stack.EMPTY;
        });
    }
    @Test void manualCursorOwnershipIsLimitedToTheCurrentExactBeforeOrAfter() {
        Stack cursor=item(LoggingRules.LOG,64);
        assertTrue(NativeLoggingRecipe.allowedManualCursor(Stack.EMPTY,Stack.EMPTY,cursor,true));
        assertTrue(NativeLoggingRecipe.allowedManualCursor(cursor,Stack.EMPTY,cursor,true));
        assertFalse(NativeLoggingRecipe.allowedManualCursor(cursor,Stack.EMPTY,cursor,false));
        assertFalse(NativeLoggingRecipe.allowedManualCursor(item(LoggingRules.LOG,63),Stack.EMPTY,cursor,true));
        assertFalse(NativeLoggingRecipe.allowedManualCursor(item("minecraft:oak_log",64),Stack.EMPTY,cursor,true));
        assertFalse(NativeLoggingRecipe.allowedManualCursor(null,Stack.EMPTY,cursor,true));
    }
    @Test void manualPickupsAndDragPhasesRequireTheirOwnNewFullExpectedState() {
        var plan=manualPlan(64);
        for(int index=0;index<plan.steps().size();index++) {
            var step=plan.steps().get(index);
            assertEquals(index+1==plan.steps().size() ? -1 : -2,
                NativeLoggingRecipe.manualPlacementAck(plan,index,true,100,101,step.after()));
            assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,index,false,100,101,step.after()));
            assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,index,true,100,100,step.after()));
            assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,index,true,100,99,step.after()));
        }
        assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,plan.steps().size(),true,100,101,plan.placed()));
        assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,-1,true,100,101,plan.initial()));
    }
    @Test void dragNoOpAcknowledgementsDoNotGrantOutputPermissionOrInventTheHiddenDragState() {
        var plan=manualPlan(64);
        for(int index=1;index<=7;index++) {
            assertEquals(plan.steps().get(index).before(),plan.steps().get(index).after());
            assertEquals(-2,NativeLoggingRecipe.manualPlacementAck(plan,index,true,100,101,plan.steps().get(index).after()));
        }
        int end=8;
        assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,end,true,100,101,plan.steps().get(end).before()),
            "Refused drag-end with unchanged inventory cannot proceed to leftover return or output");
        assertEquals(0,NativeLoggingRecipe.placement(plan.initial().items(),plan.steps().get(end).before().items(),OUTPUT));
    }
    @Test void changedCursorSourceToolOrPickupDoesNotAcknowledgeTheNextManualPrimitive() {
        var plan=manualPlan(64); var expected=plan.steps().get(0).after();
        assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,0,true,100,101,
            new LoggingCraftPlan.Snapshot(expected.items(),item(LoggingRules.LOG,63))));
        for(int index:List.of(10,20,45)) {
            var changed=new ArrayList<>(expected.items()); changed.set(index,OUTPUT);
            assertEquals(0,NativeLoggingRecipe.manualPlacementAck(plan,0,true,100,101,
                new LoggingCraftPlan.Snapshot(changed,expected.carried())));
        }
    }
    @Test void manualBatchReturnsFourRemaindersAndStillRequiresExactFireLogOutputConservation() {
        var plan=manualPlan(64); var placed=plan.placed();
        assertEquals(10,plan.quantity()); assertTrue(placed.carried().empty());
        assertEquals(4,placed.items().get(10).count());
        assertEquals(10,NativeLoggingRecipe.placement(plan.initial().items(),placed.items(),OUTPUT));
        var output=empty(); output.set(10,item(LoggingRules.LOG,4)); output.set(11,item(LoggingRules.FIRE_LOG,10));
        output.set(45,item("minecraft:netherite_axe",1));
        assertTrue(NativeLoggingRecipe.crafted(placed.items(),output,OUTPUT,10));
        output.set(11,item(LoggingRules.FIRE_LOG,11));
        assertFalse(NativeLoggingRecipe.crafted(placed.items(),output,OUTPUT,10));
    }
}
