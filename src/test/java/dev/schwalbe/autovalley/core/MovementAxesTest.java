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
}
