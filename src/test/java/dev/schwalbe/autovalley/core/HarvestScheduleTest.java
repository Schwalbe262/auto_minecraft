package dev.schwalbe.autovalley.core;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HarvestScheduleTest {
    private static final Farm ANCIENT=new Farm("Ancient",new Pos(1,72,1),new Pos(5,72,5),CropRules.ANCIENT_FRUIT);
    private static final Farm TOMATO=new Farm("Tomato",new Pos(10,72,1),new Pos(15,72,5),CropRules.TOMATO);
    private static Context context(Profile p,long day,Runnable checkpoint) {
        WorldAccess world=(WorldAccess)Proxy.newProxyInstance(WorldAccess.class.getClassLoader(),new Class<?>[]{WorldAccess.class},
            (proxy,method,args)->{if(method.getName().equals("dayTime"))return day*24000L+5000;throw new AssertionError("No maturity/world inspection: "+method.getName());});
        return new Context(world,null,null,p,new SessionState(),checkpoint);
    }
    @Test void fixedCycleUsesConfiguredCropIntervalWithoutReadingMaturity() {
        Profile p=new Profile();p.harvestCycleDays=2;
        assertEquals(623L,HarvestSchedule.nextCycle(p,ANCIENT,613));assertEquals(615L,HarvestSchedule.nextCycle(p,TOMATO,613));
        CropDefinition crop=CropRules.definition(p,CropRules.ANCIENT_FRUIT);
        p.crops.put(crop.key(),new CropDefinition(crop.key(),crop.itemId(),crop.blockIds(),crop.matureStates(),17));
        assertEquals(630L,HarvestSchedule.nextCycle(p,ANCIENT,613));
    }
    @Test void reserveCheckpointsOnceWithoutMovingAnExistingLaterDateEarlier() {
        Profile p=new Profile();AtomicInteger saves=new AtomicInteger();Context c=context(p,613,saves::incrementAndGet);
        HarvestSchedule.reserveNextCycle(c,ANCIENT);assertEquals(623L,p.nextEligibleDay.get("harvest:Ancient"));
        HarvestSchedule.reserveNextCycle(c,ANCIENT);assertEquals(1,saves.get());
        p.nextEligibleDay.put("harvest:Ancient",630L);HarvestSchedule.reserveNextCycle(c,ANCIENT);
        assertEquals(630L,p.nextEligibleDay.get("harvest:Ancient"));assertEquals(1,saves.get());
    }
    @Test void legacyForecastGetsOneConservativeTransitionAndOtherSchedulesRemainUnchanged() {
        Profile p=new Profile();p.strictHarvestTimingVersion=0;p.farms.addAll(List.of(ANCIENT,TOMATO));
        p.nextEligibleDay.putAll(Map.of("harvest:Ancient",615L,"harvest:Tomato",614L,"wine-line:ancient",618L,"logging",613L));
        AtomicInteger saves=new AtomicInteger();HarvestSchedule.transition(context(p,613,saves::incrementAndGet));
        assertEquals(Map.of("harvest:Ancient",623L,"harvest:Tomato",614L,"wine-line:ancient",618L,"logging",613L),p.nextEligibleDay);
        assertEquals(1,p.strictHarvestTimingVersion);assertEquals(1,saves.get());
        HarvestSchedule.transition(context(p,614,saves::incrementAndGet));assertEquals(623L,p.nextEligibleDay.get("harvest:Ancient"));assertEquals(1,saves.get());
    }
    @Test void legacyTransitionNeverShortensLaterDatesOrSchedulesBrandNewFields() {
        Profile p=new Profile();p.strictHarvestTimingVersion=0;p.farms.add(ANCIENT);p.nextEligibleDay.put("harvest:Ancient",640L);
        HarvestSchedule.transition(context(p,613,()->{}));assertEquals(640L,p.nextEligibleDay.get("harvest:Ancient"));
        p.strictHarvestTimingVersion=0;p.nextEligibleDay.clear();HarvestSchedule.transition(context(p,613,()->{}));
        assertTrue(p.nextEligibleDay.isEmpty());assertEquals(1,p.strictHarvestTimingVersion);
    }
    @Test void failedTransitionCheckpointRollsBackMarkerAndAllDates() {
        Profile p=new Profile();p.strictHarvestTimingVersion=0;p.farms.add(ANCIENT);p.nextEligibleDay.put("harvest:Ancient",615L);
        Map<String,Long> before=Map.copyOf(p.nextEligibleDay);
        assertThrows(IllegalStateException.class,()->HarvestSchedule.transition(context(p,613,()->{throw new IllegalStateException("storage refused");})));
        assertEquals(before,p.nextEligibleDay);assertEquals(0,p.strictHarvestTimingVersion);
    }
    @Test void explicitCustomIntervalsDoNotInheritTheOldTenStageForecastTransition() {
        Profile p=new Profile();p.strictHarvestTimingVersion=0;p.farms.add(ANCIENT);p.nextEligibleDay.put("harvest:Ancient",619L);
        CropDefinition crop=CropRules.definition(p,CropRules.ANCIENT_FRUIT);
        p.crops.put(crop.key(),new CropDefinition(crop.key(),crop.itemId(),crop.blockIds(),crop.matureStates(),20));
        HarvestSchedule.transition(context(p,613,()->{}));assertEquals(619L,p.nextEligibleDay.get("harvest:Ancient"));
    }
    @Test void cycleOverflowSaturatesInsteadOfMakingTheFieldImmediatelyDue() {
        assertEquals(Long.MAX_VALUE,HarvestSchedule.nextCycle(new Profile(),ANCIENT,Long.MAX_VALUE-1));
    }
    @Test void customAncientGrowthDefinitionsDoNotReceiveTheBuiltinTransition() {
        Profile p=new Profile();p.strictHarvestTimingVersion=0;p.farms.add(ANCIENT);p.nextEligibleDay.put("harvest:Ancient",619L);
        CropDefinition crop=CropRules.definition(p,CropRules.ANCIENT_FRUIT);
        p.crops.put(crop.key(),new CropDefinition(crop.key(),crop.itemId(),crop.blockIds(),Map.of(crop.itemId(),Map.of("age","8")),10));
        HarvestSchedule.transition(context(p,613,()->{}));assertEquals(619L,p.nextEligibleDay.get("harvest:Ancient"));
    }
}
