package graphics.cinnabar.internal.extensions.minecraft.renderer.texture;

import graphics.cinnabar.internal.vulkan.util.VulkanSampler;

public interface ICinnabarTexture {
    long viewHandle();
    
    VulkanSampler sampler();
}
