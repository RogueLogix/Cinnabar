package graphics.cinnabar;

// this is one of the first classes compiled, so the error check goes here
#if !NEO && !FABRIC
#error "Unknown loader, either NEO or FABRIC should be defined"
#endif
#if NEO && FABRIC
#error "Both NEO and FABRIC cannot be defined at the same time"
#endif

#if NEO
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
#endif

#if FABRIC
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
#endif

#if NEO
@Mod(value = Cinnabar.MOD_ID, dist = Dist.CLIENT)
#endif
public class Cinnabar #if FABRIC implements ModInitializer #endif {
    
    static {
        #if NEO
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
        #elif FABRIC
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
        #endif
            throw new IllegalStateException("Cinnabar is a client only mod, and should not be loaded on a dedicated server");
        }
    }
    
    public static final String MOD_ID = "cinnabar";
    
    public static final Logger CINNABAR_LOG = LogManager.getLogger();
    
    #if FABRIC
    @Override
    public void onInitialize() {
        // no-op
    }
    #endif
}
