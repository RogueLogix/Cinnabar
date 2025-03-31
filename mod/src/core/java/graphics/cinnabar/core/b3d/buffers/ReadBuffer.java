package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import org.jetbrains.annotations.Nullable;

public class ReadBuffer extends CinnabarGpuBuffer {
    private final VkBuffer buffer;
    
    public ReadBuffer(CinnabarDevice device, BufferType type, BufferUsage usage, int size, @Nullable String name) {
        super(device, type, usage, size);
        assert usage.isReadable();
        buffer = new VkBuffer(device, size, typeUsageBits(type), device.hostPersistentMemoryPool);
    }
    
    @Override
    public VkBuffer getBufferForWrite() {
        return buffer;
    }
    
    @Override
    public VkBuffer getBufferForRead() {
        return buffer;
    }
    
    @Override
    public void destroy() {
        buffer.destroy();
    }
}
