package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;

/**
 * Read-only proof for Society's recorded, registered giant spruce plantation.
 * Never calls ChopUtil.getTree: that API posts a mutable DetectTreeEvent.
 * The supported TreeChop 0.19.0 default detector connects neighbouring logs;
 * this deliberately inspects the entire loaded 7-block leaf envelope instead.
 * Every possible log there must be spruce inside registered trunk columns.
 * Server-only replacement detectors/custom non-log mappings are not supported.
 */
final class NativeLoggingTree {
    record Cell(boolean loaded,boolean log,boolean spruce,boolean naturalLeaves,int chops) { }
    record Proof(String rejection,Map<Pos,Integer> chops) {
        Proof { chops=Map.copyOf(chops); }
        boolean safe() { return rejection==null; }
    }
    private record Hooks(Class<?> entity,Method original,Method chops) { }
    private static final Hooks HOOKS=hooks();
    private static Hooks hooks() {
        try {
            if (!ModList.get().getModContainerById("treechop").map(m -> supportedVersion(m.getModInfo().getVersion().toString())).orElse(false)) return null;
            Class<?> entity=Class.forName("ht.treechop.common.block.ChoppedLogBlock$MyEntity",false,NativeLoggingTree.class.getClassLoader());
            return new Hooks(entity,entity.getMethod("getOriginalState"),entity.getMethod("getChops"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) { return null; }
    }
    static boolean supportedVersion(String version) { return "0.19.0".equals(version); }
    static Proof inspect(Level level,Pos target,List<LoggingPlot> plots) {
        if (level==null || HOOKS==null) return new Proof("지원하는 TreeChop 읽기 전용 API를 확인할 수 없습니다.",Map.of());
        return inspect(target,plots,pos -> cell(level,pos));
    }
    static Cell cell(Level level,Pos pos) {
        var bp=MinecraftWorld.nativePos(pos);
        if (pos.y()<level.getMinBuildHeight() || pos.y()>=level.getMaxBuildHeight() || !level.hasChunkAt(bp))
            return new Cell(false,false,false,false,-1);
        BlockState state=level.getBlockState(bp);
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (LoggingRules.CHOPPED_LOG.equals(id)) {
            try {
                Object entity=level.getBlockEntity(bp);
                if (HOOKS==null || !HOOKS.entity().isInstance(entity)) return new Cell(true,true,false,false,-1);
                Object original=HOOKS.original().invoke(entity),chops=HOOKS.chops().invoke(entity);
                return new Cell(true,true,original instanceof BlockState s && s.is(Blocks.SPRUCE_LOG),false,(Integer)chops);
            } catch (ReflectiveOperationException | RuntimeException failure) { return new Cell(true,true,false,false,-1); }
        }
        boolean candidate=state.is(BlockTags.LOGS) || state.is(TagKey.create(net.minecraft.core.registries.Registries.BLOCK,new ResourceLocation("treechop","choppables")));
        boolean leaf=state.is(Blocks.SPRUCE_LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT);
        return new Cell(true,candidate,state.is(Blocks.SPRUCE_LOG),leaf,0);
    }
    /** Pure bounded geometry proof, shared with regression tests. */
    static Proof inspect(Pos target,List<LoggingPlot> plots,Function<Pos,Cell> read) {
        if (target==null || plots==null || plots.isEmpty() || plots.size()>256 || read==null)
            return new Proof("등록한 벌목 구역이 없습니다.",Map.of());
        LoggingPlot plot=plots.stream().filter(p -> p!=null && p.plantingPositions().contains(target)).findFirst().orElse(null);
        if (plot==null) return new Proof("등록한 밑동이 아닙니다.",Map.of());
        Map<Pos,Integer> logs=new LinkedHashMap<>(); boolean leaves=false;
        Pos origin=plot.corner();
        for (int y=-1;y<=64;y++) for (int x=-7;x<=8;x++) for (int z=-7;z<=8;z++) {
            Pos pos=origin.offset(x,y,z); Cell cell=read.apply(pos);
            if (cell==null || !cell.loaded()) return new Proof("나무 전체와 잎 주변의 청크를 먼저 불러와야 합니다.",Map.of());
            leaves|=cell.naturalLeaves();
            if (!cell.log()) continue;
            if (!cell.spruce() || cell.chops()<0 || plots.stream().noneMatch(p -> p!=null && p.containsTrunk(pos)))
                return new Proof("나무 주변에 등록 구역 밖 원목이나 확인되지 않은 목재가 있습니다.",Map.of());
            // A log on the inspected boundary could lead the native detector outside the proof.
            if (x==-7 || x==8 || z==-7 || z==8 || y==-1 || y==64)
                return new Proof("나무 원목이 안전 검사 범위를 넘어 연결됩니다.",Map.of());
            logs.put(pos,cell.chops());
            if (logs.size()>1024) return new Proof("나무가 지원하는 원목 수를 초과했습니다.",Map.of());
        }
        if (!logs.containsKey(target) || !leaves) return new Proof("자연 잎이 있는 가문비나무 전체를 확인하지 못했습니다.",Map.of());
        return new Proof(null,logs);
    }
}
