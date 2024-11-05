package dev.logix.cinnabar.internal;

import dev.logix.cinnabar.internal.vulkan.VulkanCore;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;

import static dev.logix.cinnabar.Cinnabar.LOGGER;


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
