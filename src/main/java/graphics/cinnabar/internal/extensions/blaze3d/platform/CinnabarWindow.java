package graphics.cinnabar.internal.extensions.blaze3d.platform;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import graphics.cinnabar.Cinnabar;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.vulkan.MagicNumbers;
import graphics.cinnabar.internal.vulkan.util.VulkanQueueHelper;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.ImmediateWindowHandler;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static com.mojang.blaze3d.systems.RenderSystem.replayQueue;
import static graphics.cinnabar.internal.vulkan.MagicNumbers.NoSyncPresentModeOrder;
import static graphics.cinnabar.internal.vulkan.MagicNumbers.VSyncPresentModeOrder;
import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK13.vkCmdPipelineBarrier2;

@NonnullDefault
public class CinnabarWindow extends Window {
    
    private final VkDevice device = CinnabarRenderer.device();
    
    private final long surface;
    private long swapchain;
    public final Vector2i swapchainExtent = new Vector2i();
    
    private int currentSwapchainFrame = -1;
    private final LongArrayList swapchainImageSemaphores = new LongArrayList();
    private final LongArrayList swapchainImages = new LongArrayList();
    private final long frameAcquisitionFence;
    
    private final VkImageSubresourceRange imageSubresourceRange = VkImageSubresourceRange.calloc();
    private final VkImageMemoryBarrier.Buffer imageBarrier = VkImageMemoryBarrier.calloc(1);
    private final VkClearColorValue clearColor = VkClearColorValue.calloc();
    
    private boolean resetImage = false;
    
    public CinnabarWindow(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, @Nullable String preferredFullscreenVideoMode, String title) {
        super(eventHandler, screenManager, displayData, preferredFullscreenVideoMode, title);
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.mallocLong(1);
            glfwCreateWindowSurface(CinnabarRenderer.instance(), this.getWindow(), null, longPtr);
            surface = longPtr.get(0);
            
            createSwapchain(getWidth(), getHeight());
            
            vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, longPtr);
            frameAcquisitionFence = longPtr.get(0);
            
            acquireFrame(false);
        }
        
        imageSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        imageSubresourceRange.baseMipLevel(0);
        imageSubresourceRange.levelCount(1);
        imageSubresourceRange.baseArrayLayer(0);
        imageSubresourceRange.layerCount(1);
        
        imageBarrier.sType$Default();
        imageBarrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        imageBarrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        imageBarrier.subresourceRange(imageSubresourceRange);
    }
    
    @Override
    public void close() {
        clearColor.free();
        imageBarrier.free();
        imageSubresourceRange.free();
        vkDeviceWaitIdle(CinnabarRenderer.device());
        for (int i = 0; i < swapchainImageSemaphores.size(); i++) {
            vkDestroySemaphore(device, swapchainImageSemaphores.getLong(i), null);
        }
        vkWaitForFences(CinnabarRenderer.device(), frameAcquisitionFence, true, -1);
        vkDestroyFence(device, frameAcquisitionFence, null);
        vkDestroySwapchainKHR(device, swapchain, null);
        vkDestroySurfaceKHR(CinnabarRenderer.instance(), surface, null);
        CinnabarRenderer.destroy();
    }
    
    // called from mixin redirection in super constructor
    public static long setupMinecraftWindow(int width, int height, String title, long monitor) {
        // if the version isn't "3.2" the interned object, a window already exists, and needs to be destroyed
        // if it is "3.2", that means its the dummy, and no window actually exists, so this can be skipped
        //noinspection StringEquality
        if (ImmediateWindowHandler.getGLVersion() != "3.2") {
            final long immediateWindow = ImmediateWindowHandler.setupMinecraftWindow(() -> width, () -> height, () -> title, () -> monitor);
            glfwMakeContextCurrent(0);
            glfwDestroyWindow(immediateWindow);
        }
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        return glfwCreateWindow(width, height, title, monitor, 0);
    }
    
    @Override
    public void updateVsync(boolean vsync) {
        RenderSystem.assertOnRenderThreadOrInit();
        this.vsync = vsync;
        CinnabarRenderer.waitIdle();
        throwFromCode(vkWaitForFences(device, frameAcquisitionFence, true, Long.MAX_VALUE));
        recreateSwapchain(false);
    }
    
    private void recreateSwapchain(boolean recursing) {
        CinnabarRenderer.waitIdle();
        createSwapchain(getWidth(), getHeight());
        // re-acquire a swapchain image
        acquireFrame(recursing);
        // prevents a black flicker for a single frame
        Minecraft.getInstance().getMainRenderTarget().blitToScreen(getWidth(), getHeight());
    }
    
    private void createSwapchain(int width, int height) {
        // TODO: look at the different surface formats
        //       for now, this format is always supported
        //       if not, eat shit and die
        //       potentially just not care, this actually is supported everywhere,
        final int imageFormat = MagicNumbers.SwapchainColorFormat;
        final int imageColorSpace = MagicNumbers.SwapchainColorSpace;
        final var physicalDevice = device.getPhysicalDevice();
        
        try (final var stack = MemoryStack.stackPush()) {
            final var intPtr = stack.callocInt(1);
            final var longPtr = stack.callocLong(1);
            
            
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, intPtr, null);
            final int presentModeCount = intPtr.get(0);
            final var presentModes = stack.mallocInt(presentModeCount);
            vkGetPhysicalDeviceSurfacePresentModesKHR(physicalDevice, surface, intPtr, presentModes);
            int presentMode = -1;
            final var presentModeOrder = this.vsync ? VSyncPresentModeOrder : NoSyncPresentModeOrder;
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
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(physicalDevice, surface, surfaceCapabilities);
            
            VkExtent2D extent = surfaceCapabilities.currentExtent();
            if (extent.width() == -1) {
                extent = new VkExtent2D(stack.malloc(VkExtent2D.SIZEOF));
                // mojang queries GLFW framebuffer for these, so, it is in pixels
                extent.width(width);
                extent.height(height);
            }
            swapchainExtent.x = extent.width();
            swapchainExtent.y = extent.height();
            
            int imageCount = surfaceCapabilities.minImageCount() + 1;
            if (surfaceCapabilities.maxImageCount() != 0) {
                imageCount = Math.min(imageCount, surfaceCapabilities.maxImageCount());
            }
            
            final var createInfo = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default();
            createInfo.surface(surface);
            
            createInfo.minImageCount(imageCount);
            createInfo.imageFormat(imageFormat);
            createInfo.imageColorSpace(imageColorSpace);
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            // images are always blitted onto the swapchain instead of rendering directly to it
            createInfo.imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            // always blitted on the graphics queue
            createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.preTransform(surfaceCapabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);
            createInfo.oldSwapchain(swapchain);
            
            throwFromCode(vkCreateSwapchainKHR(device, createInfo, null, longPtr));
            vkDestroySwapchainKHR(device, swapchain, null);
            swapchain = longPtr.get(0);
            vkGetSwapchainImagesKHR(device, swapchain, intPtr, null);
            final int swapchainImageCount = intPtr.get(0);
            final var swapchainImagesPtr = stack.mallocLong(swapchainImageCount);
            vkGetSwapchainImagesKHR(device, swapchain, intPtr, swapchainImagesPtr);
            swapchainImages.clear();
            for (int i = 0; i < swapchainImageCount; i++) {
                swapchainImages.add(swapchainImagesPtr.get(i));
            }

            final var semaphoreCreateInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            final var handleReturn = stack.mallocLong(1);
            for (int i = swapchainImageSemaphores.size(); i < swapchainImageCount; i++) {
                throwFromCode(vkCreateSemaphore(device, semaphoreCreateInfo, null, handleReturn));
                swapchainImageSemaphores.add(handleReturn.get(0));
            }
        }
        currentSwapchainFrame = -1;
    }
    
    @Override
    public void updateDisplay() {
        glfwPollEvents();
        replayQueue();
        Tesselator.getInstance().clear();
        present();
        acquireFrame(false);
        
        if (this.fullscreen != this.actuallyFullscreen) {
            this.actuallyFullscreen = this.fullscreen;
            this.updateFullscreen(this.vsync);
        }
    }
    
    public long getImageForBlit() {
        throwFromCode(vkWaitForFences(device, frameAcquisitionFence, true, Long.MAX_VALUE));
        final var colorImageHandle = swapchainImages.getLong(currentSwapchainFrame);
        if(!resetImage){
            return colorImageHandle;
        }
        resetImage = false;
        try (final var stack = MemoryStack.stackPush()) {
            final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
            
            final var colorSubresourceRange = VkImageSubresourceRange.calloc(stack);
            colorSubresourceRange.baseMipLevel(0);
            colorSubresourceRange.levelCount(1);
            colorSubresourceRange.baseArrayLayer(0);
            colorSubresourceRange.layerCount(1);
            colorSubresourceRange.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            final var depthSubresourceRange = VkImageSubresourceRange.calloc(stack);
            depthSubresourceRange.baseMipLevel(0);
            depthSubresourceRange.levelCount(1);
            depthSubresourceRange.baseArrayLayer(0);
            depthSubresourceRange.layerCount(1);
            depthSubresourceRange.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
            
            
            // dont care to general
            final var depInfo = VkDependencyInfo.calloc(stack).sType$Default();
            final var imageBarriers = VkImageMemoryBarrier2.calloc(1, stack);
            imageBarriers.sType$Default();
            imageBarriers.image(colorImageHandle);
            imageBarriers.srcStageMask(VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
            imageBarriers.dstStageMask(VK_PIPELINE_STAGE_TRANSFER_BIT);
            imageBarriers.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            imageBarriers.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageBarriers.newLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            imageBarriers.subresourceRange(colorSubresourceRange);
            depInfo.pImageMemoryBarriers(imageBarriers);
            vkCmdPipelineBarrier2(commandBuffer, depInfo);
        }
        return colorImageHandle;
    }
    
    public void present() {

        try (final var stack = MemoryStack.stackPush()) {
            
            // present frame, if one is acquired
            if(currentSwapchainFrame != -1) {

                // semaphore and barrier are on teh same stage bit, should be fine
                final var semaphore = swapchainImageSemaphores.getLong(currentSwapchainFrame);
                CinnabarRenderer.queueHelper.signalSemaphore(VulkanQueueHelper.QueueType.MAIN_GRAPHICS, semaphore, 0, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT);

                CinnabarRenderer.queueHelper.submit(false);
                // wait for last acquire
                throwFromCode(vkWaitForFences(device, frameAcquisitionFence, true, Long.MAX_VALUE));
                
                final var swapchainPtr = stack.mallocLong(1);
                swapchainPtr.put(0, swapchain);
                final var imageIndexPtr = stack.mallocInt(1);
                imageIndexPtr.put(0, currentSwapchainFrame);
                final var presentInfo = VkPresentInfoKHR.calloc(stack);
                presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
                presentInfo.swapchainCount(1);
                presentInfo.pWaitSemaphores(stack.longs(semaphore));
                presentInfo.pSwapchains(swapchainPtr);
                presentInfo.pImageIndices(imageIndexPtr);
                throwFromCode(vkQueuePresentKHR(CinnabarRenderer.graphicsQueue(), presentInfo));
                currentSwapchainFrame = -1;
            }
        }
    }

    private void acquireFrame(boolean recursing) {
        try (final var stack = MemoryStack.stackPush()) {

            // acquire next frame
            final var intPtr = stack.mallocInt(1);
            intPtr.put(0, -1);

            throwFromCode(vkResetFences(device, frameAcquisitionFence));
            int returnCode = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, VK_NULL_HANDLE, frameAcquisitionFence, intPtr);
            resetImage = true;
            throwFromCode(returnCode);
            if (returnCode == VK_SUBOPTIMAL_KHR && !recursing) {
                // acquire wasn't happy, rebuild the swapchain
                // its probably a resolution change
                throwFromCode(vkWaitForFences(device, frameAcquisitionFence, true, Long.MAX_VALUE));
                throwFromCode(vkResetFences(device, frameAcquisitionFence));
                currentSwapchainFrame = -1;
                swapchainImages.clear();
                recreateSwapchain(true);
                // recreate swapchain is recursive into this function and will re-acquire a frame
            } else {
                currentSwapchainFrame = intPtr.get(0);
            }
        }
    }
    
    public void clear(int clearBits) {
        if ((clearBits & GL_COLOR_BUFFER_BIT) == 0) {
            return;
        }
        
        final var commandBuffer = CinnabarRenderer.queueHelper.getImplicitCommandBuffer(VulkanQueueHelper.QueueType.MAIN_GRAPHICS);
        
        
        imageBarrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
        imageBarrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
        imageBarrier.oldLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        imageBarrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        imageBarrier.image(getImageForBlit());
        
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarrier);
        
        vkCmdClearColorImage(commandBuffer, swapchainImages.getLong(currentSwapchainFrame), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clearColor, imageSubresourceRange);
        
        imageBarrier.oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        imageBarrier.newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        
        vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, imageBarrier);
    }
}
