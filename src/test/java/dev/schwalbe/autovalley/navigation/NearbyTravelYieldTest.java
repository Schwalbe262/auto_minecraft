package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class NearbyTravelYieldTest {
    private static final Pos START=new Pos(0,64,0),NEXT=new Pos(1,64,0),TARGET=new Pos(8,64,0);

    @Test void defaultNavigationAndUnknownNativeSupportNeverOptIn() {
        Navigation unknown=new Navigation() {
            public Result moveTo(Pos target,double reach,Context c){throw new AssertionError();}
            public void reset(){throw new AssertionError();}
        };
        assertFalse(unknown.canYieldTravel(null));
        BaseFixture f=new BaseFixture();f.begin();
        assertFalse(f.fullFlatSupport(START));assertFalse(f.navigator.canYieldTravel(f.context));
    }

    @Test void aFreshRealFlatRouteCanYieldWithoutAnyMutationOrAction()throws Exception {
        Fixture f=new Fixture();f.begin();Map<String,Object> before=fields(f.navigator);
        int moves=f.moves,stops=f.stops;Movement movement=f.movement;
        for(int n=0;n<20;n++)assertTrue(f.navigator.canYieldTravel(f.context));
        assertEquals(before,fields(f.navigator));assertSame(movement,f.movement);
        assertEquals(moves,f.moves);assertEquals(stops,f.stops);assertEquals(0,f.submits);
        assertEquals(0,f.outcomes);assertEquals(0,f.cancels);assertEquals(0,f.inventoryReads);
    }

    @Test void ownedDoorPendingAndSuccessfulRepliesAreReadOnlyUntilNavigationResumes()throws Exception {
        DoorFixture f=new DoorFixture();f.beginDoor();Map<String,Object> before=fields(f.navigator);
        int moves=f.moves,stops=f.stops;
        for(ActionOutcome.State state:List.of(ActionOutcome.State.PENDING,ActionOutcome.State.SUCCEEDED)) {
            f.reply=new ActionOutcome(state,"exact door reply");f.busy=!f.reply.done();
            for(int n=0;n<4;n++)assertSame(f.reply,f.navigator.pendingInteractionOutcome(f.context));
            assertEquals(before,fields(f.navigator),"Reading a door reply must not consume its ticket or reset its route");
            assertEquals(moves,f.moves);assertEquals(stops,f.stops);assertEquals(1,f.submits);assertEquals(0,f.cancels);
        }
        assertEquals(8,f.outcomes);assertEquals(0,f.inventoryReads);
        f.blocks.put(NEXT,new BlockData(NEXT,"minecraft:oak_door",Map.of("open","true")));
        assertEquals(Navigation.Result.MOVING,f.navigator.moveTo(TARGET,.1,f.context));
        assertEquals(1,f.submits);int consumed=f.outcomes;
        assertNull(f.navigator.pendingInteractionOutcome(f.context));assertEquals(consumed,f.outcomes);
    }

    @Test void ownedDoorFailureRemainsVisibleWithoutAnotherActionOrMovement()throws Exception {
        DoorFixture f=new DoorFixture();f.beginDoor();Map<String,Object> before=fields(f.navigator);
        int moves=f.moves,stops=f.stops;f.reply=new ActionOutcome(ActionOutcome.State.FAILED,"door rejected");f.busy=false;
        for(int n=0;n<4;n++)assertSame(f.reply,f.navigator.pendingInteractionOutcome(f.context));
        assertEquals(before,fields(f.navigator));assertEquals(moves,f.moves);assertEquals(stops,f.stops);
        assertEquals(1,f.submits);assertEquals(0,f.cancels);assertEquals(4,f.outcomes);assertEquals(0,f.inventoryReads);
        assertEquals(Navigation.Result.BLOCKED,f.navigator.moveTo(TARGET,.1,f.context));
        assertEquals(1,f.submits);int consumed=f.outcomes;
        assertNull(f.navigator.pendingInteractionOutcome(f.context));assertEquals(consumed,f.outcomes);
    }

    @Test void aDoorReplyIsNeverExposedThroughAnotherWorldActionPortOrNavigator()throws Exception {
        DoorFixture f=new DoorFixture();f.beginDoor();BaseFixture other=new BaseFixture();
        Map<String,Object> before=fields(f.navigator);int moves=f.moves,stops=f.stops;
        for(Context wrong:List.of(new Context(other,f,f.navigator,f.profile,f.context.session()),
            new Context(f,other,f.navigator,f.profile,f.context.session()),
            new Context(f,f,new LocalNavigator(),f.profile,f.context.session())))
            assertNull(f.navigator.pendingInteractionOutcome(wrong));
        assertNull(f.navigator.pendingInteractionOutcome(null));
        assertNull(new LocalNavigator().pendingInteractionOutcome(f.context));
        assertEquals(before,fields(f.navigator));assertEquals(moves,f.moves);assertEquals(stops,f.stops);
        assertEquals(1,f.submits);assertEquals(0,f.outcomes);assertEquals(0,other.outcomes);assertEquals(0,f.cancels);
        f.navigator.reset();assertNull(f.navigator.pendingInteractionOutcome(f.context));assertEquals(0,f.outcomes);
    }

    @Test void realTerrainFollowingAlsoOptsInAfterItsBoundedSearchFinishes() {
        Fixture f=new Fixture();f.profile.navigationMode=NavigationMode.TERRAIN;
        for(int n=0;n<100;n++) {
            assertEquals(Navigation.Result.MOVING,f.navigator.moveTo(TARGET,.1,f.context));
            if("FOLLOWING".equals(f.navigator.diagnosticStatus()))break;
            f.now++;
        }
        assertEquals("FOLLOWING",f.navigator.diagnosticStatus());assertTrue(f.navigator.canYieldTravel(f.context));
    }

    @Test void groundSupportMayBeUniformFarmlandOrSlabButNotAStairHighTread() {
        for(double height:new double[]{64,63.9375,63.5}) {
            Fixture f=new Fixture();f.floor=height;f.y=height;f.begin();
            assertTrue(f.navigator.canYieldTravel(f.context),"Equal verified full-footprint support remains flat at "+height);
        }
        for(Pos cell:List.of(START,NEXT)) {
            Fixture f=new Fixture();f.begin();f.partial.add(cell);
            assertTrue(f.canStand(cell));assertEquals(64,f.standingY(cell));
            assertFalse(f.navigator.canYieldTravel(f.context),"Integer-height partial support is not a full floor");
        }
    }

    @ParameterizedTest @ValueSource(strings={"loggingJump","descent","interruptedLanding","interruptedDescent","interruptedJumps",
        "doorTicket","endpointSettleTick","search","frontier","frontierWait","loggingPath","plantingTargets","requestInteractions",
        "previousMoving","failureKind","diagnostic","nextIndex","pastPath"})
    void groundedControllersAndOtherOwnedNavigationStatesNeverYield(String guard)throws Exception {
        Fixture f=new Fixture();f.begin();
        Object value=switch(guard) {
            case "loggingJump" -> new StepUpController(new LoggingJumpEdge(START,NEXT.offset(0,1,0)));
            case "descent" -> new DescentController(START,NEXT.offset(0,-1,0),f);
            case "interruptedLanding","interruptedDescent","loggingPath" -> true;
            case "interruptedJumps" -> Set.of(new LoggingJumpEdge(START,NEXT.offset(0,1,0)));
            case "doorTicket","endpointSettleTick","frontierWait" -> 0L;
            case "search" -> new TerrainPathSearch(START,TARGET,.1,f,f.profile,new TravelDomain(f.profile,START,TARGET),Set.of(),Set.of(),false,TerrainPathSearch.Goal.INTERACTION,List.of(),false);
            case "frontier" -> new TerrainPathSearch.Frontier(START,NEXT,false);
            case "plantingTargets" -> List.of(START);
            case "requestInteractions","previousMoving" -> false;
            case "failureKind" -> Navigation.Failure.REACH;
            case "diagnostic" -> "SEARCHING";
            case "nextIndex" -> 0;
            case "pastPath" -> 99;
            default -> throw new AssertionError(guard);
        };
        set(f.navigator,guard.equals("pastPath")?"nextIndex":guard,value);
        Map<String,Object> before=fields(f.navigator);int moves=f.moves,stops=f.stops;
        assertTrue(f.player().onGround());assertFalse(f.navigator.canYieldTravel(f.context),guard);
        assertEquals(before,fields(f.navigator));assertEquals(moves,f.moves);assertEquals(stops,f.stops);
        assertEquals(0,f.submits);assertEquals(0,f.outcomes);
    }

    @Test void theLoggingJumpControllerIsAlsoRejectedBeforeAnyLaunch()throws Exception {
        Fixture f=new Fixture();f.begin();LoggingJumpController jump=new LoggingJumpController(new LoggingJumpEdge(START,NEXT.offset(0,1,0)));
        set(f.navigator,"loggingJump",jump);assertEquals(LoggingJumpController.Phase.PREPARE,jump.phase());
        assertFalse(f.navigator.canYieldTravel(f.context));assertEquals(LoggingJumpController.Phase.PREPARE,jump.phase());
    }

    @ParameterizedTest @ValueSource(strings={"airborne","sleeping","disconnected","unfocused","busy","menu","cursor","nullMenu","nullCursor","nanX","nanY","nanZ"})
    void unsafePlayerOrActionStateIsOnlyReadAndNeverReconciled(String guard) {
        Fixture f=new Fixture();f.begin();
        switch(guard) {
            case "airborne" -> f.grounded=false;
            case "sleeping" -> f.sleeping=true;
            case "disconnected" -> f.connected=false;
            case "unfocused" -> { f.focused=false;f.profile.allowBackground=false; }
            case "busy" -> f.busy=true;
            case "menu" -> f.container=true;
            case "cursor" -> f.cursor=new ItemData("minecraft:stone",1,0,null,false,0);
            case "nullMenu" -> f.nullMenu=true;
            case "nullCursor" -> f.cursor=null;
            case "nanX" -> f.x=Double.NaN;
            case "nanY" -> f.y=Double.NaN;
            case "nanZ" -> f.z=Double.NaN;
        }
        int moves=f.moves,stops=f.stops;
        assertFalse(f.navigator.canYieldTravel(f.context));assertEquals(moves,f.moves);assertEquals(stops,f.stops);
        assertEquals(0,f.submits);assertEquals(0,f.outcomes);assertEquals(0,f.cancels);
    }

    @Test void focusAndLoggingOwnershipRemainSeparateFromTheGeometryGrant() {
        Fixture f=new Fixture();f.begin();f.focused=false;f.profile.allowBackground=true;
        f.profile.loggingRunActive=true;f.profile.enabled.put(Feature.LOGGING,false);
        assertTrue(f.navigator.canYieldTravel(f.context),"The engine separately validates its existing suspended-logging grant");
    }

    @Test void profileSessionModeActionsWorldAndNavigatorMustBeTheOriginalRequest() {
        Fixture f=new Fixture();f.begin();Fixture other=new Fixture();
        for(Context wrong:List.of(new Context(f,f,f.navigator,new Profile(),f.context.session()),
            new Context(f,f,f.navigator,f.profile,new SessionState()),new Context(other,f,f.navigator,f.profile,f.context.session()),
            new Context(f,other,f.navigator,f.profile,f.context.session()),new Context(f,f,new LocalNavigator(),f.profile,f.context.session())))
            assertFalse(f.navigator.canYieldTravel(wrong));
        f.profile.navigationMode=NavigationMode.TERRAIN;assertFalse(f.navigator.canYieldTravel(f.context));
        assertFalse(f.navigator.canYieldTravel(null));
    }

    @Test void staleFutureAndResetRoutesCannotBeBorrowed() {
        Fixture f=new Fixture();f.begin();f.now++;
        assertTrue(f.navigator.canYieldTravel(f.context));f.now++;
        assertFalse(f.navigator.canYieldTravel(f.context));f.now=-1;
        assertFalse(f.navigator.canYieldTravel(f.context));f.navigator.reset();
        assertFalse(f.navigator.canYieldTravel(f.context));
        assertFalse(new LocalNavigator().canYieldTravel(f.context));
    }

    @Test void bothCompleteStandingCellsMustRemainLoadedStandableAndTraversable() {
        for(Pos feet:List.of(START,NEXT))for(int dy:new int[]{-1,0,1}) {
            Fixture f=new Fixture();f.begin();f.unloaded.add(feet.offset(0,dy,0));
            assertFalse(f.navigator.canYieldTravel(f.context));
        }
        for(Pos feet:List.of(START,NEXT)) {
            Fixture f=new Fixture();f.begin();f.blocked.add(feet);assertFalse(f.navigator.canYieldTravel(f.context));
        }
        Fixture f=new Fixture();f.begin();f.traversable=false;assertFalse(f.navigator.canYieldTravel(f.context));
        f.traversable=true;f.fullProof=false;assertFalse(f.navigator.canYieldTravel(f.context));
        f.fullProof=true;f.throwProof=true;assertFalse(f.navigator.canYieldTravel(f.context));
    }

    @Test void unequalUnknownHeightsOrHalfTreadPositionCannotPassForFlatGround() {
        for(double value:new double[]{63.5,64.5,Double.NaN,Double.POSITIVE_INFINITY}) {
            Fixture f=new Fixture();f.begin();f.heights.put(NEXT,value);assertFalse(f.navigator.canYieldTravel(f.context));
        }
        for(double value:new double[]{63.5,64.1,Double.NaN}) {
            Fixture f=new Fixture();f.begin();f.y=value;assertFalse(f.navigator.canYieldTravel(f.context));
        }
        Fixture f=new Fixture();f.begin();f.heights.put(START,Double.NaN);assertFalse(f.navigator.canYieldTravel(f.context));
    }

    @Test void doorsAndUnavailableBlockProjectionDoNotIssueAnOpenOrClearAWait() {
        for(Pos feet:List.of(START,NEXT))for(int dy:new int[]{0,1})for(String state:List.of("closed","open","unknown","null","wrongPos")) {
            Fixture f=new Fixture();f.begin();Pos p=feet.offset(0,dy,0);
            f.blocks.put(p,switch(state) {
                case "closed","open" -> new BlockData(p,"minecraft:oak_door",Map.of("open",Boolean.toString(state.equals("open"))));
                case "unknown" -> new BlockData(p,null,Map.of());
                case "wrongPos" -> new BlockData(p.offset(0,0,1),"minecraft:air",Map.of());
                default -> null;
            });
            assertFalse(f.navigator.canYieldTravel(f.context));assertEquals(0,f.submits);
        }
    }

    @Test void positionMustRemainOnTheTrackedEdgeWithoutAdvancingItsIndex()throws Exception {
        Fixture f=new Fixture();f.begin();Object next=fields(f.navigator).get("nextIndex");f.x=1.05;
        assertTrue(f.navigator.canYieldTravel(f.context));assertEquals(next,fields(f.navigator).get("nextIndex"));
        f.x=3.5;assertFalse(f.navigator.canYieldTravel(f.context));
        f.x=.5;f.z=1.5;assertFalse(f.navigator.canYieldTravel(f.context));
    }

    @Test void changedOrNonAdjacentPathAndOutOfDomainCellsAreRejected()throws Exception {
        for(Pos next:List.of(NEXT.offset(0,-1,0),NEXT.offset(0,1,0),new Pos(3,64,0),START)) {
            Fixture f=new Fixture();f.begin();set(f.navigator,"path",List.of(START,next,TARGET));
            assertFalse(f.navigator.canYieldTravel(f.context));
        }
        Fixture f=new Fixture();f.begin();Profile elsewhere=new Profile();elsewhere.navigationMode=NavigationMode.WAYPOINTS;
        elsewhere.farms.add(new Farm("elsewhere",new Pos(100,64,0),new Pos(108,64,0)));
        set(f.navigator,"domain",new TravelDomain(elsewhere,new Pos(100,64,0),new Pos(108,64,0)));
        assertFalse(f.navigator.canYieldTravel(f.context));
    }

    @Test void aDiagonalRequiresItsExistingNativeCornerProofAsWell()throws Exception {
        Fixture f=new Fixture();f.begin();Pos diagonal=new Pos(1,64,1);
        set(f.navigator,"path",List.of(START,diagonal,TARGET));
        assertFalse(f.navigator.canYieldTravel(f.context));f.nativeDiagonal=true;
        assertTrue(f.navigator.canYieldTravel(f.context));
        f.unloaded.add(new Pos(0,64,1));assertFalse(f.navigator.canYieldTravel(f.context));
    }

    private static Map<String,Object> fields(Object owner)throws Exception {
        Map<String,Object> values=new LinkedHashMap<>();
        for(Field field:owner.getClass().getDeclaredFields()){field.setAccessible(true);Object value=field.get(owner);values.put(field.getName(),value instanceof Collection<?> c?new ArrayList<>(c):value);}
        return values;
    }
    private static void set(Object owner,String name,Object value)throws Exception {Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}

    private static class BaseFixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final LocalNavigator navigator=new LocalNavigator();final Context context;
        final Set<Pos> unloaded=new HashSet<>(),blocked=new HashSet<>();final Map<Pos,Double> heights=new HashMap<>();final Map<Pos,BlockData> blocks=new HashMap<>();
        long now;double x=.5,y=64,z=.5,floor=64;boolean grounded=true,connected=true,focused=true,sleeping,busy,container,nullMenu,nativeDiagonal,traversable=true;
        ItemData cursor=ItemData.EMPTY;Movement movement;int moves,stops,submits,outcomes,cancels,inventoryReads;
        BaseFixture(){profile.navigationMode=NavigationMode.WAYPOINTS;profile.farms.add(new Farm("synthetic",START,new Pos(8,64,8)));context=new Context(this,this,navigator,profile);}
        void begin(){assertEquals(Navigation.Result.MOVING,navigator.moveTo(TARGET,.1,context));assertEquals("FOLLOWING",navigator.diagnosticStatus());assertNotNull(movement);}
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,grounded,sleeping,20,20,4,connected,focused);}
        public boolean loaded(Pos p){return !unloaded.contains(p);}
        public boolean canStand(Pos p){return p.y()==64&&p.x()>=0&&p.x()<=8&&p.z()>=0&&p.z()<=8&&!blocked.contains(p);}
        public double standingY(Pos p){return canStand(p)?heights.getOrDefault(p,floor):Double.NaN;}
        public boolean canTraverse(Pos a,Pos b){return traversable&&canStand(a)&&canStand(b)&&Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())==1;}
        public boolean canTraverseDiagonal(Pos a,Pos b){return nativeDiagonal;}
        public boolean canInteract(Pos p,double reach){return false;}public boolean canInteractFrom(Pos p,Pos target,double reach){return p.equals(target);}
        public BlockData block(Pos p){return blocks.containsKey(p)?blocks.get(p):new BlockData(p,"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos center,int h,int v){throw new AssertionError("No scans");}
        public List<ItemSlot> inventory(){inventoryReads++;throw new AssertionError("No inventory query");}
        public MenuData menu(){return nullMenu?null:new MenuData(0,0,List.of(),cursor,container);}
        public boolean mayPlace(int slot,ItemData item){throw new AssertionError();}
        public boolean busy(){return busy;}public long submit(Action action){submits++;throw new AssertionError("No action");}
        public ActionOutcome outcome(long ticket){outcomes++;throw new AssertionError("No acknowledgement");}
        public String pauseReason(){throw new AssertionError("No reconciling getter");}public String startRejection(){throw new AssertionError("No reconciliation");}
        public void move(Movement value){moves++;movement=value;}public void stopMovement(){stops++;movement=null;}public void cancel(){cancels++;}
    }
    private static final class Fixture extends BaseFixture {
        final Set<Pos> partial=new HashSet<>();boolean fullProof=true,throwProof;
        @Override public boolean fullFlatSupport(Pos p){if(throwProof)throw new IllegalStateException("Unavailable native shape");return fullProof&&!partial.contains(p);}
    }
    private static final class DoorFixture extends BaseFixture {
        private static final long DOOR_TICKET=73;
        ActionOutcome reply=new ActionOutcome(ActionOutcome.State.PENDING,"door pending");
        void beginDoor() {
            blocks.put(NEXT,new BlockData(NEXT,"minecraft:oak_door",Map.of("open","false")));
            assertEquals(Navigation.Result.MOVING,navigator.moveTo(TARGET,.1,context));
            assertEquals(1,submits);assertTrue(busy);
        }
        @Override public boolean canInteract(Pos p,double reach){return p.equals(NEXT)&&reach>=4.25;}
        @Override public long submit(Action action) {
            assertEquals(new Action.UseBlock(NEXT,Action.Use.DOOR),action);assertFalse(busy);
            submits++;busy=true;return DOOR_TICKET;
        }
        @Override public ActionOutcome outcome(long ticket){assertEquals(DOOR_TICKET,ticket);outcomes++;return reply;}
    }
}
