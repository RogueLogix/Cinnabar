package graphics.cinnabar.core.vk.memory;

import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Destroyable;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

public class VkMemoryAllocation implements Destroyable {
    
    public final long memoryHandle;
    public final MemoryRange range;
    @Nullable
    private final Consumer<VkMemoryAllocation> freeFunc;
    
    public VkMemoryAllocation(long memoryHandle, MemoryRange range, @Nullable Consumer<VkMemoryAllocation> freeFunc) {
        this.memoryHandle = memoryHandle;
        this.range = range;
        this.freeFunc = freeFunc;
    }
    
    @Override
    public void destroy() {
        if (freeFunc != null) {
            freeFunc.accept(this);
        }
    }
    
    public CPU cpu() {
        return (CPU) this;
    }
    
    public static class CPU extends VkMemoryAllocation {
        public final PointerWrapper hostPointer;
        
        public CPU(long memoryHandle, MemoryRange range, @Nullable Consumer<VkMemoryAllocation> freeFunc, PointerWrapper hostPointer) {
            super(memoryHandle, range, freeFunc);
            this.hostPointer = hostPointer;
        }
        
        public CPU(VkMemoryAllocation baseAlloc, PointerWrapper pointerWrapper) {
            this(baseAlloc.memoryHandle, baseAlloc.range, baseAlloc.freeFunc, pointerWrapper);
        }
    }
}
