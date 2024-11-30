package graphics.cinnabar.api;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.annotations.Internal;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.slf4j.Logger;

@API
public class CinnabarAPI {
    
    @API
    public static final String MOD_ID = "cinnabar";
    
    @API(note = """
            Defines if Cinnabar is in debug mode
            debug mode will do additional runtime checks on native memory accesses and API usage
            debug mode will also force enable Vulkan validation layers
            THIS WILL HURT PERFORMANCE SIGNIFICANTLY
            
            enabled by default in development environments
            """)
    public static final boolean DEBUG_MODE = Bootstrapper.debugMode;
    
    @API
    public static final VkInstance vkInstance = Bootstrapper.vkInstance;
    @API
    public static final VkDevice vkDevice = Bootstrapper.vkDevice;
    
    @Internal
    public static final Logger CINNABAR_API_LOG = LogUtils.getLogger();
    
    @Internal
    @SuppressWarnings("NotNullFieldNotInitialized")
    static class Bootstrapper {
        static boolean debugMode = false;
        static VkInstance vkInstance;
        static VkDevice vkDevice;
    }
}
