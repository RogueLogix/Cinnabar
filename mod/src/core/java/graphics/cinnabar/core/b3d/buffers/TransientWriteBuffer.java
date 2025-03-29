package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import org.jetbrains.annotations.Nullable;

public class TransientWriteBuffer extends CinnabarGpuBuffer {
    
    @Nullable
    private VkBuffer currentBuffer;
    
    public TransientWriteBuffer(CinnabarDevice device, BufferType type, BufferUsage usage, int size) {
        super(device, type, usage, size);
    }
    
    @Override
    public VkBuffer getBufferForWrite() {
        currentBuffer = new VkBuffer(device, size, typeUsageBits(type()), device.deviceTransientMemoryPool);
        device.destroyEndOfFrame(currentBuffer);
        return currentBuffer;
    }
    
    @Override
    public VkBuffer getBufferForRead() {
        assert currentBuffer != null;
        return currentBuffer;
    }
    
    @Override
    public void destroy() {
        // N/A, all buffers immediately enqueued for destroy
    }
}
