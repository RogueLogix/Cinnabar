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
     * enabled by default in development environments
     */
    public static final boolean DEBUG_MODE = false;
    
    @Internal
    public static class Internals {
        public static final boolean DEBUG_MARKER_ENABLED = fetchDebugMarkerEnabled();
        public static final Logger CINNABAR_API_LOG = LogUtils.getLogger();
        
//        @CinnabarRedirectIMPL("graphics/cinnabar/core/CinnabarCore/fetchDebugMode")
        private static boolean fetchDebugMode() {
            return false;
        }
        
//        @CinnabarRedirectIMPL("graphics/cinnabar/core/CinnabarCore/fetchDebugMarkerEnabled")
        private static boolean fetchDebugMarkerEnabled() {
            return true;
        }
        
    }
}
