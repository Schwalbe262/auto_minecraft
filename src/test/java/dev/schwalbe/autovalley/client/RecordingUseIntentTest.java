package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RecordingUseIntentTest {
    @Test void recordsMainHandAirUseWithoutInventingABlockOrSuccess() {
        var intent=RecordingUseIntent.from(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND,42));
        assertEquals("use_item_intent",intent.type());assertNull(intent.target());
        assertEquals(InteractionHand.MAIN_HAND,intent.hand());
        assertEquals(Set.of("hand","nativeSequence"),intent.fields().keySet());
        assertEquals("MAIN_HAND",intent.fields().get("hand"));assertEquals(42,intent.fields().get("nativeSequence"));
        assertThrows(UnsupportedOperationException.class,()->intent.fields().put("rawPacket","no"));
    }
    @Test void offhandRemainsOffhandAndIsNotTreatedAsAHornActivation() {
        var intent=RecordingUseIntent.from(new ServerboundUseItemPacket(InteractionHand.OFF_HAND,7));
        assertEquals("OFF_HAND",intent.fields().get("hand"));assertNull(intent.target());
        assertFalse(intent.fields().containsKey("selectedItem"));
    }
    @Test void blockUseRemainsSeparateWithDetachedPositionAndFace() {
        var hit=new BlockHitResult(new Vec3(10.5,74.5,20.5),Direction.NORTH,new BlockPos(10,74,20),false);
        var intent=RecordingUseIntent.from(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,hit,5));
        assertEquals("use_block_intent",intent.type());assertEquals(new Pos(10,74,20),intent.target());
        assertEquals(Set.of("pos","face","hand","nativeSequence"),intent.fields().keySet());
        assertEquals("NORTH",intent.fields().get("face"));
    }
    @Test void unrelatedObjectsAreNotInspectedOrSerialized() {
        assertNull(RecordingUseIntent.from(null));
        assertNull(RecordingUseIntent.from("/private command"));
        assertNull(RecordingUseIntent.from(new Object(){@Override public String toString(){throw new AssertionError("must not serialize");}}));
    }
    @Test void clientThreadObservationIsExplicitlyUnverifiedAndCannotAssociateAnAirUseToAMenu() throws Exception {
        String source=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/ClientRecorder.java"));
        assertTrue(source.contains("RecordingUseIntent.from(packet)"));
        assertTrue(source.contains("data.put(\"associationVerified\",false)"));
        assertTrue(source.contains("data.put(\"serverSuccessVerified\",false)"));
        int queue=source.indexOf("mc.execute(() -> {");
        assertTrue(queue<source.indexOf("data.put(\"cornucopiaCenterObserved\""));
        assertTrue(source.substring(queue).contains("generation!=expected"));
        assertTrue(source.substring(queue).contains("if (useTarget!=null) {\n                lastInteraction=useTarget"));
        assertFalse(source.contains(".useItem("));assertFalse(source.contains(".send("));
    }
}
