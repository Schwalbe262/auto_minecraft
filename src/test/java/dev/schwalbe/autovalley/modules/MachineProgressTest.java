package dev.schwalbe.autovalley.modules;

import dev.schwalbe.autovalley.core.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Read-only message fixtures: these tests cannot dispatch game actions. */
class MachineProgressTest {
    @Test void scanAndSnapshotReportCompletedWarehouseCountInsteadOfGenericServicing() throws Exception {
        MachineModule module=fixture(Feature.WINE,384,32);
        set(module,"sourceIndex",22);
        for(String stage:List.of("SOURCE","SNAPSHOT")) {
            state(module,"stage",stage);
            assertEquals("토마토 재고 확인 22/32 — 가장 많은 등급 선택",message(module));
        }
        assertEquals(22,get(module,"sourceIndex"));
    }
    @Test void pendingScanAndClosingScanKeepTheWarehouseProgressVisible() throws Exception {
        MachineModule module=fixture(Feature.WINE,384,32); set(module,"sourceIndex",4);
        state(module,"stage","MACHINE"); state(module,"pending","OPEN_SCAN");
        assertTrue(message(module).startsWith("토마토 재고 확인 4/32"));
        state(module,"pending","CLOSE"); state(module,"afterClose","SOURCE");
        assertTrue(message(module).startsWith("토마토 재고 확인 4/32"));
    }
    @Test void fetchingExplainsMaterialWithdrawalAndSelectedGrade() throws Exception {
        MachineModule module=fixture(Feature.WINE,384,32); set(module,"grade",2);
        for(String stage:List.of("FETCH_SOURCE","FETCH")) {
            state(module,"stage",stage);
            assertTrue(message(module).startsWith("재료 가져오는 중 — 등급 2 토마토"));
            assertTrue(message(module).contains("와인통 1/384"));
        }
    }
    @Test void machinePhasesAlwaysCarryCurrentIndexAndCorrectFacilityKind() throws Exception {
        for(Feature feature:List.of(Feature.WINE,Feature.PRESERVES)) {
            MachineModule module=fixture(feature,144,0); set(module,"machineIndex",7);
            for(String stage:List.of("RETURN","EQUIP","VERIFY","OUTPUT")) {
                state(module,"stage",stage);
                assertTrue(message(module).startsWith((feature==Feature.WINE ? "와인통 " : "절임통 ")+"8/144 — "));
                assertFalse(message(module).contains("Servicing"));
            }
        }
    }
    @Test void completedOrEmptyListsDoNotDisplayImpossibleProgress() throws Exception {
        MachineModule module=fixture(Feature.PRESERVES,2,0); set(module,"machineIndex",2);
        state(module,"stage","VERIFY"); assertTrue(message(module).startsWith("절임통 2/2"));
        state(module,"stage","SOURCE"); assertTrue(message(module).startsWith("토마토 재고 확인 0/0"));
    }
    private MachineModule fixture(Feature feature,int machines,int sources) throws Exception {
        MachineModule module=new MachineModule(feature);
        set(module,"machines",Collections.nCopies(machines,new Poi(new Pos(0,64,0),feature==Feature.WINE?PoiKind.WINE_KEG:PoiKind.PRESERVES_JAR,"fixture",null)));
        set(module,"sources",Collections.nCopies(sources,new Poi(new Pos(1,64,0),PoiKind.TOMATO_CHEST,"source",0)));
        return module;
    }
    private Object get(MachineModule module,String name) throws Exception {
        Field field=MachineModule.class.getDeclaredField(name); field.setAccessible(true); return field.get(module);
    }
    private void set(MachineModule module,String name,Object value) throws Exception {
        Field field=MachineModule.class.getDeclaredField(name); field.setAccessible(true); field.set(module,value);
    }
    private void state(MachineModule module,String field,String value) throws Exception {
        Field target=MachineModule.class.getDeclaredField(field); target.setAccessible(true);
        target.set(module,Arrays.stream(target.getType().getEnumConstants()).filter(e -> ((Enum<?>)e).name().equals(value)).findFirst().orElseThrow());
    }
    private String message(MachineModule module) throws Exception {
        Method method=MachineModule.class.getDeclaredMethod("progressStatus"); method.setAccessible(true); return (String)method.invoke(module);
    }
}
