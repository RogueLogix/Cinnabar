package graphics.cinnabar.core;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Properties;

import static org.lwjgl.vulkan.VK12.vkGetPhysicalDeviceFeatures;
import static org.lwjgl.vulkan.VK12.vkGetPhysicalDeviceProperties2;

public class DriverWorkarounds {
    public final boolean allowIncrementalDescriptorPush;
    
    public DriverWorkarounds(VkDevice device) {
        try (final var stack = MemoryStack.stackPush()) {
            final var physicalDeviceProperties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            final var physicalDeviceProperties = physicalDeviceProperties2.properties();
            final var physicalDevice12Properties = VkPhysicalDeviceVulkan12Properties.calloc(stack).sType$Default();
            final var limits = physicalDeviceProperties.limits();
            final var deviceFeatures10 = VkPhysicalDeviceFeatures.calloc(stack);
            
            vkGetPhysicalDeviceProperties2(device.getPhysicalDevice(), physicalDeviceProperties2);
            vkGetPhysicalDeviceFeatures(device.getPhysicalDevice(), deviceFeatures10);
            
            // Intel's driver is broken for these
            allowIncrementalDescriptorPush = physicalDeviceProperties.vendorID() != 0x8086;
        }
    }
}
