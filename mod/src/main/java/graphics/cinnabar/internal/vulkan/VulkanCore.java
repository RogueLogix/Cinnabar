package graphics.cinnabar.internal.vulkan;

import graphics.cinnabar.core.CinnabarCore;
import graphics.cinnabar.api.annotations.NotNullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.JNI.invokePPI;
import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

@NotNullDefault
public class VulkanCore implements Destroyable {
    
    public final VkInstance vkInstance;
    private final long debugCallback;
    public final VkPhysicalDevice vkPhysicalDevice;
    public final VkDevice vkLogicalDevice;
    private final VkPhysicalDeviceProperties2 properties2;
    public final VkPhysicalDeviceLimits limits;
    public final VkPhysicalDeviceExternalMemoryHostPropertiesEXT externalMemoryHostProperties;
    public final int hostPtrMemoryTypeBits;
    
    public final VkQueue graphicsQueue;
    public final int graphicsQueueFamily;
    @Nullable
    public final VkQueue computeQueue;
    public final int comptueQueueFamily;
    @Nullable
    public final VkQueue transferQueue;
    public final int transferQueueFamily;
    
    
    public VulkanCore() {
        vkInstance = CinnabarCore.vkInstance;
        debugCallback = VK_NULL_HANDLE;
        vkPhysicalDevice = CinnabarCore.vkPhysicalDevice;
        vkLogicalDevice = CinnabarCore.vkDevice;
        graphicsQueue = CinnabarCore.graphicsQueue;
        graphicsQueueFamily = CinnabarCore.graphicsQueueFamily;
        computeQueue = CinnabarCore.computeQueue;
        comptueQueueFamily = CinnabarCore.computeQueueFamily;
        transferQueue = CinnabarCore.transferQueue;
        transferQueueFamily = CinnabarCore.transferQueueFamily;
        try {
        } catch (Exception e) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, debugCallback, null);
            vkDestroyInstance(vkInstance, null);
            throw e;
        }
        
        properties2 = VkPhysicalDeviceProperties2.calloc().sType$Default();
        limits = properties2.properties().limits();
        externalMemoryHostProperties = VkPhysicalDeviceExternalMemoryHostPropertiesEXT.calloc().sType$Default();
        properties2.pNext(externalMemoryHostProperties);
        vkGetPhysicalDeviceProperties2(vkPhysicalDevice, properties2);
        // uint32 in C++, 2^32 - 1
        if (limits.maxMemoryAllocationCount() != -1) {
            destroy();
            throw new IllegalStateException("VK device must allow unlimited allocations");
        }
        
        int memoryFlags = 0;
        try (var stack = MemoryStack.stackPush()) {
            final var properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            vkGetPhysicalDeviceMemoryProperties2(vkLogicalDevice.getPhysicalDevice(), properties);
            final var memoryProperties = properties.memoryProperties();
            final var types = memoryProperties.memoryTypes();
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                types.position(i);
                final var propertyFlags = types.propertyFlags();
                final var requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                if ((propertyFlags & requiredFlags) == requiredFlags) {
                    memoryFlags |= 1 << i;
                }
            }
        }
        if (memoryFlags == 0) {
            throw new IllegalStateException();
        }
        hostPtrMemoryTypeBits = memoryFlags;
    }
    
    @Override
    public void destroy() {
        externalMemoryHostProperties.free();
        properties2.free();
    }
}
