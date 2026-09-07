package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Feature;
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

    @Test void parsesEveryExactOneShotFeatureWithEitherFieldOrder() throws Exception {
        for (Feature feature:Feature.values()) {
            ClientControl.Request request=request("{\"command\":\"once\",\"feature\":\""+feature.name()+"\"}");
            assertEquals("once",request.command()); assertEquals(feature,request.feature()); assertNull(request.name());
            assertEquals(request,request("{\"feature\":\""+feature.name()+"\",\"command\":\"once\"}"));
        }
        assertEquals("once",parse("{\"command\":\"once\",\"feature\":\"WINE\"}"));
    }

    @Test void parsesRecordStartAndUnicodeNamedStop() throws Exception {
        ClientControl.Request start=request("{\"command\":\"record_start\"}");
        assertEquals("record_start",start.command()); assertNull(start.feature()); assertNull(start.name());
        ClientControl.Request stop=request("{\"name\":\"와인통 작업 1\",\"command\":\"record_stop\"}");
        assertEquals("record_stop",stop.command()); assertNull(stop.feature()); assertEquals("와인통 작업 1",stop.name());
        assertEquals("a\"b\\c",request("{\"command\":\"record_stop\",\"name\":\"a\\\"b\\\\c\"}").name());
        assertEquals("line\nbreak",request("{\"command\":\"record_stop\",\"name\":\"line\\nbreak\"}").name());
    }

    @Test void recordingNameLengthIsOneTo64UnicodeCodePoints() throws Exception {
        for (String name:new String[]{"a","a".repeat(64),"토".repeat(64),"🍅".repeat(64)}) {
            assertEquals(name,request("{\"command\":\"record_stop\",\"name\":\""+name+"\"}").name());
        }
        for (String name:new String[]{""," ","　","\u00a0","a".repeat(65),"🍅".repeat(65)}) {
            assertThrows(Exception.class,() -> request("{\"command\":\"record_stop\",\"name\":\""+name+"\"}"));
        }
        assertThrows(Exception.class,() -> request("{\"command\":\"record_stop\",\"name\":\"\\t\\n\"}"));
    }

    @Test void rejectsMissingWrongAndUnexpectedArgumentsForEveryNewCommand() {
        for (String input:new String[]{
            "{\"command\":\"once\"}", "{\"command\":\"once\",\"feature\":\"wine\"}",
            "{\"command\":\"once\",\"feature\":\"WINE \"}", "{\"command\":\"once\",\"feature\":\"ALL\"}",
            "{\"command\":\"once\",\"name\":\"WINE\"}", "{\"command\":\"once\",\"feature\":\"WINE\",\"name\":\"test\"}",
            "{\"command\":\"record_stop\"}", "{\"command\":\"record_stop\",\"feature\":\"WINE\"}",
            "{\"command\":\"record_stop\",\"name\":\"test\",\"feature\":\"WINE\"}",
            "{\"command\":\"record_start\",\"name\":\"test\"}", "{\"command\":\"record_start\",\"feature\":\"WINE\"}",
            "{\"command\":\"start\",\"feature\":\"WINE\"}", "{\"command\":\"pause\",\"name\":\"test\"}",
            "{\"command\":\"record\"}", "{\"command\":\"RECORD_START\"}",
            "{\"command\":\"record_stop\",\"name\":\"test\",\"path\":\"anything\"}",
            "{\"command\":\"once\",\"feature\":\"WINE\",\"x\":\"10\"}"
        }) assertThrows(Exception.class,() -> request(input),input);
    }

    @Test void rejectsDuplicateArgumentsEvenWhenKeysUseUnicodeEscapes() {
        for (String input:new String[]{
            "{\"command\":\"once\",\"feature\":\"WINE\",\"feature\":\"PRESERVES\"}",
            "{\"command\":\"record_stop\",\"name\":\"a\",\"name\":\"a\"}",
            "{\"command\":\"once\",\"feature\":\"WINE\",\"comm\\u0061nd\":\"once\"}",
            "{\"command\":\"record_stop\",\"name\":\"a\",\"na\\u006de\":\"b\"}"
        }) assertThrows(Exception.class,() -> request(input),input);
    }

    @Test void everyArgumentMustBeAStringAndNoNestedValuesAreAccepted() {
        for (String value:new String[]{"null","true","false","1","[]","{}"}) {
            assertThrows(Exception.class,() -> request("{\"command\":\"once\",\"feature\":"+value+"}"));
            assertThrows(Exception.class,() -> request("{\"command\":\"record_stop\",\"name\":"+value+"}"));
        }
    }

    @Test void strictJsonRejectsRawStringControlsInvalidEscapesAndTrailingDocuments() {
        for (String name:new String[]{"a\nb","a\tb","a\rb","a"+(char)0+"b","a\\'b","a\\vb"}) {
            assertThrows(Exception.class,() -> request("{\"command\":\"record_stop\",\"name\":\""+name+"\"}"));
        }
        assertThrows(Exception.class,() -> request("{\"command\":\"once\",\"feature\":\"WINE\"}{}"));
        assertThrows(Exception.class,() -> request("{\"command\":\"record_start\"}//comment"));
        assertThrows(Exception.class,() -> request("{\"command\":\"once\",\"feature\":\"WINE\",}"));
    }

    @Test void newRequestsRetainStrictByteAndUtf8Limits() throws Exception {
        byte[] valid="{\"command\":\"record_start\"}".getBytes(StandardCharsets.UTF_8);
        byte[] maximum=Arrays.copyOf(valid,1023);
        Arrays.fill(maximum,valid.length,maximum.length,(byte)' ');
        assertEquals("record_start",ClientControl.parseRequest(maximum).command());
        assertThrows(Exception.class,() -> ClientControl.parseRequest(Arrays.copyOf(maximum,1024)));
        byte[] invalid="{\"command\":\"record_stop\",\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8);
        invalid[invalid.length-3]=(byte)0xff;
        assertThrows(Exception.class,() -> ClientControl.parseRequest(invalid));
    }

    private static String parse(String input) throws Exception {
        return ClientControl.parseCommand(input.getBytes(StandardCharsets.UTF_8));
    }
    private static ClientControl.Request request(String input) throws Exception {
        return ClientControl.parseRequest(input.getBytes(StandardCharsets.UTF_8));
    }
}
