package dev.schwalbe.autovalley.ui;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationRulesTest {
    @Test void classifierRejectsMissingWineYearAndUnknownGrade() {
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, ""));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "2.5"));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.WINE_CHEST, "-1"));
        assertThrows(IllegalArgumentException.class, () -> RegistrationRules.classifier(PoiKind.TOMATO_CHEST, "4"));
        assertEquals(3, RegistrationRules.classifier(PoiKind.TOMATO_CHEST, " 3 "));
        assertEquals(7, RegistrationRules.classifier(PoiKind.WINE_CHEST, "7"));
        assertNull(RegistrationRules.classifier(PoiKind.BED, ""));
    }

    @Test void farmBoundsAreInclusiveAndOverflowSafe() {
        assertTrue(RegistrationRules.validBounds(new Pos(0, 64, 0), new Pos(31, 95, 31)));
        assertFalse(RegistrationRules.validBounds(new Pos(0, 64, 0), new Pos(32, 95, 31)));
        assertFalse(RegistrationRules.validBounds(null, new Pos(0, 0, 0)));
        assertFalse(RegistrationRules.validBounds(new Pos(Integer.MIN_VALUE, 0, 0), new Pos(Integer.MAX_VALUE, 0, 0)));
        assertTrue(RegistrationRules.validBounds(new Pos(3, 64, 3), new Pos(-3, 65, -3)));
    }

    @Test void machineClassificationNeverOffersWineStorageForShipping() {
        assertEquals(List.of(PoiKind.SHIPPING_BIN), RegistrationRules.kinds(block("shippingbin:smart_shipping_bin", new Pos(0, 0, 0), true)));
        assertEquals(List.of(PoiKind.TOMATO_CHEST, PoiKind.WINE_CHEST), RegistrationRules.kinds(block("minecraft:chest", new Pos(0, 0, 0), true)));
        assertTrue(RegistrationRules.kinds(block("minecraft:stone", new Pos(0, 0, 0), false)).isEmpty());
    }

    @Test void suggestionsContainBothVineHeightsAndKeepSeparatedFieldsSeparate() {
        List<Farm> farms = RegistrationRules.suggestFarms(List.of(
            tomato(0, 64, 0), block("farmersdelight:tomatoes_on_rope", new Pos(0, 65, 0), false), tomato(2, 64, 0), tomato(2, 65, 0),
            tomato(20, 64, 0), tomato(21, 64, 0)));
        assertEquals(2, farms.size());
        assertEquals(new Pos(0, 64, 0), farms.get(0).first());
        assertEquals(new Pos(2, 65, 0), farms.get(0).second());
        assertEquals(new Pos(20, 64, 0), farms.get(1).first());
        assertFalse(RegistrationRules.overlap(farms.get(0), farms.get(1)));
        assertTrue(RegistrationRules.overlap(farms.get(0), new Farm("touches", new Pos(2, 64, 0), new Pos(3, 65, 2))));
    }

    private static BlockData tomato(int x, int y, int z) { return block("farmersdelight:tomatoes", new Pos(x, y, z), false); }
    private static BlockData block(String id, Pos p, boolean container) { return new BlockData(p, id, Map.of("container", String.valueOf(container))); }
}
