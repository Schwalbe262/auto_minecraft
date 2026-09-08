package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MovementLookTest {
    @Test void ninetyDegreeCornerIsBoundedAndConvergesWithoutOvershooting() {
        float yaw=0; int steps=0;
        while(yaw!=90 && steps++<40) {
            float next=MovementLook.yaw(yaw,90);
            assertTrue(next>=yaw && next<=90); assertTrue(next-yaw<=18.0001);
            yaw=next;
        }
        assertEquals(90,yaw); assertTrue(steps>5 && steps<40);
    }

    @Test void wrappingUsesTheShortDirectionRatherThanSpinningAround() {
        assertTrue(MovementLook.yaw(179,-179)>179);
        assertTrue(MovementLook.yaw(-179,179)<-179);
        assertEquals(720,MovementLook.yaw(720,0));
        assertEquals(-360,MovementLook.yaw(-360,0));
        assertEquals(90.05f,MovementLook.yaw(90,90.05f));
    }

    @Test void easedCameraNeverChangesTheExactWorldMovementHeadingOrSpeed() {
        for(float desired:new float[]{-135,-45,45,135,180}) {
            float view=0;
            Movement intent=new Movement(desired,0,true,false,false,false);
            for(int tick=0;tick<30;tick++) {
                view=MovementLook.yaw(view,desired);
                MovementAxes axes=MovementAxes.from(intent,view);
                double radians=Math.toRadians(view);
                double x=axes.left()*Math.cos(radians)-axes.forward()*Math.sin(radians);
                double z=axes.forward()*Math.cos(radians)+axes.left()*Math.sin(radians);
                assertEquals(-Math.sin(Math.toRadians(desired)),x,1e-6);
                assertEquals(Math.cos(Math.toRadians(desired)),z,1e-6);
                assertEquals(1,Math.hypot(axes.forward(),axes.left()),1e-6);
            }
        }
    }

    @Test void pitchReturnsGraduallyFromInteractionAimAndStaysWithinNativeLimits() {
        assertEquals(-70,MovementLook.pitch(-80,0));
        assertEquals(70,MovementLook.pitch(80,0));
        assertTrue(MovementLook.pitch(89,500)<=90);
        assertTrue(MovementLook.pitch(-89,-500)>=-90);
        assertEquals(0,MovementLook.pitch(0,0));
    }

    @Test void nonFiniteAnglesAreRejectedRatherThanWritingAnInvalidCameraRotation() {
        for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,() -> MovementLook.yaw(invalid,0));
            assertThrows(IllegalArgumentException.class,() -> MovementLook.yaw(0,invalid));
            assertThrows(IllegalArgumentException.class,() -> MovementLook.pitch(invalid,0));
            assertThrows(IllegalArgumentException.class,() -> MovementLook.pitch(0,invalid));
        }
    }
}
