package dev.schwalbe.autovalley.core;

import java.util.Map;

/** Explicit operator reconciliation, never inferred from drops disappearing or leaving loaded chunks. */
public final class MagnetHaulRecovery {
    private MagnetHaulRecovery() { }

    public static String rejection(Context c) {
        if (!c.world().player().connected()) return "Connect to the game first";
        if (!c.profile().pendingMachineOutputs.isEmpty()) return "Resolve the separate machine-output record first";
        MenuData menu=c.world().menu();
        if (menu==null || menu.container() || !menu.carried().empty()) return "Close the container and empty the cursor first";
        if (c.world().inventory().stream().anyMatch(s -> harvestItem(s.item())))
            return "Tomatoes or rotten tomatoes remain in the inventory";
        if (c.world().groundItems().stream().anyMatch(g -> harvestItem(g.item())))
            return "Tomatoes or rotten tomatoes are still visible on the ground";
        return null;
    }

    /** Called only after the user explicitly confirms handling the haul, not by a scheduler. */
    public static void acknowledgeByUser(Context c) {
        String rejected=rejection(c);
        if (rejected!=null) throw new IllegalStateException(rejected);
        c.session().lastManuallyResolvedHaul=Map.copyOf(c.session().magnetHaulRemaining);
        c.session().magnetHaulRemaining.clear();
        c.session().magnetHaulPending=false;
    }

    private static boolean harvestItem(ItemData item) {
        return item.is(ItemData.TOMATO) || item.is(ItemData.ROTTEN);
    }
}
