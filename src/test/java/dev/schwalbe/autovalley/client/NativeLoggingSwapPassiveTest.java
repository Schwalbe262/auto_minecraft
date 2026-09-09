package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.*;
import java.util.function.BiPredicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached full-menu/metadata regressions; the native adapter owns generation and raw FULL provenance. */
class NativeLoggingSwapPassiveTest {
    private static final int SOURCE=27,DESTINATION=41;
    private static final String INITIALIZED="{Year:14,EffectAmplifier:0,EffectDuration:4800}";
    private static Stack item(String id,int count,String tag) {
        return new Stack("{id:\""+id+"\",Count:1b,tag:"+tag+"}",count,64);
    }
    private static Stack wine(String tag){return item(ItemData.WINE,1,tag);}
    private static CompoundTag root(Stack stack) {
        try {return TagParser.parseTag(stack.identity());}catch(Exception e){throw new AssertionError(e);}
    }
    private static BiPredicate<Stack,Stack> nativeResult(Stack prediction,Integer dispatchYear,Integer currentYear) {
        return (old,now)->NativeWineMetadata.exactPassiveChange(root(old),root(now),root(prediction),dispatchYear,currentYear);
    }
    private static final class Fixture {
        final List<Stack> before=new ArrayList<>(Collections.nCopies(46,Stack.EMPTY));
        final List<Stack> after;
        BiPredicate<Stack,Stack> passive=nativeResult(wine(INITIALIZED),14,14);
        Fixture() {
            before.set(SOURCE,item(ItemData.TOMATO,4,"{quality_food:{quality:2}}"));before.set(DESTINATION,wine("{}"));
            before.set(40,item("minecraft:golden_hoe",1,"{Damage:7}"));before.set(42,item("minecraft:torch",58,"{}"));
            after=new ArrayList<>(before);Collections.swap(after,SOURCE,DESTINATION);after.set(SOURCE,wine(INITIALIZED));
        }
        boolean proof(){return NativeLoggingSwap.swappedWithPassiveWine(before,after,SOURCE,DESTINATION,passive);}
    }

    @Test void exactFullTomatoWineSwapAllowsOnlyReproducedPassiveInitialization() {
        Fixture f=new Fixture();var old=List.copyOf(f.before);var current=List.copyOf(f.after);
        assertFalse(NativeLoggingSwap.swapped(f.before,f.after,SOURCE,DESTINATION));
        assertTrue(f.proof());assertEquals(old,f.before);assertEquals(current,f.after);
        assertEquals(f.before.get(SOURCE),f.after.get(DESTINATION));
    }
    @Test void WineSourceCanAlsoInitializeAtTheOppositeEndpoint() {
        Fixture f=new Fixture();Collections.swap(f.before,SOURCE,DESTINATION);
        f.after.set(SOURCE,f.before.get(DESTINATION));f.after.set(DESTINATION,wine(INITIALIZED));
        assertTrue(f.proof());
    }
    @Test void exactEndpointLegacyPredicateIsUnchangedAndNeedsNoMetadataCallback() {
        Fixture f=new Fixture();f.after.set(SOURCE,f.before.get(DESTINATION));
        f.passive=(old,now)->{throw new AssertionError("Exact endpoint must not invoke metadata exception");};
        assertTrue(f.proof());assertTrue(NativeLoggingSwap.swapped(f.before,f.after,SOURCE,DESTINATION));
        f.after.set(9,item(ItemData.TOMATO,1,"{}"));
        assertTrue(NativeLoggingSwap.swapped(f.before,f.after,SOURCE,DESTINATION),"Legacy exact-endpoint API remains unchanged");
        assertFalse(f.proof(),"The new metadata exception additionally requires every other slot unchanged");
    }
    @Test void noOpAndIdenticalEndpointSwapCannotGainProofFromNativeMetadata() {
        Fixture f=new Fixture();assertFalse(NativeLoggingSwap.swappedWithPassiveWine(f.before,f.before,SOURCE,DESTINATION,f.passive));
        f.before.set(SOURCE,f.before.get(DESTINATION));f.after.set(SOURCE,wine(INITIALIZED));f.after.set(DESTINATION,wine(INITIALIZED));
        assertFalse(f.proof());
    }
    @Test void unrelatedNormalArmorOffhandAndCursorGridSlotsRemainExact() {
        for(int slot:new int[]{0,1,5,9,26,28,40,42,45}) {
            Fixture f=new Fixture();f.after.set(slot,item(ItemData.TOMATO,1,"{}"));assertFalse(f.proof(),"slot="+slot);
        }
    }
    @Test void changedTomatoCountQualityOrIdentityCannotUseWineException() {
        for(Stack changed:List.of(item(ItemData.TOMATO,3,"{quality_food:{quality:2}}"),
                item(ItemData.TOMATO,4,"{quality_food:{quality:1}}"),item(ItemData.ROTTEN,4,"{}"),Stack.EMPTY)) {
            Fixture f=new Fixture();f.after.set(DESTINATION,changed);f.passive=(old,now)->true;assertFalse(f.proof());
        }
    }
    @Test void wineCountLimitItemOrMalformedIdentityAreRejectedBeforeException() {
        for(Stack changed:List.of(new Stack(wine(INITIALIZED).identity(),2,64),new Stack(wine(INITIALIZED).identity(),1,16),
                item(ItemData.TOMATO,1,"{}"),new Stack("invalid",1,64),Stack.EMPTY)) {
            Fixture f=new Fixture();f.after.set(SOURCE,changed);f.passive=(old,now)->true;assertFalse(f.proof());
        }
        Fixture f=new Fixture();f.before.set(DESTINATION,new Stack(wine("{}").identity(),1,128));
        f.after.set(SOURCE,new Stack(wine(INITIALIZED).identity(),1,128));f.passive=(old,now)->true;assertFalse(f.proof());
    }
    @Test void futureWrongYearQualityAndCustomTagChangesCannotMasqueradeAsNativeInitialization() {
        for(String tag:List.of("{Year:13,EffectAmplifier:0,EffectDuration:4800}",
                "{Year:14,EffectAmplifier:0,EffectDuration:4800,quality_food:{quality:1}}",
                "{Year:14,EffectAmplifier:0,EffectDuration:4800,custom:1}",
                "{Year:14,EffectAmplifier:0,EffectDuration:4800,display:{Name:'changed'}}",
                "{Year:14,EffectAmplifier:0,EffectDuration:9999}")) {
            Fixture f=new Fixture();f.after.set(SOURCE,wine(tag));assertFalse(f.proof(),tag);
        }
    }
    @Test void knownCohortMayOnlyRefreshExactNativeCacheWithItsOriginalQuality() {
        Fixture f=new Fixture();f.before.set(DESTINATION,wine("{Year:12,EffectAmplifier:1,EffectDuration:100,quality_food:{quality:3}}"));
        Stack predicted=wine("{Year:12,EffectAmplifier:2,EffectDuration:200,quality_food:{quality:3}}");
        f.after.set(SOURCE,predicted);f.passive=nativeResult(predicted,14,14);assertTrue(f.proof());
        for(String changed:List.of("{Year:13,EffectAmplifier:2,EffectDuration:200,quality_food:{quality:3}}",
                "{Year:12,EffectAmplifier:2,EffectDuration:200,quality_food:{quality:2}}")) {
            f.after.set(SOURCE,wine(changed));f.passive=nativeResult(wine(changed),14,14);assertFalse(f.proof());
        }
    }
    @Test void missingChangedClockAndUnavailableNativePredictionFailClosed() {
        for(Integer[] years:List.of(new Integer[]{null,14},new Integer[]{14,null},new Integer[]{14,15})) {
            Fixture f=new Fixture();f.passive=nativeResult(wine(INITIALIZED),years[0],years[1]);assertFalse(f.proof());
        }
        Fixture f=new Fixture();f.passive=(old,now)->false;assertFalse(f.proof());
        f.passive=(old,now)->{throw new IllegalStateException("Unavailable native metadata");};assertFalse(f.proof());
    }
    @Test void wrongShapeProtectedRegionMissingCallbackAndDuplicateEndpointsAreRejected() {
        Fixture f=new Fixture();
        assertFalse(NativeLoggingSwap.swappedWithPassiveWine(null,f.after,SOURCE,DESTINATION,f.passive));
        assertFalse(NativeLoggingSwap.swappedWithPassiveWine(f.before,null,SOURCE,DESTINATION,f.passive));
        assertFalse(NativeLoggingSwap.swappedWithPassiveWine(f.before.subList(0,45),f.after,SOURCE,DESTINATION,f.passive));
        assertFalse(NativeLoggingSwap.swappedWithPassiveWine(f.before,f.after,SOURCE,DESTINATION,null));
        for(int[] indices:new int[][]{{-1,41},{0,41},{8,41},{45,41},{27,35},{27,45},{41,41}})
            assertFalse(NativeLoggingSwap.swappedWithPassiveWine(f.before,f.after,indices[0],indices[1],f.passive));
        f.after.set(9,null);assertFalse(f.proof());
    }
}
