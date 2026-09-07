package dev.schwalbe.autovalley.core;

/** The complete allow-list of game mutations. There is deliberately no attack API. */
public final class SafetyPolicy {
    private SafetyPolicy() { }

    public static String rejection(Action action, Context context) {
        WorldAccess world = context.world();
        Profile profile = context.profile();
        PlayerState player = world.player();
        if (!player.connected() || (!profile.allowBackground && !player.focused())) return "Game is not connected or requires focus";
        MenuData menu = world.menu();
        if (menu == null || !menu.carried().empty()) return "Resolve the item on the cursor before resuming";
        if (action instanceof Action.UseBlock use) {
            if (menu.container()) return "Close the current container first";
            if (!world.loaded(use.pos())) return "Target chunk is not loaded";
            if (!world.canInteract(use.pos(),4.0)) return "Target is outside normal reach or line of sight";
            BlockData block = world.block(use.pos());
            if (block == null) return "Target is unavailable";
            return switch (use.purpose()) {
                case HARVEST -> {
                    ItemData held = held(world);
                    boolean inFarm = profile.farms.stream().anyMatch(f -> f.contains(use.pos()));
                    yield !context.session().allows(profile,Feature.HARVEST) || !inFarm || !block.matureTomato() || !held.hoe()
                        || held.durability()<=1 || player.selectedSlot()!=profile.hoeHotbarSlot
                        ? "Harvest requires a mature registered tomato and the selected usable hoe" : HarvestSafety.rejection(context,use.pos());
                }
                case MACHINE -> {
                    boolean wine = block.id().equals("society:wine_keg");
                    PoiKind kind = wine ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR;
                    Feature feature = wine ? Feature.WINE : Feature.PRESERVES;
                    ItemData held = held(world);
                    int cost = wine || block.flag("upgraded") ? 3 : 5;
                    boolean known = wine || block.id().equals("society:preserves_jar");
                    boolean validHand = held.is(ItemData.TOMATO) && held.count() >= cost
                        || block.flag("mature") && held.empty();
                    yield !known || !context.session().allows(profile,feature) || !registered(profile,use.pos(),kind)
                        || block.flag("working") && !block.flag("mature") || !validHand ? "Machine, ingredient, or batch is not ready" : null;
                }
                case OPEN_CONTAINER -> profile.pois.stream().noneMatch(p -> p.pos().equals(use.pos())
                    && (p.kind()==PoiKind.TOMATO_CHEST || p.kind()==PoiKind.WINE_CHEST || p.kind()==PoiKind.SHIPPING_BIN))
                    ? "Container is not registered" : null;
                case SLEEP -> !context.session().allows(profile,Feature.SLEEP) || !registered(profile,use.pos(),PoiKind.BED)
                    || !block.id().endsWith("_bed") ? "Bed is not registered" : null;
                case DOOR -> !block.id().endsWith("_door") || block.id().equals("minecraft:iron_door")
                    ? "Only normal wooden doors may be opened" : null;
            };
        }
        if (action instanceof Action.SelectHotbar select)
            return select.slot()<0 || select.slot()>8 ? "Invalid hotbar slot" : null;
        if (action instanceof Action.SwapHotbar swap)
            return menu.container() || swap.inventoryIndex()<0 || swap.inventoryIndex()>35 || swap.hotbarSlot()<0 || swap.hotbarSlot()>8
                ? "Invalid inventory swap" : null;
        if (action instanceof Action.ConsolidateInventory merge) {
            var plan=merge.plan();
            if (MachineOutputLedger.hasPending(context)) return "Verify pending production output before consolidating inventory";
            if (menu.container() || plan==null || !context.session().allows(profile,plan.feature())
                || plan.feature()!=Feature.WINE && plan.feature()!=Feature.PRESERVES)
                return "Inventory consolidation is outside the selected production job";
            if (plan.sourceIndex()<0 || plan.sourceIndex()>=36 || !ProductionMergePlanner.matchesSnapshot(plan,world.inventory()))
                return "Inventory consolidation snapshot is stale";
            var expected=ItemData.TOMATO.equals(plan.itemId())
                ? ProductionMergePlanner.planTomatoes(world.inventory(),plan.feature(),profile.hoeHotbarSlot,
                    plan.expectedItems().get(plan.sourceIndex()).quality(),plan.sourceIndex())
                : ProductionMergePlanner.plan(world.inventory(),plan.feature(),profile.hoeHotbarSlot,plan.sourceIndex());
            return expected.isEmpty() || !expected.get().equals(plan) ? "Inventory consolidation plan changed or is unsafe" : null;
        }
        if (action instanceof Action.QuickMove move) {
            if (!menu.container() || menu.id()!=move.containerId()) return "Container changed";
            ItemSlot slot = menu.slot(move.slot());
            if (slot==null || slot.item().empty()) return "Transfer slot is empty";
            String id=slot.item().id();
            if (!id.equals(ItemData.TOMATO) && !id.equals(ItemData.WINE) && !id.equals(ItemData.PRESERVES)) return "Item is outside automation scope";
            if (!slot.player() && !id.equals(ItemData.TOMATO)) return "Only tomatoes may be withdrawn from storage";
            return null;
        }
        if (action instanceof Action.ThrowRotten drop) {
            if (menu.id()!=drop.containerId() || menu.container() || !registered(profile,drop.disposal(),PoiKind.DISPOSAL)
                || !world.canInteract(drop.disposal(),1.25)) return "Not at the registered disposal point";
            Look facing=profile.disposalDirections.get(Profile.positionKey(drop.disposal()));
            if (facing==null || !Float.isFinite(facing.yaw()) || !Float.isFinite(facing.pitch()) || Math.abs(facing.pitch())>90)
                return "Register the disposal direction first";
            ItemSlot slot=menu.slot(drop.slot());
            return !context.session().allows(profile,Feature.DISPOSAL) || slot==null || !slot.player() || !slot.item().is(ItemData.ROTTEN)
                ? "Only rotten tomatoes may be discarded" : null;
        }
        if (action instanceof Action.CloseContainer close)
            return !menu.container() || close.containerId()!=menu.id() ? "Container changed" : null;
        return "Action is not allowed";
    }

    public static ItemData held(WorldAccess world) {
        int selected=world.player().selectedSlot();
        return world.inventory().stream().filter(s -> s.inventoryIndex()==selected).map(ItemSlot::item).findFirst().orElse(ItemData.EMPTY);
    }
    public static boolean registered(Profile profile, Pos pos, PoiKind kind) {
        return profile.pois.stream().anyMatch(p -> p.kind()==kind && p.pos().equals(pos));
    }
}
