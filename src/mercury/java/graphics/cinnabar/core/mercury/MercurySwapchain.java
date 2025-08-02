package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgSurface;
import graphics.cinnabar.api.util.Destroyable;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongLists;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

public class MercurySwapchain extends MercuryObject implements HgSurface.Swapchain {
    
    public static final int SwapchainColorFormat = VK_FORMAT_B8G8R8A8_UNORM;
    public static final int SwapchainColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    
    public static final int[] VSyncPresentModeOrder = new int[]{
            VK_PRESENT_MODE_MAILBOX_KHR, // triple buffering, should add a toggle for this
            VK_PRESENT_MODE_FIFO_RELAXED_KHR, // preferred if supported in case sync is just missed
            VK_PRESENT_MODE_FIFO_KHR // guaranteed by spec
    };
    
    public static final int[] NoSyncPresentModeOrder = new int[]{
            VK_PRESENT_MODE_IMMEDIATE_KHR,
            VK_PRESENT_MODE_FIFO_KHR // because this is the only one the VK spec guarantees, it's the fallback for nosync
    };
    
    public final int width;
    public final int height;
    public final long handle;
    private final LongList swapchainImages;
    private final ReferenceArrayList<MercurySemaphore> activeSemaphores = new ReferenceArrayList<>();
    private final ReferenceArrayList<MercurySemaphore> freeSemaphores = new ReferenceArrayList<>();
    private final long fence;
    
    private boolean swapchainInvalid = false;
    private int currentImageIndex = -1;
    
    public MercurySwapchain(MercurySurface surface, boolean vsync, @Nullable MercurySwapchain previous) {
        super(surface.device);
        
        try (final var stack = MemoryStack.stackPush()) {
            final var intPtr = stack.callocInt(1);
            final var longPtr = stack.callocLong(1);
            
            checkVkCode(vkGetPhysicalDeviceSurfacePresentModesKHR(device.vkDevice().getPhysicalDevice(), surface.vkSurface(), intPtr, null));
            final int presentModeCount = intPtr.get(0);
            final var presentModes = stack.mallocInt(presentModeCount);
            checkVkCode(vkGetPhysicalDeviceSurfacePresentModesKHR(device.vkDevice().getPhysicalDevice(), surface.vkSurface(), intPtr, presentModes));
            int presentMode = -1;
            final var presentModeOrder = vsync ? VSyncPresentModeOrder : NoSyncPresentModeOrder;
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
            checkVkCode(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device.vkDevice().getPhysicalDevice(), surface.vkSurface(), surfaceCapabilities));
            
            VkExtent2D extent = surfaceCapabilities.currentExtent();
            if (extent.width() == -1) {
                extent = VkExtent2D.calloc(stack);
                final var width = new int[1];
                final var height = new int[1];
                GLFW.glfwGetFramebufferSize(surface.glfwWindow(), width, height);
                extent.width(width[0]);
                extent.height(height[1]);
            }
            this.width = extent.width();
            this.height = extent.height();
            
            int imageCount = surfaceCapabilities.minImageCount();
            if (imageCount == 1) {
                imageCount++;
            }
            if (surfaceCapabilities.maxImageCount() != 0) {
                imageCount = Math.min(imageCount, surfaceCapabilities.maxImageCount());
            }
            
            final var createInfo = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default();
            createInfo.surface(surface.vkSurface());
            
            createInfo.minImageCount(imageCount);
            createInfo.imageFormat(SwapchainColorFormat);
            createInfo.imageColorSpace(SwapchainColorSpace);
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
            if (previous != null) {
                createInfo.oldSwapchain(previous.handle);
            }
            
            checkVkCode(vkCreateSwapchainKHR(device.vkDevice(), createInfo, null, longPtr));
            handle = longPtr.get(0);
            
            if (previous != null) {
                previous.destroy();
            }
            
            vkGetSwapchainImagesKHR(device.vkDevice(), handle, intPtr, null);
            final int swapchainImageCount = intPtr.get(0);
            final var swapchainImagesPtr = stack.mallocLong(swapchainImageCount);
            vkGetSwapchainImagesKHR(device.vkDevice(), handle, intPtr, swapchainImagesPtr);
            final var swapchainImages = new LongArrayList();
            for (int i = 0; i < swapchainImageCount; i++) {
                swapchainImages.add(swapchainImagesPtr.get(i));
            }
            this.swapchainImages = LongLists.unmodifiable(swapchainImages);
            
            activeSemaphores.size(swapchainImages.size());
            for (int i = 0; i < swapchainImages.size() * 2 + 1; i++) {
                freeSemaphores.add(device.createSemaphore(-1));
            }
            
            final var fenceCreateInfo = VkFenceCreateInfo.calloc(stack).sType$Default();
            vkCreateFence(device.vkDevice(), fenceCreateInfo, null, longPtr);
            fence = longPtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDeviceWaitIdle(device.vkDevice());
        freeSemaphores.forEach(Destroyable::destroy);
        activeSemaphores.forEach(Destroyable::destroySafe);
        vkDestroySwapchainKHR(device.vkDevice(), handle, null);
    }
    
    @Override
    public int width() {
        return width;
    }
    
    @Override
    public int height() {
        return height;
    }
    
    @Override
    public boolean acquire() {
        if (swapchainInvalid) {
            throw new IllegalStateException();
        }
        assert currentImageIndex == -1;
        try (final var stack = MemoryStack.stackPush()) {
            final var frameIndexPtr = stack.mallocInt(1);
            frameIndexPtr.put(0, -1);
            final var nextSemaphore = freeSemaphores.removeFirst();
            // because I cant use VK_EXT_swapchain_maintenance1, I have no way of knowing when a submit finished (and its wait semaphore was waited on) except for when that image index is returned again
            // at that point, the semaphore is reset, and I can put it back into the list of free semaphores
            int code = vkAcquireNextImageKHR(device.vkDevice(), handle, Long.MAX_VALUE, nextSemaphore.vkSemaphore(), fence, frameIndexPtr);
            currentImageIndex = frameIndexPtr.get(0);
            if (currentImageIndex != -1) {
                if (activeSemaphores.get(currentImageIndex) != null) {
                    freeSemaphores.addLast(activeSemaphores.get(currentImageIndex));
                }
                activeSemaphores.set(currentImageIndex, nextSemaphore);
            }
            checkVkCode(code);
            if (code != VK_SUCCESS) {
                swapchainInvalid = true;
            }
            return !swapchainInvalid;
        }
    }
    
    @Override
    public boolean present() {
        if (swapchainInvalid) {
            throw new IllegalStateException();
        }
        assert currentImageIndex != -1;
        try (final var stack = MemoryStack.stackPush()) {
            final var presentInfo = VkPresentInfoKHR.calloc(stack).sType$Default();
            presentInfo.pWaitSemaphores(stack.longs(currentSemaphore().vkSemaphore()));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(handle));
            presentInfo.pImageIndices(stack.ints(currentImageIndex));
            currentImageIndex = -1;
            final int code;
            vkWaitForFences(device.vkDevice(), new long[]{fence}, true, -1);
            vkResetFences(device.vkDevice(), fence);
            synchronized (device.graphicsQueue.vkQueue()) {
                code = vkQueuePresentKHR(device.graphicsQueue.vkQueue(), presentInfo);
            }
            checkVkCode(code);
            if (code != VK_SUCCESS) {
                swapchainInvalid = true;
            }
            return !swapchainInvalid;
        }
    }
    
    @Override
    public MercurySemaphore currentSemaphore() {
        assert currentImageIndex != -1;
        return activeSemaphores.get(currentImageIndex);
    }
    
    public long currentVkImage() {
        assert currentImageIndex != -1;
        return swapchainImages.getLong(currentImageIndex);
    }
}
