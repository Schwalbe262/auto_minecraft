package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.client.NativeArtisanReceipt.Confirmation.*;

/** Tests the predicates used by detached native receipts, without bootstrapping a game or registries. */
class NativeArtisanReceiptTest {
    private static final ArtisanRecipe SEED=ArtisanRecipe.ANCIENT_SEED,JADE=ArtisanRecipe.JADE_CRYSTAL;
    private static final Pos TARGET=new Pos(1,64,0),OTHER=new Pos(2,64,0);
    private static boolean feed(ArtisanRecipe recipe,int before,String afterId,int after,boolean sameTags,boolean collected) {
        return NativeArtisanReceipt.compatibleFeed(recipe,recipe.inputId(),before,afterId,after,sameTags,collected);
    }
    private static boolean feed(ArtisanRecipe recipe,int before,String afterId,int after,boolean sameTags,boolean collected,boolean upgraded) {
        return NativeArtisanReceipt.compatibleFeed(recipe,recipe.inputId(),before,afterId,after,sameTags,collected,upgraded);
    }
    private static NativeArtisanReceipt.SlotProof<String> slot(long seq,int menu,int slot,String value) {
        return new NativeArtisanReceipt.SlotProof<>(seq,menu,slot,false,true,value);
    }
    private static NativeArtisanReceipt.SlotProof<String> full(long seq,int menu,boolean cursorEmpty,String value) {
        return new NativeArtisanReceipt.SlotProof<>(seq,menu,-1,true,cursorEmpty,value);
    }
    private static String latest(List<NativeArtisanReceipt.SlotProof<String>> proofs) { return NativeArtisanReceipt.latestSelected(2,0,38,100,proofs); }
    private static final Map<String,String> EXTRA=Map.of("facing","north","upgraded","false");
    private static BlockData before(boolean mature) {
        Map<String,String> properties=new LinkedHashMap<>(EXTRA);properties.put("mature",Boolean.toString(mature));properties.put("working","false");
        return new BlockData(TARGET,SEED.machineId(),properties);
    }
    private static NativeArtisanReceipt.StateProof state(long seq,boolean mature,boolean working) {
        return new NativeArtisanReceipt.StateProof(seq,TARGET,SEED.machineId(),Boolean.toString(mature),Boolean.toString(working),EXTRA);
    }
    private static NativeArtisanReceipt.Confirmation classify(List<NativeArtisanReceipt.StateProof> proofs) {
        return NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,true,100,4,4,proofs);
    }

    @Test void retainedWorkingThenMatureProvesAdvancedCycleForBothIdleAndMatureOriginalMachines() {
        for(boolean originallyMature:List.of(false,true)) {
            assertEquals(CYCLE_ADVANCED,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(originallyMature),true,true,100,4,4,
                List.of(state(101,false,true),state(102,false,true),state(103,true,false),state(104,true,false))));
        }
        assertEquals(CYCLE_ADVANCED,classify(List.of(state(105,true,false),state(101,false,true),state(103,false,true))),"Sequence orders evidence, not list iteration");
    }
    @Test void absentEvictedOrPreDispatchWorkingCannotBeBorrowedForAMatureReceipt() {
        for(List<NativeArtisanReceipt.StateProof> proofs:List.of(
            List.of(state(101,true,false)),List.of(state(101,true,false),state(102,true,false)),
            List.of(state(100,false,true),state(101,true,false)),List.of(state(99,false,true),state(101,true,false))))
            assertEquals(NONE,classify(proofs));
    }
    @Test void initialMatureEchoAndAnyReturnToWorkingRejectOnlyTheAdvancedCyclePath() {
        assertEquals(NONE,classify(List.of(state(101,true,false),state(102,false,true),state(103,true,false))));
        assertEquals(NONE,classify(List.of(state(101,false,true),state(102,true,false),state(103,false,true),state(104,true,false))));
        assertEquals(CONFIRMED,classify(List.of(state(101,false,true),state(102,true,false),state(103,false,true))),
            "Latest valid working retains the old confirmation path, never CYCLE_ADVANCED");
    }
    @Test void everyPostDispatchTargetStateMustBelongToTheSameMonotoneCycle() {
        List<NativeArtisanReceipt.StateProof> invalid=List.of(
            state(102,false,false),state(102,true,true),
            new NativeArtisanReceipt.StateProof(102,TARGET,"minecraft:air","false","true",EXTRA),
            new NativeArtisanReceipt.StateProof(102,TARGET,JADE.machineId(),"false","true",EXTRA),
            new NativeArtisanReceipt.StateProof(102,TARGET,SEED.machineId(),null,"true",EXTRA),
            new NativeArtisanReceipt.StateProof(102,TARGET,SEED.machineId(),"false",null,EXTRA),
            new NativeArtisanReceipt.StateProof(102,TARGET,SEED.machineId(),"FALSE","true",EXTRA),
            new NativeArtisanReceipt.StateProof(102,TARGET,SEED.machineId(),"false","unknown",EXTRA));
        for(var bad:invalid) {
            assertEquals(NONE,classify(List.of(state(101,false,true),bad,state(103,true,false))),bad.toString());
            assertEquals(NONE,classify(List.of(bad,state(103,false,true),state(104,true,false))),"Do not discard a bad prefix");
            assertEquals(NONE,classify(List.of(state(101,false,true),state(103,true,false),new NativeArtisanReceipt.StateProof(104,bad.pos(),bad.id(),bad.mature(),bad.working(),bad.properties()))));
        }
    }
    @Test void extraNativePropertiesMustRemainEqualToTheOriginalStateThroughoutTheCycle() {
        List<Map<String,String>> invalid=Arrays.asList(null,Map.of(),Map.of("facing","south","upgraded","false"),
            Map.of("facing","north","upgraded","true"),Map.of("facing","north","upgraded","false","waterlogged","false"));
        for(Map<String,String> changed:invalid) {
            var working=new NativeArtisanReceipt.StateProof(101,TARGET,SEED.machineId(),"false","true",changed);
            var mature=new NativeArtisanReceipt.StateProof(102,TARGET,SEED.machineId(),"true","false",changed);
            assertEquals(NONE,classify(List.of(working,state(103,true,false))));
            assertEquals(NONE,classify(List.of(state(101,false,true),mature)));
            assertEquals(NONE,classify(List.of(state(101,false,true),mature,state(103,true,false))),"Reverting an extra property does not erase its change");
        }
        assertEquals(CONFIRMED,classify(List.of(new NativeArtisanReceipt.StateProof(101,TARGET,SEED.machineId(),"false","true",Map.of()))),
            "Do not tighten the existing latest-working confirmation contract here");
    }
    @Test void theCycleCannotUseAnotherTargetAnUnknownBaselineOrDifferentGeneration() {
        var good=List.of(state(101,false,true),state(103,true,false));
        for(BlockData bad:List.of(new BlockData(OTHER,SEED.machineId(),before(true).properties()),
            new BlockData(TARGET,JADE.machineId(),before(true).properties()),new BlockData(TARGET,SEED.machineId(),EXTRA),
            new BlockData(TARGET,SEED.machineId(),Map.of("mature","false","working","true"))))
            assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,bad,true,true,100,4,4,good));
        assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,true,100,4,5,good));
        assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,true,100,4,5,List.of(state(101,false,true))),
            "Generation mismatch also rejects the legacy working path");
        assertEquals(NONE,classify(List.of(new NativeArtisanReceipt.StateProof(101,OTHER,SEED.machineId(),"false","true",EXTRA),state(103,true,false))));
    }
    @Test void unrelatedTargetsAndOldReceiptsDoNotPoisonTheSameTargetCycle() {
        var unrelated=new NativeArtisanReceipt.StateProof(102,OTHER,"minecraft:air",null,null,null);
        assertEquals(CYCLE_ADVANCED,classify(List.of(state(99,true,true),state(101,false,true),unrelated,state(103,true,false))));
    }
    @Test void duplicateSequenceAndMissingStateEvidenceCannotClaimAdvancedProgress() {
        assertEquals(NONE,classify(List.of(state(101,false,true),state(101,false,true),state(103,true,false))));
        assertEquals(NONE,classify(List.of(state(101,false,true),state(101,true,false),state(103,true,false))));
        assertEquals(NONE,classify(Arrays.asList(state(101,false,true),null,state(103,true,false))));
        assertEquals(NONE,classify(List.of()));assertEquals(NONE,classify(null));
    }
    @Test void collectionOnlyNeverClaimsAnAdvancedCycleAndLegacyEmptyStateStillConfirmsCollection() {
        assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),false,true,100,4,4,
            List.of(state(101,false,true),state(103,true,false))));
        assertEquals(CONFIRMED,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),false,false,100,4,4,List.of(state(101,false,false))));
        assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(false),false,true,100,4,4,List.of(state(101,false,false))));
    }
    @Test void latestSelectedSlotCompatibilityAndCursorRemainMandatoryForCycleProof() {
        List<List<NativeArtisanReceipt.SlotProof<String>>> denied=List.of(
            List.of(slot(101,0,38,"consumed"),full(105,0,false,"consumed")),
            List.of(slot(101,0,38,"consumed"),full(105,0,true,null)),
            List.of(slot(101,0,38,"consumed"),slot(105,0,38,"unchanged")),
            List.of(slot(101,0,37,"consumed")),List.of(full(99,0,true,"consumed")));
        for(var slots:denied) {
            boolean compatible="consumed".equals(latest(slots));
            assertFalse(compatible);
            assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,compatible,100,4,4,List.of(state(102,false,true),state(104,true,false))));
        }
        assertEquals(CYCLE_ADVANCED,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,
            "consumed".equals(latest(List.of(full(105,0,true,"consumed")))),100,4,4,List.of(state(102,false,true),state(104,true,false))));
    }
    @Test void cycleProofDoesNotWidenTheExistingExactIngredientPredicate() {
        for(int after:List.of(12,14,16))assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,
            feed(SEED,16,SEED.inputId(),after,true,true),100,4,4,List.of(state(101,false,true),state(103,true,false))));
        assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,
            feed(SEED,16,SEED.inputId(),13,false,true),100,4,4,List.of(state(101,false,true),state(103,true,false))));
        assertEquals(CYCLE_ADVANCED,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,before(true),true,
            feed(SEED,16,SEED.inputId(),13,true,true),100,4,4,List.of(state(101,false,true),state(103,true,false))));
    }
    @Test void cycleProjectionIsImmutableAndFiveArgumentStateProofStaysCompatible() {
        Map<String,String> properties=new HashMap<>(EXTRA);
        var proof=new NativeArtisanReceipt.StateProof(101,TARGET,SEED.machineId(),"false","true",properties);
        properties.put("upgraded","true");assertEquals(EXTRA,proof.properties());
        assertThrows(UnsupportedOperationException.class,()->proof.properties().put("facing","south"));
        assertEquals(Map.of(),new NativeArtisanReceipt.StateProof(101,TARGET,SEED.machineId(),"false","true").properties());
    }
    @Test void projectionOnlyContainerFlagDoesNotReplaceOrHideANativePropertyChange() {
        Map<String,String> properties=new LinkedHashMap<>(before(true).properties());properties.put("container","true");
        BlockData projected=new BlockData(TARGET,SEED.machineId(),properties);
        assertEquals(CYCLE_ADVANCED,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,projected,true,true,100,4,4,
            List.of(state(101,false,true),state(102,true,false))));
        Map<String,String> changed=new LinkedHashMap<>(EXTRA);changed.put("facing","south");
        assertEquals(NONE,NativeArtisanReceipt.confirmation(SEED.machineId(),TARGET,projected,true,true,100,4,4,
            List.of(state(101,false,true),new NativeArtisanReceipt.StateProof(102,TARGET,SEED.machineId(),"true","false",changed))));
    }
    @Test void crystalCycleUsesItsOwnMachineAndUnchangedCoalescedFeedCompatibility() {
        BlockData crystalBefore=new BlockData(TARGET,JADE.machineId(),before(true).properties());
        var proofs=List.of(new NativeArtisanReceipt.StateProof(101,TARGET,JADE.machineId(),"false","true",EXTRA),
            new NativeArtisanReceipt.StateProof(103,TARGET,JADE.machineId(),"true","false",EXTRA));
        for(int after:List.of(6,7,8))assertEquals(CYCLE_ADVANCED,NativeArtisanReceipt.confirmation(JADE.machineId(),TARGET,crystalBefore,true,
            feed(JADE,7,JADE.inputId(),after,true,true),100,4,4,proofs));
        assertEquals(NONE,NativeArtisanReceipt.confirmation(JADE.machineId(),TARGET,crystalBefore,true,
            feed(JADE,7,JADE.inputId(),9,true,true),100,4,4,proofs));
        assertEquals(NONE,NativeArtisanReceipt.confirmation(JADE.machineId(),TARGET,crystalBefore,true,
            feed(JADE,7,JADE.inputId(),7,false,true),100,4,4,proofs));
    }
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
    @Test void partialSeedStagesCanConsumeOneTwoOrThreeButStillRequireAFullPreparedHand() {
        for(int consumed:List.of(1,2,3))for(int before:List.of(3,16))
            assertTrue(feed(SEED,before,SEED.inputId(),before-consumed,true,false));
        for(int before:List.of(1,2))
            assertFalse(feed(SEED,before,"minecraft:air",0,false,false));
        for(int after:List.of(0,12,16,17))assertFalse(feed(SEED,16,SEED.inputId(),after,true,false));
        for(int after:List.of(13,14,15))assertFalse(feed(SEED,16,SEED.inputId(),after,false,false));
        assertFalse(feed(SEED,16,"minecraft:apple",15,true,false));
    }
    @Test void previouslyMatureSeedHarvestResetsPartialStageAndStillRequiresExactlyThree() {
        assertTrue(feed(SEED,16,SEED.inputId(),13,true,true,true));
        assertFalse(feed(SEED,16,SEED.inputId(),14,true,true,true));
        assertFalse(feed(SEED,16,SEED.inputId(),15,true,true,true));
    }
    @Test void extraSeedReplacementRequiresBothPriorMaturityAndUpgrade() {
        assertTrue(feed(SEED,3,SEED.outputId(),2,false,true,true));
        assertFalse(feed(SEED,3,SEED.outputId(),2,false,true,false));
        assertFalse(feed(SEED,3,SEED.outputId(),2,false,false,true));
        assertFalse(feed(SEED,3,SEED.outputId(),3,false,true,true));
        assertFalse(feed(SEED,4,SEED.outputId(),2,false,true,true),"remaining input cannot be replaced");
        assertFalse(feed(SEED,3,"society:pristine_jade",1,false,true,true));
    }
    @Test void pristineJadeCanOnlyReplaceOneFullyConsumedInputDuringAnUpgradedMatureHarvest() {
        String bonus="society:pristine_jade";
        assertTrue(feed(JADE,1,bonus,1,false,true,true));
        assertFalse(feed(JADE,1,bonus,1,false,true,false));
        assertFalse(feed(JADE,1,bonus,1,false,false,true));
        assertFalse(feed(JADE,1,bonus,2,false,true,true));
        assertFalse(feed(JADE,2,bonus,1,false,true,true));
        assertFalse(feed(JADE,1,"society:pristine_ruby",1,false,true,true));
        assertFalse(feed(JADE,1,JADE.outputId(),3,false,true,true),"upgrade does not enlarge ordinary jade count");
    }
    @Test void aDifferentPartialRecipeServerRejectionCannotReleaseTheSameTargetFence() {
        var fence=new ArtisanAttemptFence<String>(8);int sends=0;
        if(!fence.blocked(TARGET,1,a->false) && fence.sent(TARGET,1,"foreign partial recipe"))sends++;
        var idle=new NativeArtisanReceipt.StateProof(101,TARGET,SEED.machineId(),"false","false");
        var latest=NativeArtisanReceipt.latestProof(List.of(idle),100,NativeArtisanReceipt.StateProof::seq,p->p.pos().equals(TARGET));
        int selectedAfter=16; // Actual selected-slot response was unchanged; client BE recipe/stage is not used.
        boolean confirmed=NativeArtisanReceipt.validState(SEED.machineId(),latest.id(),latest.mature(),latest.working(),true)
            && feed(SEED,16,SEED.inputId(),selectedAfter,true,false);
        assertFalse(confirmed);
        for(int i=0;i<600;i++)if(!fence.blocked(TARGET,1,a->confirmed) && fence.sent(TARGET,1,"retry"))sends++;
        assertEquals(1,sends);assertEquals(1,fence.size());
        assertFalse(fence.blocked(OTHER,1,a->confirmed));
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
    @Test void everyDynamicCrystalRecipePreservesExactOriginalIdentityAndTheMinusOneToPlusOneCollectedRange() {
        assertEquals(56,CrystalCollection.BASE_OUTPUT_IDS.size());
        for(String id:CrystalCollection.BASE_OUTPUT_IDS) {
            ArtisanRecipe recipe=CrystalRecipe.forInput(id);assertNotNull(recipe,id);
            assertEquals(id,recipe.inputId());assertEquals(id,recipe.outputId());assertEquals(1,recipe.inputCount());
            for(boolean upgraded:List.of(false,true)) {
                for(int after:List.of(6,7,8))assertTrue(feed(recipe,7,id,after,true,true,upgraded),id+" after="+after);
                for(int after:List.of(-1,0,5,9,64,Integer.MAX_VALUE))
                    assertFalse(feed(recipe,7,id,after,true,true,upgraded),id+" after="+after);
                for(int after:List.of(6,7,8))assertFalse(feed(recipe,7,id,after,false,true,upgraded),id+" changed tags");
                assertTrue(feed(recipe,7,id,6,true,false,upgraded),id+" idle feed consumes exactly one");
                for(int after:List.of(7,8))assertFalse(feed(recipe,7,id,after,true,false,upgraded),id+" idle cannot collect");
            }
        }
    }
    @Test void eachDynamicCrystalAdmitsOnlyItsOwnSinglePristineBonusAfterAnUpgradedMatureLastInput() {
        for(String id:CrystalCollection.BASE_OUTPUT_IDS) {
            ArtisanRecipe recipe=CrystalRecipe.forInput(id);String bonus=CrystalRecipe.bonusId(id);
            assertNotNull(bonus,id);assertEquals(bonus,recipe.separateBonusOutputId());
            assertTrue(feed(recipe,1,bonus,1,false,true,true),id);
            assertFalse(feed(recipe,1,bonus,1,false,true,false),id+" not upgraded");
            assertFalse(feed(recipe,1,bonus,1,false,false,true),id+" not mature");
            assertFalse(feed(recipe,1,bonus,2,false,true,true),id+" duplicate bonus");
            assertFalse(feed(recipe,2,bonus,1,false,true,true),id+" original remains");
            String otherBonus=CrystalRecipe.bonusId(id.equals("society:jade") ? "society:fire_quartz" : "society:jade");
            assertFalse(feed(recipe,1,otherBonus,1,false,true,true),id+" foreign bonus");
            assertTrue(feed(recipe,1,id,2,false,true,true),id+" ordinary output remains two");
            assertFalse(feed(recipe,1,id,3,false,true,true),id+" upgrade cannot invent a third ordinary output");
        }
    }
    @Test void jadeAndFireQuartzDynamicReceiptsCannotSubstituteTheirInputsOutputsOrBonusItems() {
        for(String id:List.of("society:jade","society:fire_quartz")) {
            ArtisanRecipe recipe=CrystalRecipe.forInput(id);
            String other=id.equals("society:jade") ? "society:fire_quartz" : "society:jade";
            for(int after:List.of(0,1,2,6,7,8)) {
                assertFalse(NativeArtisanReceipt.compatibleFeed(recipe,other,7,id,after,true,true,true),id+" wrong input");
                if(after>0)assertFalse(feed(recipe,7,other,after,true,true,true),id+" wrong surviving output");
            }
            assertFalse(NativeArtisanReceipt.compatibleFeed(recipe,other,1,"minecraft:air",0,false,true,true),id+" wrong last input");
            for(int after:List.of(1,2))assertFalse(feed(recipe,1,other,after,false,true,true),id+" wrong last-slot output");
            assertFalse(feed(recipe,1,CrystalRecipe.bonusId(other),1,false,true,true),id+" wrong bonus");
        }
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
