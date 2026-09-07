package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;

public final class MinecraftWorld implements WorldAccess {
    private final Minecraft mc=Minecraft.getInstance();
    private long ticks;
    public void advanceTick() { ticks++; }
    public long tick() { return ticks; }
    public long dayTime() { return mc.level==null ? 0 : mc.level.getDayTime(); }
    public static BlockPos nativePos(Pos p) { return new BlockPos(p.x(),p.y(),p.z()); }
    public static Pos pos(BlockPos p) { return new Pos(p.getX(),p.getY(),p.getZ()); }
    public PlayerState player() {
        if (mc.player==null) return new PlayerState(0,0,0,0,0,false,false,0,0,0,false,mc.isWindowActive());
        var p=mc.player;
        return new PlayerState(p.getX(),p.getY(),p.getZ(),p.getYRot(),p.getXRot(),p.onGround(),p.isSleeping(),p.getHealth(),p.getFoodData().getFoodLevel(),p.getInventory().selected,mc.level!=null,mc.isWindowActive());
    }
    public BlockData block(Pos pos) {
        if (!loaded(pos)) return new BlockData(pos,"autovalley:unloaded",Map.of());
        BlockPos bp=nativePos(pos);
        BlockState state=mc.level.getBlockState(bp);
        Map<String,String> properties=new HashMap<>();
        state.getValues().forEach((key,value) -> properties.put(key.getName(),value.toString().toLowerCase(Locale.ROOT)));
        var entity=mc.level.getBlockEntity(bp);
        if (entity instanceof Container || entity instanceof MenuProvider) properties.put("container","true");
        return new BlockData(pos,BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),properties);
    }
    public boolean loaded(Pos p) { return mc.level!=null && mc.level.hasChunkAt(nativePos(p)) && p.y()>=mc.level.getMinBuildHeight() && p.y()<mc.level.getMaxBuildHeight(); }
    private boolean door(BlockState state) { return state.getBlock() instanceof DoorBlock && !state.is(Blocks.IRON_DOOR); }
    private boolean hazard(BlockState s) {
        return !s.getFluidState().isEmpty() || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS)
            || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.POWDER_SNOW)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE);
    }
    public boolean canStand(Pos p) {
        if (mc.level==null || mc.player==null || !loaded(p) || !loaded(p.offset(0,1,0)) || !loaded(p.offset(0,-1,0))) return false;
        BlockPos feet=nativePos(p);
        BlockState floor=mc.level.getBlockState(feet.below());
        BlockState low=mc.level.getBlockState(feet), high=mc.level.getBlockState(feet.above());
        if (hazard(floor) || hazard(low) || hazard(high)) return false;
        if (floor.getCollisionShape(mc.level,feet.below()).isEmpty()) return false;
        boolean passLow=door(low) || low.getCollisionShape(mc.level,feet,CollisionContext.of(mc.player)).isEmpty();
        boolean passHigh=door(high) || high.getCollisionShape(mc.level,feet.above(),CollisionContext.of(mc.player)).isEmpty();
        return passLow && passHigh;
    }
    public boolean canTraverse(Pos from, Pos to) {
        int dx=Math.abs(to.x()-from.x()), dz=Math.abs(to.z()-from.z()), dy=to.y()-from.y();
        if (dx+dz!=1 || Math.abs(dy)>1 || !canStand(to)) return false;
        if (dy>0) {
            Block lower=mc.level.getBlockState(nativePos(from).below()).getBlock();
            Block upper=mc.level.getBlockState(nativePos(to).below()).getBlock();
            if (!(lower instanceof StairBlock || lower instanceof SlabBlock || upper instanceof StairBlock || upper instanceof SlabBlock)) return false;
        }
        if (dy<0 && mc.level.getBlockState(nativePos(to).below()).getBlock() instanceof FarmBlock) return false;
        return true;
    }
    public List<BlockData> scan(Pos center,int radius,int vertical) {
        radius=Math.max(1,Math.min(32,radius)); vertical=Math.max(1,Math.min(16,vertical));
        List<BlockData> result=new ArrayList<>();
        for (int x=center.x()-radius;x<=center.x()+radius;x++)
            for (int z=center.z()-radius;z<=center.z()+radius;z++)
                for (int y=center.y()-vertical;y<=center.y()+vertical;y++) {
                    Pos p=new Pos(x,y,z);
                    if (!loaded(p)) continue;
                    BlockState state=mc.level.getBlockState(nativePos(p));
                    if (state.isAir()) continue;
                    // Do not allocate snapshots for terrain during a user-triggered scan.
                    String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (id.contains("tomato") || id.contains("wine_keg") || id.contains("preserves_jar")
                        || id.contains("shipping_bin") || state.getBlock() instanceof BedBlock || state.hasBlockEntity()) result.add(block(p));
                }
        return result;
    }
    public static ItemData item(ItemStack stack) {
        if (stack.isEmpty()) return ItemData.EMPTY;
        CompoundTag tag=stack.getTag();
        int quality=tag!=null && tag.contains("quality_food",10) ? tag.getCompound("quality_food").getInt("quality") : 0;
        Integer year=tag!=null && tag.contains("Year",3) ? tag.getInt("Year") : null;
        return new ItemData(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),stack.getCount(),quality,year,
            stack.getItem() instanceof HoeItem,stack.isDamageableItem() ? stack.getMaxDamage()-stack.getDamageValue() : Integer.MAX_VALUE);
    }
    public List<ItemSlot> inventory() {
        List<ItemSlot> result=new ArrayList<>();
        if (mc.player!=null) for (int i=0;i<36;i++) result.add(new ItemSlot(i,i,true,item(mc.player.getInventory().getItem(i))));
        return result;
    }
    public MenuData menu() {
        if (mc.player==null) return null;
        var menu=mc.player.containerMenu;
        List<ItemSlot> slots=new ArrayList<>();
        for (Slot s : menu.slots) {
            boolean owned=s.container==mc.player.getInventory();
            slots.add(new ItemSlot(s.index,owned ? s.getContainerSlot() : -1,owned,item(s.getItem())));
        }
        return new MenuData(menu.containerId,menu.getStateId(),slots,item(menu.getCarried()),menu!=mc.player.inventoryMenu);
    }
    public boolean mayPlace(int index, ItemData data) {
        if (mc.player==null || index<0 || index>=mc.player.containerMenu.slots.size()) return false;
        Slot slot=mc.player.containerMenu.getSlot(index);
        var registered=BuiltInRegistries.ITEM.getOptional(new ResourceLocation(data.id()));
        if (registered.isEmpty()) return false;
        ItemStack stack=new ItemStack(registered.get());
        if (data.quality()!=0) { CompoundTag quality=new CompoundTag(); quality.putInt("quality",data.quality()); stack.getOrCreateTag().put("quality_food",quality); }
        if (data.year()!=null) stack.getOrCreateTag().putInt("Year",data.year());
        return slot.mayPlace(stack);
    }
    public BlockHitResult hit(Pos target, Vec3 eye) {
        if (mc.level==null || mc.player==null) return null;
        BlockPos bp=nativePos(target);
        // Try exposed surfaces, including thin tomato stems and inset artisan machine models.
        for (double y : new double[]{0.5,0.8,0.25})
            for (double x : new double[]{0.5,0.25,0.75})
                for (double z : new double[]{0.5,0.25,0.75}) {
                    Vec3 end=new Vec3(target.x()+x,target.y()+y,target.z()+z);
                    var hit=mc.level.clip(new ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
                    if (hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(bp)) return hit;
                }
        return null;
    }
    public boolean canInteract(Pos target,double reach) {
        if (mc.player==null || !loaded(target)) return false;
        if (reach<=1.25) return player().distance(target)<=reach;
        BlockHitResult hit=hit(target,mc.player.getEyePosition());
        return hit!=null && hit.getLocation().distanceTo(mc.player.getEyePosition())<=Math.min(reach,mc.gameMode.getPickRange());
    }
    public boolean canInteractFrom(Pos feet,Pos target,double reach) {
        if (!loaded(target)) return false;
        if (reach<=1.25) return feet.distanceSquared(target)<=reach*reach;
        Vec3 eye=new Vec3(feet.x()+0.5,feet.y()+1.62,feet.z()+0.5);
        BlockHitResult hit=hit(target,eye);
        return hit!=null && hit.getLocation().distanceTo(eye)<=Math.min(4,reach);
    }
}
