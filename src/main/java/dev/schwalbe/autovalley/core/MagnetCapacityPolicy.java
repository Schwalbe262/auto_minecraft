package dev.schwalbe.autovalley.core;

import java.util.List;
import java.util.Map;

/** User-selected capacity policy for ephemeral magnet tracking, never a delivery or output acknowledgement. */
public final class MagnetCapacityPolicy {
    public static final int SETTLE_TICKS=20;
    private MagnetCapacityPolicy() { }

    /** Call once per connected client tick, before controls/consumers, including while automation is paused. */
    public static boolean observe(Context context) {
        SessionState session=context.session();
        WorldAccess world=context.world();
        long tick=world.tick();
        PlayerState player=world.player();
        if (player==null || !player.connected()) return reset(session,tick,"disconnected");
        if (!session.magnetHaulPending && session.magnetHaulRemaining.isEmpty()) return reset(session,tick,"no_ephemeral_haul");
        MenuData menu=world.menu();
        if (menu==null || menu.carried()==null || !menu.carried().empty()) return reset(session,tick,"cursor_not_empty_or_unavailable");
        List<ItemSlot> inventory=world.inventory();
        boolean free=inventory!=null && inventory.stream().anyMatch(slot -> slot!=null && slot.player()
            && slot.inventoryIndex()>=0 && slot.inventoryIndex()<36 && slot.item()!=null && slot.item().empty());
        if (!free) return reset(session,tick,"normal_inventory_full_or_unavailable");
        if (tick==session.magnetCapacityObservedTick) return false;
        boolean continuous=session.magnetCapacityObservedTick!=Long.MIN_VALUE
            && session.magnetCapacityObservedTick!=Long.MAX_VALUE && tick==session.magnetCapacityObservedTick+1;
        session.magnetFreeCapacityTicks=continuous ? session.magnetFreeCapacityTicks+1 : 1;
        session.magnetCapacityObservedTick=tick;
        session.magnetCapacityStatus="free_capacity_settling_"+session.magnetFreeCapacityTicks+"_of_"+SETTLE_TICKS;
        if (session.magnetFreeCapacityTicks<SETTLE_TICKS) return false;
        session.lastCapacityReleasedHaul=Map.copyOf(session.magnetHaulRemaining);
        session.lastCapacityReleaseTick=tick;
        session.magnetHaulRemaining.clear();
        session.magnetHaulPending=false;
        session.magnetFreeCapacityTicks=0;
        session.magnetCapacityStatus="free_capacity_released_ephemeral_tracking_not_delivery";
        return true;
    }

    private static boolean reset(SessionState session,long tick,String status) {
        session.magnetCapacityObservedTick=tick;
        session.magnetFreeCapacityTicks=0;
        session.magnetCapacityStatus=status;
        return false;
    }
}
