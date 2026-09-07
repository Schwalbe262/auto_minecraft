package dev.schwalbe.autovalley.client;

import com.mojang.logging.LogUtils;
import net.minecraft.world.level.Level;
import java.lang.reflect.Method;

/** Uses the installed mod's own clock/config/mixins; never substitutes dayTime for wine aging. */
final class VineryClock {
    private static Method getYear;
    private static boolean unavailable;
    private VineryClock() { }
    static Integer year(Level level) {
        if (level==null || unavailable) return null;
        try {
            if (getYear==null) getYear=Class.forName("net.satisfy.vinery.core.util.WineYears").getMethod("getYear",Level.class);
            Object result=getYear.invoke(null,level);
            return result instanceof Integer value && value>=0 ? value : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            unavailable=true;
            LogUtils.getLogger().warn("Auto Valley cannot read Vinery's wine age clock; wine classification is disabled");
            return null;
        }
    }
}
