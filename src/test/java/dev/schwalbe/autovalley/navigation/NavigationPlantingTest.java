package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavigationPlantingTest {
    private static final Pos START=new Pos(0,0,0),STANCE=new Pos(1,0,0);
    private static final LoggingPlot PLOT=new LoggingPlot("spruce",new Pos(2,1,1));
    private static final List<Pos> CELLS=PLOT.plantingPositions();

    @Test void allFourGoalsMustBeVisibleEvenWhenGenericSoilInteractionIsAlreadyPossible() {
        Fixture f=new Fixture(); f.predicted.addAll(CELLS.subList(0,3));
        assertTrue(f.find().isEmpty());
        assertEquals(List.of(START),new LocalPathfinder().find(START,CELLS.get(0).offset(0,-1,0),3.25,f,f.profile));
        f.predicted.add(CELLS.get(3)); assertEquals(List.of(START,STANCE),f.find());
        assertTrue(f.reaches.stream().allMatch(r -> r==3.25));
    }

    @Test void threeActualFacesCannotFinishTheMoveButFourCanWithoutAnInteraction() {
        Fixture f=new Fixture(); f.predicted.addAll(CELLS); f.actual.addAll(CELLS.subList(0,3));
        assertEquals(Navigation.Result.MOVING,f.step()); assertNotNull(f.movement);
        assertFalse(f.movement.jump()); assertEquals(0,f.submissions);
        f.actual.add(CELLS.get(3)); f.now++;
        assertEquals(Navigation.Result.ARRIVED,f.step()); assertNull(f.movement);
        assertEquals(0,f.submissions);
    }

    @Test void switchingFromGenericArrivalCannotReuseItsWeakerSoilProof() {
        Fixture f=new Fixture(); f.predicted.addAll(CELLS);
        assertEquals(Navigation.Result.ARRIVED,f.navigation.moveToLogging(CELLS.get(0).offset(0,-1,0),3.25,f.context));
        assertEquals(Navigation.Result.MOVING,f.step()); assertNotNull(f.movement);
    }

    @Test void endpointSettlingRechecksEveryActualFaceInsteadOfTheGenericHit() {
        Fixture f=new Fixture(); f.x=1.5; f.predicted.addAll(CELLS); f.actual.addAll(CELLS.subList(0,3));
        assertEquals(Navigation.Result.MOVING,f.step()); assertNull(f.movement);
        f.now++; assertEquals(Navigation.Result.MOVING,f.step());
        f.now++; assertEquals(Navigation.Result.MOVING,f.step());
        f.now++; assertEquals(Navigation.Result.BLOCKED,f.step());
        assertEquals(0,f.submissions);
    }

    @Test void geometryPreflightWhileOffDoesNotAuthorizeActualPlantingMovement() {
        Fixture f=new Fixture(); f.predicted.addAll(CELLS); f.profile.loggingRunActive=false;
        assertEquals(List.of(START,STANCE),f.find());
        assertEquals(Navigation.Result.BLOCKED,f.step()); assertNull(f.movement);
        f.profile.loggingRunActive=true; f.session.oneShotFeature=null; f.profile.enabled.put(Feature.LOGGING,false);
        assertEquals(Navigation.Result.BLOCKED,f.step()); assertEquals(0,f.submissions);
    }

    @Test void missingReplantObligationAndForeignOrRepeatedTargetsAreRejected() {
        Fixture f=new Fixture(); f.predicted.addAll(CELLS); f.actual.addAll(CELLS);
        for(List<Pos> targets:List.of(List.<Pos>of(),List.of(CELLS.get(0),CELLS.get(0)),List.of(new Pos(99,1,1)),
                List.of(CELLS.get(0),new Pos(99,1,1)))) {
            assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToLoggingPlanting(targets,f.context));
            assertTrue(new LocalPathfinder().findLoggingPlanting(START,targets,3.25,f,f.profile,Set.of()).isEmpty());
        }
        assertEquals(Navigation.Result.BLOCKED,f.navigation.moveToLoggingPlanting(null,f.context));
        f.profile.loggingReplantingPlots.clear(); assertEquals(Navigation.Result.BLOCKED,f.step());
        assertNull(f.movement); assertEquals(0,f.submissions);
    }

    @Test void alreadyPlantedCellsAreNotRequiredAgainButEveryRemainingCellStillIs() {
        Fixture f=new Fixture(); List<Pos> missing=CELLS.subList(2,4); f.actual.addAll(missing);
        assertEquals(Navigation.Result.ARRIVED,f.navigation.moveToLoggingPlanting(missing,f.context));
        f.actual.remove(missing.get(1)); f.predicted.addAll(missing); f.now++;
        assertEquals(Navigation.Result.MOVING,f.navigation.moveToLoggingPlanting(missing,f.context));
        assertEquals(0,f.submissions);
    }

    @Test void snowCoveredTargetsStillRequireAllFourNativePlacementProofsWithoutClearingTerrain() {
        Fixture f=new Fixture();
        for(Pos cell:CELLS) f.blocks.put(cell,new BlockData(cell,"minecraft:snow",Map.of("layers","1")));
        f.predicted.addAll(CELLS); f.actual.addAll(CELLS.subList(0,3));
        assertEquals(List.of(START,STANCE),f.find());
        assertEquals(Navigation.Result.MOVING,f.step());
        f.actual.add(CELLS.get(3)); f.now++;
        assertEquals(Navigation.Result.ARRIVED,f.step());
        assertEquals(0,f.submissions,"Navigation only proves the planting stance; it must not dig or plant");
        assertTrue(CELLS.stream().allMatch(cell->f.block(cell).id().equals("minecraft:snow")));
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final LocalNavigator navigation=new LocalNavigator(); final Profile profile=new Profile();
        final SessionState session=new SessionState(); final Context context;
        final Set<Pos> actual=new HashSet<>(),predicted=new HashSet<>(); final List<Double> reaches=new ArrayList<>();
        final Map<Pos,BlockData> blocks=new HashMap<>();
        long now; double x=.5; Movement movement; int submissions;
        Fixture() {
            profile.navigationMode=NavigationMode.WAYPOINTS;
            profile.farms.add(new Farm("walkway",START,new Pos(3,0,0)));
            profile.loggingPlots.add(PLOT); profile.loggingRunActive=true;
            profile.loggingRemainingPlots.add(PLOT.corner()); profile.loggingReplantingPlots.add(PLOT.corner());
            session.oneShotFeature=Feature.LOGGING; context=new Context(this,this,navigation,profile,session);
        }
        Navigation.Result step() { return navigation.moveToLoggingPlanting(CELLS,context); }
        List<Pos> find() { return new LocalPathfinder().findLoggingPlanting(START,CELLS,3.25,this,profile,Set.of()); }
        public long tick(){return now;} public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,0,.5,0,0,true,false,20,20,0,true,true);}
        public boolean loaded(Pos p){return true;}
        public boolean canStand(Pos p){return p.y()==0 && p.z()==0 && p.x()>=0 && p.x()<=3;}
        public double standingY(Pos p){return canStand(p)?0:Double.NaN;}
        public boolean canTraverse(Pos a,Pos b){return canStand(a)&&canStand(b)&&Math.abs(a.x()-b.x())==1;}
        public boolean canInteract(Pos p,double reach){return true;}
        public boolean canInteractFrom(Pos feet,Pos target,double reach){return true;}
        public boolean canPlantLoggingSapling(Pos target,double reach){reaches.add(reach);return actual.contains(target);}
        public boolean canPlantLoggingSaplingFrom(Pos feet,Pos target,double reach){reaches.add(reach);return feet.equals(STANCE)&&predicted.contains(target);}
        public BlockData block(Pos p){return blocks.getOrDefault(p,new BlockData(p,"minecraft:air",Map.of()));}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();} public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return new MenuData(0,0,List.of(),ItemData.EMPTY,false);}
        public boolean mayPlace(int slot,ItemData item){return false;}
        public long submit(Action action){submissions++;throw new AssertionError("Planting navigation cannot plant");}
        public ActionOutcome outcome(long ticket){throw new AssertionError();} public boolean busy(){return false;}
        public void move(Movement m){movement=m;} public void stopMovement(){movement=null;} public void cancel(){stopMovement();}
    }
}
