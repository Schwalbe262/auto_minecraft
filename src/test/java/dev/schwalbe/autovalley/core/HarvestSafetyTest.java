package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HarvestSafetyTest {
    private static final Pos TARGET=new Pos(0,64,0);
    private static final ItemData HOE=new ItemData("minecraft:diamond_hoe",1,0,null,true,100);

    @Test void unknownAndMissingNativeModelsNeverAuthorizeAWorldClick() {
        Fixture f=new Fixture();
        for (HarvestFootprint footprint:Arrays.asList(HarvestFootprint.UNKNOWN,null)) {
            f.footprint=footprint;
            assertNotNull(f.rejection()); assertNotNull(f.policy());
        }
    }

    @Test void knownSingleRegisteredTomatoIsAllowedWithoutScanningOrMoving() {
        Fixture f=new Fixture();
        assertNull(f.rejection()); assertNull(f.policy());
        assertEquals(Set.of(TARGET),new HashSet<>(f.reads));
    }

    @Test void everyPotentialTomatoMustBelongToARegisteredFarm() {
        Fixture f=new Fixture(); Pos neighbor=TARGET.offset(1,0,0);
        f.tomato(neighbor); f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
        assertNotNull(f.rejection());
        f.farm(neighbor,neighbor);
        assertNull(f.rejection());
    }

    @Test void anotherCropIsRejectedEvenInsideTheRegisteredTomatoBounds() {
        Fixture f=new Fixture(); Pos neighbor=TARGET.offset(1,0,0);
        f.farm(neighbor,neighbor); f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
        for (String id:List.of("minecraft:wheat","minecraft:carrots","minecraft:potatoes")) {
            f.blocks.put(neighbor,new BlockData(neighbor,id,Map.of("age","7")));
            assertNotNull(f.rejection(),id+" must not inherit tomato permission merely from the farm box");
        }
    }

    @Test void aFutureDatedNeighbouringFieldCannotBeHarvestedThroughTheCurrentFieldsAreaEffect() {
        Fixture f=new Fixture(); Pos neighbor=TARGET.offset(1,0,0); f.tomato(neighbor); f.farm(neighbor,neighbor);
        Farm adjacent=f.profile.farms.get(1); String key=CropRules.farmKey(adjacent);
        f.profile.nextEligibleDay.put(key,2L); f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
        String rejection=f.rejection(); assertNotNull(rejection); assertTrue(rejection.contains("수확 주기"));
        assertTrue(rejection.contains(adjacent.name())); assertNotNull(f.policy());
        assertEquals(Map.of(key,2L),f.profile.nextEligibleDay,"The guard cannot advance or erase any field's date");
    }

    @Test void theAlreadyAdmittedFieldMayFinishAfterItsFirstAcknowledgementReservedTheNextCycle() {
        Fixture f=new Fixture();Pos neighbor=TARGET.offset(1,0,0);f.tomato(neighbor);
        f.profile.farms.set(0,new Farm("current",TARGET,neighbor));
        f.profile.nextEligibleDay.put("harvest:current",10L);
        f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
        assertNull(f.rejection());assertNull(f.policy());
        assertEquals(Map.of("harvest:current",10L),f.profile.nextEligibleDay);
    }

    @Test void dueOrUnscheduledAdjacentFieldsKeepTheirExistingRegisteredCropPermission() {
        Fixture f=new Fixture();Pos neighbor=TARGET.offset(1,0,0);f.tomato(neighbor);f.farm(neighbor,neighbor);
        String key=CropRules.farmKey(f.profile.farms.get(1));
        f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
        assertNull(f.rejection());f.profile.nextEligibleDay.put(key,0L);assertNull(f.rejection());
        f.day=24000L+1000;assertNull(f.rejection());
        f.profile.nextEligibleDay.put(key,1L);assertNull(f.rejection());
        f.profile.nextEligibleDay.put(key,2L);assertNotNull(f.rejection());
    }

    @Test void theSameCycleGuardAppliesToAncientFruitAndToNativeUpperCellFallback() {
        Fixture f=new Fixture();Pos upper=TARGET.offset(0,1,0);String crop=CropRules.ANCIENT_FRUIT;
        f.profile.farms.set(0,new Farm("lower",TARGET,TARGET,crop));
        f.profile.farms.add(new Farm("upper",upper,upper,crop));
        for(Pos position:List.of(TARGET,upper))f.blocks.put(position,new BlockData(position,CropRules.ANCIENT_FRUIT_ITEM,Map.of("age","10")));
        f.footprint=new HarvestFootprint(true,0,List.of(TARGET,upper));
        f.profile.nextEligibleDay.put("harvest:lower",10L);assertNull(f.rejection());
        f.profile.nextEligibleDay.put("harvest:upper",10L);assertNotNull(f.rejection());assertNotNull(f.policy());
    }

    @Test void futureFieldsOutsideThePotentialFootprintDoNotBlockAnAdmittedHarvest() {
        Fixture f=new Fixture();Pos neighbor=TARGET.offset(1,0,0);f.tomato(neighbor);f.farm(neighbor,neighbor);
        f.profile.nextEligibleDay.put(CropRules.farmKey(f.profile.farms.get(1)),2L);
        assertNull(f.rejection());assertNull(f.policy());assertFalse(f.reads.contains(neighbor));
    }

    @Test void upperBlockFallbackCannotEscapeTheRegisteredVerticalBounds() {
        Fixture f=new Fixture(); Pos upper=TARGET.offset(0,1,0);
        f.blocks.put(upper,new BlockData(upper,"farmersdelight:tomatoes_on_rope",Map.of("age","3")));
        f.footprint=new HarvestFootprint(true,0,List.of(TARGET,upper));
        assertNotNull(f.rejection());
        f.farm(upper,upper);
        assertNull(f.rejection());
    }

    @Test void conservativeSafetyRadiusChecksPotentialCropsOutsideTheActualRadiusHint() {
        Fixture f=new Fixture(); Pos conservativeMember=TARGET.offset(4,0,0);
        f.tomato(conservativeMember);
        f.footprint=new HarvestFootprint(true,1,4,List.of(TARGET,conservativeMember));
        assertNotNull(f.rejection(),"radius=1 must not hide a potentially affected crop in safetyRadius=4");
        f.farm(conservativeMember,conservativeMember);
        assertNull(f.rejection());
    }

    @Test void nonPotentialAirAndStoneOutsideTheFarmDoNotRequireRegistration() {
        Fixture f=new Fixture(); Pos air=TARGET.offset(1,0,0), stone=TARGET.offset(4,1,0);
        f.blocks.put(stone,new BlockData(stone,"minecraft:stone",Map.of()));
        f.footprint=new HarvestFootprint(true,1,4,List.of(TARGET));
        assertNull(f.rejection());
        assertFalse(f.reads.contains(air)); assertFalse(f.reads.contains(stone));
    }

    @Test void anIncorrectlyIncludedInertBlockCannotBeTreatedAsAPotentialTomato() {
        Fixture f=new Fixture(); Pos neighbor=TARGET.offset(1,0,0);
        f.farm(neighbor,neighbor); f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
        assertNotNull(f.rejection()); // Default AIR is not a crop, even inside a farm.
        f.blocks.put(neighbor,new BlockData(neighbor,"minecraft:stone",Map.of()));
        assertNotNull(f.rejection());
    }

    @Test void unloadedTargetOrPotentialMemberIsRejectedWithoutReadingItsBlock() {
        for (Pos unavailable:List.of(TARGET,TARGET.offset(1,0,0))) {
            Fixture f=new Fixture(); Pos neighbor=TARGET.offset(1,0,0);
            f.tomato(neighbor); f.farm(neighbor,neighbor);
            f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
            f.unloaded.add(unavailable);
            assertNotNull(f.rejection()); assertFalse(f.reads.contains(unavailable));
        }
    }

    @Test void malformedRadiiFailClosed() {
        Fixture f=new Fixture();
        for (int[] radii:List.of(new int[]{-1,0},new int[]{-1,-1},new int[]{2,1},new int[]{1,17},new int[]{17,17})) {
            f.footprint=new HarvestFootprint(true,radii[0],radii[1],List.of(TARGET));
            assertNotNull(f.rejection(),Arrays.toString(radii));
        }
    }

    @Test void emptyDuplicateMissingTargetAndOversizedListsAreInvalid() {
        Fixture f=new Fixture();
        for (List<Pos> members:List.of(List.<Pos>of(),List.of(TARGET,TARGET),List.of(TARGET.offset(1,0,0)))) {
            f.footprint=new HarvestFootprint(true,1,members);
            assertNotNull(f.rejection());
        }
        List<Pos> excessive=new ArrayList<>();
        for (int index=0;index<2179;index++) excessive.add(TARGET.offset(index,0,0));
        f.footprint=new HarvestFootprint(true,16,excessive);
        assertNotNull(f.rejection()); assertTrue(f.reads.isEmpty());
    }

    @Test void potentialMembersCannotEscapeTheHorizontalOrTwoLevelVerticalFootprint() {
        for (Pos outside:List.of(TARGET.offset(5,0,0),TARGET.offset(-5,0,0),TARGET.offset(0,0,5),
                TARGET.offset(0,0,-5),TARGET.offset(0,-1,0),TARGET.offset(0,2,0),new Pos(Integer.MIN_VALUE,64,0))) {
            Fixture f=new Fixture(); f.tomato(outside); f.farm(outside,outside);
            f.footprint=new HarvestFootprint(true,1,4,List.of(TARGET,outside));
            assertNotNull(f.rejection(),outside.toString());
        }
    }

    @Test void theUnionOfMoreThanTwoRegisteredFarmsCanCoverOneNativeFootprint() {
        Fixture f=new Fixture(); Pos second=TARGET.offset(2,0,0), third=TARGET.offset(-2,1,2);
        f.tomato(second); f.tomato(third); f.farm(second,second); f.farm(third,third);
        f.footprint=new HarvestFootprint(true,4,List.of(TARGET,second,third));
        assertEquals(3,f.profile.farms.size()); assertNull(f.rejection());
        f.profile.farms.remove(2);
        assertNotNull(f.rejection());
    }

    @Test void oversizedFarmBoundsCannotGrantPermissionToAPotentialMember() {
        Fixture f=new Fixture(); Pos neighbor=TARGET.offset(1,0,0);
        f.tomato(neighbor); f.farm(new Pos(-100,0,-100),new Pos(100,100,100));
        f.footprint=new HarvestFootprint(true,1,List.of(TARGET,neighbor));
        assertNotNull(f.rejection());
    }

    @Test void footprintOwnsItsListAndRejectsNullMembersAtConstruction() {
        List<Pos> source=new ArrayList<>(List.of(TARGET));
        HarvestFootprint footprint=new HarvestFootprint(true,0,source);
        source.clear(); assertEquals(List.of(TARGET),footprint.potentialTargets());
        assertThrows(UnsupportedOperationException.class,() -> footprint.potentialTargets().clear());
        assertThrows(NullPointerException.class,() -> new HarvestFootprint(true,0,Arrays.asList(TARGET,null)));
    }

    @Test void policyRequiresTheConfiguredSelectedHoeRatherThanAnyHoeInInventory() {
        Fixture f=new Fixture(); assertNull(f.policy());
        f.selected=1;
        assertNotNull(f.policy());
        f.items.set(1,new ItemSlot(1,1,true,HOE));
        assertNotNull(f.policy(),"a usable hoe in the wrong selected slot is still not the configured hoe");
        f.profile.hoeHotbarSlot=1;
        assertNull(f.policy());
        f.items.set(1,new ItemSlot(1,1,true,new ItemData("minecraft:diamond_shovel",1,0,null,false,100)));
        assertNotNull(f.policy());
    }

    @Test void policyPreservesOnePointOfHoeDurability() {
        Fixture f=new Fixture();
        for (int durability:List.of(0,1)) {
            f.items.set(0,new ItemSlot(0,0,true,new ItemData(HOE.id(),1,0,null,true,durability)));
            assertNotNull(f.policy());
        }
        f.items.set(0,new ItemSlot(0,0,true,new ItemData(HOE.id(),1,0,null,true,2)));
        assertNull(f.policy());
    }

    @Test void policyStillRequiresMaturityAndTheNarrowHarvestPermission() {
        Fixture f=new Fixture();
        f.blocks.put(TARGET,new BlockData(TARGET,"farmersdelight:tomatoes",Map.of("age","2")));
        assertNotNull(f.policy());
        f.blocks.put(TARGET,new BlockData(TARGET,"farmersdelight:budding_tomatoes",Map.of("age","3")));
        assertNotNull(f.policy());
        f.tomato(TARGET); f.profile.enabled.put(Feature.HARVEST,false);
        assertNotNull(f.policy());
        f.session.oneShotFeature=Feature.HARVEST;
        assertNull(f.policy());
        f.session.oneShotFeature=Feature.WINE;
        assertNotNull(f.policy());
    }

    private static final class Fixture implements WorldAccess {
        final Profile profile=new Profile();
        final SessionState session=new SessionState();
        final Map<Pos,BlockData> blocks=new HashMap<>();
        final Set<Pos> unloaded=new HashSet<>();
        final List<Pos> reads=new ArrayList<>();
        final List<ItemSlot> items=new ArrayList<>();
        HarvestFootprint footprint=HarvestFootprint.single(TARGET);
        int selected; long day=1000;
        Fixture() {
            tomato(TARGET); farm(TARGET,TARGET);
            items.add(new ItemSlot(0,0,true,HOE)); items.add(new ItemSlot(1,1,true,ItemData.EMPTY));
        }
        void tomato(Pos pos) { blocks.put(pos,new BlockData(pos,"farmersdelight:tomatoes",Map.of("age","3"))); }
        void farm(Pos first,Pos second) { profile.farms.add(new Farm("Field "+profile.farms.size(),first,second)); }
        Context context() { return new Context(this,null,null,profile,session); }
        String rejection() { return HarvestSafety.rejection(context(),TARGET); }
        String policy() { return SafetyPolicy.rejection(new Action.UseBlock(TARGET,Action.Use.HARVEST),context()); }
        @Override public long tick() { return 10; }
        @Override public long dayTime() { return day; }
        @Override public PlayerState player() { return new PlayerState(.5,64,.5,0,0,true,false,20,20,selected,true,true); }
        @Override public BlockData block(Pos pos) { reads.add(pos); return blocks.getOrDefault(pos,new BlockData(pos,"minecraft:air",Map.of())); }
        @Override public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        @Override public HarvestFootprint harvestFootprint(Pos target) { return footprint; }
        @Override public boolean canStand(Pos pos) { throw new AssertionError("Safety must not navigate"); }
        @Override public boolean canTraverse(Pos from,Pos to) { throw new AssertionError("Safety must not navigate"); }
        @Override public List<BlockData> scan(Pos center,int radius,int vertical) { throw new AssertionError("Safety must use only the native footprint"); }
        @Override public List<ItemSlot> inventory() { return items; }
        @Override public MenuData menu() { return new MenuData(0,0,items,ItemData.EMPTY,false); }
        @Override public boolean mayPlace(int slot,ItemData item) { throw new AssertionError("Harvest safety must not inspect inventory transfers"); }
    }
}
