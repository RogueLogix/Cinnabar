package graphics.cinnabar.internal.extensions.blaze3d.platform;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.vulkan.MagicNumbers;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

@NonnullDefault
public class CinnabarWindow extends Window {
    
    private final VkDevice device = CinnabarRenderer.device();
    
    private final long surface;
    private long swapchain;
    public final Vector2i swapchainExtent = new Vector2i();
    
    private int currentSwapchainFrame = -1;
    private final LongArrayList swapchainImages = new LongArrayList();
    private final long frameAcquisitionFence;
    
    private final VkImageSubresourceRange imageSubresourceRange = VkImageSubresourceRange.calloc();
    private final VkImageMemoryBarrier.Buffer imageBarrier = VkImageMemoryBarrier.calloc(1);
    
    
    public CinnabarWindow(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, @Nullable String preferredFullscreenVideoMode, String title) {
        super(eventHandler, screenManager, displayData, preferredFullscreenVideoMode, title);
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.mallocLong(1);
            glfwCreateWindowSurface(CinnabarRenderer.instance(), this.getWindow(), null, longPtr);
            surface = longPtr.get(0);
            
            createSwapchain(getWidth(), getHeight());
            
            vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, longPtr);
            frameAcquisitionFence = longPtr.get(0);
            
            final var intPtr = stack.callocInt(1);
            vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, VK_NULL_HANDLE, frameAcquisitionFence, intPtr);
            currentSwapchainFrame = intPtr.get(0);
        }
    }
    
    @Override
    public void close() {
        super.close();
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
        recreateSwapchain();
    }
    
    private void recreateSwapchain() {
        createSwapchain(getWidth(), getHeight());
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
            // TODO: no v-sync
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
            ;
            swapchain = longPtr.get(0);
            vkGetSwapchainImagesKHR(device, swapchain, intPtr, null);
            final int swapchainImageCount = intPtr.get(0);
            final var swapchainImagesPtr = stack.mallocLong(swapchainImageCount);
            vkGetSwapchainImagesKHR(device, swapchain, intPtr, swapchainImagesPtr);
            swapchainImages.clear();
            for (int i = 0; i < swapchainImageCount; i++) {
                swapchainImages.add(swapchainImagesPtr.get(i));
            }
        }
    }
    
    @Override
    public void updateDisplay() {
        glfwPollEvents();
        replayQueue();
        Tesselator.getInstance().clear();
        present();
        
        if (this.fullscreen != this.actuallyFullscreen) {
            this.actuallyFullscreen = this.fullscreen;
            this.updateFullscreen(this.vsync);
        }
    }
    
    public long getImageForBlit() {
        throwFromCode(vkWaitForFences(device, frameAcquisitionFence, true, Long.MAX_VALUE));
        return swapchainImages.getLong(currentSwapchainFrame);
    }
    
    public void present() {
        try (final var stack = MemoryStack.stackPush()) {
            final var intPtr = stack.mallocInt(1);
            
            final var swapchainPtr = stack.mallocLong(1);
            swapchainPtr.put(0, swapchain);
            final var imageIndexPtr = stack.mallocInt(1);
            imageIndexPtr.put(0, currentSwapchainFrame);
            final var presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(swapchainPtr);
            presentInfo.pImageIndices(imageIndexPtr);
            throwFromCode(vkQueuePresentKHR(CinnabarRenderer.presentQueue(), presentInfo));
            
            throwFromCode(vkWaitForFences(device, frameAcquisitionFence, true, Long.MAX_VALUE));
            throwFromCode(vkResetFences(device, frameAcquisitionFence));
            int returnCode = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, VK_NULL_HANDLE, frameAcquisitionFence, intPtr);
            if (returnCode != VK_SUCCESS) {
                throwFromCode(returnCode);
                vkWaitForFences(device, frameAcquisitionFence, true, Long.MAX_VALUE);
                vkResetFences(device, frameAcquisitionFence);
                recreateSwapchain();
                vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, VK_NULL_HANDLE, frameAcquisitionFence, intPtr);
            }
            currentSwapchainFrame = intPtr.get(0);
        }
    }
}
