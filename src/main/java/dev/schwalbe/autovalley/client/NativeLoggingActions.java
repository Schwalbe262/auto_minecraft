package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** One narrowly admitted tree/obstructing-leaf mining stroke or one UP-face sapling use. */
final class NativeLoggingActions {
    final long generation,beforeSequence;
    final Pos target;
    private final boolean planting;
    private final Action.ClearLoggingLeaf leafAction;
    private final ItemStack held;
    private final int heldIndex;
    private final BlockState original;
    private final Map<Pos,Integer> beforeChops;
    private final Map<Pos,BlockState> beforeStates;
    private final Direction face;
    private final Vec3 miningHit;
    private float progress;
    private boolean started,stopped,aborted;
    private boolean abortDispatched,abortForwarded;
    private ServerboundPlayerActionPacket abortPacket;
    private long abortBeforeSequence=-1;
    private int abortPacketSequence=-1;
    private final LoggingActionReceipt receipt;

    NativeLoggingActions(Minecraft mc,MinecraftWorld world,ServerObservations observations,Action action,Context context) {
        if (mc.player==null || mc.level==null || mc.player.containerMenu!=mc.player.inventoryMenu
            || !mc.player.inventoryMenu.getCarried().isEmpty() || mc.player.isCreative() || mc.player.isSpectator())
            throw new IllegalArgumentException("Logging requires the normal survival inventory");
        planting=action instanceof Action.PlantSapling;
        leafAction=action instanceof Action.ClearLoggingLeaf leaf ? leaf : null;
        if (planting) target=((Action.PlantSapling)action).pos();
        else if (leafAction!=null) target=leafAction.pos();
        else if (action instanceof Action.ChopTree chop) target=chop.pos();
        else throw new IllegalArgumentException("Unsupported native logging operation");
        generation=observations.generation(); beforeSequence=observations.sequence();
        receipt=new LoggingActionReceipt(generation);
        heldIndex=mc.player.getInventory().selected; held=mc.player.getMainHandItem().copy();
        original=mc.level.getBlockState(MinecraftWorld.nativePos(target));
        if (planting) {
            if (!held.is(Items.SPRUCE_SAPLING) || !canPlant(mc,target)) throw new IllegalArgumentException("Sapling planting position changed");
            face=Direction.UP; miningHit=null; beforeChops=Map.of(); beforeStates=Map.of();
        } else {
            if (mc.player.isShiftKeyDown() || !mc.player.onGround()) throw new IllegalArgumentException("Logging requires standing without crouching");
            if (leafAction!=null) {
                String rejection=LoggingLeafRules.rejection(leafAction,context);
                if (rejection!=null) throw new IllegalArgumentException(rejection);
                if (!original.is(Blocks.SPRUCE_LEAVES) || !held.is(Items.NETHERITE_AXE)
                    || heldIndex!=context.profile().loggingAxeHotbarSlot || !world.loggingAxe(heldIndex))
                    throw new IllegalArgumentException("Only the registered axe may clear the verified spruce leaf");
                // A leaf action never borrows the whole-tree/multiple-base chop ACK path.
                beforeChops=Map.of(); beforeStates=Map.of();
            } else {
                var proof=NativeLoggingTree.inspect(mc.level,target,context.profile().loggingPlots);
                if (!proof.safe()) throw new IllegalArgumentException(proof.rejection());
                beforeChops=proof.chops();
                Map<Pos,BlockState> states=new HashMap<>();
                beforeChops.keySet().forEach(p -> states.put(p,mc.level.getBlockState(MinecraftWorld.nativePos(p))));
                beforeStates=Map.copyOf(states);
            }
            var hit=world.hit(target,mc.player.getEyePosition());
            if (hit==null) throw new IllegalArgumentException("Logging target is not visible");
            face=hit.getDirection(); miningHit=hit.getLocation();
            progress=original.getDestroyProgress(mc.player,mc.level,MinecraftWorld.nativePos(target));
            if (!Float.isFinite(progress) || progress<=0) throw new IllegalArgumentException("Native axe cannot break this tree block");
        }
    }
    void begin(Minecraft mc,ServerObservations observations) {
        if (planting) {
            BlockHitResult hit=plantHit(mc,target);
            if (hit==null) throw new IllegalArgumentException("The planting soil or single snow layer UP face is not visible");
            started=true; // A throwing native use may already have sent its request.
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            // START can itself complete an instant block, including when dispatch
            // throws after sending. Such a stroke can never use pre-STOP cancellation.
            stopped=progress>=1;
            started=true; // A throwing network call may already have sent its request.
            send(mc,observations,ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            // Instant native blocks are handled by START on the server. Never invent a STOP.
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }
    String advance(Minecraft mc,MinecraftWorld world,ServerObservations observations,Context context) {
        if (generation!=observations.generation()) return "벌목 도중 연결이 변경되었습니다.";
        if (mc.player.containerMenu!=mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty())
            return "벌목 중 도구·손 또는 인벤토리가 바뀌었습니다.";
        if (planting) return null;
        // The previous confirmed chop's inventory wear can arrive after this
        // stroke's START. Damage-only monotonic wear does not prove this stroke
        // succeeded and cannot bypass its current ray or pre-STOP checks.
        boolean axeMatches=axeWearCompatible(NativeLoggingRecipe.stack(held),NativeLoggingRecipe.stack(mc.player.getMainHandItem()));
        boolean authorised=leafAction==null ? LoggingRules.allowed(context) && LoggingRules.base(context.profile(),target)
            : LoggingLeafRules.authorised(context,leafAction.stump(),leafAction.pos());
        // AIR may already be applied before its raw reply is observed. After STOP,
        // keep the admitted hit point in ordinary reach without requiring the removed
        // leaf to remain ray-hittable. This is not evidence of completion.
        boolean inReach=leafAction!=null && stopped
            ? mc.player.getEyePosition().distanceTo(miningHit)<=Math.min(4,mc.gameMode.getPickRange())
            : world.canInteract(target,4);
        String boundary=miningContinuationRejection(authorised,
            mc.player.onGround(),mc.player.isShiftKeyDown(),inReach,
            mc.player.getInventory().selected==heldIndex,world.loggingAxe(heldIndex),axeMatches);
        if (boundary!=null) return boundary;
        // STOP is never replayed. Keep checking the operating boundary while its
        // raw server confirmation is pending; failure takes the normal one-ABORT path.
        if (stopped) return null;
        if (!original.equals(mc.level.getBlockState(MinecraftWorld.nativePos(target))))
            return "벌목 대상 블록이 변경되었습니다.";
        if (leafAction!=null) {
            String rejection=LoggingLeafRules.rejection(leafAction,context);
            if (rejection!=null) return rejection;
        }
        float increment=original.getDestroyProgress(mc.player,mc.level,MinecraftWorld.nativePos(target));
        if (!Float.isFinite(increment) || increment<=0) return "네이티브 채굴 속도를 확인할 수 없습니다.";
        progress+=increment;
        if (progress>=1) {
            String rejection=leafAction==null ? LoggingRules.chopRejection(target,context) : LoggingLeafRules.rejection(leafAction,context);
            if (rejection!=null) return rejection;
            stopped=true; send(mc,observations,ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        }
        return null;
    }
    static boolean miningContinuationAllowed(boolean authorised,boolean grounded,boolean crouching,boolean inReach,boolean selectedAxe) {
        return authorised && grounded && !crouching && inReach && selectedAxe;
    }
    static String miningContinuationRejection(boolean authorised,boolean grounded,boolean crouching,boolean inReach,
            boolean sameSlot,boolean registeredAxe,boolean compatibleAxe) {
        String reason;
        if (!authorised) reason="벌목 대상의 등록·실행 권한이 변경되었습니다 (AUTHORITY).";
        else if (!grounded) reason="벌목 중 지면 착지가 확인되지 않습니다 (GROUND).";
        else if (crouching) reason="벌목 중 웅크린 자세로 바뀌었습니다 (CROUCH).";
        else if (!inReach) reason="벌목 대상의 실제 시야·도달 거리가 확인되지 않습니다 (REACH).";
        else if (!sameSlot) reason="벌목 중 선택한 단축바 슬롯이 변경되었습니다 (SLOT).";
        else if (!registeredAxe) reason="등록한 사용 가능한 벌목 도끼가 확인되지 않습니다 (AXE).";
        else if (!compatibleAxe) reason="벌목 도끼의 마모 외 항목·메타데이터가 바뀌거나 마모가 감소했습니다 (AXE_METADATA).";
        else return null;
        return reason+" 재전송하지 않고 멈춥니다.";
    }
    /** Compatibility alias retained for the original post-STOP regression contract. */
    static boolean axeAfterStop(InventoryConsolidation.Stack before,InventoryConsolidation.Stack after) {
        return axeWearCompatible(before,after);
    }
    /** Unchanged axe or positive Damage-only wear, independent of packet arrival order. Never a completion proof. */
    static boolean axeWearCompatible(InventoryConsolidation.Stack before,InventoryConsolidation.Stack after) {
        if (before==null || after==null || before.empty() || after.empty() || before.count()!=1 || after.count()!=1
            || before.limit()!=after.limit()) return false;
        try {
            CompoundTag old=TagParser.parseTag(before.identity()),now=TagParser.parseTag(after.identity());
            if (!LoggingRules.AXE.equals(old.getString("id")) || !LoggingRules.AXE.equals(now.getString("id"))) return false;
            if (old.contains("tag") && !old.contains("tag",Tag.TAG_COMPOUND) || now.contains("tag") && !now.contains("tag",Tag.TAG_COMPOUND)) return false;
            CompoundTag oldTag=old.getCompound("tag"),newTag=now.getCompound("tag");
            if (oldTag.contains("Damage") && !oldTag.contains("Damage",Tag.TAG_INT)
                || newTag.contains("Damage") && !newTag.contains("Damage",Tag.TAG_INT)) return false;
            int oldDamage=oldTag.getInt("Damage"),newDamage=newTag.getInt("Damage");
            if (oldDamage<0 || newDamage<oldDamage) return false;
            oldTag.remove("Damage"); newTag.remove("Damage");
            if (oldTag.isEmpty()) old.remove("tag"); else old.put("tag",oldTag);
            if (newTag.isEmpty()) now.remove("tag"); else now.put("tag",newTag);
            return old.equals(now);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { return false; }
    }
    boolean confirmed(ServerObservations observations) {
        if(!started)return false;
        return receipt.confirmed(observations.generation(),()->actualSuccess(observations));
    }
    /** Processing an acknowledged ABORT settles only the cancelled pre-STOP stroke, never its success. */
    boolean cancellationSettled(ServerObservations observations) {
        return LoggingActionReceipt.cancelledBeforeStop(planting,started,stopped,aborted,abortDispatched,abortForwarded,
            generation,observations.generation(),beforeSequence,abortBeforeSequence,abortPacketSequence,
            observations.nativeBlockActionsProcessed());
    }
    void packetForwarded(Object packet) { if(packet==abortPacket && packet!=null)abortForwarded=true; }
    private boolean actualSuccess(ServerObservations observations) {
        if (leafAction!=null) {
            return leafClearedConfirmed(started,original.is(Blocks.SPRUCE_LEAVES),held.is(Items.NETHERITE_AXE),
                generation,observations.generation(),beforeSequence,target,
                observations.nativeBlocksSince(beforeSequence).stream().map(s -> new LeafBlockAck(s.seq(),s.pos(),
                    s.state().is(Blocks.AIR))).toList());
        }
        if (planting) {
            // Placement is a world postcondition, not an inventory transfer. Concurrent
            // falling-tree pickups may coalesce away the transient held-count decrement.
            // Only raw server block/BE replies enter this proof; client prediction does not.
            return plantingConfirmed(started,original.isAir(),singleSnowLayer(original),!held.isEmpty() && held.is(Items.SPRUCE_SAPLING),
                generation,observations.generation(),beforeSequence,target,
                observations.nativeBlocksSince(beforeSequence).stream().map(s -> new PlantBlockAck(s.seq(),s.pos(),
                    s.state().is(Blocks.SPRUCE_SAPLING) || s.state().is(Blocks.SPRUCE_LOG),
                    NativeSnowPlanting.singleLayerWrapper(s.state()))).toList(),
                observations.nativeSnowPlantsSince(beforeSequence).stream().map(s -> new PlantSnowAck(s.seq(),s.pos(),s.spruceSapling())).toList());
        }
        for (var ack:observations.nativeChopsSince(beforeSequence)) {
            Integer before=beforeChops.get(ack.pos());
            if (before!=null && ack.chops()>before && Block.stateById(ack.originalState()).is(Blocks.SPRUCE_LOG)) return true;
        }
        return observations.nativeBlocksSince(beforeSequence).stream().anyMatch(s -> beforeStates.containsKey(s.pos())
            && (s.state().isAir() && !beforeStates.get(s.pos()).isAir() || beforeStates.get(s.pos()).is(Blocks.SPRUCE_LOG)
                && LoggingRules.CHOPPED_LOG.equals(BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString())));
    }
    record PlantBlockAck(long sequence,Pos pos,boolean planted,boolean snowWrapper) {
        PlantBlockAck(long sequence,Pos pos,boolean planted) { this(sequence,pos,planted,false); }
    }
    record PlantSnowAck(long sequence,Pos pos,boolean spruceSapling) { }
    record LeafBlockAck(long sequence,Pos pos,boolean air) { }
    /** Latest exact-target raw server AIR only; no tree-chop, inventory or client prediction proof. */
    static boolean leafClearedConfirmed(boolean started,boolean originalSpruceLeaf,boolean axeHeld,
            long generation,long currentGeneration,long beforeSequence,Pos target,List<LeafBlockAck> replies) {
        if (!started || !originalSpruceLeaf || !axeHeld || generation!=currentGeneration || target==null || replies==null) return false;
        LeafBlockAck latest=null;
        Set<Long> targetSequences=new HashSet<>();
        for (LeafBlockAck reply:replies) {
            if (reply==null || reply.pos()==null) return false;
            if (reply.sequence()>beforeSequence && target.equals(reply.pos())) {
                if (!targetSequences.add(reply.sequence())) return false;
                if (latest==null || reply.sequence()>latest.sequence()) latest=reply;
            }
        }
        return latest!=null && latest.air();
    }
    /** Reduced raw-block proof, kept pure so stale/latest/connection guards can be tested without a running registry. */
    static boolean plantingConfirmed(boolean started,boolean originalAir,boolean originalSingleSnowLayer,boolean saplingHeld,
            long generation,long currentGeneration,long beforeSequence,Pos target,List<PlantBlockAck> replies) {
        return plantingConfirmed(started,originalAir,originalSingleSnowLayer,saplingHeld,generation,currentGeneration,
            beforeSequence,target,replies,List.of());
    }
    static boolean plantingConfirmed(boolean started,boolean originalAir,boolean originalSingleSnowLayer,boolean saplingHeld,
            long generation,long currentGeneration,long beforeSequence,Pos target,List<PlantBlockAck> replies,List<PlantSnowAck> snowReplies) {
        if (!started || (!originalAir && !originalSingleSnowLayer) || !saplingHeld
            || generation!=currentGeneration || target==null || replies==null || snowReplies==null) return false;
        PlantBlockAck latest=null;
        Set<Long> targetSequences=new HashSet<>();
        for (PlantBlockAck reply:replies) {
            if (reply==null || reply.pos()==null) return false;
            if (reply.sequence()>beforeSequence && target.equals(reply.pos())) {
                if (!targetSequences.add(reply.sequence())) return false;
                if (latest==null || reply.sequence()>latest.sequence()) latest=reply;
            }
        }
        if (latest==null) return false;
        if (latest.planted()) return true;
        if (!latest.snowWrapper()) return false;
        // Prediction settlement may repeat an identical wrapper BLOCK after its
        // BE packet. Keep that continuous wrapper incarnation, but never carry
        // its BE proof across an intervening AIR/foreign/multilayer block.
        long barrier=beforeSequence;
        for (PlantBlockAck reply:replies)
            if (target.equals(reply.pos()) && !reply.snowWrapper() && reply.sequence()<latest.sequence())
                barrier=Math.max(barrier,reply.sequence());
        long wrapperStart=latest.sequence();
        for (PlantBlockAck reply:replies)
            if (target.equals(reply.pos()) && reply.snowWrapper() && reply.sequence()>barrier)
                wrapperStart=Math.min(wrapperStart,reply.sequence());
        PlantSnowAck latestSnow=null;
        targetSequences.clear();
        for (PlantSnowAck reply:snowReplies) {
            if (reply==null || reply.pos()==null) return false;
            if (reply.sequence()>wrapperStart && target.equals(reply.pos())) {
                if (!targetSequences.add(reply.sequence())) return false;
                if (latestSnow==null || reply.sequence()>latestSnow.sequence()) latestSnow=reply;
            }
        }
        return latestSnow!=null && latestSnow.spruceSapling();
    }
    void abort(Minecraft mc,ServerObservations observations) {
        if (!planting && started && !aborted && generation==observations.generation() && mc.getConnection()!=null) {
            aborted=true;abortBeforeSequence=observations.sequence();
            send(mc,observations,ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
            abortDispatched=true;
        }
    }
    private void send(Minecraft mc,ServerObservations observations,ServerboundPlayerActionPacket.Action action) {
        // Read one exact-type field; its mapped name differs between development and production.
        // Allocating a normal sequence does not predict or alter any client block state.
        try {
            Field[] fields=Arrays.stream(net.minecraft.client.multiplayer.ClientLevel.class.getDeclaredFields()).filter(f -> f.getType()==BlockStatePredictionHandler.class).toArray(Field[]::new);
            if (fields.length!=1) throw new IllegalStateException("Ambiguous native prediction handler");
            fields[0].setAccessible(true);
            try (BlockStatePredictionHandler prediction=((BlockStatePredictionHandler)fields[0].get(mc.level)).startPredicting()) {
                var packet=new ServerboundPlayerActionPacket(action,MinecraftWorld.nativePos(target),face,prediction.currentSequence());
                if(action==ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK) {
                    abortPacket=packet;abortPacketSequence=prediction.currentSequence();
                }
                observations.permitLoggingPacket(packet);
                mc.getConnection().send(packet);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native mining sequence unavailable",failure); }
    }
    static boolean canPlant(Minecraft mc,Pos target) {
        return canPlant(mc,target,3.25);
    }
    static boolean canPlant(Minecraft mc,Pos target,double reach) {
        return mc!=null && mc.player!=null && canPlantFrom(mc,target,mc.player.getEyePosition(),reach);
    }
    /** Geometry-only query for a candidate stance; this does not authorize or send an action. */
    static boolean canPlantFrom(Minecraft mc,Pos target,Vec3 eye,double reach) {
        return plantHitFrom(mc,target,eye,reach)!=null;
    }
    static BlockHitResult plantHit(Minecraft mc,Pos target) {
        return mc==null || mc.player==null ? null : plantHitFrom(mc,target,mc.player.getEyePosition(),3.25);
    }
    private static boolean singleSnowLayer(BlockState state) {
        return state!=null && state.is(Blocks.SNOW) && state.hasProperty(SnowLayerBlock.LAYERS)
            && state.getValue(SnowLayerBlock.LAYERS)==1;
    }
    private static BlockHitResult plantHitFrom(Minecraft mc,Pos target,Vec3 eye,double reach) {
        if (mc==null || mc.level==null || mc.player==null || mc.gameMode==null || target==null) return null;
        var base=MinecraftWorld.nativePos(target);
        var soil=base.below();
        if (!mc.level.hasChunkAt(base) || !mc.level.hasChunkAt(soil)) return null;
        try {
            BlockState cell=mc.level.getBlockState(base);
            boolean snow=singleSnowLayer(cell);
            if (!cell.isAir() && !snow) return null;
            if (!Blocks.SPRUCE_SAPLING.defaultBlockState().canSurvive(mc.level,base)) return null;
            // The native first ray hit is the real snow outline, not soil hidden
            // beneath it. Ordinary placement replaces that one layer in-place.
            var surface=snow ? base : soil;
            var shape=mc.level.getBlockState(surface).getShape(mc.level,surface,CollisionContext.of(mc.player));
            BlockHitResult hit=NativeLoggingPlantHit.nearest(surface,eye,shape.toAabbs(),Math.min(reach,mc.gameMode.getPickRange()),
                end -> mc.level.clip(new ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player)));
            if (hit==null) return null;
            // A stance query can run before saplings have been selected. This is
            // a read-only placement context; dispatch separately checks the real hand.
            var placement=new BlockPlaceContext(mc.player,InteractionHand.MAIN_HAND,new ItemStack(Items.SPRUCE_SAPLING),hit);
            return NativeLoggingPlantHit.placementTargetsCell(base,hit,placement.getClickedPos(),snow,
                placement.replacingClickedOnBlock(),cell.canBeReplaced(placement),placement.canPlace()) ? hit : null;
        } catch (RuntimeException unknownGeometry) { return null; }
    }
}
