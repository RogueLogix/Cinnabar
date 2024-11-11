package graphics.cinnabar.internal.vulkan.util;

import graphics.cinnabar.internal.CinnabarRenderer;
import graphics.cinnabar.internal.memory.MagicNumbers;
import graphics.cinnabar.internal.memory.PointerWrapper;
import graphics.cinnabar.internal.vulkan.Destroyable;
import net.roguelogix.phosphophyllite.util.NonnullDefault;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;

import static graphics.cinnabar.internal.vulkan.exceptions.VkException.throwFromCode;
import static org.lwjgl.vulkan.VK10.*;

@NonnullDefault
public class VulkanTransientCommandBufferPool implements Destroyable {
    private static final int COMMAND_BUFFER_ALLOC_SIZE = 128;
    private static final int BUFFER_LIST_SIZE_INCREMENTS = 1024 * MagicNumbers.LONG_BYTE_SIZE;
    
    private final VkDevice device = CinnabarRenderer.device();
    
    private final PointerWrapper allocInfo = PointerWrapper.alloc(VkCommandBufferAllocateInfo.SIZEOF);
    private final long commandPool;
    private int allocatedBuffers = 0;
    private PointerWrapper commandBuffers = PointerWrapper.NULLPTR;
    private int nextBuffer = 0;
    
    public VulkanTransientCommandBufferPool(int queueFamily) {
        try (final var stack = MemoryStack.stackPush()) {
            final var createInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default();
            createInfo.flags(VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
            createInfo.queueFamilyIndex(queueFamily);
            final var ptr = stack.mallocLong(1);
            throwFromCode(vkCreateCommandPool(device, createInfo, null, ptr));
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
        allocInfo.free();
        commandBuffers.free();
        vkDestroyCommandPool(device, commandPool, null);
    }
    
    public void reset() {
        reset(false, false);
    }
    
    public void reset(boolean releaseBuffers, boolean releaseToSystem) {
        if (releaseBuffers) {
            nvkFreeCommandBuffers(device, commandPool, allocatedBuffers, commandBuffers.pointer());
            allocatedBuffers = 0;
            commandBuffers.clear();
        }
        throwFromCode(vkResetCommandPool(device, commandPool, releaseToSystem ? VK_COMMAND_POOL_RESET_RELEASE_RESOURCES_BIT : 0));
        nextBuffer = 0;
    }
    
    public VkCommandBuffer alloc() {
        if (nextBuffer < allocatedBuffers) {
            final var bufferHandle = commandBuffers.getLongIdx(nextBuffer);
            assert bufferHandle != 0;
            nextBuffer++;
            return new VkCommandBuffer(bufferHandle, device);
        }
        final var newBufferCount = allocatedBuffers + COMMAND_BUFFER_ALLOC_SIZE;
        // not enough space to store the new handles
        if (commandBuffers.size() < ((long) newBufferCount * MagicNumbers.LONG_BYTE_SIZE)) {
            commandBuffers = commandBuffers.realloc(commandBuffers.size() + BUFFER_LIST_SIZE_INCREMENTS);
        }
        final var newBuffersPtr = commandBuffers.pointer() + ((long) allocatedBuffers * MagicNumbers.LONG_BYTE_SIZE);
        throwFromCode(nvkAllocateCommandBuffers(device, allocInfo.pointer(), newBuffersPtr));
        allocatedBuffers += COMMAND_BUFFER_ALLOC_SIZE;
        return alloc();
    }
}
