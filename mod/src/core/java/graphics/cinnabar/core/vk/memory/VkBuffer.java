package graphics.cinnabar.core.vk.memory;

import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.VulkanObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static org.lwjgl.vulkan.EXTDebugReport.VK_DEBUG_REPORT_OBJECT_TYPE_BUFFER_EXT;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK10.vkBindBufferMemory;

public class VkBuffer implements VulkanObject {
    
    private final CinnabarDevice device;
    
    public final long size;
    public final long handle;
    public final VkMemoryAllocation allocation;
    
    public VkBuffer(CinnabarDevice device, long size, int usageFlags, VkMemoryPool memoryPool) {
        this.device = device;
        this.size = size;
        try (final var stack = MemoryStack.stackPush()) {
            final var longPtr = stack.callocLong(1);
            final var createInfo = VkBufferCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            createInfo.pNext(0);
            createInfo.flags(0);
            createInfo.size(size);
            createInfo.usage(usageFlags);
            createInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.pQueueFamilyIndices(null);
            
            vkCreateBuffer(device.vkDevice, createInfo, null, longPtr);
            handle = longPtr.get(0);
            
            final var memoryRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device.vkDevice, handle, memoryRequirements);
            allocation = memoryPool.alloc(memoryRequirements);
            
            vkBindBufferMemory(device.vkDevice, handle, allocation.memoryHandle, allocation.range.offset());
            
            // TODO: bring live handles to the API package, or drop impl to core
//            LiveHandles.create(handle);
        }
    }
    
    @Override
    public long handle() {
        return handle;
    }
    
    @Override
    public int objectType() {
        return VK_DEBUG_REPORT_OBJECT_TYPE_BUFFER_EXT;
    }
    
    @Override
    public void destroy() {
        vkDestroyBuffer(device.vkDevice, handle, null);
        allocation.destroy();
//        LiveHandles.destroy(handle);
    }
}
