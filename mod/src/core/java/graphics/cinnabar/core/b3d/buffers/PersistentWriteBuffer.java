package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import org.jetbrains.annotations.Nullable;

public class PersistentWriteBuffer extends CinnabarGpuBuffer {
    private final VkBuffer gpuBuffer;
    private long lastAccessFrame = -1;
    
    public PersistentWriteBuffer(CinnabarDevice device, BufferType type, BufferUsage usage, int size, @Nullable String name) {
        super(device, type, usage, size);
        assert !usage.isReadable();
        gpuBuffer = new VkBuffer(device, size, typeUsageBits(type), device.devicePersistentMemoryPool);
        gpuBuffer.setVulkanName(name);
    }
    
    @Override
    public VkBuffer getBufferForWrite() {
        lastAccessFrame = device.currentFrameIndex();
        return gpuBuffer;
    }
    
    @Override
    public VkBuffer getBufferForRead() {
        lastAccessFrame = device.currentFrameIndex();
        return gpuBuffer;
    }
    
    @Override
    public void destroy() {
        gpuBuffer.destroy();
    }
    
    @Override
    public boolean uploadBeginningOfFrame() {
        return lastAccessFrame != device.currentFrameIndex();
    }
}
