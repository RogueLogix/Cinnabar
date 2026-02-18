package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgSampler;
import graphics.cinnabar.api.hg.enums.HgCompareOp;
import it.unimi.dsi.fastutil.longs.LongIntImmutablePair;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;

public class MercurySampler extends MercuryObject<HgSampler> implements HgSampler {
    
    private final long handle;
    
    public MercurySampler(MercuryDevice device, HgSampler.CreateInfo createInfo) {
        super(device);
        
        try (final var stack = memoryStack().push()) {
            final var vkCreateInfo = VkSamplerCreateInfo.calloc(stack);
            vkCreateInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            vkCreateInfo.pNext(0);
            vkCreateInfo.flags(0);
            vkCreateInfo.magFilter(createInfo.magLinear() ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            vkCreateInfo.minFilter(createInfo.minLinear() ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
            vkCreateInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);
            vkCreateInfo.addressModeU(createInfo.addressU());
            vkCreateInfo.addressModeV(createInfo.addressV());
            vkCreateInfo.addressModeW(createInfo.addressW());
            vkCreateInfo.mipLodBias(0.0f);
            vkCreateInfo.anisotropyEnable(createInfo.maxAnisotropy() > 0.0);
            vkCreateInfo.maxAnisotropy(createInfo.maxAnisotropy());
            vkCreateInfo.compareEnable(createInfo.compareOp() != HgCompareOp.ALWAYS);
            vkCreateInfo.compareOp(createInfo.compareOp().ordinal());
            vkCreateInfo.minLod(0);
            vkCreateInfo.maxLod(Math.max(0.25f, createInfo.mip()));
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
    
    @Override
    protected LongIntImmutablePair handleAndType() {
        return new LongIntImmutablePair(handle, VK_OBJECT_TYPE_SAMPLER);
    }
}
