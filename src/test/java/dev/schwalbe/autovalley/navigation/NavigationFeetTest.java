package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

final class NavigationFeetTest {
    @Test void farmlandBoundaryUsesTheSameVerifiedCellForPlanningAndMovement() {
        Fixture world=new Fixture(5.25,63.9375,.25);
        Pos raw=new Pos(5,63,0),standing=raw.offset(0,1,0);
        world.standable.add(standing);
        Profile profile=corridor();
        assertFalse(ProfileBounds.contains(profile,raw),"raw Y contributes the extra square outside radius 5");
        assertEquals(standing,NavigationFeet.resolve(world,world.player()));
        assertTrue(ProfileBounds.contains(profile,NavigationFeet.resolve(world,world.player())),
            "the same exact boundary cell accepted by the planner must reach the movement gate");
        assertEquals(5,profile.corridorRadius,"no corridor widening");
    }

    @Test void ordinaryFloorKeepsRawCellWithoutInspectingAbove() {
        Fixture world=new Fixture(5.25,64,.25);
        Pos raw=world.player().feet();world.standable.add(raw);
        assertEquals(raw,NavigationFeet.resolve(world,world.player()));
        assertEquals(List.of(raw),world.checked);
        assertTrue(ProfileBounds.contains(corridor(),raw));
    }

    @Test void alreadyStandablePartialHeightRawCellIsNeverRaisedToGainPermission() {
        Fixture world=new Fixture(5.25,63.5,.25);
        Pos raw=world.player().feet();world.standable.add(raw);world.standable.add(raw.offset(0,1,0));
        assertEquals(raw,NavigationFeet.resolve(world,world.player()));
        assertFalse(ProfileBounds.contains(corridor(),NavigationFeet.resolve(world,world.player())));
        assertEquals(List.of(raw,raw),world.checked,"verified raw support takes precedence even though above would enter the corridor");
    }

    @Test void bottomSlabInsideItsBlockUsesOnlyVerifiedAboveSupport() {
        Fixture world=new Fixture(2.25,63.5,.25);
        Pos raw=world.player().feet();world.standable.add(raw.offset(0,1,0));
        assertEquals(raw.offset(0,1,0),NavigationFeet.resolve(world,world.player()));
        assertEquals(List.of(raw,raw.offset(0,1,0)),world.checked);
    }

    @Test void UnavailableRawAndAboveNeverInventSupportOrSearchOtherHeights() {
        Fixture world=new Fixture(5.25,63.9375,.25);
        Pos raw=world.player().feet();world.standable.add(raw.offset(0,2,0));
        assertEquals(raw,NavigationFeet.resolve(world,world.player()));
        assertEquals(List.of(raw,raw.offset(0,1,0)),world.checked);
        assertFalse(ProfileBounds.contains(corridor(),raw));
    }

    @Test void TrulyOutsidePositionStillFailsAfterValidFarmlandNormalization() {
        Fixture world=new Fixture(6.25,63.9375,.25);
        Pos raw=world.player().feet();world.standable.add(raw.offset(0,1,0));
        Pos normalized=NavigationFeet.resolve(world,world.player());
        assertEquals(new Pos(6,64,0),normalized);
        assertFalse(ProfileBounds.contains(corridor(),normalized));
        assertEquals(6,normalized.x());assertEquals(0,normalized.z());
    }

    private static Profile corridor() {
        Profile profile=new Profile();profile.corridorRadius=5;
        profile.pois.add(new Poi(new Pos(0,64,0),PoiKind.WAYPOINT,"Verified path",null));return profile;
    }
    private static final class Fixture implements WorldAccess {
        final PlayerState player;final Set<Pos> standable=new HashSet<>();final List<Pos> checked=new ArrayList<>();
        Fixture(double x,double y,double z){player=new PlayerState(x,y,z,0,0,true,false,20,20,0,true,true);}
        @Override public PlayerState player(){return player;}
        @Override public boolean canStand(Pos pos){checked.add(pos);return standable.contains(pos);}
        @Override public boolean loaded(Pos pos){return standable.contains(pos);}
        @Override public long tick(){return 0;}
        @Override public long dayTime(){return 0;}
        @Override public BlockData block(Pos pos){throw new AssertionError("Feet resolution must not scan block data");}
        @Override public boolean canTraverse(Pos from,Pos to){throw new AssertionError("Feet resolution must not move or plan a route");}
        @Override public List<BlockData> scan(Pos center,int horizontal,int vertical){throw new AssertionError("No scan required");}
        @Override public List<ItemSlot> inventory(){return List.of();}
        @Override public MenuData menu(){return null;}
        @Override public boolean mayPlace(int slot,ItemData item){return false;}
    }
}
