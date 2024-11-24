package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.vulkan.Destroyable;
import graphics.cinnabar.internal.vulkan.util.LiveHandles;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static org.lwjgl.vulkan.VK10.*;

public class VulkanBuffer implements Destroyable {
    private static final VkDevice device = CinnabarRenderer.device();
    
    public final long size;
    public final long handle;
    private final VulkanMemoryAllocation bufferAllocation;
    
    public VulkanBuffer(long size, int usage) {
        this.size = size;
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.callocLong(1);
            final var createInfo = VkBufferCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            createInfo.pNext(0);
            createInfo.flags(0);
            createInfo.size(size);
            createInfo.usage(usage);
            createInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.pQueueFamilyIndices(null);
            
            vkCreateBuffer(device, createInfo, null, longPtr);
            handle = longPtr.get(0);
            
            final var memoryRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, handle, memoryRequirements);
            bufferAllocation = CinnabarRenderer.GPUMemoryAllocator.alloc(memoryRequirements);
            
            vkBindBufferMemory(device, handle, bufferAllocation.memoryHandle(), bufferAllocation.range().offset());
            
            LiveHandles.create(handle);
        }
    }
    
    @Override
    public void destroy() {
        vkDestroyBuffer(device, handle, null);
        bufferAllocation.destroy();
        LiveHandles.destroy(handle);
    }
    
}