package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.navigation.ProfileBounds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.ForgeMod;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Shared read-only geometry for cardinal one-block ascents. Logging retains
 * its registered-only wrapper; general transit has a separate opt-in wrapper.
 * The destination riser is expected solid terrain, not a collision to erase:
 * ordinary player physics must lift the body over it. No position/velocity,
 * key binding, block, or network state is written by this helper.
 */
final class NativeLoggingJump {
    static final double CENTER_MARGIN=.12,MAX_RISE=1.35,EPS=1.0e-5;
    private static final InputPulse PULSE=new InputPulse();
    private static BooleanSupplier pulseCheck;
    private NativeLoggingJump() { }

    record Cell(boolean loaded,boolean bounded,boolean forbidden,boolean normalSurface,List<AABB> boxes) {
        Cell { boxes=List.copyOf(boxes); }
    }
    @FunctionalInterface interface Cells { Cell at(Pos pos); }

    static boolean mayTraverse(Minecraft mc,MinecraftWorld world,LoggingJumpEdge edge,Profile profile) {
        if (mc==null || mc.level==null || mc.player==null || !normalPhysics(mc)
                || !LoggingJumpRules.verifiedSupports(edge,world,profile)) return false;
        return inspectNative(mc,world,edge,profile,true);
    }

    static boolean mayStepUp(Minecraft mc,MinecraftWorld world,LoggingJumpEdge edge,Profile profile) {
        if (mc==null || mc.level==null || mc.player==null || !normalPhysics(mc)
                || !StepUpRules.verifiedSupports(edge,world,profile)) return false;
        return inspectNative(mc,world,edge,profile,false);
    }

    private static boolean inspectNative(Minecraft mc,MinecraftWorld world,LoggingJumpEdge edge,Profile profile,boolean logging) {
        try {
            ProfileBounds bounds=logging ? new ProfileBounds(profile) : null;
            Cells cells=pos -> {
                if (!world.loaded(pos)) return new Cell(false,false,true,false,List.of());
                BlockPos bp=MinecraftWorld.nativePos(pos);
                BlockState state=mc.level.getBlockState(bp);
                Block block=state.getBlock();
                boolean forbidden=forbiddenBlock(state) || protectedPlanting(profile,pos);
                return new Cell(true,bounds==null || bounds.contains(pos),forbidden,
                    normalSurface(block.getJumpFactor(),block.getSpeedFactor(),block.getFriction()),
                    state.getCollisionShape(mc.level,bp,CollisionContext.of(mc.player)).toAabbs());
            };
            double floor=world.standingY(edge.from());
            AABB sweep=sweep(edge,mc.player.getBbWidth(),mc.player.getBbHeight(),floor);
            return world.insideBorder(sweep)
                && geometry(edge,mc.player.getBbWidth(),mc.player.getBbHeight(),floor,
                world.standingY(edge.to()),cells) && mc.level.getEntityCollisions(mc.player,sweep).isEmpty();
        } catch (RuntimeException unsupported) { return false; }
    }

    static boolean normalPhysics(Minecraft mc) {
        var p=mc.player;
        return p!=null && p.isAlive() && !p.isSpectator() && !p.isCreative()
            && p.getPose()==Pose.STANDING && !p.getAbilities().flying && !p.isNoGravity()
            && !p.isInWaterOrBubble() && !p.isInLava() && !p.isSwimming() && !p.isPassenger()
            && !p.onClimbable() && !p.isSleeping() && !p.isCrouching() && !p.isFallFlying() && !p.isAutoSpinAttack()
            && !p.hasEffect(MobEffects.JUMP) && !p.hasEffect(MobEffects.LEVITATION) && !p.hasEffect(MobEffects.SLOW_FALLING)
            && Math.abs(p.getAttributeValue(ForgeMod.ENTITY_GRAVITY.get())-.08)<EPS
            && dimensions(p.getBbWidth(),p.getBbHeight());
    }

    static boolean normalSurface(double jump,double speed,double friction) {
        return Math.abs(jump-1)<EPS && Math.abs(speed-1)<EPS && Math.abs(friction-.6)<EPS;
    }
    static boolean protectedPlanting(Profile profile,Pos pos) {
        for (Farm farm:profile.farms)
            if (farm.contains(pos) || farm.contains(pos.offset(0,1,0))) return true;
        for (LoggingPlot plot:profile.loggingPlots)
            if (plot.containsTrunk(pos) || plot.containsTrunk(pos.offset(0,1,0))) return true;
        return false;
    }
    private static boolean forbiddenBlock(BlockState s) {
        Block b=s.getBlock();
        String id=BuiltInRegistries.BLOCK.getKey(b).toString();
        return !s.getFluidState().isEmpty() || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
            || b instanceof FarmBlock || b instanceof CropBlock || b instanceof StemBlock || b instanceof AttachedStemBlock
            || b instanceof SaplingBlock || s.is(BlockTags.CROPS)
            || id.equals("farmersdelight:tomatoes") || id.equals("farmersdelight:tomatoes_on_rope")
            || id.equals("farmersdelight:budding_tomatoes")
            || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.MAGMA_BLOCK)
            || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.POWDER_SNOW)
            || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.COBWEB)
            || s.is(Blocks.HONEY_BLOCK) || s.is(Blocks.SLIME_BLOCK);
    }
    static boolean dimensions(double width,double height) {
        // Known standing-player dimensions only; scaling/crawling mods need a separate proof.
        return Double.isFinite(width) && Double.isFinite(height)
            && Math.abs(width-.6)<EPS && Math.abs(height-1.8)<EPS;
    }
    static boolean advanceAfterLift(boolean launch,double feetHeight,double landingHeight,double horizontalDistance) {
        return !launch && Double.isFinite(feetHeight) && Double.isFinite(landingHeight) && Double.isFinite(horizontalDistance)
            && feetHeight>=landingHeight+EPS && horizontalDistance>.1;
    }
    static boolean stationaryForLaunch(double velocityX,double velocityZ) {
        // LivingEntity.aiStep zeroes each horizontal component strictly below .003.
        // Do not inherit a residual approach velocity into the narrow one-block flight.
        return Double.isFinite(velocityX) && Double.isFinite(velocityZ)
            && Math.abs(velocityX)<.003 && Math.abs(velocityZ)<.003;
    }
    static AABB sweep(LoggingJumpEdge edge,double width,double height,double floor) {
        double radius=width/2+CENTER_MARGIN;
        return new AABB(Math.min(edge.from().x(),edge.to().x())+.5-radius,floor+EPS,
            Math.min(edge.from().z(),edge.to().z())+.5-radius,
            Math.max(edge.from().x(),edge.to().x())+.5+radius,floor+MAX_RISE+height,
            Math.max(edge.from().z(),edge.to().z())+.5+radius);
    }
    static boolean geometry(LoggingJumpEdge edge,double width,double height,double fromFloor,double toFloor,Cells cells) {
        if (!LoggingJumpRules.validShape(edge) || !dimensions(width,height) || cells==null
                || !Double.isFinite(fromFloor) || !Double.isFinite(toFloor) || Math.abs(toFloor-fromFloor-1)>EPS) return false;
        Pos sourceFloor=edge.from().offset(0,-1,0),landingFloor=edge.to().offset(0,-1,0);
        if (!support(cells.at(sourceFloor),sourceFloor,fromFloor) || !support(cells.at(landingFloor),landingFloor,toFloor)) return false;
        AABB body=sweep(edge,width,height,fromFloor);
        for (int x=(int)Math.floor(body.minX);x<=(int)Math.floor(body.maxX);x++)
            for (int z=(int)Math.floor(body.minZ);z<=(int)Math.floor(body.maxZ);z++)
                for (int y=(int)Math.floor(body.minY);y<=(int)Math.floor(body.maxY);y++) {
                    Pos pos=new Pos(x,y,z); Cell cell=cells.at(pos);
                    if (!safe(cell)) return false;
                    for (AABB box:cell.boxes()) {
                        if (!validBox(box)) return false;
                        if (!body.intersects(box.move(x,y,z))) continue;
                        // Only these already-verified full-footprint floors may intersect
                        // the lower envelope. A wall/ceiling beside or above them may not.
                        if (!pos.equals(sourceFloor) && !pos.equals(landingFloor)) return false;
                    }
                }
        return true;
    }
    private static boolean support(Cell cell,Pos pos,double height) {
        if (!safe(cell) || !cell.normalSurface() || cell.boxes().size()!=1) return false;
        AABB box=cell.boxes().get(0);
        return validBox(box) && Math.abs(box.minX)<EPS && Math.abs(box.minZ)<EPS
            && Math.abs(box.maxX-1)<EPS && Math.abs(box.maxZ-1)<EPS
            && Math.abs(pos.y()+box.maxY-height)<EPS;
    }
    private static boolean safe(Cell cell) { return cell!=null && cell.loaded() && cell.bounded() && !cell.forbidden(); }
    private static boolean validBox(AABB b) {
        return b!=null && Double.isFinite(b.minX) && Double.isFinite(b.minY) && Double.isFinite(b.minZ)
            && Double.isFinite(b.maxX) && Double.isFinite(b.maxY) && Double.isFinite(b.maxZ)
            && b.minX>=0 && b.minY>=0 && b.minZ>=0 && b.maxX<=1 && b.maxY<=1 && b.maxZ<=1
            && b.maxX>b.minX && b.maxY>b.minY && b.maxZ>b.minZ;
    }

    /** Exact object identity, one use, one client-tick lifetime; never a global jump key. */
    static final class InputPulse {
        private Movement movement;
        private long issued;
        void issue(Movement value,long tick) { movement=value!=null && value.jump() ? value : null; issued=tick; }
        boolean consume(Movement value,long tick) {
            boolean allowed=value!=null && value==movement && tick>=issued && tick-issued<=1;
            movement=null;
            return allowed;
        }
        void clear() { movement=null; }
    }
    static void permitPulse(Movement movement,long tick,BooleanSupplier recheck) {
        PULSE.issue(movement,tick); pulseCheck=Objects.requireNonNull(recheck);
    }
    static boolean consumePulse(Movement movement,long tick) {
        BooleanSupplier check=pulseCheck;
        pulseCheck=null;
        if (!PULSE.consume(movement,tick) || check==null) return false;
        try { return check.getAsBoolean(); } catch (RuntimeException unavailable) { return false; }
    }
    static void clearPulse() { PULSE.clear(); pulseCheck=null; }
}
