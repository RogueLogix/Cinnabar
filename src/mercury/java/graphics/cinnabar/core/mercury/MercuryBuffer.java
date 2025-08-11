package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.exceptions.NotImplemented;
import graphics.cinnabar.api.hg.HgBuffer;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.memory.PointerWrapper;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class MercuryBuffer extends MercuryObject implements HgBuffer {
    
    private final long size;
    private final long handle;
    private final long vmaAllocation;
    private final boolean deviceLocal;
    private final boolean mappable;
    
    public MercuryBuffer(MercuryDevice device, MemoryType memoryType, long size, long usage) {
        super(device);
        this.size = size;
        
        try (final var stack = MemoryStack.stackPush()) {
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
            switch (memoryType) {
                case MAPPABLE -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_HOST);
                    allocCreateInfo.flags(allocCreateInfo.flags() | VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT);
                    allocCreateInfo.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                }
                case MAPPABLE_PREF_DEVICE -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                    allocCreateInfo.flags(allocCreateInfo.flags() | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT);
                    allocCreateInfo.requiredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                    allocCreateInfo.preferredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                }
                case DEVICE_PREF_MAPPABLE -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                    allocCreateInfo.flags(allocCreateInfo.flags() | VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | VMA_ALLOCATION_CREATE_HOST_ACCESS_ALLOW_TRANSFER_INSTEAD_BIT);
                    allocCreateInfo.requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                    allocCreateInfo.preferredFlags(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
                }
                case DEVICE -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                    allocCreateInfo.requiredFlags(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
                }
                case AUTO_PREF_DEVICE -> {
                    allocCreateInfo.usage(VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                }
                default -> throw new NotImplemented();
            }
            
            final var vmaAllocationInfo = VmaAllocationInfo.calloc(stack);
            checkVkCode(vmaCreateBuffer(device.vmaAllocator(), createInfo, allocCreateInfo, bufferPtr, allocPtr, vmaAllocationInfo));
            vmaAllocation = allocPtr.get(0);
            handle = bufferPtr.get(0);
            
            final var flags = stack.ints(0);
            vmaGetMemoryTypeProperties(device.vmaAllocator(), vmaAllocationInfo.memoryType(), flags);
            deviceLocal = (flags.get(0) & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;
            mappable = (flags.get(0) & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
        }
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
    public boolean deviceLocal() {
        return deviceLocal;
    }
    
    @Override
    public boolean mappable() {
        return mappable;
    }
    
    @Override
    public PointerWrapper map() {
        try (final var stack = MemoryStack.stackPush()) {
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
