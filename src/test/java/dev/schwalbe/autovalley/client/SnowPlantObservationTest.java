package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnowPlantObservationTest {
    private static final Pos CELL=new Pos(647,75,1597);

    @Test void rawBlockEntityEvidenceKeepsItsOwnStrictlyNewSequenceAndNegativeUpdates() {
        ServerObservations observations=new ServerObservations();
        observations.block(CELL); long before=observations.sequence();
        observations.snowPlant(CELL,true); long planted=observations.sequence();
        observations.snowPlant(CELL,false);
        var replies=observations.nativeSnowPlantsSince(before);
        assertEquals(2,replies.size());assertTrue(replies.get(0).spruceSapling());assertFalse(replies.get(1).spruceSapling());
        assertTrue(replies.get(0).seq()>before);assertTrue(replies.get(1).seq()>replies.get(0).seq());
        assertEquals(1,observations.nativeSnowPlantsSince(planted).size());
        assertEquals(CELL,replies.get(0).pos());
        assertThrows(UnsupportedOperationException.class,()->replies.clear());
    }

    @Test void historyIsBoundedAndCannotSurviveAConnectionChange() {
        ServerObservations observations=new ServerObservations();
        for(int i=0;i<1100;i++)observations.snowPlant(CELL,true);
        assertEquals(1024,observations.nativeSnowPlantsSince(0).size());
        long generation=observations.generation();observations.clear();
        assertTrue(observations.nativeSnowPlantsSince(0).isEmpty());assertTrue(observations.generation()>generation);
        assertEquals(0,observations.sequence());
    }

    @Test void packetClassificationHappensBeforeForwardingAndTheRecordedFactNeverReadsLiveWorldNbt() throws Exception {
        String source=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/PacketObserver.java"));
        assertTrue(source.indexOf("NativeSnowPlanting.packetPlant(packet.getType(),packet.getTag())")
            < source.indexOf("super.channelRead(ctx,message)"));
        assertTrue(source.contains("observations.snowPlant(MinecraftWorld.pos(packet.getPos()),Boolean.TRUE.equals(snowPlant))"));
        assertTrue(source.contains("if (observations.generation()==generation) observation.run()"));
        assertFalse(source.contains("getContainedState"));assertFalse(source.contains("getBlockEntity"));
    }
}
