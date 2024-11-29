package graphics.cinnabar.internal.vulkan.memory;

import graphics.cinnabar.internal.util.MemoryRange;
import graphics.cinnabar.internal.vulkan.Destroyable;
import graphics.cinnabar.api.annotations.NotNullDefault;

import java.util.function.Consumer;

@NotNullDefault
public record VulkanMemoryAllocation(long memoryHandle, MemoryRange range, Consumer<VulkanMemoryAllocation> freeFunc) implements Destroyable {
    @Override
    public void destroy() {
        freeFunc.accept(this);
    }
}
