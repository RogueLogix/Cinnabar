package graphics.cinnabar;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.roguelogix.phosphophyllite.config.ConfigFormat;
import net.roguelogix.phosphophyllite.config.ConfigManager;
import net.roguelogix.phosphophyllite.config.ConfigType;
import net.roguelogix.phosphophyllite.registry.RegisterConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(value = Cinnabar.MOD_ID, dist = Dist.CLIENT)
public class Cinnabar {
    
    static {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER){
            throw new IllegalStateException("Cinnabar is a client only mod, and should not be loaded on a dedicated server");
        }
    }
    
    public static final String MOD_ID = "cinnabar";
    
    @RegisterConfig(name = "cinnabar", type = ConfigType.CLIENT, format = ConfigFormat.JSON5, comment = """
            Hey, you! Yes, you, the person reading this.
            You probably shouldn't be editing this config.
            The main purpose of this config is for debugging purposes, and it shouldn't be effecting compatability
            if changing a value in this fixes an issue you are having, REPORT IT
            """)
    public static final CinnabarConfig CONFIG = new CinnabarConfig();
    
    public static final Logger LOGGER = LogManager.getLogger();
    public static final Logger CINNABAR_LOG = LOGGER;
    
    static {
        try {
            // this needs to be registered extra extra early, so it can be read at cinnabar init, which is at MC's window init
            ConfigManager.registerConfig(CONFIG, Cinnabar.class.getField("CONFIG").getAnnotation(RegisterConfig.class));
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }
}
