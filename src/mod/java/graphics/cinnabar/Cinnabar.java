package graphics.cinnabar;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(value = Cinnabar.MOD_ID, dist = Dist.CLIENT)
public class Cinnabar {
    
    static {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            throw new IllegalStateException("Cinnabar is a client only mod, and should not be loaded on a dedicated server");
        }
    }
    
    public static final String MOD_ID = "cinnabar";
    
    public static final Logger CINNABAR_LOG = LogManager.getLogger();
}
