package graphics.cinnabar.core.mercury;

import com.mojang.logging.LogUtils;
import graphics.cinnabar.api.annotations.UsedFromReflection;
import graphics.cinnabar.api.hg.HgDevice;
import graphics.cinnabar.api.memory.GrowingMemoryStack;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

public class Mercury {
    public static final Logger MERCURY_LOG = LogUtils.getLogger();
    public static final boolean TRACE_LOGGING = Config.traceLogging;
    public static final boolean DEBUG_LOGGING = Config.debugLogging || TRACE_LOGGING;
    public static final boolean RENDERDOC_ATTACHED = "1".equals(System.getenv("ENABLE_VULKAN_RENDERDOC_CAPTURE"));
    public static final boolean MERCURY_VALIDATION = Config.mercuryValidationLayers;
    // vulkan validation doesnt work with renderdoc attached
    public static final boolean VULKAN_VALIDATION = !RENDERDOC_ATTACHED && Config.vulkanValidationLayers;
    
    public static final ThreadLocal<MemoryStack> MEMORY_STACK = ThreadLocal.withInitial(GrowingMemoryStack::new);
    
    static {
        MERCURY_LOG.info("Config loaded");
    }
    
    @UsedFromReflection
    public static HgDevice createDevice(HgDevice.CreateInfo createInfo) {
        return new MercuryDevice(createInfo);
    }
    
    public static class Config {
        @UsedFromReflection
        public static boolean traceLogging = Boolean.getBoolean("cinnabar.traceLogging");
        @UsedFromReflection
        public static boolean debugLogging = Boolean.getBoolean("cinnabar.debugLogging") || traceLogging;
        @UsedFromReflection
        public static boolean mercuryValidationLayers = Boolean.getBoolean("cinnabar.mercuryValidationLayers");
        @UsedFromReflection
        public static boolean vulkanValidationLayers = Boolean.getBoolean("cinnabar.vulkanValidationLayers");
    }
}
