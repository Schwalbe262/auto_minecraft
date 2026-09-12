package dev.schwalbe.autovalley.client;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class MouseMovementTakeoverTest {
    private static boolean sample(MouseMovementTakeover m,long tick,double x,double y) {
        return m.moved(tick,true,true,true,false,null,1920,1080,x,y);
    }
    @Test void stationaryCursorNeverPausesEvenAcrossManyTicks() {
        var m=new MouseMovementTakeover();
        for(int tick=0;tick<1000;tick++)assertFalse(sample(m,tick,500,400));
    }
    @Test void eitherAxisAndFractionalRealMotionTriggerAfterStartGrace() {
        for(double[] delta:new double[][]{{1,0},{0,-1},{.001,0}}) {
            var m=new MouseMovementTakeover();assertFalse(sample(m,0,500,400));
            assertTrue(sample(m,4,500+delta[0],400+delta[1]));
        }
    }
    @Test void offAndUnfocusedDesktopMotionNeverTriggersOrPoisonsTheNextFocusBaseline() {
        var m=new MouseMovementTakeover();sample(m,0,0,0);sample(m,4,0,0);
        for(int tick=5;tick<50;tick++)assertFalse(m.moved(tick,true,false,true,false,null,1920,1080,tick*999,tick*-10));
        assertFalse(sample(m,50,960,540));assertFalse(sample(m,51,123,456));
        assertFalse(sample(m,54,123,456));assertTrue(sample(m,55,124,456));
        assertFalse(m.moved(56,false,true,true,false,null,1920,1080,1000,1000));
        assertFalse(sample(m,57,1000,1000));
    }
    @Test void altTabAwayAndReturnCanRepeatWithoutAFalseManualTakeover() {
        var m=new MouseMovementTakeover();
        for(int cycle=0;cycle<50;cycle++) {
            long base=cycle*20L;
            assertFalse(m.moved(base,true,false,false,false,null,1920,1080,0,0));
            assertFalse(sample(m,base+1,960,540));assertFalse(sample(m,base+2,100,200));
            assertFalse(sample(m,base+5,100,200));assertFalse(sample(m,base+10,100,200));
        }
    }
    @Test void automaticMenuOpenCloseAndCursorRegrabRebaseButLaterGuiMotionIsTakeover() {
        var m=new MouseMovementTakeover();Object menu=new Object();sample(m,0,2,3);sample(m,4,2,3);
        assertFalse(m.moved(5,true,true,false,false,menu,1920,1080,960,540));
        assertFalse(m.moved(6,true,true,false,false,menu,1920,1080,400,300));
        assertFalse(m.moved(9,true,true,false,false,menu,1920,1080,400,300));
        assertTrue(m.moved(10,true,true,false,false,menu,1920,1080,401,300));
        assertFalse(sample(m,11,960,540));assertFalse(sample(m,12,2,3));
        assertFalse(sample(m,15,2,3));assertTrue(sample(m,16,2,4));
    }
    @Test void changingToAnotherScreenOfTheSameTypeStillRebases() {
        var m=new MouseMovementTakeover();Object first=new Object(),second=new Object();
        assertFalse(m.moved(0,true,true,false,false,first,1920,1080,1,1));
        assertFalse(m.moved(10,true,true,false,false,second,1920,1080,200,300));
        assertTrue(m.moved(14,true,true,false,false,second,1920,1080,200,301));
    }
    @Test void resizingTheGameWindowDoesNotLookLikeMouseTakeover() {
        var m=new MouseMovementTakeover();sample(m,0,1,1);sample(m,4,1,1);
        assertFalse(m.moved(5,true,true,true,false,null,1280,720,640,360));
        assertFalse(m.moved(9,true,true,true,false,null,1280,720,640,360));
        assertTrue(m.moved(10,true,true,true,false,null,1280,720,640,361));
    }
    @Test void sleepAndWakeStartANewBaselineRatherThanReadingCameraAnimation() {
        var m=new MouseMovementTakeover();sample(m,0,1,1);sample(m,4,1,1);
        assertFalse(m.moved(5,true,true,false,true,new Object(),1920,1080,960,540));
        assertFalse(sample(m,100,50,60));assertFalse(sample(m,101,960,540));
        assertFalse(sample(m,104,960,540));assertTrue(sample(m,105,960,541));
    }
    @Test void explicitResetAndTickRewindDiscardTheOldContext() {
        var m=new MouseMovementTakeover();sample(m,100,1,1);sample(m,104,1,1);
        assertFalse(sample(m,0,960,540));assertTrue(sample(m,4,961,540));
        m.reset();assertFalse(sample(m,5,20,30));assertFalse(sample(m,9,20,30));
    }
    @Test void minimizedOrInvalidCoordinatesCannotCreateAPhantomInput() {
        var m=new MouseMovementTakeover();sample(m,0,1,1);sample(m,4,1,1);
        assertFalse(m.moved(5,true,true,true,false,null,0,0,100,100));
        assertFalse(sample(m,6,Double.NaN,1));assertFalse(sample(m,7,1,Double.POSITIVE_INFINITY));
        assertFalse(sample(m,8,500,400));assertFalse(sample(m,12,500,400));
    }
    @Test void realMouseMotionWhileOffCancelsAnAutomaticSaveRecoveryButOrdinaryOffStillIgnoresIt() {
        var mouse=new MouseMovementTakeover();var recovery=new PersistenceRecovery();
        var identity=new PersistenceRecovery.Identity(new Object(),new Object(),new Object(),"profile");
        recovery.failed(identity,new ProfileStore.Diagnostic(ProfileStore.Stage.REPLACE,"AccessDeniedException",3,false),
            new java.nio.file.AccessDeniedException("isolated-profile"),0,
            new PersistenceRecovery.Resume(dev.schwalbe.autovalley.core.RunMode.CONTINUOUS,null));
        boolean engineRunning=false;
        assertFalse(mouse.moved(0,engineRunning || recovery.automaticPending(),true,true,false,null,1920,1080,10,20));
        assertTrue(mouse.moved(4,engineRunning || recovery.automaticPending(),true,true,false,null,1920,1080,11,20));
        recovery.cancelAutomatic("MANUAL_INPUT");
        assertNull(recovery.begin(identity,20,false));assertFalse(recovery.automaticPending());
        assertFalse(mouse.moved(5,engineRunning || recovery.automaticPending(),true,true,false,null,1920,1080,99,99));
        assertNotNull(recovery.begin(identity,20,true),"The user's later explicit F8 remains separate permission");
    }
    @Test void runtimeReadsCursorRatherThanCameraAnglesAndKeepsExplicitSafetyStops() throws Exception {
        String source=Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/ClientRuntime.java"));
        assertTrue(source.contains("mouseTakeover.moved(world.tick(),running() || persistenceRecovery.automaticPending(),mc.isWindowActive()"));
        assertTrue(source.contains("mc.mouseHandler.xpos(),mc.mouseHandler.ypos()"));
        String manual=source.substring(source.indexOf("public void manualInput(boolean attack)"),source.indexOf("public void manualOutputInteraction()"));
        assertTrue(manual.indexOf("cancelPersistenceResume(\"MANUAL_INPUT\")")>=0);
        assertTrue(manual.indexOf("cancelPersistenceResume(\"MANUAL_INPUT\")")<manual.indexOf("if (!running()) return"),
            "An OFF engine with a pending automatic retry must still honor manual takeover before the running guard");
        assertFalse(source.contains("expectedYaw"));assertFalse(source.contains("expectedPitch"));
        assertFalse(source.contains("anglesValid"));
        assertTrue(source.contains("public void emergencyStop()"));
        assertTrue(source.contains("!profile.allowBackground && !mc.isWindowActive()"));
    }
}
