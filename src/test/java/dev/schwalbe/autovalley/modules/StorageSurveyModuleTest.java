package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageSurveyModuleTest {
    private static ItemData tomato(int grade) { return new ItemData(ItemData.TOMATO,12,grade,null,false,0); }
    private static ItemData wine(int cohort) { return new ItemData(ItemData.WINE,5,1,cohort,false,0); }

    @Test void continuousModeNeverOpensAnythingEvenIfEnabled() {
        Fixture f=new Fixture(); f.add(tomato(1)); f.session.oneShotFeature=null;
        assertEquals(WorkResult.State.IDLE,f.step().state()); assertTrue(f.actions.isEmpty());
        assertEquals("not_started",f.session.storageSurveyStatus);
    }
    @Test void classifiesOnlyPureCandidatesAndVisitsEveryRegisteredTargetWithoutMovingItems() {
        Fixture f=new Fixture();
        Pos tomato=f.add(tomato(2)),wine=f.add(wine(8)),empty=f.add(),mixed=f.add(tomato(0),tomato(1));
        List<ItemSlot> before=f.inventory();
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(PoiKind.TOMATO_CHEST,f.poi(tomato).kind()); assertNull(f.poi(tomato).classifier());
        assertEquals(PoiKind.WINE_CHEST,f.poi(wine).kind()); assertEquals(8,f.poi(wine).classifier());
        assertEquals(PoiKind.STORAGE_CANDIDATE,f.poi(empty).kind()); assertEquals(PoiKind.TOMATO_CHEST,f.poi(mixed).kind());
        assertNull(f.poi(mixed).classifier());
        assertEquals(3,f.checkpoints); assertEquals(4,f.session.storageSurveyObservations.size());
        assertTrue(f.session.storageSurveyComplete); assertFalse(f.menu.container()); assertEquals(before,f.inventory());
        assertEquals(8,f.actions.size());
        for (Action action:f.actions) assertTrue(action instanceof Action.CloseContainer
            || action instanceof Action.UseBlock use && use.purpose()==Action.Use.OPEN_CONTAINER);
    }
    @Test void existingClassificationsAreObservedButNeverOverwritten() {
        Fixture f=new Fixture(); Pos pos=f.add(tomato(3));
        f.profile.pois.set(0,new Poi(pos,PoiKind.TOMATO_CHEST,"Existing",1));
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertEquals(1,f.poi(pos).classifier());
        assertNull(f.session.storageSurveyObservations.get(pos).classifier()); assertEquals(0,f.checkpoints);
    }
    @Test void legacyDesiredLayoutCannotClassifyAnEmptyCandidateOrConstrainTomatoGrades() {
        Fixture f=new Fixture(); Pos empty=f.add(),legacy=f.add(tomato(3)),mixed=f.add(tomato(0),tomato(1));
        for (Pos pos:List.of(empty,legacy,mixed)) f.profile.tomatoStorageTargets.put(Profile.positionKey(pos),2);
        assertEquals(WorkResult.State.IDLE,f.finish().state());
        assertEquals(PoiKind.STORAGE_CANDIDATE,f.poi(empty).kind()); assertNull(f.poi(empty).classifier());
        assertEquals(StorageSurveyObservation.Status.EMPTY,f.session.storageSurveyObservations.get(empty).status(),"evidence must remain empty, not invented tomatoes");
        assertEquals(PoiKind.TOMATO_CHEST,f.poi(legacy).kind()); assertNull(f.poi(legacy).classifier());
        assertEquals(PoiKind.TOMATO_CHEST,f.poi(mixed).kind()); assertNull(f.poi(mixed).classifier());
        assertEquals(2,f.checkpoints); assertEquals(List.of(tomato(3)),f.stock.get(legacy));
        assertEquals(6,f.actions.size()); assertTrue(f.session.storageSurveyComplete);
    }
    @Test void commodityClassificationSaveFailureRollsBackWithoutAnyInventoryAction() {
        Fixture f=new Fixture(); Pos pos=f.add(tomato(0),tomato(3)); Poi original=f.poi(pos);
        f.profile.tomatoStorageTargets.put(Profile.positionKey(pos),2); f.failSave=true;
        assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertEquals(original,f.poi(pos));
        assertEquals(2,f.profile.tomatoStorageTargets.get(Profile.positionKey(pos)));
        assertEquals(1,f.actions.size()); assertEquals(List.of(tomato(0),tomato(3)),f.stock.get(pos));
        assertEquals(StorageSurveyObservation.Status.TOMATO,f.session.storageSurveyObservations.get(pos).status());
    }
    @Test void unreachableTargetIsExplicitlyIncompleteAndKeepsEarlierEvidence() {
        Fixture f=new Fixture(); f.add(); Pos blocked=f.add(); f.blocked.add(blocked);
        assertEquals(WorkResult.State.BLOCKED,f.finish().state());
        assertFalse(f.session.storageSurveyComplete); assertEquals(blocked,f.session.storageSurveyBlockedAt);
        assertEquals(1,f.session.storageSurveyObservations.size()); assertEquals(2,f.actions.size());
    }
    @Test void saveFailureRestoresOriginalRegistrationAndNeverTransfers() {
        Fixture f=new Fixture(); Pos pos=f.add(tomato(1)); Poi original=f.poi(pos); f.failSave=true;
        assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertEquals(original,f.poi(pos));
        assertFalse(f.session.storageSurveyComplete); assertEquals(1,f.actions.size());
        assertEquals(Action.Use.OPEN_CONTAINER,((Action.UseBlock)f.actions.get(0)).purpose());
    }
    @Test void failedOpenAckCannotCreateEvidenceOrClassify() {
        Fixture f=new Fixture(); Pos pos=f.add(tomato(1)); f.failAck=true;
        assertEquals(WorkResult.State.BLOCKED,f.finish().state());
        assertTrue(f.session.storageSurveyObservations.isEmpty()); assertEquals(PoiKind.STORAGE_CANDIDATE,f.poi(pos).kind());
    }
    @Test void bankOrUnloadedBlockIsNeverOpened() {
        for (boolean unloaded:List.of(false,true)) {
            Fixture f=new Fixture(); Pos pos=f.add();
            if (unloaded) f.unloaded.add(pos); else f.blockId="numismatics:bank";
            assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertTrue(f.actions.isEmpty());
        }
    }
    @Test void otherRegisteredMachinesAreNeverSurveyTargets() {
        Fixture f=new Fixture(); f.profile.pois.add(new Poi(new Pos(1,64,0),PoiKind.PRESERVES_JAR,"Jar",null));
        f.profile.pois.add(new Poi(new Pos(2,64,0),PoiKind.SHIPPING_BIN,"Ship",null));
        assertEquals(WorkResult.State.IDLE,f.finish().state()); assertTrue(f.actions.isEmpty());
        assertEquals(0,f.session.storageSurveyTotal);
    }
    @Test void incompleteOrDuplicateStorageSlotsFailClosed() {
        for (boolean duplicate:List.of(false,true)) {
            Fixture f=new Fixture(); f.add(tomato(1)); f.badShape=!duplicate; f.duplicateSlot=duplicate;
            assertEquals(WorkResult.State.BLOCKED,f.finish().state()); assertEquals(0,f.checkpoints);
            assertTrue(f.session.storageSurveyObservations.isEmpty());
        }
    }

    private static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile(); final SessionState session=new SessionState();
        final StorageSurveyModule module=new StorageSurveyModule();
        final List<Action> actions=new ArrayList<>(); final Map<Pos,List<ItemData>> stock=new HashMap<>();
        final Set<Pos> blocked=new HashSet<>(),unloaded=new HashSet<>();
        MenuData menu=new MenuData(0,0,List.of(),ItemData.EMPTY,false);
        String blockId="minecraft:barrel"; long tick; int checkpoints,nextMenu=1;
        boolean failSave,failAck,badShape,duplicateSlot;
        final Context context=new Context(this,this,this,profile,session,() -> {
            checkpoints++; if (failSave) throw new IllegalStateException("save failed");
        });
        Fixture() { session.oneShotFeature=Feature.STORAGE_SURVEY; }
        Pos add(ItemData... items) {
            Pos pos=new Pos(profile.pois.size()*2,64,0);
            profile.pois.add(new Poi(pos,PoiKind.STORAGE_CANDIDATE,"Warehouse",null));
            stock.put(pos,List.of(items)); return pos;
        }
        Poi poi(Pos pos) { return profile.pois.stream().filter(p -> p.pos().equals(pos)).findFirst().orElseThrow(); }
        WorkResult step() { tick++; return module.tick(context); }
        WorkResult finish() {
            for(int i=0;i<100;i++) { WorkResult result=step(); if(result.state()!=WorkResult.State.BUSY) return result; }
            throw new AssertionError("Survey failed to finish");
        }
        public long tick() { return tick; } public long dayTime() { return 0; }
        public PlayerState player() { return new PlayerState(0,64,0,0,0,true,false,20,20,0,true,true); }
        public BlockData block(Pos pos) { return new BlockData(pos,blockId,Map.of("container","true")); }
        public boolean loaded(Pos pos) { return !unloaded.contains(pos); }
        public boolean canStand(Pos pos) { return true; }
        public boolean canTraverse(Pos from,Pos to) { return true; }
        public List<BlockData> scan(Pos center,int horizontal,int vertical) { return List.of(); }
        public List<ItemSlot> inventory() { return List.of(new ItemSlot(0,0,true,tomato(0))); }
        public MenuData menu() { return menu; } public boolean mayPlace(int slot,ItemData item) { return true; }
        public boolean busy() { return false; }
        public long submit(Action action) {
            actions.add(action);
            if (action instanceof Action.UseBlock use) {
                assertEquals(Action.Use.OPEN_CONTAINER,use.purpose());
                List<ItemSlot> slots=new ArrayList<>(); List<ItemData> contents=stock.get(use.pos());
                for (int i=0;i<(badShape?26:27);i++) slots.add(new ItemSlot(duplicateSlot&&i==26?0:i,-1,false,
                    i<contents.size()?contents.get(i):ItemData.EMPTY));
                slots.add(new ItemSlot(27,0,true,tomato(0)));
                menu=new MenuData(nextMenu++,0,slots,ItemData.EMPTY,true);
            } else if(action instanceof Action.CloseContainer) menu=new MenuData(0,0,List.of(),ItemData.EMPTY,false);
            else throw new AssertionError("Survey attempted an inventory or machine action: "+action);
            return actions.size();
        }
        public ActionOutcome outcome(long ticket) { return new ActionOutcome(failAck?ActionOutcome.State.FAILED:ActionOutcome.State.SUCCEEDED,"test ACK"); }
        public void move(Movement movement) { } public void stopMovement() { } public void cancel() { }
        public Result moveTo(Pos pos,double reach,Context c) { assertEquals(2.5,reach); return blocked.contains(pos)?Result.BLOCKED:Result.ARRIVED; }
        public void reset() { }
    }
}
