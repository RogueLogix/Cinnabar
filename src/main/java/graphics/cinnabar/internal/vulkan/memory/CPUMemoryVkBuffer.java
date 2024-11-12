package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.memory.PointerWrapper;
import graphics.cinnabar.internal.vulkan.Destroyable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.vkGetMemoryHostPointerPropertiesEXT;
import static org.lwjgl.vulkan.VK10.*;

public record CPUMemoryVkBuffer(long bufferHandle, PointerWrapper hostPtr, long vkImportedMemory, boolean ownsHostAllocation) implements Destroyable {
    
    public static CPUMemoryVkBuffer alloc(long size) {
        final var hostPtrAlignment = CinnabarRenderer.hostPtrAlignment();
        final var roundedUpSize = (size + (hostPtrAlignment - 1)) & -hostPtrAlignment;
        final var hostPtr = PointerWrapper.alloc(roundedUpSize, hostPtrAlignment);
        return create(hostPtr, true);
    }
    
    public static CPUMemoryVkBuffer create(long ptr, long size) {
        return create(new PointerWrapper(ptr, size), false);
    }
    
    public static CPUMemoryVkBuffer create(PointerWrapper hostPtr, boolean ownsHostAllocation) {
        final var device = CinnabarRenderer.device();
        
        if ((hostPtr.pointer() & (CinnabarRenderer.hostPtrAlignment() - 1)) != 0) {
            throw new IllegalArgumentException("Imported pointer must be aligned to hostPtrAlignment");
        }
        if ((hostPtr.size() & (CinnabarRenderer.hostPtrAlignment() - 1)) != 0) {
            throw new IllegalArgumentException("Imported pointer size must be multiple of hostPtrAlignment");
        }
        
        try (final var stack = MemoryStack.stackPush()) {
            
            final var handlePtr = stack.mallocLong(1);
            final var bufferCreateInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
            final var externalBufferCreateInfo = VkExternalMemoryBufferCreateInfo.calloc(stack).sType$Default();
            bufferCreateInfo.pNext(externalBufferCreateInfo);
            
            bufferCreateInfo.size(hostPtr.size());
            bufferCreateInfo.usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            bufferCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            externalBufferCreateInfo.handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT);
            
            throwFromCode(vkCreateBuffer(device, bufferCreateInfo, null, handlePtr));
            final var bufferHandle = handlePtr.get(0);
            
            final var memoryRequirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, bufferHandle, memoryRequirements);
            
            
            final var properties = VkMemoryHostPointerPropertiesEXT.calloc(stack).sType$Default();
            vkGetMemoryHostPointerPropertiesEXT(CinnabarRenderer.device(), VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT, hostPtr.pointer(), properties);
            
            final var importInfo = VkImportMemoryHostPointerInfoEXT.calloc(stack).sType$Default();
            importInfo.pHostPointer(hostPtr.pointer());
            importInfo.handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT);
            
            final var allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
            allocInfo.pNext(importInfo);
            allocInfo.allocationSize(hostPtr.size());
            
            allocInfo.memoryTypeIndex(-1);
            for (int i = 0; i < 32; i++) {
                int currentMemoryBit = 1 << i;
                // cant import to memory type
                if ((properties.memoryTypeBits() & currentMemoryBit) == 0) {
                    continue;
                }
                // buffer can't use memory type
                if ((memoryRequirements.memoryTypeBits() & currentMemoryBit) == 0) {
                    continue;
                }
                // memory type isn't host visible host coherent
                if ((CinnabarRenderer.hostPtrMemoryTypeBits() & currentMemoryBit) == 0) {
                    continue;
                }
                // first type that works, use it
                allocInfo.memoryTypeIndex(i);
                break;
            }
            
            if (allocInfo.memoryTypeIndex() == -1) {
                throw new IllegalStateException("Couldn't find memory type to import host alloc to");
            }
            
            throwFromCode(vkAllocateMemory(device, allocInfo, null, handlePtr));
            final var memoryHandle = handlePtr.get(0);
            
            vkBindBufferMemory(device, bufferHandle, memoryHandle, 0);
            
            return new CPUMemoryVkBuffer(bufferHandle, hostPtr, memoryHandle, ownsHostAllocation);
        }
    }
    
    @Override
    public void destroy() {
        final var device = CinnabarRenderer.device();
        
        vkDestroyBuffer(device, bufferHandle, null);
        vkFreeMemory(device, vkImportedMemory, null);
        if (ownsHostAllocation) {
            hostPtr.free();
        }
    }
}
