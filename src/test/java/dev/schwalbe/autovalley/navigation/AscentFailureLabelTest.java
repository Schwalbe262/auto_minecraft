package dev.schwalbe.autovalley.navigation;

import dev.schwalbe.autovalley.core.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

class AscentFailureLabelTest {
    private static final Pos FROM=new Pos(0,0,0),TO=new Pos(1,1,0);
    private static final LoggingJumpEdge EDGE=new LoggingJumpEdge(FROM,TO);
    private record FailureCase(String name,String suffix,Consumer<Fixture> trigger) {
        @Override public String toString() { return name; }
    }

    static Stream<Arguments> failures() {
        List<FailureCase> cases=List.of(
            new FailureCase("alignment timeout"," 출발점에 안전하게 정렬하지 못했습니다.",f -> {
                f.x=.7; assertEquals(Navigation.Result.MOVING,f.step());
                f.now=60; assertEquals(Navigation.Result.MOVING,f.step(),"The original 60-tick boundary remains inclusive");
                f.now=61; assertEquals(Navigation.Result.BLOCKED,f.step()); assertEquals(0,f.launches);
            }),
            new FailureCase("clock rollback"," 중 시간이 되돌아갔습니다. 자동 재점프하지 않습니다.",f -> {
                f.now=1; f.step(); f.now=0; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("support changed","의 출발 또는 착지 지면 높이가 바뀌었습니다.",f -> {
                f.step(); f.heightOffset=.125; f.now++; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("source not grounded","는 확인된 출발 지면에서만 시작합니다.",f -> {
                f.grounded=false; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("landing timeout"," 착지가 시간 안에 확인되지 않았습니다. 자동 재점프하지 않습니다.",f -> {
                f.launch(); f.now+=41; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("flight corridor","의 검증된 비행 범위를 벗어났습니다.",f -> {
                f.launch(); f.x=1.7; f.y=.5; f.grounded=false; f.now++; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("no lift","의 이륙 또는 착지가 확인되지 않았습니다. 자동 재점프하지 않습니다.",f -> {
                f.launch(); f.now+=5; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("lost landing ground"," 착지 확인 중 다시 지면에서 벗어났습니다.",f -> {
                f.land(); f.grounded=false; f.now++; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("left landing cell","의 확인된 착지 지면을 벗어났습니다.",f -> {
                f.land(); f.x=2; f.now++; assertEquals(Navigation.Result.BLOCKED,f.step());
            }),
            new FailureCase("cancelled","가 취소되었습니다. 같은 요청을 재전송하지 않습니다.",f -> {
                f.step(); f.controller.cancel();
            })
        );
        return Stream.of(false,true).flatMap(logging -> cases.stream().map(test -> Arguments.of(logging,test)));
    }

    @ParameterizedTest(name="logging={0}: {1}")
    @MethodSource("failures")
    void onlyTheFailureLabelChangesAndEveryFailureStaysLatched(boolean logging,FailureCase test) {
        Fixture f=new Fixture(logging); test.trigger().accept(f);
        String expected=(logging?"벌목 오르기":"한 칸 오르기")+test.suffix();
        assertEquals(expected,f.controller.failureReason());
        assertEquals(LoggingJumpController.Phase.FAILED,f.controller.phase());
        assertNull(f.movement);
        int launches=f.launches,moves=f.moves;
        f.now++; assertEquals(Navigation.Result.BLOCKED,f.step());
        assertEquals(expected,f.controller.failureReason());
        assertEquals(launches,f.launches); assertEquals(moves,f.moves);
        assertTrue(f.launches<=1); assertEquals(0,f.submissions);
    }

    private static final class Fixture implements WorldAccess,ActionPort {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final LoggingJumpController controller; final Context context; final boolean logging;
        long now; double x=.5,y,z=.5,heightOffset; boolean grounded=true;
        int launches,moves,submissions; Movement movement;
        Fixture(boolean logging) {
            this.logging=logging;
            profile.navigationMode=logging?NavigationMode.WAYPOINTS:NavigationMode.TERRAIN;
            if(logging) {
                profile.farms.add(new Farm("synthetic corridor",new Pos(-1,-1,-1),new Pos(2,3,1)));
                profile.loggingRunActive=true; session.oneShotFeature=Feature.LOGGING;
            }
            controller=logging?new LoggingJumpController(EDGE):new StepUpController(EDGE);
            context=new Context(this,this,new LocalNavigator(),profile,session);
        }
        Navigation.Result step() { return controller.tick(context); }
        void launch() { assertEquals(Navigation.Result.MOVING,step()); now++; assertEquals(Navigation.Result.MOVING,step()); assertEquals(1,launches); }
        void land() { launch(); x=1.5;y=1;now++;assertEquals(Navigation.Result.MOVING,step());assertEquals(LoggingJumpController.Phase.LAND,controller.phase()); }
        public long tick() { return now; }
        public long dayTime() { return 1000; }
        public PlayerState player() { return new PlayerState(x,y,z,0,0,grounded,false,20,20,0,true,true); }
        public BlockData block(Pos p) { return new BlockData(p,"minecraft:air",Map.of()); }
        public boolean loaded(Pos p) { return true; }
        public boolean canStand(Pos p) { return p.equals(FROM)||p.equals(TO); }
        public double standingY(Pos p) { return p.equals(FROM)?heightOffset:p.equals(TO)?1+heightOffset:Double.NaN; }
        public boolean canTraverse(Pos from,Pos to) { return false; }
        public boolean canLoggingJump(LoggingJumpEdge edge,Profile ignored) { return logging && EDGE.equals(edge); }
        public boolean canStepUp(LoggingJumpEdge edge,Profile ignored) { return !logging && EDGE.equals(edge); }
        public List<BlockData> scan(Pos p,int horizontal,int vertical) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(); }
        public MenuData menu() { return new MenuData(0,0,List.of(),ItemData.EMPTY,false); }
        public boolean mayPlace(int index,ItemData item) { return false; }
        public boolean busy() { return false; }
        public long submit(Action action) { submissions++;throw new AssertionError("No gameplay action is permitted"); }
        public ActionOutcome outcome(long ticket) { throw new AssertionError(); }
        public void move(Movement input) { assertFalse(input.jump());assertFalse(input.sprint());assertFalse(input.sneak());movement=input;moves++; }
        public boolean moveLoggingJump(LoggingJumpEdge edge,boolean launch) { assertTrue(logging);if(launch)launches++;movement=null;return true; }
        public boolean moveStepUp(LoggingJumpEdge edge,boolean launch) { assertFalse(logging);if(launch)launches++;movement=null;return true; }
        public void stopMovement() { movement=null; }
        public void cancel() { stopMovement(); }
    }
}
