package dev.schwalbe.autovalley.client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** Read-only, exact SnowRealMagic snow/spruce-sapling support; never authorizes replacing a wrapper. */
final class NativeSnowPlanting {
    static final String SNOW="snowrealmagic:snow",SAPLING="minecraft:spruce_sapling";
    private static final String ENTITY_CLASS="snownee.snow.block.entity.SnowBlockEntity";
    private NativeSnowPlanting() { }

    static boolean singleLayerWrapper(BlockState state) {
        return wrapper(state) && state.getValue(SnowLayerBlock.LAYERS)==1;
    }

    private static boolean wrapper(BlockState state) {
        return state!=null && SNOW.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString())
            && state.hasProperty(SnowLayerBlock.LAYERS) && state.getValue(SnowLayerBlock.LAYERS)>=1
            && state.getValue(SnowLayerBlock.LAYERS)<=8;
    }

    /** An observation for core occupancy only, not dispatch or ACK evidence. */
    static boolean containedSpruceSapling(BlockState wrapper,BlockEntity entity) {
        // New snowfall does not turn an already planted sapling into a vacant cell.
        // This wider observation grants no right to plant into multi-layer snow.
        if (!wrapper(wrapper) || entity==null || !ENTITY_CLASS.equals(entity.getClass().getName())
            || !SNOW.equals(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType())))) return false;
        try {
            Method method=entity.getClass().getDeclaredMethod("getContainedState");
            if (method.getReturnType()!=BlockState.class || Modifier.isStatic(method.getModifiers())) return false;
            Object contained=method.invoke(entity);
            return contained instanceof BlockState state && state.is(Blocks.SPRUCE_SAPLING);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) { return false; }
    }

    /** null means a different native BE type; false records an exact-type negative/unknown update. */
    static Boolean packetPlant(BlockEntityType<?> type,CompoundTag tag) {
        return packetPlant(type==null ? null : String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type)),tag);
    }

    /** SnowRealMagic 10.7.0 saveState/writePacketData schema, parsed without client BE state. */
    static Boolean packetPlant(String typeId,CompoundTag tag) {
        if (!SNOW.equals(typeId)) return null;
        if (tag==null) return false;
        // The mod uses Block for a default state, or State for a non-default state.
        // Never borrow a valid branch from a malformed or contradictory branch.
        if (tag.contains("Block"))
            return tag.contains("Block",Tag.TAG_STRING) && !tag.contains("State") && SAPLING.equals(tag.getString("Block"));
        if (!tag.contains("State",Tag.TAG_COMPOUND)) return false;
        CompoundTag state=tag.getCompound("State");
        if (!state.contains("Name",Tag.TAG_STRING) || !SAPLING.equals(state.getString("Name"))
            || !Set.of("Name","Properties").containsAll(state.getAllKeys())) return false;
        if (!state.contains("Properties")) return true;
        if (!state.contains("Properties",Tag.TAG_COMPOUND)) return false;
        CompoundTag properties=state.getCompound("Properties");
        if (properties.isEmpty()) return true;
        return properties.getAllKeys().equals(Set.of("stage")) && properties.contains("stage",Tag.TAG_STRING)
            && Set.of("0","1").contains(properties.getString("stage"));
    }
}
