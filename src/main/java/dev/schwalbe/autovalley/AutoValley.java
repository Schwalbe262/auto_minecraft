package dev.schwalbe.autovalley;

import dev.schwalbe.autovalley.client.ClientRuntime;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;

@Mod(AutoValley.MOD_ID)
public final class AutoValley {
    public static final String MOD_ID="autovalley";
    public AutoValley() { DistExecutor.unsafeRunWhenOn(Dist.CLIENT,() -> ClientRuntime::install); }
}
