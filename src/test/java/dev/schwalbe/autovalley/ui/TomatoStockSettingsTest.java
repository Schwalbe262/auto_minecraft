package dev.schwalbe.autovalley.ui;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TomatoStockSettingsTest {
    @Test void acceptsOnlyBoundedWholeGameDays() {
        assertEquals(1,RegistrationRules.tomatoStockRefreshDays("1"));
        assertEquals(3,RegistrationRules.tomatoStockRefreshDays(" 3 "));
        assertEquals(28,RegistrationRules.tomatoStockRefreshDays("28"));
        for(String invalid:List.of("","0","29","999","-1","1.5","NaN","true","3 days"))
            assertThrows(IllegalArgumentException.class,()->RegistrationRules.tomatoStockRefreshDays(invalid),invalid);
        assertThrows(IllegalArgumentException.class,()->RegistrationRules.tomatoStockRefreshDays(null));
    }
    @Test void bothLanguagesExplainVisitBasedRefreshAndUnknownExternalChanges() throws Exception {
        for(String locale:List.of("ko_kr","en_us")) {
            try(var input=getClass().getResourceAsStream("/assets/autovalley/lang/"+locale+".json")) {
                assertNotNull(input);
                var json=JsonParser.parseString(new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
                for(String key:List.of("refresh","refresh_hint","error"))
                    assertFalse(json.get("autovalley.tomato_storage.settings."+key).getAsString().isBlank());
                String hint=json.get("autovalley.tomato_storage.settings.refresh_hint").getAsString();
                assertTrue(hint.contains("3"));
                assertTrue(hint.contains(locale.equals("ko_kr") ? "다른 플레이어" : "Other players"));
            }
        }
    }
}
