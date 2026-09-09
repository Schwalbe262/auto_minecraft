package dev.schwalbe.autovalley.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LoggingLeafRulesTest {
    @Test void clearingIsDisabledByDefaultAndNeedsItsOwnExplicitOptIn() {
        Fixture f=new Fixture();
        assertFalse(new Profile().loggingClearObstructingLeaves);
        f.profile.loggingClearObstructingLeaves=false;
        assertFalse(LoggingLeafRules.authorised(f.context,f.base,f.leaf));
        assertNotNull(LoggingLeafRules.rejection(f.action(),f.context));
        f.profile.loggingClearObstructingLeaves=true;
        assertNull(LoggingLeafRules.rejection(f.action(),f.context));
        assertNull(SafetyPolicy.rejection(f.action(),f.context));
    }

    @Test void theShellIsExactlyTheSmallBaseEnvelopeAndNeverTheFourPlantingCells() {
        LoggingPlot plot=new LoggingPlot("tree",new Pos(100,64,-100));
        int allowed=0;
        for(int x=-2;x<=3;x++) for(int y=-1;y<=3;y++) for(int z=-2;z<=3;z++) {
            Pos leaf=plot.corner().offset(x,y,z);
            boolean expected=x>=-1 && x<=2 && z>=-1 && z<=2 && y>=0 && y<=2
                && !plot.plantingPositions().contains(leaf);
            assertEquals(expected,LoggingLeafRules.inShell(plot,leaf),leaf.toString());
            if(expected) allowed++;
        }
        assertEquals(44,allowed);
        assertFalse(LoggingLeafRules.inShell(null,plot.corner()));
        assertFalse(LoggingLeafRules.inShell(plot,null));
        assertFalse(LoggingLeafRules.inShell(new LoggingPlot("missing",null),plot.corner()));
        assertFalse(LoggingLeafRules.inShell(plot,new Pos(Integer.MIN_VALUE,64,Integer.MAX_VALUE)));
    }

    @Test void authorityRequiresAnActiveUniqueRegisteredUnprocessedTreeAndNoBorrowedHotbar() {
        for(String changed:List.of("disabled","wrong one-shot","inactive","not remaining","replanting","missing plot","overlap","lease")) {
            Fixture f=new Fixture();
            switch(changed) {
                case "disabled" -> f.profile.enabled.put(Feature.LOGGING,false);
                case "wrong one-shot" -> f.session.oneShotFeature=Feature.HARVEST;
                case "inactive" -> f.profile.loggingRunActive=false;
                case "not remaining" -> f.profile.loggingRemainingPlots.clear();
                case "replanting" -> f.profile.loggingReplantingPlots.add(f.plot.corner());
                case "missing plot" -> f.profile.loggingPlots.clear();
                case "overlap" -> f.profile.loggingPlots.add(new LoggingPlot("overlap",f.plot.corner()));
                case "lease" -> f.profile.loggingHotbarLease=new LoggingHotbarLease(9,0,item("minecraft:torch",1),"a".repeat(64),LoggingHotbarLease.Stage.PARKED);
                default -> throw new AssertionError(changed);
            }
            assertFalse(LoggingLeafRules.authorised(f.context,f.base,f.leaf),changed);
            assertNotNull(LoggingLeafRules.rejection(f.action(),f.context),changed);
        }
    }

    @Test void authorisationNeverCoversAFarmOrAnyRegisteredPointOfInterest() {
        Fixture farm=new Fixture(); farm.profile.farms.add(new Farm("protected",farm.leaf,farm.leaf));
        assertFalse(LoggingLeafRules.authorised(farm.context,farm.base,farm.leaf));
        for(PoiKind kind:PoiKind.values()) {
            Fixture f=new Fixture(); f.profile.pois.add(new Poi(f.leaf,kind,"protected",null));
            assertFalse(LoggingLeafRules.authorised(f.context,f.base,f.leaf),kind.toString());
            assertNotNull(LoggingLeafRules.rejection(f.action(),f.context),kind.toString());
        }
    }

    @Test void outsideTheShellAndBaseCellsNeverGainLeafMutationAuthority() {
        Fixture f=new Fixture();
        List<Pos> rejected=new ArrayList<>(f.plot.plantingPositions());
        rejected.addAll(List.of(f.base.offset(-2,0,0),f.base.offset(3,0,0),f.base.offset(0,-1,0),f.base.offset(0,3,0),f.base.offset(0,0,3)));
        for(Pos target:rejected) {
            f.blocks.put(target,LoggingLeafRules.LEAVES);f.obstruction=target;
            assertNotNull(LoggingLeafRules.rejection(new Action.ClearLoggingLeaf(target,f.base),f.context),target.toString());
        }
    }

    @Test void onlySpruceLeavesAndExistingSpruceOrPartiallyChoppedBasesAreAccepted() {
        for(String id:List.of("minecraft:air","minecraft:oak_leaves",LoggingRules.SAPLING,"minecraft:stone","minecraft:barrel",LoggingRules.LOG)) {
            Fixture f=new Fixture();f.blocks.put(f.leaf,id);
            assertNotNull(LoggingLeafRules.rejection(f.action(),f.context),id);
        }
        for(String id:List.of("minecraft:air",LoggingRules.SAPLING,"minecraft:oak_log","minecraft:stone")) {
            Fixture f=new Fixture();f.blocks.put(f.base,id);
            assertNotNull(LoggingLeafRules.rejection(f.action(),f.context),id);
        }
        Fixture chopped=new Fixture();chopped.blocks.put(chopped.base,LoggingRules.CHOPPED_LOG);
        assertNull(LoggingLeafRules.rejection(chopped.action(),chopped.context));
    }

    @Test void reachableLeavesMustBeTheExactCurrentFirstObstructionOfTheSameRegisteredBase() {
        for(String changed:List.of("unknown obstruction","other leaf","leaf not visible","visible target base","visible sibling base")) {
            Fixture f=new Fixture();
            switch(changed) {
                case "unknown obstruction" -> f.obstruction=null;
                case "other leaf" -> f.obstruction=f.leaf.offset(0,1,0);
                case "leaf not visible" -> f.leafVisible=false;
                case "visible target base" -> f.visibleBases.add(f.base);
                case "visible sibling base" -> f.visibleBases.add(f.plot.plantingPositions().get(3));
                default -> throw new AssertionError(changed);
            }
            assertNotNull(LoggingLeafRules.rejection(f.action(),f.context),changed);
        }
        Fixture f=new Fixture();
        assertNotNull(LoggingLeafRules.rejection(new Action.ClearLoggingLeaf(f.leaf,new Pos(20,64,0)),f.context));
        assertNotNull(LoggingLeafRules.rejection(null,f.context));
    }

    @Test void actualAxeSelectionAndItsNativeIdentityAndDurabilityAreRequired() {
        for(String changed:List.of("not selected","unregistered","hoe slot","wrong item","broken","native mismatch")) {
            Fixture f=new Fixture();
            switch(changed) {
                case "not selected" -> f.selected=4;
                case "unregistered" -> f.profile.loggingAxeHotbarSlot=-1;
                case "hoe slot" -> f.profile.hoeHotbarSlot=2;
                case "wrong item" -> f.axe=item("minecraft:netherite_pickaxe",1);
                case "broken" -> f.axe=new ItemData(LoggingRules.AXE,1,0,null,false,1);
                case "native mismatch" -> f.nativeAxe=false;
                default -> throw new AssertionError(changed);
            }
            assertNotNull(LoggingLeafRules.rejection(f.action(),f.context),changed);
        }
    }

    @Test void unknownChunksMenusCursorAndUnresolvedProductionRemainClosed() {
        for(String changed:List.of("leaf unloaded","base unloaded","sibling unloaded","airborne","container","cursor","missing menu","output")) {
            Fixture f=new Fixture();
            switch(changed) {
                case "leaf unloaded" -> f.unloaded.add(f.leaf);
                case "base unloaded" -> f.unloaded.add(f.base);
                case "sibling unloaded" -> f.unloaded.add(f.plot.plantingPositions().get(3));
                case "airborne" -> f.grounded=false;
                case "container" -> f.container=true;
                case "cursor" -> f.cursor=item("minecraft:diamond",1);
                case "missing menu" -> f.missingMenu=true;
                case "output" -> {
                    String id="11111111-1111-1111-1111-111111111111";
                    f.profile.pendingMachineOutputs.put(id,new PendingMachineOutput(id,Feature.PRESERVES,new Pos(30,64,0),1,null,1,PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION));
                }
                default -> throw new AssertionError(changed);
            }
            assertNotNull(LoggingLeafRules.rejection(f.action(),f.context),changed);
        }
    }

    @Test void individualLeafVisibilityNeverReplacesTheWholeTreeSafetyProof() {
        Fixture f=new Fixture();f.treeRejection="connected structure outside the registered 2x2";
        assertEquals(f.treeRejection,LoggingLeafRules.rejection(f.action(),f.context));
        assertEquals(1,f.treeChecks);
        f.treeRejection=null;
        assertNull(LoggingLeafRules.rejection(f.action(),f.context));
        assertEquals(2,f.treeChecks);
    }

    @Test void retainedAuthorityIsNotANewRemovalPermissionAfterTheTargetBecameAir() {
        Fixture f=new Fixture();f.blocks.put(f.leaf,"minecraft:air");
        assertTrue(LoggingLeafRules.authorised(f.context,f.base,f.leaf));
        assertNotNull(LoggingLeafRules.rejection(f.action(),f.context));
    }

    @Test void surveyAndUnrelatedOneShotNeverInheritLoggingLeafRemoval() {
        for(Feature feature:Feature.values()) if(feature!=Feature.LOGGING) {
            Fixture f=new Fixture();f.session.oneShotFeature=feature;
            assertNotNull(SafetyPolicy.rejection(f.action(),f.context),feature.toString());
        }
    }

    private static ItemData item(String id,int count) { return new ItemData(id,count,0,null,false,1000); }
    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();
        final LoggingPlot plot=new LoggingPlot("tree",new Pos(0,64,0));final Pos base=plot.corner(),leaf=base.offset(-1,1,0);
        final Map<Pos,String> blocks=new HashMap<>();final Set<Pos> unloaded=new HashSet<>(),visibleBases=new HashSet<>();
        final Context context=new Context(this,this,this,profile,session,()->{});
        Pos obstruction=leaf;ItemData axe=item(LoggingRules.AXE,1),cursor=ItemData.EMPTY;
        boolean grounded=true,nativeAxe=true,leafVisible=true,container,missingMenu;int selected=2,treeChecks;String treeRejection;
        Fixture() {
            profile.enabled.put(Feature.LOGGING,true);profile.loggingClearObstructingLeaves=true;
            profile.loggingRunActive=true;profile.loggingAxeHotbarSlot=2;profile.hoeHotbarSlot=4;
            profile.loggingPlots.add(plot);profile.loggingRemainingPlots.add(plot.corner());
            plot.plantingPositions().forEach(p->blocks.put(p,LoggingRules.LOG));blocks.put(leaf,LoggingLeafRules.LEAVES);
        }
        Action.ClearLoggingLeaf action() { return new Action.ClearLoggingLeaf(leaf,base); }
        public long tick(){return 100;}public long dayTime(){return 24000;}
        public PlayerState player(){return new PlayerState(-1.5,64,.5,0,0,grounded,false,20,20,selected,true,true);}
        public BlockData block(Pos p){assertTrue(loaded(p),"Unknown blocks must not be read");return new BlockData(p,blocks.getOrDefault(p,"minecraft:air"),Map.of());}
        public boolean loaded(Pos p){return !unloaded.contains(p);}public boolean canStand(Pos p){return true;}public boolean canTraverse(Pos a,Pos b){return true;}
        public List<BlockData> scan(Pos p,int h,int v){throw new AssertionError("Leaf safety must not scan arbitrary terrain");}
        public List<ItemSlot> inventory(){return List.of(new ItemSlot(2,2,true,axe),new ItemSlot(4,4,true,item("minecraft:golden_hoe",1)));}
        public MenuData menu(){return missingMenu?null:new MenuData(container?1:0,0,inventory(),cursor,container);}
        public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean canInteract(Pos target,double reach){assertEquals(4,reach);return target.equals(leaf)?leafVisible:visibleBases.contains(target);}
        public boolean loggingAxe(int slot){return nativeAxe && slot==2;}
        public Pos loggingLeafObstruction(Pos stump,double reach){assertEquals(base,stump);assertEquals(4,reach);return obstruction;}
        public String loggingTreeRejection(Pos stump,List<LoggingPlot> plots){assertEquals(base,stump);assertEquals(List.of(plot),plots);treeChecks++;return treeRejection;}
        public boolean busy(){return false;}public long submit(Action action){throw new AssertionError("Rules must not submit actions");}
        public ActionOutcome outcome(long ticket){throw new AssertionError("Rules must not reconcile acknowledgements");}
        public void move(Movement movement){throw new AssertionError("Rules must not move");}public void stopMovement(){throw new AssertionError("Rules must not stop movement");}
        public void cancel(){throw new AssertionError("Rules must not cancel actions");}
        public Result moveTo(Pos p,double reach,Context c){throw new AssertionError("Rules must not navigate");}public void reset(){throw new AssertionError("Rules must not reset navigation");}
    }
}
