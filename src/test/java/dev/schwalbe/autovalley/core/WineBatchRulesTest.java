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
    @Test void finishUsesOriginalCadenceAndSkipsElapsedBoundariesAfterCleanup() {
        Fixture f=new Fixture();f.open();f.day=332;WineBatchRules.confirmFeed(f.context,A);
        assertThrows(IllegalStateException.class,() -> WineBatchRules.finish(f.context));
        f.day=334;WineBatchRules.confirmFeed(f.context,B);
        assertTrue(f.profile.wineBatchSchedule.active());assertEquals(332,f.profile.wineBatchSchedule.nextDueDay());
        f.day=339;WineBatchRules.finish(f.context);
        assertEquals(new WineBatchSchedule(344,false,List.of(),334L),f.profile.wineBatchSchedule);
        assertEquals(338L,f.profile.nextEligibleDay.get(WineBatchRules.key(A)));
        assertEquals(340L,f.profile.nextEligibleDay.get(WineBatchRules.key(B)));
    }
    @Test void delayedFeedDoesNotMoveTheRackBoundaryAndFinishAlwaysSchedulesAFuturePass() {
        for (long finishDay:new long[]{332,333,337,338,339,350}) {
            Fixture f=new Fixture();f.open();f.day=finishDay;
            WineBatchRules.confirmFeed(f.context,A);WineBatchRules.confirmFeed(f.context,B);WineBatchRules.finish(f.context);
            long next=f.profile.wineBatchSchedule.nextDueDay();
            assertEquals(0,(next-332)%6);assertTrue(next>finishDay);assertTrue(next<=finishDay+6);
            assertEquals(finishDay,f.profile.wineBatchSchedule.latestFeedDay());
        }
    }
    @Test void feedDatesCannotMoveBackwardAndFailedFinishKeepsActiveDebt() {
        Fixture f=new Fixture();f.open();f.day=334;WineBatchRules.confirmFeed(f.context,A);f.day=333;WineBatchRules.confirmFeed(f.context,B);
        assertEquals(334L,f.profile.wineBatchSchedule.latestFeedDay());WineBatchSchedule old=f.profile.wineBatchSchedule;f.fail=true;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.finish(f.context));assertSame(old,f.profile.wineBatchSchedule);
        f.fail=false;WineBatchRules.finish(f.context);assertEquals(338,f.profile.wineBatchSchedule.nextDueDay());
    }
    @Test void skipMovesOnlyOnePendingMemberWithoutInventingAFeedOrChangingItsDeadline() {
        Fixture f=new Fixture();f.open();f.day=333;f.profile.nextEligibleDay.put(WineBatchRules.key(A),331L);
        Map<String,Long> deadlines=Map.copyOf(f.profile.nextEligibleDay);
        WineBatchRules.skip(f.context,A,"not_ready");
        assertEquals(List.of(B),f.profile.wineBatchSchedule.remaining());assertNull(f.profile.wineBatchSchedule.latestFeedDay());
        assertEquals(List.of(new WineBatchSchedule.SkippedMember(A,"not_ready",333)),f.profile.wineBatchSchedule.skipped());
        assertEquals(deadlines,f.profile.nextEligibleDay);assertEquals(2,f.saves);
        WineBatchSchedule after=f.profile.wineBatchSchedule;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.skip(f.context,A,"missing"));
        assertThrows(IllegalStateException.class,() -> WineBatchRules.confirmFeed(f.context,A));
        assertThrows(IllegalStateException.class,() -> WineBatchRules.finish(f.context));
        assertSame(after,f.profile.wineBatchSchedule);assertEquals(2,f.saves);
        WineBatchRules.confirmFeed(f.context,B);WineBatchRules.finish(f.context);
        assertEquals(338,f.profile.wineBatchSchedule.nextDueDay());assertEquals(333L,f.profile.wineBatchSchedule.latestFeedDay());
        assertEquals(after.skipped(),f.profile.wineBatchSchedule.skipped());
        assertEquals(331L,f.profile.nextEligibleDay.get(WineBatchRules.key(A)));
        assertEquals(339L,f.profile.nextEligibleDay.get(WineBatchRules.key(B)));
    }
    @Test void allSkippedPassCanFinishAndNextPassReconsidersEveryRegisteredMember() {
        Fixture f=new Fixture();f.open();WineBatchRules.skip(f.context,A,"missing");WineBatchRules.skip(f.context,B,"not_ready");
        assertTrue(f.profile.wineBatchSchedule.active());assertTrue(f.profile.wineBatchSchedule.remaining().isEmpty());
        assertNull(f.profile.wineBatchSchedule.latestFeedDay());
        WineBatchSchedule active=f.profile.wineBatchSchedule;
        Context restarted=new Context(f,null,null,f.profile,new SessionState(),() -> f.saves++);
        assertSame(active,WineBatchRules.ensure(restarted));WineBatchRules.finish(restarted);
        assertEquals(new WineBatchSchedule(338,false,List.of(),null,active.skipped()),f.profile.wineBatchSchedule);
        assertTrue(f.profile.nextEligibleDay.isEmpty());
        f.day=338;WineBatchRules.open(f.context,List.of(B,A));
        assertEquals(List.of(B,A),f.profile.wineBatchSchedule.remaining());assertTrue(f.profile.wineBatchSchedule.skipped().isEmpty());
        assertNull(f.profile.wineBatchSchedule.latestFeedDay());
    }
    @Test void skipRetainsOnlyActualFeedAuditAndRollsBackIfItsCheckpointFails() {
        Fixture f=new Fixture();f.open();WineBatchRules.confirmFeed(f.context,A);f.day=334;
        WineBatchSchedule old=f.profile.wineBatchSchedule;Map<String,Long> dates=Map.copyOf(f.profile.nextEligibleDay);f.fail=true;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.skip(f.context,B,"not_ready"));
        assertSame(old,f.profile.wineBatchSchedule);assertEquals(dates,f.profile.nextEligibleDay);
        f.fail=false;WineBatchRules.skip(f.context,B,"not_ready");
        assertEquals(332L,f.profile.wineBatchSchedule.latestFeedDay());assertEquals(dates,f.profile.nextEligibleDay);
        assertEquals(334,f.profile.wineBatchSchedule.skipped().get(0).day());
    }
    @Test void allSkippedFinishCheckpointFailureKeepsTheDurableActivePass() {
        Fixture f=new Fixture();f.open();WineBatchRules.skip(f.context,A,"missing");WineBatchRules.skip(f.context,B,"missing");
        WineBatchSchedule old=f.profile.wineBatchSchedule;f.fail=true;
        assertThrows(IllegalStateException.class,() -> WineBatchRules.finish(f.context));
        assertSame(old,f.profile.wineBatchSchedule);assertTrue(f.profile.nextEligibleDay.isEmpty());
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
    @Test void skippedRecordsRejectMalformedDuplicateOverlappingAndOversizedMembers() {
        WineBatchSchedule.SkippedMember skipped=new WineBatchSchedule.SkippedMember(A,"not_ready",332);
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule.SkippedMember(null,"missing",332));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule.SkippedMember(A,null,332));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule.SkippedMember(A," ",332));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule.SkippedMember(A,"x".repeat(161),332));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule.SkippedMember(A,"missing",-1));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(332,true,List.of(A),null,List.of(skipped)));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(332,true,List.of(),null,List.of(skipped,skipped)));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(332,true,List.of(B),null,Arrays.asList(skipped,null)));
        List<WineBatchSchedule.SkippedMember> many=new ArrayList<>();
        for(int i=0;i<4096;i++)many.add(new WineBatchSchedule.SkippedMember(new Pos(i,64,1),"missing",332));
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(332,true,List.of(A),null,many));
        many.add(skipped);
        assertThrows(IllegalArgumentException.class,() -> new WineBatchSchedule(332,true,List.of(),null,many));
        List<WineBatchSchedule.SkippedMember> mutable=new ArrayList<>(List.of(skipped));
        WineBatchSchedule schedule=new WineBatchSchedule(332,true,List.of(B),null,mutable);mutable.clear();
        assertEquals(List.of(skipped),schedule.skipped());assertThrows(UnsupportedOperationException.class,() -> schedule.skipped().clear());
        assertEquals(List.of(),new WineBatchSchedule(332,true,List.of(A),null,null).skipped());
    }
    @Test void invalidSkipReasonCannotRemovePendingWorkOrWriteACheckpoint() {
        Fixture f=new Fixture();f.open();WineBatchSchedule old=f.profile.wineBatchSchedule;int saves=f.saves;
        assertThrows(IllegalArgumentException.class,() -> WineBatchRules.skip(f.context,A," "));
        assertSame(old,f.profile.wineBatchSchedule);assertEquals(saves,f.saves);assertTrue(f.profile.nextEligibleDay.isEmpty());
    }
    @Test void completionOverflowCannotPublishInvalidSchedule() {
        Fixture f=new Fixture();WineBatchSchedule old=new WineBatchSchedule(Long.MAX_VALUE,true,List.of(),332L);f.profile.wineBatchSchedule=old;
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
