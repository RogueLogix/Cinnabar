package graphics.cinnabar.core.vk.descriptors;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;

public record SSBOBinding(String name, int binding, int arrayStride) implements DescriptorSetBinding {
    
    @Override
    public int bindingPoint() {
        return binding;
    }
    
    @Override
    public int type() {
        return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    }
}
