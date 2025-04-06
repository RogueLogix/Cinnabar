package graphics.cinnabar.core.vk.memory.pools;

import graphics.cinnabar.api.memory.LeakDetection;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkMemoryAllocation;
import graphics.cinnabar.core.vk.memory.VkMemoryPool;
import graphics.cinnabar.lib.util.MathUtil;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryDedicatedAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkMemoryRequirements2;

import java.util.Collections;
import java.util.Objects;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;

public class PersistentPool implements VkMemoryPool.Dedicated, VkMemoryPool.Req2.Dedicated {
    
    private final long MINIMUM_SUBALLOC_SIZE = MagicMemorySizes.KiB * 4;
    
    private final CinnabarDevice device;
    
    protected final int memoryType;
    protected final int memoryTypeBit;
    protected final long blockSize;
    protected long liveAllocations = 0;
    
    protected final Long2ReferenceMap<@Nullable AllocationBlock> memoryToBlockMap = new Long2ReferenceOpenHashMap<>();
    private final ReferenceArrayList<AllocationBlock> blocks = new ReferenceArrayList<>();
    private final ReferenceArrayList<AllocationBlock> freeBlocks = new ReferenceArrayList<>();
    private final ReferenceArrayList<AllocationBlock> dedicatedBlocks = new ReferenceArrayList<>();
    
    public PersistentPool(CinnabarDevice device, int memoryType, long blockSize) {
        this.device = device;
        this.memoryType = memoryType;
        this.memoryTypeBit = 1 << memoryType;
        this.blockSize = Math.max(MINIMUM_SUBALLOC_SIZE, MathUtil.roundUpPo2(blockSize));
    }
    
    @Override
    public void destroy() {
        blocks.forEach(AllocationBlock::destroy);
        freeBlocks.forEach(AllocationBlock::destroy);
        dedicatedBlocks.forEach(AllocationBlock::destroy);
    }
    
    public VkMemoryAllocation alloc(VkMemoryRequirements2 memoryRequirements2) {
        return alloc(memoryRequirements2, false);
    }
    
    public VkMemoryAllocation alloc(VkMemoryRequirements2 memoryRequirements2, boolean dedicated) {
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
        if ((memoryRequirements2.memoryRequirements().memoryTypeBits() & memoryTypeBit) == 0) {
            throw new IllegalArgumentException("Incompatible memory type requirements");
        }
        final var memoryRequirements = memoryRequirements2.memoryRequirements();
        return alloc(memoryRequirements.size(), memoryRequirements.alignment(), dedicated, dedicatedImage, dedicatedBuffer);
    }
    
    public VkMemoryAllocation alloc(VkMemoryRequirements memoryRequirements) {
        return alloc(memoryRequirements, false);
    }
    
    @Override
    public long totalAllocatedFromVulkan() {
        return blockSize * (blocks.size() + freeBlocks.size()) + dedicatedBlocks.stream().mapToLong(block -> block.size).sum();
    }
    
    @Override
    public long liveAllocated() {
        return liveAllocations;
    }
    
    public VkMemoryAllocation alloc(VkMemoryRequirements memoryRequirements, boolean dedicated) {
        if ((memoryRequirements.memoryTypeBits() & memoryTypeBit) == 0) {
            throw new IllegalArgumentException("Incompatible memory type requirements");
        }
        return alloc(memoryRequirements.size(), memoryRequirements.alignment(), dedicated, 0, 0);
    }
    
    public VkMemoryAllocation alloc(long size, long alignment, boolean dedicated, long dedicatedImage, long dedicatedBuffer) {
        final var roundedUpSize = (size + (MINIMUM_SUBALLOC_SIZE - 1)) & -MINIMUM_SUBALLOC_SIZE;
        // dont allow alignment to smaller than the minimum allocation size
        // this ensures that i cant have a hole of a size smaller than the minimum allocation size
        final var roundedUpAlignment = Math.max(alignment, MINIMUM_SUBALLOC_SIZE);
        
        if (dedicatedImage != 0 || dedicatedBuffer != 0) {
            dedicated = true;
        }
        if (!dedicated) {
            // if not dedicated, try and find an existing block with enough space
            for (AllocationBlock block : blocks) {
                if (block.canFit(roundedUpSize, roundedUpAlignment)) {
                    return block.alloc(roundedUpSize, roundedUpAlignment);
                }
            }
            if (!freeBlocks.isEmpty()) {
                final var newBlock = freeBlocks.pop();
                memoryToBlockMap.put(newBlock.handle, newBlock);
                blocks.add(newBlock);
                if (!newBlock.canFit(roundedUpSize, roundedUpAlignment)) {
                    throw new IllegalStateException();
                }
                return newBlock.alloc(roundedUpSize, roundedUpAlignment);
            }
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            @Nullable
            VkMemoryDedicatedAllocateInfo dedicatedAllocateInfo = null;
            if (dedicatedImage != 0 || dedicatedBuffer != 0) {
                if (dedicatedImage != 0 && dedicatedBuffer != 0) {
                    throw new IllegalArgumentException("Dedicated allocation can only be created for a single vulkan object");
                }
                dedicatedAllocateInfo = VkMemoryDedicatedAllocateInfo.calloc(stack).sType$Default();
                dedicatedAllocateInfo.image(dedicatedImage);
                dedicatedAllocateInfo.buffer(dedicatedBuffer);
            }
            if (roundedUpSize > blockSize) {
                dedicated = true;
            }
            
            // either dedicated or no other block can fit it, make a new one
            final var longPtr = stack.mallocLong(1);
            final var allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
            allocInfo.memoryTypeIndex(memoryType);
            if (dedicated) {
                allocInfo.allocationSize(roundedUpSize);
            } else {
                allocInfo.allocationSize(blockSize);
            }
            if (dedicatedAllocateInfo != null) {
                allocInfo.pNext(dedicatedAllocateInfo);
            }
            checkVkCode(vkAllocateMemory(device.vkDevice, allocInfo, null, longPtr));
            final var memoryHandle = longPtr.get(0);
            long hostPtr = 0;
            if (this instanceof CPU) {
                final var hostPtrPtr = stack.callocPointer(1);
                vkMapMemory(device.vkDevice, memoryHandle, 0, allocInfo.allocationSize(), 0, hostPtrPtr);
                hostPtr = hostPtrPtr.get(0);
            }
            final var newBlock = new AllocationBlock(this, hostPtr, memoryHandle, allocInfo.allocationSize(), dedicated);
            memoryToBlockMap.put(memoryHandle, newBlock);
            if (dedicated) {
                dedicatedBlocks.add(newBlock);
            } else {
                blocks.add(newBlock);
            }
            return newBlock.alloc(roundedUpSize, roundedUpAlignment);
        }
    }
    
    protected void free(VkMemoryAllocation allocation) {
        @Nullable
        final var block = memoryToBlockMap.get(allocation.memoryHandle);
        if (block != null) {
            if (block.dedicated) {
                memoryToBlockMap.remove(block.handle);
                dedicatedBlocks.remove(block);
                block.destroy();
                liveAllocations -= allocation.range.size();
            } else {
                block.free(allocation);
                // non-dedicated blocks are never freed back to VK, even if they no longer have any live allocations in them
            }
        }
    }
    
    private void free(AllocationBlock allocationBlock) {
        assert !allocationBlock.dedicated;
        memoryToBlockMap.remove(allocationBlock.handle);
        blocks.remove(allocationBlock);
        freeBlocks.add(allocationBlock);
        // keep at most 1 free block for every 16 that are allocated, ~6% free
        // also won't free the last block
        while (freeBlocks.size() > (blocks.size() / 16)) {
            freeBlocks.pop().destroy();
        }
    }
    
    @Override
    public void setVulkanName(String name) {
        // TODO:
    }
    
    public static class CPU extends PersistentPool implements VkMemoryPool.CPU, VkMemoryPool.Dedicated.CPU, Req2.Dedicated.CPU {
        public CPU(CinnabarDevice device, int memoryType, long blockSize) {
            super(device, memoryType, blockSize);
        }
        
        private VkMemoryAllocation.CPU attachHostPtr(VkMemoryAllocation baseAlloc) {
            final var memoryBlock = Objects.requireNonNull(memoryToBlockMap.get(baseAlloc.memoryHandle));
            return new VkMemoryAllocation.CPU(baseAlloc, new PointerWrapper(memoryBlock.hostPtr + baseAlloc.range.offset(), baseAlloc.range.size()));
        }
        
        @Override
        public VkMemoryAllocation.CPU alloc(VkMemoryRequirements2 memoryRequirements2) {
            return attachHostPtr(super.alloc(memoryRequirements2));
        }
        
        @Override
        public VkMemoryAllocation.CPU alloc(VkMemoryRequirements2 memoryRequirements2, boolean dedicated) {
            return attachHostPtr(super.alloc(memoryRequirements2, dedicated));
        }
        
        @Override
        public VkMemoryAllocation.CPU alloc(VkMemoryRequirements memoryRequirements) {
            return attachHostPtr(super.alloc(memoryRequirements));
        }
        
        @Override
        public VkMemoryAllocation.CPU alloc(VkMemoryRequirements memoryRequirements, boolean dedicated) {
            return attachHostPtr(super.alloc(memoryRequirements, dedicated));
        }
        
    }
    
    protected static class AllocationBlock implements Destroyable {
        
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
        
        
        public final PersistentPool allocator;
        private final long hostPtr;
        private final long handle;
        private final long size;
        private final boolean dedicated;
        
        private AllocationBlock(PersistentPool allocator, long hostPtr, long handle, long size, boolean dedicated) {
            this.allocator = allocator;
            this.hostPtr = hostPtr;
            this.handle = handle;
            this.size = size;
            this.dedicated = dedicated;
            freeAllocations.add(new MemoryRange(0, size));
            LeakDetection.addAccessibleLocation(new PointerWrapper(hostPtr, size));
        }
        
        @Override
        public void destroy() {
            LeakDetection.removeAccessibleLocation(new PointerWrapper(hostPtr, size));
            vkFreeMemory(allocator.device.vkDevice, handle, null);
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
        
        public boolean owns(VkMemoryAllocation allocation) {
            return allocation.memoryHandle == this.handle;
        }
        
        private synchronized VkMemoryAllocation alloc(long size, long align) {
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
            allocator.liveAllocations += size;
            return new VkMemoryAllocation(handle, new MemoryRange(allocatedSpace.offset(), size), allocator::free);
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
        
        public synchronized void free(VkMemoryAllocation allocation) {
            if (!owns(allocation)) {
                return;
            }
            if (dedicated) {
                throw new IllegalStateException();
            }
            allocator.liveAllocations -= allocation.range.size();
            var info = new MemoryRange(allocation.range.offset(), allocation.range.size());
            freeAllocations.add(info);
            var index = freeAllocations.indexOf(info);
            collapseFreeAllocationWithNext(index - 1);
            collapseFreeAllocationWithNext(index);
            if (freeAllocations.size() == 1 && freeAllocations.getFirst().size() == size) {
                // entire block has been freed, release it back to vulkan
                allocator.free(this);
            }
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
