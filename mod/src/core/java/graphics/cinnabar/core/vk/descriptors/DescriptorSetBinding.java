package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;

import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

public interface DescriptorSetBinding {
    @Constant
    @ThreadSafety.Many
    int bindingPoint();
    
    @Constant
    @ThreadSafety.Many
    int type();
    
    @Constant
    @ThreadSafety.Many
    default int count() {
        return 1;
    }
    
    @Constant
    @ThreadSafety.Many
    default int stageFlags(){
        return VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
    }
}
