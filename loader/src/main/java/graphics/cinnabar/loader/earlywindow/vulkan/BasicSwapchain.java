package graphics.cinnabar.loader.earlywindow.vulkan;

import graphics.cinnabar.loader.earlywindow.VulkanStartup;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class BasicSwapchain {

    private final VulkanStartup.Device device;
    public final long swapchainHandle;
    public final int width;
    public final int height;
    private final LongList swapchainImages;
    private final long fence;
    private int currentImageIndex = -1;

    public BasicSwapchain(VulkanStartup.Device device, long surface, long oldSwapchain) {
        this.device = device;
        try (final var stack = MemoryStack.stackPush()) {
            final var surfaceCapabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.device().getPhysicalDevice(), surface, surfaceCapabilities);

            VkExtent2D extent = surfaceCapabilities.currentExtent();
            if (extent.width() == -1) {
                extent = new VkExtent2D(stack.malloc(VkExtent2D.SIZEOF));
                // mojang queries GLFW for these, so, it is in pixels
                extent.width(854);
                extent.height(480);
            }
            this.width = extent.width();
            this.height = extent.height();

            final var createInfo = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default();
            createInfo.surface(surface);

            createInfo.minImageCount(2);
            createInfo.imageFormat(VK_FORMAT_B8G8R8A8_UNORM);
            createInfo.imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR);
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            // images are always blit onto the swapchain instead of rendering directly to it
            createInfo.imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            // always blit on the graphics queue
            createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.preTransform(surfaceCapabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(VK_PRESENT_MODE_FIFO_KHR);
            createInfo.clipped(true);
            createInfo.oldSwapchain(oldSwapchain);

            final var longPtr = stack.callocLong(1);
            vkCreateSwapchainKHR(device.device(), createInfo, null, longPtr);
            swapchainHandle = longPtr.get(0);

            final var intPtr = stack.callocInt(1);
            vkGetSwapchainImagesKHR(device.device(), swapchainHandle, intPtr, null);
            final int swapchainImageCount = intPtr.get(0);
            final var swapchainImagesPtr = stack.mallocLong(swapchainImageCount);
            vkGetSwapchainImagesKHR(device.device(), swapchainHandle, intPtr, swapchainImagesPtr);
            final var swapchainImages = new LongArrayList();
            for (int i = 0; i < swapchainImageCount; i++) {
                swapchainImages.add(swapchainImagesPtr.get(i));
            }
            this.swapchainImages = LongLists.unmodifiable(swapchainImages);

            final var fenceCreateInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            vkCreateFence(device.device(), fenceCreateInfo, null, longPtr);
            fence = longPtr.get(0);
        }
    }

    public void destroy() {
        vkDeviceWaitIdle(device.device());
        vkDestroyFence(device.device(), fence, null);
        vkDestroySwapchainKHR(device.device(), swapchainHandle, null);
    }


    public boolean acquire() {
        if (hasImageAcquired()) {
            throw new IllegalStateException();
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var frameIndexPtr = stack.mallocInt(1);
            frameIndexPtr.put(0, -1);
            int code = vkAcquireNextImageKHR(device.device(), swapchainHandle, Long.MAX_VALUE, VK_NULL_HANDLE, fence, frameIndexPtr);
            currentImageIndex = frameIndexPtr.get(0);
            if(currentImageIndex != -1) {
                vkWaitForFences(device.device(), new long[]{fence}, true, -1);
                vkResetFences(device.device(), fence);
            }
            return code == VK_SUCCESS;
        }
    }

    public boolean present() {
        if (!hasImageAcquired()) {
            throw new IllegalStateException();
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default();
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapchainHandle));
            presentInfo.pImageIndices(stack.ints(currentImageIndex));
            currentImageIndex = -1;
            final int code;
            vkDeviceWaitIdle(device.device());
            synchronized (device.queues().getFirst().queue()) {
                code = vkQueuePresentKHR(device.queues().getFirst().queue(), presentInfo);
            }
            return code == VK_SUCCESS;
        }
    }

    public boolean hasImageAcquired() {
        return currentImageIndex != -1;
    }

    public long acquiredImage() {
        if (currentImageIndex == -1) {
            throw new IllegalStateException("No image acquired");
        }
        return swapchainImages.getLong(currentImageIndex);
    }
}
