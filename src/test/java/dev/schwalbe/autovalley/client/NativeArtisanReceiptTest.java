package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the predicates used by detached native receipts, without bootstrapping a game or registries. */
class NativeArtisanReceiptTest {
    private static final ArtisanRecipe SEED=ArtisanRecipe.ANCIENT_SEED,JADE=ArtisanRecipe.JADE_CRYSTAL;
    private static final Pos TARGET=new Pos(1,64,0),OTHER=new Pos(2,64,0);
    private static boolean feed(ArtisanRecipe recipe,int before,String afterId,int after,boolean sameTags,boolean collected) {
        return NativeArtisanReceipt.compatibleFeed(recipe,recipe.inputId(),before,afterId,after,sameTags,collected);
    }
    private static NativeArtisanReceipt.SlotProof<String> slot(long seq,int menu,int slot,String value) {
        return new NativeArtisanReceipt.SlotProof<>(seq,menu,slot,false,true,value);
    }
    private static NativeArtisanReceipt.SlotProof<String> full(long seq,int menu,boolean cursorEmpty,String value) {
        return new NativeArtisanReceipt.SlotProof<>(seq,menu,-1,true,cursorEmpty,value);
    }
    private static String latest(List<NativeArtisanReceipt.SlotProof<String>> proofs) { return NativeArtisanReceipt.latestSelected(2,0,38,100,proofs); }
    @Test void seedFeedRequiresExactThreeConsumptionAndPreservedRemainingIdentity() {
        assertTrue(feed(SEED,16,SEED.inputId(),13,true,true));
        for(int after:List.of(0,12,14,16,17))assertFalse(feed(SEED,16,SEED.inputId(),after,true,true));
        assertFalse(feed(SEED,16,SEED.inputId(),13,false,true));
        assertFalse(feed(SEED,16,"minecraft:apple",13,true,true));
    }
    @Test void inadequateOrWrongIngredientNeverConfirms() {
        for(int count:List.of(-1,0,1,2))assertFalse(feed(SEED,count,"minecraft:air",0,true,true));
        assertFalse(NativeArtisanReceipt.compatibleFeed(SEED,JADE.inputId(),3,"minecraft:air",0,true,true));
        assertFalse(NativeArtisanReceipt.compatibleFeed(null,SEED.inputId(),3,"minecraft:air",0,true,true));
    }
    @Test void lastSeedBatchCanLeaveEmptyOrItsOneOldOutputButNotAnUnrelatedReplacement() {
        assertTrue(feed(SEED,3,"minecraft:air",0,false,true));
        assertTrue(feed(SEED,3,SEED.outputId(),1,false,true));
        assertFalse(feed(SEED,3,SEED.outputId(),2,false,true));
        assertFalse(feed(SEED,3,"minecraft:apple",1,false,true));
        assertFalse(feed(SEED,3,SEED.outputId(),1,false,false));
    }
    @Test void jadeCoalescedOutputIsBoundedToExactlyTwoAdditionalCrystals() {
        for(int count:List.of(6,7,8))assertTrue(feed(JADE,7,JADE.inputId(),count,true,true));
        for(int count:List.of(0,5,9,64))assertFalse(feed(JADE,7,JADE.inputId(),count,true,true));
        assertFalse(feed(JADE,7,JADE.inputId(),7,true,false));
        assertTrue(feed(JADE,7,JADE.inputId(),6,true,false));
    }
    @Test void jadeResidualInputCannotChangeNativeTagsEvenWithinItsNetCountRange() {
        for(int count:List.of(6,7,8))assertFalse(feed(JADE,7,JADE.inputId(),count,false,true));
    }
    @Test void consumedLastJadeMayBeReplacedByDifferentlyGradedPriorBatchOutput() {
        assertTrue(feed(JADE,1,JADE.outputId(),1,false,true));
        assertTrue(feed(JADE,1,JADE.outputId(),2,false,true));
        assertFalse(feed(JADE,1,JADE.outputId(),3,false,true));
        assertFalse(feed(JADE,1,JADE.outputId(),1,false,false));
        assertFalse(feed(JADE,2,JADE.outputId(),2,false,true));
    }
    @Test void negativeAndOverflowedQuantityCannotImpersonateAValidRemainingStack() {
        assertFalse(feed(JADE,3,JADE.outputId(),-1,true,true));
        assertFalse(feed(JADE,7,JADE.outputId(),Integer.MAX_VALUE,true,true));
        assertFalse(feed(SEED,3,SEED.outputId(),Integer.MAX_VALUE,true,true));
    }
    @Test void rawStateMustMatchExactMachineAndBothKnownBooleanProperties() {
        assertTrue(NativeArtisanReceipt.validState(SEED.machineId(),SEED.machineId(),"false","true",true));
        assertTrue(NativeArtisanReceipt.validState(SEED.machineId(),SEED.machineId(),"false","false",false));
        for(String mature:Arrays.asList(null,"","true","FALSE"))
            assertFalse(NativeArtisanReceipt.validState(SEED.machineId(),SEED.machineId(),mature,"true",true));
        for(String working:Arrays.asList(null,"","false","TRUE"))
            assertFalse(NativeArtisanReceipt.validState(SEED.machineId(),SEED.machineId(),"false",working,true));
        assertFalse(NativeArtisanReceipt.validState(SEED.machineId(),JADE.machineId(),"false","true",true));
    }
    @Test void targetProofIgnoresUnrelatedCoordinatesAndHistoricalPackets() {
        var old=new NativeArtisanReceipt.StateProof(100,TARGET,SEED.machineId(),"false","true");
        var other=new NativeArtisanReceipt.StateProof(103,OTHER,SEED.machineId(),"false","true");
        assertNull(NativeArtisanReceipt.latestProof(List.of(old,other),100,NativeArtisanReceipt.StateProof::seq,p->p.pos().equals(TARGET)));
    }
    @Test void newerTargetInvalidationWinsOverOlderSuccessfulWorkingReceipt() {
        var good=new NativeArtisanReceipt.StateProof(101,TARGET,SEED.machineId(),"false","true");
        var replaced=new NativeArtisanReceipt.StateProof(103,TARGET,"minecraft:air",null,null);
        var latest=NativeArtisanReceipt.latestProof(List.of(replaced,good),100,NativeArtisanReceipt.StateProof::seq,p->p.pos().equals(TARGET));
        assertEquals(replaced,latest);
        assertFalse(NativeArtisanReceipt.validState(SEED.machineId(),latest.id(),latest.mature(),latest.working(),true));
    }
    @Test void unrelatedInventoryChangesAndOtherMenusCannotSupplySelectedSlotProof() {
        assertNull(latest(List.of(slot(101,0,37,"consumed"),slot(102,-2,1,"consumed"),full(103,7,true,"consumed"))));
    }
    @Test void selectedSlotUsesActualMenuMappingOrMinusTwoInventoryIndexOnly() {
        assertEquals("menu-slot",latest(List.of(slot(101,0,38,"menu-slot"))));
        assertEquals("inventory-index",latest(List.of(slot(102,-2,2,"inventory-index"))));
        assertNull(latest(List.of(slot(101,0,2,"wrong-index"),slot(102,-2,38,"wrong-index"))));
    }
    @Test void latestFullAndSlotProofAreComparedBySequenceNotIterationCategory() {
        assertEquals("full-new",latest(List.of(slot(102,-2,2,"slot-old"),full(103,0,true,"full-new"))));
        assertEquals("slot-new",latest(List.of(full(102,0,true,"full-old"),slot(104,0,38,"slot-new"))));
        assertEquals("minus-two-new",latest(List.of(slot(104,-2,2,"minus-two-new"),slot(102,0,38,"slot-old"))));
    }
    @Test void newUnchangedSlotCannotBorrowAnOlderCompatibleDecrease() {
        assertEquals("unchanged",latest(List.of(full(101,0,true,"consumed"),slot(102,0,38,"unchanged"))));
        assertFalse(feed(SEED,16,SEED.inputId(),16,true,true));
    }
    @Test void newOccupiedCursorOrMissingFullSlotInvalidatesOlderCompatibleEvidence() {
        assertNull(latest(List.of(slot(101,0,38,"consumed"),full(102,0,false,"consumed"))));
        assertNull(latest(List.of(slot(101,0,38,"consumed"),full(102,0,true,null))));
    }
    @Test void oldAndMalformedSelectedReceiptInputsFailClosed() {
        assertNull(latest(List.of(slot(99,0,38,"old"),full(100,0,true,"equal"))));
        assertNull(NativeArtisanReceipt.latestSelected(-1,0,38,100,List.of(slot(101,0,38,"bad"))));
        assertNull(NativeArtisanReceipt.latestSelected(9,0,38,100,List.of(slot(101,0,38,"bad"))));
        assertNull(NativeArtisanReceipt.latestSelected(2,0,-1,100,List.of(slot(101,0,38,"bad"))));
        assertNull(latest(null));
    }
}
