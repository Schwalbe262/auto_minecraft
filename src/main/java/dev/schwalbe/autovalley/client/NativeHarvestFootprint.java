package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.HarvestFootprint;
import dev.schwalbe.autovalley.core.HarvestArea;
import dev.schwalbe.autovalley.core.HarvestCandidateRules;
import dev.schwalbe.autovalley.core.Pos;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Read-only model of the installed Quark 4.0-462 Simple Harvest hook. This never
 * invokes a crop use, harvest predicate with events, or a tool-modification hook.
 *
 * <p>Quark's "radius" is a range: offsets are 1-range through range-1. The core
 * radius returned here is therefore the half-span (gold hoe: 1, diamond: 2).
 * Geometry uses the actual configured range, not the maximum permitted by the
 * native configuration schema. Coverage remains a hint, never harvest proof.
 * The lower and upper cell are both included when potentially actionable: the
 * native upper fallback can run after a lower click returns PASS or is denied.
 *
 * <p>IMPORTANT: supported behavior is Society 4.1.4's default harvest rules,
 * with matching client/server range settings and no server-only custom crop
 * mappings. The user's golden-hoe demonstration matches the installed 3-by-3
 * range. Zeta flags do not establish numeric range or crop-list equality.
 * A changed server range requires revalidation, not a claim of compatibility.
 * Arbitrary server entries can make even stone actionable and cannot be
 * inferred by this client; known identifies this supported model, not proof
 * of every possible server configuration or third-party event handler.
 */
public final class NativeHarvestFootprint {
    private static final String SIMPLE = "org.violetmoon.quark.content.tweaks.module.SimpleHarvestModule";
    private static final String HOE = "org.violetmoon.quark.content.tweaks.module.HoeHarvestingModule";
    private static final Hooks HOOKS = hooks();

    private NativeHarvestFootprint() { }

    public static HarvestFootprint inspect(Player player, Level level, Pos target) {
        return inspectTool(level,target,player==null ? null : player.getMainHandItem());
    }

    /** Read-only preflight for an inventory tool without selecting or modifying its stack. */
    public static HarvestFootprint inspectTool(Level level,Pos target,ItemStack tool) {
        if (tool == null || level == null || target == null || HOOKS == null || !loaded(level,target))
            return HarvestFootprint.UNKNOWN;
        try {
            // The selected block may still receive Farmer's Delight's direct use
            // even when Simple Harvest is disabled or its own predicate is NONE.
            Set<Pos> candidates = new LinkedHashSet<>();
            candidates.add(target);
            Object rangeValue = HOOKS.range().invoke(null,tool);
            if (!(rangeValue instanceof Integer range) || range < 1 || range > 5)
                return HarvestFootprint.UNKNOWN;
            Object cropsValue = HOOKS.crops().get(null), clickableValue = HOOKS.clickable().get(null);
            if (!(cropsValue instanceof Map<?,?> crops) || !(clickableValue instanceof Set<?> clickable))
                return HarvestFootprint.UNKNOWN;
            Set<Block> cropBlocks = new HashSet<>();
            for (Object key:crops.keySet()) {
                if (!(key instanceof BlockState crop)) return HarvestFootprint.UNKNOWN;
                cropBlocks.add(crop.getBlock());
            }
            int radius = HarvestArea.halfSpan(range,HOOKS.enabled().getBoolean(null));
            if (radius<0) return HarvestFootprint.UNKNOWN;
            for (Pos p:HarvestArea.cells(target,radius)) {
                if (!loaded(level,p)) return HarvestFootprint.UNKNOWN;
                BlockState state = level.getBlockState(nativePos(p));
                Block block = state.getBlock();
                // Quark configChanged skips the minecraft namespace in its
                // automatic search. In particular, vanilla grass is not a crop
                // merely because it is a bonemealable BushBlock. Explicit native
                // crop/click mappings still include vanilla wheat, berries, etc.
                boolean nativePlant = block instanceof CropBlock
                    || (block instanceof BushBlock || block instanceof GrowingPlantBlock) && block instanceof BonemealableBlock;
                if (HarvestCandidateRules.potentialTarget(BuiltInRegistries.BLOCK.getKey(block).getNamespace(),nativePlant,
                    cropBlocks.contains(block),clickable.contains(block)))
                    candidates.add(p);
            }
            return new HarvestFootprint(true,radius,radius,List.copyOf(candidates));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return HarvestFootprint.UNKNOWN;
        }
    }

    private static boolean loaded(Level level,Pos p) {
        return p.y()>=level.getMinBuildHeight() && p.y()<level.getMaxBuildHeight() && level.hasChunkAt(nativePos(p));
    }
    private static BlockPos nativePos(Pos p) { return new BlockPos(p.x(),p.y(),p.z()); }
    private record Hooks(Field enabled,Field crops,Field clickable,Method range) { }
    private static Hooks hooks() {
        try {
            Class<?> simple = Class.forName(SIMPLE), hoe = Class.forName(HOE);
            if (!rangeMaximumIsFive(hoe.getField("regularHoeRadius")) || !rangeMaximumIsFive(hoe.getField("highTierHoeRadius")))
                return null;
            return new Hooks(simple.getField("staticEnabled"),simple.getField("crops"),simple.getField("rightClickCrops"),
                hoe.getMethod("getRange",ItemStack.class));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }
    private static boolean rangeMaximumIsFive(Field range) throws ReflectiveOperationException {
        for (Annotation annotation:range.getAnnotations()) {
            if (annotation.annotationType().getName().equals("org.violetmoon.zeta.config.Config$Max"))
                return Double.valueOf(5).equals(annotation.annotationType().getMethod("value").invoke(annotation));
        }
        return false;
    }
}
