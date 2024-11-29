package graphics.cinnabar.internal.vulkan.util;

import graphics.cinnabar.api.annotations.NotNullDefault;
import graphics.cinnabar.internal.CinnabarRenderer;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import org.jetbrains.annotations.Nullable;

import static org.lwjgl.vulkan.VK10.*;

@NotNullDefault
public class VulkanSampler {
    private static final VkDevice device = CinnabarRenderer.device();
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
    
    private static final Object2ObjectOpenHashMap<SamplerInfo, VulkanSampler> samplers = new Object2ObjectOpenHashMap<>();
    
    public static VulkanSampler DEFAULT = new VulkanSampler(new SamplerInfo(false, true, VK_SAMPLER_ADDRESS_MODE_REPEAT, 0));
    
    static {
        samplers.put(DEFAULT.samplerInfo, DEFAULT);
    }
    
    public static void startup() {
    }
    
    public static void shutdown() {
        samplers.forEach((samplerInfo, sampler) -> vkDestroySampler(device, sampler.vulkanHandle, null));
        createInfo.free();
    }
    
    public final long vulkanHandle;
    
    private record SamplerInfo(boolean minMagLinear, boolean mipmap, int edgeMode, int lodBias) {
    }
    
    private final SamplerInfo samplerInfo;
    
    private VulkanSampler(SamplerInfo samplerInfo) {
        this.samplerInfo = samplerInfo;
        
        createInfo.magFilter(samplerInfo.minMagLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
        createInfo.minFilter(samplerInfo.minMagLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
        createInfo.mipmapMode(samplerInfo.minMagLinear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST);
        createInfo.addressModeU(samplerInfo.edgeMode);
        createInfo.addressModeV(samplerInfo.edgeMode);
        createInfo.mipLodBias(samplerInfo.lodBias);
        createInfo.maxLod(samplerInfo.mipmap ? VK_LOD_CLAMP_NONE : 0.0f);
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.longs(0);
            vkCreateSampler(device, createInfo, null, longPtr);
            vulkanHandle = longPtr.get(0);
        }
    }
    
    @Nullable
    private VulkanSampler minMagOpposite;
    
    public VulkanSampler withMinMagLinear(boolean linear) {
        if (linear == samplerInfo.minMagLinear) {
            return this;
        }
        if (minMagOpposite == null) {
            final var newSamplerInfo = new SamplerInfo(linear, samplerInfo.mipmap, samplerInfo.edgeMode, samplerInfo.lodBias);
            var sampler = samplers.get(newSamplerInfo);
            if (sampler == null) {
                sampler = new VulkanSampler(newSamplerInfo);
                samplers.put(newSamplerInfo, sampler);
            }
            minMagOpposite = sampler;
        }
        return minMagOpposite;
    }
    
    @Nullable
    private VulkanSampler mipmapOpposite;
    
    public VulkanSampler withMipmap(boolean mipmap) {
        if (mipmap == samplerInfo.mipmap) {
            return this;
        }
        if (mipmapOpposite == null) {
            final var newSamplerInfo = new SamplerInfo(samplerInfo.minMagLinear, mipmap, samplerInfo.edgeMode, samplerInfo.lodBias);
            var sampler = samplers.get(newSamplerInfo);
            if (sampler == null) {
                sampler = new VulkanSampler(newSamplerInfo);
                samplers.put(newSamplerInfo, sampler);
            }
            mipmapOpposite = sampler;
        }
        return mipmapOpposite;
    }
    
    private final ObjectArrayList<VulkanSampler> edgeModes = new ObjectArrayList<>();
    {
        edgeModes.size(3);
    }
    
    public VulkanSampler withEdgeMode(int edgeMode) {
        if (edgeMode == samplerInfo.edgeMode) {
            return this;
        }
        if(edgeMode < 0 || edgeMode > VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE){
            throw new IllegalArgumentException("Unsupported edge mode");
        }
        var newEdgeModeSampler = edgeModes.get(edgeMode);
        if (newEdgeModeSampler == null) {
            final var newSamplerInfo = new SamplerInfo(samplerInfo.minMagLinear, samplerInfo.mipmap, edgeMode, samplerInfo.lodBias);
            var sampler = samplers.get(newSamplerInfo);
            if (sampler == null) {
                sampler = new VulkanSampler(newSamplerInfo);
                samplers.put(newSamplerInfo, sampler);
                edgeModes.set(edgeMode, sampler);
            }
            newEdgeModeSampler = sampler;
        }
        return newEdgeModeSampler;
    }
}
