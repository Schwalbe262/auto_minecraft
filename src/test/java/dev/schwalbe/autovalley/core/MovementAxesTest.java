package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementAxesTest {
    private static Movement walking(float yaw) { return new Movement(yaw,0,true,false,false,false); }
    @Test void alignedHeadingUsesNormalForwardInput() {
        assertEquals(new MovementAxes(1,0),MovementAxes.from(walking(75),75));
    }
    @Test void lookingSidewaysOrBackwardsDoesNotChangeWorldTravelDirection() {
        assertEquals(new MovementAxes(0,1),MovementAxes.from(walking(0),90));
        assertEquals(new MovementAxes(0,-1),MovementAxes.from(walking(0),-90));
        assertEquals(new MovementAxes(-1,0),MovementAxes.from(walking(0),180));
        assertEquals(new MovementAxes(1,0),MovementAxes.from(walking(360),0));
    }
    @Test void allAnglesPreserveUnitSpeedAndRequestedWorldHeading() {
        for(int heading=-720;heading<=720;heading+=17) for(int view=-360;view<=360;view+=29) {
            MovementAxes axes=MovementAxes.from(walking(heading),view);
            double radians=Math.toRadians(view);
            double x=axes.left()*Math.cos(radians)-axes.forward()*Math.sin(radians);
            double z=axes.forward()*Math.cos(radians)+axes.left()*Math.sin(radians);
            assertEquals(-Math.sin(Math.toRadians(heading)),x,1e-6);
            assertEquals(Math.cos(Math.toRadians(heading)),z,1e-6);
            assertEquals(1,Math.hypot(axes.forward(),axes.left()),1e-6);
        }
    }
    @Test void missingStoppedOrNonFiniteIntentNeverMoves() {
        assertEquals(MovementAxes.STOPPED,MovementAxes.from(null,0));
        assertEquals(MovementAxes.STOPPED,MovementAxes.from(new Movement(0,0,false,true,false,false),0));
        assertEquals(MovementAxes.STOPPED,MovementAxes.from(walking(Float.NaN),0));
        assertEquals(MovementAxes.STOPPED,MovementAxes.from(walking(0),Float.POSITIVE_INFINITY));
    }
    @Test void slowInputPreservesHeadingAndNeverExceedsItsRequestedStrength() {
        assertEquals(1f,walking(0).inputScale());
        for (int heading=-360;heading<=360;heading+=17) for (int view=-180;view<=180;view+=29) {
            MovementAxes axes=MovementAxes.from(new Movement(heading,0,true,false,false,false,.2f),view);
            double radians=Math.toRadians(view);
            double x=axes.left()*Math.cos(radians)-axes.forward()*Math.sin(radians);
            double z=axes.forward()*Math.cos(radians)+axes.left()*Math.sin(radians);
            assertEquals(-Math.sin(Math.toRadians(heading))*.2,x,1e-6);
            assertEquals(Math.cos(Math.toRadians(heading))*.2,z,1e-6);
            assertEquals(.2,Math.hypot(axes.forward(),axes.left()),1e-6);
        }
        assertEquals(MovementAxes.STOPPED,MovementAxes.from(new Movement(90,0,true,false,false,false,0),-45));
    }
    @Test void inputStrengthCannotInjectNonFiniteOrAmplifiedMovement() {
        for (float scale:new float[]{-.01f,1.01f,Float.NaN,Float.NEGATIVE_INFINITY,Float.POSITIVE_INFINITY})
            assertThrows(IllegalArgumentException.class,()->new Movement(0,0,true,false,false,false,scale));
    }
}
