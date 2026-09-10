package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
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
    private net.minecraft.world.item.ItemStack artisanIngredient;
    private final ArtisanAttemptFence<NativeArtisanReceipt.Attempt> artisanAttempts=new ArtisanAttemptFence<>(128);
    private NativeArtisanReceipt.Attempt artisanAttempt;
    private NativeWineFeedReceipt.Attempt wineFeedAttempt;
    private Movement movement;
    private long movementAt;
    private long movementLookAt=Long.MIN_VALUE;
    private LoggingJumpEdge loggingJumpEdge;
    private boolean generalStepUp;
    private long loggingJumpGeneration,loggingJumpStarted;
    private Pos ownedContainer;
    private ContainerShape openingShape, ownedShape;
    private int ownedMenu=-1;
    private NativeInventoryConsolidation consolidation, lateInventoryReply;
    private Context consolidationContext;
    private boolean consolidationInFlight,lateInventoryReplyInFlight;
    private ConsolidationRecoveryBudget consolidationRecovery;
    private long lateInventoryResolutionGeneration,lateInventoryResolutionAfter;
    private String consolidationFailure;
    private long failureGeneration;
    private NativeTrashSlot trash,lateTrashReply;
    private boolean trashInFlight;
    private String trashFailure;
    private long trashFailureGeneration;
    private NativeLoggingActions loggingAction,lateLoggingAction;
    private NativeLoggingRecipe loggingRecipe,lateLoggingRecipe;
    private NativeLoggingSwap loggingSwap,lateLoggingSwap;
    private long lastHotbarSwapGeneration=-1,lastHotbarSwapSequence=-1;
    private String loggingFailure;
    private long loggingFailureGeneration;
    private final LinkedHashMap<Long,ActionOutcome> outcomes=new LinkedHashMap<>();
    public MinecraftActions(MinecraftWorld world,ServerObservations observations) {
        this.world=world; this.observations=observations;
        observations.observeNativeFullMenus(() -> {
            // Latch proof only. In particular, a cancelled ticket stays cancelled and
            // an explicit OFF never restarts just because its late reply arrived.
            if (loggingSwap!=null) loggingSwap.confirmed(observations);
            if (lateLoggingSwap!=null) lateLoggingSwap.confirmed(observations);
        });
    }
    public void context(Context context) { this.context=context; }
    public void enabled(boolean enabled) { if (!enabled) stopMovement(); this.enabled=enabled; }
    public boolean busy() { return pending!=null; }
    /** Read-only settings boundary: never reconciles a late reply or clears a failure. */
    public boolean settingsEditSafe() {
        return !enabled && pending==null && movement==null && loggingJumpEdge==null
            && consolidation==null && !consolidationInFlight && consolidationRecovery==null
            && lateInventoryReply==null && !lateInventoryReplyInFlight && consolidationFailure==null
            && trash==null && !trashInFlight && lateTrashReply==null && trashFailure==null
            && loggingAction==null && loggingRecipe==null && loggingSwap==null
            && lateLoggingAction==null && lateLoggingRecipe==null && lateLoggingSwap==null && loggingFailure==null
            && artisanAttempt==null && wineFeedAttempt==null && artisanAttempts.size()==0;
    }
    @Override public String artisanRejection(Pos target) {
        return artisanAttempts.blocked(target,observations.generation(),attempt->attempt.confirmed(observations))
            ? "이 가공 기계의 이전 서버 응답이 아직 불확실합니다. 재클릭하지 않고 다른 작업을 진행합니다." : null;
    }
    @Override public boolean supportsMovingHarvest() { return true; }
    @Override public boolean supportsInventoryTrash() { return NativeTrashSlot.available(); }
    @Override public String inventoryTrashRejection() { return NativeTrashSlot.preflightRejection(); }
    /** A manual slot/key event invalidates older custody views, without changing a ticket or lease. */
    public void manualHotbarCustodyInteraction() {
        lastHotbarSwapGeneration=observations.generation();lastHotbarSwapSequence=observations.sequence();
    }
    @Override public boolean loggingHotbarRestored(LoggingHotbarLease lease) {
        // This query cannot settle an outstanding operation. The ordinary start/action
        // fences must already have established that no possibly-sent request remains.
        if (context==null || context.profile().loggingHotbarLease!=lease || pending!=null
            || lateLoggingSwap!=null || lateLoggingAction!=null || lateLoggingRecipe!=null
            || lateInventoryReply!=null || lateTrashReply!=null || loggingFailure!=null
            || consolidationFailure!=null || trashFailure!=null || mc.player==null || mc.level==null
            || mc.player.containerMenu!=mc.player.inventoryMenu || mc.player.inventoryMenu.containerId!=0
            || mc.player.inventoryMenu.slots.size()!=46 || !mc.player.inventoryMenu.getCarried().isEmpty()
            || lease==null || lease.sourceIndex()<9 || lease.sourceIndex()>35 || lease.hotbarSlot()<0 || lease.hotbarSlot()>8)
            return false;
        var slots=mc.player.inventoryMenu.slots;
        var sources=slots.stream().filter(s->s.container==mc.player.getInventory() && s.getContainerSlot()==lease.sourceIndex()).toList();
        var destinations=slots.stream().filter(s->s.container==mc.player.getInventory() && s.getContainerSlot()==lease.hotbarSlot()).toList();
        if (sources.size()!=1 || destinations.size()!=1) return false;
        int source=sources.get(0).index,destination=destinations.get(0).index;
        if (source!=lease.sourceIndex() || destination!=36+lease.hotbarSlot()) return false;
        var liveSource=restorationEndpoint(sources.get(0).getItem());
        var liveHotbar=restorationEndpoint(destinations.get(0).getItem());
        // Current custody differs from historical click completion: a newer FULL
        // invalidates an older matching view, even if the live menu predicts it again.
        var full=LoggingRestorationReceipt.latest(observations.fullNativeMenuSnapshotsSince(0,-1));
        if (full==null || !full.carried().isEmpty()) return false;
        var items=full.items();if(items.size()!=46)return false;
        return LoggingRestorationReceipt.proves(lease,observations.generation(),lastHotbarSwapGeneration,lastHotbarSwapSequence,
            full.seq(),true,liveSource,liveHotbar,restorationEndpoint(items.get(source)),restorationEndpoint(items.get(destination)));
    }
    private static LoggingRestorationReceipt.Endpoint restorationEndpoint(net.minecraft.world.item.ItemStack item) {
        try {
            String raw=item.save(new net.minecraft.nbt.CompoundTag()).toString();
            String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return new LoggingRestorationReceipt.Endpoint(MinecraftWorld.item(item),fingerprint);
        } catch (java.security.NoSuchAlgorithmException unavailable) { return null; }
    }
    @Override public String recoveryStatus() {
        if (loggingSwap!=null && pending instanceof Action.SwapHotbar && context!=null
            && world.tick()-started>=context.profile().interactionTimeoutTicks)
            return "단축바 교환 서버 확인 대기 ("+Math.max(0,(world.tick()-started)/20)
                +"초) — 재클릭 없이 응답이 확인되면 현재 작업을 이어갑니다. F8로 중지 가능";
        if (pending instanceof Action.ConsolidateInventory && consolidationRecovery!=null && consolidationRecovery.recovering())
            return "인벤토리 정리 재확인 중 ("+((consolidationRecovery.remainingTicks(world.tick())+19)/20)
                +"초 이내) — 같은 클릭을 재전송하지 않고 서버 확인 후 이어갑니다";
        return null;
    }
    public Movement movement() { return enabled && world.tick()-movementAt<=2 ? movement : null; }
    public boolean ownsContainer() {
        MenuData menu=world.menu();
        return menu!=null && menu.container() && menu.id()==ownedMenu && ownedShape!=null
            && (!ownedShape.blockId().equals("minecraft:crafting_table") || world.loggingCraftingMenu()
                || pending instanceof Action.CraftFireLogs && loggingRecipe!=null && loggingRecipe.ownsManualMenu(mc))
            && ownedShape.equals(containerShape(ownedContainer)) && ownedShape.matches(menu);
    }
    /** Exact still-owned open container only; never a guessed closed-storage target. */
    public Pos ownedContainerPosition() { return ownsContainer() ? ownedContainer : null; }
    public boolean openingContainer() { return pending instanceof Action.UseBlock use && (use.purpose()==Action.Use.OPEN_CONTAINER || use.purpose()==Action.Use.OPEN_CRAFTING); }
    public boolean expectingSleep() { return pending instanceof Action.UseBlock use && use.purpose()==Action.Use.SLEEP; }
    public long submit(Action action) {
        long ticket=++nextTicket;
        if (!enabled || context==null) { put(ticket,ActionOutcome.State.CANCELLED,"Automation is paused"); return ticket; }
        if (pending!=null) { put(ticket,ActionOutcome.State.FAILED,"Another action is still awaiting the server"); return ticket; }
        String paused=pauseReason();
        if (paused!=null) { put(ticket,ActionOutcome.State.FAILED,paused); return ticket; }
        String rejection=SafetyPolicy.rejection(action,context);
        if (rejection==null && (action instanceof Action.TrashRotten || action instanceof Action.TrashLogging))
            rejection=inventoryTrashRejection();
        if(rejection==null && action instanceof Action.UseBlock use && use.purpose()==Action.Use.ARTISAN)
            rejection=artisanRejection(use.pos());
        ContainerShape requestedShape=null;
        if (rejection==null && action instanceof Action.UseBlock use && (use.purpose()==Action.Use.OPEN_CONTAINER || use.purpose()==Action.Use.OPEN_CRAFTING)) {
            requestedShape=containerShape(use.pos());
            if (requestedShape==null || !requestedShape.canonical().equals(use.pos()))
                rejection="Container is unloaded, incomplete, or no longer the registered chest pair";
            else if (use.purpose()==Action.Use.OPEN_CONTAINER && CommodityStorageRules.openAllowed(context,use.pos())
                && !CommodityStorageRules.unreservedContainer(context.profile(),physicalContainerCells(requestedShape)))
                rejection="Generic storage cannot open a physical chest half reserved for another role";
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
        artisanIngredient=action instanceof Action.UseBlock use && use.purpose()==Action.Use.ARTISAN ? mc.player.getMainHandItem().copy() : null;
        artisanAttempt=null;
        wineFeedAttempt=null;
        put(ticket,ActionOutcome.State.PENDING,"");
        try { execute(action); }
        catch (RuntimeException e) {
            boolean waste=action instanceof Action.TrashRotten || action instanceof Action.TrashLogging;
            String message=waste && e instanceof NativeTrashSlot.PreflightRejected && !trashInFlight && trash==null
                ? e.getMessage() : "Game rejected the action: "+e.getClass().getSimpleName()
                    +" ["+action.getClass().getSimpleName()+(waste ? trashInFlight ? ", deletion send attempted" : ", before deletion send" : "")+"]";
            finish(ActionOutcome.State.FAILED,message);
        }
        return ticket;
    }
    private String transferRejection(Action.QuickMove transfer) {
        if (context.session().oneShotFeature==Feature.STORAGE_SURVEY) return "Storage survey cannot transfer items";
        if (!ownsContainer() || ownedContainer==null) return "This container was not opened by automation";
        Poi poi=context.profile().pois.stream().filter(p -> p.pos().equals(ownedContainer)).findFirst().orElse(null);
        ItemSlot source=world.menu().slot(transfer.slot());
        ItemData item=source.item();
        boolean commodity=source.player() ? CommodityStorageRules.depositAllowed(context,ownedContainer,item)
            : CommodityStorageRules.withdrawalAllowed(context,ownedContainer,item);
        commodity &= StorageSurveyRules.ordinaryStorage(world.block(ownedContainer))
            && CommodityStorageRules.unreservedContainer(context.profile(),physicalContainerCells(ownedShape));
        if (poi==null && !commodity) return "Container registration changed or commodity transfer is outside its job";
        boolean kindMatches=commodity || poi!=null && switch (poi.kind()) {
            case TOMATO_CHEST -> TomatoStorageRules.permitsTransfer(item,world.menu());
            case WINE_CHEST -> source.player() && item.is(ItemData.WINE) && item.year()!=null && item.year().equals(poi.classifier());
            case WOOD_CHEST -> LoggingRules.allowed(context) && source.player() && LoggingRules.wood(item);
            case SHIPPING_BIN -> source.player() && (item.standardShippingProduct() && context.session().allows(context.profile(),Feature.SHIPPING)
                || item.is(ItemData.WINE) && WineSaleRules.permitted(item,context)
                || item.is(ItemData.TOMATO) && TomatoSaleRules.permitted(item,context)
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
            if(use.purpose()==Action.Use.ARTISAN) {
                artisanAttempt=new NativeArtisanReceipt.Attempt(ArtisanRules.at(context.profile(),use.pos()),use.pos(),beforeBlock,
                    artisanIngredient,beforePlayer.selectedSlot(),beforeMenu.id(),beforeMenu,beforeSequence,observations.generation());
                if(!artisanAttempts.sent(use.pos(),observations.generation(),artisanAttempt)) {
                    artisanAttempt=null;finish(ActionOutcome.State.FAILED,"Unconfirmed artisan attempt prevents another send");return;
                }
            }
            if(use.purpose()==Action.Use.MACHINE && NativeWineFeedReceipt.eligible(use.pos(),beforeBlock,
                    mc.player.getMainHandItem(),beforePlayer.selectedSlot(),beforeMenu)) {
                // Idle input may be partial; mature harvest resets it before a full
                // refill. Capture this dispatch without guessing unsynchronized BE stage.
                wineFeedAttempt=new NativeWineFeedReceipt.Attempt(use.pos(),beforeBlock,mc.player.getMainHandItem(),
                    beforePlayer.selectedSlot(),beforeMenu.id(),beforeMenu,beforeSequence,observations.generation());
            }
            var result=mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
            if (result.consumesAction()) mc.player.swing(InteractionHand.MAIN_HAND);
            // PASS does not trigger useItem(): eating/air-use is never a fallback.
        } else if (action instanceof Action.SelectHotbar select) {
            mc.player.getInventory().selected=select.slot();
        } else if (action instanceof Action.SwapHotbar swap) {
            int source=mc.player.inventoryMenu.slots.stream().filter(s -> s.container==mc.player.getInventory() && s.getContainerSlot()==swap.inventoryIndex()).map(s -> s.index).findFirst().orElseThrow();
            if (swap.inventoryIndex()==swap.hotbarSlot()) { finish(ActionOutcome.State.SUCCEEDED,"Already in hotbar"); return; }
            // Every hotbar swap owns its exact server receipt, independently of
            // an unrelated suspended logging queue. Do not use the generic
            // "some inventory changed" fallback for production ingredients.
            loggingSwap=new NativeLoggingSwap(mc.player,swap,observations);
            lastHotbarSwapGeneration=loggingSwap.generation;lastHotbarSwapSequence=loggingSwap.beforeSequence;
            confirmedClick(mc.player.inventoryMenu.containerId,source,swap.hotbarSlot(),ClickType.SWAP);
        } else if (action instanceof Action.QuickMove transfer) {
            confirmedClick(transfer.containerId(),transfer.slot(),0,ClickType.QUICK_MOVE);
        } else if (action instanceof Action.ConsolidateInventory merge) {
            consolidation=NativeInventoryConsolidation.create(mc.player,merge.plan(),observations,context.profile().hoeHotbarSlot);
            if (consolidation==null) { finish(ActionOutcome.State.SUCCEEDED,"Native stacks cannot be consolidated",0); return; }
            consolidationContext=context;
            consolidationRecovery=new ConsolidationRecoveryBudget();
            sendConsolidationClick();
        } else if (action instanceof Action.TrashRotten rotten) {
            trash=new NativeTrashSlot(mc.player,rotten,observations);
            trashInFlight=true; // A send that throws may already have reached the channel: never retry it.
            trash.send();
        } else if (action instanceof Action.TrashLogging waste) {
            trash=new NativeTrashSlot(mc.player,waste,observations); trashInFlight=true; trash.send();
        } else if (action instanceof Action.ChopTree || action instanceof Action.PlantSapling || action instanceof Action.ClearLoggingLeaf) {
            loggingAction=new NativeLoggingActions(mc,world,observations,action,context);
            Pos target=action instanceof Action.ChopTree chop ? chop.pos()
                : action instanceof Action.ClearLoggingLeaf leaf ? leaf.pos() : ((Action.PlantSapling)action).pos();
            var hit=world.hit(action instanceof Action.PlantSapling ? target.offset(0,-1,0) : target,mc.player.getEyePosition());
            if (hit!=null) lookAt(hit.getLocation());
            loggingAction.begin(mc,observations);
        } else if (action instanceof Action.CraftFireLogs) {
            loggingRecipe=new NativeLoggingRecipe(mc,observations,world.tick());
            if (loggingRecipe.manual()) sendManualLoggingCraftClick();
            else loggingRecipe.place(mc,() -> confirmedClick(loggingRecipe.menuId,loggingRecipe.selfSlot,loggingRecipe.selfHotbar,ClickType.SWAP));
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
            if (loggingSwap.confirmed(observations)) { finish(ActionOutcome.State.SUCCEEDED,"서버가 핫바 교환을 확인했습니다."); return; }
            if (loggingSwap.generation!=observations.generation())
                finish(ActionOutcome.State.FAILED,"접속이 변경되어 이전 핫바 교환 작업을 보존했습니다.");
            // A deadline is not evidence that a sent swap failed. Keep this exact
            // pending ticket and its module phase; a late genuine receipt can finish
            // it normally. OFF/manual input still cancels through the usual path.
            // Never replay the click, restart a module, or let another consumer run.
            stopMovement();
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
                        var snapshot=observations.fullMenuSnapshotSince(menu.id(),beforeSequence);
                        observeTomatoStock(menu,snapshot==null ? null : snapshot.items());
                        finish(ActionOutcome.State.SUCCEEDED,"Container synchronized"); return;
                    }
                }
                case SLEEP -> { if (mc.player.isSleeping()) { finish(ActionOutcome.State.SUCCEEDED,"Entered bed"); return; } }
                case ARTISAN -> {
                    if(artisanAttempt!=null) {
                        var confirmation=artisanAttempt.confirmation(observations);
                        if(confirmation!=NativeArtisanReceipt.Confirmation.NONE) {
                            // Evidence belongs to this exact still-pending ticket.
                            // A late fence check can clear a target, but cannot
                            // manufacture this outcome for an old cancelled use.
                            var proof=confirmation==NativeArtisanReceipt.Confirmation.CYCLE_ADVANCED
                                ? ActionOutcome.Proof.ARTISAN_CYCLE_ADVANCED : ActionOutcome.Proof.NONE;
                            finish(ActionOutcome.State.SUCCEEDED,"Server confirmed artisan state and participating slot",0,proof);return;
                        }
                    }
                }
                case HARVEST, MACHINE, FRUIT, DOOR -> {
                    if(use.purpose()==Action.Use.MACHINE && wineFeedAttempt!=null) {
                        int consumed=wineFeedAttempt.confirmedCount(observations);
                        if(consumed>0) {
                            var proof=consumed<3 ? ActionOutcome.Proof.WINE_PARTIAL_FEED : ActionOutcome.Proof.WINE_FULL_FEED;
                            // This is the exact participating-slot loss, independent
                            // of ambient tomato pickups elsewhere in the inventory.
                            finish(ActionOutcome.State.SUCCEEDED,"Server confirmed wine feed and selected slot",consumed,proof);return;
                        }
                        // No broad block-or-any-inventory fallback for this opted-in
                        // attempt, including at timeout. A late selected-slot ACK must
                        // not be cut off by an earlier block-only generic success.
                        break;
                    }
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
                    if (confirmed && destination!=null && destination.kind()==PoiKind.SHIPPING_BIN && source.item().is(ItemData.TOMATO))
                        TomatoSaleRules.consume(quantity,context);
                } else if (pending instanceof Action.ThrowRotten drop) {
                    quantity=InventoryAcknowledgements.removed(beforeMenu,acknowledgement.items(),drop.slot());
                    confirmed=quantity>0;
                }
                if (confirmed) {
                    observeTomatoStock(beforeMenu,acknowledgement.items());
                    finish(ActionOutcome.State.SUCCEEDED,"Server confirmed inventory change",quantity); return;
                }
            }
        }
        if (world.tick()-started>=context.profile().interactionTimeoutTicks) finish(ActionOutcome.State.FAILED,"No server confirmation; inspect before retrying");
    }
    private void tickLogging() {
        if (loggingAction.confirmed(observations)) {
            finish(ActionOutcome.State.SUCCEEDED,pending instanceof Action.ClearLoggingLeaf
                ? "서버가 허용된 시야 방해 잎 한 칸의 제거를 확인했습니다." : "서버가 벌목·식재 진행을 확인했습니다.",1); return;
        }
        String rejection=loggingAction.advance(mc,world,observations,context);
        if (rejection!=null) { finish(ActionOutcome.State.FAILED,rejection); return; }
        if (world.tick()-started>=context.profile().interactionTimeoutTicks)
            finish(ActionOutcome.State.FAILED,"벌목·식재의 서버 확인이 없습니다. 재전송하지 않고 멈춥니다.");
    }
    private void tickLoggingRecipe() {
        if (loggingRecipe.generation!=observations.generation() || !LoggingRules.allowed(context)
            || !ownsContainer() || !world.loggingCraftingMenu() && !loggingRecipe.ownsManualMenu(mc)) {
            finish(ActionOutcome.State.FAILED,"제작 중 연결·작업대 또는 권한이 바뀌었습니다. 제작 칸을 확인하세요."); return;
        }
        for (var ack:observations.fullNativeMenuSnapshotsSince(loggingRecipe.menuId,loggingRecipe.beforeSequence)) {
            int result=loggingRecipe.acknowledge(ack);
            if (result>0) { finish(ActionOutcome.State.SUCCEEDED,"서버가 장작 제작을 확인했습니다.",result); return; }
            if (result==-2) { sendManualLoggingCraftClick(); return; }
            if (result==-1) {
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
    private void sendManualLoggingCraftClick() {
        if (loggingRecipe==null || loggingRecipe.generation!=observations.generation()
            || !LoggingRules.allowed(context) || !ownsContainer()) {
            finish(ActionOutcome.State.FAILED,"수동 장작 제작 중 작업대·연결·권한이 바뀌었습니다. 재료를 그대로 보존했습니다."); return;
        }
        try {
            var click=loggingRecipe.prepareManualClick(mc,observations.sequence(),world.tick());
            confirmedClick(loggingRecipe.menuId,click.slot(),click.button(),switch (click.type()) {
                case PICKUP -> ClickType.PICKUP;
                case QUICK_CRAFT -> ClickType.QUICK_CRAFT;
            });
        } catch (RuntimeException failure) {
            finish(ActionOutcome.State.FAILED,"수동 장작 제작 클릭이 불확실하거나 재료가 바뀌었습니다. 재전송하지 않고 제작 칸을 보존했습니다.");
        }
    }
    private void sendConsolidationClick() {
        // Baseline refresh is only for the next UNSENT primitive. An in-flight
        // click must be handled by its authoritative ACK dispatcher, never here.
        if (consolidationInFlight) return;
        if (!enabled || consolidation==null || consolidationRecovery==null || !consolidationRecovery.mayContinue(world.tick())) {
            finish(ActionOutcome.State.FAILED,"인벤토리 정리 재확인 시간이 끝났습니다. 미확인 클릭과 임시 슬롯은 보존했습니다."); return;
        }
        if (MachineOutputLedger.hasPending(context) || !context.session().allows(context.profile(),consolidation.plan.feature())
            || context.profile().hoeHotbarSlot!=consolidation.protectedHotbar) {
            finish(ActionOutcome.State.FAILED,"Production settings changed; no further inventory clicks were sent"); return;
        }
        if (!consolidation.matchesLive(mc.player,observations)) {
            if (skipOptionalUnsentConsolidation()) return;
            awaitConsolidationProof(); return;
        }
        var click=consolidation.transaction.click();
        consolidation.beforeSequence=observations.sequence(); started=world.tick();
        consolidationInFlight=true;
        confirmedClick(consolidation.menuId,consolidation.sourceMenuSlot(),click.type()==InventoryConsolidation.Type.SWAP ? click.hotbar() : 0,
            click.type()==InventoryConsolidation.Type.SWAP ? ClickType.SWAP : ClickType.QUICK_MOVE);
    }
    /** A terminal no-op, never a fabricated merge ACK or a retry of an earlier click. */
    private boolean skipOptionalUnsentConsolidation() {
        if (!(pending instanceof Action.ConsolidateInventory merge) || consolidation==null || consolidationContext==null
                || context==null || mc.player==null || consolidationRecovery==null) return false;
        var boundary=new OptionalConsolidationSkip.Boundary(
            OptionalConsolidationSkip.Reason.UNSENT_BASELINE_MISMATCH,merge.optionalOutput(),consolidationInFlight,
            context==consolidationContext,context.profile()==consolidationContext.profile(),
            context.session()==consolidationContext.session(),context.world()==consolidationContext.world(),
            consolidation.generation,observations.generation(),
            mc.getConnection()!=null && world.player().connected() && mc.player.containerMenu==mc.player.inventoryMenu,
            mc.player.inventoryMenu.containerId,mc.player.inventoryMenu.slots.size(),mc.player.inventoryMenu.getCarried().isEmpty(),
            enabled,context.session().allows(context.profile(),consolidation.plan.feature()),
            context.profile().hoeHotbarSlot==consolidation.protectedHotbar,consolidation.protectedHotbar,
            context.profile().loggingHotbarLease==null,!MachineOutputLedger.hasPending(context),
            lateInventoryReply==null && lateTrashReply==null && lateLoggingSwap==null && lateLoggingAction==null
                && lateLoggingRecipe==null && consolidationFailure==null && trashFailure==null && loggingFailure==null,
            consolidationRecovery.mayContinue(world.tick()),started,world.tick());
        if (!OptionalConsolidationSkip.allowed(consolidation.transaction,merge.plan(),boundary)) return false;
        finish(ActionOutcome.State.SKIPPED,"미전송 선택적 와인 정리를 생략하고 현재 재고로 계속합니다.",0,
            ActionOutcome.Proof.CONSOLIDATION_SKIPPED_UNSENT);
        return true;
    }
    private void tickConsolidation() {
        if (consolidation==null || observations.generation()!=consolidation.generation) {
            finish(ActionOutcome.State.FAILED,"Connection changed during inventory consolidation"); return;
        }
        if (mc.player.containerMenu!=mc.player.inventoryMenu || !mc.player.containerMenu.getCarried().isEmpty()) {
            finish(ActionOutcome.State.FAILED,"Inventory menu or cursor changed; no restoration was sent"); return;
        }
        ConsolidationRecoveryFlow.poll(consolidationRecovery,world.tick(),new ConsolidationRecoveryFlow.Step<ServerObservations.NativeMenuSnapshot>() {
            public boolean inFlight() { return consolidationInFlight; }
            public boolean normalTimeout() { return world.tick()-started>=context.profile().interactionTimeoutTicks; }
            public Iterable<ServerObservations.NativeMenuSnapshot> acknowledgements() {
                return observations.fullNativeMenuSnapshotsSince(consolidation.menuId,consolidation.beforeSequence);
            }
            public InventoryConsolidation.Confirmation acknowledge(ServerObservations.NativeMenuSnapshot acknowledgement) { return consolidation.acknowledge(acknowledgement,observations); }
            public void markAcknowledged() { consolidationInFlight=false; }
            public void complete() { finish(ActionOutcome.State.SUCCEEDED,"Server verified inventory consolidation",consolidation.transaction.freedSlots()); }
            // A later pickup needs actual post-ACK slot evidence before rebasing.
            // This callback never sends the primitive that was just acknowledged.
            public void sendUnsent() { sendConsolidationClick(); }
            public void hold() { awaitConsolidationProof(); }
            public void expired() { finish(ActionOutcome.State.FAILED,"인벤토리 정리 재확인 시간이 끝났습니다. 미확인 클릭과 임시 슬롯은 보존했습니다."); }
        });
    }
    private void awaitConsolidationProof() {
        stopMovement();
        if (consolidationRecovery==null || !consolidationRecovery.awaitProof(world.tick())) {
            finish(ActionOutcome.State.FAILED,"인벤토리 정리 재확인 시간이 끝났습니다. 미확인 클릭과 임시 슬롯은 보존했습니다."); return;
        }
        // Retain the original ticket and module ownership. It remains PENDING,
        // so no scheduler neighbour or automatic restart can consume its items.
        put(pendingTicket,ActionOutcome.State.PENDING,"서버의 정확한 인벤토리 응답 재확인 중 — 클릭 재전송 없음");
    }
    private void tickTrash() {
        if (trash==null || trash.generation!=observations.generation()) {
            finish(ActionOutcome.State.FAILED,"Connection changed during inventory waste deletion"); return;
        }
        if (mc.player.containerMenu!=mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            finish(ActionOutcome.State.FAILED,"Inventory or cursor changed during deletion; no further request was sent"); return;
        }
        int removed=trash.confirmedCount(observations);
        if (removed>0) {
            trashInFlight=false;
            finish(ActionOutcome.State.SUCCEEDED,"Server verified the single inventory waste stack deletion",removed); return;
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
                // An intermediate manual placement ACK never clears a cancelled
                // multi-click operation or authorizes cleanup/continuation.
                if (lateLoggingRecipe.acknowledge(ack)>0) { lateLoggingRecipe=null; break; }
            }
        }
        if (lateLoggingSwap!=null)
            return "핫바 교환의 이전 서버 응답을 기다립니다. 교환을 다시 누르지 않고 아이템을 보존합니다.";
        if (lateLoggingAction!=null || lateLoggingRecipe!=null)
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
            if (lateInventoryReply.generation!=observations.generation() && !lateInventoryReply.transaction.requiresRestoration()) {
                lateInventoryReply=null;lateInventoryReplyInFlight=false;
            } else if (lateInventoryReplyInFlight && lateInventoryReply.generation==observations.generation()) {
                for (var acknowledgement:observations.fullNativeMenuSnapshotsSince(lateInventoryReply.menuId,lateInventoryReply.beforeSequence)) {
                    if (lateInventoryReply.acknowledge(acknowledgement,observations)==InventoryConsolidation.Confirmation.WAIT) continue;
                    lateInventoryReplyInFlight=false;
                    lateInventoryResolutionAfter=Math.max(lateInventoryResolutionAfter,acknowledgement.seq());
                    if (!lateInventoryReply.transaction.requiresRestoration()) lateInventoryReply=null;
                    break;
                }
            }
            if (lateInventoryReply!=null && lateInventoryReply.transaction.requiresRestoration()
                && (!lateInventoryReplyInFlight || lateInventoryReply.generation!=observations.generation())) {
                // A different generation has terminated the old channel. Its
                // genuine new FULL packet may prove exact final custody, never
                // acknowledge/resend the old click or claim production success.
                long after=lateInventoryResolutionGeneration==observations.generation()?lateInventoryResolutionAfter:-1;
                for (var acknowledgement:observations.fullNativeMenuSnapshotsSince(lateInventoryReply.menuId,after)) {
                    if (!ConsolidationRecoveryFlow.manualRestoreCandidate(lateInventoryReplyInFlight,lateInventoryReply.generation,
                        observations.generation(),lateInventoryResolutionGeneration,lateInventoryResolutionAfter,acknowledgement.seq())) continue;
                    if (!lateInventoryReply.acknowledgeCancelledRestoration(acknowledgement)) continue;
                    lateInventoryReply=null;lateInventoryReplyInFlight=false;break;
                }
            }
        }
        if (lateInventoryReply!=null && lateInventoryReply.transaction.requiresRestoration()
            && (!lateInventoryReplyInFlight || lateInventoryReply.generation!=observations.generation()))
            return "인벤토리 정리의 빌린 핫바 슬롯 복원이 남아 있습니다. 원래 아이템을 정확히 되돌린 뒤 F8로 다시 확인하세요. 자동 클릭은 보내지 않습니다.";
        return lateInventoryReply!=null ? "이전 인벤토리 클릭의 정확한 서버 확인이 남아 있습니다. 같은 클릭을 다시 보내거나 자동으로 재접속하지 않습니다."
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
        if (consolidation!=null && (consolidationInFlight || consolidation.transaction.requiresRestoration())) {
            lateInventoryReply=consolidation;lateInventoryReplyInFlight=consolidationInFlight;
            lateInventoryResolutionGeneration=observations.generation();lateInventoryResolutionAfter=observations.sequence();
        }
        if (state==ActionOutcome.State.FAILED) { consolidationFailure=message; failureGeneration=observations.generation(); }
        if (consolidationRecovery!=null) consolidationRecovery.cancel();
        consolidationRecovery=null;
        consolidationContext=null;
        consolidation=null; consolidationInFlight=false;
    }
    private void finishTrash(ActionOutcome.State state,String message) {
        if (!(pending instanceof Action.TrashRotten) && !(pending instanceof Action.TrashLogging)) return;
        if (trashInFlight && trash!=null) lateTrashReply=trash;
        // Construction/preflight never sends. Only a possibly-sent request owns
        // the global acknowledgement fence; a local refusal is still FAILED,
        // never a fabricated successful deletion. Keep all post-send fences.
        if (state==ActionOutcome.State.FAILED && trashInFlight) { trashFailure=message; trashFailureGeneration=observations.generation(); }
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
    private static java.util.Collection<Pos> physicalContainerCells(ContainerShape shape) {
        if (shape==null || shape.canonical()==null) return java.util.List.of();
        return shape.chestStates().isEmpty() ? java.util.List.of(shape.canonical()) : shape.chestStates().keySet();
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
        // Only the active navigator's transit domain can drive ordinary movement.
        // Work permissions are checked independently in SafetyPolicy.
        Pos feet=NavigationFeet.resolve(world,world.player());
        if (!context.navigation().permitsTransit(feet,context) || intent.jump()) { stopMovement(); return; }
        if (!Float.isFinite(mc.player.getYRot()) || !Float.isFinite(mc.player.getXRot())) { stopMovement(); return; }
        movement=new Movement(intent.yaw(),intent.pitch(),intent.forward(),intent.sprint(),false,intent.sneak(),intent.inputScale());
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
        return moveVerifiedAscent(edge,launch,false);
    }
    @Override public boolean moveStepUp(LoggingJumpEdge edge,boolean launch) {
        return moveVerifiedAscent(edge,launch,true);
    }
    private boolean moveVerifiedAscent(LoggingJumpEdge edge,boolean launch,boolean transit) {
        if (!ascentReady(edge,transit) || !launch && (loggingJumpEdge==null || !loggingJumpEdge.equals(edge) || generalStepUp!=transit
                || loggingJumpGeneration!=observations.generation() || world.tick()-loggingJumpStarted>40)) {
            stopMovement(); return false;
        }
        double from=world.standingY(edge.from());
        if (launch) {
            if (loggingJumpEdge!=null || !stationaryLoggingLaunch()
                    || !LoggingJumpRules.standingAt(world.player(),edge.from(),from,LoggingJumpRules.SOURCE_CENTER)) {
                stopMovement(); return false;
            }
            loggingJumpEdge=edge; generalStepUp=transit; loggingJumpGeneration=observations.generation(); loggingJumpStarted=world.tick();
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
            && loggingJumpEdge.equals(edge) && generalStepUp==transit && loggingJumpGeneration==observations.generation()
            && ascentReady(edge,transit) && stationaryLoggingLaunch()
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
    private boolean ascentReady(LoggingJumpEdge edge,boolean transit) {
        if (!transit) return loggingJumpReady(edge);
        return enabled && context!=null && pending==null && mc.player!=null && mc.level!=null && mc.screen==null
            && pauseReason()==null && StepUpRules.permitted(edge,context)
            && !MachineOutputLedger.hasPending(context) && context.navigation().permitsStepUp(edge,context)
            && context.navigation().permitsTransit(edge.from(),context) && context.navigation().permitsTransit(edge.to(),context);
    }
    private void clearLoggingJump() { loggingJumpEdge=null; generalStepUp=false; NativeLoggingJump.clearPulse(); }
    public void stopMovement() { movement=null; clearLoggingJump(); if (mc.player!=null && enabled) mc.player.setSprinting(false); }
    public void cancel() {
        stopMovement();
        if (consolidationRecovery!=null) consolidationRecovery.cancel();
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
    private void finishArtisan(ActionOutcome.State state) {
        if(artisanAttempt!=null && state==ActionOutcome.State.SUCCEEDED)artisanAttempts.confirmed(artisanAttempt.target(),artisanAttempt);
        artisanAttempt=null; // Failed/cancelled sent attempts remain in their target-scoped RAM fence.
    }
    private void observeTomatoStock(MenuData shape,List<ItemData> packetItems) {
        if(context==null || ownedContainer==null || context.profile().pois(PoiKind.TOMATO_CHEST).stream().noneMatch(p->p.pos().equals(ownedContainer)))return;
        List<ItemData> contents=TomatoStockSnapshots.storage(shape,packetItems);
        if(contents==null || !context.session().tomatoStockCache.observeVerified(context,ownedContainer,contents)) {
            context.session().tomatoStockCache.invalidate(ownedContainer);context.session().tomatoSalePermit=null;
        }
    }
    private void finishTomatoStock(ActionOutcome.State state) {
        if(state==ActionOutcome.State.SUCCEEDED || context==null)return;
        Pos affected=pending instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER ? use.pos()
            : pending instanceof Action.QuickMove ? ownedContainer : null;
        if(affected!=null && context.profile().pois(PoiKind.TOMATO_CHEST).stream().anyMatch(p->p.pos().equals(affected))) {
            context.session().tomatoStockCache.invalidate(affected);context.session().tomatoSalePermit=null;
        }
    }
    private void finish(ActionOutcome.State state,String message) { finishTomatoStock(state);finishConsolidation(state,message); finishTrash(state,message); finishLogging(state,message); finishArtisan(state); wineFeedAttempt=null; put(pendingTicket,state,message); pending=null; }
    private void finish(ActionOutcome.State state,String message,int quantity) {
        finish(state,message,quantity,ActionOutcome.Proof.NONE);
    }
    private void finish(ActionOutcome.State state,String message,int quantity,ActionOutcome.Proof proof) {
        finishTomatoStock(state);
        finishConsolidation(state,message);
        finishTrash(state,message);
        finishLogging(state,message);
        finishArtisan(state);
        wineFeedAttempt=null;
        outcomes.put(pendingTicket,new ActionOutcome(state,message,quantity,proof)); pending=null;
        if (outcomes.size()>512) outcomes.remove(outcomes.keySet().iterator().next());
    }
    private void put(long ticket,ActionOutcome.State state,String message) {
        outcomes.put(ticket,new ActionOutcome(state,message));
        if (outcomes.size()>512) outcomes.remove(outcomes.keySet().iterator().next());
    }
}
