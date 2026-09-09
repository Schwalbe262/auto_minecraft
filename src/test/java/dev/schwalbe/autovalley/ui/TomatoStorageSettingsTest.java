package dev.schwalbe.autovalley.ui;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TomatoStorageSettingsTest {
    @Test void acceptsInclusiveIntegerLimitsPresetsAndWhitespace() {
        for(int percent:List.of(1,42,80,90,100)) {
            assertEquals(percent,RegistrationRules.tomatoStorageLimitPercent(Integer.toString(percent)));
            assertEquals(percent,RegistrationRules.tomatoStorageLimitPercent(" "+percent+" "));
        }
    }

    @Test void rejectsInvalidDraftsWithoutClampingFractionalOrOverflowValues() {
        assertThrows(IllegalArgumentException.class,()->RegistrationRules.tomatoStorageLimitPercent(null));
        for(String value:List.of(""," ","0","-1","101","1000","2147483648","80.5","90%","+80","1e2","eighty"))
            assertThrows(IllegalArgumentException.class,()->RegistrationRules.tomatoStorageLimitPercent(value),value);
    }

    @Test void bothLanguagesCoverEveryNewSettingAndMatchFormatArguments() throws Exception {
        JsonObject english=language("en_us"),korean=language("ko_kr");
        Set<String> suffixes=Set.of("open","enabled","limit","hint","error");
        for(String suffix:suffixes) {
            String key="autovalley.tomato_storage.settings."+suffix;
            assertTrue(english.has(key),key);assertTrue(korean.has(key),key);
            String en=english.get(key).getAsString(),ko=korean.get(key).getAsString();
            assertFalse(en.isBlank());assertFalse(ko.isBlank());
            assertEquals(en.split("%s",-1).length,ko.split("%s",-1).length,key);
        }
        assertTrue(english.get("autovalley.tomato_storage.settings.hint").getAsString().contains("registered"));
        assertTrue(korean.get("autovalley.tomato_storage.settings.hint").getAsString().contains("등록"));
    }

    private static JsonObject language(String name)throws Exception {
        try(InputStream stream=TomatoStorageSettingsTest.class.getResourceAsStream("/assets/autovalley/lang/"+name+".json")) {
            assertNotNull(stream);return JsonParser.parseString(new String(stream.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
