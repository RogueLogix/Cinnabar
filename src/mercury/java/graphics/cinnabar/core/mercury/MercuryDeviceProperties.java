package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Properties;

import static graphics.cinnabar.core.mercury.Mercury.MEMORY_STACK;
import static org.lwjgl.vulkan.VK12.*;

public record MercuryDeviceProperties(
        String apiVersion, String driverVersion, String renderer, String vendor,
        long uboAlignment, int maxTexture2dSize,
        long maxMemoryAllocSize,
        float maxAnisotropy
) implements HgDevice.Properties {
    public static MercuryDeviceProperties create(MercuryDevice device) {
        try (final var stack = MEMORY_STACK.get().push()) {
            var physicalDeviceProperties2 = VkPhysicalDeviceProperties2.calloc(stack).sType$Default();
            var physicalDeviceProperties = physicalDeviceProperties2.properties();
            var limits = physicalDeviceProperties.limits();
            var vk11Props = VkPhysicalDeviceVulkan11Properties.calloc(stack).sType$Default();
            physicalDeviceProperties2.pNext(vk11Props);
            
            vkGetPhysicalDeviceProperties2(device.vkDevice().getPhysicalDevice(), physicalDeviceProperties2);
            
            final var vendorString = switch (physicalDeviceProperties.vendorID()) {
                case 0x1002, 0x1022 -> "AMD";
                case 0x8086 -> "Intel";
                case 0x10DE, 0x12D2 -> "Nvidia";
                case 0x1969, 0x168c, 0x17CB, 0x5143 -> "Qualcomm";
                default -> String.format("0x%x", physicalDeviceProperties.vendorID());
            };
            final var APIVersionEncoded = physicalDeviceProperties.apiVersion();
            final var driverVersionEncoded = physicalDeviceProperties.driverVersion();
            final var apiVersionUsed = String.format("Vulkan %d.%d.%d", VK_VERSION_MAJOR(APIVersionEncoded), VK_VERSION_MINOR(APIVersionEncoded), VK_VERSION_PATCH(APIVersionEncoded));
            final var driverVersion = String.format("%d.%d.%d", VK_VERSION_MAJOR(driverVersionEncoded), VK_VERSION_MINOR(driverVersionEncoded), VK_VERSION_PATCH(driverVersionEncoded));
            final var renderer = String.format("%s", physicalDeviceProperties.deviceNameString());
            return new MercuryDeviceProperties(
                    apiVersionUsed, driverVersion, renderer, vendorString,
                    limits.minUniformBufferOffsetAlignment(), limits.maxImageDimension2D(),
                    vk11Props.maxMemoryAllocationSize(),
                    limits.maxSamplerAnisotropy()
            );
        }
    }
}
