package graphics.cinnabar.services;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper;
import org.slf4j.Logger;

public class CinnabarGraphicsBootstrapper implements GraphicsBootstrapper {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    static {
        LOGGER.trace("CinnabarGraphicsBootstrapper LOADED!");
    }
    
    private static boolean inIDE = false;
    
    public static boolean inIDE() {
        return inIDE;
    }
    
    @Override
    public String name() {
        return "CinnabarGraphicsBootstrapper";
    }
    
    @Override
    public void bootstrap(String[] arguments) {
        for (String argument : arguments) {
            if(argument.equals("--CinnabarLaunchedFromIDE")) {
                inIDE = true;
            }
        }
        CinnabarEarlyWindowProvider.attemptConfigInit();
        CinnabarLaunchPlugin.attemptInject();
    }
}
