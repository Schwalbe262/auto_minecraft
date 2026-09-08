package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StairFlowSafetyTest {
    @Test void aBorrowedProofRevokedInFlightStopsWithoutGuessingAnUnseenLanding() {
        class Revocable extends StairFlowPhysicsTest.Fixture {
            boolean permitted=true;
            Revocable(){super(6,1,0,true);}
            @Override public boolean canFlowDescent(List<Pos> preview,Profile profile) {
                return permitted && super.canFlowDescent(preview,profile);
            }
        }
        Revocable f=new Revocable();
        while(f.ground && f.now<100){f.control();f.physics();}
        assertFalse(f.ground);int before=index(f);f.permitted=false;f.control();
        assertEquals(Navigation.Result.BLOCKED,f.result);assertNull(f.movement);
        assertEquals(before,index(f));assertEquals(0,f.airInputs);
    }

    @Test void aFullCorridorNeverAdvancesThePathOnAnAirborneOrHalfTreadSample() {
        var f=new StairFlowPhysicsTest.Fixture(8,1,0,true);
        int airborne=0,halfTreads=0;
        while(f.result==Navigation.Result.MOVING && f.now<1000) {
            int before=index(f);
            boolean tracking=f.nav.diagnosticStatus().startsWith("DESCENT_");
            boolean half=f.ground && Math.abs(f.y-Math.floor(f.y)-.5)<.00001;
            f.control();
            if(tracking && (!f.ground || half)) {
                assertEquals(before,index(f),f.debug());
                if(half)halfTreads++;else airborne++;
            }
            if(f.result==Navigation.Result.MOVING)f.physics();
        }
        assertEquals(Navigation.Result.ARRIVED,f.result,f.debug());
        assertTrue(airborne>0);assertTrue(halfTreads>0);assertEquals(0,f.airInputs);
    }

    @Test void cancellationDuringFlowStillFencesAirborneRestart() {
        var f=new StairFlowPhysicsTest.Fixture(6,1,0,true);
        while(f.ground && f.now<100){f.control();f.physics();}
        assertFalse(f.ground);f.nav.reset();assertNull(f.movement);f.now++;
        f.control();assertEquals(Navigation.Result.BLOCKED,f.result);
        assertFalse(f.nav.retryableFailure());assertNull(f.movement);
    }

    @Test void sidewaysDepartureIsNotAcceptedAsAnotherVerifiedTread() {
        var f=new StairFlowPhysicsTest.Fixture(6,1,0,true);
        while(f.ground && f.now<100){f.control();f.physics();}
        f.z+=.2;f.control();assertEquals(Navigation.Result.BLOCKED,f.result);
        assertNull(f.movement);assertEquals(0,f.airInputs);
    }

    @Test void leavingFlowForAnOrdinaryEdgeReusesOnlyTheSlowActuallyGroundedHandoff() {
        for(int[] axis:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(double acceleration:new double[]{.06,.13,.30}) {
            var f=new StairFlowPhysicsTest.Fixture(3,axis[0],axis[1],true,false);f.acceleration=acceleration;
            double previousX=f.x,previousZ=f.z;boolean handedOff=false;
            while(f.result==Navigation.Result.MOVING && f.now<1000) {
                boolean wasFlow=Boolean.TRUE.equals(f.nav.diagnostics().get("descentFlow"));
                double measuredSpeed=Math.hypot(f.x-previousX,f.z-previousZ);
                f.control();
                if(wasFlow && !Boolean.TRUE.equals(f.nav.diagnostics().get("descentFlow")) && f.y==1) {
                    handedOff=true;assertTrue(f.ground);assertEquals(f.path.get(2),NavigationFeet.resolve(f,f.player()));
                    assertTrue(measuredSpeed<=.12,"The larger flow speed is never lent to an ordinary edge");
                    assertEquals(2,((Number)f.nav.diagnostics().get("descentHandoffs")).intValue());
                    assertEquals(3,index(f));assertEquals("DESCENT_DESCEND",f.nav.diagnosticStatus());
                }
                previousX=f.x;previousZ=f.z;
                if(f.result==Navigation.Result.MOVING)f.physics();
            }
            assertTrue(handedOff,"The bounded ordinary exit must actually be exercised");
            assertEquals(Navigation.Result.ARRIVED,f.result,f.debug());assertEquals(0,f.airInputs);assertEquals(0,f.submissions);
            assertTrue(f.ground);assertTrue(Math.hypot(f.vx,f.vz)<=.002);assertNull(f.movement);
        }
    }

    @Test void theUnprovenOrdinarySuffixStillRequiresTheFullCurrentLanding() {
        var f=new StairFlowPhysicsTest.Fixture(3,1,0,true,false) {
            @Override public boolean canChainDescent(List<Pos> preview,Profile profile) {
                return !preview.contains(goal) && super.canChainDescent(preview,profile);
            }
        };
        boolean fullStopBeforeSuffix=false;
        while(f.result==Navigation.Result.MOVING && f.now<1000) {
            f.control();
            if(f.y==1 && f.nav.diagnosticStatus().equals("DESCENT_LAND")) {
                fullStopBeforeSuffix=true;assertTrue(f.ground);assertTrue(index(f)<3);
            }
            if(f.result==Navigation.Result.MOVING)f.physics();
        }
        assertTrue(fullStopBeforeSuffix);assertEquals(Navigation.Result.ARRIVED,f.result,f.debug());
        assertTrue(f.acceptedPreviews.stream().noneMatch(preview->preview.contains(f.goal)));
        assertEquals(0,f.airInputs);assertEquals(0,f.submissions);
    }

    @Test void anUnmeasuredFlowExitCannotBorrowTheOrdinaryHandoff() {
        var f=new StairFlowPhysicsTest.Fixture(3,1,0,true,false);
        while(!(f.ground && f.y==1) && f.result==Navigation.Result.MOVING && f.now<200) {
            f.control();if(f.result==Navigation.Result.MOVING)f.physics();
        }
        assertTrue(f.ground);assertEquals(1,f.y);assertTrue(Boolean.TRUE.equals(f.nav.diagnostics().get("descentFlow")));
        int before=index(f);f.now++;f.control();
        assertEquals(Navigation.Result.MOVING,f.result);assertEquals(before,index(f));assertNull(f.movement);
        f.physics();f.control();assertEquals("DESCENT_LAND",f.nav.diagnosticStatus());
        assertEquals(0,f.airInputs);assertEquals(0,f.submissions);
    }

    private static int index(StairFlowPhysicsTest.Fixture f) {
        return ((Number)f.nav.diagnostics().get("nextIndex")).intValue();
    }
}
