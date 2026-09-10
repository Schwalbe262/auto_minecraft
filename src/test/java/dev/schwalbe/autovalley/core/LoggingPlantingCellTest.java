package dev.schwalbe.autovalley.core;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Replacement classification grants no break action or native placement authority by itself. */
class LoggingPlantingCellTest {
    private static final Pos CELL=new Pos(647,75,1597);

    @Test void acceptsOnlyAirVariantsAndAnExplicitSingleSnowLayer() {
        for(String id:List.of("minecraft:air","minecraft:cave_air","minecraft:void_air"))
            assertTrue(LoggingRules.replaceablePlantingCell(new BlockData(CELL,id,Map.of())),id);
        assertTrue(LoggingRules.replaceablePlantingCell(new BlockData(CELL,"minecraft:snow",Map.of("layers","1"))));
    }

    @Test void missingMalformedAndMultipleSnowLayersRemainObstructions() {
        assertFalse(LoggingRules.replaceablePlantingCell(new BlockData(CELL,"minecraft:snow",Map.of())));
        assertFalse(LoggingRules.replaceablePlantingCell(new BlockData(CELL,"minecraft:snow",null)));
        for(String layers:List.of("","0","2","3","4","5","6","7","8","-1","01","1.0","unknown"))
            assertFalse(LoggingRules.replaceablePlantingCell(new BlockData(CELL,"minecraft:snow",Map.of("layers",layers))),layers);
    }

    @Test void existingPlantsBlocksAndForeignReplaceableLookingBlocksNeverQualify() {
        for(String id:List.of(LoggingRules.SAPLING,LoggingRules.LOG,LoggingRules.CHOPPED_LOG,
            "minecraft:snow_block","minecraft:powder_snow","minecraft:grass","minecraft:spruce_leaves",
            "minecraft:stone_bricks","farmersdelight:tomatoes","foreign:snow"))
            assertFalse(LoggingRules.replaceablePlantingCell(new BlockData(CELL,id,Map.of("layers","1"))),id);
        assertFalse(LoggingRules.replaceablePlantingCell(null));
        assertFalse(LoggingRules.replaceablePlantingCell(new BlockData(CELL,null,Map.of())));
    }
}
