package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.ItemData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached native NBT only: no client, server, registries, inventory or game input. */
class NativeWineMetadataTest {
    private static CompoundTag wine() {
        CompoundTag root=new CompoundTag();root.putString("id",ItemData.WINE);root.putByte("Count",(byte)1);return root;
    }
    private static CompoundTag initialized(int year,int amplifier,int duration) {
        CompoundTag root=wine(),tag=new CompoundTag();tag.putInt("Year",year);
        tag.putInt("EffectAmplifier",amplifier);tag.putInt("EffectDuration",duration);root.put("tag",tag);return root;
    }
    private static boolean accepts(CompoundTag before,CompoundTag after,CompoundTag prediction) {
        return NativeWineMetadata.exactPassiveChange(before,after,prediction,9,9);
    }
    @Test void missingTagAcceptsOnlyExactNativeCurrentYearInitialization() {
        assertTrue(accepts(wine(),initialized(9,0,1200),initialized(9,0,1200)));
        assertFalse(accepts(wine(),initialized(8,0,1200),initialized(8,0,1200)));
        assertFalse(accepts(wine(),initialized(9,0,1201),initialized(9,0,1200)));
    }
    @Test void knownCohortAcceptsExactCacheRefreshWithoutChangingCohort() {
        assertTrue(accepts(initialized(7,0,1200),initialized(7,1,1800),initialized(7,1,1800)));
        assertFalse(accepts(initialized(7,0,1200),initialized(8,1,1800),initialized(8,1,1800)));
    }
    @Test void removingKnownYearOrEntireTagIsRejected() {
        CompoundTag after=initialized(7,1,1800);after.getCompound("tag").remove("Year");
        assertFalse(accepts(initialized(7,0,1200),after,after));
        assertFalse(accepts(initialized(7,0,1200),wine(),wine()));
    }
    @Test void unrelatedItemAndChangedRootCountAreRejected() {
        CompoundTag before=wine(),after=initialized(9,0,1200);
        before.putString("id",ItemData.PRESERVES);assertFalse(accepts(before,after,after));
        before=wine();after.putByte("Count",(byte)2);assertFalse(accepts(before,after,after));
    }
    @Test void qualityChangeCannotBeHiddenByNativePrediction() {
        CompoundTag before=initialized(7,0,1200),after=initialized(7,1,1800);
        before.getCompound("tag").putInt("quality",1);after.getCompound("tag").putInt("quality",2);
        assertFalse(accepts(before,after,after));
    }
    @Test void capabilitiesAndCustomTagChangesRemainExact() {
        CompoundTag before=initialized(7,0,1200),after=initialized(7,1,1800);
        before.putString("ForgeCaps","before");after.putString("ForgeCaps","after");assertFalse(accepts(before,after,after));
        before=initialized(7,0,1200);after=initialized(7,1,1800);
        after.getCompound("tag").putString("custom","unexpected");assertFalse(accepts(before,after,after));
    }
    @Test void preservedCustomTagsAndCapabilitiesAreAllowed() {
        CompoundTag before=wine(),after=initialized(9,0,1200),tag=new CompoundTag();
        tag.putInt("quality",2);before.put("tag",tag);after.getCompound("tag").putInt("quality",2);
        before.putString("ForgeCaps","same");after.putString("ForgeCaps","same");assertTrue(accepts(before,after,after));
    }
    @Test void malformedYearAndCacheTypesFailClosed() {
        CompoundTag before=initialized(7,0,1200),after=initialized(7,1,1800);
        before.getCompound("tag").putString("Year","7");assertFalse(accepts(before,after,after));
        before=initialized(7,0,1200);after.getCompound("tag").putLong("EffectDuration",1800L);assertFalse(accepts(before,after,after));
        before=wine();before.put("tag",StringTag.valueOf("not-a-compound"));assertFalse(accepts(before,initialized(9,0,1200),initialized(9,0,1200)));
    }
    @Test void negativeYearAndMissingCacheAreRejected() {
        assertFalse(accepts(initialized(-1,0,1200),initialized(-1,1,1800),initialized(-1,1,1800)));
        CompoundTag after=initialized(9,0,1200);after.getCompound("tag").remove("EffectDuration");assertFalse(accepts(wine(),after,after));
    }
    @Test void UnknownOrChangedNativeCalendarCannotAuthorizeInitialization() {
        CompoundTag before=wine(),after=initialized(9,0,1200);
        assertFalse(NativeWineMetadata.exactPassiveChange(before,after,after,null,9));
        assertFalse(NativeWineMetadata.exactPassiveChange(before,after,after,9,null));
        assertFalse(NativeWineMetadata.exactPassiveChange(before,after,after,8,9));
    }
    @Test void failedPredictionDoesNotMutateOriginalTags() {
        CompoundTag before=initialized(7,0,1200),after=initialized(7,1,1800),oldCopy=before.copy(),newCopy=after.copy();
        assertFalse(accepts(before,after,initialized(7,2,1800)));assertEquals(oldCopy,before);assertEquals(newCopy,after);
    }
}
