package graphics.cinnabar.internal;

import graphics.cinnabar.internal.memory.MagicNumbers;
import graphics.cinnabar.internal.vulkan.VulkanCore;
import graphics.cinnabar.internal.vulkan.memory.GPUMemoryAllocator;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;

import static graphics.cinnabar.Cinnabar.LOGGER;
import static org.lwjgl.vulkan.VK10.vkDeviceWaitIdle;


public class CinnabarRenderer {
    static {
        LOGGER.info("Initializing Vulkan");
    }
    // MUST be created first, the other constructors will pull device statically, assuming it exists
    private static final VulkanCore VK_CORE = new VulkanCore();
    
    public static VkDevice device() {
        return VK_CORE.vkLogicalDevice;
    }
    
    public static void waitIdle() {
        // will early out if there is nothing to submit
        queueHelper.submit(true);
        // technically, everything is done already, but wait anyway
        vkDeviceWaitIdle(device());
    }
    
    public static VkPhysicalDeviceLimits limits() {
        return VK_CORE.limits;
    }
    
    public static long hostPtrAlignment() {
        return VK_CORE.externalMemoryHostProperties.minImportedHostPointerAlignment();
    }
    
    public static int hostPtrMemoryTypeBits() {
        return VK_CORE.hostPtrMemoryTypeBits;
    }
    
    // TODO: configurable sizes?
    public static final GPUMemoryAllocator GPUMemoryAllocator = new GPUMemoryAllocator(MagicNumbers.MiB * 256, MagicNumbers.KiB * 4);
    
    public static final VulkanQueueHelper queueHelper = new VulkanQueueHelper(2, VK_CORE.graphicsQueue, VK_CORE.graphicsQueueFamily, VK_CORE.computeQueue, VK_CORE.comptueQueueFamily, VK_CORE.transferQueue, VK_CORE.transferQueueFamily);
    
    public static void create() {
    }
    
    public static void destroy() {
        VK_CORE.destroy();
    }
}
