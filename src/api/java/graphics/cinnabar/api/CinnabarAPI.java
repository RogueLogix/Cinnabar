package graphics.cinnabar.api;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import org.slf4j.Logger;

@API
public class CinnabarAPI {
    
    public static final String MOD_ID = "cinnabar";
    
    /*
     * Defines if Cinnabar is in debug mode
     * debug mode will do additional runtime checks on native memory accesses and API usage
     * debug mode will also force enable Vulkan validation layers
     * THIS WILL HURT PERFORMANCE SIGNIFICANTLY WHEN ENABLED
     *
     * debug marker can be enabled separately (forced on when this is enabled)
     */
    public static final boolean DEBUG_MODE = Internals.fetchDebugMode();
    
    @Internal
    public static class Internals {
        public static final boolean DEBUG_MARKER_ENABLED = fetchDebugMarkerEnabled();
        public static final Logger CINNABAR_API_LOG = LogUtils.getLogger();
        
        private static boolean fetchDebugMode() {
            return false;
        }
        
        private static boolean fetchDebugMarkerEnabled() {
            return false;
        }
        
    }
}
