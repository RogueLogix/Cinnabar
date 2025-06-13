package graphics.cinnabar.loader.services;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.slf4j.Logger;

public class CinnabarGraphicsBootstrapper implements GraphicsBootstrapper {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    static {
        LOGGER.trace("CinnabarGraphicsBootstrapper LOADED!");
    }
    
    @Override
    public String name() {
        return "CinnabarGraphicsBootstrapper";
    }
    
    @Override
    public void bootstrap(String[] arguments) {
        CinnabarEarlyWindowProvider.attemptConfigInit();
        CinnabarLaunchPlugin.attemptInject();
    }
}
