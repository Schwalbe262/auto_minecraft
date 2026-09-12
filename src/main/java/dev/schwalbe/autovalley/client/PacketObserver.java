package dev.schwalbe.autovalley.client;

import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.item.ItemStack;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Passively observes vanilla/installed TreeChop and SnowRealMagic replies; never adds a server channel. */
public final class PacketObserver extends ChannelDuplexHandler {
    private final ServerObservations observations;
    private final BooleanSupplier blockAttacks;
    private final ClientRecorder recorder;
    private final long generation;
    private PacketObserver(ServerObservations observations,BooleanSupplier blockAttacks,ClientRecorder recorder) {
        this.observations=observations; this.blockAttacks=blockAttacks; this.generation=observations.generation();
        this.recorder=recorder;
    }
    public static void install(Connection connection,ServerObservations observations,BooleanSupplier blockAttacks,ClientRecorder recorder) {
        var channel=connection.channel();
        if (channel==null) return;
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get("autovalley_observer")!=null) channel.pipeline().remove("autovalley_observer");
            channel.pipeline().addBefore("packet_handler","autovalley_observer",new PacketObserver(observations,blockAttacks,recorder));
        });
    }
    @Override public void channelRead(ChannelHandlerContext ctx,Object message) throws Exception {
        long recordingEpoch=recorder.captureEpoch();
        // Vanilla installs packet stacks in live menus. Detach both items and cursor BEFORE
        // forwarding, so prediction/a later pickup cannot rewrite this server reply. This native
        // client-visible metadata stays in RAM; the recorder still receives only reduced ItemData.
        ServerObservations.NativeMenuSnapshot nativePacket=message instanceof ClientboundContainerSetContentPacket packet
            ? new ServerObservations.NativeMenuSnapshot(0,packet.getItems(),packet.getCarriedItem()) : null;
        List<ItemStack> nativeItems=nativePacket==null ? null : nativePacket.items();
        ItemStack carried=nativePacket==null ? null : nativePacket.carried();
        List<ItemData> fullItems=nativeItems==null ? null : nativeItems.stream().map(MinecraftWorld::item).toList();
        ItemStack changedSlot=message instanceof ClientboundContainerSetSlotPacket packet
            ? new ServerObservations.NativeMenuSnapshot(0,List.of(packet.getItem()),ItemStack.EMPTY).items().get(0) : null;
        var chop=message instanceof ClientboundCustomPayloadPacket packet ? NativeLoggingPackets.chop(packet) : null;
        var crystal=message instanceof ClientboundCustomPayloadPacket packet ? NativeJadePackets.crystal(packet) : null;
        // Parse a detached boolean from the server packet before its mutable NBT
        // can be applied to a predicted/live block entity. Unrelated types are not proof.
        Boolean snowPlant=message instanceof ClientboundBlockEntityDataPacket packet
            ? NativeSnowPlanting.packetPlant(packet.getType(),packet.getTag()) : null;
        // The vanilla listener enqueues application first; our confirmation follows on the same main thread.
        super.channelRead(ctx,message);
        if (message instanceof ClientboundContainerSetSlotPacket packet) later(() -> {
            var player=Minecraft.getInstance().player;
            if (player!=null && player.containerMenu==player.inventoryMenu && packet.getContainerId()==player.inventoryMenu.containerId)
                observations.nativeSlot(packet.getContainerId(),packet.getSlot(),changedSlot,
                    player.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList(),player.inventoryMenu.getCarried());
            else observations.menu(packet.getContainerId());
        });
        else if (message instanceof ClientboundContainerSetContentPacket packet) later(() -> {
            observations.fullMenu(packet.getContainerId(),fullItems,nativeItems,carried);
            recorder.fullMenu(recordingEpoch,packet.getContainerId(),fullItems);
        });
        else if (message instanceof ClientboundBlockChangedAckPacket packet)
            later(() -> observations.blockActionsProcessed(packet.sequence()));
        else if (message instanceof ClientboundBlockUpdatePacket packet) later(() -> {
            var pos=MinecraftWorld.pos(packet.getPos()); observations.block(pos,packet.getBlockState()); recorder.blockUpdate(recordingEpoch,pos);
        });
        else if (message instanceof ClientboundSectionBlocksUpdatePacket packet)
            later(() -> packet.runUpdates((pos,state) -> {
                var observed=MinecraftWorld.pos(pos); observations.block(observed,state); recorder.blockUpdate(recordingEpoch,observed);
            }));
        else if (message instanceof ClientboundBlockEntityDataPacket packet)
            later(() -> observations.snowPlant(MinecraftWorld.pos(packet.getPos()),Boolean.TRUE.equals(snowPlant)));
        if (chop!=null) later(() -> observations.chop(chop.pos(),chop.chops(),chop.originalState()));
        if (crystal!=null) later(() -> observations.crystal(crystal.pos(),crystal.blockEntityId(),crystal.inputId()));
    }
    private void later(Runnable observation) {
        Minecraft.getInstance().execute(() -> { if (observations.generation()==generation) observation.run(); });
    }
    @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) throws Exception {
        boolean destroy=message instanceof ServerboundPlayerActionPacket packet && switch (packet.getAction()) {
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> true;
                default -> false;
            };
        boolean admitted=destroy && observations.consumeLoggingPacket(message,generation);
        if (blockAttacks.getAsBoolean()) {
            // Auto Valley never interacts with entities; suppress all such packets while it owns control.
            if (destroy && !admitted || message instanceof ServerboundInteractPacket) { promise.setSuccess(); return; }
        }
        recorder.outgoing(message);
        super.write(ctx,message,promise);
        // Suppression returned above; a successful caller-side send alone is not
        // admission. Retain the exact packet only after the actual write succeeds.
        if(destroy && !promise.isVoid())promise.addListener(sent -> {
            if(sent.isSuccess())later(() -> observations.loggingPacketForwarded(message));
        });
    }
}
