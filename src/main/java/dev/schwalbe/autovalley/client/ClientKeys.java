package dev.schwalbe.autovalley.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.schwalbe.autovalley.AutoValley;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid=AutoValley.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class ClientKeys {
    public static final KeyMapping TOGGLE=new KeyMapping("key.autovalley.toggle",KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_F8,"key.categories.autovalley");
    public static final KeyMapping SETTINGS=new KeyMapping("key.autovalley.settings",KeyConflictContext.IN_GAME,KeyModifier.CONTROL,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_F8,"key.categories.autovalley");
    public static final KeyMapping STOP=new KeyMapping("key.autovalley.stop",KeyConflictContext.UNIVERSAL,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_PAUSE,"key.categories.autovalley");
    public static final KeyMapping WAYPOINT=new KeyMapping("key.autovalley.waypoint",KeyConflictContext.IN_GAME,InputConstants.Type.KEYSYM,GLFW.GLFW_KEY_UNKNOWN,"key.categories.autovalley");
    @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE); event.register(SETTINGS); event.register(STOP); event.register(WAYPOINT);
    }
}
