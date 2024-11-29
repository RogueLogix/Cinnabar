package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.internal.util.MemoryRange;
import graphics.cinnabar.internal.vulkan.Destroyable;
import graphics.cinnabar.internal.vulkan.util.LiveHandles;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.annotations.NotNullDefault;
import graphics.cinnabar.lib.util.Pair;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties2;

@NotNullDefault
public class TransientCPUBufferAllocator implements Destroyable {
    private static final VkDevice device = CinnabarRenderer.device();

    private static final long TRANSIENT_BUFFER_ALLOCATION_SIZE = 16 * MagicMemorySizes.MiB;

    private final int memoryTypeIndex;

    private final LongArrayList memoryAllocations = new LongArrayList();
    private final LongArrayList allocationHostPtrs = new LongArrayList();
    private int currentAllocationIndex = 0;
    private long currentAllocationOffset = 0;

    public TransientCPUBufferAllocator() {
        try (var stack = MemoryStack.stackPush()) {
            int selectedMemoryType = -1;
            final var properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            vkGetPhysicalDeviceMemoryProperties2(device.getPhysicalDevice(), properties);
            final var memoryProperties = properties.memoryProperties();
            final var types = memoryProperties.memoryTypes();
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                types.position(i);
                final var propertyFlags = types.propertyFlags();
                final var requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                if ((propertyFlags & requiredFlags) == requiredFlags) {
                    selectedMemoryType = i;
                    break;
                }
            }
            if (selectedMemoryType == -1) {
                throw new IllegalStateException("Unable to find memory type, VK SPEC VIOLATION");
            }
            memoryTypeIndex = selectedMemoryType;
        }
    }

    @Override
    public void destroy() {
        for (int i = 0; i < memoryAllocations.size(); i++) {
            vkFreeMemory(device, memoryAllocations.getLong(i), null);
        }
    }

    @ThreadSafety.Any
    public VulkanBuffer.CPU alloc(long size) {
        return alloc(size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
    }

    @ThreadSafety.Any
    public VulkanBuffer.CPU alloc(long size, int usage) {
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
            final var handle = longPtr.get(0);

            final var memoryRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(device, handle, memoryRequirements);
            final var bufferAllocation = memAlloc(memoryRequirements);

            vkBindBufferMemory(device, handle, bufferAllocation.first().memoryHandle(), bufferAllocation.first().range().offset());

            LiveHandles.create(handle);
            return new VulkanBuffer.CPU(size, handle, bufferAllocation.first(), bufferAllocation.second());
        }
    }

    private Pair<VulkanMemoryAllocation, PointerWrapper> memAlloc(VkMemoryRequirements requirements) {
        final var requiredSize = requirements.size();
        final var requiredAlignment = requirements.alignment();

        for (int i = currentAllocationIndex; i < memoryAllocations.size(); i++) {

            final var startingOffset = (currentAllocationOffset + requiredAlignment - 1) & -requiredAlignment;
            final var endingOffset = startingOffset + requiredSize;

            if (endingOffset > TRANSIENT_BUFFER_ALLOCATION_SIZE) {
                currentAllocationIndex++;
                currentAllocationOffset = 0;
                continue;
            }

            currentAllocationOffset = startingOffset;
            break;
        }

        if (currentAllocationIndex == memoryAllocations.size()) {
            // ran out of memory, allocate more
            try (final var stack = MemoryStack.stackPush()) {
                final var allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
                allocInfo.allocationSize(TRANSIENT_BUFFER_ALLOCATION_SIZE);
                allocInfo.memoryTypeIndex(memoryTypeIndex);
                final var memoryHandlePtr = stack.mallocLong(1);
                final var hostPtr = stack.mallocPointer(1);
                vkAllocateMemory(device, allocInfo, null, memoryHandlePtr);
                vkMapMemory(device, memoryHandlePtr.get(0), 0, TRANSIENT_BUFFER_ALLOCATION_SIZE, 0, hostPtr);
                memoryAllocations.add(memoryHandlePtr.get(0));
                allocationHostPtrs.add(hostPtr.get(0));
            }
        }

        // free is a non-op because the pool is reset as a whole
        final var vkAlloc = new VulkanMemoryAllocation(memoryAllocations.getLong(currentAllocationIndex), new MemoryRange(currentAllocationOffset, requiredSize), a -> {});
        final var hostPtr = new PointerWrapper(allocationHostPtrs.getLong(currentAllocationIndex) + currentAllocationOffset, requiredSize);
        currentAllocationOffset += requiredSize;
        return new Pair<>(vkAlloc, hostPtr);
    }

    @ThreadSafety.Any
    public void reset() {
        currentAllocationIndex = 0;
        currentAllocationOffset = 0;
    }
}
