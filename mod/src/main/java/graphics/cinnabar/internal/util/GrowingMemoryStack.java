package graphics.cinnabar.internal.util;

import graphics.cinnabar.internal.memory.MagicNumbers;
import graphics.cinnabar.internal.memory.PointerWrapper;
import graphics.cinnabar.internal.vulkan.Destroyable;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import org.lwjgl.vulkan.VkCommandBufferSubmitInfo;
import org.lwjgl.vulkan.VkSemaphoreSubmitInfo;

public class GrowingMemoryStack implements Destroyable {
    private static final long STACK_BLOCK_SIZE = 64 * MagicNumbers.KiB;
    private final ReferenceArrayList<PointerWrapper> stackBlocks = new ReferenceArrayList<>();
    private int currentStackBlockIndex = 0;
    private long currentStackBlockAllocated = 0;
    
    public GrowingMemoryStack() {
        stackBlocks.add(PointerWrapper.alloc(STACK_BLOCK_SIZE));
    }
    
    @Override
    public void destroy() {
        stackBlocks.forEach(PointerWrapper::free);
    }
    
    public PointerWrapper calloc(long size, long alignment) {
        if (size > STACK_BLOCK_SIZE) {
            throw new IllegalArgumentException("Stack alloc size is too large");
        }
        var stackBlock = stackBlocks.get(currentStackBlockIndex);
        long allocAddress = ((stackBlock.pointer() + currentStackBlockAllocated) + (alignment - 1)) & (-alignment);
        long allocOffset = allocAddress - stackBlock.pointer();
        // wont fit in this block, get the next one
        if ((allocOffset + size) > STACK_BLOCK_SIZE) {
            currentStackBlockIndex++;
            // out of blocks, new one
            if (currentStackBlockIndex == stackBlocks.size()) {
                stackBlocks.add(PointerWrapper.alloc(STACK_BLOCK_SIZE));
            }
            stackBlock = stackBlocks.get(currentStackBlockIndex);
            allocAddress = (stackBlock.pointer() + (alignment - 1)) & (-alignment);
            allocOffset = allocAddress - stackBlock.pointer();
            if ((allocOffset + size) > STACK_BLOCK_SIZE) {
                // this is a special case where the alignment would require an overrun of the buffer
                // this should be exceedingly rare
                // if this is ever actually hit, ill handle it then
                throw new IllegalArgumentException("Stack alloc size/alignment is too large");
            }
        }
        currentStackBlockAllocated = allocOffset + size;
        return stackBlock.slice(allocOffset, size).clear();
    }
    
    public void reset() {
        currentStackBlockIndex = 0;
        currentStackBlockAllocated = 0;
    }
    
    public VkCommandBufferSubmitInfo commandBufferSubmitInfo() {
        final var ptr = calloc(VkCommandBufferSubmitInfo.SIZEOF, VkCommandBufferSubmitInfo.ALIGNOF);
        return VkCommandBufferSubmitInfo.create(ptr.pointer());
    }
    
    public VkCommandBufferSubmitInfo.Buffer commandBufferSubmitInfo(int count) {
        final var ptr = calloc(VkCommandBufferSubmitInfo.SIZEOF * (long)count, VkCommandBufferSubmitInfo.ALIGNOF);
        return VkCommandBufferSubmitInfo.create(ptr.pointer(), count);
    }
    
    public VkSemaphoreSubmitInfo semaphoreSubmitInfo() {
        final var ptr = calloc(VkSemaphoreSubmitInfo.SIZEOF, VkSemaphoreSubmitInfo.ALIGNOF);
        return VkSemaphoreSubmitInfo.create(ptr.pointer());
    }
    
    public VkSemaphoreSubmitInfo.Buffer semaphoreSubmitInfo(int count) {
        final var ptr = calloc(VkSemaphoreSubmitInfo.SIZEOF * (long)count, VkSemaphoreSubmitInfo.ALIGNOF);
        return VkSemaphoreSubmitInfo.create(ptr.pointer(), count);
    }
}
