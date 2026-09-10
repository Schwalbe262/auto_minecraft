package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import java.util.LinkedHashMap;
import java.util.Map;

/** Detached scalar intent only. This is neither an action replay nor proof of server success. */
record RecordingUseIntent(String type,Pos target,InteractionHand hand,Map<String,Object> fields) {
    RecordingUseIntent { fields=Map.copyOf(fields); }
    static RecordingUseIntent from(Object packet) {
        Map<String,Object> data=new LinkedHashMap<>();
        if(packet instanceof ServerboundUseItemOnPacket use) {
            var block=use.getHitResult().getBlockPos();
            Pos target=new Pos(block.getX(),block.getY(),block.getZ());
            data.put("pos",target);data.put("face",use.getHitResult().getDirection().name());
            data.put("hand",use.getHand().name());data.put("nativeSequence",use.getSequence());
            return new RecordingUseIntent("use_block_intent",target,use.getHand(),data);
        }
        if(packet instanceof ServerboundUseItemPacket use) {
            data.put("hand",use.getHand().name());data.put("nativeSequence",use.getSequence());
            return new RecordingUseIntent("use_item_intent",null,use.getHand(),data);
        }
        return null;
    }
}
