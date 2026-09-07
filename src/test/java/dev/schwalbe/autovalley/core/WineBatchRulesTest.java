package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WineBatchRulesTest {
    private static final Pos A=new Pos(1,64,0),B=new Pos(2,64,0);
    @Test void migratesMaximumRegisteredDeadlineInsteadOfFirstOrForeignEntry() {
        Fixture f=new Fixture();f.day=326;
        f.profile.nextEligibleDay.put(WineBatchRules.key(A),331L);
        f.profile.nextEligibleDay.put(WineBatchRules.key(B),326L+6);
        f.profile.nextEligibleDay.put(WineBatchRules.key(new Pos(99,64,0)),999L);
        f.profile.nextEligibleDay.put("preserves:"+Profile.positionKey(A),1000L);
        assertEquals(new WineBatchSchedule(332,false,List.of(),null),WineBatchRules.ensure(f.context));
        assertEquals(1,f.saves);assertEquals(4,f.profile.nextEligibleDay.size());
        WineBatchRules.ensure(f.context);assertEquals(1,f.saves);
    }
    @Test void absentRackDoesNotCreateOrPersistASchedule() {
        Fixture f=new Fixture();f.profile.pois.clear();assertNull(WineBatchRules.ensure(f.context));assertEquals(0,f.saves);
    }
    @Test void failedMigrationRestoresNullAndLeavesLegacyDeadlines() {
        Fixture f=new Fixture();f.fail=true;f.profile.nextEligibleDay.put(WineBatchRules.key(A),332L);
        assertThrows(IllegalStateException.class,() -> WineBatchRules.ensure(f.context));
        assertNull(f.profile.wineBatchSchedule);assertEquals(332L,f.profile.nextEligibleDay.get(WineBatchRules.key(A)));
    }
    @Test void openRequiresDueWholeRackAndDoesNotAcceptDuplicates() {
        Fixture f=new Fixture();f.profile.wineBatchSchedule=new WineBatchSchedule(332,false,List.of(),null);f.day=331;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.open(f.context,List.of(A,B)));
        f.day=332;
        assertThrows(IllegalArgumentException.class,() -> WineBatchRules.open(f.context,List.of(A)));
        assertThrows(IllegalArgumentException.class,() -> WineBatchRules.open(f.context,List.of(A,B,B)));
        assertFalse(f.profile.wineBatchSchedule.active());assertEquals(0,f.saves);
        WineBatchRules.open(f.context,List.of(B,A));assertTrue(f.profile.wineBatchSchedule.active());
        assertEquals(List.of(B,A),f.profile.wineBatchSchedule.remaining());assertNull(f.profile.wineBatchSchedule.latestFeedDay());
        assertThrows(IllegalStateException.class,() -> WineBatchRules.open(f.context,List.of(A,B)));
    }
    @Test void openSaveFailureRestoresOriginalBoundary() {
        Fixture f=new Fixture();f.day=332;WineBatchSchedule old=new WineBatchSchedule(332,false,List.of(),null);
        f.profile.wineBatchSchedule=old;f.fail=true;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.open(f.context,List.of(A,B)));
        assertSame(old,f.profile.wineBatchSchedule);
    }
    @Test void activeSnapshotSurvivesNewSessionAndRegistryOrderChanges() {
        Fixture f=new Fixture();f.open();WineBatchRules.confirmFeed(f.context,A);
        WineBatchSchedule active=f.profile.wineBatchSchedule;
        Collections.reverse(f.profile.pois);f.profile.nextEligibleDay.put(WineBatchRules.key(B),999L);
        Context restarted=new Context(f,null,null,f.profile,new SessionState(),() -> {throw new AssertionError("Do not recreate the active snapshot");});
        assertSame(active,WineBatchRules.ensure(restarted));assertEquals(List.of(B),active.remaining());
    }
    @Test void confirmedFeedUpdatesOnlyThatMemberAndRejectsDuplicateConfirmation() {
        Fixture f=new Fixture();f.open();f.day=333;WineBatchRules.confirmFeed(f.context,A);
        assertEquals(List.of(B),f.profile.wineBatchSchedule.remaining());assertEquals(333L,f.profile.wineBatchSchedule.latestFeedDay());
        assertEquals(339L,f.profile.nextEligibleDay.get(WineBatchRules.key(A)));assertFalse(f.profile.nextEligibleDay.containsKey(WineBatchRules.key(B)));
        WineBatchSchedule after=f.profile.wineBatchSchedule;int saves=f.saves;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.confirmFeed(f.context,A));
        assertSame(after,f.profile.wineBatchSchedule);assertEquals(saves,f.saves);
    }
    @Test void failedFeedCheckpointRestoresSnapshotAndBothExistingAndAbsentDeadline() {
        for (Long prior:Arrays.asList(null,320L)) {
            Fixture f=new Fixture();f.open();if(prior!=null)f.profile.nextEligibleDay.put(WineBatchRules.key(A),prior);
            WineBatchSchedule old=f.profile.wineBatchSchedule;f.fail=true;
            assertThrows(IllegalStateException.class,() -> WineBatchRules.confirmFeed(f.context,A));
            assertSame(old,f.profile.wineBatchSchedule);assertEquals(prior,f.profile.nextEligibleDay.get(WineBatchRules.key(A)));
            assertEquals(prior!=null,f.profile.nextEligibleDay.containsKey(WineBatchRules.key(A)));
        }
    }
    @Test void onlyFinalConfirmedFeedDaySetsNextBoundaryAfterCleanup() {
        Fixture f=new Fixture();f.open();f.day=332;WineBatchRules.confirmFeed(f.context,A);
        assertThrows(IllegalStateException.class,() -> WineBatchRules.finish(f.context));
        f.day=334;WineBatchRules.confirmFeed(f.context,B);
        assertTrue(f.profile.wineBatchSchedule.active());assertEquals(332,f.profile.wineBatchSchedule.nextDueDay());
        f.day=339;WineBatchRules.finish(f.context);
        assertEquals(new WineBatchSchedule(340,false,List.of(),334L),f.profile.wineBatchSchedule);
    }
    @Test void feedDatesCannotMoveBackwardAndFailedFinishKeepsActiveDebt() {
        Fixture f=new Fixture();f.open();f.day=334;WineBatchRules.confirmFeed(f.context,A);f.day=333;WineBatchRules.confirmFeed(f.context,B);
        assertEquals(334L,f.profile.wineBatchSchedule.latestFeedDay());WineBatchSchedule old=f.profile.wineBatchSchedule;f.fail=true;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.finish(f.context));assertSame(old,f.profile.wineBatchSchedule);
        f.fail=false;WineBatchRules.finish(f.context);assertEquals(340,f.profile.wineBatchSchedule.nextDueDay());
    }
    @Test void recordRejectsInvalidStatesAndDetachesRemainingList() {
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(-1,false,List.of(),null));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(0,true,List.of(),null));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(0,false,List.of(A),null));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(0,true,List.of(A,A),null));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(0,true,List.of(A),-1L));
        assertThrows(RuntimeException.class,() -> new WineBatchSchedule(0,true,Arrays.asList(A,null),null));
        List<Pos> mutable=new ArrayList<>(List.of(A,B));WineBatchSchedule schedule=new WineBatchSchedule(1,true,mutable,null);mutable.clear();
        assertEquals(List.of(A,B),schedule.remaining());assertThrows(UnsupportedOperationException.class,() -> schedule.remaining().clear());
    }
    @Test void completionOverflowCannotPublishInvalidSchedule() {
        Fixture f=new Fixture();WineBatchSchedule old=new WineBatchSchedule(1,true,List.of(),Long.MAX_VALUE);f.profile.wineBatchSchedule=old;
        assertThrows(ArithmeticException.class,() -> WineBatchRules.finish(f.context));assertSame(old,f.profile.wineBatchSchedule);assertEquals(0,f.saves);
    }
    private static final class Fixture implements WorldAccess {
        final Profile profile=new Profile();final SessionState session=new SessionState();long day=332;int saves;boolean fail;
        final Context context=new Context(this,null,null,profile,session,() -> {saves++;if(fail)throw new IllegalStateException("test checkpoint failure");});
        Fixture(){profile.pois.add(new Poi(A,PoiKind.WINE_KEG,"A",null));profile.pois.add(new Poi(B,PoiKind.WINE_KEG,"B",null));}
        void open(){profile.wineBatchSchedule=new WineBatchSchedule(332,false,List.of(),null);WineBatchRules.open(context,List.of(A,B));}
        public long tick(){return 0;}public long dayTime(){return day*24000;}public PlayerState player(){return null;}
        public BlockData block(Pos p){throw new AssertionError("Schedule must not inspect machines");}public boolean loaded(Pos p){return false;}
        public boolean canStand(Pos p){return false;}public boolean canTraverse(Pos a,Pos b){return false;}
        public List<BlockData> scan(Pos p,int a,int b){throw new AssertionError("No scan");}public List<ItemSlot> inventory(){return List.of();}
        public MenuData menu(){return null;}public boolean mayPlace(int slot,ItemData item){return false;}
    }
}
