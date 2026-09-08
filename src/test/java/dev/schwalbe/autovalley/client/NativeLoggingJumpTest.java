package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeLoggingJumpTest {
    private static final Pos FROM=new Pos(0,72,0),TO=new Pos(1,73,0);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(FROM,TO);
    private static final AABB CUBE=new AABB(0,0,0,1,1,1);
    private static final NativeLoggingJump.Cell AIR=cell(true,true,false,true,List.of());
    private static NativeLoggingJump.Cell cell(boolean loaded,boolean bounded,boolean forbidden,boolean normal,List<AABB> boxes) {
        return new NativeLoggingJump.Cell(loaded,bounded,forbidden,normal,boxes);
    }
    private static NativeLoggingJump.Cell solid(AABB shape) { return cell(true,true,false,true,List.of(shape)); }
    private static Map<Pos,NativeLoggingJump.Cell> stairs() {
        Map<Pos,NativeLoggingJump.Cell> cells=new HashMap<>();
        cells.put(FROM.offset(0,-1,0),solid(CUBE)); cells.put(TO.offset(0,-1,0),solid(CUBE));
        return cells;
    }
    private static boolean inspect(Map<Pos,NativeLoggingJump.Cell> cells) {
        return NativeLoggingJump.geometry(EDGE,.6,1.8,72,73,p -> cells.getOrDefault(p,AIR));
    }
    @Test void fullSolidGrassTableAndPlankGeometryAllowsTheExpectedOneBlockRiser() {
        // All three recorded native supports expose this full collision cube.
        assertTrue(inspect(stairs()));
        assertTrue(NativeLoggingJump.normalSurface(1,1,.6000000238418579));
        assertTrue(NativeLoggingJump.sweep(EDGE,.6,1.8,72).intersects(CUBE.move(1,72,0)),
            "the expected riser must not be mistaken for an unrelated wall");
    }
    @Test void reverseDownwardAndDiagonalEdgesDoNotGainJumpPermission() {
        var cells=stairs();
        for (LoggingJumpEdge edge:List.of(new LoggingJumpEdge(TO,FROM),new LoggingJumpEdge(FROM,new Pos(1,73,1)),
                new LoggingJumpEdge(FROM,new Pos(2,73,0)),new LoggingJumpEdge(FROM,new Pos(1,72,0))))
            assertFalse(NativeLoggingJump.geometry(edge,.6,1.8,72,73,p -> cells.getOrDefault(p,AIR)));
    }
    @Test void fullHeadroomIncludesTheHighestAscendingPlayerBody() {
        var cells=stairs(); cells.put(FROM.offset(0,3,0),solid(CUBE)); assertFalse(inspect(cells));
        cells=stairs(); cells.put(TO.offset(0,2,0),solid(CUBE)); assertFalse(inspect(cells));
    }
    @Test void wallAboveRiserIsNotExcusedAsPartOfTheExpectedSupport() {
        var cells=stairs(); cells.put(TO,solid(CUBE)); assertFalse(inspect(cells));
        cells=stairs(); cells.put(FROM,solid(new AABB(.8,0,0,1,1,1))); assertFalse(inspect(cells));
    }
    @Test void partialStairsFenceAndUnevenFootprintsAreRejected() {
        for (AABB partial:List.of(new AABB(0,0,0,.5,1,1),new AABB(.25,0,.25,.75,1,.75))) {
            var cells=stairs(); cells.put(TO.offset(0,-1,0),solid(partial)); assertFalse(inspect(cells));
        }
        var cells=stairs(); cells.put(TO.offset(0,-1,0),cell(true,true,false,true,
            List.of(new AABB(0,0,0,1,.5,1),new AABB(.5,.5,0,1,1,1)))); assertFalse(inspect(cells));
    }
    @Test void actualSupportHeightsMustAgreeAndRiseExactlyOne() {
        var cells=stairs();
        assertFalse(NativeLoggingJump.geometry(EDGE,.6,1.8,71.5,72.5,p -> cells.getOrDefault(p,AIR)));
        assertFalse(NativeLoggingJump.geometry(EDGE,.6,1.8,72,72.5,p -> cells.getOrDefault(p,AIR)));
        cells.put(FROM.offset(0,-1,0),solid(new AABB(0,0,0,1,.5,1)));
        cells.put(TO.offset(0,-1,0),solid(new AABB(0,0,0,1,.5,1)));
        assertTrue(NativeLoggingJump.geometry(EDGE,.6,1.8,71.5,72.5,p -> cells.getOrDefault(p,AIR)));
    }
    @Test void fluidsDoorsCropsAndPlantingCellsAreForbiddenEvenWhenCollisionIsEmpty() {
        for (Pos pos:List.of(FROM,TO,FROM.offset(0,1,0),TO.offset(0,1,0),TO.offset(0,-1,0))) {
            var cells=stairs(); var previous=cells.getOrDefault(pos,AIR);
            cells.put(pos,cell(true,true,true,true,previous.boxes())); assertFalse(inspect(cells));
        }
    }
    @Test void fullSweepAndBothSupportCellsMustBeLoadedAndWithinRegisteredBounds() {
        for (Pos pos:List.of(FROM.offset(0,-1,0),TO.offset(0,-1,0),FROM.offset(0,3,0),TO.offset(0,2,0))) {
            var cells=stairs(); var previous=cells.getOrDefault(pos,AIR);
            cells.put(pos,cell(false,true,false,true,previous.boxes())); assertFalse(inspect(cells));
            cells.put(pos,cell(true,false,false,true,previous.boxes())); assertFalse(inspect(cells));
        }
    }
    @Test void alteredJumpSpeedOrSlipperyBounceSurfacesAreNotSupported() {
        assertFalse(NativeLoggingJump.normalSurface(.5,1,.6));
        assertFalse(NativeLoggingJump.normalSurface(1,.4,.6));
        assertFalse(NativeLoggingJump.normalSurface(1,1,.98));
        assertFalse(NativeLoggingJump.normalSurface(Double.NaN,1,.6));
        var cells=stairs(); cells.put(FROM.offset(0,-1,0),cell(true,true,false,false,List.of(CUBE)));
        assertFalse(inspect(cells));
    }
    @Test void registeredTomatoSoilAndSaplingSoilAreProtectedWithoutForbiddingWholeLoggingEnvelope() {
        Profile profile=new Profile(); profile.loggingPlots.add(new LoggingPlot("tree",new Pos(5,75,5)));
        assertTrue(NativeLoggingJump.protectedPlanting(profile,new Pos(5,74,5)));
        assertTrue(NativeLoggingJump.protectedPlanting(profile,new Pos(6,75,6)));
        assertFalse(NativeLoggingJump.protectedPlanting(profile,new Pos(4,75,5)));
        profile.farms.add(new Farm("tomatoes",new Pos(1,73,0),new Pos(2,73,2)));
        assertTrue(NativeLoggingJump.protectedPlanting(profile,TO));
        assertTrue(NativeLoggingJump.protectedPlanting(profile,TO.offset(0,-1,0)));
    }
    @Test void scaledCrawlingAndNonfiniteDimensionsFailClosed() {
        assertFalse(NativeLoggingJump.dimensions(.6,1.5)); assertFalse(NativeLoggingJump.dimensions(.9,1.8));
        assertFalse(NativeLoggingJump.dimensions(Double.NaN,1.8));
        assertTrue(NativeLoggingJump.dimensions(.6000000238418579,1.7999999523162842));
    }
    private static Movement jump() { return new Movement(0,0,false,false,true,false); }
    @Test void jumpInputRequiresExactPermittedObjectAndCanOnlyBeConsumedOnce() {
        var pulse=new NativeLoggingJump.InputPulse(); Movement movement=jump();
        pulse.issue(movement,10); assertFalse(pulse.consume(jump(),10));
        pulse.issue(movement,10); assertTrue(pulse.consume(movement,11)); assertFalse(pulse.consume(movement,11));
    }
    @Test void expiredCanceledAndOrdinaryMovementNeverBecomeJumpInput() {
        var pulse=new NativeLoggingJump.InputPulse(); Movement movement=jump();
        pulse.issue(movement,10); assertFalse(pulse.consume(movement,12));
        pulse.issue(movement,10); assertFalse(pulse.consume(movement,9));
        pulse.issue(movement,10); pulse.clear(); assertFalse(pulse.consume(movement,10));
        Movement walking=new Movement(0,0,true,false,false,false);
        pulse.issue(walking,10); assertFalse(pulse.consume(walking,10));
        pulse.issue(null,10); assertFalse(pulse.consume(null,10));
    }
    @Test void pulseRechecksCurrentAuthorityAndCannotSurviveFailedRecheck() {
        Movement movement=jump(); NativeLoggingJump.permitPulse(movement,10,() -> false);
        assertFalse(NativeLoggingJump.consumePulse(movement,11)); assertFalse(NativeLoggingJump.consumePulse(movement,11));
        NativeLoggingJump.permitPulse(movement,20,() -> true); NativeLoggingJump.clearPulse();
        assertFalse(NativeLoggingJump.consumePulse(movement,20));
    }
    @Test void launchIsVerticalAndHorizontalMotionWaitsForActualRiserClearance() {
        assertFalse(NativeLoggingJump.advanceAfterLift(true,73.2,73,1));
        assertFalse(NativeLoggingJump.advanceAfterLift(false,72.9,73,1));
        assertFalse(NativeLoggingJump.advanceAfterLift(false,73,73,1));
        assertFalse(NativeLoggingJump.advanceAfterLift(false,73.000005,73,1));
        assertTrue(NativeLoggingJump.advanceAfterLift(false,73.001336,73,1));
        assertFalse(NativeLoggingJump.advanceAfterLift(false,73.2,73,.1));
        assertFalse(NativeLoggingJump.advanceAfterLift(false,Double.NaN,73,1));
    }
    @Test void nativeJumpArithmeticReachesTheRiserBeforeYFirstDownwardCollision() {
        assertTrue(landsWithNativeArithmetic(false,0));
        assertTrue(landsWithNativeArithmetic(false,-.06),"the far edge of the permitted source alignment still overlaps the landing");
        assertFalse(landsWithNativeArithmetic(true,0),"a .01 clearance wastes the critical fourth tick of air steering");
    }
    private static boolean landsWithNativeArithmetic(boolean legacyClearance,double x) {
        // Pinned 1.20.1: jump .42f; air acceleration .02f; aiStep input .98f;
        // travel drag .91f/(vertical .98f), gravity .08. Entity.collideWithShapes
        // resolves Y against the CURRENT body before horizontal displacement.
        double y=0,vy=(double).42f,vx=0;
        for (int tick=1;tick<=9;tick++) {
            boolean forward=legacyClearance ? y>=1.01 : NativeLoggingJump.advanceAfterLift(tick==1,y,1,1-x);
            if (forward) vx+=(double).02f*(double).98f;
            double nextY=y+vy;
            if (vy<0 && y>=1 && nextY<=1) return x+.3>.5;
            y=nextY; x+=vx; vx*=(double).91f; vy=(vy-.08)*(double).98f;
        }
        return false;
    }
    @Test void launchRequiresNativeZeroableHorizontalVelocityInBothAxes() {
        assertTrue(NativeLoggingJump.stationaryForLaunch(0,0));
        assertTrue(NativeLoggingJump.stationaryForLaunch(.0029,-.0029));
        assertFalse(NativeLoggingJump.stationaryForLaunch(.003,0));
        assertFalse(NativeLoggingJump.stationaryForLaunch(0,-.003));
        assertFalse(NativeLoggingJump.stationaryForLaunch(Double.NaN,0));
    }
}
