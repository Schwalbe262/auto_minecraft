package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AncientHarvestTimingTest {
    private static final Pos ORIGIN=new Pos(716,72,1587);
    private static final String ANCIENT=CropRules.ANCIENT_FRUIT_ITEM;

    @Test void supportsOnlyTheExactTenDayBuiltinAncientFruitDefinition() {
        Fixture f=new Fixture(1); assertTrue(AncientHarvestTiming.supports(f.profile,f.farm));
        assertFalse(AncientHarvestTiming.supports(null,f.farm));
        assertFalse(AncientHarvestTiming.supports(f.profile,null));
        assertFalse(AncientHarvestTiming.supports(f.profile,new Farm("tomatoes",ORIGIN,ORIGIN)));
        List<CropDefinition> alternatives=List.of(
            new CropDefinition(CropRules.ANCIENT_FRUIT,ANCIENT,Set.of(ANCIENT),Map.of(ANCIENT,Map.of("age","10")),9),
            new CropDefinition(CropRules.ANCIENT_FRUIT,ANCIENT,Set.of(ANCIENT),Map.of(ANCIENT,Map.of("age","10")),11),
            new CropDefinition(CropRules.ANCIENT_FRUIT,"other:fruit",Set.of(ANCIENT),Map.of(ANCIENT,Map.of("age","10")),10),
            new CropDefinition(CropRules.ANCIENT_FRUIT,ANCIENT,Set.of(ANCIENT,"other:crop"),Map.of(ANCIENT,Map.of("age","10")),10),
            new CropDefinition(CropRules.ANCIENT_FRUIT,ANCIENT,Set.of(ANCIENT),Map.of(ANCIENT,Map.of("age","9")),10),
            new CropDefinition(CropRules.ANCIENT_FRUIT,ANCIENT,Set.of(ANCIENT),Map.of(ANCIENT,Map.of("age","10","ripe","true")),10),
            new CropDefinition(CropRules.ANCIENT_FRUIT,ANCIENT,Set.of(),Map.of(),10));
        for(CropDefinition crop:alternatives) {
            f.profile.crops.put(CropRules.ANCIENT_FRUIT,crop);
            assertFalse(AncientHarvestTiming.supports(f.profile,f.farm),crop.toString());
        }
    }

    @Test void everyObservedGrowthStageKeepsItsOwnRemainingDays() {
        Fixture f=new Fixture(1);
        for(int age=0;age<=10;age++)for(boolean harvested:new boolean[]{false,true}) {
            f.crop(ORIGIN,Integer.toString(age));
            assertEquals(603+10-age,f.next(603,harvested),"age "+age+", harvested "+harvested);
        }
    }

    @Test void mixedNewlyHarvestedAndOlderPlantsScheduleTheEarliestActualMaturity() {
        Fixture f=new Fixture(4);
        f.crop(ORIGIN,"0");f.crop(ORIGIN.offset(1,0,0),"2");f.crop(ORIGIN.offset(2,0,0),"9");f.crop(ORIGIN.offset(3,0,0),"7");
        assertEquals(604,f.next(603,true));
        f.crop(ORIGIN.offset(2,0,0),"0");assertEquals(606,f.next(603,true));
        f.crop(ORIGIN.offset(3,0,0),"2");assertEquals(611,f.next(603,true));
    }

    @Test void aRemainingMaturePlantMeansTodayEvenWithUnknownOrUnloadedNeighbours() {
        Fixture f=new Fixture(4);
        f.crop(ORIGIN,"0");f.crop(ORIGIN.offset(1,0,0),"unknown");f.unloaded.add(ORIGIN.offset(2,0,0));
        f.crop(ORIGIN.offset(3,0,0),"10");
        assertEquals(603,f.next(603,true));assertFalse(f.reads.contains(ORIGIN.offset(2,0,0)));
    }

    @Test void missingMalformedOrOutOfRangeAgeIsRecheckedByTomorrow() {
        Fixture f=new Fixture(2);f.crop(ORIGIN,"0");Pos second=ORIGIN.offset(1,0,0);
        for(Map<String,String> properties:List.of(Map.<String,String>of(),Map.of("age",""),Map.of("age","ripe"),
            Map.of("age","1.5"),Map.of("age","-1"),Map.of("age","11"),Map.of("age","2147483648"))) {
            f.blocks.put(second,new BlockData(second,ANCIENT,properties));assertEquals(604,f.next(603,true),properties.toString());
        }
        f.blocks.put(second,new BlockData(second,ANCIENT,null));assertEquals(604,f.next(603,true));
    }

    @Test void ordinaryEmptyCellsDoNotShortenAConfirmedImmatureCropButNoCropsMeansTomorrow() {
        Fixture f=new Fixture(4);f.crop(ORIGIN,"2");
        f.blocks.put(ORIGIN.offset(1,0,0),new BlockData(ORIGIN.offset(1,0,0),"minecraft:air",Map.of()));
        f.blocks.put(ORIGIN.offset(2,0,0),new BlockData(ORIGIN.offset(2,0,0),"minecraft:dirt",Map.of()));
        f.blocks.put(ORIGIN.offset(3,0,0),new BlockData(ORIGIN.offset(3,0,0),"society:sprinkler",Map.of()));
        assertEquals(611,f.next(603,true));f.blocks.remove(ORIGIN);assertEquals(604,f.next(603,true));
    }

    @Test void checksAllThreeInclusiveDimensionsWithoutReadingOutsideTheRegisteredBox() {
        Fixture f=new Fixture(1);Pos opposite=ORIGIN.offset(1,1,1);
        f.farm=new Farm("ancient",opposite,ORIGIN,CropRules.ANCIENT_FRUIT);f.crop(ORIGIN,"0");f.crop(opposite,"8");
        f.crop(ORIGIN.offset(-1,0,0),"10");assertEquals(605,f.next(603,true));
        assertEquals(8,f.reads.size());assertTrue(f.reads.stream().allMatch(f.farm::contains));
        assertEquals(new HashSet<>(f.reads),f.loadChecks);
    }

    @Test void unloadedUnknownMismatchedOrFailedSnapshotsCannotPostponeTheWholeFieldForTenDays() {
        for(String unavailable:List.of("unloaded","null","wrong position","unknown id","loaded exception","block exception")) {
            Fixture f=new Fixture(2);f.crop(ORIGIN,"0");Pos second=ORIGIN.offset(1,0,0);
            switch(unavailable) {
                case "unloaded" -> f.unloaded.add(second);
                case "null" -> f.blocks.put(second,null);
                case "wrong position" -> f.blocks.put(second,new BlockData(ORIGIN,ANCIENT,Map.of("age","10")));
                case "unknown id" -> f.blocks.put(second,new BlockData(second,"autovalley:unloaded",Map.of()));
                case "loaded exception" -> f.failedLoaded.add(second);
                case "block exception" -> f.failedReads.add(second);
            }
            assertEquals(604,f.next(603,true),unavailable);
            if(unavailable.equals("unloaded") || unavailable.equals("loaded exception"))assertFalse(f.reads.contains(second));
        }
    }

    @Test void customAncientIntervalsAndOtherCropsKeepLegacyRulesWithoutAnyWorldObservation() {
        Fixture f=new Fixture(1);f.crop(ORIGIN,"10");
        f.profile.crops.put(CropRules.ANCIENT_FRUIT,new CropDefinition(CropRules.ANCIENT_FRUIT,ANCIENT,Set.of(ANCIENT),Map.of(ANCIENT,Map.of("age","10")),7));
        assertEquals(610,f.next(603,true));assertEquals(604,f.next(603,false));
        f.profile.harvestCycleDays=2;f.farm=new Farm("tomatoes",ORIGIN,ORIGIN);
        assertEquals(605,f.next(603,true));assertEquals(604,f.next(603,false));assertTrue(f.loadChecks.isEmpty());
    }

    @Test void oversizeAndMalformedBoundsDoNotReadTerrainAndIntegerEdgesDoNotLoopForever() {
        Fixture f=new Fixture(1);f.farm=new Farm("ancient",ORIGIN,ORIGIN.offset(32768,0,0),CropRules.ANCIENT_FRUIT);
        assertEquals(604,f.next(603,true));assertTrue(f.loadChecks.isEmpty());
        f.farm=new Farm("ancient",null,ORIGIN,CropRules.ANCIENT_FRUIT);assertEquals(604,f.next(603,true));
        f.farm=new Farm("ancient",new Pos(Integer.MIN_VALUE,Integer.MIN_VALUE,Integer.MIN_VALUE),
            new Pos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE),CropRules.ANCIENT_FRUIT);
        assertEquals(604,f.next(603,true));assertTrue(f.loadChecks.isEmpty());
        Pos edge=new Pos(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE);
        f.farm=new Farm("ancient",edge,edge,CropRules.ANCIENT_FRUIT);f.crop(edge,"9");
        assertEquals(604,f.next(603,true));assertEquals(List.of(edge),f.reads);
    }

    @Test void dateArithmeticSaturatesInsteadOfWrappingIntoThePast() {
        Fixture f=new Fixture(1);f.crop(ORIGIN,"0");
        assertEquals(Long.MAX_VALUE,f.next(Long.MAX_VALUE-2,true));assertEquals(Long.MAX_VALUE,f.next(Long.MAX_VALUE,true));
        f.crop(ORIGIN,"10");assertEquals(Long.MAX_VALUE,f.next(Long.MAX_VALUE,true));
        f.crop(ORIGIN,"9");assertEquals(Long.MIN_VALUE+1,f.next(Long.MIN_VALUE,true));
        f.farm=new Farm("tomatoes",ORIGIN,ORIGIN);f.profile.harvestCycleDays=2;
        assertEquals(Long.MAX_VALUE,f.next(Long.MAX_VALUE-1,true));
    }

    @Test void readsNeverChangeDefinitionsSchedulesRegistrationsOrInventory() {
        Fixture f=new Fixture(2);f.crop(ORIGIN,"0");f.crop(ORIGIN.offset(1,0,0),"10");
        f.profile.farms.add(f.farm);f.profile.nextEligibleDay.put("harvest:ancient",611L);
        Map<String,CropDefinition> crops=Map.copyOf(f.profile.crops);List<Farm> farms=List.copyOf(f.profile.farms);
        assertEquals(603,f.next(603,true));assertEquals(crops,f.profile.crops);assertEquals(farms,f.profile.farms);
        assertEquals(Map.of("harvest:ancient",611L),f.profile.nextEligibleDay);
    }

    private static final class Fixture implements WorldAccess {
        final Profile profile=new Profile();Farm farm;
        final Map<Pos,BlockData> blocks=new HashMap<>();final Set<Pos> unloaded=new HashSet<>(),failedLoaded=new HashSet<>(),failedReads=new HashSet<>();
        final List<Pos> reads=new ArrayList<>();final Set<Pos> loadChecks=new HashSet<>();
        Fixture(int width){farm=new Farm("ancient",ORIGIN,ORIGIN.offset(width-1,0,0),CropRules.ANCIENT_FRUIT);}
        void crop(Pos pos,String age){blocks.put(pos,new BlockData(pos,ANCIENT,Map.of("age",age)));}
        long next(long day,boolean harvested){return AncientHarvestTiming.nextCheckDay(profile,farm,this,day,harvested);}
        public boolean loaded(Pos pos){loadChecks.add(pos);if(failedLoaded.contains(pos))throw new IllegalStateException("test loading");return !unloaded.contains(pos);}
        public BlockData block(Pos pos){assertTrue(loadChecks.contains(pos));assertFalse(unloaded.contains(pos));reads.add(pos);if(failedReads.contains(pos))throw new IllegalStateException("test block");return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of()));}
        public long tick(){throw new AssertionError("Timing cannot read movement or wall-clock time");}
        public long dayTime(){throw new AssertionError("Caller supplies the observation day");}
        public PlayerState player(){throw new AssertionError("Timing cannot navigate");}
        public boolean canStand(Pos p){throw new AssertionError("Timing cannot navigate");}
        public boolean canTraverse(Pos a,Pos b){throw new AssertionError("Timing cannot navigate");}
        public List<BlockData> scan(Pos p,int h,int v){throw new AssertionError("Timing is bounded by registered corners");}
        public List<ItemSlot> inventory(){throw new AssertionError("Timing cannot inspect inventory");}
        public MenuData menu(){throw new AssertionError("Timing cannot operate menus");}
        public boolean mayPlace(int i,ItemData item){throw new AssertionError("Timing cannot modify inventory");}
    }
}
