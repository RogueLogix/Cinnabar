package graphics.cinnabar.api;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import graphics.cinnabar.api.annotations.API;
import graphics.cinnabar.api.util.Destroyable;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;

@API
public interface CinnabarGpuDevice extends GpuDevice {
    
    @API
    static CinnabarGpuDevice get() {
        return (CinnabarGpuDevice) RenderSystem.getDevice();
    }
    
    @API
    VkInstance vkInstance();
    
    @API
    VkDevice vkDevice();
    
    @API
    <T extends Destroyable> T destroyEndOfFrame(T destroyable);
    
    @API
    <T extends Destroyable> T destroyOnShutdown(T destroyable);
    
    void endFrame();
}
