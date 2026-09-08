package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.ProfileBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
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
    private PlayerState beforePlayer;
    private Movement movement;
    private long movementAt;
    private long movementLookAt=Long.MIN_VALUE;
    private LoggingJumpEdge loggingJumpEdge;
    private long loggingJumpGeneration,loggingJumpStarted;
    private Pos ownedContainer;
    private ContainerShape openingShape, ownedShape;
    private int ownedMenu=-1;
    private NativeInventoryConsolidation consolidation, lateInventoryReply;
    private boolean consolidationInFlight;
    private String consolidationFailure;
    private long failureGeneration;
    private NativeTrashSlot trash,lateTrashReply;
    private boolean trashInFlight;
    private String trashFailure;
    private long trashFailureGeneration;
    private NativeLoggingActions loggingAction,lateLoggingAction;
    private NativeLoggingRecipe loggingRecipe,lateLoggingRecipe;
    private NativeLoggingSwap loggingSwap,lateLoggingSwap;
    private String loggingFailure;
    private long loggingFailureGeneration;
    private final LinkedHashMap<Long,ActionOutcome> outcomes=new LinkedHashMap<>();
    public MinecraftActions(MinecraftWorld world,ServerObservations observations) { this.world=world; this.observations=observations; }
    public void context(Context context) { this.context=context; }
    public void enabled(boolean enabled) { if (!enabled) stopMovement(); this.enabled=enabled; }
    public boolean busy() { return pending!=null; }
    @Override public boolean supportsMovingHarvest() { return true; }
    @Override public boolean supportsInventoryTrash() { return NativeTrashSlot.available(); }
    public Movement movement() { return enabled && world.tick()-movementAt<=2 ? movement : null; }
    public boolean ownsContainer() {
        MenuData menu=world.menu();
        return menu!=null && menu.container() && menu.id()==ownedMenu && ownedShape!=null
            && (!ownedShape.blockId().equals("minecraft:crafting_table") || world.loggingCraftingMenu())
            && ownedShape.equals(containerShape(ownedContainer)) && ownedShape.matches(menu);
    }
    public boolean openingContainer() { return pending instanceof Action.UseBlock use && (use.purpose()==Action.Use.OPEN_CONTAINER || use.purpose()==Action.Use.OPEN_CRAFTING); }
    public boolean expectingSleep() { return pending instanceof Action.UseBlock use && use.purpose()==Action.Use.SLEEP; }
    public long submit(Action action) {
        long ticket=++nextTicket;
        if (!enabled || context==null) { put(ticket,ActionOutcome.State.CANCELLED,"Automation is paused"); return ticket; }
        if (pending!=null) { put(ticket,ActionOutcome.State.FAILED,"Another action is still awaiting the server"); return ticket; }
        String paused=pauseReason();
        if (paused!=null) { put(ticket,ActionOutcome.State.FAILED,paused); return ticket; }
        String rejection=SafetyPolicy.rejection(action,context);
        ContainerShape requestedShape=null;
        if (rejection==null && action instanceof Action.UseBlock use && (use.purpose()==Action.Use.OPEN_CONTAINER || use.purpose()==Action.Use.OPEN_CRAFTING)) {
            requestedShape=containerShape(use.pos());
            if (requestedShape==null || !requestedShape.canonical().equals(use.pos()))
                rejection="Container is unloaded, incomplete, or no longer the registered chest pair";
        }
        if (rejection==null && action instanceof Action.QuickMove transfer) rejection=transferRejection(transfer);
        if (rejection==null && action instanceof Action.CraftFireLogs craft && (!ownsContainer() || !craft.table().equals(ownedContainer)))
            rejection="Crafting table was not opened by this automation action";
        if (rejection!=null) { put(ticket,ActionOutcome.State.FAILED,rejection); return ticket; }
        stopMovement();
        pendingTicket=ticket; pending=action; started=world.tick(); beforeSequence=observations.sequence();
        openingShape=requestedShape;
        beforeMenu=world.menu(); beforeInventory=world.inventory();
        beforePlayer=world.player();
        beforeBlock=action instanceof Action.UseBlock use ? world.block(use.pos()) : null;
        put(ticket,ActionOutcome.State.PENDING,"");
        try { execute(action); }
        catch (RuntimeException e) { finish(ActionOutcome.State.FAILED,"Game rejected the action: " + e.getClass().getSimpleName()); }
        return ticket;
    }
    private String transferRejection(Action.QuickMove transfer) {
        if (context.session().oneShotFeature==Feature.STORAGE_SURVEY) return "Storage survey cannot transfer items";
        if (!ownsContainer() || ownedContainer==null) return "This container was not opened by automation";
        Poi poi=context.profile().pois.stream().filter(p -> p.pos().equals(ownedContainer)).findFirst().orElse(null);
        if (poi==null) return "Container registration changed";
        ItemSlot source=world.menu().slot(transfer.slot());
        ItemData item=source.item();
        boolean kindMatches=switch (poi.kind()) {
            case TOMATO_CHEST -> TomatoStorageRules.permitsTransfer(item,world.menu());
            case WINE_CHEST -> source.player() && item.is(ItemData.WINE) && item.year()!=null && item.year().equals(poi.classifier());
            case WOOD_CHEST -> LoggingRules.allowed(context) && source.player() && LoggingRules.wood(item);
            case SHIPPING_BIN -> source.player() && (item.standardShippingProduct() && context.session().allows(context.profile(),Feature.SHIPPING)
                || item.is(ItemData.WINE) && WineSaleRules.permitted(item,context)
                || LoggingRules.allowed(context) && LoggingRules.byproduct(item));
            default -> false;
        };
        if (!kindMatches) return "Item does not match the registered container classification";
        if (source.player() && world.menu().slots().stream().noneMatch(s -> !s.player() && world.mayPlace(s.index(),item)))
            return "No eligible input slot in this container";
        return null;
    }
    private void execute(Action action) {
        if (action instanceof Action.UseBlock use) {
            if (use.purpose()==Action.Use.OPEN_CONTAINER || use.purpose()==Action.Use.OPEN_CRAFTING) { ownedMenu=-1; ownedContainer=null; ownedShape=null; }
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
            if (context.profile().loggingRunActive) loggingSwap=new NativeLoggingSwap(mc.player,swap,observations);
            confirmedClick(mc.player.inventoryMenu.containerId,source,swap.hotbarSlot(),ClickType.SWAP);
        } else if (action instanceof Action.QuickMove transfer) {
            confirmedClick(transfer.containerId(),transfer.slot(),0,ClickType.QUICK_MOVE);
        } else if (action instanceof Action.ConsolidateInventory merge) {
            consolidation=NativeInventoryConsolidation.create(mc.player,merge.plan(),observations,context.profile().hoeHotbarSlot);
            if (consolidation==null) { finish(ActionOutcome.State.SUCCEEDED,"Native stacks cannot be consolidated",0); return; }
            sendConsolidationClick();
        } else if (action instanceof Action.TrashRotten rotten) {
            trash=new NativeTrashSlot(mc.player,rotten,observations);
            trashInFlight=true; // A send that throws may already have reached the channel: never retry it.
            trash.send();
        } else if (action instanceof Action.TrashLogging waste) {
            trash=new NativeTrashSlot(mc.player,waste,observations); trashInFlight=true; trash.send();
        } else if (action instanceof Action.ChopTree || action instanceof Action.PlantSapling) {
            loggingAction=new NativeLoggingActions(mc,world,observations,action,context);
            Pos target=action instanceof Action.ChopTree chop ? chop.pos() : ((Action.PlantSapling)action).pos();
            var hit=world.hit(action instanceof Action.PlantSapling ? target.offset(0,-1,0) : target,mc.player.getEyePosition());
            if (hit!=null) lookAt(hit.getLocation());
            loggingAction.begin(mc,observations);
        } else if (action instanceof Action.CraftFireLogs) {
            loggingRecipe=new NativeLoggingRecipe(mc,observations,world.tick());
            loggingRecipe.place(mc,() -> confirmedClick(loggingRecipe.menuId,loggingRecipe.selfSlot,loggingRecipe.selfHotbar,ClickType.SWAP));
        } else if (action instanceof Action.ThrowRotten drop) {
            Look facing=context.profile().disposalDirections.get(Profile.positionKey(drop.disposal()));
            mc.player.setYRot(facing.yaw()); mc.player.setXRot(facing.pitch());
            // Rotation reaches the server before the one discard click on the same connection.
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(facing.yaw(),facing.pitch(),mc.player.onGround()));
            confirmedClick(drop.containerId(),drop.slot(),1,ClickType.THROW);
        } else if (action instanceof Action.CloseContainer) {
            mc.player.closeContainer();
            ownedMenu=-1; ownedContainer=null; ownedShape=null;
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
        if (!enabled || mc.player==null || (!context.profile().allowBackground && !mc.isWindowActive())) { cancel(); return; }
        if (world.tick()==started) return;
        MenuData menu=world.menu();
        if (pending instanceof Action.ConsolidateInventory) { tickConsolidation(); return; }
        if (pending instanceof Action.TrashRotten || pending instanceof Action.TrashLogging) { tickTrash(); return; }
        if (loggingAction!=null) { tickLogging(); return; }
        if (loggingRecipe!=null) { tickLoggingRecipe(); return; }
        if (loggingSwap!=null) {
            if (loggingSwap.confirmed(observations)) { finish(ActionOutcome.State.SUCCEEDED,"서버가 벌목 핫바 교환을 확인했습니다."); return; }
            if (loggingSwap.generation!=observations.generation() || world.tick()-started>=context.profile().interactionTimeoutTicks)
                finish(ActionOutcome.State.FAILED,"벌목 핫바 교환의 정확한 응답을 기다리고 있습니다.");
            return;
        }
        if (pending instanceof Action.UseBlock use) {
            switch (use.purpose()) {
                case OPEN_CONTAINER, OPEN_CRAFTING -> {
                    if (menu.container() && menu.id()!=beforeMenu.id() && observations.fullMenuSince(menu.id(),beforeSequence)) {
                        ContainerShape currentShape=containerShape(use.pos());
                        if (openingShape==null || !openingShape.equals(currentShape) || !openingShape.matches(menu)
                            || use.purpose()==Action.Use.OPEN_CRAFTING && !world.loggingCraftingMenu()) {
                            finish(ActionOutcome.State.FAILED,"Container geometry changed or its complete contents were not opened"); return;
                        }
                        ownedMenu=menu.id(); ownedContainer=currentShape.canonical(); ownedShape=currentShape;
                        finish(ActionOutcome.State.SUCCEEDED,"Container synchronized"); return;
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
            if (use.purpose()!=Action.Use.OPEN_CONTAINER && use.purpose()!=Action.Use.OPEN_CRAFTING && menu.id()!=beforeMenu.id()) { finish(ActionOutcome.State.FAILED,"Unexpected container opened"); return; }
        } else if (pending instanceof Action.SelectHotbar select) {
            if (mc.player.getInventory().selected==select.slot()) { finish(ActionOutcome.State.SUCCEEDED,"Hotbar selected"); return; }
        } else if (pending instanceof Action.CloseContainer) {
            if (!menu.container()) { finish(ActionOutcome.State.SUCCEEDED,"Container closed"); return; }
        } else {
            if (menu.id()!=beforeMenu.id()) { finish(ActionOutcome.State.FAILED,"Container changed while waiting"); return; }
            for (var acknowledgement:observations.fullMenuSnapshotsSince(menu.id(),beforeSequence)) {
                if (!InventoryAcknowledgements.changed(beforeMenu,acknowledgement.items())) continue;
                if (!menu.carried().empty()) { finish(ActionOutcome.State.FAILED,"Unexpected item on cursor"); return; }
                int quantity=0;
                boolean confirmed=true;
                if (pending instanceof Action.QuickMove move) {
                    quantity=InventoryAcknowledgements.removed(beforeMenu,acknowledgement.items(),move.slot());
                    ItemSlot source=beforeMenu.slot(move.slot());
                    confirmed=quantity>0 && source!=null && (!source.player()
                        || InventoryAcknowledgements.destinationIncrease(beforeMenu,acknowledgement.items(),source.item())>=quantity);
                    Poi destination=ownedContainer==null ? null : context.profile().pois.stream().filter(p -> p.pos().equals(ownedContainer)).findFirst().orElse(null);
                    if (confirmed && destination!=null && destination.kind()==PoiKind.SHIPPING_BIN && source.item().is(ItemData.WINE))
                        WineSaleRules.consume(source.item(),quantity,context);
                } else if (pending instanceof Action.ThrowRotten drop) {
                    quantity=InventoryAcknowledgements.removed(beforeMenu,acknowledgement.items(),drop.slot());
                    confirmed=quantity>0;
                }
                if (confirmed) { finish(ActionOutcome.State.SUCCEEDED,"Server confirmed inventory change",quantity); return; }
            }
        }
        if (world.tick()-started>=context.profile().interactionTimeoutTicks) finish(ActionOutcome.State.FAILED,"No server confirmation; inspect before retrying");
    }
    private void tickLogging() {
        if (loggingAction.confirmed(observations)) { finish(ActionOutcome.State.SUCCEEDED,"서버가 벌목·식재 진행을 확인했습니다.",1); return; }
        String rejection=loggingAction.advance(mc,world,observations,context);
        if (rejection!=null) { finish(ActionOutcome.State.FAILED,rejection); return; }
        if (world.tick()-started>=context.profile().interactionTimeoutTicks)
            finish(ActionOutcome.State.FAILED,"벌목·식재의 서버 확인이 없습니다. 재전송하지 않고 멈춥니다.");
    }
    private void tickLoggingRecipe() {
        if (loggingRecipe.generation!=observations.generation() || !LoggingRules.allowed(context)
            || !ownsContainer() || !world.loggingCraftingMenu()) {
            finish(ActionOutcome.State.FAILED,"제작 중 연결·작업대 또는 권한이 바뀌었습니다. 제작 칸을 확인하세요."); return;
        }
        for (var ack:observations.fullNativeMenuSnapshotsSince(loggingRecipe.menuId,loggingRecipe.beforeSequence)) {
            int result=loggingRecipe.acknowledge(ack);
            if (result>0) { finish(ActionOutcome.State.SUCCEEDED,"서버가 장작 제작을 확인했습니다.",result); return; }
            if (result<0) {
                if (!loggingRecipe.readyToTake(mc)) {
                    finish(ActionOutcome.State.FAILED,"제작 재료 배치 후 인벤토리가 바뀌었거나 장작 공간이 없습니다. 제작 칸을 확인하세요."); return;
                }
                loggingRecipe.outputSent(observations.sequence(),world.tick());
                confirmedClick(loggingRecipe.menuId,0,0,ClickType.QUICK_MOVE); return;
            }
        }
        if (world.tick()-loggingRecipe.stepStarted>=context.profile().interactionTimeoutTicks)
            finish(ActionOutcome.State.FAILED,"제작 응답이 불확실합니다. 재료 칸을 자동으로 닫거나 다시 누르지 않습니다.");
    }
    private void sendConsolidationClick() {
        if (MachineOutputLedger.hasPending(context) || !context.session().allows(context.profile(),consolidation.plan.feature())
            || context.profile().hoeHotbarSlot!=consolidation.protectedHotbar) {
            finish(ActionOutcome.State.FAILED,"Production settings changed; no further inventory clicks were sent"); return;
        }
        if (!consolidation.matchesLive(mc.player,observations)) {
            finish(ActionOutcome.State.FAILED,"Inventory changed before the next consolidation step; inspect the layout"); return;
        }
        var click=consolidation.transaction.click();
        consolidation.beforeSequence=observations.sequence(); started=world.tick();
        consolidationInFlight=true;
        confirmedClick(consolidation.menuId,consolidation.sourceMenuSlot(),click.type()==InventoryConsolidation.Type.SWAP ? click.hotbar() : 0,
            click.type()==InventoryConsolidation.Type.SWAP ? ClickType.SWAP : ClickType.QUICK_MOVE);
    }
    private void tickConsolidation() {
        if (consolidation==null || observations.generation()!=consolidation.generation) {
            finish(ActionOutcome.State.FAILED,"Connection changed during inventory consolidation"); return;
        }
        if (mc.player.containerMenu!=mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) {
            finish(ActionOutcome.State.FAILED,"Inventory menu or cursor changed; no restoration was sent"); return;
        }
        for (var acknowledgement:observations.fullNativeMenuSnapshotsSince(consolidation.menuId,consolidation.beforeSequence)) {
            var confirmation=consolidation.acknowledge(acknowledgement);
            if (confirmation==InventoryConsolidation.Confirmation.WAIT) continue;
            consolidationInFlight=false;
            if (confirmation==InventoryConsolidation.Confirmation.COMPLETE) {
                finish(ActionOutcome.State.SUCCEEDED,"Server verified inventory consolidation",consolidation.transaction.freedSlots()); return;
            }
            // A later pickup needs exact post-ACK server slot evidence before
            // rebasing unrelated slots. An unconfirmed primitive is never replayed.
            sendConsolidationClick(); return;
        }
        if (world.tick()-started>=context.profile().interactionTimeoutTicks)
            finish(ActionOutcome.State.FAILED,"No exact consolidation acknowledgement; inspect inventory before resuming");
    }
    private void tickTrash() {
        if (trash==null || trash.generation!=observations.generation()) {
            finish(ActionOutcome.State.FAILED,"Connection changed during rotten tomato deletion"); return;
        }
        if (mc.player.containerMenu!=mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            finish(ActionOutcome.State.FAILED,"Inventory or cursor changed during deletion; no further request was sent"); return;
        }
        if (trash.confirmed(observations)) {
            int removed=trash.quantity; trashInFlight=false;
            finish(ActionOutcome.State.SUCCEEDED,"Server verified the single rotten tomato stack deletion",removed); return;
        }
        if (world.tick()-started>=context.profile().interactionTimeoutTicks)
            finish(ActionOutcome.State.FAILED,"No exact TrashSlot acknowledgement; inspect before resuming");
    }
    @Override public String pauseReason() {
        if (loggingFailureGeneration!=observations.generation()) loggingFailure=null;
        if (lateLoggingAction!=null && (lateLoggingAction.generation!=observations.generation() || lateLoggingAction.confirmed(observations))) lateLoggingAction=null;
        if (lateLoggingSwap!=null && (lateLoggingSwap.generation!=observations.generation() || lateLoggingSwap.confirmed(observations))) lateLoggingSwap=null;
        if (lateLoggingRecipe!=null) {
            if (lateLoggingRecipe.generation!=observations.generation()) lateLoggingRecipe=null;
            else for (var ack:observations.fullNativeMenuSnapshotsSince(lateLoggingRecipe.menuId,lateLoggingRecipe.beforeSequence)) {
                if (lateLoggingRecipe.acknowledge(ack)!=0) { lateLoggingRecipe=null; break; }
            }
        }
        if (lateLoggingAction!=null || lateLoggingSwap!=null || lateLoggingRecipe!=null)
            return "벌목 작업의 이전 서버 응답을 기다립니다. 확인 전 재실행하지 않습니다. 응답이 없으면 재접속하세요.";
        if (loggingFailure!=null) return loggingFailure;
        if (trashFailureGeneration!=observations.generation()) trashFailure=null;
        if (lateTrashReply!=null) {
            if (lateTrashReply.generation!=observations.generation()) lateTrashReply=null;
            else if (lateTrashReply.confirmed(observations)) {
                lateTrashReply=null;
            }
        }
        if (lateTrashReply!=null) return "Waiting for an unconfirmed TrashSlot request's exact server reply; no new actions. Reconnect if it never arrives.";
        if (trashFailure!=null) return trashFailure;
        if (failureGeneration!=observations.generation()) consolidationFailure=null;
        if (lateInventoryReply!=null) {
            if (lateInventoryReply.generation!=observations.generation()) lateInventoryReply=null;
            else for (var acknowledgement:observations.fullNativeMenuSnapshotsSince(lateInventoryReply.menuId,lateInventoryReply.beforeSequence)) {
                if (lateInventoryReply.acknowledge(acknowledgement)!=InventoryConsolidation.Confirmation.WAIT) { lateInventoryReply=null; break; }
            }
        }
        return lateInventoryReply!=null ? "Waiting for an unconfirmed inventory click's exact server reply; no new actions. Reconnect if it never arrives."
            : consolidationFailure;
    }
    @Override public String startRejection() {
        pauseReason();
        if (lateInventoryReply!=null || lateTrashReply!=null || lateLoggingAction!=null || lateLoggingSwap!=null || lateLoggingRecipe!=null) return pauseReason();
        consolidationFailure=null;
        trashFailure=null;
        loggingFailure=null;
        return null;
    }
    private void finishConsolidation(ActionOutcome.State state,String message) {
        if (!(pending instanceof Action.ConsolidateInventory)) return;
        if (consolidationInFlight && consolidation!=null) lateInventoryReply=consolidation;
        if (state==ActionOutcome.State.FAILED) { consolidationFailure=message; failureGeneration=observations.generation(); }
        consolidation=null; consolidationInFlight=false;
    }
    private void finishTrash(ActionOutcome.State state,String message) {
        if (!(pending instanceof Action.TrashRotten) && !(pending instanceof Action.TrashLogging)) return;
        if (trashInFlight && trash!=null) lateTrashReply=trash;
        if (state==ActionOutcome.State.FAILED) { trashFailure=message; trashFailureGeneration=observations.generation(); }
        trash=null; trashInFlight=false;
    }
    /** Null means unsafe, not a single chest fallback: both halves must be observable and reciprocal. */
    public Pos canonicalContainer(Pos pos) {
        ContainerShape shape=containerShape(pos);
        return shape==null ? null : shape.canonical();
    }
    private record ContainerShape(Pos canonical,String blockId,Map<Pos,BlockState> chestStates,int chestSlots) {
        private ContainerShape { chestStates=Map.copyOf(chestStates); }
        boolean matches(MenuData menu) {
            if (blockId.equals("minecraft:crafting_table")) return menu.slots().size()==46
                && menu.slots().stream().filter(s -> !s.player()).count()==10
                && menu.slots().stream().filter(ItemSlot::player).map(ItemSlot::inventoryIndex).distinct().count()==36;
            if (SmartShippingRules.BLOCK_ID.equals(blockId))
                return chestSlots==SmartShippingRules.STORAGE_SLOTS && SmartShippingRules.matchesMenu(menu);
            return chestSlots==0 || menu.slots().stream().filter(s -> !s.player()).count()==chestSlots;
        }
    }
    private ContainerShape containerShape(Pos pos) {
        if (pos==null || mc.level==null || !world.loaded(pos)) return null;
        BlockData block=world.block(pos);
        if (block.id().equals("minecraft:crafting_table"))
            return new ContainerShape(pos,block.id(),Map.of(pos,mc.level.getBlockState(MinecraftWorld.nativePos(pos))),10);
        if (!block.flag("container")) return null;
        var state=mc.level.getBlockState(MinecraftWorld.nativePos(pos));
        if (SmartShippingRules.BLOCK_ID.equals(block.id())) {
            int slots=NativeSmartShippingInventory.expectedSlots(block.id(),mc.level.getBlockEntity(MinecraftWorld.nativePos(pos)));
            return slots==SmartShippingRules.STORAGE_SLOTS ? new ContainerShape(pos,block.id(),Map.of(pos,state),slots) : null;
        }
        if (!(state.getBlock() instanceof ChestBlock)) return new ContainerShape(pos,block.id(),Map.of(),0);
        if (state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)
            return new ContainerShape(pos,block.id(),Map.of(pos,state),27);
        var nativeOther=MinecraftWorld.nativePos(pos).relative(ChestBlock.getConnectedDirection(state));
        Pos other=MinecraftWorld.pos(nativeOther);
        if (!world.loaded(other) || !world.block(other).flag("container")) return null;
        var paired=mc.level.getBlockState(nativeOther);
        if (paired.getBlock()!=state.getBlock() || !paired.hasProperty(ChestBlock.TYPE)
            || paired.getValue(ChestBlock.TYPE)==ChestType.SINGLE || paired.getValue(ChestBlock.TYPE)==state.getValue(ChestBlock.TYPE)
            || paired.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING)
            || !nativeOther.relative(ChestBlock.getConnectedDirection(paired)).equals(MinecraftWorld.nativePos(pos))) return null;
        Pos canonical=other.x()<pos.x() || other.x()==pos.x() && (other.y()<pos.y() || other.y()==pos.y() && other.z()<pos.z()) ? other : pos;
        return new ContainerShape(canonical,block.id(),Map.of(pos,state,other,paired),54);
    }
    private void lookAt(Vec3 target) {
        Vec3 delta=target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float)(Math.toDegrees(Math.atan2(delta.z,delta.x))-90));
        mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.sqrt(delta.x*delta.x+delta.z*delta.z))));
    }
    public void move(Movement intent) {
        clearLoggingJump();
        boolean harvesting=pending instanceof Action.UseBlock use && use.purpose()==Action.Use.HARVEST;
        if (!enabled || intent==null || !Float.isFinite(intent.yaw()) || !Float.isFinite(intent.pitch())
            || pending!=null && !harvesting || pauseReason()!=null || mc.player==null || world.menu().container()
            || (!context.profile().allowBackground && !mc.isWindowActive())) { stopMovement(); return; }
        // Only a short grounded harvest continuation can overlap a server ACK. Inventory,
        // machine, door and sleep operations continue to exclude all movement.
        if (harvesting && !HarvestMovementRules.mayOverlap(pending,intent,beforePlayer,world.player(),world.tick()-started,
            context.profile().continueHarvestWhenFull && context.session().allows(context.profile(),Feature.HARVEST))) { stopMovement(); return; }
        // Navigation cannot gain permission to jump or leave the approved farm/corridor.
        Pos feet=NavigationFeet.resolve(world,world.player());
        if (!ProfileBounds.contains(context.profile(),feet) || intent.jump()) { stopMovement(); return; }
        if (!Float.isFinite(mc.player.getYRot()) || !Float.isFinite(mc.player.getXRot())) { stopMovement(); return; }
        movement=new Movement(intent.yaw(),intent.pitch(),intent.forward(),intent.sprint(),false,intent.sneak());
        movementAt=world.tick();
        if (!harvesting && movementLookAt!=world.tick()) {
            // Easing affects the view only. MovementAxes preserves the intended
            // world direction even while the camera catches up with a corner.
            // Explicit useBlock lookAt remains exact; pending harvest aim is fixed.
            mc.player.setYRot(MovementLook.yaw(mc.player.getYRot(),intent.yaw()));
            mc.player.setXRot(MovementLook.pitch(mc.player.getXRot(),intent.pitch()));
            movementLookAt=world.tick();
        }
        mc.player.setSprinting(intent.sprint() && intent.forward() && MovementAxes.from(intent,mc.player.getYRot()).forward()>.8f);
    }
    @Override public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch) {
        if (!loggingJumpReady(edge) || !launch && (loggingJumpEdge==null || !loggingJumpEdge.equals(edge)
                || loggingJumpGeneration!=observations.generation() || world.tick()-loggingJumpStarted>40)) {
            stopMovement(); return false;
        }
        double from=world.standingY(edge.from());
        if (launch) {
            if (loggingJumpEdge!=null || !stationaryLoggingLaunch()
                    || !LoggingJumpRules.standingAt(world.player(),edge.from(),from,LoggingJumpRules.SOURCE_CENTER)) {
                stopMovement(); return false;
            }
            loggingJumpEdge=edge; loggingJumpGeneration=observations.generation(); loggingJumpStarted=world.tick();
        } else if (!LoggingJumpRules.insideFlight(edge,world.player(),from)) { stopMovement(); return false; }
        else if (movementAt==world.tick()) return true; // Do not overwrite this tick's one-use launch input.
        double dx=edge.to().x()+.5-mc.player.getX(),dz=edge.to().z()+.5-mc.player.getZ();
        float yaw=(float)(Math.toDegrees(Math.atan2(dz,dx))-90);
        if (!Float.isFinite(yaw) || !Float.isFinite(mc.player.getYRot()) || !Float.isFinite(mc.player.getXRot())) {
            stopMovement(); return false;
        }
        // Lift vertically first. Only after native physics clears the full-block
        // riser do we steer toward its top; never assign velocity or player position.
        boolean forward=NativeLoggingJump.advanceAfterLift(launch,mc.player.getY(),world.standingY(edge.to()),Math.hypot(dx,dz));
        movement=new Movement(yaw,0,forward,false,launch,false); movementAt=world.tick();
        mc.player.setSprinting(false);
        if (movementLookAt!=world.tick()) {
            mc.player.setYRot(MovementLook.yaw(mc.player.getYRot(),yaw));
            mc.player.setXRot(MovementLook.pitch(mc.player.getXRot(),0));
            movementLookAt=world.tick();
        }
        if (launch) NativeLoggingJump.permitPulse(movement,world.tick(),() -> loggingJumpEdge!=null
            && loggingJumpEdge.equals(edge) && loggingJumpGeneration==observations.generation()
            && loggingJumpReady(edge) && stationaryLoggingLaunch()
            && LoggingJumpRules.standingAt(world.player(),edge.from(),world.standingY(edge.from()),LoggingJumpRules.SOURCE_CENTER));
        return true;
    }
    private boolean stationaryLoggingLaunch() {
        return mc.player!=null && NativeLoggingJump.stationaryForLaunch(mc.player.getDeltaMovement().x,mc.player.getDeltaMovement().z);
    }
    private boolean loggingJumpReady(LoggingJumpEdge edge) {
        return enabled && context!=null && pending==null && mc.player!=null && mc.level!=null && mc.screen==null
            && pauseReason()==null && LoggingJumpRules.authorised(context) && LoggingRules.allowed(context)
            && !MachineOutputLedger.hasPending(context) && world.canLoggingJump(edge,context.profile());
    }
    private void clearLoggingJump() { loggingJumpEdge=null; NativeLoggingJump.clearPulse(); }
    public void stopMovement() { movement=null; clearLoggingJump(); if (mc.player!=null && enabled) mc.player.setSprinting(false); }
    public void cancel() {
        stopMovement();
        if (pending!=null) finish(ActionOutcome.State.CANCELLED,"Cancelled without additional input");
        // Ownership survives pause only for diagnostics; start() requires manual closure.
    }
    public ActionOutcome outcome(long ticket) { return outcomes.getOrDefault(ticket,new ActionOutcome(ActionOutcome.State.CANCELLED,"Expired action")); }
    private void finishLogging(ActionOutcome.State state,String message) {
        if (loggingAction==null && loggingRecipe==null && loggingSwap==null) return;
        if (state!=ActionOutcome.State.SUCCEEDED) {
            if (loggingAction!=null) {
                try { loggingAction.abort(mc,observations); } catch (RuntimeException ignored) { /* Keep the unresolved fence. */ }
                lateLoggingAction=loggingAction;
            }
            if (loggingRecipe!=null) lateLoggingRecipe=loggingRecipe;
            if (loggingSwap!=null) lateLoggingSwap=loggingSwap;
            if (state==ActionOutcome.State.FAILED) { loggingFailure=message; loggingFailureGeneration=observations.generation(); }
        }
        loggingAction=null; loggingRecipe=null; loggingSwap=null;
    }
    private void finish(ActionOutcome.State state,String message) { finishConsolidation(state,message); finishTrash(state,message); finishLogging(state,message); put(pendingTicket,state,message); pending=null; }
    private void finish(ActionOutcome.State state,String message,int quantity) {
        finishConsolidation(state,message);
        finishTrash(state,message);
        finishLogging(state,message);
        outcomes.put(pendingTicket,new ActionOutcome(state,message,quantity)); pending=null;
        if (outcomes.size()>512) outcomes.remove(outcomes.keySet().iterator().next());
    }
    private void put(long ticket,ActionOutcome.State state,String message) {
        outcomes.put(ticket,new ActionOutcome(state,message));
        if (outcomes.size()>512) outcomes.remove(outcomes.keySet().iterator().next());
    }
}
