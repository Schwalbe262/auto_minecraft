package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HarvestMovementRulesTest {
    private static final Action HARVEST=new Action.UseBlock(new Pos(0,64,0),Action.Use.HARVEST);
    private static final Movement MOVE=new Movement(0,0,true,true,false,false);
    private static PlayerState player(double x,double y,boolean grounded) { return new PlayerState(x,y,0,0,0,grounded,false,20,20,0,true,false); }
    private static boolean allowed(Action action,Movement movement,PlayerState current,long elapsed,boolean enabled) {
        return HarvestMovementRules.mayOverlap(action,movement,player(0,64,true),current,elapsed,enabled);
    }
    @Test void groundedShortHarvestCanContinueInTheBackground() {
        assertTrue(allowed(HARVEST,MOVE,player(2,64,true),8,true));
        assertTrue(allowed(HARVEST,MOVE,player(3,64.5,true),10,true));
    }
    @Test void lateRepliesAndExcessiveDriftStopMovement() {
        assertFalse(allowed(HARVEST,MOVE,player(0,64,true),11,true));
        assertFalse(allowed(HARVEST,MOVE,player(3.01,64,true),3,true));
        assertFalse(allowed(HARVEST,MOVE,player(0,64.51,true),3,true));
        assertFalse(allowed(HARVEST,MOVE,player(0,64,true),-1,true));
    }
    @Test void inventoryDoorMachineAndSleepRepliesNeverAllowAnOverlap() {
        for(Action action:new Action[]{new Action.QuickMove(0,9),new Action.SwapHotbar(9,5),new Action.SelectHotbar(0),
            new Action.CloseContainer(1),new Action.UseBlock(new Pos(0,64,0),Action.Use.DOOR),
            new Action.UseBlock(new Pos(0,64,0),Action.Use.MACHINE),new Action.UseBlock(new Pos(0,64,0),Action.Use.SLEEP)})
            assertFalse(allowed(action,MOVE,player(0,64,true),0,true));
    }
    @Test void jumpSneakAirborneDisabledAndNonFiniteIntentStop() {
        assertFalse(allowed(HARVEST,new Movement(0,0,true,true,true,false),player(0,64,true),0,true));
        assertFalse(allowed(HARVEST,new Movement(0,0,true,true,false,true),player(0,64,true),0,true));
        assertFalse(allowed(HARVEST,MOVE,player(0,64,false),0,true));
        assertFalse(allowed(HARVEST,MOVE,player(0,64,true),0,false));
        assertFalse(allowed(HARVEST,new Movement(Float.NaN,0,true,false,false,false),player(0,64,true),0,true));
        assertFalse(allowed(HARVEST,MOVE,player(Double.NaN,64,true),0,true));
        assertFalse(allowed(HARVEST,null,player(0,64,true),0,true));
    }
}
