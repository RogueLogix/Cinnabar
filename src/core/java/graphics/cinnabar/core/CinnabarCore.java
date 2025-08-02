package graphics.cinnabar.core;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.annotations.CinnabarRedirectIMPL;
import org.slf4j.Logger;

public class CinnabarCore {
    public static final Logger CINNABAR_CORE_LOG = LogUtils.getLogger();
    
    static {
    }
    
    @CinnabarRedirectIMPL.Dst("graphics/cinnabar/api/CinnabarAPI$Internals/fetchDebugMode")
    public static boolean fetchDebugMode() {
        return false;
    }
    
    @CinnabarRedirectIMPL.Dst("graphics/cinnabar/api/CinnabarAPI$Internals/fetchDebugMarkerEnabled")
    public static boolean fetchDebugMarkerEnabled() {
        return false;
    }
}
