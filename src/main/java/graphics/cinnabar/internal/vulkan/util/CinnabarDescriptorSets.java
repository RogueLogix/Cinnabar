package graphics.cinnabar.internal.vulkan.util;

import graphics.cinnabar.internal.CinnabarRenderer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;

import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.vulkan.VK10.*;

public class CinnabarDescriptorSets {
    // TODO: dynamic the shit out of this
    //       ill need more, a lot more
    private static final long descriptorPool;
    
    static {
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkDescriptorPoolCreateInfo.callocStack(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            
            final var poolSizes = VkDescriptorPoolSize.callocStack(2, stack);
            poolSizes.position(0);
            poolSizes.type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            poolSizes.descriptorCount(512);
            poolSizes.position(1);
            poolSizes.type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            poolSizes.descriptorCount(512);
            poolSizes.position(0);
            
            createInfo.pPoolSizes(poolSizes);
            createInfo.maxSets(1024);
            
            final var longPtr = stack.mallocLong(1);
            vkCreateDescriptorPool(CinnabarRenderer.device(), createInfo, null, longPtr);
            descriptorPool = longPtr.get(0);
        }
    }
    
    public static long allocDescriptorSet(long layout) {
        try (final var stack = MemoryStack.stackPush()) {
            final var setLayout = stack.mallocLong(1);
            setLayout.put(0, layout);
            
            final var allocInfo = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default();
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(setLayout);
    
            final var longPtr = stack.mallocLong(1);
            throwFromCode(vkAllocateDescriptorSets(CinnabarRenderer.device(), allocInfo, longPtr));
            return longPtr.get(0);
        }
    }
}
