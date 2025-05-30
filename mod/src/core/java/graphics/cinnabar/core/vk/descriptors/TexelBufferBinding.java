package graphics.cinnabar.core.vk.descriptors;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;

public record TexelBufferBinding(String name, int bindingPoint, int textureFormat) implements DescriptorSetBinding {
    @Override
    public int type() {
        return VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
    }
}
