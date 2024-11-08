package graphics.cinnabar.internal;

import graphics.cinnabar.internal.vulkan.VulkanCore;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;

import static graphics.cinnabar.Cinnabar.LOGGER;


public class CinnabarRenderer {
    static {
        LOGGER.info("Initializing Vulkan");
    }
    private static final VulkanCore VK_CORE = new VulkanCore();
    
    public static void create() {
    }
    
    public static void destroy() {
        VK_CORE.destroy();
    }
    
    public static VkPhysicalDeviceLimits limits() {
        return VK_CORE.limits;
    }
}
