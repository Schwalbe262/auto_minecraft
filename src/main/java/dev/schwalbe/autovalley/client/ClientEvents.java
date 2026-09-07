package dev.schwalbe.autovalley.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.schwalbe.autovalley.core.Movement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

public final class ClientEvents {
    private ClientRuntime runtime() { return ClientRuntime.instance(); }
    @SubscribeEvent public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase!=TickEvent.Phase.END) return;
        boolean settings=false;
        while (ClientKeys.SETTINGS.consumeClick()) { settings=true; runtime().openSettings(); }
        while (ClientKeys.TOGGLE.consumeClick()) if (!settings && !Screen.hasControlDown()) runtime().toggle();
        while (ClientKeys.STOP.consumeClick()) runtime().emergencyStop();
        while (ClientKeys.WAYPOINT.consumeClick()) runtime().addWaypoint();
        runtime().tick();
    }
    @SubscribeEvent public void keyboard(InputEvent.Key event) {
        if (event.getAction()!=GLFW.GLFW_PRESS || !runtime().running()) return;
        var input=InputConstants.getKey(event.getKey(),event.getScanCode());
        if (ClientKeys.TOGGLE.isActiveAndMatches(input) || ClientKeys.SETTINGS.isActiveAndMatches(input)
            || ClientKeys.STOP.isActiveAndMatches(input) || ClientKeys.WAYPOINT.isActiveAndMatches(input)) return;
        if (event.getKey()==GLFW.GLFW_KEY_LEFT_CONTROL || event.getKey()==GLFW.GLFW_KEY_RIGHT_CONTROL) return;
        boolean attack=Minecraft.getInstance().options.keyAttack.isActiveAndMatches(input);
        runtime().manualInput(attack);
        if (attack) Minecraft.getInstance().options.keyAttack.setDown(false);
    }
    @SubscribeEvent public void mouse(InputEvent.MouseButton.Pre event) {
        if (event.getAction()==GLFW.GLFW_PRESS && runtime().running()) {
            boolean attack=event.getButton()==GLFW.GLFW_MOUSE_BUTTON_LEFT || Minecraft.getInstance().options.keyAttack.matchesMouse(event.getButton());
            runtime().manualInput(attack);
            if (attack) event.setCanceled(true);
        }
    }
    @SubscribeEvent public void scroll(InputEvent.MouseScrollingEvent event) { if (runtime().running()) runtime().manualInput(false); }
    @SubscribeEvent public void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (event.isAttack() && runtime().blockAttacks()) { event.setCanceled(true); event.setSwingHand(false); }
    }
    @SubscribeEvent public void movement(MovementInputUpdateEvent event) {
        if (event.getEntity()!=Minecraft.getInstance().player || !runtime().running()) return;
        Movement intent=runtime().movement();
        var input=event.getInput();
        input.forwardImpulse=intent!=null && intent.forward() ? 1 : 0;
        input.leftImpulse=0; input.up=input.forwardImpulse>0; input.down=false; input.left=false; input.right=false;
        input.jumping=false; input.shiftKeyDown=intent!=null && intent.sneak();
    }
    @SubscribeEvent public void hud(RenderGuiEvent.Post event) {
        var mc=Minecraft.getInstance();
        if (mc.player==null || mc.options.hideGui || mc.screen!=null) return;
        String text="Auto Valley " + (runtime().running() ? "ON" : "OFF") + " | " + runtime().status();
        int max=Math.max(100,mc.getWindow().getGuiScaledWidth()-20);
        text=mc.font.plainSubstrByWidth(text,max);
        event.getGuiGraphics().fill(6,6,mc.font.width(text)+14,22,0xB0182029);
        event.getGuiGraphics().drawString(mc.font,text,10,10,runtime().running() ? 0xA7E6A0 : 0xD8DEE9,false);
    }
}
