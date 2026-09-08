package dev.schwalbe.autovalley.core;

import dev.schwalbe.autovalley.core.InventoryConsolidation.Stack;
import java.util.*;
import java.util.function.Function;

/**
 * Immutable ordinary-click placement for the installed six-spruce fire-log recipe.
 * The adapter supplies native-verified spruce source slots and a detached native
 * recipe resolver. Every expected state includes the entire 46-slot table and
 * cursor; packet provenance, ownership, sequence and cancellation remain native.
 * This plan never takes a result, closes a menu or retries a click.
 */
public final class LoggingCraftPlan {
    public enum Type { PICKUP, QUICK_CRAFT }
    public record Click(int slot,int button,Type type) {
        public Click {
            Objects.requireNonNull(type);
            if (type==Type.PICKUP && (slot<1 || slot>=46 || button<0 || button>1)
                || type==Type.QUICK_CRAFT && !(slot==-999 && (button==0 || button==2)
                    || slot>=1 && slot<=6 && button==1)) throw new IllegalArgumentException("Unsupported logging placement click");
        }
    }
    public record Snapshot(List<Stack> items,Stack carried) {
        public Snapshot {
            items=List.copyOf(items); Objects.requireNonNull(carried);
            if (items.size()!=46) throw new IllegalArgumentException("Expected an exact 46-slot crafting table");
        }
    }
    public record Step(Click click,Snapshot before,Snapshot after) {
        public Step { Objects.requireNonNull(click); Objects.requireNonNull(before); Objects.requireNonNull(after); }
        public List<Stack> expectedItems() { return after.items(); }
        public Stack expectedCursor() { return after.carried(); }
        public boolean matches(Snapshot actual) { return after.equals(actual); }
    }
    public record Plan(Snapshot initial,int quantity,List<Step> steps) {
        public Plan {
            Objects.requireNonNull(initial); steps=List.copyOf(steps);
            if (quantity<1 || quantity>10 || steps.isEmpty() || steps.size()>14)
                throw new IllegalArgumentException("Unbounded logging placement plan");
            Snapshot previous=initial;
            for (Step step:steps) {
                if (!previous.equals(step.before())) throw new IllegalArgumentException("Discontinuous placement plan");
                previous=step.after();
            }
        }
        public Snapshot placed() { return steps.get(steps.size()-1).after(); }
    }
    private LoggingCraftPlan() { }

    /**
     * Resolver input is an immutable expected 46-slot menu; it reads only grid
     * slots 1..9 and returns the native virtual result (EMPTY when no recipe).
     * One log may expose planks during individual placement, so result slot 0
     * must not be guessed or omitted from the exact acknowledgement comparison.
     */
    public static Plan create(List<Stack> initial46,Stack carried,Set<Integer> verifiedSpruceMenuSlots,
                              Stack fireLogOutput,Function<List<Stack>,Stack> resultResolver) {
        Snapshot initial=new Snapshot(initial46,carried);
        Objects.requireNonNull(resultResolver); Objects.requireNonNull(fireLogOutput);
        if (!carried.empty() || fireLogOutput.empty() || fireLogOutput.count()!=1
            || verifiedSpruceMenuSlots==null || verifiedSpruceMenuSlots.size()>36)
            throw new IllegalArgumentException("Empty cursor and native recipe/source proof are required");
        for (int i=0;i<10;i++) if (!initial.items().get(i).empty())
            throw new IllegalArgumentException("Logging placement cannot borrow an occupied crafting grid");
        List<Integer> sources=new ArrayList<>();
        for (Integer slot:verifiedSpruceMenuSlots) {
            if (slot==null || slot<10 || slot>=46) throw new IllegalArgumentException("Source must be a normal table inventory slot");
            Stack item=initial.items().get(slot);
            if (item.empty() || item.limit()!=64 || item.identity().equals(fireLogOutput.identity()))
                throw new IllegalArgumentException("Invalid native spruce source");
            sources.add(slot);
        }
        sources.sort(Comparator.<Integer>comparingInt(i -> initial.items().get(i).count()).reversed().thenComparingInt(i -> i));
        if (sources.stream().mapToInt(i -> initial.items().get(i).count()).sum()<6)
            throw new IllegalArgumentException("Six native spruce logs are required");
        Builder builder=new Builder(initial,resultResolver);
        int quantity;
        int largest=sources.get(0);
        if (initial.items().get(largest).count()>=6) {
            Stack ingredient=initial.items().get(largest); quantity=ingredient.count()/6;
            builder.pickUp(largest);
            // Start/add drag phases have no visible item changes. The native
            // adapter still sends and awaits each distinct full server reply;
            // these no-ops NEVER prove the final hidden drag state succeeded.
            builder.unchanged(new Click(-999,0,Type.QUICK_CRAFT));
            for (int grid=1;grid<=6;grid++) builder.unchanged(new Click(grid,1,Type.QUICK_CRAFT));
            List<Stack> after=new ArrayList<>(builder.current.items());
            for (int grid=1;grid<=6;grid++) after.set(grid,amount(ingredient,quantity));
            builder.add(new Click(-999,2,Type.QUICK_CRAFT),after,amount(ingredient,ingredient.count()-quantity*6),true);
            if (!builder.current.carried().empty()) builder.returnTo(largest);
        } else {
            quantity=1; int grid=1;
            for (int source:sources) {
                builder.pickUp(source);
                while (!builder.current.carried().empty() && grid<=6) builder.placeOne(grid++);
                if (!builder.current.carried().empty()) builder.returnTo(source);
                if (grid>6) break;
            }
        }
        Snapshot placed=builder.current;
        if (!placed.carried().empty() || !placed.items().get(0).equals(fireLogOutput))
            throw new IllegalArgumentException("Native six-log recipe result differs from the expected fire log");
        for (int grid=1;grid<=9;grid++) {
            Stack item=placed.items().get(grid);
            if (grid<=6 ? item.empty() || item.count()!=quantity : !item.empty())
                throw new IllegalArgumentException("Incomplete six-cell placement");
        }
        int capacity=0;
        for (int i=10;i<46;i++) {
            Stack item=placed.items().get(i);
            if (item.empty()) capacity+=fireLogOutput.limit();
            else if (item.identity().equals(fireLogOutput.identity()) && item.limit()==fireLogOutput.limit())
                capacity+=item.limit()-item.count();
        }
        if (capacity<quantity) throw new IllegalArgumentException("Insufficient inventory space for this fire-log batch");
        return new Plan(initial,quantity,builder.steps);
    }

    private static Stack amount(Stack source,int count) {
        return count==0 ? Stack.EMPTY : new Stack(source.identity(),count,source.limit());
    }
    private static final class Builder {
        private Snapshot current;
        private final Function<List<Stack>,Stack> resolver;
        private final List<Step> steps=new ArrayList<>();
        Builder(Snapshot initial,Function<List<Stack>,Stack> resolver) { current=initial; this.resolver=resolver; }
        void add(Click click,List<Stack> items,Stack carried,boolean gridChanged) {
            if (gridChanged) items.set(0,Objects.requireNonNull(resolver.apply(List.copyOf(items)),"Native recipe result"));
            Snapshot after=new Snapshot(items,carried); steps.add(new Step(click,current,after)); current=after;
        }
        void unchanged(Click click) { add(click,new ArrayList<>(current.items()),current.carried(),false); }
        void pickUp(int source) {
            if (!current.carried().empty() || current.items().get(source).empty()) throw new IllegalArgumentException("Invalid source pickup");
            List<Stack> items=new ArrayList<>(current.items()); Stack cursor=items.set(source,Stack.EMPTY);
            add(new Click(source,0,Type.PICKUP),items,cursor,false);
        }
        void placeOne(int grid) {
            if (current.carried().empty() || !current.items().get(grid).empty()) throw new IllegalArgumentException("Invalid single placement");
            List<Stack> items=new ArrayList<>(current.items()); items.set(grid,amount(current.carried(),1));
            add(new Click(grid,1,Type.PICKUP),items,amount(current.carried(),current.carried().count()-1),true);
        }
        void returnTo(int source) {
            if (current.carried().empty() || !current.items().get(source).empty()) throw new IllegalArgumentException("Invalid remainder return");
            List<Stack> items=new ArrayList<>(current.items()); items.set(source,current.carried());
            add(new Click(source,0,Type.PICKUP),items,Stack.EMPTY,false);
        }
    }
}
