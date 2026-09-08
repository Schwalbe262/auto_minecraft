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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** One narrowly admitted native mining stroke or one UP-face sapling use. */
final class NativeLoggingActions {
    final long generation,beforeSequence;
    final Pos target;
    private final boolean planting;
    private final ItemStack held;
    private final int heldIndex,menuId,sourceMenuSlot;
    private final BlockState original;
    private final Map<Pos,Integer> beforeChops;
    private final Map<Pos,BlockState> beforeStates;
    private final Direction face;
    private float progress;
    private boolean started,stopped,aborted;

    NativeLoggingActions(Minecraft mc,MinecraftWorld world,ServerObservations observations,Action action,Context context) {
        if (mc.player==null || mc.level==null || mc.player.containerMenu!=mc.player.inventoryMenu
            || !mc.player.inventoryMenu.getCarried().isEmpty() || mc.player.isCreative() || mc.player.isSpectator())
            throw new IllegalArgumentException("Logging requires the normal survival inventory");
        planting=action instanceof Action.PlantSapling;
        target=planting ? ((Action.PlantSapling)action).pos() : ((Action.ChopTree)action).pos();
        generation=observations.generation(); beforeSequence=observations.sequence();
        heldIndex=mc.player.getInventory().selected; held=mc.player.getMainHandItem().copy(); menuId=mc.player.inventoryMenu.containerId;
        sourceMenuSlot=mc.player.inventoryMenu.slots.stream().filter(s -> s.container==mc.player.getInventory() && s.getContainerSlot()==heldIndex)
            .mapToInt(s -> s.index).findFirst().orElseThrow();
        original=mc.level.getBlockState(MinecraftWorld.nativePos(target));
        if (planting) {
            if (!held.is(Items.SPRUCE_SAPLING) || !canPlant(mc,target)) throw new IllegalArgumentException("Sapling planting position changed");
            face=Direction.UP; beforeChops=Map.of(); beforeStates=Map.of();
        } else {
            if (mc.player.isShiftKeyDown() || !mc.player.onGround()) throw new IllegalArgumentException("Logging requires standing without crouching");
            var proof=NativeLoggingTree.inspect(mc.level,target,context.profile().loggingPlots);
            if (!proof.safe()) throw new IllegalArgumentException(proof.rejection());
            beforeChops=proof.chops();
            Map<Pos,BlockState> states=new HashMap<>();
            beforeChops.keySet().forEach(p -> states.put(p,mc.level.getBlockState(MinecraftWorld.nativePos(p))));
            beforeStates=Map.copyOf(states);
            var hit=world.hit(target,mc.player.getEyePosition());
            if (hit==null) throw new IllegalArgumentException("Tree target is not visible");
            face=hit.getDirection();
            progress=original.getDestroyProgress(mc.player,mc.level,MinecraftWorld.nativePos(target));
            if (!Float.isFinite(progress) || progress<=0) throw new IllegalArgumentException("Native axe cannot break this tree block");
        }
    }
    void begin(Minecraft mc,ServerObservations observations) {
        started=true; // A throwing network call may already have sent its request.
        if (planting) {
            BlockHitResult hit=plantHit(mc,target);
            if (hit==null) throw new IllegalArgumentException("The planting soil UP face is not visible");
            mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
            mc.player.swing(InteractionHand.MAIN_HAND);
        } else {
            send(mc,observations,ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            // Instant native blocks are handled by START on the server. Never invent a STOP.
            stopped=progress>=1;
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
    }
    String advance(Minecraft mc,MinecraftWorld world,ServerObservations observations,Context context) {
        if (generation!=observations.generation()) return "벌목 도중 연결이 변경되었습니다.";
        if (mc.player.containerMenu!=mc.player.inventoryMenu || !mc.player.inventoryMenu.getCarried().isEmpty())
            return "벌목 중 도구·손 또는 인벤토리가 바뀌었습니다.";
        if (planting) return null;
        boolean axeMatches=stopped ? axeAfterStop(NativeLoggingRecipe.stack(held),NativeLoggingRecipe.stack(mc.player.getMainHandItem()))
            : ItemStack.matches(held,mc.player.getMainHandItem());
        if (!miningContinuationAllowed(LoggingRules.allowed(context) && LoggingRules.base(context.profile(),target),
                mc.player.onGround(),mc.player.isShiftKeyDown(),world.canInteract(target,4),
                mc.player.getInventory().selected==heldIndex && world.loggingAxe(heldIndex) && axeMatches))
            return "벌목 거리·자세 또는 대상 블록이 변경되었습니다.";
        // STOP is never replayed. Keep checking the operating boundary while its
        // raw server confirmation is pending; failure takes the normal one-ABORT path.
        if (stopped) return null;
        if (!original.equals(mc.level.getBlockState(MinecraftWorld.nativePos(target))))
            return "벌목 대상 블록이 변경되었습니다.";
        float increment=original.getDestroyProgress(mc.player,mc.level,MinecraftWorld.nativePos(target));
        if (!Float.isFinite(increment) || increment<=0) return "네이티브 채굴 속도를 확인할 수 없습니다.";
        progress+=increment;
        if (progress>=1) {
            String rejection=LoggingRules.chopRejection(target,context);
            if (rejection!=null) return rejection;
            stopped=true; send(mc,observations,ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
        }
        return null;
    }
    static boolean miningContinuationAllowed(boolean authorised,boolean grounded,boolean crouching,boolean inReach,boolean selectedAxe) {
        return authorised && grounded && !crouching && inReach && selectedAxe;
    }
    /** A server may update axe wear before its chop packet. Only that positive wear is tolerated after STOP. */
    static boolean axeAfterStop(InventoryConsolidation.Stack before,InventoryConsolidation.Stack after) {
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
        if (generation!=observations.generation()) return false;
        if (planting) {
            boolean planted=observations.nativeBlocksSince(beforeSequence).stream().anyMatch(s -> s.pos().equals(target)
                && (s.state().is(Blocks.SPRUCE_SAPLING) || s.state().is(Blocks.SPRUCE_LOG)));
            if (!planted) return false;
            for (var ack:observations.fullNativeMenuSnapshotsSince(menuId,beforeSequence))
                if (ack.carried().isEmpty() && sourceMenuSlot<ack.items().size() && consumedOne(ack.items().get(sourceMenuSlot))) return true;
            return observations.nativeSlotSnapshotsSince(menuId,beforeSequence).stream()
                .anyMatch(s -> s.slot()==sourceMenuSlot && s.appliedMenu().carried().isEmpty() && consumedOne(s.packetItem()));
        }
        for (var ack:observations.nativeChopsSince(beforeSequence)) {
            Integer before=beforeChops.get(ack.pos());
            if (before!=null && ack.chops()>before && Block.stateById(ack.originalState()).is(Blocks.SPRUCE_LOG)) return true;
        }
        return observations.nativeBlocksSince(beforeSequence).stream().anyMatch(s -> beforeStates.containsKey(s.pos())
            && (s.state().isAir() && !beforeStates.get(s.pos()).isAir() || beforeStates.get(s.pos()).is(Blocks.SPRUCE_LOG)
                && LoggingRules.CHOPPED_LOG.equals(BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString())));
    }
    private boolean consumedOne(ItemStack after) {
        return held.getCount()==1 && after.isEmpty() || !after.isEmpty() && ItemStack.isSameItemSameTags(held,after) && after.getCount()==held.getCount()-1;
    }
    void abort(Minecraft mc,ServerObservations observations) {
        if (!planting && started && !aborted && generation==observations.generation() && mc.getConnection()!=null) {
            aborted=true; send(mc,observations,ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
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
                observations.permitLoggingPacket(packet);
                mc.getConnection().send(packet);
            }
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native mining sequence unavailable",failure); }
    }
    static boolean canPlant(Minecraft mc,Pos target) {
        if (mc.level==null || mc.player==null || target==null || !mc.level.hasChunkAt(MinecraftWorld.nativePos(target))
            || !mc.level.hasChunkAt(MinecraftWorld.nativePos(target).below()) || !mc.level.getBlockState(MinecraftWorld.nativePos(target)).isAir()) return false;
        return Blocks.SPRUCE_SAPLING.defaultBlockState().canSurvive(mc.level,MinecraftWorld.nativePos(target)) && plantHit(mc,target)!=null;
    }
    private static BlockHitResult plantHit(Minecraft mc,Pos target) {
        if (mc.level==null || mc.player==null) return null;
        var soil=MinecraftWorld.nativePos(target).below();
        Vec3 point=new Vec3(target.x()+.5,target.y(),target.z()+.5),eye=mc.player.getEyePosition();
        if (eye.distanceTo(point)>Math.min(4,mc.gameMode.getPickRange())) return null;
        var ray=mc.level.clip(new ClipContext(eye,point.add(0,-.001,0),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
        return ray.getType()==HitResult.Type.BLOCK && ray.getBlockPos().equals(soil) && ray.getDirection()==Direction.UP
            ? new BlockHitResult(point,Direction.UP,soil,false) : null;
    }
}
