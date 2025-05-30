package graphics.cinnabar.core.b3d.window;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.lib.annotations.RewriteHierarchy;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.lib.helpers.GLFWClassloadHelper.glfwExtCreateWindowSurface;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;

@RewriteHierarchy
public class CinnabarWindow extends Window {
    
    @SuppressWarnings("DataFlowIssue")
    private CinnabarDevice device = null;
    private long surface;
    private CinnabarSwapchain swapchain;
    private boolean actuallyVSync = this.vsync;
    
    
    public CinnabarWindow(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, String preferredFullscreenVideoMode, String title) {
        super(eventHandler, screenManager, displayData, preferredFullscreenVideoMode, title);
    }
    
    @Override
    public void updateVsync(boolean vsync) {
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
    
    public void detachDevice(){
        swapchain.destroy();
        vkDestroySurfaceKHR(device.vkInstance, surface, null);
    }
    
    @Override
    public void updateDisplay(TracyFrameCapture tracyFrameCapture) {
        RenderSystem.pollEvents();
        Tesselator.getInstance().clear();
        
        boolean shouldRecreateSwapchain = !swapchain.present();
        
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
            final var newSwapchain = new CinnabarSwapchain(device, surface, this.vsync, getWidth(), getHeight(), swapchain.handle());
            swapchain.destroy(); // delayed destroy?
            swapchain = newSwapchain;
        }
        
        swapchain.acquire();
        RenderSystem.getDynamicUniforms().reset();
        Minecraft.getInstance().levelRenderer.endFrame();
        RenderSystem.pollEvents();
    }
    
    public CinnabarSwapchain swapchain() {
        return swapchain;
    }
}
