package graphics.cinnabar.core.vk.memory.pools;

import graphics.cinnabar.api.memory.LeakDetection;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkMemoryAllocation;
import graphics.cinnabar.core.vk.memory.VkMemoryPool;
import it.unimi.dsi.fastutil.longs.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static org.lwjgl.vulkan.VK10.*;

public class TransientPool implements VkMemoryPool, VkMemoryPool.Transient {
    
    protected final CinnabarDevice device;
    
    protected final int memoryType;
    protected final long blockSize;
    
    protected final LongArrayList memoryBlocks = new LongArrayList();
    private int currentMemoryBlock = 0;
    private long currentMemoryBlockOffset = 0;
    
    public TransientPool(CinnabarDevice device, int memoryType, long blockSize) {
        this.device = device;
        this.memoryType = memoryType;
        this.blockSize = blockSize;
    }
    
    @Override
    public void destroy() {
        for (int i = 0; i < memoryBlocks.size(); i++) {
            vkFreeMemory(device.vkDevice, memoryBlocks.getLong(i), null);
        }
    }
    
    @Override
    public void setVulkanName(String name) {
        // TODO:
    }
    
    protected LongObjectPair<MemoryRange> internalAlloc(VkMemoryRequirements requirements) {
        final var requiredSize = requirements.size();
        final var requiredAlignment = requirements.alignment();
        
        for (int i = currentMemoryBlock; i < memoryBlocks.size(); i++) {
            
            final var startingOffset = (currentMemoryBlockOffset + requiredAlignment - 1) & -requiredAlignment;
            final var endingOffset = startingOffset + requiredSize;
            
            if (endingOffset > blockSize) {
                currentMemoryBlock++;
                currentMemoryBlockOffset = 0;
                continue;
            }
            
            currentMemoryBlockOffset = startingOffset;
            break;
        }
        
        if (currentMemoryBlock == memoryBlocks.size()) {
            allocBlock();
        }
        
        final var allocation = new LongObjectImmutablePair<>(memoryBlocks.getLong(currentMemoryBlock), new MemoryRange(currentMemoryBlockOffset, requiredSize));
        currentMemoryBlockOffset += requiredSize;
        return allocation;
    }
    
    protected void allocBlock() {
        try (final var stack = MemoryStack.stackPush()) {
            final var allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
            allocInfo.allocationSize(blockSize);
            allocInfo.memoryTypeIndex(memoryType);
            final var memoryHandlePtr = stack.mallocLong(1);
            vkAllocateMemory(device.vkDevice, allocInfo, null, memoryHandlePtr);
            memoryBlocks.add(memoryHandlePtr.get(0));
        }
    }
    
    @Override
    public VkMemoryAllocation alloc(VkMemoryRequirements memoryRequirements) {
        final var internalAlloc = internalAlloc(memoryRequirements);
        return new VkMemoryAllocation(internalAlloc.firstLong(), internalAlloc.second(), null);
    }
    
    @Override
    public long totalAllocatedFromVulkan() {
        return memoryBlocks.size() * blockSize;
    }
    
    @Override
    public long liveAllocated() {
        return currentMemoryBlock * blockSize + currentMemoryBlockOffset;
    }
    
    @Override
    public void reset() {
        currentMemoryBlock = 0;
        currentMemoryBlockOffset = 0;
    }
    
    public static class CPU extends TransientPool implements VkMemoryPool.Transient.CPU {
        
        private final Long2ReferenceMap<PointerWrapper> mappedMemory = new Long2ReferenceOpenHashMap<>();
        
        public CPU(CinnabarDevice device, int memoryType, long blockSize) {
            super(device, memoryType, blockSize);
        }
        
        @Override
        public void destroy() {
            super.destroy();
            mappedMemory.forEach((a, ptr) -> LeakDetection.removeAccessibleLocation(ptr));
        }
        
        @Override
        protected void allocBlock() {
            super.allocBlock();
            try (final var stack = MemoryStack.stackPush()) {
                final var hostPtr = stack.mallocPointer(1);
                final var memoryHandle = memoryBlocks.peekLong(0);
                vkMapMemory(device.vkDevice, memoryHandle, 0, blockSize, 0, hostPtr);
                final var mappedRange = new PointerWrapper(hostPtr.get(0), blockSize);
                LeakDetection.addAccessibleLocation(mappedRange);
                mappedMemory.put(memoryHandle, new PointerWrapper(hostPtr.get(0), blockSize));
            }
        }
        
        @Override
        public VkMemoryAllocation.CPU alloc(VkMemoryRequirements memoryRequirements) {
            final var internalAlloc = internalAlloc(memoryRequirements);
            return new VkMemoryAllocation.CPU(internalAlloc.firstLong(), internalAlloc.second(), null, mappedMemory.get(internalAlloc.first()).slice(internalAlloc.second().offset(), internalAlloc.second().size()));
        }
    }
}
