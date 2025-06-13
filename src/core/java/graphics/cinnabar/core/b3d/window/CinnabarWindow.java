package graphics.cinnabar.core.b3d.window;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import graphics.cinnabar.api.annotations.RewriteHierarchy;
import graphics.cinnabar.api.cvk.systems.CVKGpuDevice;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.loader.earlywindow.GLFWClassloadHelper.glfwExtCreateWindowSurface;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

@RewriteHierarchy
public class CinnabarWindow extends Window {
    
    @Nullable
    private CinnabarDevice device = null;
    private long surface;
    @Nullable
    private CinnabarSwapchain swapchain;
    private boolean actuallyVSync = this.vsync;
    
    public CinnabarWindow(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, String preferredFullscreenVideoMode, String title) {
        super(eventHandler, screenManager, displayData, preferredFullscreenVideoMode, title);
    }
    
    @Override
    public void updateVsync(boolean vsync) {
        if (device == null) {
            super.updateVsync(vsync);
            return;
        }
        RenderSystem.assertOnRenderThread();
        this.vsync = vsync;
    }
    
    public void attachDevice(CinnabarDevice device) {
        this.device = device;
        
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.mallocLong(1);
            checkVkCode(glfwExtCreateWindowSurface(device.vkInstance, this.getWindow(), null, longPtr));
            surface = longPtr.get(0);
        }
        
        swapchain = new CinnabarSwapchain(device, surface, this.vsync, getWidth(), getHeight(), VK_NULL_HANDLE);
        
        swapchain.acquire();
    }
    
    public void detachDevice() {
        if (device == null) {
            return;
        }
        assert swapchain != null;
        swapchain.destroy();
        vkDestroySurfaceKHR(device.vkInstance, surface, null);
    }
    
    @Override
    public void updateDisplay(@Nullable TracyFrameCapture tracyFrameCapture) {
        if(device == null){
            super.updateDisplay(tracyFrameCapture);
            return;
        }
        RenderSystem.pollEvents();
        Tesselator.getInstance().clear();
        
        CVKGpuDevice.get().endFrame();
        
        assert swapchain != null;
        boolean shouldRecreateSwapchain = !swapchain.present();
        shouldRecreateSwapchain = shouldRecreateSwapchain || this.getWidth() != swapchain.width;
        shouldRecreateSwapchain = shouldRecreateSwapchain || this.getHeight() != swapchain.height;

        if (this.fullscreen != this.actuallyFullscreen) {
            this.actuallyFullscreen = this.fullscreen;
            shouldRecreateSwapchain = true;
            try {
                this.setMode();
                this.eventHandler.resizeDisplay();
            } catch (Exception exception) {
                LOGGER.error("Couldn't toggle fullscreen", exception);
            }
        }
        
        if (this.vsync != this.actuallyVSync) {
            this.actuallyVSync = this.vsync;
            shouldRecreateSwapchain = true;
        }
        
        if (shouldRecreateSwapchain) {
            recreateSwapchain();
        }
        
        while (!swapchain.acquire()) {
            // if the swapchain failed to acquire here, 
            refreshFramebufferSize();
            recreateSwapchain();
        }
        
        RenderSystem.getDynamicUniforms().reset();
        Minecraft.getInstance().levelRenderer.endFrame();
        RenderSystem.pollEvents();
    }
    
    private void recreateSwapchain() {
        assert swapchain != null;
        final var newSwapchain = new CinnabarSwapchain(device, surface, this.vsync, getWidth(), getHeight(), swapchain.handle());
        swapchain.destroy(); // delayed destroy?
        swapchain = newSwapchain;
    }
    
    public CinnabarSwapchain swapchain() {
        assert swapchain != null;
        return swapchain;
    }
}
