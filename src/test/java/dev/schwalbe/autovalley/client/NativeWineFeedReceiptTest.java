package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached server-receipt projections: no Minecraft bootstrap or predicted inventory is evidence. */
class NativeWineFeedReceiptTest {
    private static final Pos TARGET=new Pos(1,64,0),OTHER=TARGET.offset(1,0,0);
    private static final String KEG="society:wine_keg";
    private static final Map<String,String> EXTRA=Map.of("facing","north","upgraded","false");
    private static BlockData before(boolean mature,boolean working) {
        Map<String,String> state=new LinkedHashMap<>(EXTRA);state.put("mature",Boolean.toString(mature));state.put("working",Boolean.toString(working));
        return new BlockData(TARGET,KEG,state);
    }
    private static NativeWineFeedReceipt.StateProof state(long seq) {
        return new NativeWineFeedReceipt.StateProof(seq,TARGET,KEG,"false","true",EXTRA,true);
    }
    private static NativeWineFeedReceipt.SelectedProof selected(int consumed) {
        return new NativeWineFeedReceipt.SelectedProof(103,2,true,true,ItemData.TOMATO,12,ItemData.TOMATO,12-consumed,true);
    }
    private static int count(BlockData before,List<NativeWineFeedReceipt.StateProof> states,NativeWineFeedReceipt.SelectedProof selected) {
        return NativeWineFeedReceipt.confirmedCount(TARGET,before,2,100,7,7,states,selected);
    }
    private static int count(NativeWineFeedReceipt.SelectedProof selected) {return count(before(false,false),List.of(state(102)),selected);}

    @Test void previouslyPartialIdleStagesRequireTheExactOneOrTwoSelectedTomatoLoss() {
        // Native internal stage is not invented as a BlockData property: stage1/2
        // manifests as a raw selected-slot loss2/1 and the exact working transition.
        assertEquals(2,count(selected(2)));assertEquals(1,count(selected(1)));
        assertEquals(3,count(selected(3)),"ordinary idle full feed may be observed without widening partial outcome authority");
    }
    @Test void aPreviouslyMatureCollectionNeverQualifiesForTheIdlePartialException() {
        for(boolean working:List.of(false,true)) {
            for(int consumed:new int[]{1,2})assertEquals(0,count(before(true,working),List.of(state(102)),selected(consumed)));
            assertEquals(3,count(before(true,working),List.of(state(102)),selected(3)));
        }
        assertEquals(0,count(before(false,true),List.of(state(102)),selected(2)));
    }
    @Test void matureFullFeedRequiresExactSelectedNativeLossOrExactThreeItemExhaustion() {
        for(boolean working:List.of(false,true)) {
            BlockData mature=before(true,working);
            assertEquals(3,count(mature,List.of(state(102)),new NativeWineFeedReceipt.SelectedProof(103,2,true,true,
                ItemData.TOMATO,64,ItemData.TOMATO,61,true)));
            assertEquals(3,count(mature,List.of(state(102)),new NativeWineFeedReceipt.SelectedProof(103,2,true,true,
                ItemData.TOMATO,3,"minecraft:air",0,false)));
            for(int used:new int[]{-1,0,1,2,4})assertEquals(0,count(mature,List.of(state(102)),selected(used)));
            assertEquals(0,count(mature,List.of(state(102)),new NativeWineFeedReceipt.SelectedProof(103,2,true,true,
                ItemData.TOMATO,12,ItemData.TOMATO,9,false)),"public count is not native quality/tag/limit proof");
            assertEquals(0,count(mature,List.of(state(102)),new NativeWineFeedReceipt.SelectedProof(103,2,true,true,
                ItemData.TOMATO,3,ItemData.WINE,1,false)));
        }
    }
    @Test void matureFullFeedCannotBorrowStaleWrongSlotOrLatestContradictoryBlockEvidence() {
        BlockData mature=before(true,true);
        assertEquals(0,count(mature,List.of(),selected(3)));
        assertEquals(0,count(mature,List.of(state(102)),null));
        assertEquals(0,NativeWineFeedReceipt.confirmedCount(TARGET,mature,2,100,7,8,List.of(state(102)),selected(3)));
        for(var slot:List.of(
            new NativeWineFeedReceipt.SelectedProof(100,2,true,true,ItemData.TOMATO,12,ItemData.TOMATO,9,true),
            new NativeWineFeedReceipt.SelectedProof(103,3,true,true,ItemData.TOMATO,12,ItemData.TOMATO,9,true),
            new NativeWineFeedReceipt.SelectedProof(103,2,false,true,ItemData.TOMATO,12,ItemData.TOMATO,9,true),
            new NativeWineFeedReceipt.SelectedProof(103,2,true,false,ItemData.TOMATO,12,ItemData.TOMATO,9,true)))
            assertEquals(0,count(mature,List.of(state(102)),slot));
        for(var latest:List.of(
            new NativeWineFeedReceipt.StateProof(104,TARGET,"minecraft:air","false","true",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","false",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"true","true",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,null,"true",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","true",EXTRA,false),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","true",Map.of("facing","south","upgraded","false"),true)))
            assertEquals(0,count(mature,List.of(state(102),latest),selected(3)));
    }
    @Test void unknownMatureBaselineWorkingStateCannotAuthorizeFullFeeding() {
        for(String working:List.of("unknown","TRUE","")) {
            Map<String,String> properties=new LinkedHashMap<>(before(true,true).properties());properties.put("working",working);
            assertEquals(0,count(new BlockData(TARGET,KEG,properties),List.of(state(102)),selected(3)));
        }
        Map<String,String> properties=new LinkedHashMap<>(before(true,true).properties());properties.remove("working");
        assertEquals(0,count(new BlockData(TARGET,KEG,properties),List.of(state(102)),selected(3)));
    }
    @Test void unchangedIncreasedAndOverconsumedSelectedCountsAreNotAFeedReceipt() {
        for(int consumed:new int[]{0,-1,4,5,12})assertEquals(0,count(selected(consumed)),"consumed="+consumed);
        assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,true,ItemData.TOMATO,12,"minecraft:air",0,false)));
        assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,true,ItemData.TOMATO,12,ItemData.TOMATO,-1,true)));
    }
    @Test void exactExhaustionRequiresTheOriginalFullThreePreparedTomatoes() {
        assertEquals(3,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,true,ItemData.TOMATO,3,"minecraft:air",0,false)));
        for(int before:new int[]{0,1,2})assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,true,
            ItemData.TOMATO,before,"minecraft:air",0,false)));
    }
    @Test void wrongQualityRawTagsOrItemCannotBeHiddenByAnExactPublicCountDifference() {
        assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,true,ItemData.TOMATO,12,ItemData.TOMATO,10,false)),
            "sameNativeIdentity includes native quality/tags and stack limit, not just the public item ID");
        assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,true,ItemData.TOMATO,12,"minecraft:apple",10,true)));
        assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,true,"minecraft:apple",12,ItemData.TOMATO,10,true)));
    }
    @Test void onlyTheExactSelectedHotbarSlotWithPostDispatchRawEvidenceAndEmptyCursorCanConfirm() {
        for(int slot:new int[]{-1,1,3,9,36})assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,slot,true,true,
            ItemData.TOMATO,12,ItemData.TOMATO,10,true)));
        for(long seq:new long[]{99,100})assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(seq,2,true,true,
            ItemData.TOMATO,12,ItemData.TOMATO,10,true)));
        assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,false,true,ItemData.TOMATO,12,ItemData.TOMATO,10,true)));
        assertEquals(0,count(new NativeWineFeedReceipt.SelectedProof(103,2,true,false,ItemData.TOMATO,12,ItemData.TOMATO,10,true)));
        for(int selected:new int[]{-1,9,36})assertEquals(0,NativeWineFeedReceipt.confirmedCount(TARGET,before(false,false),selected,100,7,7,List.of(state(102)),selected(2)));
    }
    @Test void anotherGenerationTargetOrStaleBlockStateCannotConfirmTheSelectedLoss() {
        assertEquals(0,NativeWineFeedReceipt.confirmedCount(TARGET,before(false,false),2,100,7,8,List.of(state(102)),selected(2)));
        assertEquals(0,NativeWineFeedReceipt.confirmedCount(OTHER,before(false,false),2,100,7,7,List.of(state(102)),selected(2)));
        for(long seq:new long[]{99,100})assertEquals(0,count(before(false,false),List.of(state(seq)),selected(2)));
        assertEquals(0,count(before(false,false),List.of(new NativeWineFeedReceipt.StateProof(102,OTHER,KEG,"false","true",EXTRA,true)),selected(2)));
        assertEquals(0,count(before(false,false),List.of(new NativeWineFeedReceipt.StateProof(102,TARGET,KEG,"false","true",EXTRA,false)),selected(2)));
    }
    @Test void latestTargetStateMustRemainThisWorkingKegWithUnchangedNativeProperties() {
        List<NativeWineFeedReceipt.StateProof> invalid=List.of(
            new NativeWineFeedReceipt.StateProof(104,TARGET,"minecraft:air","false","true",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","false",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"true","false",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","unknown",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","true",EXTRA,false),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,null,"true",EXTRA,true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","true",Map.of("facing","south","upgraded","false"),true),
            new NativeWineFeedReceipt.StateProof(104,TARGET,KEG,"false","true",Map.of("facing","north","upgraded","true"),true));
        for(var latest:invalid)assertEquals(0,count(before(false,false),List.of(state(102),latest),selected(2)));
        assertEquals(2,count(before(false,false),List.of(state(104),state(102)),selected(2)),"packet sequence, not list iteration, determines latest state");
    }
    @Test void unknownBaselineAndMissingEvidenceCannotBorrowUnrelatedInventoryChanges() {
        for(BlockData invalid:List.of(new BlockData(OTHER,KEG,before(false,false).properties()),
            new BlockData(TARGET,"minecraft:air",before(false,false).properties()),new BlockData(TARGET,KEG,EXTRA),
            new BlockData(TARGET,KEG,Map.of("mature","false","working","FALSE","facing","north","upgraded","false"))))
            assertEquals(0,count(invalid,List.of(state(102)),selected(2)));
        assertEquals(0,count(before(false,false),List.of(),selected(2)));
        assertEquals(0,count(before(false,false),null,selected(2)));
        assertEquals(0,count(before(false,false),List.of(state(102)),null),"an unrelated inventory delta supplies no selected-slot receipt");
        assertEquals(0,count(null,List.of(state(102)),selected(2)));
    }
    @Test void onlyAnExplicitSuccessfulOutcomeMayCarryPartialWineAuthority() {
        for(ActionOutcome.State state:ActionOutcome.State.values())if(state!=ActionOutcome.State.SUCCEEDED)
            assertThrows(IllegalArgumentException.class,()->new ActionOutcome(state,"unconfirmed",2,ActionOutcome.Proof.WINE_PARTIAL_FEED));
        assertEquals(ActionOutcome.Proof.NONE,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"ordinary count",2).proof());
        assertEquals(ActionOutcome.Proof.WINE_PARTIAL_FEED,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"exact native slot",2,
            ActionOutcome.Proof.WINE_PARTIAL_FEED).proof());
    }
    @Test void fullWineProofIsSuccessfulAndExactlyThreeButCannotBeInferredFromOrdinaryCount() {
        for(ActionOutcome.State state:ActionOutcome.State.values())if(state!=ActionOutcome.State.SUCCEEDED)
            assertThrows(IllegalArgumentException.class,()->new ActionOutcome(state,"unconfirmed",3,ActionOutcome.Proof.WINE_FULL_FEED));
        for(int count:List.of(0,1,2,4))assertThrows(IllegalArgumentException.class,()->new ActionOutcome(
            ActionOutcome.State.SUCCEEDED,"wrong count",count,ActionOutcome.Proof.WINE_FULL_FEED));
        assertEquals(ActionOutcome.Proof.NONE,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"ordinary count",3).proof());
        assertEquals(3,new ActionOutcome(ActionOutcome.State.SUCCEEDED,"exact full selected loss",3,
            ActionOutcome.Proof.WINE_FULL_FEED).confirmedCount());
    }
}
