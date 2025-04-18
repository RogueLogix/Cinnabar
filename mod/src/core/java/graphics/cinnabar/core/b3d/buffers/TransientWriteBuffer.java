package graphics.cinnabar.core.b3d.buffers;

import com.mojang.blaze3d.buffers.BufferType;
import com.mojang.blaze3d.buffers.BufferUsage;
import graphics.cinnabar.api.memory.MemoryRange;
import graphics.cinnabar.api.memory.PointerWrapper;
import graphics.cinnabar.api.util.Pair;
import graphics.cinnabar.core.b3d.CinnabarDevice;
import graphics.cinnabar.core.vk.memory.VkBuffer;
import graphics.cinnabar.core.vk.memory.VkMemoryAllocation;
import org.jetbrains.annotations.Nullable;

public class TransientWriteBuffer extends CinnabarGpuBuffer {
    
    @Nullable
    private VkBuffer currentBuffer;
    @Nullable
    private VkMemoryAllocation.CPU lastCPUData;
    private int lastWrittenFrame = 0;
    private final PointerWrapper lastWritten;
    @Nullable
    private final String name;
    
    public TransientWriteBuffer(CinnabarDevice device, BufferType type, BufferUsage usage, int size, @Nullable String name) {
        super(device, type, usage, size);
        lastWritten = PointerWrapper.alloc(size);
        this.name = name;
    }
    
    @Override
    public Pair<VkBuffer, MemoryRange> getBufferForWrite() {
        currentBuffer = new VkBuffer(device, size, typeUsageBits(type()), device.deviceTransientMemoryPool);
        currentBuffer.setVulkanName(name);
        device.destroyEndOfFrame(currentBuffer);
        return new Pair<>(currentBuffer, new MemoryRange(0, currentBuffer.size));
    }
    
    @Override
    public Pair<VkBuffer, MemoryRange> getBufferForRead() {
        if (currentBuffer == null || lastWrittenFrame != device.currentFrameIndex()) {
            throw new IllegalStateException();
        }
        assert currentBuffer != null;
        return new Pair<>(currentBuffer, new MemoryRange(0, currentBuffer.size));
    }
    
    public void write(VkMemoryAllocation.CPU buffer) {
        this.lastCPUData = buffer;
        lastWrittenFrame = device.currentFrameIndex();
    }
    
    public void copyBackLastWrite() {
        if (this.lastCPUData == null) {
            return;
        }
        lastCPUData.hostPointer.copyTo(lastWritten);
        lastCPUData = null;
    }
    
    @Override
    public void destroy() {
        lastWritten.free();
    }
    
    @Override
    public boolean uploadBeginningOfFrame() {
        return true;
    }
}
