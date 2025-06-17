package graphics.cinnabar.core.vk.shaders;

import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.api.vk.VulkanObject;

import static org.lwjgl.vulkan.EXTDebugReport.VK_DEBUG_REPORT_OBJECT_TYPE_SHADER_MODULE_EXT;
import static org.lwjgl.vulkan.VK10.vkDestroyShaderModule;

public class ShaderModule implements VulkanObject {
    
    
    private final CinnabarDevice device;
    private final long handle;
    
    @ThreadSafety.Any
    public ShaderModule(CinnabarDevice device, String name, long handle) {
        this.device = device;
        this.handle = handle;
        setVulkanName(name);
    }
    
    @Override
    @ThreadSafety.Many
    public long handle() {
        return handle;
    }
    
    @Override
    @ThreadSafety.Many
    public int objectType() {
        return VK_DEBUG_REPORT_OBJECT_TYPE_SHADER_MODULE_EXT;
    }
    
    @Override
    @ThreadSafety.Any
    public void destroy() {
        vkDestroyShaderModule(device.vkDevice, handle, null);
    }
}
