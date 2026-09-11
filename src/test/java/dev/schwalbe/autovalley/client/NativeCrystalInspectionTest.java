package dev.schwalbe.autovalley.client;

import dev.schwalbe.autovalley.core.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeCrystalInspectionTest {
    private static final Pos TARGET=new Pos(640,64,1570),OTHER=new Pos(641,64,1570);
    private static final Object OWNER=new Object(),STATE=new Object();
    private static BlockData block(boolean mature,boolean working) {
        return new BlockData(TARGET,CrystalCollection.MACHINE_ID,
            Map.of("mature",Boolean.toString(mature),"working",Boolean.toString(working),"upgraded","false"));
    }
    private static NativeCrystalInspection inspection(ServerObservations observations,BlockData block) {
        return new NativeCrystalInspection(TARGET,OWNER,block,STATE,observations.generation(),observations.sequence(),100);
    }
    private static NativeCrystalInspection.Result read(NativeCrystalInspection request,ServerObservations observations,BlockData block) {
        return request.read(observations,OWNER,block,STATE,101);
    }
    @Test void onlyANewerExactTargetServerReplyCanIdentifyTheOriginal() {
        var observations=new ServerObservations();var block=block(true,false);
        observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:jade");
        var request=inspection(observations,block);
        assertFalse(read(request,observations,block).replied());
        observations.crystal(OTHER,CrystalCollection.MACHINE_ID,"society:ruby");
        assertFalse(read(request,observations,block).replied());
        observations.menu(0);observations.block(OTHER);
        assertNull(read(request,observations,block).inspection(),"Inventory or unrelated block traffic is not recipe evidence");
        observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:fire_quartz");
        var result=read(request,observations,block);
        assertTrue(result.current());assertTrue(result.replied());assertEquals("society:fire_quartz",result.inspection().inputId());
        assertTrue(result.inspection().mature());assertFalse(result.inspection().working());
    }
    @Test void latestUnknownEmptyOrForeignTypeCannotReuseAnOlderValidOriginal() {
        for(String invalid:Arrays.asList(null,"","society:pristine_jade","other:jade")) {
            var observations=new ServerObservations();var block=block(true,false);var request=inspection(observations,block);
            observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:jade");
            assertNotNull(read(request,observations,block).inspection());
            observations.crystal(TARGET,CrystalCollection.MACHINE_ID,invalid);
            assertTrue(read(request,observations,block).replied());assertNull(read(request,observations,block).inspection());
        }
        var observations=new ServerObservations();var block=block(true,false);var request=inspection(observations,block);
        observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:jade");
        observations.crystal(TARGET,"minecraft:barrel","society:jade");
        assertNull(read(request,observations,block).inspection());
    }
    @Test void anEmptyRecipeIsUsableOnlyWithAnActuallyIdleMachineAndDoesNotSelectAnyNewOriginal() {
        var observations=new ServerObservations();var block=block(false,false);var request=inspection(observations,block);
        observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"");
        var result=read(request,observations,block).inspection();assertNotNull(result);assertTrue(result.empty());assertTrue(result.valid());
        observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:jade");
        assertNull(read(request,observations,block).inspection(),"An idle state with a nonempty recipe is inconsistent");
    }
    @Test void everyBaseOriginalSupportsItsOwnWorkingAndMatureObservation() {
        for(String id:CrystalCollection.BASE_OUTPUT_IDS)for(boolean mature:List.of(true,false)) {
            var observations=new ServerObservations();var block=block(mature,!mature);var request=inspection(observations,block);
            observations.crystal(TARGET,CrystalCollection.MACHINE_ID,id);
            assertEquals(new CrystalInspection(TARGET,id,mature,!mature),read(request,observations,block).inspection());
        }
    }
    @Test void connectionContextClockAndNativeOrProjectedStateChangesInvalidateTheRead() {
        var observations=new ServerObservations();var block=block(true,false);var request=inspection(observations,block);
        observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:jade");
        assertNotNull(request.read(observations,OWNER,block,STATE,200).inspection());
        for(long tick:List.of(99L,201L,1000L))assertFalse(request.read(observations,OWNER,block,STATE,tick).current());
        assertFalse(request.read(observations,new Object(),block,STATE,101).current());
        assertFalse(request.read(observations,OWNER,block,new Object(),101).current());
        assertFalse(request.read(observations,OWNER,block(false,true),STATE,101).current());
        assertFalse(request.read(observations,OWNER,new BlockData(TARGET,"minecraft:barrel",block.properties()),STATE,101).current());
        observations.clear();observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:jade");
        assertFalse(read(request,observations,block).current());
    }
    @Test void missingNoncanonicalOrContradictoryFlagsCannotInventAMatureState() {
        for(Map<String,String> properties:List.of(Map.<String,String>of(),Map.of("mature","true","working","TRUE"),
                Map.of("mature","true","working","true"),Map.of("mature","false","working","false"))) {
            var observations=new ServerObservations();var block=new BlockData(TARGET,CrystalCollection.MACHINE_ID,properties);
            var request=inspection(observations,block);observations.crystal(TARGET,CrystalCollection.MACHINE_ID,"society:jade");
            assertNull(read(request,observations,block).inspection());
        }
    }
    @Test void observationHistoryIsImmutableBoundedAndClearedAcrossConnections() {
        var observations=new ServerObservations();
        for(int i=0;i<300;i++)observations.crystal(TARGET,CrystalCollection.MACHINE_ID,i%2==0?"society:jade":null);
        var replies=observations.nativeCrystalsSince(0);assertEquals(256,replies.size());
        assertTrue(replies.get(0).seq()>0);assertTrue(replies.get(255).seq()>replies.get(0).seq());
        assertNull(replies.get(255).inputId());assertThrows(UnsupportedOperationException.class,replies::clear);
        long generation=observations.generation();observations.clear();
        assertEquals(generation+1,observations.generation());assertTrue(observations.nativeCrystalsSince(-1).isEmpty());
    }
    @Test void packetCaptureAndReadRequestStayOutsideInventoryPredictionAndClickPaths() throws Exception {
        String observer=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/PacketObserver.java"));
        assertTrue(observer.indexOf("NativeJadePackets.crystal(packet)")<observer.indexOf("super.channelRead(ctx,message)"));
        assertTrue(observer.contains("observations.crystal(crystal.pos(),crystal.blockEntityId(),crystal.inputId())"));
        assertTrue(observer.contains("if (observations.generation()==generation) observation.run()"));
        String source=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/MinecraftActions.java"));
        int start=source.indexOf("if(action instanceof Action.InspectCrystal inspect) {");
        String request=source.substring(start,source.indexOf("if (action instanceof Action.UseBlock use)",start));
        assertTrue(request.contains("NativeJadePackets.request(hit,net.minecraft.world.level.block.Block.getId(state))"));
        assertFalse(request.contains("gameMode.useItemOn("));assertFalse(request.contains("confirmedClick("));
        assertFalse(request.contains("lookAt("));assertFalse(request.contains("getInventory().selected="));
        assertTrue(source.contains("if(this.context!=context)crystalInspection=null"));
        assertTrue(source.contains("stopMovement();crystalInspection=null"));
        int manual=source.indexOf("public void manualHotbarCustodyInteraction()");
        assertTrue(source.substring(manual,source.indexOf("@Override",manual)).contains("crystalInspection=null"));
        int dynamic=source.indexOf("ArtisanRules.forAction(context,use.pos())");
        assertTrue(dynamic<source.indexOf("crystalInspection=null;",dynamic));
        assertTrue(source.indexOf("crystalInspection=null;",dynamic)<source.indexOf("mc.gameMode.useItemOn",dynamic));
        String receipt=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/NativeCrystalInspection.java"));
        assertTrue(receipt.contains("nativeBlocksSince(beforeSequence)"));
        assertTrue(receipt.contains("target.equals(reply.pos()) && !nativeState.equals(reply.state())"));
    }
}
