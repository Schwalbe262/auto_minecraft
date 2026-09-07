package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.ProfileBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.*;

public final class MinecraftActions implements ActionPort {
    private final Minecraft mc=Minecraft.getInstance();
    private final MinecraftWorld world;
    private final ServerObservations observations;
    private Context context;
    private boolean enabled;
    private long nextTicket, pendingTicket, started, beforeSequence;
    private Action pending;
    private MenuData beforeMenu;
    private List<ItemSlot> beforeInventory;
    private BlockData beforeBlock;
    private Movement movement;
    private long movementAt;
    private Pos ownedContainer;
    private int ownedMenu=-1;
    private final LinkedHashMap<Long,ActionOutcome> outcomes=new LinkedHashMap<>();
    public MinecraftActions(MinecraftWorld world,ServerObservations observations) { this.world=world; this.observations=observations; }
    public void context(Context context) { this.context=context; }
    public void enabled(boolean enabled) { this.enabled=enabled; if (!enabled) stopMovement(); }
    public boolean busy() { return pending!=null; }
    public Movement movement() { return enabled && world.tick()-movementAt<=2 ? movement : null; }
    public boolean ownsContainer() { return world.menu()!=null && world.menu().container() && world.menu().id()==ownedMenu; }
    public boolean openingContainer() { return pending instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER; }
    public boolean expectingSleep() { return pending instanceof Action.UseBlock use && use.purpose()==Action.Use.SLEEP; }
    public long submit(Action action) {
        long ticket=++nextTicket;
        if (!enabled || context==null) { put(ticket,ActionOutcome.State.CANCELLED,"Automation is paused"); return ticket; }
        if (pending!=null) { put(ticket,ActionOutcome.State.FAILED,"Another action is still awaiting the server"); return ticket; }
        String rejection=SafetyPolicy.rejection(action,context);
        if (rejection==null && action instanceof Action.QuickMove transfer) rejection=transferRejection(transfer);
        if (rejection!=null) { put(ticket,ActionOutcome.State.FAILED,rejection); return ticket; }
        stopMovement();
        pendingTicket=ticket; pending=action; started=world.tick(); beforeSequence=observations.sequence();
        beforeMenu=world.menu(); beforeInventory=world.inventory();
        beforeBlock=action instanceof Action.UseBlock use ? world.block(use.pos()) : null;
        put(ticket,ActionOutcome.State.PENDING,"");
        try { execute(action); }
        catch (RuntimeException e) { finish(ActionOutcome.State.FAILED,"Game rejected the action: " + e.getClass().getSimpleName()); }
        return ticket;
    }
    private String transferRejection(Action.QuickMove transfer) {
        if (!ownsContainer() || ownedContainer==null) return "This container was not opened by automation";
        Poi poi=context.profile().pois.stream().filter(p -> p.pos().equals(ownedContainer)).findFirst().orElse(null);
        if (poi==null) return "Container registration changed";
        ItemSlot source=world.menu().slot(transfer.slot());
        ItemData item=source.item();
        boolean kindMatches=switch (poi.kind()) {
            case TOMATO_CHEST -> item.is(ItemData.TOMATO) && poi.classifier()!=null && item.quality()==poi.classifier();
            case WINE_CHEST -> source.player() && item.is(ItemData.WINE) && item.year()!=null && item.year().equals(poi.classifier());
            case SHIPPING_BIN -> source.player() && item.is(ItemData.PRESERVES);
            default -> false;
        };
        if (!kindMatches) return "Item does not match the registered container classification";
        if (source.player() && world.menu().slots().stream().noneMatch(s -> !s.player() && world.mayPlace(s.index(),item)))
            return "No eligible input slot in this container";
        return null;
    }
    private void execute(Action action) {
        if (action instanceof Action.UseBlock use) {
            var hit=world.hit(use.pos(),mc.player.getEyePosition());
            if (hit==null) { finish(ActionOutcome.State.FAILED,"Target no longer visible"); return; }
            lookAt(hit.getLocation());
            var result=mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
            if (result.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
            // PASS does not trigger useItem(): eating/air-use is never a fallback.
        } else if (action instanceof Action.SelectHotbar select) {
            mc.player.getInventory().selected=select.slot();
        } else if (action instanceof Action.SwapHotbar swap) {
            int source=mc.player.inventoryMenu.slots.stream().filter(s -> s.container==mc.player.getInventory() && s.getContainerSlot()==swap.inventoryIndex()).map(s -> s.index).findFirst().orElseThrow();
            if (swap.inventoryIndex()==swap.hotbarSlot()) { finish(ActionOutcome.State.SUCCEEDED,"Already in hotbar"); return; }
            confirmedClick(mc.player.inventoryMenu.containerId,source,swap.hotbarSlot(),ClickType.SWAP);
        } else if (action instanceof Action.QuickMove transfer) {
            confirmedClick(transfer.containerId(),transfer.slot(),0,ClickType.QUICK_MOVE);
        } else if (action instanceof Action.ThrowRotten drop) {
            Look facing=context.profile().disposalDirections.get(Profile.positionKey(drop.disposal()));
            mc.player.setYRot(facing.yaw()); mc.player.setXRot(facing.pitch());
            // Rotation reaches the server before the one discard click on the same connection.
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(facing.yaw(),facing.pitch(),mc.player.onGround()));
            confirmedClick(drop.containerId(),drop.slot(),1,ClickType.THROW);
        } else if (action instanceof Action.CloseContainer) {
            mc.player.closeContainer();
            ownedMenu=-1; ownedContainer=null;
        }
    }
    private void confirmedClick(int containerId,int slot,int button,ClickType type) {
        // A matching predicted vanilla click may produce NO reply. Send one unpredicted
        // normal click with an old state id: vanilla executes it once and returns the
        // full authoritative menu. Never replay a timed-out click or fake an ACK.
        mc.getConnection().send(new ServerboundContainerClickPacket(containerId,-1,slot,button,type,
            mc.player.containerMenu.getCarried().copy(),new Int2ObjectOpenHashMap<>()));
    }
    public void tick() {
        if (pending==null) return;
        if (!enabled || mc.player==null || !mc.isWindowActive()) { cancel(); return; }
        if (world.tick()==started) return;
        MenuData menu=world.menu();
        if (pending instanceof Action.UseBlock use) {
            switch (use.purpose()) {
                case OPEN_CONTAINER -> {
                    if (menu.container() && menu.id()!=beforeMenu.id() && observations.fullMenuSince(menu.id(),beforeSequence)) {
                        ownedMenu=menu.id(); ownedContainer=canonicalContainer(use.pos()); finish(ActionOutcome.State.SUCCEEDED,"Container synchronized"); return;
                    }
                }
                case SLEEP -> { if (mc.player.isSleeping()) { finish(ActionOutcome.State.SUCCEEDED,"Entered bed"); return; } }
                case HARVEST, MACHINE, DOOR -> {
                    boolean blockChanged=!world.block(use.pos()).equals(beforeBlock);
                    boolean inventoryChanged=!world.inventory().equals(beforeInventory);
                    if ((blockChanged && observations.blockSince(use.pos(),beforeSequence))
                        || use.purpose()==Action.Use.MACHINE && inventoryChanged && observations.menuSince(menu.id(),beforeSequence)) {
                        finish(ActionOutcome.State.SUCCEEDED,"Server confirmed interaction"); return;
                    }
                }
            }
            if (use.purpose()!=Action.Use.OPEN_CONTAINER && menu.id()!=beforeMenu.id()) { finish(ActionOutcome.State.FAILED,"Unexpected container opened"); return; }
        } else if (pending instanceof Action.SelectHotbar select) {
            if (mc.player.getInventory().selected==select.slot()) { finish(ActionOutcome.State.SUCCEEDED,"Hotbar selected"); return; }
        } else if (pending instanceof Action.CloseContainer) {
            if (!menu.container()) { finish(ActionOutcome.State.SUCCEEDED,"Container closed"); return; }
        } else {
            if (menu.id()!=beforeMenu.id()) { finish(ActionOutcome.State.FAILED,"Container changed while waiting"); return; }
            if (observations.fullMenuSince(menu.id(),beforeSequence) && (!world.inventory().equals(beforeInventory) || !menu.slots().equals(beforeMenu.slots()))) {
                if (!menu.carried().empty()) { finish(ActionOutcome.State.FAILED,"Unexpected item on cursor"); return; }
                finish(ActionOutcome.State.SUCCEEDED,"Server confirmed inventory change"); return;
            }
        }
        if (world.tick()-started>=context.profile().interactionTimeoutTicks) finish(ActionOutcome.State.FAILED,"No server confirmation; inspect before retrying");
    }
    public Pos canonicalContainer(Pos pos) {
        if (mc.level==null) return pos;
        var state=mc.level.getBlockState(MinecraftWorld.nativePos(pos));
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
            Pos other=MinecraftWorld.pos(MinecraftWorld.nativePos(pos).relative(ChestBlock.getConnectedDirection(state)));
            if (other.x()<pos.x() || other.x()==pos.x() && (other.y()<pos.y() || other.y()==pos.y() && other.z()<pos.z())) return other;
        }
        return pos;
    }
    private void lookAt(Vec3 target) {
        Vec3 delta=target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90));
        mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));
    }
    public void move(Movement intent) {
        if (!enabled || pending!=null || mc.player==null || world.menu().container() || !mc.isWindowActive()) { stopMovement(); return; }
        // Navigation cannot gain permission to jump or leave the approved farm/corridor.
        Pos feet=world.player().feet();
        if (!ProfileBounds.contains(context.profile(),feet) || intent.jump()) { stopMovement(); return; }
        movement=new Movement(intent.yaw(),intent.pitch(),intent.forward(),intent.sprint(),false,intent.sneak());
        movementAt=world.tick();
        mc.player.setYRot(intent.yaw()); mc.player.setXRot(intent.pitch());
        mc.player.setSprinting(intent.sprint() && intent.forward());
    }
    public void stopMovement() { movement=null; if (mc.player!=null && enabled) mc.player.setSprinting(false); }
    public void cancel() {
        stopMovement();
        if (pending!=null) finish(ActionOutcome.State.CANCELLED,"Cancelled without additional input");
        // Ownership survives pause only for diagnostics; start() requires manual closure.
    }
    public ActionOutcome outcome(long ticket) { return outcomes.getOrDefault(ticket,new ActionOutcome(ActionOutcome.State.CANCELLED,"Expired action")); }
    private void finish(ActionOutcome.State state,String message) { put(pendingTicket,state,message); pending=null; }
    private void put(long ticket,ActionOutcome.State state,String message) {
        outcomes.put(ticket,new ActionOutcome(state,message));
        if (outcomes.size()>512) outcomes.remove(outcomes.keySet().iterator().next());
    }
}
