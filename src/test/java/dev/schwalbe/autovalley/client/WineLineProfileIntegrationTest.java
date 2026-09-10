package dev.schwalbe.autovalley.client;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WineLineProfileIntegrationTest {
    private static final Gson JSON=new Gson();
    @TempDir Path directory;
    private Profile original() {
        Profile p=new Profile();p.wineBatchSchedule=new WineBatchSchedule(80,false,List.of(),74L);
        p.nextEligibleDay.put("wine:0:64:0",80L);p.pois.add(new Poi(new Pos(0,64,0),PoiKind.WINE_KEG,"legacy",null));
        p.commodityStores.put("ancient",new CommodityStore("ancient","fruit",Set.of(ItemData.ANCIENT_FRUIT),List.of(new Pos(10,64,0))));
        return p;
    }
    private WineProductionLine line() {return new WineProductionLine("ancient_wine","고대과일 와인",ItemData.ANCIENT_FRUIT,ItemData.ANCIENT_WINE,
        "ancient","ancient_reserve",List.of(new Pos(12,64,0)),6,true);}
    private String additions() {
        return JSON.toJson(new WorkRegistrationImport.Data(Map.of(),List.of(),Map.of("ancient_reserve",
            new CommodityStore("ancient_reserve","reserve",Set.of(ItemData.ANCIENT_WINE),List.of(new Pos(14,64,0)))),Map.of(),Map.of(),List.of(),
            Map.of("ancient_wine",line()),Set.of(Feature.WINE,Feature.WINE_STORAGE,Feature.WINE_SURPLUS_SHIPPING)));
    }
    @Test void addOnlyImportKeepsTomatoScheduleAndExistingInputStoreAndIsIdempotent() {
        Profile before=original();String unchanged=JSON.toJson(before);
        Profile added=WorkRegistrationImport.merge(before,additions());
        assertEquals(unchanged,JSON.toJson(before));assertEquals(before.wineBatchSchedule,added.wineBatchSchedule);
        assertEquals(before.nextEligibleDay,added.nextEligibleDay);assertEquals(before.commodityStores.get("ancient"),added.commodityStores.get("ancient"));
        assertEquals(1,added.pois(PoiKind.WINE_KEG).size());assertEquals(line(),added.wineProductionLines.get("ancient_wine"));
        assertEquals(JSON.toJson(added),JSON.toJson(WorkRegistrationImport.merge(added,additions())));
    }
    @Test void schemaSixRoundTripPreservesIndependentCadencesAndDoesNotRewriteOnLoad() throws Exception {
        Profile p=WorkRegistrationImport.merge(original(),additions());p.wineProductionSchedules.put("ancient_wine",new WineBatchSchedule(85,false,List.of(),79L));
        String key=ProfileStore.key("test-wine-line");ProfileStore store=new ProfileStore(directory);store.save(key,p);
        Path file=directory.resolve(key+".json");String bytes=Files.readString(file);Profile loaded=store.load(key);
        assertEquals(6,loaded.schemaVersion);assertEquals(80,loaded.wineBatchSchedule.nextDueDay());
        assertEquals(85,loaded.wineProductionSchedules.get("ancient_wine").nextDueDay());assertEquals(bytes,Files.readString(file));
        assertEquals(p.wineProductionLines,loaded.wineProductionLines);
    }
    @Test void explicitNullDefinitionsAndImportedScheduleInjectionCannotHideAnExistingLine() {
        Profile p=WorkRegistrationImport.merge(original(),additions());String bytes=JSON.toJson(p);
        assertThrows(IllegalArgumentException.class,()->WorkRegistrationImport.merge(p,"{\"wineProductionSchedules\":{}}"));
        assertThrows(IllegalArgumentException.class,()->WorkRegistrationImport.merge(p,"{\"wineProductionLines\":{\"ancient_wine\":null}}"));
        assertEquals(bytes,JSON.toJson(p));
    }
    @Test void oldSchemaDefaultsPreserveTomatoProductionWithoutInventingAnAncientLine() throws Exception {
        String key=ProfileStore.key("old-wine-line");Path file=directory.resolve(key+".json");
        String bytes="{\"schemaVersion\":5,\"wineCycleDays\":6}";Files.writeString(file,bytes);
        Profile p=new ProfileStore(directory).load(key);assertTrue(p.tomatoWineEnabled);assertTrue(p.wineProductionLines.isEmpty());
        assertEquals(6,p.schemaVersion);assertEquals(bytes,Files.readString(file));
    }
}
