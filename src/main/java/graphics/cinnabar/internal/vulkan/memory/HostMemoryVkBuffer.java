package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.memory.LeakDetection;
import graphics.cinnabar.internal.memory.PointerWrapper;
import graphics.cinnabar.internal.vulkan.Destroyable;
import graphics.cinnabar.internal.vulkan.util.LiveHandles;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static graphics.cinnabar.internal.CinnabarDebug.DEBUG;
import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.VK_EXTERNAL_MEMORY_HANDLE_TYPE_HOST_ALLOCATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTExternalMemoryHost.vkGetMemoryHostPointerPropertiesEXT;
import static org.lwjgl.vulkan.VK10.*;

@NonnullDefault
public record HostMemoryVkBuffer(long bufferHandle, PointerWrapper hostPtr, long vkImportedMemory,
                                 boolean ownsHostAllocation, @Nullable RuntimeException src) implements Destroyable {

    // debug feature, works better in renderdoc
    private static final boolean ALLOCATE_VULKAN_MEMORY_FOR_ALLOC = false;
    private static final int VULKAN_CPU_MEMORY_TYPE_INDEX = 1;

    public static HostMemoryVkBuffer alloc(long size) {

        final var hostPtrAlignment = CinnabarRenderer.hostPtrAlignment();
        final var roundedUpSize = (size + (hostPtrAlignment - 1)) & -hostPtrAlignment;

        if (ALLOCATE_VULKAN_MEMORY_FOR_ALLOC) {
            final var device = CinnabarRenderer.device();
            try (final var stack = MemoryStack.stackPush()) {
                final var allocInfo = VkMemoryAllocateInfo.calloc(stack).sType$Default();
                allocInfo.allocationSize(roundedUpSize);
                allocInfo.memoryTypeIndex(VULKAN_CPU_MEMORY_TYPE_INDEX);
                final var memoryHandlePtr = stack.mallocLong(1);
                final var hostPtr = stack.mallocPointer(1);
                vkAllocateMemory(device, allocInfo, null, memoryHandlePtr);
                vkMapMemory(device, memoryHandlePtr.get(0), 0, roundedUpSize, 0, hostPtr);

                final var bufferHandlePtr = stack.mallocLong(1);

                final var bufferCreateInfo = VkBufferCreateInfo.calloc(stack).sType$Default();
                bufferCreateInfo.size(roundedUpSize);
                bufferCreateInfo.usage(VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
                bufferCreateInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

                throwFromCode(vkCreateBuffer(device, bufferCreateInfo, null, bufferHandlePtr));

                vkBindBufferMemory(device, bufferHandlePtr.get(0), memoryHandlePtr.get(0), 0);

                LeakDetection.addAccessibleLocation(new PointerWrapper(hostPtr.get(0), roundedUpSize));

                @Nullable final var src = DEBUG ? new RuntimeException("Double free of CPU Vk Buffer!") : null;
                final var buffer = new HostMemoryVkBuffer(bufferHandlePtr.get(0), new PointerWrapper(hostPtr.get(0), roundedUpSize), memoryHandlePtr.get(0), true, src);
                LiveHandles.create(buffer);
                return buffer;
            }
        }

        final var hostPtr = PointerWrapper.alloc(roundedUpSize, hostPtrAlignment);
        return create(hostPtr, true);
    }

    public static HostMemoryVkBuffer create(long ptr, long size) {
        return create(new PointerWrapper(ptr, size), false);
    }

    private static HostMemoryVkBuffer create(PointerWrapper hostPtr, boolean ownsHostAllocation) {
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
            @Nullable final var src = DEBUG ? new RuntimeException("Double free of CPU Vk Buffer!") : null;
            final var buffer = new HostMemoryVkBuffer(bufferHandle, hostPtr, memoryHandle, ownsHostAllocation, src);

            LiveHandles.create(buffer);

            return buffer;
        }
    }

    @Override
    public void destroy() {
        final var device = CinnabarRenderer.device();

        vkDestroyBuffer(device, bufferHandle, null);
        vkFreeMemory(device, vkImportedMemory, null);
        if (ownsHostAllocation) {
            if (ALLOCATE_VULKAN_MEMORY_FOR_ALLOC) {
                LeakDetection.removeAccessibleLocation(hostPtr);
            } else {
                hostPtr.free();
            }
        }

        LiveHandles.destroy(this);
    }
}
