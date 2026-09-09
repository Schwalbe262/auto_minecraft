package dev.schwalbe.autovalley.core;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TomatoStockCacheTest {
    @Test void partialObservationsNeverPretendThatTheWholeWarehouseWasSurveyed() {
        Fixture f=new Fixture();assertTrue(f.cache.observeVerified(f.c,f.a,stock(0,64)));
        assertTrue(f.cache.reusable(f.c).isEmpty());
        assertTrue(f.cache.observeVerified(f.c,f.b,stock(1,64)));
        assertTrue(f.cache.reusable(f.c).isEmpty());
        f.survey();assertEquals(128,f.view().total());assertEquals(3456,f.view().capacity());
        assertArrayEquals(new int[]{64,64,0,0},f.view().qualityCounts());
    }
    @Test void exactNativeReplacementsAreIdempotentForDepositsAndWithdrawals() {
        Fixture f=new Fixture();f.survey();long epoch=f.cache.invalidationEpoch();
        f.cache.observeVerified(f.c,f.a,stock(0,32));f.cache.observeVerified(f.c,f.a,stock(0,32));
        assertEquals(96,f.view().total());assertEquals(epoch,f.cache.invalidationEpoch());
        f.cache.observeVerified(f.c,f.a,stock(0,96));f.cache.observeVerified(f.c,f.a,stock(0,96));
        assertEquals(160,f.view().total());assertEquals(epoch,f.cache.invalidationEpoch());
    }
    @Test void expiryIsThreeGameDaysAndDoesNotRequestMovementOrInventoryReads() {
        Fixture f=new Fixture();f.survey();f.day=12;f.tick=1000;
        assertTrue(f.cache.reusable(f.c).isPresent());f.day=13;f.tick=1200;
        assertTrue(f.cache.reusable(f.c).isEmpty());
        f.profile.tomatoStockRefreshDays=4;assertTrue(f.cache.reusable(f.c).isPresent());
        f.day=14;assertTrue(f.cache.reusable(f.c).isEmpty());
    }
    @Test void TouchingOneBarrelDoesNotRenewPeriodicWholeWarehouseAge() {
        Fixture f=new Fixture();f.survey();f.day=12;f.tick=1000;
        f.cache.observeVerified(f.c,f.a,stock(2,128));
        assertEquals(10,f.view().fullSurveyDay());assertEquals(100,f.view().fullSurveyTick());
        f.day=13;assertTrue(f.cache.reusable(f.c).isEmpty());
        f.survey();assertEquals(13,f.view().fullSurveyDay());assertEquals(1000,f.view().fullSurveyTick());
    }
    @Test void harmlessPauseNewContextAndUnrelatedRegistrationsKeepTheSameCache() {
        Fixture f=new Fixture();f.survey();
        f.session.oneShotFeature=Feature.WINE;f.profile.enabled.put(Feature.LOGGING,false);
        f.profile.pois.add(new Poi(new Pos(90,64,0),PoiKind.WAYPOINT,"unrelated",null));
        f.profile.pois.set(0,new Poi(f.a,PoiKind.TOMATO_CHEST,"renamed",17));
        assertEquals(128,f.cache.reusable(new Context(f,f,f,f.profile,f.session)).orElseThrow().total());
        f.profile.tomatoStorageLimitPercent=80;assertEquals(128,f.view().total());
    }
    @Test void ManualDirtyPosRevokesCompleteEvidenceUntilThatExactBarrelIsVerified() {
        Fixture f=new Fixture();f.survey();long epoch=f.view().epoch();
        f.cache.invalidate(f.a);assertTrue(f.cache.invalidationEpoch()>epoch);assertTrue(f.cache.reusable(f.c).isEmpty());
        f.cache.observeVerified(f.c,f.b,stock(3,2));assertTrue(f.cache.reusable(f.c).isEmpty());
        f.cache.observeVerified(f.c,f.a,stock(0,5));assertEquals(7,f.view().total());
        assertEquals(10,f.view().fullSurveyDay());
    }
    @Test void unrelatedDirtyDoesNotRevokeStockAndRepeatedDirtyIsIdempotent() {
        Fixture f=new Fixture();f.survey();long epoch=f.view().epoch();
        f.cache.invalidate(new Pos(99,64,0));assertEquals(epoch,f.view().epoch());
        f.cache.invalidate(f.a);long dirty=f.cache.invalidationEpoch();f.cache.invalidate(f.a);assertEquals(dirty,f.cache.invalidationEpoch());
    }
    @Test void allDirtyNeedsAllBarrelsAgainAndDoesNotBecomeFreshByWaiting() {
        Fixture f=new Fixture();f.survey();f.cache.invalidateAll();
        f.cache.observeVerified(f.c,f.a,stock(0,1));assertTrue(f.cache.reusable(f.c).isEmpty());
        f.tick++;assertTrue(f.cache.reusable(f.c).isEmpty());
        f.cache.observeVerified(f.c,f.b,stock(0,1));assertEquals(2,f.view().total());
    }
    @Test void interruptedSurveyCannotCommitAcrossManualChangesOrReplayItsToken() {
        Fixture f=new Fixture();TomatoStockCache.SurveyToken token=f.cache.beginSurvey(f.c);
        f.cache.invalidate(f.a);assertFalse(f.cache.completeSurvey(f.c,token,f.snapshots()));assertTrue(f.cache.reusable(f.c).isEmpty());
        TomatoStockCache.SurveyToken next=f.cache.beginSurvey(f.c);
        assertTrue(f.cache.completeSurvey(f.c,next,f.snapshots()));
        assertFalse(f.cache.completeSurvey(f.c,next,f.snapshots()));
    }
    @Test void normalAckUpdatesDuringFullSurveyDoNotInvalidateItsToken() {
        Fixture f=new Fixture();var token=f.cache.beginSurvey(f.c);
        f.cache.observeVerified(f.c,f.a,stock(0,64));f.cache.observeVerified(f.c,f.b,stock(1,64));
        assertTrue(f.cache.completeSurvey(f.c,token,f.snapshots()));assertEquals(128,f.view().total());
    }
    @Test void missingExtraOrDuplicateRegisteredBarrelsCannotPublishACompleteView() {
        Fixture f=new Fixture();var token=f.cache.beginSurvey(f.c);
        assertFalse(f.cache.completeSurvey(f.c,token,Map.of(f.a,stock(0,64))));
        Map<Pos,List<ItemData>> extra=new LinkedHashMap<>(f.snapshots());extra.put(new Pos(2,64,0),stock(1,1));
        assertFalse(f.cache.completeSurvey(f.c,f.cache.beginSurvey(f.c),extra));
        f.profile.pois.add(new Poi(f.a,PoiKind.TOMATO_CHEST,"duplicate",null));
        assertFalse(f.cache.completeSurvey(f.c,f.cache.beginSurvey(f.c),f.snapshots()));assertTrue(f.cache.reusable(f.c).isEmpty());
    }
    @Test void registrationChangesRevokeOldSurveyAndOldViews() {
        Fixture f=new Fixture();f.survey();var token=f.cache.beginSurvey(f.c);
        f.profile.pois.remove(1);assertTrue(f.cache.reusable(f.c).isEmpty());
        assertFalse(f.cache.completeSurvey(f.c,token,Map.of(f.a,stock(0,64))));
        f.profile.pois.add(new Poi(f.b,PoiKind.TOMATO_CHEST,"restored",null));assertTrue(f.cache.reusable(f.c).isEmpty());
    }
    @Test void reconnectProfileReplacementAndWorldReplacementNeverInheritStock() {
        for(String replacement:List.of("session","profile","world")) {
            Fixture f=new Fixture();f.survey();Profile p=f.profile;SessionState s=f.session;WorldAccess world=f;
            if(replacement.equals("session"))s=new SessionState();
            if(replacement.equals("profile")){p=new Profile();p.pois.addAll(f.profile.pois);}
            if(replacement.equals("world"))world=new Fixture();
            assertTrue(f.cache.reusable(new Context(world,f,f,p,s)).isEmpty(),replacement);
        }
    }
    @Test void dayOrTickRollbackClearsEvidenceRatherThanExtendingIt() {
        for(boolean dayRollback:List.of(false,true)) {
            Fixture f=new Fixture();f.survey();if(dayRollback)f.day--;else f.tick--;
            assertTrue(f.cache.reusable(f.c).isEmpty());f.day=10;f.tick=100;assertTrue(f.cache.reusable(f.c).isEmpty());
        }
    }
    @Test void unloadedOrChangedGeometryCannotUseAClosedInventoryShortcut() {
        Fixture f=new Fixture();f.survey();f.unloaded.add(f.a);assertEquals(128,f.view().total());
        f.unloaded.clear();assertEquals(128,f.view().total());
        f.kind="minecraft:chest";f.chestType="left";assertTrue(f.cache.reusable(f.c).isEmpty());
        assertFalse(f.cache.observeVerified(f.c,f.a,stock(0,64)));f.chestType="single";
        assertTrue(f.cache.reusable(f.c).isEmpty());
    }
    @Test void malformedWrongItemUnknownQualityAndNon27SlotSnapshotsRevokeOnlyTheirBarrel() {
        for(String changed:List.of("short","double","null","other","negative","oversize","unknown quality")) {
            Fixture f=new Fixture();f.survey();List<ItemData> malformed=new ArrayList<>(stock(0,64));
            switch(changed) {
                case "short"->malformed.remove(0);
                case "double"->malformed.addAll(stock(0,64));
                case "null"->malformed.set(0,null);
                case "other"->malformed.set(0,new ItemData("minecraft:diamond",1,0,null,false,100));
                case "negative"->malformed.set(0,tomato(0,-1));
                case "oversize"->malformed.set(0,tomato(0,65));
                case "unknown quality"->malformed.set(0,tomato(4,1));
            }
            assertFalse(f.cache.observeVerified(f.c,f.a,malformed),changed);assertTrue(f.cache.reusable(f.c).isEmpty(),changed);
            f.cache.observeVerified(f.c,f.a,stock(0,1));assertEquals(65,f.view().total(),changed);
        }
    }
    @Test void replacingBarrelsWithSupportedSingleChestsStillRequiresFreshNativeSnapshots() {
        Fixture f=new Fixture();f.survey();long epoch=f.cache.invalidationEpoch();
        f.kind="minecraft:chest";assertTrue(f.cache.reusable(f.c).isEmpty());assertTrue(f.cache.invalidationEpoch()>epoch);
        f.kind="minecraft:barrel";assertTrue(f.cache.reusable(f.c).isEmpty(),"restoring the ID is not a contents ACK");
        f.survey();f.kind="minecraft:chest";assertTrue(f.cache.reusable(f.c).isEmpty());
        f.cache.observeVerified(f.c,f.a,stock(0,1));f.cache.observeVerified(f.c,f.b,stock(0,1));assertEquals(2,f.view().total());
    }
    @Test void snapshotAndViewCopiesCannotMutateTheStoredCounts() {
        Fixture f=new Fixture();List<ItemData> a=new ArrayList<>(stock(0,64));Map<Pos,List<ItemData>> map=new LinkedHashMap<>(f.snapshots());map.put(f.a,a);
        assertTrue(f.cache.completeSurvey(f.c,f.cache.beginSurvey(f.c),map));a.set(0,tomato(0,1));map.clear();
        TomatoStockCache.View view=f.view();assertEquals(128,view.total());
        assertThrows(UnsupportedOperationException.class,()->view.contents().clear());
        assertThrows(UnsupportedOperationException.class,()->view.contents().get(f.a).set(0,ItemData.EMPTY));
        view.qualityCounts()[0]=999;assertEquals(64,view.qualityCounts()[0]);
        f.cache.observeVerified(f.c,f.a,stock(0,1));assertEquals(128,view.total());assertEquals(65,f.view().total());
    }
    @Test void zeroNegativeOrTooLongRefreshPolicyCannotLendFreshEvidence() {
        Fixture f=new Fixture();f.survey();for(int days:new int[]{-1,0,29,Integer.MAX_VALUE}) {
            f.profile.tomatoStockRefreshDays=days;assertTrue(f.cache.reusable(f.c).isEmpty());
        }
        for(int days:new int[]{1,3,28}){f.profile.tomatoStockRefreshDays=days;assertTrue(f.cache.reusable(f.c).isPresent());}
    }
    @Test void surveyTokensCannotBeImportedBetweenCacheObjects() {
        Fixture f=new Fixture(),other=new Fixture();
        assertFalse(other.cache.completeSurvey(other.c,f.cache.beginSurvey(f.c),other.snapshots()));
    }

    static ItemData tomato(int quality,int count){return new ItemData(ItemData.TOMATO,count,quality,null,false,100);}
    static List<ItemData> stock(int quality,int count) {
        List<ItemData> result=new ArrayList<>(Collections.nCopies(27,ItemData.EMPTY));
        for(int slot=0;count>0;slot++){int size=Math.min(64,count);result.set(slot,tomato(quality,size));count-=size;}
        return result;
    }
    static final class Fixture implements WorldAccess,ActionPort,Navigation {
        final Profile profile=new Profile();final SessionState session=new SessionState();final TomatoStockCache cache=session.tomatoStockCache;
        final Pos a=new Pos(0,64,0),b=new Pos(1,64,0);final Context c=new Context(this,this,this,profile,session);
        final Set<Pos> unloaded=new HashSet<>();long day=10,tick=100;String kind="minecraft:barrel",chestType="single";
        Fixture(){profile.pois.add(new Poi(a,PoiKind.TOMATO_CHEST,"left",null));profile.pois.add(new Poi(b,PoiKind.TOMATO_CHEST,"right",null));}
        Map<Pos,List<ItemData>> snapshots(){return Map.of(a,stock(0,64),b,stock(1,64));}
        void survey(){assertTrue(cache.completeSurvey(c,cache.beginSurvey(c),snapshots()));}
        TomatoStockCache.View view(){return cache.reusable(c).orElseThrow();}
        public long tick(){return tick;}public long dayTime(){return day*24000;}
        public PlayerState player(){throw new AssertionError("Cache cannot request player movement");}
        public boolean loaded(Pos pos){return !unloaded.contains(pos);}
        public BlockData block(Pos pos){assertTrue(loaded(pos));return new BlockData(pos,kind,Map.of("type",chestType));}
        public boolean canStand(Pos pos){throw new AssertionError();}public boolean canTraverse(Pos a,Pos b){throw new AssertionError();}
        public List<BlockData> scan(Pos center,int radius,int height){throw new AssertionError("No terrain scan");}
        public List<ItemSlot> inventory(){throw new AssertionError("No unverified inventory read");}
        public MenuData menu(){throw new AssertionError("No closed container read");}public boolean mayPlace(int slot,ItemData item){throw new AssertionError();}
        public boolean busy(){throw new AssertionError();}public long submit(Action action){throw new AssertionError("No action");}
        public ActionOutcome outcome(long ticket){throw new AssertionError();}public void move(Movement movement){throw new AssertionError();}
        public void stopMovement(){throw new AssertionError();}public void cancel(){throw new AssertionError();}
        public Result moveTo(Pos target,double reach,Context context){throw new AssertionError("No date-triggered trip");}public void reset(){throw new AssertionError();}
    }
}
