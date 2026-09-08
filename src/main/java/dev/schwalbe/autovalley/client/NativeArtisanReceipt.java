package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Exact target-block and selected-slot server evidence, never client prediction alone. */
final class NativeArtisanReceipt {
    record StateProof(long seq,Pos pos,String id,String mature,String working) { }
    record SlotProof<T>(long seq,int menuId,int slot,boolean full,boolean cursorEmpty,T value) { }
    record Attempt(ArtisanRecipe recipe,Pos target,BlockData before,ItemStack ingredient,int selected,int menuId,
                   MenuData beforeMenu,long sequence,long generation) {
        Attempt { ingredient=ingredient.copy();beforeMenu=new MenuData(beforeMenu.id(),beforeMenu.revision(),List.copyOf(beforeMenu.slots()),beforeMenu.carried(),beforeMenu.container()); }
        boolean confirmed(ServerObservations observations) {
            return observations.generation()==generation && NativeArtisanReceipt.confirmed(recipe,target,before,ingredient,selected,menuId,beforeMenu,observations,sequence);
        }
    }
    private NativeArtisanReceipt() { }
    static boolean confirmed(ArtisanRecipe recipe,Pos target,BlockData before,ItemStack ingredient,int selected,int menuId,
                             MenuData beforeMenu,ServerObservations observations,long sequence) {
        if(recipe==null || before==null || ingredient==null)return false;
        boolean feeding=!ingredient.isEmpty();
        var latest=latestTargetReceipt(observations,target,sequence);
        if(latest==null)return false;
        boolean state=validState(recipe,latest.state(),feeding);
        if(!state)return false;
        if(!feeding)return before.flag("mature");
        ItemStack observed=latestSelectedReceipt(selected,menuId,beforeMenu,observations,sequence);
        return observed!=null && compatibleFeed(recipe,ingredient,observed,before.flag("mature"));
    }
    static ServerObservations.NativeBlockSnapshot latestTargetReceipt(ServerObservations observations,Pos target,long sequence) {
        return latestProof(observations.nativeBlocksSince(sequence),sequence,ServerObservations.NativeBlockSnapshot::seq,s->s.pos().equals(target));
    }
    static <T> T latestProof(List<T> proofs,long sequence,java.util.function.ToLongFunction<T> order,java.util.function.Predicate<T> selected) {
        T latest=null;long seen=sequence;
        if(proofs==null)return null;
        for(T proof:proofs)if(proof!=null && selected.test(proof) && order.applyAsLong(proof)>seen) { latest=proof;seen=order.applyAsLong(proof); }
        return latest;
    }
    static ItemStack latestSelectedReceipt(int selected,int menuId,MenuData beforeMenu,ServerObservations observations,long sequence) {
        List<ItemSlot> slots=beforeMenu.slots().stream().filter(s->s.player() && s.inventoryIndex()==selected).toList();
        if(slots.size()!=1)return null;
        int menuSlot=slots.get(0).index();if(menuSlot<0)return null;
        List<SlotProof<ItemStack>> proofs=new ArrayList<>();
        for(var packet:observations.nativeSlotSnapshotsSince(menuId,sequence))
            proofs.add(new SlotProof<>(packet.seq(),packet.menuId(),packet.slot(),false,packet.appliedMenu().carried().isEmpty(),packet.packetItem()));
        for(var packet:observations.nativeSlotSnapshotsSince(-2,sequence))
            proofs.add(new SlotProof<>(packet.seq(),packet.menuId(),packet.slot(),false,packet.appliedMenu().carried().isEmpty(),packet.packetItem()));
        for(var packet:observations.fullNativeMenuSnapshotsSince(menuId,sequence)) {
            List<ItemStack> items=packet.items();
            proofs.add(new SlotProof<>(packet.seq(),menuId,menuSlot,true,packet.carried().isEmpty(),menuSlot<items.size() ? items.get(menuSlot) : null));
        }
        return latestSelected(selected,menuId,menuSlot,sequence,proofs);
    }
    static <T> T latestSelected(int selected,int menuId,int menuSlot,long sequence,List<SlotProof<T>> proofs) {
        if(selected<0 || selected>8 || menuSlot<0 || proofs==null)return null;
        SlotProof<T> latest=null;
        for(var proof:proofs) {
            if(proof==null || proof.seq()<=sequence)continue;
            boolean matches=proof.menuId()==menuId && (proof.full() || proof.slot()==menuSlot)
                || !proof.full() && proof.menuId()==-2 && proof.slot()==selected;
            if(matches && (latest==null || proof.seq()>latest.seq()))latest=proof;
        }
        return latest!=null && latest.cursorEmpty() ? latest.value() : null;
    }
    static boolean validState(ArtisanRecipe recipe,net.minecraft.world.level.block.state.BlockState state,boolean feeding) {
            if(!Objects.toString(BuiltInRegistries.BLOCK.getKey(state.getBlock()),"").equals(recipe.machineId()))return false;
            var properties=state.getValues();
            String mature=null,working=null;
            for(var entry:properties.entrySet()) {
                if(entry.getKey().getName().equals("mature"))mature=entry.getValue().toString();
                if(entry.getKey().getName().equals("working"))working=entry.getValue().toString();
            }
            return validState(recipe.machineId(),Objects.toString(BuiltInRegistries.BLOCK.getKey(state.getBlock()),""),mature,working,feeding);
    }
    static boolean validState(String expectedId,String actualId,String mature,String working,boolean feeding) {
        return expectedId!=null && expectedId.equals(actualId) && "false".equals(mature) && Boolean.toString(feeding).equals(working);
    }
    /** Coalesced same-ID output may refill this slot before its packet is sent.
     * The separate working-state receipt proves feed; this bounds compatible
     * native stack changes, rather than pretending to observe a hidden decrement. */
    static boolean compatibleFeed(ArtisanRecipe recipe,ItemStack before,ItemStack after,boolean collected) {
        if(recipe==null || before==null || after==null || before.isEmpty()
            || !BuiltInRegistries.ITEM.getKey(before.getItem()).toString().equals(recipe.inputId()) || before.getCount()<recipe.inputCount())return false;
        if(!after.isEmpty() && after.getCount()>after.getMaxStackSize())return false;
        return compatibleFeed(recipe,BuiltInRegistries.ITEM.getKey(before.getItem()).toString(),before.getCount(),
            after.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(after.getItem()).toString(),after.isEmpty()?0:after.getCount(),
            ItemStack.isSameItemSameTags(before,after),collected);
    }
    static boolean compatibleFeed(ArtisanRecipe recipe,String beforeId,int beforeCount,String afterId,int afterCount,boolean sameTags,boolean collected) {
        if(recipe==null || !recipe.inputId().equals(beforeId) || beforeCount<recipe.inputCount() || afterCount<0)return false;
        int remaining=beforeCount-recipe.inputCount(),allowance=collected ? recipe.outputCount() : 0;
        if(afterCount==0)return remaining==0;
        if(remaining==0 && allowance>0 && recipe.outputId().equals(afterId))return afterCount<=allowance;
        return beforeId.equals(afterId) && sameTags && (recipe.sameInputAndOutput()
            ? afterCount>=remaining && (long)afterCount<=(long)remaining+allowance : afterCount==remaining);
    }
}
