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

    private static int index(StairFlowPhysicsTest.Fixture f) {
        return ((Number)f.nav.diagnostics().get("nextIndex")).intValue();
    }
}
