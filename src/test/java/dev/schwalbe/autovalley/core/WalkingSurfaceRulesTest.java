package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WalkingSurfaceRulesTest {
    @Test void stairsAllowTwoHalfStepsButNotTheirTallFace() {
        assertTrue(WalkingSurfaceRules.canStep(72,72.5,0.6,false));
        assertTrue(WalkingSurfaceRules.canStep(72.5,73,0.6,false));
        assertFalse(WalkingSurfaceRules.canStep(72,73,0.6,false));
        assertFalse(WalkingSurfaceRules.canStep(71.5,72.5,0.6,false));
    }
    @Test void farmlandDepressionIsNotAFullBlockDrop() {
        assertTrue(WalkingSurfaceRules.canStep(72,71.9375,0.6,true));
        assertTrue(WalkingSurfaceRules.canStep(71.9375,72,0.6,false));
        assertFalse(WalkingSurfaceRules.canStep(72.5,71.9375,0.6,true));
        assertFalse(WalkingSurfaceRules.canStep(73,71.5,0.6,false));
    }
    @Test void unknownOrUnsupportedSurfacesAreRejected() {
        assertFalse(WalkingSurfaceRules.canStep(72,Double.NaN,0.6,false));
        assertFalse(WalkingSurfaceRules.canStep(Double.NEGATIVE_INFINITY,72,0.6,false));
        assertFalse(WalkingSurfaceRules.canStep(72,72.5,0.4,false));
    }
    @Test void hypotheticalPickupDistanceUsesActualStandingHeight() {
        Pos target=new Pos(1,72,0), beside=new Pos(0,72,0);
        assertTrue(WalkingSurfaceRules.positionalDistance(beside,71.9375,target)>1.0);
        assertEquals(0.0625,WalkingSurfaceRules.positionalDistance(target,71.9375,target),1e-9);
        assertTrue(WalkingSurfaceRules.positionalDistance(beside,71.5,target)>1.0);
        assertEquals(0.5,WalkingSurfaceRules.positionalDistance(target,71.5,target),1e-9);
    }
}
