package graphics.cinnabar.core.vk;

import graphics.cinnabar.api.annotations.NotNullDefault;
import graphics.cinnabar.api.cvk.systems.CVKGpuDevice;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

@NotNullDefault
public class VulkanSampler {
    // TODO: don't pull this statically
    private static final VkDevice device = CVKGpuDevice.get().vkDevice();
    private static final VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc();
    
    static {
        createInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
        createInfo.pNext(0);
        createInfo.flags(0);
        createInfo.magFilter(VK_FILTER_NEAREST); // set at build
        createInfo.minFilter(VK_FILTER_NEAREST); // set at build
        createInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST); // set at build
        createInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT); // set at build
        createInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT); // set at build
        createInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
        createInfo.mipLodBias(0.0f); // set at build
        createInfo.anisotropyEnable(false); // TODO
        createInfo.maxAnisotropy(0.0f);
        createInfo.compareEnable(false);
        createInfo.compareOp(VK_COMPARE_OP_ALWAYS);
        createInfo.minLod(0);
        createInfo.maxLod(VK_LOD_CLAMP_NONE); // set at build
        createInfo.borderColor(VK_BORDER_COLOR_INT_TRANSPARENT_BLACK);
        createInfo.unnormalizedCoordinates(false);
    }
    
    private static final Object2ObjectOpenHashMap<State, VulkanSampler> samplers = new Object2ObjectOpenHashMap<>();
    
    public static void startup() {
    }
    
    public static void shutdown() {
        samplers.forEach((samplerInfo, sampler) -> vkDestroySampler(device, sampler.vulkanHandle, null));
        createInfo.free();
    }
    
    public final long vulkanHandle;
    
    public record State(int minFilter, int magFilter, boolean mipmap, int edgeModeU, int edgeModeV, int lodBias) {
        public VulkanSampler sampler() {
            return samplers.computeIfAbsent(this, VulkanSampler::new);
        }
    }
    
    private VulkanSampler(State samplerInfo) {
        createInfo.magFilter(samplerInfo.minFilter);
        createInfo.minFilter(samplerInfo.magFilter);
        createInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);
        createInfo.addressModeU(samplerInfo.edgeModeU);
        createInfo.addressModeV(samplerInfo.edgeModeV);
        createInfo.mipLodBias(samplerInfo.lodBias);
        createInfo.maxLod(samplerInfo.mipmap ? VK_LOD_CLAMP_NONE : 0.0f);
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.longs(0);
            vkCreateSampler(device, createInfo, null, longPtr);
            vulkanHandle = longPtr.get(0);
        }
    }
}
