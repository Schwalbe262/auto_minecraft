package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Wine runs before preserves; each run services machines due on the current game day. */
public final class MachineModule implements AutomationModule {
    private enum Stage { START, MACHINE, SOURCE, SNAPSHOT, CHOOSE, FETCH_SOURCE, FETCH, RETURN, EQUIP, VERIFY, PICKUP }
    private enum Pending { CLOSE, OPEN_SCAN, OPEN_FETCH, WITHDRAW, SWAP, SELECT, USE }
    private final Feature feature;
    private Stage stage = Stage.START, afterClose;
    private Pending pending;
    private long ticket = -1, verifySince;
    private List<Poi> machines = List.of(), sources = List.of();
    private final Map<Poi,int[]> stock = new LinkedHashMap<>();
    private static final int STOCK_REFRESH_TICKS = 1200;
    private static final int OUTPUT_SETTLE_TICKS = 5;
    private boolean stockReady, freshForHaul;
    private long stockDay, stockTick;
    private int machineIndex, sourceIndex, containerId = -1, grade = -1, cost, hotbar, inputBefore, outputBefore, withdrawalBefore;
    private Poi source;
    private boolean collected, feeding;
    private String unresolvedInteraction;

    public MachineModule(Feature feature) {
        if (feature != Feature.WINE && feature != Feature.PRESERVES) throw new IllegalArgumentException("Machine feature must be WINE or PRESERVES");
        this.feature = feature;
    }
    @Override public Feature feature() { return feature; }
    @Override public int priority() { return feature == Feature.WINE ? 60 : 70; }
    private String blockId() { return feature == Feature.WINE ? "society:wine_keg" : "society:preserves_jar"; }
    private String outputId() { return feature == Feature.WINE ? ItemData.WINE : ItemData.PRESERVES; }
    private Poi target() { return machines.get(machineIndex); }
    private BlockData machine(Context c) { return c.world().block(target().pos()); }

    @Override public WorkResult tick(Context c) {
        if (unresolvedInteraction != null) return WorkResult.blocked(unresolvedInteraction);
        if (ticket >= 0) {
            ActionOutcome result = c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy("Waiting for production acknowledgement");
            ticket = -1;
            if (!result.success()) return fail("Production action failed: " + result.message());
            switch (pending) {
                case CLOSE -> { containerId = -1; stage = afterClose; }
                case OPEN_SCAN, OPEN_FETCH -> {
                    if (!c.world().menu().container() || !c.world().menu().carried().empty()) return fail("Tomato source menu did not synchronize");
                    containerId = c.world().menu().id(); stage = pending == Pending.OPEN_SCAN ? Stage.SNAPSHOT : Stage.FETCH;
                }
                case WITHDRAW -> {
                    if (!validMenu(c)) return fail("Tomato source changed during withdrawal");
                    if (ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade)) <= withdrawalBefore) return fail("Tomato withdrawal was not acknowledged");
                    String invalid = snapshot(c,source);
                    if (invalid != null) return fail(invalid);
                    stage = Stage.FETCH;
                }
                case SWAP, SELECT -> stage = Stage.EQUIP;
                case USE -> { stage = Stage.VERIFY; verifySince = c.world().tick(); }
            }
            pending = null;
        }
        if (stockReady && stockDay != gameDay(c) && switch (stage) {
            case CHOOSE, FETCH_SOURCE, FETCH, RETURN, EQUIP -> true;
            default -> false;
        }) {
            beginStockScan(c);
            if (c.world().menu().container()) close(c,Stage.SOURCE);
            return WorkResult.busy("Refreshing tomato stock for the new game day");
        }
        switch (stage) {
            case START -> {
                machines = ModuleSupport.nearest(c,c.profile().pois(feature == Feature.WINE ? PoiKind.WINE_KEG : PoiKind.PRESERVES_JAR)
                    .stream().filter(p -> eligible(c,p)).toList());
                if (machines.isEmpty()) return WorkResult.idle();
                machineIndex = 0; stage = Stage.MACHINE;
            }
            case MACHINE -> {
                if (machineIndex >= machines.size()) { clearRun(); return WorkResult.idle(); }
                if (closeIfNeeded(c,Stage.MACHINE)) return WorkResult.busy("Closing container before production");
                Navigation.Result nav = c.navigation().moveTo(target().pos(),4.0,c);
                if (nav == Navigation.Result.BLOCKED) return fail("Registered production machine cannot be reached");
                if (nav != Navigation.Result.ARRIVED) return WorkResult.busy("Approaching production machine");
                BlockData block = machine(c);
                if (!block.id().equals(blockId())) return fail("Registered production machine no longer matches its type");
                if (!block.properties().containsKey("working") || !block.properties().containsKey("mature")) return fail("Machine state is not synchronized");
                if (block.flag("working") && !block.flag("mature")) {
                    if (morningSettled(c)) schedule(c,target(),1);
                    machineIndex++; return WorkResult.busy("Production in progress; checking next machine");
                }
                cost = feature == Feature.WINE || block.flag("upgraded") ? 3 : 5;
                grade = -1;
                if (stockReady && stockDay == gameDay(c) && c.world().tick()-stockTick < STOCK_REFRESH_TICKS) stage = Stage.CHOOSE;
                else beginStockScan(c);
            }
            case SOURCE -> {
                if (sourceIndex >= sources.size()) {
                    stockReady = true; freshForHaul = true; stockDay = gameDay(c); stockTick = c.world().tick();
                    stage = Stage.CHOOSE; break;
                }
                source = sources.get(sourceIndex);
                if (source.classifier() == null || source.classifier() < 0 || source.classifier() > 3) return fail("Register a valid grade for each tomato source");
                Navigation.Result nav = c.navigation().moveTo(source.pos(),2.5,c);
                if (nav == Navigation.Result.BLOCKED) return fail("Registered tomato source cannot be reached");
                if (nav == Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(source.pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_SCAN);
            }
            case SNAPSHOT -> {
                if (!validMenu(c)) return fail("Tomato source menu changed during stock count");
                String invalid = snapshot(c,source);
                if (invalid != null) return fail(invalid);
                sourceIndex++; close(c,Stage.SOURCE);
            }
            case CHOOSE -> {
                grade = chooseGrade(totals(c),cost);
                // Reuse known chest counts while consuming held stock. Before another
                // underground haul (or concluding supplies ran out), recount all sources.
                if (!freshForHaul && !sources.isEmpty() && (grade < 0 || heldCandidate(c) == null)) { beginStockScan(c); break; }
                if (grade < 0) {
                    // A one-shot must not report an unfunded refill as completed or hide
                    // it behind tomorrow's polling deadline. Leave mature output in place
                    // so supplying ingredients permits a safe retry on this same game day.
                    if (c.session().oneShotFeature==feature)
                        return fail("Not enough tomatoes: "+(machines.size()-machineIndex)+" machine(s) remaining; need "
                            +cost+" tomatoes of one grade to refill the machine at "+target().pos()+". Add ingredients and retry.");
                    feeding = false;
                    if (!machine(c).flag("mature")) { schedule(c,target(),1); machineIndex++; stage = Stage.MACHINE; break; }
                    stage = Stage.RETURN;
                } else {
                    feeding = true;
                    if (heldCandidate(c) != null) stage = Stage.RETURN;
                    else {
                        source = stock.entrySet().stream().filter(e -> e.getValue()[grade] > 0).map(Map.Entry::getKey).findFirst().orElse(null);
                        if (source == null) return fail("Combine fragmented tomatoes of the selected grade into one stack");
                        stage = Stage.FETCH_SOURCE;
                    }
                }
            }
            case FETCH_SOURCE -> {
                Navigation.Result nav = c.navigation().moveTo(source.pos(),2.5,c);
                if (nav == Navigation.Result.BLOCKED) return fail("Selected tomato source cannot be reached");
                if (nav == Navigation.Result.ARRIVED) submit(c,new Action.UseBlock(source.pos(),Action.Use.OPEN_CONTAINER),Pending.OPEN_FETCH);
            }
            case FETCH -> {
                if (!validMenu(c)) return fail("Tomato source menu changed before withdrawal");
                String invalid = snapshot(c,source);
                if (invalid != null) return fail(invalid);
                // Count this source again after opening it; never withdraw from an old chest snapshot.
                int currentBest = chooseGrade(totals(c),cost);
                if (currentBest != grade) { close(c,Stage.CHOOSE); break; }
                if (heldCandidate(c) != null) { close(c,Stage.RETURN); break; }
                ItemSlot slot = c.world().menu().slots().stream().filter(s -> !s.player())
                    .filter(s -> ModuleSupport.tomatoGrade(s.item(),grade)).max(Comparator.comparingInt(s -> s.item().count())).orElse(null);
                if (slot == null) { close(c,Stage.CHOOSE); break; }
                if (!ModuleSupport.hasEmptyInventorySlot(c) && ModuleSupport.inventoryItem(c,i -> ModuleSupport.tomatoGrade(i,grade) && i.count() < 64) == null)
                    return fail("Inventory is full; cannot withdraw production tomatoes");
                withdrawalBefore = ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade));
                submit(c,new Action.QuickMove(containerId,slot.index()),Pending.WITHDRAW);
            }
            case RETURN -> {
                Navigation.Result nav = c.navigation().moveTo(target().pos(),4.0,c);
                if (nav == Navigation.Result.BLOCKED) return fail("Production machine cannot be reached with ingredients");
                if (nav == Navigation.Result.ARRIVED) {
                    BlockData block = machine(c);
                    if (!block.id().equals(blockId())) return fail("Production machine changed");
                    if (block.flag("working") && !block.flag("mature")) { machineIndex++; stage = Stage.MACHINE; break; }
                    if (!feeding && !block.flag("mature")) { machineIndex++; stage = Stage.MACHINE; break; }
                    hotbar = (c.profile().hoeHotbarSlot + 1) % 9; stage = Stage.EQUIP;
                }
            }
            case EQUIP -> {
                if (c.world().menu().container() || !c.world().menu().carried().empty()) return fail("Close inventory screens and clear the cursor before production");
                ItemSlot selected = c.world().inventory().stream().filter(s -> s.inventoryIndex() == hotbar).findFirst().orElse(null);
                boolean correct = feeding ? selected != null && ModuleSupport.tomatoGrade(selected.item(),grade) && selected.item().count() >= cost
                    : selected == null || selected.item().empty();
                if (!correct) {
                    ItemSlot ingredient = feeding ? heldCandidate(c) : emptySlot(c);
                    if (ingredient == null) return fail(feeding ? "Selected-grade tomato stack is no longer available" : "A free inventory slot is needed for empty-hand collection");
                    submit(c,new Action.SwapHotbar(ingredient.inventoryIndex(),hotbar),Pending.SWAP); break;
                }
                if (c.world().player().selectedSlot() != hotbar) { submit(c,new Action.SelectHotbar(hotbar),Pending.SELECT); break; }
                BlockData block = machine(c);
                if (!block.id().equals(blockId())) return fail("Production machine changed before interaction");
                if (block.flag("working") && !block.flag("mature")) { machineIndex++; stage = Stage.MACHINE; break; }
                collected = block.flag("mature");
                if (!feeding && !collected) { machineIndex++; stage = Stage.MACHINE; break; }
                // Output quality/year can differ from existing items: reserve a real free slot.
                if (collected && !ModuleSupport.hasEmptyInventorySlot(c)) return fail("Free an inventory slot to collect the completed product");
                // A pickup of an older product (even another wine cohort) would make a
                // simple inventory increase look like confirmation of this new output.
                if (collected && c.world().groundItems().stream().anyMatch(g -> g.item().is(outputId())))
                    return fail("Pick up previously dropped "+outputId()+" before collecting another completed machine");
                inputBefore = ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade));
                outputBefore = ModuleSupport.count(c,i -> i.is(outputId()));
                submit(c,new Action.UseBlock(target().pos(),Action.Use.MACHINE),Pending.USE);
            }
            case VERIFY -> {
                BlockData block = machine(c);
                int consumed = inputBefore - ModuleSupport.count(c,i -> ModuleSupport.tomatoGrade(i,grade));
                boolean inputConfirmed = !feeding || consumed == cost;
                boolean stateConfirmed = !block.flag("mature") && (!feeding || block.flag("working"));
                if (inputConfirmed && stateConfirmed) {
                    if (feeding) freshForHaul = false;
                    schedule(c,target(),feeding ? (feature == Feature.WINE ? c.profile().wineCycleDays : c.profile().preservesCycleDays) : 1);
                    if (collected) stage = Stage.PICKUP;
                    else { machineIndex++; stage = Stage.MACHINE; }
                } else if (c.world().tick() - verifySince > c.profile().interactionTimeoutTicks) return fail("Production input or machine state did not synchronize; inspect the machine");
            }
            case PICKUP -> {
                if (ModuleSupport.count(c,i -> i.is(outputId())) > outputBefore) { machineIndex++; stage = Stage.MACHINE; break; }
                if (c.world().tick() - verifySince > c.profile().interactionTimeoutTicks) return fail("Completed product was not picked up; inspect the machine output side");
                Pos observed = c.world().tick()-verifySince < OUTPUT_SETTLE_TICKS ? null : observedPickup(c);
                if (observed == null) {
                    c.actions().stopMovement();
                    return WorkResult.busy("Waiting for the completed product to fall or be picked up");
                }
                // Elevated outputs may still be falling or temporarily unreachable. A failed
                // path is not a failed interaction: wait for another observation, never click again.
                Navigation.Result nav = c.navigation().moveTo(observed,0.9,c);
                if (nav == Navigation.Result.BLOCKED) {
                    c.actions().stopMovement();
                    return WorkResult.busy("Waiting for a reachable completed product");
                }
            }
        }
        return WorkResult.busy("Servicing " + (feature == Feature.WINE ? "wine kegs" : "preserves jars"));
    }

    /** Largest total stock wins, with lower grade winning ties. Counts include synchronized chests and player inventory. */
    static int chooseGrade(int[] counts, int required) {
        int best = -1;
        for (int i = 0; i < 4; i++) if (counts[i] >= required && (best < 0 || counts[i] > counts[best])) best = i;
        return best;
    }
    private int[] totals(Context c) {
        int[] counts = new int[4];
        for (int[] chest : stock.values()) for (int i = 0; i < 4; i++) counts[i] += chest[i];
        for (ItemSlot s : c.world().inventory()) if (s.item().is(ItemData.TOMATO) && s.item().quality() >= 0 && s.item().quality() < 4) counts[s.item().quality()] += s.item().count();
        return counts;
    }
    private void beginStockScan(Context c) {
        sources = ModuleSupport.nearest(c,c.profile().pois(PoiKind.TOMATO_CHEST));
        stock.clear(); stockReady = false; freshForHaul = false; sourceIndex = 0; stage = Stage.SOURCE;
    }
    private static long gameDay(Context c) { return Math.floorDiv(c.world().dayTime(),24000); }
    private boolean eligible(Context c,Poi poi) {
        long day = Math.floorDiv(c.world().dayTime(),24000);
        if (day < c.profile().nextEligibleDay.getOrDefault(scheduleKey(poi),Long.MIN_VALUE)) return false;
        if (c.world().loaded(poi.pos())) {
            BlockData block = c.world().block(poi.pos());
            if (!block.id().equals(blockId())) return true; // Report a replaced registration instead of hiding it.
            if (block.flag("mature")) return true;
            if (block.flag("working")) {
                // Artisan morning progress is processed asynchronously during ticks 20..219.
                // Keep today's deadline until that window has settled instead of skipping a ripe batch.
                if (morningSettled(c)) schedule(c,poi,1);
                return false;
            }
        }
        return true;
    }
    private String scheduleKey(Poi poi) {
        Pos p = poi.pos();
        return (feature == Feature.WINE ? "wine:" : "preserves:") + p.x() + ":" + p.y() + ":" + p.z();
    }
    private static boolean morningSettled(Context c) { return Math.floorMod(c.world().dayTime(),24000) >= 220; }
    private void schedule(Context c,Poi poi,int days) {
        c.profile().nextEligibleDay.put(scheduleKey(poi),Math.floorDiv(c.world().dayTime(),24000)+Math.max(1,days));
    }
    private String snapshot(Context c, Poi poi) {
        int[] counts = new int[4];
        for (ItemSlot s : c.world().menu().slots()) if (!s.player() && s.item().is(ItemData.TOMATO)) {
            int q = s.item().quality();
            if (q < 0 || q > 3 || !Objects.equals(poi.classifier(),q)) return "Tomato source contains a grade different from its registered classification";
            counts[q] += s.item().count();
        }
        stock.put(poi,counts); return null;
    }
    private ItemSlot heldCandidate(Context c) {
        return ModuleSupport.inventoryItem(c,i -> ModuleSupport.tomatoGrade(i,grade) && i.count() >= cost);
    }
    private ItemSlot emptySlot(Context c) {
        for (int i = 0; i < 36; i++) {
            final int index = i;
            if (i == c.profile().hoeHotbarSlot) continue;
            ItemSlot slot = c.world().inventory().stream().filter(s -> s.inventoryIndex() == index).findFirst().orElse(null);
            if (slot == null || slot.item().empty()) return new ItemSlot(i,i,true,ItemData.EMPTY);
        }
        return null;
    }
    private boolean validMenu(Context c) { return c.world().menu().container() && c.world().menu().id() == containerId && c.world().menu().carried().empty(); }
    private Pos observedPickup(Context c) {
        Pos output=outputPosition(machine(c));
        PlayerState player=c.world().player();
        return c.world().groundItems().stream().filter(g -> g.item().is(outputId()))
            .filter(g -> Double.isFinite(g.x()) && Double.isFinite(g.y()) && Double.isFinite(g.z()))
            .filter(g -> Math.pow(g.x()-output.x()-.5,2)+Math.pow(g.z()-output.z()-.5,2)<=9
                && g.y()<=output.y()+1 && g.y()>=output.y()-6)
            .sorted(Comparator.comparingDouble(g -> Math.pow(g.x()-player.x(),2)+Math.pow(g.y()-player.y(),2)+Math.pow(g.z()-player.z(),2)))
            .map(g -> observedStandingCell(c,g)).filter(Objects::nonNull).findFirst().orElse(null);
    }
    private static Pos observedStandingCell(Context c,GroundItem item) {
        Pos cell=new Pos((int)Math.floor(item.x()),(int)Math.floor(item.y()),(int)Math.floor(item.z()));
        if (!c.world().loaded(cell)) return null;
        if (c.world().canStand(cell)) return cell;
        // Items on a bottom slab, farmland or a stair half share the support block's
        // cell. Normalize only when the observed item height matches its actual surface.
        Pos above=cell.offset(0,1,0);
        if (!c.world().loaded(above) || !c.world().canStand(above)) return null;
        double surface=c.world().standingY(above);
        return Double.isFinite(surface) && Math.abs(surface-item.y())<=.125 ? above : null;
    }
    private static Pos outputPosition(BlockData block) {
        return switch (block.properties().getOrDefault("facing","")) {
            case "north" -> block.pos().offset(0,0,-1);
            case "south" -> block.pos().offset(0,0,1);
            case "west" -> block.pos().offset(-1,0,0);
            case "east" -> block.pos().offset(1,0,0);
            default -> block.pos();
        };
    }
    private void submit(Context c, Action action, Pending reason) { pending = reason; ticket = c.actions().submit(action); }
    private void close(Context c, Stage next) { afterClose = next; submit(c,new Action.CloseContainer(c.world().menu().id()),Pending.CLOSE); }
    private boolean closeIfNeeded(Context c, Stage next) { if (!c.world().menu().container()) return false; close(c,next); return true; }
    private WorkResult fail(String message) {
        // A refill may already have happened even if its response or dropped output is missing.
        // Keep that uncertainty visible across scheduler passes; only explicit user restart clears it.
        boolean uncertainInteraction = pending == Pending.USE || stage == Stage.VERIFY || stage == Stage.PICKUP;
        clearRun();
        if (uncertainInteraction) unresolvedInteraction = message;
        return WorkResult.blocked(message);
    }
    @Override public void reset() { clearRun(); unresolvedInteraction = null; }
    private void clearRun() {
        stage = Stage.START; afterClose = null; pending = null; ticket = -1; verifySince = 0;
        machines = List.of(); sources = List.of(); stock.clear(); machineIndex = 0; sourceIndex = 0;
        stockReady = false; freshForHaul = false; stockDay = 0; stockTick = 0;
        source = null; containerId = -1; grade = -1; collected = false; feeding = false;
    }
}
