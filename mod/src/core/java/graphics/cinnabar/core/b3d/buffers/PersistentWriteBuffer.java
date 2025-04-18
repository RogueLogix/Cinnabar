package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.util.Pair;
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
    public Pair<VkBuffer, MemoryRange> getBufferForWrite() {
        lastAccessFrame = device.currentFrameIndex();
        return new Pair<>(gpuBuffer, new MemoryRange(0, gpuBuffer.size));
    }
    
    @Override
    public Pair<VkBuffer, MemoryRange> getBufferForRead() {
        lastAccessFrame = device.currentFrameIndex();
        return new Pair<>(gpuBuffer, new MemoryRange(0, gpuBuffer.size));
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
