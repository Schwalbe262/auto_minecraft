package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** Dispatch-local proof for finishing an idle wine keg's possibly partial input.
 * Client BE stage/recipe data are not synchronized and are never used as evidence.
 * No current inventory totals, predicted menu, or another target can confirm a feed. */
final class NativeWineFeedReceipt {
    private static final String KEG="society:wine_keg";
    record StateProof(long seq,Pos pos,String id,String mature,String working,Map<String,String> properties,boolean raw) {
        StateProof { properties=properties==null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(properties)); }
    }
    /** sameNativeIdentity includes exact item/tags (including quality) and stack limit. */
    record SelectedProof(long seq,int inventoryIndex,boolean raw,boolean cursorEmpty,String beforeId,int beforeCount,
                         String afterId,int afterCount,boolean sameNativeIdentity) { }
    record Attempt(Pos target,BlockData before,ItemStack ingredient,int selected,int menuId,MenuData beforeMenu,
                   long sequence,long generation) {
        Attempt {
            before=new BlockData(before.pos(),before.id(),Map.copyOf(before.properties()));
            ingredient=ingredient.copy();
            beforeMenu=new MenuData(beforeMenu.id(),beforeMenu.revision(),List.copyOf(beforeMenu.slots()),beforeMenu.carried(),beforeMenu.container());
        }
        @Override public ItemStack ingredient() { return ingredient.copy(); }
        int confirmedCount(ServerObservations observations) {
            if(observations==null || observations.generation()!=generation)return 0;
            List<StateProof> states=new ArrayList<>();
            for(var receipt:observations.nativeBlocksSince(sequence))if(target.equals(receipt.pos())) {
                Map<String,String> properties=new LinkedHashMap<>();
                receipt.state().getValues().forEach((key,value)->properties.put(key.getName(),value.toString().toLowerCase(Locale.ROOT)));
                String mature=properties.remove("mature"),working=properties.remove("working");
                states.add(new StateProof(receipt.seq(),receipt.pos(),BuiltInRegistries.BLOCK.getKey(receipt.state().getBlock()).toString(),mature,working,properties,true));
            }
            return NativeWineFeedReceipt.confirmedCount(target,before,selected,sequence,generation,observations.generation(),states,
                latestSelected(observations,ingredient,selected,menuId,beforeMenu,sequence));
        }
    }
    private NativeWineFeedReceipt() { }
    static boolean eligible(Pos target,BlockData before,ItemStack ingredient,int selected,MenuData menu) {
        return idle(target,before) && selected>=0 && selected<9 && menu!=null && menu.id()==0 && !menu.container() && menu.carried().empty()
            && ingredient!=null && !ingredient.isEmpty() && ItemData.TOMATO.equals(BuiltInRegistries.ITEM.getKey(ingredient.getItem()).toString())
            && ingredient.getCount()>=3 && ingredient.getCount()<=ingredient.getMaxStackSize();
    }
    static int confirmedCount(Pos target,BlockData before,int selected,long sequence,long generation,long observedGeneration,
                              List<StateProof> blocks,SelectedProof slot) {
        if(!idle(target,before) || selected<0 || selected>=9 || generation!=observedGeneration || blocks==null || slot==null
            || !slot.raw() || !slot.cursorEmpty() || slot.seq()<=sequence || slot.inventoryIndex()!=selected
            || !ItemData.TOMATO.equals(slot.beforeId()) || slot.beforeCount()<3 || slot.afterCount()<0)return 0;
        StateProof latest=null;
        for(StateProof block:blocks)if(block!=null && target.equals(block.pos()) && block.seq()>sequence
            && (latest==null || block.seq()>latest.seq()))latest=block;
        if(latest==null || !latest.raw() || !KEG.equals(latest.id()) || !"false".equals(latest.mature()) || !"true".equals(latest.working())
            || !extraProperties(before).equals(latest.properties()))return 0;
        long used=(long)slot.beforeCount()-slot.afterCount();
        if(used<1 || used>3)return 0;
        if(slot.afterCount()==0)return slot.beforeCount()==3 && "minecraft:air".equals(slot.afterId()) ? 3 : 0;
        return ItemData.TOMATO.equals(slot.afterId()) && slot.sameNativeIdentity() ? (int)used : 0;
    }
    private static boolean idle(Pos target,BlockData before) {
        return target!=null && before!=null && target.equals(before.pos()) && KEG.equals(before.id()) && before.properties()!=null
            && "false".equals(before.properties().get("working")) && "false".equals(before.properties().get("mature"));
    }
    private static Map<String,String> extraProperties(BlockData before) {
        Map<String,String> properties=new LinkedHashMap<>(before.properties());
        properties.remove("working");properties.remove("mature");properties.remove("container");return properties;
    }
    /** Only actual raw selected-slot packets or full server menus participate, never applied-menu contents. */
    private static SelectedProof latestSelected(ServerObservations observations,ItemStack ingredient,int selected,int menuId,MenuData beforeMenu,long sequence) {
        List<ItemSlot> matching=beforeMenu.slots().stream().filter(s->s.player() && s.inventoryIndex()==selected).toList();
        if(matching.size()!=1 || menuId!=0)return null;
        int menuSlot=matching.get(0).index();if(menuSlot<0 || menuSlot>=46)return null;
        long newest=sequence;ItemStack value=null;boolean cursorEmpty=false;
        for(int id:new int[]{menuId,-2})for(var packet:observations.nativeSlotSnapshotsSince(id,sequence)) {
            if(packet.slot()!=(id==-2 ? selected : menuSlot) || packet.seq()<=newest)continue;
            newest=packet.seq();value=packet.packetItem();cursorEmpty=packet.appliedMenu().carried().isEmpty();
        }
        for(var packet:observations.fullNativeMenuSnapshotsSince(menuId,sequence))if(packet.seq()>newest) {
            newest=packet.seq();List<ItemStack> items=packet.items();value=items.size()==46 ? items.get(menuSlot) : null;cursorEmpty=packet.carried().isEmpty();
        }
        if(value==null || ingredient.isEmpty() || ingredient.getCount()>ingredient.getMaxStackSize()
            || !value.isEmpty() && (value.getCount()>value.getMaxStackSize() || value.getMaxStackSize()!=ingredient.getMaxStackSize()))return null;
        return new SelectedProof(newest,selected,true,cursorEmpty,BuiltInRegistries.ITEM.getKey(ingredient.getItem()).toString(),ingredient.getCount(),
            value.isEmpty()?"minecraft:air":BuiltInRegistries.ITEM.getKey(value.getItem()).toString(),value.isEmpty()?0:value.getCount(),
            ItemStack.isSameItemSameTags(ingredient,value) && ingredient.getMaxStackSize()==value.getMaxStackSize());
    }
}
