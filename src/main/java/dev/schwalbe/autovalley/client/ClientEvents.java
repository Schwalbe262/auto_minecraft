package dev.schwalbe.autovalley.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.schwalbe.autovalley.core.Movement;
import dev.schwalbe.autovalley.core.MovementAxes;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    private final EmergencyKeyRules emergencyKeys=new EmergencyKeyRules();
    private ClientRuntime runtime() { return ClientRuntime.instance(); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase!=TickEvent.Phase.END) return;
        boolean queuedStop=false;
        while (ClientKeys.STOP.consumeClick()) queuedStop=true;
        if (emergencyKeys.consumeStopPriority(queuedStop)) {
            while (ClientKeys.TOGGLE.consumeClick()) { }
            while (ClientKeys.SETTINGS.consumeClick()) { }
            while (ClientKeys.WAYPOINT.consumeClick()) { }
            runtime().emergencyStop();
            runtime().tick();
            return;
        }
        boolean settings=false;
        while (ClientKeys.SETTINGS.consumeClick()) { settings=true; runtime().openSettings(); }
        while (ClientKeys.TOGGLE.consumeClick()) if (!settings && !Screen.hasControlDown()) runtime().toggle();
        while (ClientKeys.WAYPOINT.consumeClick()) runtime().addWaypoint();
        runtime().tick();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST,receiveCanceled=true)
    public void screenKey(ScreenEvent.KeyPressed.Pre event) {
        var input=InputConstants.getKey(event.getKeyCode(),event.getScanCode());
        if (ClientKeys.STOP.isActiveAndMatches(input)) {
            emergencyInput(Minecraft.getInstance().options.keyAttack.isActiveAndMatches(input));
            event.setCanceled(true);
            return;
        }
        if (event.isCanceled() || automationControl(input) || windowSwitchKey(event.getKeyCode(),event.getModifiers())
            || !(event.getScreen() instanceof AbstractContainerScreen<?>)) return;
        boolean close=event.getKeyCode()==GLFW.GLFW_KEY_ESCAPE;
        ItemInput.Decision decision=ItemInput.decide(runtime().running(),false,false,
            itemKey(input) || close && runtime().running());
        if (decision==ItemInput.Decision.BLOCK) event.setCanceled(true);
        else if (decision==ItemInput.Decision.MANUAL) manualItems();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void screenMouse(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?>)) return;
        if (runtime().running()) event.setCanceled(true);
        else manualItems();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void screenMouseRelease(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?>)) return;
        if (runtime().running()) event.setCanceled(true);
        else manualItems();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void screenScroll(ScreenEvent.MouseScrolled.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?>)) return;
        if (runtime().running()) event.setCanceled(true);
        else manualItems();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void screenDrag(ScreenEvent.MouseDragged.Pre event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?>)) return;
        if (runtime().running()) event.setCanceled(true);
        else manualItems();
    }
    private void emergencyInput(boolean attack) {
        emergencyKeys.requestStop();
        if (attack) {
            runtime().manualOutputInteraction();
            runtime().manualInput(true);
            var key=Minecraft.getInstance().options.keyAttack;
            key.setDown(false);
            while (key.consumeClick()) { }
        }
        runtime().emergencyStop();
    }
    @SubscribeEvent public void keyboard(InputEvent.Key event) {
        if (event.getAction()!=GLFW.GLFW_PRESS && event.getAction()!=GLFW.GLFW_REPEAT) return;
        var input=InputConstants.getKey(event.getKey(),event.getScanCode());
        var options=Minecraft.getInstance().options;
        if (ClientKeys.STOP.isActiveAndMatches(input)) { emergencyInput(options.keyAttack.isActiveAndMatches(input)); return; }
        boolean attack=options.keyAttack.isActiveAndMatches(input);
        ItemInput.Decision decision=ItemInput.decide(runtime().running(),runtime().blockAttacks(),attack,itemKey(input));
        // Forge's raw key event is not cancelable and runs after vanilla queues KeyMapping clicks.
        // Remove both the held state and queued clicks before vanilla's next action tick.
        if (decision==ItemInput.Decision.BLOCK) clearItemKeys(input);
        if (automationControl(input) || windowSwitchKey(event.getKey(),event.getModifiers())
            || Minecraft.getInstance().screen!=null) return;
        if (decision==ItemInput.Decision.MANUAL) manualItems();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST,receiveCanceled=true)
    public void mouse(InputEvent.MouseButton.Pre event) {
        if (event.getAction()==GLFW.GLFW_PRESS) {
            var input=InputConstants.Type.MOUSE.getOrCreate(event.getButton());
            if (ClientKeys.STOP.isActiveAndMatches(input)) {
                emergencyInput(Minecraft.getInstance().options.keyAttack.matchesMouse(event.getButton()));
                event.setCanceled(true);
                return;
            }
            if (event.isCanceled()) return;
            var mc=Minecraft.getInstance();
            if (automationControl(input)) {
                if (runtime().running() && itemKey(input)) {
                    // A remapped automation control keeps its queued dispatch even if it shares an item button.
                    KeyMapping.click(input);
                    clearItemKeys(input);
                    event.setCanceled(true);
                }
                return;
            }
            // Screen input has its own cancellable events, including slot-release/drag actions.
            if (mc.screen!=null) return;
            boolean attack=event.getButton()==GLFW.GLFW_MOUSE_BUTTON_LEFT || mc.options.keyAttack.matchesMouse(event.getButton());
            ItemInput.Decision decision=ItemInput.decide(runtime().running(),runtime().blockAttacks(),attack,itemKey(input) || attack);
            if (decision==ItemInput.Decision.BLOCK) {
                // A refocus click normally grabs the cursor and then queues an item click.
                // Keep its normal focus behavior while suppressing the conflicting item action.
                if (mc.isWindowActive() && mc.getOverlay()==null && !mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.grabMouse();
                clearItemKeys(input);
                event.setCanceled(true);
            } else if (decision==ItemInput.Decision.MANUAL) manualItems();
        }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void scroll(InputEvent.MouseScrollingEvent event) {
        if (runtime().running()) event.setCanceled(true);
        else manualItems();
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        // This event belongs to vanilla key/mouse use, not direct gameMode.useItemOn calls.
        if (ItemInput.decide(runtime().running(),runtime().blockAttacks(),event.isAttack(),true)==ItemInput.Decision.BLOCK) {
            event.setCanceled(true); event.setSwingHand(false);
            clearKey(event.getKeyMapping());
            return;
        }
        if(event.isUseItem())runtime().manualStockContainerUse();
        manualItems();
    }
    private boolean automationControl(InputConstants.Key input) {
        return ClientKeys.TOGGLE.isActiveAndMatches(input) || ClientKeys.SETTINGS.isActiveAndMatches(input)
            || ClientKeys.WAYPOINT.isActiveAndMatches(input);
    }
    private static boolean windowSwitchKey(int key,int modifiers) {
        return WindowSwitchKeys.allowed(true,key,(modifiers & GLFW.GLFW_MOD_ALT)!=0)
            || (modifiers & GLFW.GLFW_MOD_SUPER)!=0;
    }
    private boolean itemKey(InputConstants.Key input) {
        var options=Minecraft.getInstance().options;
        if (options.keyAttack.isActiveAndMatches(input) || options.keyUse.isActiveAndMatches(input)
            || options.keyPickItem.isActiveAndMatches(input) || options.keyDrop.isActiveAndMatches(input)
            || options.keySwapOffhand.isActiveAndMatches(input) || options.keyInventory.isActiveAndMatches(input)) return true;
        for (var key:options.keyHotbarSlots) if (key.isActiveAndMatches(input)) return true;
        return false;
    }
    private void clearItemKeys(InputConstants.Key input) {
        var options=Minecraft.getInstance().options;
        for (var key:new KeyMapping[]{options.keyAttack,options.keyUse,options.keyPickItem,options.keyDrop,
                options.keySwapOffhand,options.keyInventory}) if (key.getKey().equals(input)) clearKey(key);
        for (var key:options.keyHotbarSlots) if (key.getKey().equals(input)) clearKey(key);
    }
    private static void clearKey(KeyMapping key) {
        key.setDown(false);
        while (key.consumeClick()) { }
    }
    private void manualItems() {
        runtime().manualOutputInteraction();
        runtime().manualStockContainerInteraction();
    }
    /** Item-input ownership is independent of manual takeover, which only cursor movement detects. */
    static final class ItemInput {
        enum Decision { IGNORE, BLOCK, MANUAL }
        static Decision decide(boolean running,boolean attackFence,boolean attack,boolean itemInteraction) {
            if (!itemInteraction) return Decision.IGNORE;
            return running || attack && attackFence ? Decision.BLOCK : Decision.MANUAL;
        }
    }
    @SubscribeEvent public void movement(MovementInputUpdateEvent event) {
        if (event.getEntity()!=Minecraft.getInstance().player || !runtime().running()) return;
        Movement intent=runtime().movement();
        var input=event.getInput();
        MovementAxes axes=MovementAxes.from(intent,event.getEntity().getYRot());
        input.forwardImpulse=axes.forward(); input.leftImpulse=axes.left();
        input.up=axes.forward()>0; input.down=axes.forward()<0; input.left=axes.left()>0; input.right=axes.left()<0;
        input.jumping=NativeLoggingJump.consumePulse(intent,runtime().world().tick());
        input.shiftKeyDown=intent!=null && intent.sneak();
    }
    @SubscribeEvent public void hud(RenderGuiEvent.Post event) {
        var mc=Minecraft.getInstance();
        if (mc.player==null || mc.options.hideGui || mc.screen!=null) return;
        String text="Auto Valley " + (runtime().running() ? "ON" : "OFF") + " | " + runtime().status();
        if (runtime().recording()) text="Auto Valley "+runtime().recordingStatus()+" | Ctrl+F8 → 실행·기록";
        int max=Math.max(100,mc.getWindow().getGuiScaledWidth()-20);
        text=mc.font.plainSubstrByWidth(text,max);
        // Society owns the upper-left calendar; keep automation status above the hotbar instead.
        int y=Math.max(40,mc.getWindow().getGuiScaledHeight()-74);
        event.getGuiGraphics().fill(6,y,mc.font.width(text)+14,y+16,0xB0182029);
        event.getGuiGraphics().drawString(mc.font,text,10,y+4,runtime().running() ? 0xA7E6A0 : 0xD8DEE9,false);
    }
}
