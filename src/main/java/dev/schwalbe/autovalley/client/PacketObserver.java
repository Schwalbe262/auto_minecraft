package dev.schwalbe.autovalley.client;

import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.item.ItemStack;
import dev.schwalbe.autovalley.core.ItemData;
import java.util.List;
import java.util.function.BooleanSupplier;

/** No custom server channel: passively observes vanilla replies and fences attack packets. */
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
        else if (message instanceof ClientboundBlockUpdatePacket packet) later(() -> {
            var pos=MinecraftWorld.pos(packet.getPos()); observations.block(pos); recorder.blockUpdate(recordingEpoch,pos);
        });
        else if (message instanceof ClientboundSectionBlocksUpdatePacket packet)
            later(() -> packet.runUpdates((pos,state) -> {
                var observed=MinecraftWorld.pos(pos); observations.block(observed); recorder.blockUpdate(recordingEpoch,observed);
            }));
    }
    private void later(Runnable observation) {
        Minecraft.getInstance().execute(() -> { if (observations.generation()==generation) observation.run(); });
    }
    @Override public void write(ChannelHandlerContext ctx,Object message,ChannelPromise promise) throws Exception {
        if (blockAttacks.getAsBoolean()) {
            boolean destroy=message instanceof ServerboundPlayerActionPacket packet && switch (packet.getAction()) {
                case START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> true;
                default -> false;
            };
            // Auto Valley never interacts with entities; suppress all such packets while it owns control.
            if (destroy || message instanceof ServerboundInteractPacket) { promise.setSuccess(); return; }
        }
        recorder.outgoing(message);
        super.write(ctx,message,promise);
    }
}
