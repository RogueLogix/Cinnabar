package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.util.MemoryRange;
import graphics.cinnabar.internal.vulkan.Destroyable;
import net.roguelogix.phosphophyllite.util.NonnullDefault;

@NonnullDefault
public record VulkanMemoryAllocation(long memoryHandle, MemoryRange range, GPUMemoryAllocator allocator) implements Destroyable {
    @Override
    public void destroy() {
        allocator.free(this);
    }
}
