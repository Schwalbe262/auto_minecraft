package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Exact target-block and selected-slot server evidence, never client prediction alone. */
final class NativeArtisanReceipt {
    enum Confirmation { NONE, CONFIRMED, CYCLE_ADVANCED }
    record StateProof(long seq,Pos pos,String id,String mature,String working,Map<String,String> properties) {
        StateProof { properties=properties==null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(properties)); }
        StateProof(long seq,Pos pos,String id,String mature,String working) { this(seq,pos,id,mature,working,Map.of()); }
    }
    record SlotProof<T>(long seq,int menuId,int slot,boolean full,boolean cursorEmpty,T value) { }
    record Attempt(ArtisanRecipe recipe,Pos target,BlockData before,ItemStack ingredient,int selected,int menuId,
                   MenuData beforeMenu,long sequence,long generation) {
        Attempt { ingredient=ingredient.copy();beforeMenu=new MenuData(beforeMenu.id(),beforeMenu.revision(),List.copyOf(beforeMenu.slots()),beforeMenu.carried(),beforeMenu.container()); }
        Confirmation confirmation(ServerObservations observations) {
            return NativeArtisanReceipt.confirmation(recipe,target,before,ingredient,selected,menuId,beforeMenu,observations,sequence,generation);
        }
        boolean confirmed(ServerObservations observations) {
            return confirmation(observations)!=Confirmation.NONE;
        }
    }
    private NativeArtisanReceipt() { }
    static boolean confirmed(ArtisanRecipe recipe,Pos target,BlockData before,ItemStack ingredient,int selected,int menuId,
                             MenuData beforeMenu,ServerObservations observations,long sequence) {
        return confirmation(recipe,target,before,ingredient,selected,menuId,beforeMenu,observations,sequence,
            observations==null ? Long.MIN_VALUE : observations.generation())!=Confirmation.NONE;
    }
    private static Confirmation confirmation(ArtisanRecipe recipe,Pos target,BlockData before,ItemStack ingredient,int selected,int menuId,
                                              MenuData beforeMenu,ServerObservations observations,long sequence,long generation) {
        if(recipe==null || before==null || ingredient==null || observations==null || observations.generation()!=generation)return Confirmation.NONE;
        boolean feeding=!ingredient.isEmpty();
        List<StateProof> proofs=new ArrayList<>();
        for(var receipt:observations.nativeBlocksSince(sequence))if(Objects.equals(receipt.pos(),target)) {
            Map<String,String> properties=new LinkedHashMap<>();
            receipt.state().getValues().forEach((key,value)->properties.put(key.getName(),value.toString().toLowerCase(Locale.ROOT)));
            String mature=properties.remove("mature"),working=properties.remove("working");
            proofs.add(new StateProof(receipt.seq(),receipt.pos(),BuiltInRegistries.BLOCK.getKey(receipt.state().getBlock()).toString(),mature,working,properties));
        }
        boolean selectedCompatible=true;
        if(feeding) {
            if(beforeMenu==null)return Confirmation.NONE;
            ItemStack observed=latestSelectedReceipt(selected,menuId,beforeMenu,observations,sequence);
            selectedCompatible=observed!=null && compatibleFeed(recipe,ingredient,observed,before.flag("mature"),before.flag("upgraded"));
        }
        return confirmation(recipe.machineId(),target,before,feeding,selectedCompatible,sequence,generation,observations.generation(),proofs);
    }
    /** Pure projection of retained raw receipts. This never predicts a calendar transition. */
    static Confirmation confirmation(String expectedId,Pos target,BlockData before,boolean feeding,boolean selectedCompatible,
                                     long sequence,long generation,long observedGeneration,List<StateProof> proofs) {
        if(generation!=observedGeneration || expectedId==null || target==null || before==null || proofs==null
            || feeding && !selectedCompatible)return Confirmation.NONE;
        StateProof latest=latestProof(proofs,sequence,StateProof::seq,p->target.equals(p.pos()));
        if(latest==null)return Confirmation.NONE;
        // Keep the existing latest-valid-state path unchanged. In particular,
        // W->M->W can still be CONFIRMED, but can never be CYCLE_ADVANCED.
        if(validState(expectedId,latest.id(),latest.mature(),latest.working(),feeding))
            return feeding || before.flag("mature") ? Confirmation.CONFIRMED : Confirmation.NONE;
        return feeding && cycleAdvanced(expectedId,target,before,sequence,proofs) ? Confirmation.CYCLE_ADVANCED : Confirmation.NONE;
    }
    private static boolean cycleAdvanced(String expectedId,Pos target,BlockData before,long sequence,List<StateProof> proofs) {
        if(!expectedId.equals(before.id()) || !target.equals(before.pos()) || before.properties()==null
            || !("true".equals(before.properties().get("mature")) || "false".equals(before.properties().get("mature")))
            || !"false".equals(before.properties().get("working")))return false;
        Map<String,String> expectedProperties=new LinkedHashMap<>(before.properties());
        expectedProperties.remove("mature");expectedProperties.remove("working");
        expectedProperties.remove("container"); // MinecraftWorld adds this projection-only field, not a native block property.
        if(expectedProperties.entrySet().stream().anyMatch(e->e.getKey()==null || e.getValue()==null))return false;
        List<StateProof> ordered=new ArrayList<>();
        for(StateProof proof:proofs) {
            if(proof==null)return false;
            if(proof.seq()>sequence && target.equals(proof.pos()))ordered.add(proof);
        }
        ordered.sort(Comparator.comparingLong(StateProof::seq));
        boolean workingSeen=false,matureSeen=false;long previous=sequence;
        for(StateProof proof:ordered) {
            if(proof.seq()<=previous || !expectedId.equals(proof.id()) || !expectedProperties.equals(proof.properties()))return false;
            previous=proof.seq();
            if("false".equals(proof.mature()) && "true".equals(proof.working())) {
                if(matureSeen)return false;
                workingSeen=true;
            } else if("true".equals(proof.mature()) && "false".equals(proof.working())) {
                if(!workingSeen)return false;
                matureSeen=true;
            } else return false;
        }
        // All post-dispatch target receipts must form W+ M+. A missing/evicted
        // working receipt, initial mature echo, idle state, replacement, property
        // change, or a return to working cannot establish the advanced cycle.
        return workingSeen && matureSeen;
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
        return compatibleFeed(recipe,before,after,collected,false);
    }
    static boolean compatibleFeed(ArtisanRecipe recipe,ItemStack before,ItemStack after,boolean collected,boolean upgraded) {
        if(recipe==null || before==null || after==null || before.isEmpty()
            || !BuiltInRegistries.ITEM.getKey(before.getItem()).toString().equals(recipe.inputId()) || before.getCount()<recipe.inputCount())return false;
        if(!after.isEmpty() && after.getCount()>after.getMaxStackSize())return false;
        return compatibleFeed(recipe,BuiltInRegistries.ITEM.getKey(before.getItem()).toString(),before.getCount(),
            after.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(after.getItem()).toString(),after.isEmpty()?0:after.getCount(),
            ItemStack.isSameItemSameTags(before,after),collected,upgraded);
    }
    static boolean compatibleFeed(ArtisanRecipe recipe,String beforeId,int beforeCount,String afterId,int afterCount,boolean sameTags,boolean collected) {
        return compatibleFeed(recipe,beforeId,beforeCount,afterId,afterCount,sameTags,collected,false);
    }
    static boolean compatibleFeed(ArtisanRecipe recipe,String beforeId,int beforeCount,String afterId,int afterCount,boolean sameTags,boolean collected,boolean upgraded) {
        if(recipe==null || !recipe.inputId().equals(beforeId) || beforeCount<recipe.inputCount() || afterCount<0)return false;
        // KubeJS does not synchronize these machines' recipe/stage BE data. A
        // full hand and raw working-state completion bound a partial seed fill;
        // an unchanged slot or unsynchronized client stage never proves success.
        int remaining=beforeCount-recipe.inputCount();
        int maximumRemaining=beforeCount-recipe.minimumInputConsumed(collected);
        int allowance=recipe.maximumCollectedOutput(collected,upgraded);
        if(afterCount==0)return remaining==0;
        if(remaining==0 && allowance>0 && recipe.outputId().equals(afterId))return afterCount<=allowance;
        if(remaining==0 && collected && upgraded && recipe.separateBonusOutputId()!=null
                && recipe.separateBonusOutputId().equals(afterId))return afterCount==1;
        return beforeId.equals(afterId) && sameTags && (recipe.sameInputAndOutput()
            ? afterCount>=remaining && (long)afterCount<=(long)remaining+allowance
            : afterCount>=remaining && afterCount<=maximumRemaining);
    }
}
