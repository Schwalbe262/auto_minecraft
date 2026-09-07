package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerObservationsTest {
    @Test void unrelatedOrPredictedSlotChangesCannotConfirmInventoryTransaction() {
        ServerObservations observations=new ServerObservations();
        long sent=observations.sequence();
        observations.menu(-2);
        observations.menu(4);
        observations.fullMenu(5);
        assertFalse(observations.fullMenuSince(4,sent));
        observations.fullMenu(4);
        assertTrue(observations.fullMenuSince(4,sent));
        assertFalse(observations.fullMenuSince(4,observations.sequence()));
    }
    @Test void reconnectInvalidatesOldServerEvidence() {
        ServerObservations observations=new ServerObservations();
        long generation=observations.generation();
        observations.fullMenu(3);
        observations.block(new Pos(1,64,2));
        observations.clear();
        assertNotEquals(generation,observations.generation());
        assertFalse(observations.fullMenuSince(3,0));
        assertFalse(observations.blockSince(new Pos(1,64,2),0));
    }
    @Test void onlyTargetBlockUpdateConfirmsAWorldInteraction() {
        ServerObservations observations=new ServerObservations();
        Pos tomato=new Pos(1,64,2), neighbor=new Pos(2,64,2);
        long sent=observations.sequence();
        observations.block(neighbor);
        assertFalse(observations.blockSince(tomato,sent));
        observations.block(tomato);
        assertTrue(observations.blockSince(tomato,sent));
    }
}
