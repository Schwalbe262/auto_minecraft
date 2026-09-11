package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import io.netty.buffer.Unpooled;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeJadePacketsTest {
    private static final Pos TARGET=new Pos(640,64,1570);
    private static CompoundTag tag(String input) {
        CompoundTag tag=new CompoundTag();tag.putInt("x",TARGET.x());tag.putInt("y",TARGET.y());tag.putInt("z",TARGET.z());
        tag.putString("id",CrystalCollection.MACHINE_ID);tag.putString("recipe",input);return tag;
    }
    private static NativeJadePackets.Crystal decode(ResourceLocation channel,int discriminator,CompoundTag tag,boolean suffix) {
        FriendlyByteBuf data=new FriendlyByteBuf(Unpooled.buffer());
        try {
            data.writeByte(discriminator);data.writeNbt(tag);if(suffix)data.writeByte(1);
            int before=data.readerIndex(),size=data.readableBytes();
            var result=NativeJadePackets.crystal(new ClientboundCustomPayloadPacket(channel,data));
            assertEquals(before,data.readerIndex());assertEquals(size,data.readableBytes());assertEquals(1,data.refCnt());
            return result;
        } finally { data.release(); }
    }
    @Test void actualJadeReplyDecodesEverySupportedOriginalWithoutConsumingOrMutatingThePacket() {
        for(String id:CrystalCollection.BASE_OUTPUT_IDS) {
            var result=decode(NativeJadePackets.CHANNEL,0,tag(id),false);
            assertNotNull(result);assertEquals(TARGET,result.pos());assertEquals(CrystalCollection.MACHINE_ID,result.blockEntityId());
            assertEquals(id,result.inputId());
        }
    }
    @Test void exactRecipeStringIsRequiredAndAnUnknownOrEmptyReplyKeepsThePositionForInvalidation() {
        assertEquals("",NativeJadePackets.crystal(tag("")).inputId());
        for(String id:List.of("society:pristine_jade","other:jade","society:JADE","society:jade ","society:jade_suffix")) {
            var result=NativeJadePackets.crystal(tag(id));assertEquals(TARGET,result.pos());assertNull(result.inputId());
        }
        CompoundTag missing=tag("society:jade");missing.remove("recipe");
        assertNull(NativeJadePackets.crystal(missing).inputId());
        missing.putInt("recipe",1);assertNull(NativeJadePackets.crystal(missing).inputId());
        missing.putString("recipe","society:jade");missing.putString("id","minecraft:barrel");
        var wrongType=NativeJadePackets.crystal(missing);assertEquals(TARGET,wrongType.pos());assertNull(wrongType.inputId());
        missing.remove("id");assertNull(NativeJadePackets.crystal(missing).inputId());
    }
    @Test void coordinatesRequireExactIntegerTagsAndDoNotBorrowPositionsFromNestedNbt() {
        for(String coordinate:List.of("x","y","z")) {
            CompoundTag bad=tag("society:ruby");bad.putLong(coordinate,64);assertNull(NativeJadePackets.crystal(bad));
            bad=tag("society:ruby");bad.remove(coordinate);assertNull(NativeJadePackets.crystal(bad));
        }
        CompoundTag nested=new CompoundTag();nested.put("BlockEntityTag",tag("society:jade"));
        assertNull(NativeJadePackets.crystal(nested));assertNull(NativeJadePackets.crystal((CompoundTag)null));
    }
    @Test void foreignChannelsWrongDiscriminatorsTrailingBytesAndMalformedPayloadsAreNotReplies() {
        assertNull(decode(new ResourceLocation("other","networking"),0,tag("society:jade"),false));
        for(int discriminator:List.of(1,2,3,128,255))assertNull(decode(NativeJadePackets.CHANNEL,discriminator,tag("society:jade"),false));
        assertNull(decode(NativeJadePackets.CHANNEL,0,tag("society:jade"),true));
        FriendlyByteBuf data=new FriendlyByteBuf(Unpooled.buffer());
        try {
            data.writeByte(0);data.writeInt(0x0AFFFF00);
            assertNull(NativeJadePackets.crystal(new ClientboundCustomPayloadPacket(NativeJadePackets.CHANNEL,data)));
            assertEquals(0,data.readerIndex());
        } finally { data.release(); }
    }
    @Test void requestContainsOnlyTheInstalledReadOnlyTooltipWireFieldsAndNoFakeItem() {
        BlockHitResult hit=new BlockHitResult(new Vec3(640.5,64.5,1570),Direction.NORTH,new BlockPos(640,64,1570),false);
        var request=NativeJadePackets.request(hit,179);
        assertEquals(NativeJadePackets.CHANNEL,request.getIdentifier());
        FriendlyByteBuf data=request.getData();
        try {
            assertEquals(3,data.readUnsignedByte());assertFalse(data.readBoolean());
            BlockHitResult decoded=data.readBlockHitResult();
            assertEquals(hit.getBlockPos(),decoded.getBlockPos());assertEquals(hit.getDirection(),decoded.getDirection());
            assertEquals(hit.getLocation(),decoded.getLocation());assertEquals(179,data.readVarInt());
            assertFalse(data.readBoolean(),"The fake-block ItemStack must be empty");assertFalse(data.isReadable());
        } finally { data.release(); }
        assertThrows(IllegalArgumentException.class,()->NativeJadePackets.request(null,1));
        assertThrows(IllegalArgumentException.class,()->NativeJadePackets.request(hit,-1));
    }
}
