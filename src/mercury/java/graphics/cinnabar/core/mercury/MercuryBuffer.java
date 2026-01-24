package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.hg.HgBuffer;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.memory.PointerWrapper;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static graphics.cinnabar.core.mercury.Mercury.RENDERDOC_ATTACHED;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryBuffer extends MercuryObject implements HgBuffer {
    
    private final MemoryType memoryType;
    private final long size;
    private final long handle;
    private final long vmaAllocation;
    
    @Nullable
    public static MercuryBuffer attemptCreate(MercuryDevice device, MemoryRequest memoryRequest, long size, long usage) {
        try (final var stack = memoryStack().push()) {
            final var bufferPtr = stack.callocLong(1);
            final var allocPtr = stack.callocPointer(1);
            final var createInfo = VkBufferCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            createInfo.pNext(0);
            createInfo.size(size);
            createInfo.usage(Math.toIntExact(usage));
            createInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            createInfo.pQueueFamilyIndices(null);
            
            final var allocCreateInfo = VmaAllocationCreateInfo.calloc(stack);
            allocCreateInfo.flags(VMA_ALLOCATION_CREATE_WITHIN_BUDGET_BIT);
            
            switch (memoryRequest) {
                case CPU -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
                    allocCreateInfo.flags(allocCreateInfo.flags() | VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
                    allocCreateInfo.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    allocCreateInfo.preferredFlags(VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
                    allocCreateInfo.memoryTypeBits(device.allowedHostBufferMemoryBits);
                }
                case MAPPABLE_PREF_GPU -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                    allocCreateInfo.flags(allocCreateInfo.flags() | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
                    allocCreateInfo.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    allocCreateInfo.preferredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                    // because this buffer is allowed on both host and device, both's memory types can be used
                    allocCreateInfo.memoryTypeBits(device.allowedHostBufferMemoryBits | device.allowedDeviceBufferMemoryBits);
                }
                case GPU -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                    allocCreateInfo.requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                    allocCreateInfo.memoryTypeBits(device.allowedDeviceBufferMemoryBits);
                    if (!RENDERDOC_ATTACHED) {
                        allocCreateInfo.flags(allocCreateInfo.flags() | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_ALLOW_TRANSFER_INSTEAD_BIT);
                        allocCreateInfo.preferredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    }
                }
                default -> throw new NotImplemented();
            }
            
            
            final var vmaAllocationInfo = VmaAllocationInfo.calloc(stack);
            final var returnCode = vmaCreateBuffer(device.vmaAllocator(), createInfo, allocCreateInfo, bufferPtr, allocPtr, vmaAllocationInfo);
            if (returnCode != VK_SUCCESS) {
                return null;
            }
            
            final var flags = stack.ints(0);
            vmaGetMemoryTypeProperties(device.vmaAllocator(), vmaAllocationInfo.memoryType(), flags);
            final var deviceLocal = (flags.get(0) & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;
            final var mappable = (flags.get(0) & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
            
            final MemoryType memoryType;
            if (device.UMA) {
                assert deviceLocal;
                assert mappable;
                memoryType = MemoryType.UMA;
            } else if (!deviceLocal) {
                assert mappable;
                memoryType = MemoryType.CPU;
            } else {
                memoryType = mappable && !RENDERDOC_ATTACHED ? MemoryType.GPU_MAPPABLE : MemoryType.GPU;
            }
            
            return new MercuryBuffer(device, memoryType, size, bufferPtr.get(0), allocPtr.get(0));
        }
        
    }
    
    private MercuryBuffer(MercuryDevice device, MemoryType memoryType, long size, long handle, long vmaAllocation) {
        super(device);
        this.memoryType = memoryType;
        this.size = size;
        this.handle = handle;
        this.vmaAllocation = vmaAllocation;
    }
    
    @Override
    public void destroy() {
        vmaDestroyBuffer(device.vmaAllocator(), handle, vmaAllocation);
    }
    
    public long vkBuffer() {
        return handle;
    }
    
    @Override
    public long size() {
        return size;
    }
    
    @Override
    public MemoryType memoryType() {
        return memoryType;
    }
    
    @Override
    public PointerWrapper map() {
        try (final var stack = memoryStack().push()) {
            final var pointerReturn = stack.pointers(0);
            checkVkCode(vmaMapMemory(device.vmaAllocator(), vmaAllocation, pointerReturn));
            return new PointerWrapper(pointerReturn.get(0), size);
        }
    }
    
    @Override
    public void unmap() {
        vmaUnmapMemory(device.vmaAllocator(), vmaAllocation);
    }
    
    @Override
    public View view(HgFormat format, long offset, long size) {
        return new MercuryBufferView(this, format, offset, size);
    }
}
