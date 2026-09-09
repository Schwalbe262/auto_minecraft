package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.schwalbe.autovalley.core.InventoryConsolidation.Confirmation.*;

/** Detached exact native-NBT prediction plus core conservation; no client or packets. */
class NativeConsolidationReceiverMetadataTest {
    private static Stack wine(int count,boolean initialized) {
        CompoundTag root=new CompoundTag();root.putString("id",ItemData.WINE);root.putByte("Count",(byte)1);
        CompoundTag tag=new CompoundTag();tag.putInt("quality",2);
        if(initialized){tag.putInt("Year",13);tag.putInt("EffectAmplifier",0);tag.putInt("EffectDuration",1560);}
        root.put("tag",tag);return new Stack(root.toString(),count,64);
    }
    private static boolean proves(Stack old,Stack now,CompoundTag prediction)throws Exception {
        Stack normalized=NativeWineMetadata.receiverAtOriginalCount(old,now);
        return normalized!=null && NativeWineMetadata.exactPassiveChange(TagParser.parseTag(old.identity()),
            TagParser.parseTag(normalized.identity()),prediction,13,13);
    }
    @Test void originalCountNormalizationNeverInventsAnItemOrCapacity() {
        Stack old=wine(1,false),now=wine(2,true);
        assertEquals(wine(1,true),NativeWineMetadata.receiverAtOriginalCount(old,now));
        assertEquals(1,old.count());assertEquals(2,now.count());
        assertNull(NativeWineMetadata.receiverAtOriginalCount(Stack.EMPTY,now));
        assertNull(NativeWineMetadata.receiverAtOriginalCount(old,Stack.EMPTY));
        assertNull(NativeWineMetadata.receiverAtOriginalCount(old,wine(1,true)));
        assertNull(NativeWineMetadata.receiverAtOriginalCount(wine(3,false),now));
        assertNull(NativeWineMetadata.receiverAtOriginalCount(old,new Stack(now.identity(),2,16)));
        assertNull(NativeWineMetadata.receiverAtOriginalCount(new Stack(old.identity(),1,128),new Stack(now.identity(),2,128)));
        assertNull(NativeWineMetadata.receiverAtOriginalCount(null,now));
        assertNull(NativeWineMetadata.receiverAtOriginalCount(old,null));
    }
    @Test void receiverGrowthRequiresExactNativeInitializationNotJustTheSameWineItem()throws Exception {
        Stack old=wine(1,false),now=wine(2,true);CompoundTag prediction=TagParser.parseTag(now.identity());
        assertTrue(proves(old,now,prediction));
        for(String key:List.of("Year","EffectAmplifier","EffectDuration","quality","custom")) {
            CompoundTag changed=prediction.copy();changed.getCompound("tag").putInt(key,99);
            Stack bad=new Stack(changed.toString(),2,64);
            assertFalse(proves(old,bad,prediction),key);
            if(Set.of("Year","quality","custom").contains(key))assertFalse(proves(old,bad,changed),"even a claimed prediction cannot change "+key);
        }
        CompoundTag caps=prediction.copy();caps.putString("ForgeCaps","changed");assertFalse(proves(old,new Stack(caps.toString(),2,64),caps));
        CompoundTag wrongItem=prediction.copy();wrongItem.putString("id",ItemData.TOMATO);assertFalse(proves(old,new Stack(wrongItem.toString(),2,64),wrongItem));
    }
    @Test void capturedInitializedReceiverMergeStillNeedsExactMoveAndThenExactBorrowedItemRestoration()throws Exception {
        Stack torch=new Stack("{Count:1b,id:\"minecraft:torch\"}",62,64);
        List<Stack> items=new ArrayList<>(Collections.nCopies(36,Stack.EMPTY));items.set(6,torch);
        items.set(9,wine(1,true));items.set(10,wine(1,false));items.set(12,wine(33,true));
        List<ItemData> visible=new ArrayList<>(Collections.nCopies(36,ItemData.EMPTY));visible.set(6,new ItemData("minecraft:torch",62,0,null,false,0));
        InventoryConsolidation transaction=new InventoryConsolidation(new ProductionMergePlanner.Plan(Feature.WINE,ItemData.WINE,9,6,List.of(12),visible),new Snapshot(items));
        Collections.swap(items,9,6);assertEquals(NEXT,transaction.acknowledge(new Snapshot(items)));
        Stack beforeReceiver=items.get(10);items.set(6,Stack.EMPTY);items.set(10,wine(2,true));
        assertTrue(proves(beforeReceiver,items.get(10),TagParser.parseTag(wine(1,true).identity())));
        assertEquals(WAIT,transaction.acknowledge(new Snapshot(items)),"old overload cannot infer this proof");
        assertEquals(NEXT,transaction.acknowledge(new Snapshot(items),Set.of(),Set.of(),Set.of(10)));
        assertEquals(33,transaction.expectedLive().items().get(12).count(),"actual receiver, not predicted destination, controls conservation");
        // A separately proven post-merge pickup may occupy scratch before its inverse SWAP.
        items.set(6,wine(1,true));assertTrue(transaction.rebaseVerifiedUpdates(new Snapshot(items),Set.of(6),Set.of(6)));
        Collections.swap(items,9,6);assertEquals(COMPLETE,transaction.acknowledge(new Snapshot(items)));
        assertEquals(torch,items.get(6));assertEquals(wine(1,true),items.get(9));assertEquals(wine(2,true),items.get(10));
    }
}
