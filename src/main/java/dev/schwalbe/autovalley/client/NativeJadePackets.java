package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.CrystalCollection;
import dev.schwalbe.autovalley.core.Pos;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;

/** Pinned installed Jade 11.13.2 Forge protocol 2; these are its ordinary tooltip packets. */
final class NativeJadePackets {
    static final ResourceLocation CHANNEL=new ResourceLocation("jade","networking");
    record Crystal(Pos pos,String blockEntityId,String inputId) { }

    static Crystal crystal(ClientboundCustomPayloadPacket packet) {
        if(!CHANNEL.equals(packet.getIdentifier()))return null;
        // In this pinned vanilla/Forge build getData() already returns an owned copy.
        // Release it on every path; never copy twice and leak the first buffer.
        FriendlyByteBuf copy=packet.getData();
        try {
            if(copy.readableBytes()<5 || copy.readableBytes()>65536)return null;
            // Forge SimpleChannel writes one unsigned discriminator BYTE, not a VarInt.
            if(copy.readUnsignedByte()!=0)return null;
            CompoundTag tag=copy.readNbt();
            return copy.isReadable() ? null : crystal(tag);
        } catch(RuntimeException malformed) { return null; }
        finally { copy.release(); }
    }

    static Crystal crystal(CompoundTag tag) {
        if(tag==null || !tag.contains("x",Tag.TAG_INT) || !tag.contains("y",Tag.TAG_INT)
            || !tag.contains("z",Tag.TAG_INT))return null;
        Pos pos=new Pos(tag.getInt("x"),tag.getInt("y"),tag.getInt("z"));
        String type=tag.contains("id",Tag.TAG_STRING) ? tag.getString("id") : null;
        if(type!=null && type.length()>128)type=null;
        String input=tag.contains("recipe",Tag.TAG_STRING) ? tag.getString("recipe") : null;
        // Unknown or foreign replies retain their target and invalidate an older positive reply.
        if(!CrystalCollection.MACHINE_ID.equals(type) || input!=null
            && !input.isEmpty() && !CrystalCollection.BASE_OUTPUT_IDS.contains(input))input=null;
        return new Crystal(pos,type,input);
    }

    static ServerboundCustomPayloadPacket request(BlockHitResult hit,int nativeStateId) {
        if(hit==null || nativeStateId<0 || hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK)
            throw new IllegalArgumentException("Jade inspection requires a real target hit and native state");
        FriendlyByteBuf buffer=new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeByte(3); // RequestTilePacket
        buffer.writeBoolean(false); // showDetails
        buffer.writeBlockHitResult(hit);
        buffer.writeVarInt(nativeStateId);
        buffer.writeBoolean(false); // FriendlyByteBuf.writeItem(ItemStack.EMPTY): no fake block
        return new ServerboundCustomPayloadPacket(CHANNEL,buffer);
    }
}
