package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoordinateControlTest {
    private ClientControl.Request parse(String json) throws Exception {
        return ClientControl.parseRequest(json.getBytes(StandardCharsets.UTF_8));
    }
    @Test void moveOnlyRequiresThreeIntegerStringsAndCarriesNoFeature() throws Exception {
        var request=parse("{\"command\":\"move_once\",\"x\":\"12\",\"y\":\"70\",\"z\":\"-5\"}");
        assertEquals(new Pos(12,70,-5),request.position()); assertNull(request.feature()); assertNull(request.name());
        assertTrue(EmergencyStartGate.rejectsCommand(true,"move_once"));
        assertFalse(EmergencyStartGate.rejectsCommand(false,"move_once"));
    }
    @Test void invalidPartialExtraAndNonStringCoordinatesCannotMove() {
        for (String json:new String[]{
            "{\"command\":\"move_once\",\"x\":\"0\",\"y\":\"70\"}",
            "{\"command\":\"move_once\",\"x\":0,\"y\":\"70\",\"z\":\"0\"}",
            "{\"command\":\"move_once\",\"x\":\"0.5\",\"y\":\"70\",\"z\":\"0\"}",
            "{\"command\":\"move_once\",\"x\":\"2147483647\",\"y\":\"70\",\"z\":\"0\"}",
            "{\"command\":\"move_once\",\"x\":\"0\",\"y\":\"70\",\"z\":\"0\",\"feature\":\"WINE\"}",
            "{\"command\":\"move_once\",\"x\":\"0\",\"y\":\"70\",\"z\":\"0\",\"x\":\"1\"}"
        }) assertThrows(Exception.class,() -> parse(json));
    }
    @Test void observingUsesOnlyAnExplicitSavedDraftNameAndStopPriority() throws Exception {
        var request=parse("{\"command\":\"observe_once\",\"name\":\"보관소 후보\"}");
        assertEquals("보관소 후보",request.name()); assertNull(request.position()); assertNull(request.feature());
        assertTrue(EmergencyStartGate.rejectsCommand(true,"observe_once"));
        assertThrows(Exception.class,() -> parse("{\"command\":\"observe_once\",\"name\":\"보관소 후보\",\"x\":\"0\"}"));
    }
}
