package graphics.cinnabar.core.vk.descriptors;

import graphics.cinnabar.core.b3d.CinnabarDevice;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import java.nio.LongBuffer;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK12.*;

public class DescriptorPool implements IDescriptorPool {
    private final CinnabarDevice device;
    
    private final LongArrayList pools = new LongArrayList();
    private int lastPool = 0;
    private final VkDescriptorSetAllocateInfo setAllocInfo = VkDescriptorSetAllocateInfo.calloc().sType$Default();
    private final LongBuffer layoutPtr = MemoryUtil.memCallocLong(1);
    private final LongBuffer returnPtr = MemoryUtil.memCallocLong(1);
    
    public DescriptorPool(CinnabarDevice device) {
        this.device = device;
    }
    
    @Override
    public void destroy() {
        for (int i = 0; i < pools.size(); i++) {
            vkDestroyDescriptorPool(device.vkDevice, pools.getLong(i), null);
        }
        setAllocInfo.free();
        MemoryUtil.memFree(layoutPtr);
        MemoryUtil.memFree(returnPtr);
    }
    
    @Override
    public long allocSet(long layout) {
        setAllocInfo.sType$Default();
        layoutPtr.put(0, layout);
        setAllocInfo.pSetLayouts(layoutPtr);
        for (int i = lastPool; i < pools.size(); i++) {
            setAllocInfo.descriptorPool(pools.getLong(i));
            if (checkVkCode(vkAllocateDescriptorSets(device.vkDevice, setAllocInfo, returnPtr)) == VK_SUCCESS) {
                lastPool = i;
                return returnPtr.get(0);
            }
        }
        lastPool = pools.size();
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default();
            createInfo.maxSets(512);
            final var poolSize = VkDescriptorPoolSize.calloc(4, stack);
            poolSize.descriptorCount(512);
            poolSize.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            poolSize.position(1);
            poolSize.descriptorCount(512);
            poolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            poolSize.position(2);
            poolSize.descriptorCount(512);
            poolSize.type(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER);
            poolSize.position(3);
            poolSize.descriptorCount(512);
            poolSize.type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            poolSize.position(0);
            createInfo.pPoolSizes(poolSize);
            vkCreateDescriptorPool(device.vkDevice, createInfo, null, returnPtr);
            pools.add(returnPtr.get(0));
        }
        setAllocInfo.descriptorPool(pools.getLast());
        checkVkCode(vkAllocateDescriptorSets(device.vkDevice, setAllocInfo, returnPtr));
        return returnPtr.get(0);
    }
    
    @Override
    public void reset() {
        lastPool = 0;
        for (int i = 0; i < pools.size(); i++) {
            vkResetDescriptorPool(device.vkDevice, pools.getLong(i), 0);
        }
    }
}
