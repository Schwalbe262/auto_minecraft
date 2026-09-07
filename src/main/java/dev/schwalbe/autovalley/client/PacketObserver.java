package dev.schwalbe.autovalley.client;

import io.netty.channel.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.*;
import java.util.function.BooleanSupplier;

/** No custom server channel: passively observes vanilla replies and fences attack packets. */
public final class PacketObserver extends ChannelDuplexHandler {
    private final ServerObservations observations;
    private final BooleanSupplier blockAttacks;
    private final long generation;
    private PacketObserver(ServerObservations observations,BooleanSupplier blockAttacks) {
        this.observations=observations; this.blockAttacks=blockAttacks; this.generation=observations.generation();
    }
    public static void install(Connection connection,ServerObservations observations,BooleanSupplier blockAttacks) {
        var channel=connection.channel();
        if (channel==null) return;
        channel.eventLoop().execute(() -> {
            if (channel.pipeline().get("autovalley_observer")!=null) channel.pipeline().remove("autovalley_observer");
            channel.pipeline().addBefore("packet_handler","autovalley_observer",new PacketObserver(observations,blockAttacks));
        });
    }
    @Override public void channelRead(ChannelHandlerContext ctx,Object message) throws Exception {
        // The vanilla listener enqueues application first; our confirmation follows on the same main thread.
        super.channelRead(ctx,message);
        if (message instanceof ClientboundContainerSetSlotPacket packet) later(() -> observations.menu(packet.getContainerId()));
        else if (message instanceof ClientboundContainerSetContentPacket packet) later(() -> observations.fullMenu(packet.getContainerId()));
        else if (message instanceof ClientboundBlockUpdatePacket packet) later(() -> observations.block(MinecraftWorld.pos(packet.getPos())));
        else if (message instanceof ClientboundSectionBlocksUpdatePacket packet)
            later(() -> packet.runUpdates((pos,state) -> observations.block(MinecraftWorld.pos(pos))));
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
        super.write(ctx,message,promise);
    }
}
