package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.*;
import java.util.function.BiPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeRestoreMetadataReceiptTest {
    private static Stack item(String id,int count,String tag){return new Stack("{Count:1b,id:\""+id+"\",tag:"+tag+"}",count,64);}
    private static Stack wine(int count,boolean refreshed){return item(ItemData.WINE,count,
        "{Year:15,quality:3,EffectAmplifier:"+(refreshed?1:0)+",EffectDuration:"+(refreshed?1800:1560)+"}");}
    private static final Stack TORCH=item("minecraft:torch",13,"{}");

    @Test void exactNativeWineRefreshAndInversePreserveBothPartnersAndAllOtherSlots() {
        for(int count:new int[]{1,64}) {
            Fixture f=new Fixture();f.before.set(42,wine(count,false));f.after.set(9,wine(count,true));
            List<Stack> before=List.copyOf(f.before),after=List.copyOf(f.after);
            assertEquals(wine(count,true),f.proof());assertEquals(before,f.before);assertEquals(after,f.after);
            assertEquals(TORCH,f.after.get(42));
        }
    }
    @Test void metadataNeedsExactPredictionAndOriginalTransactionYearNotJustSamePublicWine() {
        Fixture f=new Fixture();f.passive=(old,now)->false;assertNull(f.proof());
        f=new Fixture();f.currentYear=16;assertNull(f.proof());
        f=new Fixture();f.transactionYear=null;assertNull(f.proof());
        f=new Fixture();f.transactionYear=16;assertNull(f.proof());
        for(String key:List.of("Year","quality","custom","EffectAmplifier","EffectDuration")) {
            Fixture changed=new Fixture();
            CompoundTag tag=parse(wine(1,true).identity());tag.getCompound("tag").putInt(key,99);
            changed.after.set(9,new Stack(tag.toString(),1,64));assertNull(changed.proof(),key);
        }
    }
    @Test void nonWineCountLimitAndBorrowedChangesCannotBeApprovedEvenByAnOverbroadCallback() {
        for(int invalid=0;invalid<8;invalid++) {
            Fixture f=new Fixture();f.passive=(old,now)->true;
            switch(invalid) {
                case 0 -> f.after.set(9,wine(2,true));
                case 1 -> f.after.set(9,new Stack(wine(1,true).identity(),1,16));
                case 2 -> {f.before.set(42,new Stack(wine(1,false).identity(),1,128));f.after.set(9,new Stack(wine(1,true).identity(),1,128));}
                case 3 -> f.before.set(42,item(ItemData.TOMATO,1,"{}"));
                case 4 -> f.after.set(9,item(ItemData.PRESERVES,1,"{}"));
                case 5 -> f.before.set(42,new Stack("malformed",1,64));
                case 6 -> f.after.set(42,item("minecraft:torch",12,"{}"));
                case 7 -> f.after.set(42,item("minecraft:torch",13,"{changed:1b}"));
            }
            assertNull(f.proof(),"invalid="+invalid);
        }
    }
    @Test void allFortyFourNonparticipantMenuSlotsStayExactIncludingCraftingArmorAndOffhand() {
        for(int slot=0;slot<46;slot++)if(slot!=9 && slot!=42) {
            Fixture f=new Fixture();f.after.set(slot,wine(1,true));assertNull(f.proof(),"slot="+slot);
        }
    }
    @Test void noOpMetadataOnlyOrIdenticalPartnersCannotClaimTheInverse() {
        Fixture noOp=new Fixture();noOp.after=new ArrayList<>(noOp.before);assertNull(noOp.proof());
        Fixture metadata=new Fixture();metadata.after=new ArrayList<>(metadata.before);metadata.after.set(42,wine(1,true));assertNull(metadata.proof());
        Fixture ordinary=new Fixture();ordinary.after.set(9,wine(1,false));assertNull(ordinary.proof());
        Fixture identical=new Fixture();identical.before.set(9,wine(1,true));identical.after.set(42,wine(1,true));assertNull(identical.proof());
    }
    @Test void generationSequenceCursorShapeAndSlotMappingRemainFailClosed() {
        for(int invalid=0;invalid<15;invalid++) {
            Fixture f=new Fixture();
            switch(invalid) {
                case 0 -> f.observedGeneration++;
                case 1 -> {f.generation=-1;f.observedGeneration=-1;}
                case 2 -> f.menu=1;
                case 3 -> f.beforeSequence=-1;
                case 4 -> f.fullSequence=f.beforeSequence;
                case 5 -> f.fullSequence=f.beforeSequence-1;
                case 6 -> f.cursorEmpty=false;
                case 7 -> f.source=8;
                case 8 -> f.source=36;
                case 9 -> f.scratch=35;
                case 10 -> f.scratch=45;
                case 11 -> f.before.remove(45);
                case 12 -> f.after.add(Stack.EMPTY);
                case 13 -> f.before.set(9,Stack.EMPTY);
                case 14 -> f.before.set(42,Stack.EMPTY);
            }
            assertNull(f.proof(),"invalid="+invalid);
        }
    }
    @Test void aCopiedOrMissingFullReplyCannotBorrowRetainedPacketProvenance() {
        record Reply(long sequence,List<String> contents) { }
        Reply actual=new Reply(7629,List.of("actual FULL")),copy=new Reply(7629,List.of("actual FULL"));
        assertEquals(actual,copy);assertNotSame(actual,copy);
        assertTrue(NativeRestoreMetadataReceipt.retainedFull(actual,List.of(actual)));
        assertFalse(NativeRestoreMetadataReceipt.retainedFull(copy,List.of(actual)));
        assertFalse(NativeRestoreMetadataReceipt.retainedFull(actual,List.of()));
        assertFalse(NativeRestoreMetadataReceipt.retainedFull(null,List.of(actual)));
        assertFalse(NativeRestoreMetadataReceipt.retainedFull(actual,null));
    }
    private static CompoundTag parse(String value) {
        try{return TagParser.parseTag(value);}catch(Exception bad){throw new AssertionError(bad);}
    }
    private static final class Fixture {
        long generation=5,observedGeneration=5,beforeSequence=7628,fullSequence=7629;
        int menu=0,source=9,scratch=42;boolean cursorEmpty=true;Integer transactionYear=15,currentYear=15;
        List<Stack> before=new ArrayList<>(Collections.nCopies(46,Stack.EMPTY)),after;
        BiPredicate<Stack,Stack> passive=(old,now)->NativeWineMetadata.exactPassiveChange(parse(old.identity()),
            parse(now.identity()),parse(wine(1,true).identity()),transactionYear,currentYear);
        Fixture(){before.set(9,TORCH);before.set(42,wine(1,false));before.set(12,wine(34,false));after=new ArrayList<>(before);
            after.set(9,wine(1,true));after.set(42,TORCH);}
        Stack proof(){return NativeRestoreMetadataReceipt.verifiedMovedWine(generation,observedGeneration,menu,
            beforeSequence,fullSequence,source,scratch,before,after,cursorEmpty,passive);}
    }
}
