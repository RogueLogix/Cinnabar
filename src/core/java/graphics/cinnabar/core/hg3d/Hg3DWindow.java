package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.TracyFrameCapture;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.Tesselator;
import graphics.cinnabar.api.annotations.RewriteHierarchy;
import graphics.cinnabar.api.hg.HgDevice;
import graphics.cinnabar.api.hg.HgSurface;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

@RewriteHierarchy
public class Hg3DWindow extends Window {
    @Nullable
    private Hg3DGpuDevice device;
    @Nullable
    private HgSurface surface;
    @Nullable
    private HgSurface.Swapchain swapchain;
    public Hg3DWindow(WindowEventHandler eventHandler, ScreenManager screenManager, DisplayData displayData, String preferredFullscreenVideoMode, String title) {
        super(eventHandler, screenManager, displayData, preferredFullscreenVideoMode, title);
    }
    private boolean actuallyVSync = this.vsync;
    
    public void attachDevice(Hg3DGpuDevice device) {
        this.device = device;
        surface = device.hgDevice().createSurface(getWindow());
        swapchain = surface.createSwapchain(this.vsync, null);
        swapchain.acquire();
    }
    
    public void detachDevice() {
        if (device == null) {
            return;
        }
        
        assert swapchain != null;
        swapchain.destroy();
        assert surface != null;
        surface.destroy();
        device = null;
    }
    
    public void updateVsync(boolean vsync) {
        RenderSystem.assertOnRenderThread();
        this.vsync = vsync;
    }
    
    @Override
    public void updateDisplay(@Nullable TracyFrameCapture tracyFrameCapture) {
        if (device == null) {
            super.updateDisplay(tracyFrameCapture);
            return;
        }
        
        RenderSystem.pollEvents();
        Tesselator.getInstance().clear();
        
        device.endFrame();
        
        assert swapchain != null;
        boolean shouldRecreateSwapchain = !swapchain.present();
        shouldRecreateSwapchain = shouldRecreateSwapchain || this.getWidth() != swapchain.width();
        shouldRecreateSwapchain = shouldRecreateSwapchain || this.getHeight() != swapchain.height();
        
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
            refreshFramebufferSize();
            recreateSwapchain();
            this.eventHandler.resizeDisplay();
        }
        
        while (!swapchain.acquire()) {
            // if the swapchain failed to acquire here, 
            refreshFramebufferSize();
            recreateSwapchain();
            this.eventHandler.resizeDisplay();
        }
        
        RenderSystem.getDynamicUniforms().reset();
        Minecraft.getInstance().levelRenderer.endFrame();
        RenderSystem.pollEvents();
    }
    
    private void recreateSwapchain() {
        assert surface != null;
        swapchain = surface.createSwapchain(this.vsync, swapchain);
    }
    
    public HgSurface.Swapchain swapchain() {
        assert swapchain != null;
        return swapchain;
    }
}
