package graphics.cinnabar.core.b3d.command;

import graphics.cinnabar.api.CinnabarAPI;
import graphics.cinnabar.api.CinnabarGpuDevice;
import graphics.cinnabar.api.annotations.NotNullDefault;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDebugMarkerObjectNameInfoEXT;

import static graphics.cinnabar.api.exceptions.VkException.checkVkCode;
import static org.lwjgl.vulkan.EXTDebugMarker.vkDebugMarkerSetObjectNameEXT;
import static org.lwjgl.vulkan.VK10.*;

@NotNullDefault
public class VulkanTransientCommandBufferPool implements Destroyable {
    private static final int COMMAND_BUFFER_ALLOC_SIZE = 128;
    private static final int BUFFER_LIST_SIZE_INCREMENTS = 1024 * MagicMemorySizes.LONG_BYTE_SIZE;
    
    private final CinnabarDevice device;
    private final PointerWrapper allocInfo = PointerWrapper.alloc(VkCommandBufferAllocateInfo.SIZEOF);
    private final long commandPool;
    private int allocatedBuffers = 0;
    private PointerWrapper commandBuffers = PointerWrapper.NULLPTR;
    private int nextBuffer = 0;
    
    public VulkanTransientCommandBufferPool(CinnabarDevice device, int queueFamily) {
        this.device = device;
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default();
            createInfo.flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
            createInfo.queueFamilyIndex(queueFamily);
            final var ptr = stack.mallocLong(1);
            checkVkCode(vkCreateCommandPool(device.vkDevice, createInfo, null, ptr));
            commandPool = ptr.get(0);
        }
        allocInfo.clear();
        final var allocInfoPtr = allocInfo.pointer();
        VkCommandBufferAllocateInfo.nsType(allocInfoPtr, VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        VkCommandBufferAllocateInfo.ncommandPool(allocInfoPtr, commandPool);
        // TODO: secondary command buffers? dont really need them because of dynamic rendering
        VkCommandBufferAllocateInfo.nlevel(allocInfoPtr, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        VkCommandBufferAllocateInfo.ncommandBufferCount(allocInfoPtr, COMMAND_BUFFER_ALLOC_SIZE);
    }
    
    @Override
    public void destroy() {
        if (allocatedBuffers != 0) {
            nvkFreeCommandBuffers(device.vkDevice, commandPool, allocatedBuffers, commandBuffers.pointer());
            allocatedBuffers = 0;
        }
        vkDestroyCommandPool(device.vkDevice, commandPool, null);
        allocInfo.free();
        commandBuffers.free();
    }
    
    public void reset() {
        reset(false, false);
    }
    
    public void reset(boolean releaseBuffers, boolean releaseToSystem) {
        if (releaseBuffers && allocatedBuffers != 0) {
            nvkFreeCommandBuffers(device.vkDevice, commandPool, allocatedBuffers, commandBuffers.pointer());
            allocatedBuffers = 0;
            commandBuffers.clear();
        }
        checkVkCode(vkResetCommandPool(device.vkDevice, commandPool, releaseToSystem ? VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT : 0));
        nextBuffer = 0;
    }
    
    public VkCommandBuffer alloc(@Nullable String name) {
        if (nextBuffer < allocatedBuffers) {
            final var bufferHandle = commandBuffers.getLongIdx(nextBuffer);
            assert bufferHandle != 0;
            nextBuffer++;
            if (CinnabarAPI.Internals.DEBUG_MARKER_ENABLED && name != null) {
                try (final var stack = MemoryStack.stackPush()) {
                    final var nameInfo = VkDebugMarkerObjectNameInfoEXT.calloc(stack).sType$Default();
                    nameInfo.objectType(VK_OBJECT_TYPE_COMMAND_BUFFER);
                    nameInfo.object(bufferHandle);
                    nameInfo.pObjectName(stack.UTF8(name));
                    vkDebugMarkerSetObjectNameEXT(device.vkDevice, nameInfo);
                }
            }
            return new VkCommandBuffer(bufferHandle, device.vkDevice);
        }
        final var newBufferCount = allocatedBuffers + COMMAND_BUFFER_ALLOC_SIZE;
        // not enough space to store the new handles
        if (commandBuffers.size() < ((long) newBufferCount * MagicMemorySizes.LONG_BYTE_SIZE)) {
            commandBuffers = commandBuffers.realloc(commandBuffers.size() + BUFFER_LIST_SIZE_INCREMENTS);
        }
        final var newBuffersPtr = commandBuffers.pointer() + ((long) allocatedBuffers * MagicMemorySizes.LONG_BYTE_SIZE);
        checkVkCode(nvkAllocateCommandBuffers(device.vkDevice, allocInfo.pointer(), newBuffersPtr));
        allocatedBuffers += COMMAND_BUFFER_ALLOC_SIZE;
        return alloc(name);
    }
    
    public int usedBuffers() {
        return nextBuffer;
    }
    
    public int allocatedBuffers() {
        return allocatedBuffers;
    }
}
