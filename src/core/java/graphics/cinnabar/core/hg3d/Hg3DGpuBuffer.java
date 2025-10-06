package graphics.cinnabar.core.hg3d;

import com.mojang.blaze3d.buffers.GpuBuffer;
import graphics.cinnabar.api.hg.HgBuffer;
import graphics.cinnabar.api.hg.HgCommandBuffer;
import graphics.cinnabar.api.hg.HgQueue;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.lib.datastructures.SpliceableLinkedList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCString;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

public class Hg3DGpuBuffer extends GpuBuffer implements Hg3DObject, Destroyable {
    private final Hg3DGpuDevice device;
    private final HgBuffer.MemoryRequest requestedMemory;
    private boolean isClosed = false;
    private final Manager manager;
    
    @Nullable
    private final ByteBuffer sourceData;
    @Nullable
    private HgBuffer buffer;
    @Nullable
    private HgBuffer.Slice slice;
    @Nullable
    private HgBuffer.Suballocator.Alloc alloc;
    @Nullable
    private ByteBuffer evictedData;
    
    private final SpliceableLinkedList.Node<Hg3DGpuBuffer> usageListNode = new SpliceableLinkedList.Node<>(this);
    private long lastUsedFrame = -1;
    private boolean pendingPromotion = false;
    
    private Hg3DGpuBuffer(Manager manager, int usage, int size, @Nullable ByteBuffer sourceData) {
        super(usage, size);
        this.device = manager.device;
        this.manager = manager;
        if (sourceData != null) {
            // make a copy, because we own this buffer
            final var newSourceData = MemoryUtil.memAlloc(sourceData.remaining());
            LibCString.memcpy(newSourceData, sourceData);
            sourceData = newSourceData;
        }
        this.sourceData = sourceData;
        
        // i consider map for read a client storage hint
        final boolean clientStorageHint = (usage & (USAGE_HINT_CLIENT_STORAGE | USAGE_MAP_READ)) != 0;
        final boolean mappable = (usage & (USAGE_MAP_READ | USAGE_MAP_WRITE)) != 0;
        requestedMemory = clientStorageHint ? HgBuffer.MemoryRequest.CPU : mappable ? HgBuffer.MemoryRequest.MAPPABLE_PREF_GPU : HgBuffer.MemoryRequest.GPU;
    }
    
    @Override
    public boolean isClosed() {
        return isClosed;
    }
    
    @Override
    public void close() {
        if (isClosed) {
            return;
        }
        isClosed = true;
        if (!isInFlight()) {
            // buffer is already out of use enough that it can be destroyed now
            destroy();
        } else {
            device.destroyEndOfFrame(this);
        }
    }
    
    @Override
    public void destroy() {
        if (buffer != null) {
            buffer.destroy();
        }
        if (alloc != null) {
            alloc.destroy();
        }
        MemoryUtil.memFree(sourceData);
        MemoryUtil.memFree(evictedData);
        manager.destroy(this);
    }
    
    @Override
    public Hg3DGpuDevice device() {
        return device;
    }
    
    public boolean usedThisFrame() {
        return lastUsedFrame == device.currentFrame();
    }
    
    public HgBuffer.Slice hgSlice() {
        final var currentFrame = device.currentFrame();
        if (currentFrame != lastUsedFrame || slice == null) {
            manager.used(this);
            if (slice == null) {
                // this promotion can technically fail, if we are out of all VK accessible memory, which is unlikely
                manager.promoteImmediate(this);
                assert slice != null;
            } else if (!hasPreferredMemoryType()) {
                // buffer would like to be device-local, and isn't, but can be used by the GPU
                // attempt a softer promotion, this won't fail
                manager.promoteToDevice(this);
            }
            // mark used after promotion to allow promotion to happen at the beginning of the frame automagically
            lastUsedFrame = currentFrame;
        }
        return slice;
    }
    
    private boolean canEvictImmediate() {
        // original data is the source _and_ it hasn't been used in long enough that the buffer can't be in flight
        // the buffer can be evicted without any other considerations
        return sourceData != null && !isInFlight();
    }
    
    public boolean isInFlight() {
        // buffer is currently in-flight on the GPU
        return device.currentFrame() - lastUsedFrame <= MagicNumbers.MaximumFramesInFlight || isClosed;
    }
    
    private boolean hasPreferredMemoryType() {
        if (slice == null) {
            return false;
        }
        if (requestedMemory == HgBuffer.MemoryRequest.CPU) {
            // anything mappable always has its preferred type, mappable
            return true;
        }
        if (requestedMemory == HgBuffer.MemoryRequest.MAPPABLE_PREF_GPU) {
            // TODO: detect when we have mappable device memory, and return false here
            //       for now, consider it always preferred
            return true;
        }
        return slice.buffer().memoryType().gpuLocal;
    }
    
    public static class Manager implements Destroyable {
        private final Hg3DGpuDevice device;
        private final SpliceableLinkedList<Hg3DGpuBuffer> liveBuffers = new SpliceableLinkedList<>();
        private long lastPromotionFailedFrame = 0;
        @Nullable
        private HgCommandBuffer promotionCommandBuffer;
        private final HgBuffer emergencyEvictionBuffer;
        
        public Manager(Hg3DGpuDevice device) {
            this.device = device;
            emergencyEvictionBuffer = device.hgDevice().createBuffer(HgBuffer.MemoryRequest.CPU, MagicMemorySizes.MiB, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            device.hgDevice().setAllocFailedCallback(this::allocFailed);
        }
        
        @Override
        public void destroy() {
            emergencyEvictionBuffer.destroy();
        }
        
        public Hg3DGpuBuffer create(int usage, int size, int align, @Nullable ByteBuffer data) {
            // if the data can't change, then i can rely on the data currently passed in to be constant for the buffer's entire lifetime
            final var dataCanChange = (usage & (USAGE_COPY_DST | USAGE_MAP_WRITE)) != 0;
            assert dataCanChange || data != null;
            final var buffer = new Hg3DGpuBuffer(this, usage, size, !dataCanChange ? data : null);
            if (dataCanChange && data != null) {
                // if data was specified (and its not constant), consider it "evicted data" at first
                // it'll automatically get promoted when it gets used
                buffer.evictedData = MemoryUtil.memAlloc(data.remaining());
                LibCString.memcpy(buffer.evictedData, data);
            }
            return buffer;
        }
        
        public void destroy(Hg3DGpuBuffer buffer) {
            buffer.buffer = null;
            buffer.slice = null;
            buffer.alloc = null;
            buffer.evictedData = null;
            if (buffer.usageListNode.linked()) {
                liveBuffers.remove(buffer.usageListNode);
            }
        }
        
        public void used(Hg3DGpuBuffer buffer) {
            if (buffer.usageListNode.linked()) {
                liveBuffers.remove(buffer.usageListNode);
            }
            liveBuffers.add(buffer.usageListNode);
        }
        
        private void endPromotionCommandBuffer() {
            if (promotionCommandBuffer == null) {
                return;
            }
            promotionCommandBuffer.barrier();
            promotionCommandBuffer.end();
            device.createCommandEncoder().insertCommandBufferFirst(promotionCommandBuffer);
            promotionCommandBuffer = null;
        }
        
        private boolean allocFailed(boolean gpuLocal, long allocSize) {
            // if this fails, the game may crash, so, make every attempt to free some memory.
            // notable that the current frame's buffers can't be freed, because this may be within a renderpass and I cant end that early
            // device can be stalled and anything for N-1 can be though
            boolean anythingFreed = false;
            
            for (@Nullable var currentBuffer = liveBuffers.peekFirst(); currentBuffer != null && allocSize > 0; ) {
                if (currentBuffer.data.isInFlight()) {
                    // buffer is too new, must fall to second pass
                    break;
                }
                assert currentBuffer.data.buffer != null;
                if (currentBuffer.data.buffer.memoryType().gpuLocal != gpuLocal) {
                    // wrong kind of memory, freeing this won't help
                    currentBuffer = currentBuffer.next();
                    continue;
                }
                if (currentBuffer.data.sourceData != null) {
                    currentBuffer.data.buffer.destroy();
                    currentBuffer.data.buffer = null;
                    currentBuffer.data.slice = null;
                    final var removedBuffer = currentBuffer;
                    currentBuffer = currentBuffer.next();
                    liveBuffers.remove(removedBuffer);
                    allocSize -= removedBuffer.data.size();
                    anythingFreed = true;
                } else if (currentBuffer.data.buffer.memoryType().mappable && currentBuffer.data.buffer.memoryType() != HgBuffer.MemoryType.GPU_MAPPABLE) {
                    // mappable memory, can evict to CPU memory and then follow immediate eviction path
                    // UMA (iGPUs) will also end up here
                    // GPU_MAPPABLE is specifically for over-pcie devices, which is extremely slow to read (though you can), so im not doing it in this pass
                    currentBuffer.data.evictedData = MemoryUtil.memAlloc(currentBuffer.data.size());
                    final var ptr = currentBuffer.data.buffer.slice().map();
                    LibCString.nmemcpy(MemoryUtil.memAddress(currentBuffer.data.evictedData), ptr.pointer(), currentBuffer.data.size());
                    currentBuffer.data.buffer.slice().unmap();
                    
                    currentBuffer.data.buffer.destroy();
                    currentBuffer.data.buffer = null;
                    currentBuffer.data.slice = null;
                    final var removedBuffer = currentBuffer;
                    currentBuffer = currentBuffer.next();
                    liveBuffers.remove(removedBuffer);
                    allocSize -= removedBuffer.data.size();
                    anythingFreed = true;
                } else {
                    currentBuffer = currentBuffer.next();
                }
            }
            
            if (allocSize <= 0) {
                // enough memory freed, attempt the alloc again
                return true;
            }
            device.hgDevice().waitIdle(); // TODO: use something better than wait idle, this is dangerous w/ multiple threads
            // anything for N-1 is now free to yeet too
            
            for (@Nullable var currentBuffer = liveBuffers.peekFirst(); currentBuffer != null && allocSize > 0; ) {
                if (currentBuffer.data.usedThisFrame()) {
                    // buffer is too new, must fall to second pass
                    break;
                }
                assert currentBuffer.data.buffer != null;
                if (currentBuffer.data.buffer.memoryType().gpuLocal != gpuLocal) {
                    // wrong kind of memory, freeing this wont help
                    currentBuffer = currentBuffer.next();
                    continue;
                }
                if (currentBuffer.data.sourceData != null) {
                    currentBuffer.data.buffer.destroy();
                    currentBuffer.data.buffer = null;
                    currentBuffer.data.slice = null;
                    final var removedBuffer = currentBuffer;
                    currentBuffer = currentBuffer.next();
                    liveBuffers.remove(removedBuffer);
                    allocSize -= removedBuffer.data.size();
                    anythingFreed = true;
                } else if (currentBuffer.data.buffer.memoryType().mappable && currentBuffer.data.buffer.memoryType() != HgBuffer.MemoryType.GPU_MAPPABLE) {
                    // mappable memory, can evict to CPU memory and then follow immediate eviction path
                    // UMA (iGPUs) will also end up here
                    // GPU_MAPPABLE is specifically for over-pcie devices, which is extremely slow to read (though you can), so im not doing it in this pass
                    currentBuffer.data.evictedData = MemoryUtil.memAlloc(currentBuffer.data.size());
                    final var ptr = currentBuffer.data.buffer.slice().map();
                    LibCString.nmemcpy(MemoryUtil.memAddress(currentBuffer.data.evictedData), ptr.pointer(), currentBuffer.data.size());
                    currentBuffer.data.buffer.slice().unmap();
                    
                    currentBuffer.data.buffer.destroy();
                    currentBuffer.data.buffer = null;
                    currentBuffer.data.slice = null;
                    final var removedBuffer = currentBuffer;
                    currentBuffer = currentBuffer.next();
                    liveBuffers.remove(removedBuffer);
                    allocSize -= removedBuffer.data.size();
                    anythingFreed = true;
                } else {
                    currentBuffer = currentBuffer.next();
                }
            }
            
            if (allocSize <= 0) {
                // enough memory freed, attempt the alloc again
                return true;
            }
            if (!gpuLocal || device.hgDevice().UMA()) {
                // if not trying to free GPU local memory (or UMA), anything we could free has already been freed
                return anythingFreed;
            }
            // anything that could be immediately evicted has been, this is a very shit situation
            // time to start shuffling buffers out of VRAM
            
            while (allocSize > 0) {
                long currentEmergencyBufferOffset = 0;
                SpliceableLinkedList<Hg3DGpuBuffer> shufflingBuffers = new SpliceableLinkedList<>();
                HgCommandBuffer currentCommandBuffer = device.createCommandEncoder().allocateCommandBuffer();
                
                for (@Nullable var currentBuffer = liveBuffers.peekFirst(); currentBuffer != null && currentEmergencyBufferOffset < allocSize; ) {
                    if (currentBuffer.data.usedThisFrame()) {
                        // buffer too new, SOL
                        return false;
                    }
                    assert currentBuffer.data.buffer != null;
                    if (currentBuffer.data.buffer.memoryType().gpuLocal != gpuLocal) {
                        // wrong kind of memory, freeing this wont help
                        currentBuffer = currentBuffer.next();
                        continue;
                    }
                    if (currentBuffer.data.size() > emergencyEvictionBuffer.size()) {
                        // buffer is too big, just skip it
                        currentBuffer = currentBuffer.next();
                        continue;
                    }
                    if (currentEmergencyBufferOffset + currentBuffer.data.size() > emergencyEvictionBuffer.size()) {
                        // out of space for this pass, submit and try again
                        break;
                    }
                    currentCommandBuffer.copyBufferToBuffer(currentBuffer.data.buffer.slice(), emergencyEvictionBuffer.slice(currentEmergencyBufferOffset, currentBuffer.data.size()));
                    currentEmergencyBufferOffset += currentBuffer.data.size();
                    final var removedBuffer = currentBuffer;
                    currentBuffer = currentBuffer.next();
                    liveBuffers.remove(removedBuffer);
                    shufflingBuffers.add(removedBuffer);
                }
                
                if (shufflingBuffers.empty()) {
                    assert currentEmergencyBufferOffset == 0;
                    currentCommandBuffer.destroy();
                    return anythingFreed;
                }
                
                currentCommandBuffer.end();
                final var queue = device.hgDevice().queue(HgQueue.Type.GRAPHICS);
                try (final var submit = queue.submit()) {
                    submit.execute(currentCommandBuffer);
                }
                device.hgDevice().waitIdle(); // TODO: use something better than wait idle, this is dangerous w/ multiple threads
                currentCommandBuffer.destroy();
                final var ptr = emergencyEvictionBuffer.map();
                
                currentEmergencyBufferOffset = 0;
                for (@Nullable var currentBuffer = shufflingBuffers.peekFirst(); currentBuffer != null; ) {
                    currentBuffer.data.evictedData = MemoryUtil.memAlloc(currentBuffer.data.size());
                    LibCString.nmemcpy(MemoryUtil.memAddress(currentBuffer.data.evictedData), ptr.pointer() + currentEmergencyBufferOffset, currentBuffer.data.size());
                    currentEmergencyBufferOffset += currentBuffer.data.size();
                    
                    assert currentBuffer.data.buffer != null;
                    currentBuffer.data.buffer.destroy();
                    currentBuffer.data.buffer = null;
                    currentBuffer.data.slice = null;
                    final var removedBuffer = currentBuffer;
                    currentBuffer = currentBuffer.next();
                    liveBuffers.remove(removedBuffer);
                    allocSize -= removedBuffer.data.size();
                    anythingFreed = true;
                }
            }
            
            // shuffled enough from the GPU, attempt the alloc again
            return true;
        }
        
        private void promoteImmediate(Hg3DGpuBuffer buffer) {
            // buffer is about to be used, and doesn't have any backing HgBuffer.Slice
            // it needs something to work with _immediately_
            
            buffer.buffer = device.hgDevice().tryCreateBuffer(buffer.requestedMemory, buffer.size(), Hg3DConst.bufferUsageBits(buffer.usage()));
            if (buffer.buffer == null) {
                buffer.buffer = device.hgDevice().createBuffer(HgBuffer.MemoryRequest.CPU, buffer.size(), Hg3DConst.bufferUsageBits(buffer.usage()));
            }
            buffer.slice = buffer.buffer.slice();
            
            if (buffer.evictedData != null || buffer.sourceData != null) {
                final var toUpload = buffer.evictedData == null ? buffer.sourceData : buffer.evictedData;
                if (buffer.slice.buffer().memoryType().mappable) {
                    // mappable, direct copy
                    final var bufferPtr = buffer.slice.map();
                    LibCString.nmemcpy(bufferPtr.pointer(), MemoryUtil.memAddress(toUpload), toUpload.remaining());
                    buffer.slice.unmap();
                } else {
                    // non-mappable, need a  staging buffer
                    final var tempBuffer = device.createCommandEncoder().uploadBufferSlice(toUpload.remaining());
                    final var ptr = tempBuffer.map();
                    LibCString.nmemcpy(ptr.pointer(), MemoryUtil.memAddress(toUpload), ptr.size());
                    tempBuffer.unmap();
                    
                    if (promotionCommandBuffer == null) {
                        device.createCommandEncoder().addFlushCallback(this::endPromotionCommandBuffer);
                        promotionCommandBuffer = device.createCommandEncoder().allocateCommandBuffer();
                        promotionCommandBuffer.setName("Promotion command buffer");
                        promotionCommandBuffer.barrier();
                    }
                    
                    promotionCommandBuffer.copyBufferToBuffer(tempBuffer, buffer.slice);
                }
                MemoryUtil.memFree(buffer.evictedData);
                buffer.evictedData = null;
            }
        }
        
        private void promoteToDevice(Hg3DGpuBuffer buffer) {
            if (lastPromotionFailedFrame == device.currentFrame()) {
                // if another promotion failed this frame, just skip an attempt this frame
                // the demotion step will make room, if it can
                return;
            }
            
            @Nullable
            final var newBuffer = device.hgDevice().tryCreateBuffer(HgBuffer.MemoryRequest.GPU, buffer.size(), Hg3DConst.bufferUsageBits(buffer.usage()));
            if (newBuffer == null) {
                // alloc failed, this is ok, the auto-demote process should make room next frame
                // skip any other device promotions this frame though, we are out of room
                lastPromotionFailedFrame = device.currentFrame();
                return;
            }
            assert newBuffer.memoryType().gpuLocal;
            assert buffer.buffer != null;
            device.destroyEndOfFrame(buffer.buffer);
            if (promotionCommandBuffer == null) {
                device.createCommandEncoder().addFlushCallback(this::endPromotionCommandBuffer);
                promotionCommandBuffer = device.createCommandEncoder().allocateCommandBuffer();
                promotionCommandBuffer.setName("Promotion command buffer");
                promotionCommandBuffer.barrier();
            }
            promotionCommandBuffer.copyBufferToBuffer(buffer.buffer.slice(), newBuffer.slice());
            buffer.buffer = newBuffer;
            buffer.slice = newBuffer.slice();
        }
        
        private void autoDemote() {
            {
                @Nullable
                final var first = liveBuffers.peekFirst();
                if (first != null && first.data.isInFlight()) {
                    // everything live is in-flight, can't purge anything from VK
                    return;
                }
            }
            
            final var commandEncoder = device.createCommandEncoder();
            final var commandBuffer = commandEncoder.allocateCommandBuffer();
            commandBuffer.setName("Demotion command buffer");
            commandBuffer.barrier();
            
            // stage 1, demote any not in-flight mappable memory above 87.5% (7/8ths) of VMA's budget to system memory
            {
                final var memoryStats = device.hgDevice().hostLocalMemoryStats();
                final var targetUsage = ((memoryStats.rightLong() >> 3) * 8);
                long currentUsage = memoryStats.leftLong();
                for (
                        @Nullable var currentNode = liveBuffers.peekFirst();
                        currentUsage > targetUsage && currentNode != null && !currentNode.data.isInFlight();
                ) {
                    final var currentBuffer = currentNode.data;
                    assert currentBuffer.buffer != null;
                    if (!currentBuffer.buffer.memoryType().cpuLocal) {
                        currentNode = currentNode.next();
                        continue;
                    }
                    assert currentBuffer.buffer.memoryType().mappable;
                    if (currentBuffer.isInFlight()) {
                        // out of old buffers
                        break;
                    }
                    if (currentBuffer.canEvictImmediate()) {
                        // easy route, just evict it
                        // this buffer can re-upload itself when it gets used next
                        currentBuffer.buffer.destroy();
                        currentBuffer.buffer = null;
                        currentBuffer.slice = null;
                        final var toRemove = currentNode;
                        currentNode = currentNode.next();
                        liveBuffers.remove(toRemove);
                    } else {
                        // must shuffle this to system memory
                        // mappable memory, can evict to CPU memory and then follow immediate eviction path
                        // UMA (iGPUs) will also end up here
                        currentBuffer.evictedData = MemoryUtil.memAlloc(currentBuffer.size());
                        final var ptr = currentBuffer.buffer.slice().map();
                        LibCString.nmemcpy(MemoryUtil.memAddress(currentBuffer.evictedData), ptr.pointer(), currentBuffer.size());
                        currentBuffer.buffer.slice().unmap();
                        
                        currentBuffer.buffer.destroy();
                        currentBuffer.buffer = null;
                        currentBuffer.slice = null;
                        final var toRemove = currentNode;
                        currentNode = currentNode.next();
                        liveBuffers.remove(toRemove);
                    }
                }
            }
            
            // stage 2, demote anything not in-flight device-local above 93.75% (15/16ths) of VMA's budget to cpu-local memory
            {
                final var memoryStats = device.hgDevice().deviceLocalMemoryStats();
                final var targetUsage = ((memoryStats.rightLong() >> 4) * 15);
                long currentUsage = memoryStats.leftLong();
                for (
                        @Nullable var currentNode = liveBuffers.peekFirst();
                        currentUsage > targetUsage && currentNode != null && !currentNode.data.isInFlight();
                ) {
                    final var currentBuffer = currentNode.data;
                    assert currentBuffer.buffer != null;
                    if (!currentBuffer.buffer.memoryType().gpuLocal) {
                        currentNode = currentNode.next();
                        continue;
                    }
                    
                    @Nullable
                    final var newBuffer = device.hgDevice().tryCreateBuffer(HgBuffer.MemoryRequest.CPU, currentBuffer.size(), Hg3DConst.bufferUsageBits(currentBuffer.usage()));
                    if (newBuffer == null) {
                        // alloc failed, next demotion cycle(s) should free enough to demote this buffer
                        break;
                    }
                    assert !newBuffer.memoryType().gpuLocal;
                    assert newBuffer.memoryType().mappable;
                    assert currentBuffer.buffer != null;
                    device.destroyEndOfFrame(currentBuffer.buffer);
                    commandBuffer.copyBufferToBuffer(currentBuffer.buffer.slice(), newBuffer.slice());
                    currentBuffer.buffer = newBuffer;
                    currentBuffer.slice = newBuffer.slice();
                    currentNode = currentNode.next();
                    // for demotion process, consider it used
                    used(currentBuffer);
                    currentUsage -= currentBuffer.size();
                }
            }
            
            commandBuffer.barrier();
            commandBuffer.end();
            commandEncoder.insertCommandBufferFirst(commandBuffer);
        }
        
        public void endOfFrame() {
            autoDemote();
        }
    }
}
