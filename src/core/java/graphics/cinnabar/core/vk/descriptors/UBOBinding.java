package graphics.cinnabar.core.vk.descriptors;

import static org.lwjgl.vulkan.VK12.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;

public record UBOBinding(String name, int binding, int size) implements DescriptorSetBinding {
    
    @Override
    public int bindingPoint() {
        return binding;
    }
    
    @Override
    public int type() {
        return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    }
}
