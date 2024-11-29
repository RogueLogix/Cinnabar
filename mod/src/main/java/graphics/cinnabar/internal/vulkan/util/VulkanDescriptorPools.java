package graphics.cinnabar.internal.vulkan.util;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.vulkan.Destroyable;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK13.*;

public class VulkanDescriptorPools implements Destroyable {

    private final VkDevice device = CinnabarRenderer.device();

    private final LongArrayList uboPools = new LongArrayList();
    private int currentUBOPoolIndex = 0;
    private final LongArrayList samplerPools = new LongArrayList();
    private int currentSamplerPoolIndex = 0;

    final LongBuffer setReturnPtr = MemoryUtil.memAllocLong(1);
    final LongBuffer setLayout = MemoryUtil.memAllocLong(1);
    final VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc().sType$Default();

    @Override
    public void destroy() {
        for (int i = 0; i < uboPools.size(); i++) {
            vkDestroyDescriptorPool(device, uboPools.getLong(i), null);
        }
        for (int i = 0; i < samplerPools.size(); i++) {
            vkDestroyDescriptorPool(device, samplerPools.getLong(i), null);
        }
        allocInfo.free();
        MemoryUtil.memFree(setLayout);
    }

    public long allocSamplerSet(long layout) {
        currentSamplerPoolIndex = allocSet(samplerPools, currentSamplerPoolIndex, layout);
        if (currentSamplerPoolIndex == -1) {
            // alloc failed, create a new pool
            allocPool(samplerPools, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        }
        currentSamplerPoolIndex = samplerPools.size() - 1;
        currentSamplerPoolIndex = allocSet(samplerPools, currentSamplerPoolIndex, layout);
        if (currentSamplerPoolIndex == -1) {
            throw new IllegalStateException("Failed to allocate descriptor set");
        }
        return setReturnPtr.get(0);
    }

    public long allocUBOSet(long layout) {
        currentUBOPoolIndex = allocSet(uboPools, currentUBOPoolIndex, layout);
        if (currentUBOPoolIndex == -1) {
            // alloc failed, create a new pool
            allocPool(uboPools, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        }
        currentUBOPoolIndex = uboPools.size() - 1;
        currentUBOPoolIndex = allocSet(uboPools, currentUBOPoolIndex, layout);
        if (currentUBOPoolIndex == -1) {
            throw new IllegalStateException("Failed to allocate descriptor set");
        }
        return setReturnPtr.get(0);
    }

    private void allocPool(LongArrayList pools, int type){
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);

            final var poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.type(type);
            poolSizes.descriptorCount(512);
            createInfo.pPoolSizes(poolSizes);
            createInfo.maxSets(512);

            final var longPtr = stack.mallocLong(1);
            checkVkCode(vkCreateDescriptorPool(CinnabarRenderer.device(), createInfo, null, longPtr));
            pools.add(longPtr.get(0));
        }
    }

    private int allocSet(LongArrayList pools, int firstPool, long layout) {
        setLayout.put(0, layout);
        allocInfo.pSetLayouts(setLayout);
        for (int i = firstPool; i < pools.size(); i++) {
            final var currentPool = pools.getLong(i);
            allocInfo.descriptorPool(currentPool);
            int code = vkAllocateDescriptorSets(CinnabarRenderer.device(), allocInfo, setReturnPtr);
            if (code == VK_ERROR_OUT_OF_POOL_MEMORY || code == VK_ERROR_FRAGMENTED_POOL) {
                // pool is full, try the next one
                continue;
            }
            checkVkCode(code);
            return i;
        }
        return -1;
    }

    public void reset() {
        currentUBOPoolIndex = 0;
        currentSamplerPoolIndex = 0;
        for (int i = 0; i < uboPools.size(); i++) {
            vkResetDescriptorPool(device, uboPools.getLong(i), 0);
        }
        for (int i = 0; i < samplerPools.size(); i++) {
            vkResetDescriptorPool(device, samplerPools.getLong(i), 0);
        }
    }
}
