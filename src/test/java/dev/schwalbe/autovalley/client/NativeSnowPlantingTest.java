package dev.schwalbe.autovalley.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSnowPlantingTest {
    private static Boolean parse(String nbt) throws Exception {
        return NativeSnowPlanting.packetPlant("snowrealmagic:snow",TagParser.parseTag(nbt));
    }

    @Test void exactDefaultSpruceAndBothValidStagesAreAccepted() throws Exception {
        assertTrue(parse("{Block:\"minecraft:spruce_sapling\"}"));
        assertTrue(parse("{Block:\"minecraft:spruce_sapling\",RO:1b}"));
        assertTrue(parse("{State:{Name:\"minecraft:spruce_sapling\"}}"));
        assertTrue(parse("{State:{Name:\"minecraft:spruce_sapling\",Properties:{}}}"));
        for(String stage:List.of("0","1"))
            assertTrue(parse("{State:{Name:\"minecraft:spruce_sapling\",Properties:{stage:\""+stage+"\"}}}"));
    }

    @Test void wrongBlockEntityTypeIsIgnoredEvenWithAnOtherwiseValidTag() throws Exception {
        CompoundTag tag=TagParser.parseTag("{Block:\"minecraft:spruce_sapling\"}");
        for(String type:List.of("snowrealmagic:texture_tile","snowrealmagic:snow_other","minecraft:snow","minecraft:chest",""))
            assertNull(NativeSnowPlanting.packetPlant(type,tag));
        assertNull(NativeSnowPlanting.packetPlant((String)null,tag));
    }

    @Test void absentMalformedAndDifferentContainedPlantsAreNegativeUpdates() throws Exception {
        assertFalse(NativeSnowPlanting.packetPlant("snowrealmagic:snow",null));
        for(String nbt:List.of("{}","{RO:1b}","{Block:1}","{Block:{Name:\"minecraft:spruce_sapling\"}}",
            "{Block:\"minecraft:air\"}","{Block:\"minecraft:oak_sapling\"}","{Block:\"minecraft:spruce_log\"}",
            "{State:\"minecraft:spruce_sapling\"}","{State:{Name:1}}","{State:{Name:\"minecraft:oak_sapling\"}}",
            "{State:{Name:\"minecraft:spruce_sapling\",Properties:1}}",
            "{State:{Name:\"minecraft:spruce_sapling\",Properties:{stage:1}}}",
            "{State:{Name:\"minecraft:spruce_sapling\",Properties:{stage:\"2\"}}}",
            "{State:{Name:\"minecraft:spruce_sapling\",Properties:{stage:\"1\",other:\"x\"}}}",
            "{State:{Name:\"minecraft:spruce_sapling\",Unexpected:1}}"))
            assertFalse(parse(nbt),nbt);
    }

    @Test void competingBlockAndStateBranchesCannotLendEachOtherAuthority() throws Exception {
        for(String nbt:List.of("{Block:\"minecraft:air\",State:{Name:\"minecraft:spruce_sapling\"}}",
            "{Block:1,State:{Name:\"minecraft:spruce_sapling\"}}",
            "{Block:\"minecraft:spruce_sapling\",State:{Name:\"minecraft:air\"}}",
            "{Block:\"minecraft:spruce_sapling\",State:{Name:\"minecraft:spruce_sapling\"}}"))
            assertFalse(parse(nbt));
    }

    @Test void worldObservationKeepsWrapperIdentityAndIsNotRawAckEvidence() throws Exception {
        String world=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/MinecraftWorld.java"));
        assertTrue(world.contains("NativeSnowPlanting.containedSpruceSapling(state,entity)"));
        assertTrue(world.contains("properties.put(\"loggingContainedPlant\",LoggingRules.SAPLING)"));
        String helper=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/NativeSnowPlanting.java"));
        assertTrue(helper.contains("ENTITY_CLASS.equals(entity.getClass().getName())"));
        assertTrue(helper.contains("BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType())"));
        assertTrue(helper.contains("getDeclaredMethod(\"getContainedState\")"));
        assertTrue(helper.contains("state.is(Blocks.SPRUCE_SAPLING)"));
        assertTrue(helper.contains("state.getValue(SnowLayerBlock.LAYERS)>=1"));
        assertTrue(helper.contains("state.getValue(SnowLayerBlock.LAYERS)<=8"));
        assertTrue(helper.contains("return wrapper(state) && state.getValue(SnowLayerBlock.LAYERS)==1;"));
        assertFalse(helper.contains("setContainedState("));
        assertFalse(helper.contains("setBlock("));
        String actions=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/NativeLoggingActions.java"));
        String confirmed=actions.substring(actions.indexOf("boolean confirmed("),actions.indexOf("void abort("));
        assertTrue(confirmed.contains("observations.nativeSnowPlantsSince(beforeSequence)"));
        assertFalse(confirmed.contains("containedSpruceSapling("));
        assertFalse(confirmed.contains("loggingContainedPlant"));
    }
}
