package dev.schwalbe.autovalley.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Request parsing and stop priority only: no runtime or native action is invoked. */
class PendingShipmentControlTest {
    private static final String ID="abcdef12-3456-7890-abcd-ef1234567890";
    private static byte[] bytes(String json){return json.getBytes(StandardCharsets.UTF_8);}
    private static String request(String id){return "{\"command\":\"recover_pending_ship\",\"name\":\""+id+"\"}";}

    @Test void onlyAnExactCanonicalPendingIdAndTheTwoRequiredStringFieldsAreAccepted() throws IOException {
        ClientControl.Request parsed=ClientControl.parseRequest(bytes(request(ID)));
        assertEquals("recover_pending_ship",parsed.command());assertEquals(ID,parsed.name());
        assertNull(parsed.feature());assertNull(parsed.position());
        assertEquals("recover_pending_ship",ClientControl.parseCommand(bytes(request(ID))));
        for(String id:List.of("",ID.toUpperCase(java.util.Locale.ROOT)," "+ID,ID+" ","1-1-1-1-1",ID.replace("-",""),"not-a-pending-id"))
            assertThrows(IOException.class,()->ClientControl.parseRequest(bytes(request(id))),id);
        for(String json:List.of(
                "{\"command\":\"recover_pending_ship\"}",
                "{\"name\":\""+ID+"\"}",
                "{\"command\":\"recover_pending_ship\",\"name\":null}",
                "{\"command\":\"recover_pending_ship\",\"name\":123}",
                "{\"command\":\"recover_pending_ship\",\"name\":\""+ID+"\",\"feature\":\"SHIPPING\"}",
                "{\"command\":\"recover_pending_ship\",\"name\":\""+ID+"\",\"x\":\"2\"}",
                "{\"command\":\"recover_pending_ship\",\"name\":\""+ID+"\",\"name\":\""+ID+"\"}",
                "{\"command\":\"recover_pending_ship\",\"name\":\""+ID+"\",\"resolve\":\"true\"}",
                request(ID)+" {}"))
            assertThrows(IOException.class,()->ClientControl.parseRequest(bytes(json)),json);
    }

    @Test void emergencyStopRejectsRecoveryIncludingAnUnsettledOldRequestWithoutBlockingPause() {
        assertTrue(EmergencyStartGate.rejectsCommand(true,"recover_pending_ship"));
        assertFalse(EmergencyStartGate.rejectsCommand(false,"recover_pending_ship"));
        assertFalse(EmergencyStartGate.rejectsCommand(true,"pause"));
        EmergencyStartGate gate=new EmergencyStartGate();gate.stopAt(99);
        assertTrue(EmergencyStartGate.rejectsCommand(gate.blockedAt(99),"recover_pending_ship"));
        assertTrue(EmergencyStartGate.rejectsCommand(gate.blockedAt(100),"recover_pending_ship"));
        gate.controlChecked(100,false);
        assertTrue(EmergencyStartGate.rejectsCommand(gate.blockedAt(1000),"recover_pending_ship"));
        gate.controlChecked(1000,true);
        assertFalse(EmergencyStartGate.rejectsCommand(gate.blockedAt(1000),"recover_pending_ship"));
    }
}
