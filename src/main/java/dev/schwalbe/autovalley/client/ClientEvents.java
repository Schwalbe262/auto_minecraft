package dev.schwalbe.autovalley.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.schwalbe.autovalley.core.Movement;
import dev.schwalbe.autovalley.core.MovementAxes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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
        if (!ClientKeys.STOP.isActiveAndMatches(input)) return;
        emergencyInput(Minecraft.getInstance().options.keyAttack.isActiveAndMatches(input));
        event.setCanceled(true);
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
        if (event.getAction()!=GLFW.GLFW_PRESS) return;
        var input=InputConstants.getKey(event.getKey(),event.getScanCode());
        var options=Minecraft.getInstance().options;
        if (ClientKeys.STOP.isActiveAndMatches(input)) { emergencyInput(options.keyAttack.isActiveAndMatches(input)); return; }
        if (options.keyUse.isActiveAndMatches(input) || options.keyAttack.isActiveAndMatches(input)
            || options.keyDrop.isActiveAndMatches(input) || options.keySwapOffhand.isActiveAndMatches(input)
            || options.keyInventory.isActiveAndMatches(input)) runtime().manualOutputInteraction();
        if (!runtime().running()) return;
        if (ClientKeys.TOGGLE.isActiveAndMatches(input) || ClientKeys.SETTINGS.isActiveAndMatches(input)
            || ClientKeys.STOP.isActiveAndMatches(input) || ClientKeys.WAYPOINT.isActiveAndMatches(input)) return;
        if (event.getKey()==GLFW.GLFW_KEY_LEFT_CONTROL || event.getKey()==GLFW.GLFW_KEY_RIGHT_CONTROL) return;
        boolean attack=Minecraft.getInstance().options.keyAttack.isActiveAndMatches(input);
        if (!attack && WindowSwitchKeys.allowed(runtime().profile().allowBackground,event.getKey(),Screen.hasAltDown())) return;
        runtime().manualInput(attack);
        if (attack) Minecraft.getInstance().options.keyAttack.setDown(false);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST,receiveCanceled=true)
    public void mouse(InputEvent.MouseButton.Pre event) {
        if (event.getAction()==GLFW.GLFW_PRESS) {
            if (ClientKeys.STOP.isActiveAndMatches(InputConstants.Type.MOUSE.getOrCreate(event.getButton()))) {
                emergencyInput(Minecraft.getInstance().options.keyAttack.matchesMouse(event.getButton()));
                event.setCanceled(true);
                return;
            }
            if (event.isCanceled()) return;
            runtime().manualOutputInteraction();
            if (!runtime().running()) return;
            boolean attack=event.getButton()==GLFW.GLFW_MOUSE_BUTTON_LEFT || Minecraft.getInstance().options.keyAttack.matchesMouse(event.getButton());
            runtime().manualInput(attack);
            if (attack) event.setCanceled(true);
        }
    }
    @SubscribeEvent public void scroll(InputEvent.MouseScrollingEvent event) {
        runtime().manualOutputInteraction();
        if (runtime().running()) runtime().manualInput(false);
    }
    @SubscribeEvent public void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        // This event belongs to vanilla key/mouse use, not direct gameMode.useItemOn calls.
        runtime().manualOutputInteraction();
        if (runtime().running()) runtime().manualInput(event.isAttack());
        if (event.isAttack() && runtime().blockAttacks()) { event.setCanceled(true); event.setSwingHand(false); }
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
