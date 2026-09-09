package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.*;
import java.util.function.BiPredicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached raw/FULL evidence; authentication of those packet origins remains the native adapter's job. */
class NativeRestorePickupReceiptTest {
    private static final Stack EMPTY=Stack.EMPTY;
    private static final Stack TORCH=item("minecraft:torch",62,"{}");
    private static Stack wine(int count){return item(ItemData.WINE,count,"{Year:14,quality:0,initialized:1b}");}
    private static Stack item(String id,int count,String tag){return new Stack("{Count:1b,id:\""+id+"\",tag:"+tag+"}",count,64);}
    private static NativeRestorePickupReceipt.SlotProof raw(long seq,int menu,int slot,Stack item){return new NativeRestorePickupReceipt.SlotProof(seq,menu,slot,item);}

    @Test void rawProductionPickupThenFullInversePreservesEveryBorrowedAndMergedUnitWithoutMutatingEvidence() {
        for(String id:List.of(ItemData.WINE,ItemData.TOMATO,ItemData.PRESERVES))
            for(int count:new int[]{1,64}) {
                Fixture f=new Fixture(); Stack pickup=item(id,count,"{Year:14,quality:0,initialized:1b}");
                f.after.set(10,pickup); f.slots=List.of(raw(2465,0,42,pickup));
                var before=List.copyOf(f.before);var after=List.copyOf(f.after);var slots=List.copyOf(f.slots);
                assertEquals(pickup,f.proof()); assertEquals(before,f.before);assertEquals(after,f.after);assertEquals(slots,f.slots);
                assertEquals(TORCH,f.after.get(42));assertEquals(wine(34),f.after.get(12));
                assertEquals(count,f.after.get(10).count());assertEquals(62,f.after.get(42).count());
            }
    }

    @Test void initializedWineRequiresExactNativePassiveReproductionOfTheRawPickup() {
        Fixture f=new Fixture();Stack rawWine=item(ItemData.WINE,1,"{}");
        f.slots=List.of(raw(2465,0,42,rawWine));assertNull(f.proof());
        int[] calls={0};f.passive=(old,now)->{calls[0]++;return old.equals(rawWine)&&now.equals(wine(1));};
        assertEquals(wine(1),f.proof());assertEquals(1,calls[0]);
        f.after.set(10,item(ItemData.WINE,1,"{Year:14,quality:1,initialized:1b}"));assertNull(f.proof());
        f.after.set(10,item(ItemData.WINE,1,"{Year:13,quality:0,initialized:1b}"));assertNull(f.proof());
        f.after.set(10,item(ItemData.TOMATO,1,"{}"));assertNull(f.proof());
    }

    @Test void exactRawIdentityNeedsNoMetadataException() {
        Fixture f=new Fixture();f.passive=(old,now)->{throw new AssertionError("Exact raw identity is already the evidence");};
        assertEquals(wine(1),f.proof());
    }

    @Test void evenApprovedMetadataCannotHideWrongCountsLimitsOrNonproductionItems() {
        for(int invalid=0;invalid<7;invalid++) {
            Fixture f=new Fixture();Stack rawWine=wine(1);f.passive=(old,now)->true;
            switch(invalid) {
                case 0 -> f.after.set(10,wine(2));
                case 1 -> rawWine=new Stack(wine(1).identity(),1,16);
                case 2 -> {rawWine=new Stack(wine(1).identity(),1,128);f.after.set(10,rawWine);}
                case 3 -> {rawWine=item("minecraft:torch",1,"{}");f.after.set(10,rawWine);}
                case 4 -> {rawWine=new Stack("not_native_stack_data",1,64);f.after.set(10,rawWine);}
                case 5 -> rawWine=EMPTY;
                case 6 -> f.after.set(10,EMPTY);
            }
            f.slots=List.of(raw(2465,0,42,rawWine));assertNull(f.proof(),"invalid="+invalid);
        }
    }

    @Test void onlyTheNewestScratchPacketBeforeTheFullReplyCanProveThePickup() {
        Fixture f=new Fixture();f.fullSequence=2470;
        f.slots=List.of(raw(2465,0,42,wine(1)),raw(2469,0,42,wine(2)));assertNull(f.proof());
        f.slots=List.of(raw(2469,0,42,wine(1)),raw(2465,0,42,wine(2)));assertEquals(wine(1),f.proof(),"List order is not packet order");
        f.slots=List.of(raw(2465,0,42,wine(1)),raw(2469,0,42,EMPTY));assertNull(f.proof());
        f.slots=List.of(raw(2464,0,42,wine(1)),raw(2470,0,42,wine(1)),raw(2471,0,42,wine(1)));assertNull(f.proof());
        f.slots=List.of(raw(2465,0,42,wine(1)),raw(2469,3,42,wine(2)),raw(2471,0,42,wine(2)));
        assertEquals(wine(1),f.proof(),"Another menu or a later packet cannot rewrite this independently authenticated historical FULL");
    }

    @Test void missingWrongSlotMalformedAndDuplicateRawEvidenceIsRejected() {
        for(int invalid=0;invalid<9;invalid++) {
            Fixture f=new Fixture();f.fullSequence=2470;
            f.slots=switch(invalid) {
                case 0 -> null;
                case 1 -> List.of();
                case 2 -> List.of(raw(2465,0,10,wine(1)));
                case 3 -> List.of(raw(2465,3,42,wine(1)));
                case 4 -> List.of(raw(2465,0,-1,wine(1)));
                case 5 -> List.of(raw(2465,0,46,wine(1)));
                case 6 -> List.of(raw(2465,0,42,null));
                case 7 -> Arrays.asList((NativeRestorePickupReceipt.SlotProof)null);
                default -> List.of(raw(2465,0,42,wine(1)),raw(2465,0,12,wine(34)));
            };
            assertNull(f.proof(),"invalid="+invalid);
        }
    }

    @Test void generationSequenceNormalMenuAndNativeSlotRegionsRemainStrict() {
        for(int invalid=0;invalid<15;invalid++) {
            Fixture f=new Fixture();
            switch(invalid) {
                case 0 -> f.observedGeneration++;
                case 1 -> {f.generation=-1;f.observedGeneration=-1;}
                case 2 -> f.menuId=3;
                case 3 -> f.beforeSequence=-1;
                case 4 -> f.fullSequence=f.beforeSequence;
                case 5 -> f.fullSequence=f.beforeSequence-1;
                case 6 -> f.sourceSlot=8;
                case 7 -> f.sourceSlot=36;
                case 8 -> f.sourceSlot=-1;
                case 9 -> f.scratchSlot=35;
                case 10 -> f.scratchSlot=45;
                case 11 -> f.scratchSlot=f.sourceSlot;
                case 12 -> f.sourceSlot=Integer.MAX_VALUE;
                case 13 -> f.scratchSlot=Integer.MAX_VALUE;
                case 14 -> f.menuId=-2;
            }
            assertNull(f.proof(),"invalid="+invalid);
        }
    }

    @Test void theBorrowedStackAndEveryOtherMenuSlotRemainExact() {
        for(Stack changed:List.of(EMPTY,wine(1),item("minecraft:torch",61,"{}"),item("minecraft:torch",63,"{}"),
                item("minecraft:torch",62,"{changed:1b}"),new Stack(TORCH.identity(),62,128))) {
            Fixture f=new Fixture();f.after.set(42,changed);assertNull(f.proof());
        }
        for(int slot:new int[]{0,4,5,8,9,11,12,15,40,45}) {
            Fixture f=new Fixture();f.after.set(slot,wine(1));assertNull(f.proof(),"Unproven menu slot="+slot);
        }
    }

    @Test void noOpPickupOnlyCursorOrIncompleteMenuEvidenceCannotBecomeAnInverseSwapReceipt() {
        for(int invalid=0;invalid<13;invalid++) {
            Fixture f=new Fixture();
            switch(invalid) {
                case 0 -> f.after=new ArrayList<>(f.before);
                case 1 -> {f.after=new ArrayList<>(f.before);f.after.set(42,wine(1));}
                case 2 -> f.after.set(10,EMPTY);
                case 3 -> f.cursorEmpty=false;
                case 4 -> f.before.remove(45);
                case 5 -> f.after.add(EMPTY);
                case 6 -> f.before=null;
                case 7 -> f.after=null;
                case 8 -> f.before.set(20,null);
                case 9 -> f.after.set(20,null);
                case 10 -> f.before.set(10,EMPTY);
                case 11 -> f.before.set(42,wine(1));
                case 12 -> f.passive=null;
            }
            assertNull(f.proof(),"invalid="+invalid);
        }
    }

    private static final class Fixture {
        long generation=7,observedGeneration=7,beforeSequence=2464,fullSequence=2466;
        int menuId=0,sourceSlot=10,scratchSlot=42;boolean cursorEmpty=true;
        List<Stack> before=new ArrayList<>(Collections.nCopies(46,EMPTY));List<Stack> after;
        List<NativeRestorePickupReceipt.SlotProof> slots=List.of(raw(2465,0,42,wine(1)));
        BiPredicate<Stack,Stack> passive=(old,now)->false;
        Fixture() {
            before.set(10,TORCH);before.set(12,wine(34));before.set(40,new Stack("protected_hoe{exact}",1,1));
            before.set(15,item("minecraft:apple",3,"{}"));
            after=new ArrayList<>(before);after.set(10,wine(1));after.set(42,TORCH);
        }
        Stack proof(){return NativeRestorePickupReceipt.verifiedPickup(generation,observedGeneration,menuId,
            beforeSequence,fullSequence,sourceSlot,scratchSlot,before,after,cursorEmpty,slots,passive);}
    }
}
