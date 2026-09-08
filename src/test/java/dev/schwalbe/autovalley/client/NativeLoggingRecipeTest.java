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
}
