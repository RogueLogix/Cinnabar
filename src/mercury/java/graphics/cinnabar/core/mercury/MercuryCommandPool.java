package graphics.cinnabar.core.mercury;

import graphics.cinnabar.api.hg.HgCommandBuffer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

public class MercuryCommandPool extends MercuryObject implements HgCommandBuffer.Pool {
    private static final int COMMAND_BUFFER_ALLOC_SIZE = 128;
    
    
    private final boolean commandBufferReset;
    private final VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc().sType$Default();
    private final long poolHandle;
    private final LongArrayList newBuffers = new LongArrayList();
    private final LongArrayList buffersToDelete = new LongArrayList();
    
    public MercuryCommandPool(MercuryDevice device, int queueFamily, boolean commandBufferReset, boolean oneTimeSubmit) {
        super(device);
        this.commandBufferReset = commandBufferReset;
        try (final var stack = memoryStack().push()) {
            final var createInfo = VkCommandPoolCreateInfo.calloc(stack).sType$Default();
            if (commandBufferReset) {
                createInfo.flags(createInfo.flags() | VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            }
            if (oneTimeSubmit) {
                createInfo.flags(createInfo.flags() | VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
            }
            createInfo.queueFamilyIndex(queueFamily);
            final var handleReturn = stack.longs(0);
            vkCreateCommandPool(device.vkDevice(), createInfo, null, handleReturn);
            poolHandle = handleReturn.get(0);
        }
        allocInfo.commandPool(poolHandle);
        // TODO: secondary command buffers
        allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        allocInfo.commandBufferCount(COMMAND_BUFFER_ALLOC_SIZE);
    }
    
    @Override
    public void destroy() {
        vkDestroyCommandPool(device.vkDevice(), poolHandle, null);
        allocInfo.free();
    }
    
    private void allocateMoreBuffers() {
        try (final var stack = memoryStack().push()) {
            if (!buffersToDelete.isEmpty()) {
                try (final var ignored = stack.push()) {
                    final var buffers = stack.callocPointer(buffersToDelete.size());
                    for (int i = 0; i < buffersToDelete.size(); i++) {
                        buffers.put(i, buffersToDelete.getLong(i));
                        vkFreeCommandBuffers(device.vkDevice(), poolHandle, buffers);
                    }
                }
            }
            final var buffers = stack.callocPointer(COMMAND_BUFFER_ALLOC_SIZE);
            vkAllocateCommandBuffers(device.vkDevice(), allocInfo, buffers);
            for (int i = 0; i < COMMAND_BUFFER_ALLOC_SIZE; i++) {
                newBuffers.push(buffers.get(i));
            }
        }
    }
    
    private void freeBuffer(VkCommandBuffer commandBuffer) {
        if (commandBufferReset) {
            vkResetCommandBuffer(commandBuffer, VK_COMMAND_BUFFER_RESET_RELEASE_RESOURCES_BIT);
            newBuffers.add(commandBuffer.address());
        } else {
            buffersToDelete.push(commandBuffer.address());
        }
    }
    
    @Override
    public HgCommandBuffer allocate() {
        if (newBuffers.isEmpty()) {
            allocateMoreBuffers();
        }
        return new MercuryCommandBuffer(device, new VkCommandBuffer(newBuffers.popLong(), device.vkDevice()), this::freeBuffer);
    }
    
    @Override
    public void reset() {
        vkResetCommandPool(device.vkDevice(), poolHandle, 0);
    }
}
