package dev.schwalbe.autovalley.client;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static dev.schwalbe.autovalley.client.ClientEvents.ItemInput.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

class MouseOnlyItemInputTest {
    @Test void runningAutomationSuppressesEveryItemActionWithoutRequestingManualTakeover() {
        for (boolean attack:new boolean[]{false,true})
            for (boolean fence:new boolean[]{false,true})
                assertEquals(BLOCK,ClientEvents.ItemInput.decide(true,fence,attack,true));
    }

    @Test void offItemActionsRemainManualEvidenceInvalidations() {
        assertEquals(MANUAL,ClientEvents.ItemInput.decide(false,false,false,true));
        assertEquals(MANUAL,ClientEvents.ItemInput.decide(false,false,true,true));
    }

    @Test void residualAttackFenceBlocksAnAttackEvenAfterAutomationStopped() {
        assertEquals(BLOCK,ClientEvents.ItemInput.decide(false,true,true,true));
        assertEquals(MANUAL,ClientEvents.ItemInput.decide(false,true,false,true));
    }

    @Test void desktopKeysAndOtherNonItemInputCannotInvalidateCustodyOrAcquireItemControl() {
        for (boolean running:new boolean[]{false,true})
            for (boolean fence:new boolean[]{false,true})
                assertEquals(IGNORE,ClientEvents.ItemInput.decide(running,fence,false,false));
    }

    @Test void ordinaryEventHandlersCannotReintroduceAutomaticPause() throws Exception {
        String source=source();
        for (String name:new String[]{"keyboard","mouse","scroll","interaction","screenKey","screenMouse",
                "screenMouseRelease","screenScroll","screenDrag"}) {
            String method=method(source,name);
            assertFalse(method.contains("manualInput("),name);
            assertFalse(method.contains(".pause("),name);
        }
        // The attack-overlapping explicit STOP retains its short packet fence.
        assertTrue(method(source,"emergencyInput").contains("manualInput(true)"));
    }

    @Test void noncancelableKeyboardEventsDrainHeldAndQueuedItemBindingsIncludingRebinds() throws Exception {
        String source=source();
        assertTrue(method(source,"keyboard").contains("clearItemKeys(input)"));
        String clear=method(source,"clearItemKeys");
        for (String binding:new String[]{"keyAttack","keyUse","keyPickItem","keyDrop","keySwapOffhand","keyInventory","keyHotbarSlots"})
            assertTrue(clear.contains(binding),binding);
        assertTrue(clear.contains("key.getKey().equals(input)"),"Drain the matching configured binding, not a fixed default key");
        assertTrue(method(source,"clearKey").contains("key.setDown(false)"));
        assertTrue(method(source,"clearKey").contains("while (key.consumeClick())"));
    }

    @Test void blockedVanillaActionsReturnBeforeManualStockOrOutputEvidenceCanChange() throws Exception {
        String interaction=method(source(),"interaction");
        assertTrue(interaction.indexOf("event.setCanceled(true)")<interaction.indexOf("return;"));
        assertTrue(interaction.indexOf("return;")<interaction.indexOf("manualStockContainerUse()"));
        assertTrue(interaction.indexOf("return;")<interaction.indexOf("manualItems()"));
        assertTrue(interaction.contains("event.setSwingHand(false)"));
        assertTrue(method(source(),"manualItems").contains("manualOutputInteraction()"));
        assertTrue(method(source(),"manualItems").contains("manualStockContainerInteraction()"));
    }

    @Test void stopKeepsPriorityOverOrdinaryInputFilteringAndSharedMouseBindings() throws Exception {
        String source=source();
        for (String name:new String[]{"keyboard","mouse","screenKey"}) {
            String method=method(source,name);
            assertTrue(method.indexOf("ClientKeys.STOP")<method.indexOf("automationControl(input)"),name);
            assertTrue(method.contains("emergencyInput("),name);
        }
        assertTrue(method(source,"tick").indexOf("consumeStopPriority")<method(source,"tick").indexOf("runtime().toggle()"));
        String mouse=method(source,"mouse");
        assertTrue(mouse.contains("KeyMapping.click(input)"),"A mouse control sharing an item binding still queues its explicit control");
    }

    @Test void aRefocusClickGrabsTheCursorAndCancelsTheItemPress() throws Exception {
        String mouse=method(source(),"mouse");
        int refocus=mouse.indexOf("mc.isWindowActive()");
        assertTrue(refocus>=0);
        String block=mouse.substring(refocus);
        assertTrue(block.contains("!mc.mouseHandler.isMouseGrabbed()"));
        assertTrue(block.indexOf("mc.mouseHandler.grabMouse()")<block.indexOf("event.setCanceled(true)"));
        assertTrue(block.indexOf("clearItemKeys(input)")<block.indexOf("event.setCanceled(true)"));
    }

    @Test void screenDesktopKeysAreFilteredBeforeAnyCustodyInvalidation() throws Exception {
        String source=source();
        String screenKey=method(source,"screenKey");
        assertTrue(screenKey.indexOf("windowSwitchKey(")<screenKey.indexOf("manualItems()"));
        assertTrue(screenKey.contains("AbstractContainerScreen<?>"));
        String windowKeys=method(source,"windowSwitchKey");
        assertTrue(windowKeys.contains("WindowSwitchKeys.allowed(true"),"Window switching is independent of the background option");
        assertTrue(windowKeys.contains("GLFW.GLFW_MOD_SUPER"));
    }

    private static String source() throws Exception {
        return Files.readString(Path.of("src/main/java/dev/schwalbe/autovalley/client/ClientEvents.java"));
    }

    private static String method(String source,String name) {
        var matcher=java.util.regex.Pattern.compile("(?:public|private)\\s+(?:static\\s+)?[\\w.]+\\s+"+name+"\\(").matcher(source);
        assertTrue(matcher.find(),name);
        int start=source.indexOf('{',matcher.end()),depth=1,end=start+1;
        while (depth>0 && end<source.length()) {
            char ch=source.charAt(end++);
            if (ch=='{') depth++;
            else if (ch=='}') depth--;
        }
        assertEquals(0,depth,name);
        return source.substring(start,end);
    }
}
