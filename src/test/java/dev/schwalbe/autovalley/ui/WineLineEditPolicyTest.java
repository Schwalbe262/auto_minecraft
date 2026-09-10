package dev.schwalbe.autovalley.ui;

import com.google.gson.Gson;
import dev.schwalbe.autovalley.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WineLineEditPolicyTest {
    private static final Gson JSON=new Gson();
    private static final WineLineEditPolicy.Boundary SAFE=boundary(
        new boolean[]{false,false,true,true,true,true,true,true,true,false});

    @Test void aCleanPausedBoundaryAllowsLocalWineSettingsAndUnknownStateRejects() {
        assertNull(WineLineEditPolicy.rejection(SAFE));
        assertNotNull(WineLineEditPolicy.rejection(null));
    }

    @Test void everyUnsafeBoundaryExplainsTheBlockIncludingAutomationOnAndPendingOutputs() {
        List<String> reasons=List.of("일시정지","기록","현재 접속의 설정","저장 오류","긴급 정지",
            "게임에 접속","상자나 작업대","커서","서버 응답","기계 산출물");
        for(int index=0;index<reasons.size();index++) {
            boolean[] flags={false,false,true,true,true,true,true,true,true,false};
            flags[index]=!flags[index];
            String rejection=WineLineEditPolicy.rejection(boundary(flags));
            assertNotNull(rejection,"unsafe boundary "+index);
            assertTrue(rejection.contains(reasons.get(index)),rejection);
        }
    }

    @Test void pausedRetainedLoggingAndParkedLeaseAllowWineExpansionWithoutChangingLoggingOrSchedules() {
        Profile profile=retained();
        var before=JSON.toJsonTree(profile);
        var remaining=profile.loggingRemainingPlots;
        var replanting=profile.loggingReplantingPlots;
        var lease=profile.loggingHotbarLease;
        var active=profile.wineProductionSchedules.get("ancient");
        var snapshot=GroupExpansionRules.capture(profile);
        var target=GroupExpansionRules.wineLine(snapshot,"ancient");
        var addition=new GroupExpansionRules.Candidate(new BlockData(pos(11),"society:wine_keg",Map.of()),List.of(pos(11)));

        assertNull(WineLineEditPolicy.rejection(SAFE));
        var changes=GroupExpansionRules.expand(snapshot,target,List.of(addition),Set.of(pos(11)),List.of(addition),false);

        assertEquals(List.of(pos(10),pos(11)),changes.wineLines().get("ancient").machines());
        assertEquals(snapshot.pois(),changes.pois());
        assertEquals(snapshot.machines(),changes.machines());
        assertEquals(snapshot.stores(),changes.stores());
        assertEquals(before,JSON.toJsonTree(profile));
        assertTrue(profile.loggingRunActive);
        assertTrue(profile.enabled(Feature.LOGGING));
        assertSame(remaining,profile.loggingRemainingPlots);
        assertSame(replanting,profile.loggingReplantingPlots);
        assertSame(lease,profile.loggingHotbarLease);
        assertEquals(LoggingHotbarLease.Stage.PARKED,lease.stage());
        assertSame(active,profile.wineProductionSchedules.get("ancient"));
        assertEquals(List.of(pos(10)),active.remaining());
        assertEquals(37L,profile.nextEligibleDay.get(LoggingRules.DUE_KEY));
    }

    @Test void anActualPendingOutputStillBlocksAndThePolicyDoesNotResolveItOrTheLoggingCheckpoint() {
        Profile profile=retained();
        String id="11111111-1111-1111-1111-111111111111";
        var output=new PendingMachineOutput(id,Feature.PRESERVES,pos(15),1,null,1,
            PendingMachineOutput.Phase.AWAITING_MACHINE_CONFIRMATION);
        profile.pendingMachineOutputs.put(id,output);
        var before=JSON.toJsonTree(profile);
        var blocked=new WineLineEditPolicy.Boundary(false,false,true,true,true,true,true,true,true,
            !profile.pendingMachineOutputs.isEmpty());

        assertTrue(WineLineEditPolicy.rejection(blocked).contains("기계 산출물"));
        assertSame(output,profile.pendingMachineOutputs.get(id));
        assertEquals(before,JSON.toJsonTree(profile));
    }

    private static WineLineEditPolicy.Boundary boundary(boolean[] values) {
        return new WineLineEditPolicy.Boundary(values[0],values[1],values[2],values[3],values[4],
            values[5],values[6],values[7],values[8],values[9]);
    }
    private static Pos pos(int x) {return new Pos(x,64,0);}
    private static Profile retained() {
        Profile profile=new Profile();
        profile.loggingRunActive=true;
        profile.enabled.put(Feature.LOGGING,true);
        profile.loggingPlots.add(new LoggingPlot("retained",pos(0)));
        profile.loggingRemainingPlots.add(pos(0));
        profile.loggingReplantingPlots.add(pos(0));
        profile.loggingHotbarLease=new LoggingHotbarLease(9,0,new ItemData("minecraft:torch",5,0,null,false,0),
            "a".repeat(64),LoggingHotbarLease.Stage.PARKED);
        profile.nextEligibleDay.put(LoggingRules.DUE_KEY,37L);
        profile.commodityStores.put("fruit",new CommodityStore("fruit","Fruit",Set.of(ItemData.ANCIENT_FRUIT),List.of(pos(30))));
        profile.commodityStores.put("wine",new CommodityStore("wine","Wine",Set.of(ItemData.ANCIENT_WINE),List.of(pos(31))));
        profile.wineProductionLines.put("ancient",new WineProductionLine("ancient","Ancient fruit wine",
            ItemData.ANCIENT_FRUIT,ItemData.ANCIENT_WINE,"fruit","wine",List.of(pos(10)),6,true));
        profile.wineProductionSchedules.put("ancient",new WineBatchSchedule(120,true,List.of(pos(10)),114L));
        return profile;
    }
}
