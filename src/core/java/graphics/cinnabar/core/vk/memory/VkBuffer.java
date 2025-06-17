package graphics.cinnabar.core.vk.memory;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import graphics.cinnabar.api.CinnabarAPI;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.api.vk.VulkanObject;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.CinnabarConfig.defaultVal;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.EXTDebugReport.VK_DEBUG_REPORT_OBJECT_TYPE_BUFFER_EXT;
import static org.lwjgl.vulkan.VK10.*;

public class VkBuffer implements VulkanObject {
    
    private final CinnabarDevice device;
    
    public static final int CINNABAR_BUFFER_USAGE_TRANSIENT = defaultVal(1);
    
    public final long size;
    public final long handle;
    public final long vmaAllocation;
    public final VmaAllocationInfo allocationInfo = VmaAllocationInfo.calloc();
    
    public VkBuffer(CinnabarDevice device, long size, int vkUsageFlags, IntIntImmutablePair memoryType) {
        this.device = device;
        this.size = size;
        try (final var stack = MemoryStack.stackPush()) {
            final var bufferPtr = stack.callocLong(1);
            final var allocPtr = stack.callocPointer(1);
            final var createInfo = VkBufferCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            createInfo.pNext(0);
            createInfo.flags(0);
            createInfo.size(size);
            createInfo.usage(vkUsageFlags);
            createInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.pQueueFamilyIndices(null);
            
            final var mappableMemory = (memoryType.rightInt() & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) != 0;
            final var allocCreateInfo = VmaAllocationCreateInfo.calloc(stack);
            allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO);
            allocCreateInfo.flags(allocCreateInfo.flags() | (mappableMemory ? (VMA_ALLOCATION_CREATE_MAPPED_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT) : 0));
            allocCreateInfo.memoryTypeBits(1 << memoryType.leftInt());
            
            checkVkCode(vmaCreateBuffer(device.vmaAllocator, createInfo, allocCreateInfo, bufferPtr, allocPtr, allocationInfo));
            vmaAllocation = allocPtr.get(0);
            handle = bufferPtr.get(0);
            
            if (mappableMemory && allocationInfo.pMappedData() == 0) {
                throw new IllegalStateException();
            }
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
        vmaDestroyBuffer(device.vmaAllocator, handle, vmaAllocation);
        allocationInfo.free();
    }
    
    public Slice whole() {
        return new Slice(new MemoryRange(0, size));
    }
    
    public Slice slice(MemoryRange range) {
        if (CinnabarAPI.DEBUG_MODE) {
            if (range.offset() < 0) {
                throw new IllegalArgumentException("Attempt to slice buffer before beginning");
            }
            if ((range.offset() + range.size()) > size) {
                throw new IllegalArgumentException("Attempt to slice buffer after end");
            }
        }
        return new Slice(range);
    }
    
    public class Slice {
        public final MemoryRange range;
        
        public Slice(MemoryRange range) {
            this.range = range;
        }
        
        public VkBuffer buffer() {
            return VkBuffer.this;
        }
        
        public Slice slice(MemoryRange range) {
            if (CinnabarAPI.DEBUG_MODE) {
                if (range.offset() < 0) {
                    throw new IllegalArgumentException("Attempt to slice buffer before beginning");
                }
                if ((range.offset() + range.size()) > this.range.size()) {
                    throw new IllegalArgumentException("Attempt to slice buffer after end");
                }
            }
            return new Slice(new MemoryRange(this.range.offset() + range.offset(), range.size()));
        }
        
        public Slice slice(GpuBufferSlice slice) {
            return slice(new MemoryRange(slice.offset(), slice.length()));
        }
    }
}
