package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.util.MemoryRange;
import graphics.cinnabar.internal.vulkan.Destroyable;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import net.roguelogix.phosphophyllite.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.util.Collections;

import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceMemoryProperties2;

/**
 * you may notice that (given the name), this only handles GPU memory
 * all CPU memory is done via normal allocations, that are imported to VK and copied directly from
 * this removes any need for a staging buffer, and any need for me to do host buffer allocations
 */
@NonnullDefault
public class GPUMemoryAllocator implements Destroyable {
    private final VkDevice device = CinnabarRenderer.device();
    private final long VkAllocationSize;
    private final long MinimumSubAllocationSize;
    private final long MinimumSubAllocationBitmask;
    
    private final int MemoryType;
    private final int MemoryTypeBit;
    
    private final ReferenceArrayList<AllocationBlock> blocks = new ReferenceArrayList<>();
    private final ReferenceArrayList<AllocationBlock> dedicatedBlocks = new ReferenceArrayList<>();
    
    public GPUMemoryAllocator(long vkAllocationSize, long minimumSubAllocationSize) {
        VkAllocationSize = vkAllocationSize;
        MinimumSubAllocationSize = Util.roundUpPo2(minimumSubAllocationSize);
        MinimumSubAllocationBitmask = MinimumSubAllocationSize - 1;
        // this will almost always get memory type 0, but _meh_ thats fine
        try (var stack = MemoryStack.stackPush()) {
            // TODO: use budget to determine if i should compact the memory
            final var properties = VkPhysicalDeviceMemoryProperties2.calloc(stack).sType$Default();
            vkGetPhysicalDeviceMemoryProperties2(device.getPhysicalDevice(), properties);
            final var memoryProperties = properties.memoryProperties();
            int selectedMemory = -1;
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                memoryProperties.memoryTypes().position(i);
                final var propertyFlags = memoryProperties.memoryTypes().propertyFlags();
                final var requiredFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
                if ((propertyFlags & requiredFlags) == requiredFlags) {
                    selectedMemory = i;
                    break;
                }
            }
            if (selectedMemory == -1) {
                // every device _must_ have a VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT type
                // the spec requires it,
                // but just in case
                throw new IllegalStateException();
            }
            MemoryType = selectedMemory;
            MemoryTypeBit = 1 << MemoryType;
        }
    }
    
    @Override
    public void destroy() {
        blocks.forEach(AllocationBlock::destroy);
        dedicatedBlocks.forEach(AllocationBlock::destroy);
    }
    
    public VulkanMemoryAllocation alloc(VkMemoryRequirements2 memoryRequirements2) {
        return alloc(memoryRequirements2, false);
    }
    
    public VulkanMemoryAllocation alloc(VkMemoryRequirements2 memoryRequirements2, boolean dedicated) {
        if (memoryRequirements2.pNext() == 0) {
            return alloc(memoryRequirements2.memoryRequirements());
        }
        long currentStruct = memoryRequirements2.address();
        long dedicatedImage = 0;
        long dedicatedBuffer = 0;
        while (VkMemoryRequirements2.npNext(currentStruct) != 0) {
            final int structureType = VkMemoryRequirements2.nsType(currentStruct);
            if (structureType == VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO) {
                final var dedicatedAllocateInfo = VkMemoryDedicatedAllocateInfo.create(currentStruct);
                dedicatedImage = dedicatedAllocateInfo.image();
                dedicatedBuffer = dedicatedAllocateInfo.buffer();
                break;
            }
        }
        if ((memoryRequirements2.memoryRequirements().memoryTypeBits() & MemoryTypeBit) == 0) {
            throw new IllegalArgumentException("Incompatible memory type requirements");
        }
        final var memoryRequirements = memoryRequirements2.memoryRequirements();
        return alloc(memoryRequirements.size(), memoryRequirements.alignment(), dedicated, dedicatedImage, dedicatedBuffer);
    }
    
    public VulkanMemoryAllocation alloc(VkMemoryRequirements memoryRequirements) {
        return alloc(memoryRequirements, false);
    }
    
    public VulkanMemoryAllocation alloc(VkMemoryRequirements memoryRequirements, boolean dedicated) {
        if ((memoryRequirements.memoryTypeBits() & MemoryTypeBit) == 0) {
            throw new IllegalArgumentException("Incompatible memory type requirements");
        }
        return alloc(memoryRequirements.size(), memoryRequirements.alignment(), dedicated);
    }
    
    public VulkanMemoryAllocation alloc(long size, long alignment) {
        return alloc(size, alignment, false);
    }
    
    public VulkanMemoryAllocation alloc(long size, long alignment, boolean dedicated) {
        return alloc(size, alignment, dedicated, 0, 0);
    }
    
    /**
     * @param dedicated:       create an allocation used for no other object (but not necessarily VK's dedicated)
     * @param dedicatedImage:  implies 'dedicated' above, creates VK dedicated allocation for given image,
     * @param dedicatedBuffer: implies 'dedicated' above, creates VK dedicated allocation for given buffer,
     *                         one of dedicatedImage and dedicatedBuffer MUST be NULL
     */
    public VulkanMemoryAllocation alloc(long size, long alignment, boolean dedicated, long dedicatedImage, long dedicatedBuffer) {
        final var roundedUpSize = (size + MinimumSubAllocationBitmask) & ~MinimumSubAllocationBitmask;
        // dont allow alignment to smaller than the minimum allocation size
        // this ensures that i cant have a hole of a size smaller than the minimum allocation size
        final var roundedUpAlignment = Math.max(alignment, MinimumSubAllocationSize);
        
        try (final var stack = MemoryStack.stackPush()) {
            @Nullable
            VkMemoryDedicatedAllocateInfo dedicatedAllocateInfo = null;
            if (dedicatedImage != 0 || dedicatedBuffer != 0) {
                dedicated = true;
                if (dedicatedImage != 0 && dedicatedBuffer != 0) {
                    throw new IllegalArgumentException("Dedicated allocation can only be created for a single vulkan object");
                }
                dedicatedAllocateInfo = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default();
                dedicatedAllocateInfo.image(dedicatedImage);
                dedicatedAllocateInfo.buffer(dedicatedBuffer);
            }
            if (roundedUpSize > VkAllocationSize) {
                dedicated = true;
            }
            if (!dedicated) {
                // if not dedicated, try and find an existing block with enough space
                for (AllocationBlock block : blocks) {
                    if (block.canFit(roundedUpSize, roundedUpAlignment)) {
                        return block.alloc(roundedUpSize, roundedUpAlignment);
                    }
                }
            }
            // either dedicated or no other block can fit it, make a new one
            final var longPtr = stack.mallocLong(1);
            final var allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
            allocInfo.memoryTypeIndex(MemoryType);
            if (dedicated) {
                allocInfo.allocationSize(size);
            } else {
                allocInfo.allocationSize(VkAllocationSize);
            }
            if (dedicatedAllocateInfo != null) {
                allocInfo.pNext(dedicatedAllocateInfo);
            }
            throwFromCode(vkAllocateMemory(device, allocInfo, null, longPtr));
            final var newBlock = new AllocationBlock(this, longPtr.get(0), allocInfo.allocationSize(), dedicated);
            if (dedicated) {
                dedicatedBlocks.add(newBlock);
            } else {
                blocks.add(newBlock);
            }
            return newBlock.alloc(roundedUpSize, roundedUpAlignment);
        }
    }
    
    public void free(VulkanMemoryAllocation allocation) {
        // TODO: use privateData to have the memory object itself know what memory block its attached to
        //       notable, because the memoryHandle doesnt change, i can update this if i shuffle things around
        final var handle = allocation.memoryHandle();
        for (AllocationBlock dedicatedBlock : dedicatedBlocks) {
            if (dedicatedBlock.handle == handle) {
                dedicatedBlocks.remove(dedicatedBlock);
                dedicatedBlock.destroy();
                return;
            }
        }
        for (AllocationBlock block : blocks) {
            if (block.handle == handle) {
                block.free(allocation);
                return;
            }
        }
    }
    
    private static class AllocationBlock implements Destroyable {
        
        private final ObjectArrayList<MemoryRange> freeAllocations = new ObjectArrayList<>() {
            @Override
            public boolean add(@Nullable MemoryRange allocation) {
                if (allocation == null) {
                    return false;
                }
                int index = Collections.binarySearch(this, allocation);
                if (index < 0) {
                    index = ~index;
                    super.add(index, allocation);
                } else {
                    super.set(index, allocation);
                }
                return true;
            }
        };
        
        public final GPUMemoryAllocator allocator;
        private final long handle;
        private final long size;
        private final boolean dedicated;
        
        private AllocationBlock(GPUMemoryAllocator allocator, long handle, long size, boolean dedicated) {
            this.allocator = allocator;
            this.handle = handle;
            this.size = size;
            this.dedicated = dedicated;
            freeAllocations.add(new MemoryRange(0, size));
        }
        
        @Override
        public void destroy() {
            vkFreeMemory(allocator.device, handle, null);
        }
        
        private boolean canFit(long size, long alignment) {
            for (MemoryRange freeAlloc : freeAllocations) {
                final long nextValidAlignment = (freeAlloc.offset() + (alignment - 1)) & (-alignment);
                final long alignmentWaste = nextValidAlignment - freeAlloc.offset();
                if (freeAlloc.size() - alignmentWaste >= size) {
                    // fits
                    return true;
                }
            }
            // dont fit
            return false;
        }
        
        public boolean owns(VulkanMemoryAllocation allocation) {
            return allocation.memoryHandle() == this.handle;
        }
        
        private synchronized VulkanMemoryAllocation alloc(long size, long align) {
            if (dedicated && this.size != size) {
                throw new IllegalStateException();
            }
            @Nullable
            MemoryRange allocatedSpace = null;
            for (MemoryRange freeAllocation : freeAllocations) {
                @Nullable var alloc = attemptAllocInSpace(freeAllocation, size, align);
                if (alloc != null) {
                    allocatedSpace = alloc;
                    freeAllocations.remove(freeAllocation);
                    break;
                }
            }
            if (allocatedSpace == null) {
                throw new IllegalStateException("Failed to make allocation in block");
            }
            return new VulkanMemoryAllocation(handle, new MemoryRange(allocatedSpace.offset(), size), allocator);
        }
        
        @Nullable
        private MemoryRange attemptAllocInSpace(MemoryRange freeAlloc, long size, long alignment) {
            // next value guaranteed to be at *most* one less than the next alignment, then bit magic because powers of two to round down without a divide
            final long nextValidAlignment = (freeAlloc.offset() + (alignment - 1)) & (-alignment);
            final long alignmentWaste = nextValidAlignment - freeAlloc.offset();
            if (freeAlloc.size() - alignmentWaste < size) {
                // wont fit, *neeeeeeeeeeeext*
                return null;
            }
            if (alignmentWaste > 0) {
                final var newAllocs = freeAlloc.split(alignmentWaste);
                // not concurrent modification because this will always return
                freeAllocations.add(newAllocs.first());
                freeAlloc = newAllocs.second();
                
                int index = freeAllocations.indexOf(newAllocs.first());
                collapseFreeAllocationWithNext(index - 1);
                collapseFreeAllocationWithNext(index);
            }
            if (freeAlloc.size() > size) {
                final var newAllocs = freeAlloc.split(size);
                // not concurrent modification because this will always return
                freeAlloc = newAllocs.first();
                freeAllocations.add(newAllocs.second());
                int index = freeAllocations.indexOf(newAllocs.second());
                collapseFreeAllocationWithNext(index - 1);
                collapseFreeAllocationWithNext(index);
            }
            
            return freeAlloc;
        }
        
        public synchronized void free(VulkanMemoryAllocation allocation) {
            if (!owns(allocation)) {
                return;
            }
            if (dedicated) {
                throw new IllegalStateException();
            }
            var info = new MemoryRange(allocation.range().offset(), allocation.range().size());
            freeAllocations.add(info);
            var index = freeAllocations.indexOf(info);
            collapseFreeAllocationWithNext(index - 1);
            collapseFreeAllocationWithNext(index);
        }
        
        private void collapseFreeAllocationWithNext(int freeAllocationIndex) {
            if (freeAllocationIndex < 0 || freeAllocationIndex >= freeAllocations.size() - 1) {
                return;
            }
            var allocA = freeAllocations.get(freeAllocationIndex);
            var allocB = freeAllocations.get(freeAllocationIndex + 1);
            if (allocA.offset() + allocA.size() == allocB.offset()) {
                // neighboring allocations, collapse them
                freeAllocations.remove(freeAllocationIndex + 1);
                freeAllocations.remove(freeAllocationIndex);
                freeAllocations.add(new MemoryRange(allocA.offset(), allocA.size() + allocB.size()));
            }
        }
    }
}
