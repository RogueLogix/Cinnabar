package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.internal.vulkan.Destroyable;
import graphics.cinnabar.internal.vulkan.util.LiveHandles;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryRequirements;

import java.util.function.Function;

import static org.lwjgl.vulkan.VK10.*;

public class VulkanBuffer implements Destroyable {
    private static final VkDevice device = CinnabarRenderer.device();

    public final long size;
    public final long handle;
    private final VulkanMemoryAllocation bufferAllocation;

    public VulkanBuffer(long size, int usage) {
        this(size, usage, CinnabarRenderer.GPUMemoryAllocator::alloc);
    }

    public VulkanBuffer(long size, int usage, Function<VkMemoryRequirements, VulkanMemoryAllocation> allocationFunction) {
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
            bufferAllocation = allocationFunction.apply(memoryRequirements);

            vkBindBufferMemory(device, handle, bufferAllocation.memoryHandle(), bufferAllocation.range().offset());

            LiveHandles.create(handle);
        }
    }

    protected VulkanBuffer(long size, long handle, VulkanMemoryAllocation bufferAllocation) {
        this.size = size;
        this.handle = handle;
        this.bufferAllocation = bufferAllocation;
    }

    @Override
    public void destroy() {
        vkDestroyBuffer(device, handle, null);
        bufferAllocation.destroy();
        LiveHandles.destroy(handle);
    }


    public static class CPU extends VulkanBuffer {
        public final PointerWrapper hostPtr;

        CPU(long size, long handle, VulkanMemoryAllocation allocation, PointerWrapper hostPtr) {
            super(size, handle, allocation);
            this.hostPtr = hostPtr;
        }
    }
}