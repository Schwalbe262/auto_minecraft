package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** A bounded two-child feature pass, including both legacy and additional wine lines in one-shots. */
public final class WineRoutineModule implements AutomationModule {
    private final Feature scope;
    private final List<AutomationModule> children;
    private final List<WorkResult> results=new ArrayList<>();
    private int index;
    private WorkResult terminal;
    private long closeTicket=-1;
    private String boundaryFailure;
    private boolean yieldAfterSweep;
    public WineRoutineModule(Feature scope) {
        this(scope,scope==Feature.WINE_STORAGE ? List.of(new WineStorageModule(),new WineLineStorageModule())
            : List.of(new WineSurplusShippingModule(),new WineLineSurplusShippingModule()));
    }
    public WineRoutineModule(Feature scope,List<AutomationModule> children) {
        if (scope!=Feature.WINE_STORAGE && scope!=Feature.WINE_SURPLUS_SHIPPING || children==null || children.size()!=2
            || children.stream().anyMatch(child->child==null || child.feature()!=scope))
            throw new IllegalArgumentException("Wine routine requires its two matching storage or sale children");
        this.scope=scope;this.children=List.copyOf(children);
    }
    @Override public Feature feature() { return scope; }
    @Override public int priority() { return scope==Feature.WINE_STORAGE ? 30 : 35; }
    @Override public WorkResult tick(Context c) {
        if (boundaryFailure!=null) return blocked(boundaryFailure);
        if (!c.session().allows(c.profile(),scope)) return blocked("현재 와인 작업의 실행 권한이 없습니다.");
        if (closeTicket>=0) {
            ActionOutcome outcome=c.actions().outcome(closeTicket);
            if (!outcome.done()) return WorkResult.busy(summary()+"메뉴 닫기 서버 확인 대기");
            closeTicket=-1;
            if (!outcome.success() || c.world().menu().container()) {
                boundaryFailure="메뉴 닫기가 확인되지 않아 다음 와인 작업을 시작하지 않았습니다.";
                return blocked(boundaryFailure);
            }
        }
        String fence=c.actions().pauseReason();
        if (fence!=null) return blocked("다음 와인 작업 미진행: "+fence);
        if (c.world().menu()==null || !c.world().menu().carried().empty())
            return blocked("커서 상태가 불확실하여 다음 와인 작업을 시작하지 않았습니다.");
        if (c.actions().busy()) return WorkResult.busy(summary()+"진행 중인 서버 응답을 기다립니다.");
        // The engine revisits higher priorities immediately when active work ends.
        // Yield once without starting another multi-tick empty sweep so production,
        // harvest and sleep below this wrapper can actually be scheduled.
        if (yieldAfterSweep) { yieldAfterSweep=false;return WorkResult.idle(); }
        if (terminal!=null) return finishChild(c);
        WorkResult result=children.get(index).tick(c);
        if (result.state()==WorkResult.State.BUSY) return result;
        terminal=result;
        // Recheck after the child: even a wrongly reported terminal result cannot
        // advance over a sent action, cursor ownership or the adapter's late fence.
        fence=c.actions().pauseReason();
        if (fence!=null) return blocked("다음 와인 작업 미진행: "+fence);
        if (c.actions().busy()) {
            boundaryFailure="완료 처리되지 않은 조작이 남아 다음 와인 작업을 시작하지 않았습니다.";
            return blocked(boundaryFailure);
        }
        if (c.world().menu()==null || !c.world().menu().carried().empty())
            return blocked("커서의 아이템을 보존하고 다음 와인 작업을 보류했습니다.");
        return finishChild(c);
    }
    private WorkResult finishChild(Context c) {
        if (c.world().menu().container()) {
            if (!c.actions().ownsContainer()) return blocked("소유하지 않은 메뉴가 열려 다음 와인 작업을 시작하지 않았습니다.");
            c.actions().stopMovement();
            closeTicket=c.actions().submit(new Action.CloseContainer(c.world().menu().id()));
            return WorkResult.busy(summary()+"현재 와인 메뉴 닫기");
        }
        results.add(terminal);
        // IDLE children retain deliberate sweep/yield state. Failed or deferred
        // work restarts only after every native action and owned menu has settled.
        if (terminal.state()!=WorkResult.State.IDLE) children.get(index).reset();
        terminal=null;c.actions().stopMovement();c.navigation().reset();
        if (++index<children.size()) return WorkResult.busy(summary()+"다음 와인 작업 확인");
        WorkResult.State state=WorkResult.State.IDLE;
        for (WorkResult result:results) if (rank(result.state())>rank(state)) state=result.state();
        if (state==WorkResult.State.RESOURCE_WAIT) state=WorkResult.State.BLOCKED;
        // Only the production feature may grant the engine's sleep-safe cooldown.
        // A storage/sale child wait uses the ordinary, non-sleep-safe retry path.
        if (state==WorkResult.State.COOLDOWN) state=WorkResult.State.DEFERRED;
        String message=summary();index=0;results.clear();yieldAfterSweep=state==WorkResult.State.IDLE;
        return new WorkResult(state,message);
    }
    private static int rank(WorkResult.State state) {
        return switch(state) { case BLOCKED,RESOURCE_WAIT -> 4; case DEFERRED -> 3; case COOLDOWN -> 2; case IDLE -> 0; case BUSY -> 1; };
    }
    private String summary() {
        List<String> messages=new ArrayList<>();
        for(int i=0;i<results.size();i++) if(results.get(i).state()!=WorkResult.State.IDLE)
            messages.add(label(i)+": "+results.get(i).message());
        if(terminal!=null && terminal.state()!=WorkResult.State.IDLE) messages.add(label(index)+": "+terminal.message());
        return messages.isEmpty()?"":String.join(" / ",messages)+" — ";
    }
    private static String label(int index) { return index==0?"토마토 와인":"추가 와인 라인"; }
    private WorkResult blocked(String message) { return WorkResult.blocked(summary()+message); }
    @Override public void reset() {
        for(AutomationModule child:children)child.reset();
        index=0;terminal=null;closeTicket=-1;boundaryFailure=null;yieldAfterSweep=false;results.clear();
    }
}
