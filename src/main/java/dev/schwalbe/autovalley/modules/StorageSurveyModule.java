package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import java.util.*;

/** Explicit one-shot ordinary-warehouse inspection. Its only actions are OPEN and CLOSE. */
public final class StorageSurveyModule implements AutomationModule {
    private enum Stage { START, APPROACH, INSPECT, CLOSE }
    private Stage stage=Stage.START;
    private List<Poi> targets=List.of();
    private int index,menuId=-1;
    private long ticket=-1;
    private boolean opening;
    private String openedBlockId;

    @Override public Feature feature() { return Feature.STORAGE_SURVEY; }
    @Override public int priority() { return 5; }
    public static boolean targetKind(PoiKind kind) {
        return kind==PoiKind.STORAGE_CANDIDATE || kind==PoiKind.TOMATO_CHEST || kind==PoiKind.WINE_CHEST;
    }

    @Override public WorkResult tick(Context c) {
        if (c.session().oneShotFeature!=Feature.STORAGE_SURVEY) return WorkResult.idle();
        if (stage==Stage.START) {
            targets=StorageVisitOrder.order(c.profile().pois.stream().filter(p -> targetKind(p.kind())).toList(),c.world().player());
            c.session().storageSurveyObservations.clear(); c.session().storageSurveyTotal=targets.size();
            c.session().storageSurveyComplete=false; c.session().storageSurveyBlockedAt=null;
            c.session().storageSurveyStatus="Inspecting registered warehouses without moving items";
            if (targets.size()>4096 || targets.stream().map(Poi::pos).distinct().count()!=targets.size())
                return fail(c,"Invalid or duplicate warehouse registrations");
            index=0; stage=Stage.APPROACH;
            if (targets.isEmpty()) return complete(c);
        }
        if (ticket>=0) {
            ActionOutcome result=c.actions().outcome(ticket);
            if (!result.done()) return WorkResult.busy("Waiting for warehouse inspection acknowledgement");
            ticket=-1;
            if (!result.success()) return fail(c,"Warehouse acknowledgement failed: "+result.message());
            if (opening) {
                MenuData menu=c.world().menu();
                if (menu==null || !menu.container() || !menu.carried().empty()) return fail(c,"Warehouse did not open with an empty cursor");
                menuId=menu.id(); stage=Stage.INSPECT;
            } else {
                MenuData menu=c.world().menu();
                if (menu==null || menu.container() || !menu.carried().empty()) return fail(c,"Warehouse did not close cleanly");
                menuId=-1; index++; stage=Stage.APPROACH;
                if (index>=targets.size()) return complete(c);
            }
        }
        Poi target=targets.get(index);
        switch (stage) {
            case APPROACH -> {
                if (!c.profile().pois.contains(target)) return fail(c,"Warehouse registration changed during inspection");
                if (!c.world().loaded(target.pos())) return navigationResult(c,ModuleSupport.observe(c,target.pos(),8,"창고 조사 위치 확인"));
                BlockData block=c.world().block(target.pos());
                if (!StorageSurveyRules.ordinaryStorage(block)) return fail(c,"Only ordinary barrels and chests may be surveyed");
                MenuData menu=c.world().menu();
                if (menu==null || menu.container() || !menu.carried().empty()) return fail(c,"Close the current screen and clear the cursor first");
                Navigation.Result navigation=c.navigation().moveTo(target.pos(),2.5,c);
                if (navigation==Navigation.Result.BLOCKED) return navigationResult(c,ModuleSupport.navigationResult(c,"Registered warehouse cannot be reached"));
                if (navigation==Navigation.Result.ARRIVED) {
                    openedBlockId=block.id(); opening=true;
                    ticket=c.actions().submit(new Action.UseBlock(target.pos(),Action.Use.OPEN_CONTAINER));
                }
            }
            case INSPECT -> {
                MenuData menu=c.world().menu();
                if (menu==null || !menu.container() || menu.id()!=menuId || !menu.carried().empty()) return fail(c,"Warehouse menu or cursor changed");
                if (!c.world().loaded(target.pos()) || !c.profile().pois.contains(target)) return fail(c,"Warehouse changed or unloaded during inspection");
                BlockData block=c.world().block(target.pos());
                if (!StorageSurveyRules.ordinaryStorage(block) || !Objects.equals(openedBlockId,block.id())) return fail(c,"Warehouse block identity changed");
                long slots=menu.slots().stream().filter(s -> !s.player()).count();
                if (slots!=27 && slots!=54) return fail(c,"Ordinary warehouse did not expose its complete 27 or 54 slots");
                StorageSurveyObservation observation;
                try { observation=StorageSurveyRules.inspect(target.pos(),c.world().tick(),menu.slots()); }
                catch (RuntimeException malformed) { return fail(c,"Warehouse contents could not be safely interpreted"); }
                c.session().storageSurveyObservations.put(target.pos(),observation);
                PoiKind classifiedKind=observation.classifiedKind();
                Integer classifier=observation.classifier();
                // Old desired-grade layouts are ignored. Empty storage has no
                // item evidence; only explicit user registration can designate it.
                if (target.kind()==PoiKind.STORAGE_CANDIDATE && classifiedKind!=null) {
                    int position=c.profile().pois.indexOf(target);
                    if (position<0) return fail(c,"Warehouse registration disappeared before classification");
                    Poi classified=new Poi(target.pos(),classifiedKind,target.label(),classifier);
                    c.profile().pois.set(position,classified);
                    try { c.checkpoint().run(); }
                    catch (RuntimeException failure) {
                        c.profile().pois.set(position,target);
                        return fail(c,"Could not save warehouse classification; original registration restored");
                    }
                }
                c.session().storageSurveyStatus="Inspected "+c.session().storageSurveyObservations.size()+" / "+targets.size()+" warehouses";
                stage=Stage.CLOSE; opening=false;
                ticket=c.actions().submit(new Action.CloseContainer(menuId));
            }
            case CLOSE -> { return fail(c,"Warehouse close acknowledgement was lost"); }
            case START -> throw new IllegalStateException();
        }
        return WorkResult.busy(c.session().storageSurveyStatus);
    }

    private WorkResult complete(Context c) {
        c.actions().stopMovement();
        c.session().storageSurveyComplete=true;
        c.session().storageSurveyBlockedAt=null;
        c.session().storageSurveyStatus="Warehouse survey complete: "+c.session().storageSurveyObservations.size()+" / "+targets.size()+" inspected; no items transferred";
        return WorkResult.idle();
    }
    private WorkResult fail(Context c,String reason) {
        c.actions().stopMovement();
        Pos blocked=index<targets.size() ? targets.get(index).pos() : null;
        c.session().storageSurveyComplete=false; c.session().storageSurveyBlockedAt=blocked;
        c.session().storageSurveyStatus=reason+(blocked==null ? "" : " at "+blocked.x()+", "+blocked.y()+", "+blocked.z());
        return WorkResult.blocked(c.session().storageSurveyStatus);
    }
    private WorkResult navigationResult(Context c,WorkResult result) {
        if (result.state()==WorkResult.State.BUSY) return result;
        WorkResult reported=fail(c,result.message());
        return new WorkResult(result.state(),reported.message());
    }
    @Override public void reset() {
        stage=Stage.START; targets=List.of(); index=0; menuId=-1; ticket=-1; opening=false; openedBlockId=null;
        // Session observations survive completion/cancellation for the user's local diagnostics.
    }
}
