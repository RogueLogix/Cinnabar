package graphics.cinnabar.core.b3d.buffers;

import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.CinnabarCore;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.b3d.command.VulkanTransientCommandBufferPool;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import graphics.cinnabar.lib.util.MathUtil;
import it.unimi.dsi.fastutil.ints.IntIntImmutablePair;
import it.unimi.dsi.fastutil.longs.LongLongImmutablePair;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.VmaVirtualAllocationCreateInfo;
import org.lwjgl.util.vma.VmaVirtualBlockCreateInfo;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.LongBuffer;

import static graphics.cinnabar.core.b3d.buffers.CinnabarGpuBuffer.vkUsageBits;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

public class BufferPool implements Destroyable {
    private final CinnabarDevice device;
    private final IntIntImmutablePair memoryType;
    private final int usage;
    private final String name;
    private final boolean uploadPool;
    
    private final VmaVirtualAllocationCreateInfo allocationCreateInfo = VmaVirtualAllocationCreateInfo.calloc();
    private final PointerBuffer allocationReturn = MemoryUtil.memCallocPointer(1);
    private final LongBuffer offsetReturn = MemoryUtil.memCallocLong(1);
    final ReferenceOpenHashSet<Buffer> buffers = new ReferenceOpenHashSet<>();
    final ReferenceArrayList<Buffer> buffersInOverflow = new ReferenceArrayList<>();
    private long overflowSinceLastRealloc = 0;
    
    @Nullable
    private VkBuffer mainBuffer;
    private long vmaVirtualBlock = 0;
    
    public BufferPool(CinnabarDevice device, IntIntImmutablePair memoryType, int usage, long initialPoolSize, String name, boolean uploadPool) {
        this.device = device;
        this.memoryType = memoryType;
        this.usage = usage;
        this.name = name;
        this.uploadPool = uploadPool;
        createMainBuffer(initialPoolSize);
    }
    
    @Override
    public void destroy() {
        destroyMainBuffer();
    }
    
    private void createMainBuffer(long size) {
        if (mainBuffer != null || vmaVirtualBlock != 0) {
            throw new IllegalStateException();
        }
        mainBuffer = new VkBuffer(device, size, vkUsageBits(usage, false) | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, memoryType);
        mainBuffer.setVulkanName(name);
        try (final var stack = MemoryStack.stackPush()) {
            final var virtualBlockCreateInfo = VmaVirtualBlockCreateInfo.calloc(stack);
            virtualBlockCreateInfo.size(mainBuffer.size);
            virtualBlockCreateInfo.flags(uploadPool ? VMA_VIRTUAL_BLOCK_CREATE_LINEAR_ALGORITHM_BIT : 0);
            final var virtualBlockHandle = stack.pointers(0);
            vmaCreateVirtualBlock(virtualBlockCreateInfo, virtualBlockHandle);
            vmaVirtualBlock = virtualBlockHandle.get(0);
        }
        
        allocationCreateInfo.alignment(1024);
        allocationCreateInfo.flags(0);
    }
    
    private void destroyMainBuffer() {
        if (vmaVirtualBlock == 0 || mainBuffer == null) {
            throw new IllegalStateException();
        }
        vmaClearVirtualBlock(vmaVirtualBlock);
        vmaDestroyVirtualBlock(vmaVirtualBlock);
        vmaVirtualBlock = 0;
        mainBuffer.destroy();
        mainBuffer = null;
    }
    
    @Nullable
    private LongLongImmutablePair tryVmaAlloc(long size) {
        allocationCreateInfo.size(size);
        allocationReturn.put(0, 0);
        offsetReturn.put(0, 0);
        final var allocResult = vmaVirtualAllocate(vmaVirtualBlock, allocationCreateInfo, allocationReturn, offsetReturn);
        if (allocResult != VK_SUCCESS) {
            return null;
        }
        return LongLongImmutablePair.of(offsetReturn.get(0), allocationReturn.get(0));
    }
    
    public boolean canAllocate(int usage) {
        return (this.usage | usage) <= this.usage;
    }
    
    public CinnabarGpuBuffer alloc(int usage, int size, @Nullable String name) {
        if (!canAllocate(usage)) {
            // usage isn't a subset, allocate a regular one
            return new CinnabarIndividualGpuBuffer(device, usage, size, name);
        }
        final var pooledBuffer = new Buffer(device, usage, size, name);
        @Nullable
        final var vmaAlloc = tryVmaAlloc(size);
        if (vmaAlloc == null) {
            pooledBuffer.createOverflowBuffer();
            overflowSinceLastRealloc += size;
            buffersInOverflow.add(pooledBuffer);
        } else {
            pooledBuffer.offset = vmaAlloc.firstLong();
            pooledBuffer.vmaAllocation = vmaAlloc.secondLong();
            buffers.add(pooledBuffer);
        }
        return pooledBuffer;
    }
    
    public void processRealloc() {
        if (uploadPool) {
            if (!buffersInOverflow.isEmpty() || !buffers.isEmpty()) {
                throw new IllegalStateException();
            }
            vmaClearVirtualBlock(vmaVirtualBlock);
        }
        
        if (overflowSinceLastRealloc == 0) {
            // no overflow, no need to realloc
            return;
        }
        
        final var newSize = MathUtil.roundUpPo2(mainBuffer.size + overflowSinceLastRealloc);
        overflowSinceLastRealloc = 0;
        CinnabarCore.CINNABAR_CORE_LOG.info("BufferPool {} resizing, new size: {}", name, newSize);
        
        // it is safe for me to stall the device right now, and because im about to recreate the pool, i need device idle
        final var commandPool = new VulkanTransientCommandBufferPool(device, device.graphicsQueueFamily);
        
        @Nullable
        VkBuffer oldDataBuffer = null;
        if (!buffers.isEmpty()) {
            device.idleAndClear();
            oldDataBuffer = new VkBuffer(device, mainBuffer.size, VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, device.hostMemoryType);
            try (final var stack = MemoryStack.stackPush()) {
                final var copyDownCommandBuffer = commandPool.alloc("Pooled GpuBuffer realloc copy down");
                final var beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
                vkBeginCommandBuffer(copyDownCommandBuffer, beginInfo);
                {
                    final var copyRegion = VkBufferCopy.calloc(1, stack);
                    copyRegion.srcOffset(0);
                    copyRegion.dstOffset(0);
                    copyRegion.size(mainBuffer.size);
                    vkCmdCopyBuffer(copyDownCommandBuffer, mainBuffer.handle, oldDataBuffer.handle, copyRegion);
                }
                vkEndCommandBuffer(copyDownCommandBuffer);
                final var submitInfo = VkSubmitInfo.calloc(stack).sType$Default();
                submitInfo.pCommandBuffers(stack.pointers(copyDownCommandBuffer));
                vkQueueSubmit(device.graphicsQueue, submitInfo, 0);
            }
            device.idleAndClear();
            commandPool.reset();
        }
        
        destroyMainBuffer();
        createMainBuffer(newSize);
        
        if(oldDataBuffer != null || !buffersInOverflow.isEmpty()) {
            device.idleAndClear();
            try (final var stack = MemoryStack.stackPush()) {
                final var copyUpCommandBuffer = commandPool.alloc("Pooled GpuBuffer realloc copy up");
                final var beginInfo = VkCommandBufferBeginInfo.calloc(stack).sType$Default();
                vkBeginCommandBuffer(copyUpCommandBuffer, beginInfo);
                if (oldDataBuffer != null) {
                    final var mainCopyUps = VkBufferCopy.calloc(buffers.size());
                    for (Buffer buffer : buffers) {
                        
                        final var previousOffset = buffer.offset;
                        @Nullable
                        final var vmaAlloc = tryVmaAlloc(buffer.size);
                        if (vmaAlloc == null) {
                            throw new IllegalStateException();
                        }
                        
                        buffer.offset = vmaAlloc.firstLong();
                        buffer.vmaAllocation = vmaAlloc.secondLong();
                        
                        mainCopyUps.srcOffset(previousOffset);
                        mainCopyUps.dstOffset(buffer.offset);
                        mainCopyUps.size(buffer.size);
                        mainCopyUps.position(mainCopyUps.position() + 1);
                    }
                    mainCopyUps.limit(mainCopyUps.position());
                    mainCopyUps.position(0);
                    vkCmdCopyBuffer(copyUpCommandBuffer, oldDataBuffer.handle, mainBuffer.handle, mainCopyUps);
                    mainCopyUps.free();
                }
                {
                    final var overflowCopyUp = VkBufferCopy.calloc(1, stack);
                    for (Buffer overflowBuffer : buffersInOverflow) {
                        
                        @Nullable
                        final var vmaAlloc = tryVmaAlloc(overflowBuffer.size);
                        if (vmaAlloc == null) {
                            throw new IllegalStateException();
                        }
                        
                        overflowBuffer.offset = vmaAlloc.firstLong();
                        overflowBuffer.vmaAllocation = vmaAlloc.secondLong();
                        
                        overflowCopyUp.srcOffset(0);
                        overflowCopyUp.dstOffset(overflowBuffer.offset);
                        overflowCopyUp.size(overflowBuffer.size);
                        assert overflowBuffer.overflowBuffer != null;
                        vkCmdCopyBuffer(copyUpCommandBuffer, overflowBuffer.overflowBuffer.handle, mainBuffer.handle, overflowCopyUp);
                        
                        buffers.add(overflowBuffer);
                    }
                }
                vkEndCommandBuffer(copyUpCommandBuffer);
                final var submitInfo = VkSubmitInfo.calloc(stack).sType$Default();
                submitInfo.pCommandBuffers(stack.pointers(copyUpCommandBuffer));
                vkQueueSubmit(device.graphicsQueue, submitInfo, 0);
            }
            device.idleAndClear();
            assert oldDataBuffer != null;
            oldDataBuffer.destroy();
            commandPool.destroy();
            buffersInOverflow.forEach(Buffer::destroyOverflowBuffer);
            buffersInOverflow.clear();
        }
    }
    
    public final class Buffer extends CinnabarGpuBuffer {
        
        @Nullable
        private VkBuffer overflowBuffer;
        private long offset = -1;
        private long vmaAllocation = 0;
        
        private Buffer(CinnabarDevice device, int usage, int size, @Nullable String name) {
            super(device, usage, size, name);
        }
        
        @Override
        protected VkBuffer.Slice internalBackingSlice() {
            if (overflowBuffer != null) {
                return overflowBuffer.whole();
            }
            assert mainBuffer != null;
            return mainBuffer.slice(new MemoryRange(offset, size));
        }
        
        @Override
        public void destroy() {
            destroyOverflowBuffer();
            assert vmaVirtualBlock != 0;
            if (vmaAllocation != 0) {
                vmaVirtualFree(vmaVirtualBlock, vmaAllocation);
            }
            buffers.remove(this);
            buffersInOverflow.remove(this);
        }
        
        private void createOverflowBuffer() {
            overflowBuffer = new VkBuffer(device, size, vkUsageBits(usage(), clientStorage(usage())) | VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT, memoryType);
        }
        
        private void destroyOverflowBuffer() {
            if (overflowBuffer != null) {
                overflowBuffer.destroy();
                overflowBuffer = null;
            }
        }
    }
}
