package graphics.cinnabar.api.hg;

import graphics.cinnabar.api.annotations.Constant;
import graphics.cinnabar.api.annotations.ThreadSafety;
import graphics.cinnabar.api.hg.enums.HgFormat;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.util.vma.VmaVirtualAllocationCreateInfo;
import org.lwjgl.util.vma.VmaVirtualBlockCreateInfo;

import static org.lwjgl.util.vma.Vma.*;

@ApiStatus.NonExtendable
@ThreadSafety.VulkanObjectHandle
public interface HgBuffer extends HgObject {
    @Constant
    @ThreadSafety.Many
    long size();
    
    @Constant
    @ThreadSafety.Many
    MemoryType memoryType();
    
    @ThreadSafety.Many
    @ThreadSafety.VulkanObjectHandle
    PointerWrapper map();
    
    @ThreadSafety.Many
    @ThreadSafety.VulkanObjectHandle
    void unmap();
    
    default Slice slice() {
        return new Slice(this, 0, size());
    }
    
    default Slice slice(long offset, long size) {
        return new Slice(this, offset, size);
    }
    
    default ImageSlice imageSlice(Slice slice, int width, int height) {
        return new ImageSlice(this, slice.offset, slice.size, width, height);
    }
    
    default ImageSlice imageSlice(long offset, long size, int width, int height) {
        return new ImageSlice(this, offset, size, width, height);
    }
    
    default View view(HgFormat format) {
        return view(format, 0, size());
    }
    
    @ThreadSafety.Many
    View view(HgFormat format, long offset, long size);
    
    enum MemoryRequest {
        // CPU memory, quickly accessible by CPU
        CPU,
        // CPU write only
        // may be in CPU or GPU memory
        MAPPABLE_PREF_GPU,
        // GPU memory, mappable if possible
        GPU,
    }
    
    enum MemoryType {
        UMA(true, true, true),
        CPU(true, false, true),
        // only one device type will be used at runtime
        GPU(false, true, false),
        GPU_MAPPABLE(false, true, true),
        ;
        
        public final boolean cpuLocal;
        public final boolean gpuLocal;
        public final boolean mappable;
        
        MemoryType(boolean cpuLocal, boolean gpuLocal, boolean mappable) {
            this.cpuLocal = cpuLocal;
            this.gpuLocal = gpuLocal;
            this.mappable = mappable;
        }
    }
    
    interface View extends HgObject {
    }
    
    record Slice(HgBuffer buffer, long offset, long size) {
        @ThreadSafety.Many
        @ThreadSafety.VulkanObjectHandle
        public PointerWrapper map() {
            return buffer.map().slice(offset, size);
        }
        
        @ThreadSafety.Many
        @ThreadSafety.VulkanObjectHandle
        public void unmap() {
            buffer.unmap();
        }
        
        public Slice slice(long offset, long size) {
            return new Slice(buffer, this.offset + offset, size);
        }
        
        public ImageSlice image(int width, int height) {
            return new ImageSlice(buffer, offset, size, width, height);
        }
        
        public ImageSlice imageSlice(int offset, int size, int width, int height) {
            return new ImageSlice(buffer, this.offset + offset, size, width, height);
        }
        
        public View view(HgFormat format) {
            return buffer.view(format, this.offset, size);
        }
        
        public View view(HgFormat format, long offset, long size) {
            return buffer.view(format, this.offset + offset, size);
        }
    }
    
    record ImageSlice(HgBuffer buffer, long offset, long size, int width, int height) {
    }
    
    class Suballocator implements Destroyable {
        private final HgBuffer buffer;
        private final long vmaBlock;
        
        public Suballocator(HgBuffer buffer) {
            this.buffer = buffer;
            try (final var stack = MemoryStack.stackPush()) {
                final var createInfo = VmaVirtualBlockCreateInfo.calloc(stack);
                final var blockReturn = stack.callocPointer(1);
                vmaCreateVirtualBlock(createInfo, blockReturn);
                vmaBlock = blockReturn.get(0);
            }
        }
        
        @Override
        public void destroy() {
            vmaDestroyVirtualBlock(vmaBlock);
        }
        
        public HgBuffer sourceBuffer() {
            return buffer;
        }
        
        @Nullable
        public Alloc alloc(long size, long align) {
            try (final var stack = MemoryStack.stackPush()) {
                final var createInfo = VmaVirtualAllocationCreateInfo.calloc(stack);
                createInfo.size(size);
                createInfo.alignment(align);
                final var allocReturn = stack.callocPointer(1);
                vmaVirtualAllocate(vmaBlock, createInfo, allocReturn, null);
                if (allocReturn.get(0) == 0) {
                    // alloc failed
                    return null;
                }
                final var vmaAlloc = VmaAllocationInfo.create(allocReturn.get(0));
                final var slice = buffer.slice(vmaAlloc.offset(), size);
                return new Alloc(vmaAlloc, slice);
            }
        }
        
        public class Alloc implements Destroyable {
            
            private final VmaAllocationInfo vmaAlloc;
            private final Slice slice;
            
            Alloc(VmaAllocationInfo vmaAlloc, final Slice slice) {
                this.vmaAlloc = vmaAlloc;
                this.slice = slice;
            }
            
            @Override
            public void destroy() {
                vmaVirtualFree(vmaBlock, vmaAlloc.address());
            }
            
            public Slice slice() {
                return slice;
            }
        }
    }
}
