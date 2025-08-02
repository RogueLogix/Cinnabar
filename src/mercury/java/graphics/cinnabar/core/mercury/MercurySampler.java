package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgSampler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class MercurySampler extends MercuryObject implements HgSampler {
    
    private final long handle;
    
    public MercurySampler(MercuryDevice device, HgSampler.CreateInfo createInfo) {
        super(device);
        
        try (final var stack = MemoryStack.stackPush()) {
            final var vkCreateInfo = VkSamplerCreateInfo.calloc(stack);
            vkCreateInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            vkCreateInfo.pNext(0);
            vkCreateInfo.flags(0);
            vkCreateInfo.magFilter(createInfo.minLinear() ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            vkCreateInfo.minFilter(createInfo.magLinear() ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            vkCreateInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
            vkCreateInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            vkCreateInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            vkCreateInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            vkCreateInfo.mipLodBias(0.0f);
            vkCreateInfo.anisotropyEnable(false);
            vkCreateInfo.maxAnisotropy(0.0f);
            vkCreateInfo.compareEnable(false);
            vkCreateInfo.compareOp(VK_COMPARE_OP_ALWAYS);
            vkCreateInfo.minLod(0);
            vkCreateInfo.maxLod(createInfo.mip() ? VK_LOD_CLAMP_NONE : 0.0f);
            vkCreateInfo.borderColor(VK_BORDER_COLOR_INT_TRANSPARENT_BLACK);
            vkCreateInfo.unnormalizedCoordinates(false);
            final var longPtr = stack.longs(0);
            checkVkCode(vkCreateSampler(device.vkDevice(), vkCreateInfo, null, longPtr));
            handle = longPtr.get(0);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroySampler(device.vkDevice(), handle, null);
    }
    
    public long vkSampler() {
        return handle;
    }
}
