package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import graphics.cinnabar.api.memory.MagicMemorySizes;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.util.Destroyable;
import graphics.cinnabar.api.util.Pair;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.util.MagicNumbers;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import graphics.cinnabar.core.vk.memory.VkMemoryAllocation;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;

import static org.lwjgl.vulkan.VK10.*;

public class RenderChunkVertexBufferPool implements Destroyable {
    private final CinnabarDevice device;
    // TODO: be able to reallocate this?
    final VkBuffer backingBuffer;
    
    private final ObjectArrayList<MemoryRange> freeAllocations = new ObjectArrayList<>() {
        @Override
        public boolean add(@Nullable MemoryRange allocation) {
            if (allocation == null) {
                return false;
            }
            int index = Collections.binarySearch(this, allocation);
            if (index < 0) {
                index = ~index;
                super.add(index, allocation);
            } else {
                super.set(index, allocation);
            }
            return true;
        }
    };
    
    public RenderChunkVertexBufferPool(CinnabarDevice device) {
        this.device = device;
        backingBuffer = new VkBuffer(device, 2 * MagicMemorySizes.GiB, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT, device.devicePersistentMemoryPool);
        freeAllocations.add(new MemoryRange(0, backingBuffer.size));
    }
    
    @Override
    public void destroy() {
        backingBuffer.destroy();
    }
    
    public Buffer alloc(int size) {
        return new Buffer(device, BufferType.VERTICES, BufferUsage.STATIC_WRITE, size);
    }
    
    @Nullable
    private MemoryRange attemptAllocInSpace(MemoryRange freeAlloc, long size, long alignment) {
        // next value guaranteed to be at *most* one less than the next alignment, then bit magic because powers of two to round down without a divide
        final long nextValidAlignment = (freeAlloc.offset() + (alignment - 1)) & (-alignment);
        final long alignmentWaste = nextValidAlignment - freeAlloc.offset();
        if (freeAlloc.size() - alignmentWaste < size) {
            // wont fit, *neeeeeeeeeeeext*
            return null;
        }
        if (alignmentWaste > 0) {
            final var newAllocs = freeAlloc.split(alignmentWaste);
            // not concurrent modification because this will always return
            freeAllocations.add(newAllocs.first());
            freeAlloc = newAllocs.second();
            
            int index = freeAllocations.indexOf(newAllocs.first());
            collapseFreeAllocationWithNext(index - 1);
            collapseFreeAllocationWithNext(index);
        }
        if (freeAlloc.size() > size) {
            final var newAllocs = freeAlloc.split(size);
            // not concurrent modification because this will always return
            freeAlloc = newAllocs.first();
            freeAllocations.add(newAllocs.second());
            int index = freeAllocations.indexOf(newAllocs.second());
            collapseFreeAllocationWithNext(index - 1);
            collapseFreeAllocationWithNext(index);
        }
        
        return freeAlloc;
    }
    
    public synchronized void free(MemoryRange info) {
        freeAllocations.add(info);
        var index = freeAllocations.indexOf(info);
        collapseFreeAllocationWithNext(index - 1);
        collapseFreeAllocationWithNext(index);
    }
    
    private void collapseFreeAllocationWithNext(int freeAllocationIndex) {
        if (freeAllocationIndex < 0 || freeAllocationIndex >= freeAllocations.size() - 1) {
            return;
        }
        var allocA = freeAllocations.get(freeAllocationIndex);
        var allocB = freeAllocations.get(freeAllocationIndex + 1);
        if (allocA.offset() + allocA.size() == allocB.offset()) {
            // neighboring allocations, collapse them
            freeAllocations.remove(freeAllocationIndex + 1);
            freeAllocations.remove(freeAllocationIndex);
            freeAllocations.add(new MemoryRange(allocA.offset(), allocA.size() + allocB.size()));
        }
    }
    
    public class Buffer extends CinnabarGpuBuffer {
        
        private final MemoryRange alloc;
        
        public Buffer(CinnabarDevice device, BufferType type, BufferUsage usage, int size) {
            super(device, type, usage, size);
            @Nullable
            MemoryRange allocatedSpace = null;
            for (MemoryRange freeAllocation : freeAllocations) {
                assert DefaultVertexFormat.BLOCK.getVertexSize() == 32;
                @Nullable var alloc = attemptAllocInSpace(freeAllocation, size, 32);
                if (alloc != null) {
                    allocatedSpace = alloc;
                    freeAllocations.remove(freeAllocation);
                    break;
                }
            }
            if (allocatedSpace == null) {
                throw new OutOfMemoryError();
            }
            this.alloc = allocatedSpace;
        }
        
        @Override
        public Pair<VkBuffer, MemoryRange> getBufferForWrite() {
            return new Pair<>(backingBuffer, alloc);
        }
        
        @Override
        public Pair<VkBuffer, MemoryRange> getBufferForRead() {
            return new Pair<>(backingBuffer, alloc);
        }
        
        @Override
        public void destroy() {
            free(alloc);
        }
        
        public int vertexOffset() {
            return (int) (alloc.offset() / 32);
        }
    }
    
}
