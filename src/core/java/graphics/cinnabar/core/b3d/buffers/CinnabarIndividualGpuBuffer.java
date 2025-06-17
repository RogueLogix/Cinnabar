package graphics.cinnabar.core.b3d.buffers;

import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import org.jetbrains.annotations.Nullable;

public final class CinnabarIndividualGpuBuffer extends CinnabarGpuBuffer {
    
    private final VkBuffer backingBuffer;
    
    public CinnabarIndividualGpuBuffer(CinnabarDevice device, int usage, int size, @Nullable String name) {
        super(device, usage, size, name);
        final var clientStorage = clientStorage(usage);
        backingBuffer = new VkBuffer(device, size, vkUsageBits(usage, clientStorage), clientStorage ? device.hostMemoryType : device.deviceMemoryType);
        backingBuffer.setVulkanName(name);
        backingSlice = backingBuffer.whole();
    }
    
    @Override
    public void destroy() {
        backingBuffer.destroy();
    }
}
