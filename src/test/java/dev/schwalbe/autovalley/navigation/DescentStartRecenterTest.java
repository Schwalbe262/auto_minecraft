package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Detached inward-walking model; native hazard/swept-volume proofs stay authoritative. */
class DescentStartRecenterTest {
    private static final Pos TOP=new Pos(0,1,0),LOW=new Pos(1,0,0);

    @Test void supportedOffCenterOriginIsQuietlyRecenteredBeforeDescending() {
        for (double response:new double[]{.06,.13,.30}) {
            Fixture f=new Fixture(response);
            assertEquals(TOP,NavigationFeet.resolve(f,f.player()));
            assertTrue(f.distance()>.45 && f.distance()<.46,"The observed failure was just outside preparation's existing bound");
            assertEquals(Navigation.Result.MOVING,f.begin());
            assertEquals("GROUND_RECENTER",f.navigation.diagnosticStatus());
            assertEquals(TOP,f.navigation.diagnostics().get("recenterAnchor"));
            assertNull(f.movement);
            long started=f.now;
            for(int i=0;i<2;i++) {
                f.advance();assertEquals(Navigation.Result.MOVING,f.move());
                assertNull(f.movement,"Two initial quiet intervals must precede input");
            }
            f.finishRecenter();
            assertTrue(f.now-started<=60);assertTrue(f.distance()<=.06);
            assertTrue(Math.hypot(f.lastDx,f.lastDz)<=.002);assertNull(f.movement);
            assertEquals(1,f.y);assertEquals(TOP,NavigationFeet.resolve(f,f.player()));
            assertEquals(0,f.navigation.diagnostics().get("pathLength"),"The old edge is discarded after actual quiet centering");
            assertFalse(f.inputs.isEmpty());assertTrue(f.proofTargets.stream().allMatch(TOP::equals));
            f.advance();assertEquals(Navigation.Result.MOVING,f.begin());
            assertEquals("DESCENT_PREPARE",f.navigation.diagnosticStatus());
            assertNull(f.navigation.diagnostics().get("recenterAnchor"));assertNull(f.movement);
            assertEquals(0,f.submissions);
        }
    }

    @Test void descentPreparationItselfDoesNotAcceptAnExpandedOriginRadius() {
        Fixture f=new Fixture(.13);DescentController controller=new DescentController(TOP,LOW,f);
        assertEquals(Navigation.Result.BLOCKED,controller.tick(f.context));
        assertTrue(controller.failureReason().contains("상단"));assertTrue(f.inputs.isEmpty());
        assertEquals(.45,DescentController.MAX_PREPARE_DISTANCE);
    }

    @Test void unknownProofAndUnsafeInitialConditionsCannotAuthorizeRecenterInput() {
        for(String invalid:List.of("proof","nonflat","wrong height","airborne","cursor","busy","fence","unknown menu","closed door")) {
            Fixture f=new Fixture(.13);
            switch(invalid) {
                case "proof"->f.proof=false;
                case "nonflat"->f.flat=false;
                case "wrong height"->f.y=1.01;
                case "airborne"->f.grounded=false;
                case "cursor"->f.cursor=new ItemData("minecraft:stone",1,0,null,false,Integer.MAX_VALUE);
                case "busy"->f.busy=true;
                case "fence"->f.fence="unconfirmed";
                case "unknown menu"->f.unknownMenu=true;
                case "closed door"->f.closedDoor=true;
                default->throw new AssertionError(invalid);
            }
            assertEquals(Navigation.Result.BLOCKED,f.begin(),invalid);
            assertNull(f.navigation.diagnostics().get("recenterAnchor"),invalid);
            assertTrue(f.inputs.isEmpty(),invalid);assertNull(f.movement,invalid);assertEquals(0,f.submissions);
        }
    }

    @Test void waypointsAndHarvestLookaheadDoNotGainTheTerrainRecoveryPermission() {
        Fixture waypoints=new Fixture(.13);waypoints.profile.navigationMode=NavigationMode.WAYPOINTS;
        waypoints.profile.pois.add(new Poi(TOP,PoiKind.WAYPOINT,"top",null));
        waypoints.profile.pois.add(new Poi(LOW,PoiKind.WAYPOINT,"bottom",null));
        assertEquals(Navigation.Result.BLOCKED,waypoints.begin());
        assertTrue(waypoints.proofTargets.isEmpty());assertTrue(waypoints.inputs.isEmpty());
        Fixture lookahead=new Fixture(.13);lookahead.lookahead=true;
        assertEquals(Navigation.Result.BLOCKED,lookahead.begin());
        assertTrue(lookahead.proofTargets.isEmpty());assertTrue(lookahead.inputs.isEmpty());
    }

    @Test void anAlreadyCenteredStartKeepsTheOriginalDescentPreparation() {
        Fixture f=new Fixture(.13);f.x=.5;f.z=.5;
        assertEquals(Navigation.Result.MOVING,f.begin());
        assertEquals("DESCENT_PREPARE",f.navigation.diagnosticStatus());
        assertTrue(f.proofTargets.isEmpty());assertTrue(f.inputs.isEmpty());
    }

    @Test void losingTopSupportOrItsNativeHazardProofStopsTheActiveRecenterImmediately() {
        for(String change:List.of("proof","support","unloaded","nonflat","height","airborne","cursor","fence")) {
            Fixture f=new Fixture(.13);f.untilInput();int count=f.inputs.size();
            switch(change) {
                case "proof"->f.proof=false;
                case "support"->f.standing.remove(TOP);
                case "unloaded"->f.unloaded=TOP.offset(0,-1,0);
                case "nonflat"->f.flat=false;
                case "height"->f.topHeight=1.125;
                case "airborne"->f.grounded=false;
                case "cursor"->f.cursor=new ItemData("minecraft:stone",1,0,null,false,Integer.MAX_VALUE);
                case "fence"->f.fence="unconfirmed";
                default->throw new AssertionError(change);
            }
            f.now++;assertEquals(Navigation.Result.BLOCKED,f.move(),change);
            assertEquals(count,f.inputs.size(),change);assertNull(f.movement,change);
            assertEquals(0,f.submissions);
        }
    }

    @Test void aRecenterProofNeverPreservesPermissionForAChangedLandingOrDrop() {
        for(String change:List.of("hazardous landing","blocked edge","excessive drop")) {
            Fixture f=new Fixture(.13);assertEquals(Navigation.Result.MOVING,f.begin());f.finishRecenter();
            int count=f.inputs.size();
            switch(change) {
                case "hazardous landing"->f.standing.remove(LOW);
                case "blocked edge"->f.traversable=false;
                case "excessive drop"->f.lowHeight=-.125;
                default->throw new AssertionError(change);
            }
            f.advance();assertEquals(Navigation.Result.BLOCKED,f.begin(),change);
            assertEquals(count,f.inputs.size(),change);assertNull(f.movement,change);
            assertFalse(f.navigation.diagnosticStatus().startsWith("DESCENT"),change);
            assertEquals(0,f.submissions);
        }
    }

    @Test void aStalledSupportedOriginCannotLoopRecenterAttempts() {
        Fixture f=new Fixture(.13);Navigation.Result result=f.begin();long started=f.now;
        for(int i=0;i<65 && result==Navigation.Result.MOVING;i++) { f.now++;result=f.move(); }
        assertEquals(Navigation.Result.BLOCKED,result);assertTrue(f.now-started<=61);
        assertNull(f.movement);int count=f.inputs.size();
        for(int i=0;i<5;i++) { f.now++;assertEquals(Navigation.Result.BLOCKED,f.begin()); }
        assertEquals(count,f.inputs.size());assertEquals(0,f.submissions);
    }

    @Test void failureRetryRequiresAnExplicitNativeProofAndDoesNotChangeNavigationOrActions() {
        Fixture f=new Fixture(.13);f.proof=false;
        assertEquals(Navigation.Result.BLOCKED,f.begin());assertEquals(Navigation.Failure.SAFETY,f.navigation.failureKind());
        assertFalse(f.navigation.safeFailureRetry(f.context));
        f.proof=true;Map<String,Object> before=f.navigation.diagnostics();int stops=f.stops;
        for(int i=0;i<5;i++)assertTrue(f.navigation.safeFailureRetry(f.context));
        assertEquals(before,f.navigation.diagnostics());assertEquals(stops,f.stops);
        assertEquals(Navigation.Failure.SAFETY,f.navigation.failureKind());assertFalse(f.navigation.retryableFailure());
        assertTrue(f.inputs.isEmpty());assertEquals(0,f.submissions);assertEquals(0,f.outcomes);
        Navigation unknown=new Navigation() {
            public Result moveTo(Pos target,double reach,Context context){return Result.BLOCKED;}
            public void reset(){throw new AssertionError("A proof cannot reset navigation");}
        };
        assertFalse(unknown.safeFailureRetry(new Context(f,f,unknown,f.profile,f.context.session())));
        assertFalse(f.navigation.safeFailureRetry(null));
    }

    @Test void invalidStartRetryAlsoNeedsFreshFlatSupportRatherThanTheFailedRecenterState() {
        Fixture f=new Fixture(.13);f.untilInput();f.proof=false;f.now++;
        assertEquals(Navigation.Result.BLOCKED,f.move());assertEquals(Navigation.Failure.INVALID_START,f.navigation.failureKind());
        assertFalse(f.navigation.safeFailureRetry(f.context));
        f.proof=true;int inputs=f.inputs.size(),stops=f.stops;
        assertTrue(f.navigation.safeFailureRetry(f.context));assertEquals(inputs,f.inputs.size());assertEquals(stops,f.stops);
        assertEquals(Navigation.Failure.INVALID_START,f.navigation.failureKind());assertNull(f.movement);
    }

    @Test void changedPoseHazardsAndNativeActionFencesRejectTheFailureRetryProof() {
        for(String change:List.of("proof","proof unavailable","nonflat","support","unloaded","height","unknown height",
            "airborne","not finite","outside domain","sleeping","dead","unknown health","disconnected","focus","cursor","unknown cursor","container","menu","busy","fence","door")) {
            Fixture f=failedFixture();
            switch(change) {
                case "proof"->f.proof=false;
                case "proof unavailable"->f.proofThrows=true;
                case "nonflat"->f.flat=false;
                case "support"->f.standing.remove(TOP);
                case "unloaded"->f.unloaded=TOP.offset(0,-1,0);
                case "height"->f.y=1.01;
                case "unknown height"->f.topHeight=Double.NaN;
                case "airborne"->f.grounded=false;
                case "not finite"->f.x=Double.NaN;
                case "outside domain"->{f.x=300.5;f.standing.add(new Pos(300,1,0));}
                case "sleeping"->f.sleeping=true;
                case "dead"->f.health=0;
                case "unknown health"->f.health=Float.NaN;
                case "disconnected"->f.connected=false;
                case "focus"->{f.profile.allowBackground=false;f.focused=false;}
                case "cursor"->f.cursor=new ItemData("minecraft:stone",1,0,null,false,Integer.MAX_VALUE);
                case "unknown cursor"->f.cursor=null;
                case "container"->f.container=true;
                case "menu"->f.unknownMenu=true;
                case "busy"->f.busy=true;
                case "fence"->f.fence="unconfirmed";
                case "door"->f.closedDoor=true;
                default->throw new AssertionError(change);
            }
            int stops=f.stops;assertFalse(f.navigation.safeFailureRetry(f.context),change);
            assertEquals(stops,f.stops,change);assertTrue(f.inputs.isEmpty(),change);assertEquals(0,f.outcomes);
        }
    }

    @Test void unfinishedControllersAndEvenACompletedUnconsumedDoorTicketCannotBorrowRetryPermission() throws Exception {
        for(String field:List.of("doorTicket","loggingJump","descent","groundRecenter","stairRecenter","stairRecoveryRetryAt",
            "interruptedLanding","interruptedDescent","interruptedJumps","requestInteractions")) {
            Fixture f=failedFixture();LoggingJumpEdge edge=new LoggingJumpEdge(LOW,TOP);
            Object value=switch(field) {
                case "doorTicket"->7L;
                case "loggingJump"->new LoggingJumpController(edge);
                case "descent"->new DescentController(TOP,LOW,f);
                case "groundRecenter"->new GroundRecenterController(TOP,f.context);
                case "stairRecenter"->new StairRecenterController(TOP,f.context);
                case "stairRecoveryRetryAt"->0L;
                case "interruptedLanding","interruptedDescent"->true;
                case "interruptedJumps"->new HashSet<>(Set.of(edge));
                case "requestInteractions"->false;
                default->throw new AssertionError(field);
            };
            set(f.navigation,field,value);int stops=f.stops;
            assertFalse(f.navigation.safeFailureRetry(f.context),field);assertEquals(stops,f.stops,field);
            // No outcome read means even a completed-but-unconsumed ticket remains owned.
            assertEquals(0,f.outcomes,field);assertTrue(f.inputs.isEmpty(),field);
        }
        for(Navigation.Failure failure:Navigation.Failure.values()) {
            Fixture f=failedFixture();set(f.navigation,"failureKind",failure);
            assertEquals(failure==Navigation.Failure.SAFETY || failure==Navigation.Failure.INVALID_START,
                f.navigation.safeFailureRetry(f.context),failure.name());
        }
    }

    @Test void retryProofCannotBeTransferredToAnotherContextOrNavigationMode() {
        Fixture f=failedFixture(),other=new Fixture(.13);
        for(Context context:List.of(new Context(other,f,f.navigation,f.profile,f.context.session()),
            new Context(f,other,f.navigation,f.profile,f.context.session()),
            new Context(f,f,other.navigation,f.profile,f.context.session()),
            new Context(f,f,f.navigation,other.profile,f.context.session()),
            new Context(f,f,f.navigation,f.profile,new SessionState())))assertFalse(f.navigation.safeFailureRetry(context));
        f.profile.navigationMode=NavigationMode.WAYPOINTS;assertFalse(f.navigation.safeFailureRetry(f.context));
        assertTrue(f.inputs.isEmpty());assertEquals(0,f.outcomes);
    }

    private static Fixture failedFixture() {
        Fixture f=new Fixture(.13);f.proof=false;assertEquals(Navigation.Result.BLOCKED,f.begin());f.proof=true;
        assertTrue(f.navigation.safeFailureRetry(f.context));return f;
    }
    private static void set(Object target,String name,Object value)throws Exception {
        java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile();final LocalNavigator navigation=new LocalNavigator();
        final Context context=new Context(this,this,navigation,profile,new SessionState());
        final Set<Pos> standing=new HashSet<>(Set.of(TOP,LOW));
        final List<Movement> inputs=new ArrayList<>();final List<Pos> proofTargets=new ArrayList<>();
        final double response;
        double x=.4682,y=1,z=.9523,vx,vz,lastDx,lastDz,topHeight=1,lowHeight;
        long now;boolean proof=true,flat=true,grounded=true,busy,unknownMenu,closedDoor,traversable=true,lookahead,proofThrows,container,sleeping;
        boolean connected=true,focused=true;float health=20;
        Pos unloaded;String fence;ItemData cursor=ItemData.EMPTY;Movement movement;int submissions,stops,outcomes;
        Fixture(double response) { this.response=response;profile.navigationMode=NavigationMode.TERRAIN; }
        public long tick(){return now;}public long dayTime(){return 5000;}
        public PlayerState player(){return new PlayerState(x,y,z,0,0,grounded,sleeping,health,20,0,connected,focused);}
        public boolean loaded(Pos p){return !p.equals(unloaded);}
        public boolean canStand(Pos p){return loaded(p)&&standing.contains(p);}
        public double standingY(Pos p){return !canStand(p)?Double.NaN:p.equals(TOP)?topHeight:lowHeight;}
        public boolean fullFlatSupport(Pos p){return flat&&canStand(p);}
        public boolean canRecenterOnSupport(Pos p){proofTargets.add(p);if(proofThrows)throw new IllegalStateException("Native proof unavailable");return proof&&p.equals(TOP);}
        public boolean canTraverse(Pos a,Pos b){return traversable&&canStand(a)&&canStand(b)
            && Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z())==1
            && Math.abs(standingY(a)-standingY(b))<=WalkingSurfaceRules.MAX_DESCENT_HEIGHT+1.0e-5;}
        public boolean standardDescentPhysics(){return true;}
        public boolean canInteract(Pos p,double reach){return false;}
        public boolean canInteractFrom(Pos from,Pos target,double reach){return from.equals(target);}
        public BlockData block(Pos p){return new BlockData(p,closedDoor&&p.equals(TOP)?"minecraft:oak_door":"minecraft:air",Map.of());}
        public List<BlockData> scan(Pos p,int h,int v){return List.of();}
        public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return unknownMenu?null:new MenuData(0,0,List.of(),cursor,container);}
        public boolean mayPlace(int slot,ItemData item){return false;}
        public boolean busy(){return busy;}public String pauseReason(){return fence;}
        public long submit(Action action){submissions++;throw new AssertionError("Source centering cannot interact");}
        public ActionOutcome outcome(long ticket){outcomes++;throw new AssertionError("No action receipt belongs to source centering");}
        public void move(Movement input){
            assertFalse(input.jump());assertFalse(input.sprint());assertFalse(input.sneak());assertTrue(grounded);
            assertEquals("GROUND_RECENTER",navigation.diagnosticStatus());
            assertEquals(distance()<=.20?.1f:.2f,input.inputScale());
            movement=input;inputs.add(input);
        }
        public void stopMovement(){stops++;movement=null;}public void cancel(){stopMovement();}
        Navigation.Result move(){return lookahead?navigation.moveToWithoutInteraction(LOW,.1,context):navigation.moveToPosition(LOW,.1,context);}
        Navigation.Result begin(){
            for(int i=0;i<30;i++) {
                Navigation.Result result=move();
                if(!navigation.diagnosticStatus().equals("SEARCHING"))return result;
                assertNull(movement);now++;
            }
            throw new AssertionError("The two-cell search did not finish within its bounded slices");
        }
        double distance(){return Math.hypot(x-TOP.x()-.5,z-TOP.z()-.5);}
        void advance(){
            if(movement!=null&&movement.forward()) {
                double yaw=Math.toRadians(movement.yaw());vx-=Math.sin(yaw)*response*movement.inputScale();vz+=Math.cos(yaw)*response*movement.inputScale();
            }
            lastDx=vx;lastDz=vz;x+=vx;z+=vz;vx*=.546;vz*=.546;now++;
            assertEquals(TOP,NavigationFeet.resolve(this,player()),"The recenter never leaves its exact upper support");
        }
        void untilInput(){
            assertEquals(Navigation.Result.MOVING,begin());
            for(int i=0;i<15&&inputs.isEmpty();i++){advance();assertEquals(Navigation.Result.MOVING,move());}
            assertNotNull(movement);
        }
        void finishRecenter(){
            for(int i=0;i<65;i++) {
                assertEquals(Navigation.Result.MOVING,move(),navigation.failureReason());
                if(navigation.diagnosticStatus().equals("GROUND_RECENTER_COMPLETE"))return;
                advance();
            }
            fail("No bounded source centering completion: "+navigation.failureReason());
        }
    }
}
