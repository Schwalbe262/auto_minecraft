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
    public Integer wineYear() { return VineryClock.year(mc.level); }
    public HarvestFootprint harvestFootprint(Pos target) { return NativeHarvestFootprint.inspect(mc.player,mc.level,target); }
    public String loggingTreeRejection(Pos target,List<LoggingPlot> plots) { return NativeLoggingTree.inspect(mc.level,target,plots).rejection(); }
    public boolean canPlantLoggingSapling(Pos target) { return NativeLoggingActions.canPlant(mc,target); }
    public boolean canPlantLoggingSapling(Pos target,double reach) { return NativeLoggingActions.canPlant(mc,target,reach); }
    public boolean canPlantLoggingSaplingFrom(Pos feet,Pos target,double reach) {
        if (mc.player==null || feet==null || !loaded(feet) || !canStand(feet)) return false;
        double surface=standingY(feet);
        if (!Double.isFinite(surface)) return false;
        Vec3 eye=new Vec3(feet.x()+.5,surface+mc.player.getEyeHeight(),feet.z()+.5);
        return NativeLoggingActions.canPlantFrom(mc,target,eye,reach);
    }
    public boolean canLoggingJump(LoggingJumpEdge edge,Profile profile) { return NativeLoggingJump.mayTraverse(mc,this,edge,profile); }
    @Override public boolean standardDescentPhysics() {
        // Reuse the read-only native physics gate, not its jump permission:
        // normal gravity/dimensions and no flight, fluids or fall-altering effects.
        try { return mc.level!=null && NativeLoggingJump.normalPhysics(mc); }
        catch (RuntimeException unsupported) { return false; }
    }
    @Override public boolean canStepUp(LoggingJumpEdge edge,Profile profile) { return NativeLoggingJump.mayStepUp(mc,this,edge,profile); }
    @Override public boolean canChainDescent(List<Pos> feet,Profile profile) { return NativeDescentChain.mayChain(mc,this,feet,profile); }
    @Override public boolean canFlowDescent(List<Pos> feet,Profile profile) { return NativeDescentChain.mayFlow(mc,this,feet,profile); }
    @Override public boolean straightDescentStair(Pos from,Pos to,Profile profile) { return NativeDescentChain.mayUseHalfSteps(mc,this,from,to,profile); }
    public boolean loggingAxe(int inventoryIndex) {
        return mc.player!=null && inventoryIndex>=0 && inventoryIndex<9
            && mc.player.getInventory().getItem(inventoryIndex).is(net.minecraft.world.item.Items.NETHERITE_AXE)
            && mc.player.getInventory().getItem(inventoryIndex).getItem() instanceof net.minecraft.world.item.AxeItem;
    }
    public boolean loggingCraftingMenu() { return mc.player!=null && NativeLoggingRecipe.menu(mc.player.containerMenu); }
    public boolean loggingCraftingGridEmpty() { return mc.player!=null && NativeLoggingRecipe.gridEmpty(mc.player.containerMenu); }
    public String loggingItemFingerprint(int inventoryIndex) {
        if (mc.player==null || inventoryIndex<0 || inventoryIndex>=36) return null;
        try {
            String tag=mc.player.getInventory().getItem(inventoryIndex).save(new CompoundTag()).toString();
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { return null; }
    }
    public List<GroundItem> groundItems() {
        if (mc.level==null || mc.player==null) return List.of();
        return mc.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,mc.player.getBoundingBox().inflate(48))
            .stream().filter(e -> e.isAlive()).map(e -> new GroundItem(e.getId(),e.getX(),e.getY(),e.getZ(),item(e.getItem()))).toList();
    }
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
        String blockId=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (entity instanceof Container || entity instanceof MenuProvider
                || NativeSmartShippingInventory.expectedSlots(blockId,entity)==SmartShippingRules.STORAGE_SLOTS)
            properties.put("container","true");
        return new BlockData(pos,blockId,properties);
    }
    public boolean loaded(Pos p) { return mc.level!=null && mc.level.hasChunkAt(nativePos(p)) && p.y()>=mc.level.getMinBuildHeight() && p.y()<mc.level.getMaxBuildHeight(); }
    /** WorldBorder's AABB overlap helper is insufficient: require the whole swept body inside. */
    boolean insideBorder(AABB body) {
        if (mc.level==null || body==null) return false;
        var border=mc.level.getWorldBorder();
        return body.minX>=border.getMinX() && body.maxX<=border.getMaxX()
            && body.minZ>=border.getMinZ() && body.maxZ<=border.getMaxZ();
    }
    private boolean door(BlockState state) { return state.getBlock() instanceof DoorBlock && !state.is(Blocks.IRON_DOOR); }
    private boolean hazard(BlockState s) {
        return !s.getFluidState().isEmpty() || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS)
            || s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.POWDER_SNOW)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE);
    }
    public boolean canStand(Pos p) {
        if (mc.level==null || mc.player==null || !loaded(p) || !loaded(p.offset(0,1,0)) || !loaded(p.offset(0,-1,0))) return false;
        double radius=mc.player.getBbWidth()/2.0;
        if (!insideBorder(new AABB(p.x()+.5-radius,p.y(),p.z()+.5-radius,
                p.x()+.5+radius,p.y()+mc.player.getBbHeight(),p.z()+.5+radius))) return false;
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
        if (dx+dz!=1 || Math.abs(dy)>1 || !canStand(from) || !canStand(to)) return false;
        double stepHeight=mc.player.maxUpStep();
        double previous=standingY(from);
        BlockState destinationFloor=mc.level.getBlockState(nativePos(to).below());
        // Uniform flat floor boxes cover ordinary terrain/farmland without repeated shape sampling.
        if (dy==0 && uniformFloor(from) && uniformFloor(to))
            return WalkingSurfaceRules.canStep(previous,standingY(to),stepHeight,destinationFloor.getBlock() instanceof FarmBlock);
        int minSupportY=Math.min(from.y(),to.y())-2, maxSupportY=Math.max(from.y(),to.y())-1;
        for (int i=1;i<=10;i++) {
            double amount=i/10.0, x=from.x()+0.5+(to.x()-from.x())*amount, z=from.z()+0.5+(to.z()-from.z())*amount;
            Surface next=surfaceAt(x,z,minSupportY,maxSupportY);
            if (!WalkingSurfaceRules.canStep(previous,next.height(),stepHeight,next.farmland())
                || !clearBodyAt(x,next.height(),z)) return false;
            previous=next.height();
        }
        return Math.abs(previous-standingY(to))<1.0e-4;
    }
    public boolean canTraverseDiagonal(Pos from,Pos to) {
        int dx=to.x()-from.x(),dz=to.z()-from.z();
        if (mc.level==null || mc.player==null || from.y()!=to.y() || Math.abs(dx)!=1 || Math.abs(dz)!=1) return false;
        double height=standingY(from);
        if (!Double.isFinite(height)) return false;
        for (Pos cell:new Pos[]{from,from.offset(dx,0,0),from.offset(0,0,dz),to}) {
            // Flat full-footprint floor boxes only: stairs, partial-edge supports,
            // doors and changing heights retain the existing cardinal traversal.
            if (!canStand(cell) || !uniformFloor(cell) || Math.abs(standingY(cell)-height)>1.0e-4) return false;
            for (int dy=0;dy<=1;dy++)
                if (mc.level.getBlockState(nativePos(cell.offset(0,dy,0))).getBlock() instanceof DoorBlock) return false;
        }
        // Sample the actual player-width body along the diagonal, including both
        // corner-adjacent cells. No point may lose support, step, fall or collide.
        for (int i=0;i<=16;i++) {
            double amount=i/16.0,x=from.x()+.5+dx*amount,z=from.z()+.5+dz*amount;
            Surface surface=surfaceAt(x,z,from.y()-2,from.y()-1);
            if (!Double.isFinite(surface.height()) || Math.abs(surface.height()-height)>1.0e-4
                    || !clearBodyAt(x,height,z)) return false;
        }
        return true;
    }
    public double standingY(Pos feet) {
        if (mc.level==null || !loaded(feet.offset(0,-1,0))) return Double.NaN;
        BlockPos floor=nativePos(feet).below();
        var shape=mc.level.getBlockState(floor).getCollisionShape(mc.level,floor);
        return shape.isEmpty() ? Double.NaN : floor.getY()+shape.max(net.minecraft.core.Direction.Axis.Y);
    }
    private boolean uniformFloor(Pos feet) {
        BlockPos floor=nativePos(feet).below();
        var boxes=mc.level.getBlockState(floor).getCollisionShape(mc.level,floor).toAabbs();
        if (boxes.size()!=1) return false;
        AABB box=boxes.get(0);
        return box.minX==0 && box.minZ==0 && box.maxX==1 && box.maxZ==1;
    }
    private record Surface(double height,boolean farmland) { }
    private Surface surfaceAt(double x,double z,int minY,int maxY) {
        double radius=mc.player.getBbWidth()/2.0-1.0e-5, height=Double.NEGATIVE_INFINITY;
        boolean farmland=false;
        for (int bx=(int)Math.floor(x-radius);bx<=(int)Math.floor(x+radius);bx++)
            for (int bz=(int)Math.floor(z-radius);bz<=(int)Math.floor(z+radius);bz++)
                for (int by=minY;by<=maxY;by++) {
                    Pos p=new Pos(bx,by,bz);
                    if (!loaded(p)) return new Surface(Double.NaN,false);
                    BlockPos bp=nativePos(p);
                    BlockState state=mc.level.getBlockState(bp);
                    if (door(state)) continue;
                    for (AABB box:state.getCollisionShape(mc.level,bp,CollisionContext.of(mc.player)).toAabbs()) {
                        if (bx+box.maxX<=x-radius || bx+box.minX>=x+radius || bz+box.maxZ<=z-radius || bz+box.minZ>=z+radius) continue;
                        double top=by+box.maxY;
                        if (top>height) { height=top; farmland=state.getBlock() instanceof FarmBlock; }
                    }
                }
        return new Surface(height,farmland);
    }
    private boolean clearBodyAt(double x,double y,double z) {
        double radius=mc.player.getBbWidth()/2.0-1.0e-5;
        AABB body=new AABB(x-radius,y+1.0e-5,z-radius,x+radius,y+mc.player.getBbHeight()-1.0e-5,z+radius);
        if (!insideBorder(body)) return false;
        for (int bx=(int)Math.floor(body.minX);bx<=(int)Math.floor(body.maxX);bx++)
            for (int bz=(int)Math.floor(body.minZ);bz<=(int)Math.floor(body.maxZ);bz++)
                for (int by=(int)Math.floor(body.minY);by<=(int)Math.floor(body.maxY);by++) {
                    Pos p=new Pos(bx,by,bz);
                    if (!loaded(p)) return false;
                    BlockPos bp=nativePos(p);
                    BlockState state=mc.level.getBlockState(bp);
                    if (hazard(state)) return false;
                    if (door(state)) continue; // LocalNavigator opens registered-path doors before walking.
                    for (AABB box:state.getCollisionShape(mc.level,bp,CollisionContext.of(mc.player)).toAabbs())
                        if (body.intersects(box.move(bx,by,bz))) return false;
                }
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
        BlockState state=mc.level.getBlockState(bp);
        if (LoggingRules.CHOPPED_LOG.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())) {
            if (mc.gameMode==null) return null;
            return NativeChoppedLogHit.nearest(bp,eye,state.getShape(mc.level,bp,CollisionContext.of(mc.player)).toAabbs(),
                Math.min(4,mc.gameMode.getPickRange()),end -> mc.level.clip(
                    new ClipContext(eye,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player)));
        }
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
        double surface=standingY(feet);
        if (!Double.isFinite(surface)) return false;
        if (reach<=1.25) return WalkingSurfaceRules.positionalDistance(feet,surface,target)<=reach;
        Vec3 eye=new Vec3(feet.x()+0.5,surface+mc.player.getEyeHeight(),feet.z()+0.5);
        BlockHitResult hit=hit(target,eye);
        return hit!=null && hit.getLocation().distanceTo(eye)<=Math.min(4,reach);
    }
}
