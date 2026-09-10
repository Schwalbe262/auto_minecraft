package dev.schwalbe.autovalley.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/** One inventory no-op requesting a genuine server FULL, never a replay of an item operation. */
final class NativeInventoryRefresh {
    final long generation,beforeSequence;
    private final NativeLoggingSwap.ReceiptLatch receipt;
    NativeInventoryRefresh(ServerObservations observations) {
        generation=observations.generation();beforeSequence=observations.sequence();
        receipt=new NativeLoggingSwap.ReceiptLatch(generation,beforeSequence);
    }
    void send(Minecraft mc) {
        if(mc.player==null || mc.getConnection()==null || mc.screen!=null
            || mc.player.containerMenu!=mc.player.inventoryMenu || mc.player.inventoryMenu.getClass()!=InventoryMenu.class
            || !validFull(mc.player.inventoryMenu.containerId,mc.player.inventoryMenu.slots.size(),mc.player.inventoryMenu.getCarried().isEmpty()))
            throw new IllegalStateException("Inventory refresh requires the ordinary closed empty-cursor menu");
        // Forge 47.4 / vanilla 1.20.1: PICKUP at -1 returns before touching any
        // slot or item hook. It is NOT -999 (outside/drop). State -1 causes the
        // normal handler to broadcastFullState after the no-op. No prediction.
        mc.getConnection().send(new ServerboundContainerClickPacket(0,-1,-1,0,ClickType.PICKUP,
            mc.player.inventoryMenu.getCarried().copy(),new Int2ObjectOpenHashMap<>()));
    }
    boolean confirmed(ServerObservations observations) {
        return receipt.confirmed(observations.generation(),()->observations.fullNativeMenuSnapshotsSince(0,beforeSequence).stream()
            .filter(full->validFull(0,full.items().size(),full.carried().isEmpty()))
            .mapToLong(ServerObservations.NativeMenuSnapshot::seq).findFirst().orElse(-1));
    }
    static boolean validFull(int menuId,int size,boolean emptyCursor) { return menuId==0 && size==46 && emptyCursor; }
}
