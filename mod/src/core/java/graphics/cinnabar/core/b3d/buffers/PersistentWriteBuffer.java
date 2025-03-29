package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import org.jetbrains.annotations.Nullable;

public class PersistentWriteBuffer extends CinnabarGpuBuffer {
    private final VkBuffer gpuBuffer;
    
    public PersistentWriteBuffer(CinnabarDevice device, BufferType type, BufferUsage usage, int size, @Nullable String name) {
        super(device, type, usage, size);
        assert !usage.isReadable();
        gpuBuffer = new VkBuffer(device, size, typeUsageBits(type), device.devicePersistentMemoryPool);
        gpuBuffer.setVulkanName(name);
    }
    
    @Override
    public VkBuffer getBufferForWrite() {
        return gpuBuffer;
    }
    
    @Override
    public VkBuffer getBufferForRead() {
        return gpuBuffer;
    }
    
    @Override
    public void destroy() {
        gpuBuffer.destroy();
    }
}
