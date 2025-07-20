package graphics.cinnabar.core.b3d.window;

import graphics.cinnabar.api.vk.VulkanObject;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.util.MagicNumbers;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.EXTDebugReport.VK_DEBUG_REPORT_OBJECT_TYPE_SWAPCHAIN_KHR_EXT;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarSwapchain implements VulkanObject {
    
    private final CinnabarDevice device;
    
    private final long swapchainHandle;
    private final long[] acquirePresentSemaphores;
    private final LongArrayList freeSemaphores = new LongArrayList();
    private final LongList swapchainImages;
    public final int width;
    public final int height;
    
    private int currentImageIndex = -1;
    
    public CinnabarSwapchain(CinnabarDevice device, long surface, boolean vsync, int width, int height, long oldSwapchain) {
        this.device = device;
        try (final var stack = MemoryStack.stackPush()) {
            final var intPtr = stack.callocInt(1);
            final var longPtr = stack.callocLong(1);
            
            checkVkCode(vkGetPhysicalDeviceSurfacePresentModesKHR(device.vkPhysicalDevice, surface, intPtr, null));
            final int presentModeCount = intPtr.get(0);
            final var presentModes = stack.mallocInt(presentModeCount);
            checkVkCode(vkGetPhysicalDeviceSurfacePresentModesKHR(device.vkPhysicalDevice, surface, intPtr, presentModes));
            int presentMode = -1;
            final var presentModeOrder = vsync ? MagicNumbers.VSyncPresentModeOrder : MagicNumbers.NoSyncPresentModeOrder;
            for (int i = 0; i < presentModeOrder.length && presentMode == -1; i++) {
                final int preferredPresentMode = presentModeOrder[i];
                for (int j = 0; j < presentModeCount; j++) {
                    final int currentPresentMode = presentModes.get(j);
                    if (currentPresentMode == preferredPresentMode) {
                        presentMode = preferredPresentMode;
                        break;
                    }
                }
            }
            
            final var surfaceCapabilities = VkSurfaceCapabilitiesKHR.calloc(stack);
            checkVkCode(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.vkPhysicalDevice, surface, surfaceCapabilities));
            
            VkExtent2D extent = surfaceCapabilities.currentExtent();
            if (extent.width() == -1) {
                extent = new VkExtent2D(stack.malloc(VkExtent2D.SIZEOF));
                // mojang queries GLFW for these, so, it is in pixels
                extent.width(width);
                extent.height(height);
            }
            this.width = extent.width();
            this.height = extent.height();
            
            int imageCount = surfaceCapabilities.minImageCount() + 1;
            if (surfaceCapabilities.maxImageCount() != 0) {
                imageCount = Math.min(imageCount, surfaceCapabilities.maxImageCount());
            }
            
            
            final var createInfo = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default();
            createInfo.surface(surface);
            
            createInfo.minImageCount(imageCount);
            createInfo.imageFormat(MagicNumbers.SwapchainColorFormat);
            createInfo.imageColorSpace(MagicNumbers.SwapchainColorSpace);
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            // images are always blit onto the swapchain instead of rendering directly to it
            createInfo.imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            // always blit on the graphics queue
            createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.preTransform(surfaceCapabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);
            createInfo.oldSwapchain(oldSwapchain);
            
            checkVkCode(vkCreateSwapchainKHR(device.vkDevice, createInfo, null, longPtr));
            swapchainHandle = longPtr.get(0);
            
            vkGetSwapchainImagesKHR(device.vkDevice, swapchainHandle, intPtr, null);
            final int swapchainImageCount = intPtr.get(0);
            final var swapchainImagesPtr = stack.mallocLong(swapchainImageCount);
            vkGetSwapchainImagesKHR(device.vkDevice, swapchainHandle, intPtr, swapchainImagesPtr);
            final var swapchainImages = new LongArrayList();
            for (int i = 0; i < swapchainImageCount; i++) {
                swapchainImages.add(swapchainImagesPtr.get(i));
            }
            this.swapchainImages = LongLists.unmodifiable(swapchainImages);
            
            final var semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            final var handleReturn = stack.mallocLong(1);
            for (int i = 0; i < swapchainImages.size() + 1; i++) {
                checkVkCode(vkCreateSemaphore(device.vkDevice, semaphoreCreateInfo, null, handleReturn));
                freeSemaphores.add(handleReturn.get(0));
            }
            acquirePresentSemaphores = new long[swapchainImages.size()];
        }
    }
    
    @Override
    public void destroy() {
        vkDeviceWaitIdle(device.vkDevice);
        for (int i = 0; i < freeSemaphores.size(); i++) {
            if (freeSemaphores.getLong(i) != VK_NULL_HANDLE) {
                vkDestroySemaphore(device.vkDevice, freeSemaphores.getLong(i), null);
            }
        }
        for (int i = 0; i < acquirePresentSemaphores.length; i++) {
            if (acquirePresentSemaphores[i] != VK_NULL_HANDLE) {
                vkDestroySemaphore(device.vkDevice, acquirePresentSemaphores[i], null);
            }
        }
        vkDestroySwapchainKHR(device.vkDevice, swapchainHandle, null);
    }
    
    @Override
    public long handle() {
        return swapchainHandle;
    }
    
    @Override
    public int objectType() {
        return VK_DEBUG_REPORT_OBJECT_TYPE_SWAPCHAIN_KHR_EXT;
    }
    
    public boolean acquire() {
        if (hasImageAcquired()) {
            throw new IllegalStateException();
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var frameIndexPtr = stack.mallocInt(1);
            frameIndexPtr.put(0, -1);
            final var nextSemaphore = freeSemaphores.popLong();
            // because i cant use VK_EXT_swapchain_maintenance1, i have no way of knowing when a submit finished (and its wait semaphore was waited on) except for when that image index is returned again
            // at that point, the semaphore is reset, and i can put it back into the list of free semaphores
            int code = vkAcquireNextImageKHR(device.vkDevice, swapchainHandle, Long.MAX_VALUE, nextSemaphore, VK_NULL_HANDLE, frameIndexPtr);
            currentImageIndex = frameIndexPtr.get(0);
            if (currentImageIndex != -1) {
                if (acquirePresentSemaphores[currentImageIndex] != VK_NULL_HANDLE) {
                    freeSemaphores.push(acquirePresentSemaphores[currentImageIndex]);
                }
                acquirePresentSemaphores[currentImageIndex] = nextSemaphore;
            }
            checkVkCode(code);
            return code == VK_SUCCESS;
        }
    }
    
    public boolean present() {
        if (!hasImageAcquired()) {
            throw new IllegalStateException();
        }
        try (final var stack = MemoryStack.stackPush()) {
            final var presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default();
            presentInfo.pWaitSemaphores(stack.longs(semaphore()));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapchainHandle));
            presentInfo.pImageIndices(stack.ints(currentImageIndex));
            currentImageIndex = -1;
            final int code;
            synchronized (device.graphicsQueue) {
                code = vkQueuePresentKHR(device.graphicsQueue, presentInfo);
            }
            checkVkCode(code);
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
    
    public long semaphore() {
        if (currentImageIndex == -1) {
            throw new IllegalStateException("No image acquired");
        }
        return acquirePresentSemaphores[currentImageIndex];
    }
}
