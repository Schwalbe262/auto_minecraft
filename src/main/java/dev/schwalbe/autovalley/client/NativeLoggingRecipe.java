package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapelessRecipe;

/** Native recipe placement (book or exact manual clicks) and result QUICK_MOVE, with true full ACKs. */
final class NativeLoggingRecipe {
    final long generation;
    final int menuId,selfSlot,selfHotbar;
    long beforeSequence,stepStarted;
    private final Recipe<?> recipe;
    private final Stack output;
    private final LoggingCraftPlan.Plan manualPlan;
    private int manualStep;
    private boolean manualInFlight;
    private List<Stack> before,placed;
    private int quantity;
    private boolean outputSent,done;

    NativeLoggingRecipe(Minecraft mc,ServerObservations observations,long tick) {
        if (mc.player==null || mc.level==null || !menu(mc.player.containerMenu) || !gridEmpty(mc.player.containerMenu))
            throw new IllegalArgumentException("A synchronized empty 3x3 crafting table is required");
        var normal=mc.player.containerMenu.slots.stream().filter(s -> s.container==mc.player.getInventory()
            && s.getContainerSlot()>=0 && s.getContainerSlot()<36).toList();
        if (normal.size()!=36 || normal.stream().map(s -> s.getContainerSlot()).distinct().count()!=36
            || normal.stream().anyMatch(s -> s.index<10 || s.index>=46))
            throw new IllegalArgumentException("Unexpected crafting-table inventory mapping");
        var candidate=mc.level.getRecipeManager().byKey(new ResourceLocation(LoggingRules.RECIPE)).orElseThrow();
        ItemStack expected=candidate.getResultItem(mc.level.registryAccess());
        if (!knownRecipe(candidate instanceof ShapelessRecipe,candidate.canCraftInDimensions(3,3),
                BuiltInRegistries.ITEM.getKey(expected.getItem()).toString(),expected.getCount(),
                candidate.getIngredients().stream().map(i -> i.test(new ItemStack(Items.SPRUCE_LOG))).toList()))
            throw new IllegalArgumentException("The known six-log fire-log recipe is unavailable or changed");
        // The client recipe book is not server authorization: this pack can have
        // the actual synchronized recipe but no client-book entry. Normal native
        // placement still checks the SERVER recipe book; rejection cannot pass
        // the exact full-menu placement proof and never permits an output click.
        // Recipe-book placement is server-selected. Reject competing raw-log ingredients,
        // rather than assuming the server will choose spruce from a broad recipe tag.
        for (int i=0;i<36;i++) {
            ItemStack stack=mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && !stack.is(Items.SPRUCE_LOG) && candidate.getIngredients().stream().anyMatch(in -> in.test(stack)))
                throw new IllegalArgumentException("Store other raw-log species before automatic fire-log crafting");
        }
        before=stacks(mc.player.containerMenu.slots.stream().map(s -> s.getItem()).toList());
        if (count(before,LoggingRules.LOG)<6) throw new IllegalArgumentException("Six spruce logs are required");
        recipe=candidate; output=stack(expected); generation=observations.generation(); beforeSequence=observations.sequence();
        // Choose once, before any dispatch. Never fall back after an uncertain book
        // request: it could still populate the real grid later.
        if (useManualPlacement(mc.player.getRecipeBook().contains(candidate))) {
            Set<Integer> spruceSlots=new HashSet<>();
            for (var slot:normal) if (slot.getItem().is(Items.SPRUCE_LOG)) spruceSlots.add(slot.index);
            manualPlan=LoggingCraftPlan.create(before,Stack.EMPTY,spruceSlots,output,grid -> resultForGrid(mc,grid));
        } else manualPlan=null;
        menuId=mc.player.containerMenu.containerId;
        selfHotbar=mc.player.getInventory().selected;
        selfSlot=mc.player.containerMenu.slots.stream().filter(s -> s.container==mc.player.getInventory() && s.getContainerSlot()==selfHotbar)
            .mapToInt(s -> s.index).findFirst().orElseThrow();
        stepStarted=tick;
    }
    /** Recipe data only; a missing client-book entry must not replace the server's own placement check. */
    static boolean knownRecipe(boolean shapeless,boolean canCraft3x3,String outputId,int outputCount,List<Boolean> acceptsSpruce) {
        return shapeless && canCraft3x3 && LoggingRules.FIRE_LOG.equals(outputId) && outputCount==1
            && acceptsSpruce!=null && acceptsSpruce.size()==6 && acceptsSpruce.stream().allMatch(Boolean.TRUE::equals);
    }
    static boolean useManualPlacement(boolean clientBookContains) { return !clientBookContains; }
    private static boolean menuShape(AbstractContainerMenu menu) {
        return menu instanceof CraftingMenu crafting && crafting.getGridWidth()==3 && crafting.getGridHeight()==3
            && crafting.getResultSlotIndex()==0 && crafting.getSize()==10 && menu.slots.size()==46;
    }
    static boolean menu(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return menuShape(menu) && menu.getCarried().isEmpty();
    }
    static boolean gridEmpty(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        if (!menu(menu)) return false;
        for (int i=0;i<10;i++) if (!menu.getSlot(i).getItem().isEmpty()) return false;
        return true;
    }
    boolean manual() { return manualPlan!=null; }
    /** Only the current primitive's exact before/after cursor is temporarily owned. */
    boolean ownsManualMenu(Minecraft mc) {
        if (!manual() || mc.player==null || !menuShape(mc.player.containerMenu) || mc.player.containerMenu.containerId!=menuId) return false;
        Stack carried=stack(mc.player.containerMenu.getCarried());
        if (outputSent || manualStep>=manualPlan.steps().size()) return carried.empty();
        var step=manualPlan.steps().get(manualStep);
        return allowedManualCursor(carried,step.before().carried(),step.after().carried(),manualInFlight);
    }
    static boolean allowedManualCursor(Stack actual,Stack before,Stack after,boolean inFlight) {
        return actual!=null && before!=null && after!=null && (actual.equals(before) || inFlight && actual.equals(after));
    }
    static int manualPlacementAck(LoggingCraftPlan.Plan plan,int step,boolean inFlight,long beforeSequence,
            long sequence,LoggingCraftPlan.Snapshot after) {
        if (plan==null || !inFlight || sequence<=beforeSequence || step<0 || step>=plan.steps().size()
            || after==null || !plan.steps().get(step).matches(after)) return 0;
        return step+1==plan.steps().size() ? -1 : -2;
    }
    LoggingCraftPlan.Click prepareManualClick(Minecraft mc,long sequence,long tick) {
        if (!manual() || manualInFlight || outputSent || manualStep>=manualPlan.steps().size()
            || !ownsManualMenu(mc)) throw new IllegalStateException("Manual crafting state is not ready for another click");
        var step=manualPlan.steps().get(manualStep);
        var live=new LoggingCraftPlan.Snapshot(stacks(mc.player.containerMenu.slots.stream().map(s -> s.getItem()).toList()),
            stack(mc.player.containerMenu.getCarried()));
        if (!step.before().equals(live)) throw new IllegalStateException("Inventory changed before the next manual crafting click");
        beforeSequence=sequence; stepStarted=tick;
        manualInFlight=true; // Set before the network call, which may throw after sending.
        return step.click();
    }
    private static Stack resultForGrid(Minecraft mc,List<Stack> expectedItems) {
        // This container and its inert parent are detached from the player's menu.
        // Query recipe data only: never set a live slot, unlock a recipe, or call
        // an assembly callback that could have mod-defined side effects.
        AbstractContainerMenu parent=new AbstractContainerMenu(null,-1) {
            @Override public ItemStack quickMoveStack(Player player,int slot) { return ItemStack.EMPTY; }
            @Override public boolean stillValid(Player player) { return false; }
        };
        var grid=new TransientCraftingContainer(parent,3,3);
        for (int index=0;index<9;index++) grid.setItem(index,nativeStack(expectedItems.get(index+1)));
        return mc.level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,grid,mc.level)
            .map(found -> stack(found.getResultItem(mc.level.registryAccess()))).orElse(Stack.EMPTY);
    }
    private static ItemStack nativeStack(Stack stack) {
        if (stack.empty()) return ItemStack.EMPTY;
        try {
            ItemStack nativeItem=ItemStack.of(TagParser.parseTag(stack.identity()));
            nativeItem.setCount(stack.count());
            if (!stack(nativeItem).equals(stack)) throw new IllegalArgumentException("Unexpected manual crafting item fingerprint");
            return nativeItem;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
            throw new IllegalArgumentException("Invalid manual crafting item fingerprint",failure);
        }
    }
    void place(Minecraft mc,Runnable requestFullSnapshot) {
        if (manual()) throw new IllegalStateException("Manual crafting cannot dispatch recipe-book placement");
        mc.gameMode.handlePlaceRecipe(menuId,recipe,true);
        // An exact self-hotbar SWAP is a no-op even after recipe placement. Its
        // unpredicted state-id forces a complete server reply without touching the grid.
        requestFullSnapshot.run();
    }
    /** 0 waits, -2 permits the next manual primitive, -1 permits output, positive means completed output. */
    int acknowledge(ServerObservations.NativeMenuSnapshot ack) {
        if (ack.seq()<=beforeSequence) return 0;
        List<Stack> after=stacks(ack.items());
        if (manual() && !outputSent) {
            if (after.size()!=46) return 0;
            int result=manualPlacementAck(manualPlan,manualStep,manualInFlight,beforeSequence,ack.seq(),
                new LoggingCraftPlan.Snapshot(after,stack(ack.carried())));
            if (result==0) return 0;
            if (manualStep+1==manualPlan.steps().size()) {
                int n=placement(before,after,output);
                if (n!=manualPlan.quantity() || !ack.carried().isEmpty()) return 0;
                placed=after; quantity=n;
            }
            manualStep++; manualInFlight=false; beforeSequence=ack.seq();
            return result;
        }
        if (!ack.carried().isEmpty()) return 0;
        if (!outputSent) {
            int n=placement(before,after,output);
            if (n==0) return 0;
            placed=after; quantity=n; beforeSequence=ack.seq(); return -1;
        }
        if (crafted(placed,after,output,quantity)) { done=true; return quantity; }
        return 0;
    }
    boolean readyToTake(Minecraft mc) {
        if (placed==null || outputSent || !menu(mc.player.containerMenu) || mc.player.containerMenu.containerId!=menuId) return false;
        List<Stack> live=stacks(mc.player.containerMenu.slots.stream().map(s -> s.getItem()).toList());
        if (!live.equals(placed)) return false;
        int capacity=0;
        for (int i=10;i<46;i++) {
            Stack s=live.get(i);
            if (s.empty()) capacity+=output.limit();
            else if (s.identity().equals(output.identity()) && s.limit()==output.limit()) capacity+=s.limit()-s.count();
        }
        return capacity>=quantity;
    }
    void outputSent(long sequence,long tick) { outputSent=true; beforeSequence=sequence; stepStarted=tick; }
    boolean done() { return done; }
    static List<Stack> stacks(List<ItemStack> items) { return items.stream().map(NativeLoggingRecipe::stack).toList(); }
    static Stack stack(ItemStack item) {
        if (item.isEmpty()) return Stack.EMPTY;
        ItemStack one=item.copy(); one.setCount(1);
        return new Stack(one.save(new CompoundTag()).toString(),item.getCount(),item.getMaxStackSize());
    }
    private static String id(Stack item) {
        if (item.empty()) return "minecraft:air";
        try { return TagParser.parseTag(item.identity()).getString("id"); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) { return "invalid"; }
    }
    private static int count(List<Stack> items,String id) { return items.stream().filter(s -> id.equals(id(s))).mapToInt(Stack::count).sum(); }
    private static Map<String,Integer> totals(List<Stack> items) {
        Map<String,Integer> result=new HashMap<>();
        // Result slot is virtual, not an owned inventory item.
        for (int i=1;i<items.size();i++) if (!items.get(i).empty()) result.merge(key(items.get(i)),items.get(i).count(),Integer::sum);
        return result;
    }
    private static String key(Stack item) { return item.identity()+"\u0000"+item.limit(); }
    static int placement(List<Stack> before,List<Stack> after,Stack output) {
        if (before==null || after==null || before.size()!=46 || after.size()!=46 || output==null || output.empty()
            || !after.get(0).equals(output)) return 0;
        for (int i=0;i<10;i++) if (!before.get(i).empty()) return 0;
        int occupied=0,amount=0;
        for (int i=1;i<10;i++) {
            Stack item=after.get(i); if (item.empty()) continue;
            if (!LoggingRules.LOG.equals(id(item)) || item.count()<1 || item.count()>64 || amount!=0 && amount!=item.count()) return 0;
            occupied++; amount=item.count();
        }
        return occupied==6 && totals(before).equals(totals(after)) ? amount : 0;
    }
    static boolean crafted(List<Stack> placed,List<Stack> after,Stack output,int quantity) {
        if (placed==null || after==null || placed.size()!=46 || after.size()!=46 || quantity<1 || quantity>64) return false;
        if (!placed.get(0).equals(output)) return false;
        for (int i=0;i<10;i++) if (!after.get(i).empty()) return false;
        Map<String,Integer> expected=totals(placed);
        int ingredients=0;
        for (int i=1;i<10;i++) {
            Stack s=placed.get(i);
            if (!s.empty()) {
                if (!LoggingRules.LOG.equals(id(s)) || s.count()!=quantity) return false;
                ingredients++;
                expected.compute(key(s),(key,n) -> n==null ? -1 : n-s.count());
            }
        }
        if (ingredients!=6) return false;
        expected.entrySet().removeIf(e -> e.getValue()==0);
        expected.merge(key(output),quantity,Integer::sum);
        return expected.equals(totals(after));
    }
}
