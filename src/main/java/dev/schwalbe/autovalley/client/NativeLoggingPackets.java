package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.Pos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/** Pinned TreeChop Forge protocol 7: discriminator BYTE, BlockPos, CompoundTag. */
final class NativeLoggingPackets {
    record Chop(Pos pos,int chops,int originalState) { }
    static Chop chop(ClientboundCustomPayloadPacket packet) {
        if (!new ResourceLocation("minecraft","treechop-channel").equals(packet.getIdentifier())) return null;
        FriendlyByteBuf original=packet.getData();
        if (original.readableBytes()<10 || original.readableBytes()>8192) return null;
        FriendlyByteBuf copy=new FriendlyByteBuf(original.copy());
        try {
            if (copy.readUnsignedByte()!=(new ResourceLocation("treechop","server_update_chops").hashCode()&255)) return null;
            Pos pos=MinecraftWorld.pos(copy.readBlockPos()); var tag=copy.readNbt();
            if (copy.isReadable() || tag==null || !tag.contains("Chops",Tag.TAG_INT) || !tag.contains("OriginalState",Tag.TAG_INT)) return null;
            int chops=tag.getInt("Chops"),state=tag.getInt("OriginalState");
            return chops>=0 && chops<=1024 && state>=0 ? new Chop(pos,chops,state) : null;
        } catch (RuntimeException malformed) { return null; }
        finally { copy.release(); }
    }
}
