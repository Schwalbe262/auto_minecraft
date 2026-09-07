package dev.schwalbe.autovalley.client;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class ClientControlTest {
    @Test void acceptsOnlySupportedStartAndPauseCommands() throws Exception {
        assertEquals("start",parse("{\"command\":\"start\"}"));
        assertEquals("pause",parse(" \n { \"command\" : \"pause\" } \t "));
    }

    @Test void rejectsMalformedJsonWithoutLenientParsing() {
        for (String input : new String[]{"", "{", "{\"command\":\"start\"", "{'command':'start'}",
                "{command:\"start\"}", "{\"command\":\"start\",}", "/*comment*/{\"command\":\"start\"}",
                "{\"command\":\"start\"}//comment", "[\"start\"]", "null", "\"start\""}) {
            assertThrows(Exception.class,() -> parse(input),input);
        }
    }

    @Test void rejectsDuplicateFieldsExtraFieldsAndTrailingDocuments() {
        for (String input : new String[]{"{\"command\":\"start\",\"command\":\"pause\"}",
                "{\"command\":\"start\",\"command\":\"start\"}",
                "{\"command\":\"start\",\"x\":10}", "{\"x\":10,\"command\":\"start\"}",
                "{\"command\":\"pause\"}{}", "{\"command\":\"pause\"}true"}) {
            assertThrows(Exception.class,() -> parse(input),input);
        }
    }

    @Test void rejectsUnknownCommandsAndNonStringValues() {
        for (String input : new String[]{"{}", "{\"command\":\"toggle\"}", "{\"command\":\"START\"}",
                "{\"command\":\"start \"}", "{\"command\":\"harvest\"}", "{\"command\":null}",
                "{\"command\":true}", "{\"command\":1}", "{\"command\":{}}", "{\"command\":[]}"}) {
            assertThrows(Exception.class,() -> parse(input),input);
        }
    }

    @Test void enforcesStrictByteLimitAndRejectsMalformedUtf8() throws Exception {
        byte[] valid = "{\"command\":\"start\"}".getBytes(StandardCharsets.UTF_8);
        byte[] maximum = Arrays.copyOf(valid,1023);
        Arrays.fill(maximum,valid.length,maximum.length,(byte)' ');
        assertEquals("start",ClientControl.parseCommand(maximum));
        byte[] oversized = Arrays.copyOf(maximum,1024);
        oversized[1023] = ' ';
        assertThrows(Exception.class,() -> ClientControl.parseCommand(oversized));
        assertThrows(Exception.class,() -> ClientControl.parseCommand(null));
        assertThrows(Exception.class,() -> ClientControl.parseCommand(new byte[0]));
        assertThrows(Exception.class,() -> ClientControl.parseCommand(new byte[]{(byte)0xc3,0x28}));
    }

    private static String parse(String input) throws Exception {
        return ClientControl.parseCommand(input.getBytes(StandardCharsets.UTF_8));
    }
}
