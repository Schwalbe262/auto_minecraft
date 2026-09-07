package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.InventoryConsolidation;
import dev.schwalbe.autovalley.core.ItemData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import java.lang.reflect.Method;
import java.util.Objects;

/**
 * RAM-only, client-visible Vinery metadata proof; never a general tag comparator.
 * The installed DrinkBlockItem initializes a missing Year on a server inventory
 * tick, then periodically refreshes only EffectAmplifier and EffectDuration.
 * We reproduce that operation on a detached copy and require its exact result.
 * Server-hidden/share-tag data is not claimed to be known; native clicks remain
 * authoritative and transaction participants keep full, exact identity checks.
 */
final class NativeWineMetadata {
    private static Method initialize,refresh;
    private NativeWineMetadata() { }

    static boolean passiveChange(InventoryConsolidation.Stack before,InventoryConsolidation.Stack after,
                                 Level level,Integer transactionYear) {
        if (before.empty() || after.empty() || before.count()!=after.count() || before.limit()!=after.limit()
                || before.identity().equals(after.identity())) return false;
        Integer currentYear=VineryClock.year(level);
        if (transactionYear==null || !transactionYear.equals(currentYear)) return false;
        try {
            CompoundTag oldRoot=TagParser.parseTag(before.identity()),newRoot=TagParser.parseTag(after.identity());
            if (!eligibleRoots(oldRoot,newRoot,transactionYear,currentYear)) return false;
            ItemStack detached=ItemStack.of(oldRoot.copy());
            Class<?> wineYears=Class.forName("net.satisfy.vinery.core.util.WineYears");
            if (initialize==null) initialize=wineYears.getMethod("setWineYear",ItemStack.class,Level.class);
            if (refresh==null) refresh=wineYears.getMethod("refreshCached",ItemStack.class,Level.class);
            (oldRoot.getCompound("tag").contains("Year") ? refresh : initialize).invoke(null,detached,level);
            return exactPassiveChange(oldRoot,newRoot,detached.save(new CompoundTag()),transactionYear,currentYear);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException | com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            return false;
        }
    }

    /** Package-visible for detached NBT adversarial tests, without booting Minecraft. */
    static boolean exactPassiveChange(CompoundTag before,CompoundTag after,CompoundTag nativePrediction,
                                      Integer transactionYear,Integer currentYear) {
        return eligibleRoots(before,after,transactionYear,currentYear) && after.equals(nativePrediction);
    }

    private static boolean eligibleRoots(CompoundTag before,CompoundTag after,Integer transactionYear,Integer currentYear) {
        if (before==null || after==null || transactionYear==null || transactionYear<0
                || !transactionYear.equals(currentYear) || !ItemData.WINE.equals(before.getString("id"))
                || !ItemData.WINE.equals(after.getString("id"))
                || before.contains("tag") && !before.contains("tag",Tag.TAG_COMPOUND)
                || !after.contains("tag",Tag.TAG_COMPOUND)) return false;
        CompoundTag oldTag=before.getCompound("tag"),newTag=after.getCompound("tag");
        if (!newTag.contains("Year",Tag.TAG_INT) || newTag.getInt("Year")<0) return false;
        boolean knownYear=oldTag.contains("Year");
        if (knownYear && (!oldTag.contains("Year",Tag.TAG_INT) || oldTag.getInt("Year")<0
                || oldTag.getInt("Year")!=newTag.getInt("Year"))) return false;
        if (!knownYear && newTag.getInt("Year")!=transactionYear) return false;
        for (String key:new String[]{"EffectAmplifier","EffectDuration"}) {
            if (oldTag.contains(key) && !oldTag.contains(key,Tag.TAG_INT) || !newTag.contains(key,Tag.TAG_INT)) return false;
        }
        CompoundTag oldUnchanged=before.copy(),newUnchanged=after.copy();
        removeAllowed(oldUnchanged,!knownYear);removeAllowed(newUnchanged,!knownYear);
        // This comparison retains Count, item id, quality, capabilities, custom
        // tags and every other root field. Only the verified native keys differ.
        return Objects.equals(oldUnchanged,newUnchanged);
    }

    private static void removeAllowed(CompoundTag root,boolean initializing) {
        CompoundTag tag=root.getCompound("tag");
        tag.remove("EffectAmplifier");tag.remove("EffectDuration");
        if (initializing) tag.remove("Year");
        if (tag.isEmpty()) root.remove("tag");else root.put("tag",tag);
    }
}
