package dev.schwalbe.autovalley.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FarmRegistrationRulesTest {
    private static final Farm ANCIENT=new Farm("ancient",new Pos(10,70,10),new Pos(19,70,19),CropRules.ANCIENT_FRUIT);
    private static final Farm TOMATO=new Farm("tomato",new Pos(30,70,30),new Pos(39,70,39),CropRules.TOMATO);
    private static Profile profile() {
        Profile p=new Profile();p.farms.addAll(List.of(ANCIENT,TOMATO));
        p.nextEligibleDay.putAll(Map.of("harvest:ancient",115L,"harvest:tomato",108L,
            "wine-line:ancient",110L,"wine",111L,"preserves",112L,"logging",113L,"crystal",114L));
        return p;
    }
    private static Farm changed(Pos a,Pos b,String crop) {return new Farm(ANCIENT.name(),a,b,crop);}

    @Test void changingEitherCornerOrCropClearsOnlyThatFieldsHarvestEligibility() {
        for(Farm update:List.of(changed(ANCIENT.first().offset(-1,0,0),ANCIENT.second(),ANCIENT.cropId()),
            changed(ANCIENT.first(),ANCIENT.second().offset(0,0,5),ANCIENT.cropId()),
            changed(ANCIENT.first(),ANCIENT.second().offset(0,1,0),ANCIENT.cropId()),
            changed(ANCIENT.first(),ANCIENT.second(),CropRules.TOMATO))) {
            Profile p=profile();Map<String,Long> expected=new HashMap<>(p.nextEligibleDay);expected.remove("harvest:ancient");
            FarmRegistrationRules.apply(p,ANCIENT,update);
            assertEquals(List.of(update,TOMATO),p.farms);assertEquals(expected,p.nextEligibleDay);
        }
    }

    @Test void unchangedDraftAndReversedCornersPreserveTheFullSchedule() {
        for(Farm update:List.of(new Farm(ANCIENT.name(),ANCIENT.first(),ANCIENT.second(),ANCIENT.cropId()),
            changed(ANCIENT.second(),ANCIENT.first(),ANCIENT.cropId()),
            changed(new Pos(19,70,10),new Pos(10,70,19),ANCIENT.cropId()))) {
            Profile p=profile();Map<String,Long> before=new HashMap<>(p.nextEligibleDay);
            FarmRegistrationRules.apply(p,ANCIENT,update);
            assertEquals(before,p.nextEligibleDay);
        }
    }

    @Test void renameAloneMovesTheSameCooldownWithoutLeavingAnOldNameOrBorrowingAnOrphanDate() {
        Profile p=profile();p.nextEligibleDay.put("harvest:renamed",999L);
        Map<String,Long> expected=new HashMap<>(p.nextEligibleDay);expected.remove("harvest:ancient");expected.put("harvest:renamed",115L);
        Farm renamed=new Farm("renamed",ANCIENT.first(),ANCIENT.second(),ANCIENT.cropId());
        FarmRegistrationRules.apply(p,ANCIENT,renamed);
        assertEquals(expected,p.nextEligibleDay);assertEquals(List.of(renamed,TOMATO),p.farms);
    }

    @Test void renameAndGeometryChangeClearBothOldAndNewNamesButNoOtherTask() {
        Profile p=profile();p.nextEligibleDay.put("harvest:expanded",999L);
        Map<String,Long> expected=new HashMap<>(p.nextEligibleDay);expected.remove("harvest:ancient");expected.remove("harvest:expanded");
        Farm expanded=new Farm("expanded",ANCIENT.first(),ANCIENT.second().offset(1,0,0),ANCIENT.cropId());
        FarmRegistrationRules.apply(p,ANCIENT,expanded);
        assertEquals(expected,p.nextEligibleDay);
    }

    @Test void newRegistrationDoesNotInheritAnOldDeletedFieldsSameNameCooldown() {
        Profile p=profile();p.nextEligibleDay.put("harvest:new",888L);
        Map<String,Long> expected=new HashMap<>(p.nextEligibleDay);expected.remove("harvest:new");
        Farm added=new Farm("new",new Pos(100,70,100),new Pos(110,70,110),CropRules.ANCIENT_FRUIT);
        FarmRegistrationRules.apply(p,null,added);
        assertEquals(List.of(ANCIENT,TOMATO,added),p.farms);assertEquals(expected,p.nextEligibleDay);
    }

    @Test void renamingAnUnscheduledFieldDoesNotCreateEligibilityFromAStaleNewName() {
        Profile p=profile();p.nextEligibleDay.remove("harvest:ancient");p.nextEligibleDay.put("harvest:renamed",999L);
        FarmRegistrationRules.apply(p,ANCIENT,new Farm("renamed",ANCIENT.first(),ANCIENT.second(),ANCIENT.cropId()));
        assertFalse(p.nextEligibleDay.containsKey("harvest:ancient"));assertFalse(p.nextEligibleDay.containsKey("harvest:renamed"));
    }

    @Test void saveFailureRestoresTheExactFarmOrderAndEveryCalendarEntryIncludingNullValues() {
        for(Farm update:List.of(changed(ANCIENT.first(),ANCIENT.second().offset(1,0,0),ANCIENT.cropId()),
            new Farm("renamed",ANCIENT.first(),ANCIENT.second(),ANCIENT.cropId()),
            new Farm("renamed",ANCIENT.first(),ANCIENT.second().offset(1,0,0),ANCIENT.cropId()))) {
            Profile p=profile();p.nextEligibleDay.put("harvest:renamed",999L);p.nextEligibleDay.put("legacy:null",null);
            List<Farm> beforeFarms=new ArrayList<>(p.farms);Map<String,Long> beforeDays=new HashMap<>(p.nextEligibleDay);
            Runnable rollback=FarmRegistrationRules.apply(p,ANCIENT,update);
            assertNotEquals(beforeFarms,p.farms);rollback.run();
            assertEquals(beforeFarms,p.farms);assertEquals(beforeDays,p.nextEligibleDay);
        }
    }

    @Test void newRegistrationRollbackRestoresAnyOrphanedCalendarEntryAndListOrder() {
        Profile p=profile();p.nextEligibleDay.put("harvest:new",900L);
        List<Farm> beforeFarms=new ArrayList<>(p.farms);Map<String,Long> beforeDays=new HashMap<>(p.nextEligibleDay);
        Runnable rollback=FarmRegistrationRules.apply(p,null,new Farm("new",new Pos(100,70,100),new Pos(110,70,110)));
        rollback.run();assertEquals(beforeFarms,p.farms);assertEquals(beforeDays,p.nextEligibleDay);
    }

    @Test void staleEditAndDuplicateNamesCannotMutateAnotherFieldsSchedule() {
        Profile p=profile();List<Farm> beforeFarms=new ArrayList<>(p.farms);Map<String,Long> beforeDays=new HashMap<>(p.nextEligibleDay);
        assertThrows(IllegalArgumentException.class,()->FarmRegistrationRules.apply(p,ANCIENT,
            new Farm(TOMATO.name(),ANCIENT.first(),ANCIENT.second(),ANCIENT.cropId())));
        assertThrows(IllegalArgumentException.class,()->FarmRegistrationRules.apply(p,null,ANCIENT));
        Farm missing=new Farm("gone",ANCIENT.first(),ANCIENT.second());
        assertThrows(IllegalArgumentException.class,()->FarmRegistrationRules.apply(p,missing,new Farm("new",missing.first(),missing.second())));
        assertEquals(beforeFarms,p.farms);assertEquals(beforeDays,p.nextEligibleDay);
    }

    @Test void screenSavesTheFarmAndItsCalendarThroughTheSameRollbackBoundary() throws Exception {
        String screen=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/ui/ValleyScreen.java"));
        String save=screen.substring(screen.indexOf("private void saveFarm()"),screen.indexOf("private String nextFarmName()"));
        assertTrue(save.contains("Runnable rollback=FarmRegistrationRules.apply(runtime.profile(),replaced,candidate);"));
        assertTrue(save.contains("if (persist(rollback))"));
        assertTrue(save.indexOf("RegistrationRules.overlap")<save.indexOf("FarmRegistrationRules.apply"));
    }
}
