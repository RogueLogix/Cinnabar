package graphics.cinnabar.core.vk.descriptors;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

public record SamplerBinding(String name, int bindingPoint) implements DescriptorSetBinding {
    @Override
    public int type() {
        return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }
}
