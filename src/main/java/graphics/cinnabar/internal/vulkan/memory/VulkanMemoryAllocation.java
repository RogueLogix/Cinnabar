package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.util.MemoryRange;
import net.roguelogix.phosphophyllite.util.NonnullDefault;

@NonnullDefault
public record VulkanMemoryAllocation(long memoryHandle, MemoryRange range) {
}
